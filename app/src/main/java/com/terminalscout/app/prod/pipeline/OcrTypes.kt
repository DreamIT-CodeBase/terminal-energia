package com.terminalscout.app.prod.pipeline

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

// ---------------------------------------------------------------------------------------------
// The OCR data model and the prompt text: what Stage 4 asks for and the shape of what comes back.
//
// It came out of the reference prototype's on-device reader, which held two unrelated things — a
// LiteRT-LM engine running a local vision model, and this. The engine was never part of
// this build (the reader is off-device), and everything that still carried the on-device reader's
// name has now gone with it: the whole-board fuel-name prompt it used, the parsers that turned its
// prose into rows, and the temporal-anchoring prompt that fed it the previous frame's answer.
//
// What is left is what the cloud reader actually uses. The seven-segment block lives in ONE constant
// on purpose: it is the only description of what a digit looks like on these boards, and two copies
// of it would drift.
// ---------------------------------------------------------------------------------------------

/**
 * One fuel row read off the board: its 1-based row number (from the top), the fuel type (left of
 * the price) and its price (right). [rowNum] is -1 when the model didn't report a row.
 *
 * Stage 4 does not produce these any more — it reads digits per lettered box, and the names come from
 * Stage 3's layout read. This survives as the type of a payload field that is not filled yet: see
 * [com.terminalscout.app.prod.upload.FrameDetection.readings].
 */
data class FuelPrice(val fuelType: String, val price: String, val rowNum: Int = -1)

/**
 * One box's answer: the letter, the digits read inside it, and the reader's own verdict on whether it
 * could really see them.
 *
 * [label] is the tag Stage 3 drew on the canvas (`A`, `B`, …) and is what binds this reading back to a
 * [PriceRegion]. [digits] holds ONLY the characters `0-9` — no decimal point, no separator, no currency
 * — and is empty when the panel was there but unreadable.
 *
 * [legible] defaults to FALSE and nothing downstream weighs it any more: Stage 5 used to discount a
 * read the reader would not stake on, which is a model marking its own homework on evidence nobody can
 * recount, and it was removed in favour of counting repeats. It is still parsed because the prompt
 * still asks for it and a `_` in the digits is worth seeing in the log.
 */
data class BoxDigits(val label: String, val digits: String, val legible: Boolean = false)

/** One canvas's digits-only answer: the raw reply text plus the per-box readings. */
data class BoxReading(val rawJson: String, val boxes: List<BoxDigits>)

/**
 * The OCR prompt text and the parsers for what comes back — shared by every reader.
 */
object OcrPrompts {
        private const val TAG = "OcrPrompts"

        // The LiteRT-LM engine lock and the `/data/local/tmp/llm/...litertlm` model path used to sit
        // here. Both existed only for the on-device reader: the lock serialised access to a native
        // library that is not in this build, and the path named a file nothing pushes or opens.

