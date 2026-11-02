package com.terminalscout.app.prod

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.location.LocationManager
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.terminalscout.app.prod.capture.LocationMap
import com.terminalscout.app.prod.capture.LocationMapDbHelper
import androidx.work.WorkManager
import com.terminalscout.app.prod.capture.LocationMapRefreshScheduler
import com.terminalscout.app.prod.config.DeviceConfig
import com.terminalscout.app.prod.service.FleetService
import com.terminalscout.app.prod.ui.AppErrors
import com.terminalscout.app.prod.ui.ErrorLog
import com.terminalscout.app.prod.ui.ErrorLogDbHelper
import com.terminalscout.app.prod.ui.KioskMode
import com.terminalscout.app.prod.upload.AzureMapsRefreshScheduler
import com.terminalscout.app.prod.upload.AzureMapsStore
import com.terminalscout.app.prod.upload.UploadDbHelper
import com.terminalscout.app.prod.upload.UserDbHelper
import com.terminalscout.app.prod.upload.UserStore
import com.terminalscout.app.prod.download.DebugUploadScheduler
import com.terminalscout.app.prod.download.ModelDownloadManager
import com.terminalscout.app.prod.download.ModelRepository
import com.terminalscout.app.prod.upload.UploadScheduler
import java.io.File

/**
 * Brings the app up. Everything that has to be true before Stage 0 can do its job, in one place.
 *
 * ### Why this exists as its own file
 * Startup was scattered: `MainActivity.onCreate` armed three schedulers, the location-map refresh was
 * armed from `FleetService`, and the SQLite tables were created lazily by whoever happened to open them
 * first. Permissions were asked for in exactly one place — `FirstFragment` requested camera and
 * foreground location when that screen opened — which left background location and notifications never
 * requested at all, and nothing asked on any other screen. Background location is the costly one:
 * without it the trigger goes blind the moment the screen turns off, which looks exactly like a working
 * app that never passes a forecourt.
 *
 * ### It is idempotent, and that is the point
 * [run] is safe to call on every launch and does only what is not already done. A permission the user
 * revoked in Settings, a scheduler a task-killer cancelled, a map that never downloaded — each is
 * repaired on the next open rather than staying broken until someone reinstalls. Nothing here is a
 * one-shot "first run" flag, because a first-run flag records that setup *happened*, not that the
 * device is *set up*.
 *
 * ### What it checks, and repairs, on every pass
 * | | check | repair |
 * |---|---|---|
 * | **Folders** | every working directory under `filesDir` | `mkdirs()` |
 * | **Tables** | each database's tables exist in `sqlite_master` | opening the helper runs its `onCreate` |
 * | **Permissions** | camera, location, background location, notifications | asked for by `MainActivity` — see below |
 * | **Device switches** | battery exemption, overlay, exact alarms, GPS | a settings screen per gap |
 * | **Kiosk** | is the app holding its own screen | **forced on at every launch** — see [ensureKioskMode] |
 * | **Background jobs** | the four WorkManager schedules | re-armed unconditionally; the schedulers are idempotent |
 * | **Station map** | the table has rows | a catch-up fetch is requested |
 * | **Retired seed rows** | no `surveyed:`/`detected:` rows the API stopped serving | deleted — see [LocationMap.purgeBundled] |
 * | **Detection models** | every model in `ModelRepository.ALL` is on disk | the download is started |
 * | **Capture service** | `FleetService.isRunning` | started, once the two holds below are clear |
 *
 * Kiosk is the one condition setup *overrides* rather than merely reports. It is what holds the app on
 * screen, and holding the app on screen is what makes a `camera` foreground service startable and what
 * every watchdog path checks before bringing the app back. A release made from the menu still stands for
 * the life of the process, so the device stays serviceable; it does not survive a restart.
 *
 * ### Nothing here may throw
 * Every step is wrapped, and a failure becomes a row on the errors screen rather than an exception.
 * That is not defensive habit, it is the consequence of where this runs: [run] is called from
 * `onResume`, on every launch and every resume, so an escaping exception is not one crash — the
 * watchdog respawns into the same call five seconds later, and the unit crash-loops until the
 * ten-attempt guard stands it down. A broken `user.db` must cost the device its identity, not its life.
 *
 * Steps are also independent. One failing does not skip the rest: a device that cannot read its device
 * details still gets its folders made, its tables verified and its jobs armed.
 *
 * ### Order matters in two places
 * The capture service is started **only once device details exist** ([UserStore.exists]) and location is
 * actually granted. A capture taken before the first produces a payload with no device or vehicle
 * identity, which the readings API cannot attribute to anything. Starting before the second throws
 * outright: `FleetService` declares the `location` foreground-service type, and Android 14+ refuses a
 * typed service whose permission is not held.
 */
object AppSetup {

    private const val TAG = "AppSetup"

