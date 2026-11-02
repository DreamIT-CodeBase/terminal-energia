package com.terminalscout.app.prod.pipeline

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/** One row of a reviewed board. [price] is `X.XXX` or empty. */
data class ReviewedRow(
    val rowNum: Int,
    val box: String,
    val price: String,
    /**
     * The fuel name this row already carried, carried straight through.
     *
     * The review judges DIGITS against the board images and is never asked about names, so the name it
     * was handed is the name it hands back. It used to be written out blank here, which silently
     * discarded what Stage 3's layout read every time the review fired — and the review normally fires.
     */
    val fuelType: String = ""
)

/**
 * What the review produced.
 *
 * @param json     the corrected readings JSON, in Stage 5's own schema — this becomes the stage's output
 * @param rows     [json] parsed, for the dashboard
 * @param rawText  exactly what the model replied, before any parsing — streamed to the trace so the
 *                 review is inspectable rather than a black box
 * @param changes  human-readable diff against the consolidated input, one line per changed row
 */
data class ConsolidationReview(
    val json: String,
    val rows: List<ReviewedRow>,
    val rawText: String,
    val changes: List<String>
)

/**
 * The last word on Stage 5: a cloud model checks the consolidated answer **against the board images**
 * and returns a corrected version of it.
 *
 * The deterministic [ConsensusConsolidator] can only work with what Stage 4 read. When the reader
 * misses a
 * panel entirely, or truncates `1914` to `19`, no amount of cross-frame voting recovers it — the digits
 * were never in the pool. This step puts the answer and the pictures in front of a model that can see
 * both, and asks it to fill the gaps and fix what disagrees with the images.
 *
 * ### Why here and not in Stage 4
 * The first attempt did this per box during OCR, overwriting each frame's reading with one cloud value.
 * That erased the very disagreement Stage 5 exists to resolve: a correct `1914` read from one canvas
 * was overwritten in *all* of them by a wrong `1834`, and the cross-frame vote had nothing left to
 * weigh. Reviewing the finished answer keeps the voting intact and confines the model's influence to a
 * single, visible, all-at-once decision.
 *
 * ### It cannot make things structurally worse
 * The reply is parsed and re-validated before use: rows are matched to the consolidated ones by
 * `row_num`, any price that isn't `X.XXX` is blanked, and unknown rows are ignored. If the reply is
 * missing, unparseable, or contains no rows, the consolidated answer is kept unchanged. The model can
 * change *values*; it cannot change the board's shape or introduce a malformed price.
 */
class ConsolidationReviewer(private val cloud: CloudOcrReader = CloudOcrReader()) {

    /** True when a cloud key is configured; otherwise [review] always returns null. */
    val isEnabled: Boolean get() = cloud.isEnabled

    /**
     * Reviews [consolidatedJson] against [canvasPaths] and returns the corrected board, or null when
     * the review did not happen or produced nothing usable — in which case the caller keeps its own
     * answer.
     */
    suspend fun review(consolidatedJson: String, canvasPaths: List<String>): ConsolidationReview? {
        if (!isEnabled) return null
        if (canvasPaths.isEmpty()) {
            Log.d(TAG, "no board images to review against — skipping")
            return null
        }

        val before = parseRows(consolidatedJson)
        if (before.isEmpty()) {
            Log.d(TAG, "consolidated answer has no rows — nothing to review")
            return null
        }

        val reply = cloud.complete(prompt(consolidatedJson), canvasPaths) ?: return null
        val after = parseRows(reply)
        if (after.isEmpty()) {
            Log.w(TAG, "review returned no usable rows — keeping the consolidated answer")
            return null
        }

        // Keep the consolidated board's SHAPE: every row is matched by row_num, and a row the review
        // did not mention keeps its consolidated value. The model corrects prices, it does not get to
        // add or remove rows.
        val byRow = after.associateBy { it.rowNum }
        val changes = mutableListOf<String>()
        val merged = before.map { row ->
            val reviewed = byRow[row.rowNum] ?: return@map row
            if (reviewed.price == row.price) return@map row
            changes += "row ${row.rowNum} (${row.box}): " +
                "${row.price.ifEmpty { "blank" }} -> ${reviewed.price.ifEmpty { "blank" }}"
            row.copy(price = reviewed.price)
        }

        Log.d(TAG, "review changed ${changes.size}/${before.size} row(s): ${changes.joinToString("; ")}")
        return ConsolidationReview(buildJson(merged), merged, reply.trim(), changes)
    }