        /**
         * How to tell one seven-segment digit from another — the accuracy core of every read prompt,
         * kept in ONE place so the digits-only prompt and the full board prompt can never drift into
         * describing the same glyph differently.
         */
        const val SEVEN_SEGMENT_RULES =
"--- SEVEN-SEGMENT DIGITS (THE PRICES ARE ON SEVEN-SEGMENT PANELS) ---\n" +
            "Every price digit is drawn from up to SEVEN bars: a TOP bar, a MIDDLE bar, a BOTTOM bar, and four short verticals — TOP-LEFT, TOP-RIGHT, BOTTOM-LEFT, BOTTOM-RIGHT. Unlit bars are dark/blank. Identify each digit by EXACTLY which bars are lit and the SHAPE they form.\n" +
            "\n" +
            "PER-DIGIT SHAPES (which bars are lit):\n" +
            "- 0 = top + bottom + all four verticals, MIDDLE BAR OFF → a hollow rectangle/ring with an empty centre.\n" +
            "- 1 = ONLY the two RIGHT verticals (top-right + bottom-right) → a thin vertical line on the right; no top, no middle, no bottom, nothing on the left.\n" +
            "- 2 = top + TOP-RIGHT vertical + middle + BOTTOM-LEFT vertical + bottom → a Z/S shape; top-left and bottom-right are OFF.\n" +
            "- 3 = top + middle + bottom + BOTH RIGHT verticals; the ENTIRE LEFT side is OFF → like a backwards 'E' open to the left.\n" +
            "- 4 = TOP-LEFT vertical + middle + BOTH RIGHT verticals; NO top bar and NO bottom bar and NO bottom-left.\n" +
            "- 5 = top + TOP-LEFT vertical + middle + BOTTOM-RIGHT vertical + bottom; top-right and bottom-left are OFF.\n" +
            "- 6 = like a 5 PLUS the BOTTOM-LEFT vertical lit (closed loop at the bottom); ONLY the TOP-RIGHT vertical is OFF.\n" +
            "- 7 = top bar + BOTH RIGHT verticals; NO middle, NO bottom, nothing on the left → a top bar with a line down the right.\n" +
            "- 8 = ALL SEVEN bars lit → a full solid figure-eight, middle bar present.\n" +
            "- 9 = like an 8 but the BOTTOM-LEFT vertical is OFF → top loop closed, bottom-left open.\n" +
            "\n" +
            "COMMON CONFUSIONS (decide by the exact bars above):\n" +
            "- 0 vs 1 at the FIRST digit position: when the left half of a '0' is cut off, its visible right side can resemble a '1'. Look for the TOP and BOTTOM horizontal bars extending LEFT from the visible right verticals: those bars indicate a '0'; a '1' has NO top or bottom bar. Do not call it '0' from the right verticals alone; if the bars cannot be seen, mark the position unreadable with '_'.\n" +
            "- 1 vs 7: a '1' is JUST the right vertical line with a DARK top edge; a '7' adds a lit HORIZONTAL top bar over that line.\n" +
            "- 1 vs 4: a '1' has nothing on the left and no middle; a '4' adds the TOP-LEFT vertical and the MIDDLE bar.\n" +
            "- 0 vs 8: both are full rectangles — a '0' has the MIDDLE BAR OFF (empty centre), an '8' has the MIDDLE BAR LIT (a dash across the middle).\n" +
            "- 8 vs 9: identical except the BOTTOM-LEFT vertical — LIT on an '8', OFF on a '9'.\n" +
            "- 8 vs 6: identical except the TOP-RIGHT vertical — LIT on an '8', OFF on a '6'.\n" +
            "- 8 vs 3: a '3' has the WHOLE LEFT side OFF (both left verticals dark); an '8' has both left verticals lit.\n" +
            "- 5 vs 6: a '5' has the BOTTOM-LEFT vertical OFF (open bottom-left); a '6' has it LIT (closed bottom loop).\n" +
            "- 5 vs 9: a '5' has the TOP-RIGHT vertical OFF; a '9' has the TOP-RIGHT vertical LIT.\n" +
            "- 3 vs 9: a '3' has the ENTIRE LEFT side OFF; a '9' has the TOP-LEFT vertical LIT.\n" +
            "- 2 vs 3: below the middle bar, a '2' lights the BOTTOM-LEFT vertical; a '3' lights the BOTTOM-RIGHT vertical.\n" +
            "- 2 vs 7: a '2' has a middle bar, a bottom bar and a bottom-left vertical; a '7' has ONLY the top bar + the right verticals (no middle, no bottom).\n" +
            "- 6 vs 0: a '6' has the MIDDLE BAR LIT and the TOP-RIGHT vertical OFF; a '0' has the MIDDLE BAR OFF and all four verticals lit.\n" +
            "- 4 vs 9: a '4' has NO top bar and NO bottom bar; a '9' has BOTH the top bar and the bottom bar lit.\n" +
            "- 0 vs 6 vs 9: a '0' has NO middle bar; BOTH '6' and '9' have the middle bar lit — they differ by which vertical is dark (BOTTOM-LEFT off = 9, TOP-RIGHT off = 6).\n" +
            "\n"

        // The whole-board fuel-name prompt used to sit here - "read each row top to bottom, trace
        // left from the price panel to its label". It belonged to the on-device reader and was dead:
        // Stage 4 asks for DIGITS inside lettered boxes ([DIGITS_ONLY_PROMPT]) because asking one model
        // for names and digits at once cost accuracy on the digits, and the names come from Stage 3's
        // layout read instead. Its last claimed caller - a Stage 5 "magnifying glass" re-read - does
        // not exist in this build.

