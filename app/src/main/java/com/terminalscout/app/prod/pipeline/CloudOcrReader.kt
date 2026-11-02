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
 * Thin client for the Azure AI Foundry chat-completions endpoint: one user message carrying a prompt
 * and any number of images, in, the model's reply text out.
 *
 * It knows nothing about price boards. Its one caller is [ConsolidationReviewer], which uses it to
 * have a cloud model check Stage 5's consolidated answer against the board images.
 *
 * ### Why one request with every image
 * All the canvases go in a single call, because **Stage 3's box tags are stable across frames** — box
 * `B` is the same physical panel in every image. So a model shown all of them can do what no
 * single-image read can: resolve a panel that was mid-refresh in one frame from the frames where it
 * was lit.
 *
 * ### Failure is never fatal
 * No key, no network, a timeout, an HTTP error, an unparseable body — every one of them logs and
 * returns null, and the pipeline keeps its own answer. A cross-check that can take the run down with
 * it is worse than no cross-check.
 *
 * ### On the API key
 * [BuildConfig.AZURE_AI_KEY] comes from `local.properties`, so it stays out of git — but it ships
 * inside the APK, where it is a plain string in the dex and extractable in seconds by anyone holding
 * the file. There is no way to change that: obfuscation, native storage and encryption all reduce to
 * hiding a key next to the thing that unlocks it. Unlike the storage key this app already ships, this
 * one bills per token against a chat model, so the exposure is **financial** rather than a debug
 * container. Treat a hard spend cap on the deployment as mandatory, and a server-side proxy as the
 * actual fix.
 */
class CloudOcrReader {

    /** True when a key was configured at build time. */
    val isEnabled: Boolean get() = BuildConfig.AZURE_AI_KEY.isNotBlank()

    private val client by lazy {
        OkHttpClient.Builder()
            // Generous: the request carries several megabytes of base64 images and the model is asked
            // to reason over all of them before answering.
            //
            // The read timeout is [TIMEOUT_S], matching Stage 3's two Responses calls. It was 240 s,
            // which was the shortest of the four cloud calls in the pipeline and sat on the one carrying
            // the most images — up to fourteen canvases of one board. A timeout here is not a degraded
            // review, it is NO review: this call has no retry, so a slow reply means the consolidated
            // answer ships unchecked, and since the stability drop was removed from Stage 5 the review
            // is the only thing left that can fill a blank price or correct a wrong one.
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .build()
    }

    // `readBoxDigits` used to sit here: one canvas per call, image-then-text, deliberately shaped
    // to match the on-device reader it stood in for. Nothing called it. Stage 4 reads through
    // [BatchOcrReader] — several canvases per request, each labelled — and this class's only live job
    // is [complete], which the Stage 5 review uses. A reader kept "in case" is a reader nobody runs.

    /**
     * Sends [prompt] plus every image in [canvasPaths] as one user message and returns the model's
     * reply text, or **null** on any failure — no key, unreadable images, HTTP error, timeout,
     * unparseable envelope. The caller decides what to do without it; nothing here throws.
     *
     * Runs on IO.
     */
    suspend fun complete(
        prompt: String,
        canvasPaths: List<String>,
        imagesFirst: Boolean = false
    ): String? =
        withContext(Dispatchers.IO) {
            if (!isEnabled) {
                Log.d(TAG, "no API key configured — cloud review disabled")
                return@withContext null
            }
            val images = canvasPaths.mapNotNull { path ->
                val f = File(path)
                if (!f.isFile) {
                    Log.w(TAG, "canvas missing, not sending: $path")
                    null
                } else {
                    runCatching { Base64.encodeToString(f.readBytes(), Base64.NO_WRAP) }
                        .onFailure { Log.w(TAG, "could not read $path", it) }
                        .getOrNull()
                }
            }
            if (images.isEmpty()) {
                Log.w(TAG, "no readable canvases — skipping the cloud call")
                return@withContext null
            }

            val body = buildRequest(prompt, images, imagesFirst)
            val approxMb = body.length / 1_048_576.0
            Log.d(TAG, "sending ${images.size} canvas(es) to ${BuildConfig.AZURE_AI_MODEL} " +
                "(%.1f MB)".format(approxMb))

            val started = System.currentTimeMillis()

            // RETRIED, up to [REQUEST_ATTEMPTS], [RETRY_DELAY_MS] apart — the same policy the Stage 4
            // reader uses, and for a stronger reason.
            //
            // This call had one attempt. That was survivable while Stage 5's consensus was expected to
            // produce the answer and the review merely checked it; it is not survivable now. Measured on
            // a real clip: the consolidator blanked BOTH prices on corroboration grounds and the review
            // filled both, so every price that shipped came from this call. A single 500 here is an empty
            // board, and the run has already spent ten minutes of frame extraction, detection, prep and
            // three OCR requests by the time it gets here.
            //
            // A 5xx or 429 is retried: the service faltering, and the same request may work in a moment.
            // A 4xx is NOT — it is an answer, and it will be the same answer forever.
            for (attempt in 1..REQUEST_ATTEMPTS) {
                if (attempt > 1) {
                    Log.w(TAG, "retrying the review (attempt $attempt/$REQUEST_ATTEMPTS)")
                    delay(RETRY_DELAY_MS)
                }
                try {
                    val request = Request.Builder()
                        .url(BuildConfig.AZURE_AI_ENDPOINT)
                        .header("Authorization", "Bearer ${BuildConfig.AZURE_AI_KEY}")
                        .header("Content-Type", JSON_MEDIA_TYPE.toString())
                        .post(body.toRequestBody(JSON_MEDIA_TYPE))
                        .build()

                    val outcome = client.newCall(request).execute().use { response ->
                        val text = response.body?.string().orEmpty()
                        if (!response.isSuccessful) {
                            // The body carries the provider's reason (quota, bad deployment, bad key).
                            Log.e(TAG, "HTTP ${response.code} — ${text.take(400)}")
                            return@use if (isRetryable(response.code)) RETRY else null
                        }
                        val content = contentOf(text)
                        if (content == null) {
                            // A successful envelope with no content is a malformed answer, not a
                            // transport fault. Retrying would send the identical request and get the
                            // identical shape back.
                            Log.e(TAG, "no message content in response: ${text.take(400)}")
                            return@use null
                        }
                        Log.d(TAG, "cloud replied in ${System.currentTimeMillis() - started}ms, " +
                            "${content.length} chars")
                        content
                    }
                    if (outcome !== RETRY) return@withContext outcome
                } catch (e: Exception) {
                    // A timeout or a dropped connection. Worth another attempt, unlike a 4xx.
                    Log.e(TAG, "cloud call failed after ${System.currentTimeMillis() - started}ms", e)
                }
            }
            Log.e(TAG, "review failed after $REQUEST_ATTEMPTS attempt(s) — the board ships unreviewed")
            null
        }