    /** Rows from a `{"readings":[…]}` document; prices re-validated to `X.XXX` or blank. */
    private fun parseRows(json: String): List<ReviewedRow> = try {
        // The model may fence its JSON or wrap it in prose; take the outermost object.
        val start = json.indexOf('{')
        val end = json.lastIndexOf('}')
        if (start < 0 || end <= start) emptyList()
        else {
            val arr = JSONObject(json.substring(start, end + 1)).optJSONArray("readings")
            (0 until (arr?.length() ?: 0)).mapNotNull { i ->
                val o = arr?.optJSONObject(i) ?: return@mapNotNull null
                ReviewedRow(
                    rowNum = o.optInt("row_num", -1),
                    box = o.optString("box"),
                    fuelType = o.optString("fuel_type").trim(),
                    // The X.XXX guard again — the model's output is no more trusted than the
                    // pipeline's own, and a plausible-looking "1.91" must not reach the payload.
                    //
                    // And the lead-digit rule with it, for the same reason and in the same place.
                    // A reviewed row REPLACES Stage 5's, so without this the correction applied in
                    // [ConsensusConsolidator.correctLeadDigit] would be silently undone on exactly
                    // the rows the review touched — and a review that reads a ghosted 1 as a 7 makes
                    // the identical mistake the rule exists for, off the identical pixels. The
                    // substitution runs AFTER the shape check, so "78.5" is still blanked rather than
                    // rewritten into a price.
                    price = o.optString("price").trim()
                        .takeIf { PRICE_SHAPE.matches(it) }
                        ?.let { ConsensusConsolidator.correctLeadDigit(it) }
                        .orEmpty()
                )
            }.filter { it.rowNum >= 1 }
        }
    } catch (e: Exception) {
        Log.w(TAG, "could not parse readings JSON: ${json.take(200)}", e)
        emptyList()
    }

    /**
     * The reviewed readings, in Stage 5's own shape.
     *
     * `fuel_type` is whatever came IN for that row — the token Stage 3's layout read off the board. This
     * step revises digits, not names, so blanking the field here would throw away a fact from a step
     * that never had a view on it.
     */
    private fun buildJson(rows: List<ReviewedRow>): String {
        val arr = JSONArray()
        rows.forEach { r ->
            arr.put(JSONObject().apply {
                put("row_num", r.rowNum)
                put("box", r.box)
                put("fuel_type", r.fuelType)
                put("price", r.price)
            })
        }
        return JSONObject().put("readings", arr).toString()
    }

    /**
     * The review prompt. It carries the board's own reading rules — the same seven-segment shapes and
     * confusion table the on-device reader gets — so both models are judging the digits by identical
     * criteria rather than by whatever each happens to assume.
     */
    private fun prompt(consolidatedJson: String): String =
        "You are checking an automated fuel-price-board reading against the photographs it came from.\n\n" +

            "The images are frames of the SAME board, taken moments apart. Some price panels are " +
            "outlined by a RED RECTANGLE tagged with a CAPITAL LETTER on a red plate. A given letter " +
            "means the SAME physical panel in every image, so a panel that is unreadable in one frame " +
            "may be clear in another — use every image together.\n\n" +

            "This is the reading an automated pipeline produced. `box` is the letter of the panel the " +
            "row came from, and `price` is empty where the pipeline could not settle on a value:\n" +
            consolidatedJson + "\n\n" +

            "YOUR TASK: compare that reading against the images and return a CORRECTED version of it.\n" +
            "- FILL every empty price you can read from the images.\n" +
            "- CORRECT any price that disagrees with what the panel actually shows.\n" +
            "- KEEP a price that is already right, exactly as it is.\n\n" +

            "--- HARD RULES ---\n" +
            "- Keep EXACTLY the same rows, with the same \"row_num\" and \"box\" values. Do not add, " +
            "remove, merge or reorder rows.\n" +
            "- Every price must be written as ONE digit, a decimal point, then THREE digits — for " +
            "example \"1.834\". Never fewer or more digits, no currency, no units, no spaces.\n" +
            "- If a panel genuinely cannot be read in any image, leave its price as \"\". Never guess " +
            "a digit you cannot see; an empty price is better than a wrong one.\n" +
            "- Read ONLY the digits inside the red rectangles. Ignore fuel names, headers, banners and " +
            "anything outside them.\n\n" +

            "Output ONLY the corrected JSON object, in the same schema, with no markdown, backticks or " +
            "commentary:\n" +
            "{\"readings\":[{\"row_num\":1,\"box\":\"A\",\"fuel_type\":\"\",\"price\":\"1.834\"}]}\n\n" +

            OcrPrompts.SEVEN_SEGMENT_RULES

    companion object {
        private const val TAG = "ConsolidationReviewer"

        /** One integer digit, a point, three decimals — Stage 5's committed shape. */
        private val PRICE_SHAPE = Regex("""^\d\.\d{3}$""")
    }
}
