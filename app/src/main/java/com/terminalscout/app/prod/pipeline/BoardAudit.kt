package com.terminalscout.app.prod.pipeline

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
 * One named region as the audit sees it — geometry and a label, nothing else.
 *
 * [id] is the caller's own handle on the region (Stage 3 uses the index into its per-canvas list), so
 * the caller can map an audited result back onto whatever it holds. The audit never interprets it.
 */
class AuditRegion(
    val id: Int,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    var label: String
) {
    val area: Float get() = (right - left) * (bottom - top)
}

/**
 * One canvas's naming, in reading order.
 *
 * [frameIndex] is the key the reply comes back under, so it has to be unique across the canvases sent
 * — see [BoardAudit.run], which drops a second canvas sharing a frame number rather than risk
 * addressing the wrong one.
 */
class AuditCanvas(
    val frameIndex: Int,
    val name: String,
    var regions: MutableList<AuditRegion>
)

/** What the audit was asked, what it answered, and what survived validation. */
data class AuditOutcome(
    val ran: Boolean = false,
    val framesSent: Int = 0,
    val regionsSent: Int = 0,
    /** Frames the reply proposed anything for. */
    val framesActioned: Int = 0,
    /** Actions in the reply, before validation. */
    val proposed: Int = 0,
    val renamed: Int = 0,
    val removed: Int = 0,
    val rejected: Int = 0,
    /** Why actions were refused, by reason. */
    val rejectWhy: Map<String, Int> = emptyMap(),
    val tagsBefore: Int = 0,
    val tagsAfter: Int = 0,
    /** Canvases the audit left with no regions at all. */
    val emptied: Int = 0,
    /** ... and ones left under [BoardAudit.MIN_REGIONS_EXPECTED] but not empty. */
    val thinned: Int = 0,
    /** One line per applied action, for the log. */
    val log: List<String> = emptyList(),
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val reasoningTokens: Int = 0,
    val elapsedMs: Long = 0,
    val error: String? = null
)

/**
 * Stage 3, Step F — the board audit. The only part of the pipeline's image path that leaves the device.
 *
 * ### What it is for
 * Steps A-E decide everything from geometry: a corner distance, a pitch, an affine fit. That is the
 * right way round — cheap, repeatable, needs no network — but it is blind to the one thing the whole
 * video makes obvious and no single canvas can show: **which** of the named regions are actually the
 * board's price panels. Geometry can only ask "does this box sit where a remembered slot sits"; it
 * cannot ask "do these boxes, taken together, look like a stack of fuel price panels bolted to a post".
 *
 * So after pass 2 the finished naming is sent to the model as **pure geometry — no images**: per
 * canvas, each region's name, its two corners and its area. What comes back is, per frame, actions of
 * two kinds:
 *
 *   - `rename` — this region is a real panel wearing the wrong letter → that letter
 *   - `remove` — this region is not one of the board's price panels at all
 *
 * The letters themselves are left alone. The audit used to re-letter the surviving panels from A in
 * board order, so a canvas came out carrying a tidy `A..E` run; that is gone, and Step E's letters
 * stand exactly as minted, holes and all. Tidiness was the only thing it bought, and it was paid for in
 * evidence: it made `board_order` alphabetical BY CONSTRUCTION, so the letter and the measured vertical
 * order stopped being two independent signals that could be caught disagreeing; it discarded the old
 * -> new mapping, orphaning every log line above it; and because it ran only when the cloud call
 * SUCCEEDED, identical footage came out lettered one way with the audit and another way when the
 * endpoint timed out. Nothing downstream needs contiguity: `board_order` is measured from the geometry
 * after this runs, and both readers bind on the letter they can see rather than on its place in the
 * alphabet.
 *
 * ### Nothing is trusted
 * Every action is validated against the board before it is applied, and a frame whose actions break any
 * rule keeps the naming Step E gave it. The asymmetry is deliberate: Step E's answer is wrong in a way
 * that is already measured, whereas an unvalidated action could put one name on two panels or reverse
 * the board — worse than the mislabelling it claims to fix. The four rules are in [applyActions].
 *
 * ### A third request shape
 * This calls the **Responses** API (`input` + `max_output_tokens` + `reasoning`), not the
 * chat-completions endpoint [CloudOcrReader] uses, nor the Anthropic-shaped one the deleted
 * `ClaudeOcrReader` used.
 * Same key and same Foundry resource; different envelope, hence [BuildConfig.AZURE_RESPONSES_ENDPOINT].
 *
 * ### Failure is never fatal
 * No key, no endpoint, a timeout, an HTTP error, an unparseable reply — every one of them returns an
 * [AuditOutcome] with `error` set and leaves the naming exactly as Step E produced it.
 */
