package com.terminalscout.app.prod.capture

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.util.Log
import com.terminalscout.app.prod.capture.LocationMapDbHelper.Companion.COL_BRAND
import com.terminalscout.app.prod.capture.LocationMapDbHelper.Companion.COL_ID
import com.terminalscout.app.prod.capture.LocationMapDbHelper.Companion.COL_LAST_CAPTURE_TIME
import com.terminalscout.app.prod.capture.LocationMapDbHelper.Companion.COL_LATITUDE
import com.terminalscout.app.prod.capture.LocationMapDbHelper.Companion.COL_LONGITUDE
import com.terminalscout.app.prod.capture.LocationMapDbHelper.Companion.COL_MODIFIED_TIME
import com.terminalscout.app.prod.capture.LocationMapDbHelper.Companion.COL_NAME
import com.terminalscout.app.prod.capture.LocationMapDbHelper.Companion.TABLE
import java.io.File

/**
 * One fuel station on the device's location map.
 *
 * @param id                the Azure Maps POI id, and the table's primary key. Stable across sweeps,
 *                          which is what lets a refresh update a row without resetting
 *                          [lastCaptureTimeMs].
 * @param name              the POI name, for logs and the status line. Never used for matching.
 * @param brand             the operator (Olerex, Circle K, …). Never blank on a row a sweep wrote — a
 *                          brand is a precondition for the station being in this table at all
 *                          ([AzureMapsStations.BRAND_REQUIRED_NOTE]).
 * @param lastCaptureTimeMs epoch ms of the last video actually captured at this station, or **null
 *                          when none ever has been**. Null rather than 0 because "never" and "at the
 *                          epoch" are different facts. Written by the capture, not by the trigger —
 *                          see [markCaptured].
 * @param modifiedTimeMs    epoch ms of the last refresh that updated this row.
 *
 * There is deliberately no fuel-type list. Azure Maps publishes none, and the price-board row order
 * that `upload/StationDirectory` carries cannot be derived from a POI record.
 */
data class FuelStation(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val brand: String,
    val lastCaptureTimeMs: Long? = null,
    val modifiedTimeMs: Long = 0L
)

/**
 * STAGE 0 — the location map. Every branded Estonian fuel station the device knows about, in SQLite.
 *
 * ### Where it lives
 * Table `location_mapping` in `location_map.db` — see [LocationMapDbHelper] for the schema and for why
 * it is a separate database from the upload queues.
 *
 * This **replaced a JSON file** at `filesDir/location_mapping/location_map.json`. [deleteLegacyJson]
 * removes it on first use, so a device updated from the previous build does not keep a stale copy of
 * the map lying around next to the live one.
 *
 * ### Held in memory, because the trigger reads it constantly
 * [LocationTrigger] compares every GPS fix against every station, at the API-configured interval. Querying SQLite
 * at that rate would work — 349 rows is nothing — but it would be the only real cost in an otherwise
 * free loop, so [cached] holds the parsed list and [invalidate] is what a write calls to drop it.
 *
 * ### API-backed membership
 * A successful sweep removes stations the API no longer serves. Rows still served are updated in
 * place so their capture history survives.
 */
object LocationMap {

    private const val TAG = "LocationMap"

    /** The JSON store this table replaced. Removed on first use. */
    private const val LEGACY_DIR = "location_mapping"
    private const val LEGACY_FILE = "location_map.json"

    /**
     * The id prefixes the retired APK-bundled seed used. Kept only so [purgeBundled] can recognise its
     * leftovers on a device that upgraded; nothing writes these any more.
     */
    private const val BUNDLED_SURVEYED = "surveyed:"
    private const val BUNDLED_DETECTED = "detected:"

    @Volatile
    private var cached: List<FuelStation>? = null

    private val columns = arrayOf(
        COL_ID, COL_LATITUDE, COL_LONGITUDE, COL_NAME, COL_BRAND,
        COL_LAST_CAPTURE_TIME, COL_MODIFIED_TIME
    )

    // ---- Reads --------------------------------------------------------------------------------- //

