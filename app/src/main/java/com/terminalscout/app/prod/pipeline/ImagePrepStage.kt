package com.terminalscout.app.prod.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import com.terminalscout.app.prod.config.ModelConfig
import com.terminalscout.app.prod.download.ModelRepository
import com.terminalscout.app.prod.config.DeviceConfig
import org.opencv.android.OpenCVLoader
import kotlinx.coroutines.CancellationException
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * One price region found on a prepped canvas.
 *
 * [box] is in the coordinate space of the 640 canvas SAVED FOR THIS FRAME, so the box indexes straight
 * into that PNG with no mapping. That is not always the canvas the region was detected on: Step C2
 * re-frames the canvas on the panels once they are known, and moves these boxes with it. Naming and
 * `boardOrder` are settled before the re-frame and are measured in the detection canvas's space, which
 * is common to every frame — these boxes are not, because each frame is re-framed on its own panels.
 *
 * [label] is the region's tag. It is DRAWN onto the canvas beside the box, and it is how Stage 4
 * binds a price back to a region: the reader is asked to report which letter each set of digits came
 * from, so the association is carried in the image itself rather than inferred from output ordering.
 *
 * The tag is a CROSS-FRAME identity, not a position on this canvas: it names the same physical panel
 * on every canvas of the run. It carries NO order: the letters are the ones Step E minted, and a
 * board can perfectly well read `F > J > K > C > H` top to bottom after Step F has folded aliases
 * together and dropped what was not a panel. That is not a fault to be tidied — the letter names the
 * panel, and where the panel SITS is a separate measurement (`boardOrder`, from the boxes' own
 * geometry) that the run keeps separately for exactly that reason.
 *
 * The box is an integer [Rect] because this is the artifact type — it indexes a raster. Naming itself
 * works in floats; see `ImagePrepStage.BoxF`.
 */
data class PriceRegion(val box: Rect, val confidence: Float, val label: String)

/**
 * One board crop that cleared the clarity gate, was prepped, and written to disk.
 *
 * One frame can carry several boards, so [detectionId] — not [frameIndex] — is the unique key; it
 * and [slug] come straight from the [BoardDetection] this was prepped from.
 *
 * @param preppedPath       location on disk of the prepped 640x640 canvas PNG
 * @param gateClarityScore  clarity score of the RAW crop — the score the gate was applied to
 * @param finalClarityScore clarity score of the finished canvas — the quality of what moves on
 * @param regions           the price regions found on this canvas, ordered TOP TO BOTTOM. Carried
 *                          forward so Stage 4 can bind each price it reads to the box it came from.
 * @param dimmedBoxes       boxes whose unlit segments were crushed in Step D
 */
data class PreppedFrame(
    val detectionId: Int,
    val frameIndex: Int,
    /** 0 = the strongest board in this frame. */
    val rank: Int,
    val timestampUs: Long,
    val preppedPath: String,
    val gateClarityScore: Double,
    val finalClarityScore: Double,
    val regions: List<PriceRegion> = emptyList(),
    /**
     * The detector's confidence in the box this canvas was cropped from.
     *
     * Carried through Stage 3 purely so Stage 4 can chunk its requests in the same order Stage 3 walked
     * its crops. Stage 3 returns its frames in ascending time — every downstream stage reads them that
     * way — so without this the confidence ranking would be lost at the stage boundary and Stage 4 would
     * be splitting the pool by clock alone.
     */
    val confidence: Float = 0f,
    val dimmedBoxes: Int = 0,
    /**
     * The detection's file-naming stem, carried rather than recomputed.
     *
     * It was `"f%05d_d%02d".format(frameIndex, rank)` here as well as in [BoardDetection] — two copies
     * of one rule, and both were wrong in the same way: a frame the two shifted-window rungs both found
     * a board in produced two detections with identical frame and rank, so their canvases overwrote
     * each other on disk. The stem now includes the rung and there is one definition of it, in
     * [BoardDetection.slug], which is where the pass is known.
     */
    val slug: String = "",
) {

    /** How many price regions were found on this canvas. */
    val regionCount: Int get() = regions.size
}

/**
 * Whether Stage 3's cloud audit actually happened, and why not when it did not.
 *
 * The audit failing is invisible in the naming it leaves behind — an un-audited board looks exactly like
 * one the audit had nothing to say about. On a run that lost its network that is the difference between
 * "checked, nothing wrong" and "never checked", so it is recorded rather than inferred.
 */
data class AuditRecord(val ran: Boolean = false, val error: String? = null)

/**
 * Everything Stage 3 produced: one canvas per crop, plus the board's layout.
 *
 * The layout is board-level and kept ONCE rather than repeated on every frame — it is a property of
 * the board, and carrying a copy per canvas would invite a consumer to believe two frames could
 * disagree about it. It is empty (`ran = false`) whenever Step G did not run or was refused, and
 * [BoardLayout.applied] is the only thing that says its names may be trusted.
 */
data class PrepResult(
    val frames: List<PreppedFrame>,
    val layout: BoardLayout = BoardLayout(),
    /** Whether Step F's audit ran, and what stopped it if not. See [AuditRecord]. */
    val audit: AuditRecord = AuditRecord(),
    /**
     * The board's panels as LABELS in true top-to-bottom order — position 1 is the top panel.
     *
     * This is the board's row order, and the only thing that has it: a panel's slot in one canvas's
     * region list is a property of that canvas, not of the board. The two diverge constantly — a canvas
     * that missed the top panel puts the second panel in slot 1 — which is why the row number a consumer
     * sees has to be resolved through this rather than counted off a frame.
     *
     * Measured over the whole run (each label's median vertical centre), and the letters never carry it:
     * they follow Step E's minting order and Step F does not renumber them, so this is the only thing
     * that recovers the real order — whether or not the audit ran.
     */
    val boardOrder: List<String> = emptyList(),
    /**
     * Detection ids this stage actually OPENED, which since the cap is not the same as the ids it was
     * given: it walks the board's crops newest-first and stops once it has
     * [ImagePrepStage.MAX_BOARDS_FORWARD] canvases, so the older ones are never looked at.
     *
     * Reported because the alternative is a record that cannot tell the two apart. A crop with no canvas
     * beside it used to mean one thing — a gate dropped it — and now it can equally mean the run had
     * already seen enough. Those are opposite facts about the same crop, and the debug folder is read to
     * distinguish exactly this sort of thing.
     */
    val attempted: Set<Int> = frames.map { it.detectionId }.toSet(),
    /**
     * On-disk paths of the tagged canvases Step G was sent (`prepped_<slug>_tag.png`), if any.
     *
     * A separate render from the frames Stage 4 reads: same pixels and the same outlines, but the letter
     * tags sit OUTSIDE each box so they cannot cover the fuel names Step G is there to read, and no
     * dimming has been applied. Kept so the debug folder can show the exact images the layout was read
     * off — a name that came out wrong is otherwise unattributable.
     */
    val layoutCanvasPaths: List<String> = emptyList()
)

/**
 * Stage 3 — image prep. Gets each detected board ready for the reader.
 *
 * Input is a native-size board crop from Stage 2 — the price-post stage has already found the board
 * and cut it out of its frame at its own pixel size, so nothing here re-crops. Every crop goes
 * through the same steps:
 *
 *   Step A  clarity gate   — MOVED TO STAGE 2. It is now one of that stage's guardrails
 *                            ([ClarityGate]), so a crop too blurred or too flat to read never becomes
 *                            a detection at all and Stage 2 re-samples the video until enough clear
 *                            boards exist. Everything handed to this stage has already passed it;
 *                            clarity is still SCORED here, for the dashboard only.
 *   Step B  enhancement    — DAY: greyscale, CLAHE, unsharp mask ([prepImage]). NIGHT: greyscale, a
 *                            gamma curve and a large-kernel unsharp ([NightFilter.enhance]). Output
 *                            stays continuous-tone grey either way; it is never binarised.
 *   Step C  640 letterbox  — aspect-preserving resize onto a 640x640 grey canvas, the region model's
 *                            exact input size. See [letterboxToCanvas].
 *   Step C2 price zoom     — once the regions are known, the crop is re-framed on the panels: a tenth
 *                            of the board's height, the top panel's upper edge, the panels between, the
 *                            bottom panel's lower edge, another tenth. Step C fits THAT to the square,
 *                            so the digits are magnified at no cost in canvas size. Vertical only, and
 *                            the box coordinates move with it. See [zoomToPrices].
 *   Step D  post-region    — after the regions are final, DAY re-renders only the detected boxes from
 *                            the brightest source color channel (without CLAHE or sharpening), then
 *                            crushes the faint unlit seven-segment ghosts to black so a
 *                            camera's ghost of an unlit stroke cannot be misread as a lit one
 *                            ([dimUnlitSegments]); NIGHT re-stretches mildly and erodes, reopening the
 *                            segment gaps bloom had closed ([NightFilter.finish]). Each box
 *                            is then outlined in red and its tag drawn on a plate in the box's inner
 *                            bottom-left corner — Stage 4 asks the reader which letter each reading came
 *                            from, so the price-to-box binding travels in the image itself.
 *   Step E  region naming  — every region gets a tag that means the same panel on every canvas of the
 *                            run, so Stage 5 can pool a panel's reads across frames. **Two passes**
 *                            over the same detections; see [RegionNamer].
 *   Step F  board audit    — optional, and the only part of this stage that leaves the device. The
 *                            finished naming is sent as pure geometry and audited for which regions
 *                            really are the board's panels. See [BoardAudit].
 *
 * ### Why naming is two passes
 * Naming used to be single-pass and feed-forward: the first canvas named minted the whole board, and
 * every later canvas was measured against a rolling window of the ones just named. One bad canvas near
 * the seed — a clipped box, a reflection detected as a panel, a missing top row — propagated into the
 * window and out again, and nothing ever revisited it. Cold start, blank-gap recovery and
 * self-correction are all the same missing thing: a second look.
 *
 *   - **pass 1** names every canvas exactly as before and its output is thrown away. Its only job is
 *     to learn what the board is: which slots exist, how many canvases saw each, and where each sits.
 *   - **between**, [RegionNamer.consolidate] merges slots that are one panel twice, drops slots too
 *     few canvases ever saw, reduces each survivor to the MEDIAN of every box fitted to it, and
 *     re-sorts the result into reading order — so an order pass 1 got wrong is corrected here.
 *   - **pass 2** re-names every canvas against that one fixed, outlier-resistant board. A bad canvas
 *     can no longer contaminate the next five, because nothing is measured against it.
 *
 * The model runs ONCE per canvas; only the naming is repeated, which is pure arithmetic.
 *
 * ### The canvas is rebuilt, not held
 * Naming cannot finish until every canvas has been scanned, so the artifacts are written in a later
 * loop — and holding one 640x640 canvas per crop until then would be tens of megabytes on a run this
 * app has already been killed for. Each canvas is therefore rebuilt from its crop (a decode plus
 * Step B plus Step C) when it is time to dim, annotate and save it.
 *
 * The region head is decoded down a **confidence ladder** until at least [MIN_REGIONS] boxes survive
 * the plausibility guards: a forecourt price post always carries at least two panels, so one region is
 * a threshold problem rather than the truth. Only the decode repeats.
 *
 * ### One switch, made from measured sun times
 * Which branch Steps B and D take is decided ONCE per run by [DayNight], from the capture time against
 * the stored sunrise and sunset, with both shifts 30 minutes early. It is not decided from the image:
 * each branch is actively damaging on the other's board, so a decision that could flap between crops of
 * one clip would be worse than either branch applied consistently.
 *
 * **The letterboxed canvas is the artifact.** Detections are reported in the coordinate space of the
 * 640 canvas, which is also what gets dimmed, annotated and saved — so there is no letterbox to undo,
 * and the board is only ever resampled once: Step C2 re-frames by going back to the SOURCE crop and
 * letterboxing again, never by enlarging the canvas it already made. The dimmed, box-annotated canvas is the single image
 * that continues to Stage 4; there is deliberately no separate clean copy kept alongside it.
 *
 * Prepped images are written to a directory linked to the frames/crops dirs by the same video id:
 *   frames/<videoId>/  →  crops/<videoId>/  →  prepped/<videoId>/prepped_f00000_d00.png
 *
 * Region model contract (verified against this model, YOLO11n, single class — shipped as
 * `price-region-v2.tflite` in assets, now downloaded as `price-region-002.tflite`, same network):
 *   input : [1, 3, 640, 640] float32 (NCHW, 0-1). The 0-1 scaling is not an assumption — feeding
 *           0-255 collapses the head, emitting thousands of anchors all scoring exactly 1.000. Three
 *           channels are required but the content is GREY: the model was trained on grey imagery, and
 *           grey replicated across the three channels measured better than colour (mean best score
 *           0.856 vs 0.834 colour, 0.814 channel-swapped). Step B's grey output is fed as three
 *           identical channels, which is both what the model wants and what it scores best on.
 *   output: [1, 5, 8400] float32, rows = (cx, cy, w, h, score), coordinates NORMALISED to 0-1 —
 *           verified, not assumed (measured max 0.995 across all four rows).
 */
class ImagePrepStage(private val context: Context) {

