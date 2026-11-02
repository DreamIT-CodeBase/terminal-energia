package com.terminalscout.app.prod.upload

import android.content.Context
import com.terminalscout.app.prod.config.DeviceConfig
import android.util.Log
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The day's Azure Maps answer, stored as one JSON file — **the file is the source of truth**, and its
 * [path] is the global anything in the app can read.
 *
 * ### The rule this enforces
 * **One API call per service day.** Everything asks this store; the store answers from the file and
 * calls Azure only when what is on disk is older than the most recent 03:00 Europe/Tallinn boundary. So
 * twenty pipeline runs in an afternoon make zero network calls between them.
 *
 * ### Where it lives, and how to read it
 * ```
 * filesDir/azure_maps/timezone.json        AzureMapsStore.path
 * ```
 * [init] resolves that path (any `Context` will do, and it is idempotent); [AzureMapsRefreshScheduler]
 * and [AzureMapsRefreshWorker] both call it, so by the time anything else runs it is set. After that:
 *
 * | | |
 * |---|---|
 * | [path] | the absolute path, as a plain string |
 * | [rawJson] | the Azure response body, verbatim, as text |
 * | [json] | the same, parsed |
 * | [stored] | the whole document, with the fields already pulled out |
 * | [current] | [stored], but null unless it is **today's** |
 *
 * Nothing is held in memory: every read opens the file. That is the deliberate trade for having one
 * source of truth that survives process death — a phone started at 09:00 has the day's data with no
 * network call. A read is a few kilobytes and a parse, which is nothing at pipeline cadence; do not put
 * one inside a per-frame loop.
 *
 * ### What the file contains
 * A small envelope around the response, **which is stored verbatim** so that a field Azure adds later is
 * on disk even though this build ignores it:
 * ```json
 * {
 *   "service_day_start_ms": 1787443200000,   // the 03:00 boundary this answer belongs to
 *   "fetched_at_ms":        1787443210000,
 *   "query":                "58.595300,25.013600",
 *   "response":             { ... the Azure body, untouched ... }
 * }
 * ```
 * The envelope is what makes the file self-describing: freshness is decided by a field the writer wrote,
 * not by a modification time that a backup, a copy or a restore would quietly change.
 *
 * ### Why 03:00 Tallinn is the boundary
 * Sunrise and sunset are what this data is for, so the refresh has to land in the dark, before the first
 * driver is out. The same instant does two jobs, which is what keeps them from disagreeing:
 * [AzureMapsRefreshScheduler] aims the daily work at it, and [serviceDayStart] uses it to decide whether
 * the file is still today's. The scheduler can be late — Doze, no signal, the phone was off — without
 * any of it mattering, because the boundary check is what actually decides.
 *
 * ### On failure the old file stands
 * A failed fetch never deletes what is there. Yesterday's sunrise is minutes from today's and vastly
 * better than nothing, and forecourts are frequently dead spots. Repeated failures are throttled to one
 * attempt every [FAILED_RETRY_MS].
 *
 * ### java.time is not available here
 * `minSdk 24` with no core-library desugaring, so the boundary arithmetic is [Calendar] in the Tallinn
 * zone. Centralised in [serviceDayStart] / [nextRefreshAfter] rather than repeated in the scheduler.
 */
object AzureMapsStore {

    private const val TAG = "AzureMapsStore"

    /** Under `filesDir`. Registered in `AppReset.DIRECTORIES`, so a storage wipe takes it. */
    const val DIR = "azure_maps"

    const val FILE_NAME = "timezone.json"

    /** The refresh hour, local to [ZONE]. Both the schedule and the staleness check use it. */
    val REFRESH_HOUR: Int get() = DeviceConfig.int("setup.azure_maps_store.refresh_hour", 3)

    /**
     * Estonia's zone, hard-coded because the fleet is Estonian and the daily boundary has to be one
     * fixed thing for the whole fleet. It is NOT the device's own zone: a phone left on a foreign
     * timezone would otherwise refresh at some unrelated hour.
     */
    val ZONE: TimeZone = TimeZone.getTimeZone("Europe/Tallinn")

