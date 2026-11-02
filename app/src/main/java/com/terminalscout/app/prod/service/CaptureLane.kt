package com.terminalscout.app.prod.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.Preview
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.terminalscout.app.prod.CameraCaptureManager
import com.terminalscout.app.prod.VideoMetadataStore
import com.terminalscout.app.prod.upload.UserStore
import com.terminalscout.app.prod.capture.CaptureTrigger
import com.terminalscout.app.prod.capture.CaptureTriggerSink
import com.terminalscout.app.prod.capture.FrozenBoards
import com.terminalscout.app.prod.capture.LocationMap
import com.terminalscout.app.prod.capture.LocationTrigger
import com.terminalscout.app.prod.capture.ManualTrigger
import com.terminalscout.app.prod.capture.StopRecordingMonitor
import com.terminalscout.app.prod.capture.TriggerSource
import com.terminalscout.app.prod.capture.VehicleFix
import com.terminalscout.app.prod.ui.AppErrors
import com.terminalscout.app.prod.ui.ErrorLog
import com.terminalscout.app.prod.ContainedFaults
import com.terminalscout.app.prod.containedIn
import com.terminalscout.app.prod.crashproof
import com.terminalscout.app.prod.crashproofOr
import com.terminalscout.app.prod.crashproofSuspend
import com.terminalscout.app.prod.config.DeviceConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * STAGE 0 — the capture lane. Owns the camera and turns trigger decisions into queued clips.
 *
 * One of [FleetService]'s two independent lanes. It does not know the pipeline exists: a finished clip is
 * moved into `recorded_videos/` and the lane's involvement ends there — nothing is called, nothing is
 * awaited. That independence is the point — a truck passes a second forecourt while the first clip is
 * still six minutes into Stage 4, and a capture path that waited for the processing path would simply
 * not record it.
 *
 * ### The camera is up only while recording
 * Binding costs battery and, on this app's test device, heat — and heat is what kills a run. So the
 * camera is bound when a trigger fires and released the moment the clip finalizes. The exception is a
 * debug screen with the preview attached, which is a human watching and is not a truck.
 *
 * ### A trigger owns both edges, and this lane owns the backstop
 * The [CaptureTrigger] contract lets a trigger decide start *and* stop, which is what the buttons do
 * today and what "board in view / board out of view" will do later. Nothing in that contract bounds
 * clip length, so the lane caps it at [MAX_CLIP_MS] — enforced inside CameraX, below anything a wedged
 * trigger could interfere with. Hitting the cap is a complete clip, not a failure.
 *
 * ### A clip is staged while it records and published when it stops
 * The recorder writes into `staged_videos/`; [publishClip] moves the finished file into
 * `recorded_videos/`, and that move is what hands the clip to [ProcessingLane]. It runs off the main
 * thread, because the move degrades to a full copy when a rename fails and CameraX delivers the
 * finalize callback on the looper. The two directories keep
 * "still being written" and "ready to process" apart as *places* rather than as a race — the poller
 * treats every `.mp4` in `recorded_videos/` as finished work, which a file the encoder still has open
 * cannot be. A clip that records but cannot be published is a reported failure, not a silent one.
 *
 * ### Refusals are normal
 * A trigger firing into a busy lane, a device with no room left, a missing permission — all are logged
 * and dropped. None throws. A capture request is a request.
 */
