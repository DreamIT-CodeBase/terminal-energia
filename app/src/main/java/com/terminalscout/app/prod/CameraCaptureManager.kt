package com.terminalscout.app.prod

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.OrientationEventListener
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.io.IOException

/**
 * Owns the CameraX rear-camera session.
 *
 * Responsibilities:
 *   - bind a [VideoCapture] use case to the back camera, scoped to the caller's lifecycle;
 *   - record a video-only clip into app FILES storage and hand the finished [File] back so the
 *     pipeline ([com.terminalscout.app.prod.pipeline]) can extract frames from it.
 *
 * ### A clip is recorded in one directory and published into another
 * Recording writes into [STAGED_VIDEOS_DIR]; the finished clip is moved into [RECORDED_VIDEOS_DIR] by
 * [promoteToRecorded], which the capture lane calls once the finalize event has landed. The move is the
 * publish: `recorded_videos/` is the processing lane's queue, so a file appearing there means "finished,
 * process me", and a file still being written cannot be allowed to make that claim. See
 * [STAGED_VIDEOS_DIR] for what went wrong when there was only one directory.
 *
 * There is no audio track (video-only), so the app needs only the CAMERA permission.
 *
 * ### Headless by default; the preview is the optional part
 * This used to require a `PreviewView` at construction, which made the camera unusable without a
 * screen — the exact thing an autonomous truck deployment needs. The preview is now attached and
 * detached at runtime ([attachPreview] / [detachPreview]) and the [Preview] use case is bound only
 * while one is present. A deployed truck records with nothing drawn; the debug screen attaches when it
 * opens and detaches when it leaves, and the difference costs one rebind.
 *
 * ### Completion is a callback, never a return value
 * A recording can end for three different reasons — somebody called [stopRecording], the duration cap
 * fired inside CameraX, or the camera failed — and only the first of those is something the caller
 * asked for. So the finished clip arrives through the `onFinished` callback given to [startRecording],
 * whichever of the three happened. [stopRecording] is fire-and-forget: it *requests* the end, and the
 * callback reports it. The alternative — suspending inside `stopRecording` — could only ever describe
 * the one ending the caller initiated, and silently lost the other two.
 */
class CameraCaptureManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {

    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null

    /** Where the live preview should be drawn, when a screen is watching. Null = headless. */
    private var surfaceProvider: Preview.SurfaceProvider? = null

    /** Set for the duration of a recording; invoked exactly once, however the recording ends. */
    private var finishHandler: ((Result<File>) -> Unit)? = null

    /** Last rotation pushed to [videoCapture], so the listener only acts when it actually changes. */
    private var lastRotation: Int = Int.MIN_VALUE

