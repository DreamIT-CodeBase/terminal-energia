package com.terminalscout.app.prod.upload

import android.content.Context
import android.util.Log
import com.terminalscout.app.prod.BuildConfig
import com.terminalscout.app.prod.config.DeviceConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Azure Maps `Timezone/byCoordinates` client — a Kotlin port of the `map-test/main.py` probe, kept
 * function-for-function so anything that script verified against the live endpoint holds here too.
 *
 * ### Call it through [AzureMapsStore], not directly
 * This object is the transport, and it calls out every time it is asked. The rate limit lives one level
 * up: [AzureMapsStore] keeps one answer per **service day** (03:00 Europe/Tallinn to 03:00) in a file,
 * and refreshes it once, from the scheduled [AzureMapsRefreshWorker] or from the first caller after the
 * boundary. Pipeline code should never reach past it — a per-run call here is exactly the thing the
 * store exists to prevent. Direct use is for the diagnostic path ([probe], [probeAll]) and tests.
 *
 * Nothing consumes the data yet; the eventual consumer is
 * [com.terminalscout.app.prod.pipeline.EnrichmentStage], which already holds the one input a lookup needs —
 * the capture-time [LocationFix].
 *
 * ### The two calls, and why only one of them is usually worth making
 * | | |
 * |---|---|
 * | [Options.BASIC] | timezone id, offsets, a reference UTC/local timestamp — **and sunrise/sunset** |
 * | [Options.ALL] | the same, plus `Countries`, `RepresentativePoint` and `TimeTransitions` (the DST switch dates for the surrounding years) |
 *
 * Verified against the live endpoint on 2026-08-23: that is the *only* difference. Sunrise and sunset
 * come back on the basic call too, under `TimeZones[].ReferenceTime.Sunrise/.Sunset`, so [sunTimes] and
 * [timezone] both use [Options.BASIC] and skip the heavier body. [probe] runs both, for the same reason
 * the Python script did: to capture what the endpoint actually returns.
 *
 * ### Locale is a real hazard here
 * The endpoint wants `query=58.595300,25.013600`. A device set to Estonian formats a Double with a
 * **comma** decimal separator, which would send `58,595300,25,013600` and get back a 400 that reads
 * like a server fault. Every number that goes into a URL here is formatted through [coordinate] with
 * [Locale.US]. Do not replace it with string interpolation.
 *
 * ### Failure is never fatal
 * No key, no network, a timeout, an HTTP error, an unparseable body — each returns a [Probe] with
 * `ok = false` and the detail in [Probe.error]. A sunrise time is enrichment; a run must not die for
 * want of one.
 *
 * ### On the subscription key
 * [BuildConfig.AZURE_MAPS_KEY] comes from `local.properties` (`azure.maps.key=...`), so it stays out of
 * git — but it ships inside the APK as a plain dex string, extractable in seconds by anyone holding the
 * file. Same exposure as [BuildConfig.AZURE_AI_KEY], and the same real fix: broker it through
 * [com.terminalscout.app.prod.download.DeviceApi] so the device holds nothing. Until then, scope the key to
 * the Timezone service and cap it. The key is never logged and never written to disk — everything that
 * records a URL goes through [redact], exactly as the Python script does.
 */
object AzureMapsTimezone {

    private const val TAG = "AzureMapsTz"

    private const val ENDPOINT = "https://atlas.microsoft.com/timezone/byCoordinates/json"
    private const val API_VERSION = "1.0"

    /** Geographic centre of Estonia — the Python script's default, useful as a smoke-test target. */
    const val DEFAULT_QUERY = "58.595300,25.013600"

    private val TIMEOUT_S: Long get() = DeviceConfig.long("setup.azure_maps_store.timezone_timeout_s", 30L)

    /** Which variant of the call to make. [ALL] appends `&options=all`. */
    enum class Options(val value: String?) {
        BASIC(null),
        ALL("all")
    }

