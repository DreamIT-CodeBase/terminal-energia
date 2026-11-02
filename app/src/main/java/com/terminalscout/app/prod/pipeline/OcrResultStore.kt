package com.terminalscout.app.prod.pipeline

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists **Stage 4's OCR output** (the box→digits mapping the reader produced, before any Stage-5
 * reconstruction) to a single JSON file: `ocr_response.json`.
 *
 * Why: the read is expensive — several requests to a reasoning model over a dozen images, minutes per
 * run, and billed. Capturing the per-board reads lets Stage 5 be iterated on WITHOUT re-reading the
 * board: the saved JSON is a replay fixture.
 *
 * ### Where it goes vs. where you want it
 * `res/raw/` (like `assets/`) is packaged into the APK at build time and is **read-only at runtime**,
 * so the app cannot write there on-device. This store therefore writes to the app's external files
 * dir (falling back to internal `filesDir`), and logs an `adb pull` command. To turn a real run into
 * the checked-in fixture at `app/src/main/res/raw/[FILE_NAME]`, pull the file and copy it into the
 * source tree — it is then bundled on the next build and readable via `R.raw.ocr_response`.
 *
 * ### Schema — key/value only, keys monotonically increasing
 * Both collections (boards, and the regions on a board) are JSON **objects keyed `"1"`, `"2"`, `"3"`…
 * rather than arrays**, so every value in the file is reachable by a stable key. Board keys ascend in
 * `detection_id` order (i.e. in time); region keys ascend in Stage 3's top-to-bottom order.
 *
 * ```
 * {
 *   "source": "PriceBoardPipeline · Stage 4 OCR",
 *   "video_id": "<uuid>",
 *   "board_count": <int>,
 *   "board_order": ["A", "B", "C"],
 *   "boards": {
 *     "1": {
 *       "detection_id": <int>,
 *       "frame_index": <int>,
 *       "slug": "f00012_d00",
 *       "timestamp_us": <long>,
 *       "prepped_image": "prepped_f00012_d00.png",
 *       "region_count": <int>,
 *       "regions": {
 *         "1": {
 *           "box": "A",
 *           "bbox": { "x1": <int>, "y1": <int>, "x2": <int>, "y2": <int> },
 *           "digits": "247",
 *           "region_confidence": 0.87
 *         }
 *       }
 *     }
 *   }
 * }
 * ```
 *
 * `board_order` is the PHYSICAL board's panels in top-to-bottom order, and it is the only statement of
 * row identity in the file. A region's key is its slot in ONE canvas — a canvas that missed the top panel
 * puts the second panel in slot 1 — so a consumer that wants "the board's second row" resolves the
 * region's `box` letter through this list rather than counting keys. Absent when Stage 3 could not
 * measure it, and absent from a fixture that predates it: [ConsensusConsolidator] then falls back to
 * slot order, which is what it always did.
 *
 * One `boards` entry per DETECTION (a frame holding two boards yields two), and one `regions` entry
 * per price region on it — **including regions the reader could not read**, which carry `"digits": ""`, so
 * a read failure is visible in the fixture rather than absent. `bbox` is in the prepped 640 canvas's
 * coordinate space, and `digits` holds only `0-9` (a panel showing `2.47` is `"247"`). Failures are
 * logged and swallowed so the pipeline keeps running.
 */
class OcrResultStore(private val context: Context) {

    private val boards = mutableListOf<OcrResult>()

    /**
     * The board order recorded so far, or null once two DIFFERENT orders have been seen.
     *
     * One video can hold two physical boards, which this store accumulates into one fixture file — and
     * they have different row orders. Rather than stamp one board's order over both, a conflict drops it:
     * the file then replays on slot order, exactly as it did before this field existed. The per-board
     * Stage-5 input never has this problem, because [PriceBoardPipeline] builds one document per board.
     */
    private var boardOrder: List<String>? = null
    private var boardOrderConflict = false

