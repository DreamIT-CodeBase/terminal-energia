package com.terminalscout.app.prod.upload

import android.content.ContentValues
import com.terminalscout.app.prod.config.DeviceConfig
import android.content.Context
import android.util.Log
import java.io.File

/** A readings-API payload waiting to be POSTed. */
data class PendingReading(
    val id: Long,
    val payloadJson: String,
    val attempts: Int
)

/** A discarded-frame record waiting to be POSTed to the images API. */
data class PendingImage(
    val id: Long,
    val imagePath: String,
    val metadataJson: String,
    val attempts: Int
)

/** Metadata captured for one low-confidence (YOLO-discarded) frame at enqueue time. */
data class DiscardedFrameRecord(
    val frameIndex: Int,
    val timestampUs: Long,
    val confidence: Float,
    val clarityScore: Double,
    val sourceImagePath: String,
    val deviceId: String,
    val vehicleId: String,
    val latitude: Double?,
    val longitude: Double?,
    val gpsAccuracyM: Float?,
    val capturedAt: Long,
    val metadataJson: String
)

/**
 * Repository over [UploadDbHelper]: enqueue new work, read PENDING rows for the uploader, and
 * transition rows to SENT/FAILED.
 *
 * ### Persistent image copies
 * Discarded frames are written by the pipeline into `cacheDir/failed_frames`, which is **wiped at
 * the start of every run** and reclaimable by the OS. Since uploads happen later on a cadence, this
 * queue copies each frame image into a durable directory ([imagesDir], under `filesDir`) at enqueue
 * time and stores *that* path — so a subsequent run (or low-storage cleanup) can't delete an image
 * out from under a pending upload.
 */
class UploadQueue(context: Context) {

    private val appContext = context.applicationContext
    private val helper = UploadDbHelper(appContext)

    /** Durable home for frame images awaiting upload (survives cache clears / new runs). */
    private val imagesDir: File
        get() = File(appContext.filesDir, "pending_uploads/images").apply { mkdirs() }

    // ---------------- enqueue ----------------

    /** Enqueues one aggregated-reading payload. Returns the new row id (-1 on failure). */
    fun enqueueReading(payloadJson: String, createdAt: Long): Long {
        val values = ContentValues().apply {
            put(UploadDbHelper.COL_PAYLOAD_JSON, payloadJson)
            put(UploadDbHelper.COL_CREATED_AT, createdAt)
            put(UploadDbHelper.COL_STATUS, UploadDbHelper.STATUS_PENDING)
        }
        return helper.writableDatabase.insert(UploadDbHelper.TABLE_READINGS, null, values).also {
            Log.d(TAG, "Enqueued reading payload as row $it")
        }
    }

