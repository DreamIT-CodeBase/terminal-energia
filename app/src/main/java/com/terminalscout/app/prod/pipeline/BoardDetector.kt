package com.terminalscout.app.prod.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import com.terminalscout.app.prod.config.ModelConfig
import com.terminalscout.app.prod.download.ModelRepository
import com.terminalscout.app.prod.config.DeviceConfig
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * One rung of the detection ladder. The high and low passes share model output; later passes
 * use another slice of the frame or additional frames. [suffix] tags the crops a pass produced so a
 * crop's filename says which rung found it.
 *
 * [REFINE] is the exception: it runs several times at climbing rates, so the rung supplies its own
 * `_r<rate>fps` tag and this value is only the fallback. It used to be a hardcoded `_r15fps`, which
 * labelled every re-sampled crop with a rate that is not even on the ladder any more.
 */
enum class DetectPass(val label: String, val suffix: String) {
    HIGH("1 high-conf", ""),
    LOW("2 low-conf", "_lowconf"),
    SHIFT_LEFT("3 shift-left", "_shiftL"),
    SHIFT_RIGHT("4 shift-right", "_shiftR"),
    REFINE("5 re-sample", "_refine")
}

/**
 * One board that cleared a pass's confidence gate: where its crop was written, the detection's
 * confidence and bounding box (in the ORIGINAL frame's pixel coordinates), and which rung found it.
 *
 * A frame can yield MORE THAN ONE board, so [frameIndex] is no longer a unique key — [detectionId]
 * is. Everything downstream that names a file or keys a map (Stage 3's prepped image, Stage 4's OCR
 * result) uses [detectionId] / [slug]; [frameIndex] and [timestampUs] stay for provenance and for
 * ordering the crops in time.
 */
data class BoardDetection(
    /**
     * Unique within a run, and the key every later stage files things under.
     *
     * A sequential number over the run's surviving detections in `(timestamp, rank)` order — so it is
     * still ascending in time, and still in rank order within one frame, which is what
     * [OcrResultStore.buildRoot]'s board keys and Stage 3's tie-break both read it for.
     *
     * It used to be `frameIndex * 1000 + rank`, which is not unique: the two shifted-window rungs run
     * over the SAME frames in one stage (the gate is evaluated once, before either), so a frame that
     * yields a box on both produced two detections with one id. They then silently overwrote each other
     * in every map keyed on it — Stage 3's attempted set, the prepped-path lookup, the OCR ids and the
     * replay fixture's board keys — and one of the two boards vanished from the run without a word.
     * Counting cannot collide; arithmetic over a non-unique pair can.
     */
    val detectionId: Int,
    val frameIndex: Int,
    /** 0 = the strongest box in this frame, 1 = next strongest, … (the script's `d00`, `d01`). */
    val rank: Int,
    val timestampUs: Long,
    /** Location on disk of the cropped board PNG. */
    val cropPath: String,
    val confidence: Float,
    val boundingBox: Rect,
    val pass: DetectPass,
    /**
     * Which physical board this crop is of — see [BoardTracker].
     *
     * One video can show more than one price post, and everything downstream of this stage treats the
     * run as ONE board: Stage 3 fits every canvas to a single remembered tag order, Stage 5 pools every
     * reading into one price list. So the pipeline groups by this id and runs Stages 3-6 once per
     * board. -1 only when tracking could not run at all.
     */
    val boardId: Int = -1
) {
    /**
     * File-naming stem for this detection: `f00012_d00`, plus the rung's tag when it was not the
     * ordinary high-confidence pass (`f00012_d00_shiftL`).
     *
     * The pass belongs in here for the same reason it belongs in the id: the frame and the rank do not
     * identify a detection on their own once two rungs have run over one frame, and this stem names
     * files — `prepped_<slug>.png`, `ocr/<slug>.json`. Two detections sharing it meant the second
     * board's canvas overwrote the first's on disk, which no map fix would have caught, because the
     * loss happened in the filesystem.
     *
     * [DetectPass.HIGH]'s tag is empty, so the ordinary path's filenames are exactly what they always
     * were, and a crop pairs with its canvas by stem as before.
     */
    val slug: String get() = "f%05d_d%02d".format(frameIndex, rank) + pass.suffix
}

/** Everything Stage 2 produced: the boards it found, plus any frames its re-sample pass created. */
data class BoardDetectionResult(
    /** Every board found by every pass, in ascending time order (then by rank within a frame). */
    val detections: List<BoardDetection>,
    /** Extra frames the re-sample pass asked Stage 1 for. Empty unless pass 5 ran. */
    val refineFrames: List<FrameFile>,
    /**
     * The physical boards the run resolved to, ordered by first sighting. Empty when nothing was
     * detected. A [TrackedBoard.weak] one is a track too short to be a board the car drove past —
     * the pipeline does not carry those downstream.
     */
    val boards: List<TrackedBoard> = emptyList()
) {
    /** The boards worth processing downstream: everything that is not a weak track. */
    val strongBoards: List<TrackedBoard> get() = boards.filter { !it.weak }

    /** Detections of one board, in the order Stage 3 wants them. */
    fun detectionsOf(boardId: Int): List<BoardDetection> = detections.filter { it.boardId == boardId }
}

interface BoardDetector {
    /**
     * Runs the board-detection ladder over every frame produced by [VideoFrameExtractor.extractFrames].
     *
     * [refiner] lets the last rung ask Stage 1 for extra frames; pass null to disable pass 5.
     */
    suspend fun detectBoards(
        extraction: FrameExtractionResult,
        refiner: FrameRefiner? = null
    ): BoardDetectionResult
}

/**
 * Stage 2 — board detection, as a five-rung escalation ladder.
 *
 * Each rung only runs if the ones before it came back empty, so the cheap high-precision pass
 * handles the easy clips and the expensive escalations are reserved for the ones that find nothing:
 *
 *   Pass 1  every frame -> high, data-driven confidence ([CONF_THRES])
 *   Pass 2  if 0 boards: reuse the full-frame predictions at low confidence ([FALLBACK_CONF])
 *   Pass 3  if 0 boards: every frame, window x[-25%..55%]     -> no threshold
 *   Pass 4  and the same, window x[45%..125%]                 -> no threshold
 *   Pass 5  if under [MIN_BOARDS] usable boards: probe sparse gaps if there were no hits;
 *           otherwise re-sample +/-2s around possible boards, climbing 20 -> 30 fps.
 *
 * Passes 3 and 4 are ONE stage: the gate is evaluated once, before either runs, and BOTH run. A
 * board can sit on either edge, and the left shift finding something is no reason to skip the right
 * half of the frame.
 *
 * Each shifted window hangs a quarter of the frame width PAST the edge it is pushed against, filled
 * with neutral grey, so both are 80% of frame width and an object hard against the frame edge sits
 * inside the window with pad around it rather than flush against its border — the geometry the model
 * was trained on. One definition of that window serves both passes and the re-sample rung's per-frame
 * escalation: see [ShiftWindows].
 *
 * There is deliberately no full-frame no-threshold pass. At no threshold the model emits something
 * on nearly every frame, so such a pass almost always "detects" a tree or a patch of sky, which
 * would satisfy the gate and stop the ladder before the shifted passes — the ones that actually
 * recover missed boards — ever ran.
 *
 * The area guardrail ([MAX_AREA_FRAC]) applies to EVERY pass. It is what makes the low- and
 * no-threshold rungs usable at all: this model latches onto shiny reflections (wet road, dashboard,
 * windshield, hood) and emits a huge box, sometimes with higher confidence than a real board.
 *
 * Crops are written at their NATIVE pixel size — exactly the detected box, no resize and no padding.
 * Resampling to a fixed model input is the consuming stage's job (Stage 3 upscales toward 600 px),
 * so a board is only ever resampled once.
 *
 * Model contract (read from the .tflite, but both layouts are handled):
 *   input : [1, 640, 640, 3] float32 (NHWC, RGB, 0-1) — or [1, 3, 640, 640] NCHW
 *   output: [1, 5, 8400] float32, rows = (cx, cy, w, h, score), coords normalised 0-1
 *           (Ultralytics litert `_NormalizeCoords` export). Single class -> "price-post".
 */
