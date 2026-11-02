package com.terminalscout.app.prod.pipeline

import android.util.Base64
import android.util.Log
import com.terminalscout.app.prod.BuildConfig
import com.terminalscout.app.prod.config.DeviceConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * The board's layout: which fuel each lettered panel sells, read off the board itself.
 *
 * Board-level on purpose, and kept ONCE rather than per canvas — the layout is a property of the
 * board, and repeating it per frame would invite a consumer to believe two frames could disagree
 * about it.
 *
 * @param names    label → the fuel token as PRINTED on the board (`"95"`, `"D"`, `"LPG"`), as the model
 *                 itself keyed it off the letter tags. A panel whose name was unreadable is simply
 *                 absent, so `names[label]` distinguishes "no name available" from "this panel has no
 *                 name".
 * @param order    the labels in the board's own top-to-bottom order, measured from the geometry
 * @param applied  whether [names] may be trusted — false when nothing could be applied, see [reason]
 * @param reason   why nothing was applied, when nothing was
 * @param illegible rows the model itself marked unreadable
 * @param unknownLabels letters in the reply that are not on this board — dropped, and counted here
 *                 because a reply full of them is a reply that could not read the tags
 * @param json     the translation map as the debug artifact `layout.json`, or empty when it never ran
 */
data class BoardLayout(
    val ran: Boolean = false,
    val framesSent: Int = 0,
    val rowsReturned: Int = 0,
    /** Panels the board actually has, per the geometry. */
    val panels: Int = 0,
    val names: Map<String, String> = emptyMap(),
    val order: List<String> = emptyList(),
    val applied: Boolean = false,
    val illegible: Int = 0,
    val unknownLabels: Int = 0,
    val json: String = "",
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val reasoningTokens: Int = 0,
    val elapsedMs: Long = 0,
    val error: String? = null,
    val reason: String? = null
) {
    /** The layout as one line, for the log: `A=95 > B=98 > C=D`. */
    fun summary(): String =
        if (order.isEmpty()) "no panels"
        else order.joinToString(" > ") { "$it=${names[it].orEmpty().ifEmpty { "?" }}" }
}

