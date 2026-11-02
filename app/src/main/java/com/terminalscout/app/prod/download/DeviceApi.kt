package com.terminalscout.app.prod.download

import android.content.Context
import android.util.Log
import com.terminalscout.app.prod.BuildConfig
import com.terminalscout.app.prod.upload.UserStore
import com.terminalscout.app.prod.config.DeviceConfig
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * The Device API client — the ONLY thing in the app that talks to our own server, and what replaced
 * direct Azure Storage access.
 *
 * ### What this exists to remove
 * The app used to embed the storage **account key** and mint its own SAS on-device (`AzureBlobAuth`,
 * now deleted). That key is recoverable from any APK, and it grants the whole account: every container,
 * read and write and delete, forever. Now the app embeds only [BuildConfig.FLEET_SECRET], which buys
 * nothing except short-lived URLs scoped to one blob — and can be revoked server-side.
 *
 * ### The bytes never come here
 * Each call returns a **URL**, not a transfer. The device then talks to Azure directly, so this API
 * does not scale with video size and a 100 MB clip costs it one small JSON response.
 *
 * ### The four endpoints
 * | | |
 * |---|---|
 * | [token] | fleet secret → bearer token, cached for its hour |
 * | [artifactUpload] | one write-only URL for one artifact (15 min) |
 * | [completeRun] | declare what the run produced, so gaps are detectable |
 * | [modelDownload] | one read-only URL for one model file (1 hour) |
 * | [stations] | the fuel-station mapping, stored on device and refreshed per run |
 * | [config] | the fetched tunables — see `config/DeviceConfig` |
 *
 * ### Retry contract
 * Every call returns [ApiResult], which encodes the server's own rule: `4xx` except `429` is
 * [ApiResult.Permanent] (will never succeed as sent), while `429`, `5xx` and transport errors are
 * [ApiResult.Transient]. `DebugUploadWorker` already branched on exactly this distinction — getting it
 * backwards would either wedge the queue behind a poison artifact or silently discard good data.
 *
 * A `401` is handled here rather than by callers: the token is refreshed and the request retried
 * **once**. A second `401` means the fleet secret itself is wrong, so it is reported as permanent
 * instead of looping.
 *
 * ### Logging
 * Signed URLs are never logged in full — the `sig` parameter is a live credential until it expires, so
 * everything goes through [redact].
 */
object DeviceApi {

    private const val TAG = "DeviceApi"

    /** Renew this long before the server's stated expiry, so a token can't die mid-upload. */
    private val REFRESH_MARGIN_MS: Long get() = DeviceConfig.long("setup.device_api.token_refresh_margin_ms", 5 * 60 * 1000L)

    private val JSON = "application/json; charset=utf-8".toMediaType()

    /** True when the app was built with an API base URL and a fleet secret. */
    val isConfigured: Boolean
        get() = BuildConfig.DEVICE_API_BASE_URL.isNotBlank() && BuildConfig.FLEET_SECRET.isNotBlank()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /** What a call produced, in the three shapes a caller must tell apart. */
    sealed interface ApiResult<out T> {
        data class Ok<T>(val value: T) : ApiResult<T>

        /** Worth trying again later: `429`, `5xx`, or the network being unavailable. */
        data object Transient : ApiResult<Nothing>

        /** Malformed, unauthorised or unknown — identical retries would fail identically. */
        data class Permanent(val code: Int, val detail: String) : ApiResult<Nothing>
    }

    /** A write-only grant for ONE artifact. [uploadUrl] is a live credential — do not log it. */
    data class UploadGrant(
        val uploadUrl: String,
        val blobPath: String,
        val requiredHeaders: Map<String, String>
    )

    /** A read-only grant for ONE model file, with the size needed for progress and skip decisions. */
    data class DownloadGrant(val downloadUrl: String, val sizeBytes: Long)

    /** The server's reconciliation of a run: what it expected against what actually arrived. */
    data class RunCompletion(
        val complete: Boolean,
        val expectedCount: Int,
        val receivedCount: Int,
        val missing: List<String>
    )

    // ---- token ---------------------------------------------------------------------------- //

    private val tokenLock = Mutex()
    private var cachedToken: String? = null
    private var tokenExpiresAtMs = 0L