        /**
         * The Stage-4 prompt. Reads DIGITS ONLY, per lettered box — no fuel names, no row numbers, no
         * layout reasoning.
         *
         * Stage 3 has already found each price panel, outlined it in red and tagged it with a capital
         * letter. So the model is not asked to segment the board or to pair a name with a price; it is
         * asked one narrow question per box, and to say which letter its answer came from. That letter
         * is the binding key — reporting it explicitly is what makes the mapping robust, where relying
         * on output ORDER would silently shift every price if the model skipped or added one row.
         *
         * Digits are requested bare (no separator) and are re-filtered in Kotlin regardless, since a
         * prompt rule is a request, not a guarantee.
         *
         * **Public because [CloudOcrReader] sends the SAME prompt** to the cloud cross-check model.
         * One definition, so the two readers can never be asked subtly different questions and have
         * their answers compared as if they were.
         */
        /**
         * The task framing: what the image holds and what to do with it.
         *
         * Split out from [DIGITS_ONLY_PROMPT] so the batched reader ([BatchOcrReader]) can swap ONLY
         * the output contract while reusing this and [DIGITS_ONLY_RULES] verbatim. The rules are what
         * determine read quality, so they must not exist in two places that can drift apart.
         */
        const val DIGITS_ONLY_INTRO =
"You are an on-device OCR agent reading SEVEN-SEGMENT LED/LCD digit panels. Some regions of the image are outlined by a RED RECTANGLE. Each red rectangle is tagged with a single CAPITAL LETTER (A, B, C, …) printed on a red plate just above or below it.\n" +
            "\n" +
            "The rectangles were placed by an automatic detector, not by a person, and THE DETECTOR IS SOMETIMES WRONG. A rectangle may sit on foliage, sky, brickwork, signage or any other high-contrast texture, and the image may contain no price board at all.\n" +
            "\n" +
            "YOUR TASK, for EACH red rectangle, in this order:\n" +
            "1. DECIDE whether the rectangle contains a SEVEN-SEGMENT LED/LCD digit panel: digits built from straight bars, in evenly spaced cells, on a uniform panel background. The bar shapes are described below - use them to decide, not just to read.\n" +
            "2. If it IS such a panel, read the digits inside it and report them against that rectangle's letter.\n" +
            "3. If it is NOT - leaves, branches, sky, printed text in an ordinary typeface, or any texture that merely suggests digits - report that letter with \"digits\":\"\" and read nothing from it.\n" +
            "\n" +
            "Answering \"\" for a rectangle that holds no digit panel is a CORRECT and EXPECTED answer, not a failure. Rejecting the detector's mistakes is part of the task. A price returned from a rectangle that held no panel is the one outcome this pipeline cannot detect downstream.\n" +
            "\n" +
            "\n"

        /**
         * The SINGLE-image output contract: one flat `readings` array, no frame attribution.
         *
         */
        /**
         * What `legible` means, as the reference states it — the reader's own verdict on ONE box.
         *
         * Its own constant because BOTH prompt shapes need it while only one of them needs the schema
         * line above: the batched envelope changes how answers are addressed, never what a field means.
         */
        /**
         * The single-image output contract: one flat `readings` array.
         *
         * The batched reader replaces this paragraph — and only this paragraph — with a per-image
         * envelope, because a flat array over many images could not say which frame a price came
         * from.
         *
         * There is deliberately NO legibility field. The reader reports what it read; whether a reading
         * survives is decided downstream by evidence that can be recounted — how many times a price
         * repeated, and how much of the pool agrees with it. A model's own verdict on its own reading was
         * a second gate on the same evidence that nothing could audit, and it is gone. `_` still marks a
         * digit position that could not be read, which is a statement about the PANEL, not about the
         * reader's confidence, and stays.
         */
        const val DIGITS_ONLY_SCHEMA =
            "Output ONLY a valid JSON object matching this schema, with no markdown, backticks, or extra text: {\"readings\":[{\"box\":\"<letter>\",\"digits\":\"<digits>\"}]}. If you can read nothing at all, return {\"readings\":[]}.\n"

