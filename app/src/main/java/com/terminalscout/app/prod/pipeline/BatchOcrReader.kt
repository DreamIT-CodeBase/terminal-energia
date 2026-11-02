package com.terminalscout.app.prod.pipeline

import android.util.Log
import com.terminalscout.app.prod.config.DeviceConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * Why a batched read produced the readings it did — the distinction the caller has to act on.
 *
 * A read that came back with no digits and a read that never happened are identical in the readings
 * alone, and treating them alike is how a misconfigured key turns a forecourt's footage into "this board
 * had no prices": the run reports itself complete, an empty payload is uploaded, and the clip is
 * deleted. Only [READ] may be counted as a reading of the board.
 */
enum class ReadOutcome {
    /** The reader answered, and the readings ARE its answer for those canvases. */
    READ,

    /**
     * The read did not land, in a way another attempt could fix — no route, a timeout, the service
     * faltering, or **a reply that arrived and could not be used**: a body that was not JSON, one
     * carrying no `output_text`, or one that attributed no image. Nothing about the request is wrong,
     * so the same canvases read later will very likely succeed.
     *
     * The reply-shaped causes belong here rather than in [REFUSED] because of what produces them: a
     * response truncated by a connection that dropped mid-body, a proxy or captive portal answering
     * with its own page, or a model that spent its whole allowance on reasoning. Every one is a fault
     * of that attempt, and the reader retries all of them before reporting this.
     */
    UNREACHABLE,

    /**
     * The request itself was refused, or could not be built: no key configured at build time, a dead
     * key, an exhausted quota, a wrong model or deployment name, or canvases missing from disk.
     *
     * It differs from [UNREACHABLE] only in what it tells a human — a retry fails identically until
     * somebody changes something. So the bar for using it is high: it is the outcome `OcrStage` reports
     * as "a configuration fault, not a connection one", and a transient fault dressed as this sends
     * whoever reads it to check a key that was never the problem. It is emphatically NOT a read: the
     * board was never looked at.
     */
    REFUSED,
}

/**
 * What one batched read produced: one reading per canvas, positionally aligned with the paths that went
 * in, and whether the reader answered at all.
 *
 * [outcome] is separate from the readings being empty on purpose — see [ReadOutcome]. It used to be a
 * `reachedServer` boolean, and an HTTP 4xx set it TRUE: the server had answered, after all. But "did the
 * server answer" is not the question anything downstream is asking, and answering that one instead made
 * a bad key indistinguishable from a board with nothing readable on it.
 */
data class BatchRead(val readings: List<BoxReading>, val outcome: ReadOutcome) {

    /** True only when these readings are an actual reading of the canvases that went in. */
    val read: Boolean get() = outcome == ReadOutcome.READ
}

/**
 * A remote reader Stage 4 can use: every canvas in ONE request, one reading back per canvas.
 *
 * One implementation — [LunaOcrReader], on the Responses endpoint of the Foundry resource. The interface
 * stays because the prompt text, the canvases, the `Image N:` labels and the batching all come from
 * HERE rather than from the reader, so a second reader can be compared against this one on one clip
 * without the two request builders drifting apart. There was such a second reader — `ClaudeOcrReader`,
 * on the Anthropic Messages endpoint — deleted once Luna became the reader: an unused code path is one
 * nobody notices going wrong.
 */
interface BatchOcrReader {

    /** True when this reader's key and endpoint were configured at build time. */
    val isEnabled: Boolean

    /**
     * Reads every canvas in one request. The returned list is positionally aligned with [canvasPaths];
     * a canvas whose entry never arrives yields an empty reading rather than someone else's digits.
     */
    suspend fun readBoxDigitsBatch(canvasPaths: List<String>): BatchRead

    companion object {
        private const val TAG = "BatchOcrReader"

        /** A canvas that could not be read, or an image the reply never mentioned. */
        val EMPTY = BoxReading("", emptyList())

        /** Reply budget: a fixed floor plus room per image. A truncated reply loses EVERY canvas. */
        val TOKENS_BASE: Int get() = DeviceConfig.int("ocr.batch_ocr.tokens_base", 2_000)
        val TOKENS_PER_IMAGE: Int get() = DeviceConfig.int("ocr.batch_ocr.tokens_per_image", 500)

        /**
         * The batched prompt: the single-canvas prompt with its output contract — and ONLY that —
         * swapped for a per-image one.
         *
         * [OcrPrompts.DIGITS_ONLY_INTRO], [OcrPrompts.LEGIBLE_RULE] and
         * [OcrPrompts.DIGITS_ONLY_RULES] are reused verbatim, so every rule that governs how a
         * digit is read is literally the same text the on-device reader gets. Batching changes how
         * answers are ADDRESSED, never how they are produced.
         */
        fun batchPrompt(count: Int): String =
            OcrPrompts.DIGITS_ONLY_INTRO +
                "You are given $count images, labeled \"Image 1\" through \"Image $count\". Each one is a " +
                "SEPARATE photo of a DIFFERENT moment and MUST be read independently. Never copy a reading " +
                "from one image onto another, and never merge them: if two images disagree, report what " +
                "each one actually shows.\n\n" +

                "Output ONLY a valid JSON object matching this schema, with no markdown, backticks, or " +
                "extra text: {\"frames\":[{\"image\":<number>,\"readings\":[{\"box\":\"<letter>\"," +
                "\"digits\":\"<digits>\"}]}]}. Include EVERY image exactly once, " +
                "numbered as labeled. An image you can read nothing on still gets an entry, with " +
                "\"readings\":[].\n\n" +

                OcrPrompts.DIGITS_ONLY_RULES

        /**
         * Parses `{"frames":[{"image":N,"readings":[…]}]}` into image number → boxes.
         *
         * Each frame's `readings` array is handed to [OcrPrompts.parseBoxDigitsJson] — the same
         * sanitiser the single-canvas path uses, so `"(B)"` and `"2.47"` are normalised identically, the
         * underscore survives, and a box answered twice with different digits is dropped here too.
         *
         * A frame entry with no usable image number is dropped rather than guessed at: its number is the
         * only thing tying its digits to a canvas.
         */
        fun parseBatchFrames(raw: String): Map<Int, List<BoxDigits>> {
            val text = raw.replace(Regex("(?i)```json"), "").replace("```", "")
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start !in 0 until end) {
                Log.w(TAG, "no JSON object in the batched reply: ${raw.take(200)}")
                return emptyMap()
            }
            return try {
                val frames = JSONObject(text.substring(start, end + 1)).optJSONArray("frames")
                if (frames == null) {
                    Log.w(TAG, "batched reply has no \"frames\" array: ${raw.take(200)}")
                    return emptyMap()
                }
                val out = LinkedHashMap<Int, List<BoxDigits>>()
                for (i in 0 until frames.length()) {
                    val frame = frames.optJSONObject(i) ?: continue
                    val number = frame.optInt("image", -1)
                    if (number < 1) {
                        Log.w(TAG, "dropping a frame entry with no usable image number")
                        continue
                    }
                    val readings = frame.optJSONArray("readings") ?: JSONArray()
                    // Re-wrap as {"readings":[…]} so the shared per-box sanitiser can be reused as-is.
                    val boxes = OcrPrompts.parseBoxDigitsJson(
                        JSONObject().put("readings", readings).toString()
                    )
                    if (out.putIfAbsent(number, boxes) != null) {
                        Log.w(TAG, "duplicate entry for image $number — keeping the first")
                    }
                }
                out
            } catch (e: Exception) {
                Log.w(TAG, "could not parse batched frames: ${raw.take(200)}", e)
                emptyMap()
            }
        }
    }
}