/**
 * Stage 3, Step G — the board layout.
 *
 * ### What it is for
 * Steps E and F answer "which boxes are panels, and which panel is which". They cannot answer **what
 * this panel sells**, because that is printed on the board as TEXT and both steps work in geometry
 * alone. A consumer asking for "diesel" has a letter and no way to get from one to the other.
 *
 * This closes the gap with one request per run: a handful of canvases rendered for this step alone —
 * the prepped 640 the detector saw, undimmed, with each panel outlined and its letter tagged just
 * OUTSIDE the box — and one question, which fuel each lettered panel sells.
 *
 * **Stage 4's canvas cannot be used here.** Its dimming crushes strokes and its letter plates sit inside
 * the boxes, exactly on top of the text this step has to read. Step G gets its own render (see
 * `ImagePrepStage.drawRegions(outerTags = true)`), which is why the tags can be present without covering
 * the answer.
 *
 * ### The letter is the binding
 * The model reads the tag off the image and keys its answer by it: `{"box":"A","fuel":"95"}`. So the
 * binding between a name and a panel is stated by the thing that read the board, not reconstructed
 * afterwards from row order.
 *
 * That is what replaced the count gate. The names used to be zipped onto the panels' vertical order and
 * the whole reply refused unless it had exactly one row per panel — necessary while the binding was
 * POSITIONAL, because a reply one row short slid every name below the gap onto the wrong panel and
 * nothing downstream could tell. Keyed by letter there is no sequence left to shift: a partial reply is
 * applied for the panels it names, and a letter that is not on this board is dropped. See [map].
 *
 * A panel the model marks illegible maps to no name at all. Reporting it is still worth asking for: a
 * letter that comes back blank is visibly a non-answer, whereas a letter simply left out is
 * indistinguishable from one the model never saw.
 *
 * ### Why the prompt presses so hard on "illegible"
 * The count gate is a gate on the SHAPE of the reply, and the failure it cannot see is a reply of the
 * right shape whose names were never read. That is not hypothetical: an Alexela board of four panels
 * priced 1.749 / 1.849 / 0.994 / 0.949 came back named `E10, 95, 98, D` — a canonical-looking board,
 * with a petrol grade sitting on a row priced like gas, and the count matched so it was applied.
 *
 * A name is the one thing this pipeline emits that nothing downstream can contradict: a price can be
 * checked against the canvas it came from, whereas "95" on the diesel panel is a plausible row only the
 * forecourt could disprove. So the prompt states that a name which is cropped, covered, too small or
 * simply not printed is *unreadable* rather than a gap to fill, and that returning every row blank is a
 * correct answer. Blanks are cheap here — the row keeps its position, and [EnrichmentStage] can still
 * name it from the station's fuel list.
 *
 * Substituted labelling schemes are refused for the same reason, and stated as a class rather than by
 * name: an EU pump sticker (`E10` is what 95 IS, `B7` what D is), a standards code, the token another
 * country prints for the same product. A model that knows fuel labelling has a ready substitution for a
 * row it cannot read, and every one of them is a plausible non-answer.
 *
 * ### The naming prior was REMOVED — do not put it back
 * The prompt used to carry a compact summary of how Estonian boards are laid out: one canonical
 * top-to-bottom order the stations take a subsequence of (95, 98, D, ECOFORCE/MyD, LPG, CNG, ADBLUE),
 * the closed set of tokens that get printed, and the per-brand tells (ADBLUE ⇒ Circle K, MyD ⇒ Neste,
 * ECOFORCE ⇒ Olerex, LPG with CNG ⇒ Olerex or Alexela). It was there to settle a token that is
 * half-legible, and it was fenced as such — the prompt forbade reordering, adding or dropping rows to
 * fit it.
 *
 * The fence did not hold. A canonical order plus a closed set of tokens is a complete board that can be
 * written without reading one, and that is what came back: a model that cannot resolve a token has a
 * ranked list of what the token probably is and a sequence saying which slot it belongs in, so the
 * cheapest way to answer is to emit the convention. The instruction not to is an instruction about
 * INTENT, and it cannot be obeyed by a model that does not know which of its own tokens it read and
 * which it inferred — the substitution and the reading feel identical from the inside.
 *
 * It is also the one hallucination nothing can catch. The count gate counts rows and a substituted name
 * keeps the count. A name is the one thing this pipeline emits that nothing downstream can contradict:
 * a price can be checked against the canvas it came from, whereas "95" on the diesel panel is a
 * plausible row only the forecourt could disprove. And real boards break the convention (one station
 * prints 98 above 95, another prints `DD`), so the prior was wrong exactly where a blank mattered most.
 *
 * What is left in its place is the "unreadable" rule: a token that cannot be read is reported blank.
 * That trades names for silence, which is the trade this step is supposed to make — a blank keeps its
 * row and position, and [EnrichmentStage] can still name it from the station's fuel list, whereas a
 * confabulated name is believed forever.
 *
 * The format rule is also stated as a TRANSFORMATION rather than a vocabulary: do not expand an
 * abbreviation, do not add a word the board does not print, do not swap in a code from another
 * labelling scheme. That constrains the shape of the answer without supplying the answer.
 *
 * **The prompt does carry a token table again**, added deliberately 2026-09-09: the observed tokens with
 * the form each canonicalises to, fenced as a speller for a token already read and explicitly not a
 * menu. Read everything above before touching it — a token list in this prompt is the failure mode this
 * step was rebuilt to avoid, and the fence is the only thing separating the two. It is a list of tokens
 * with no order and no per-brand tell, which is what the removed prior had and what made a whole board
 * writable without reading one; keep it that way. If names start coming back for panels the canvas
 * cannot show, this table is the first suspect.
 *
 * ### The prompt describes the canvas, so the description has to stay TRUE
 * The opening two paragraphs are a claim about the image, and a model has no way to check one. Two of
 * them were wrong and both cost readings:
 *
 *  - It said the images were "photographed a fraction of a second apart". They are not: [layoutFrames]
 *    picks by panel count, then clarity, then lateness, so the three can be seconds apart, from
 *    anywhere in an approach during which the board slides and grows. A model told they are near-
 *    simultaneous has been told that a board at two different scales is two different boards.
 *  - It said the tag is "printed just OUTSIDE the panel's bottom-left corner, so it covers none of the
 *    board". `ImagePrepStage.drawRegions(outerTags = true)` places it by a three-way cascade — left of
 *    the box, else below it, else back INSIDE the bottom-left corner — and that third case covers
 *    exactly the corner where a board often prints the fuel name. So the prompt promised the answer was
 *    never obscured while the render can obscure it, which is a licence to guess at a name that is
 *    genuinely hidden. It now states the cascade and says a name under a plate is unreadable.
 *
 * Anything else that stage 3's render changes — the tag positions, the letterbox, the greyscale, the
 * fact that Step G's canvas is undimmed — has to be changed here in the same commit.
 *
 * ### Board-agnostic on purpose
 * Nothing in the prompt is specific to a market, a brand or a board style. A panel's name is described
 * as "printed with" its box — above, below, either side, or inside — rather than "beside" it, which
 * was both an assumption about layout and, read with the old exclusion rule ("anything not inside a red
 * box"), a self-contradiction that excluded the very text being asked for. The exclusion now scopes
 * ENTRIES to outlined boxes, not the name's position.
 * ### Failure is never fatal
 * No key, no endpoint, a timeout, an HTTP error, an unparseable reply, a refused count — every one of
 * them returns a [BoardLayout] with `applied = false` and leaves the run otherwise untouched.
 */