object BoardAudit {

    private const val TAG = "BoardAudit"

    /** True when both the key and the Responses endpoint were configured at build time. */
    val isConfigured: Boolean
        get() = BuildConfig.AZURE_AI_KEY.isNotBlank() && BuildConfig.AZURE_RESPONSES_ENDPOINT.isNotBlank()

    /**
     * Thinking allowance, on top of the answer budget. The reply is billed for the deliberation as well
     * as the answer, and a truncated reply loses the audit of every canvas at once rather than one of
     * them — the same bargain the OCR stage makes, for the same reason.
     *
     * Below the OCR stage's on purpose: this call runs at the deployment's default effort (see [EFFORT])
     * and only has to judge geometry it was handed, where the reader has to resolve digits off a photo.
     * If the audit starts coming back empty, check logcat for the `reply INCOMPLETE` warning before
     * blaming the prompt — that line, and nothing else, is what an allowance set too low looks like.
     *
     * Raised 8k → 20k on 2026-09-18, alongside the Stage-4 reader's own allowance. Headroom
     * rather than a response to an observed truncation: neither of these two calls has been seen
     * to return `reply INCOMPLETE`, but both fail the same expensive way when they do — the whole
     * board's answer is lost, not one frame's — and the ceiling is a CAP, so a reply that already
     * finished costs exactly what it cost before.
     */
    private val REASONING_ALLOWANCE: Int get() = DeviceConfig.int("board_prep.step_f_board_audit.reasoning_allowance", 20_000)
    private val TOKENS_BASE: Int get() = DeviceConfig.int("board_prep.step_f_board_audit.tokens_base", 2_000)
    private val TOKENS_PER_FRAME: Int get() = DeviceConfig.int("board_prep.step_f_board_audit.tokens_per_frame", 120)

    /**
     * Reasoning effort. Null sends no `reasoning` key at all, so the deployment's own default (medium)
     * applies and the request is what it would have been before this option existed.
     */
    private val EFFORT: String? = null

    private val TIMEOUT_S: Long get() = DeviceConfig.long("board_prep.step_f_board_audit.timeout_s", 900L)