    companion object {
        private const val TAG = "ImagePrepStage"

        // ---- Step B: enhancement -------------------------------------------------------------- //
        // Order is fixed and each step consumes the previous one's output: grey -> CLAHE -> sharpen.
        // The image is deliberately NOT binarised — it stays continuous-tone grey, which is what the
        // region model was trained on and what the VLM reads.
        /** CLAHE clip limit + tile size (local contrast; the clip caps noise amplification). Kept LOW
         *  (1.2) so CLAHE doesn't lift the faint UNLIT 7-segment "ghost" strokes up to look lit. */
        private val CLAHE_CLIP: Double get() = DeviceConfig.double("board_prep.image_prep.clahe_clip", 1.2)
        private val CLAHE_TILE: Double get() = DeviceConfig.double("board_prep.image_prep.clahe_tile", 8.0)
        /** Unsharp mask: `out = clahe + AMOUNT·(clahe − blur)`. */
        private val SHARPEN_SIGMA: Double get() = DeviceConfig.double("board_prep.image_prep.sharpen_sigma", 1.5)
        private val SHARPEN_AMOUNT: Double get() = DeviceConfig.double("board_prep.image_prep.sharpen_amount", 1.5)

        // ---- Step C: letterbox ----------------------------------------------------------------- //
        /** Neutral grey Ultralytics letterboxes with. */
        private val NET_PAD: Double get() = DeviceConfig.double("board_prep.image_prep.net_pad", 114.0)

        // ---- Step C2: price zoom ----------------------------------------------------------------- //
        /**
         * Whether the finished canvas is re-framed on the price panels. Off restores the plain Step C
         * canvas — same pixels the region model saw, whole board, one resample.
         */
        private val PRICE_ZOOM_ENABLED: Boolean get() = DeviceConfig.boolean("board_prep.image_prep.price_zoom_enabled", true)

        /**
         * The margin kept beyond the outermost panel at each end of the re-frame, as a fraction of the
         * BOARD's own height.
         *
         * The finished image reads: 10% margin, top price panel, whatever panels lie between, bottom
         * price panel, 10% margin. A share of the board and not of the leftover space, so the margin is
         * the same band whether the crop was framed tightly or loosely — the re-frame's output has one
         * shape, decided by where the panels are and nothing else.
         *
         * Measured against the CROP, which is the board; the canvas can carry grey pad that is not board
         * at all, and a share of that would be a share of nothing.
         *
         * Not zero because the panels are not the whole story: a cut flush to the boxes takes the price's
         * own surround with it, and both the dimming and the reader do better with a little board around
         * the digits than with digits bleeding into grey pad.
         */
        private val ZOOM_MARGIN_FRAC: Float get() = DeviceConfig.float("board_prep.image_prep.zoom_margin_frac", 0.10f)

        /** Shortest re-frame worth making (px, in the crop's own pixels). Below it, keep the full crop. */
        private val MIN_ZOOM_PX: Int get() = DeviceConfig.int("board_prep.image_prep.min_zoom_px", 24)

        /**
         * Keep the full crop when the window would retain at least this much of it — there is nothing
         * to gain from a second resample that crops almost nothing.
         */
        private val ZOOM_MIN_TRIM: Float get() = DeviceConfig.float("board_prep.image_prep.zoom_min_trim", 0.98f)

        /**
         * Least magnification gain that justifies the re-frame.
         *
         * The letterbox fits the LONGER side, so trimming height only helps while height is the binding
         * side. On a board already limited by its width the window can lose half the image and the digits
         * come out exactly the same size — this is what notices that and declines.
         */
        private val ZOOM_MIN_GAIN: Float get() = DeviceConfig.float("board_prep.image_prep.zoom_min_gain", 1.02f)

        // ---- Step D: ghost dimming -------------------------------------------------------------- //
        // A seven-segment display's unlit strokes are not truly black — the camera picks them up as
        // faint ghosts, and a reader can mistake a ghost for a lit segment, turning an 8 into a 9 or a
        // 1 into a 7. This crushes them. The split between lit and unlit is found PER BOX with Otsu,
        // never globally: a global threshold would wipe a board that is uniformly dim, while a per-box
        // one adapts to each region's own exposure.
        //
        // The two knobs do different jobs, and it is worth being precise about which, because they
        // were previously set to fight each other:
        //   GHOST_BLACK_POINT  what gets clipped to pure black. The only part that DISCRIMINATES — it
        //                      is measured against the box's own Otsu split, i.e. against that panel's
        //                      lit/unlit boundary.
        //   GHOST_GAMMA        how everything ABOVE the black point is re-stretched. Discriminates
        //                      nothing; it just reshapes what survived. >1 darkens the faint end, <1
        //                      lifts it.
        //
        // The earlier 0.60/0.70 settings were tuned on the CLAHE/sharpened luminance image. Day panels
        // now use the brightest source channel without either operator: lit colored strokes start
        // brighter, so a higher black point and gamma above 1 suppress the remaining LED halo.
        /** Black-point sits at this fraction of that box's Otsu split; below it, clipped to black. */
        internal val GHOST_BLACK_POINT: Double get() = DeviceConfig.double("board_prep.image_prep.ghost_black_point", 0.80)
        /** Re-stretch above it. Above 1 darkens the faint halo between lit strokes. */
        internal val GHOST_GAMMA: Double get() = DeviceConfig.double("board_prep.image_prep.ghost_gamma", 1.30)

        // ---- Price-region model ------------------------------------------------------------------ //
        /**
         * Name of the price-region model, for logs only — the file itself is DOWNLOADED into private
         * storage (see [ModelRepository.priceRegionFile]), not bundled in the APK.
         */
        private val REGION_MODEL_NAME = ModelConfig.PriceRegion.FILE_NAME
        /** Score a region must reach to survive. */
        private val REGION_CONF_THRESHOLD: Float get() = DeviceConfig.float("board_prep.image_prep.region_conf_threshold", 0.25f)
        /** IoU above which overlapping region boxes are merged by NMS. */
        private val REGION_IOU_THRESHOLD: Float get() = DeviceConfig.float("board_prep.image_prep.region_iou_threshold", 0.30f)
        /** Hard cap on boxes kept from one canvas. */
        private val REGION_MAX_DET: Int get() = DeviceConfig.int("board_prep.image_prep.region_max_det", 300)

        /**
         * A price post always carries at least two price panels — a board with one is not a thing that
         * exists on a forecourt. So a canvas that decodes to fewer than two regions is treated as a
         * threshold problem rather than as the truth, and the head is re-decoded at successively lower
         * confidences until two appear.
         *
         * Escalating costs almost nothing: the model's raw output is already in hand and only the
         * DECODE is repeated, so no second inference is run. A canvas still showing fewer than two
         * regions at the bottom rung genuinely has fewer than two.
         *
         * ### The bottom rung is 0.01, not 0.001 (2026-09-18)
         * 0.001 is three orders of magnitude below the model's own threshold and was reaching past the
         * point where a box means anything: on a crop of foliage it manufactured boxes by the dozen —
         * 16, 13 and 12 regions on single canvases in one observed run, and 33 in the run that first
         * motivated [MAX_REGIONS] — all small enough to slip under the area guard. Every one of those
         * canvases was then thrown away whole, so the depth bought nothing and cost the board its
         * closest crops.
         *
         * 0.01 is still an order of magnitude below the threshold, so it keeps what the rung was for —
         * a real panel whose score sits just under 0.08 because the text is small or the light is flat.
         * What it gives up is the far tail, which in practice was noise rather than panels.
         */
        private val MIN_REGIONS: Int get() = DeviceConfig.int("board_prep.image_prep.min_regions", 2)
        private val CONF_ESCALATION: FloatArray
            get() = DeviceConfig.floatArray(
                "board_prep.image_prep.conf_escalation", floatArrayOf(0.08f, 0.01f)
            )

        /**
         * The most price panels one canvas may resolve to. A canvas resolving to more than this is
         * **discarded whole** — not trimmed to the best few.
         *
         * This is the counterweight to the confidence ladder above. The ladder chases [MIN_REGIONS] by
         * re-decoding at lower confidences, which assumes the canvas actually contains a board; on a
         * crop of trees and kerbstones it instead manufactures boxes, all small enough to slip under
         * the area guard. One observed run produced **33 regions on a single canvas**, which then
         * minted 42 tags and put 98 phantom panels in front of the reader, where the model duly
         * hallucinated a price for each.
         *
         * Discarding beats trimming because the count is evidence about the CANVAS, not about the
         * boxes: a crop yielding 33 panels is not a board with 28 spurious extras, it is not a board.
         * Checked BEFORE naming — naming is what mints tags, and a tag minted from noise pollutes the
         * board layout for every canvas that follows.
         *
         * ### Why 7 and not 5 (2026-09-18)
         * 5 was described as the most panels a real board carries, and the pipeline's own behaviour
         * disagreed: the consolidator routinely builds boards of six and seven slots, because it pools
         * tags ACROSS canvases while this cap applies to ONE. A 2026-09-18 run consolidated a six-panel
         * board (`F > E > C > A > B > D`) out of canvases that each resolved four or five regions.
         *
         * That made the discard selective in the worst direction. The canvas most likely to exceed the
         * cap is the closest, best-framed one — the only crop that shows every panel at once — while
         * the partial and distant crops sail through. 7 covers the boards this pipeline is actually
         * seeing and still catches the failure this guard exists for, which was never a board with one
         * or two panels too many; it was foliage resolving to double figures.
         *
         * Raising it is not free: the cap is also what bounds how many phantom panels a bad canvas can
         * put in front of the reader, so each step up buys layout coverage with reader noise.
         */
        private val MAX_REGIONS: Int get() = DeviceConfig.int("board_prep.image_prep.max_regions", 7)

        /**
         * How many board canvases this stage carries forward **per board**, and therefore how many
         * Stage 4 reads. The latest that many survivors; everything older is not looked at.
         *
         * ### Why the cap lives HERE and not after the stage
         * It used to: this stage prepped every crop the detector produced for the board and Stage 4 then
         * took the latest fifteen, so a clip yielding sixty crops paid for sixty region-model
         * inferences, sixty enhancement passes and sixty PNG writes to read fourteen of them. The cap
         * now applies where the work is, and because pass 1 walks the run BACKWARD — latest crop first —
         * the ones it keeps are exactly the ones the old selection would have picked. Same images, a
         * fraction of the work.
         *
         * ### What it also changes, deliberately
         * The board this stage consolidates is now learned from the canvases that are actually READ
         * rather than from every crop in the clip. That is the more defensible of the two: a tag order
         * fitted to fifty canvases and then applied to fourteen is fitted partly to boards nobody looks
         * at, and the far crops — smallest text, worst angle — are the ones that mint spurious tags.
         *
         * ### The number
         * Counted in SURVIVORS, so the cap is on images reaching the reader, not on crops attempted: a
         * crop the clarity gate drops or the [MAX_REGIONS] discard throws out does not spend a slot, and
         * the walk simply reaches one further back. `BoardDetector.MIN_BOARDS` is deliberately one
         * HIGHER than this, and that difference is the spare — see its own note.
         *
         * The floor under it is Stage 5's cross-frame vote, which settles each panel by pooling that
         * panel's reads and taking the mode. Fourteen reads is what makes one misread digit a minority;
         * halve it and a single bad read starts to carry.
         */
        val MAX_BOARDS_FORWARD: Int get() = DeviceConfig.int("board_prep.image_prep.max_boards_forward", 14)

        /**
         * Confidence is compared in buckets this wide when ordering a board's crops for prep.
         *
         * Without it the sort would be almost entirely confidence-driven and the time signal would never
         * break a tie: 0.8123 beating 0.8119 is sampling noise, not a judgement about which crop is
         * better. At 0.05 the detector's opinion decides between crops that genuinely differ, and crops
         * of comparable confidence are still taken newest-first — nearest, largest text.
         *
         * This model tops out around 0.86 (`BoardDetector.MODEL_MAX_CONF`) and Pass 1's threshold is
         * 0.38, so the usable range is about ten buckets wide.
         */
        private val CONF_BUCKET: Float get() = DeviceConfig.float("board_prep.image_prep.conf_bucket", 0.05f)

        /**
         * Confidence rounded down to a [CONF_BUCKET] step, so near-equal scores compare equal.
         *
         * Internal rather than private because Stage 4 chunks its requests in the order this stage
         * walked its crops, and one definition of "comparably confident" has to serve both. Two copies
         * of a rounding rule is how two stages start disagreeing about which canvas is better.
         */
        internal fun confidenceBucket(confidence: Float): Int =
            (confidence / CONF_BUCKET).toInt()

        /**
         * The order a board's crops are considered in: confidence bucket first, newest-first within a
         * bucket. Stage 3 walks its crops in this order and Stage 4 re-derives it to split its pool.
         */
        internal fun <T> byConfidenceThenNewest(
            confidence: (T) -> Float,
            timestampUs: (T) -> Long
        ): Comparator<T> =
            compareByDescending<T> { confidenceBucket(confidence(it)) }
                .thenByDescending { timestampUs(it) }

        /**
         * Area guard: nothing occupying this much of the canvas is a price panel.
         *
         * Applied unconditionally — a box over the cap is dropped whatever else is on the canvas, and
         * if that leaves fewer than [MIN_REGIONS] the confidence ladder simply climbs another rung and
         * looks again. The largest genuine panel measured across the reference crop set is 9.0% of the
         * canvas, so 12% leaves about a third of headroom above the real distribution.
         */
        private val MAX_AREA_FRAC: Double get() = DeviceConfig.double("board_prep.image_prep.max_area_frac", 0.12)

        /**
         * The FLOORS the area cap had no counterpart for. They exist because the confidence ladder
         * ends at 0.001: on a crop that is not a board the bottom rung emits whatever it likes, and
         * what it likes is slivers clamped to the canvas boundary. Those were being NAMED — one 145x8
         * sliver was tracked as a price panel across 13 canvases of one video, which alone minted 21
         * tags for a board carrying about three real panels.
         *
         * Both floors were swept over all 2538 boxes of a 643-crop run, splitting on the model's own
         * confidence as a weak label — boxes at or above the first rung (0.25) are the ones worth
         * keeping, boxes under 0.08 the ones worth cutting:
         *
         * | | loses of trusted | cuts of suspect |
         * |---|---|---|
         * | min side 30px | 0.9% | 39.0% |
         * | min area 0.008 | 0.3% | 44.3% |
         * | together | 0.9% | 48.4% |
         *
         * Both sit just under the trusted distribution's 1st percentile (side 31px, area 0.0094), which
         * is what makes them nearly free. Past them the cost climbs fast — a 36px side loses 2.9%, a
         * 0.012 area loses 3.4% — so this is the knee, not a round number.
         */
        private val MIN_REGION_SIDE_PX: Float get() = DeviceConfig.float("board_prep.image_prep.min_region_side_px", 30f)
        private val MIN_AREA_FRAC: Double get() = DeviceConfig.double("board_prep.image_prep.min_area_frac", 0.008)

        // ---- Step E: region naming ---------------------------------------------------------------- //
        // A region's tag must mean the same panel on every canvas, so a box is matched against the
        // board's known boxes rather than being numbered afresh per image. The measure is the VERTICAL
        // pixel distance between respective corners: every tag a corner can reach within the guard
        // becomes a candidate for that box, carrying its closest distance.
        //
        // The candidates are then NOT resolved box by box. The namer remembers the ORDER the tags run
        // down the board — a list like E > A > B > C > D — and fits the whole canvas against it at
        // once, keeping that order. Panels are bolted in a stack, so the third one down is always the
        // third one down no matter how the board slides, zooms, or how many panels the detector
        // misses; the order is therefore the one fact about the board that cannot change, and it is
        // worth more than any single box-to-box distance. Fitting the canvas as a whole is what lets
        // three good pairings overrule a fourth box's attractive-in-isolation mistake, and it makes
        // one tag per canvas structural rather than a rule bolted on afterwards.

        /**
         * The guard, as a fraction of the board's LOCAL PITCH — the vertical spacing between
         * neighbouring panels on this canvas — and NOT of the region's own height.
         *
         * Pitch is the thing that actually separates one panel from the next (median 69px over the
         * reference crop set); a panel's own height is merely correlated with it, and the region's
         * width (median 198px, nearly three pitches) carries no discrimination at all. A corner that
         * has travelled further than this fraction of the pitch between two frames is not that panel
         * drifting, it is a different panel, so the box gets a new tag.
         *
         * At 0.5 the limit is about 35px on a 69px pitch, comfortably inside it, so a neighbour can no
         * longer be mistaken for a drifting panel. Because it is a property of the CANVAS rather than
         * of any one box, it is measured once per canvas into [Geom].
         *
         * There is deliberately no pixel floor under it: a floor is a guard that stops scaling with the
         * board, and on a distant board it would be the whole guard.
         */
        private val GUARD_PITCH_FRAC: Float get() = DeviceConfig.float("board_prep.image_prep.guard_pitch_frac", 0.5f)

        /**
         * How many already-named canvases the candidates are drawn from, in pass 1.
         *
         * One canvas pair can be misled by a single bad frame — a box the detector clipped, or a jump
         * the camera made — whereas the sequence fit gets to choose among everything the whole window
         * offers. Canvases only contribute when they offer something, so early in a run, or after a
         * run of blank canvases, the candidates simply come from fewer frames.
         *
         * Pass 2 does not use a window at all: it holds ONE entry, the consolidated board, and never
         * replaces it. That is the whole point — a bad canvas cannot enter a window nothing is ever
         * added to, so it cannot contaminate the canvases that follow it.
         */
        private val HISTORY_FRAMES: Int get() = DeviceConfig.int("board_prep.image_prep.history_frames", 5)

        /**
         * Columns. Corner distance deliberately ignores x, which is right within one vertical stack and
         * wrong across two side-by-side stacks — there the left column's panels sit at the same heights
         * as the right column's and would happily steal each other's tags.
         *
         * Columns are found per canvas as gaps in the sorted x-centres, and the x constraint is applied
         * ONLY when a canvas really does show more than one, so the ordinary single-post case behaves
         * exactly as before and a board that pans sideways still keeps its identity.
         */
        /** An x-centre gap this many panel widths wide starts a new column. */
        private val COLUMN_GAP_FRAC: Float get() = DeviceConfig.float("board_prep.image_prep.column_gap_frac", 0.6f)
        /** ...and a pairing may not reach that much of a width across. */
        private val COLUMN_TOL_FRAC: Float get() = DeviceConfig.float("board_prep.image_prep.column_tol_frac", 0.5f)

        /**
         * What it costs to leave a region unexplained, as a multiple of the canvas's guard limit.
         *
         * Anything above 1.0 makes every guard-passing pairing cheaper than minting, so the fit only
         * ever mints when it genuinely cannot place a box — it will not trade a real panel away to save
         * distance.
         */
        private val MINT_COST_FACTOR: Float get() = DeviceConfig.float("board_prep.image_prep.mint_cost_factor", 1.5f)

        /**
         * Two equally-priced fits used to be separated by nothing but the path the alignment's
         * backtrack happened to walk, which resolved differently on different canvases. This breaks the
         * tie on evidence instead — the slot more canvases offered wins — and is far below any
         * meaningful pixel difference, so it can only ever move an exact tie.
         *
         * Folded into the optimiser's own copy of the costs, never into the distances the reports print
         * or the mint comparison.
         */
        private val TIE_EPS: Float get() = DeviceConfig.float("board_prep.image_prep.tie_eps", 1e-3f)

        // ---- Step E: board motion (affine) -------------------------------------------------------- //
        // When the whole board moves further than the guard between two canvases, the correct pairing is
        // not merely expensive — it is unreachable, so the fit is left choosing between wrong answers.
        // What it then finds is the off-by-one reading: every region takes its neighbour's tag and the
        // leftover region mints a new one.
        //
        // The cure is to notice the board's own motion and measure against it, as `new = scale·old +
        // offset`: offset alone is a slide, and scale as well is the camera closing on the board or
        // pulling away — the case a pure slide cannot express at all, since under a zoom the top panel
        // rises while the bottom one falls.
        //
        // Deliberately conservative, because a wrongly detected motion would re-anchor the whole board:
        // it needs [SHIFT_MIN_SUPPORT] correspondences so one stray box cannot invent motion; the scale
        // must stay inside [MIN_SCALE]..[MAX_SCALE]; the displacement may not exceed
        // [SHIFT_MAX_PITCHES] of the SMALLEST gap on the canvas; and it must beat measuring with no
        // motion at all, on regions explained first and total distance second.
        //
        // Known gap: this covers slide and zoom, not rotation or skew. Those need a full 6-DOF affine
        // on all four corners, and are rare on a fixed forecourt post filmed from a vehicle.
        private val SHIFT_MIN_SUPPORT: Int get() = DeviceConfig.int("board_prep.image_prep.shift_min_support", 3)

        /**
         * The motion cap, in panel gaps — measured against the SMALLEST gap on the canvas rather than
         * the median, so a slide that is safe across a wide gap cannot overrun a narrow one.
         */
        private val SHIFT_MAX_PITCHES: Float get() = DeviceConfig.float("board_prep.image_prep.shift_max_pitches", 1.0f)

        /** Points agree within this fraction of a region's height. */
        private val SHIFT_TOL_FRAC: Float get() = DeviceConfig.float("board_prep.image_prep.shift_tol_frac", 0.35f)

        /**
         * The plausible scale band, against the CONSOLIDATED board — which is the median of the whole
         * run rather than the canvas next door. Over one approach to a forecourt the board can easily
         * double on screen, so a canvas at either end of the drive is a long way from the median in
         * scale as well as in position, and a per-frame range (0.8..1.25) would refuse to describe it
         * at all.
         */
        private val MIN_SCALE: Float get() = DeviceConfig.float("board_prep.image_prep.min_scale", 0.5f)
        private val MAX_SCALE: Float get() = DeviceConfig.float("board_prep.image_prep.max_scale", 2.0f)

        /**
         * Correspondences needed before a SCALE is fitted at all. With two boxes a zoom and a shift are
         * indistinguishable — any pair of points fits some line — so a scale read off two panels is as
         * likely to be noise as motion. Below this only an offset is fitted.
         */
        private val SCALE_MIN_BOXES: Int get() = DeviceConfig.int("board_prep.image_prep.scale_min_boxes", 3)

        /**
         * When a shift explains the SAME number of regions, a shorter total distance is enough to
         * accept it. Requiring strictly MORE regions is tempting as the conservative choice and is a
         * trap: it can only fire when the slide runs off the end of the board and mints a tag, so on a
         * board that already has a spare slot to absorb the slide — which is to say on every board
         * already damaged once — the correction it was written for can never happen.
         */
        private val SHIFT_ACCEPT_TIES: Boolean get() = DeviceConfig.boolean("board_prep.image_prep.shift_accept_ties", true)

        // ---- Step E: two-pass board consolidation ------------------------------------------------- //
        /**
         * A slot needs this many canvases behind it to be believed, or [BOARD_MIN_SIGHTING_FRAC] of
         * however many canvases there were, whichever is SMALLER — on a run of six canvases there is
         * not enough footage for a real panel to be seen three times, and the threshold has to relax
         * rather than delete the board. It never prunes below [MIN_REGIONS] slots.
         *
         * Pruning a real-but-rare panel is not the failure it looks like: pass 2 mints it the first
         * time it appears and folds it into the board, so it costs one tag, in the right place, once.
         * Keeping a spurious one costs a slot in the order for the entire run — which is the asymmetry
         * this threshold is set against.
         */
        private val BOARD_MIN_SIGHTINGS: Int get() = DeviceConfig.int("board_prep.image_prep.board_min_sightings", 3)
        private val BOARD_MIN_SIGHTING_FRAC: Double get() = DeviceConfig.double("board_prep.image_prep.board_min_sighting_frac", 0.15)

        /**
         * Before any pruning, slots that are the same panel twice are merged. Real panels are disjoint
         * rectangles bolted one above the next, so two slots whose median boxes sit on top of each
         * other cannot both be panels — they are one panel that a feed-forward pass met at two
         * different zoom levels and minted twice, which is where the off-by-one mint ends up. Adjacent
         * panels overlap by nothing at all (there is a gap between them), so this only ever fires on a
         * genuine duplicate.
         *
         * Merging runs BEFORE pruning, so two half-attested halves of one panel become one
         * well-attested slot rather than two prunable ones — and it is the piece that actually removes
         * a surplus tag rather than moving it.
         *
         * Measured as a fraction of the SHORTER of the two boxes.
         */
        private val BOARD_MERGE_OVERLAP: Float get() = DeviceConfig.float("board_prep.image_prep.board_merge_overlap", 0.5f)

        // ---- Step F: the board audit -------------------------------------------------------------- //
        /**
         * Whether Step F may run at all. **On**, so every run whose naming is worth auditing is audited
         * — one reasoning call per video, against [BuildConfig.AZURE_RESPONSES_ENDPOINT].
         *
         * The reference harness keeps this opt-in per run (`--llm-audit`); here it is a standing part of
         * the pipeline. It still needs [BoardAudit.isConfigured] on top of this, so a build with no key
         * or no Responses endpoint runs Steps A-E and stops there, and the naming stands as Step E left
         * it. Flip to false to keep a run entirely on the device.
         *
         * A compile-time switch rather than a setting, deliberately: this is the only part of the image
         * path that leaves the device, and whether it does should not depend on a runtime flag being
         * set correctly.
         */
        private val LLM_AUDIT_ENABLED: Boolean get() = DeviceConfig.boolean("board_prep.image_prep.llm_audit_enabled", true)

        /**
         * Whether Step G may run. **On**, like Step F — one more reasoning call per run, this one
         * carrying [BoardLayoutReader.FRAMES] canvases as images, which is materially more expensive
         * than Step F's text-only geometry.
         *
         * It also needs [BoardLayoutReader.isConfigured]. Flip to false to keep a run entirely on the
         * device, or to run the geometry steps without paying for the layout read.
         */
        private val LLM_LAYOUT_ENABLED: Boolean get() = DeviceConfig.boolean("board_prep.image_prep.llm_layout_enabled", true)

        // ---- Annotation ----------------------------------------------------------------------------- //
        private val BOX_COLOR = Color.RED
        /** OpenCV's HERSHEY font scale is about 31px of Android text size per unit. */
        private val HERSHEY_TO_TEXT_SIZE: Float get() = DeviceConfig.float("board_prep.image_prep.hershey_to_text_size", 31f)
        /** Floor on the tag plate's text, so a tag stays readable on a small panel. */
        private val LABEL_MIN_TEXT_PX: Float get() = DeviceConfig.float("board_prep.image_prep.label_min_text_px", 26f)

        /** Spreadsheet-style tag for position [index]: A..Z, then AA, AB … */
        private fun boxLabel(index: Int): String {
            var n = index
            val sb = StringBuilder()
            while (true) {
                sb.append('A' + (n % 26))
                n = n / 26 - 1
                if (n < 0) break
            }
            return sb.reverse().toString()
        }

        /** Median of [values]; 0 when empty. */
        private fun median(values: List<Float>): Float {
            if (values.isEmpty()) return 0f
            val s = values.sorted()
            val mid = s.size / 2
            return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2f
        }

        /** The per-edge median of [boxes] — the position no single canvas can move. */
        private fun medianBox(boxes: List<BoxF>): BoxF = BoxF(
            median(boxes.map { it.left }),
            median(boxes.map { it.top }),
            median(boxes.map { it.right }),
            median(boxes.map { it.bottom })
        )

        /**
         * Groups box indices into columns by their x-centre. One group is the normal case.
         *
         * A gap in the sorted x-centres wider than [COLUMN_GAP_FRAC] of a median panel width starts a
         * new column, so a single stack's own box-fitting jitter (a few px) never splits it, while two
         * stacks a panel-width apart always separate.
         *
         * Returned in left-to-right order, each group's indices in that same order.
         */
        private fun columnsOf(boxes: List<BoxF>): List<List<Int>> {
            if (boxes.isEmpty()) return emptyList()
            val width = median(boxes.map { it.width }).takeIf { it > 0f } ?: 1f
            val order = boxes.indices.sortedBy { boxes[it].midX }
            val cols = mutableListOf(mutableListOf(order.first()))
            for (i in order.drop(1)) {
                if (boxes[i].midX - boxes[cols.last().last()].midX > COLUMN_GAP_FRAC * width) {
                    cols.add(mutableListOf())
                }
                cols.last().add(i)
            }
            return cols
        }

        /**
         * The order a canvas's regions are named in, and the board is stored in.
         *
         * Top edge first, left edge as the tie-break. The tie-break matters for panels sitting side by
         * side at the same height: without it their order could flip between canvases and the fit would
         * be fighting a board that appears to reshuffle itself. Boxes are floats by the time they get
         * here, so two panels a fraction of a pixel apart no longer round into a tie at all.
         *
         * On a board that really is two stacks side by side, it reads **stack by stack** instead.
         * Interleaving them by height would make the ORDER itself unstable — the moment the right stack
         * sits a pixel lower than the left, the order flips from L,R,L,R to L,L,R,R and the fit is left
         * trying to honour an order the board never had. Column-major, each stack stays a run of its
         * own, and the order within a stack is the one thing that cannot change.
         *
         * Both the per-canvas naming and the consolidated board go through here, so the board pass 2 is
         * fitted against is stored in the order pass 2 reads canvases in. Anything else and the ordered
         * fit could not place a two-column board at all — it would be asked to walk one order while
         * measuring another.
         */
        private fun <T> readingOrder(items: List<T>, box: (T) -> BoxF): List<T> {
            if (items.size < 2) return items
            val ordered = items.sortedWith(compareBy({ box(it).top }, { box(it).left }))
            val cols = columnsOf(ordered.map(box))
            if (cols.size <= 1) return ordered
            // A column's indices come back in x order and `ordered` is already in y order, so ascending
            // index is descending the stack.
            return cols.flatMap { group -> group.sorted().map { ordered[it] } }
        }

        /**
         * Why this box cannot be a price panel, or null if it can.
         *
         * Pure geometry, judged per box on its own terms — nothing else on the canvas exempts it. The
         * reason is returned so the log can say which rule fired rather than just how many boxes went.
         */
        private fun implausible(box: BoxF, canvasEdge: Int): String? {
            val frac = (box.width.toDouble() * box.height) / (canvasEdge.toDouble() * canvasEdge)
            return when {
                frac >= MAX_AREA_FRAC -> "oversized"
                min(box.width, box.height) < MIN_REGION_SIDE_PX -> "too thin"
                frac < MIN_AREA_FRAC -> "too small"
                else -> null
            }
        }

        /**
         * The closest RESPECTIVE-CORNER vertical distance between two boxes that passes [limit], or
         * null when no corner does. [transform] moves the old box to where the board's own motion says
         * it would now be, before anything is measured.
         *
         * Only the y axis counts: panels are stacked vertically and all span very nearly the same x
         * range, so horizontal displacement carries no information about which panel a box is — it is
         * box-fitting noise that can only pull a distance away from the truth. Dropping it also means a
         * board that pans sideways no longer looks like it changed identity. That reasoning holds
         * WITHIN a column and fails across two of them, which is why x comes back as a separate
         * constraint in [RegionNamer.candidates] on multi-column canvases only.
         *
         * Corners are compared strictly like-for-like: TL to TL, TR to TR, BR to BR, BL to BL. With the
         * x axis dropped, TL and TR both reduce to the top edge and BR and BL both to the bottom, so
         * those four comparisons collapse to exactly TWO distinct distances — computed once each here.
         */
        private fun closestCorner(new: BoxF, old: BoxF, transform: Transform, limit: Float): Float? {
            val dTop = abs(new.top - transform.applyTo(old.top))
            val dBottom = abs(new.bottom - transform.applyTo(old.bottom))
            var best: Float? = null
            if (dTop <= limit) best = dTop
            if (dBottom <= limit && (best == null || dBottom < best)) best = dBottom
            return best
        }
    }