    /** Minimum spacing between attempts after a failure, so a dead network stays cheap. */
    val FAILED_RETRY_MS: Long get() = DeviceConfig.long("setup.azure_maps_store.failed_retry_ms", 15 * 60 * 1000L)

    private const val KEY_SERVICE_DAY = "service_day_start_ms"
    private const val KEY_FETCHED_AT = "fetched_at_ms"
    private const val KEY_QUERY = "query"
    private const val KEY_RESPONSE = "response"

    // ---- the global path ---------------------------------------------------------------------- //

    /**
     * **The global.** Absolute path of the stored JSON, or empty before [init] has run.
     *
     * `AzureMapsStore.path` from anywhere — this is an `object`, so there is nothing to construct,
     * inject or pass around. Empty is the honest answer before initialisation rather than a path that
     * would not resolve.
     */
    @Volatile
    var path: String = ""
        private set

    /** [path] as a [File], or null before [init]. */
    fun file(): File? = path.takeIf { it.isNotEmpty() }?.let(::File)

    /**
     * Resolves [path] and creates the directory. Idempotent and cheap — call it from anywhere that has a
     * `Context` and might be first: the scheduler and the worker both do, and the worker matters because
     * WorkManager can start the process without any Activity.
     */
    fun init(context: Context) {
        initAt(File(context.applicationContext.filesDir, DIR))
    }

    /** [init] without a `Context`, for tests and for anything that already knows the directory. */
    @VisibleForTesting
    fun initAt(dir: File) {
        dir.mkdirs()
        path = File(dir, FILE_NAME).absolutePath
    }

    // ---- what is on disk ---------------------------------------------------------------------- //

    /**
     * One day's stored answer, as read back from the file.
     *
     * [query] records the coordinate it was fetched for, which is not necessarily where the device is
     * now — the 03:00 refresh has no GPS fix to work from and falls back to the country centre. Across
     * Estonia sunrise varies by roughly ten minutes end to end, which is why one answer is allowed to
     * serve the whole fleet-day rather than being re-fetched per station.
     */
    data class Stored(
        val serviceDayStartMs: Long,
        val fetchedAtMs: Long,
        val query: String,
        val rawJson: String,
        val timezone: AzureMapsTimezone.TimezoneInfo?
    ) {
        val sunrise: String? get() = timezone?.sunrise
        val sunset: String? get() = timezone?.sunset

        /** Sunrise as epoch millis, or null when absent or in a shape [parseIso8601] does not know. */
        val sunriseAtMs: Long? by lazy { parseIso8601(sunrise) }

        /** Sunset as epoch millis, or null when absent or unparseable. */
        val sunsetAtMs: Long? by lazy { parseIso8601(sunset) }

        /**
         * True between sunrise and sunset, false outside it, **null when either time is missing** — a
         * caller must decide for itself what to do with "unknown" rather than get a silent `false`.
         *
         * Assumes sunrise precedes sunset on the day, which holds everywhere this app runs and stops
         * holding above the Arctic Circle.
         */
        fun isDaylightAt(atMs: Long): Boolean? {
            val rise = sunriseAtMs ?: return null
            val set = sunsetAtMs ?: return null
            return atMs in rise until set
        }
    }

    /** The whole envelope as written, or null when there is no readable file. */
    fun document(): JSONObject? {
        val f = file() ?: run {
            Log.w(TAG, "read before init() — path not resolved")
            return null
        }
        if (!f.isFile) return null
        return try {
            JSONObject(f.readText())
        } catch (e: Exception) {
            // A truncated or corrupt file is treated as absent, so the next caller refetches rather
            // than the app wedging on a document it cannot read.
            Log.w(TAG, "stored document unreadable — treating as absent", e)
            null
        }
    }

