package com.terminalscout.app.prod.capture

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.SystemClock
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
import com.terminalscout.app.prod.crashproofOr
import com.terminalscout.app.prod.service.FleetBus
import com.terminalscout.app.prod.ui.AppErrors
import com.terminalscout.app.prod.ui.ErrorLog
import com.terminalscout.app.prod.upload.StationDirectory
import com.terminalscout.app.prod.config.DeviceConfig

/**
 * STAGE 0 — `locationTrigger`. The always-running comparison of where the device is against where the
 * fuel stations are.
 *
 * This is the autonomous trigger, and registering it is what makes the app autonomous: it takes no
 * input, reads the GPS and the map itself, and emits nothing but start/stop requests into the
 * [CaptureTriggerSink].
 *
 * ### It does not depend on the UI
 * It is attached by `FleetService`, which is a foreground service, so it keeps running with the app
 * closed, swiped out of recents, or after a reboot. Two permissions make that real rather than
 * nominal, and both are easy to miss:
 *
 *  - `ACCESS_BACKGROUND_LOCATION` — from Android 10, location updates stop being delivered once the
 *    app is no longer visible without it. The service stays alive and simply goes blind.
 *  - the `location` foreground-service type on the service itself.
 *
 * [isBackgroundCapable] reports whether the background grant is actually held, so a device missing it
 * says so on the bus instead of quietly never triggering.
 *
 * ### It only asks for starts
 * The stop edge moved out to [StopRecordingMonitor]. This function now answers one question — *is
 * there a station I should be recording?* — and never decides when to stop, because stopping is about
 * the board a capture is already committed to rather than about the map.
 *
 * ### Enter at 80 m while moving; a captured board is frozen until 130 m
 * Two fresh fixes at or above 10 km/h are required before an automatic capture. When a fix has no
 * reported speed, movement is estimated from consecutive positions and their elapsed times.
 * A parked vehicle therefore does not start a new automatic clip just because it is inside 80 m.
 *
 * A vehicle that stops at a forecourt stays inside 80 m, so without a guard this would fire on every fix
 * seconds for as long as it is parked and each fire would be another clip. [FrozenBoards] is that
 * guard: `CaptureLane` freezes a board when a capture begins for it, a frozen board is not a candidate
 * here, and [StopRecordingMonitor] releases it once the vehicle is past [EXIT_RADIUS_M]. One visit,
 * one capture.
 *
 * The 50 m band between the two radii is hysteresis — an 80 m circle and a fix with 15 m of error is
 * enough to flip a decision made on a single radius, and freezing on entry while releasing on the same
 * distance would chatter.
 *
 * ### Lightweight, as required
 * The cost is in acquiring a fix, not in comparing one. [readLocationMap] returns the parsed,
 * in-memory station list and the comparison is a few hundred haversines — microseconds per fix. What
 * is genuinely expensive is [UPDATE_INTERVAL_MS] at [Priority.PRIORITY_HIGH_ACCURACY]; that is the
 * knob to turn if battery becomes the problem, not the arithmetic.
 */
class LocationTrigger(context: Context) : CaptureTrigger {

    override val name: String = "location"

    private val appContext = context.applicationContext

    /**
     * The fused provider, or null when this device cannot supply one.
     *
     * **Lazy and nullable, and both halves matter.** `getFusedLocationProviderClient` reaches into Google
     * Play services, which a device can be missing, have disabled, or hold at a version that throws — and
     * this object is constructed by `CaptureLane`'s constructor, which is constructed by
     * `FleetService.onCreate`. A throw there is a crash inside a system lifecycle callback with no frame
     * of ours above it, so the service dies on creation, the watchdog respawns it, and it dies again: a
     * device with no Play services would crash-loop rather than simply not capturing.
     *
     * Deferring it to first use moves the failure to [attach], which can report it and carry on.
     */
    private val client: FusedLocationProviderClient? by lazy {
        runCatching { LocationServices.getFusedLocationProviderClient(appContext) }
            .onFailure { Log.e(TAG, "no fused location provider on this device", it) }
            .getOrNull()
    }

    private var sink: CaptureTriggerSink? = null
    private val movementGate = MovementGate()

    private val callback = object : LocationCallback() {
        /**
         * Guarded whole, because of where it runs: this is Play services calling us back on the main
         * looper, every 1.5 seconds, with no frame of ours underneath. A throw out of here is a process
         * death on the app's hottest callback — and the arithmetic it contains reads a SQLite-backed
         * station list and does floating-point geometry over 349 rows, so "it cannot throw" is a claim
         * about code that will keep changing.
         *
         * Losing one fix costs nothing: another arrives in 1.5 seconds, and at 90 km/h the vehicle is
         * still inside the same 80 m circle.
         */
        override fun onLocationResult(result: LocationResult) {
            crashproof(SOURCE, ContainedFaults.LOCATION_TRIGGER, "onLocationResult") {
                val fix = result.lastLocation ?: return@crashproof
                // Recorded before the comparison, not after it: [onFix] returns early on an empty map and
                // on a fix with no candidate nearby, and the vehicle's position is worth keeping on both
                // of those paths — a manual capture taken between stations needs it too.
                VehicleFix.record(fix.latitude, fix.longitude, fix.accuracy)
                onFix(fix, movementGate.observe(
                    MovementGate.Fix(
                        fix.latitude,
                        fix.longitude,
                        fix.elapsedRealtimeNanos,
                        reportedSpeed(fix),
                        if (fix.hasAccuracy()) fix.accuracy.toDouble() else null
                    ),
                    SystemClock.elapsedRealtimeNanos()
                ))
            }
        }
    }