    /**
     * A box in canvas coordinates, in FLOAT.
     *
     * Floats rather than a [Rect] because truncating to int injected up to a pixel of downward bias
     * into every coordinate, and it landed exactly where Step E is most delicate: the reading-order
     * sort of two panels at nearly the same height, and a fit decided by a fraction of a pixel. The
     * cast now happens only where a raster is actually indexed — [dimUnlitSegments] and [drawRegions],
     * both of which take [PriceRegion] — so nothing upstream of those keeps a rounded number.
     */
    private data class BoxF(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
        val midX: Float get() = (left + right) / 2f
        fun toRect(): Rect = Rect(
            left.roundToInt(), top.roundToInt(), right.roundToInt(), bottom.roundToInt()
        )

        /** Intersection-over-union with [other]. 0 when they don't meet. */
        fun iou(other: BoxF): Float {
            val w = max(0f, min(right, other.right) - max(left, other.left))
            val h = max(0f, min(bottom, other.bottom) - max(top, other.top))
            val inter = w * h
            val union = width * height + other.width * other.height - inter
            return if (union > 0f) inter / union else 0f
        }
    }

    /** One decoded box, before naming. */
    private class Detected(val box: BoxF, val confidence: Float)

    /** Loads OpenCV's bundled native lib once; if it fails we fail-open (pass images through). */
    private val openCvReady: Boolean by lazy {
        OpenCVLoader.initLocal().also { ok ->
            if (ok) Log.d(TAG, "OpenCV initialised for image prep")
            else Log.e(TAG, "OpenCV failed to init — clarity passes everything, prep is a pass-through")
        }
    }

    /**
     * Price-region detector, memory-mapped once from the DOWNLOADED model file. Null if the model
     * can't be loaded — most likely because the download screen was never completed — in which case
     * the canvas simply continues unmarked and undimmed.
     */
    private val regionInterpreter: Interpreter? by lazy {
        try {
            // Cap to (cores - 1) so a core stays free for the UI RenderThread (avoids input-dispatch ANRs).
            Interpreter(loadRegionModel(), Interpreter.Options().apply {
                setNumThreads((Runtime.getRuntime().availableProcessors() - 1).coerceAtLeast(1))
            })
                .also { Log.d(TAG, "$REGION_MODEL_NAME loaded for region marking") }
        } catch (t: Throwable) {
            // `Throwable`, and this is the clearest case for it in the file: the faults that stop a
            // TFLite model loading are almost all `Error`. `UnsatisfiedLinkError` when the native
            // library is missing for the device's ABI, `NoClassDefFoundError` from a stripped or absent
            // split, `OutOfMemoryError` mapping the model file. Caught as `Exception`, every one of
            // them escaped a `lazy` whose entire documented purpose is to answer null.
            //
            // Failing to null is the right answer and already handled: `detectRegions` returns an empty
            // scan, the canvas continues unmarked and undimmed, and Stage 4 still reads the board — it
            // just reads a whole canvas instead of tagged price regions. Degraded, not lost.
            //
            // It is also a `lazy`, so this runs once and the null is remembered. A device with no model
            // does not re-attempt the load per crop.
            Log.e(TAG, "$REGION_MODEL_NAME failed to load — region marking disabled", t)
            null
        }
    }

    /**
     * What the region pass found on one canvas, BEFORE naming.
     *
     * @param boxes       the plausible boxes, in the canvas's own coordinates
     * @param confUsed    the confidence rung this canvas was finally decoded at
     * @param confStep    how many rungs down the escalation ladder that was (0 = the first)
     * @param dropped     boxes the plausibility guards threw out, by reason
     * @param discard     the canvas resolved to more than [MAX_REGIONS] panels and is not a board —
     *                    it must not be named, prepped, saved or read
     */
    private class RegionScan(
        val boxes: List<Detected>,
        val confUsed: Float = REGION_CONF_THRESHOLD,
        val confStep: Int = 0,
        val dropped: Map<String, Int> = emptyMap(),
        val discard: Boolean = false
    ) {
        val droppedCount: Int get() = dropped.values.sum()
    }

    /**
     * One canvas's raw head output, held so it can be DECODED repeatedly at different confidences
     * without running the model again — which is what makes the escalation ladder nearly free.
     */
    private class RegionHead(
        private val out: Array<FloatArray>,
        private val channelsFirst: Boolean,
        val channels: Int,
        val anchors: Int,
        val netSize: Int
    ) {
        /** Channel [c] of anchor [a], whichever way round the export laid them out. */
        fun ch(c: Int, a: Int): Float = if (channelsFirst) out[c][a] else out[a][c]
    }

    /**
     * The confidence rungs, strictly descending. The head is re-decoded down this ladder until at least
     * [MIN_REGIONS] boxes survive the plausibility guards — see [MIN_REGIONS].
     */
    private val confLadder: List<Float> by lazy {
        val rungs = mutableListOf(REGION_CONF_THRESHOLD)
        for (c in CONF_ESCALATION) if (c < rungs.last()) rungs.add(c)
        rungs
    }

    /**
     * How an earlier canvas's vertical coordinates map into this one's: `new = scale * old + offset`.
     *
     * [IDENTITY] means "the board has not moved", which is also what is used whenever no transform
     * could be established.
     */
    private data class Transform(val scale: Float, val offset: Float) {
        val isIdentity: Boolean get() = scale == 1f && offset == 0f
        fun applyTo(y: Float): Float = scale * y + offset

        /** The inverse: where a box observed under this motion sits in the board's own coordinates. */
        fun undo(box: BoxF): BoxF =
            if (isIdentity) box
            else BoxF(box.left, (box.top - offset) / scale, box.right, (box.bottom - offset) / scale)

        companion object {
            val IDENTITY = Transform(1f, 0f)
        }
    }

    /** One position in the board's remembered top-to-bottom tag order. */
    private class Slot(
        val label: String,
        var sightings: Int = 0,
        /** Pass 1's count, carried into pass 2 where [sightings] restarts. */
        val consolidated: Int = 0
    )

    /**
     * One named tag and every box ever fitted to it — the raw material for consolidation, whose median
     * is the position pass 2 measures against.
     */
    private class TagRecord(val label: String, val boxes: MutableList<BoxF> = mutableListOf())

