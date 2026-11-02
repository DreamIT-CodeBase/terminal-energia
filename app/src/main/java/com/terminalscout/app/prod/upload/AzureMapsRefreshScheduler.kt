package com.terminalscout.app.prod.upload

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Aims [AzureMapsRefreshWorker] at 03:00 Europe/Tallinn, daily.
 *
 * ### Why a 24-hour periodic and not a self-rescheduling one-shot
 * A periodic request is one registration that survives process death and reboot with no bookkeeping. Its
 * one weakness is that the period is 24 hours of *elapsed* time, so across a DST switch the fire time
 * drifts an hour off 03:00 local. A worker that re-enqueued its own unique name would avoid that but
 * would have to REPLACE work that is still running (itself) to do it — a worse trade than the fix used
 * here, which is to re-aim the next run explicitly on every [ensureArmed].
 *
 * Re-aiming on every app start cannot cause an extra API call: the worker only asks the store, and
 * [AzureMapsStore.ensureFresh] is a no-op while the stored file is today's.
 *
 * ### The drift correction is `setNextScheduleTimeOverride`, NOT `setInitialDelay`
 * Worth stating because the obvious version of this is silently wrong. `WorkSpec.calculateNextRunTime`
 * (checked against work-runtime 2.9.1 sources) computes `lastEnqueueTime + initialDelay` only while
 * `periodCount == 0`, and `lastEnqueueTime + intervalDuration` for every run after that; and
 * [ExistingPeriodicWorkPolicy.UPDATE] deliberately preserves `lastEnqueueTime` and `periodCount`. So an
 * updated initial delay is accepted and ignored, and the schedule keeps whatever anchor it acquired on
 * its first ever run. The schedule-time override is the field the same function gives top priority to,
 * and the one `updateWorkImpl` copies across an update.
 *
 * ### Offline at 03:00 → it runs when the phone next comes online
 * Both requests carry a `CONNECTED` constraint, and that constraint is what delivers the requirement:
 * an unsatisfied constraint does not fail or skip the job, it **holds** it. A phone with no signal at
 * 03:00 keeps the job pending and runs it the moment connectivity returns, whether that is 03:04 or
 * midday. Nothing polls, and nothing wakes the radio to find out.
 *
 * Two requests rather than one, because they cover different absences:
 *
 *   - [ensureArmed] registers the daily [PeriodicWorkRequestBuilder] — the phone was on and merely
 *     offline, so the job existed and was held.
 *   - [requestCatchUp] enqueues a one-shot — the phone was **off** at 03:00, or the app's process was
 *     never started that day, so there was no held job to release. It is asked for at app start when
 *     the stored file is not today's.
 *
 * Neither can double-fetch: both run the same worker, which asks [AzureMapsStore.ensureFresh], which is
 * a no-op while the stored file is today's.
 */
object AzureMapsRefreshScheduler {

    private const val TAG = "AzureMapsRefresh"
    const val PERIODIC_NAME = "azure-maps-daily-refresh"
    private const val CATCH_UP_NAME = "azure-maps-catch-up"

    private val networkConstraint = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /**
     * Registers (or re-aims) the daily refresh, and asks for a catch-up when the stored file is not
     * today's. Idempotent and cheap — call it on every app start, next to the other schedulers, and after
     * anything that cancels all work.
     */
    fun ensureArmed(context: Context) {
        // Resolves AzureMapsStore.path before anything reads it, and before the check below.
        AzureMapsStore.init(context)
        val nowMs = System.currentTimeMillis()
        val nextAtMs = AzureMapsStore.nextRefreshAfter(nowMs)
        val request = PeriodicWorkRequestBuilder<AzureMapsRefreshWorker>(1, TimeUnit.DAYS)
            // Aims the FIRST ever run. Read only while `periodCount == 0` — see the note below.
            .setInitialDelay(nextAtMs - nowMs, TimeUnit.MILLISECONDS)
            // Aims every run after that. `setInitialDelay` cannot: WorkSpec.calculateNextRunTime uses
            // `lastEnqueueTime + initialDelay` only for the first period and `lastEnqueueTime +
            // intervalDuration` forever after, and UPDATE preserves `lastEnqueueTime`/`periodCount` —
            // so an updated initial delay is silently ignored and the DST drift would stand. The
            // override takes priority for periodic work and IS carried through UPDATE, but only for the
            // next run, which is exactly the granularity wanted here.
            .setNextScheduleTimeOverride(nextAtMs)
            .setConstraints(networkConstraint)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_NAME,
            // UPDATE, not KEEP: the whole point of calling this on every start is to re-anchor a
            // schedule that DST (or a long-lived registration) has let drift off 03:00.
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
        Log.d(
            TAG,
            "armed for ${AzureMapsStore.stamp(nextAtMs)} Tallinn (in ${(nextAtMs - nowMs) / 60_000} min)"
        )

        // The file is not today's: either 03:00 has not come round yet, or it came and went while the
        // phone was off or had no signal. Ask for it now — held until there is a connection.
        if (AzureMapsStore.current(nowMs) == null) requestCatchUp(context)
    }

    /**
     * Asks for the day's data as soon as there is a connection, without waiting for 03:00.
     *
     * [ExistingWorkPolicy.KEEP], not REPLACE: repeated app launches must not keep cancelling and
     * re-enqueuing a request that is sitting there waiting for signal — REPLACE would reset it every
     * time and, on a phone being opened repeatedly in a dead spot, could starve it indefinitely.
     *
     * Also the hook for a caller that knows connectivity just returned. It cannot cause a duplicate
     * fetch: the worker asks the store, which answers from the file while that file is today's.
     */
    fun requestCatchUp(context: Context) {
        val request = OneTimeWorkRequestBuilder<AzureMapsRefreshWorker>()
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
}