class MlKitGenericBoardDetector(private val context: Context) : BoardDetector {

    companion object {
        private const val TAG = "BoardDetector"

        /**
         * Pass 1. Chosen from this model's measured curves, not the framework default:
         *  - F1 peaks at 0.97 at conf 0.382, and the F1 plateau is flat from ~0.10 to ~0.60, so the
         *    old 0.25-style default bought no extra recall and simply cost precision. 0.38 is the peak.
         *  - Recall is flat at ~0.97 up to conf 0.60, then falls off a cliff, reaching near zero by
         *    0.85. The model never emits more than ~[MODEL_MAX_CONF], so a high threshold silently
         *    drops real boards rather than cleaning up output.
         *  - Precision only reaches 1.00 at conf 0.764, where recall is under 0.2 — not usable.
         */
        private val CONF_THRES: Float get() = DeviceConfig.float("detection.detector.conf_thres", 0.38f)

        /** Anything above this is past the useful range — recall collapses. Reported in the log banner. */
        private val CONF_CLIFF: Float get() = DeviceConfig.float("detection.detector.conf_cliff", 0.65f)

        /** Highest score this model is observed to emit. */
        private val MODEL_MAX_CONF: Float get() = DeviceConfig.float("detection.detector.model_max_conf", 0.86f)

        /**
         * Pass 2: hard-set low threshold, for boards that only score 0.20-0.38 (distant, dim,
         * motion-blurred). Measured recovery: a clip held a legible board at 0.33, 0.05 under pass 1.
         * Also the confidence the re-sample pass (5) runs at.
         */
        private val FALLBACK_CONF: Float get() = DeviceConfig.float("detection.detector.fallback_conf", 0.20f)

        /**
         * Passes 3-4: "no pass" — no confidence threshold at all. Not literally 0: at 0 every anchor
         * qualifies and the output degenerates into near-zero-score boxes. 0.01 is indistinguishable
         * in practice (NMS does the real work) while staying interpretable.
         */
        private val NOPASS_CONF: Float get() = DeviceConfig.float("detection.detector.nopass_conf", 0.01f)

        /**
         * Score an anchor needs to even enter NMS, kept deliberately below the pass gate so the pass
         * confidence is the only thing that decides what survives. Floored to the pass confidence on
         * the no-threshold rungs, so passes 3-4 are genuinely unthresholded rather than clamped here.
         *
         * Because this floor is always <= the pass gate, and NMS processes boxes in descending score
         * order, these extra low-score candidates can never suppress a real one — they are simply
         * dropped by the gate afterwards. The result is identical to thresholding up front.
         */
        private val CANDIDATE_CONFIDENCE_THRESHOLD: Float get() = DeviceConfig.float("detection.detector.candidate_confidence_threshold", 0.05f)

        // The shifted window's geometry, its span text and how it is built all live in
        // [ShiftWindows] — one definition, shared by passes 3/4 and by the re-sample rung's per-frame
        // escalation. See [SHIFT_FRAC] and [SHIFT_PAD_FRAC] there.

        private val IOU_THRESHOLD: Float get() = DeviceConfig.float("detection.detector.iou_threshold", 0.45f)
        private val MAX_DET: Int get() = DeviceConfig.int("detection.detector.max_det", 300)

        /**
         * The model gets stuck on shiny reflections — dashboard sheen, windshield glare, wet tarmac,
         * the reflective hood — and boxes a huge slab of the frame, often while missing the actual
         * post. The test is relative to each image's own area, so it adapts to any resolution, aspect
         * ratio or crop rather than using pixel sizes; on a shifted pass it is measured against the
         * window the model actually saw. Measured over 900 segmented frames: 133 of 282 raw
         * detections (47%) covered >=15% of their frame, at a mean confidence (0.567) no lower than
         * genuine posts — confidence does not separate them, area does. Real posts land under 5% of
         * frame area, plus a high-confidence 5-15% band (mean 0.729) of legitimate close-ups, so the
         * cut goes at 15%. Set to 0 to see raw model output.
         */
        private val MAX_AREA_FRAC: Double get() = DeviceConfig.double("detection.detector.max_area_frac", 0.15)

        /**
         * How many guardrail-passing boards a run must have before it moves on to Stage 3.
         *
         * "Guardrail-passing" means a crop that survived EVERY filter this stage applies — the pass
         * confidence gate, NMS, the area guard, the minimum-size check and the clarity gate. Only
         * those are counted and only those are written, so this is a floor on genuinely usable
         * boards rather than on raw model output.
         *
         * A FLOOR, never a cap. Falling below it makes the detector work harder — re-scanning the clip
         * more densely until enough usable boards exist. Rising above it costs nothing and truncates
         * nothing: a clip that yields forty boards keeps all forty, and the only thing the floor stops
         * is further re-scanning, which by then has nothing left to find.
         *
         * Held ONE ABOVE `ImagePrepStage.MAX_BOARDS_FORWARD`, which is how many canvases Stage 3 carries
         * forward per board and therefore how many the reader gets. The two used to be equal, on the
         * argument that a floor promising fewer boards than the reader wants is a lie. It is the same
         * argument that makes them differ now: Stage 3 can still LOSE a crop between here and there — its
         * second clarity gate, or the `MAX_REGIONS` discard throwing out a canvas that resolved to more
         * than five panels — and an exactly-matched floor means every such loss lands the reader one
         * image short with nothing in reserve. The extra crop is that reserve, and one is enough because
         * a run that loses several has bigger problems than the floor.
         *
         * Stage 5 settles each box by pooling that box's reads and taking the mode, so this is really a
         * floor on how much evidence that vote gets.
         */
        private val MIN_BOARDS: Int get() = DeviceConfig.int("detection.detector.min_boards", 15)

        /**
         * Pass 5: adaptive re-sampling, escalating.
         *
         * A clip that ends up short of [MIN_BOARDS] usable boards has usually driven past the board
         * quickly, or produced crops too blurred to read — either way the base grid was too coarse.
         * The windows around possible hits are re-sampled at [REFINE_FPS_START], and if
         * that still leaves the run short the rate climbs by [REFINE_FPS_STEP] and it looks again,
         * up to [REFINE_FPS_MAX] — so the rungs are 20, 22, 24, 26, 28, 30 fps.
         *
         * The start sits ABOVE the base grid rather than at it: a re-scan at the rate already covered
         * would decode nothing new, since Stage 1 dedupes against what is on disk.
         *
         * Each round only decodes timestamps no earlier round already covered (Stage 1 dedupes
         * against everything extracted so far), so climbing the rate fills in the gaps between
         * existing frames instead of re-reading them.
         *
         * With no hits, first probe isolated gaps between base frames. Only a promising hit
         * triggers the denser windowed search; an empty probe ends the ladder.
         */
        private val REFINE_FPS_START: Double get() = DeviceConfig.double("detection.detector.refine_fps_start", 20.0)
        private val REFINE_FPS_STEP: Double get() = DeviceConfig.double("detection.detector.refine_fps_step", 2.0)
        private val REFINE_FPS_MAX: Double get() = DeviceConfig.double("detection.detector.refine_fps_max", 30.0)
        private val REFINE_WINDOW_US: Long get() = DeviceConfig.long("detection.detector.refine_window_us", 2_000_000L)

        /**
         * Matches Ultralytics' default gray letterbox pad colour.
         *
         * Aliased to [NEUTRAL_GREY] rather than repeated: the shifted-window overhang fills with the
         * same value, and the padding only does its job if the two cannot drift apart.
         */
        private val LETTERBOX_FILL: Int get() = NEUTRAL_GREY

        /** The `_bNN` board token in a crop's filename, for stripping a stale one before restamping. */
        private val BOARD_TOKEN = Regex("""_b\d\d(?=_d)""")
    }