    /**
     * One detected region on one canvas while it is being named. [matchPx] is its distance to the slot
     * the fit chose and [frames] how many remembered canvases offered that tag — diagnostics only,
     * except in [RegionNamer.consolidate], which borrows [frames] to carry a slot's sighting count
     * through [readingOrder].
     */
    private class Named(
        val box: BoxF,
        val confidence: Float,
        var label: String = "",
        var minted: Boolean = false,
        var matchPx: Float = 0f,
        var frames: Int = 0
    )

    /**
     * One canvas's own geometry, measured once and passed down.
     *
     * Everything the fit scales itself by lives here rather than being recomputed per region: the guard
     * is a fraction of the board's local PITCH, not of any one region's height, so it has to be a
     * property of the canvas.
     *
     * @param pitch   median vertical gap between neighbouring panels, WITHIN a column
     * @param minGap  the tightest such gap — what board motion is capped by
     * @param limit   the guard, one canvas's worth
     * @param tol     motion-fitting agreement tolerance
     * @param columns how many side-by-side stacks this canvas shows
     * @param colTol  ...and how far across a pairing may reach when there is more than one
     */
    private data class Geom(
        val pitch: Float = 0f,
        val minGap: Float = 0f,
        val limit: Float = 0f,
        val tol: Float = 0f,
        val columns: Int = 1,
        val colTol: Float = 0f
    )

    /** The consolidated board: each slot's median box with the canvases that saw it. */
    private class Board(
        val slots: List<Pair<BoxF, Int>>,
        val pruned: Int,
        val merged: Int
    )

    /**
     * Step E. Names each canvas's regions by fitting them to the board's remembered tag order, so a
     * tag means the same physical panel on every canvas of the run.
     *
     * Two modes, and they run the identical code — a consolidated board is just a one-entry history
     * that is never replaced:
     *
     *   - **pass 1** (no [board]): each canvas is measured against the last [HISTORY_FRAMES] canvases
     *     named. Its naming is thrown away; what is kept is [consolidate].
     *   - **pass 2** (a [board] supplied): every canvas is measured against ONE synthetic canvas built
     *     from each slot's median box, which no real canvas ever replaces.
     *
     * **Naming runs temporally BACKWARD**, from the run's latest frame to its oldest, so the latest
     * frame is the one that seeds the board — it is the frame whose prices matter most and the one most
     * likely to show every panel lit.
     *
     * State lives for one run: tags are only meaningful within a single camera's sequence, so a fresh
     * namer is made per pass of each [prepare] call.
     */
    private class RegionNamer(board: List<Pair<BoxF, Int>>? = null) {

        /** Every tag issued so far, so a new one is never a repeat. */
        private val issued = mutableListOf<String>()

        /**
         * The board as remembered: its tags in top-to-bottom order. This is the memory the fit works
         * against, and within a pass it only ever grows — a slot is never reordered, because the
         * physical stack it describes cannot be.
         */
        private var sequence = mutableListOf<Slot>()

        /** Every box ever fitted to each tag, for [consolidate]. */
        private val tags = LinkedHashMap<String, TagRecord>()

        /** True in pass 2: the sequence is given rather than learned, and the history never rolls. */
        private val fixed = board != null

        /**
         * The canvases the candidates are drawn from — in pass 1 the last [HISTORY_FRAMES] the namer
         * was handed that had regions. Naming runs backward, so these are the canvases LATER in the run
         * than the one being named. A canvas with no detections does not break the chain; the window
         * simply reaches further forward in time.
         */
        private val history = ArrayDeque<MutableList<Named>>()

        // ---- telemetry for the caller's log ----
        var slotsKnown = 0; private set
        var slotsUsed = 0; private set
        var minted = 0; private set
        var shiftSpan = ""; private set
        var shiftGain = 0; private set
        var zoomed = false; private set
        var columns = 1; private set

        init {
            if (board != null) {
                val synthetic = mutableListOf<Named>()
                for ((box, sightings) in board) {
                    val slot = Slot(newTag(), consolidated = sightings)
                    sequence.add(slot)
                    synthetic.add(Named(box, 0f, slot.label))
                }
                history.addLast(synthetic)
            }
        }

        private fun newTag(): String = boxLabel(issued.size).also { issued.add(it) }

        fun tagsIssued(): Int = issued.size

        /** The board's remembered tag order, top to bottom — what [BoardAudit] validates against. */
        fun boardOrder(): List<String> = sequence.map { it.label }

        /**
         * The same, with each slot's sighting count, for the log. In pass 2 the count pass 1 gave the
         * slot is shown alongside, as `A(4/9)` — this pass's sightings over the consolidated ones —
         * because a slot the board believes in that this pass keeps missing is the interesting case.
         */
        fun boardSummary(): String = sequence.joinToString(" > ") {
            if (it.consolidated > 0) "${it.label}(${it.sightings}/${it.consolidated})"
            else "${it.label}(${it.sightings})"
        }

        /**
         * Names one canvas's detections. Returns them in reading order.
         *
         * Nothing is decided by a single box: every region keeps all its reachable tags, the whole
         * canvas is fitted to the remembered order at once, and the board's own motion is removed first
         * if that explains more.
         */
        fun name(found: List<Detected>): List<Named> {
            val regions = readingOrder(found.map { Named(it.box, it.confidence) }) { it.box }
            val geom = geometry(regions)

            val identity = List(history.size) { Transform.IDENTITY }
            var costs = regions.map { candidates(it, identity, geom) }
            var placed = fit(regions, costs, geom)
            val explained = placed.count { it != null }
            var transforms = identity
            var gain = 0

            // Motion detection: measuring again with the board's own movement and size change removed
            // is accepted when it explains more regions — or, on a tie, places them closer to where the
            // board says they are.
            val voted = voteTransforms(regions, geom)
            if (voted.any { !it.isIdentity }) {
                val altCosts = regions.map { candidates(it, voted, geom) }
                val altPlaced = fit(regions, altCosts, geom)
                val altExplained = altPlaced.count { it != null }
                var better = altExplained > explained
                if (!better && SHIFT_ACCEPT_TIES && altExplained == explained) {
                    better = total(altCosts, altPlaced) < total(costs, placed)
                }
                if (better) {
                    gain = altExplained - explained
                    costs = altCosts; placed = altPlaced; transforms = voted
                }
            }

            apply(regions, costs, placed)
            shiftSpan = span(transforms)
            shiftGain = gain
            columns = geom.columns
            // A least-squares refine practically never returns a scale of exactly 1, so "was it a zoom"
            // has to be asked in pixels: did the scale term move neighbouring panels relative to each
            // other by a pixel or more? Anything less is a slide with rounding on it.
            zoomed = transforms.any { !it.isIdentity && abs(it.scale - 1f) * geom.pitch >= 1f }

            if (regions.isEmpty()) return regions
            if (fixed) {
                // A pass-2 mint is a panel the consolidated board does not have. Fold it into the
                // synthetic canvas so the canvases after it can match it too, instead of every one of
                // them minting the same panel again.
                //
                // Through the INVERSE of this canvas's transform: the board is held in its own
                // coordinates, and a box observed on a canvas that was zoomed is not in them. Filing it
                // raw would pin the new slot to whatever zoom the canvas that first saw it happened to
                // have, and every canvas at a different distance would then miss it and mint again —
                // which is the bug this fold-in exists to prevent.
                val undo = transforms.firstOrNull() ?: Transform.IDENTITY
                val fresh = regions.filter { it.minted }
                    .map { Named(undo.undo(it.box), it.confidence, it.label) }
                if (fresh.isNotEmpty()) history.first().addAll(fresh)
            } else {
                history.addLast(regions.toMutableList())
                while (history.size > HISTORY_FRAMES) history.removeFirst()
            }
            return regions
        }

        /** Measures this canvas once: pitch, guard, motion tolerance, columns. */
        private fun geometry(regions: List<Named>): Geom {
            if (regions.isEmpty()) return Geom()
            val heights = regions.map { it.box.height }
            val widths = regions.map { it.box.width }

            // Pitch is measured WITHIN each stack, never across two of them. Two side-by-side panels
            // sit at the same height, so a gap taken over the canvas as a whole would be zero and would
            // collapse the guard to nothing on exactly the boards that need it most.
            val cols = columnsOf(regions.map { it.box })
            val gaps = mutableListOf<Float>()
            for (group in cols) {
                val tops = group.map { regions[it].box.top }.sorted()
                gaps += tops.zipWithNext { a, b -> b - a }
            }
            // With one panel per stack there is no pitch to measure, so panel height is the only scale
            // available — the one case where height still stands in.
            val pitch = if (gaps.isNotEmpty()) median(gaps) else median(heights)
            return Geom(
                pitch = pitch,
                minGap = gaps.minOrNull() ?: pitch,
                limit = GUARD_PITCH_FRAC * pitch,
                tol = SHIFT_TOL_FRAC * median(heights),
                columns = cols.size,
                colTol = COLUMN_TOL_FRAC * median(widths)
            )
        }

        /** Total distance a fit paid, over the regions it actually placed. */
        private fun total(costs: List<Map<String, Pair<Float, Int>>>, placed: Array<Int?>): Float {
            var sum = 0f
            placed.forEachIndexed { i, j ->
                if (j != null) sum += costs[i][sequence[j].label]?.first ?: 0f
            }
            return sum
        }

        /** The motion removed, as a compact report string: `x0.71 +4`, or ranges across canvases. */
        private fun span(transforms: List<Transform>): String {
            val live = transforms.filter { !it.isIdentity }
            if (live.isEmpty()) return ""
            fun range(values: List<Float>, fmt: String): String {
                val lo = values.min()
                val hi = values.max()
                return if (lo == hi) fmt.format(lo) else "${fmt.format(lo)}..${fmt.format(hi)}"
            }
            val scale = range(live.map { it.scale }, "%.2f")
            val offset = range(live.map { it.offset }, "%+.0f")
            // A pure slide is the common case; don't clutter it with "x1.00".
            return if (live.all { it.scale == 1f }) offset else "x$scale $offset"
        }

        /**
         * The board's motion against each remembered canvas, as a scale and an offset.
         *
         * Two votes, in order of strength. A canvas with enough boxes gets the full **scale + offset**
         * fit ([voteScaled]); one with too few falls back to the **offset-only** vote
         * ([voteTranslation]). Either can decline, in which case the canvas is measured with no
         * transform at all.
         */
        private fun voteTransforms(regions: List<Named>, geom: Geom): List<Transform> {
            // With one region, motion and identity change are indistinguishable: any transform fits it
            // perfectly.
            if (regions.size < 2 || history.isEmpty()) return List(history.size) { Transform.IDENTITY }
            return history.map { old ->
                if (regions.size >= SCALE_MIN_BOXES && old.size >= 2) {
                    voteScaled(regions, old, geom.tol) ?: voteTranslation(regions, old, geom)
                } else {
                    voteTranslation(regions, old, geom)
                }
            }
        }

        /**
         * Solves for `new = scale * old + offset` by proposing from PAIRS of pairings.
         *
         * One correspondence fixes an offset; **two fix a scale as well**, because two points define a
         * line. So every pair of boxes here is tried against every pair there — both taken in
         * top-to-bottom order, so a proposal can never assume the board turned over — and each pair of
         * pairs yields exactly one candidate transform.
         *
         * Each candidate is then scored the same way the offset-only vote scores an offset: apply it to
         * every remembered box and count how many of THIS canvas's boxes land on one. The true
         * transform lines up every panel; a wrong one lines up a couple. Returns null when nothing
         * clears [SHIFT_MIN_SUPPORT], leaving the caller to fall back.
         */
        private fun voteScaled(regions: List<Named>, old: List<Named>, tol: Float): Transform? {
            val newTops = regions.map { it.box.top }
            val oldTops = old.map { it.box.top }

            var best: Transform? = null
            var bestSupport = 0
            var bestResidual = Float.MAX_VALUE

            for (i in newTops.indices) {
                for (j in i + 1 until newTops.size) {
                    val spanNew = newTops[j] - newTops[i]
                    if (spanNew <= 0f) continue
                    for (p in oldTops.indices) {
                        for (q in p + 1 until oldTops.size) {
                            val spanOld = oldTops[q] - oldTops[p]
                            if (spanOld <= 0f) continue
                            val scale = spanNew / spanOld
                            if (scale < MIN_SCALE || scale > MAX_SCALE) continue
                            val offset = newTops[i] - scale * oldTops[p]

                            var support = 0
                            var residual = 0f
                            for (n in newTops) {
                                val nearest = oldTops.minOf { abs(n - (scale * it + offset)) }
                                if (nearest <= tol) {
                                    support++
                                    residual += nearest
                                }
                            }
                            // Most boxes explained wins; on a tie, the tighter fit.
                            if (support > bestSupport || (support == bestSupport && residual < bestResidual)) {
                                bestSupport = support
                                bestResidual = residual
                                best = Transform(scale, offset)
                            }
                        }
                    }
                }
            }

            val seed = best ?: return null
            if (bestSupport < SHIFT_MIN_SUPPORT) return null
            return refine(regions, old, seed, tol)
        }

        /**
         * Re-fits the transform by least squares over everything it explains, rather than trusting the
         * two points that happened to propose it — the analogue of taking the median of an offset
         * vote's supporters. Falls back to [seed] if the refit is degenerate or lands outside the
         * scale band.
         */
        private fun refine(
            regions: List<Named>,
            old: List<Named>,
            seed: Transform,
            tol: Float
        ): Transform {
            // Pair each box here with the remembered box the seed puts it nearest to, keeping only the
            // pairs the seed actually explains.
            val pairs = mutableListOf<Pair<Double, Double>>()   // (old top, new top)
            for (r in regions) {
                val n = r.box.top
                var nearestOld = 0f
                var nearest = Float.MAX_VALUE
                for (o in old) {
                    val d = abs(n - seed.applyTo(o.box.top))
                    if (d < nearest) { nearest = d; nearestOld = o.box.top }
                }
                if (nearest <= tol) pairs += nearestOld.toDouble() to n.toDouble()
            }
            if (pairs.size < 2) return seed

            val count = pairs.size
            val sx = pairs.sumOf { it.first }
            val sy = pairs.sumOf { it.second }
            val sxx = pairs.sumOf { it.first * it.first }
            val sxy = pairs.sumOf { it.first * it.second }
            val denom = count * sxx - sx * sx
            if (abs(denom) < 1e-6) return seed                  // every point at the same height

            val scale = ((count * sxy - sx * sy) / denom).toFloat()
            val offset = ((sy - scale * sx) / count).toFloat()
            return if (scale < MIN_SCALE || scale > MAX_SCALE) seed else Transform(scale, offset)
        }

        /**
         * The offset-only vote, for canvases with too few boxes to estimate a scale.
         *
         * Every pairing of a region here with a region there proposes one offset, and the offset the
         * most pairings agree on wins, refined to the median of its supporters. Returned only if it
         * clears the support, size and non-zero tests — the size test measured against the SMALLEST gap
         * on this canvas, so a slide that is safe across a wide gap cannot overrun a narrow one.
         */
        private fun voteTranslation(regions: List<Named>, old: List<Named>, geom: Geom): Transform {
            // With one region, motion and identity change are indistinguishable: every offset gets
            // exactly one vote.
            if (regions.size < 2) return Transform.IDENTITY

            val cap = SHIFT_MAX_PITCHES * geom.minGap
            val offsets = regions.flatMap { r -> old.map { r.box.top - it.box.top } }
            var support = -1
            var bestAbs = 0f
            var value = 0f
            for (cand in offsets) {
                val agree = offsets.filter { abs(it - cand) <= geom.tol }
                // Most support wins; on a tie the smaller absolute offset does.
                if (agree.size > support || (agree.size == support && abs(cand) < bestAbs)) {
                    support = agree.size
                    bestAbs = abs(cand)
                    value = median(agree)
                }
            }
            return if (
                support < SHIFT_MIN_SUPPORT ||
                abs(value) > cap ||
                abs(value) <= geom.tol   // indistinguishable from no motion
            ) Transform.IDENTITY else Transform(1f, value)
        }

        /**
         * Every reachable tag for this box: `tag -> (closest distance, canvases offering it)`.
         *
         * The guard is [GUARD_PITCH_FRAC] of the board's local pitch and is a HARD limit: a corner that
         * has moved further than that is not the same panel drifting, so that pairing is not offered to
         * the fit at all, however well it would suit the rest of the canvas. With a transform supplied,
         * the guard applies to what is LEFT of the motion once the board's own is removed.
         *
         * On a genuinely multi-column canvas a box may not take a tag from the other stack, however
         * well the heights line up — the one place the x axis is consulted.
         */
        private fun candidates(
            region: Named,
            transforms: List<Transform>,
            geom: Geom
        ): Map<String, Pair<Float, Int>> {
            val out = LinkedHashMap<String, Pair<Float, Int>>()
            history.forEachIndexed { hi, old ->
                val transform = transforms.getOrElse(hi) { Transform.IDENTITY }
                for (o in old) {
                    if (geom.columns > 1 && abs(o.box.midX - region.box.midX) > geom.colTol) continue
                    // The old box is moved AND resized to where the board's motion says it would now
                    // be, before anything is measured. Only its closest PASSING corner counts.
                    val near = closestCorner(region.box, o.box, transform, geom.limit) ?: continue
                    val prev = out[o.label]
                    // A tag is unique within a canvas, so a repeat is one more canvas offering it;
                    // keep the closest distance any of them gave.
                    out[o.label] = if (prev == null) near to 1 else min(prev.first, near) to prev.second + 1
                }
            }
            return out
        }