object BoardLayoutReader {

    private const val TAG = "BoardLayout"

    /** True when both the key and the Responses endpoint were configured at build time. */
    val isConfigured: Boolean
        get() = BuildConfig.AZURE_AI_KEY.isNotBlank() && BuildConfig.AZURE_RESPONSES_ENDPOINT.isNotBlank()

    /**
     * How many canvases to send. The layout is a property of the BOARD, not of a frame, so this is
     * about seeing it clearly rather than often: a handful of the canvases that show the most panels,
     * which is where the whole stack is visible at once — and, since the letters are read off the image
     * now, where the most tags are legible at once.
     */
    val FRAMES: Int get() = DeviceConfig.int("board_prep.step_g_board_layout.frames", 3)

    /** JPEG quality for the canvases sent. Matches the harness. */
    val JPEG_QUALITY: Int get() = DeviceConfig.int("board_prep.step_g_board_layout.jpeg_quality", 90)

    /**
     * Thinking allowance, on top of the answer budget — the reply is billed for both. Matches the
     * audit's; a truncated reply loses the whole board's names, so the failure shows up as a `reply
     * INCOMPLETE` warning in logcat and an unapplied layout, not as wrong names.
     *
     * Raised 8k → 20k on 2026-09-18, alongside the Stage-4 reader's own allowance. Headroom
     * rather than a response to an observed truncation: neither of these two calls has been seen
     * to return `reply INCOMPLETE`, but both fail the same expensive way when they do — the whole
     * board's answer is lost, not one frame's — and the ceiling is a CAP, so a reply that already
     * finished costs exactly what it cost before.
     */
    private val REASONING_ALLOWANCE: Int get() = DeviceConfig.int("board_prep.step_g_board_layout.reasoning_allowance", 20_000)
    private val TOKENS_BASE: Int get() = DeviceConfig.int("board_prep.step_g_board_layout.tokens_base", 2_000)
    private val TOKENS_PER_FRAME: Int get() = DeviceConfig.int("board_prep.step_g_board_layout.tokens_per_frame", 300)

    /** Null sends no `reasoning` key, so the deployment's own default applies. */
    private val EFFORT: String? = null

    private val TIMEOUT_S: Long get() = DeviceConfig.long("board_prep.step_g_board_layout.timeout_s", 900L)

    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            // The request carries several 640x640 canvases as base64.
            .writeTimeout(120, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .build()
    }

