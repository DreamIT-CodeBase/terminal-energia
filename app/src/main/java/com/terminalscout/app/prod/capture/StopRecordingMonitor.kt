package com.terminalscout.app.prod.capture

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.terminalscout.app.prod.ContainedFaults
import com.terminalscout.app.prod.crashproof
import com.terminalscout.app.prod.upload.StationDirectory

/**
 * STAGE 0 — `stopRecording`. An always-on background watch over the frozen list.
 *
 * ### Why this is its own function and not part of the trigger
 * The start edge and the stop edge answer different questions. The trigger asks *"is there a station
 * I should be recording?"* — a question about the whole map. This asks *"have I left the board I am
 * already committed to?"* — a question about [FrozenBoards], and about nothing else on the map.
 *
 * Folding them together is what the old design did, and it could only ever hold **one** station at a
 * time. Frozen boards are a set: a truck can pass two forecourts inside a minute, capture at both, and
 * then need both released as it leaves. A single `armedAt` field cannot express that.
 *
 * ### It does not depend on the UI
 * Attached by `CaptureLane` inside the foreground service, with its own location subscription. Two
 * subscriptions rather than sharing the trigger's is deliberate: the fused provider merges concurrent
 * requests and services the most demanding one, so a second listener at the same interval costs
 * essentially nothing, and the two functions stay genuinely independent — one can be detached without
 * blinding the other.
 *
 * ### What it does on every fix
 * Stop the active station's clip once the vehicle has traveled 50 m from its closest observed
 * approach and three consecutive fixes show at least 10 m of increasing station distance. Separately,
 * unfreeze each board past [LocationTrigger.EXIT_RADIUS_M] for a future visit.
 *
 * Naming the board on the stop matters. An unqualified stop would end whatever happened to be
 * recording, and with more than one board frozen that is regularly a *different* station's clip —
 * leaving one station's recording truncated because the truck drove out of range of another.
 */