    /** Worth another attempt: the service faltering, never a malformed request. */
    private fun isRetryable(code: Int): Boolean = code == 408 || code == 429 || code >= 500

    /** Builds the chat-completions body: one user message, the prompt, then every image. */
    private fun buildRequest(prompt: String, base64Images: List<String>, imagesFirst: Boolean): String {
        val textPart = JSONObject().apply {
            put("type", "text")
            put("text", prompt)
        }
        val imageParts = base64Images.map { b64 ->
            JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().apply {
                    // Stage 3 writes lossless PNG; JPEG would ring around the digit edges.
                    put("url", "data:image/png;base64,$b64")
                })
            }
        }
        // Order matters to a vision model, and the flag is kept because the two arrangements are not
        // interchangeable.
        //
        // Images first is how a read prompt is fed: the model sees the canvas and is then told what to
        // do with it. The consolidation review is the other way round — its prompt embeds the
        // consolidated JSON and refers forward to "the images", so the text has to come first for that
        // sentence to mean anything.
        val content = JSONArray().apply {
            if (imagesFirst) {
                imageParts.forEach { put(it) }
                put(textPart)
            } else {
                put(textPart)
                imageParts.forEach { put(it) }
            }
        }
        return JSONObject().apply {
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", content)
            }))
            put("model", BuildConfig.AZURE_AI_MODEL)
            // NOT `max_tokens` — this model rejects that outright:
            //   "Unsupported parameter: 'max_tokens' is not supported with this model.
            //    Use 'max_completion_tokens' instead." (HTTP 400, invalid_request_error)
            put("max_completion_tokens", MAX_COMPLETION_TOKENS)
        }.toString()
    }

    /** Digs `choices[0].message.content` out of the response, or null if it isn't there. */
    private fun contentOf(responseBody: String): String? = try {
        JSONObject(responseBody)
            .optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            ?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        Log.e(TAG, "could not parse response envelope", e)
        null
    }

    companion object {
        private const val TAG = "CloudOcrReader"
        /**
         * The whole reply budget for the cross-check read — thinking included, since this model
         * bills both out of one pot and rejects `max_tokens` outright (see the call site).
         *
         * Raised 10k → 20k on 2026-09-18. Not from an observed truncation here, but this reader is
         * the one whose answer is used to ARGUE WITH the on-device consolidation: it rescued two
         * blanked rows on a real board in one run and left a hallucinated pair standing on a tree in
         * another. A reviewer that runs out of budget mid-thought does not report a weaker opinion,
         * it reports none — and a missing review is silently indistinguishable from a review that
         * changed nothing. Worth the headroom for that reason alone.
         */
        private val MAX_COMPLETION_TOKENS: Int get() = DeviceConfig.int("ocr.cloud_ocr.max_completion_tokens", 20_000)

        /**
         * How long to wait for the reply, in seconds.
         *
         * 900, the same as `BoardAudit` and `BoardLayoutReader`. Those two settled on it for the same
         * reason that applies here: a reasoning model handed several megabytes of images takes as long
         * as it takes, and the pipeline is already a minutes-long job on a parked truck — there is
         * nothing to be gained by giving up early, and a run whose review timed out has spent every
         * other stage's cost for an unverified answer.
         */
        private val TIMEOUT_S: Long get() = DeviceConfig.long("ocr.cloud_ocr.timeout_s", 900L)

        /**
         * How many times the review may be sent.
         *
         * Three, matching `LunaOcrReader`. The cost of an extra attempt is one more call against a
         * board whose frames, crops and canvases are already on disk; the cost of not retrying is every
         * earlier stage's work shipping an unverified answer.
         */
        private val REQUEST_ATTEMPTS: Int get() = DeviceConfig.int("ocr.cloud_ocr.request_attempts", 3)

        /** Pause between attempts. Long enough for a 429's window to pass, short enough not to stall. */
        private val RETRY_DELAY_MS: Long get() = DeviceConfig.long("ocr.cloud_ocr.retry_delay_ms", 5_000L)

        /**
         * Sentinel meaning "this attempt failed in a way worth repeating".
         *
         * Needed because the outcome of one attempt is three-valued — a reply, a permanent refusal, or a
         * transient fault — and `String?` can only carry two. Identity-compared, never returned.
         */
        private val RETRY = String()
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
