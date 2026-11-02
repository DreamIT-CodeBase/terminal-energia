package com.terminalscout.app.prod.pipeline

import android.util.Base64
import android.util.Log
import com.terminalscout.app.prod.BuildConfig
import com.terminalscout.app.prod.config.DeviceConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Stage 4's reader on the **Responses** API — using the configured Foundry deployment.
 *
 * The prompt, the canvases, the `Image N:` labels and the batching are all [BatchOcrReader]'s and not
 * this class's, so only the ENVELOPE lives here. That split is what would make a second reader
 * comparable to this one on the same clip rather than a second code path that drifted; there was one —
 * `ClaudeOcrReader`, on the Anthropic Messages endpoint — and it is gone, because a switch nobody
 * flipped is a branch nobody tested.
 *
 * ### Reasoning effort
 * [EFFORT] is `xhigh` because the errors left at this stage are perceptual — a faint top bar, a
 * washed-out panel — so the open question is whether more deliberation on the same pixels resolves them.
 *
 * ### The budget has to cover the thinking
 * A Responses model is billed for its deliberation out of the SAME output budget as its answer, so the
 * allowance is added ON TOP of the reply budget. Without it a long think truncates the JSON and loses
 * every canvas at once rather than one of them — which is the same bargain, for the same reason, that
 * Steps F and G make.
 *
 * ### Failure handling, identical to the other reader
 * Three attempts, five seconds apart. Retried: a 5xx or 429 — the service faltering, and the same
 * request may work in a moment — a throw, and **a 200 whose reply cannot be used**, which means a body
 * that is not JSON, a body carrying no `output_text`, or one that attributes no image.
 *
 * Not retried: a 4xx. It is an answer, and it will be the same answer forever.
 *
 * That line is drawn by *what another attempt could change*, not by whether the server responded. All
 * three unusable-reply cases arrive on a 200 and every one of them is a fault of the attempt — a body
 * truncated by a connection that dropped, a captive portal's own page, a model that spent its whole
 * allowance thinking. They used to return **refused** on the first occurrence, which skipped the
 * remaining attempts and reported a dropped connection to the operator as a configuration fault.
 *
 * Exhausting the attempts reports **not reached**, so the run is parked and read later rather than
 * written out as a board of blanks.
 */
class LunaOcrReader : BatchOcrReader {

