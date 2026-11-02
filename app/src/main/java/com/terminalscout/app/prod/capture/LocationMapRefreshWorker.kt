package com.terminalscout.app.prod.capture

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * STAGE 0 — `fetchLocationMap`, as WorkManager runs it.
 *
 * Aimed at 02:00 Europe/Tallinn daily by [LocationMapRefreshScheduler]; also enqueued as a one-shot
 * catch-up when the device starts up with no map, or with one older than a day.
 *
 * **The sweep itself moved to [LocationMapRefresh] on 2026-09-18**, when the menu gained a REFRESH
 * STATION MAP button. Two callers doing the same fetch is exactly how the manual path and the nightly
 * one start disagreeing about what an empty answer means, so there is one implementation and this file
 * is the adapter onto WorkManager: run it, map the outcome onto a `Result`, nothing else. The fetch's
 * own guarantees — failure never destroys the stored map, successful sync preserves capture history
 * for stations still served — are documented where they are enforced.
 *
 * **The source moved on 2026-09-14**, from a thirty-call Azure Maps grid sweep run on the device
 * ([AzureMapsStations], kept for the reasoning behind that sweep) to `GET /v1/stations`.
 *
 * ### It is autonomous
 * A worker runs in its own process-scoped context with no Activity involved, so this keeps working with
 * the app closed, swiped away, or after a reboot. That is the whole point: a truck's map must not go
 * stale because nobody opened the app.
 *
 * ### Retry, not failure
 * `Result.retry()` on anything that could come good, so WorkManager's exponential backoff gets a second
 * chance within the day instead of waiting for tomorrow's 02:00. A build with no API credentials returns
 * success instead: retrying that would re-fire for the life of the install against a condition only a
 * new build can change.
 */
class LocationMapRefreshWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = when (LocationMapRefresh.runOnce(applicationContext)) {
        is LocationMapRefresh.Outcome.Refreshed -> Result.success()

        // Not a retry: no amount of backoff produces a credential that was never compiled in.
        LocationMapRefresh.Outcome.NotConfigured -> Result.success()

        LocationMapRefresh.Outcome.FetchFailed,
        LocationMapRefresh.Outcome.EmptySweep,
        LocationMapRefresh.Outcome.WriteFailed -> {
            Log.w(TAG, "refresh did not land — keeping the stored map, will retry with backoff")
            Result.retry()
        }
    }

    private companion object {
        const val TAG = "LocationMapRefresh"
    }
}
