package com.terminalscout.app.prod.upload

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * On-device SQLite store backing the two upload queues.
 *
 * Two tables, one per destination API:
 *   - [TABLE_READINGS] — the aggregated fuel-price payload JSON produced at the end of each
 *     pipeline run, held until it is POSTed to the readings API.
 *   - [TABLE_IMAGES] — one row per low-confidence frame the YOLO gate set aside, tracking the
 *     persistent image-file path plus its metadata + GPS fix, held until it is POSTed to the
 *     (separate) discarded-images API.
 *
 * Rows carry a small upload state machine (`status`, `attempts`, `last_error`, `sent_at`) so the
 * background [UploadWorker] can drain PENDING rows, retry on failure, and skip already-SENT rows.
 *
 * Raw [SQLiteOpenHelper] (rather than Room) is deliberate: two flat tables with simple CRUD don't
 * justify pulling a KSP annotation processor into this Kotlin 2.3 build.
 */
class UploadDbHelper(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_READINGS (
                $COL_ID            INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_PAYLOAD_JSON  TEXT    NOT NULL,
                $COL_CREATED_AT    INTEGER NOT NULL,
                $COL_STATUS        TEXT    NOT NULL DEFAULT '$STATUS_PENDING',
                $COL_ATTEMPTS      INTEGER NOT NULL DEFAULT 0,
                $COL_LAST_ATTEMPT  INTEGER,
                $COL_LAST_ERROR    TEXT,
                $COL_SENT_AT       INTEGER
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE $TABLE_IMAGES (
                $COL_ID            INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_IMAGE_PATH    TEXT    NOT NULL,
                $COL_FRAME_INDEX   INTEGER,
                $COL_CONFIDENCE    REAL,
                $COL_CLARITY       REAL,
                $COL_TIMESTAMP_US  INTEGER,
                $COL_DEVICE_ID     TEXT,
                $COL_VEHICLE_ID    TEXT,
                $COL_LATITUDE      REAL,
                $COL_LONGITUDE     REAL,
                $COL_GPS_ACCURACY  REAL,
                $COL_CAPTURED_AT   INTEGER NOT NULL,
                $COL_METADATA_JSON TEXT,
                $COL_STATUS        TEXT    NOT NULL DEFAULT '$STATUS_PENDING',
                $COL_ATTEMPTS      INTEGER NOT NULL DEFAULT 0,
                $COL_LAST_ATTEMPT  INTEGER,
                $COL_LAST_ERROR    TEXT,
                $COL_SENT_AT       INTEGER
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_readings_status ON $TABLE_READINGS($COL_STATUS)")
        db.execSQL("CREATE INDEX idx_images_status ON $TABLE_IMAGES($COL_STATUS)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // No migrations yet — the queues are transient upload buffers, so a destructive
        // recreate is acceptable on a schema bump.
        db.execSQL("DROP TABLE IF EXISTS $TABLE_READINGS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_IMAGES")
        onCreate(db)
    }

    companion object {
        private const val DB_NAME = "uploads.db"
        private const val DB_VERSION = 1

        const val TABLE_READINGS = "reading_uploads"
        const val TABLE_IMAGES = "image_uploads"

        // Shared columns
        const val COL_ID = "id"
        const val COL_STATUS = "status"
        const val COL_ATTEMPTS = "attempts"
        const val COL_LAST_ATTEMPT = "last_attempt_at"
        const val COL_LAST_ERROR = "last_error"
        const val COL_SENT_AT = "sent_at"

        // reading_uploads
        const val COL_PAYLOAD_JSON = "payload_json"
        const val COL_CREATED_AT = "created_at"

        // image_uploads
        const val COL_IMAGE_PATH = "image_path"
        const val COL_FRAME_INDEX = "frame_index"
        const val COL_CONFIDENCE = "confidence"
        const val COL_CLARITY = "clarity_score"
        const val COL_TIMESTAMP_US = "timestamp_us"
        const val COL_DEVICE_ID = "device_id"
        const val COL_VEHICLE_ID = "vehicle_id"
        const val COL_LATITUDE = "latitude"
        const val COL_LONGITUDE = "longitude"
        const val COL_GPS_ACCURACY = "gps_accuracy_m"
        const val COL_CAPTURED_AT = "captured_at"
        const val COL_METADATA_JSON = "metadata_json"

        // status values
        const val STATUS_PENDING = "PENDING"
        const val STATUS_SENT = "SENT"
        const val STATUS_FAILED = "FAILED"
    }
}