    /**
     * Keeps the recording's rotation metadata in step with how the phone is actually being held.
     *
     * Without this, CameraX takes the display rotation once at bind time and never revisits it, so a
     * clip recorded with the phone held sideways is written with a PORTRAIT rotation flag. Those pixels
     * are already world-upright, so applying that flag tips them over: `MediaMetadataRetriever` obeys
     * the metadata and Stage 1 extracts sideways frames — at which point the board detector, trained on
     * upright boards, stops recognising the board at all. Observed on a real clip: the full-frame passes
     * found zero boards and only the 0.01 shifted rungs fired, on noise.
     *
     * CameraX applies the target rotation when a recording STARTS, so keeping it continuously current
     * is enough — nothing extra is needed in [startRecording].
     *
     * On a truck the phone is bracketed and never turns, so this will sit idle; it costs nothing and it
     * is the difference between a usable clip and an unreadable one on any device that is not.
     */
    private val orientationListener = object : OrientationEventListener(context) {
        /**
         * Guarded whole: this is the sensor service calling us back, several times a second while the
         * device is moving, and `targetRotation` throws on a use case CameraX has torn down underneath
         * us. A throw here is a process death caused by nothing more than the phone being tilted.
         */
        override fun onOrientationChanged(orientation: Int) {
            crashproof(SOURCE, ContainedFaults.CAMERA, "onOrientationChanged") {
                // Reported while the device is flat, where "which way up" is meaningless. Keep the last
                // known rotation rather than snapping to an arbitrary one.
                if (orientation == ORIENTATION_UNKNOWN) return@crashproof

                val rotation = when (orientation) {
                    in 45 until 135 -> Surface.ROTATION_270
                    in 135 until 225 -> Surface.ROTATION_180
                    in 225 until 315 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                if (rotation == lastRotation) return@crashproof  // fires on every degree; real changes only
                lastRotation = rotation
                videoCapture?.targetRotation = rotation
                Log.d(TAG, "device orientation ${orientation}° → target rotation $rotation")
            }
        }
    }

    /** True between [startRecording] and the finalize event. */
    var isRecording: Boolean = false
        private set

    /** True once [startCamera] has bound a video-capture use case. */
    val isBound: Boolean get() = videoCapture != null

    private val mainExecutor by lazy { ContextCompat.getMainExecutor(context) }

    /**
     * Starts drawing the camera onto [provider], rebinding if the camera is already up.
     *
     * Only a debug screen calls this. A rebind mid-recording is not attempted — CameraX would tear the
     * active recording down with it — so a screen that opens during a capture simply sees the preview
     * from the next clip onwards. Losing the picture of a capture already in progress is a great deal
     * cheaper than losing the capture.
     */
    fun attachPreview(provider: Preview.SurfaceProvider) {
        crashproof(SOURCE, ContainedFaults.CAMERA, "attachPreview") {
            surfaceProvider = provider
            if (isRecording) {
                Log.d(TAG, "preview attached mid-recording — binding deferred to the next clip")
                return@crashproof
            }
            if (cameraProvider != null) startCamera()
        }
    }

    /** Stops drawing the preview, rebinding without it. Same mid-recording rule as [attachPreview]. */
    fun detachPreview() {
        crashproof(SOURCE, ContainedFaults.CAMERA, "detachPreview") {
            surfaceProvider = null
            if (isRecording) {
                Log.d(TAG, "preview detached mid-recording — unbinding deferred to the end of the clip")
                return@crashproof
            }
            if (cameraProvider != null) startCamera()
        }
    }

    /**
     * Binds the rear-camera video-capture use case (plus the preview, if one is attached). Call once
     * the CAMERA permission is granted. Safe to call again to rebind.
     *
     * @param onReady invoked on the main thread once the camera is bound and recording is possible.
     * @param onError invoked on the main thread if the camera could not be brought up. A truck cannot
     *   surface a dialogue, so a failure has to be a value the caller can act on rather than a log line
     *   nobody reads — the capture lane uses it to release the wake lock and report the missed station.
     */
    fun startCamera(onReady: (() -> Unit)? = null, onError: ((Throwable) -> Unit)? = null) {
        // `getInstance` runs on the CALLER's thread and can throw by itself — no camera provider on the
        // device, an initialisation failure inside CameraX. It has to reach [onError] rather than
        // propagate: the caller has a start deadline armed and nothing else is going to answer it.
        val future = try {
            ProcessCameraProvider.getInstance(context)
        } catch (t: Throwable) {
            Log.e(TAG, "could not ask for a camera provider", t)
            answerError(onError, t)
            return
        }
        future.addListener({
            // The bind and the caller's callback are separately guarded, and that separation is the
            // point: an exception thrown by the caller's own `onReady` used to land in this catch and be
            // reported as a bind failure, so `onError` was then called for a camera that had bound
            // perfectly — two answers for one start, against a latch that only expects one.
            val bound = try {
                val provider = future.get()
                cameraProvider = provider

                // Record at [CAPTURE_QUALITY] and fall back down the list, so recording never fails to
                // start on a device that cannot offer the top rung.
                //
                // Resolution here is the ceiling on everything downstream. No amount of later
                // processing recovers detail the encoder never captured — Stage 3 enlarges crops with a
                // cubic kernel, which sharpens edges but invents no information.
                //
                // **This used to lead with UHD and no longer does.** The argument for UHD was that it
                // is 4x the pixels on a distant board for the same shot, and that the clip was
                // transient anyway. The second half stopped being true: the clip is now MOVED to the
                // debug container and uploaded rather than deleted after processing, so every capture
                // costs a few hundred megabytes of mobile data, and it recurs for every trigger. The
                // comment that used to sit here said "watch the transfer if that bites" — it bit.
                //
                // FHD rather than something smaller because the gate is measurable: a board 30 m out is
                // ~197 px wide at UHD, ~99 px at FHD, ~66 px at HD and ~33 px at SD, while
                // `ClarityGate` rejects a crop under 32 px. FHD keeps roughly a 3x margin over that
                // threshold at a quarter of UHD's bytes; SD would put a 30 m board on the threshold
                // itself, which is the one saving that would cost readings rather than storage.
                //
                // The saving is not only the clip. Stage 1 writes a full-resolution PNG per sampled
                // frame, and those scale with pixel count too, so the whole run's disk drops with this.
                //
                // NOT MEASURED: whether UHD actually read boards that FHD misses. Nobody has A/B'd the
                // two on the same forecourt. If a later run shows distant boards being lost, this is
                // the first thing to put back.
                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.fromOrderedList(CAPTURE_QUALITY_LADDER))
                    .build()
                val capture = VideoCapture.withOutput(recorder)
                videoCapture = capture

                // The preview is bound only when something is going to draw it. Headless is the normal
                // case on a truck; a bound Preview with no surface is wasted GPU on a device this app
                // has been killed on for exactly that kind of load.
                val useCases = buildList {
                    add(capture)
                    surfaceProvider?.let { sp ->
                        add(Preview.Builder().build().also { it.setSurfaceProvider(sp) })
                    }
                }

                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    *useCases.toTypedArray()
                )
                // Start tracking how the phone is held now that there is a use case to apply it to.
                // Guarded on its own: a device with a broken orientation sensor must still record, and
                // the only cost of losing this is the rotation flag — see the listener's note.
                runCatching {
                    if (orientationListener.canDetectOrientation()) {
                        orientationListener.enable()
                    } else {
                        Log.w(TAG, "no orientation sensor — recordings keep the bind-time rotation")
                    }
                }.onFailure { Log.w(TAG, "could not start the orientation listener", it) }
                true
            } catch (t: Throwable) {
                // Throwable: `future.get()` wraps whatever CameraX threw, and an Error out of a native
                // camera stack is exactly the kind of thing that reaches here.
                Log.e(TAG, "Failed to start rear camera", t)
                videoCapture = null
                answerError(onError, t)
                false
            }
            if (bound) {
                Log.d(TAG, "camera bound (preview=${surfaceProvider != null})")
                // The caller's callback, contained. It rolls a recording and touches the error log and
                // the bus; a throw out of it here would be a process death on CameraX's executor.
                crashproof(SOURCE, ContainedFaults.CAMERA, "onReady") { onReady?.invoke() }
            }
        }, mainExecutor)
    }

    /** Hands a failure to the caller without letting the caller's own fault escape into the executor. */
    private fun answerError(onError: ((Throwable) -> Unit)?, cause: Throwable) {
        crashproof(SOURCE, ContainedFaults.CAMERA, "onError") { onError?.invoke(cause) }
    }

    /**
     * Starts recording a video-only clip to a fresh file in files storage.
     *
     * @param maxDurationMs hard ceiling on the clip, enforced by CameraX itself. Reaching it ends the
     *   recording normally — the clip is complete and is delivered through [onFinished] exactly as a
     *   requested stop would be. This is the backstop for a trigger that starts a capture and then
     *   never stops it: the contract lets a trigger own both edges, so something has to own the case
     *   where it only ever supplies one, and it has to be lower than the camera stack so a wedged
     *   caller cannot defeat it.
     * @param onStarted invoked on the main thread once recording is actually rolling.
     * @param onFinished invoked on the main thread exactly once, with the clip or with the failure —
     *   whether the recording was stopped, hit [maxDurationMs], or failed.
     */
    @SuppressLint("MissingPermission") // CAMERA checked by caller; no audio => no RECORD_AUDIO.
    fun startRecording(
        maxDurationMs: Long,
        onStarted: (() -> Unit)? = null,
        onFinished: (Result<File>) -> Unit
    ) {
        val capture = videoCapture
        if (capture == null) {
            onFinished(Result.failure(IllegalStateException("camera is not bound — call startCamera() first")))
            return
        }
        if (isRecording) {
            onFinished(Result.failure(IllegalStateException("already recording")))
            return
        }

        // The clip is named by a fresh video id, per the Stage 0 specification. A UUID rather than a
        // timestamp because two captures inside the same millisecond would collide on a clock, and
        // because the id is what the clip is referred to by from here on.
        // Written into staged_videos/, NOT recorded_videos/. The processing lane treats every .mp4 in
        // recorded_videos/ as finished work and polls it every 5 s, so a clip that is still being
        // written cannot live there — the poller would claim a file the encoder has not finalized and
        // Stage 1 would extract frames from a truncated container. The move into recorded_videos/ is
        // what publishes the clip, and it happens once, after the finalize event. See [promoteToRecorded].
        val videoId = UUID.randomUUID().toString()
        val outFile = File(stagedVideosDir(context), "$videoId.mp4")
        val options = FileOutputOptions.Builder(outFile)
            .setDurationLimitMillis(maxDurationMs)
            .build()

        isRecording = true
        finishHandler = onFinished
        try {
            activeRecording = capture.output
                .prepareRecording(context, options) // no withAudioEnabled() → video-only
                .start(mainExecutor) { event ->
                    // The whole event callback is contained. It runs on CameraX's main executor with no
                    // frame of ours below it, and it invokes the caller's `onStarted` and `onFinished` —
                    // which freeze a board, write a sidecar, move a file and touch two databases. A
                    // throw out of any of that would be a process death at the moment a clip completes,
                    // which is the worst available time: the footage is on disk and nothing would know.
                    crashproof(SOURCE, ContainedFaults.CAMERA, "VideoRecordEvent") {
                        when (event) {
                            is VideoRecordEvent.Start -> {
                                Log.d(
                                    TAG,
                                    "Recording started → ${outFile.absolutePath} (cap ${maxDurationMs}ms)"
                                )
                                onStarted?.invoke()
                            }

                            is VideoRecordEvent.Finalize -> {
                                // State is cleared BEFORE the handler is invoked, so a handler that
                                // fails still leaves this object idle and able to record again.
                                isRecording = false
                                activeRecording = null
                                val handler = finishHandler
                                finishHandler = null
                                handler?.invoke(resultOf(event, outFile))
                            }

                            else -> Unit
                        }
                    }
                }
        } catch (t: Throwable) {
            // `prepareRecording` and `start` throw for a camera unbound underneath us, an output file
            // the encoder cannot open, and a CAMERA grant revoked mid-capture.
            //
            // The unwind is the important half: leaving [isRecording] set after a failed start would
            // make every later call refuse with "already recording" for the life of this object — the
            // same latch the lane's deadlines exist to break, one layer further down. And the caller is
            // told through `onFinished`, because it has a deadline armed that nothing else will answer.
            Log.e(TAG, "could not start the recording", t)
            isRecording = false
            activeRecording = null
            finishHandler = null
            discardStaged(outFile)
            crashproof(SOURCE, ContainedFaults.CAMERA, "onFinished (start failed)") {
                onFinished(Result.failure(t))
            }
        }
    }

    /**
     * Turns a finalize event into the clip or the failure.
     *
     * `ERROR_DURATION_LIMIT_REACHED` and `ERROR_FILE_SIZE_LIMIT_REACHED` are reported by CameraX as
     * errors and are not: the recording was stopped by a limit WE set, and the file it wrote is
     * complete and playable. Treating them as failures would throw away every clip captured by a
     * trigger that never called stop — which is the case the cap exists to serve.
     */
    private fun resultOf(event: VideoRecordEvent.Finalize, outFile: File): Result<File> {
        val benign = event.error == VideoRecordEvent.Finalize.ERROR_NONE ||
            event.error == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED ||
            event.error == VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED
        if (!benign) {
            Log.w(TAG, "Recording finalized with error ${event.error}", event.cause)
            // Delete the part-file. It is still in staged_videos/, so nothing else can be holding a
            // reference to it and nothing downstream has seen it — which is exactly why deleting here
            // is safe now and was not before staging existed: this file used to be written straight
            // into recorded_videos/, where the poller would pick up a truncated container.
            discardStaged(outFile)
            return Result.failure(IOException("Recording failed with CameraX error ${event.error}", event.cause))
        }
        if (event.error != VideoRecordEvent.Finalize.ERROR_NONE) {
            Log.i(TAG, "Recording ended on a limit (${event.error}) — the clip is complete")
        }
        val file = event.outputResults.outputUri.path?.let { File(it) } ?: outFile
        return if (!file.exists() || file.length() == 0L) {
            discardStaged(file)
            Result.failure(IOException("Recorded file missing or empty: $file"))
        } else {
            Result.success(file)
        }
    }

    /**
     * Asks the in-flight recording to end. Fire-and-forget — the clip arrives through the `onFinished`
     * callback given to [startRecording]. A no-op when nothing is recording.
     */
    fun stopRecording() {
        val recording = activeRecording
        if (recording == null) {
            Log.d(TAG, "stopRecording ignored — nothing is recording")
            return
        }
        // Guarded: `stop` throws on a recording CameraX has already finalized, which is a race the lane
        // can lose legitimately — a named stop arriving in the same millisecond as the duration cap.
        // The clip still arrives through the finalize event either way, so there is nothing to repair.
        runCatching { recording.stop() }
            .onFailure { Log.w(TAG, "the recording would not stop — waiting for its finalize event", it) }
    }

    /**
     * Releases all camera resources.
     *
     * An in-flight recording is stopped first and its callback still fires, so a clip is never
     * abandoned half-written on disk with nobody knowing it exists.
     */
    fun release() {
        // Every step guarded independently, and in this order on purpose: this is called from
        // `onDestroy` and from the lane's abort paths, so it is reached *while something is already
        // going wrong. A camera that will not release must not stop the fields below being cleared —
        // a stale `videoCapture` is what a later capture would try to record through.
        runCatching { orientationListener.disable() }
        lastRotation = Int.MIN_VALUE
        runCatching { activeRecording?.stop() }
        activeRecording = null
        isRecording = false
        runCatching { cameraProvider?.unbindAll() }
            .onFailure { Log.w(TAG, "could not unbind the camera", it) }
        cameraProvider = null
        videoCapture = null
    }

    companion object {
        private const val TAG = "CameraCapture"

        /** Shown under this class's rows on the errors screen. */
        const val SOURCE = "Camera"

        /**
         * Where recorded clips are written: app-private FILES storage, **not the cache**.
         *
         * A clip arrives here by a move out of [STAGED_VIDEOS_DIR] once it is finalized, and sits here
         * until the poller reaches it, which can be several videos' worth of processing later. The cache was not a safe place for that: Android
         * reclaims `cacheDir` under storage pressure, and the pipeline's own leftovers made that
         * likely (see [com.terminalscout.app.prod.pipeline.RunScratch]), so a clip could be deleted while
         * still waiting its turn — the run then failed with "video file is missing or empty".
         *
         * The system never reclaims files storage, which makes clearing up OUR job: a clip is deleted as
         * soon as it has been processed, together with its metadata sidecar. Nothing else sweeps this
         * directory, because everything in it is work that is still owed.
         */
        fun recordedVideosDir(context: Context): File =
            File(context.filesDir, RECORDED_VIDEOS_DIR).also { ensureDir(it) }

        /** Directory name, per the Stage 0 specification. */
        /**
         * Recording resolution, best first — CameraX takes the first rung the device can offer.
         *
         * **FHD, not UHD.** See the note at the `Recorder.Builder` in `bindCamera` for the full
         * reasoning; in short, a clip is now uploaded rather than deleted after processing, so its size
         * is mobile data on every trigger, and FHD is a quarter of UHD's bytes while still putting a
         * 30 m board at ~99 px against `ClarityGate`'s 32 px floor.
         *
         * The list stays a LADDER rather than a single quality: `fromOrderedList` falls through to the
         * next rung on a device that cannot record the first, and a capture that fails to start is
         * worth more than a capture at the resolution somebody preferred. SD stays on the end for that
         * reason alone — as a last resort it beats no recording, even though a distant board at SD sits
         * on the clarity gate's threshold.
         *
         * Raising this back to UHD is a one-line change if distant boards start being missed.
         */
        private val CAPTURE_QUALITY_LADDER =
            listOf(Quality.FHD, Quality.HD, Quality.SD)

        const val RECORDED_VIDEOS_DIR = "recorded_videos"

        /**
         * Where a clip is written **while it is being recorded**: app-private FILES storage, beside
         * [RECORDED_VIDEOS_DIR] and on the same volume, so publishing a clip is a rename and never a copy.
         *
         * This directory exists because `recorded_videos/` is the queue — [ProcessingLane] polls it every
         * five seconds and every `.mp4` in it is, by definition, finished work that is owed. A file the
         * encoder is still writing satisfies that test and satisfies nothing else: the poller claims it,
         * `MediaMetadataRetriever` opens a container with no moov atom, and the run fails on a clip that
         * would have been perfectly good four seconds later. Two directories make "being written" and
         * "finished" different *places* rather than a timing question.
         *
         * Nothing but a live recording is ever in here. A `.mp4` found here at startup is the wreckage of
         * a process that died mid-clip — unfinalized, and not recoverable — so it is swept, not promoted.
         */
        const val STAGED_VIDEOS_DIR = "staged_videos"

        /** @see STAGED_VIDEOS_DIR */
        fun stagedVideosDir(context: Context): File =
            File(context.filesDir, STAGED_VIDEOS_DIR).also { ensureDir(it) }

        /**
         * Makes a directory if it is not there, and never throws.
         *
         * `mkdirs` answers SecurityException on a volume this app may not write, and both callers above
         * are on hot paths — the processing lane's five-second poll, and the recorder's output path. A
         * directory that cannot be created is reported by the write that follows it, which is the thing
         * that actually knows what it was trying to do.
         */
        private fun ensureDir(dir: File) {
            runCatching { if (!dir.isDirectory) dir.mkdirs() }
                .onFailure { Log.e(TAG, "could not create ${dir.name}/", it) }
        }

        /**
         * Publishes a finished clip by moving it from `staged_videos/` into `recorded_videos/`, returning
         * where it now is.
         *
         * A rename, not a copy: both directories are under `filesDir`, so this is one atomic-enough
         * metadata operation on the same volume rather than several hundred megabytes read and rewritten.
         * It is also what makes the appearance of a clip in the queue a single event — the poller sees a
         * whole file or no file, never a partial one, which a copy could not promise.
         *
         * The name is preserved, so the video id and its metadata sidecar (keyed by file name) still
         * agree after the move.
         *
         * ### Why this suspends
         * It is called from the recorder's finalize callback, which CameraX delivers on the MAIN THREAD.
         * The rename is cheap enough there, but the copy fallback below is not: a UHD clip is a few
         * hundred megabytes, and reading and rewriting that on the UI thread is the exact failure the
         * deleted `saveClipToCameraRoll` was removed for — an ANR at the moment a capture completes, on
         * the one device this app has already been killed on for load. So the whole thing runs on
         * [Dispatchers.IO] and the caller waits for it from a coroutine instead of blocking a looper.
         *
         * @throws IOException if the clip could not be moved. That is deliberately loud: the caller must
         *   not report a queued clip that is not in the queue. The staged file is left where it is, so a
         *   failure loses the position in the pipeline and not the footage.
         */
        suspend fun promoteToRecorded(context: Context, staged: File): File =
            withContext(Dispatchers.IO) {
                val published = File(recordedVideosDir(context), staged.name)
                if (staged.renameTo(published)) {
                    Log.i(TAG, "clip published ${staged.name} → $RECORDED_VIDEOS_DIR/")
                    return@withContext published
                }
                // renameTo returns false without saying why. A same-volume move should not fail, so the
                // fallback is a copy — slow and worth complaining about, but it publishes the clip. The
                // staged original is only removed once the copy is on disk and the right length.
                Log.w(TAG, "rename failed for ${staged.name} — falling back to a copy")
                staged.copyTo(published, overwrite = true)
                if (!published.exists() || published.length() != staged.length()) {
                    published.delete()
                    throw IOException("could not publish ${staged.name} into $RECORDED_VIDEOS_DIR")
                }
                discardStaged(staged)
                published
            }

        /** Removes a staged file that will never be published. Never throws — there is nothing to do. */
        fun discardStaged(staged: File) {
            runCatching { if (staged.exists() && !staged.delete()) Log.w(TAG, "could not delete ${staged.name}") }
        }

        /**
         * Deletes anything left in `staged_videos/`, returning how many files went. Call at startup.
         *
         * Every one of them is an unfinalized recording from a process that died while the camera was
         * rolling — a crash, an OOM kill, a battery pull. CameraX writes the `moov` atom at finalize, so
         * these containers have no index and no later stage can read them; promoting them would feed the
         * pipeline a guaranteed failure, and leaving them would grow a directory nothing ever reads.
         *
         * Sweeping the staged directory is safe in a way sweeping `recorded_videos/` never is: a file
         * here has not been published, so nothing owns it and no queue is owed it.
         */
        fun sweepStaged(context: Context): Int = try {
            val stale = stagedVideosDir(context).listFiles()?.filter { it.isFile } ?: emptyList()
            var removed = 0
            stale.forEach { if (runCatching { it.delete() }.getOrDefault(false)) removed++ }
            if (removed > 0) Log.w(TAG, "swept $removed unfinalized clip(s) from $STAGED_VIDEOS_DIR/")
            removed
        } catch (t: Throwable) {
            // Called from the capture lane's `start`, which is called from the service's `ensureStarted`.
            // A directory listing that fails must not stop the triggers attaching — an unswept staged
            // file costs disk, a lane that never attached costs every station.
            Log.e(TAG, "could not sweep $STAGED_VIDEOS_DIR/", t)
            0
        }

        // sweepOrphanedCaptures used to live here. It existed because the queue was a separate list
        // that could disagree with the directory: a clip nobody claimed had to be swept, and a clip the
        // queue still owed had to be spared — which meant reading VideoQueueStore.referencedPaths().
        //
        // The directory IS the queue now. Every .mp4 in recorded_videos/ is work that is owed, by
        // definition, and a processed clip is deleted along with its metadata sidecar. There is nothing
        // left for a sweep to identify, so removing it is not a regression: the condition it tested can
        // no longer arise.
    }
}