    // ---- CaptureTrigger ------------------------------------------------------------------------ //

    @SuppressLint("MissingPermission") // guarded by hasForegroundLocation() immediately below
    override fun attach(sink: CaptureTriggerSink) = crashproof(
        SOURCE, ContainedFaults.LOCATION_TRIGGER, "attach"
    ) {
        this.sink = sink
        movementGate.reset()

        if (!hasForegroundLocation()) {
            Log.e(TAG, "no location permission — the trigger cannot fire")
            ErrorLog.showError(AppErrors.PERMISSIONS)
            FleetBus.publishStatus("Auto capture off — location permission is not granted.")
            return@crashproof
        }
        // No provider, no trigger — and it is reported rather than thrown. See [client].
        val provider = client
        if (provider == null) {
            Log.e(TAG, "no location provider — the trigger cannot fire")
            ErrorLog.showError(ERR_NO_PROVIDER, MSG_NO_PROVIDER, SOURCE)
            FleetBus.publishStatus("Auto capture off — this device has no location provider.")
            return@crashproof
        }
        ErrorLog.removeErrorByKey(ERR_NO_PROVIDER)
        if (!isBackgroundCapable) {
            // Not fatal: it works while the app is visible. Said out loud because the failure mode
            // otherwise is "the truck drove past forty stations and recorded nothing".
            Log.w(
                TAG,
                "ACCESS_BACKGROUND_LOCATION is not granted — the trigger will stop firing once the " +
                    "app is no longer visible"
            )
            FleetBus.publishStatus("Auto capture is foreground-only — grant background location.")
        }

        val stations = readLocationMap().size
        if (stations == 0) {
            Log.w(TAG, "location map is empty — the trigger cannot fire until it is fetched")
            FleetBus.publishStatus("Auto capture waiting — no station map yet.")
        }

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, UPDATE_INTERVAL_MS)
            .setMinUpdateIntervalMillis(UPDATE_INTERVAL_MS)
            // A stationary vehicle needs no fixes at all; this lets the provider coalesce them.
            .setMinUpdateDistanceMeters(MIN_UPDATE_DISTANCE_M)
            .build()