    private val PROMPT = """
        These images all show the SAME fuel price board, taken by one camera at different moments as it approached the board - so the board may sit at a different position and scale in each. They have been converted to greyscale and sharpened, and fitted onto a square canvas, so plain grey padding may surround the board.

        Each price panel has been OUTLINED IN RED and given a LETTER TAG - a white letter on a small red plate. The tag names the panel whose outline it sits against: just to the LEFT of that outline, or, where there was no room, just BELOW it, or, failing both, inside the panel's bottom-left corner. So a plate usually covers nothing - but it CAN land on top of the board, and a name hidden under a plate is a name you cannot read.

        YOUR ONLY TASK: say which fuel each TAGGED panel sells - a map from letter to the fuel name or grade printed against that panel.

        RULES
        - One entry per TAGGED panel, keyed by the letter on its plate. Match each plate to the outline it belongs to; do not assume the letters run A, B, C down the board, and do not report a letter that is not printed on these images.
        - A panel is a red-outlined box of price digits. Its name or grade is the text printed with that box, which may be above, below, either side of, or inside it. Every entry must be about a red-outlined box: text elsewhere on the board - headers, brand marks, signage, advertising - is not a panel and gets no entry, whatever it says.
        - Report the name EXACTLY as printed: the same characters, in the same form. Do not expand an abbreviation into the word it stands for, do not add a word the board does not print, and do not swap what is printed for an equivalent code from any other labelling scheme - a pump sticker, a standard, a name used in another country. What is printed IS the answer, even where it looks unfamiliar, inconsistent or incomplete.
        - Do NOT read the prices. The digits are another stage's job and are ignored here.
        - If a panel's name is unreadable, report that panel with "fuel":"" and "legible":false. Report it either way: a letter you leave out is indistinguishable from a letter you never saw.
        - "Unreadable" includes NOT PRESENT IN THE IMAGE: a name cropped off the canvas, covered by a tag plate or anything else, too small or too faint to resolve, or simply not printed on this board. Report those "legible":false as well. Do NOT supply the name the panel probably carries, the one a board like this usually carries, or the one that would complete a pattern you think the board follows - a name you did not actually read is worse than a blank, because a blank is visibly a non-answer while a plausible name is believed and nothing later can contradict it. Returning every panel blank is a correct answer for a board whose names you cannot see.
        - The board has the same layout in every image, and a letter means the same panel in all of them. Use all of them to read it, and report ONE map, not one per image.

        FUEL TOKENS SEEN ON THESE BOARDS
        Use this only to settle the exact form of a token you HAVE read. It is not a menu: do not use it to supply a name you could not read, and do not use it to complete a board. A token that is not in this table is still the right answer when it is what the panel prints.

        | Token (verbatim) | Canonical | Is Fuel? | Meaning / Notes |
        |------------------|-----------|----------|-----------------|
        | 95               | 95        | Yes      | Petrol, regular (RON 95) |
        | 98               | 98        | Yes      | Petrol, premium (RON 98) |
        | D                | D         | Yes      | Diesel |
        | DD               | D         | Yes      | Diesel - typo/double-grade (Valga Olerex) |
        | MyD              | MyD       | Yes      | Renewable diesel, Neste (Tartu Ringtee Neste) |
        | MYD              | MyD       | Yes      | Renewable diesel, Neste - all-caps variant (Peetri Neste) |
        | ECOFORCE         | ECOFORCE  | Yes      | Olerex diesel product (Peetri Olerex) |
        | LPG              | LPG       | Yes      | Autogas; "LPG " with trailing space appears |
        | CNG              | CNG       | Yes      | Compressed natural gas; "CNG " with trailing space appears |
        | ADBLUE           | ADBLUE    | No       | DEF/urea additive - not a fuel |

        Output ONLY a valid JSON object matching this schema, with no markdown, backticks or extra text: {"rows":[{"box":"<the letter on the plate>","fuel":"<printed token>","legible":<true|false>}]}. If no panel's name is legible at all, return every panel with "fuel":"" and "legible":false.
    """.trimIndent()

    /**
     * Reads the layout off [images] and maps it onto [order].
     *
     * @param images the TAGGED canvases as JPEG bytes — undimmed, outlined, letters outside the boxes
     * @param order  the board's labels in vertical order, top to bottom, measured from the geometry.
     *               Used to reject a letter the board does not have, and carried into the artifact; the
     *               names themselves are bound by the letter the model read, not by this order.
     */
    suspend fun read(images: List<ByteArray>, order: List<String>): BoardLayout {
        if (!isConfigured) {
            Log.d(TAG, "no key or Responses endpoint configured — Step G skipped")
            return BoardLayout(error = "not configured")
        }
        if (order.isEmpty()) return BoardLayout(error = "no named panels")
        if (images.isEmpty()) return BoardLayout(order = order, panels = order.size, error = "no tagged canvas captured")

        Log.d(
            TAG,
            "Step G: reading the layout of a ${order.size}-panel board from ${images.size} canvas(es) " +
                "with ${BuildConfig.AZURE_AI_MODEL}"
        )
        val started = System.currentTimeMillis()
        val reply = post(images, order)
        val elapsed = System.currentTimeMillis() - started

        if (reply.error != null) {
            Log.w(TAG, "Step G: ${reply.error} — the board keeps its letters and no names")
            return BoardLayout(
                ran = true, framesSent = images.size, panels = order.size, order = order,
                elapsedMs = elapsed, error = reply.error
            )
        }

        val rows = parse(reply.text)
        val mapped = map(order, rows)
        val layout = BoardLayout(
            ran = true,
            framesSent = images.size,
            rowsReturned = rows.size,
            panels = order.size,
            names = mapped.names,
            order = order,
            applied = mapped.names.isNotEmpty(),
            illegible = rows.count { !it.legible },
            unknownLabels = mapped.unknownLabels,
            inputTokens = reply.inputTokens,
            outputTokens = reply.outputTokens,
            reasoningTokens = reply.reasoningTokens,
            elapsedMs = elapsed,
            reason = mapped.reason
        )
        val named = layout.copy(json = artifact(layout))
        if (named.applied) {
            Log.d(
                TAG,
                "Step G: ${named.summary()} (${named.names.size}/${order.size} named, " +
                    "${named.illegible} illegible, ${named.unknownLabels} unknown letter(s), ${elapsed}ms)"
            )
        } else {
            Log.w(TAG, "Step G: no names applied — ${mapped.reason ?: "unknown"} (${elapsed}ms)")
        }
        return named
    }