class CaptureLane(
    private val context: Context,
    lifecycleOwner: LifecycleOwner,
    /**
     * Asks the service to take the `camera` foreground-service type, returning whether it now holds it.
     *
     * The lane owns this call because the lane is the only thing every capture passes through. It used
     * to live in `FleetService.fireCaptureStart`, which is reached from the debug button and the adb
     * intent — and from nothing else. The autonomous path goes `LocationTrigger → requestStart → here`
     * and never touched the service at all, so **the one path the product depends on was the one path
     * that never promoted.** On Android 14+ that means the service holds only `location` while an
     * automatic capture opens the camera, and it fails in the worst shape available: the service stays
     * up, the readout still says WATCHING, and the clips are black or absent.
     */
    private val onCameraNeeded: () -> Boolean
) : CaptureTriggerSink {

    /**
     * Camera work is main-thread: CameraX's callbacks arrive there and its state is not thread-safe.
     *
     * The supervisor and the handler are not decoration. Every public entry point on this lane is a
     * `launch` into this scope, and an unhandled exception in a launched coroutine does not stay in the
     * coroutine — it reaches the thread's uncaught-exception handler, which is a process death. So a
     * trigger firing at a forecourt could take the app down through a fault three calls deep in the
     * camera. Each block is wrapped at its own boundary; this is the scope's backstop.
     */
    private val mainScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
            containedIn(SOURCE, ContainedFaults.CAPTURE_LANE, "capture-lane coroutine")
    )

    private val camera = CameraCaptureManager(context, lifecycleOwner)

    /** The buttons' door into the lane. Held so [FleetService] can route its intents to it. */
    val manualTrigger = ManualTrigger()

    /**
     * Every registered source of capture decisions.
     *
     * [LocationTrigger] is the autonomous one and is live: it compares the GPS against the station map
     * continuously and asks for a clip inside 80 m of a forecourt. [ManualTrigger] stays registered
     * beside it so a tester can still force a capture and compare it against an automatic one, which
     * was the reason the contract allows more than one source in the first place.
     */
    private val locationTrigger = LocationTrigger(context)

    private val triggers: List<CaptureTrigger> =
        listOf(manualTrigger, locationTrigger)

    /**
     * `stopRecording` as a standing background function: stops a clip after 50 m of travel
     * from the closest approach, and releases frozen boards past 130 m.
     *
     * Owned here rather than by [FleetService] because it needs this lane as its sink, and because its
     * lifetime is exactly this lane's — a freeze with nothing watching for the departure that clears it
     * is a board frozen for the life of the process.
     */
    private val stopMonitor = StopRecordingMonitor(context, this)

    /**
     * Set between a granted start request and the clip finalizing. Guards against a double start.
     *
     * Every path that leaves it set is bounded by a deadline — see [armDeadline]. It is the flag that
     * refuses every later trigger with "already recording", so a camera event that simply never arrives
     * must not be able to hold it for the life of the process.
     */
    private var capturing: Boolean = false

    /**
     * Bumped for every capture attempt, so a callback from an attempt already written off cannot claim
     * a later one's deadline.
     *
     * The null latch alone was enough while the only bounded wait was the bind, which is over in
     * milliseconds. It is not enough now: the finalize wait spans a whole clip, and a recorder that
     * reports two minutes late would otherwise take the *next* capture's deadline and run
     * [finishCapture] over the next capture's station id.
     */
    private var captureGeneration: Long = 0L

    /**
     * Posts the camera start-up deadline. Main thread, like everything else the lane does with the
     * camera — the deadline and the callbacks it races must run on one thread or the latch below is
     * not a latch.
     */
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * The armed deadline for whichever camera hand-off is outstanding, or null when none is.
     *
     * One slot, because the hand-offs are strictly sequential: the bind answers, then the recorder
     * starts, then it finalizes. Each one arms the next and there is never more than one waiting.
     *
     * It doubles as the latch for [answer]: whichever of the callback and the deadline arrives first
     * takes it, and the loser finds null and stands down. Without that, a camera that answers a second
     * after the deadline expired would act on a capture the lane has already given up on and
     * un-flagged — a second start against a lane that thinks it is idle.
     */
    private var deadline: Runnable? = null

    /** What asked for the recording in flight, so the finished clip can be attributed. */
    private var activeSource: TriggerSource = TriggerSource.MANUAL_BUTTON

    /**
     * The station the recording in flight was requested for, or null for a manual capture.
     *
     * Held for the duration of the capture so [finishCapture] can stamp `last_capture_time` on the
     * right row — and only on success, which is the whole reason the lane owns this rather than the
     * trigger.
     */
    private var activeStationId: String? = null

    /**
     * Where the vehicle was when the recorder rolled, or null if no fix had arrived yet.
     *
     * Snapshotted rather than read at the end of the capture, because by then the vehicle is up to two
     * minutes past the board and the number the sidecar wants is the one at the start — the lead
     * distance is measured from where recording began, not from where it stopped.
     */
    private var activeVehicleFix: VehicleFix.Fix? = null

    /** When the recording in flight started, for the log line. */
    private var startedAtMs: Long = 0L

    /** True while the camera is rolling. Read by the service for its notification. */
    val isRecording: Boolean get() = camera.isRecording

    // ---- Lifecycle ------------------------------------------------------------------------------ //

    /** Attaches every trigger. Called once when the service starts. */
    fun start() = crashproof(SOURCE, ContainedFaults.CAPTURE_LANE, "start") {
        // Anything left in staged_videos/ is an unfinalized clip from a process that died with the
        // camera rolling. Swept here because this is the one moment the lane can be certain nothing is
        // recording — the triggers are not attached yet — and because a staged file has not been
        // published, so no queue is owed it and nothing else can be holding it.
        CameraCaptureManager.sweepStaged(context)
        // Each trigger attached independently, so one that will not attach cannot cost the others. The
        // location trigger is the one that matters and it is last in nobody's interest to skip.
        triggers.forEach { trigger ->
            crashproof(SOURCE, ContainedFaults.CAPTURE_LANE, "attach(${trigger.name})") {
                trigger.attach(this)
            }
        }
        // Started alongside the triggers, not lazily on the first freeze: a board frozen with no
        // monitor running is a board nothing will ever release.
        stopMonitor.start()
        Log.i(
            TAG,
            "capture lane up — triggers: ${triggers.joinToString { it.name }}, " +
                "stopRecording watching the frozen list"
        )
    }

    /**
     * Re-attaches whatever part of the lane has stopped watching, and nothing that has not.
     *
     * Called from the Stage 0 health check every few minutes. It exists because a lane can fail *inside*
     * a running service — revoke the location permission at runtime and the trigger detaches while the
     * service, the notification and the queue all carry on — and [start] cannot repair that, because the
     * service only calls it once and would double-subscribe if it called it again.
     *
     * Each half is guarded by its own flag, so a healthy component is left alone.
     */
    fun ensureAttached() {
        // Onto the main thread, like every other entry point here: the health check calls this from the
        // service's renewal loop, which runs on Dispatchers.Default.
        mainScope.launch {
            // Two independent repairs, guarded independently: this is the health check's only lever, it
            // runs every 60 seconds with nobody watching, and a fault repairing one half must not leave
            // the other half broken until the next tick.
            crashproof(SOURCE, ContainedFaults.CAPTURE_LANE, "ensureAttached(trigger)") {
                if (!LocationTrigger.isAttached) {
                    Log.w(TAG, "location trigger was not watching — re-attaching")
                    locationTrigger.attach(this@CaptureLane)
                }
            }
            crashproof(SOURCE, ContainedFaults.CAPTURE_LANE, "ensureAttached(stop watch)") {
                if (!StopRecordingMonitor.isWatching) {
                    Log.w(TAG, "stop-recording watch was off — restarting it")
                    stopMonitor.start()
                }
            }
        }
    }

    /** Detaches every trigger and releases the camera. An in-flight clip is stopped, not abandoned. */
    fun stop() {
        // Guarded step by step, and that is the whole point of the shape here: this runs from
        // `onDestroy`, so it is the last chance to let go of the camera and the two location
        // subscriptions. One of them refusing must not leave the other two held by a dead lane.
        triggers.forEach { trigger ->
            crashproof(SOURCE, ContainedFaults.CAPTURE_LANE, "detach(${trigger.name})") { trigger.detach() }
        }
        // Clears whatever is still frozen — see StopRecordingMonitor.stop.
        stopMonitor.stop()
        mainScope.launch {
            // A deadline outliving the lane would fire against a stopped one, raising a camera error
            // for a capture nobody is waiting for any more.
            crashproof(SOURCE, ContainedFaults.CAPTURE_LANE, "stop") {
                cancelDeadline()
                // Bumped with it, so a camera event still in flight cannot reach a restarted lane; and
                // cleared, because a lane left flagged as recording refuses every capture it is asked
                // for afterwards.
                captureGeneration++
                capturing = false
                camera.release()
            }
        }
    }

    /** Debug screen attached — start drawing the preview. */
    fun attachPreview(provider: Preview.SurfaceProvider) {
        mainScope.launch {
            crashproof(SOURCE, ContainedFaults.CAPTURE_LANE, "attachPreview") {
                camera.attachPreview(provider)
            }
        }
    }

    /** Debug screen gone — stop drawing, and drop the camera if nothing is being recorded. */
    fun detachPreview() {
        mainScope.launch {
            crashproof(SOURCE, ContainedFaults.CAPTURE_LANE, "detachPreview") {
                camera.detachPreview()
                if (!capturing && camera.isBound) camera.release()
            }
        }
    }

    // ---- CaptureTriggerSink --------------------------------------------------------------------- //

    override fun requestStart(source: TriggerSource, reason: String, stationId: String?) {
        // The autonomous path's door, reached from a location callback at the configured interval. Contained here
        // as well as inside the coroutine's scope handler, because this is where the fault can be
        // *attributed* — a row that says the capture lane failed is worth reading; one that says a
        // coroutine failed is not.
        mainScope.launch {
            crashproof(SOURCE, ContainedFaults.CAPTURE_LANE, "requestStart") {
                beginCapture(source, reason, stationId)
            }
        }
    }

    override fun requestStop(reason: String, stationId: String?) {
        mainScope.launch {
            crashproof(SOURCE, ContainedFaults.CAPTURE_LANE, "requestStop") {
            if (!capturing) {
                Log.d(TAG, "stop ($reason) ignored — nothing is recording")
                return@crashproof
            }
            // A named stop is about one board. Several can be frozen at once, so a stop that names a
            // board other than the one being recorded must be ignored rather than truncating this clip.
            if (stationId != null && stationId != activeStationId) {
                Log.d(
                    TAG,
                    "stop ($reason) ignored — recording $activeStationId, not $stationId"
                )
                return@crashproof
            }
            Log.i(TAG, "stop requested: $reason")
            camera.stopRecording()   // the clip arrives through the callback set in beginCapture
            }
        }
    }

    // ---- The capture itself ---------------------------------------------------------------------- //

    private fun beginCapture(source: TriggerSource, reason: String, stationId: String?) {
        if (capturing) {
            Log.i(TAG, "start (${source.wireName}: $reason) refused — already recording")
            return
        }
        if (!hasCameraPermission()) {
            Log.w(TAG, "start (${source.wireName}: $reason) refused — no CAMERA permission")
            ErrorLog.showError(AppErrors.NO_CAMERA_PERMISSION)
            return
        }
        ErrorLog.removeError(AppErrors.NO_CAMERA_PERMISSION)
        val freeMb = freeStorageMb()
        if (freeMb >= MIN_FREE_STORAGE_MB) ErrorLog.removeError(AppErrors.LOW_STORAGE)
        if (freeMb < MIN_FREE_STORAGE_MB) {
            // Recording into a nearly-full device is worse than missing the station: the clip lands,
            // Stage 1 turns it into gigabytes of PNGs, writes start failing part-way through, and the
            // run fails having also taken down whatever else was still queued. Better to say no.
            Log.w(TAG, "start (${source.wireName}: $reason) refused — only ${freeMb}MB free")
            // A constant, for the same reason as the camera message: the free figure changes between
            // the raise and the clear, so putting it in the text would leave an unremovable entry — and
            // a disk creeping down a megabyte at a time would stack a fresh one on every fix.
            ErrorLog.showError(AppErrors.LOW_STORAGE)
            FleetBus.publishStatus("Capture refused — ${freeMb}MB free, need ${MIN_FREE_STORAGE_MB}MB.")
            return
        }

        // The camera foreground-service type, taken before the camera is touched and after the cheap
        // refusals above — there is no point promoting for a capture that was never going to roll.
        //
        // A refusal is fatal to THIS capture and is not fatal to the visit: the board is not frozen yet,
        // so the trigger will ask again on the next location fix, by which time
        // `promoteToCameraType` has brought the app forward and the retry can succeed. Rolling the
        // camera anyway would produce exactly the black clip this check exists to prevent.
        if (!onCameraNeeded()) {
            Log.e(TAG, "start (${source.wireName}: $reason) refused — no camera foreground-service type")
            FleetBus.publishStatus(
                FleetBus.status.value.copy(text = "Camera unavailable in the background.", recording = false)
            )
            return
        }

        capturing = true
        val generation = ++captureGeneration
        activeSource = source
        activeStationId = stationId
        // NOTE: the board is NOT frozen here. Binding the camera can fail, and a board frozen for a
        // capture that never rolled is a station lost for the whole visit — see rollRecording, where
        // the freeze happens once the recorder actually starts.
        Log.i(TAG, "capture starting (${source.wireName}: $reason)")
        FleetBus.publishStatus(FleetBus.status.value.copy(text = "Starting camera…", recording = true))

        // The deadline is armed BEFORE the call, not after: `startCamera` can answer synchronously when
        // the provider future is already resolved, and arming afterwards would leave a deadline running
        // against a capture that is already rolling.
        armDeadline(generation, Stage.BIND, CAMERA_START_TIMEOUT_MS, source, reason)
        camera.startCamera(
            onReady = { if (answer(generation)) rollRecording(generation, source, reason) },
            onError = { e ->
                if (!answer(generation)) return@startCamera
                Log.e(TAG, "capture aborted — camera would not bind", e)
                abortCapture("Camera failed: ${e.message}")
            }
        )
    }

    /**
     * The camera hand-off an armed deadline is waiting on.
     *
     * Three of them, because a capture is three promises the camera stack makes and can fail to keep,
     * and each one leaves [capturing] set while it is outstanding. The bind was bounded first because it
     * was the one observed wedging; the other two are the same fault one and two steps further along.
     */
    private enum class Stage(val waitingFor: String, val statusText: String) {
        BIND("camera did not answer", "Camera did not respond."),
        ROLL("recorder did not start", "The recorder did not start."),
        FINALIZE("recording did not finalize", "The recording did not finalize.")
    }

    /**
     * Arms the deadline for one camera hand-off, replacing whatever was armed before it.
     *
     * Every one of the three is a callback the camera stack promises and cannot be held to. `startCamera`
     * waits on `ProcessCameraProvider.getInstance`, and a wedged stack — another process holding the
     * device, a HAL that stops replying — resolves that future neither way; the recorder's `Start` and
     * `Finalize` events come off the same stack and can go missing for the same reasons. [capturing] is
     * cleared only by an answer, so silence in *any* of the three latches the lane for the life of the
     * process: every later trigger refused with "already recording", the readout still green, and
     * nothing recorded again until the unit was restarted. This bounds all three.
     *
     * Giving up costs one capture. Up to [Stage.BIND] it does not even cost the visit — the board is not
     * frozen until the recorder rolls, so the station is still a candidate and the trigger asks again on
     * the next fix. Past that it does, deliberately: the camera was rolling, and one rolling attempt per
     * visit is the rule [rollRecording] states.
     */
    private fun armDeadline(
        generation: Long,
        stage: Stage,
        timeoutMs: Long,
        source: TriggerSource,
        reason: String
    ) {
        cancelDeadline()
        val armed = Runnable {
            crashproof(SOURCE, ContainedFaults.CAPTURE_LANE, "capture deadline (${stage.name})") {
            // Superseded: this attempt has already been written off and another may be in flight.
            if (generation != captureGeneration) return@crashproof
            // Taking the latch here is what makes a late answer harmless: [answer] then finds null, and
            // the camera's eventual reply is dropped instead of acting on a lane that has moved on.
            deadline = null
            Log.e(
                TAG,
                "capture aborted (${source.wireName}: $reason) — ${stage.waitingFor} within ${timeoutMs}ms"
            )
            // The camera is up and, by the definition of this timeout, not answering. Dropping it is what
            // gives the next capture a fresh bind rather than another wedged one. Not on the bind path,
            // where there is nothing bound to drop.
            abortCapture(stage.statusText, releaseCamera = stage != Stage.BIND)
            }
        }
        deadline = armed
        mainHandler.postDelayed(armed, timeoutMs)
    }

    /**
     * Claims the outstanding hand-off, returning false if something else claimed it first.
     *
     * Exactly one caller wins — the camera's callback, or the deadline — and the loser must do nothing at
     * all. A callback carrying a stale [captureGeneration] always loses: it belongs to a capture that was
     * already written off, and the deadline it would otherwise take belongs to a different clip.
     */
    private fun answer(generation: Long): Boolean {
        if (generation != captureGeneration) return false
        val armed = deadline ?: return false
        mainHandler.removeCallbacks(armed)
        deadline = null
        return true
    }

    /** Drops an armed deadline without treating it as an answer. */
    private fun cancelDeadline() {
        deadline?.let { mainHandler.removeCallbacks(it) }
        deadline = null
    }

    /**
     * Unwinds a capture that will not produce a clip — a bind failure, or any of the three deadlines.
     *
     * Clearing [capturing] is the point: it is the flag that lets the next trigger through. The rest is
     * the tidy-up the bind-failure path always did, kept here so every path cannot drift apart.
     */
    private fun abortCapture(statusText: String, releaseCamera: Boolean = false) {
        capturing = false
        stopMonitor.recordingFinished()
        if (releaseCamera) camera.release()
        FleetBus.publishActiveCapture(null)
        // Cleared with it: a stale station id would let a later named stop match a capture
        // that never happened.
        activeStationId = null
        activeVehicleFix = null
        // The banner text is a CONSTANT. Interpolating the failure would make every distinct failure a
        // different entry, and — worse — nothing could ever clear it, because the clear side in
        // rollRecording cannot reconstruct that text. The detail goes to Logcat, which is where a
        // stack trace belongs anyway. The transient status line below may carry it; the error row
        // may not.
        ErrorLog.showError(ERR_CAMERA_FAILED, MSG_CAMERA_FAILED, SOURCE)
        FleetBus.publishStatus(
            FleetBus.status.value.copy(text = statusText, recording = false)
        )
    }

    private fun rollRecording(generation: Long, source: TriggerSource, reason: String) {
        startedAtMs = SystemClock.elapsedRealtime()
        // Armed before the call, for the same reason the bind's was: `startRecording` answers
        // synchronously when the camera is not bound or is already recording, and arming afterwards
        // would leave a deadline running against a capture that has already reported.
        armDeadline(generation, Stage.ROLL, CAMERA_START_TIMEOUT_MS, source, reason)
        camera.startRecording(
            maxDurationMs = MAX_CLIP_MS,
            onStarted = {
                crashproof(SOURCE, ContainedFaults.CAPTURE_LANE, "onStarted") {
                if (!answer(generation)) return@crashproof
                // The clip is now rolling, so the next thing owed is the finalize event — and that wait
                // has to span the whole clip, hence the cap plus the same grace the other two get.
                armDeadline(generation, Stage.FINALIZE, RECORDING_FINALIZE_TIMEOUT_MS, source, reason)
                // FREEZE, here and nowhere else. `onStarted` fires on CameraX's Start event, so by this
                // point the recorder is genuinely rolling for this board — which is the only state that
                // justifies taking the board out of the trigger's candidates.
                //
                // Everything that can fail earlier now fails without freezing anything: a signal refused
                // for storage or permission never reaches this function, and a camera that will not bind
                // ends in the onError branch above. Both leave the board available, so the station is
                // still capturable on this visit.
                //
                // It stays frozen if the recording LATER fails to produce a clip. That is deliberate:
                // the camera was rolling, so a retry inside the same visit risks the loop this mechanism
                // exists to stop. One rolling attempt per visit.
                activeStationId?.let { FrozenBoards.freeze(it, System.currentTimeMillis()) }
                // Taken at the same instant as the freeze, and for the same reason: this is the moment
                // the capture is committed to a board, so this is the vehicle position the clip was
                // recorded from. Null on a cold process that has not had a fix yet, which the sidecar
                // states rather than guessing.
                activeVehicleFix = VehicleFix.last
                stopMonitor.recordingStarted(activeStationId, activeVehicleFix)
                // Published at the same moment as the freeze, and for the same reason: this is the
                // instant the capture is genuinely committed to a board. The station is resolved to a
                // name and a position here, once, so nothing downstream has to look it up.
                publishActiveCapture(reason)
                ErrorLog.removeErrorByKey(ERR_CAMERA_FAILED)
                FleetBus.publishStatus(
                    FleetBus.status.value.copy(
                        text = "Recording (${activeSource.wireName}: $reason)…",
                        recording = true
                    )
                )
                }
            },
            onFinished = { result ->
                crashproof(SOURCE, ContainedFaults.CAPTURE_LANE, "onFinished") {
                if (answer(generation)) {
                    finishCapture(result)
                } else {
                    // A recorder reporting after its deadline expired. The lane has un-flagged and may
                    // already be recording something else, so this clip cannot be published — its
                    // station id and its fix are gone. Discarded rather than left: it is still staged,
                    // so nothing owns it, and a sidecar was never written for it.
                    Log.w(TAG, "finalize dropped — this capture was already written off")
                    result.getOrNull()?.let { CameraCaptureManager.discardStaged(it) }
                }
                }
            }
        )
    }

    /**
     * Tells the readout which board is rolling, resolving the station once.
     *
     * A manual capture has no station, so it publishes a row that says so rather than an empty
     * coordinate — the distinction matters on a screen whose whole job is to show what the unit is
     * pointed at.
     */
    private fun publishActiveCapture(reason: String) {
        val station = activeStationId?.let { id ->
            runCatching { LocationMap.stations(context).firstOrNull { it.id == id } }.getOrNull()
        }
        FleetBus.publishActiveCapture(
            FleetBus.ActiveCapture(
                stationId = activeStationId,
                stationName = station?.name ?: "manual capture — no board",
                latitude = station?.latitude,
                longitude = station?.longitude,
                trigger = activeSource,
                reason = reason,
                startedAtMs = System.currentTimeMillis()
            )
        )
    }

    private fun finishCapture(result: Result<File>) {
        val elapsed = SystemClock.elapsedRealtime() - startedAtMs
        val stationId = activeStationId
        val vehicleFix = activeVehicleFix
        capturing = false
        stopMonitor.recordingFinished()
        // Cleared the moment the clip finalizes, however it ended. A stale row here would have the
        // screen reporting a board the camera stopped watching minutes ago.
        FleetBus.publishActiveCapture(null)
        // Cleared here, not on the next start: a named stop arriving after the clip finalized must not
        // match a station whose capture is already over.
        activeStationId = null
        // Same rule: a fix belonging to a finished capture must never reach the next one's sidecar.
        activeVehicleFix = null
        // Release unless a screen is watching: a bound camera the truck is not using is heat, and heat
        // is what has killed runs on this device before.
        if (!FleetBus.hasObservers) camera.release()

        result.fold(
            onSuccess = { staged -> publishClip(staged, stationId, vehicleFix, activeSource, elapsed) },
            onFailure = { e ->
                Log.e(TAG, "capture failed after ${elapsed / 1000}s", e)
                FleetBus.publishStatus(
                    FleetBus.status.value.copy(text = "Recording failed: ${e.message}", recording = false)
                )
            }
        )
    }

    /**
     * Files a finished clip: sidecar, then the move that hands it to the processing lane.
     *
     * A coroutine rather than the tail of [finishCapture], because every line of it touches the disk or
     * SQLite and [finishCapture] runs on CameraX's finalize callback — the MAIN THREAD. The move is a
     * rename normally and a several-hundred-megabyte copy when the rename fails, and the sidecar step
     * reads the station map and the device identity out of two databases. None of that belongs on a
     * looper: the clip is finished, nothing is waiting on the answer, and an ANR at the moment a capture
     * completes is the failure that loses the NEXT capture too.
     *
     * The trigger, station and fix are passed in rather than read from the fields, because the fields
     * belong to whatever capture is current by the time this runs — and a capture starting while this
     * coroutine is in flight would otherwise have its own station stamped by the previous one's clip.
     */
    private fun publishClip(
        staged: File,
        stationId: String?,
        vehicleFix: VehicleFix.Fix?,
        source: TriggerSource,
        elapsedMs: Long
    ) {
        mainScope.launch {
            crashproofSuspend(SOURCE, ContainedFaults.CAPTURE_LANE, "publishClip") {
                val sizeMb = staged.length() / 1_000_000
                // The provenance sidecar is written while the clip is still STAGED, deliberately before
                // the move that publishes it. Written afterwards it would race the poller: the clip is
                // visible to the processing lane the instant it lands in recorded_videos/, and a run
                // that starts in that gap loses its station attribution for no reason other than the
                // order of two lines here. Sidecars are keyed by file name, which the move preserves.
                //
                // Guarded and non-fatal — a clip without metadata is processed anyway, losing only its
                // station attribution. See [VideoMetadataStore].
                withContext(Dispatchers.IO) {
                    runCatching { writeMetadata(staged, stationId, vehicleFix) }
                        .onFailure { Log.e(TAG, "could not write metadata for ${staged.name}", it) }
                }

                // THE PUBLISH. Recording wrote into staged_videos/; moving the finished clip into
                // recorded_videos/ is what hands it to the processing lane. It suspends onto IO — see
                // [CameraCaptureManager.promoteToRecorded] for why that matters.
                val clip = runCatching { CameraCaptureManager.promoteToRecorded(context, staged) }
                    .getOrElse { e ->
                        // The footage is on disk and readable, it is simply not in the queue — so this is
                        // reported as a failure of the capture rather than swallowed. `markCaptured` and
                        // `onClipCaptured` are both skipped: the station must not be stamped for a clip
                        // nothing will process, and the lane must not announce work it did not queue.
                        //
                        // The sidecar is removed with it. The staged file stays on disk for now — a
                        // failed move usually means a full volume, and it can be pulled off the device
                        // — but [CameraCaptureManager.sweepStaged] will take it at the next service
                        // start, and a sidecar left behind for a clip that is gone is precisely the
                        // orphan this lane is supposed not to create.
                        Log.e(TAG, "clip recorded but could not be published: ${staged.name}", e)
                        withContext(Dispatchers.IO) { VideoMetadataStore.delete(context, staged.name) }
                        ErrorLog.showError(ERR_CLIP_NOT_QUEUED, MSG_CLIP_NOT_QUEUED, SOURCE)
                        FleetBus.publishStatus(
                            FleetBus.status.value.copy(text = "Clip recorded but not queued.", recording = false)
                        )
                        return@crashproofSuspend
                    }
                ErrorLog.removeErrorByKey(ERR_CLIP_NOT_QUEUED)

                Log.i(
                    TAG,
                    "captured ${clip.name} — ${elapsedMs / 1000}s, ${sizeMb}MB, " +
                        "trigger=${source.wireName}"
                )
                FleetBus.publishStatus(
                    FleetBus.status.value.copy(text = "Clip captured — queued.", recording = false)
                )
                // The clip is in the queue, so this is the moment the station can be said to have been
                // captured. Stamped here and nowhere else: a refused start, a camera that never bound
                // and a clip that could not be published all reach neither this line nor the column.
                stationId?.let { id ->
                    withContext(Dispatchers.IO) {
                        LocationMap.markCaptured(context, id, System.currentTimeMillis())
                    }
                }
                // NOTE: the board stays FROZEN here. The clip is done, but the vehicle is still at the
                // forecourt — releasing now is exactly the loop this prevents. StopRecordingMonitor
                // releases it on departure.
                //
                // Nothing is notified. The move above IS the handover: the clip is in recorded_videos/
                // and the processing lane's poll finds it within five seconds. There used to be an
                // `onClipCaptured` callback here, whose only subscriber copied the clip into
                // DCIM/Camera on the main thread — a few hundred megabytes per capture, for a
                // convenience no part of the app read.
            }
        }
    }

    /**
     * Records what was known about this capture, beside the clip.
     *
     * **Two positions, both stored.** The station's is where the board is and says what the clip was
     * aimed at; the vehicle's is [rollRecording]'s snapshot of the last GPS fix and says where the
     * recorder actually was when it started. The difference between them is the lead distance, which is
     * the number the trigger geometry work needs and which neither coordinate gives alone — so storing
     * only one of them, as this did until 2026-09-09, throws the measurement away at the only moment it
     * can be taken.
     *
     * Either can legitimately be null and the sidecar states that rather than guessing: a manual
     * capture names no station, and a capture in the first seconds of a cold process has had no fix.
     */
    private fun writeMetadata(staged: File, stationId: String?, vehicleFix: VehicleFix.Fix?) {
        val station = stationId?.let { id ->
            runCatching { LocationMap.stations(context).firstOrNull { it.id == id } }.getOrNull()
        }
        VideoMetadataStore.write(
            context,
            VideoMetadataStore.Metadata(
                videoName = staged.name,
                stationId = stationId,
                stationName = station?.name,
                stationLatitude = station?.latitude,
                stationLongitude = station?.longitude,
                vehicleLatitude = vehicleFix?.latitude,
                vehicleLongitude = vehicleFix?.longitude,
                vehicleAccuracyM = vehicleFix?.accuracyM,
                vehicleFixAtMs = vehicleFix?.atMs,
                deviceId = runCatching { UserStore(context).deviceId() }.getOrDefault("unknown"),
                capturedAtMs = System.currentTimeMillis()
            )
        )
    }

    // ---- Preflight -------------------------------------------------------------------------------- //

    // Fails CLOSED: a read that threw must report "no permission" rather than let the camera be opened
    // on a guess, which answers with a SecurityException from inside CameraX instead.
    private fun hasCameraPermission(): Boolean =
        crashproofOr(SOURCE, ContainedFaults.CAPTURE_LANE, "hasCameraPermission", false) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        }

    /**
     * Free space on the volume holding this app's files, in MB.
     *
     * `filesDir` specifically, not the external volume: the clip, the frames, the crops and the
     * canvases all land there, and it is the volume that fills.
     */
    private fun freeStorageMb(): Long = try {
        val stat = StatFs(context.filesDir.absolutePath)
        (stat.availableBlocksLong * stat.blockSizeLong) / 1_048_576L
    } catch (e: Exception) {
        // Unreadable free space must not be a reason never to record again. Fail open and let the
        // write itself be the thing that fails, loudly, if it comes to that.
        Log.w(TAG, "could not read free storage — assuming there is room", e)
        Long.MAX_VALUE
    }

    companion object {
        private const val TAG = "CaptureLane"

        /**
         * Hard ceiling on one clip, enforced by CameraX.
         *
         * A trigger owns both edges of a capture, so nothing in the trigger contract bounds this — and
         * an unbounded clip is an unbounded Stage 1. Extracted frames, crops, and canvases can consume
         * substantial storage, so a trigger that fires and never stops can fill the device.
         *
         * Reaching it is not a failure — the clip is complete and is queued exactly as a stopped one.
         *
         * The API owns the clip limit; 25 s is the fallback when no valid config is available.
         * At 15 fps a 25 s clip yields up to 375 extracted frames; longer API-configured clips yield
         * proportionally more, so Stage 1's free-space checks still apply.
         */
        val MAX_CLIP_MS: Long get() =
            DeviceConfig.long("capture.capture_lane.max_clip_ms", 25_000L)

        /**
         * Free space below which a capture is refused, in MB.
         *
         * **6 GB is a headroom figure, not a worst-case one, and that is a deliberate choice.** It was
         * 22 GB, sized to cover one UHD clip's *downstream* cost at the old 120 s ceiling. With the
         * current JPEG frames and configured [MAX_CLIP_MS], a full-length clip can still require
         * substantial frame storage plus crops and canvases. A floor that high
         * refuses captures on a device with a perfectly usable amount of space left, which costs boards
         * on every visit made below it; 6 GB keeps recording through the range where recording is still
         * the right thing to do.
         *
         * What it gives up is the guarantee. Above this floor a short clip is safe and a full-length one
         * at UHD is not: the capture lands, and the run can still exhaust the volume part-way through
         * frame extraction. That failure is Stage 1's and it is recoverable — the clip stays on disk and
         * the frames are scratch — where filling the volume outright is not, so the trade is a run that
         * may fail against visits that certainly would have been skipped.
         *
         * It is a floor to keep the device alive, not a budget. A real budget needs the queue's depth as
         * well, which is the storage work already owed, and the honest fix for the worst case is Stage
         * 1's sample rate and frame format rather than a number here.
         */
        val MIN_FREE_STORAGE_MB: Long get() = DeviceConfig.long("capture.capture_lane.min_free_storage_mb", 6_000L)

        /**
         * How long `startCamera` gets to answer before the capture is written off, in ms.
         *
         * Sized for the slow-but-real bind rather than the typical one: a warm bind is well under a
         * second, but the first bind after a process start, with the provider future still
         * initialising, runs into the low seconds. Eight is clear of that and still short enough that
         * the vehicle is usually inside the station's radius when the next fix arrives, so the retry
         * lands on the same board.
         *
         * It bounds *silence*, not slowness: a camera that answers at all — ready or failed — stops
         * the clock.
         */
        val CAMERA_START_TIMEOUT_MS: Long get() = DeviceConfig.long("capture.capture_lane.camera_start_timeout_ms", 8_000L)

        /**
         * How long the recorder gets to deliver the finalize event before the clip is written off, in ms.
         *
         * [MAX_CLIP_MS] plus [CAMERA_START_TIMEOUT_MS]. The cap is enforced inside CameraX, so a healthy
         * recording — stopped by a trigger, or by the cap — has always finalized well before this; the
         * grace on the end is the same eight seconds the other two hand-offs get, measured from the
         * latest moment the clip could legitimately still have been running.
         *
         * It is therefore derived from [MAX_CLIP_MS] rather than written out, because the two cannot be
         * allowed to drift: raising the clip ceiling without raising this would start writing off
         * perfectly good long clips.
         */
        val RECORDING_FINALIZE_TIMEOUT_MS: Long get() = MAX_CLIP_MS + CAMERA_START_TIMEOUT_MS

        // No fix screen: a camera that will not bind is a hardware or state problem, and sending the
        // operator to a settings page that cannot help is worse than saying nothing.
        const val SOURCE = "Capture"
        const val ERR_CAMERA_FAILED = "camera_failed"
        const val MSG_CAMERA_FAILED = "The camera would not start, so nothing was recorded."

        // Raised when a clip recorded cleanly but could not be moved out of staged_videos/ into the
        // queue. A constant like the rest — the raise and the clear have to match exactly — and its own
        // key, because it is a different fault from a camera that never started: the footage exists and
        // the failure is a filesystem one, which is what an operator reading this needs to know.
        const val ERR_CLIP_NOT_QUEUED = "clip_not_queued"
        const val MSG_CLIP_NOT_QUEUED =
            "A video was recorded but could not be queued for processing. Check free storage."

    }
}