    /**
     * The Azure response body verbatim, as text — the raw JSON, without the envelope. Null when nothing
     * is stored. Stale on purpose: it hands back yesterday's document rather than nothing. A caller that
     * needs today's specifically should use [current].
     */
    val rawJson: String? get() = document()?.optJSONObject(KEY_RESPONSE)?.toString()

    /** The Azure response body, parsed. A fresh object per call, so a caller may mutate it freely. */
    fun json(): JSONObject? = document()?.optJSONObject(KEY_RESPONSE)

    /** The stored answer with its fields pulled out, stale or not. Null when nothing is stored. */
    fun stored(): Stored? = parse(document())

    /** [stored], but only if it belongs to the current service day. Never calls out. */
    fun current(nowMs: Long = System.currentTimeMillis()): Stored? =
        stored()?.takeIf { it.serviceDayStartMs == serviceDayStart(nowMs) }

    /** Deletes the file. For [com.terminalscout.app.prod.AppReset] and tests — nothing else needs it. */
    fun clear() {
        file()?.delete()
        lastAttemptMs = 0L
        lastError = null
        lastKnownQuery = null
    }

    private fun parse(doc: JSONObject?): Stored? {
        val response = doc?.optJSONObject(KEY_RESPONSE) ?: return null
        return Stored(
            serviceDayStartMs = doc.optLong(KEY_SERVICE_DAY, 0L),
            fetchedAtMs = doc.optLong(KEY_FETCHED_AT, 0L),
            query = doc.optString(KEY_QUERY),
            rawJson = response.toString(),
            timezone = AzureMapsTimezone.extract(response)
        )
    }

    // ---- transient state ------------------------------------------------------------------------ //
    //
    // Not the payload, so it does not belong in the file: a retry throttle and a last-seen coordinate
    // are about this process's recent behaviour, and both are meant to be forgotten on a restart.

    /** When the last attempt was made, successful or not — the input to the failure throttle. */
    @Volatile
    private var lastAttemptMs = 0L

    /** Why the last attempt failed, for logs and the debug screen. Cleared by a success. */
    @Volatile
    var lastError: String? = null
        private set

    /**
     * The coordinate to use when a caller does not supply one — the last fix anyone remembered this
     * process, else the centre of Estonia. Deliberately not persisted: writing a location trail to disk
     * to serve a lookup this insensitive to position would be a poor trade.
     */
    @Volatile
    private var lastKnownQuery: String? = null

    /** Only one fetch at a time; concurrent callers wait for it instead of racing to duplicate it. */
    private val lock = Mutex()

    /** Remembers where the device was, for the next refresh that is given no coordinate of its own. */
    fun remember(fix: LocationFix) {
        lastKnownQuery = AzureMapsTimezone.coordinate(fix)
    }

    // ---- refresh -------------------------------------------------------------------------------- //