    /**
     * The cached bearer token, fetched on first use and reused for its full hour.
     *
     * Deliberately NOT one token per upload: a run stages dozens of artifacts, and a token call before
     * each would be pure overhead on a link already carrying the payload. The lock means concurrent
     * callers wait for one fetch rather than racing to mint several.
     */
    private suspend fun token(context: Context, forceRefresh: Boolean): ApiResult<String> =
        tokenLock.withLock {
            val cached = cachedToken
            if (!forceRefresh && cached != null && System.currentTimeMillis() < tokenExpiresAtMs) {
                return@withLock ApiResult.Ok(cached)
            }
            if (!isConfigured) {
                return@withLock ApiResult.Permanent(0, "no API base URL / fleet secret configured")
            }

            val body = JSONObject()
                .put("deviceId", UserStore(context).deviceId())
                .put("deviceSecret", BuildConfig.FLEET_SECRET)
                .toString()
            val request = Request.Builder()
                .url("${BuildConfig.DEVICE_API_BASE_URL}/v1/auth/token")
                .post(body.toRequestBody(JSON))
                .build()

            when (val res = execute("auth/token", request) { it.optString("access_token") }) {
                is ApiResult.Ok -> {
                    if (res.value.isEmpty()) {
                        return@withLock ApiResult.Permanent(200, "no access_token in response")
                    }
                    // Trust the server's own lifetime, minus a margin; fall back to an hour if absent.
                    cachedToken = res.value
                    tokenExpiresAtMs = System.currentTimeMillis() +
                        (lastExpiresInSeconds.takeIf { it > 0 } ?: 3600) * 1000L - REFRESH_MARGIN_MS
                    val mins = (tokenExpiresAtMs - System.currentTimeMillis()) / 60_000
                    Log.d(TAG, "token acquired, reusing for ${mins}min")
                    ApiResult.Ok(res.value)
                }
                is ApiResult.Permanent -> {
                    // 401 here is the fleet secret being wrong — nothing a retry can fix.
                    Log.e(TAG, "token refused (HTTP ${res.code}): ${res.detail}")
                    cachedToken = null
                    res
                }
                ApiResult.Transient -> {
                    cachedToken = null
                    res
                }
            }
        }

    /** `expires_in` from the most recent token response, read out of band by [token]. */
    @Volatile
    private var lastExpiresInSeconds: Int = 0

    // ---- endpoints ------------------------------------------------------------------------ //

    /**
     * Endpoint 2 — a write-only URL for `<runId>/<artifactPath>`.
     *
     * Call it immediately before the PUT and never cache the result: the URL lives 15 minutes, so an
     * outbox item retried an hour later must ask again.
     */
    suspend fun artifactUpload(
        context: Context,
        runId: String,
        artifactPath: String
    ): ApiResult<UploadGrant> =
        authed(context, "artifacts/$artifactPath", build = { base ->
            Request.Builder()
                .url("$base/v1/runs/${encodeSegment(runId)}/artifacts/${encodePath(artifactPath)}")
                .post(ByteArray(0).toRequestBody(null))
                .build()
        }) { json ->
            val headers = mutableMapOf<String, String>()
            json.optJSONObject("required_headers")?.let { req ->
                req.keys().forEach { k -> headers[k] = req.optString(k) }
            }
            UploadGrant(
                uploadUrl = json.optString("upload_url"),
                blobPath = json.optString("blob_path"),
                requiredHeaders = headers
            )
        }

    /**
     * Endpoint 3 — declare every artifact the run produced, including ones that never uploaded.
     *
     * That inclusion is the whole point: without a declared manifest the server cannot tell "produced
     * 14, all arrived" from "produced 14, 11 arrived". A non-empty [RunCompletion.missing] is a
     * successful `200` reporting a gap, not a failure.
     */
    suspend fun completeRun(
        context: Context,
        runId: String,
        artifacts: List<Pair<String, Long>>
    ): ApiResult<RunCompletion> {
        val arr = JSONArray()
        for ((path, size) in artifacts) {
            arr.put(JSONObject().apply {
                put("path", path)
                if (size > 0) put("sizeBytes", size)
            })
        }
        val body = JSONObject()
            .put("artifacts", arr)
            .put("deviceId", UserStore(context).deviceId())
            .toString()

        return authed(context, "runs/$runId/complete", build = { base ->
            Request.Builder()
                .url("$base/v1/runs/${encodeSegment(runId)}/complete")
                .post(body.toRequestBody(JSON))
                .build()
        }) { json ->
            val missing = json.optJSONArray("missing")
            RunCompletion(
                complete = json.optBoolean("complete"),
                expectedCount = json.optInt("expected_count"),
                receivedCount = json.optInt("received_count"),
                missing = (0 until (missing?.length() ?: 0)).map { missing!!.optString(it) }
            )
        }
    }