    override val isEnabled: Boolean
        get() = BuildConfig.AZURE_AI_KEY.isNotBlank() && BuildConfig.AZURE_RESPONSES_ENDPOINT.isNotBlank()

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            // The request carries a dozen-plus 640x640 canvases as base64.
            .writeTimeout(120, TimeUnit.SECONDS)
            // Generous: the model reasons over every canvas before it answers.
            .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .build()
    }

    override suspend fun readBoxDigitsBatch(canvasPaths: List<String>): BatchRead =
        withContext(Dispatchers.IO) {
            // Nothing was asked for, so nothing was lost: READ over an empty list, not a failure.
            if (canvasPaths.isEmpty()) return@withContext BatchRead(emptyList(), ReadOutcome.READ)
            // REFUSED, not READ. There is no key, so this board was never looked at — and a run that
            // treats that as a finished read uploads an empty payload and deletes the clip behind it.
            if (!isEnabled) {
                Log.e(TAG, "no key or Responses endpoint configured — the board CANNOT be read")
                return@withContext BatchRead(
                    canvasPaths.map { BatchOcrReader.EMPTY }, ReadOutcome.REFUSED
                )
            }

            // Encode first: a canvas missing from disk must not silently shift every later image's
            // number, so the request is built only from what actually loaded and the reply is mapped back
            // through this list.
            val loaded = canvasPaths.mapIndexedNotNull { index, path ->
                val file = File(path)
                if (!file.isFile) {
                    Log.w(TAG, "canvas missing, not sending: $path")
                    return@mapIndexedNotNull null
                }
                runCatching { Base64.encodeToString(file.readBytes(), Base64.NO_WRAP) }
                    .onFailure { Log.w(TAG, "could not read $path", it) }
                    .getOrNull()?.let { Triple(index, file.name, it) }
            }
            if (loaded.isEmpty()) {
                // Also REFUSED: there is no request to send, so nothing read this board. A canvas that
                // vanished between Stage 3 writing it and this reading it is a fault of ours, and one
                // that must not be reported as a board with no prices on it.
                Log.e(TAG, "no canvas could be read from disk — nothing to send")
                return@withContext BatchRead(
                    canvasPaths.map { BatchOcrReader.EMPTY }, ReadOutcome.REFUSED
                )
            }

            val started = System.currentTimeMillis()
            var lastFailure: String? = null
            var lastThrown: Exception? = null

            for (attempt in 1..REQUEST_ATTEMPTS) {
                if (attempt > 1) {
                    Log.w(TAG, "retrying the batched read (attempt $attempt/$REQUEST_ATTEMPTS)")
                    delay(RETRY_WAIT_MS)
                }
                try {
                    val request = Request.Builder()
                        .url(BuildConfig.AZURE_RESPONSES_ENDPOINT)
                        .header("api-key", BuildConfig.AZURE_AI_KEY)
                        .post(buildRequest(loaded.map { it.third }).toRequestBody(JSON))
                        .build()

                    client.newCall(request).execute().use { response ->
                        val body = response.body?.string().orEmpty()
                        if (!response.isSuccessful) {
                            Log.e(TAG, "HTTP ${response.code} — ${body.take(400)}")
                            if (response.code == 429 || response.code >= 500) {
                                lastFailure = "HTTP ${response.code}"
                                return@use
                            }
                            // THE 4xx. The server answered, so this used to report the board as
                            // READ with every canvas blank — a dead key, an exhausted quota or a wrong
                            // deployment name then shipped "no prices at this forecourt" for every clip
                            // in the fleet and deleted the footage behind it. It is a REFUSAL: nothing
                            // read this board, and a retry will be refused identically.
                            return@withContext BatchRead(
                                canvasPaths.map { BatchOcrReader.EMPTY }, ReadOutcome.REFUSED
                            )
                        }
                        // RETRIED, not refused.
                        //
                        // A 200 whose body will not parse is a TRANSPORT fault, not a configuration
                        // one: a response truncated by a connection that dropped mid-body, a proxy or
                        // captive portal substituting an HTML error page, a gateway returning its own
                        // page with a 200. None of those describes the request — the same request over
                        // a working connection parses fine — and all three are exactly what a truck
                        // driving between cells produces.
                        //
                        // Classifying it REFUSED was wrong twice over. It skipped the two remaining
                        // attempts, and REFUSED is the outcome `OcrStage` reports to the operator as
                        // "a configuration fault, not a connection one: the clip stays queued and will
                        // keep failing until the key, endpoint or model is fixed" — sending whoever
                        // read it to check a key that was never the problem.
                        val json = try {
                            JSONObject(body)
                        } catch (e: Exception) {
                            Log.e(TAG, "reply was not JSON: ${body.take(400)}")
                            lastFailure = "reply was not JSON"
                            return@use
                        }
                        if (json.optString("status") == "incomplete") {
                            val why = json.optJSONObject("incomplete_details")?.optString("reason") ?: "unknown"
                            Log.w(TAG, "reply INCOMPLETE ($why) — raise the reasoning allowance")
                        }
                        // Retried, for the same reason and one of its own: well-formed JSON carrying no
                        // `output_text` is most often the `incomplete` reply logged just above — the
                        // model spent its whole allowance on reasoning and emitted nothing. That is a
                        // fault of one attempt, not of the configuration: the next attempt is a fresh
                        // sampling of the same request and routinely answers.
                        val text = replyText(json)
                        if (text.isBlank()) {
                            Log.e(TAG, "no output_text in the reply: ${body.take(400)}")
                            lastFailure = "no output_text in the reply"
                            return@use
                        }

                        // "Image N" is 1-based over `loaded`, so N-1 indexes it; its own `first` carries
                        // the caller's original position.
                        val byImageNumber = BatchOcrReader.parseBatchFrames(text)
                        val out = MutableList(canvasPaths.size) { BatchOcrReader.EMPTY }
                        var attributed = 0
                        loaded.forEachIndexed { position, (originalIndex, name, _) ->
                            val boxes = byImageNumber[position + 1]
                            if (boxes == null) {
                                Log.w(TAG, "no entry for image ${position + 1} ($name) — leaving it unread")
                            } else {
                                attributed++
                                out[originalIndex] = BoxReading(text, boxes)
                                Log.d(
                                    TAG,
                                    "  image ${position + 1} $name: " +
                                        boxes.joinToString {
                                            "${it.label}=${it.digits.ifEmpty { "-" }}${if (it.legible) "" else "?"}"
                                        }
                                )
                            }
                        }
                        val usage = json.optJSONObject("usage")
                        Log.d(
                            TAG,
                            "batched ${loaded.size} canvas(es) in ${System.currentTimeMillis() - started}ms " +
                                "— $attributed attributed, ${usage?.optInt("input_tokens") ?: 0} in / " +
                                "${usage?.optInt("output_tokens") ?: 0} out " +
                                "(${usage?.optJSONObject("output_tokens_details")?.optInt("reasoning_tokens") ?: 0} thinking)"
                        )
                        // Not one image was attributed: the reply was truncated (the thinking ate the
                        // budget), or it carried no `frames` array this parser could use. Whatever the
                        // cause, no canvas was read — reporting READ here would put a board of blanks
                        // into the consensus and call the run finished.
                        //
                        // Retried too, and this is the same fault as the two above wearing a different
                        // hat: the output survived JSON parsing and then failed to CLEAN into readings.
                        // The cause named in the paragraph above — a truncated reply — is transient by
                        // definition, so refusing on the first one both skipped the retries and told
                        // the operator to go and check a key.
                        if (attributed == 0) {
                            Log.e(TAG, "the reply attributed NO image — retrying")
                            lastFailure = "the reply attributed no image"
                            return@use
                        }
                        return@withContext BatchRead(out, ReadOutcome.READ)
                    }
                } catch (e: Exception) {
                    // Thrown, not answered: DNS failure, no route, timeout. The read never happened.
                    lastThrown = e
                    lastFailure = "${e.javaClass.simpleName}: ${e.message}"
                }
            }
            Log.e(
                TAG,
                "batched read failed after $REQUEST_ATTEMPTS attempt(s) and " +
                    "${System.currentTimeMillis() - started}ms — last failure: $lastFailure",
                lastThrown
            )
            // Every attempt failed in a way worth repeating, and the budget is spent. Five ways to get
            // here now: a throw (DNS, no route, timeout), a 5xx or 429, a body that was not JSON, a
            // reply carrying no `output_text`, and a reply that attributed no image. The last three
            // used to return REFUSED from inside the loop, on the first occurrence.
            //
            // UNREACHABLE rather than REFUSED, because none of the five says anything is wrong with
            // the request: the same canvases sent again over a working connection read fine, which is
            // exactly what the clip staying on disk is for. REFUSED is reserved for the one fault a
            // retry cannot help — the 4xx above, where the server understood the request and declined
            // it, and where `OcrStage`'s "keep failing until the key, endpoint or model is fixed" is
            // the truth rather than a wild goose chase.
            BatchRead(canvasPaths.map { BatchOcrReader.EMPTY }, ReadOutcome.UNREACHABLE)
        }

    /**
     * One Responses request: `Image 1:` / image / `Image 2:` / image / … / instructions.
     *
     * Labels go BEFORE their image so each one names what follows, and the instructions come last so the
     * model has seen every canvas before being told the task — the same order the Anthropic reader, the
     * on-device reader and Steps F and G all use.
     */
    private fun buildRequest(base64Pngs: List<String>): String {
        val content = JSONArray()
        base64Pngs.forEachIndexed { i, b64 ->
            content.put(JSONObject().put("type", "input_text").put("text", "Image ${i + 1}:"))
            content.put(
                JSONObject()
                    .put("type", "input_image")
                    // Stage 3 writes lossless PNG; JPEG would ring around the digit edges.
                    .put("image_url", "data:image/png;base64,$b64")
                    .put("detail", "high")
            )
        }
        content.put(
            JSONObject().put("type", "input_text")
                .put("text", BatchOcrReader.batchPrompt(base64Pngs.size))
        )

        return JSONObject()
            .put("model", BuildConfig.AZURE_AI_MODEL)
            // The reply budget, plus an allowance for the thinking that is billed out of the same pot.
            .put(
                "max_output_tokens",
                BatchOcrReader.TOKENS_BASE +
                    base64Pngs.size * BatchOcrReader.TOKENS_PER_IMAGE +
                    REASONING_ALLOWANCE
            )
            .put("input", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
            .put("reasoning", JSONObject().put("effort", EFFORT))
            .toString()
    }

    /**
     * The answer text out of the Responses envelope.
     *
     * Only `output_text` inside a `message` counts. Reasoning items are deliberately NOT folded in: they
     * discuss the same digits and would hand the parser a second, contradictory set of numbers.
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

    private companion object {
        private const val TAG = "LunaOcrReader"

        /**
         * Reasoning effort. `xhigh` because what is left to get wrong here is perceptual, and effort is
         * the only knob that addresses that — it is also where this model's bill lives, so it is stated
         * rather than left to the deployment default.
         */
        const val EFFORT = "xhigh"

        /**
         * On top of the reply budget: the thinking is billed out of the same allowance.
         *
         * ### 50k, raised from 25k on 2026-09-18
         * Headroom, not a fix for anything observed: by the time this was raised the prompt change had
         * already taken the pressure off (see below), and the run after it truncated nothing. It is a
         * CAP, not a target — a reply that finishes costs what it costs, so the only thing a larger
         * ceiling buys is the tail, and the tail is where a whole batch is lost rather than one canvas.
         *
         * ### 25k, raised from 12k on 2026-09-18
         * At [EFFORT] `high` the thinking IS the budget — measured over two runs of the same clip it
         * took 98–99% of every reply (9,322 / 13,466 / 14,916 tokens of thinking against 150–200 tokens
         * of JSON). Against the old ceiling of 16,500 for a five-image batch, the largest successful
         * reply finished 1,411 tokens short of it and the next one did not finish at all:
         * `reply INCOMPLETE (max_output_tokens)`, which costs the whole batch — five canvases read for
         * nothing, since a truncated envelope carries no `output_text` to parse.
         *
         * So 12k was not a ceiling the work fitted under, it was the middle of the distribution.
         *
         * What actually removed the pressure was the Stage-4 prompt learning to say "this is not a
         * digit panel": the same canopy canvases that had cost 9,322–14,916 thinking tokens apiece came
         * back in 8–37 seconds once the reader was allowed to reject them, because most of that
         * thinking had been the model deliberating over noise it had been told was a price panel. The
         * allowance covers the pipeline's failures; the prompt is what stops there being so many.
         *
         * Counter-intuitively the expensive canvases are the ones with nothing on them: a crop of
         * foliage gives the model shapes to deliberate over and no digits to settle on, so it spends
         * far more than a legible board does. The allowance therefore has to cover the pipeline's
         * failures, not its successes.
         *
         * Raising this does not raise the bill for a reply that was already finishing — it is a cap,
         * not a target. What it does buy is the tail.
         */
        val REASONING_ALLOWANCE: Int get() = DeviceConfig.int("ocr.luna_ocr.reasoning_allowance", 50_000)

        val TIMEOUT_S: Long get() = DeviceConfig.long("ocr.luna_ocr.timeout_s", 600L)
        val REQUEST_ATTEMPTS: Int get() = DeviceConfig.int("ocr.luna_ocr.request_attempts", 3)
        val RETRY_WAIT_MS: Long get() = DeviceConfig.long("ocr.luna_ocr.retry_wait_ms", 5_000L)

        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