        provider.requestLocationUpdates(request, callback, appContext.mainLooper)
        // Set only once updates are genuinely requested. Every early return above leaves it false, so
        // the flag means "receiving fixes", not "the object exists" — the distinction the readout needs,
        // because a trigger that attached without permission looks identical to a working one otherwise.
        attached = this
        isAttached = true
        Log.i(
            TAG,
            "attached — comparing every ${UPDATE_INTERVAL_MS / 1000}s against $stations station(s), " +
                "enter ${ENTER_RADIUS_M.toInt()}m / re-arm ${EXIT_RADIUS_M.toInt()}m"
        )
    }

    override fun detach() = crashproof(SOURCE, ContainedFaults.LOCATION_TRIGGER, "detach") {
        // Only the trigger that is currently reporting may clear the flag.
        //
        // Two service instances overlap for a moment whenever the app recovers itself: the one that was
        // restarted in the background stands down while the one started from the Activity is coming up,
        // and their onDestroy / onCreate are not ordered. Clearing unconditionally let the dying
        // instance switch off a readout the live one had just switched on — leaving the home panel
        // saying NOT WATCHING, permanently, over a trigger that was in fact watching. The readout is the
        // only capture indicator on the panel now, so it has to be right.
        if (attached === this) {
            attached = null
            isAttached = false
        }
        // Unconditional: this instance's own subscription must go either way — and separately guarded,
        // because `sink = null` has to happen even when the provider refuses the removal. A detached
        // trigger still holding a sink would keep asking a stopped lane for captures.
        runCatching { client?.removeLocationUpdates(callback) }
            .onFailure { Log.w(TAG, "could not remove the location updates", it) }
        sink = null
        movementGate.reset()
        Log.i(TAG, "detached")
    }

    // ---- The comparison ------------------------------------------------------------------------ //

    /**
     * One fix, compared against the map. The whole of the trigger's logic.
     *
     * Frozen boards are skipped **before** the nearest-station search rather than after it. Filtering
     * afterwards would mean a frozen board sitting closest hides the one behind it: two forecourts
     * across a road from each other, the first captured and frozen, and the second never seen because
     * it was never the nearest.
     */
    private fun onFix(fix: Location, moving: Boolean) {
        if (!moving) return
        val stations = readLocationMap()
        if (stations.isEmpty()) return

        var nearest: FuelStation? = null
        var nearestM = Double.MAX_VALUE
        for (s in stations) {
            if (FrozenBoards.isFrozen(s.id)) continue
            val m = readGps(fix.latitude, fix.longitude, s)
            if (m < nearestM) {
                nearestM = m
                nearest = s
            }
        }

        val candidate = nearest ?: return
        if (nearestM <= ENTER_RADIUS_M) {
            val acc = if (fix.hasAccuracy()) ", fix ±${fix.accuracy.toInt()}m" else ""
            val reason = "within ${nearestM.toInt()}m of ${candidate.name}$acc"
            Log.i(TAG, "FIRE — $reason")
            // The station id travels WITH the request, and the lane does two things with it: freezes
            // the board when the capture begins, and stamps last_capture_time once a clip is written.
            // Both belong there rather than here, because only the lane knows whether it accepted.
            sink?.requestStart(TriggerSource.GEOFENCE, reason, stationId = candidate.id)
        }
    }

    /**
     * `readGps` — the distance from this fix to a station, in metres.
     *
     * Named for the specification's internal function. The haversine itself is
     * [StationDirectory.distanceMetres] rather than a second copy: one implementation of the earth's
     * radius in this codebase, and it is already covered by that object's tests.
     */
    private fun readGps(latitude: Double, longitude: Double, station: FuelStation): Double =
        StationDirectory.distanceMetres(latitude, longitude, station.latitude, station.longitude)

    /** `readLocation` — the station map, from memory. Parsed once by [LocationMap] and held. */
    private fun readLocationMap(): List<FuelStation> = LocationMap.stations(appContext)

    private fun reportedSpeed(fix: Location): Double? {
        if (!fix.hasSpeed() || !fix.speed.isFinite() || fix.speed < 0f) return null
        val uncertainty = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            fix.hasSpeedAccuracy() && fix.speedAccuracyMetersPerSecond.isFinite()
        ) fix.speedAccuracyMetersPerSecond.coerceAtLeast(0f) else 0f
        return (fix.speed - uncertainty).coerceAtLeast(0f).toDouble()
    }

    // ---- Permissions --------------------------------------------------------------------------- //

    // Fails CLOSED: a permission read that threw must read as "not granted", because the alternative is
    // calling `requestLocationUpdates` without the permission and taking a SecurityException instead.
    private fun hasForegroundLocation(): Boolean =
        crashproofOr(SOURCE, ContainedFaults.LOCATION_TRIGGER, "hasForegroundLocation", false) {
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }

    /**
     * Whether location will still be delivered with no screen open.
     *
     * Below Android 10 there is no separate background grant and the foreground one covers both, so
     * this reports the foreground answer there rather than a hardcoded false.
     */
    val isBackgroundCapable: Boolean
        get() = crashproofOr(SOURCE, ContainedFaults.LOCATION_TRIGGER, "isBackgroundCapable", false) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                hasForegroundLocation()
            } else {
                ContextCompat.checkSelfPermission(
                    appContext,
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            }
        }

    companion object {
        private const val TAG = "LocationTrigger"

        /** Shown under this trigger's banner entries. */
        const val SOURCE = "Auto capture"

        // A device with no fused location provider at all — no Play services, or a version that will not
        // hand one over. Constant, like every message in this app, so the raise and the clear match.
        const val ERR_NO_PROVIDER = "location_provider_missing"
        const val MSG_NO_PROVIDER =
            "This device has no location provider, so a forecourt can never be detected. Automatic " +
                "capture will not work on it."

        /**
         * True while the trigger is receiving location fixes.
         *
         * For the home readout. A flag rather than asking the fused provider, which has no API for
         * "is this listener registered" — and the answer that matters is whether THIS object asked,
         * which only it knows.
         */
        @Volatile
        var isAttached: Boolean = false
            private set

        /** Which trigger [isAttached] is reporting on. See [detach] for why identity matters. */
        @Volatile
        private var attached: LocationTrigger? = null

        /**
         * Enter radius, metres. The API configuration owns this value; 80 m is the build fallback.
         */
        val ENTER_RADIUS_M: Double get() = DeviceConfig.double("capture.trigger.enter_radius_m", 80.0)

        /**
         * Re-arm radius, metres. The API configuration owns this value; it must be outside the
         * enter radius to re-arm after a completed capture.
         */
        val EXIT_RADIUS_M: Double get() = DeviceConfig.double("capture.trigger.exit_radius_m", 130.0)

        /**
         * Requested interval between fixes, taken directly from API configuration. The provider's
         * minimum interval follows this value too; there is no separate local polling floor.
         */
        val UPDATE_INTERVAL_MS: Long get() = DeviceConfig.long("capture.trigger.update_interval_ms", 1_500L)

        /**
         * Below this much movement the provider need not report at all. A parked vehicle then costs
         * nothing. The API configuration owns this threshold.
         */
        val MIN_UPDATE_DISTANCE_M: Float get() = DeviceConfig.float("capture.trigger.min_update_distance_m", 25f)
    }
}
