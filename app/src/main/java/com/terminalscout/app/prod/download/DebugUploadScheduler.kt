package com.terminalscout.app.prod.download

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
 * Schedules [DebugUploadWorker], mirroring [com.terminalscout.app.prod.upload.UploadScheduler]: a one-time
 * drain kicked whenever a stage stages new artifacts, plus a 15-minute periodic drain that catches up
 * on anything a long outage (or a killed process) left behind.
 *
 * Kept separate from the readings queue on purpose. These are debug artifacts — megabytes of images
 * whose delivery nobody is waiting on — and they must never delay or fail the reading uploads that
 * matter, nor be delayed by them.
 *
 * The one-time request uses [ExistingWorkPolicy.APPEND_OR_REPLACE] rather than REPLACE: several stages
 * of one run each ask for a drain, and REPLACE would keep cancelling a drain that was mid-transfer.
 */
object DebugUploadScheduler {

    const val PERIODIC_NAME = "debug-upload-periodic"
    private const val ONESHOT_NAME = "debug-upload-oneshot"

    private val networkConstraint = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /** Registers the recurring catch-up drain. Idempotent — safe to call on every app start. */
    fun ensurePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<DebugUploadWorker>(15, TimeUnit.MINUTES)
            .setConstraints(networkConstraint)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    /** Asks for a drain now (network permitting) — called as soon as artifacts have been staged. */
    fun requestImmediate(context: Context) {
        val request = OneTimeWorkRequestBuilder<DebugUploadWorker>()
            .setConstraints(networkConstraint)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            ONESHOT_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request
        )
    }
}