        /**
         * Fits the canvas to the remembered order. Returns the slot index chosen per region, or null
         * where the fit could place none.
         *
         * Both sides may have gaps: a remembered slot the detector missed on this canvas is skipped for
         * free, and a region that cannot be placed is minted at a cost of [MINT_COST_FACTOR] guard
         * limits — one price for the whole canvas, since the guard is now a property of the canvas
         * rather than of each box. What the order buys is that region `i` can only take a slot after
         * region `i-1`'s, so a box cannot pick a tag that contradicts its neighbours — the case a
         * box-by-box decision gets wrong, because a tag that looks best for one box in isolation may
         * leave every other box on the canvas unexplainable.
         *
         * A plain O(regions × slots) alignment: `f[i][j]` is the cheapest way to account for the first
         * `i` regions using the first `j` slots.
         */
        private fun fit(
            regions: List<Named>,
            costs: List<Map<String, Pair<Float, Int>>>,
            geom: Geom
        ): Array<Int?> {
            val n = regions.size
            val m = sequence.size
            val mint = MINT_COST_FACTOR * geom.limit
            val index = HashMap<String, Int>(m)
            sequence.forEachIndexed { j, slot -> index[slot.label] = j }
            // The optimiser's own copy of the costs, and the ONLY place the tie-break exists: on two
            // fits priced identically in pixels, the slot more canvases offered wins. [total] and the
            // reported matchPx read `costs`, so the epsilon never reaches a printed number or the mint
            // comparison.
            val pair = costs.map { c ->
                val byIndex = HashMap<Int, Float>()
                for ((tag, v) in c) index[tag]?.let { byIndex[it] = v.first - TIE_EPS * v.second }
                byIndex
            }

            val inf = Float.POSITIVE_INFINITY
            val f = Array(n + 1) { FloatArray(m + 1) { inf } }
            val back = Array(n + 1) { arrayOfNulls<Move>(m + 1) }
            f[0][0] = 0f
            for (j in 1..m) { f[0][j] = 0f; back[0][j] = Move.SKIP }
            for (i in 1..n) { f[i][0] = f[i - 1][0] + mint; back[i][0] = Move.MINT }

            for (i in 1..n) {
                for (j in 1..m) {
                    // Assignment is tested first and wins ties, so an explained region is always
                    // preferred to a skipped slot or a mint.
                    var best = inf
                    var how: Move? = null
                    val px = pair[i - 1][j - 1]
                    if (px != null) { best = f[i - 1][j - 1] + px; how = Move.ASSIGN }
                    if (f[i][j - 1] < best) { best = f[i][j - 1]; how = Move.SKIP }
                    if (f[i - 1][j] + mint < best) { best = f[i - 1][j] + mint; how = Move.MINT }
                    f[i][j] = best
                    back[i][j] = how
                }
            }

            val placed = arrayOfNulls<Int>(n)
            var i = n
            var j = m
            while (i > 0 || j > 0) {
                when (back[i][j]) {
                    Move.ASSIGN -> { placed[i - 1] = j - 1; i--; j-- }
                    Move.SKIP -> j--
                    else -> i--
                }
            }
            return placed
        }

        /**
         * Writes the fit onto the regions and folds new panels into the sequence.
         *
         * A minted tag is INSERTED where the region was seen, not appended: a strip above `A` becomes a
         * slot before `A`, so the next canvas can fit that panel in its real place. It goes immediately
         * before the next slot this canvas did use, which is the tightest position the canvas justifies.
         */
        private fun apply(
            regions: List<Named>,
            costs: List<Map<String, Pair<Float, Int>>>,
            placed: Array<Int?>
        ) {
            // For a region that mints, the slot its successors settled on — how far down the sequence
            // the new slot has to be inserted.
            val after = IntArray(regions.size + 1)
            var nxt = sequence.size
            for (i in regions.indices.reversed()) {
                after[i] = nxt
                placed[i]?.let { nxt = it }
            }

            val rebuilt = mutableListOf<Slot>()
            var si = 0
            regions.forEachIndexed { i, region ->
                val j = placed[i]
                if (j != null) {
                    for (k in si until j) rebuilt.add(sequence[k])   // panels this canvas missed
                    val slot = sequence[j]
                    slot.sightings++
                    rebuilt.add(slot)
                    si = j + 1
                    region.label = slot.label
                    costs[i][slot.label]?.let { region.matchPx = it.first; region.frames = it.second }
                } else {
                    for (k in si until after[i]) rebuilt.add(sequence[k])
                    si = max(si, after[i])
                    val slot = Slot(newTag(), 1)
                    rebuilt.add(slot)
                    region.label = slot.label
                    region.minted = true
                }
                tags.getOrPut(region.label) { TagRecord(region.label) }.boxes.add(region.box)
            }
            for (k in si until sequence.size) rebuilt.add(sequence[k])

            slotsKnown = sequence.size
            slotsUsed = placed.count { it != null }
            minted = placed.count { it == null }
            sequence = rebuilt
        }

        /**
         * Reduces what pass 1 learned to the board pass 2 will be fitted against.
         *
         * Four things happen here, and every one of them is something single-pass naming structurally
         * could not do:
         *
         *  - each slot becomes the MEDIAN of every box ever fitted to it, so its position is decided by
         *    the whole run rather than by whichever canvas happened to be named first;
         *  - slots that turn out to be the same panel twice are merged ([BOARD_MERGE_OVERLAP]);
         *  - slots too few canvases ever saw are dropped ([BOARD_MIN_SIGHTINGS]) — those are the
         *    spurious boxes a feed-forward run mints once and then honours for the rest of the run;
         *  - what is left is re-sorted into [readingOrder], which is where an order pass 1 got wrong
         *    quietly corrects itself.
         *
         * @param canvases how many canvases actually had regions — the denominator for the relative
         *   sighting threshold.
         */
        fun consolidate(canvases: Int): Board {
            if (sequence.isEmpty()) return Board(emptyList(), 0, 0)

            // (boxes, sightings) per slot, in median-top order so duplicates of one panel end up
            // adjacent whatever order pass 1 happened to mint them in.
            val entries = sequence.mapNotNull { slot ->
                val boxes = tags[slot.label]?.boxes
                if (boxes.isNullOrEmpty()) null else boxes.toMutableList() to slot.sightings
            }.sortedBy { medianBox(it.first).top }.map { it.first to it.second }.toMutableList()
            if (entries.isEmpty()) return Board(emptyList(), sequence.size, 0)

            val merged = mutableListOf<Pair<MutableList<BoxF>, Int>>()
            for ((boxes, seen) in entries) {
                val last = merged.lastOrNull()
                if (last != null) {
                    val a = medianBox(last.first)
                    val b = medianBox(boxes)
                    val shorter = min(a.height, b.height)
                    val overlap = min(a.bottom, b.bottom) - max(a.top, b.top)
                    val narrower = min(a.width, b.width)
                    val sameColumn = abs(a.midX - b.midX) <= COLUMN_TOL_FRAC * narrower
                    if (shorter > 0f && sameColumn && overlap > BOARD_MERGE_OVERLAP * shorter) {
                        last.first.addAll(boxes)
                        merged[merged.lastIndex] = last.first to (last.second + seen)
                        continue
                    }
                }
                merged.add(boxes to seen)
            }

            val need = min(
                BOARD_MIN_SIGHTINGS,
                max(1, ceil(BOARD_MIN_SIGHTING_FRAC * canvases).toInt())
            )
            var keep = merged.filter { it.second >= need }
            if (keep.size < MIN_REGIONS) {
                // Never prune the board below what a price post structurally has; if the evidence is
                // that thin, keep the best-attested slots instead.
                keep = merged.sortedByDescending { it.second }.take(MIN_REGIONS)
            }

            // `frames` is borrowed as the sightings carrier so readingOrder, which works on boxes, can
            // put the board in the order pass 2 will read in.
            val medians = keep.map { (boxes, seen) ->
                Named(medianBox(boxes), 0f).apply { frames = seen }
            }
            val board = readingOrder(medians) { it.box }.map { it.box to it.frames }
            return Board(board, merged.size - board.size, entries.size - merged.size)
        }

        /** How the alignment reached a cell. */
        private enum class Move { ASSIGN, SKIP, MINT }
    }

    // ------------------------------------------------------------------------------------------ //
    // Stage entry point
    // ------------------------------------------------------------------------------------------ //

    /** One canvas carried between the passes: what was detected on it, and what it was named. */
    private class Scanned(
        val detection: BoardDetection,
        val scan: RegionScan,
        val gateScore: Double
    ) {
        var named: MutableList<Named> = mutableListOf()
        var slotsKnown = 0
        var slotsUsed = 0
        var minted = 0
        var shiftSpan = ""
        var columns = 1
        var zoomed = false
    }

