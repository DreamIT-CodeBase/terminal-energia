package com.terminalscout.app.prod.capture

import android.content.Context
import com.terminalscout.app.prod.config.DeviceConfig
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * STAGE 0 — aims [LocationMapRefreshWorker] at 02:00 Europe/Tallinn, daily.
 *
 * Deliberately the same shape as `upload/AzureMapsRefreshScheduler` (which owns the 03:00 sun-times
 * job), because that one already paid for the two things the obvious version of this gets wrong:
 *
 * ### The drift correction is `setNextScheduleTimeOverride`, NOT `setInitialDelay`
 * A 24-hour periodic request counts 24 hours of *elapsed* time, so across a DST switch the fire time
 * drifts an hour off 02:00 local. `WorkSpec.calculateNextRunTime` uses `lastEnqueueTime +
 * initialDelay` only while `periodCount == 0` and `lastEnqueueTime + intervalDuration` for every run
 * after that, and [ExistingPeriodicWorkPolicy.UPDATE] preserves `lastEnqueueTime`/`periodCount` — so
 * an updated initial delay is accepted and silently ignored. The schedule-time override is the field
 * that takes priority and is carried across an update.
 *
 * ### Offline at 02:00 means "later today", not "skipped"
 * The `CONNECTED` constraint **holds** an unsatisfied job rather than failing it. A phone with no
 * signal at 02:00 keeps the job pending and runs it the moment connectivity returns. Nothing polls.
 *
 * Two requests, because they cover different absences:
 *  - [ensureArmed] registers the daily periodic — the phone was on and merely offline.
 *  - [requestCatchUp] enqueues a one-shot — the phone was **off** at 02:00, or the map is missing
 *    entirely, so there was no held job to release.
 *
 * ### Why 02:00 and 03:00 rather than one job
 * They fetch different things from different services and neither should be able to fail the other:
 * a dead Azure Maps Search quota must not cost the day's sunrise times, which decide Stage 3's
 * day/night branch. An hour apart also keeps two grid sweeps off the same radio wake.
 */
object LocationMapRefreshScheduler {

    private const val TAG = "LocationMapRefresh"
    const val PERIODIC_NAME = "location-map-daily-refresh"
    private const val CATCH_UP_NAME = "location-map-catch-up"

    /** 02:00 Tallinn, per the Stage 0 specification. */
    private val REFRESH_HOUR: Int get() = DeviceConfig.int("setup.station_map_sweep.refresh_hour", 2)

    /** A map older than this is treated as needing a catch-up rather than waiting for 02:00. */
    private val STALE_AFTER_MS: Long
        get() = DeviceConfig.long("setup.station_map_sweep.stale_after_ms", 24 * 60 * 60 * 1000L)

    val ZONE: TimeZone = TimeZone.getTimeZone("Europe/Tallinn")

    private val networkConstraint = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /**
     * Registers (or re-aims) the daily refresh, and asks for a catch-up when the map is missing or
     * stale. Idempotent and cheap — safe to call on every service start.
     */
    fun ensureArmed(context: Context) {
        val nowMs = System.currentTimeMillis()
        val nextAtMs = nextRefreshAfter(nowMs)

        val request = PeriodicWorkRequestBuilder<LocationMapRefreshWorker>(1, TimeUnit.DAYS)
            // Aims the FIRST ever run; read only while periodCount == 0.
            .setInitialDelay(nextAtMs - nowMs, TimeUnit.MILLISECONDS)
            // Aims every run after that — see the class note on why the initial delay cannot.
            .setNextScheduleTimeOverride(nextAtMs)
            .setConstraints(networkConstraint)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_NAME,
            // UPDATE, not KEEP: re-anchoring a schedule that DST has let drift is the reason this is
            // called on every start.
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
        Log.d(TAG, "armed for ${stamp(nextAtMs)} Tallinn (in ${(nextAtMs - nowMs) / 60_000} min)")

        val lastMs = LocationMap.lastRefreshMs(context)
        if (lastMs == 0L) {
            Log.i(TAG, "location_mapping is empty — asking for a sweep now")
            requestCatchUp(context)
        } else if (nowMs - lastMs > STALE_AFTER_MS) {
            Log.i(TAG, "location map is ${(nowMs - lastMs) / 3_600_000}h old — asking for a catch-up")
            requestCatchUp(context)
        }
    }

    /**
     * Asks for the map as soon as there is a connection, without waiting for 02:00.
     *
     * [ExistingWorkPolicy.KEEP], not REPLACE: repeated service starts must not keep cancelling and
     * re-enqueuing a request that is already waiting for signal, which on a phone in a dead spot
     * could starve it indefinitely.
     */
    fun requestCatchUp(context: Context) {
        val request = OneTimeWorkRequestBuilder<LocationMapRefreshWorker>()
            .setConstraints(networkConstraint)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            CATCH_UP_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
        Log.d(TAG, "catch-up requested — runs as soon as there is a connection")
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_NAME)
        WorkManager.getInstance(context).cancelUniqueWork(CATCH_UP_NAME)
    }

    // ---- Tallinn-local arithmetic ------------------------------------------------------------- //
    //
    // `java.time` is not available: minSdk 24 with no core-library desugaring, so this is Calendar in
    // the Tallinn zone, exactly as AzureMapsStore does it. Centralised here rather than inlined.

    private fun tallinn(atMs: Long): Calendar =
        Calendar.getInstance(ZONE).apply { timeInMillis = atMs }

    /** The 02:00 Tallinn instant at or before [nowMs]. */
    private fun refreshDayStart(nowMs: Long): Long {
        val cal = tallinn(nowMs)
        cal.set(Calendar.HOUR_OF_DAY, REFRESH_HOUR)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        if (cal.timeInMillis > nowMs) cal.add(Calendar.DAY_OF_MONTH, -1)
        return cal.timeInMillis
    }

    /**
     * The next 02:00 Tallinn strictly after [nowMs].
     *
     * The guard loop covers the two days a year when the wall clock says 02:00 twice or not at all:
     * stepping an hour at a time past the switch is bounded by a day's worth of hours, and a runaway
     * here would hang the caller.
     */
    fun nextRefreshAfter(nowMs: Long): Long {
        var probeMs = tallinn(refreshDayStart(nowMs))
            .apply { add(Calendar.DAY_OF_MONTH, 1) }
            .timeInMillis
        var next = refreshDayStart(probeMs)
        var guard = 0
        while (next <= nowMs && guard++ < 26) {
            probeMs += 3_600_000L
            next = refreshDayStart(probeMs)
        }
        return next
    }

    fun stamp(atMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
            .apply { timeZone = ZONE }
            .format(Date(atMs))
}
