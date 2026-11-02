package com.terminalscout.app.prod.service

import android.content.Context
import android.util.Log
import com.terminalscout.app.prod.CameraCaptureManager
import com.terminalscout.app.prod.ContainedFaults
import com.terminalscout.app.prod.RunOutputStore
import com.terminalscout.app.prod.VideoMetadataStore
import com.terminalscout.app.prod.crashproof
import com.terminalscout.app.prod.crashproofSuspend
import com.terminalscout.app.prod.download.DebugUploader
import com.terminalscout.app.prod.pipeline.PipelineEvent
import com.terminalscout.app.prod.pipeline.PriceBoardPipeline
import com.terminalscout.app.prod.pipeline.RunScratch
import com.terminalscout.app.prod.upload.Connectivity
import com.terminalscout.app.prod.upload.UploadDbHelper
import com.terminalscout.app.prod.upload.UploadQueue
import com.terminalscout.app.prod.upload.UploadScheduler
import com.terminalscout.app.prod.upload.UploadWorker
import com.terminalscout.app.prod.config.DeviceConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * STAGE 1+ — the processing lane. Watches the disk for clips and runs the whole pipeline over each one.
 *
 * ### The loop
 * ```
 * poll recorded_videos/ every 5 s
 *      → nothing there?  poll again
 *      → a clip?         stop polling, process it end to end, hand it to the upload outbox,
 *                        wait 10 s, resume polling
 * ```
 *
 * One clip at a time, for as long as the service lives. Polling stops while a clip is in flight, so the
 * loop can never start a second run on top of a first, and there is no lock to get wrong.
 *
 * ### Why the disk is the queue
 * There used to be a durable queue — `video_queue/<id>.json`, an id counter, a status per item, a
 * restore-on-start. It was replaced by looking at `recorded_videos/`, because **the clip on disk already
 * is the work item.** A separate list of work items can disagree with the directory: an entry pointing
 * at a file somebody deleted, a file no entry claims. Neither is possible now — there is exactly one
 * source of truth, and processing a clip means deleting it.
 *
 * Nothing was lost in durability. The queue's whole purpose was surviving a process death, and a file in
 * `filesDir` survives a process death by itself.
 *
 * What a bare filename cannot carry is provenance, so that moved to a sidecar written at capture time —
 * see [VideoMetadataStore]. Metadata is optional: a clip without it is processed anyway.
 *
 * ### It is not triggered, and it does not trigger
 * No intent starts this and no capture starts this. Stage 0 records a clip and stops; the poller picks
 * it up within 5 s on its own. The two halves share only the directory, which is the point — a drive
 * past forty forecourts and a three-hour pipeline backlog are separate concerns that used to fight over
 * the same CPU.
 *
 * ### Offline
 * The poller keeps polling. The **pipeline** is held, because a run with no network produces a
 * well-formed payload with no prices, and nothing downstream can contradict a reading the server has
 * already accepted. Clips accumulate on disk until a connection returns, which is the correct failure:
 * data delayed rather than data wrong.
 *
 * ### Every clip leaves the device, exactly once
 * A run's last act is to hand its clip to the debug upload outbox — a MOVE, not a copy — whatever the
 * outcome. A run that read the board sends the clip; a run that could not, and a run that threw, send
 * the clip plus a `failure.txt` naming the stage, the throwable and the run's logcat tail.
 *
 * This closed the hazard that used to be documented here, which was the highest-risk open item in the
 * app: a failed clip stayed on disk and the next poll picked it up five seconds later, forever, at full
 * CPU on a device that has been thermally killed before. The loop is removed by removing the file, which
 * is what the plan in `DECISIONS.md` always said it would be.
 *
 * It also closed the opposite fault on the success path, which was quieter and worse: a clip was deleted
 * as soon as the pipeline answered, while its reading was still a pending row in `uploads.db`. If that
 * upload then failed for good, the only footage of the capture was already gone. Now the clip outlives
 * the run and is deleted by [DebugUploadWorker] only once the bytes have actually been delivered.
 *
 * Two paths still leave a clip on disk, both deliberate: a run **superseded** by a restarted loop (the
 * live loop owns that clip), and an outbox that **refused** the clip (disabled, or the move failed) —
 * where retrying forever genuinely does beat destroying a recording nothing has a copy of.
 */