    /**
     * Runs Steps A-G over every board crop Stage 2 produced. Returns one [PreppedFrame] per crop that
     * cleared the guardrails — a crop that can't be decoded off disk is simply absent — plus the
     * board's layout from Step G.
     *
     * Three loops, because naming is two passes and the artifact cannot be written until the letters
     * are final: scan every canvas, name every canvas against the consolidated board, then rebuild each
     * canvas to dim, annotate and save it. Step G's clean canvases are captured inside that last loop,
     * which is the only place they exist.
     */
    suspend fun prepare(
        detections: List<BoardDetection>,
        /**
         * DAY or NIGHT for this run, which selects the branch Steps B and D take. Defaults to a day run
         * so a caller that has not decided gets the filter that cannot damage a readable crop.
         */
        light: LightDecision = LightDecision(LightPhase.DAY, System.currentTimeMillis(), "default")
    ): PrepResult {
        var unreadable = 0
        var overcrowded = 0
        var blurred = 0
        var escalated = 0
        var shortOfTwo = 0
        var shifted = 0
        var zoomedIn = 0
        var totalRegions = 0
        val droppedWhy = LinkedHashMap<String, Int>()

        // ---- Which crops of this board get looked at, and in what order ----
        //
        // CONFIDENCE first, then newest-first within equal confidence. The cap below takes the first
        // [MAX_BOARDS_FORWARD] survivors of this order, so the detector's own opinion now decides which
        // crops are spent rather than the clock alone.
        //
        // It used to be time only, on the argument that the latest crop is the nearest and carries the
        // most complete prices. That holds on a clean drive-past and fails on the shape this app
        // actually sees: the last crops before a board leaves frame are taken at the sharpest angle and
        // are regularly the worst of the set, while a strong mid-approach detection is thrown away
        // because forty later ones filled the fourteen slots.
        //
        // Rounded to [CONF_BUCKET] before comparing. Raw floats would make the order almost entirely
        // confidence-driven and discard the time signal completely — 0.8123 beating 0.8119 is noise, not
        // a judgement — so crops of comparable confidence are still taken newest-first.
        val ordered = detections.sortedWith(
            byConfidenceThenNewest({ it.confidence }, { it.timestampUs })
        )

        // ---- What this changes about the tag anchor ----
        //
        // Pass 1's naming fits every canvas against the FIRST one it walks, so the leading crop is the
        // board's tag anchor. That used to be the latest crop, chosen because it is the nearest and the
        // likeliest to show the full panel stack. It is now the most confident one.
        //
        // The bet is that they usually coincide: this detector was trained on whole price posts, so a
        // partial or badly-angled board tends to score lower, and the highest-confidence crop is
        // normally a well-framed complete one. **That is an assumption, not a measurement.** The way it
        // would fail is a tight, very confident crop of a board's top half anchoring the whole run on a
        // partial layout — visible as a run whose rows are consistently short by the bottom panel.
        //
        // The returned list is put back into ascending time order at the end either way.
        val netSize = regionInputSize()
        Log.d(TAG, "image processing branch: ${DayNight.describe(light)}")

        // ---- Pass 1 — inference, and a provisional naming whose only job is to learn the board. ----
        val learner = RegionNamer()
        val scanned = mutableListOf<Scanned>()
        // Crops never looked at because the run already has its [MAX_BOARDS_FORWARD] canvases. Reported
        // rather than passed over in silence: "prepped 14/60" with nothing else said reads as 46 crops
        // FAILING something, which is the opposite of what happened.
        var notReached = 0
        val attempted = LinkedHashSet<Int>()
        for ((position, d) in ordered.withIndex()) {
            // The cap, applied at the top of the walk rather than by taking a prefix up front: it counts
            // SURVIVORS, and whether a crop survives is only known after it is decoded and gated, so a
            // crop the gate drops costs nothing and the walk simply reaches one further down the order.
            if (scanned.size >= MAX_BOARDS_FORWARD) {
                notReached = ordered.size - position
                break
            }
            // Counted BEFORE the decode, so a crop that could not even be opened still reads as looked
            // at {DASH} which it was, and the reason it produced nothing is a different fact.
            attempted.add(d.detectionId)
            val crop = BitmapFactory.decodeFile(d.cropPath)
            if (crop == null) {
                Log.w(TAG, "Could not decode crop ${d.slug} at ${d.cropPath}")
                unreadable++
                continue
            }
            var canvas: Bitmap? = null
            try {
                // Step A — clarity. The gate MOVED to Stage 2, where a rejected crop makes the detector
                // re-sample rather than merely shrink the run; this is the second line of defence, not
                // the decision. With the same constants over the same pixels it should never fire, and
                // that is the point: if it ever does, the two stages have drifted apart and this is
                // where it becomes visible instead of a blurred board reaching the reader.
                //
                // Free to check — the score is computed here anyway for the dashboard.
                val gate = ClarityGate.score(crop)
                if (!gate.isSuitable) {
                    blurred++
                    Log.w(
                        TAG,
                        "${d.slug}: REJECTED by the clarity gate a second time — ${gate.reason}. " +
                            "Stage 2 passed it, so the two gates disagree."
                    )
                    continue
                }
                canvas = buildCanvas(crop, netSize, light)
                val scan = detectRegions(canvas, d.slug, netSize)

                for ((why, n) in scan.dropped) droppedWhy[why] = (droppedWhy[why] ?: 0) + n
                if (scan.confStep > 0) escalated++
                if (scan.discard) {
                    overcrowded++
                    continue
                }
                if (scan.boxes.size < MIN_REGIONS) shortOfTwo++
                totalRegions += scan.boxes.size

                learner.name(scan.boxes)                 // provisional — thrown away
                scanned.add(Scanned(d, scan, gate.score))
            } catch (t: Throwable) {
                // `Throwable`, so an `OutOfMemoryError` costs THIS crop and not the board.
                //
                // This loop is the heaviest allocator in the app — a decode, five OpenCV `Mat`s and a
                // fresh 640 canvas per crop, over every detection of the board — so OOM is its
                // characteristic failure, and it is an `Error`. Skipping one crop is a genuinely good
                // answer to it: the `finally` below frees this crop's bitmaps immediately, which is
                // often the very memory the next crop needs, and a board is photographed dozens of
                // times so the ones that fit still reach the reader. Left as `Exception` the whole
                // stage went, and `PriceBoardPipeline` reported a detected board as unpreppable.
                //
                // `UnsatisfiedLinkError` out of OpenCV's loader is also `Error`, and also arrives here
                // — that one will fail on every crop, which is the correct and visible outcome: every
                // crop logs, nothing is prepped, and the stage reports no canvases.
                Log.e(TAG, "Image prep failed on crop ${d.slug}", t)
            } finally {
                canvas?.recycle()
                crop.recycle()
            }
        }

        // ---- Between the passes — consolidate what pass 1 learned into one board. ----
        val board = learner.consolidate(scanned.count { it.scan.boxes.isNotEmpty() })
        Log.d(
            TAG,
            "board consolidated: ${board.slots.size} slot(s) from ${learner.tagsIssued()} tag(s) " +
                "(${board.merged} merged, ${board.pruned} pruned) — pass 1 order ${learner.boardSummary()}"
        )

        // ---- Pass 2 — re-name every canvas against that board. Naming only: the drawing waits for
        // Step F, which may still change what a letter means. With no board (nothing detected at all)
        // this is a plain rolling-window run, i.e. exactly the old behaviour.
        val namer = if (board.slots.isNotEmpty()) RegionNamer(board.slots) else RegionNamer()
        for (s in scanned) {
            s.named = namer.name(s.scan.boxes).toMutableList()
            s.slotsKnown = namer.slotsKnown
            s.slotsUsed = namer.slotsUsed
            s.minted = namer.minted
            s.shiftSpan = namer.shiftSpan
            s.columns = namer.columns
            s.zoomed = namer.zoomed
            if (s.shiftSpan.isNotEmpty()) shifted++
            Log.d(
                TAG,
                "${s.detection.slug}: ${s.named.joinToString(",") { it.label }} " +
                    "(${s.slotsUsed}/${s.slotsKnown} slot(s) used, ${s.minted} minted" +
                    (if (s.shiftSpan.isNotEmpty()) ", shift ${s.shiftSpan}" else "") +
                    (if (s.zoomed) " (zoom)" else "") +
                    (if (s.columns > 1) ", ${s.columns} columns" else "") + ")"
            )
        }

        // ---- Step F — the board audit, between naming and drawing. ----
        var audit = AuditRecord(ran = false, error = "audit disabled or not configured")
        // It runs here and not one loop earlier for a reason: it is shown the naming of the WHOLE run at
        // once, which is the only vantage point from which a name used for two different panels is
        // visible at all. Drawing therefore cannot start until it has answered — an annotated canvas
        // carrying a letter the audit went on to change would be an artifact of a board that never
        // existed.
        if (LLM_AUDIT_ENABLED && BoardAudit.isConfigured) {
            // Fails OPEN, leaving the naming exactly as Step E made it — which is what
            // `PriceBoardPipeline`'s network-marker note already promises this step does ("the audit
            // leaves the naming as Step E made it"), and until now only half true. `BoardAudit.post`
            // catches its own HTTP faults and returns an error-carrying `Reply`, but everything around
            // it does not: `buildPayload` walks every named canvas of the run into one JSON document,
            // and `parseActions`/`applyActions` walk a model's reply. A throw in any of them aborted
            // Stage 3 outright — so a board that had been detected, cropped and named was reported as
            // unpreppable because a validation of the naming failed.
            //
            // That document is GEOMETRY, not images — a label, two corners and an area per region — and
            // Step F's prompt says "No images." in as many words. This comment used to claim
            // `buildPayload` base64-encodes every canvas of the run into the largest single allocation
            // the prep stage makes. It does not, and nothing here does: the only base64 in this stage is
            // Step G's, and that is at most `BoardLayoutReader.FRAMES` canvases (see `wantedLayout`
            // below), never the run. The `try` below is still needed — `parseActions`/`applyActions`
            // are the throwing half — but not for that reason.
            //
            // The audit only ever corrects labels that already exist. Not running it costs a check, and
            // the record below says so, which is what `cloud_calls.json` reports.
            audit = try {
                runAudit(scanned, namer.boardOrder(), netSize)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.e(TAG, "Step F threw — the naming is left as Step E made it", t)
                AuditRecord(ran = false, error = "the audit failed unexpectedly")
            }
        }

        // Which canvases Step G will want, decided BEFORE the write loop because that loop is the only
        // place the undimmed canvas exists — it is rebuilt there and then overwritten by dimming and
        // drawing. Chosen from the FINAL labels, so an audit that changed the board changes this too,
        // and so the letters tagged onto Step G's canvas are the ones the rest of the run uses.
        @Suppress("UNUSED_VALUE")
        val wantedLayout: Set<String> =
            if (LLM_LAYOUT_ENABLED && BoardLayoutReader.isConfigured) {
                layoutFrames(scanned).map { it.detection.slug }.toSet()
            } else emptySet()
        val layoutCanvases = LinkedHashMap<String, ByteArray>()
        val layoutCanvasPaths = mutableListOf<String>()

        // ---- Write the artifacts, using whatever the labels finally are. ----
        val prepped = mutableListOf<PreppedFrame>()
        for (s in scanned) {
            val d = s.detection
            val crop = BitmapFactory.decodeFile(d.cropPath)
            if (crop == null) {
                Log.w(TAG, "Crop ${d.slug} vanished between naming and writing")
                unreadable++
                continue
            }
            var canvas: Bitmap? = null
            try {
                // The canvas the model saw, REBUILT rather than carried: holding a 640x640 bitmap per
                // crop across the whole run would be tens of megabytes, and rebuilding it is a decode
                // plus Step B plus Step C.
                canvas = buildCanvas(crop, netSize, light)
                var regions = s.named.map { PriceRegion(it.box.toRect(), it.confidence, it.label) }

                // Store under prepped/<videoId>/, named by the DETECTION: two boards in one frame would
                // otherwise overwrite each other here.
                val preppedDir = File(preppedRootDir(), videoIdOf(d.cropPath)).apply { mkdirs() }

                // Step G's canvas, built HERE because this is the only place the undimmed canvas exists.
                // It is the clean canvas plus outlines and OUTER letter tags: Step G has to read the fuel
                // names, which the dimming crushes and an inner tag plate covers, but it also has to say
                // which LETTER each name belongs to — so the tags have to be on the image, just not on
                // top of the answer. Held in memory for the few frames Step G will send, and only for
                // those: keeping every canvas would be the very cost the rebuild-per-crop design exists
                // to avoid.
                if (d.slug in wantedLayout) {
                    val tagged = canvas.copy(Bitmap.Config.ARGB_8888, true)
                    if (tagged != null) {
                        try {
                            drawRegions(tagged, regions, outerTags = true)
                            ByteArrayOutputStream().use { jpeg ->
                                if (tagged.compress(
                                        Bitmap.CompressFormat.JPEG, BoardLayoutReader.JPEG_QUALITY, jpeg
                                    )
                                ) {
                                    layoutCanvases[d.slug] = jpeg.toByteArray()
                                }
                            }
                            // Written out as well as sent: the artifact is what a reader of the debug
                            // folder needs to see WHY a letter was named what it was named.
                            val tagFile = File(preppedDir, "prepped_${d.slug}_tag.png")
                            FileOutputStream(tagFile).use { out ->
                                tagged.compress(Bitmap.CompressFormat.PNG, 100, out)
                            }
                            layoutCanvasPaths.add(tagFile.absolutePath)
                        } finally {
                            tagged.recycle()
                        }
                    }
                }

                // Step C2 AFTER Step G's copy and BEFORE anything is drawn. Step G is left on the whole
                // board on purpose: it is there to read the fuel names, and a name printed above or below
                // the block of panels is exactly what this re-frame cuts away. Stage 4 has the opposite
                // need — it reads digits inside the boxes, and wants them as large as the canvas allows.
                val zoom = zoomToPrices(crop, regions, netSize, light)
                if (zoom != null) {
                    canvas.recycle()
                    canvas = zoom.canvas
                    regions = zoom.regions
                    zoomedIn++
                }

                // Step D before drawing: both branches read the canvas's own pixels, so this must run
                // before the opaque red boxes and tag plates are painted over them.
                //
                // Day uses max-channel pixels only inside the final price boxes, after any zoom.
                // The region detector and Step G still see the original board preparation.
                if (!light.isNight && zoom == null) {
                    rerenderDayRegions(canvas, crop, regions, netSize)
                }
                // Day crushes faint ghosts (black point 0.80, gamma 1.30); night re-stretches mildly
                // and erodes to reopen gaps that bloom closed.
                val dimmed = when {
                    !openCvReady -> 0
                    light.isNight -> NightFilter.finish(canvas, regions)
                    else -> dimUnlitSegments(canvas, regions)
                }
                drawRegions(canvas, regions)

                // Re-score the finished canvas (dashboard telemetry only — no gate applies here).
                val finalScore = ClarityGate.score(canvas)

                val outFile = File(preppedDir, "prepped_${d.slug}.png")
                FileOutputStream(outFile).use { out ->
                    canvas.compress(Bitmap.CompressFormat.PNG, 100, out)
                }

                prepped.add(
                    PreppedFrame(
                        detectionId = d.detectionId,
                        frameIndex = d.frameIndex,
                        rank = d.rank,
                        timestampUs = d.timestampUs,
                        preppedPath = outFile.absolutePath,
                        gateClarityScore = s.gateScore,
                        confidence = d.confidence,
                        finalClarityScore = finalScore.score,
                        regions = regions,
                        dimmedBoxes = dimmed,
                        slug = d.slug
                    )
                )
            } catch (t: Throwable) {
                // `Throwable`, for the reason the pass-1 loop above gives: one canvas that could not be
                // built or written must not take the canvases either side of it, and on this pass the
                // fault that does that is an `OutOfMemoryError` or a volume that filled up mid-write.
                // Every surviving canvas is still handed to Stage 4.
                Log.e(TAG, "Writing the prepped canvas failed for ${d.slug}", t)
            } finally {
                canvas?.recycle()
                crop.recycle()
            }
        }

        // The board's own row order, measured once from the finished naming. Computed unconditionally,
        // not just for Step G: it is what a row number MEANS downstream, so a run with the cloud steps
        // switched off must still be able to say which panel is the board's second.
        val boardOrder = boardOrder(scanned)

        // ---- Step G — the board's layout, and the fuel each letter sells. ----
        var layout = BoardLayout()
        if (wantedLayout.isNotEmpty()) {
            // Fails OPEN to a bare [BoardLayout], which `PriceBoardPipeline` reads as "not applied" —
            // and an unapplied layout means the board keeps its box letters and gets no fuel names.
            // That is the documented behaviour of this step failing ("the layout is simply not
            // applied"), and it costs the NAMES on the rows, never the prices: Stage 5 consolidates on
            // box letters and passes `fuelNames` only when the layout was applied.
            //
            // Guarded for the same reason as Step F above: `post` handles the HTTP, but `parse` and
            // `map` chew through a model's reply, and the request carries every tagged canvas as an
            // image. A run whose fuel names could not be read must still file its prices.
            layout = try {
                BoardLayoutReader.read(
                    images = wantedLayout.mapNotNull { layoutCanvases[it] },
                    order = boardOrder
                )
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.e(TAG, "Step G threw — the board keeps its letters and no names", t)
                BoardLayout(
                    ran = true, panels = boardOrder.size, order = boardOrder,
                    error = "the layout read failed unexpectedly"
                )
            }
        }

        Log.d(
            TAG,
            "Prepped ${prepped.size}/${detections.size} crop(s) " +
                (if (notReached > 0) "(capped at $MAX_BOARDS_FORWARD — $notReached older crop(s) " +
                    "not looked at) " else "") + "— " +
                "$unreadable unreadable, $blurred re-rejected by the clarity gate, " +
                "$overcrowded discarded for >$MAX_REGIONS regions, " +
                "$totalRegions price region(s) marked; $escalated canvas(es) needed a lower " +
                "confidence, $shortOfTwo still under $MIN_REGIONS regions, " +
                "$shifted shift-corrected, $zoomedIn re-framed on their prices" +
                (if (droppedWhy.isEmpty()) "" else
                    ", dropped " + droppedWhy.entries.joinToString { "${it.value} ${it.key}" })
        )
        Log.d(TAG, "Board as named (top to bottom): ${namer.boardSummary()}")
        logStability(scanned, namer)

        // Back into ascending time order: naming walked the run backward, and every downstream stage
        // reads this list forward. The detectionId tie-break keeps two boards from ONE frame in rank
        // order (strongest first), which a stable sort on the timestamp alone would leave reversed by
        // the backward walk.
        //
        // This order is presentation, NOT selection. It used to say "Stage 4 takes the LAST entries as
        // the most recent boards", which was a claim about a stage this one cannot see — and a false
        // one: Stage 4 took the FIRST entries, so the earliest canvases were read and the nearest ones
        // discarded. Stage 4 now re-derives THIS stage's own ranking to pick its pool
        // ([byConfidenceThenNewest], via `PriceBoardPipeline.selectOcrFrames`), so which canvases are
        // best is decided once, here, and the order this list happens to be in decides nothing.
        return PrepResult(
            frames = prepped.sortedWith(compareBy({ it.timestampUs }, { it.detectionId })),
            layout = layout,
            audit = audit,
            boardOrder = boardOrder,
            attempted = attempted,
            layoutCanvasPaths = layoutCanvasPaths.toList()
        )
    }

    /**
     * The canvases that show the most of the board, newest first.
     *
     * Ranked by panel count before anything else: a canvas showing four of five panels cannot reveal
     * the fifth row's name however sharp it is. Clarity breaks ties, and lateness breaks those — the
     * newest canvases are nearest the board, so their text is largest.
     */
    private fun layoutFrames(scanned: List<Scanned>): List<Scanned> =
        scanned.filter { it.named.isNotEmpty() }
            .sortedWith(
                compareByDescending<Scanned> { it.named.size }
                    .thenByDescending { it.gateScore }
                    .thenByDescending { it.detection.timestampUs }
            )
            .take(BoardLayoutReader.FRAMES)

    /**
     * The labels in the board's own order, top to bottom — measured, not assumed.
     *
     * A label's position is the MEDIAN vertical centre of its box over every canvas it appears on. The
     * median rather than one canvas's value because the board slides and zooms through a run, and
     * rather than the mean because one clipped box would drag an average past its neighbour.
     *
     * The ONLY statement of the board's order, and it is measured rather than read off the letters:
     * those follow Step E's minting order, and Step F does not renumber them. Works whether or not
     * the audit ran — it changes which labels are live, not what this measures.
     */
    private fun boardOrder(scanned: List<Scanned>): List<String> {
        val centres = LinkedHashMap<String, MutableList<Float>>()
        for (s in scanned) {
            for (r in s.named) {
                centres.getOrPut(r.label) { mutableListOf() }.add((r.box.top + r.box.bottom) / 2f)
            }
        }
        return centres.entries.sortedBy { median(it.value) }.map { it.key }
    }

    /**
     * Output stability — the three numbers a run with Step F is compared against a run without.
     *
     * None of them can be read off one canvas, which is why they are computed here over the finished
     * naming rather than logged per canvas:
     *
     *  - **surplus tags** — tags issued minus the most regions ever seen on ONE canvas. A board with
     *    five panels that minted eight letters has three that are aliases of the others, and every one
     *    of them is a slot in the order that every later canvas is fitted against.
     *  - **singleton tags** — letters that appear on exactly one canvas. A real panel is seen repeatedly;
     *    a letter seen once is usually a box the detector clipped or a reflection that got a name.
     *  - **canvas agreement** — the share of canvases whose set of letters matches the modal set. A board
     *    that is genuinely stable reads the same on nearly every canvas.
     */
    private fun logStability(scanned: List<Scanned>, namer: RegionNamer) {
        val named = scanned.filter { it.named.isNotEmpty() }
        if (named.isEmpty()) return

        val peak = named.maxOf { it.named.size }
        val issued = namer.tagsIssued()
        val surplus = (issued - peak).coerceAtLeast(0)

        val canvasesPerLabel = LinkedHashMap<String, Int>()
        named.forEach { s ->
            s.named.map { it.label }.toSet().forEach { canvasesPerLabel.merge(it, 1, Int::plus) }
        }
        val singletons = canvasesPerLabel.count { it.value == 1 }

        // The modal label SET, not the modal count: two canvases showing three panels each agree only
        // if they are the same three.
        val shapes = named.groupingBy { s -> s.named.map { it.label }.toSortedSet().joinToString(",") }
            .eachCount()
        val modal = shapes.values.max()
        val agreement = 100.0 * modal / named.size

        Log.d(
            TAG,
            "stability: %d tag(s) issued, peak %d on one canvas -> %d surplus, %d singleton(s), %.0f%% canvas agreement (%d/%d)"
                .format(issued, peak, surplus, singletons, agreement, modal, named.size)
        )
        if (surplus > 0 || singletons > 0) {
            Log.d(
                TAG,
                "  per label, canvases seen on: " +
                    canvasesPerLabel.entries.joinToString { "${it.key}=${it.value}" }
            )
        }
    }

    /**
     * Step F. Hands the whole run's naming to [BoardAudit] and folds back whatever survived validation.
     *
     * The audit works on its own flat geometry type, so nothing of Stage 3's internals crosses the
     * network seam. Regions come back addressed by the index they went out with, which is how a
     * removal and a rename are told apart from each other on the way home.
     */
    private suspend fun runAudit(
        scanned: List<Scanned>,
        boardOrder: List<String>,
        netSize: Int
    ): AuditRecord {
        val canvases = scanned.mapNotNull { s ->
            if (s.named.isEmpty()) null else AuditCanvas(
                frameIndex = s.detection.frameIndex,
                name = s.detection.slug,
                regions = s.named.mapIndexed { i, n ->
                    AuditRegion(i, n.box.left, n.box.top, n.box.right, n.box.bottom, n.label)
                }.toMutableList()
            )
        }
        if (canvases.isEmpty()) return AuditRecord(ran = false, error = "nothing named to audit")

        val videoId = scanned.firstOrNull()?.let { videoIdOf(it.detection.cropPath) } ?: "unknown"
        val outcome = BoardAudit.run(canvases, boardOrder, netSize, videoId)
        if (!outcome.ran || outcome.error != null) {
            return AuditRecord(ran = outcome.ran, error = outcome.error ?: "audit did not run")
        }

        // Fold the audited labels back on. A region the audit removed is not in `regions` any more, so
        // it drops out here — which is exactly what "remove" has to mean by the time Stage 4 reads the
        // canvas.
        val byName = canvases.associateBy { it.name }
        for (s in scanned) {
            val audited = byName[s.detection.slug] ?: continue
            val kept = mutableListOf<Named>()
            for (r in audited.regions) {
                val original = s.named.getOrNull(r.id) ?: continue
                original.label = r.label
                kept.add(original)
            }
            s.named = kept
        }
        return AuditRecord(ran = true)
    }

    // ------------------------------------------------------------------------------------------ //
    // Step B — enhancement
    // ------------------------------------------------------------------------------------------ //

    /**
     * Greyscale → CLAHE → unsharp mask. Returns a grey bitmap at the crop's NATIVE size — no resize
     * happens here, because Step C is where the single resample belongs. Passes the input through (as
     * a copy) if OpenCV is unavailable.
     */
    fun prepImage(bitmap: Bitmap): Bitmap {
        if (!openCvReady) return bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)