    private val interpreter: Interpreter by lazy {
        // Normally the board-detection model is downloaded once into the app's private storage (see
        // ModelRepository / ModelDownloadManager) and is not shipped in the APK. A bundled test model
        // (ModelConfig.Yolo.BUNDLED_ASSET) overrides that when set — see loadMappedFile.
        val modelBuffer = loadMappedFile(context)
        // Cap to (cores - 1) so a core stays free for the UI RenderThread (avoids input-dispatch ANRs).
        val threads = (Runtime.getRuntime().availableProcessors() - 1).coerceAtLeast(1)
        val options = Interpreter.Options().apply { setNumThreads(threads) }
        Interpreter(modelBuffer, options).also {
            val inShape = it.getInputTensor(0).shape().joinToString("x")
            val outShape = it.getOutputTensor(0).shape().joinToString("x")
            Log.i(TAG, "board model=${ModelConfig.Yolo.ACTIVE_VERSION} " +
                "source=${ModelConfig.Yolo.BUNDLED_ASSET?.let { a -> "assets/$a" } ?: "downloaded"} " +
                "in=$inShape out=$outShape")
        }
    }

    /**
     * Memory-maps the board-detection `.tflite` for the interpreter.
     *
     * [ModelConfig.Yolo.BUNDLED_ASSET] wins when set: the model is mapped straight out of the APK's
     * asset section (which is why it must stay uncompressed — see `noCompress` in build.gradle.kts),
     * and the downloaded file is not touched at all. Otherwise the downloaded model is mapped as before.
     *
     * Either way the layout is read off the tensors at inference time, so a bundled model may be NHWC
     * or NCHW, float or quantized, with normalised or pixel-space boxes, exactly like a downloaded one.
     */
    private fun loadMappedFile(context: Context): MappedByteBuffer {
        val asset = ModelConfig.Yolo.BUNDLED_ASSET
        if (asset != null) {
            context.assets.openFd(asset).use { fd ->
                FileInputStream(fd.fileDescriptor).use { fis ->
                    return fis.channel.map(
                        FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength
                    )
                }
            }
        }
        val file = ModelRepository.yoloFile(context)
        FileInputStream(file).use { fis ->
            val channel: FileChannel = fis.channel
            return channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
        }
    }

    /**
     * How an image was letterboxed onto the square model input — everything needed to map a box on
     * the network canvas back to source pixels.
     *
     * [srcW]/[srcH] are the dimensions of the image actually FED TO THE MODEL, so on a shifted pass
     * the area guard is measured against the window the model saw, not the whole frame.
     */
    private data class Fit(
        val scale: Float,
        val padX: Float,
        val padY: Float,
        val srcW: Int,
        val srcH: Int
    )

    /** A decoded box in source-image pixel space, before it is rounded to a [Rect]. */
    private data class Candidate(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val confidence: Float
    ) {
        val width: Float get() = x2 - x1
        val height: Float get() = y2 - y1
    }

    /** What one inference produced: the surviving boxes, and how many the area guard rejected. */
    private data class DecodeResult(val boxes: List<Candidate>, val dropped: Int)

    private data class InferenceOutput(
        val head: Array<FloatArray>,
        val anchors: Int,
        val netSize: Int,
        val fit: Fit
    )

    /** Which half of the frame a shifted pass looks at. */
    // The window side lives with the window geometry, next to the offsets it selects: see [ShiftSide].

    /** Per-pass tally, for the Logcat ladder report. */
    private class PassStats(val pass: DetectPass, val conf: Float, val scope: String) {
        var frames = 0
        var hitFrames = 0
        var detections = 0
        /** Boxes the area guard rejected. */
        var dropped = 0
        /** Boxes the clarity gate rejected — a real board, too blurred or flat to read. */
        var blurred = 0
        var elapsedMs = 0L
    }

    // ---------------------------------------------------------------------------------------- //
    // The ladder
    // ---------------------------------------------------------------------------------------- //

    override suspend fun detectBoards(
        extraction: FrameExtractionResult,
        refiner: FrameRefiner?
    ): BoardDetectionResult {
        // Crops live in a sibling dir keyed by the SAME video id as the frames dir → semantic link.
        //   frames/<videoId>/frame_00000.jpg   ->   crops/<videoId>/crop_f00000_d00_….png
        val cropsDir = File(cropsRootDir(), extraction.videoId).apply { mkdirs() }

        // Every box the model emitted, gate-rejected ones included: board identity is resolved over all
        // of them, and the BoardDetections the pipeline consumes are built from these at the end, once
        // the ids are final and the crops carry them.
        val records = mutableListOf<TrackedDetection>()
        val stats = mutableListOf<PassStats>()
        // Total across every rung so far — the ladder's gates read this, exactly as the script does.
        fun hitFrames(): Int = stats.sumOf { it.hitFrames }

        Log.d(
            TAG,
            "ladder over ${extraction.frames.size} frame(s): " +
                "conf $CONF_THRES / $FALLBACK_CONF / $NOPASS_CONF (F1 peak 0.382, recall cliff " +
                "$CONF_CLIFF, model max ~$MODEL_MAX_CONF), IoU $IOU_THRESHOLD, " +
                "area guard ${(MAX_AREA_FRAC * 100).roundToInt()}%, " +
                "shifted windows ${ShiftWindows.spansLabel()}"
        )

        // Both full-frame thresholds use the same model output. Low-confidence crops are still
        // accepted only if the entire high-confidence pass found nothing usable.
        stats += runFullFramePasses(extraction.frames, cropsDir, records)

        // Passes 3 and 4 — shifted windows at no threshold. ONE stage: the gate is evaluated once,
        // before either runs, so the left shift finding something does not skip the right half.
        if (hitFrames() == 0) {
            stats += runPass(
                DetectPass.SHIFT_LEFT, extraction.frames, NOPASS_CONF, ShiftSide.LEFT, cropsDir, records
            )
            stats += runPass(
                DetectPass.SHIFT_RIGHT, extraction.frames, NOPASS_CONF, ShiftSide.RIGHT, cropsDir, records
            )
        }

        // Board identity has to be resolved BEFORE pass 5, because pass 5's floor is per board and
        // there is nothing to evaluate it against otherwise.
        var boards = BoardTracker.assign(records)
        renameCrops(records)

        // Pass 5 — re-sample, climbing the rate until EVERY board the run shows has [MIN_BOARDS] usable
        // crops of its own. Per board rather than per run: a forecourt with a bright board and a dim one
        // reaches the floor on the bright one alone, and stopping there leaves the dim board's prices
        // unread — which is the whole reason the two are told apart in the first place.
        //
        // Needs Stage 1 to produce frames that don't exist yet, so it only runs when a refiner was
        // supplied. Every crop counted has already cleared every guardrail including the clarity gate,
        // so "too few" means too few READABLE boards, not too few raw detections.
        val refineFrames = mutableListOf<FrameFile>()
        if (refiner != null) {
            var covered = extraction
            if (records.none { it.written }) {
                val probeTimes = SparseProbeSchedule.timestamps(
                    extraction.frames.map { it.timestampUs }, extraction.meta.durationUs, REFINE_FPS_MAX
                )
                Log.d(TAG, "sparse probe: ${probeTimes.size} gap(s) at $REFINE_FPS_MAX fps")
                if (probeTimes.isNotEmpty()) {
                    // A one-microsecond window on each side includes exactly the selected grid tick.
                    val added = refiner.extractWindows(covered, probeTimes, 1L, REFINE_FPS_MAX, newestFirst = true)
                    covered = covered.copy(frames = covered.frames + added)
                    refineFrames += added
                    if (added.isNotEmpty()) {
                        stats += runRefinePass(added, cropsDir, records, REFINE_FPS_MAX, true, 0)
                        boards = BoardTracker.assign(records)
                        renameCrops(records)
                    }
                }
            }

            // The sparse probe did not reveal a candidate: no whole-clip dense sweep. Weak tracks
            // are candidates too, so their timestamps still receive the usual targeted refinement.
            for (fps in refineRungs()) {
                val strongNow = boards.filter { !it.weak }
                val deficient = strongNow.filter { it.crops < MIN_BOARDS }
                if (strongNow.isNotEmpty() && deficient.isEmpty()) break   // every board has its own bag

                val targets: List<Pair<List<Long>, Int>> = if (strongNow.isEmpty()) {
                    val possibleHits = records.filter { it.written }.map { it.timestampUs }.distinct()
                    if (possibleHits.isEmpty()) break
                    listOf(possibleHits to 0)
                } else {
                    deficient.map { b -> b.detections.map { it.timestampUs } to b.crops }
                }

                var gainedAny = false
                for ((centers, have) in targets) {
                    // Newest-first: later frames are nearer the board and so clearer, so filling the
                    // bag from the end reaches a usable set sooner and wastes less work on early frames
                    // that would only be discarded downstream.
                    //
                    // It used to justify itself with "they are the ones Stage 4 keeps (it reads the
                    // LATEST board images)", which was not true of the code: Stage 4 trimmed its pool
                    // from the FRONT of an ascending-time list. The reason above stands on its own —
                    // a clearer crop is worth having whatever picks the pool — and Stage 4 now selects
                    // by confidence-then-newest, which is the order these frames feed anyway.
                    val added = refiner.extractWindows(covered, centers, REFINE_WINDOW_US, fps, newestFirst = true)
                    if (added.isEmpty()) continue      // this rate offered nothing new here
                    covered = covered.copy(frames = covered.frames + added)
                    refineFrames += added
                    stats += runRefinePass(added, cropsDir, records, fps, false, have)
                    gainedAny = true
                }
                if (!gainedAny) continue

                // Re-resolved from scratch after every rung rather than patched incrementally: the
                // tracker walks detections in time order while this pass walks frames newest-first, and a
                // rung that fills the gap between two fragments of one board should MERGE them, which a
                // from-scratch pass does for free.
                boards = BoardTracker.assign(records)
                renameCrops(records)
            }
        }

        val strong = boards.filter { !it.weak }
        val short = strong.filter { it.crops < MIN_BOARDS }
        if (boards.isNotEmpty()) {
            Log.d(
                TAG,
                "${boards.size} board(s): ${boards.joinToString { it.summary() }}" +
                    (if (boards.size > strong.size) "  (${boards.size - strong.size} weak)" else "")
            )
        }
        if (short.isNotEmpty()) {
            Log.w(
                TAG,
                "short after re-sampling ran out at ${REFINE_FPS_MAX.roundToInt()} fps: " +
                    short.joinToString { "b%02d %d/%d".format(it.boardId, it.crops, MIN_BOARDS) } +
                    ". Continuing with what there is."
            )
        } else if (strong.isEmpty()) {
            Log.w(
                TAG,
                "no board held together for ${BoardTracker.WEAK_TRACK_DETS} detections — " +
                    "${records.count { it.written }} crop(s) from ${boards.size} weak track(s). " +
                    "Continuing with what there is."
            )
        }

        // Time order, not discovery order: pass 5's frames carry indices that continue past the base
        // grid, so sorting by index alone would file them after frames they actually precede.
        val detections = toDetections(records)
        logLadder(stats, detections, cropsDir)
        return BoardDetectionResult(detections, refineFrames, boards)
    }

