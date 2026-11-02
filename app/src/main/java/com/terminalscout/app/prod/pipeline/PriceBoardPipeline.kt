package com.terminalscout.app.prod.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import com.terminalscout.app.prod.TestClips
import com.terminalscout.app.prod.VideoMetadataStore
import com.terminalscout.app.prod.download.DebugUploader
import com.terminalscout.app.prod.upload.LocationFix
import com.terminalscout.app.prod.upload.LocationProvider
import com.terminalscout.app.prod.upload.ReadingPayload
import com.terminalscout.app.prod.upload.StationDirectory
import com.terminalscout.app.prod.upload.TestClipLocations
import com.terminalscout.app.prod.upload.UserStore
import com.terminalscout.app.prod.config.DeviceConfig
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import java.io.File

/** The pipeline's coarse stages, run one after another over ALL frames (batch, not per-frame). */
enum class PipelineStage(val displayName: String) {
    SEGMENT("Stage 1 · Frame Segmentation"),
    DETECT("Stage 2 · Board Detection"),
    PREP("Stage 3 · Image Processing"),
    OCR("Stage 4 · OCR"),
    RECONSTRUCT("Stage 5 · Consolidation"),
    ENRICH("Stage 6 · API Output")
}

/** One fuel type's aggregated result across all passing frames. */
data class AggregatedReading(
    val fuelType: String,
    val price: String,
    val votes: Int,
    val totalReads: Int,
    val confidence: Double = 0.0,
    /** Board row (1-based from the top), -1 if unknown. Used as an identity anchor. */
    val rowNum: Int = -1,
    /**
     * The Stage 3 box letter this row settled on, kept SEPARATE from [fuelType].
     *
     * They used to be the same field: with no fuel name to report, the letter was put in [fuelType] so
     * the payload said something. Now Stage 3's layout can read the real name off the board, and the two
     * have to be told apart — otherwise a row named "B" looks exactly like a row named "B7", and the
     * station's list would be treated as already-satisfied.
     */
    val box: String = ""
)

/**
 * A stage threw, so the run is over.
 *
 * Stages 1-4 used to swallow their own exceptions and carry on over an empty input: a detector that
 * crashed produced no boards, a prep that crashed produced no canvases, a reader that crashed produced
 * no digits — and every one of those is indistinguishable, in the payload, from a forecourt whose board
 * genuinely said nothing. The run then reported itself COMPLETE, the empty body was uploaded, and the
 * clip was deleted behind it. There is no recovering from that downstream: nothing can contradict a
 * reading the server has already accepted.
 *
 * So a stage fault now ends the run. The flow throws this, the caller reports it and leaves the clip on
 * disk, and the capture is read again rather than answered wrongly. [PipelineEvent.Failed] is emitted
 * first, so a screen and the errors list see WHICH stage went.
 */
class PipelineStageException(
    /** The stage that threw, in the same words [PipelineEvent.Failed] uses: segment/detect/prep/ocr. */
    val stage: String,
    cause: Throwable
) : Exception("Stage $stage failed: ${cause.message ?: cause.javaClass.simpleName}", cause)

/**
 * Events streamed to the debug dashboard as the pipeline runs.
 */
sealed class PipelineEvent {
    /**
     * Emitted once, as soon as Stage 1 has somewhere to write: the id of the cache sub-trees this run
     * uses for its frames/crops/prepped images (see [RunScratch]).
     *
     * The caller needs it to DELETE those images once the run's artifacts have been uploaded. It is
     * announced early, and separately from the result, precisely so a run that later fails is still
     * cleaned up — the failures are the runs that left the most behind.
     */
    data class ScratchOpened(val videoId: String) : PipelineEvent()

    /** A stage has begun. [atMs] is elapsed time since the run started. */
    data class StageStarted(val stage: PipelineStage, val atMs: Long) : PipelineEvent()

    /** A stage has finished. [atMs] is elapsed time since the run started; [durationMs] its length. */
    data class StageFinished(val stage: PipelineStage, val atMs: Long, val durationMs: Long) : PipelineEvent()

    /** Per-frame sub-progress within a stage (e.g. "3/8 read" during the OCR stage). */
    data class StageProgress(val stage: PipelineStage, val current: Int, val total: Int) : PipelineEvent()

    /** Stage 1: one extracted frame. */
    data class FrameSegmented(val frameIndex: Int, val bitmap: Bitmap) : PipelineEvent()

    /** Stage 2: the board crop kept after detection. */
    data class FrameCropped(val frameIndex: Int, val crop: Bitmap) : PipelineEvent()

    /** Stage 3: a frame that cleared the clarity gate, carrying its prepped crop + clarity score. */
    data class FrameScored(
        val frameIndex: Int,
        val crop: Bitmap,
        val clarityScore: Double
    ) : PipelineEvent()

    /**
     * Stage 4: a board image that was read — the canvas sent, the JSON, and one entry per
     * price region on it bound to the digits read inside that region.
     */
    data class FrameRead(
        val frameIndex: Int,
        val crop: Bitmap,
        val rawJson: String,
        val readings: List<RegionReading>
    ) : PipelineEvent()

    /** Stage 5: ONE line of the consolidator agent's reasoning, streamed live as it is produced. */
    data class ConsolidationReasoningLine(val line: String) : PipelineEvent()

    /** Stage 5/6: the reconstructed readings JSON + parsed rows + the enriched API payload + tallies. */
    data class AggregatedResult(
        /**
         * Which physical board this result is for, and how many the run found. A video can show more
         * than one price post, and each is prepped, read, consolidated and enriched on its own — so a
         * two-board run emits TWO of these, and a consumer that enqueues an upload per result gets one
         * reading per board.
         */
        val boardId: Int = 0,
        /**
         * This board's place in the run, 1-based — "board 2 of 2".
         *
         * Separate from [boardId] because that is the TRACKER's id, numbered over every track it found
         * including the weak ones that are never processed. A run whose first track was a reflection
         * hands its only real board id 1, and labelling that "board 2 of 1" is how a display built on
         * the id alone reads. The id still identifies the artifacts on disk (`_bNN`); this counts the
         * results.
         */
        val boardOrdinal: Int = 1,
        val boardCount: Int = 1,
        /** Stage 5 cleaned readings JSON: `{"readings":[{row_num,fuel_type,price}]}`. */
        val json: String,
        /** [json] parsed into rows, for the dashboard's per-fuel display. */
        val readings: List<AggregatedReading>,
        /** Stage 6 enriched, API-ready payload JSON. */
        val framesTotal: Int,
        val framesPassed: Int,
        val framesDiscarded: Int
        // No canvas paths here. They used to be carried so this event's consumer could store the run,
        // which it no longer does — the run is stored once, from [CaptureResult], with every board's
        // canvases together.
    ) : PipelineEvent()

    /**
     * The run's ONE readings-API body, emitted after every board has been through Stages 3-6.
     *
     * This is what gets uploaded. [AggregatedResult] arrives per board and is what the dashboard draws
     * as it goes; this arrives once, carries every board's rows in its `boards` array, and is the only
     * event a consumer should enqueue {DASH} see [ReadingPayload] for why the capture, and not the board, is
     * the unit that gets POSTed.
     */
    data class CaptureResult(
        /** The video's id. Also names the run's frames/crops/prepped folders and its debug upload. */
        val captureId: String,
        /** How many boards the capture produced. Always matches `boards.length` in [payload]. */
        val boardCount: Int,
        /** The enriched, API-ready payload as pretty JSON. */
        val payload: String,
        /**
         * Whether every stage that had to run, ran.
         *
         * False when a board's OCR could not reach the reader — the run still produces a well-formed
         * payload, but one with no prices in it, and an empty answer is indistinguishable from a board
         * that genuinely had nothing readable on it. A consumer must NOT send an incomplete body: the
         * capture is owed, not finished. See [PriceBoardPipeline.run].
         */
        val complete: Boolean = true,
        /**
         * Every board's read canvases, in board order. Kept with the run so the dashboard can still show
         * what the boards looked like long after the cache is gone.
         */
        val ocrFramePaths: List<String> = emptyList()
    ) : PipelineEvent()

    data class Failed(val stage: String, val error: Throwable) : PipelineEvent()
}

/**
 * What one board contributed to its capture: the entry it puts in the body's `boards` array, and the
 * on-disk canvases the reader was given for it.
 *
 * The paths are kept BESIDE the payload entry rather than inside it. They are local file names of a
 * cache that is deleted as soon as the run's uploads finish {DASH} useful to the dashboard, meaningless to
 * the API, and a payload type that carries them invites one to be serialised by accident.
 */
private data class BoardOutcome(
    val block: ReadingPayload.BoardReading,
    val canvasPaths: List<String>,
    /** False when this board's OCR never reached the reader — see [PipelineEvent.CaptureResult.complete]. */
    val readerReached: Boolean = true
)

// NOTE: BoardDetector, MlKitGenericBoardDetector and VideoFrameExtractor are the REAL
// implementations defined elsewhere in this package.

/**
 * Stage-wise (batch) pipeline. Each stage runs to completion over ALL frames before the next
 * begins, so the dashboard can show a clean per-stage timeline with timing:
 *
 *   Stage 1 SEGMENT     — extract every frame (15 fps) to disk. See [VideoFrameExtractor].
 *   Stage 2 DETECT      — YOLO board detection as an escalation ladder (shared full-frame high/low
 *                         output → shifted windows → sparse probe or targeted re-sample),
 *                         crop every surviving board at native
 *                         size, and store each crop. See [BoardDetector].
 *   Stage 3 PREP        — clarity-gate each crop (drop blurry ones), enhance survivors (grey → CLAHE
 *                         → unsharp), letterbox onto the region model's 640 canvas, ghost-dim and mark
 *                         the price regions, and store that canvas. See [ImagePrepStage].
 *   Stage 4 OCR         — the reader reads the DIGITS inside each lettered price region on the
 *                         canvas, bound back to that region's box → a frame/box/digits mapping.
 *                         See [OcrStage].
 *   Stage 5 CONSOLIDATE — reduce the per-frame OCR to one consensus price per row. See [ConsensusConsolidator].
 *   Stage 6 ENRICH      — build the API-ready payload from the readings + config + GPS. See [EnrichmentStage].
 *
 * Each stage emits [PipelineEvent.StageStarted]/[PipelineEvent.StageFinished] with elapsed-time
 * flags so the UI can show start/end markers and how long each stage took.
 */
