package com.terminalscout.app.prod.upload

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.provider.Settings

/**
 * On-device SQLite store for the single "user" record holding the identity the pipeline can't infer
 * (device id + vehicle id).
 *
 * There is exactly one row (id = [ROW_ID]) — the app is single-user, so "create" and "edit" are the
 * same upsert. The details screen is prefilled from [load] when a row exists and left empty the
 * first time (when it returns null). Every uploaded payload is enriched from [loadOrDefault].
 *
 * Raw [SQLiteOpenHelper] (not Room) matches [UploadDbHelper]: one flat table with trivial CRUD does
 * not justify a KSP annotation processor in this Kotlin 2.3 build.
 */
class UserStore(context: Context) {

    private val appContext = context.applicationContext
    private val helper = UserDbHelper(appContext)

    /** The saved vehicle details, or null the first time (no row yet → empty form). */
    fun load(): VehicleConfig? {
        helper.readableDatabase.query(
            UserDbHelper.TABLE_USER,
            null,
            "${UserDbHelper.COL_ID} = ?",
            arrayOf(ROW_ID.toString()),
            null, null, null
        ).use { c ->
            if (!c.moveToFirst()) return null
            fun str(col: String) = c.getString(c.getColumnIndexOrThrow(col)).orEmpty()
            return VehicleConfig(
                deviceId = str(UserDbHelper.COL_DEVICE_ID),
                vehicleId = str(UserDbHelper.COL_VEHICLE_ID)
            )
        }
    }

    /** True once the user has saved their details at least once. */
    fun exists(): Boolean = load() != null

    /**
     * The saved details, always with the authoritative device id ([deviceId]) — never the stored
     * column — so the payload's device id can't drift from the hardware. Falls back to a blank
     * vehicle id when nothing is saved yet.
     */
    fun loadOrDefault(): VehicleConfig =
        (load() ?: VehicleConfig(deviceId = deviceId(), vehicleId = "")).copy(deviceId = deviceId())

    /** Insert-or-replace the single user row. */
    fun save(config: VehicleConfig) {
        val values = ContentValues().apply {
            put(UserDbHelper.COL_ID, ROW_ID)
            put(UserDbHelper.COL_DEVICE_ID, config.deviceId.trim().ifBlank { deviceId() })
            put(UserDbHelper.COL_VEHICLE_ID, config.vehicleId.trim())
        }
        helper.writableDatabase.insertWithOnConflict(
            UserDbHelper.TABLE_USER, null, values, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    /**
     * The device's own identifier, read from the device itself — the real hardware serial requires a
     * privileged permission and returns "unknown" for normal apps on Android 8+, so we derive a
     * stable per-install id from [Settings.Secure.ANDROID_ID]. Shown read-only on the details screen
     * and used as the payload's device id.
     */
    @Suppress("HardwareIds") // ANDROID_ID is fine as a non-resettable-but-per-app device tag here.
    fun deviceId(): String {
        val androidId = Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
        return "IOT-TRK-" + (androidId?.takeLast(6)?.uppercase() ?: "000000")
    }

    companion object {
        private const val ROW_ID = 1L
    }
}

/** SQLite helper backing [UserStore] — a single-table `user` database. */
class UserDbHelper(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_USER (
                $COL_ID         INTEGER PRIMARY KEY,
                $COL_DEVICE_ID  TEXT NOT NULL DEFAULT '',
                $COL_VEHICLE_ID TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_USER")
        onCreate(db)
    }

    companion object {
        private const val DB_NAME = "user.db"
        // v2: dropped the unused station_brand / currency / unit columns.
        private const val DB_VERSION = 2

        const val TABLE_USER = "user"
        const val COL_ID = "id"
        const val COL_DEVICE_ID = "device_id"
        const val COL_VEHICLE_ID = "vehicle_id"
    }
}