    /**
     * The translation map as JSON — `layout.json` in the run's debug folder.
     *
     * The map is the answer, so it comes first and flat: `{"A":"95","B":"D"}`, exactly the binding every
     * later stage uses. Everything under `debug` is about how much of the board that map covers — a
     * board where two of five panels are named is a materially different result from one where five are,
     * and the payload alone cannot show the difference.
     */
    private fun artifact(layout: BoardLayout): String = JSONObject().apply {
        put("source", "PriceBoardPipeline · Stage 3 Step G (board layout)")
        put("model", BuildConfig.AZURE_AI_MODEL)
        put("names", JSONObject().apply { layout.names.forEach { (k, v) -> put(k, v) } })
        put("board_order", JSONArray().apply { layout.order.forEach { put(it) } })
        put("applied", layout.applied)
        put("reason", layout.reason ?: JSONObject.NULL)
        put("debug", JSONObject().apply {
            put("panels", layout.panels)
            put("named", layout.names.size)
            put("rows_returned", layout.rowsReturned)
            put("illegible", layout.illegible)
            put("unknown_labels", layout.unknownLabels)
            put("frames_sent", layout.framesSent)
            put("elapsed_ms", layout.elapsedMs)
            put("tokens", JSONObject().apply {
                put("input", layout.inputTokens)
                put("output", layout.outputTokens)
                put("reasoning", layout.reasoningTokens)
            })
            put("error", layout.error ?: JSONObject.NULL)
        })
    }.toString(2)

    // ---- the request ------------------------------------------------------------------------- //

    private class Reply(
        val text: String = "",
        val inputTokens: Int = 0,
        val outputTokens: Int = 0,
        val reasoningTokens: Int = 0,
        val error: String? = null
    )