    // Errors this object raises that have no screen to open. They live here rather than in AppErrors,
    // which is a directory of FIXES — an entry there would promise an action that does not exist.
    private const val SOURCE = "Setup"
    private const val ERR_TABLES = "tables"
    private const val MSG_TABLES = "The on-device database could not be prepared."
    private const val ERR_SERVICES_DOWN = "services_down"
    private const val MSG_SERVICES_DOWN =
        "A background service is not running. Reopen the app, or exit and start it again."
    private const val ERR_NO_STATION_MAP = "no_station_map"
    private const val MSG_NO_STATION_MAP = "No station map yet. Fetching it — this needs a connection."
    private const val ERR_DIRECTORIES = "directories"
    private const val MSG_DIRECTORIES =
        "The app's working folders could not be created. Check free storage."
    private const val ERR_BACKGROUND_WORK = "background_work"
    private const val MSG_BACKGROUND_WORK =
        "The recurring background jobs could not be scheduled. Uploads and the daily refreshes are stopped."
    private const val ERR_MAP_FETCH = "station_map_fetch"
    private const val MSG_MAP_FETCH = "The station map could not be requested. Auto capture has nothing to compare against."
    private const val ERR_DEVICE_DETAILS_UNREADABLE = "device_details_unreadable"
    private const val MSG_DEVICE_DETAILS_UNREADABLE =
        "The saved device and vehicle details could not be read. Open DEVICE INFO and save them again."
    private const val ERR_SERVICE_START = "service_start_failed"
    private const val MSG_SERVICE_START =
        "The capture service would not start. Nothing will be recorded until it does."
    private const val ERR_MODELS_MISSING = "models_missing"
    private const val MSG_MODELS_MISSING =
        "The detection models are not on the device. They are downloading — this needs a connection, " +
            "and nothing can be read from a clip until they are here."
    private const val ERR_MODEL_DOWNLOAD = "model_download_failed"
    private const val MSG_MODEL_DOWNLOAD =
        "The detection models could not be downloaded. Check the connection — the app keeps trying by " +
            "itself, and nothing can be read from a clip until they arrive."
    private const val ERR_KIOSK_OFF = "kiosk_off"
    private const val MSG_KIOSK_OFF =
        "Kiosk mode is released, so the app will not hold the screen and will not restart itself after " +
            "a crash. It comes back on by itself the next time the app starts."
    private const val ERR_SETUP_FAILED = "setup_failed"
    private const val MSG_SETUP_FAILED =
        "Setup did not finish. Some checks were skipped — reopen the app, and if this persists reinstall."

    /**
     * The app's working folders, under `filesDir`.
     *
     * Every owner still creates its own with `mkdirs()` on first use, so this is a **pre-flight, not a
     * dependency**: a name missing from this list degrades to "created later by whoever needs it", never
     * to a broken app. What it buys is finding out *now*, on a device with no space or a read-only
     * volume, rather than three hours into a shift when the first clip lands.
     *
     * The ones read from their owners' constants are the ones most likely to be renamed. The rest are
     * literals for the same reason `AppReset` keeps literals: importing ten objects into setup to spell
     * nine strings costs more than it saves.
     */
    private val WORKING_DIRECTORIES: List<String> = listOf(
        "frames",                 // Stage 1 extracts here
        "crops",                  // Stage 2 board crops
        "prepped",                // Stage 3 filtered canvases
        "runs",                   // per-run output
        // Both halves of the capture handover, and they are a pair: a clip is recorded into the staged
        // directory and moved into the recorded one, so a missing staged folder does not degrade to
        // "created later" the way most of this list does — it degrades to a recording that cannot open
        // its output file at the moment a truck is passing a forecourt. Pre-flighting it is the point.
        CameraCaptureManager.STAGED_VIDEOS_DIR,
        CameraCaptureManager.RECORDED_VIDEOS_DIR,
        "pending_uploads/images", // UploadQueue — images staged for the readings API
        "debug_outbox",           // DebugUploadOutbox
        "debug_manifests",        // DebugRunManifest
        "models",                 // ModelRepository — the downloaded TFLite models
        AzureMapsStore.DIR,       // sun times
        "stations",               // StationDirectory
        "kiosk",                  // KioskMode's one-word switch
        "watchdog"                // Watchdog's attempt chain
    )

    /** Holds the versionCode this device last ran, so a changed build can clear its working set. */
    private const val INSTALL_MARKER = "install_marker"


    /**
     * What [run] found and did. Returned rather than logged only, so the caller can put the gaps in
     * front of the user instead of leaving them in Logcat.
     */
    data class Status(
        val deviceDetailsEntered: Boolean,
        val missingPermissions: List<String>,
        val backgroundLocationGranted: Boolean,
        val batteryExempt: Boolean,
        val canDrawOverlays: Boolean,
        val gpsEnabled: Boolean,
        val exactAlarmsAllowed: Boolean,
        val kioskEnabled: Boolean,
        val stationCount: Int,
        val mapFetchRequested: Boolean,
        val modelsReady: Boolean,
        val serviceStarted: Boolean,
        val tablesReady: Boolean,
        val directoriesReady: Boolean,
        val servicesUp: Int,
        val servicesTotal: Int
    ) {
        /** True when nothing is outstanding and Stage 0 can actually run unattended. */
        val isComplete: Boolean
            get() = deviceDetailsEntered &&
                missingPermissions.isEmpty() &&
                backgroundLocationGranted &&
                batteryExempt &&
                canDrawOverlays &&
                gpsEnabled &&
                exactAlarmsAllowed &&
                kioskEnabled &&
                tablesReady &&
                directoriesReady &&
                stationCount > 0 &&
                modelsReady &&
                serviceStarted &&
                servicesUp == servicesTotal

        /** One line for the debug screen. */
        fun summary(): String = when {
            !tablesReady -> "Setup: the on-device database could not be prepared."
            !directoriesReady -> "Setup: the working folders could not be created."
            !deviceDetailsEntered -> "Setup: enter the device and vehicle details."
            missingPermissions.isNotEmpty() ->
                "Setup: ${missingPermissions.size} permission(s) still needed."
            !backgroundLocationGranted -> "Setup: allow location \"all the time\" for auto capture."
            !batteryExempt -> "Setup: turn off battery optimisation for this app."
            !canDrawOverlays -> "Setup: allow \"display over other apps\"."
            !exactAlarmsAllowed -> "Setup: allow exact alarms, or a restart is delayed."
            !gpsEnabled -> "Setup: location services are switched off."
            !kioskEnabled -> "Setup: kiosk mode is off — the app will not hold the screen or restart itself."
            stationCount == 0 -> "Setup: fetching the station map…"
            !modelsReady -> "Setup: downloading the detection models…"
            !serviceStarted -> "Setup: capture service not running."
            servicesUp < servicesTotal ->
                "Setup: $servicesUp of $servicesTotal background services running."
            else -> "Ready — $stationCount station(s) mapped."
        }
    }

