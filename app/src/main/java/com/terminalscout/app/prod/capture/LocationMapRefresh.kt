package com.terminalscout.app.prod.capture

import android.content.Context
import android.util.Log
import com.terminalscout.app.prod.ui.ErrorLog

/**
 * STAGE 0 — `fetchLocationMap` itself, with no WorkManager in it.
 *
 * Lifted out of [LocationMapRefreshWorker] on 2026-09-18 so the menu's REFRESH STATION MAP button and
 * the 02:00 Tallinn job cannot drift apart. The worker is now a thin adapter that maps [Outcome] onto a
 * `Result`; every guarantee that used to be written on the worker lives here, because this is the code
 * that makes them:
 *
 *  - **Failure never destroys the map already held.** [SystemApiStations.fetchAll] returns null when the
 *    answer could not be trusted, [LocationMap.upsertAll] refuses an empty list, and an empty sweep is
 *    disbelieved outright — Estonia has hundreds of forecourts. In all three cases the stored rows stand,
 *    so a device that has swept once keeps triggering forever offline.
 *  - **Successful refreshes use the API as the source of truth.** Rows returned by the API are
 *    upserted in place to preserve `last_capture_time`; rows absent from the response are deleted
 *    in the same transaction. The menu button and the nightly job use this same path.
 *
 * Suspending and safe to call from a UI scope: the transport is already on IO and the database work is
 * small. The caller decides what to do about a failure — the worker retries with backoff, the button
 * says so on screen.
 */
object LocationMapRefresh {

    private const val TAG = "LocationMapRefresh"

    /** No fix screen: a failed sweep is a network or quota problem, not something to tap through. */
    const val SOURCE = "Station map"
    const val ERR_FETCH_FAILED = "map_fetch_failed"
    const val MSG_FETCH_FAILED = "Could not refresh the station map. The last one is still in use."

    /**
     * What one refresh did. Every branch except [Refreshed] left the stored map exactly as it was.
     *
     * [NotConfigured] is deliberately separate from the failures: no amount of retrying produces a
     * credential that was never compiled in, so it is neither an error to show nor a reason to back off.
     */
    sealed interface Outcome {
        data class Refreshed(
            val swept: Int,
            val inserted: Int,
            val updated: Int,
            val total: Int,
            /** Rows the API no longer serves, removed by this refresh. */
            val removed: Int
        ) : Outcome

        object NotConfigured : Outcome
        object FetchFailed : Outcome
        object EmptySweep : Outcome
        object WriteFailed : Outcome
    }

    suspend fun runOnce(context: Context): Outcome {
        val appContext = context.applicationContext

        if (!SystemApiStations.isEnabled) {
            Log.w(TAG, "no API base URL / fleet secret — skipping the location-map refresh")
            return Outcome.NotConfigured
        }

        Log.i(TAG, "location-map refresh starting")
        val stations = SystemApiStations.fetchAll(appContext)

        if (stations == null) {
            Log.w(TAG, "fetch failed — keeping the stored map")
            ErrorLog.showError(ERR_FETCH_FAILED, MSG_FETCH_FAILED, SOURCE)
            return Outcome.FetchFailed
        }
        if (stations.isEmpty()) {
            // A successful sweep that found nothing is a service-side answer we do not believe.
            Log.w(TAG, "sweep returned no stations — keeping the stored map")
            return Outcome.EmptySweep
        }

        // One timestamp for all rows returned by this sweep.
        val sweptAtMs = System.currentTimeMillis()
        val result = LocationMap.upsertAll(appContext, stations, sweptAtMs)
        if (result == null) {
            Log.e(TAG, "could not write location_mapping")
            return Outcome.WriteFailed
        }

        ErrorLog.removeErrorByKey(ERR_FETCH_FAILED)

        val total = LocationMap.count(appContext)
        val brands = stations.groupingBy { it.brand }.eachCount()
            .entries.sortedByDescending { it.value }
            .joinToString { it.key + " " + it.value }
        Log.i(
            TAG,
            "location map refreshed: ${stations.size} station(s) swept — ${result.inserted} new, " +
                "${result.updated} updated, ${result.removed} removed, $total row(s) in the table" +
                " — $brands"
        )
        return Outcome.Refreshed(
            swept = stations.size,
            inserted = result.inserted,
            updated = result.updated,
            total = total,
            removed = result.removed
        )
    }
}
