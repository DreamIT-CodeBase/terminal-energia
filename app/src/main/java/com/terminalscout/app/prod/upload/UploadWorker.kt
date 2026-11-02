package com.terminalscout.app.prod.upload

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Drains both upload queues to their (separate) APIs. Runs on WorkManager's cadence + network
 * constraint, so it fires roughly every 15 minutes and whenever connectivity returns.
 *
 *   - PENDING [UploadDbHelper.TABLE_READINGS] rows → JSON POST to [READINGS_ENDPOINT].
 *   - PENDING [UploadDbHelper.TABLE_IMAGES] rows   → multipart (image + metadata) POST to
 *     [IMAGES_ENDPOINT]; the durable image file is deleted once the row is SENT.
 *
 * The real APIs don't exist yet, so both endpoints are placeholders — uploads will fail and rows
 * stay PENDING/FAILED (with backoff retry) until the URLs are swapped for live ones.
 */
class UploadWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    private val queue = UploadQueue(appContext)
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val nowMs = System.currentTimeMillis()
        var anyFailed = false
        var abandoned = 0

        // ---- readings API ----
        for (reading in queue.pendingReadings()) {
            // Cleared before each attempt. It is one field reused by every row of the drain, so a row
            // whose POST failed without setting it was recorded with the PREVIOUS row's error — which
            // is the worst kind of diagnostic, a plausible one that is about something else.
            lastError = ""
            val ok = postJson(READINGS_ENDPOINT, reading.payloadJson)
            if (ok) {
                queue.markSent(UploadDbHelper.TABLE_READINGS, reading.id, nowMs)
                Log.d(TAG, "Reading ${reading.id} uploaded")
            } else {
                if (queue.markFailed(
                        UploadDbHelper.TABLE_READINGS, reading.id, reading.attempts,
                        lastError.ifEmpty { "unknown failure" }, nowMs
                    )
                ) {
                    abandoned++
                } else {
                    // Only a row that may still be retried asks for a retry. Without this, a queue
                    // holding nothing but abandoned rows kept asking WorkManager to come back and do
                    // nothing, on a backoff that grows but never ends.
                    anyFailed = true
                }
            }
        }

        // ---- discarded-images API ----
        for (image in queue.pendingImages()) {
            val file = File(image.imagePath)
            if (!file.exists()) {
                // Nothing to send; treat as done so it stops blocking the queue.
                queue.markSent(UploadDbHelper.TABLE_IMAGES, image.id, nowMs)
                continue
            }
            lastError = ""
            val ok = postMultipart(IMAGES_ENDPOINT, file, image.metadataJson)
            if (ok) {
                queue.markSent(UploadDbHelper.TABLE_IMAGES, image.id, nowMs)
                file.delete() // durable copy no longer needed once accepted
                Log.d(TAG, "Image ${image.id} uploaded")
            } else {
                if (queue.markFailed(
                        UploadDbHelper.TABLE_IMAGES, image.id, image.attempts,
                        lastError.ifEmpty { "unknown failure" }, nowMs
                    )
                ) {
                    abandoned++
                } else {
                    anyFailed = true
                }
            }
        }

        if (abandoned > 0) {
            Log.e(
                TAG,
                "$abandoned row(s) hit ${UploadQueue.MAX_ATTEMPTS} attempts and were abandoned — " +
                    "${queue.abandonedCount(UploadDbHelper.TABLE_READINGS)} reading(s) and " +
                    "${queue.abandonedCount(UploadDbHelper.TABLE_IMAGES)} image(s) now stranded on this " +
                    "device"
            )
        }

        // Retry (with WorkManager backoff) only while something retryable is left.
        if (anyFailed) Result.retry() else Result.success()
    }

    private var lastError: String = ""

    private fun postJson(url: String, json: String): Boolean = try {
        val request = Request.Builder()
            .url(url)
            .post(json.toRequestBody(JSON_MEDIA))
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) lastError = "HTTP ${resp.code}"
            resp.isSuccessful
        }
    } catch (e: Exception) {
        lastError = e.message ?: e.javaClass.simpleName
        Log.w(TAG, "JSON POST failed: $lastError")
        false
    }

    private fun postMultipart(url: String, file: File, metadataJson: String): Boolean = try {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("metadata", null, metadataJson.toRequestBody(JSON_MEDIA))
            .addFormDataPart("image", file.name, file.asRequestBody(JPEG_MEDIA))
            .build()
        val request = Request.Builder().url(url).post(body).build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) lastError = "HTTP ${resp.code}"
            resp.isSuccessful
        }
    } catch (e: Exception) {
        lastError = e.message ?: e.javaClass.simpleName
        Log.w(TAG, "Multipart POST failed: $lastError")
        false
    }

    companion object {
        private const val TAG = "UploadWorker"

        // Placeholder endpoints — swap for the real APIs when they exist.
        const val READINGS_ENDPOINT = "https://example.com/api/v1/fuel-readings"
        const val IMAGES_ENDPOINT = "https://example.com/api/v1/discarded-frames"

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val JPEG_MEDIA = "image/jpeg".toMediaType()
    }
}