    /** Where the JSON is written (external files dir if available, else internal files dir). */
    val outputFile: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, FILE_NAME)

    /** Clears accumulated boards from the previous run. Call once at the start of a run. */
    fun clear() {
        boards.clear()
        boardOrder = null
        boardOrderConflict = false
    }

    /**
     * Records one board image's Stage-4 result and rewrites the JSON file, so the output is up to
     * date even if the run is interrupted.
     */
    fun record(result: OcrResult, boardOrder: List<String> = emptyList()) {
        boards.add(result)
        if (boardOrder.isNotEmpty()) {
            val known = this.boardOrder
            if (known == null) {
                this.boardOrder = boardOrder
            } else if (known != boardOrder && !boardOrderConflict) {
                boardOrderConflict = true
                Log.d(TAG, "two boards with different row orders in one video — board_order omitted")
            }
        }
        write()
    }

    private fun write() {
        try {
            outputFile.writeText(
                buildRoot(boards, if (boardOrderConflict) emptyList() else boardOrder.orEmpty()).toString(2)
            )
            Log.d(TAG, "Wrote ${boards.size} board(s) of Stage-4 OCR -> ${outputFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write Stage-4 OCR JSON", e)
        }
    }

    /** Logs the final path + an `adb pull` hint for refreshing the bundled `res/raw` fixture. */
    fun logPullHint() {
        Log.i(
            TAG,
            "Stage-4 OCR output saved. To refresh the bundled fixture:\n" +
                "  adb pull \"${outputFile.absolutePath}\" app/src/main/res/raw/$FILE_NAME"
        )
    }

    companion object {
        private const val TAG = "OcrResultStore"
        private const val SOURCE = "PriceBoardPipeline · Stage 4 OCR"

        /** Shared name for both the runtime output file and the bundled `res/raw` fixture. */
        const val FILE_NAME = "ocr_response.json"

        /**
         * Builds the `ocr_response.json` document for [results] — the single definition of that shape.
         *
         * Both the on-device file and Stage 5's [ConsensusConsolidator] input come through here, so a
         * live run and the pulled `res/raw/ocr_response.json` fixture reduce through byte-identical
         * JSON: replaying the checked-in file cannot diverge from what the pipeline actually consumed.
         *
         * Boards are keyed `"1"…"N"` in `detectionId` (i.e. time) order; regions keyed `"1"…"N"` in the
         * order that canvas's regions were read in.
         *
         * @param boardOrder the physical board's labels top to bottom, written out as `board_order`. Omitted
         *   when empty, so a caller that cannot supply it produces the document this built before — and
         *   Stage 5 falls back to slot order for it.
         */
        fun buildRoot(results: List<OcrResult>, boardOrder: List<String> = emptyList()): JSONObject {
            val ordered = results.sortedBy { it.detectionId }
            val boardsObj = JSONObject()
            ordered.forEachIndexed { bIdx, b ->
                val regionsObj = JSONObject()
                b.regions.forEachIndexed { rIdx, r ->
                    regionsObj.put(
                        (rIdx + 1).toString(),
                        JSONObject().apply {
                            put("box", r.label)
                            put("bbox", JSONObject().apply {
                                put("x1", r.box.left)
                                put("y1", r.box.top)
                                put("x2", r.box.right)
                                put("y2", r.box.bottom)
                            })
                            put("digits", r.digits)
                            // Round the float: a raw Float→Double widening writes 0.8700000047683716.
                            put("region_confidence", Math.round(r.regionConfidence * 1000f) / 1000.0)
                        }
                    )
                }
                boardsObj.put(
                    (bIdx + 1).toString(),
                    JSONObject().apply {
                        put("detection_id", b.detectionId)
                        put("frame_index", b.frameIndex)
                        put("slug", b.slug)
                        put("timestamp_us", b.timestampUs)
                        put("prepped_image", File(b.preppedPath).name)
                        put("region_count", b.regions.size)
                        // Which independent request read this canvas. Stage 5 groups by it to tell
                        // corroboration from repetition — see [OcrResult.batch].
                        put("batch", b.batch)
                        put("regions", regionsObj)
                    }
                )
            }
            return JSONObject().apply {
                put("source", SOURCE)
                put("video_id", ordered.firstOrNull()?.videoId ?: "unknown")
                put("board_count", ordered.size)
                // Written before `boards` so the file states what a row IS before listing any.
                if (boardOrder.isNotEmpty()) {
                    put("board_order", JSONArray().apply { boardOrder.forEach { put(it) } })
                }
                put("boards", boardsObj)
            }
        }
    }
}