        /** The reading rules — identical for every reader and every prompt shape. */
        const val DIGITS_ONLY_RULES =
"--- HARD RULES ---\n" +
            "- \"digits\" must contain ONLY the characters 0-9 and the underscore '_'. NEVER include a decimal point, comma, space, currency symbol, unit, letter or any other character. A panel reading 2.47 is reported as \"247\".\n" +
            "- Use '_' for a digit POSITION you cannot read: one underscore per unreadable position, in place. A panel whose second digit is obscured is \"1_49\", not \"149\" and not a guess. Keep the character count equal to the number of digit positions on the panel, so the ones you did read stay in their true places.\n" +
            "- NEVER invent a digit to reach a length, and never drop one to avoid an underscore. A partly-read price is useful; a wrongly-completed one is not. Reporting '_' costs nothing - reporting a wrong digit is the one outcome this pipeline cannot detect.\n" +
            "- Read the digits in the order they appear on the panel, LEFT TO RIGHT, including any digit after the decimal point. Do not reorder, round, or drop a digit.\n" +
            "- Report EVERY lettered rectangle you can see, exactly ONCE each, using its own letter. Do not invent a letter that is not printed in the image.\n" +
            "- Do NOT read fuel names, product labels, row numbers, brand banners, headers, footnotes, dates, times, phone numbers, units or currencies. Do NOT read anything OUTSIDE the red rectangles.\n" +
            "- If a rectangle is present but NOTHING in it can be read - blank, dark, blurred beyond use, or not a digit panel at all - report that letter with \"digits\":\"\". Use \"\" for a panel you cannot read at all and '_' for individual positions inside a panel you can partly read. Never guess a digit you cannot see.\n" +
            "- A digit cell that is DARK/UNLIT is not a digit — it is a blank position. Do not output a digit for it.\n" +
            "\n" +
            "--- THESE BOARDS SPECIFICALLY ---\n" +
            "- A fuel price on these boards is FOUR digits: a panel showing 2.045 is reported as \"2045\". If you have only three AND you have already decided this rectangle holds a seven-segment panel, you have missed a position - look again, and if it is still unreadable put '_' where it belongs (\"2_45\", \"204_\"). Never pad to four and never hand back three: both are read as a different price than the board shows. This rule is about counting POSITIONS on a panel you can SEE; it is never a reason to produce four digits from a rectangle that holds no panel.\n" +
            "- A faint or blurred panel makes a LIT bar look dark. Before you answer '1', check for a faint top bar above the vertical - a '7' whose top bar is washed out reads as a '1'. Same for the top bar of a '9' against a '7'. When the image is soft, judge the bar by whether it is BRIGHTER than the panel background, not by whether it is crisp.\n" +
            "- A small number printed to the LEFT of the digit panel, OUTSIDE it - 95, 98, E10, D, B7 - is the fuel GRADE, not part of the price. It is often the same colour and inside the same red rectangle. Never join it to the digits: a panel showing \"95 | 20.45\" is \"2045\", NOT \"952045\".\n" +
            "\n" +
            "\n" +

                SEVEN_SEGMENT_RULES

        /**
         * The prompt the on-device reader sends: intro + single-image schema + rules.
         *
         * Assembled from the three parts above rather than written out again, so the batched reader can
         * recompose the middle third without the rules existing twice.
         *
         * ### No longer byte-identical to `ml_prompt.txt` (2026-09-18)
         * It was, and that was worth stating while the only changes were structural. Two deliberate
         * edits ended it, both from one observed failure: a crop of a TREE, its rectangles placed by the
         * region detector, came back as the prices 1.819 and 0.959 on five separate canvases, and
         * [ConsensusConsolidator] promoted them because five canvases agreed.
         *
         * 1. **The intro asked the wrong question.** It opened by asserting the image was "a fuel price
         *    board image" and set the task as "for EACH red rectangle, read the digits displayed INSIDE
         *    that rectangle" - which presupposes digits are displayed. The permission to reject a
         *    rectangle existed only as one clause deep in the hard rules. The reader now decides panel
         *    or not-a-panel FIRST, is told the detector is fallible, and is told that "" is a correct
         *    answer rather than a failure.
         *
         * 2. **The rules handed over the two prices it hallucinated.** The four-digit rule illustrated
         *    itself with `1.819 -> "1819", 0.959 -> "0959"` - the exact strings that came back off the
         *    tree. A model with nothing to read fell back on the worked examples in its own
         *    instructions. There is now ONE example, it is a different number, and the four-digit rule
         *    is explicitly gated behind having already decided the rectangle holds a panel.
         *
         * Both edits are in the SHARED constants, so [CloudOcrReader] asks the same question - which is
         * the point of them being shared, and matters more here than usual: the cross-check is only
         * evidence while both readers were asked the same thing.
         */
        const val DIGITS_ONLY_PROMPT =
            DIGITS_ONLY_INTRO + DIGITS_ONLY_SCHEMA + DIGITS_ONLY_RULES