class StopRecordingMonitor(
    context: Context,
    private val sink: CaptureTriggerSink
) {

    private val appContext = context.applicationContext
    private var recordingStationId: String? = null
    private var departureTrend = DepartureTrend()
    private var stopRequested = false
    private var previousFix: VehicleFix.Fix? = null
    private var startFixPending = false

    fun recordingStarted(stationId: String?, startFix: VehicleFix.Fix?) {
        recordingStationId = stationId
        departureTrend = DepartureTrend()
        stopRequested = false
        previousFix = startFix
        startFixPending = startFix != null
    }

    fun recordingFinished() {
        recordingStationId = null
        departureTrend = DepartureTrend()
        stopRequested = false
        previousFix = null
        startFixPending = false
    }

    /** The fused provider, or null when this device cannot supply one. See [LocationTrigger.client]. */
    private val client: FusedLocationProviderClient? by lazy {
        runCatching { LocationServices.getFusedLocationProviderClient(appContext) }
            .onFailure { Log.e(TAG, "no fused location provider on this device", it) }
            .getOrNull()
    }

    private val callback = object : LocationCallback() {
        /**
         * Guarded whole, for the same reason as [LocationTrigger]'s: Play services calls this on the main
         * looper with no frame of ours below it, so a throw is a process death. Losing one fix delays a
         * board's release by one configured location interval and delays nothing else.
         */
        override fun onLocationResult(result: LocationResult) {
            crashproof(SOURCE, ContainedFaults.STOP_MONITOR, "onLocationResult") {
                val fix = result.lastLocation ?: return@crashproof
                // This monitor keeps its own subscription, so it can be the one holding a fresh fix when
                // the trigger is detached — see [VehicleFix] on why both writers exist.
                VehicleFix.record(fix.latitude, fix.longitude, fix.accuracy)
                onFix(fix.latitude, fix.longitude)
            }
        }
    }

    @SuppressLint("MissingPermission") // guarded by hasLocationPermission()
    fun start() = crashproof(SOURCE, ContainedFaults.STOP_MONITOR, "start") {
        if (!hasLocationPermission()) {
            Log.e(TAG, "no location permission — frozen boards will never be released")
            return@crashproof
        }
        val provider = client
        if (provider == null) {
            Log.e(TAG, "no location provider — frozen boards will never be released")
            return@crashproof
        }
        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            LocationTrigger.UPDATE_INTERVAL_MS
        )
            .setMinUpdateIntervalMillis(LocationTrigger.UPDATE_INTERVAL_MS)
            .setMinUpdateDistanceMeters(LocationTrigger.MIN_UPDATE_DISTANCE_M)
            .build()

        provider.requestLocationUpdates(request, callback, appContext.mainLooper)
        // Set only once updates are genuinely requested, so the flag means "watching", not "the object
        // exists" — the early return above leaves it false, which is the distinction Stage0Health needs.
        watching = this
        isWatching = true
        Log.i(
            TAG,
            "watching the frozen list — releasing past ${LocationTrigger.EXIT_RADIUS_M.toInt()}m"
        )
    }

    fun stop() = crashproof(SOURCE, ContainedFaults.STOP_MONITOR, "stop") {
        // Only the monitor that is currently reporting may clear the flag. Two service instances overlap
        // for a moment whenever the app recovers itself, and their onDestroy / onCreate are not ordered —
        // see the same guard in LocationTrigger.detach for what unconditional clearing cost there.
        if (watching === this) {
            watching = null
            isWatching = false
        }
        // Separately guarded, so a provider that refuses the removal cannot stop the clear below it.
        runCatching { client?.removeLocationUpdates(callback) }
            .onFailure { Log.w(TAG, "could not remove the location updates", it) }
        recordingFinished()
        // Every remaining freeze is now unreleasable: this was the only thing that clears them.
        FrozenBoards.clear()
        Log.i(TAG, "stopped watching the frozen list")
    }

    /**
     * One fix against every frozen board.
     *
     * Iterates a snapshot of the ids so unfreezing mid-loop is safe, and resolves each id through the
     * location map because [FrozenBoards] stores ids rather than coordinates — the map is the one place
     * a station's position is defined, and it is already held in memory.
     */
    private fun onFix(latitude: Double, longitude: Double) {
        val frozenIds = FrozenBoards.ids()
        val activeId = recordingStationId
        if (frozenIds.isEmpty() && activeId == null) return

        val byId = LocationMap.stations(appContext).associateBy { it.id }

        // Keep tracking the rolling clip even if its station has already passed the
        // 130 m re-arm boundary before the departure trend was confirmed.
        if (activeId != null && !stopRequested) {
            byId[activeId]?.let { station ->
                val metres = StationDirectory.distanceMetres(
                    latitude, longitude, station.latitude, station.longitude
                )
                val previous = previousFix
                if (startFixPending && previous != null) {
                    val startMetres = StationDirectory.distanceMetres(
                        previous.latitude, previous.longitude, station.latitude, station.longitude
                    )
                    departureTrend.observe(startMetres, 0.0)
                    startFixPending = false
                }
                val stepMetres = previous?.let {
                    StationDirectory.distanceMetres(
                        it.latitude, it.longitude, latitude, longitude
                    )
                } ?: 0.0
                previousFix = VehicleFix.last
                if (departureTrend.observe(metres, stepMetres)) {
                    val reason = "leaving ${station.name} — traveled 50m past closest approach"
                    Log.i(TAG, "STOP — $reason")
                    stopRequested = true
                    sink.requestStop(reason, stationId = activeId)
                }
            }
        }

        for (id in frozenIds) {
            val station = byId[id]
            if (station == null) {
                // Nothing to measure against. Releasing is the only safe answer: a board that cannot be
                // located can never satisfy the departure test, so holding it would freeze it forever.
                Log.w(TAG, "$id is frozen but not in the location map — releasing it")
                FrozenBoards.unfreeze(id)
                continue
            }

            val metres = StationDirectory.distanceMetres(
                latitude, longitude, station.latitude, station.longitude
            )
            if (metres > LocationTrigger.EXIT_RADIUS_M) {
                val reason = "left ${station.name} — ${metres.toInt()}m > " +
                    "${LocationTrigger.EXIT_RADIUS_M.toInt()}m"
                Log.i(TAG, "RELEASE — $reason")
                FrozenBoards.unfreeze(id)
            }
        }
    }

    // Fails CLOSED, like the trigger's: a read that threw must not lead to an unpermitted
    // `requestLocationUpdates`, which answers with a SecurityException rather than a false.
    private fun hasLocationPermission(): Boolean = try {
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) {
        Log.e(TAG, "could not read the location permission — treating it as absent", t)
        false
    }

    companion object {
        private const val TAG = "StopRecordingMonitor"

        /** Shown under this monitor's rows on the errors screen. */
        const val SOURCE = "Stop watch"

        /**
         * True while the frozen list is actually being watched.
         *
         * Read by [com.terminalscout.app.prod.Stage0Health]. It matters more than it looks: with this
         * off, a board frozen by a capture is never released, so every station the unit visits becomes
         * capturable exactly once for the life of the process and then silently stops producing clips.
         */
        @Volatile
        var isWatching: Boolean = false
            private set

        /** Which monitor [isWatching] is reporting on. See [stop] for why identity matters. */
        @Volatile
        private var watching: StopRecordingMonitor? = null
    }
}