    /**
     * One Responses request: the canvases, then the task. **Step G is a single call** — one reply names
     * every panel, because the layout is one property of one board and asking per panel would pay the
     * canvases' input cost again for each.
     *
     * Images first and instructions last, the same order Step F and the OCR stage use.
     *
     * The letters are stated because they are known — this stage drew them — and naming them is what
     * stops the model reporting a panel that is not tagged, or inventing a letter for one it cannot pin
     * down. It is NOT a count gate: a reply that misses a letter costs that panel's name and nothing
     * else, since every name is bound by the letter the model read rather than by its place in a list.
     */
    private suspend fun post(images: List<ByteArray>, labels: List<String>): Reply = withContext(Dispatchers.IO) {
        val content = JSONArray()
        images.forEachIndexed { i, blob ->
            content.put(JSONObject().put("type", "input_text").put("text", "Image ${i + 1}:"))
            content.put(
                JSONObject()
                    .put("type", "input_image")
                    .put(
                        "image_url",
                        "data:image/jpeg;base64," + Base64.encodeToString(blob, Base64.NO_WRAP)
                    )
                    .put("detail", "high")
            )
        }
        content.put(
            JSONObject().put("type", "input_text").put(
                "text",
                "This board has ${labels.size} tagged price panel(s), lettered " +
                    "${labels.sorted().joinToString(", ")}. Report one entry per letter." + "\n\n" + PROMPT
            )
        )

        val body = JSONObject()
            .put("model", BuildConfig.AZURE_AI_MODEL)
            .put(
                "max_output_tokens",
                TOKENS_BASE + TOKENS_PER_FRAME * images.size + REASONING_ALLOWANCE
            )
            .put("input", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
        EFFORT?.let { body.put("reasoning", JSONObject().put("effort", it)) }

        val request = Request.Builder()
            .url(BuildConfig.AZURE_RESPONSES_ENDPOINT)
            .header("api-key", BuildConfig.AZURE_AI_KEY)
            .post(body.toString().toRequestBody(JSON))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Reply(error = "HTTP ${response.code}: ${raw.take(300)}")
                }
                val json = try {
                    JSONObject(raw)
                } catch (e: Exception) {
                    return@withContext Reply(error = "reply was not JSON")
                }
                if (json.optString("status") == "incomplete") {
                    val why = json.optJSONObject("incomplete_details")?.optString("reason") ?: "unknown"
                    Log.w(TAG, "reply INCOMPLETE ($why) — raise the reasoning allowance")
                }
                val usage = json.optJSONObject("usage")
                val text = replyText(json)
                Reply(
                    text = text,
                    inputTokens = usage?.optInt("input_tokens") ?: 0,
                    outputTokens = usage?.optInt("output_tokens") ?: 0,
                    reasoningTokens = usage?.optJSONObject("output_tokens_details")
                        ?.optInt("reasoning_tokens") ?: 0,
                    error = if (text.isBlank()) "empty reply" else null
                )
            }
        } catch (e: Exception) {
            Reply(error = "unreachable: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** The answer text out of the Responses envelope; reasoning items are not folded in. */
    private fun replyText(body: JSONObject): String {
        val output = body.optJSONArray("output") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            if (item.optString("type") != "message") continue
            val content = item.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val part = content.optJSONObject(j) ?: continue
                if (part.optString("type") == "output_text") sb.append(part.optString("text"))
            }
        }
        return sb.toString()
    }

    // ---- the reply -------------------------------------------------------------------------- //

    /** One panel of the board, as the model reported it: the letter it read, and the name beside it. */
    private class Row(val box: String, val fuel: String, val legible: Boolean)

    /**
     * `{"rows":[{"box","fuel","legible"}]}` → one entry per letter the reply names.
     *
     * A row without a usable letter is dropped rather than guessed at: the letter IS the binding here,
     * so a row that cannot say which panel it describes carries no information. Duplicate letters keep
     * the first — the reply is meant to name each panel once, and a repeat would otherwise let the
     * second overwrite a name already read.
     */
    private fun parse(text: String): List<Row> {
        val cleaned = text.replace(Regex("(?i)```json"), "").replace("```", "")
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) return emptyList()
        val rows = try {
            JSONObject(cleaned.substring(start, end + 1)).optJSONArray("rows")
        } catch (e: Exception) {
            Log.w(TAG, "reply did not parse as JSON", e)
            null
        } ?: return emptyList()

        val seen = LinkedHashMap<String, Row>()
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val box = row.optString("box").trim().uppercase()
            if (box.isEmpty() || seen.containsKey(box)) continue
            val fuel = row.optString("fuel").trim()
            val legible = if (row.has("legible")) row.optBoolean("legible") else fuel.isNotEmpty()
            val usable = legible && fuel.isNotEmpty()
            seen[box] = Row(box, if (usable) fuel else "", usable)
        }
        return seen.values.toList()
    }

    /**
     * Label → printed fuel token, applied **per letter**.
     *
     * ### Why there is no longer a count gate
     * This used to zip the reply's rows against the board's vertical order and refuse the whole reply
     * unless the counts matched exactly. That gate existed because the binding was POSITIONAL: a reply
     * one row short slid every name below the gap onto the wrong panel, and nothing downstream could
     * tell. The names are now keyed by the letter printed on the canvas the model was shown, so a
     * missing panel costs that panel's name and nothing else — there is no sequence left to shift.
     *
     * So a partial reply is used for what it says. A letter that is not on this board is dropped rather
     * than trusted: it is a letter the model invented or misread, and the panel it was meant for cannot
     * be recovered from it.
     */
    private fun map(order: List<String>, rows: List<Row>): MapResult {
        if (order.isEmpty()) return MapResult(emptyMap(), 0, "no panels to name")
        if (rows.isEmpty()) return MapResult(emptyMap(), 0, "no rows returned")

        val onBoard = order.toSet()
        val names = LinkedHashMap<String, String>()
        var unknown = 0
        for (row in rows) {
            if (row.box !in onBoard) {
                unknown++
                continue
            }
            if (row.fuel.isNotEmpty()) names[row.box] = row.fuel
        }
        val reason = when {
            names.isNotEmpty() -> null
            unknown == rows.size -> "no letter in the reply is on this board"
            else -> "every panel came back unreadable"
        }
        return MapResult(names, unknown, reason)
    }

    /** [map]'s outcome: the names that stuck, how many letters were not on the board, and why none did. */
    private class MapResult(
        val names: Map<String, String>,
        val unknownLabels: Int,
        val reason: String?
    )
}