class PriceBoardPipeline(
    private val context: Context,
    private val boardDetector: BoardDetector = MlKitGenericBoardDetector(context),
    private val ocrResultStore: OcrResultStore = OcrResultStore(context)
) {

    private val frameExtractor = VideoFrameExtractor(context)
    private val imagePrepStage = ImagePrepStage(context)
    private val ocrStage = OcrStage()
    private val enrichmentStage = EnrichmentStage()
    /** Stage 5's last word: a cloud model checks the consolidated answer against the board images. */
    private val consolidationReviewer = ConsolidationReviewer()

    /**
     * The pipeline's single entry point: runs over one video file location — a clip recorded from
     * the rear camera OR a bundled test video (both are just a [File] on disk). Stage 1 calls
     * [VideoFrameExtractor.extractFrames] to sample the video into PNG frames on disk; Stage 2 hands
     * that result to [BoardDetector.detectBoards], which walks its escalation ladder over the frames,
     * crops every board it finds, and returns the stored crops for the downstream clarity and OCR
     * stages. The extractor also goes in as the ladder's refiner, so its last rung can pull extra
     * frames out of the same clip.
     */
    /**
     * Whether to decode the preview bitmaps the dashboard draws.
     *
     * Set false when nothing is observing — see [com.terminalscout.app.prod.service.FleetBus.hasObservers].
     * The stage/progress/result events are unaffected; only the image-carrying ones stop, because they
     * are the only ones with a cost worth avoiding. Volatile because the lane flips it between runs from
     * a different thread than the pipeline reads it on.
     */
    @Volatile
    var emitPreviews: Boolean = true

    fun run(
        videoFile: File,
        samplesPerSecond: Int = 15,
        runLabel: String? = null,
        /**
         * What was known when the clip was RECORDED — see [VideoMetadataStore].
         *
         * The capture's timestamp, the vehicle's fix and the board it was aimed at all come from here,
         * because all three are facts about the recording and none of them can be re-derived at
         * processing time: a clip is read minutes after it was filmed on a good day and hours after it
         * on a backlog, by which point the clock and the GPS describe a different place and a different
         * moment. Null for a sideloaded clip with no sidecar, which falls back to the live fix.
         */
        metadata: VideoMetadataStore.Metadata? = null
    ): Flow<PipelineEvent> = flow {
        val startMs = SystemClock.elapsedRealtime()

        // The replay fixture from the LAST run. Optional in both directions — it is a debugging
        // convenience, not an input — so a clear that fails leaves stale rows in a file nothing on the
        // device reads, and losing the capture over it would be absurd.
        optional("clearing the previous run's replay fixture", Unit) { ocrResultStore.clear() }
        // No native engine to rebuild for this run:
        // (No native engine to reset: Stage 4 reads off-device in this build, so there is no
        // LiteRT-LM instance to tear down between runs.)

        // DEBUG UPLOAD: one folder per run under debugdirectory/, mirroring the local cache layout.
        // Artifacts are pushed (fire-and-forget, background) as each stage produces them. The caller
        // may pass a [runLabel] so a recorded clip in the camera roll shares this folder's name.
        // Refresh the station mapping for this run. Bounded and ignored on failure: it decides only the
        // fuel NAMES at the very end, so it must never be a reason a capture does not get read. A run
        // started with no signal falls through to whatever copy is already on disk.
        if (EnrichmentStage.STATION_LOOKUP_ENABLED) {
            // "Bounded and ignored on failure" is what the note above promises, and the timeout only
            // delivered half of it: `withTimeoutOrNull` answers null for a call that ran too long and
            // rethrows one that threw. So a refresh that failed outright — no signal mid-request, a
            // 500, a malformed body, an unwritable database — ended the capture before Stage 1 opened
            // the clip, over a step that decides nothing but the fuel NAMES at the very end.
            optional("the station-name refresh", null) {
                withTimeoutOrNull(STATION_REFRESH_TIMEOUT_MS) { StationDirectory.refresh(context) }
            }
        }

        // A label, and there is always one. `newRunLabel` reads the clock and the filesystem, so it can
        // fail; a run whose artifacts land in a folder called "unlabelled" is a debug folder with an
        // awkward name, which is not a reason to lose a reading.
        val debugRun = runLabel ?: optional("naming this run's debug folder", "unlabelled") {
            DebugUploader.newRunLabel()
        }
        // A sideloaded test clip is opted out of the debug container before a single artifact is staged:
        // only what a camera actually captured belongs in the fleet's evidence. Recorded here rather than
        // at each enqueue call because a run's artifacts are staged from seven places in this file and
        // from the pending-OCR worker hours later, and one missed site is a test run in the container.
        // Fails CLOSED, to `true`. A read that threw has not established that this clip came off a
        // camera, and the container's whole rule is that only what a camera captured belongs in it —
        // so an unknown answer suppresses the upload. The cost of being wrong that way is one real
        // capture whose evidence is missing while its reading is filed as normal; the cost the other
        // way is test footage in the fleet's evidence, which nothing downstream can tell from the real
        // thing. See `ERROR_HANDLING.md` §3.
        val testClip = optional("the test-clip check", true) { TestClips.isTestClip(context, videoFile) }
        if (testClip) {
            optional("suppressing the debug container for a test clip", Unit) {
                DebugUploader.disableForRun(context, debugRun)
            }
        }
        Log.d(
            TAG,
            "debug artifacts → debugdirectory/$debugRun (upload enabled=${DebugUploader.isEnabled}" +
                if (testClip) ", SUPPRESSED — test clip)" else ")"
        )

        // ---------------- Stage 1: SEGMENT ----------------
        val segStart = SystemClock.elapsedRealtime() - startMs
        emit(PipelineEvent.StageStarted(PipelineStage.SEGMENT, segStart))
        // Function 2: sample the video into PNG frames on disk. Keep the result in a variable so it
        // can be handed straight to the board detector in Stage 2.
        val extraction = try {
            frameExtractor.extractFrames(videoFile, samplesPerSecond)
        } catch (c: CancellationException) {
            // Not a fault: the service is shutting down or the scope was cancelled. Rethrown ahead of
            // the catch below, which would otherwise report a shutdown as a broken stage.
            throw c
        } catch (t: Throwable) {
            // A clip that cannot be sampled has no answer to give. Ending the run here leaves it on
            // disk, which is the only place it can be read from again.
            //
            // `Throwable` rather than `Exception`, and the same change is made at every stage below.
            // This stage decodes, rotates and compresses a thousand UHD bitmaps, so its characteristic
            // failure is `OutOfMemoryError` — an `Error`, which used to slip past this catch and reach
            // the collector with no [PipelineEvent.Failed] behind it. The run ended either way; the
            // difference is that nothing on the device said WHICH stage went, which is the one thing
            // this catch exists to record.
            abortStage("segment", t)
        }
        // Tell the caller where this run's images live, so it can delete them once they are uploaded.
        emit(PipelineEvent.ScratchOpened(extraction.videoId))
        // Show each extracted frame in the dashboard's "segmented frames" strip.
        extraction.frames.forEach { frameFile ->
            decodePreview(frameFile.file.absolutePath)?.let { bmp ->
                emit(PipelineEvent.FrameSegmented(frameFile.index, bmp))
            }
        }
        run {
            val end = SystemClock.elapsedRealtime() - startMs
            emit(PipelineEvent.StageFinished(PipelineStage.SEGMENT, end, end - segStart))
        }

        if (extraction.frames.isEmpty()) {
            // A container that opened and yielded nothing: a truncated clip, or one whose duration the
            // platform reports as zero. Reported as the segment stage failing rather than as a bare
            // throw, so the screen names the stage like every other fault.
            abortStage(
                "segment",
                IllegalStateException("no frames were extracted from ${videoFile.name}")
            )
        }

        // NO Stage 1 upload — the clip does not go up from HERE. It used to: `video.mp4` was staged at
        // this point, and staging is a byte-for-byte COPY into `debug_outbox/`, so a few hundred
        // megabytes of clip became a few hundred more at the worst possible moment — the run is about to
        // write a couple of gigabytes of frames, and the outbox has no size cap and no age cut-off by
        // design. On a device with a backlog that was a second copy of every queued clip, sitting on the
        // volume the frames need.
        //
        // The clip still reaches the container; it is handed over at the END of the run instead, by a
        // MOVE rather than a copy — see `DebugUploader.enqueueClip`, called from `ProcessingLane`'s
        // `finally`. A rename within `filesDir` duplicates nothing, and by then the run's frames are
        // already gone, so neither half of the objection above applies. Nothing about that belongs in
        // this stage: Stage 1 needs the clip, and a stage that uploaded it would be uploading a file it
        // is still reading.


        // ---------------- Stage 2: DETECT ----------------
        val detStart = SystemClock.elapsedRealtime() - startMs
        emit(PipelineEvent.StageStarted(PipelineStage.DETECT, detStart))
        // Hand the whole extraction to the board detector, which shares model output between the
        // high- and low-confidence gates, then tries shifted windows and sparse or targeted re-sampling.
        // Every surviving box is cropped at native size and written under crops/<videoId>/.
        // The extractor is passed as the refiner so the last rung can ask Stage 1 for the extra frames
        // it needs; those land in the same frames/<videoId>/ folder and are uploaded below.
        val detection = try {
            boardDetector.detectBoards(extraction, frameExtractor)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            // NOT an empty detection. A detector that threw has not established that there was no board
            // here — it has established nothing at all — and carrying on would send `board_count: 0`,
            // which is a claim about the forecourt this run is in no position to make.
            //
            // `Throwable` because this stage runs TFLite over every frame: an `OutOfMemoryError` on the
            // input buffers and a `NoClassDefFoundError` or `UnsatisfiedLinkError` from a native
            // delegate that would not load are all `Error`, and all three describe a detector that did
            // not run — which is exactly the claim this catch is here to refuse to make.
            abortStage("detect", t)
        }
        val detections = detection.detections
        // Dashboard: any frame the re-sample pass created belongs in the segmented strip too.
        detection.refineFrames.forEach { frameFile ->
            decodePreview(frameFile.file.absolutePath)?.let { bmp ->
                emit(PipelineEvent.FrameSegmented(frameFile.index, bmp))
            }
        }
        // Dashboard: show each detected board crop. (Stage 3 re-reads the crops from disk itself.)
        for ((i, d) in detections.withIndex()) {
            emit(PipelineEvent.StageProgress(PipelineStage.DETECT, i + 1, detections.size))
            decodePreview(d.cropPath)?.let { crop ->
                emit(PipelineEvent.FrameCropped(d.frameIndex, crop))
            }
        }
        run {
            val end = SystemClock.elapsedRealtime() - startMs
            emit(PipelineEvent.StageFinished(PipelineStage.DETECT, end, end - detStart))
        }

        // NOTE: no Stage 2 upload. Every crop went up here before, at native size; only the crops behind
        // an actual reading are worth the bytes, and which those are isn't known until Stage 4 has run.

        // ---------------- Stages 3-6: ONCE PER BOARD ----------------
        // One video can show more than one price post — a forecourt with two brands, or a board and its
        // neighbour across the road — and every stage from here on assumes a single board: Stage 3 fits
        // every canvas to ONE remembered tag order, and Stage 5 pools every reading into ONE price list.
        // Run them over a mixed set and two boards' panels are named against each other and their prices
        // blend into one answer.
        //
        // So the run splits here. Each board gets its own prep, its own OCR budget and its own
        // consolidation — which is why the reader's floor in Stage 2 is per board too: a bright board
        // and a dim one both need enough images of their own. They CONVERGE again at Stage 6: the
        // capture sends one body carrying every board, because one video is one capture.
        //
        // WEAK tracks are not carried downstream. A track holding fewer than
        // [BoardTracker.WEAK_TRACK_DETS] detections is the shape a reflection or a roadside sign makes,
        // not the shape a board the car drove past makes, and reading one spends the OCR budget on noise
        // and risks uploading a price that came from a road sign.
        // ---------------- Capture-level facts, established ONCE ----------------
        // Everything except the prices belongs to the CAPTURE and not to the board: one device filmed
        // one place at one moment, however many posts happened to be in shot. Resolving these per board
        // ran the same station lookup twice for one answer, asked the OS for a second GPS fix, and
        // stamped the two boards with timestamps a second or two apart — which is precisely what made
        // two bodies from one video look like one body submitted twice.
        // Fails CLOSED, by ABORTING — the only capture-level fact here that does.
        //
        // This is the device's identity, and it is stamped onto the body. `UserStore.load` resolves its
        // columns with `getColumnIndexOrThrow` and has no catch anywhere in its path (see
        // `ERROR_HANDLING.md` §6), so a `user.db` bad enough to throw gets this far — and
        // `loadOrDefault`'s name is about a MISSING record, not an unreadable one. Falling back to the
        // default here would upload a reading the API cannot attribute to a vehicle, which is the one
        // outcome `UserStore.exists()` failing closed in `AppSetup` exists to prevent; letting it
        // through the pipeline would go round the back of that gate. The clip stays on disk and the
        // errors screen names the stage, so the reading is recoverable once the identity is repaired.
        val config = try {
            UserStore(context).loadOrDefault()
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            abortStage("provenance", t)
        }
        // A replayed test clip stands in for a capture somewhere else entirely, so it supplies the fix
        // recorded when it was originally shot — otherwise the station step could never be exercised off
        // a desk. Scoped to the testclips directory; a real recording always uses the live fix.
        // WHERE the vehicle was, in order of what each source actually knows:
        //
        //  1. a replayed test clip's recorded fix, so the station step can be exercised off a desk;
        //  2. the sidecar's VEHICLE fix — where the recorder was when it rolled, taken at that instant
        //     by the capture lane;
        //  3. a live fix, only for a clip with no sidecar at all.
        //
        // (3) used to be (2)'s job, and it is wrong for the same reason the clock below was: a clip is
        // read minutes after it was filmed at best and hours after it on a backlog, so the live fix is
        // where the truck is NOW — the next forecourt, the depot, a motorway thirty kilometres on. The
        // payload then carried a reading of one board stamped with the position of somewhere else.
        //
        // Each source is guarded SEPARATELY, and that is the point of doing it here rather than once
        // around the chain: these are three independent answers to one question, and a fault in the
        // first must fall through to the second exactly as a null does. One `try` around the whole
        // elvis chain would have let an unreadable test-clip index suppress a perfectly good sidecar
        // fix. Fails OPEN to null at every rung — the type is already nullable and every reader of it
        // handles that, because a clip with no sidecar has always been allowed to have no fix.
        val location = optional("the test clip's recorded fix", null) {
            TestClipLocations.forVideo(context, videoFile)
        }
            ?: optional("the sidecar's vehicle fix", null) { metadata?.vehicleFix() }
            // suspends; null without permission/fix — and now also null when the read itself throws,
            // which a `SecurityException` on a revoked permission does.
            ?: optional("a live GPS fix", null) { LocationProvider(context).currentFix() }
        // WHICH forecourt this was. The sidecar names the board the trigger actually fired on, which is
        // a better answer than re-deriving it: the nearest-station search can only guess from a fix, and
        // by processing time it would be guessing from the wrong one. The GPS match stays as the
        // fallback for a clip whose sidecar named no station — a manual or adb capture.
        //
        // Fails OPEN to null, both rungs, and separately for the same reason the fix above does. An
        // unnamed forecourt is a reading with a position and no station label, which the payload
        // already allows for every clip whose sidecar named nothing; an aborted run is a forecourt the
        // fleet has no reading of at all. The station is a label on the answer, never the answer.
        //
        // `StationDirectory.load` is inside the guard on purpose: it opens `location_map.db`, so it is
        // the likeliest of the three to throw, and a database that will not open must not be able to
        // stop a capture being read.
        val station = optional("the sidecar's station match", null) { metadata?.boardMatch(location) }
            ?: optional("matching the fix to a station", null) {
                enrichmentStage.matchStation(
                    location,
                    if (EnrichmentStage.STATION_LOOKUP_ENABLED) StationDirectory.load(context)
                    else emptyList()
                )
            }
        // WHEN the clip was recorded, not when it was read. `System.currentTimeMillis()` here made the
        // payload's timestamp the moment the pipeline reached this line, so a clip processed after an
        // offline spell reported prices as of hours after they were photographed — and the fleet has no
        // way to tell such a reading from a fresh one.
        val capturedAtMs = metadata?.capturedAtMs?.takeIf { it > 0L } ?: System.currentTimeMillis()
        Log.d(
            TAG,
            "capture provenance: " + if (metadata != null) {
                "sidecar — recorded ${(System.currentTimeMillis() - capturedAtMs) / 1000}s ago" +
                    ", station=${metadata.stationId ?: "none"}" +
                    ", vehicle fix=${if (metadata.vehicleFix() != null) "yes" else "none"}"
            } else {
                "no sidecar — falling back to the clock and the live fix"
            }
        )

        // A capture is a drive-past, not a survey. Three price posts in one clip is already the far end
        // of plausible, and every board past that is a FULL run of Stages 3-6 of its own: its own prep,
        // its own layout call, its own OCR batch, its own consolidation. Past the cap the extras are far
        // likelier to be the detector latching onto a fourth thing than a fourth post the car drove by,
        // so they are dropped here rather than paid for downstream.
        //
        // The cap keeps the STRONGEST tracks, not the first ones seen: a long, well-covered board is the
        // one with material to read, and dropping it because a brief track happened to appear earlier in
        // the clip would be the wrong trade. Time order is restored afterwards, so board 1 of N is still
        // the first board the car passed.
        val strong = detection.strongBoards
        val boards = if (strong.size <= MAX_BOARDS) strong else {
            val kept = strong
                .sortedWith(
                    compareByDescending<TrackedBoard> { it.crops }
                        .thenByDescending { it.detections.size }
                        .thenByDescending { it.meanConfidence }
                )
                .take(MAX_BOARDS)
                .sortedBy { it.firstSeenUs }
            val keptIds = kept.map { it.boardId }.toSet()
            Log.w(
                TAG,
                "board cap: ${strong.size} strong track(s), processing $MAX_BOARDS - " +
                    "kept ${kept.joinToString { it.summary() }}; " +
                    "dropped ${strong.filter { it.boardId !in keptIds }.joinToString { it.summary() }}"
            )
            kept
        }
        if (boards.isEmpty() && detection.boards.isNotEmpty()) {
            Log.w(
                TAG,
                "nothing to process — ${detection.boards.size} track(s), none holding together for " +
                    "${BoardTracker.WEAK_TRACK_DETS} detections"
            )
        }
        if (boards.size > 1) {
            Log.d(TAG, "${boards.size} boards to process separately: ${boards.joinToString { it.summary() }}")
        }
        val enriched = mutableListOf<ReadingPayload.BoardReading>()
        val readCanvases = mutableListOf<String>()
        // Cleared by ANY board whose OCR could not reach the reader. One unread board is enough to make
        // the capture's body incomplete: it would carry the boards that WERE read and silently omit the
        // one that was not, which is a worse answer than none at all.
        var readerReached = true
        /**
         * Cleared by a board whose own stages THREW, as distinct from one the reader could not read.
         *
         * Both make the capture incomplete and both are separate from the other, because they are
         * different faults with different fixes — no signal versus our own image processing breaking —
         * and the log has to be able to say which. Anded into `complete` below either way.
         */
        var allBoardsRan = true
        if (boards.isEmpty()) {
            // Nothing to read, but the run still has to finish: the stages walk their empty inputs, the
            // dashboard's timeline completes, and an empty result is emitted. Without this a run that
            // found no board would simply stop, leaving the UI waiting for a result that never comes.
            //
            // Its entry is DISCARDED on purpose. A capture that found nothing is a real fact about
            // that stretch of road, and `boards: []` with `board_count: 0` states it exactly; an entry
            // with no prices, no `seen_s` and a board id of -1 would instead assert that a board was
            // there and said nothing, which is a different claim and not one this run can make.
            // Contained like the real boards below, for a smaller reason: there is no reading to lose
            // here, but the run still has to reach its `CaptureResult` — that is the whole purpose of
            // this branch — and a throw would leave the dashboard waiting on a result that never comes
            // and the clip retrying a clip that found nothing to read.
            try {
                processBoard(null, 1, 1, detection, extraction, videoFile, station, debugRun, startMs)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                allBoardsRan = false
                Log.e(TAG, "the empty-board pass failed — the capture is reported incomplete", t)
            }
        } else {
            // Numbered by POSITION here, not by the tracker's board id: the id counts every track
            // including the weak ones just dropped, so it is not a count of results.
            for ((index, board) in boards.withIndex()) {
                // ONE board's fault is one board.
                //
                // Stages 3-6 abort by throwing — see [abortStage] — and that throw used to leave
                // `run`, so a two-board capture in which the second board's prep hit a corrupt crop
                // lost the FIRST board too: its prices, its debug artifacts, its place in the
                // dashboard's timeline. Boards are independent by construction (that is why the run
                // splits here at all), so there is no reason the second should be able to spend the
                // first.
                //
                // The capture is still reported INCOMPLETE, which is the part that keeps this honest.
                // A body carrying the boards that worked and silently omitting the one that did not is
                // a worse answer than none — it is the exact failure `readerReached` exists to prevent
                // — so the clip stays on disk and the whole capture is read again. What is gained is
                // not a shortcut past that: it is that the surviving board's evidence is staged, the
                // failed STAGE is named on the errors screen by the [PipelineEvent.Failed] that
                // `abortStage` already emitted, and a board that fails on every retry says so instead
                // of a capture that just never completes.
                val outcome = try {
                    processBoard(
                        board, index + 1, boards.size, detection, extraction, videoFile, station,
                        debugRun, startMs
                    )
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    allBoardsRan = false
                    Log.e(
                        TAG,
                        "board b${board.boardId} (${index + 1} of ${boards.size}) failed — the other " +
                            "boards are still processed and the capture is reported incomplete",
                        t
                    )
                    null
                }
                if (outcome?.readerReached == false) readerReached = false
                if (outcome != null) {
                    enriched += outcome.block
                    readCanvases += outcome.canvasPaths
                }
            }
        }

        // ---------------- The capture's ONE payload ----------------
        // Built here rather than in the loop because it describes the VIDEO: every board's rows on one
        // body, under one timestamp, one location and one station. A board that failed its own stages
        // still appears, with an empty `fuel_prices` — silence about a board that was detected and read
        // is worse than a row saying nothing was recovered from it.
        // Aborts, like the identity read above, and for the same reason: this IS the answer. There is
        // nothing to emit without it, and a `CaptureResult` carrying an empty or half-built body would
        // be enqueued for upload and accepted by the server — a reading the fleet cannot contradict
        // afterwards. Failing here leaves the clip on disk with the stage named, which is recoverable.
        val capturePayload = try {
            enrichmentStage.buildCapture(
                boards = enriched,
                config = config,
                location = location,
                timestampMs = capturedAtMs,
                captureId = extraction.videoId,
                station = station
            )
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            abortStage("enrich", t)
        }
        // DEBUG UPLOAD (Stage 6): the body that goes to the API — completes the run's debug folder.
        // ONE file per run now, not one per board: there is one body.
        optional("staging the capture body for the debug container", Unit) {
            DebugUploader.enqueueText(context, debugRun, "api_response.json", capturePayload)
        }

        // EVERY artifact staged before the result is announced, and that ordering is the point.
        //
        // The consumer of [PipelineEvent.CaptureResult] enqueues the reading for upload, so emitting it
        // first meant the reading could be POSTed and ACCEPTED while this run's crops, canvases and
        // JSON were still being copied into the outbox. Staging is local and quick, so it always won in
        // practice — but "always won in practice" is not an ordering, and the window it left is the one
        // that matters: a process killed inside it leaves a reading the server has taken with no
        // evidence behind it, and nothing later can attach the evidence to a capture already accepted.
        //
        // Cheap to wait for: staging is a file copy, not a transfer. The network drain is WorkManager's
        // and happens long after this returns.
        //
        // Guarded, and it is the one `optional` in this file whose failure is not harmless: the wait is
        // what keeps the evidence ahead of the reading, so a wait that THREW would announce the result
        // with staging still in flight — the window the ordering above exists to close. It is still
        // optional rather than fatal, because the alternative is worse in both directions: aborting
        // here throws away a reading that is complete and correct over a file copy, and there is
        // nothing to retry — the artifacts are already staged or already lost. The log line is what
        // makes the one case where evidence could trail its reading findable.
        optional("waiting for the debug container to finish staging", Unit) {
            DebugUploader.awaitIdle()
        }

        emit(
            PipelineEvent.CaptureResult(
                captureId = extraction.videoId,
                boardCount = enriched.size,
                payload = capturePayload,
                // A board that threw makes the capture incomplete exactly as an unread one does, so the
                // clip stays on disk and is read again whole. See `allBoardsRan`.
                complete = readerReached && allBoardsRan,
                ocrFramePaths = readCanvases
            )
        )

        Log.d(
            TAG,
            "Run complete: frames=${extraction.frames.size}(+${detection.refineFrames.size} re-sampled) " +
                "crops=${detection.detections.size} boards=${boards.size} processed of " +
                "${detection.boards.size} track(s) total=${SystemClock.elapsedRealtime() - startMs}ms"
        )
    }
        .flowOn(Dispatchers.Default) // Heavy TFLite / OpenCV / HTTP work runs off the main thread.

    /**
     * Stages 3-6 for ONE board: prep, read, consolidate, enrich, and emit that board's result.
     *
     * Everything in here is scoped to [board]'s own crops. The stage events are emitted per board, so a
     * two-board run walks the dashboard's timeline twice — the rows are updated in place, so what the
     * timeline finally shows is the last board's timings.
     *
     * Debug artifacts that would otherwise collide are suffixed with the board id, and only when the run
     * actually found more than one: a single-board run writes exactly the filenames it always has.
     */
    private suspend fun FlowCollector<PipelineEvent>.processBoard(
        /** Null only when the run found no board at all — the stages then walk empty inputs. */
        board: TrackedBoard?,
        /**
         * This board's place in the run, 1-based. What the dashboard shows — see
         * [PipelineEvent.AggregatedResult.boardOrdinal].
         */
        boardOrdinal: Int,
        boardCount: Int,
        detection: BoardDetectionResult,
        extraction: FrameExtractionResult,
        videoFile: File,
        /** The capture's station, matched once by [run] — see [EnrichmentStage.matchStation]. */
        station: StationDirectory.Match?,
        debugRun: String,
        startMs: Long
    ): BoardOutcome? {
        val boardDetections = if (board == null) emptyList() else detection.detectionsOf(board.boardId)
        // DAY or NIGHT, decided once for the whole board from the capture time and the stored sun times
        // — or, for a sideloaded test clip, taken straight from DayNight.NIGHT_TEST_CLIPS.
        // Fails OPEN to DAY, which is the fallback `DayNight` itself already uses whenever it has no sun
        // times to reason from — and for the reason stated there: the night branch's gamma curve is
        // actively damaging on a day board, so DAY is the answer that costs least when the truth is
        // unknown. It is not a coin flip in either direction.
        //
        // Guarded because this reads `AzureMapsStore` and the test-clip index, so it touches storage,
        // and it sits before Stage 3 — a throw here lost the board before a single crop was opened,
        // over a choice between two filter branches.
        val light = optional(
            "the day/night decision",
            LightDecision(
                phase = LightPhase.DAY,
                capturedAtMs = System.currentTimeMillis(),
                source = "clock",
                reason = "the light decision failed — defaulting to DAY"
            )
        ) { DayNight.decideFor(context, videoFile) }
        val boardSuffix = if (board != null && boardCount > 1) "_b%02d".format(board.boardId) else ""
        val boardLabel =
            if (board != null && boardCount > 1) " for board $boardOrdinal of $boardCount" else ""
        if (board != null && boardCount > 1) {
            Log.d(TAG, "--- board ${board.summary()}: ${boardDetections.size} crop(s) ---")
        }

        // ---------------- Stage 3: IMAGE PREP ----------------
        val prepStart = SystemClock.elapsedRealtime() - startMs
        emit(PipelineEvent.StageStarted(PipelineStage.PREP, prepStart))
        // Hand the detections to the image-prep stage: it reads each crop, gates on clarity (dropping
        // blurry crops), enhances survivors (grey → CLAHE → unsharp), letterboxes each onto the region
        // model's 640 canvas, crushes the unlit seven-segment ghosts inside every detected price
        // region and marks those regions, then writes the canvas under prepped/<videoId>/. That canvas
        // is the artifact — it is what Stage 4 reads. Returns one entry per surviving crop.
        // Steps F and G also run in there: the naming is audited, and the board's own printed fuel
        // names are read off the clean canvases. Both are board-level, so the layout comes back once
        // beside the frames rather than copied onto each of them.
        val prep = try {
            imagePrepStage.prepare(boardDetections, light)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            // An empty PrepResult here meant no canvases, so no OCR, so no prices — a board that was
            // detected, photographed and then reported as blank because our own image processing broke.
            //
            // `Throwable`: this stage is the heaviest allocator in the pipeline. It decodes each crop,
            // builds five OpenCV `Mat`s per image and letterboxes onto a fresh 640 canvas, so
            // `OutOfMemoryError` is its characteristic failure and OpenCV's native loader raises
            // `UnsatisfiedLinkError` — both `Error`, both meaning the prep did not run, neither worth
            // reporting as a blank board.
            abortStage("prep", t)
        }
        val prepped = prep.frames
        if (prep.layout.ran) {
            Log.d(
                TAG,
                "Board layout: ${prep.layout.summary()}" +
                    if (prep.layout.applied) " (${prep.layout.names.size}/${prep.layout.panels} panels named)"
                    else " (NO names — ${prep.layout.reason ?: prep.layout.error})"
            )
        }
        // Crops Stage 3 OPENED and threw out {DASH} its second clarity gate, or the region cap. Measured
        // against what it opened rather than against every crop of the board: it stops after
        // MAX_BOARDS_FORWARD canvases, so the difference from `boardDetections.size` is mostly crops it never
        // looked at, and calling those discarded would report a healthy run as having thrown most of
        // itself away.
        val discarded = prep.attempted.size - prepped.size
        // Dashboard: show each survivor's prepped crop + its final clarity score. (Dropped frames
        // aren't returned by prepare(), so they simply don't appear.)
        for ((i, pf) in prepped.withIndex()) {
            emit(PipelineEvent.StageProgress(PipelineStage.PREP, i + 1, prepped.size))
            decodePreview(pf.preppedPath)?.let { bmp ->
                emit(PipelineEvent.FrameScored(pf.frameIndex, bmp, pf.finalClarityScore))
            }
        }
        run {
            val end = SystemClock.elapsedRealtime() - startMs
            emit(PipelineEvent.StageFinished(PipelineStage.PREP, end, end - prepStart))
        }

        // DEBUG UPLOAD (Stage 3): this stage's INPUT and OUTPUT together — every board crop it OPENED,
        // and every 640 canvas that came back out.
        //
        // Both halves, because either one alone answers half a question. A canvas that reads badly could
        // be the prep's fault or the crop's, and only the pair distinguishes them; and a crop with no
        // canvas beside it is how you see what a gate dropped. They pair up by name — the `fXXXXX_dNN`
        // stem is the same in `crops/` and `prepped/`.
        //
        // Scoped to `prep.attempted` rather than to every detection of the board: the stage stops after
        // MAX_BOARDS_FORWARD canvases, so the crops behind that point were never opened. Uploading them would
        // put unpaired crops in the folder that look exactly like gate rejections — and the manifest,
        // which names every detection either way, is where those crops are accounted for instead.
        //
        // Affordable because a crop is a board, not a frame: a fraction of the pixels of the 4K frame it
        // came from, and JPEG on the way out.
        optional("staging Stage 3's crops and canvases", Unit) {
            DebugUploader.enqueueFiles(context, debugRun, buildList {
                boardDetections.filter { it.detectionId in prep.attempted }
                    .forEach { add("crops/${File(it.cropPath).name}" to File(it.cropPath)) }
                prepped.forEach { add("prepped/${File(it.preppedPath).name}" to File(it.preppedPath)) }
                // The `_tag` canvases Step G was sent: same board, undimmed, letters outside the boxes.
                // Uploaded because a fuel name that came out wrong is otherwise unattributable — these
                // are the only images that show what the layout was actually read off.
                prep.layoutCanvasPaths.forEach { add("prepped/${File(it).name}" to File(it)) }
            })
        }

        // DEBUG UPLOAD (Stage 3, Step G): the translation map — which letter sells which fuel.
        if (prep.layout.json.isNotEmpty()) {
            optional("staging the board layout", Unit) {
                DebugUploader.enqueueText(context, debugRun, "layout$boardSuffix.json", prep.layout.json)
            }
        }

        // The canvases to read: the first OCR_FRAMES of what Stage 3 returned, NOT all of it. Every
        // prepped canvas is still written and uploaded — only the reader sees fewer. All of them go to
        // the reader in ONE request.
        // Fails OPEN, to an UNRANKED trim of the same size. This call is a sort and a `take`, so the
        // only way it throws is a comparator disagreeing with itself on odd data — and the honest
        // answer to "I could not decide which canvases are best" is to read some of them anyway rather
        // than to lose the board.
        //
        // The fallback keeps the `take`, and that part is not cosmetic: it is the only cap on how many
        // canvases go into a single OCR request, so falling back to the untrimmed list would answer a
        // failed sort with an unbounded request body — a far worse outcome than an unranked one. What
        // it gives up is the ranking, which is exactly the thing that just failed. It is the
        // behaviour this call had before the ranking existed, and the note below says what that cost:
        // the earliest canvases rather than the ones nearest the board.
        val ocrPool = optional("choosing which canvases to read", prepped.take(OCR_FRAMES)) {
            selectOcrFrames(prepped)
        }

        // ---------------- Stage 4: OCR ----------------
        val ocrStart = SystemClock.elapsedRealtime() - startMs
        emit(PipelineEvent.StageStarted(PipelineStage.OCR, ocrStart))
        // Run OCR over the selected board images: OcrStage reads each prepped canvas, asks the reader for
        // the DIGITS inside each lettered price region, and binds every answer back to the region
        // Stage 3 tagged with that letter — giving a frame → bounding box → digits mapping per board.
        val ocrStageResult = try {
            ocrStage.run(ocrPool)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            // A thrown read is not a read. This used to fall through to an empty result whose
            // `readerUnavailable` was false, which is the one combination that lets a board of blanks be
            // uploaded as an answer.
            //
            // `Throwable` for consistency with the stages either side, and because this one loads every
            // canvas in the pool into memory at once to build a single request body.
            abortStage("ocr", t)
        }
        val ocrResults = ocrStageResult.results
        // The canvases the reader was actually given — the pool minus any canvas carrying no price
        // region, which there would be nothing to bind a reading to on.
        val ocrFrames = ocrStageResult.sentFrames

        // The board was never read. Nothing is parked for a later read: the WHOLE clip is redone (the
        // clip stays in `recorded_videos/` and is not marked done), and parking on top of that would
        // read the same board twice, from two different moments, and produce two answers for one
        // capture. The run reports itself incomplete instead, which is what stops its empty body being
        // sent.
        //
        // The two causes are said apart because what a reader can do about them differs: no signal
        // clears on its own, a refusal (no key, a dead key, a wrong deployment name) never does.
        if (ocrStageResult.readerUnavailable) {
            emit(PipelineEvent.ConsolidationReasoningLine(
                if (ocrStageResult.refused) {
                    "The reader REFUSED the request — ${ocrFrames.size} board image(s)$boardLabel went " +
                        "unread. This is a configuration fault, not a connection one: the clip stays " +
                        "queued and will keep failing until the key, endpoint or model is fixed."
                } else {
                    "No connection — ${ocrFrames.size} board image(s)$boardLabel went unread. This clip " +
                        "stays queued and runs again from the start once you are back online."
                }
            ))
        }
        // Mirror each board's readings to the replay fixture + the dashboard. The prepped canvas is
        // reloaded from disk since OcrStage freed each bitmap after reading it.
        val preppedPathByDetection = ocrFrames.associate { it.detectionId to it.preppedPath }
        for ((i, ocr) in ocrResults.withIndex()) {
            emit(PipelineEvent.StageProgress(PipelineStage.OCR, i + 1, ocrResults.size))
            // Persist this board's region→digits mapping to the on-device replay fixture
            // (ocr_response.json — pull it into res/raw to check it in; see [OcrResultStore]).
            optional("recording the replay fixture for ${ocr.slug}", Unit) {
                ocrResultStore.record(ocr, prep.boardOrder)
            }
            // Dashboard: show the prepped canvas that was read + its readings JSON.
            preppedPathByDetection[ocr.detectionId]?.let { path ->
                decodePreview(path)?.let { crop ->
                    emit(PipelineEvent.FrameRead(ocr.frameIndex, crop, ocr.ocrResults, ocr.regions))
                }
            }
        }
        optional("logging the replay-fixture pull hint", Unit) { ocrResultStore.logPullHint() }
        run {
            val end = SystemClock.elapsedRealtime() - startMs
            emit(PipelineEvent.StageFinished(PipelineStage.OCR, end, end - ocrStart))
        }

        // DEBUG UPLOAD (Stage 4): each frame's cleaned OCR JSON.
        optional("staging Stage 4's readings", Unit) {
            DebugUploader.enqueueTexts(
                context, debugRun,
                ocrResults.map { "ocr/${it.slug}.json" to it.ocrResults }
            )
        }

        // NO frame upload. Not the ~90 sampled frames, and not the handful behind a reading either.
        //
        // A sampled frame is a whole UHD image, about a megabyte apiece, and a run produces hundreds of
        // them. Uploading the ones that led to a reading was already the thinned version of this — and
        // it is still the most expensive set in the folder and the least informative one: the CROP shows
        // the board and the CANVAS shows exactly what the reader was given, both of which go up, while
        // the frame only shows where on the road the board happened to sit.
        //
        // Every frame is still accounted for. `pipeline_manifest.json` names each detection, its frame
        // index, its timestamp, its confidence and how far down the pipeline it got, and lists the
        // frames that produced no board at all — so the record of what was sampled survives without the
        // pixels. The board images are the evidence; the frames were the bill.
        // DEBUG UPLOAD: what the run produced against what was uploaded. Cutting the images down means
        // a folder no longer shows, by itself, that a frame was sampled and found nothing — so the
        // record says so explicitly instead. Cheap, and it is the part that made the cut defensible.
        // `runManifest` is inside the guard, not just the staging call. It walks every detection of the
        // run and builds a JSON document out of them, so it is the half of this line that can actually
        // throw — and a record of the run is not worth the run.
        optional("staging the run manifest", Unit) {
            DebugUploader.enqueueText(context, debugRun, "pipeline_manifest$boardSuffix.json", runManifest(
                videoFile, extraction, detection, boardDetections, prep, ocrFrames,
                board, boardCount
            ))
        }

        // ---------------- Stage 5: CONSOLIDATION ----------------
        val recStart = SystemClock.elapsedRealtime() - startMs
        emit(PipelineEvent.StageStarted(PipelineStage.RECONSTRUCT, recStart))

        // var, not val: the deterministic branch replaces both when the cloud review returns a
        // corrected board.
        var cleanJson: String
        var readings: List<AggregatedReading>
        val stage5Uploads: List<Pair<String, String>>
        // Whether Stage 5's cloud review actually answered. Declared out here because the marker below
        // is written for both consolidation paths, and the agentic one never runs a review at all.
        var reviewRan = false

        // ---- Deterministic consolidation (the only path in this build) ----
        // The reference prototype gated this against an agentic Stage 5 (RowConsolidator +
        // GroundedPriceReconstructor) behind AGENTIC_CONSOLIDATION, which was already false there: it
        // groups rows by fuel NAME and seat, and Stage 4 is digits-only, so it had nothing to group on
        // and returned zero readings. Those classes are not part of this build, so the flag and its
        // branch are gone and what follows is the former `else`.
        // ---- Deterministic path (LIVE) ----
        // Reduce Stage 4's box→digits mapping to one consensus price per board row with the
        // window-merge algorithm: pool each box label's reads, take the modal per-frame row count
        // as the board's row count, then slide a row-count-wide window over the box list and keep
        // the board the most window positions agree on. No model, no prompts — same input, same
        // board, every time. See [ConsensusConsolidator].
        //
        // It consumes the SAME `ocr_response.json` document that gets written to disk, so replaying
        // the pulled res/raw fixture exercises this stage on byte-identical input.
        // Stage 3's layout names the rows: a box letter maps to the fuel token printed beside that
        // panel. Passed only when Step G was APPLIED — a refused layout has no names to give, and
        // half a layout would name the wrong panels.
        //
        // ABORTS on a fault, like every other stage — and it was the one stage with no wrapper at all.
        //
        // Both halves are inside it, because both decide the answer. `buildRoot` assembles the
        // document Stage 5 reduces and `reduce` is the reduction itself, so a throw in either means
        // there is no consensus — and `cleanJson` and `readings` are what Stage 6 enriches and what
        // the payload carries. Falling through with them unset is not available (Kotlin would not
        // compile it) but falling through with them EMPTY was the shape a well-meaning fix would take,
        // and it is the worst one: a board that was detected, photographed and read, reporting no
        // prices because the arithmetic that combines the reads threw.
        //
        // A crash here was also, until now, the fault most likely to reach the collector with no
        // [PipelineEvent.Failed] behind it — the run ended, the clip stayed on disk, and nothing on the
        // device named consolidation. The clip retries, which is right: the input is deterministic, so
        // a genuine fault repeats and the clip-is-stuck row is how it becomes visible.
        val consensus = try {
            ConsensusConsolidator.reduce(
                // The board order travels IN the document: Stage 5 resolves each box letter to the
                // board's own row through it, so a row number means the same thing here and on a
                // replay of the pulled fixture.
                OcrResultStore.buildRoot(ocrResults, prep.boardOrder),
                fuelNames = if (prep.layout.applied) prep.layout.names else emptyMap()
            )
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            abortStage("consolidate", t)
        }
        cleanJson = consensus.readingsJson
        readings = consensus.rows.map { r ->
            AggregatedReading(
                // No fuel names exist any more (Stage 4 is digits-only), so the box letter is the
                // row's only identity — shown in the dashboard. The API payload keeps fuel_type
                // blank rather than passing a letter off as a fuel name.
                fuelType = r.label,
                // The X.XXX output guard: blank when the consensus isn't a shape a board can show.
                price = r.output,
                votes = r.prices.count { ConsensusConsolidator.pricesMatch(it, r.price) },
                totalReads = r.reads,
                confidence = r.agreement,
                // The BOARD's row, not the canvas slot the merge worked in — see
                // [ConsensusConsolidator.Row.boardRow].
                rowNum = r.boardRowOr
            )
        }
        // Surface the deterministic digest in the dashboard's Stage-5 trace panel, which the
        // agentic path used for its reasoning. The panel's header says the agent is off.
        emit(PipelineEvent.ConsolidationReasoningLine("Agentic consolidation: OFF"))
        emit(PipelineEvent.ConsolidationReasoningLine("Deterministic window-merge consensus:"))
        consensus.report.lineSequence().forEach {
            emit(PipelineEvent.ConsolidationReasoningLine(it))
        }
        val uploads = mutableListOf("consensus$boardSuffix.json" to consensus.json)

        // ---- Cloud review: the consolidated answer, checked against the board images ----
        // The deterministic pass can only work with what Stage 4 read; a panel never read is
        // not recoverable by voting. This puts the answer and the pictures in front of a model that
        // sees both. It runs last, and a failure just leaves the consolidated answer standing.
        // Fails OPEN to null, which the branch below already treats as "keep the consolidated answer" —
        // the correct outcome, because the deterministic pass has already produced a usable board and
        // the review only ever adjusts it.
        //
        // `Throwable` rather than `Exception`: the call loads every prepped canvas of the board into
        // one request body, so `OutOfMemoryError` is a real way for it to fail, and an unaffordable
        // verification is emphatically not worth a capture. `CancellationException` is rethrown ahead
        // of it — it was previously swallowed here, which would have reported a service shutdown as a
        // failed review and let the rest of the board's stages run on inside a cancelled scope.
        val review = try {
            consolidationReviewer.review(cleanJson, ocrResults.map { it.preppedPath }.distinct())
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.e(TAG, "cloud consolidation review failed — keeping the consolidated answer", t)
            null
        }
        if (review != null) {
            reviewRan = true
            cleanJson = review.json
            readings = review.rows.map { r ->
                // Named arguments deliberately: `fuelType` and `box` are separate fields now, and
                // passing the letter positionally into the name slot is exactly how the board's own
                // reading got lost on this path.
                AggregatedReading(
                    fuelType = r.fuelType,
                    price = r.price,
                    votes = 0,
                    totalReads = 0,
                    rowNum = r.rowNum,
                    box = r.box
                )
            }
            // Deliberately unbranded. The review is a background step a tester has no reason to
            // know the shape of, so the trace reports WHAT changed and never what changed it.
            // (Logcat and the debug artifact still name the model — those are ours, not theirs.)
            emit(PipelineEvent.ConsolidationReasoningLine(""))
            emit(PipelineEvent.ConsolidationReasoningLine("--- Verification pass ---"))
            if (review.changes.isEmpty()) {
                emit(PipelineEvent.ConsolidationReasoningLine("every row confirmed — no changes"))
            } else {
                emit(PipelineEvent.ConsolidationReasoningLine(
                    "${review.changes.size} row(s) adjusted:"))
                review.changes.forEach {
                    emit(PipelineEvent.ConsolidationReasoningLine("  $it"))
                }
            }
            emit(PipelineEvent.ConsolidationReasoningLine("verified readings:"))
            review.rawText.lineSequence().forEach {
                emit(PipelineEvent.ConsolidationReasoningLine("  $it"))
            }
            uploads += "consolidation_review$boardSuffix.json" to JSONObject().apply {
                put("model", com.terminalscout.app.prod.BuildConfig.AZURE_AI_MODEL)
                put("consolidated_in", runCatching { JSONObject(consensus.readingsJson) }.getOrNull())
                put("reviewed_out", runCatching { JSONObject(review.json) }.getOrNull())
                put("changes", JSONArray().apply { review.changes.forEach { put(it) } })
                put("raw_reply", review.rawText)
            }.toString(2)
        } else if (consolidationReviewer.isEnabled) {
            emit(PipelineEvent.ConsolidationReasoningLine(""))
            emit(PipelineEvent.ConsolidationReasoningLine(
                "--- Verification pass unavailable — keeping the consolidated answer ---"))
        }
        stage5Uploads = uploads

        run {
            val end = SystemClock.elapsedRealtime() - startMs
            emit(PipelineEvent.StageFinished(PipelineStage.RECONSTRUCT, end, end - recStart))
        }

        // DEBUG UPLOAD (Stage 5): whichever consolidation ran writes its own artifact(s).
        optional("staging Stage 5's consolidation artifacts", Unit) {
            DebugUploader.enqueueTexts(context, debugRun, stage5Uploads)
        }

        // Every cloud step that had to run, ran. The review is excluded when it is not configured at
        // all — a step that was never going to run is not a step that was lost.
        val cloudComplete = prep.audit.ran &&
            prep.layout.ran &&
            !ocrStageResult.readerUnavailable &&
            (!consolidationReviewer.isEnabled || reviewRan)

        // ---- The network marker ----
        // Four of this pipeline's steps leave the device, and every one of them fails QUIETLY: the audit
        // leaves the naming as Step E made it, the layout is simply not applied, OCR returns nothing, the
        // review keeps the consolidated answer. A run that lost its connection therefore looks, in every
        // artifact it writes, exactly like a run where the models had nothing to say.
        //
        // This is the difference, stated once, in a file a reader can grep. `complete` is the single
        // question worth asking of a run; the per-step detail is for explaining WHICH call was lost.
        optional("staging the network marker", Unit) {
        DebugUploader.enqueueText(
            context, debugRun, "cloud_calls$boardSuffix.json",
            JSONObject().apply {
                put("complete", cloudComplete)
                put("audit", JSONObject().apply {
                    put("ran", prep.audit.ran)
                    put("error", prep.audit.error ?: JSONObject.NULL)
                })
                put("layout", JSONObject().apply {
                    put("ran", prep.layout.ran)
                    put("applied", prep.layout.applied)
                    put("error", prep.layout.error ?: JSONObject.NULL)
                })
                put("ocr", JSONObject().apply {
                    // Whether the board was READ, which is not the same question as whether the server
                    // answered: a 4xx answers and reads nothing. `refused` says which side of that a
                    // failure fell on — a refusal will repeat identically until something is changed.
                    put("reader_reached", !ocrStageResult.readerUnavailable)
                    put("refused", ocrStageResult.refused)
                    put("canvases_sent", ocrFrames.size)
                    put("canvases_read", ocrResults.size)
                })
                put("review", JSONObject().apply {
                    put("enabled", consolidationReviewer.isEnabled)
                    put("ran", reviewRan)
                })
            }.toString(2)
        )
        }

        // ---------------- Stage 6: ENRICHMENT (this board's share of it) ----------------
        // Names this board's rows and returns its entry for the capture body. The body itself is built
        // once, by [run], after every board has been through here — the device, the fix, the timestamp
        // and the station are properties of the capture and were settled before this loop started.
        val enrStart = SystemClock.elapsedRealtime() - startMs
        emit(PipelineEvent.StageStarted(PipelineStage.ENRICH, enrStart))
        // ABORTS. This board's entry on the capture body is built here, so a fault means there is no
        // entry — and the alternative to aborting is returning a `BoardOutcome` with an empty block,
        // which `run` would add to `enriched` and send. The API would accept a board reporting no
        // prices, and nothing afterwards can contradict a reading the server has taken.
        //
        // The board's failure no longer costs the capture's other boards: `run` contains this throw per
        // board and reports the capture incomplete, so the clip is read again whole. See the board loop.
        val block = try {
            enrichmentStage.enrichBoard(
            reconstructedJson = cleanJson,
            ordinal = boardOrdinal,
            count = boardCount,
            // The tracker's id, which is what this board's debug artifacts are suffixed with. -1 when
            // there was no board at all, so the body cannot be read as describing board zero.
            boardId = board?.boardId ?: -1,
            imagesRead = ocrFrames.size,
            seenFromS = board?.let { it.firstSeenUs / 1e6 },
            seenToS = board?.let { it.lastSeenUs / 1e6 },
            station = station
            )
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            abortStage("enrich", t)
        }

        emit(
            PipelineEvent.AggregatedResult(
                boardId = board?.boardId ?: 0,
                boardOrdinal = boardOrdinal,
                boardCount = boardCount,
                json = cleanJson,
                readings = readings,
                framesTotal = extraction.frames.size + detection.refineFrames.size,
                framesPassed = ocrFrames.size,
                framesDiscarded = discarded
            )
        )
        run {
            val end = SystemClock.elapsedRealtime() - startMs
            emit(PipelineEvent.StageFinished(PipelineStage.ENRICH, end, end - enrStart))
        }

        // The board id is interpolated, not formatted. `%02d` used to sit in the FIRST literal of
        // this concatenation while `.format()` was attached to the third — so it formatted a string
        // with no placeholder in it, and every run logged the literal text "Board b%02d complete".
        // Nothing was broken by it except the one line you would read to find out which board this was.
        Log.d(
            TAG,
            "Board b${board?.boardId ?: -1} complete: crops=${boardDetections.size} " +
                "opened=${prep.attempted.size} prepped=${prepped.size} " +
                "sentToOcr=${ocrFrames.size} discarded=$discarded " +
                "at=${SystemClock.elapsedRealtime() - startMs}ms"
        )
        return BoardOutcome(
            block,
            ocrFrames.map { it.preppedPath },
            readerReached = !ocrStageResult.readerUnavailable
        )
    }

    /**
     * Reports the stage that threw, then ends the run.
     *
     * Two things happen and both matter: [PipelineEvent.Failed] goes out so a screen, the status line
     * and the errors list can name the stage, and [PipelineStageException] is thrown so nothing
     * downstream builds a payload out of a stage that did not run. Returns [Nothing], so a `catch` that
     * calls it satisfies the type of whatever the stage was supposed to produce without a placeholder
     * empty result — which is how these faults used to get past.
     */
    private suspend fun FlowCollector<PipelineEvent>.abortStage(
        stage: String,
        cause: Throwable
    ): Nothing {
        Log.e(TAG, "Stage $stage failed — abandoning the run, the clip stays on disk", cause)
        emit(PipelineEvent.Failed(stage, cause))
        throw PipelineStageException(stage, cause)
    }

    /**
     * Runs one step that the READING does not depend on, and swallows anything it throws.
     *
     * The counterpart to [abortStage], and the two together are the whole error policy of this file:
     * **a step that decides the answer aborts the run, and a step that only describes it does not.**
     * Getting that split wrong in either direction is a real fault — an aborted run over a debug file
     * that would not copy throws away a reading the device drove somewhere to get, and a swallowed
     * consolidation leaves a payload built out of a stage that did not run.
     *
     * What belongs in here: the debug container's artifacts, the replay fixture, the dashboard's
     * preview bitmaps, the station-name refresh. Every one of them is evidence or convenience, the run
     * is correct without it, and each is a file write or a network call on a device that is by
     * definition unattended — the exact things that fail for reasons having nothing to do with the
     * clip.
     *
     * No errors row. These are logged and nothing more, deliberately: `ERROR_HANDLING.md` requires a
     * key that something clears, and a run whose debug copy failed is not a state the device is IN —
     * it is one run's history, already over by the time anybody could look. A row per occurrence would
     * be an errors screen nobody reads, which costs more than the information is worth.
     *
     * @param what the step, for the log line. Not a stage name — nothing is reported as a stage here.
     * @param fallback what the caller gets, chosen at the call site to fail in the safe direction.
     */
    private inline fun <T> optional(what: String, fallback: T, block: () -> T): T = try {
        block()
    } catch (c: CancellationException) {
        // Not a fault, and it must not be downgraded into one: the service is shutting down, and a
        // swallowed cancellation would let the rest of the run walk on inside a cancelled scope.
        throw c
    } catch (t: Throwable) {
        // `Throwable`, for the reason `Crashproof.kt` gives: this file decodes UHD bitmaps and runs
        // OpenCV over them, so `OutOfMemoryError` is the likeliest fault in it, and an optional step is
        // the last thing worth losing a capture to.
        Log.e(TAG, "$what failed — the run carries on without it", t)
        fallback
    }

    /**
     * The run's own record of itself: every frame sampled, every board detected, what survived each
     * gate, and which of those images were uploaded.
     *
     * The debug folder used to be a complete mirror of the local cache, so its contents WERE the record
     * — 90 frames present meant 90 frames sampled, and a frame with no crop beside it meant the
     * detector found nothing there. Uploading only the images behind a reading takes that away, and a
     * reduced folder that cannot be told apart from a truncated one is worse than a large one. So the
     * counts and the per-frame outcome are stated here, at a few kilobytes for the whole run.
     */
    private fun runManifest(
        videoFile: File,
        extraction: FrameExtractionResult,
        detection: BoardDetectionResult,
        boardDetections: List<BoardDetection>,
        prep: PrepResult,
        ocrFrames: List<PreppedFrame>,
        board: TrackedBoard?,
        boardCount: Int
    ): String {
        val prepped = prep.frames
        val preppedByDetection = prepped.associateBy { it.detectionId }
        val ocrIds = ocrFrames.map { it.detectionId }.toSet()
        return JSONObject().apply {
            put("video", videoFile.name)
            put("video_bytes", videoFile.length())
            put("video_id", extraction.videoId)
            put("duration_s", extraction.meta.durationUs / 1_000_000.0)
            put("source_resolution", "${extraction.meta.width}x${extraction.meta.height}")
            put("frames_sampled", extraction.frames.size)
            put("frames_resampled", detection.refineFrames.size)
            // Board-scoped from here down: a manifest describes ONE board's journey through the run,
            // and a two-board video writes two of them.
            put("board_id", board?.boardId ?: JSONObject.NULL)
            put("boards_in_video", boardCount)
            // What Stage 2 actually resolved, before MAX_BOARDS trimmed it. Equal to `boards_in_video`
            // on any normal run; higher says the cap dropped tracks, which `boards_in_video` alone
            // could not tell you.
            put("boards_tracked", detection.strongBoards.size)
            put(
                "board_seen_s",
                board?.let { "%.1f-%.1f".format(it.firstSeenUs / 1e6, it.lastSeenUs / 1e6) }
                    ?: JSONObject.NULL
            )
            put("board_hue", board?.hue?.toInt() ?: JSONObject.NULL)
            put("crops_detected", detection.detections.size)
            put("boards_detected", boardDetections.size)
            put("boards_attempted", prep.attempted.size)
            put("boards_prepped", prepped.size)
            put("boards_read", ocrIds.size)
            put(
                "images_uploaded",
                "board crops Stage 3 opened, and every prepped canvas. NO full frames and NO source " +
                    "clip — both are large and neither shows the board any better than the crop does. " +
                    "Crops marked not_looked_at have no upload — see `outcome` below."
            )
            // One row per detected board: where it came from and how far down the pipeline it got.
            put("detections", JSONArray().apply {
                boardDetections.forEach { d ->
                    put(JSONObject().apply {
                        put("detection_id", d.detectionId)
                        put("frame_index", d.frameIndex)
                        put("timestamp_s", d.timestampUs / 1_000_000.0)
                        put("confidence", d.confidence)
                        put("pass", d.pass)
                        put("crop", File(d.cropPath).name)
                        // Explicit JSON null, not a missing key — JSONObject.put(name, null) REMOVES
                        // the key, and a reader cannot tell an absent field from one that was never
                        // written. Null here means no canvas, and `outcome` below says why.
                        put(
                            "prepped",
                            preppedByDetection[d.detectionId]?.let { File(it.preppedPath).name }
                                ?: JSONObject.NULL
                        )
                        // Why this crop has a canvas or has not, in one word. Stage 3 stops after
                        // MAX_BOARDS_FORWARD canvases, so "no canvas" now has two quite different causes and
                        // a folder listing cannot tell them apart:
                        //   read            — prepped and sent to the reader
                        //   prepped         — a canvas exists but the reader did not get it
                        //   dropped         — opened, and a gate or the region cap threw it out
                        //   not_looked_at   — the run already had its canvases before reaching this crop
                        put(
                            "outcome",
                            when {
                                d.detectionId in ocrIds -> "read"
                                d.detectionId in preppedByDetection -> "prepped"
                                d.detectionId in prep.attempted -> "dropped"
                                else -> "not_looked_at"
                            }
                        )
                        put("read_by_ocr", d.detectionId in ocrIds)
                        // Kept, and now always false: no frame is uploaded for any detection. The
                        // key stays because a reader of two folders either side of this change must be
                        // able to tell "no frame was sent" from "this manifest predates the field".
                        put("frame_uploaded", false)
                    })
                }
            })
            // Frames that produced no board at all: they have no detection row, so name them here.
            put("frames_without_a_board", JSONArray().apply {
                val withBoard = detection.detections.map { it.frameIndex }.toSet()
                (extraction.frames + detection.refineFrames)
                    .filter { it.index !in withBoard }
                    .forEach { put(it.index) }
            })
        }.toString(2)
    }

    /**
     * Decodes an on-disk image **for the dashboard only**, at the smallest power-of-two scale whose
     * longest side still clears [PREVIEW_MIN_PX].
     *
     * Every bitmap this pipeline emits is a thumbnail in a debug strip; nothing downstream reads one
     * (each stage re-opens the files it needs). Decoding them at full size meant a 4K frame arrived as
     * ~33 MB of bitmap for the UI to shrink to 240 px and throw away — and since these are emitted into
     * a buffered channel, several could be in flight at once. Bitmaps are native allocations, so that
     * showed up as the system killing the process for memory rather than as a diagnosable OOM. Decoding
     * small caps the cost per event at roughly a megabyte, which is what makes the bounded buffer in
     * the collector a real ceiling rather than a smaller unbounded one.
     */
    private fun decodePreview(path: String): Bitmap? {
        // Nothing is going to draw it. Every preview emission in this file goes through here and is
        // guarded by `?.let`, so returning null is what switches the whole dashboard feed off — and it
        // is a real saving, not a tidy-up: a run decodes one of these per frame, per crop and per
        // canvas, several hundred file reads that a truck with no screen has no use for.
        if (!emitPreviews) return null
        // A preview is for a screen, and a truck has nobody looking at one. So every fault in here
        // answers null, which is the same answer `emitPreviews = false` gives and is already handled
        // by every call site's `?.let`.
        //
        // It needs saying because this is one of the two most likely places in the file to throw: it
        // is called several hundred times a run, once per frame, crop and canvas, and each call is a
        // `decodeFile` — so a corrupt or truncated image gives an exception and a bitmap the heap has
        // no room for gives an `OutOfMemoryError`. Unguarded, either one cost the whole capture, and
        // it cost it to draw a thumbnail on a dashboard nobody had open.
        //
        // `longest` deserves the same care and gets it by construction: `decodeFile` returns null and
        // leaves `outWidth`/`outHeight` at -1 for a file it cannot read, so the loop below simply does
        // not run and the full-size decode is attempted — which is where a real failure surfaces, in
        // a call whose answer is already allowed to be null.
        return optional("a dashboard preview of ${File(path).name}", null) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            // Halve while the result would still be at least PREVIEW_MIN_PX on its longest side. A
            // prepped 640 canvas therefore comes back untouched; a 3840-wide frame comes back at 480.
            var sample = 1
            while (longest / (sample * 2) >= PREVIEW_MIN_PX) sample *= 2
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }

    /**
     * The board images sent to the reader: the BEST [OCR_FRAMES] canvases Stage 3 handed back, by the
     * same ranking Stage 3 itself used — confidence bucket first, newest-first inside a bucket.
     *
     * ### It used to take the oldest
     * This was `prepped.take(OCR_FRAMES)`, and Stage 3 returns its canvases in **ascending time** — so
     * the trim kept the EARLIEST board images and threw away the ones nearest the board. Both of the
     * stages either side of it say the opposite in writing: `ImagePrepStage` returns the list explaining
     * that "Stage 4 takes the LAST entries as the most recent boards", and `BoardDetector` fills its
     * re-sample bag newest-first precisely because "they are the ones Stage 4 keeps". Three places, two
     * different answers, and the code was the one that disagreed.
     *
     * Nothing was actually losing prices today, because both caps are 14 and the trim was a no-op — the
     * bug was armed rather than firing, and would have gone off the first time
     * [ImagePrepStage.MAX_BOARDS_FORWARD] was raised or [OCR_FRAMES] lowered, as a silent drop in read
     * quality with nothing pointing at this line.
     *
     * ### Why this ranking and not simply "the latest"
     * Because that is the argument Stage 3 already settled and it should not be re-litigated a stage
     * later. Time alone fails on the shape this app sees: the last crops before a board leaves frame are
     * taken at the sharpest angle and are regularly the worst of the set, which is why Stage 3 orders by
     * confidence in fixed-width buckets and uses recency only to break a tie between crops of
     * comparable confidence. Reusing that comparator means one definition of "the best
     * canvases" serves the stage that prepares them, the stage that trims them and the stage that chunks
     * them into requests.
     *
     * The result is put back into ascending time order, because that is what Stage 3 returns and what
     * every consumer of the pool reads — [OcrStage] re-derives the ranking itself for its chunking.
     *
     * ### Why trim at all
     * Stage 3's cap bounds how many canvases are PREPARED. This is a second, smaller cap on how many are
     * READ, and it exists for a different reason: a request carries several canvases at once, and past a
     * point more images stop adding evidence and start costing accuracy — the model is asked to keep a
     * dozen near-identical boards apart and begins carrying a reading from one onto another, which is
     * the one failure the prompt cannot detect afterwards.
     *
     * Trimming here rather than in Stage 3 keeps every prepped canvas on disk and in the debug upload,
     * so a run still shows what it saw even though the reader was shown less of it. The cost is the prep
     * work on the canvases that are never read.
     *
     * The unit is a DETECTION, not a frame: a frame that yielded two boards contributes two entries, so
     * the cap bounds board images rather than frames.
     */
    private fun selectOcrFrames(prepped: List<PreppedFrame>): List<PreppedFrame> {  // the pool
        val chosen = prepped
            .sortedWith(ImagePrepStage.byConfidenceThenNewest({ it.confidence }, { it.timestampUs }))
            .take(OCR_FRAMES)
            // Back to the order Stage 3 hands out and every consumer expects.
            .sortedWith(compareBy({ it.timestampUs }, { it.detectionId }))
        if (chosen.size < prepped.size) {
            Log.d(
                TAG,
                "OCR pool: dropped ${prepped.size - chosen.size} of ${prepped.size} canvas(es) — " +
                    "kept the most confident, newest-first"
            )
        }
        Log.d(
            TAG,
            "OCR pool (${chosen.size} of ${prepped.size} board image(s), cap $OCR_FRAMES): " +
                chosen.joinToString { it.slug }
        )
        return chosen
    }

    companion object {
        private const val TAG = "PriceBoardPipeline"

        /**
         * How long a run waits for a fresh station mapping before starting.
         *
         * Short on purpose: the response is a few KB, and the stored copy is a perfectly good fallback, so
         * there is nothing here worth making a capture wait for.
         */
        private val STATION_REFRESH_TIMEOUT_MS: Long get() = DeviceConfig.long("ocr.run.station_refresh_timeout_ms", 8_000L)

        /**
         * Most boards one capture will process, however many strong tracks Stage 2 resolved.
         *
         * Not a detection limit: Stage 2 still tracks everything it sees and the manifest still reports
         * every track, so the record of what was in the clip stays honest. This caps what is CARRIED
         * DOWNSTREAM - each board past it costs a full Stage 3-6 pass (prep, a layout call, an OCR
         * batch, consolidation, enrichment), and the run's wall clock and cloud spend scale with it.
         *
         * Three is the plausible ceiling for one drive-past. Beyond it the extra tracks are much more
         * often the detector holding onto something board-shaped than a real post, so the cap sheds the
         * cost where the return is worst. Selection is by track strength - see the use site.
         */
        private val MAX_BOARDS: Int get() = DeviceConfig.int("ocr.run.max_boards", 3)

        /**
         * How many board images the reader is sent, per board (one frame can contribute more than one
         * board, so the unit is a board image and not a frame).
         *
         * **A real second cap**, and it answers a different question from Stage 3's
         * [ImagePrepStage.MAX_BOARDS_FORWARD]. That one is about COST — how many canvases are worth
         * preparing. This one is about the reader: asking a model to hold a dozen near-identical boards
         * apart in a single call is where it starts copying a reading from one image onto another.
         * Fewer, better-separated images beat more of them. Which fourteen, see [selectOcrFrames].
         *
         * **Fourteen, read as three requests of [OcrStage.BATCH_SIZE] — 5 + 5 + 4.** This doc said
         * "twelve, read as three requests" for a while, which was simply the wrong number: the constant
         * has been 14, and 14 split by 5 is 5 + 5 + 4. The batch arithmetic is worth stating correctly
         * because it is load-bearing in the other direction too — the corroboration gate that used to
         * live in Stage 5 demanded two SEPARATE requests, which a board reduced to five canvases or
         * fewer can never produce, and it blanked every such board's prices. That gate is gone
         * ([ConsensusConsolidator.MIN_REPEATS] guards the same failure by counting reads instead), so
         * this number no longer decides whether a board can commit a price at all — but a doc that
         * disagrees with its own constant is how that kind of mistake gets made twice.
         *
         * Three requests remain worth having: they are three independent looks at the board, so
         * agreement between them is evidence rather than an echo, and one request lost to a timeout
         * costs a third of the evidence rather than all of it.
         *
         * It is per BOARD: a two-board video sends two sets of requests and gets two answers back,
         * which is why the reader's floor in Stage 2 is per board too.
         */
        private val OCR_FRAMES: Int get() = DeviceConfig.int("ocr.run.ocr_frames", 14)

        /**
         * Longest-side floor for a dashboard preview bitmap (px). Comfortably above the 240 px the
         * strips store, so the extra headroom costs little and nothing looks resampled twice.
         */
        private val PREVIEW_MIN_PX: Int get() = DeviceConfig.int("ocr.run.preview_min_px", 480)

        /**
         * Which Stage 5 runs.
         *
         * **`false` (current build): the deterministic [ConsensusConsolidator]** — window-merge
         * consensus over Stage 4's box→digits mapping.
         *
         * The agentic alternative (RowConsolidator + GroundedPriceReconstructor) is not part of this
         * build, so there is no flag: the deterministic path is the only path.
         */
    }
}