    /** True when a subscription key was configured at build time. */
    val isConfigured: Boolean get() = BuildConfig.AZURE_MAPS_KEY.isNotBlank()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .build()
    }

    // ---- results ---------------------------------------------------------------------------- //

    /**
     * One round trip, in the same shape as the Python `record` dict — so a saved [Probe] and a saved
     * Python response are the same document.
     *
     * [requestUrl] is already redacted and safe to log or write to disk. [response] is the raw body,
     * kept verbatim rather than reduced to [extracted]: a field Azure adds later survives in the stored
     * copy even if this build ignores it.
     */
    data class Probe(
        val requestUrl: String,
        val ok: Boolean,
        val status: Int?,
        val elapsedMs: Long,
        val response: JSONObject?,
        val responseText: String?,
        val error: String?,
        val extracted: TimezoneInfo?
    ) {
        /** The probe as the JSON the Python script writes per call, for `-basic.json` / `-all.json`. */
        fun toJson(): JSONObject = JSONObject().apply {
            put("request_url", requestUrl)
            put("ok", ok)
            put("status", status ?: JSONObject.NULL)
            put("elapsed_ms", elapsedMs)
            put("response", response ?: JSONObject.NULL)
            responseText?.let { put("response_text", it) }
            put("error", error ?: JSONObject.NULL)
            put("extracted", extracted?.toJson() ?: JSONObject())
        }
    }

    /**
     * The fields worth keeping out of a successful response, read from `TimeZones[0]`.
     *
     * All nullable: a coordinate in the middle of an ocean returns an empty `TimeZones` array with a
     * `200`, which is a successful call that found nothing rather than an error.
     */
    data class TimezoneInfo(
        val timezoneId: String?,
        val abbreviation: String?,
        val standardOffset: String?,
        val daylightSavings: String?,
        val referenceWallTime: String?,
        val posixTz: String?,
        val sunrise: String?,
        val sunset: String?
    ) {
        /**
         * [standardOffset] plus [daylightSavings] in minutes, e.g. `02:00:00` + `01:00:00` → 180.
         *
         * The live endpoint returns `HH:MM:SS` with no sign on a positive offset (checked 2026-08-23),
         * NOT the `+02:00` form the timestamps use. [offsetToMinutes] reads both and drops the seconds,
         * which no real zone uses.
         */
        val effectiveOffsetMinutes: Int?
            get() {
                val base = offsetToMinutes(standardOffset) ?: return null
                return base + (offsetToMinutes(daylightSavings) ?: 0)
            }

        fun toJson(): JSONObject = JSONObject().apply {
            put("timezone_id", timezoneId ?: JSONObject.NULL)
            put("abbreviation", abbreviation ?: JSONObject.NULL)
            put("standard_offset", standardOffset ?: JSONObject.NULL)
            put("daylight_savings", daylightSavings ?: JSONObject.NULL)
            put("reference_wall_time", referenceWallTime ?: JSONObject.NULL)
            put("posix_tz", posixTz ?: JSONObject.NULL)
            put("sunrise", sunrise ?: JSONObject.NULL)
            put("sunset", sunset ?: JSONObject.NULL)
        }
    }

    /** Sunrise and sunset for the reference day, both ISO-8601 with an offset, as Azure returns them. */
    data class SunTimes(val sunrise: String, val sunset: String)

    // ---- key ------------------------------------------------------------------------------- //

    /**
     * The subscription key: an explicit [override] first, then the build-time key.
     *
     * The Python script's environment-variable and key-file steps are dropped — an Android app has
     * neither — leaving the two ends of that chain that still mean something: a caller-supplied key, or
     * the configured one. Returns null when nothing is configured, which every entry point treats as
     * "disabled" rather than throwing.
     */
    private fun resolveKey(override: String?): String? =
        override?.trim()?.takeIf { it.isNotEmpty() }
            ?: BuildConfig.AZURE_MAPS_KEY.trim().takeIf { it.isNotEmpty() }

    /** The URL with the subscription key masked — the only form allowed to be logged or stored. */
    fun redact(url: String, key: String?): String =
        if (key.isNullOrEmpty()) url else url.replace(key, "***")

    // ---- request --------------------------------------------------------------------------- //

    /** `58.595300,25.013600` from a lat/lon pair, locale-proof. See the class note on locale. */
    fun coordinate(latitude: Double, longitude: Double): String =
        String.format(Locale.US, "%.6f,%.6f", latitude, longitude)

    /** `58.595300,25.013600` from a GPS fix. */
    fun coordinate(fix: LocationFix): String = coordinate(fix.latitude, fix.longitude)

    /** The full request URL, key included — pass it through [redact] before it goes anywhere. */
    fun buildUrl(query: String, key: String, options: Options): String =
        ENDPOINT.toHttpUrl().newBuilder()
            .addQueryParameter("api-version", API_VERSION)
            .addQueryParameter("query", query)
            .addQueryParameter("subscription-key", key)
            .apply { options.value?.let { addQueryParameter("options", it) } }
            .build()
            .toString()

    /**
     * GETs [url] and returns the response or the error detail — never throws.
     *
     * A 4xx/5xx body is kept rather than discarded: Azure Maps names the actual problem there (bad key,
     * quota, malformed query) far better than the status code alone does.
     */
    suspend fun fetch(url: String, key: String?): Probe = withContext(Dispatchers.IO) {
        val safeUrl = redact(url, key)
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .get()
            .build()
        val started = System.nanoTime()

        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val ms = (System.nanoTime() - started) / 1_000_000
                val parsed = try {
                    JSONObject(body)
                } catch (e: Exception) {
                    Log.w(TAG, "response was not JSON (HTTP ${response.code})", e)
                    null
                }
                when {
                    !response.isSuccessful -> Probe(
                        requestUrl = safeUrl,
                        ok = false,
                        status = response.code,
                        elapsedMs = ms,
                        response = parsed,
                        responseText = if (parsed == null) body.take(2000) else null,
                        error = "HTTP ${response.code} ${response.message}",
                        extracted = null
                    )

                    else -> Probe(
                        requestUrl = safeUrl,
                        ok = parsed != null,
                        status = response.code,
                        elapsedMs = ms,
                        response = parsed,
                        responseText = if (parsed == null) body.take(2000) else null,
                        error = if (parsed == null) "response was not JSON" else null,
                        extracted = extract(parsed)
                    )
                }
            }
        } catch (e: Exception) {
            // Includes the offline case, which is normal for this app: a forecourt is often a dead spot.
            Probe(
                requestUrl = safeUrl,
                ok = false,
                status = null,
                elapsedMs = (System.nanoTime() - started) / 1_000_000,
                response = null,
                responseText = null,
                error = "network error: ${e.javaClass.simpleName}: ${e.message}",
                extracted = null
            )
        }
    }

    // ---- extraction ------------------------------------------------------------------------ //

    /**
     * Pulls [TimezoneInfo] out of a response body, or null when it carries no zone.
     *
     * Only `TimeZones[0]` is read. A coordinate can in principle sit on a boundary and return more than
     * one zone; the first is the one Azure ranks best, and a forecourt is never genuinely ambiguous. The
     * full array stays in [Probe.response] if that ever needs revisiting.
     */
    fun extract(response: JSONObject?): TimezoneInfo? {
        val zone = response?.optJSONArray("TimeZones")?.optJSONObject(0) ?: return null
        val reference = zone.optJSONObject("ReferenceTime") ?: JSONObject()
        return TimezoneInfo(
            timezoneId = zone.optStringOrNull("Id"),
            abbreviation = reference.optStringOrNull("Tag"),
            standardOffset = reference.optStringOrNull("StandardOffset"),
            daylightSavings = reference.optStringOrNull("DaylightSavings"),
            referenceWallTime = reference.optStringOrNull("WallTime"),
            posixTz = reference.optStringOrNull("PosixTz"),
            sunrise = reference.optStringOrNull("Sunrise"),
            sunset = reference.optStringOrNull("Sunset")
        )
    }

    /** The DST switch dates from an `options=all` response; empty on a basic one. */
    fun timeTransitions(response: JSONObject?): List<JSONObject> {
        val zone = response?.optJSONArray("TimeZones")?.optJSONObject(0) ?: return emptyList()
        val transitions = zone.optJSONArray("TimeTransitions") ?: return emptyList()
        return (0 until transitions.length()).mapNotNull { transitions.optJSONObject(it) }
    }

    // ---- entry points ---------------------------------------------------------------------- //

    /**
     * One lookup for an already-formatted [query], returning the whole [Probe] — or null when no key is
     * configured, which is the one case that is not a failure but an "off".
     *
     * Returns the probe rather than just [TimezoneInfo] so a caller that has to decide whether to retry
     * can see *why* it failed. [AzureMapsStore] is the caller that needs it; [timezone] is the shorthand
     * for everyone else.
     */
    suspend fun lookup(
        query: String,
        options: Options = Options.BASIC,
        key: String? = null
    ): Probe? {
        val subscriptionKey = resolveKey(key) ?: run {
            Log.d(TAG, "no Azure Maps key configured — lookup skipped")
            return null
        }
        return fetch(buildUrl(query, subscriptionKey, options), subscriptionKey).also {
            if (!it.ok) Log.w(TAG, "lookup($query) failed: ${it.error}")
        }
    }

    /**
     * The timezone for a coordinate, or null if the key is unset or the call failed.
     *
     * Uses [Options.BASIC] — see the class note: `options=all` adds nothing this returns.
     */
    suspend fun timezone(latitude: Double, longitude: Double, key: String? = null): TimezoneInfo? =
        lookup(coordinate(latitude, longitude), Options.BASIC, key)?.extracted

    /** The timezone for a GPS fix. */
    suspend fun timezone(fix: LocationFix, key: String? = null): TimezoneInfo? =
        timezone(fix.latitude, fix.longitude, key)

    /**
     * Sunrise and sunset for a coordinate's reference day, or null if either is missing.
     *
     * One call, not two: the basic response carries both, which is the single most useful thing the
     * Python probe established.
     */
    suspend fun sunTimes(latitude: Double, longitude: Double, key: String? = null): SunTimes? {
        val info = timezone(latitude, longitude, key) ?: return null
        val sunrise = info.sunrise ?: return null
        val sunset = info.sunset ?: return null
        return SunTimes(sunrise, sunset)
    }

    /** Sunrise and sunset for a GPS fix. */
    suspend fun sunTimes(fix: LocationFix, key: String? = null): SunTimes? =
        sunTimes(fix.latitude, fix.longitude, key)

    /**
     * Both variants of the call for one coordinate, keyed `"basic"` / `"all"` — the Python script's
     * per-coordinate loop. Pass [options] to run only one of them.
     *
     * For capture-time enrichment use [timezone] or [sunTimes] instead; this is the diagnostic path, and
     * the one that hands [saveProbes] something to write.
     */
    suspend fun probe(
        query: String = DEFAULT_QUERY,
        options: Options? = null,
        key: String? = null
    ): Map<String, Probe> {
        val subscriptionKey = resolveKey(key) ?: run {
            Log.d(TAG, "no Azure Maps key configured — probe skipped")
            return emptyMap()
        }
        val variants = options?.let { listOf(it) } ?: listOf(Options.BASIC, Options.ALL)
        return variants.associate { variant ->
            val name = variant.name.lowercase(Locale.US)
            val probe = fetch(buildUrl(query, subscriptionKey, variant), subscriptionKey)
            if (probe.ok) {
                val info = probe.extracted
                Log.d(
                    TAG,
                    "$query $name ${probe.status} ${probe.elapsedMs}ms tz ${info?.timezoneId} " +
                        "offset ${info?.standardOffset} dst ${info?.daylightSavings} " +
                        "sunrise ${info?.sunrise} sunset ${info?.sunset}"
                )
            } else {
                Log.w(TAG, "$query $name FAILED — ${probe.error}")
            }
            name to probe
        }
    }

    /** [probe] over several coordinates, keyed by query — the whole of the Python script's `main`. */
    suspend fun probeAll(
        queries: List<String> = listOf(DEFAULT_QUERY),
        options: Options? = null,
        key: String? = null
    ): Map<String, Map<String, Probe>> =
        queries.associateWith { probe(it, options, key) }

    // ---- saving ---------------------------------------------------------------------------- //

    /**
     * Writes the files the Python script writes, under `filesDir/azure_maps` by default:
     *
     * ```
     * <stamp>-<lat>_<lon>-basic.json
     * <stamp>-<lat>_<lon>-all.json
     * <stamp>-summary.json
     * ```
     *
     * Debug output, not app state — it is what makes a field failure readable after the fact, and it
     * sits outside the run folders so a run reset does not take the evidence with it. Nothing written
     * here contains the key.
     *
     * @return the files written, summary last
     */
    fun saveProbes(
        context: Context,
        results: Map<String, Map<String, Probe>>,
        // Same directory as the stored answer, so AppReset's one entry covers both. The names cannot
        // collide: these are stamped, AzureMapsStore.FILE_NAME is fixed.
        dir: File = File(context.applicationContext.filesDir, AzureMapsStore.DIR)
    ): List<File> {
        if (results.isEmpty()) return emptyList()
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val written = mutableListOf<File>()

        val coordinates = JSONArray()
        for ((query, calls) in results) {
            val callsJson = JSONObject()
            for ((name, probe) in calls) {
                callsJson.put(name, probe.toJson())
                val file = File(dir, "$stamp-${slug(query)}-$name.json")
                file.writeText(probe.toJson().toString(2))
                written += file
            }
            coordinates.put(JSONObject().put("query", query).put("calls", callsJson))
        }

        val summary = JSONObject()
            .put("generated_utc", utcStamp())
            .put("endpoint", ENDPOINT)
            .put("api_version", API_VERSION)
            .put("coordinates", coordinates)
        val summaryFile = File(dir, "$stamp-summary.json")
        summaryFile.writeText(summary.toString(2))
        written += summaryFile

        Log.d(TAG, "wrote ${written.size} probe files to ${dir.absolutePath}")
        return written
    }

    /** `58.595300,25.013600` → `58.595300_25.013600`, safe as a filename. */
    fun slug(query: String): String =
        query.replace(",", "_").replace(" ", "").replace("/", "-")

    // ---- helpers --------------------------------------------------------------------------- //

    private fun utcStamp(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())

    /**
     * `optString` returns `""` for a missing key, which is indistinguishable from a field the service
     * genuinely sent empty. Null instead, so [TimezoneInfo] can say "absent".
     */
    private fun JSONObject.optStringOrNull(name: String): String? =
        if (isNull(name)) null else optString(name).takeIf { it.isNotEmpty() }

    /** `02:00:00`, `+02:00`, `-05:30` or `0` → minutes, seconds ignored. Null when unparseable. */
    private fun offsetToMinutes(offset: String?): Int? {
        val text = offset?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val sign = if (text.startsWith("-")) -1 else 1
        val parts = text.removePrefix("+").removePrefix("-").split(":")
        val hours = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val minutes = parts.getOrNull(1)?.toIntOrNull() ?: 0
        return sign * (hours * 60 + minutes)
    }
}
