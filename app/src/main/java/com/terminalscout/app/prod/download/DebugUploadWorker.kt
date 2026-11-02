package com.terminalscout.app.prod.download

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Drains [DebugUploadOutbox] to the `debugdirectory` container, on WorkManager's terms: it only runs
 * with a network, retries with backoff, and picks up where it left off after the process is killed.
 *
 * This is the half of the debug upload that touches the network. The pipeline no longer does — it
 * stages artifacts locally and returns — so a run can delete its working files as soon as they are
 * staged, instead of waiting on transfers it could not outlast. Previously a run waited up to 90 s and
 * then deleted whatever had not made it, which is exactly what happened on a weak connection.
 *
 * A drain is not all-or-nothing: each artifact that lands is deleted from the outbox immediately, so a
 * run that is interrupted half-way resumes rather than restarts.
 *
 * Once a run has nothing left owed, [completeDrainedRuns] declares what it produced to the server, which
 * is what makes a gap detectable at all — the outbox alone cannot distinguish "uploaded everything" from
 * "uploaded what survived".
 */
class DebugUploadWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        DebugUploadOutbox.discardStalePartials(applicationContext)

        val pending = DebugUploadOutbox.pending(applicationContext)
        var uploaded = 0
        var abandoned = 0
        var retryLater = false

        if (pending.isNotEmpty()) {
            Log.d(TAG, "draining ${pending.size} staged artifact(s)")
            for ((blobPath, file) in pending) {
                when (DebugUploader.upload(applicationContext, blobPath, file)) {
                    DebugUploader.Outcome.UPLOADED -> {
                        file.delete()
                        uploaded++
                    }
                    // Rejected outright: parked, not deleted, and NOT retried. The request itself
                    // is wrong and will be as wrong next time, so the only useful thing left is to
                    // explain it — DebugUploader stages a logs.txt carrying the reason and this
                    // process's logcat tail.
                    DebugUploader.Outcome.ABANDONED -> {
                        DebugUploader.recordRejection(applicationContext, blobPath)
                        DebugUploadOutbox.reject(applicationContext, file)
                        abandoned++
                    }
                    // The network has gone. Stop — the remaining artifacts would each fail the same way,
                    // and WorkManager will bring us back when there is a connection worth using.
                    DebugUploader.Outcome.RETRY -> {
                        retryLater = true
                    }
                }
                if (retryLater) break
            }
            DebugUploadOutbox.removeEmptyRunDirs(applicationContext)
        }

        // Reconcile every run that has nothing left owed. This runs even on a drain that uploaded
        // nothing, because a run can reach that state by having its last artifact ABANDONED — and a run
        // whose artifacts all failed is precisely the one worth reporting.
        val deferred = completeDrainedRuns()

        Log.d(
            TAG,
            "drain finished — $uploaded uploaded, $abandoned rejected (parked, not retried), " +
                "${if (retryLater) "retrying later" else "outbox clear"}"
        )
        if (retryLater || deferred) Result.retry() else Result.success()
    }

    /**
     * Calls `complete` for each run that still has a manifest but no staged files left.
     *
     * "No files left" is the only signal available that a run is finished: staging happens in batches as
     * the pipeline's stages land, so there is no explicit end-of-run event to hook. A run still holding
     * staged artifacts is skipped and reconciled on a later drain.
     *
     * @return true if any completion was deferred and the worker should be retried.
     */
    private suspend fun completeDrainedRuns(): Boolean {
        val awaiting = DebugRunManifest.runsAwaitingCompletion(applicationContext)
        if (awaiting.isEmpty()) return false

        // A run holding only REJECTED leftovers counts as drained: those will never be sent, so the
        // run is finished from this device's side and completing it is what reports them missing.
        val stillQueued = DebugUploadOutbox.owedRuns(applicationContext)

        var deferred = false
        for (run in awaiting) {
            if (run in stillQueued) continue
            if (!DebugUploader.completeRun(applicationContext, run)) deferred = true
        }
        return deferred
    }

    private companion object {
        const val TAG = "DebugUploadWorker"
    }
}
