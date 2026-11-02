package com.terminalscout.app.prod.upload

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * The 03:00 job: one call to [AzureMapsStore.ensureFresh], nothing else.
 *
 * Scheduled by [AzureMapsRefreshScheduler] at the 03:00 Europe/Tallinn boundary, and also enqueued as a
 * catch-up when the stored file is not today's. It carries no logic of its own on purpose — whether a
 * call is actually needed is the store's decision, so this worker firing twice, late, or a minute early
 * cannot produce a second API call.
 *
 * ### What it leaves behind outlives the process
 * The store writes `filesDir/azure_maps/timezone.json`, so if WorkManager starts the process at 03:00
 * purely for this job, the answer survives the process being reclaimed straight after. That is the whole
 * reason the file is the source of truth rather than a field in memory: the phone can then be picked up
 * at 09:00 with the day's data already there and no network in sight.
 *
 * ### Retry
 * A failed fetch returns [Result.retry] and WorkManager backs off, but the store's own
 * [AzureMapsStore.FAILED_RETRY_MS] throttle still applies — so a retry that arrives too early is a no-op
 * rather than a second call. A build with no key configured returns success instead: retrying that would
 * re-fire for the life of the install against a condition only a new build can change.
 */
class AzureMapsRefreshWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val nowMs = System.currentTimeMillis()
        Log.d(TAG, "refresh firing at ${AzureMapsStore.stamp(nowMs)} (Tallinn)")

        if (!AzureMapsTimezone.isConfigured) {
            Log.d(TAG, "no Azure Maps key in this build — nothing to do")
            return Result.success()
        }

        val stored = AzureMapsStore.ensureFresh(applicationContext, nowMs = nowMs)
        val isToday = stored != null &&
            stored.serviceDayStartMs == AzureMapsStore.serviceDayStart(nowMs)

        return if (isToday) {
            Log.d(TAG, "have today's answer: ${stored?.query} sunrise ${stored?.sunrise}")
            Result.success()
        } else {
            // Either the fetch failed or the throttle held it off; both are worth one backed-off retry,
            // and neither is worth escalating — the next 03:00 is already scheduled.
            Log.w(TAG, "nothing for today (${AzureMapsStore.lastError}) — will retry")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "AzureMapsRefresh"
    }
}