        // The board canvas is encoded LOSSLESS (PNG). JPEG's DCT quantization rings around the
        // sharp black-on-white edges of the price digits — exactly the "smeared digits" artifact —
        // so we never round-trip the crop through a lossy codec before the vision encoder sees it.

        /**
         * Parses a `{"readings":[{"box":"A","digits":"247"}]}` response into per-box readings.
         *
         * Both fields are sanitised here rather than trusted: the label is uppercased and reduced to
         * letters, and the digits are reduced to `0-9`, so a model that answers `"box": "(B)"` or
         * `"digits": "2.47"` still lands correctly instead of being discarded. A row whose label
         * survives as empty is dropped — without a label there is nothing to bind it to. Duplicate
         * labels keep the FIRST non-empty reading.
         */
        fun parseBoxDigitsJson(raw: String): List<BoxDigits> {
            val text = raw.replace(Regex("(?i)```json"), "").replace("```", "")
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start !in 0 until end) return emptyList()
            return try {
                val arr = JSONObject(text.substring(start, end + 1)).optJSONArray("readings")
                    ?: return emptyList()
                val seen = LinkedHashMap<String, BoxDigits>()
                val contradicted = mutableSetOf<String>()
                for (i in 0 until arr.length()) {
                    val row = arr.optJSONObject(i) ?: continue
                    val label = row.optString("box").uppercase().filter { it in 'A'..'Z' }
                    if (label.isEmpty()) continue
                    // 0-9 AND the underscore. The underscore marks a position the model could not read
                    // and is deliberately KEPT: stripping it turns "18_9" into the shorter, plausible,
                    // wrong "189", which Stage 5 would then happily vote on. Left in, it fails Stage 5's
                    // numeric check and the region is skipped with a reason.
                    val digits = row.optString("digits").filter { it.isDigit() || it == '_' }
                    // Defaults to FALSE. A missing field, an underscore, or an empty read all count as
                    // not legible: the reader has to claim confidence for a read to be weighed as
                    // confident, and silence is not a claim.
                    val legible = row.optBoolean("legible", false) &&
                        digits.isNotEmpty() && !digits.contains('_')

                    val existing = seen[label]
                    if (existing != null && existing.digits != digits) {
                        // The same letter answered twice with different digits. Picking the first would
                        // be a silent coin flip, so BOTH go — the model contradicted itself about this
                        // box and neither answer is evidence.
                        contradicted.add(label)
                        continue
                    }
                    if (existing == null) seen[label] = BoxDigits(label, digits, legible)
                }
                if (contradicted.isNotEmpty()) {
                    Log.w(TAG, "dropped ${contradicted.size} box(es) answered twice with different " +
                        "digits: ${contradicted.joinToString()}")
                }
                seen.values.filter { it.label !in contradicted }
            } catch (e: Exception) {
                Log.w(TAG, "Could not parse box-digits JSON: ${raw.take(120)}", e)
                emptyList()
            }
        }

        // `readingsToJson`, `buildTemporalPrompt` and `compactReadingsJson` used to close this
        // object. They rendered [FuelPrice] rows back into the whole-board prompt's shape and fed the
        // model its own previous frame's answer, so it could complete a number whose digits were
        // mid-refresh. All three served the prompt removed above, and nothing called any of them.
}
