package com.terminalscout.app.prod.capture

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * STAGE 0 — the SQLite database behind the location map.
 *
 * ### Its own database, not a table in `uploads.db`
 * `upload/UploadDbHelper` declares a deliberately **destructive** `onUpgrade` — it drops and recreates,
 * on the argument that its two tables are transient upload buffers and losing them costs nothing. That
 * argument does not hold here: [COL_LAST_CAPTURE_TIME] is history that cannot be re-derived from
 * anywhere, so a schema bump on the upload queues must not be able to erase it. Separate file,
 * separate version counter.
 *
 * Raw [SQLiteOpenHelper] rather than Room, for the same reason that file gives: one flat table with
 * simple CRUD does not justify a KSP annotation processor in this build.
 *
 * ### The table
 *
 *     location_mapping
 *       id                  TEXT    PRIMARY KEY   the Azure Maps POI id
 *       latitude            REAL    NOT NULL
 *       longitude           REAL    NOT NULL
 *       name                TEXT    NOT NULL
 *       brand               TEXT    NOT NULL      the operator; never blank on a swept row
 *       last_capture_time   INTEGER NULL          epoch ms, NULL = never; written by the capture
 *       modified_time       INTEGER NOT NULL      epoch ms, written whenever a refresh updates the row
 *
 * **`id` is one column beyond the requested list, and it is load-bearing.** The refresh has to update
 * rows without resetting [COL_LAST_CAPTURE_TIME], which needs a stable key. The coordinate pair is not
 * one — Azure nudges a position between sweeps and the row would silently become a new station with no
 * capture history. `name` is worse: "OLEREX" occurs 64 times in the live data.
 *
 * **`last_capture_time` is NULL-able on purpose.** A station never captured and a station captured at
 * epoch 0 are different facts, and a sentinel like `0` collapses them.
 */
class LocationMapDbHelper(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
                $COL_ID                TEXT    PRIMARY KEY,
                $COL_LATITUDE          REAL    NOT NULL,
                $COL_LONGITUDE         REAL    NOT NULL,
                $COL_NAME              TEXT    NOT NULL,
                $COL_BRAND             TEXT    NOT NULL DEFAULT '',
                $COL_LAST_CAPTURE_TIME INTEGER,
                $COL_MODIFIED_TIME     INTEGER NOT NULL
            )
            """.trimIndent()
        )
        // The trigger reads every row on every load, so nothing here needs an index for the read path.
        // This one serves the refresh scheduler's staleness check (`MAX(modified_time)`).
        db.execSQL("CREATE INDEX idx_location_modified ON $TABLE($COL_MODIFIED_TIME)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Intentionally NOT destructive, unlike the upload queues: last_capture_time is not
        // recoverable from anywhere, so dropping the table is the one thing this database exists to
        // prevent. Each step migrates in place.
        if (oldVersion < 2) {
            // v1 had no brand. Added with an empty default because ALTER TABLE cannot add a NOT NULL
            // column without one; the next sweep fills every row it returns. A row left blank is a row
            // Azure no longer returns, which is the same signal `modified_time` already carries.
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN $COL_BRAND TEXT NOT NULL DEFAULT ''")
        }
    }

    companion object {
        private const val DB_NAME = "location_map.db"

        /** 1 = original. 2 = added [COL_BRAND]. */
        private const val DB_VERSION = 2

        const val TABLE = "location_mapping"

        const val COL_ID = "id"
        const val COL_LATITUDE = "latitude"
        const val COL_LONGITUDE = "longitude"
        const val COL_NAME = "name"
        const val COL_BRAND = "brand"
        const val COL_LAST_CAPTURE_TIME = "last_capture_time"
        const val COL_MODIFIED_TIME = "modified_time"
    }
}