class ProcessingLane(
    private val context: Context,
    private val scope: CoroutineScope
) {

    private val runStore = RunOutputStore(context)
    private val uploadQueue = UploadQueue(context)

    /** The pipeline, built once for the life of the service. */
    private val pipeline: PriceBoardPipeline by lazy {
        PriceBoardPipeline(context = context)
    }

    /** The poll loop. Null when the lane is stopped. */
    private var pollJob: Job? = null

    /** The clip in flight, for the readout. Null while polling. */
    var processing: String?
        get() = current
        private set(value) {
            current = value
        }

    /**
     * Set while the pipeline is held for lack of a connection.
     *
     * Distinct from "idle": the poller is running and finding clips, it simply will not process them.
     * The readout has to tell those apart, because one is a healthy unit with nothing to do and the
     * other is a unit accumulating unread footage.
     */
    private var heldOffline: Boolean
        get() = offline
        set(value) {
            offline = value
        }

    /** How many failures this clip has had in a row, so the log can say so. Reset on a new clip. */
    private var consecutiveFailures = 0

    // ---- Lifecycle -------------------------------------------------------------------------------- //

    /**
     * Starts the poll loop, and sweeps the leftovers of runs that never got to clean up after
     * themselves.
     *
     * Called once when the service starts. The loop is the lane — there is nothing else to switch on,
     * and nothing to ask.
     */
    fun start() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            // Claimed BEFORE the orphan sweep, not after.
            //
            // The sweep walks directories and can take seconds on a device with a backlog. With the flag
            // set afterwards there was a window in which `pollJob` was active but `isPolling` was still
            // false — and `Stage0Health`'s 60-second check landing in that window would cancel a poller
            // that was mid-startup and restart it, re-running the sweep. A repair fighting a healthy
            // component.
            //
            // So the flag means "this loop is claimed", which is the question the health check is
            // actually asking. Every exit from [loop] clears it, and the wrapper in [loop] means the only
            // exit is cancellation.
            isPolling = true
            runCatching { com.terminalscout.app.prod.ui.ErrorLog.removeErrorByKey(ERR_POLL_FAILED) }
            // Guarded: the sweep walks directories left by runs that died, and a listing that fails must
            // not cost the loop below it. Leftover scratch costs disk; a lane that never started polling
            // costs every clip already on the device.
            runCatching { withContext(Dispatchers.IO) { RunScratch.sweepUnclaimed(context) } }
                .onFailure { Log.e(TAG, "the orphan sweep failed — polling anyway", it) }
            Log.i(TAG, "processing lane up — polling every ${POLL_INTERVAL_MS / 1000}s")
            loop()
        }
    }

    /**
     * Restarts the poll loop if it has stopped, and does nothing if it is running.
     *
     * Called by [com.terminalscout.app.prod.Stage0Health] when it finds [isPolling] false inside a live
     * service. That state is reachable: the loop body cannot throw, but the job can still be cancelled
     * or killed out from under the lane, and a service with no poller looks identical to a healthy one
     * from outside.
     */
    fun ensurePolling() = crashproof(SOURCE, ContainedFaults.PROCESSING_LANE, "ensurePolling") {
        if (pollJob?.isActive == true && isPolling) return@crashproof
        Log.w(TAG, "poll loop was not running — restarting it")
        pollJob?.cancel()
        pollJob = null
        start()
    }

    /**
     * Records a sign of life from the loop. See [lastBeatAtMs].
     *
     * Called on every poll tick and on every pipeline event, which is the only pair of places that can
     * distinguish a lane doing slow work from a lane that has stopped doing any.
     */
    private fun beat() {
        lastBeatAtMs = System.currentTimeMillis()
    }

    /**
     * Kills the poll loop and starts a fresh one, whatever state the old one was in.
     *
     * The difference from [ensurePolling] is the case each is for. That one repairs a loop that has
     * ENDED — the job is gone, the flag is false, and starting it again is all there is to do. This one
     * is for a loop that is still alive and no longer making progress: a run wedged inside a native
     * decode, a socket that never times out, a coroutine parked on a lock nothing will release. The job
     * is alive, so `ensurePolling` returns and repairs nothing, and the lane sits there holding a clip
     * for the life of the process.
     *
     * Cancelling a run in flight is safe and always was — the clip is deleted only by a run that
     * completed and answered ([processClip]) — so the worst case is repeating the work on a clip that
     * is still on disk. Losing the work is the point: it was not going to finish.
     *
     * ### Two honest limits
     * **A cancel is cooperative.** If the old run is blocked inside a native call rather than parked at
     * a suspension point, `cancel()` only marks it and the thread stays where it is until that call
     * returns — possibly never. The LANE recovers either way, which is what matters, but a wedge of
     * that kind leaks one thread of `Dispatchers.Default` for the life of the process. Repeated stalls
     * would eventually starve the pool, which is why this raises an errors row rather than repairing
     * quietly: something is wrong that a restart is only papering over.
     *
     * **A zombie can wake up.** The same cooperative cancel means the old run may resume minutes later
     * and walk on through its own `finally`. That is why [generation] exists: a superseded run may not
     * file a reading or delete a clip the new loop has since claimed.
     */
    fun restartPolling(reason: String) = crashproof(
        SOURCE, ContainedFaults.PROCESSING_LANE, "restartPolling"
    ) {
        Log.e(TAG, "forcing a poll-loop restart — $reason")
        // Bumped BEFORE the cancel, so anything the old job does from here on is already superseded.
        generation++
        runCatching { com.terminalscout.app.prod.ui.ErrorLog.showError(ERR_STALLED, MSG_STALLED, SOURCE) }
        runCatching { pollJob?.cancel() }
            .onFailure { Log.e(TAG, "could not cancel the wedged poll loop", it) }
        pollJob = null
        isPolling = false
        processing = null
        // Beaten before the new loop starts, so the freshly started loop is not immediately judged
        // stalled by a check that lands between `start()` and its first tick.
        beat()
        start()
    }

    /** Stops polling. A run in flight is cancelled with the scope. */
    fun stop() {
        // Reached from `onDestroy`. The flags come first and are not guarded — they cannot throw — so a
        // readout can never describe a lane that has been stopped as still polling, whatever the two
        // calls around them do.
        runCatching { pollJob?.cancel() }
            .onFailure { Log.e(TAG, "could not cancel the poll loop", it) }
        pollJob = null
        isPolling = false
        processing = null
        crashproof(SOURCE, ContainedFaults.PROCESSING_LANE, "stop(publishQueue)") { publishQueue() }
    }

    // ---- The loop ---------------------------------------------------------------------------------- //

    /**
     * Poll, process, wait, repeat.
     *
     * The structure is why this needs no synchronisation: the poll and the run are the same coroutine,
     * sequentially, so "stop polling while processing" is not a flag anybody has to maintain — it is
     * simply where the code is.
     */
    private suspend fun loop() {
        // coroutineContext.isActive, not the CoroutineScope extension: this is a plain suspend function,
        // and the check has to see the job the caller launched so `stop()` actually ends the loop.
        while (coroutineContext.isActive) {
            // NOTHING in this body may escape.
            //
            // The scope is a SupervisorJob, so a throw here does not take the service down — it kills
            // this coroutine and leaves the service running happily with no poller. Clips would then pile
            // up on disk while the readout said "idle, polling every 5s", which is the same class of lie
            // as a home panel reporting 5/5 over a dead service.
            //
            // `processClip` has its own try/catch for a failed pipeline run. This one is for everything
            // around it: the directory listing, the readout publish, an OOM while measuring file sizes.
            // A tick that fails is skipped; the loop is not.
            try {
                tick()
            } catch (c: CancellationException) {
                // Not a fault. `stop()` cancels this job, and the cancellation travels as a throwable
                // through every suspension point in `tick` — including the ones in the `finally` that
                // cleans up a clip. Reporting it would put a "video-processing check failed" row on the
                // errors screen every single time the service shuts down, which is the fastest way to
                // teach an operator to ignore that screen. Rethrown so the loop actually ends.
                throw c
            } catch (t: Throwable) {
                Log.e(TAG, "poll tick failed — the loop continues", t)
                runCatching {
                    com.terminalscout.app.prod.ui.ErrorLog.showError(ERR_POLL_FAILED, MSG_POLL_FAILED, SOURCE)
                }
                processing = null
                delay(POLL_INTERVAL_MS)
            }
        }
        // Reached only by cancellation, since the body cannot throw. Cleared so the health check does
        // not repair a lane that was stopped deliberately... and set false either way, because a
        // cancelled loop is not polling whatever the reason.
        isPolling = false
        Log.i(TAG, "poll loop ended")
    }

    /** One pass: look, maybe process, wait. Split out so [loop] can wrap it whole. */
    private suspend fun tick() {
        beat()
        val clip = withContext(Dispatchers.IO) { nextClip() }

        if (clip == null) {
            publishQueue()
            delay(POLL_INTERVAL_MS)
            return
        }

        val ran = processClip(clip)

        processing = null
        publishQueue()
        // The cooldown belongs to a clip that was actually PROCESSED. It used to run on every tick that
        // found a clip, including the ones that only discovered the device was offline — so a held lane
        // spent ten seconds sleeping here on top of the five it had already spent inside `processClip`,
        // fifteen seconds at a time, re-listing the directory each round, to learn the same thing.
        if (ran) delay(POST_CLIP_DELAY_MS)
    }

    /**
     * The oldest clip on disk, or null when there is none.
     *
     * Oldest first so a backlog drains in capture order, which matters because prices move: a reading
     * from two hours ago is worth less than a reading from ten minutes ago, and processing newest-first
     * would leave the oldest clips to rot at the bottom.
     */
    private fun nextClip(): File? {
        val dir = File(context.filesDir, CameraCaptureManager.RECORDED_VIDEOS_DIR)
        val clips = dir.listFiles { f -> f.isFile && f.name.endsWith(".mp4") } ?: return null
        return clips.minByOrNull { it.lastModified() }
    }

    /** Every clip waiting, for the readout. Cheap — a directory listing, not a stat per file. */
    private fun clipCount(): Int {
        val dir = File(context.filesDir, CameraCaptureManager.RECORDED_VIDEOS_DIR)
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".mp4") }?.size ?: 0
    }

    // ---- One clip ---------------------------------------------------------------------------------- //

    /**
     * Runs one clip end to end, or holds it.
     *
     * @return true when the pipeline actually ran over the clip. False means the lane was held offline
     *   and nothing was attempted, which is what spares the caller its post-clip cooldown.
     */
    private suspend fun processClip(clip: File): Boolean {
        // Which loop this run belongs to. Read once, checked before anything irreversible: a run whose
        // loop has been restarted underneath it is a zombie — the new loop owns this directory now, and
        // a zombie that filed a reading or deleted a clip would be acting on the other run's work.
        val runGeneration = generation
        // Held before anything expensive happens, and NOT a reason to stop polling: the clip stays on
        // disk, the readout says why, and the lane picks it up the moment there is a connection.
        if (!Connectivity.isOnline(context)) {
            heldOffline = true
            val waiting = withContext(Dispatchers.IO) { clipCount() }
            FleetBus.publishStatus(
                FleetBus.Status("Offline — $waiting clip(s) waiting. Processing resumes once online.")
            )
            // Waits for the NETWORK, not for a timer. This used to `delay(POLL_INTERVAL_MS)` and return,
            // so a device out of coverage woke every five seconds, listed the directory, counted the
            // clips, published the same line and went back to sleep — and then took the ten-second
            // post-clip cooldown on top, for a clip it had not touched. The platform already has an
            // answer to "tell me when there is a connection"; asking it is both cheaper and faster,
            // because the hold ends when the network returns rather than up to fifteen seconds later.
            awaitConnection()
            return false
        }

        heldOffline = false
        val name = displayName(clip)  // unique per clip — see displayName
        processing = name
        publishQueue()

        val metadata = withContext(Dispatchers.IO) { VideoMetadataStore.read(context, clip.name) }
        if (metadata == null) {
            // Ordinary for a sideloaded test clip, and survivable for a recorded one: the reading loses
            // its station attribution and is produced anyway.
            Log.w(TAG, "$name has no metadata — processing without provenance")
        }

        FleetBus.publishStatus(
            FleetBus.status.value.copy(text = "Processing “$name”…", processing = true)
        )

        // NULL until a CaptureResult arrives, and that is the point.
        //
        // This was `var complete = true`, cleared only by an incomplete CaptureResult — so a run that
        // THREW never cleared it, and the `finally` below deleted the clip. Every documented promise
        // that a failed clip stays on disk was inverted: a stage fault, an OOM or a full volume during
        // frame extraction destroyed the footage, which is the one copy of it that exists.
        //
        // Three states, because there are three outcomes: a run that finished and answered (`true`), a
        // run that finished and could not answer (`false` — the reader was unavailable), and a run that
        // never got to an answer at all (`null`).
        //
        // All three now hand the clip to the outbox; what the state decides is whether a `failure.txt`
        // goes with it, and whether the reading is filed at all. It is still three values rather than
        // two because "could not read" and "never got there" want different reports — one names a
        // reader that refused, the other names a stage that threw — and a reader would not be able to
        // tell them apart from the clip alone.
        var captureComplete: Boolean? = null
        var scratchId: String? = null
        var saveJob: Job? = null
        // The throwable, kept so the failure report can carry it. `noteFailure` already logs it; the
        // report is what leaves the device, and a stack trace on a screen nobody is attached to is not
        // a diagnosis.
        var runFault: Throwable? = null
        // The run's debug folder, named HERE rather than inside the pipeline.
        //
        // The pipeline names its own when the caller passes none, and that was fine while nothing out
        // here needed the name. Now the clip is handed to the outbox under it from this `finally` — on
        // every path, including a Stage 1 throw that happens before a single artifact is staged — so the
        // lane has to know the folder before the run starts rather than learn it from an event that a
        // failed run may never emit.
        val debugRun = DebugUploader.newRunLabel()
        // The stage that threw, if one did — for the status line and the log. The pipeline emits
        // `Failed` before it throws, so this is set by the time the exception arrives here.
        var failedStage: String? = null

        try {
            pipeline.emitPreviews = FleetBus.hasObservers
            pipeline.run(
                videoFile = clip,
                samplesPerSecond = SAMPLES_PER_SECOND,
                runLabel = debugRun,
                // The clip's provenance goes IN, rather than being appended to the stored run at the
                // end: the payload's timestamp, its location and its station are facts about the
                // recording, and the pipeline is the only thing that can put them on the body.
                metadata = metadata
            )
                .buffer(EVENT_BUFFER)
                .collect { event ->
                    // Every event is a sign of life. A run legitimately goes quiet for a long time
                    // inside Stage 4 — see [STALL_AFTER_MS] — but it cannot go quiet FOREVER, and this
                    // is what tells the difference.
                    beat()
                    FleetBus.emit(event)
                    when (event) {
                        is PipelineEvent.StageStarted -> {
                            Log.i(TAG, "$name ${event.stage.displayName} started at ${event.atMs}ms")
                        }
                        is PipelineEvent.StageFinished -> {
                            Log.i(
                                TAG,
                                "$name ${event.stage.displayName} finished in ${event.durationMs}ms " +
                                    "(at ${event.atMs}ms)"
                            )
                        }
                        is PipelineEvent.ScratchOpened -> {
                            scratchId = event.videoId
                            // Claimed the moment Stage 1 has somewhere to write, so a sweep — this
                            // lane's own at startup, or a validation run's — cannot delete this run's
                            // frames while it is using them.
                            RunScratch.claim(event.videoId)
                        }
                        is PipelineEvent.CaptureResult -> {
                            // AND across every result, so one incomplete board makes the capture
                            // incomplete — and non-null either way, because a result did arrive.
                            captureComplete = (captureComplete ?: true) && event.complete
                            if (runGeneration == generation) {
                                saveJob = fileRun(name, metadata, event, previous = saveJob)
                            } else {
                                // A run written off as stalled that answered anyway. Not filed: the
                                // restarted loop is processing this same clip, and two readings from one
                                // capture is exactly what the payload's one-body-per-capture shape
                                // exists to prevent.
                                Log.w(TAG, "$name answered after its loop was restarted — not filed")
                            }
                        }
                        // A stage threw. Reported here rather than left to the collector's `else`,
                        // which used to drop these on the floor: the run is about to end with an
                        // exception, and without this nothing on the device would say WHICH stage went.
                        is PipelineEvent.Failed -> {
                            failedStage = event.stage
                            noteStageFailure(name, event)
                        }
                        else -> Unit
                    }
                }

            if (captureComplete == true) {
                consecutiveFailures = 0
                runCatching { com.terminalscout.app.prod.ui.ErrorLog.removeErrorByKey(ERR_STAGE_FAILED) }
                // A completed run is the proof the restart worked. Cleared here and nowhere else: the
                // row is about the lane being able to finish a clip, and nothing short of finishing one
                // demonstrates that.
                runCatching { com.terminalscout.app.prod.ui.ErrorLog.removeErrorByKey(ERR_STALLED) }
            } else if (captureComplete == false) {
                // The run finished but could not read the board — a reader that refused the request,
                // or a partial read. NOT the plain-offline case: the loop declines to start a run at all
                // without a network (see the `Connectivity` gate above), so an unreachable reader here
                // is one that answered badly rather than one that was never asked.
                //
                // This used to stay on disk to be re-read later, and that recovery is what the move
                // costs. It is given up knowingly: the same clip re-read produced the same refusal often
                // enough that the retry was a CPU loop rather than a second chance, and a clip whose
                // reader refused it is exactly the footage a human needs to see.
                FleetBus.publishStatus("“$name” could not be read — uploading it for review.")
                Log.w(TAG, "$name: incomplete run — handed over for review")
            } else {
                // The flow ended without a CaptureResult and without throwing. Not reachable today —
                // every exit from `run` either emits one or throws — but it is the shape a future
                // early return would take, and treating it as a success would delete a clip nothing
                // read.
                Log.e(TAG, "$name: the run produced no result at all — handed over for review")
                FleetBus.publishStatus("“$name” produced no result — uploading it for review.")
            }
        } catch (c: CancellationException) {
            // Not a fault: `stop()` cancels the scope this runs in, and the cancellation travels as a
            // throwable. Caught explicitly ahead of `Exception` — which it is a subclass of — because
            // reporting it put a "failed" row and a failure count on every single service shutdown.
            throw c
        } catch (t: Throwable) {
            // `Throwable`, not `Exception`, and this one is not habit — see `ERROR_HANDLING.md` §1's
            // "off the launch path, catch `Exception`", which this is a deliberate exception to.
            //
            // The likeliest single fault in the whole pipeline is an `OutOfMemoryError`: the run
            // decodes UHD frames, rotates them, hands them to OpenCV and to TFLite, and it does that
            // a thousand times per clip. `OutOfMemoryError` is an `Error`, so it used to sail past
            // here to [loop]'s catch — which contained it, so the app survived, but reported it under
            // [ERR_POLL_FAILED] ("the check for new video failed") for a fault that was nothing of the
            // sort, did not increment [consecutiveFailures], and so could never reach the
            // clip-is-stuck row however many times it repeated. A clip too big for the device's heap
            // is exactly the fault that repeats forever, and it was the one fault the counter could
            // not see.
            //
            // Containing it here is also what makes the `finally` below correct rather than
            // incidental: `captureComplete` is still null, so the clip goes over with a report saying
            // it threw rather than over with nothing, or nowhere at all.
            runFault = t
            noteFailure(name, t, failedStage)
        } finally {
            saveJob?.join()
            withContext(Dispatchers.IO) {
                DebugUploader.awaitIdle()
                // EVERY outcome hands the clip over. It used to be deleted on success and left on disk
                // on anything else, which is the two halves of one bug: a successful capture destroyed
                // the only footage of it before its reading had been accepted by anything, and a failed
                // one was re-read every five seconds forever at full CPU — the hazard this class's note
                // called the highest-risk open item in the app.
                //
                // The clip is MOVED into the upload outbox instead, and the outbox is durable: the file
                // is off the processing queue the instant the move lands, so the loop cannot pick it up
                // again, and it is not deleted until `DebugUploadWorker` has actually delivered it. A
                // move is a rename — `recorded_videos/` and `debug_outbox/` are both under `filesDir` —
                // so nothing is duplicated and the pipeline is not made to wait on a network.
                //
                // The one case that must NOT move it is a superseded run: the live loop owns that clip.
                if (runGeneration != generation) {
                    Log.w(TAG, "$name finished in a superseded run — left for the live loop")
                } else {
                    // A run that answered sends the clip alone. One that did not sends a report with
                    // it, because a clip in the container with nothing saying why it is there is a
                    // video nobody can account for.
                    if (captureComplete != true) {
                        runCatching {
                            DebugUploader.enqueueRunFailure(
                                context = context,
                                runFolder = debugRun,
                                clip = name,
                                clipBytes = clip.length(),
                                outcome = if (captureComplete == false) "incomplete" else "threw",
                                stage = failedStage,
                                cause = runFault
                            )
                        }.onFailure { Log.e(TAG, "could not stage the failure report for $name", it) }
                    }
                    // Staged BEFORE the clip moves, so `clip.length()` above is still readable.
                    val handedOver = runCatching {
                        DebugUploader.enqueueClip(context, debugRun, clip)
                    }.onFailure {
                        Log.e(TAG, "could not hand $name to the outbox", it)
                    }.getOrDefault(false)

                    if (handedOver) {
                        VideoMetadataStore.delete(context, clip.name)
                        Log.d(TAG, "$name handed to the outbox as $debugRun/${DebugUploader.CLIP_FILE}")
                    } else {
                        // The outbox refused it — disabled, or the move failed. Deleting it here would
                        // destroy footage nothing has a copy of, so it stays, and the poll loop will
                        // offer it again. That is the OLD behaviour, kept for exactly this path: it is
                        // the one case where retrying forever beats losing the recording.
                        Log.w(TAG, "$name kept on disk — the outbox did not take it")
                    }
                }
                // The scratch images go either way: they are re-derived from the clip on the next run,
                // and leaving them is how a device with a backlog fills up. `delete` drops the claim
                // with them; the `release` covers the path where the delete itself threw.
                scratchId?.let {
                    runCatching { RunScratch.delete(context, it) }
                        .onFailure { e -> Log.e(TAG, "could not clear the scratch for $it", e) }
                    RunScratch.release(it)
                }
            }
        }
        return true
    }

    /**
     * Suspends until there is a validated internet connection, or [OFFLINE_WAIT_MAX_MS] elapses.
     *
     * The timeout is a backstop, not the mechanism: [Connectivity.whenOnline] registers a network
     * callback that fires once and unregisters itself, and Android delivers `onAvailable` for a network
     * that already satisfies the request at registration time — so there is no window in which the
     * connection returns unnoticed between the check above and the wait here. The cap exists because a
     * callback that never arrives at all must not hold the lane for the life of the process; when it
     * expires the loop simply comes round and checks again.
     *
     * The handle is cancelled on the way out either way, so a hold that ends by timeout does not leave
     * a registered callback behind — enough of those and the platform refuses to register any more.
     */
    private suspend fun awaitConnection() {
        val returned = withTimeoutOrNull(OFFLINE_WAIT_MAX_MS) {
            suspendCancellableCoroutine<Unit> { cont ->
                val handle = Connectivity.whenOnline(context) {
                    // Arrives on a binder thread; resuming a coroutine from one is safe.
                    if (cont.isActive) cont.resume(Unit)
                }
                cont.invokeOnCancellation { runCatching { handle.cancel() } }
            }
        }
        if (returned == null) {
            Log.d(TAG, "still offline after ${OFFLINE_WAIT_MAX_MS / 1000}s — re-checking")
        } else {
            Log.i(TAG, "connection back — resuming processing")
        }
    }

    /**
     * Puts a failed stage on the errors screen and the status line, as it happens.
     *
     * The event used to be dropped by the collector's `else` branch, so a broken detector or a broken
     * prep stage was visible in Logcat and nowhere else — on a unit nobody is attached to, that is
     * nowhere at all. The row is a CONSTANT message so the raise and the clear match; which stage went
     * and why goes to the status line and the log, where an interpolated value is free.
     */
    private fun noteStageFailure(name: String, event: PipelineEvent.Failed) {
        Log.e(TAG, "“$name” — stage ${event.stage} failed", event.error)
        runCatching {
            com.terminalscout.app.prod.ui.ErrorLog.showError(ERR_STAGE_FAILED, MSG_STAGE_FAILED, SOURCE)
        }
        FleetBus.publishStatus(
            "“$name” — the ${event.stage} stage failed: " +
                (event.error.localizedMessage ?: event.error.javaClass.simpleName)
        )
    }

    /**
     * Records a failed run, loudly.
     *
     * The clip IS moved aside — the `finally` in [processOne] hands it to the upload outbox with this
     * failure's report beside it, so it cannot be picked up again. What the count is for is the device,
     * not the clip: see the note in the body.
     *
     * @param stage the pipeline stage that reported itself failed, when one did — a run that threw
     *   inside a stage has already raised its own row, and repeating the same message under a second
     *   key would put two lines on the errors screen for one fault.
     */
    private fun noteFailure(name: String, e: Throwable, stage: String? = null) {
        // Counts CONSECUTIVE FAILING CLIPS, not retries of one clip. It used to be the latter — a clip
        // stayed on disk and came back every five seconds, so the same name arriving again was the
        // signal. A failed clip now leaves the device with its report, so the same name cannot recur,
        // and a counter keyed on it would sit at 1 forever.
        //
        // What is worth alarming on instead is a device that fails EVERYTHING: the fault is then the
        // unit, not the clip, and no amount of moving clips off it will help.
        consecutiveFailures++
        Log.e(
            TAG,
            "“$name” failed ($consecutiveFailures in a row" +
                (stage?.let { ", stage $it" } ?: "") + ") — it is being uploaded for review",
            e
        )
        FleetBus.publishStatus("“$name” failed: ${e.localizedMessage}")
        if (consecutiveFailures >= FAILURE_ALARM_AT) {
            runCatching {
                com.terminalscout.app.prod.ui.ErrorLog.showError(
                    ERR_CLIP_STUCK, MSG_CLIP_STUCK, SOURCE
                )
            }
        }
    }

    /**
     * The vehicle's position for a stored run, with the fix's age and error beside it.
     *
     * Both qualifiers are printed because the raw pair is not interpretable without them: a fix taken
     * four minutes before the clip is where the vehicle parked, not where it was recording from, and a
     * lead distance computed from a fix with 40 m of error is not a measurement.
     */
    private fun vehicleLine(metadata: VideoMetadataStore.Metadata): String {
        val lat = metadata.vehicleLatitude
        val lon = metadata.vehicleLongitude
        if (lat == null || lon == null) return "no GPS fix at the time of recording"
        val accuracy = metadata.vehicleAccuracyM?.let { " ±${it.toInt()}m" } ?: ""
        val age = metadata.vehicleFixAtMs
            ?.let { (metadata.capturedAtMs - it).coerceAtLeast(0L) / 1000 }
            ?.let { " (fix ${it}s before the clip finalized)" }
            ?: ""
        return "$lat, $lon$accuracy$age"
    }

    /**
     * Files a finished capture: enqueues its payload for upload and stores the run for the picker.
     *
     * Chained onto [previous] rather than launched alongside it — two saves writing the run store at
     * once would race, and the loop waits on the returned handle to know the run's images have been
     * copied out of `prepped/` before it deletes them.
     *
     * **Once per capture, not once per board.** The payload carries every board of the capture in its
     * `boards` array, so there is one body, one queue row and one stored run however many posts were in
     * shot.
     */
    private fun fileRun(
        name: String,
        metadata: VideoMetadataStore.Metadata?,
        event: PipelineEvent.CaptureResult,
        previous: Job?
    ): Job = scope.launch {
        // Guarded whole. This is a fire-and-forget `launch` into the SERVICE's scope, and the only thing
        // that ever waits on it is the `join` in the poll loop's `finally` — so an escape here would not
        // be reported by anything: it would reach the scope's handler at best and the thread's
        // uncaught-exception handler at worst. It writes an upload queue row, a run-store entry and a
        // set of copied images, all of which can fail on a full or unwritable volume.
        crashproofSuspend(SOURCE, ContainedFaults.PROCESSING_LANE, "fileRun") {
        previous?.join()

        // An incomplete run's body is well-formed and empty. Sending it would put "this forecourt had
        // no prices" into the fleet's data, and nothing later can contradict it — the recovered prices
        // have no way back into a capture the server already accepted. So it is stored for review and
        // not sent.
        //
        // It used to be re-read instead: the clip stayed on disk and posted once, with real prices, on
        // a later run. That is gone — the clip is uploaded for review and leaves the device — so this
        // capture now files NO reading at all rather than a late one. The empty body is still not sent,
        // which is the part that mattered: a missing reading can be filled in from the clip a human is
        // looking at, whereas an accepted empty one cannot be taken back.
        val willUpload = event.complete
        val enriched = StringBuilder(event.payload).append("\n\n")

        if (willUpload) {
            withContext(Dispatchers.IO) {
                val rid = uploadQueue.enqueueReading(event.payload, System.currentTimeMillis())
                enriched.append("--- Upload queue ---\n")
                enriched.append("Reading payload enqueued (row $rid).\n")
                enriched.append("Pending: ${uploadQueue.pendingCount(UploadDbHelper.TABLE_READINGS)} reading(s).\n")
                enriched.append("Endpoint: ${UploadWorker.READINGS_ENDPOINT} (placeholder)\n")
            }
            UploadScheduler.requestImmediate(context)
        } else {
            enriched.append("(Not enqueued — the run did not complete.)\n")
        }

        // The sidecar's provenance, appended so a stored run says which board it was aimed at even when
        // the pipeline read a different one. Absent for a test clip, which the line says plainly.
        if (metadata != null) {
            enriched.append("Station: ${metadata.stationName ?: "unknown"} (${metadata.stationId ?: "-"})\n")
            // Both positions, labelled. They used to be one line reading "Captured at:" over what were
            // in fact the station's coordinates — which read as the vehicle's and was the more useful
            // of the two to misreport, because it is the one a reader would use to judge the approach.
            enriched.append(
                "Board at: ${metadata.stationLatitude ?: "-"}, ${metadata.stationLongitude ?: "-"}\n"
            )
            enriched.append("Vehicle at: " + vehicleLine(metadata) + "\n")
            enriched.append("Device: ${metadata.deviceId}\n")
        } else {
            enriched.append("Station: no metadata sidecar for this clip.\n")
        }

        val label = name + if (event.boardCount > 1) " · ${event.boardCount} boards" else ""
        withContext(Dispatchers.IO) { runStore.save(label, enriched.toString(), event.ocrFramePaths) }
        FleetBus.notifyRunsChanged()
        Log.i(TAG, "filed $name: ${event.boardCount} board(s), uploaded=$willUpload")
        }
    }

    // ---- Readout ------------------------------------------------------------------------------------ //

    /**
     * Publishes what is on disk and what is running.
     *
     * The rows are synthesised from the directory rather than held in a list, so the readout cannot
     * describe work the disk does not have. There are only two states now — the clip being processed,
     * and the clips waiting — because a finished clip is deleted and a failed one is indistinguishable
     * from a waiting one until it is tried again.
     */
    private fun publishQueue() = crashproof(
        SOURCE, ContainedFaults.PROCESSING_LANE, "publishQueue"
    ) {
        val running = processing
        val clips = File(context.filesDir, CameraCaptureManager.RECORDED_VIDEOS_DIR)
            .listFiles { f -> f.isFile && f.name.endsWith(".mp4") }
            ?.sortedBy { it.lastModified() }
            ?: emptyList()

        FleetBus.publishQueue(
            clips.mapIndexed { index, f ->
                val name = displayName(f)
                FleetBus.QueueRow(
                    id = index.toLong(),
                    name = name,
                    processing = name == running,
                    bytes = f.length()
                )
            }
        )
    }

    /**
     * `Capture 08:14:13 · 8a6ea825` — when the recording finalized, and which clip it was.
     *
     * The id half is not decoration. This was the time alone, which is not an identity: two clips
     * finalized in the same second share it, and so does every clip recorded at 08:14:13 on any other
     * day. Two things key off this string — the stored run's label in the picker, and the queue row the
     * readout highlights as processing — and both quietly merged clips that happened to collide.
     *
     * A third used to: the consecutive-failure counter compared this name against the last failing one,
     * so two different clips failing once each read as one clip failing twice. That comparison is gone,
     * because a failed clip now leaves the device and the same name cannot recur — the counter simply
     * counts failures in a row. The identity still matters for the other two.
     *
     * The clip's file name is a UUID minted per recording, so its first eight characters are enough to
     * tell any two clips on a device apart while staying short enough to read. The date is not in here
     * because the run store stamps its own timestamp beside it.
     */
    private fun displayName(clip: File): String {
        val at = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(clip.lastModified()))
        val id = clip.nameWithoutExtension.take(8)
        return "Capture $at · $id"
    }

    // ---- Test clips ---------------------------------------------------------------------------------- //

    // enqueueTestClip used to live here. It COPIED a sideloaded clip out of `testclips/` and into
    // `recorded_videos/` under a new name — `test-<millis>-<original>.mp4` — so the poller would find
    // it and run the real pipeline over it.
    //
    // The rename is what made it unusable. Every "is this a replayed clip?" test in the app is
    // `TestClips.isTestClip`, which asks whether the file sits in `testclips/`, and a copy in
    // `recorded_videos/` does not. So a replayed clip was treated as a genuine capture by all four
    // things that must know better:
    //
    //   - DebugUploader was never opted out, so the clip and every artifact of the run went into the
    //     fleet's evidence container as though a truck had filmed it;
    //   - TestClipLocations could not supply the fix the clip was originally shot at, so the payload
    //     took a live one — the phone on the desk;
    //   - DayNight.NIGHT_TEST_CLIPS no longer matched the name, so night footage was prepped on the day
    //     branch, which is the one combination that damages a readable crop;
    //   - VideoFrameExtractor.SIDEWAYS_CLIPS no longer matched either, so the three clips that need a
    //     rotation fix were extracted lying down, where the detector finds nothing at all.
    //
    // The `ValidationService` that replayed the set in place is gone too — a production unit does not
    // spend hours of CPU on a test set, and the two process-wide switches it flipped could reach into a
    // real capture being processed beside it. Nothing in this build replays a clip.

    // saveClipToCameraRoll used to live here. It copied every finished clip into DCIM/Camera so a
    // tester could pull it off the device without `run-as`, and it was called from the capture lane's
    // completion callback — which runs on the MAIN THREAD. A UHD clip is a few hundred megabytes, so
    // every capture blocked the UI thread for a whole stream copy, on the one device the app has been
    // killed on for load. It also doubled the disk cost of a capture at the moment the disk is
    // fullest, on a volume the app's own storage floor does not measure.
    //
    // A debug clip is still reachable: the debug uploader files artifacts for a run, and `adb` can read
    // filesDir on a debug build. Nothing in the pipeline read the gallery copy.

    /**
     * What the loop is doing right now.
     *
     * Four states rather than a boolean: a poller that is idle and a poller that is refusing to process
     * are both "running", and confusing them would have the panel call a unit healthy while it silently
     * accumulates unread footage.
     */
    enum class PollState { STOPPED, POLLING, OFFLINE_HELD, PROCESSING }

    companion object {
        private const val TAG = "ProcessingLane"

        /**
         * True while the poll loop is running.
         *
         * Companion-level and `@Volatile` for the same reason as `LocationTrigger.isAttached`: the thing
         * that has to read it is `Stage0Health`, which holds no reference to the lane. It means "the loop
         * is alive", not "the object exists" — [start] sets it after the sweep, and every exit from
         * [loop] clears it.
         */
        @Volatile
        var isPolling: Boolean = false
            private set

        /**
         * What the loop is doing right now, for the home readout.
         *
         * Companion-level like [isPolling], because the reader is `Stage0Health`, which holds no
         * reference to the lane. Four states rather than a boolean: a poller that is idle and a poller
         * that is refusing to process are both "running", and confusing them would have the panel call a
         * unit healthy while it silently accumulates unread footage.
         */
        /** The lane's live state, derived from the flags the loop maintains. */
        val pollState: PollState
            get() = when {
                !isPolling -> PollState.STOPPED
                current != null -> PollState.PROCESSING
                offline -> PollState.OFFLINE_HELD
                else -> PollState.POLLING
            }

        /**
         * Which incarnation of the poll loop is current. Bumped by every forced restart.
         *
         * The cancel that [restartPolling] issues is cooperative, so the loop it replaces can still be
         * running — and can still finish, minutes later, inside a `finally` that would delete a clip.
         * This is how a run tells whether it is still the one that owns the directory.
         */
        @Volatile
        private var generation: Long = 0L

        /**
         * When the loop last showed a sign of life, as epoch millis. 0 until it first runs.
         *
         * Companion-level and `@Volatile` for the same reason as [isPolling]: the reader is the health
         * check, which holds no reference to the lane. It is what makes a STALL visible — `isPolling`
         * only says the loop was claimed, and a loop parked forever inside one clip satisfies that
         * exactly as well as a healthy one does. Without this, the readout said "processing" and meant
         * it, indefinitely, and the watchdog had nothing to test.
         */
        @Volatile
        var lastBeatAtMs: Long = 0L
            private set

        /**
         * True when the loop is claimed but has shown no sign of life for [STALL_AFTER_MS].
         *
         * Deliberately false while the loop is not running at all: that is a different fault with a
         * different repair ([ensurePolling] rather than [restartPolling]), and reporting both would have
         * the health check fix one and then immediately "fix" the other.
         */
        val isStalled: Boolean
            get() {
                if (!isPolling) return false
                val last = lastBeatAtMs
                if (last == 0L) return false   // never ticked: starting, not stalled
                return System.currentTimeMillis() - last > STALL_AFTER_MS
            }

        /** How long the loop has been silent, in ms. 0 before its first beat. */
        val silentForMs: Long
            get() = lastBeatAtMs.takeIf { it > 0L }?.let { System.currentTimeMillis() - it } ?: 0L

        /** Mirrors of the instance flags, so [pollState] can be read without the lane. */
        @Volatile
        private var current: String? = null

        @Volatile
        private var offline: Boolean = false

        const val SOURCE = "Processing"
        const val ERR_POLL_FAILED = "poll_tick_failed"
        const val MSG_POLL_FAILED =
            "A video-processing check failed. Recording is unaffected; clips are waiting on the device " +
                "and processing will retry."
        /**
         * A pipeline STAGE threw — the detector, the image prep, the reader.
         *
         * Its own key, separate from [ERR_CLIP_STUCK]: this says the software broke on a clip, where
         * that one says a clip keeps failing. An operator can act on the second (clean memory) and can
         * only report the first, so telling them apart is the point of having two.
         */
        const val ERR_STAGE_FAILED = "pipeline_stage_failed"
        const val MSG_STAGE_FAILED =
            "A stage of video processing failed on a clip. Recording is unaffected; the clip has " +
                "been uploaded for review with a report of which stage failed, and removed from the " +
                "device. The errors detail and the log say which stage."

        /**
         * The processing loop was found wedged and force-restarted.
         *
         * Its own row, and it stays up until a run completes: a restart is a recovery, not a fix. The
         * lane comes back, but something took forty-five minutes of silence to get there — a socket
         * with no timeout, a native call that did not return — and a unit that does this repeatedly is
         * leaking a thread each time (see [restartPolling]). That is worth a human seeing.
         */
        const val ERR_STALLED = "processing_stalled"
        const val MSG_STALLED =
            "Video processing stopped responding and was restarted. Recording is unaffected and no " +
                "clip was lost; if this keeps happening the device needs attention."

        const val ERR_CLIP_STUCK = "clip_stuck"
        const val MSG_CLIP_STUCK =
            "Several clips in a row have failed to process. They have been uploaded for review and " +
                "removed from the device, so nothing is stuck — but a unit failing every clip needs " +
                "attention."

        /** Base frame extraction rate. */
        private val SAMPLES_PER_SECOND: Int get() = DeviceConfig.int("extraction.samples_per_second", 15)

        /** How many pipeline events may sit undelivered inside the collector. */
        private val EVENT_BUFFER: Int get() = DeviceConfig.int("capture.processing_lane.event_buffer", 16)

        /** How often the disk is checked while nothing is being processed. */
        val POLL_INTERVAL_MS: Long get() = DeviceConfig.long("capture.processing_lane.poll_interval_ms", 5_000L)

        /** Idle gap after a clip finishes, before polling resumes. */
        val POST_CLIP_DELAY_MS: Long get() = DeviceConfig.long("capture.processing_lane.post_clip_delay_ms", 10_000L)

        /**
         * Silence after which the poll loop is treated as wedged rather than busy.
         *
         * It has to clear the longest a HEALTHY run can legitimately say nothing, and that bound comes
         * from Stage 4: the reader is given a 600-second read timeout and three attempts five seconds
         * apart, so a single request group can sit silent for a little over half an hour before it
         * either answers or gives up. Nothing is emitted while it waits. Forty-five minutes is clear of
         * that with room to spare.
         *
         * It is silence, not duration. A three-hour run that keeps emitting stage and progress events
         * is never stalled; a run that emits nothing for forty-five minutes is not slow, it is stuck —
         * every bounded wait in the pipeline is shorter than this, so there is no legitimate state left
         * that produces this much quiet.
         */
        val STALL_AFTER_MS: Long get() = DeviceConfig.long("capture.processing_lane.stall_after_ms", 45 * 60 * 1000L)

        /**
         * Longest the lane will wait on a network callback before checking the connection itself.
         *
         * A backstop on [awaitConnection], not a poll interval: the callback normally ends the hold
         * within a second of the connection returning. Five minutes is long enough that a device parked
         * out of coverage costs nothing, and short enough that a callback lost to a platform quirk
         * delays a backlog by minutes rather than until the service restarts.
         */
        val OFFLINE_WAIT_MAX_MS: Long get() = DeviceConfig.long("capture.processing_lane.offline_wait_max_ms", 5 * 60 * 1000L)

        /** Consecutive failures of the same clip before the errors screen is told. */
        private val FAILURE_ALARM_AT: Int get() = DeviceConfig.int("capture.processing_lane.failure_alarm_at", 3)
    }
}