    /**
     * The re-sample rates to try, in order: 20, 22, 24 … 28, then 30.
     *
     * [REFINE_FPS_MAX] is appended explicitly rather than left to the step, so the ceiling is always
     * actually tried — a step that did not divide the range evenly would otherwise stop short of it.
     */
    private fun refineRungs(): List<Double> = buildList {
        var fps = REFINE_FPS_START
        while (fps < REFINE_FPS_MAX) {
            add(fps)
            fps += REFINE_FPS_STEP
        }
        add(REFINE_FPS_MAX)
    }

    private fun runFullFramePasses(
        frames: List<FrameFile>,
        cropsDir: File,
        out: MutableList<TrackedDetection>
    ): List<PassStats> {
        val high = PassStats(DetectPass.HIGH, CONF_THRES, "full frame")
        val pendingLow = mutableListOf<Pair<FrameFile, DecodeResult>>()
        val startMs = SystemClock.elapsedRealtime()

        for (frameFile in frames) {
            val bitmap = BitmapFactory.decodeFile(frameFile.file.absolutePath)
            if (bitmap == null) {
                Log.w(TAG, "Could not decode frame ${frameFile.index} at ${frameFile.file}")
                continue
            }
            high.frames++
            try {
                val inference = inferenceOutput(bitmap)
                val result = decode(inference.head, inference.anchors, inference.netSize, inference.fit, CONF_THRES)
                high.dropped += result.dropped
                if (result.boxes.isNotEmpty()) {
                    val found = saveCrops(bitmap, result.boxes, cropsDir, frameFile, DetectPass.HIGH, high)
                    out += found
                    val written = found.count { it.written }
                    if (written > 0) {
                        high.hitFrames++
                        high.detections += written
                    }
                }
                if (high.hitFrames == 0) {
                    // Store only decoded boxes, not hundreds of full model-output tensors or bitmaps.
                    pendingLow += frameFile to decode(
                        inference.head, inference.anchors, inference.netSize, inference.fit, FALLBACK_CONF
                    )
                } else {
                    pendingLow.clear()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Detection failed on frame ${frameFile.index} (pass ${DetectPass.HIGH.label})", t)
            } finally {
                bitmap.recycle()
            }
        }
        high.elapsedMs = SystemClock.elapsedRealtime() - startMs
        Log.d(TAG, "pass ${high.pass.label}: ${high.frames} frames, ${high.detections} boards " +
            "(${high.hitFrames} frames), ${high.dropped} oversized, ${high.blurred} unclear, " +
            "${high.elapsedMs}ms  [full frame, low-confidence predictions cached]")
        if (high.hitFrames > 0) return listOf(high)

        val low = PassStats(DetectPass.LOW, FALLBACK_CONF, "cached full-frame predictions")
        val lowStartMs = SystemClock.elapsedRealtime()
        for ((frameFile, result) in pendingLow) {
            low.frames++
            low.dropped += result.dropped
            if (result.boxes.isEmpty()) continue
            val bitmap = BitmapFactory.decodeFile(frameFile.file.absolutePath)
            if (bitmap == null) {
                Log.w(TAG, "Could not decode frame ${frameFile.index} at ${frameFile.file}")
                continue
            }
            try {
                val found = saveCrops(bitmap, result.boxes, cropsDir, frameFile, DetectPass.LOW, low)
                out += found
                val written = found.count { it.written }
                if (written > 0) {
                    low.hitFrames++
                    low.detections += written
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Detection failed on frame ${frameFile.index} (pass ${DetectPass.LOW.label})", t)
            } finally {
                bitmap.recycle()
            }
        }
        low.elapsedMs = SystemClock.elapsedRealtime() - lowStartMs
        Log.d(TAG, "pass ${low.pass.label}: ${low.frames} frames, ${low.detections} boards " +
            "(${low.hitFrames} frames), ${low.dropped} oversized, ${low.blurred} unclear, " +
            "${low.elapsedMs}ms  [cached full-frame predictions]")
        return listOf(high, low)
    }

    /**
     * One rung: run the model over [frames] at [conf], optionally on a shifted window only, writing a
     * crop per surviving box. Appends every detection to [out].
     */
    private fun runPass(
        pass: DetectPass,
        frames: List<FrameFile>,
        conf: Float,
        side: ShiftSide?,
        cropsDir: File,
        out: MutableList<TrackedDetection>,
        rateLabel: String? = null
    ): PassStats {
        val scope = when {
            rateLabel != null -> "full frame @ $rateLabel"
            side == null -> "full frame"
            else -> "shifted ${side.name.lowercase()} ${ShiftWindows.spanLabel(side)}"
        }
        val stats = PassStats(pass, conf, scope)
        val startMs = SystemClock.elapsedRealtime()

        for (frameFile in frames) {
            val bitmap = BitmapFactory.decodeFile(frameFile.file.absolutePath)
            if (bitmap == null) {
                Log.w(TAG, "Could not decode frame ${frameFile.index} at ${frameFile.file}")
                continue
            }
            stats.frames++
            try {
                val decoded = detectOn(bitmap, conf, side)
                stats.dropped += decoded.dropped
                if (decoded.boxes.isEmpty()) continue

                // Every box is recorded for tracking; only the ones whose crop cleared the clarity
                // gate count as a hit, because only those can be read.
                val records = saveCrops(bitmap, decoded.boxes, cropsDir, frameFile, pass, stats)
                out += records
                val written = records.count { it.written }
                if (written > 0) {
                    stats.hitFrames++
                    stats.detections += written
                }
            } catch (t: Throwable) {
                // `Throwable`, so an `OutOfMemoryError` costs THIS frame and not the pass.
                //
                // The pass holds a decoded UHD frame, a letterboxed 640 copy of it, a direct
                // `ByteBuffer` for the interpreter and a crop per detection, so OOM is its
                // characteristic failure and it is an `Error`. Skipping one frame is a good answer:
                // the `finally` frees this frame immediately — which is usually the memory the next
                // frame needs — and a board is in shot across dozens of frames, so the ladder still
                // finds it. Left as `Exception` the whole pass ended, and a pass that ends early is
                // read by the escalation ladder as "nothing here", which sends the run down the
                // low-confidence rungs looking for a board it had already found.
                //
                // A model that will not load fails differently and should: `interpreter` is a `lazy`,
                // so `UnsatisfiedLinkError` or `NoClassDefFoundError` arrives on the first frame and
                // then on every frame after it. Every one logs and the pass reports no detections,
                // which `PriceBoardPipeline` turns into an aborted run rather than `board_count: 0`.
                Log.e(TAG, "Detection failed on frame ${frameFile.index} (pass ${pass.label})", t)
            } finally {
                bitmap.recycle() // free the full frame's RAM immediately
            }
        }

        stats.elapsedMs = SystemClock.elapsedRealtime() - startMs
        Log.d(
            TAG,
            "pass ${pass.label}: ${stats.frames} frames, ${stats.detections} boards " +
                "(${stats.hitFrames} frames), ${stats.dropped} oversized, ${stats.blurred} unclear, " +
                "${stats.elapsedMs}ms  [$scope]"
        )
        return stats
    }

    /**
     * One inference over [bitmap] at [conf] — the whole frame when [side] is null, otherwise only that
     * shifted window, with the boxes mapped back onto the full frame's coordinates.
     *
     * Cropping the window BEFORE the letterbox is the point of a shifted scope: the region then fills
     * the model's input instead of half of it, giving roughly 1.8x the effective resolution.
     *
     * The window now hangs [SHIFT_PAD_FRAC] of the frame width off its edge, filled with grey, so the
     * offset it comes back with is NEGATIVE on the left. That offset is applied to the boxes here and
     * puts some of them outside the frame — which is correct and expected; [saveCrops] is where they
     * are clamped back to real pixels.
     */
    private fun detectOn(bitmap: Bitmap, conf: Float, side: ShiftSide?): DecodeResult {
        if (side == null) return infer(bitmap, conf)

        val (window, geom) = ShiftWindows.materialise(bitmap, side)
        val result = try {
            // The guard divides by the window's REAL pixels, not the padded canvas — see
            // [ShiftGeometry.realArea].
            infer(window, conf, realAreaOverride = geom.realArea)
        } finally {
            window.recycle()
        }
        return result.copy(
            boxes = result.boxes.map { it.copy(x1 = it.x1 + geom.x0, x2 = it.x2 + geom.x0) }
        )
    }

    /**
     * The re-sample rung: runs a MINI-LADDER over each newly extracted frame, rather than the single
     * full-frame pass the earlier rungs use.
     *
     * Per frame: the cheap full-frame pass at [FALLBACK_CONF] first, and — only if that found nothing
     * ON THIS FRAME — both shifted windows at [NOPASS_CONF]. Without this, re-sampling applies a test
     * strictly WEAKER than the one that already failed: reaching a blind whole-clip round means the
     * shifted passes found nothing on the base grid, so repeating only the full-frame pass on denser
     * frames could recover a board that fell between frames but never one that is merely small or off
     * to one side.
     *
     * The gate is per FRAME, not per pass: the earlier rungs gate on the whole clip because they are
     * deciding whether the clip is hopeless, whereas here every frame is already a targeted retry and
     * is worth its own escalation. Frames the cheap pass handles cost one inference; only the ones it
     * misses cost three.
     *
     * Worth stating because it is MORE than the ladder table describes: pass 5 is summarised as "whole
     * frame at [FALLBACK_CONF]", and the shifted retry is a deliberate addition to it, kept because
     * reaching pass 5 at all means the shifted passes already found nothing on the base grid — so
     * repeating only the full-frame test on denser frames could recover a board that fell BETWEEN
     * frames, but never one that is merely small or off to one side.
     *
     * Both shifted windows always run together, as they do in the base ladder, and their boxes are
     * merged through NMS before cropping — the two windows overlap in the middle 10% of the frame, so
     * a board sitting there would otherwise be written out twice.
     */
    private fun runRefinePass(
        frames: List<FrameFile>,
        cropsDir: File,
        out: MutableList<TrackedDetection>,
        fps: Double,
        blind: Boolean,
        /**
         * Crops the board this rung is densifying for ALREADY has. The rung stops once `have + gained`
         * reaches [MIN_BOARDS], so a board that is already full is never worked on while another is
         * still short.
         *
         * Attribution during the rung is by whose windows the frames came from — an estimate, not the
         * verdict, since the frames have not been tracked yet. `detectBoards` re-runs the tracker after
         * every rung, so this only ever decides when to stop WITHIN one rung.
         */
        have: Int
    ): PassStats {
        // Both the log label and the crops' filename tag come from the rung's own rate, so they cannot
        // disagree with the rate the model actually ran at — which is how `_r15fps` came to be stamped
        // on crops found at 20-30 fps.
        val rateLabel = "%.0f fps%s".format(fps, if (blind) ", sparse probe" else "")
        val suffix = "_r%.0ffps".format(fps)
        val stats = PassStats(DetectPass.REFINE, FALLBACK_CONF, "mini-ladder @ $rateLabel")
        val startMs = SystemClock.elapsedRealtime()
        var escalated = 0
        var skipped = 0

        var gained = 0
        for (frameFile in frames) {
            // This board's bag is full. Frames arrive newest-first, so everything left is older than
            // what has already been collected and would be discarded by Stage 4's latest-N selection
            // anyway — there is nothing to gain by running the model over it.
            if (have + gained >= MIN_BOARDS) {
                skipped++
                continue
            }
            val bitmap = BitmapFactory.decodeFile(frameFile.file.absolutePath)
            if (bitmap == null) {
                Log.w(TAG, "Could not decode frame ${frameFile.index} at ${frameFile.file}")
                continue
            }
            stats.frames++
            try {
                // Rung 1 — full frame at the fallback confidence.
                val full = detectOn(bitmap, FALLBACK_CONF, null)
                stats.dropped += full.dropped
                var records = if (full.boxes.isEmpty()) emptyList() else {
                    saveCrops(bitmap, full.boxes, cropsDir, frameFile, DetectPass.REFINE, stats, suffix)
                }
                out += records
                var written = records.count { it.written }

                // Rungs 2+3 — shifted windows at no threshold, only when rung 1 came back empty.
                if (written == 0) {
                    escalated++
                    val shifted = mutableListOf<Candidate>()
                    for (side in listOf(ShiftSide.LEFT, ShiftSide.RIGHT)) {
                        val result = detectOn(bitmap, NOPASS_CONF, side)
                        stats.dropped += result.dropped
                        shifted += result.boxes
                    }
                    if (shifted.isNotEmpty()) {
                        val merged = nms(shifted, IOU_THRESHOLD).take(MAX_DET)
                        records = saveCrops(bitmap, merged, cropsDir, frameFile, DetectPass.REFINE, stats, suffix)
                        out += records
                        written = records.count { it.written }
                    }
                }

                if (written > 0) {
                    stats.hitFrames++
                    stats.detections += written
                    gained += written
                }
            } catch (t: Throwable) {
                // `Throwable`, for the reason the base pass above gives — and more so here. This rung
                // runs LAST, on the fullest heap of the detection stage, and it escalates to two extra
                // shifted-window inferences per frame that came back empty. It is the likeliest place
                // in the detector to meet an `OutOfMemoryError`, and it is also the most affordable
                // one to lose a frame in: everything this pass finds is a top-up on detections the
                // earlier rungs already made.
                Log.e(TAG, "Detection failed on frame ${frameFile.index} (pass refine)", t)
            } finally {
                bitmap.recycle() // free the full frame's RAM immediately
            }
        }

        stats.elapsedMs = SystemClock.elapsedRealtime() - startMs
        Log.d(
            TAG,
            "pass refine: ${stats.frames} frames, ${stats.detections} boards " +
                "(${stats.hitFrames} frames), $escalated escalated to shifted windows, " +
                "${stats.dropped} oversized, ${stats.blurred} unclear" +
                (if (skipped > 0) ", $skipped older frames skipped (target board full)" else "") +
                ", ${stats.elapsedMs}ms  [${stats.scope}]"
        )
        return stats
    }

    // ---------------------------------------------------------------------------------------- //
    // Inference
    // ---------------------------------------------------------------------------------------- //

    /** Letterboxes [image], runs the model, and decodes the head into source-pixel boxes. */
    private fun infer(image: Bitmap, conf: Float, realAreaOverride: Double? = null): DecodeResult {
        val output = inferenceOutput(image)
        return decode(output.head, output.anchors, output.netSize, output.fit, conf, realAreaOverride)
    }

    private fun inferenceOutput(image: Bitmap): InferenceOutput {
        val inputTensor = interpreter.getInputTensor(0)
        val inputShape = inputTensor.shape()
        val nchw = inputShape[1] == 3 // [1,3,S,S] vs [1,S,S,3]
        val netSize = if (nchw) inputShape[2] else inputShape[1]

        // Letterbox (resize preserving aspect ratio, pad to square) instead of a plain stretch,
        // so geometry matches what the model was trained/exported on.
        val (padded, fit) = letterbox(image, netSize)
        val inputBuffer = try {
            bitmapToBuffer(padded, inputTensor.dataType(), nchw)
        } finally {
            padded.recycle()
        }

        val (head, numAnchors) = runHead(inputBuffer)
        return InferenceOutput(head, numAnchors, netSize, fit)
    }

    /**
     * Runs the interpreter and normalises the detection head into `[row][anchor]`, whichever way the
     * export laid it out (`[1, rows, anchors]` or `[1, anchors, rows]`) and whichever dtype it uses.
     * Anchors always vastly outnumber rows, which is what tells the two layouts apart.
     *
     * @return the head plus its anchor count.
     */
    private fun runHead(input: ByteBuffer): Pair<Array<FloatArray>, Int> {
        val outputTensor = interpreter.getOutputTensor(0)
        val shape = outputTensor.shape() // [1, rows, anchors] or [1, anchors, rows]
        val channelsFirst = shape[1] <= shape[2]
        val rows = if (channelsFirst) shape[1] else shape[2]
        val numAnchors = if (channelsFirst) shape[2] else shape[1]
        val dtype = outputTensor.dataType()

        val head: Array<FloatArray> = if (dtype == DataType.FLOAT32) {
            if (channelsFirst) {
                val raw = Array(1) { Array(rows) { FloatArray(numAnchors) } }
                interpreter.run(input, raw)
                raw[0]
            } else {
                val raw = Array(1) { Array(numAnchors) { FloatArray(rows) } }
                interpreter.run(input, raw)
                Array(rows) { r -> FloatArray(numAnchors) { a -> raw[0][a][r] } }
            }
        } else {
            val isUnsigned = dtype == DataType.UINT8
            val scale = outputTensor.quantizationParams().scale
            val zeroPoint = outputTensor.quantizationParams().zeroPoint
            fun deq(b: Byte): Float {
                val i = if (isUnsigned) (b.toInt() and 0xFF) else b.toInt()
                return (i - zeroPoint) * scale
            }
            if (channelsFirst) {
                val raw = Array(1) { Array(rows) { ByteArray(numAnchors) } }
                interpreter.run(input, raw)
                Array(rows) { r -> FloatArray(numAnchors) { a -> deq(raw[0][r][a]) } }
            } else {
                val raw = Array(1) { Array(numAnchors) { ByteArray(rows) } }
                interpreter.run(input, raw)
                Array(rows) { r -> FloatArray(numAnchors) { a -> deq(raw[0][a][r]) } }
            }
        }
        return head to numAnchors
    }

    /**
     * Detection head -> NMS-filtered boxes in the source image's pixel space, plus the number the
     * area guard rejected.
     *
     * Ultralytics' Detect head applies `sigmoid()` to the class logit during export, so the score row
     * is already a 0-1 probability for both the float32 and int8 variants — do NOT sigmoid it again.
     */
    private fun decode(
        head: Array<FloatArray>,
        numAnchors: Int,
        netSize: Int,
        fit: Fit,
        conf: Float,
        /**
         * Area the guard divides by, when the image is not all real pixels.
         *
         * Null means "the whole image is real", which is true of every full-frame pass. A shifted pass
         * passes its window's real-pixel area: the canvas it hands over is about 31% grey overhang, and
         * measuring against that would loosen the guard by ~1.45x on the rungs that run at no
         * confidence threshold and depend on it most.
         */
        realAreaOverride: Double? = null
    ): DecodeResult {
        val scoreRow = head.size - 1 // single class: (cx, cy, w, h, score)
        // Never above the pass gate, so passes 3-4 stay genuinely unthresholded.
        val candidateFloor = min(CANDIDATE_CONFIDENCE_THRESHOLD, conf)

        val kept = ArrayList<Int>()
        for (a in 0 until numAnchors) {
            if (head[scoreRow][a].coerceIn(0f, 1f) >= candidateFloor) kept.add(a)
        }
        if (kept.isEmpty()) return DecodeResult(emptyList(), 0)

        // Box channels come out normalised as a fraction of the model's input canvas (the litert
        // export keeps them grid-relative for quantization stability). Guard the assumption rather
        // than hard-coding it, in case a raw pixel-space export is ever swapped in.
        var maxCoord = 0f
        for (a in kept) {
            for (r in 0..3) maxCoord = max(maxCoord, kotlin.math.abs(head[r][a]))
        }
        val coordScale = if (maxCoord <= 1.5f) netSize.toFloat() else 1f

        val candidates = ArrayList<Candidate>(kept.size)
        for (a in kept) {
            val cx = head[0][a] * coordScale
            val cy = head[1][a] * coordScale
            val w = head[2][a] * coordScale
            val h = head[3][a] * coordScale

            // Undo the letterbox (canvas pixels -> source pixels), clamped to the image the model saw.
            val x1 = ((cx - w / 2 - fit.padX) / fit.scale).coerceIn(0f, fit.srcW - 1f)
            val y1 = ((cy - h / 2 - fit.padY) / fit.scale).coerceIn(0f, fit.srcH - 1f)
            val x2 = ((cx + w / 2 - fit.padX) / fit.scale).coerceIn(0f, fit.srcW - 1f)
            val y2 = ((cy + h / 2 - fit.padY) / fit.scale).coerceIn(0f, fit.srcH - 1f)

            val cand = Candidate(x1, y1, x2, y2, head[scoreRow][a].coerceIn(0f, 1f))
            if (cand.width > 1f && cand.height > 1f) candidates.add(cand)
        }
        if (candidates.isEmpty()) return DecodeResult(emptyList(), 0)

        val survivors = nms(candidates, IOU_THRESHOLD)
            .filter { it.confidence >= conf } // the pass gate
            .take(MAX_DET)

        // Area guard — the thing that makes the low/no-threshold rungs usable. Measured against the
        // image the model actually saw, so a shifted pass is judged against its own window.
        val imageArea = (realAreaOverride ?: (fit.srcW.toDouble() * fit.srcH.toDouble()))
            .coerceAtLeast(1.0)
        var dropped = 0
        val boxes = ArrayList<Candidate>(survivors.size)
        for (c in survivors) {
            if (MAX_AREA_FRAC > 0 && (c.width.toDouble() * c.height.toDouble()) / imageArea >= MAX_AREA_FRAC) {
                dropped++
                continue
            }
            boxes.add(c)
        }
        // nms() already returns descending confidence; filtering preserves that order.
        return DecodeResult(boxes, dropped)
    }

    /** Greedy non-max suppression: keep the strongest box, drop everything overlapping it past [iou]. */
    private fun nms(candidates: List<Candidate>, iou: Float): List<Candidate> {
        val pool = candidates.sortedByDescending { it.confidence }.toMutableList()
        val selected = mutableListOf<Candidate>()
        while (pool.isNotEmpty()) {
            val best = pool.removeAt(0)
            selected.add(best)
            pool.removeAll { calculateIoU(best, it) > iou }
        }
        return selected
    }

    private fun calculateIoU(a: Candidate, b: Candidate): Float {
        val interW = max(0f, min(a.x2, b.x2) - max(a.x1, b.x1))
        val interH = max(0f, min(a.y2, b.y2) - max(a.y1, b.y1))
        val intersection = interW * interH
        val union = a.width * a.height + b.width * b.height - intersection
        return if (union > 0f) intersection / union else 0f
    }

    // ---------------------------------------------------------------------------------------- //
    // Crops
    // ---------------------------------------------------------------------------------------- //

    /**
     * Cuts each detected board out of [frame] and writes it as a PNG.
     *
     * Crops are written at their NATIVE pixel size — exactly the detected box, no resize and no
     * padding. Boards vary from a couple of dozen pixels wide to a few hundred, so files vary
     * accordingly; any resampling to a fixed model input belongs to the stage that consumes these,
     * so a board is never resampled twice. One file per detection, ranked by confidence (`d00` is the
     * strongest box in the frame). The `_o<w>x<h>` tag records the board's pixel size, so the
     * filename stays self-describing and sortable without opening the image.
     */
    private fun saveCrops(
        frame: Bitmap,
        boxes: List<Candidate>,
        cropsDir: File,
        frameFile: FrameFile,
        pass: DetectPass,
        stats: PassStats,
        /** Overrides [DetectPass.suffix] — the re-sample rung tags crops with the rate it ran at. */
        suffix: String = pass.suffix
    ): List<TrackedDetection> {
        val records = mutableListOf<TrackedDetection>()
        for ((rank, c) in boxes.withIndex()) {
            // Clamped to the frame, because a shifted window's boxes can land in its grey overhang: the
            // window hangs off the edge, so a box may be partly — or entirely — outside the frame.
            //
            // Partly outside is KEPT, trimmed to whatever real pixels it has: an object half off the
            // edge is still a board worth reading, and the padding is there precisely so the model can
            // see one. Entirely outside collapses to zero width or height here and is dropped by the
            // check below, which is the only honest answer — there are no pixels to crop.
            val left = c.x1.toInt().coerceIn(0, frame.width)
            val top = c.y1.toInt().coerceIn(0, frame.height)
            val right = (c.x1 + c.width).toInt().coerceIn(0, frame.width)
            val bottom = (c.y1 + c.height).toInt().coerceIn(0, frame.height)
            val w = right - left
            val h = bottom - top
            if (w < 2 || h < 2) continue

            val name = "crop_f%05d_d%02d_t%07.2fs_c%.2f_o%dx%d%s.png".format(
                frameFile.index, rank, frameFile.timestampUs / 1_000_000.0, c.confidence, w, h, suffix
            )
            val cropFile = File(cropsDir, name)
            val crop = Bitmap.createBitmap(frame, left, top, w, h)

            // ONE `finally` for the crop, covering every exit — the write, both `continue`s, and any
            // throw.
            //
            // The recycle used to be repeated per exit path, and two calls between the allocation and
            // the first of them were outside every one of them: `BoardTracker.signature` reads the
            // crop's pixels and `ClarityGate.score` runs a Laplacian over them, so either throwing
            // leaked the crop. The exception then reached `runPass`, which frees the FRAME and knows
            // nothing about the crop — and this loop runs per detection, per frame, per pass of the
            // escalation ladder, so a fault that repeats leaks a crop of a UHD frame hundreds of times
            // in one clip. That is the shape of an OOM that gets blamed on whatever allocates next.
            //
            // `crop !== frame` is kept: `createBitmap` returns the SOURCE instance when the region is
            // the whole bitmap, and recycling it here would pull the frame out from under the rest of
            // the pass.
            try {
                // Signed up for tracking BEFORE the clarity gate runs. Board identity is resolved over
                // ALL detections, gated or not: gating first would fragment a dim board's track on
                // every frame its crop failed the check, and hand it a fresh id each time it came back.
                val record = TrackedDetection(
                    frameIndex = frameFile.index,
                    timestampUs = frameFile.timestampUs,
                    box = Rect(left, top, right, bottom),
                    confidence = c.confidence,
                    signature = BoardTracker.signature(crop),
                    aspect = w.toFloat() / h,
                    rank = rank,
                    pass = pass
                )
                records.add(record)

                // GUARDRAIL — clarity. Scored on the crop's own pixels, before anything is written: a
                // board too blurred or too flat to read is not a usable board, so it must not be
                // written, must not count toward the run's tally, and must not stop the re-sampler from
                // looking for a better one. This is why the gate lives here rather than in Stage 3,
                // where it used to run: by then the frames were already chosen and a blurred run could
                // only shrink, never recover.
                val clarity = ClarityGate.score(crop)
                if (!clarity.isSuitable) {
                    stats.blurred++
                    Log.d(
                        TAG,
                        "frame ${frameFile.index} d%02d REJECTED by the clarity gate — %s (%dx%d)"
                            .format(rank, clarity.reason, w, h)
                    )
                    continue
                }

                FileOutputStream(cropFile).use { out -> crop.compress(Bitmap.CompressFormat.PNG, 100, out) }

                record.written = true
                record.cropFile = cropFile
            } finally {
                if (crop !== frame) crop.recycle() // free the crop's RAM once it's on disk
            }
        }
        return records
    }

    /**
     * Turns the run's detection records into the [BoardDetection]s the rest of the pipeline consumes.
     *
     * Built at the END rather than as the passes go, because the crop's path is not final until
     * [BoardTracker.assign] has run and [renameCrops] has stamped the board id into the filename.
     */
    private fun toDetections(records: List<TrackedDetection>): List<BoardDetection> =
        records.filter { it.written && it.cropFile != null }
            .sortedWith(compareBy({ it.timestampUs }, { it.rank }))
            // Numbered here, over the sorted list, so the id is unique by construction rather than by
            // an arithmetic assumption about frames and ranks that the shifted-window rungs break. The
            // ordering it carries is the same one it always did — see [BoardDetection.detectionId].
            .mapIndexed { index, r ->
                BoardDetection(
                    detectionId = index,
                    frameIndex = r.frameIndex,
                    rank = r.rank,
                    timestampUs = r.timestampUs,
                    cropPath = r.cropFile!!.absolutePath,
                    confidence = r.confidence,
                    boundingBox = r.box,
                    pass = r.pass,
                    boardId = r.boardId
                )
            }

    /**
     * Stamps the board id into each crop's filename: `crop_f00012_t0005.03s_b01_d00_…`.
     *
     * A filename token rather than a per-board sub-folder: every existing consumer looks up a crop by
     * its path, and the debug upload mirrors the folder flat, so a sub-folder would move files out from
     * under all of them while `_b01` leaves every path valid and makes a crop self-describing.
     *
     * Any existing token is STRIPPED before the current one goes on, rather than taken as proof the
     * file is already done. Ids are handed out by first sighting and [BoardTracker.assign] re-runs after
     * every re-sample rung, so a rung that turns up a new board earlier in the clip renumbers every
     * board after it. Skipping already-tagged files would leave those carrying the previous pass's id —
     * the one failure mode worse than no id at all, because it looks authoritative.
     */
    private fun renameCrops(records: List<TrackedDetection>): Int {
        var moved = 0
        for (r in records) {
            val current = r.cropFile ?: continue
            if (!r.written || r.boardId < 0) continue
            // Inserted immediately before the `_dNN` rank token: `crop_f00012_b01_d00_t0005.03s_…`.
            // The harness puts it after the timestamp instead, because its names run
            // `…_t0005.03s_d00_…`; the position that matters is the same — beside the rank, where a
            // reader looking for "which board" finds it next to "which box of that board".
            val stripped = current.name.replace(BOARD_TOKEN, "")
            val marker = "_d%02d".format(r.rank)
            val at = stripped.indexOf(marker)
            val renamed =
                if (at < 0) stripped
                else stripped.substring(0, at) + "_b%02d".format(r.boardId) + stripped.substring(at)
            if (renamed == current.name) continue
            val target = File(current.parentFile, renamed)
            if (!current.renameTo(target)) {
                Log.w(TAG, "could not rename ${current.name} to $renamed")
                continue
            }
            r.cropFile = target
            moved++
        }
        return moved
    }

    /** Root folder holding one UUID sub-folder of board crops per video (mirrors the frames root). */
    /**
     * Root of the working images Stages 1-3 write. **Files storage, not cache.**
 *
     * `cacheDir` is space Android reclaims whenever the device is short, with no warning and no way to
     * object. That is survivable for a run finishing in seconds, but not for one whose images must outlive
     * a queue of other videos, or an offline spell waiting on OCR and upload. Losing a board that was
     * photographed correctly because the OS wanted the space is exactly the failure this pipeline must not
     * have, so the working set lives where only this app deletes it (see RunScratch).
     */
    private fun cropsRootDir(): File = File(context.filesDir, "crops").apply { mkdirs() }

    // ---------------------------------------------------------------------------------------- //
    // Pre-processing
    // ---------------------------------------------------------------------------------------- //

    /**
     * Resizes [src] preserving aspect ratio and pads it to a square [target] with Ultralytics'
     * default 114 grey. Returns the canvas plus the [Fit] needed to map detections back.
     */
    private fun letterbox(src: Bitmap, target: Int): Pair<Bitmap, Fit> {
        val scale = min(target.toFloat() / src.width, target.toFloat() / src.height)
        val newW = (src.width * scale).roundToInt().coerceAtLeast(1)
        val newH = (src.height * scale).roundToInt().coerceAtLeast(1)
        val padX = (target - newW) / 2f
        val padY = (target - newH) / 2f

        // Both intermediates are freed on a throw. `resized` is a downscale of a UHD frame and
        // `canvasBitmap` is the model's 640 input, and neither had an owner on a failure path: this
        // returns a Pair, so a caller that receives an exception holds no reference to either and can
        // never free them. `detectOn`'s `finally` frees only the window it was handed, which on a
        // throw is a window it never received.
        //
        // It matters because of where this sits: once per frame per pass of the escalation ladder, so
        // up to five times per frame on a clip the ladder works hard on.
        val resized = resample(src, newW, newH)
        val canvasBitmap = try {
            Bitmap.createBitmap(target, target, Bitmap.Config.ARGB_8888).also { canvas ->
                try {
                    Canvas(canvas).apply {
                        drawColor(Color.rgb(LETTERBOX_FILL, LETTERBOX_FILL, LETTERBOX_FILL))
                        drawBitmap(resized, padX, padY, Paint(Paint.FILTER_BITMAP_FLAG))
                    }
                } catch (t: Throwable) {
                    canvas.recycle()
                    throw t
                }
            }
        } finally {
            if (resized !== src) resized.recycle()
        }

        return canvasBitmap to Fit(scale, padX, padY, src.width, src.height)
    }

    /**
     * Resizes to [w] x [h]. Downscales by repeated halving before the final step, because a single
     * bilinear pass over a >2x reduction point-samples and aliases away thin digit strokes — the
     * halving chain averages instead, which is what the reference pipeline's INTER_AREA does and
     * what the confidence thresholds above were measured against. Upscales are a plain bilinear step
     * (INTER_LINEAR), same as the reference.
     */
    private fun resample(src: Bitmap, w: Int, h: Int): Bitmap {
        if (src.width == w && src.height == h) return src
        var current = src
        // The halving chain frees its own intermediate on a throw, which the `finally` is for.
        //
        // Each `current` past the first is an intermediate this function alone owns — nobody else has
        // ever seen it — so a `createScaledBitmap` that fails part-way down the chain used to leak the
        // step before it. On a UHD frame the first intermediate is around 8 MB, and this runs once per
        // frame per pass; a device meeting the same failure on frame after frame leaks its way to a
        // process kill. `src` is excluded because it belongs to the caller, and `out` because it is
        // the return value.
        try {
            while (current.width >= w * 2 && current.height >= h * 2) {
                val halved = Bitmap.createScaledBitmap(
                    current, max(w, current.width / 2), max(h, current.height / 2), true
                )
                if (current !== src) current.recycle()
                current = halved
            }
            val out = Bitmap.createScaledBitmap(current, w, h, true)
            if (current !== src && current !== out) current.recycle()
            return out
        } catch (t: Throwable) {
            if (current !== src) current.recycle()
            throw t
        }
    }

    /**
     * Converts a [bitmap] already sized to the model's input dimensions into the raw ByteBuffer the
     * interpreter expects, matching the input tensor's type (FLOAT32 normalized to 0-1, UINT8 raw,
     * INT8 shifted by -128) and its layout ([nchw] planar RGB vs interleaved NHWC).
     */
    private fun bitmapToBuffer(bitmap: Bitmap, inputType: DataType, nchw: Boolean): ByteBuffer {
        val bytesPerChannel = if (inputType == DataType.FLOAT32) 4 else 1
        val buffer = ByteBuffer.allocateDirect(bitmap.width * bitmap.height * 3 * bytesPerChannel)
        buffer.order(ByteOrder.nativeOrder())
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        fun put(value: Int) {
            when (inputType) {
                DataType.FLOAT32 -> buffer.putFloat(value / 255.0f)
                DataType.UINT8 -> buffer.put(value.toByte())
                else -> buffer.put((value - 128).toByte()) // INT8 signed
            }
        }

        if (nchw) {
            // Planar: every R, then every G, then every B.
            for (shift in intArrayOf(16, 8, 0)) {
                for (pixel in pixels) put((pixel shr shift) and 0xFF)
            }
        } else {
            for (pixel in pixels) {
                put((pixel shr 16) and 0xFF)
                put((pixel shr 8) and 0xFF)
                put(pixel and 0xFF)
            }
        }
        buffer.rewind()
        return buffer
    }

    // ---------------------------------------------------------------------------------------- //
    // Reporting (Logcat only)
    // ---------------------------------------------------------------------------------------- //

    /** The script's per-video pass table, as a Logcat block: which rung actually delivered. */
    private fun logLadder(stats: List<PassStats>, detections: List<BoardDetection>, cropsDir: File) {
        val producing = stats.filter { it.detections > 0 }
        val report = buildString {
            appendLine("-- detection ladder ------------------------------------------------")
            appendLine("pass            conf  frames  boards  hit fr  rejected  time(ms)  scope")
            for (p in stats) {
                appendLine(
                    "%-14s%6.2f%8d%8d%8d%10d%10d  %s".format(
                        p.pass.label, p.conf, p.frames, p.detections, p.hitFrames, p.dropped, p.elapsedMs, p.scope
                    )
                )
            }
            appendLine(
                "%-14s%6s%8d%8d%8d%10d%10d".format(
                    "TOTAL", "", stats.sumOf { it.frames }, stats.sumOf { it.detections },
                    stats.sumOf { it.hitFrames }, stats.sumOf { it.dropped }, stats.sumOf { it.elapsedMs }
                )
            )
            append("detected by   : ")
            appendLine(
                if (producing.isEmpty()) "nothing — every pass came back empty"
                else producing.joinToString { "${it.pass.label} (${it.detections})" }
            )
            if (detections.isNotEmpty()) {
                val confs = detections.map { it.confidence }
                appendLine(
                    "confidence    : min %.3f / mean %.3f / max %.3f".format(
                        confs.min(), confs.average(), confs.max()
                    )
                )
            }
            append("crops         : ${cropsDir.absolutePath}")
        }
        Log.d(TAG, report)
    }
}