    /** Mirrors Stage 3's own floor, for reporting a canvas the audit thinned below a real board. */
    val MIN_REGIONS_EXPECTED: Int get() = DeviceConfig.int("board_prep.step_f_board_audit.min_regions_expected", 2)

    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            // Generous: the model reasons over every canvas of the video before it answers.
            .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .build()
    }

    private val PROMPT = """
        You are auditing the region-naming output of a fuel price board detector.

        A fuel price post carries a CLUSTER of price panels: seven-segment displays bolted one above the next in a vertical column, of similar width and height, at a roughly even vertical pitch, and holding their relative order permanently - panel 2 is below panel 1 on every frame, however far the board slides or grows on screen as the camera approaches it.

        The detector has already named every region it found. A name (A, B, C ...) is meant to mean the SAME physical panel on every frame of this video. You are given, per frame, each named region's name, the pixel coordinates of its top-left and bottom-right corners on the canvas, and its area in pixels. No images.

        Work out what the board actually is: which names form the viable cluster of fuel price panels, in what top-to-bottom order, and how that cluster moves and scales through the video. Judge each frame against it. Two things go wrong:

          * a region is not one of the board's price panels at all - a reflection, a sign beside the post, a sliver clipped off the board edge, a stray box far from the column, one panel boxed twice, or a name that appears on only one or two frames out of many;
          * a region IS a panel but wears the wrong name - it has taken a neighbour's name, so the board appears to have more panels than it has, and one name means two different panels on two different frames.

        For EACH frame, output only the actions that frame needs:

          {"region":"<name>","action":"rename","to":"<name>","why":"<max 8 words>"}
          {"region":"<name>","action":"remove","why":"<max 8 words>"}

        Rules for your output:
          * "to" must be a name that appears somewhere in the input.
          * Emit NO action for a region that is already correct. Most frames need nothing.
          * Within one frame, the names left after your actions must be distinct, and must run in the cluster's top-to-bottom order down the frame.
          * A name that is wrong on EVERY frame is not wrong - it is what that panel is called. Rename only where a frame disagrees with the rest of the video.
          * Prefer rename over remove for anything sitting in the column at the right pitch.

        Output ONLY a JSON object, no markdown, no backticks:
        {"frames":[{"frame":<int>,"actions":[ ... ]}]}
        A frame needing nothing may be omitted entirely. Do not explain outside the JSON.
    """.trimIndent()

    /**
     * Audits [canvases] against [boardOrder] and applies whatever survives validation, **mutating**
     * each canvas's `regions` list and its regions' labels in place.
     *
     * @param canvases every NAMED canvas of one video, each with its regions in reading order. An
     *   unnamed canvas has nothing to audit and should not be passed.
     * @param boardOrder the board's remembered tag sequence, top to bottom — the rename targets that
     *   are allowed, and the order a frame's surviving labels must run in.
     * @param canvasSize the square canvas edge, sent so the model can read the coordinates in context.
     * @param videoId names the video in the payload; diagnostic only.
     */
    suspend fun run(
        canvases: List<AuditCanvas>,
        boardOrder: List<String>,
        canvasSize: Int,
        videoId: String
    ): AuditOutcome {
        if (!isConfigured) {
            Log.d(TAG, "no key or Responses endpoint configured — Step F skipped")
            return AuditOutcome(error = "not configured")
        }

        // Frames run in video order, OLDEST FIRST — the reverse of the naming order — because the
        // cluster the model has to infer develops over time, and reading it backwards would make the
        // board appear to shrink away.
        val byFrame = LinkedHashMap<Int, AuditCanvas>()
        for (c in canvases) {
            if (c.regions.isEmpty()) continue
            // Two boards cut from one frame: the frame number is the key the reply comes back under, so
            // a second canvas sharing it could not be addressed unambiguously. Left un-audited rather
            // than risked.
            if (byFrame.containsKey(c.frameIndex)) {
                Log.d(TAG, "${c.name}: shares frame ${c.frameIndex} with another canvas — not audited")
                continue
            }
            byFrame[c.frameIndex] = c
        }
        val ordered = byFrame.values.sortedBy { it.frameIndex }
        if (ordered.isEmpty()) return AuditOutcome(ran = true, error = "nothing named to audit")

        val payload = buildPayload(videoId, ordered, canvasSize)
        val framesSent = ordered.size
        val regionsSent = ordered.sumOf { it.regions.size }
        Log.d(TAG, "Step F: auditing $framesSent canvas(es) ($regionsSent regions) with ${BuildConfig.AZURE_AI_MODEL}")

        val started = System.currentTimeMillis()
        val reply = post(payload, framesSent)
        val elapsed = System.currentTimeMillis() - started
        if (reply.error != null) {
            Log.w(TAG, "Step F: ${reply.error} — naming left as Step E had it")
            return AuditOutcome(
                ran = true, framesSent = framesSent, regionsSent = regionsSent,
                elapsedMs = elapsed, error = reply.error
            )
        }

        val actions = parseActions(reply.text)
        val applied = applyActions(byFrame, actions, boardOrder)

        val outcome = applied.copy(
            ran = true,
            framesSent = framesSent,
            regionsSent = regionsSent,
            inputTokens = reply.inputTokens,
            outputTokens = reply.outputTokens,
            reasoningTokens = reply.reasoningTokens,
            elapsedMs = elapsed
        )
        Log.d(
            TAG,
            "Step F: ${outcome.renamed} renamed, ${outcome.removed} removed, ${outcome.rejected} " +
                "rejected, tags ${outcome.tagsBefore} -> ${outcome.tagsAfter}, ${elapsed}ms"
        )
        outcome.log.forEach { Log.d(TAG, "  $it") }
        if (outcome.rejectWhy.isNotEmpty()) {
            Log.d(TAG, "  rejected: " + outcome.rejectWhy.entries.joinToString { "${it.value}x ${it.key}" })
        }
        return outcome
    }

    // ---- the request ------------------------------------------------------------------------- //

    /** The geometry, as the model is shown it: name, both corners, area. Corners rounded to pixels. */
    private fun buildPayload(videoId: String, canvases: List<AuditCanvas>, size: Int): JSONObject {
        val frames = JSONArray()
        for (c in canvases) {
            val regions = JSONArray()
            for (r in c.regions) {
                regions.put(
                    JSONObject()
                        .put("name", r.label)
                        .put("tl", JSONArray().put(Math.round(r.left)).put(Math.round(r.top)))
                        .put("br", JSONArray().put(Math.round(r.right)).put(Math.round(r.bottom)))
                        .put("area", Math.round(r.area))
                )
            }
            frames.put(JSONObject().put("frame", c.frameIndex).put("regions", regions))
        }
        return JSONObject()
            .put("video", videoId)
            .put("canvas", JSONArray().put(size).put(size))
            .put("frames", frames)
    }

    private class Reply(
        val text: String = "",
        val inputTokens: Int = 0,
        val outputTokens: Int = 0,
        val reasoningTokens: Int = 0,
        val error: String? = null
    )

    /**
     * One Responses request: the geometry first, the instructions last — the same order the OCR stage
     * uses, so the model has seen the whole video before it is told what to do with it.
     */
    private suspend fun post(payload: JSONObject, frames: Int): Reply = withContext(Dispatchers.IO) {
        val content = JSONArray()
            .put(JSONObject().put("type", "input_text").put("text", payload.toString()))
            .put(JSONObject().put("type", "input_text").put("text", PROMPT))
        val body = JSONObject()
            .put("model", BuildConfig.AZURE_AI_MODEL)
            .put("max_output_tokens", TOKENS_BASE + TOKENS_PER_FRAME * frames + REASONING_ALLOWANCE)
            .put(
                "input",
                JSONArray().put(JSONObject().put("role", "user").put("content", content))
            )
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

    /**
     * The answer text out of the Responses envelope.
     *
     * Reasoning items are deliberately NOT folded in: they discuss the same names and would hand the
     * parser a second, contradictory set of actions.
     */
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

    private class Action(val region: String, val kind: String, val to: String, val why: String)

    /**
     * Frame number → its actions.
     *
     * The OUTERMOST JSON object is taken, so a model that wraps its answer in prose or a fenced block
     * is still understood rather than silently ignored.
     */
    private fun parseActions(text: String): Map<Int, List<Action>> {
        if (text.isBlank()) return emptyMap()
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return emptyMap()
        val obj = try {
            JSONObject(text.substring(start, end + 1))
        } catch (e: Exception) {
            Log.w(TAG, "reply did not parse as JSON", e)
            return emptyMap()
        }
        val frames = obj.optJSONArray("frames") ?: return emptyMap()
        val out = LinkedHashMap<Int, MutableList<Action>>()
        for (i in 0 until frames.length()) {
            val entry = frames.optJSONObject(i) ?: continue
            if (!entry.has("frame")) continue
            val frame = entry.optInt("frame", Int.MIN_VALUE)
            if (frame == Int.MIN_VALUE) continue
            val acts = entry.optJSONArray("actions") ?: continue
            for (j in 0 until acts.length()) {
                val a = acts.optJSONObject(j) ?: continue
                out.getOrPut(frame) { mutableListOf() }.add(
                    Action(
                        region = a.optString("region"),
                        kind = a.optString("action").lowercase(),
                        to = a.optString("to"),
                        why = a.optString("why").take(40)
                    )
                )
            }
        }
        return out
    }

    /**
     * Applies the reply, one frame at a time. Four rules, and a frame whose actions break any of them
     * keeps the naming Step E gave it:
     *
     *  1. the frame and the region named must exist, and each region may be actioned **once** — a
     *     second action on the same region is a contradiction, not a refinement;
     *  2. a rename target must be a name the board knows, so the reply cannot invent panels;
     *  3. after the frame's actions the surviving names must be **distinct** — the whole point of a tag
     *     is that it means one panel;
     *  4. ...and must run in the board's own top-to-bottom order down the frame. Renames are applied
     *     simultaneously, so two panels may legitimately swap names; what may not happen is the order
     *     coming out reversed.
     */
    private fun applyActions(
        byFrame: Map<Int, AuditCanvas>,
        actions: Map<Int, List<Action>>,
        boardOrder: List<String>
    ): AuditOutcome {
        val rank = boardOrder.withIndex().associate { (i, label) -> label to i }
        val tagsBefore = byFrame.values.flatMap { c -> c.regions.map { it.label } }.toSet().size

        var proposed = 0
        var framesActioned = 0
        var renamed = 0
        var removed = 0
        var rejected = 0
        var emptied = 0
        var thinned = 0
        val rejectWhy = LinkedHashMap<String, Int>()
        val log = mutableListOf<String>()
        fun refuse(reason: String, n: Int) {
            rejected += n
            rejectWhy[reason] = (rejectWhy[reason] ?: 0) + n
        }

        for (frame in actions.keys.sorted()) {
            val acts = actions[frame] ?: continue
            proposed += acts.size
            framesActioned++
            val canvas = byFrame[frame]
            if (canvas == null) {
                refuse("no such frame", acts.size)
                continue
            }

            val here = canvas.regions.associateBy { it.label }
            val renames = LinkedHashMap<String, String>()
            val drops = LinkedHashSet<String>()
            var bad = 0
            val pending = mutableListOf<String>()
            for (a in acts) {
                when {
                    a.region !in here -> { bad++; rejectWhy.merge("no such region on that frame", 1, Int::plus) }
                    a.region in renames || a.region in drops ->
                        { bad++; rejectWhy.merge("region actioned twice", 1, Int::plus) }
                    a.kind == "remove" -> {
                        drops.add(a.region)
                        pending.add("f%06d  remove %-3s %s".format(frame, a.region, a.why))
                    }
                    a.kind == "rename" -> when {
                        a.to !in rank -> { bad++; rejectWhy.merge("rename to unknown name", 1, Int::plus) }
                        a.to == a.region -> { bad++; rejectWhy.merge("rename to itself", 1, Int::plus) }
                        else -> {
                            renames[a.region] = a.to
                            pending.add("f%06d  rename %s -> %-3s %s".format(frame, a.region, a.to, a.why))
                        }
                    }
                    else -> { bad++; rejectWhy.merge("unknown action '${a.kind.take(12)}'", 1, Int::plus) }
                }
            }
            rejected += bad
            if (renames.isEmpty() && drops.isEmpty()) continue

            // Rules 3 and 4, on the frame as a whole. The regions are in reading order, so the
            // surviving labels are already top to bottom.
            val kept = canvas.regions.filter { it.label !in drops }
            val finalLabels = kept.map { renames[it.label] ?: it.label }
            val n = acts.size - bad
            if (finalLabels.toSet().size != finalLabels.size) {
                refuse("would repeat a name on one frame", n)
                continue
            }
            val ranks = finalLabels.mapNotNull { rank[it] }
            if (ranks.zipWithNext().any { (a, b) -> b <= a }) {
                refuse("would break the board's order", n)
                continue
            }

            log += pending
            kept.forEachIndexed { i, region ->
                if (region.label != finalLabels[i]) {
                    region.label = finalLabels[i]
                    renamed++
                }
            }
            removed += canvas.regions.size - kept.size
            canvas.regions = kept.toMutableList()
            if (kept.isEmpty()) emptied++ else if (kept.size < MIN_REGIONS_EXPECTED) thinned++
        }

        val tagsAfter = byFrame.values.flatMap { c -> c.regions.map { it.label } }.toSet().size
        return AuditOutcome(
            framesActioned = framesActioned,
            proposed = proposed,
            renamed = renamed,
            removed = removed,
            rejected = rejected,
            rejectWhy = rejectWhy,
            tagsBefore = tagsBefore,
            tagsAfter = tagsAfter,
            emptied = emptied,
            thinned = thinned,
            log = log
        )
    }
}