    /**
     * Every station, parsed once and held.
     *
     * Returns an empty list when the table is empty — the trigger then simply never fires, which is
     * the correct behaviour for a device that has never had a connection.
     */
    fun stations(context: Context): List<FuelStation> {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val loaded = query(context)
            cached = loaded
            return loaded
        }
    }

    fun count(context: Context): Int = stations(context).size

    /** True once a sweep has written at least one row. */
    fun exists(context: Context): Boolean = count(context) > 0

    /**
     * The newest `modified_time` in the table, or 0 when it is empty — i.e. when the last successful
     * sweep ran. Drives [LocationMapRefreshScheduler]'s staleness check.
     */
    fun lastRefreshMs(context: Context): Long = try {
        LocationMapDbHelper(context).readableDatabase.use { db ->
            db.rawQuery("SELECT MAX($COL_MODIFIED_TIME) FROM $TABLE", null).use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
            }
        }
    } catch (e: Exception) {
        // 0 means "never swept", which is what the staleness check treats as due-a-refresh. An
        // unreadable table therefore asks for a fetch rather than throwing into a WorkManager worker
        // or into the scheduler that arms it — both of which run with nobody watching.
        Log.e(TAG, "could not read the last refresh time — reporting never", e)
        0L
    }

    @SuppressLint("Recycle") // closed by `use`
    private fun query(context: Context): List<FuelStation> = try {
        LocationMapDbHelper(context).readableDatabase.use { db ->
            db.query(TABLE, columns, null, null, null, null, "$COL_NAME ASC").use { c ->
                val out = ArrayList<FuelStation>(c.count)
                while (c.moveToNext()) {
                    out += FuelStation(
                        id = c.getString(0),
                        latitude = c.getDouble(1),
                        longitude = c.getDouble(2),
                        name = c.getString(3),
                        brand = c.getString(4),
                        lastCaptureTimeMs = if (c.isNull(5)) null else c.getLong(5),
                        modifiedTimeMs = c.getLong(6)
                    )
                }
                if (out.isEmpty()) {
                    Log.i(TAG, "location_mapping is empty — the trigger will not fire until a sweep runs")
                }
                out
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "could not read location_mapping — treating the map as absent", e)
        emptyList()
    }

    // ---- Writes -------------------------------------------------------------------------------- //

    /** Drops the in-memory copy so the next [stations] call re-reads the table. */
    fun invalidate() {
        cached = null
    }

    /**
     * Inserts new stations and updates the ones already known, in one transaction.
     *
     * **[COL_LAST_CAPTURE_TIME] is never written here.** That is the whole reason this is an upsert
     * rather than a delete-and-reinsert: a daily sweep must not be able to forget that a station was
     * captured. The insert leaves it NULL for a station seen for the first time; the update does not
     * mention the column at all, so an existing value survives untouched.
     *
     * A successful nonempty sweep also deletes rows absent from the response in the same transaction.
     * A failed write rolls back inserts, updates, and deletes together.
     *
     * @param nowMs the sweep's timestamp, shared by all rows it updates.
     * @return how many rows were inserted, updated, and removed, or null if the write failed.
     */
    fun upsertAll(context: Context, stations: List<FuelStation>, nowMs: Long): UpsertResult? {
        if (stations.isEmpty()) {
            Log.w(TAG, "refusing to apply an empty sweep — keeping whatever the table holds")
            return null
        }
        deleteLegacyJson(context)

        return try {
            LocationMapDbHelper(context).writableDatabase.use { db ->
                var inserted = 0
                var updated = 0
                var removed = 0
                db.beginTransaction()
                try {
                    val servedIds = stations.mapTo(HashSet(stations.size)) { it.id }
                    for (s in stations) {
                        // Update first, insert only if it matched nothing. The other order would need
                        // INSERT OR IGNORE followed by an UPDATE, which writes every row twice.
                        val values = ContentValues().apply {
                            put(COL_LATITUDE, s.latitude)
                            put(COL_LONGITUDE, s.longitude)
                            put(COL_NAME, s.name)
                            put(COL_BRAND, s.brand)
                            put(COL_MODIFIED_TIME, nowMs)
                        }
                        val rows = db.update(TABLE, values, "$COL_ID = ?", arrayOf(s.id))
                        if (rows > 0) {
                            updated++
                        } else {
                            values.put(COL_ID, s.id)
                            // Explicitly NULL: a station seen for the first time has never been
                            // captured, and that has to be the stored value rather than a default.
                            values.putNull(COL_LAST_CAPTURE_TIME)
                            if (db.insert(TABLE, null, values) == -1L) {
                                error("could not insert station ${s.id}")
                            }
                            inserted++
                        }
                    }
                    val missingIds = ArrayList<String>()
                    db.query(TABLE, arrayOf(COL_ID), null, null, null, null, null).use { c ->
                        while (c.moveToNext()) {
                            val id = c.getString(0)
                            if (id !in servedIds) missingIds += id
                        }
                    }
                    for (id in missingIds) {
                        removed += db.delete(TABLE, "$COL_ID = ?", arrayOf(id))
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
                invalidate()
                Log.i(TAG, "location_mapping: $inserted inserted, $updated updated, $removed removed")
                UpsertResult(inserted = inserted, updated = updated, removed = removed)
            }
        } catch (e: Exception) {
            Log.e(TAG, "failed to write location_mapping", e)
            null
        }
    }

    /**
     * Deletes the rows the APK used to seed — `surveyed:*` and `detected:*` — that the server does not
     * serve. Returns how many went, or null when the delete failed.
     *
     * ### Why this exists at all
     * Until 2026-09-18 the app shipped 84 hand-surveyed sites and re-seeded them on every setup pass.
     * Setup still calls this narrow cleanup before the next successful API refresh; [upsertAll] then
     * removes every row the API no longer serves, regardless of its id prefix.
     *
     * ### It cannot delete a station the API still serves, and that is structural
     * [keepAtOrAfterMs] is the last sweep's timestamp, and a row the sweep touched carries exactly that
     * value — so `modified_time < keepAtOrAfterMs` is precisely "the last sweep did not return this
     * row". The server does serve a handful of the old ids; those are refreshed by every sweep, sit at
     * the sweep timestamp, and are therefore invisible to this delete. Without that clause the purge
     * and the sweep would fight: the sweep would re-insert the row, the next setup pass would delete
     * it again, and `last_capture_time` would be reset on every launch.
     *
     * It is also why this needs no "already done" flag. After the first run there is nothing left that
     * matches, so it is idempotent and costs one indexed DELETE per setup pass. On a table that has
     * never been swept (`keepAtOrAfterMs` 0, or every row at the same timestamp) it deletes nothing,
     * which is the safe direction to fail in.
     *
     * @param keepAtOrAfterMs rows at or after this `modified_time` are kept. Pass [lastRefreshMs], or
     *   the sweep's own timestamp when calling straight after one.
     */
    fun purgeBundled(context: Context, keepAtOrAfterMs: Long): Int? {
        if (keepAtOrAfterMs <= 0L) {
            Log.d(TAG, "no sweep to compare against — not purging bundled rows")
            return 0
        }
        return try {
            LocationMapDbHelper(context).writableDatabase.use { db ->
                val removed = db.delete(
                    TABLE,
                    "($COL_ID LIKE ? OR $COL_ID LIKE ?) AND $COL_MODIFIED_TIME < ?",
                    arrayOf("$BUNDLED_SURVEYED%", "$BUNDLED_DETECTED%", keepAtOrAfterMs.toString())
                )
                if (removed > 0) {
                    invalidate()
                    Log.i(TAG, "purged $removed bundled station(s) the API does not serve")
                } else {
                    Log.d(TAG, "no bundled stations left to purge")
                }
                removed
            }
        } catch (e: Exception) {
            Log.e(TAG, "could not purge the bundled stations", e)
            null
        }
    }

    /**
     * Stamps [atMs] onto a station's `last_capture_time`.
     *
     * **Called by the capture, not by the trigger.** `CaptureLane` invokes it once a clip has actually
     * finalized on disk, so the column means "a video was captured here", not "a capture was asked
     * for". The distinction is not academic: a start that arrives while the lane is already recording
     * is discarded, and a camera that fails to bind produces no clip — stamping on the request would
     * record captures that never happened.
     *
     * Never fatal. Failing to record a timestamp must not affect a clip that has already been written.
     */
    fun markCaptured(context: Context, stationId: String, atMs: Long) {
        try {
            LocationMapDbHelper(context).writableDatabase.use { db ->
                val values = ContentValues().apply { put(COL_LAST_CAPTURE_TIME, atMs) }
                val rows = db.update(TABLE, values, "$COL_ID = ?", arrayOf(stationId))
                if (rows == 0) {
                    Log.w(TAG, "no row for station $stationId — capture time not recorded")
                } else {
                    invalidate()
                }
            }
        } catch (e: Exception) {
            // Never fatal: failing to record a timestamp must not stop a capture happening.
            Log.e(TAG, "could not record last_capture_time for $stationId", e)
        }
    }

    /** How many rows a sweep changed. */
    data class UpsertResult(val inserted: Int, val updated: Int, val removed: Int) {
        val touched: Int get() = inserted + updated
    }

    // ---- Migration off the JSON file ----------------------------------------------------------- //

    /**
     * Removes the JSON map this table replaced.
     *
     * Not migrated into the table: the JSON carried no capture history and no ids the sweep will not
     * supply within a day anyway, so importing it would add a code path for no information. Deleting
     * it stops a device that has been updated from carrying two maps, one of them silently dead.
     */
    fun deleteLegacyJson(context: Context) {
        // Guarded: `delete` throws SecurityException, and this is reached from the middle of a sweep
        // that is writing the station map. Failing to remove a file the app stopped reading a build ago
        // must not cost the sweep.
        runCatching {
            val legacy = File(File(context.filesDir, LEGACY_DIR), LEGACY_FILE)
            if (legacy.exists() && legacy.delete()) {
                Log.i(TAG, "removed the legacy JSON location map — the table is the store now")
            }
        }.onFailure { Log.w(TAG, "could not remove the legacy JSON location map", it) }
    }
}
