package com.terminalscout.app.prod.upload

import android.content.Context
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
 * Schedules [UploadWorker]. Two triggers cover the requirement "every 15 minutes OR when internet
 * is available":
 *
 *   - a **periodic** request at the platform-minimum 15-minute interval, and
 *   - a **one-time** request kicked after each pipeline run,
 *
 * both gated by a `CONNECTED` network constraint — so WorkManager holds them until there is
 * connectivity and then runs them, and retries with exponential backoff on failure.
 */
object UploadScheduler {

    const val PERIODIC_NAME = "upload-queue-periodic"
    private const val ONESHOT_NAME = "upload-queue-oneshot"

    private val networkConstraint = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /** Registers the recurring 15-minute drain. Idempotent — safe to call on every app start. */
    fun ensurePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<UploadWorker>(15, TimeUnit.MINUTES)
            .setConstraints(networkConstraint)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    /** Kicks an immediate (network-permitting) drain, e.g. right after a run enqueues new work. */
    fun requestImmediate(context: Context) {
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(networkConstraint)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            ONESHOT_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }
}