        val srcMat = Mat()
        val grayMat = Mat()
        val claheMat = Mat()
        val blurMat = Mat()
        val sharpMat = Mat()
        try {
            // --- 1. GREYSCALE ---
            Utils.bitmapToMat(bitmap, srcMat)                        // RGBA
            Imgproc.cvtColor(srcMat, grayMat, Imgproc.COLOR_RGBA2GRAY)

            // --- 2. LOCAL CONTRAST: CLAHE (clip-limited so noise isn't amplified) ---
            val clahe = Imgproc.createCLAHE(CLAHE_CLIP, Size(CLAHE_TILE, CLAHE_TILE))
            clahe.apply(grayMat, claheMat)

            // --- 3. SHARPEN (unsharp mask): out = clahe + AMOUNT·(clahe − blur) ---
            Imgproc.GaussianBlur(claheMat, blurMat, Size(0.0, 0.0), SHARPEN_SIGMA)
            Core.addWeighted(claheMat, 1.0 + SHARPEN_AMOUNT, blurMat, -SHARPEN_AMOUNT, 0.0, sharpMat)

            // Recycled if the copy back out of OpenCV throws, so the enhanced image does not leak on
            // the one path where nobody is left holding a reference to it. It is allocated at the
            // CROP's native size, not at the 640 canvas size — a board photographed close up is
            // megabytes — and this runs once per crop of every board of every clip.
            val out = Bitmap.createBitmap(sharpMat.width(), sharpMat.height(), Bitmap.Config.ARGB_8888)
            try {
                Utils.matToBitmap(sharpMat, out)
            } catch (t: Throwable) {
                out.recycle()
                throw t
            }
            return out
        } finally {
            srcMat.release(); grayMat.release(); claheMat.release(); blurMat.release(); sharpMat.release()
        }
    }

    /**
     * Steps B and C together: the canvas as the model sees it, and as it will be saved.
     *
     * Called twice per crop — once in pass 1 to detect on, once at the end to annotate — because
     * rebuilding is cheaper than holding every canvas in memory until the letters are final.
     */
    private fun buildCanvas(crop: Bitmap, netSize: Int, light: LightDecision): Bitmap {
        // Step B, day or night. The letterbox below is shared: one resample either way.
        val enhanced = if (light.isNight) NightFilter.enhance(crop) else prepImage(crop)
        return try {
            letterboxToCanvas(enhanced, netSize)
        } finally {
            if (enhanced !== crop) enhanced.recycle()
        }
    }

    /**
     * Rebuilds the model's letterbox from the source crop's brightest color channel, then copies
     * only the final price boxes. The rest of the canvas stays exactly as the detector saw it.
     */
    private fun rerenderDayRegions(
        canvas: Bitmap,
        source: Bitmap,
        regions: List<PriceRegion>,
        netSize: Int
    ) {
        if (!openCvReady || regions.isEmpty()) return

        val rgba = Mat()
        val red = Mat()
        val green = Mat()
        val blue = Mat()
        try {
            Utils.bitmapToMat(source, rgba)
            Core.extractChannel(rgba, red, 0)
            Core.extractChannel(rgba, green, 1)
            Core.extractChannel(rgba, blue, 2)
            Core.max(red, green, red)
            Core.max(red, blue, red)

            val brightest = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
            try {
                Utils.matToBitmap(red, brightest)
                val regionCanvas = letterboxToCanvas(brightest, netSize)
                try {
                    val painter = Canvas(canvas)
                    val bounds = Rect(0, 0, canvas.width, canvas.height)
                    for (region in regions) {
                        val box = Rect(region.box)
                        if (box.intersect(bounds)) {
                            painter.drawBitmap(regionCanvas, box, box, null)
                        }
                    }
                } finally {
                    regionCanvas.recycle()
                }
            } finally {
                brightest.recycle()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Day region re-render failed — leaving the board preparation in place", t)
        } finally {
            rgba.release(); red.release(); green.release(); blue.release()
        }
    }

    // ------------------------------------------------------------------------------------------ //
    // Step C — letterbox
    // ------------------------------------------------------------------------------------------ //

    /**
     * Resizes [src] preserving aspect ratio and pads it to a square [size] canvas of neutral grey.
     *
     * One scale factor drives both axes, so the board's proportions survive — stretching it to fill
     * the square was measured to cost about a third of the model's confidence.
     *
     * The kernel is INTER_CUBIC on upscale, deliberately NOT the INTER_LINEAR Stage 2 uses: Stage 2
     * letterboxes whole video frames at roughly 1:1, where the kernel barely matters and matching
     * Ultralytics exactly is worth more, while here every crop is enlarged (often several times over)
     * and the better kernel earns its cost.
     *
     * Detections are reported in this canvas's coordinate space, which is also what gets annotated
     * and saved, so the scale and padding are never needed afterwards and are not returned.
     */
    /** A re-framed canvas and the region boxes moved into its coordinate space. */
    private class Zoom(val canvas: Bitmap, val regions: List<PriceRegion>)

    /**
     * Step C2 — re-frames the canvas on the price panels, so the digits fill more of the 640 the
     * reader is given.
     *
     * The board crop Stage 2 cuts is a whole price post: sign, brand panel, pole, sky at the edges. Fitted
     * to a square canvas the prices end up a band across the middle, and the reader spends its resolution
     * on the parts of the post nobody is asking about. This cuts the crop down to the panels plus
     * [ZOOM_MARGIN_FRAC] of the board's height at each end — margin, top panel, the panels between,
     * bottom panel, margin — and lets Step C fit THAT to the square, which is a straight magnification
     * of the digits at no cost in canvas size.
     *
     * **Vertical only.** Width is left alone deliberately — a price panel's own row is where the fuel
     * name and any currency mark live, and trimming sideways is how you lose them.
     *
     * **Cut from the source crop, not from the canvas.** The window is computed in canvas coordinates
     * (which is where the boxes are measured) and mapped back into the crop's own pixels, so the finished
     * image is still ONE resample from native pixels rather than an enlargement of an enlargement.
     * Step B is re-run over the sub-crop for the same reason.
     *
     * Returns null — keep the full canvas — when there are no regions to frame on, when the window is
     * degenerate, or when the re-frame would not actually magnify anything ([ZOOM_MIN_GAIN]).
     */
    private fun zoomToPrices(
        crop: Bitmap,
        regions: List<PriceRegion>,
        netSize: Int,
        light: LightDecision
    ): Zoom? {
        if (!PRICE_ZOOM_ENABLED || regions.isEmpty()) return null

        // Step C's transform for the WHOLE crop, recomputed rather than remembered: it is four lines of
        // arithmetic, and the alternative is threading a transform object through naming and the audit.
        val scale0 = min(netSize.toFloat() / crop.width, netSize.toFloat() / crop.height)
        val nw0 = (crop.width * scale0).roundToInt().coerceAtLeast(1)
        val nh0 = (crop.height * scale0).roundToInt().coerceAtLeast(1)
        val padX0 = (netSize - nw0) / 2
        val padY0 = (netSize - nh0) / 2

        // The panels' extent in the CROP's own pixels — the boxes are measured on the canvas, so Step
        // C's transform is undone to get there. Working in the crop is what makes "a tenth of the board"
        // mean something: the crop is the board, where the canvas can carry grey pad that is not.
        val top = (regions.minOf { it.box.top } - padY0) / scale0
        val bottom = (regions.maxOf { it.box.bottom } - padY0) / scale0
        val margin = ZOOM_MARGIN_FRAC * crop.height

        // Clamped to the crop: a panel near the board's own edge leaves less than the margin asks for,
        // and there are no pixels beyond it to keep. The re-frame is then simply shorter at that end —
        // padding it back out to a full tenth would be padding with grey, which is what this step exists
        // to get rid of.
        val y0 = (top - margin).toInt().coerceIn(0, crop.height)
        val y1 = ceil(bottom + margin).toInt().coerceIn(0, crop.height)
        val h = y1 - y0
        if (h < MIN_ZOOM_PX) return null
        if (h >= crop.height * ZOOM_MIN_TRIM) return null

        val scale1 = min(netSize.toFloat() / crop.width, netSize.toFloat() / h)
        if (scale1 < scale0 * ZOOM_MIN_GAIN) return null

        val sub = Bitmap.createBitmap(crop, 0, y0, crop.width, h)
        try {
            val canvas = buildCanvas(sub, netSize, light)
            try {
                // The same two letterboxes, run the other way: canvas -> crop with Step C's transform,
                // crop -> canvas with the re-framed one. The moved boxes index the saved PNG.
                val nw1 = (crop.width * scale1).roundToInt().coerceAtLeast(1)
                val nh1 = (h * scale1).roundToInt().coerceAtLeast(1)
                val padX1 = (netSize - nw1) / 2
                val padY1 = (netSize - nh1) / 2
                fun mapX(x: Int): Int =
                    ((x - padX0) / scale0 * scale1 + padX1).roundToInt().coerceIn(0, netSize)
                fun mapY(y: Int): Int =
                    (((y - padY0) / scale0 - y0) * scale1 + padY1).roundToInt().coerceIn(0, netSize)

                val moved = regions.map {
                    PriceRegion(
                        Rect(mapX(it.box.left), mapY(it.box.top), mapX(it.box.right), mapY(it.box.bottom)),
                        it.confidence,
                        it.label
                    )
                }
                if (!light.isNight) rerenderDayRegions(canvas, sub, moved, netSize)
                return Zoom(canvas, moved)
            } catch (t: Throwable) {
                canvas.recycle()
                throw t
            }
        } finally {
            if (sub !== crop) sub.recycle()
        }
    }

    private fun letterboxToCanvas(src: Bitmap, size: Int): Bitmap {
        val scale = min(size.toFloat() / src.width, size.toFloat() / src.height)
        val nw = (src.width * scale).roundToInt().coerceAtLeast(1)
        val nh = (src.height * scale).roundToInt().coerceAtLeast(1)
        val padX = (size - nw) / 2
        val padY = (size - nh) / 2

        if (!openCvReady) return letterboxWithCanvas(src, size, nw, nh, padX, padY)

        val srcMat = Mat()
        val grayMat = Mat()
        val resized = Mat()
        val canvasMat = Mat(size, size, CvType.CV_8UC1, Scalar(NET_PAD))
        val rgba = Mat()
        try {
            Utils.bitmapToMat(src, srcMat)
            Imgproc.cvtColor(srcMat, grayMat, Imgproc.COLOR_RGBA2GRAY)
            val interp = if (scale < 1f) Imgproc.INTER_AREA else Imgproc.INTER_CUBIC
            Imgproc.resize(grayMat, resized, Size(nw.toDouble(), nh.toDouble()), 0.0, 0.0, interp)

            // `roi` in a `finally` — it is a handle onto a view of `canvasMat`, and `copyTo` is a
            // native call that can fail. Released on the happy path only, a failed copy leaked it and
            // then took the fallback below, so the leak happened on exactly the runs that were already
            // going wrong. The pixels belong to `canvasMat`, which the outer `finally` owns.
            val roi = canvasMat.submat(padY, padY + nh, padX, padX + nw)
            try {
                resized.copyTo(roi)
            } finally {
                roi.release()
            }

            // Grey replicated across the channels: the model needs three, but was trained on grey,
            // and the saved artifact is what Stage 4 reads.
            Imgproc.cvtColor(canvasMat, rgba, Imgproc.COLOR_GRAY2RGBA)
            val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            try {
                Utils.matToBitmap(rgba, out)
            } catch (t: Throwable) {
                // Freed before the fallback allocates its own canvas of the same size. Without this a
                // failure here held one 640 canvas and immediately asked for another — which, when the
                // failure was an `OutOfMemoryError`, is the request most likely to fail as well.
                out.recycle()
                throw t
            }
            return out
        } catch (t: Throwable) {
            // Fails over to the bilinear path, which is the whole reason that function exists.
            //
            // `Throwable`, because the two faults this most needs to survive are both `Error`:
            // `UnsatisfiedLinkError` when OpenCV's native library did not load — the case
            // `letterboxWithCanvas` was written for — and `OutOfMemoryError` on a `Mat`.
            //
            // The fallback is worth attempting on an OOM even though it runs BEFORE the `finally`
            // below, with all five `Mat`s still held: it asks for one Java bitmap rather than native
            // buffers, so it can succeed while OpenCV cannot. It is not guaranteed to, and it does not
            // need to be — if it throws too, the throw leaves this function with every `Mat` released
            // by the `finally` and the crop's own loop in `prepare` skips that crop and frees its
            // bitmaps. Nothing leaks either way; the only question is whether this canvas is built.
            Log.w(TAG, "OpenCV letterbox failed — falling back to a bilinear canvas", t)
            return letterboxWithCanvas(src, size, nw, nh, padX, padY)
        } finally {
            srcMat.release(); grayMat.release(); resized.release(); canvasMat.release(); rgba.release()
        }
    }

    /** Fallback letterbox for when OpenCV is down: bilinear, but the geometry is identical. */
    private fun letterboxWithCanvas(src: Bitmap, size: Int, nw: Int, nh: Int, padX: Int, padY: Int): Bitmap {
        // This is the last resort — the path taken when OpenCV is unavailable or has just failed — so
        // it is the one place in Step C that must not leak on the way out. Both bitmaps are freed on a
        // throw: `resized` because `drawBitmap` sits between its allocation and its recycle, and `out`
        // because a caller that receives an exception never gets a reference to it and so can never
        // free it itself.
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        try {
            val pad = NET_PAD.toInt()
            Canvas(out).apply {
                drawColor(Color.rgb(pad, pad, pad))
                val resized = Bitmap.createScaledBitmap(src, nw, nh, true)
                try {
                    drawBitmap(resized, padX.toFloat(), padY.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG))
                } finally {
                    if (resized !== src) resized.recycle()
                }
            }
        } catch (t: Throwable) {
            out.recycle()
            throw t
        }
        return out
    }

    /** The region model's square input edge; 640 for this build, read from the tensor rather than assumed. */
    private fun regionInputSize(): Int {
        val interp = regionInterpreter ?: return 640
        val shape = interp.getInputTensor(0).shape() // NHWC [1,H,W,3] or NCHW [1,3,H,W]
        val nchw = shape.size == 4 && shape[1] == 3
        return if (nchw) shape[2] else shape[1]
    }

    // ------------------------------------------------------------------------------------------ //
    // The region pass
    // ------------------------------------------------------------------------------------------ //

    /**
     * Runs the region model over [canvas] and walks the confidence ladder. Detection ONLY — naming,
     * dimming and drawing all happen later, because the letters are not final until pass 2 and Step F
     * have run.
     *
     * A canvas the model finds nothing on is not dropped: Stage 4 still has to read the board, and a
     * missing region box is a marking failure, not a bad board.
     */
    private fun detectRegions(canvas: Bitmap, slug: String, netSize: Int): RegionScan {
        val interp = regionInterpreter ?: return RegionScan(emptyList())
        val head = try {
            runRegionModel(interp, canvas)
        } catch (t: Throwable) {
            // `Throwable`: this is a TFLite inference over a 640 canvas with a direct `ByteBuffer` for
            // input and output, so `OutOfMemoryError` is how it fails on a device under pressure — and
            // it runs once per crop, which is exactly where "abandon the stage" is the wrong answer.
            // Leaving one canvas unmarked is what the comment above already describes as survivable:
            // "a missing region box is a marking failure, not a bad board".
            Log.e(TAG, "price-region detection failed — leaving canvas unmarked", t)
            null
        } ?: return RegionScan(emptyList())

        // Each rung decodes, THEN drops implausible boxes, then counts — so a rung whose extra boxes
        // are all junk escalates again rather than settling for them. Only the decode repeats; the
        // model ran once, above.
        var found: List<Detected> = emptyList()
        var confUsed = confLadder.first()
        var step = 0
        var dropped: Map<String, Int> = emptyMap()
        for ((i, conf) in confLadder.withIndex()) {
            val decoded = decodeHead(head, conf)
            val (kept, why) = dropImplausible(decoded, netSize)
            found = kept
            confUsed = conf
            step = i
            dropped = why
            if (kept.size >= MIN_REGIONS) break
        }
        if (step > 0) {
            Log.d(TAG, "$slug: escalated to conf %.3f (rung %d) for ${found.size} region(s)"
                .format(confUsed, step))
        }

        // GUARDRAIL — region count. Checked BEFORE naming, deliberately: a discarded canvas must not
        // mint tags, or its phantoms would live on in the board's remembered order and every later
        // canvas would be fitted against them.
        if (found.size > MAX_REGIONS) {
            Log.d(
                TAG,
                "$slug: DISCARDED — %d regions at conf %.3f, more than the %d a board can carry"
                    .format(found.size, confUsed, MAX_REGIONS)
            )
            return RegionScan(emptyList(), confUsed, step, dropped, discard = true)
        }
        return RegionScan(found, confUsed, step, dropped)
    }

    /**
     * Throws out boxes that cannot be a price panel. Returns the survivors and the reasons the rest
     * went, by count.
     *
     * The canvas may end up empty — if it does, the confidence ladder climbs another rung and decodes
     * again, which is the point: a rung whose extra boxes are all junk should escalate rather than
     * settle for them.
     */
    private fun dropImplausible(dets: List<Detected>, canvasEdge: Int): Pair<List<Detected>, Map<String, Int>> {
        val kept = mutableListOf<Detected>()
        val why = LinkedHashMap<String, Int>()
        for (d in dets) {
            val reason = implausible(d.box, canvasEdge)
            if (reason == null) kept.add(d) else why[reason] = (why[reason] ?: 0) + 1
        }
        return kept to why
    }

    /**
     * Turns the `[1, 5, 8400]` head output into NMS-filtered boxes in the coordinate space of the
     * [canvas] the model was fed — the same image that gets annotated, so there is no letterbox to
     * undo here.
     *
     * Written to adapt to the model's actual tensor shapes at runtime (input NHWC or NCHW; output
     * channels-first or channels-last; coords normalised 0-1 or already in pixels), so an export
     * change can't silently produce garbage boxes.
     */
    private fun runRegionModel(interp: Interpreter, canvas: Bitmap): RegionHead {
        // --- input ---
        val inT = interp.getInputTensor(0)
        val inShape = inT.shape()
        val nchw = inShape.size == 4 && inShape[1] == 3
        val netSize = if (nchw) inShape[2] else inShape[1]
        val inputBuffer = bitmapToBuffer(canvas, inT.dataType(), nchw)

        // --- output (read into float [d1][d2] regardless of dtype) ---
        val outT = interp.getOutputTensor(0)
        val outShape = outT.shape() // [1, d1, d2]
        val d1 = outShape[1]
        val d2 = outShape[2]
        val dt = outT.dataType()

        val out: Array<FloatArray> = if (dt == DataType.FLOAT32) {
            val raw = Array(1) { Array(d1) { FloatArray(d2) } }
            interp.run(inputBuffer, raw)
            raw[0]
        } else {
            val raw = Array(1) { Array(d1) { ByteArray(d2) } }
            interp.run(inputBuffer, raw)
            val unsigned = dt == DataType.UINT8
            val scale = outT.quantizationParams().scale
            val zeroPoint = outT.quantizationParams().zeroPoint
            Array(d1) { i ->
                FloatArray(d2) { j ->
                    val v = if (unsigned) (raw[0][i][j].toInt() and 0xFF) else raw[0][i][j].toInt()
                    (v - zeroPoint) * scale
                }
            }
        }

        // Channels are the small dimension; anchors the large one. Index accordingly.
        val channelsFirst = d1 <= d2
        return RegionHead(
            out = out,
            channelsFirst = channelsFirst,
            channels = if (channelsFirst) d1 else d2,
            anchors = if (channelsFirst) d2 else d1,
            netSize = netSize
        )
    }

    /**
     * Decodes one already-computed head output at [conf] into NMS-filtered boxes.
     *
     * Cheap enough to call repeatedly: the model is not involved, so the confidence ladder in
     * [detectRegions] costs a handful of array passes per rung rather than a second inference.
     *
     * Corners stay FLOAT. Truncating them here used to inject up to a pixel of downward bias into
     * every coordinate, and it landed exactly where Step E is most delicate: the reading-order sort of
     * two panels at nearly the same height, and a fit decided by a fraction of a pixel.
     */
    private fun decodeHead(head: RegionHead, conf: Float): List<Detected> {
        val netSize = head.netSize

        // Score row(s): a single class here, so this is just row 4 — taking the max over the class
        // rows costs nothing and keeps a two-class export from being silently misread.
        val kept = ArrayList<Int>()
        val scores = FloatArray(head.anchors)
        for (a in 0 until head.anchors) {
            var best = 0f
            for (k in 4 until head.channels) best = max(best, head.ch(k, a))
            best = best.coerceIn(0f, 1f)
            scores[a] = best
            if (best >= conf) kept.add(a)
        }
        if (kept.isEmpty()) return emptyList()

        // Normalised (0-1) -> canvas pixels. Guard against a non-normalised export by deciding ONCE
        // over all kept boxes, not per value — a mixed judgement would scale some rows and not others.
        var maxCoord = 0f
        for (a in kept) for (r in 0..3) maxCoord = max(maxCoord, abs(head.ch(r, a)))
        val coordScale = if (maxCoord <= 1.5f) netSize.toFloat() else 1f

        val candidates = ArrayList<Detected>(kept.size)
        for (a in kept) {
            val cx = head.ch(0, a) * coordScale
            val cy = head.ch(1, a) * coordScale
            val w = head.ch(2, a) * coordScale
            val h = head.ch(3, a) * coordScale

            // Centre-and-size -> corners, clipped to the canvas.
            val left = (cx - w / 2).coerceIn(0f, netSize - 1f)
            val top = (cy - h / 2).coerceIn(0f, netSize - 1f)
            val right = (cx + w / 2).coerceIn(0f, netSize - 1f)
            val bottom = (cy + h / 2).coerceIn(0f, netSize - 1f)
            if (right - left > 1f && bottom - top > 1f) {
                candidates.add(Detected(BoxF(left, top, right, bottom), scores[a]))
            }
        }
        if (candidates.isEmpty()) return emptyList()
        return nms(candidates, REGION_IOU_THRESHOLD).take(REGION_MAX_DET)
    }

    /** Greedy non-max suppression: keep the highest-confidence box, drop others overlapping it > iou. */
    private fun nms(candidates: List<Detected>, iou: Float): List<Detected> {
        val sorted = candidates.sortedByDescending { it.confidence }.toMutableList()
        val kept = ArrayList<Detected>()
        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            kept.add(best)
            sorted.removeAll { best.box.iou(it.box) > iou }
        }
        return kept
    }

    // ------------------------------------------------------------------------------------------ //
    // Step D — ghost dimming, and the annotation
    // ------------------------------------------------------------------------------------------ //

    /**
     * Step D. Crushes the unlit segments inside each detected box to black.
     *
     * Works per box and only inside that box — pixels outside every box are left untouched. The
     * lit/unlit split is computed per ROI with Otsu rather than once for the whole canvas, so a
     * uniformly dim board is rescaled on its own terms instead of being wiped out. Everything at or
     * below the black-point clips to pure black; everything above is re-stretched with a gamma that
     * darkens the faint halo between the lit strokes.
     *
     * Mutates [bmp] in place. Returns the number of boxes actually processed.
     */
    private fun dimUnlitSegments(bmp: Bitmap, regions: List<PriceRegion>): Int {
        val full = Mat()
        val gray = Mat()
        var done = 0
        try {
            Utils.bitmapToMat(bmp, full)                              // RGBA
            Imgproc.cvtColor(full, gray, Imgproc.COLOR_RGBA2GRAY)     // canvas content is grayscale
            for (r in regions) {
                val x = r.box.left.coerceIn(0, gray.cols() - 1)
                val y = r.box.top.coerceIn(0, gray.rows() - 1)
                val w = r.box.width().coerceIn(0, gray.cols() - x)
                val h = r.box.height().coerceIn(0, gray.rows() - y)
                if (w <= 0 || h <= 0) continue

                // Released in a `finally`, all three, and that is not tidiness.
                //
                // A `Mat` is a native allocation behind a Java handle, and these used to be released on
                // the happy path only — so a throw anywhere in the four calls below leaked `roi`, `tmp`
                // and `lut`. The catch outside then reported the dimming as failed and the run carried
                // on, which is right, and the leak was invisible: this loop runs per box, per crop, per
                // board, per clip, so a fault that repeats — a box geometry OpenCV refuses, a LUT it
                // will not build — leaks thousands of them across a backlog. The Java wrapper's
                // finalizer reclaims a `Mat` eventually, which is precisely the problem: "eventually"
                // is not a schedule, and the memory it is holding is native, where the pressure does
                // not read as heap pressure and the process is killed rather than given an OOM.
                //
                // `roi` is a VIEW into `gray` and is released as a handle; the pixels belong to `gray`,
                // which the outer `finally` owns, so releasing it here cannot lose the work written
                // through it.
                var roi: Mat? = null
                val tmp = Mat()
                var lut: Mat? = null
                try {
                    roi = gray.submat(y, y + h, x, x + w)            // VIEW into gray (writes through)
                    // Otsu on this box alone -> the brightness that separates lit from unlit. We don't
                    // need the binary mask it also produces, only the split value it returns.
                    val split =
                        Imgproc.threshold(roi, tmp, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
                    lut = blackPointGammaLut(split * GHOST_BLACK_POINT, GHOST_GAMMA)
                    Core.LUT(roi, lut, roi)                          // in-place
                    done++
                } finally {
                    tmp.release()
                    lut?.release()
                    roi?.release()
                }
            }
            val outRgba = Mat()
            try {
                Imgproc.cvtColor(gray, outRgba, Imgproc.COLOR_GRAY2RGBA)
                Utils.matToBitmap(outRgba, bmp)                      // write the dimmed result back
            } finally {
                outRgba.release()
            }
        } catch (t: Throwable) {
            // Fails OPEN: the canvas is left as Step C made it and the board is still read. Dimming the
            // unlit ghosts makes a dim board easier to read; not dimming it is a worse image, not a
            // wrong one.
            //
            // `Throwable` because everything in here is native — OpenCV raises `UnsatisfiedLinkError`
            // when its library did not load and a `Mat` allocation that will not fit is an
            // `OutOfMemoryError`, both `Error` — and the whole point of this catch is that a failed
            // enhancement costs an enhancement.
            Log.w(TAG, "ghost dimming failed — leaving canvas unchanged", t)
            return 0
        } finally {
            full.release(); gray.release()
        }
        return done
    }

    /**
     * Builds a 1x256 8-bit LUT: intensities at or below [black] map to 0 (pure black), and those above
     * are stretched across 0..255 with exponent [gamma] (1.0 = linear, <1 lifts the low end of the
     * survivors). Used to crush unlit 7-segment ghosts while keeping the lit digits.
     */
    private fun blackPointGammaLut(black: Double, gamma: Double): Mat {
        val span = max(1.0, 255.0 - black)
        val data = ByteArray(256) { i ->
            val t = ((i - black) / span).coerceIn(0.0, 1.0)
            (255.0 * t.pow(gamma)).roundToInt().coerceIn(0, 255).toByte()
        }
        return Mat(1, 256, CvType.CV_8UC1).apply { put(0, 0, data) }
    }

    /**
     * Outlines each region and stamps its tag on a plate in the box's inner bottom-left corner — or,
     * with [outerTags], just OUTSIDE that corner.
     *
     * The plate carries the TAG alone. Confidence is a diagnostic for the log, not something the
     * reader should be shown — it is one more number next to the digits it is supposed to read.
     *
     * The text is floored at [LABEL_MIN_TEXT_PX] rather than left at the reference pipeline's font
     * scale, which works out to about 16px on a 640 canvas and is legible to a person but not reliably
     * to a VLM being asked which letter it is looking at.
     *
     * ### The two tag positions, and why both exist
     * **Inside** (Stage 4's canvas): the tag has to be unmistakably attached to the box whose digits are
     * being read, and inside the outline nothing can be confused about which box it belongs to. It costs
     * a corner of the panel, which is affordable there because the digits are what is being read and
     * they are not in the corner.
     *
     * **Outside** ([outerTags], Step G's canvas): that same corner is where a board often prints the
     * fuel NAME, and Step G exists to read exactly that — so a plate covering it would hide the answer.
     * The tag is placed by a cascade, in order of how little it can obscure:
     *   1. to the LEFT of the box, bottom-aligned — a letterboxed price post leaves grey margin there,
     *      so on most canvases this lands on padding and covers nothing at all;
     *   2. failing that, BELOW the box — used when the board runs to the canvas's left edge. Panels are
     *      stacked, so this can graze the next panel down, which is why it is second and not first;
     *   3. failing that, back inside the corner — a box flush against the canvas's bottom-left has
     *      nowhere outside to put it, and a tag inside beats a tag half off the canvas.
     */
    private fun drawRegions(bmp: Bitmap, regions: List<PriceRegion>, outerTags: Boolean = false) {
        val w = bmp.width
        val h = bmp.height
        val thickness = max(1, (min(h, w) / 600f).roundToInt())
        val fontScale = max(0.45f, min(h, w) / 1200f)

        val canvas = Canvas(bmp)
        val stroke = Paint().apply {
            color = BOX_COLOR
            style = Paint.Style.STROKE
            strokeWidth = max(2, thickness).toFloat()
            isAntiAlias = true
        }
        val fill = Paint().apply {
            color = BOX_COLOR
            style = Paint.Style.FILL
        }
        val text = Paint().apply {
            color = Color.WHITE // on the red plate; higher contrast than black at this size
            textSize = max(fontScale * HERSHEY_TO_TEXT_SIZE, LABEL_MIN_TEXT_PX)
            isFakeBoldText = true
            isAntiAlias = true
        }

        val metrics = text.fontMetrics
        val textHeight = -metrics.ascent          // cap height above the baseline
        val baselineDrop = metrics.descent        // what hangs below it
        val plateHeight = textHeight + baselineDrop + 4f

        for (r in regions) {
            canvas.drawRect(r.box, stroke)
            if (r.label.isEmpty()) continue

            val plateWidth = text.measureText(r.label) + 8f
            val inset = stroke.strokeWidth
            // Inner bottom-left corner, sat just inside the outline so the stroke stays visible.
            var left = (r.box.left + inset)
                .coerceAtLeast(0f)
                .coerceAtMost(max(0f, w - plateWidth))
            var bottom = (r.box.bottom - inset)
                .coerceAtLeast(plateHeight)
                .coerceAtMost(h.toFloat())

            if (outerTags) {
                val gap = inset + 2f
                val toLeft = r.box.left - gap - plateWidth
                val below = r.box.bottom + gap + plateHeight
                when {
                    // 1. Left of the box, bottom edges aligned — the margin, on most canvases.
                    toLeft >= 0f -> {
                        left = toLeft
                        bottom = r.box.bottom.toFloat().coerceIn(plateHeight, h.toFloat())
                    }
                    // 2. Below the box, left edges aligned.
                    below <= h -> {
                        left = r.box.left.toFloat().coerceIn(0f, max(0f, w - plateWidth))
                        bottom = below
                    }
                    // 3. Nowhere outside to put it — the inner corner already computed stands.
                    else -> Unit
                }
            }

            canvas.drawRect(left, bottom - plateHeight, left + plateWidth, bottom, fill)
            // Baseline sits a descender's height above the plate's bottom edge.
            canvas.drawText(r.label, left + 4f, bottom - baselineDrop - 2f, text)
        }
    }

    // ------------------------------------------------------------------------------------------ //
    // Plumbing
    // ------------------------------------------------------------------------------------------ //

    /**
     * Memory-maps the downloaded region model — the same pattern [BoardDetector] uses for its own
     * `.tflite`. Throws when the file is absent, which the caller turns into "region marking disabled".
     */
    private fun loadRegionModel(): MappedByteBuffer {
        val file = ModelRepository.priceRegionFile(context)
        if (!file.isFile || file.length() == 0L) {
            error("$REGION_MODEL_NAME not downloaded (looked in ${file.path})")
        }
        FileInputStream(file).use { fis ->
            return fis.channel.map(FileChannel.MapMode.READ_ONLY, 0, fis.channel.size())
        }
    }

    /**
     * Packs [bitmap] into the interpreter's input buffer, honouring dtype and NHWC/NCHW layout.
     *
     * FLOAT32 is scaled to 0-1, which is not an assumption: feeding 0-255 collapses this model's head,
     * emitting thousands of anchors all scoring exactly 1.000.
     */
    private fun bitmapToBuffer(bitmap: Bitmap, inputType: DataType, nchw: Boolean): ByteBuffer {
        val w = bitmap.width
        val h = bitmap.height
        val bytesPerChannel = if (inputType == DataType.FLOAT32) 4 else 1
        val buffer = ByteBuffer.allocateDirect(w * h * 3 * bytesPerChannel).order(ByteOrder.nativeOrder())
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        fun put(value: Int) {
            when (inputType) {
                DataType.FLOAT32 -> buffer.putFloat(value / 255.0f)
                DataType.UINT8 -> buffer.put(value.toByte())
                else -> buffer.put((value - 128).toByte())          // INT8 signed
            }
        }

        if (nchw) {                                                 // plane order: all R, then G, then B
            for (shift in intArrayOf(16, 8, 0)) {
                for (p in pixels) put((p shr shift) and 0xFF)
            }
        } else {                                                    // interleaved RGB per pixel
            for (p in pixels) {
                put((p shr 16) and 0xFF)
                put((p shr 8) and 0xFF)
                put(p and 0xFF)
            }
        }
        buffer.rewind()
        return buffer
    }

    /** Derives the video id from a crop path: crops/<videoId>/crop_….png → "<videoId>". */
    private fun videoIdOf(cropPath: String): String = File(cropPath).parentFile?.name ?: "unknown"

    /** Root folder holding one UUID sub-folder of prepped images per video (mirrors frames/crops). */
    /** Files storage, not cache — see [RunScratch]; the OS must not reclaim a run mid-flight. */
    private fun preppedRootDir(): File = File(context.filesDir, "prepped").apply { mkdirs() }
}