    /**
     * Endpoint 4 — a read-only URL for one model, named `<container>/<blob>` (e.g.
     * `yolo/price-region-002.tflite`).
     *
     * This is the endpoint that lets the storage key leave the APK entirely: the downloader used the
     * same key as the uploader, so brokering uploads alone would have left it in place.
     */
    suspend fun modelDownload(context: Context, name: String): ApiResult<DownloadGrant> =
        authed(context, "models/$name", build = { base ->
            Request.Builder()
                .url("$base/v1/models/${encodePath(name)}")
                .get()
                .build()
        }) { json ->
            DownloadGrant(
                downloadUrl = json.optString("download_url"),
                sizeBytes = json.optLong("size_bytes", -1L)
            )
        }

    /**
     * The station mapping, returned as the raw JSON body so the caller can store it verbatim.
     *
     * Deliberately NOT parsed here: [com.terminalscout.app.prod.upload.StationDirectory] persists exactly what
     * arrived and parses on read, so the stored copy is the server's document rather than this build's
     * interpretation of it. A field added server-side survives on disk even if this version ignores it.
     */
    suspend fun stations(context: Context): ApiResult<String> =
        authed(context, "stations", build = { base ->
            Request.Builder().url("$base/v1/stations").get().build()
        }) { json -> json.toString() }

    /**
     * The device configuration document, returned as JSON so the caller stores what arrived rather than
     * this build's reading of it.
     *
     * Same reasoning as [stations]: `config/DeviceConfig` validates and applies it, and a field added
     * server-side survives on disk even where this version ignores it.
     */
    suspend fun config(context: Context): ApiResult<String> =
        authed(context, "config", build = { base ->
            Request.Builder().url("$base/v1/config").get().build()
        }) { json -> json.toString() }

    // ---- plumbing ------------------------------------------------------------------------- //

    /**
     * Runs an authenticated request, refreshing the token and retrying **once** on `401`.
     *
     * Centralised here so no caller has to remember the rule. The retry is capped at one: a second
     * `401` means the fleet secret is wrong rather than the token being stale, and looping on it would
     * hammer the endpoint with a credential that can never work.
     */
    private suspend fun <T> authed(
        context: Context,
        what: String,
        build: (base: String) -> Request,
        parse: (JSONObject) -> T
    ): ApiResult<T> {
        var refreshed = false
        while (true) {
            val bearer = when (val t = token(context, forceRefresh = refreshed)) {
                is ApiResult.Ok -> t.value
                is ApiResult.Permanent -> return t
                ApiResult.Transient -> return ApiResult.Transient
            }
            val request = build(BuildConfig.DEVICE_API_BASE_URL).newBuilder()
                .header("Authorization", "Bearer $bearer")
                .build()

            val result = execute(what, request, parse)
            if (result is ApiResult.Permanent && result.code == 401 && !refreshed) {
                Log.d(TAG, "$what got 401 — refreshing token and retrying once")
                refreshed = true
                continue
            }
            return result
        }
    }

    /** One HTTP round trip, mapped onto [ApiResult] by the retry contract. */
    private fun <T> execute(what: String, request: Request, parse: (JSONObject) -> T): ApiResult<T> =
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                when {
                    response.isSuccessful -> try {
                        val json = JSONObject(body)
                        lastExpiresInSeconds = json.optInt("expires_in", lastExpiresInSeconds)
                        ApiResult.Ok(parse(json))
                    } catch (e: Exception) {
                        // A 200 we cannot read is not worth retrying — it will parse the same next time.
                        Log.e(TAG, "$what: unreadable response body", e)
                        ApiResult.Permanent(response.code, "unparseable response")
                    }
                    // 429 is a rate limit, not a rejection: the same request works once we back off.
                    response.code == 429 || response.code >= 500 -> {
                        Log.w(TAG, "$what: HTTP ${response.code} — will retry")
                        ApiResult.Transient
                    }
                    else -> {
                        Log.w(TAG, "$what: HTTP ${response.code} — ${body.take(200)}")
                        ApiResult.Permanent(response.code, body.take(200))
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "$what: ${e.javaClass.simpleName} — will retry")
            ApiResult.Transient
        }

    /** Percent-encodes one path SEGMENT (slashes included — a run id must not contain any). */
    private fun encodeSegment(segment: String): String =
        segment.replace(" ", "%20").replace("/", "%2F")

    /**
     * Encodes a multi-segment path, KEEPING the slashes.
     *
     * Both forms were checked against the live endpoint — raw slashes and fully percent-encoded both
     * return the same `artifact_path` — so the readable one is used, with only spaces escaped. Our own
     * paths are otherwise made of blob-safe characters (alnum, dot, underscore, hyphen).
     */
    private fun encodePath(path: String): String = path.replace(" ", "%20")

    /** Strips the `sig` value so a URL can be logged without logging a live credential. */
    fun redact(url: String): String = url.replace(Regex("(sig=)[^&]*"), "$1<redacted>")
}