    /**
     * Enqueues one discarded-frame record, copying its image into the durable [imagesDir] first.
     * Returns the new row id, or -1 if the source image was missing (nothing enqueued).
     */
    fun enqueueImage(record: DiscardedFrameRecord): Long {
        val source = File(record.sourceImagePath)
        if (!source.exists()) {
            Log.w(TAG, "Skipping enqueue — source frame image missing: ${record.sourceImagePath}")
            return -1
        }
        val durable = File(imagesDir, "frame_${record.capturedAt}_${record.frameIndex}.jpg")
        try {
            source.copyTo(durable, overwrite = true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy frame image into durable dir", e)
            return -1
        }

        val values = ContentValues().apply {
            put(UploadDbHelper.COL_IMAGE_PATH, durable.absolutePath)
            put(UploadDbHelper.COL_FRAME_INDEX, record.frameIndex)
            put(UploadDbHelper.COL_CONFIDENCE, record.confidence.toDouble())
            put(UploadDbHelper.COL_CLARITY, record.clarityScore)
            put(UploadDbHelper.COL_TIMESTAMP_US, record.timestampUs)
            put(UploadDbHelper.COL_DEVICE_ID, record.deviceId)
            put(UploadDbHelper.COL_VEHICLE_ID, record.vehicleId)
            record.latitude?.let { put(UploadDbHelper.COL_LATITUDE, it) }
            record.longitude?.let { put(UploadDbHelper.COL_LONGITUDE, it) }
            record.gpsAccuracyM?.let { put(UploadDbHelper.COL_GPS_ACCURACY, it.toDouble()) }
            put(UploadDbHelper.COL_CAPTURED_AT, record.capturedAt)
            put(UploadDbHelper.COL_METADATA_JSON, record.metadataJson)
            put(UploadDbHelper.COL_STATUS, UploadDbHelper.STATUS_PENDING)
        }
        return helper.writableDatabase.insert(UploadDbHelper.TABLE_IMAGES, null, values).also {
            Log.d(TAG, "Enqueued discarded frame ${record.frameIndex} as row $it -> ${durable.name}")
        }
    }

    // ---------------- read ----------------

    /**
     * The readings still owed, oldest first — [STATUS_ABANDONED] rows excluded.
     *
     * The exclusion is what stops the queue eating itself. The filter was `status != SENT` with no
     * attempt cap anywhere, so a row that could never be accepted — a payload the server rejects, a
     * body from a schema it no longer takes — was re-POSTed every fifteen minutes for the life of the
     * device. With `limit` rows drained per pass and the failures sorted to the FRONT by their own age,
     * a handful of poison rows at the head starved every good reading behind them indefinitely.
     */
    fun pendingReadings(limit: Int = 50): List<PendingReading> {
        val out = mutableListOf<PendingReading>()
        helper.readableDatabase.query(
            UploadDbHelper.TABLE_READINGS,
            arrayOf(UploadDbHelper.COL_ID, UploadDbHelper.COL_PAYLOAD_JSON, UploadDbHelper.COL_ATTEMPTS),
            NOT_DELIVERABLE, arrayOf(UploadDbHelper.STATUS_SENT, STATUS_ABANDONED),
            null, null, "${UploadDbHelper.COL_CREATED_AT} ASC", limit.toString()
        ).use { c ->
            while (c.moveToNext()) {
                out += PendingReading(c.getLong(0), c.getString(1), c.getInt(2))
            }
        }
        return out
    }

    fun pendingImages(limit: Int = 50): List<PendingImage> {
        val out = mutableListOf<PendingImage>()
        helper.readableDatabase.query(
            UploadDbHelper.TABLE_IMAGES,
            arrayOf(UploadDbHelper.COL_ID, UploadDbHelper.COL_IMAGE_PATH, UploadDbHelper.COL_METADATA_JSON, UploadDbHelper.COL_ATTEMPTS),
            NOT_DELIVERABLE, arrayOf(UploadDbHelper.STATUS_SENT, STATUS_ABANDONED),
            null, null, "${UploadDbHelper.COL_CAPTURED_AT} ASC", limit.toString()
        ).use { c ->
            while (c.moveToNext()) {
                out += PendingImage(c.getLong(0), c.getString(1), c.getString(2) ?: "{}", c.getInt(3))
            }
        }
        return out
    }

    /**
     * Count of rows still worth sending — neither delivered nor abandoned. For the UI status line.
     *
     * Abandoned rows are excluded on purpose: a count that includes work nothing will ever attempt again
     * is a number that only ever goes up, and an operator reading "412 pending" cannot tell that none of
     * them is moving. [abandonedCount] reports those separately, which is the honest split.
     */
    fun pendingCount(table: String): Int {
        helper.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $table WHERE ${UploadDbHelper.COL_STATUS} NOT IN (?, ?)",
            arrayOf(UploadDbHelper.STATUS_SENT, STATUS_ABANDONED)
        ).use { c -> return if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    /** Rows given up on after [MAX_ATTEMPTS] failures. Kept on disk, never sent again. */
    fun abandonedCount(table: String): Int {
        helper.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $table WHERE ${UploadDbHelper.COL_STATUS} = ?",
            arrayOf(STATUS_ABANDONED)
        ).use { c -> return if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    // ---------------- state transitions ----------------

    fun markSent(table: String, id: Long, sentAt: Long) {
        val values = ContentValues().apply {
            put(UploadDbHelper.COL_STATUS, UploadDbHelper.STATUS_SENT)
            put(UploadDbHelper.COL_SENT_AT, sentAt)
            putNull(UploadDbHelper.COL_LAST_ERROR)
        }
        helper.writableDatabase.update(table, values, "${UploadDbHelper.COL_ID}=?", arrayOf(id.toString()))
    }

    /**
     * Marks a row FAILED, bumping its attempt counter and recording the error + time — and gives up on
     * it once it has failed [MAX_ATTEMPTS] times.
     *
     * The cap is the whole point. `attempts` was counted and never read, so there was no state a row
     * could reach that stopped it being retried: the drain re-sent it every fifteen minutes forever,
     * and while it sat at the head of the queue it held up every later reading with it. An abandoned
     * row is not deleted — the payload is the only copy of that capture, and a person can still pull it
     * off the device — it is simply out of the drain.
     *
     * @return true when this failure was the one that abandoned the row.
     */
    fun markFailed(table: String, id: Long, attempts: Int, error: String, at: Long): Boolean {
        val used = attempts + 1
        val giveUp = used >= MAX_ATTEMPTS
        val values = ContentValues().apply {
            put(
                UploadDbHelper.COL_STATUS,
                if (giveUp) STATUS_ABANDONED else UploadDbHelper.STATUS_FAILED
            )
            put(UploadDbHelper.COL_ATTEMPTS, used)
            put(UploadDbHelper.COL_LAST_ATTEMPT, at)
            put(UploadDbHelper.COL_LAST_ERROR, error.take(500))
        }
        helper.writableDatabase.update(table, values, "${UploadDbHelper.COL_ID}=?", arrayOf(id.toString()))
        if (giveUp) {
            Log.e(
                TAG,
                "$table row $id ABANDONED after $used attempt(s) — it will not be sent again. " +
                    "Last error: ${error.take(200)}"
            )
        }
        return giveUp
    }

    companion object {
        private const val TAG = "UploadQueue"

        /**
         * Failures before a row is given up on.
         *
         * The drain runs on WorkManager's cadence — roughly every fifteen minutes, and whenever
         * connectivity returns — so eight attempts spans a couple of hours of trying at minimum, and far
         * longer when the failures are spread over intermittent signal. That is generous for anything
         * transient and short enough that a genuinely unsendable row leaves the queue the same day
         * rather than blocking it for the life of the device.
         *
         * A row that fails this many times is not failing for want of a network: WorkManager only runs
         * the drain when there is one.
         */
        val MAX_ATTEMPTS: Int get() = DeviceConfig.int("upload.upload.queue_max_attempts", 8)

        /**
         * Status for a row the queue has given up on: kept, never retried.
         *
         * It lives here rather than beside its siblings in [UploadDbHelper] because that file is frozen
         * (see `freeze_files.md`) — and it needs no schema change to work, since `status` is a plain TEXT
         * column and every read filters on the two values it must exclude rather than matching a list of
         * the ones it wants.
         */
        const val STATUS_ABANDONED = "ABANDONED"

        /** The one selection both queues drain by: not delivered, and not given up on. */
        private val NOT_DELIVERABLE =
            "${UploadDbHelper.COL_STATUS} != ? AND ${UploadDbHelper.COL_STATUS} != ?"
    }
}