    /**
     * The entry point for everything: returns today's answer, fetching and rewriting the file **only**
     * if what is stored is from an earlier service day.
     *
     * Safe to call on every run — that is the point. The three ways it returns without a network call
     * are all normal: the file is current, a failure was too recent to retry, or no key is configured.
     *
     * @param query the coordinate to look up; defaults to the last remembered fix, then to the centre
     *   of Estonia
     * @param force refetch even if the file is current — for a manual "refresh now" only. Every
     *   automatic path must leave this false or the once-a-day guarantee is gone.
     * @return today's answer, or the stale one if the fetch failed, or null if there has never been one
     */
    suspend fun ensureFresh(
        context: Context,
        query: String? = null,
        nowMs: Long = System.currentTimeMillis(),
        force: Boolean = false
    ): Stored? {
        init(context)
        if (!force) current(nowMs)?.let { return it }

        return lock.withLock {
            // Re-check under the lock: while this call was queued behind another, that other one may
            // have already fetched and written exactly what is being asked for.
            if (!force) current(nowMs)?.let { return@withLock it }

            if (!force && lastAttemptMs != 0L && nowMs - lastAttemptMs < FAILED_RETRY_MS) {
                Log.d(TAG, "last attempt ${(nowMs - lastAttemptMs) / 1000}s ago — holding off")
                return@withLock stored()
            }
            lastAttemptMs = nowMs

            val target = query ?: lastKnownQuery ?: AzureMapsTimezone.DEFAULT_QUERY
            val probe = AzureMapsTimezone.lookup(target)
            val response = probe?.response
            if (response == null || probe.extracted == null) {
                // Three different nulls, one outcome: no key configured, the call failed, or a 200 that
                // carried no zone. None of them is worth losing the previous day's answer over.
                lastError = probe?.error ?: "no key configured, or no zone for $target"
                Log.w(TAG, "refresh failed ($target): $lastError — keeping what is on disk")
                return@withLock stored()
            }

            lastError = null
            val doc = buildDocument(
                serviceDayStartMs = serviceDayStart(nowMs),
                fetchedAtMs = nowMs,
                query = target,
                response = response
            )
            if (!save(doc)) {
                // The fetch worked but the write did not: return the fresh answer anyway rather than
                // failing the caller, and let the next call try the disk again.
                Log.w(TAG, "could not write $path — this run uses the fetched answer only")
                return@withLock parse(doc)
            }
            val fresh = parse(doc)
            Log.d(
                TAG,
                "wrote $path — $target tz ${fresh?.timezone?.timezoneId} sunrise ${fresh?.sunrise} " +
                    "sunset ${fresh?.sunset} (service day from ${stamp(serviceDayStart(nowMs))})"
            )
            fresh
        }
    }

    /** The envelope described in the class docs. */
    @VisibleForTesting
    fun buildDocument(
        serviceDayStartMs: Long,
        fetchedAtMs: Long,
        query: String,
        response: JSONObject
    ): JSONObject = JSONObject()
        .put(KEY_SERVICE_DAY, serviceDayStartMs)
        .put(KEY_FETCHED_AT, fetchedAtMs)
        .put(KEY_QUERY, query)
        .put(KEY_RESPONSE, response)

    /**
     * Writes the document, replacing whatever was there.
     *
     * Through a temporary file rather than straight over the target: readers now hit the disk on every
     * access, so a reader arriving mid-write would otherwise get a truncated document. Delete-then-
     * rename instead of a plain rename because `File.renameTo` does not replace an existing file on
     * every platform — the gap between the two is microseconds, and a caller that lands in it sees no
     * file, which is a case every read already handles.
     */
    @VisibleForTesting
    fun save(doc: JSONObject): Boolean {
        val target = file() ?: run {
            Log.w(TAG, "write before init() — path not resolved")
            return false
        }
        return try {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(doc.toString(2))
            target.delete()
            tmp.renameTo(target)
        } catch (e: Exception) {
            Log.w(TAG, "write failed", e)
            false
        }
    }

    // ---- the 03:00 boundary --------------------------------------------------------------------- //

    /**
     * The 03:00 Tallinn instant at or before [nowMs] — the start of the service day, and the identity
     * written into the file. **This function is the single definition of the boundary**;
     * [nextRefreshAfter] is expressed in terms of it rather than repeating the arithmetic.
     *
     * Two days a year the wall clock says 03:00 either twice or not at all, and [Calendar] resolves both
     * leniently — in autumn to the second (standard-time) 03:00, in spring to 03:00 of the new offset.
     * So the boundary can land up to an hour off the nominal one on those two days. Immaterial for a
     * daily refresh, and it is still exactly one boundary per day.
     */
    fun serviceDayStart(nowMs: Long): Long {
        val cal = tallinn(nowMs)
        cal.set(Calendar.HOUR_OF_DAY, REFRESH_HOUR)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        // Before 03:00 the current service day started yesterday. A loop, not an `if`: on the autumn
        // switch day the resolved 03:00 can sit an hour ahead of the nominal one, so one step back is
        // not provably enough.
        while (cal.timeInMillis > nowMs) cal.add(Calendar.DAY_OF_MONTH, -1)
        return cal.timeInMillis
    }