    /**
     * The runtime permissions Stage 0 needs and can ask for with an ordinary dialog.
     *
     * `ACCESS_BACKGROUND_LOCATION` is **not** here on purpose — see [hasBackgroundLocation]. Asking for
     * it in the same request as the foreground one is silently denied on Android 11+, so it has to be a
     * second step, after the foreground grant is already held.
     */
    fun requiredPermissions(): List<String> = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        // The service posts an ongoing notification; from Android 13 that needs asking for.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun missingPermissions(context: Context): List<String> =
        requiredPermissions().filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }

    /**
     * Whether location will still be delivered with no screen open.
     *
     * Below Android 10 there is no separate background grant and the foreground one covers both, so
     * this reports the foreground answer there rather than a hardcoded false.
     */
    fun hasBackgroundLocation(context: Context): Boolean =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }

    /**
     * Whether the app is exempt from Doze and App Standby.
     *
     * The unit is permanently powered, so every battery restriction is pure downside: Doze coalesces
     * the location updates the trigger reads at its configured interval, defers WorkManager past its window —
     * which would push the 02:00 map refresh to whenever the device next woke — and makes the process
     * a cheaper target when the system wants memory back.
     *
     * Below Android 6 there is no Doze, so there is nothing to be exempt from and this reports true.
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Opens the system dialog that asks for the exemption directly.
     *
     * `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` shows one prompt with a yes/no, which is far better
     * than sending someone into the battery settings tree to find the same switch. It needs the
     * `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission in the manifest merely to be allowed to ask —
     * the user still decides.
     *
     * Falls back to the app's settings page if the device has no such activity, which some heavily
     * modified builds do not.
     */
    fun requestIgnoreBatteryOptimizations(context: Context) {
        if (isIgnoringBatteryOptimizations(context)) return
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opened = runCatching { context.startActivity(intent) }.isSuccess
        if (!opened) {
            Log.w(TAG, "no battery-optimisation dialog on this device — falling back to app settings")
            openPermissionSettings(context)
        }
    }

    /**
     * Whether location services are switched on at the device level.
     *
     * Distinct from holding the location permission: a granted app on a phone with location turned off
     * gets no fixes at all, and — since the capture service now takes the `location` foreground-service
     * type — cannot even start. Two different faults with two different fixes, so they are reported
     * separately.
     */
    fun isGpsEnabled(context: Context): Boolean = try {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        lm != null && (
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            )
    } catch (e: Exception) {
        Log.e(TAG, "could not read location settings", e)
        false
    }

    /**
     * Whether the app may put itself on screen from the background.
     *
     * Nothing here draws an overlay. `SYSTEM_ALERT_WINDOW` is wanted purely as the documented exemption
     * from Android's background-activity-start restriction: without it, an app woken with no screen
     * showing — after a reboot, or after the system killed the process — cannot raise its own UI.
     *
     * That is load-bearing for capture, not cosmetic. A foreground service started while nothing is on
     * screen is refused the camera **for the whole life of that service instance**, so recovering from a
     * process death means showing an Activity first and starting the service from it. This permission is
     * what makes that possible without a human picking the phone up.
     *
     * Below Android 6 there is no such gate and this reports true.
     */
    fun canDrawOverlays(context: Context): Boolean =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) true
        else Settings.canDrawOverlays(context)

    /**
     * Whether the app may set an exact alarm.
     *
     * The watchdog's five-second respawn is an exact alarm; without this it falls back to an inexact one
     * and a crashed unit comes back in minutes rather than seconds. Not fatal, which is why it is
     * reported rather than enforced — but on a unit that is meant to recover unattended, minutes of
     * blindness per crash adds up.
     *
     * Below Android 12 there is no such gate and this reports true.
     */
    fun canScheduleExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
        return runCatching { am.canScheduleExactAlarms() }.getOrDefault(false)
    }

    /**
     * Opens the "Alarms & reminders" screen for this app.
     *
     * A special permission like the battery exemption and the overlay: there is no runtime dialog, so
     * this is a settings screen rather than a prompt.
     */
    fun requestExactAlarmPermission(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || canScheduleExactAlarms(context)) return
        val intent = Intent(
            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
            Uri.fromParts("package", context.packageName, null)
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opened = runCatching { context.startActivity(intent) }.isSuccess
        if (!opened) {
            Log.w(TAG, "no exact-alarm settings screen on this device — falling back to app settings")
            openPermissionSettings(context)
        }
    }

    /**
     * Opens the "Display over other apps" settings screen for this app.
     *
     * A special permission, like the battery exemption: there is no runtime dialog for it, so this is
     * a settings screen rather than a prompt. Falls back to the app's details page on builds without
     * the dedicated activity.
     */
    fun requestOverlayPermission(context: Context) {
        if (canDrawOverlays(context)) return
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.fromParts("package", context.packageName, null)
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opened = runCatching { context.startActivity(intent) }.isSuccess
        if (!opened) {
            Log.w(TAG, "no overlay settings screen on this device — falling back to app settings")
            openPermissionSettings(context)
        }
    }

    /**
     * Runs the whole setup and reports what is still outstanding.
     *
     * Does **not** request permissions — that needs an Activity and a result callback, so the caller
     * owns it and hands the answer back by calling this again. Everything else happens here.
     */
    fun run(context: Context): Status {
        // The log first, and outside the guarded steps: a fault raised during this very pass has to have
        // somewhere to go, so this is the one call that cannot be allowed to fail silently.
        runCatching { ErrorLog.init(context) }
            .onFailure { Log.e(TAG, "the error log would not open — faults this pass go to Logcat only", it) }

        return try {
            runSteps(context)
        } catch (t: Throwable) {
            // The backstop, and deliberately the LAST line of defence rather than the first.
            //
            // Every step below is guarded individually, because a single catch around the whole pass
            // would tell the operator "setup failed" and nothing else — it cannot say which of nine
            // checks broke, and it would skip the eight that were fine. This catches only what escapes
            // that: a fault in the glue between the steps.
            //
            // Throwable, not Exception. The whole point of this method is that the app does not die
            // because of it: run() is called from onResume, on every launch and every resume, so an
            // escape here is not one crash — the watchdog respawns into the same call five seconds later
            // and the unit crash-loops until the ten-attempt guard stands it down. Swallowing an Error is
            // a bad thing to do in general and the better of the two things available here.
            Log.e(TAG, "setup itself failed", t)
            runCatching { ErrorLog.showError(ERR_SETUP_FAILED, MSG_SETUP_FAILED, SOURCE) }
            degraded()
        }
    }

    /**
     * Everything setup actually does. Each step is independent, and each one is allowed to fail.
     *
     * The shape is deliberate: **check, repair, report** — for every condition, in that order, on every
     * pass. Nothing here is conditional on a first-run flag, and nothing gives up because an earlier step
     * failed. A device with an unreadable `user.db` still gets its folders made and its jobs armed.
     */
    private fun runSteps(context: Context): Status {
        // Cleared at the top of the pass, not by each guard: several steps share this key, so clearing
        // it on one step's success would erase the row another step had just raised. Raised by any guard
        // that catches, it therefore survives until a pass in which nothing throws at all.
        runCatching { ErrorLog.removeErrorByKey(ERR_SETUP_FAILED) }

        // ---- The fetched tunables, FIRST. ----
        //
        // Before anything else reads a constant: `DeviceConfig` hands out the stored document's value
        // or the build's own, and every step below this line may ask it for one. Loading it after a
        // step that reads one would run that step on the compiled value and the next pass on the
        // fetched one — the same launch behaving two ways.
        //
        // It cannot fail the pass. No document, an unreadable one, or one that breaks an invariant all
        // leave the build's own constants in force, which is exactly what this device ran yesterday.
        guard(Unit) { DeviceConfig.load(context) }
        // Fire-and-forget: what arrives is stored, and takes effect at the NEXT process start. Setup
        // must not wait on a network call — it holds the capture service behind it.
        guard(Unit) { DeviceConfig.requestRefresh(context) }

        guard(Unit) { Watchdog.noteAppUp(context) }
        guard(Unit) { wipeOnFreshInstall(context) }

        // ---- Structure: folders, then tables. Both are check-and-create. ----
        val directoriesReady = step(ERR_DIRECTORIES, MSG_DIRECTORIES, false) { ensureDirectories(context) }
        val tablesReady = step(ERR_TABLES, MSG_TABLES, false) { ensureTables(context) }

        // ---- Identity ----
        // Fails CLOSED: an unreadable user.db reports "no details", which holds the capture service. The
        // alternative — assuming details exist because the read threw — uploads readings the API cannot
        // attribute to a device, which is worse than not capturing.
        val details = step(ERR_DEVICE_DETAILS_UNREADABLE, MSG_DEVICE_DETAILS_UNREADABLE, false) {
            UserStore(context).exists()
        }

        // ---- Permissions and device switches ----
        // Cheap reads that should not be able to throw, guarded anyway: a permission check that throws
        // must not be able to stop the folders being made.
        val missing = guard(emptyList<String>()) { missingPermissions(context) }
        val background = guard(false) { hasBackgroundLocation(context) }
        val batteryExempt = guard(false) { isIgnoringBatteryOptimizations(context) }
        val gps = guard(false) { isGpsEnabled(context) }
        val overlays = guard(false) { canDrawOverlays(context) }
        val exactAlarms = guard(false) { canScheduleExactAlarms(context) }
        val kiosk = guard(false) { ensureKioskMode(context) }

        // ---- Background work: re-armed on every pass, which IS the repair ----
        val workArmed = step(ERR_BACKGROUND_WORK, MSG_BACKGROUND_WORK, false) {
            armBackgroundWork(context)
            true
        }
        val fetchRequested = step(ERR_MAP_FETCH, MSG_MAP_FETCH, false) { ensureStationMap(context) }
        // The 84 APK-bundled sites used to be re-seeded here on every pass. Retired on 2026-09-18:
        // `GET /v1/stations` is the source of truth and the server serves the handful of those ids it
        // still recognises. What is left is a delete rather than a write — the leftovers cannot go on
        // their own, because the sweep is an upsert that never deletes. Scoped so it can only ever
        // touch rows the last sweep did not return; see [LocationMap.purgeBundled].
        //
        // No error key: a purge that fails changes nothing an operator could act on, and the next pass
        // (or the next sweep, which also calls it) tries again.
        guard(null) { LocationMap.purgeBundled(context, LocationMap.lastRefreshMs(context)) }
        val modelsReady = step(ERR_MODEL_DOWNLOAD, MSG_MODEL_DOWNLOAD, false) { ensureModels(context) }

        // ---- The capture service ----
        //
        // Held until the device knows who it is — see the class note — and until location is actually
        // granted. The permission half is not caution, it is the crash: `FleetService` declares the
        // `location` foreground-service type, and from Android 14 starting a typed service without the
        // permission that type requires throws a SecurityException out of startForeground. Setup used to
        // call this on a fresh install before the permission dialog had been answered, which took the
        // process down — and the app came back, ran setup, and did it again. Checking first is what makes
        // the stated order ("check the permissions, then start the background services") true in code.
        // Guarded like every other read in this pass, and it was the one that was not. It fails CLOSED
        // — an unreadable permission state holds the service — because the hold is what stops a
        // SecurityException out of `startForeground`, and starting on a guess is the crash this check
        // exists to prevent.
        val locationGranted = guard(false) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
        }

        val alreadyRunning = FleetService.isRunning
        val serviceStarted = when {
            !details -> {
                Log.w(TAG, "device details not entered — holding the capture service")
                false
            }
            !locationGranted -> {
                Log.w(TAG, "location not granted — holding the capture service (it would be refused)")
                false
            }
            // Check, then repair. An already-running service needs no start intent, and knowing which of
            // the two happened is what lets the readout below tell the truth on this pass.
            alreadyRunning -> true
            else -> step(ERR_SERVICE_START, MSG_SERVICE_START, false) {
                FleetService.start(context)
                true
            }
        }

        val counted = guard(0 to 5) { backgroundServices(context) }
        val servicesTotal = counted.second
        // A service started by THIS pass is not running yet — startForegroundService is asynchronous, and
        // `FleetService.isRunning` goes true a few milliseconds later, once that instance has reached
        // startForeground. Counting it as down put a red "a background service is not running" on the
        // screen at every single launch, cleared on the next resume: an error that was never true, on a
        // screen whose entire value is that its errors are.
        val servicesUp = if (serviceStarted && !alreadyRunning && counted.first < servicesTotal) {
            counted.first + 1
        } else {
            counted.first
        }

        val status = Status(
            deviceDetailsEntered = details,
            missingPermissions = missing,
            backgroundLocationGranted = background,
            batteryExempt = batteryExempt,
            canDrawOverlays = overlays,
            gpsEnabled = gps,
            exactAlarmsAllowed = exactAlarms,
            kioskEnabled = kiosk,
            stationCount = guard(0) { LocationMap.count(context) },
            mapFetchRequested = fetchRequested,
            modelsReady = modelsReady,
            serviceStarted = serviceStarted,
            tablesReady = tablesReady,
            directoriesReady = directoriesReady,
            servicesUp = servicesUp,
            servicesTotal = servicesTotal
        )
        if (!workArmed) Log.w(TAG, "background work is NOT armed")
        Log.i(TAG, "setup: " + status.summary())
        guard(Unit) { report(status) }
        return status
    }

    /**
     * Runs one step, and turns a failure into a row on the errors screen instead of a dead app.
     *
     * Clears [key] on success, so a condition that has been fixed stops being reported without anything
     * else having to notice — the same both-directions contract [report] uses.
     *
     * @param fallback what the caller gets when the step failed. Chosen at each call site to fail in the
     *   direction that is safe: false for "is this ready", an empty list for "what is missing".
     */
    private fun <T> step(key: String, message: String, fallback: T, block: () -> T): T = try {
        val value = block()
        runCatching { ErrorLog.removeErrorByKey(key) }
        value
    } catch (t: Throwable) {
        Log.e(TAG, "setup step '" + key + "' failed — carrying on with the rest", t)
        runCatching { ErrorLog.showError(key, message, SOURCE) }
        fallback
    }

    /**
     * Like [step], for the small reads that share [ERR_SETUP_FAILED] rather than owning a key.
     *
     * It does not clear on success, because the key is shared: a permission read succeeding says nothing
     * about whether the station count threw. `runSteps` clears it once per pass instead.
     */
    private fun <T> guard(fallback: T, block: () -> T): T = try {
        block()
    } catch (t: Throwable) {
        Log.e(TAG, "a setup check failed — carrying on with the rest", t)
        runCatching { ErrorLog.showError(ERR_SETUP_FAILED, MSG_SETUP_FAILED, SOURCE) }
        fallback
    }

    /** What [run] reports when the pass could not be completed at all. Everything outstanding. */
    private fun degraded(): Status = Status(
        deviceDetailsEntered = false,
        missingPermissions = emptyList(),
        backgroundLocationGranted = false,
        batteryExempt = false,
        canDrawOverlays = false,
        gpsEnabled = false,
        exactAlarmsAllowed = false,
        kioskEnabled = false,
        stationCount = 0,
        mapFetchRequested = false,
        modelsReady = false,
        serviceStarted = false,
        tablesReady = false,
        directoriesReady = false,
        servicesUp = 0,
        servicesTotal = 5
    )

    /**
     * Makes sure the detection models are on the device, and starts fetching whatever is missing.
     *
     * ### Why setup owns this at all
     * The models are what Stage 2 and Stage 3 read with; without them a unit captures perfectly, queues
     * perfectly, and fails every clip downstream. Nothing about that shows on the home panel — the
     * readout would say WATCHING and 5/5 while every run died — which is the worst shape a fault can
     * take on a device nobody is standing next to. Setup is the one thing that runs often enough to
     * notice.
     *
     * There is no download screen any more, and that is the point: a screen can only download models
     * while somebody is looking at it, which is the one thing a deployed unit never has. Setup runs on
     * every launch and every resume, so the models arrive whether or not anyone is present — and after a
     * "clean memory", which deletes them, they come back on their own rather than waiting for a visit.
     *
     * ### What actually gets downloaded
     * [ModelRepository.ALL], which is a much shorter list than it looks: the vision LLM is excluded
     * (Stage 4 reads off-device, so its ~3.7 GB would be a long wait for a file nothing opens) and the
     * board detector drops out too while it is bundled in the APK. In this build that leaves the
     * price-region model. This is not a call that can quietly pull gigabytes over mobile data.
     *
     * ### It returns false while a download is in flight
     * "Not ready" and "not downloading" are the same answer here, on purpose. The distinction the
     * operator needs is on the download screen; what the home readout needs to say is that clips cannot
     * be read yet, which is true in both cases.
     *
     * [ModelDownloadManager.ensureStarted] is idempotent — a second call while a job is running does
     * nothing — so this is safe on a pass that happens five times a launch. A download that *failed*
     * ends its job, so the next pass starts a fresh attempt, which is the retry.
     *
     * @return true when every model in [ModelRepository.ALL] is on disk.
     */
    fun ensureModels(context: Context): Boolean {
        if (ModelRepository.allDownloaded(context)) return true
        Log.w(TAG, "detection models are missing — starting the download")
        ModelDownloadManager.ensureStarted(context)
        return false
    }

    /**
     * Turns kiosk mode on if it is off, and reports whether it ended up on.
     *
     * ### Forced, not defaulted
     * Kiosk is what makes the unit work at all, and none of it is cosmetic: it holds the app on screen,
     * which is the only way a `camera` foreground service can ever be started (the platform requires a
     * visible activity), and it is the switch every [Watchdog] path checks before bringing the app back.
     * A unit sitting with kiosk off is a unit that will not record with the screen asleep and will not
     * come back from a crash — and nothing about the truck says so, because the app looks fine.
     *
     * So the stored setting no longer survives a restart. Whatever the file says, every launch turns it
     * back on. That makes the default irrelevant and makes "off" a *temporary* state rather than a
     * property of the device.
     *
     * ### The one exception, and why it is not a loophole
     * [KioskMode.wasReleasedThisSession] holds the force off for the life of the process, so the menu
     * switch still works while somebody is standing in front of the device. Without it the force would
     * fight the button: setup runs again the instant the home panel repaints, so the switch would flick
     * back to ON under the technician's finger and the unit could not be serviced.
     *
     * It is in memory only, so it dies with the process. A crash, a kill, or EXIT DEVICE followed by a
     * fresh open all put the unit back to holding its own screen — which is the behaviour that matters,
     * because those are the states nobody is present for.
     *
     * @return true when kiosk is on when this returns.
     */
    fun ensureKioskMode(context: Context): Boolean {
        if (KioskMode.isEnabled(context)) return true
        if (KioskMode.wasReleasedThisSession) {
            Log.i(TAG, "kiosk was released from the menu this session — leaving it off until restart")
            return false
        }
        Log.w(TAG, "kiosk was off at launch — forcing it back on")
        KioskMode.setEnabled(context, true)
        return KioskMode.isEnabled(context)
    }

    /**
     * Creates every working folder that is not already there.
     *
     * `mkdirs()` returns false both for "it already exists" and for "it could not be created", so
     * existence is checked afterwards rather than trusting the return value — those two answers matter
     * and the boolean does not tell them apart.
     *
     * @return true when every folder in [WORKING_DIRECTORIES] exists and is a directory.
     */
    fun ensureDirectories(context: Context): Boolean {
        var created = 0
        val failed = mutableListOf<String>()

        WORKING_DIRECTORIES.forEach { name ->
            val dir = File(context.filesDir, name)
            if (dir.isDirectory) return@forEach
            runCatching { dir.mkdirs() }
                .onFailure { Log.e(TAG, "could not create " + name, it) }
            if (dir.isDirectory) created++ else failed += name
        }

        if (failed.isEmpty()) {
            Log.d(TAG, if (created == 0) "working folders present" else "created " + created + " folder(s)")
            return true
        }
        Log.e(TAG, "could not create: " + failed.joinToString())
        return false
    }

    /**
     * Pushes each outstanding condition to [ErrorLog], and clears the ones that have been fixed.
     *
     * Both directions on every pass, which is the point of setup being idempotent: granting a permission
     * in Settings and coming back makes the banner entry disappear without anything else having to
     * notice. The messages are constants so the clear side matches the raise side exactly — two strings
     * that drifted apart would leave an error that could never be removed.
     */
    private fun report(status: Status) {
        if (status.missingPermissions.isEmpty()) {
            ErrorLog.removeError(AppErrors.PERMISSIONS)
        } else {
            val names = status.missingPermissions.joinToString { it.substringAfterLast('.') }
            Log.w(TAG, "missing permissions: $names")
            ErrorLog.showError(AppErrors.PERMISSIONS)
        }

        if (status.backgroundLocationGranted) {
            ErrorLog.removeError(AppErrors.BACKGROUND_LOCATION)
        } else {
            Log.w(TAG, "background location not granted — auto capture is foreground-only")
            ErrorLog.showError(AppErrors.BACKGROUND_LOCATION)
        }

        if (status.batteryExempt) {
            ErrorLog.removeError(AppErrors.BATTERY_OPTIMISED)
        } else {
            Log.w(TAG, "battery optimisation is ON — the system may throttle or kill Stage 0")
            ErrorLog.showError(AppErrors.BATTERY_OPTIMISED)
        }

        if (status.canDrawOverlays) {
            ErrorLog.removeError(AppErrors.NO_OVERLAY)
        } else {
            Log.w(TAG, "no overlay permission — the app cannot restart itself after a process kill")
            ErrorLog.showError(AppErrors.NO_OVERLAY)
        }

        if (status.gpsEnabled) {
            ErrorLog.removeError(AppErrors.GPS_OFF)
        } else {
            Log.w(TAG, "location services are off — nothing can be captured")
            ErrorLog.showError(AppErrors.GPS_OFF)
        }

        if (status.tablesReady) {
            ErrorLog.removeErrorByKey(ERR_TABLES)
        } else {
            ErrorLog.showError(ERR_TABLES, MSG_TABLES, SOURCE)
        }

        // Raised here as well as by the step that creates them: the step only catches a THROW, and
        // "mkdirs returned without making the folder" is a failure that throws nothing.
        if (status.directoriesReady) {
            ErrorLog.removeErrorByKey(ERR_DIRECTORIES)
        } else {
            ErrorLog.showError(ERR_DIRECTORIES, MSG_DIRECTORIES, SOURCE)
        }

        if (status.exactAlarmsAllowed) {
            ErrorLog.removeError(AppErrors.NO_EXACT_ALARMS)
        } else {
            Log.w(TAG, "exact alarms not permitted — an automatic restart will be delayed")
            ErrorLog.showError(AppErrors.NO_EXACT_ALARMS)
        }

        // Only ever raised for a release made from the menu THIS session — see [ensureKioskMode]. A
        // unit that merely had the setting off is already back on by the time this runs, so the row
        // exists to mark the window in which the device is deliberately releasable, and it clears itself
        // on the next launch.
        if (status.kioskEnabled) {
            ErrorLog.removeErrorByKey(ERR_KIOSK_OFF)
        } else {
            Log.w(TAG, "kiosk mode is OFF — the app will not hold the screen or restart itself")
            ErrorLog.showError(ERR_KIOSK_OFF, MSG_KIOSK_OFF, SOURCE)
        }

        // Only reported once the service is meant to be up. Before device details are entered it is
        // held deliberately, and calling that a fault would put a red line on a device that is simply
        // not finished being set up.
        if (!status.serviceStarted || status.servicesUp == status.servicesTotal) {
            ErrorLog.removeErrorByKey(ERR_SERVICES_DOWN)
        } else {
            Log.w(TAG, "background services: ${status.servicesUp}/${status.servicesTotal}")
            ErrorLog.showError(ERR_SERVICES_DOWN, MSG_SERVICES_DOWN, SOURCE)
        }

        if (status.deviceDetailsEntered) {
            ErrorLog.removeError(AppErrors.NO_DEVICE_DETAILS)
        } else {
            ErrorLog.showError(AppErrors.NO_DEVICE_DETAILS)
        }

        // Raised on a false return, not a throw — the step above only catches a download that could not
        // be STARTED. Models still arriving is the ordinary case and is reported the same way, because
        // what it costs the operator is identical: nothing can be read from a clip yet.
        if (status.modelsReady) {
            ErrorLog.removeErrorByKey(ERR_MODELS_MISSING)
        } else {
            ErrorLog.showError(ERR_MODELS_MISSING, MSG_MODELS_MISSING, SOURCE)
        }

        if (status.stationCount > 0) {
            ErrorLog.removeErrorByKey(ERR_NO_STATION_MAP)
        } else {
            ErrorLog.showError(ERR_NO_STATION_MAP, MSG_NO_STATION_MAP, SOURCE)
        }
    }

    /**
     * Clears the working set the first time a given build runs on this device.
     *
     * Installing over the top of an existing app (`adb install -r`, and a Play update) keeps all of its
     * data, so a new build inherits the previous one's frames, crops, canvases and queued clips. That
     * is exactly the wrong inheritance during development: the leftovers were produced by different
     * code, they are gigabytes, and a run that trips over them looks like a bug in the new build.
     *
     * A fresh install has nothing to clear — Android wipes app data on uninstall — so in that case this
     * only writes the marker.
     *
     * **What it does NOT touch:** `location_map.db` (the station map and, more importantly,
     * `last_capture_time`, which cannot be re-fetched) and the device/vehicle details. Those are
     * identity and history, not working state.
     */
    private fun wipeOnFreshInstall(context: Context) {
        val marker = File(context.filesDir, INSTALL_MARKER)
        val current = BuildConfig.VERSION_CODE.toString()
        val previous = runCatching { marker.readText().trim() }.getOrNull()
        if (previous == current) return

        val working = listOf(
            "frames", "crops", "prepped",
            // An unfinalized recording is never worth keeping across a build change — CameraX writes
            // the index at finalize, so nothing can read one — and a clip in the queue belongs to a
            // pipeline that may have changed underneath it.
            CameraCaptureManager.STAGED_VIDEOS_DIR,
            CameraCaptureManager.RECORDED_VIDEOS_DIR,
            // Legacy, and kept on this list for exactly that reason. The durable queue was replaced by
            // `recorded_videos/` itself, so nothing writes here any more and it is no longer pre-flighted
            // as a working folder — but a device upgraded from an older build is still carrying one, and
            // this is the only thing that will ever remove it.
            "video_queue",
            "runs"
        )
        var removed = 0
        working.forEach { name ->
            val dir = File(context.filesDir, name)
            if (dir.exists() && dir.deleteRecursively()) removed++
        }
        runCatching { marker.writeText(current) }
        Log.i(
            TAG,
            if (previous == null) "first run of this install — marker written, $removed dir(s) cleared"
            else "build changed ($previous -> $current) — cleared $removed working dir(s)"
        )
    }

    /**
     * Opens every SQLite helper so its `onCreate`/`onUpgrade` runs now rather than on whichever call
     * site happens to touch it first.
     *
     * Worth doing eagerly for one reason: `location_map.db`'s upgrade path is the only migration in the
     * app that must not be destructive, and running it at a known moment — with the log line next to
     * the rest of setup — beats discovering it fired halfway through a capture.
     */
    fun ensureTables(context: Context): Boolean {
        var ok = true

        ok = verify(
            "location_map.db",
            listOf(LocationMapDbHelper.TABLE)
        ) { LocationMapDbHelper(context).writableDatabase } && ok

        ok = verify(
            "uploads.db",
            listOf(UploadDbHelper.TABLE_READINGS, UploadDbHelper.TABLE_IMAGES)
        ) { UploadDbHelper(context).writableDatabase } && ok

        ok = verify(
            "user.db",
            listOf(UserDbHelper.TABLE_USER)
        ) { UserDbHelper(context).writableDatabase } && ok

        // The error log verifies itself last, and its failure is the awkward one: the place a fault is
        // reported IS the thing that is broken. It still belongs here — a missing row on the errors
        // screen is worth knowing about even when the only place the answer can go is Logcat.
        ok = verify(
            "error_log.db",
            listOf(ErrorLogDbHelper.TABLE)
        ) { ErrorLogDbHelper(context).writableDatabase } && ok

        Log.d(TAG, if (ok) "tables ready" else "TABLES NOT READY")
        return ok
    }

    /**
     * Opens a database and checks the tables it should hold are actually there.
     *
     * Opening a [android.database.sqlite.SQLiteOpenHelper] is not proof of anything: it creates the
     * file and returns a handle whether or not `onCreate` did what it meant to, and a migration that
     * half-ran leaves a file that opens perfectly and is missing a table. Asking `sqlite_master`
     * is the difference between "the file exists" and "the schema is there".
     */
    private fun verify(
        label: String,
        tables: List<String>,
        open: () -> android.database.sqlite.SQLiteDatabase
    ): Boolean = try {
        open().use { db ->
            val missing = tables.filter { table ->
                db.rawQuery(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
                    arrayOf(table)
                ).use { c -> !c.moveToFirst() }
            }
            if (missing.isEmpty()) {
                true
            } else {
                Log.e(TAG, "$label is missing table(s): ${missing.joinToString()}")
                false
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "$label would not open", e)
        false
    }

    /**
     * Arms every recurring background job. All four are idempotent and cheap; re-arming on each launch
     * is how a cancelled or DST-drifted schedule gets repaired.
     */
    fun armBackgroundWork(context: Context) {
        // The readings/images queues, and — separately, so megabytes of debug images can never hold up
        // a reading — the debug artifact outbox.
        UploadScheduler.ensurePeriodic(context)
        DebugUploadScheduler.ensurePeriodic(context)
        // 02:00 Tallinn: the station map the location trigger reads.
        LocationMapRefreshScheduler.ensureArmed(context)
        // 03:00 Tallinn: sun times, which decide Stage 3's day/night branch. An hour apart from the
        // above so one service's failure cannot cost the other's data.
        AzureMapsRefreshScheduler.ensureArmed(context)
        Log.d(TAG, "background work armed")
    }

    /**
     * Asks for the initial station map when there is none.
     *
     * @return true when a fetch was requested. The request carries a `CONNECTED` constraint, so on a
     *   device with no signal it is *held* rather than failed and runs when connectivity returns.
     */
    fun ensureStationMap(context: Context): Boolean {
        if (LocationMap.exists(context)) {
            Log.d(TAG, "station map present — ${LocationMap.count(context)} station(s)")
            return false
        }
        Log.i(TAG, "no station map yet — requesting the initial fetch")
        LocationMapRefreshScheduler.requestCatchUp(context)
        return true
    }

    /**
     * How many of the background components are actually live, and how many there should be.
     *
     * Five things keep Stage 0 running, and they fail independently — which is the whole reason this is
     * a fraction rather than a lamp. The capture service can be killed while the WorkManager jobs stay
     * armed, and a "Clean memory" cancels every job while the service keeps going. A single up/down
     * light would show green in both cases.
     *
     * The four jobs are read by their unique names; a job counts as live when WorkManager holds it in
     * any state that is not cancelled or finished — `ENQUEUED` is the normal resting state for a
     * periodic job between runs, so treating only `RUNNING` as live would report 0/5 almost always.
     *
     * Blocking: `getWorkInfosForUniqueWork` returns a future, and this is called from the UI thread on
     * a resume. The query is a local Room read of four rows, but if it ever shows up in a frame trace
     * this is the thing to move off the main thread.
     */
    fun backgroundServices(context: Context): Pair<Int, Int> {
        val jobs = listOf(
            LocationMapRefreshScheduler.PERIODIC_NAME,
            AzureMapsRefreshScheduler.PERIODIC_NAME,
            UploadScheduler.PERIODIC_NAME,
            DebugUploadScheduler.PERIODIC_NAME
        )
        val wm = WorkManager.getInstance(context.applicationContext)
        val armed = jobs.count { name ->
            runCatching {
                wm.getWorkInfosForUniqueWork(name).get().any { !it.state.isFinished }
            }.getOrDefault(false)
        }
        val serviceUp = if (FleetService.isRunning) 1 else 0
        return (armed + serviceUp) to (jobs.size + 1)
    }

    /**
     * Opens this app's permission screen in Settings.
     *
     * The only way to obtain `ACCESS_BACKGROUND_LOCATION` on Android 11+: the platform stopped granting
     * it from an in-app dialog, and requires the user to choose "Allow all the time" themselves. Asking
     * via `requestPermissions` there does not show a dialog and does not grant — it just returns denied,
     * which is why this app deep-links instead of pretending to ask.
     */
    fun openPermissionSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { Log.e(TAG, "could not open the app's settings page", it) }
    }
}