    /**
     * The next boundary strictly after [nowMs] — the scheduler's target.
     *
     * A calendar day is stepped rather than 24 hours added, so the answer stays 03:00 local across the
     * DST switches instead of drifting an hour. The result is then re-resolved through
     * [serviceDayStart]: `Calendar.add(DAY_OF_MONTH, 1)` and `Calendar.set(HOUR_OF_DAY, 3)` disagree
     * about which of the autumn switch day's **two** 03:00s they mean — an hour apart — and taking one
     * function's word for it produced a real defect. At the first 03:00 EEST this returned that same
     * instant, so the delay was zero and the work fired immediately, for a service day that had not
     * started. Hourly steps from there settle on whichever instant [serviceDayStart] itself calls the
     * boundary, which is by definition the one the staleness check will use.
     */
    fun nextRefreshAfter(nowMs: Long): Long {
        var probeMs = tallinn(serviceDayStart(nowMs))
            .apply { add(Calendar.DAY_OF_MONTH, 1) }
            .timeInMillis
        var next = serviceDayStart(probeMs)
        // Bounded by a day's worth of hours: a zone would have to be pathological to need more than the
        // one step the autumn switch costs, and a runaway loop here would hang the caller.
        var guard = 0
        while (next <= nowMs && guard++ < 26) {
            probeMs += 3_600_000L
            next = serviceDayStart(probeMs)
        }
        return next
    }

    /** Milliseconds from [nowMs] until the next boundary — the scheduler's initial delay. */
    fun delayToNextRefreshMs(nowMs: Long = System.currentTimeMillis()): Long =
        (nextRefreshAfter(nowMs) - nowMs).coerceAtLeast(0L)

    private fun tallinn(atMs: Long): Calendar =
        Calendar.getInstance(ZONE).apply { timeInMillis = atMs }

    /** For logs only: an instant as Tallinn wall time. */
    fun stamp(atMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
            .apply { timeZone = ZONE }
            .format(Date(atMs))

    // ---- parsing --------------------------------------------------------------------------------- //

    /**
     * Azure's timestamps as epoch millis, or null if the shape is unrecognised.
     *
     * The live endpoint returns **seven** fractional digits — `2026-08-23T05:57:35.1119448+03:00`,
     * checked on 2026-08-23 — and that is a trap worth spelling out. [SimpleDateFormat]'s `SSS` is a
     * plain number field, not a fraction: handed `1119448` it adds 1,119,448 **milliseconds**, putting
     * sunrise eighteen minutes late with no error to show for it. So the fraction is stripped before
     * parsing rather than matched. Whole seconds are all this is for.
     *
     * Hand-rolled because `java.time` is unavailable at this minSdk. The formatter is built per call
     * rather than cached: [SimpleDateFormat] is not thread-safe, and this is not hot.
     */
    fun parseIso8601(text: String?): Long? {
        val value = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        // The only dot in an ISO-8601 timestamp introduces the fractional second, so this is safe.
        val whole = value.replace(FRACTION, "")
        for (pattern in ISO_PATTERNS) {
            try {
                return SimpleDateFormat(pattern, Locale.US)
                    .apply {
                        timeZone = UTC
                        isLenient = false
                    }
                    .parse(whole)
                    ?.time
            } catch (_: ParseException) {
                // Try the next shape.
            } catch (_: IllegalArgumentException) {
                // Malformed pattern for this platform — treat as no match.
            }
        }
        Log.w(TAG, "unrecognised timestamp shape: $value")
        return null
    }

    private val UTC: TimeZone = TimeZone.getTimeZone("UTC")

    /** The fractional-second part, of any length. See [parseIso8601] for why it is discarded. */
    private val FRACTION = Regex("\\.\\d+")

    /**
     * Offset form first: that is what the live endpoint returns, and the only one that actually places
     * the instant. The trailing `Z` and bare forms are fallbacks — the last one is read as UTC, which is
     * a guess, so it is tried last.
     */
    private val ISO_PATTERNS = listOf(
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd'T'HH:mm:ss"
    )
}
