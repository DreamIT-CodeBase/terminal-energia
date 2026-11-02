package com.terminalscout.app.prod.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.camera.core.Preview
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.terminalscout.app.prod.ContainedFaults
import com.terminalscout.app.prod.MainActivity
import com.terminalscout.app.prod.Stage0Health
import com.terminalscout.app.prod.Watchdog
import com.terminalscout.app.prod.containedIn
import com.terminalscout.app.prod.crashproof
import com.terminalscout.app.prod.crashproofOr
import com.terminalscout.app.prod.capture.LocationMapRefreshScheduler
import com.terminalscout.app.prod.capture.TriggerSource
import com.terminalscout.app.prod.ui.ErrorLog
import com.terminalscout.app.prod.ui.KioskMode
import com.terminalscout.app.prod.config.DeviceConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The app's engine. Owns capture and processing; runs with or without a screen.
 *
 * ### Why a service at all
 * The record → queue → process loop used to live in `FirstFragment`, in its view lifecycle scope. That
 * meant the app only did its job while somebody had the screen open and in the foreground — fine for a
 * tester watching a clip go through, useless on a truck. A foreground service with a notification is
 * the only thing on Android that keeps CPU work running with the screen off, the phone unplugged and
 * the app swiped out of recents; a partial wake lock on top is what stops the CPU idling while the
 * pipeline sits inside native code, which is minutes at a time.
 *
 * The debug screen is now an observer ([FleetBus]) and a remote control (this service's intents and
 * [LocalBinder]). It can be closed at any moment and nothing stops.
 *
 * ### Two lanes, deliberately independent
 * [CaptureLane] and [ProcessingLane] do not wait for each other. A clip takes minutes to process and a
 * truck can pass a second forecourt inside that window; a capture path gated on the pipeline being idle
 * would simply not record it. The cost is that the camera and the pipeline can be busy at once, which
 * on this device is a thermal question — see the note on [ProcessingLane]'s cooldown. The capture lane
 * releases the camera the instant a clip finalizes, which is most of the mitigation.
 *
 * ### It is a LifecycleOwner
 * CameraX binds use cases to a lifecycle and a `Service` is not one, so this carries its own
 * [LifecycleRegistry], driven from the service callbacks. That is a smaller thing to own than a
 * dependency on `androidx.lifecycle:lifecycle-service` for one class.
 *
 * ### Killed mid-processing
 * `START_STICKY` plus clips living on disk means a process death costs the run in flight, not the
 * backlog: Android restarts the service, [ProcessingLane.start] resumes polling, and every clip still in
 * `recorded_videos/` is picked up again — a processed clip is deleted, so what remains is exactly what is
 * still owed. This is not theoretical for this app; it has a history of being killed under thermal and
 * memory pressure.
 *
 * ### Not implemented: starting on boot
 * A deployed truck needs this service up without anybody opening the app, which means a
 * `BOOT_COMPLETED` receiver. It is deliberately absent: Android 14 forbids starting a `camera`-type
 * foreground service from the background, so a boot receiver would have to start in `dataSync` only and
 * promote when a capture fires — the promotion path below already handles exactly that, but the policy
 * around it (what to do when promotion is refused) belongs with the autonomous trigger work.
 */
class FleetService : Service(), LifecycleOwner {

    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry

    /**
     * The service's scope. Both lanes' background work runs in it.
     *
     * The handler is load-bearing, not decoration: a `SupervisorJob` stops a failing child cancelling
     * its siblings and does nothing at all about the exception, which goes to the thread's
     * uncaught-exception handler and takes the process down. The wake-lock renewal, the dead-man loop
     * and every `fileRun` are launched here, so without it any one of them could kill the unit.
     */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            containedIn(SOURCE, ContainedFaults.SERVICE, "fleet-service coroutine")
    )

    private lateinit var captureLane: CaptureLane
    private lateinit var processingLane: ProcessingLane

    private var wakeLock: PowerManager.WakeLock? = null
    private var started = false

    /**
     * Whether `onCreate` managed to build the two lanes.
     *
     * False is reachable: `CaptureLane`'s constructor reaches into CameraX and the fused location
     * provider, and a device missing either can refuse. An instance in that state can never do its job,
     * so [ensureStarted] stands it down rather than going foreground and reporting itself healthy —
     * which is the shape of failure this whole file is written to avoid.
     */
    private var lanesBuilt = false

    /** True once [startForeground] has been called with the camera type included. */
    private var cameraTypeHeld = false

    // ---- Binding (the debug screen's remote control) ---------------------------------------------- //

    /**
     * Handed to a screen that binds. Everything on it is a request the lanes are free to refuse.
     *
     * A binder rather than a static instance reference: the screen's hold on the service is then the
     * system's business, and a stale reference cannot outlive the service it points at.
     */
    inner class LocalBinder : Binder() {

        /**
         * Every method here is guarded, and the reason is the caller rather than the callee: a bound
         * screen invokes these from a click listener on the UI thread, so a throw is a crash of the
         * Activity — and `lateinit` makes that concrete, because a lane `onCreate` could not build
         * answers `UninitializedPropertyAccessException` to a button press.
         */
        /** Fires the manual trigger's start edge. */
        fun startCapture(reason: String = "debug button") =
            crashproof(SOURCE, ContainedFaults.SERVICE, "binder.startCapture") {
                fireCaptureStart(TriggerSource.MANUAL_BUTTON, reason)
            }

        /** Fires the manual trigger's stop edge. */
        fun stopCapture(reason: String = "debug button") =
            crashproof(SOURCE, ContainedFaults.SERVICE, "binder.stopCapture") {
                captureLane.manualTrigger.release(reason)
            }

        // enqueueTestClip used to sit here, handing a sideloaded clip to the processing lane. Both
        // halves are gone — see the note in ProcessingLane: copying a test clip into the capture queue
        // renamed it out of `testclips/`, and every check that keeps replayed footage out of the fleet's
        // data keys on it being there. There is no replay path in this build at all.

        /** Starts drawing the camera preview onto [provider]. */
        fun attachPreview(provider: Preview.SurfaceProvider) =
            crashproof(SOURCE, ContainedFaults.SERVICE, "binder.attachPreview") {
                captureLane.attachPreview(provider)
            }

        /** Stops drawing the camera preview. */
        fun detachPreview() =
            crashproof(SOURCE, ContainedFaults.SERVICE, "binder.detachPreview") {
                captureLane.detachPreview()
            }
    }

    override fun onBind(intent: Intent?): IBinder = LocalBinder()

    // ---- Service lifecycle -------------------------------------------------------------------------- //

    override fun onCreate() {
        // `isRunning` is deliberately NOT set here. See its declaration: it means "this instance went
        // foreground and brought its lanes up", not "an instance exists". Claiming it in onCreate is
        // what let a health-check intent construct a service that then reported itself healthy forever.
        super.onCreate()
        // Guarded, because a service lifecycle callback has no frame of ours above it: a throw here is a
        // process death before the service exists, and the watchdog then respawns straight into it.
        // `CaptureLane`'s constructor reaches CameraX and the fused location provider, both of which a
        // device can refuse — see [lanesBuilt] for what happens next when it does.
        crashproof(SOURCE, ContainedFaults.SERVICE, "onCreate") {
        registry.currentState = Lifecycle.State.CREATED
        registry.currentState = Lifecycle.State.STARTED

        captureLane = CaptureLane(
            context = applicationContext,
            lifecycleOwner = this,
            onCameraNeeded = { promoteToCameraType() }
            // The processing lane is NOT told about a capture. It polls recorded_videos/ and finds the
            // clip within five seconds on its own — the two lanes share a directory and nothing else.
            //
            // There is no completion callback any more. The one that existed did a single thing: copy
            // the finished clip into DCIM/Camera, on the main thread, a few hundred megabytes at a
            // time. With that gone the lanes are coupled by the directory alone, which is what the
            // paragraph above always claimed.
        )
        processingLane = ProcessingLane(applicationContext, scope)
        lanesBuilt = true
        }

        // The notification mirrors the bus, so there is one description of what the service is doing
        // and the shade cannot disagree with the screen. Launched outside the guard above and with its
        // own inner guard: a notification that will not build must not stop the lanes, and a lane that
        // would not build must not stop the shade describing what little is running.
        scope.launch {
            combine(FleetBus.status, FleetBus.queue) { status, queue -> status to queue }
                .collect { (status, queue) ->
                    crashproof(SOURCE, ContainedFaults.SERVICE, "updateNotification") {
                        if (started) updateNotification(status, queue.size)
                    }
                }
        }
    }

    /**
     * Guarded whole, and the failure path has to satisfy the foreground obligation on its way out.
     *
     * Whoever called `startForegroundService` bought this instance five seconds to reach
     * `startForeground`, and that debt does not go away because our dispatch threw. Stopping without
     * paying it kills the process with `ForegroundServiceDidNotStartInTimeException` — which the
     * watchdog then respawns into the same fault, and the unit loops. So the catch goes foreground
     * first and stops second. See [standDownSatisfyingObligation].
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val result = crashproofOr(SOURCE, ContainedFaults.SERVICE, "onStartCommand", null) {
            dispatch(intent)
        }
        if (result != null) return result
        if (!started) standDownSatisfyingObligation("the capture service failed while starting")
        return START_NOT_STICKY
    }

    /** What [onStartCommand] does when nothing goes wrong. */
    private fun dispatch(intent: Intent?): Int {
        // A NULL intent is the system restarting us after a kill — `START_STICKY` re-creates the service
        // and delivers no intent, which is the one reliable way to tell that restart apart from a start
        // somebody asked for.
        //
        // It is a background start, so this instance can hold neither `location` nor `camera`: on
        // Android 14+ `startForeground` throws, and below that it succeeds and the service is simply
        // blind, which is how a truck drives past forty forecourts with a green readout. Neither is
        // worth having, so it hands over to the Activity and gets out of the way.
        //
        // Crucially there is **no outstanding `startForegroundService()` obligation** on this path —
        // nobody called it — so stopping without going foreground is safe here and *only* here. See
        // [standDown].
        if (intent == null) {
            standDown("the capture service was restarted by the system with no screen")
            return START_NOT_STICKY
        }

        when (intent.action) {
            ACTION_STOP -> {
                Log.i(TAG, "stop requested")
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START_CAPTURE -> {
                if (!ensureStarted()) return START_NOT_STICKY
                val reason = intent.getStringExtra(EXTRA_REASON) ?: "external intent"
                // EXTERNAL_INTENT rather than the parser's own default: this branch IS the adb door, so
                // a capture that names no trigger arrived through it and must not be filed as a button.
                val source = TriggerSource.fromWire(
                    intent.getStringExtra(EXTRA_TRIGGER),
                    fallback = TriggerSource.EXTERNAL_INTENT
                )
                fireCaptureStart(source, reason)
                return START_STICKY
            }

            ACTION_HEALTH -> {
                // Deliberately does NOT call ensureStarted: its whole job is to repair a lane inside a
                // service that is already up, and a start from here could be refused the `location`
                // type anyway.
                //
                // But `requestHealthCheck` sends this with plain `startService`, and on Android that
                // means "start it OR deliver to it" — so a repair aimed at a service that has just gone
                // down CREATES one instead. That instance never reaches [ensureStarted]: no
                // startForeground, no lanes, and no 60-second self-heal loop, because that loop lives
                // inside ensureStarted. Left alive it was a permanent latch — see [isRunning].
                //
                // Standing down is safe on this path and, like `intent == null`, only here: nobody
                // called `startForegroundService()` for it, so there is no five-second obligation to
                // satisfy. It hands over to the Activity, which starts the service properly.
                if (!started) {
                    standDown("a health-check intent created the capture service with no lanes")
                    return START_NOT_STICKY
                }
                if (::captureLane.isInitialized) captureLane.ensureAttached()
                return START_STICKY
            }

            ACTION_PROCESSING_RESTART -> {
                // Like ACTION_HEALTH: it repairs something inside a service that is already up, so it
                // must not call ensureStarted — and an instance this intent CREATED has no lanes and
                // nothing to restart, so it stands down the same way. See ACTION_HEALTH for why that is
                // safe on this path and only on this path.
                if (!started) {
                    standDown("a processing-restart intent created the capture service with no lanes")
                    return START_NOT_STICKY
                }
                if (::processingLane.isInitialized) {
                    processingLane.restartPolling(
                        intent.getStringExtra(EXTRA_REASON) ?: "asked for by the health check"
                    )
                }
                return START_STICKY
            }

            ACTION_STOP_CAPTURE -> {
                if (!ensureStarted()) return START_NOT_STICKY
                captureLane.manualTrigger.release(intent.getStringExtra(EXTRA_REASON) ?: "external intent")
                return START_STICKY
            }
        }
        return if (ensureStarted()) START_STICKY else START_NOT_STICKY
    }

    /**
     * The manual trigger's start edge — the debug button and the adb intent.
     *
     * It no longer promotes the foreground-service type. That moved to [CaptureLane], which is the one
     * place *every* capture passes through: this function is reached from two doors, and the autonomous
     * trigger uses neither of them. Promoting here looked like "the single entry point for starting a
     * capture" and was in fact the single entry point for the two that did not matter.
     */
    private fun fireCaptureStart(source: TriggerSource, reason: String) {
        captureLane.manualTrigger.fire(source, reason)
    }

    /**
     * Hands over to the Activity and stops this instance.
     *
     * The Activity's `onResume` runs `AppSetup`, which starts the service again — from the foreground,
     * where both foreground-service types are available. The relaunch is deliberately **not** charged to
     * the crash-loop budget: whatever killed the process has already been counted, and paying twice for
     * one fault would halve the guard.
     *
     * **Only call this when nothing called `startForegroundService()` for this instance.** That call
     * carries a five-second obligation to reach `startForeground`, and stopping without it kills the
     * process with `ForegroundServiceDidNotStartInTimeException` — which the watchdog then respawns
     * straight back into the same situation. That is exactly what an earlier version of this did: it
     * stood down on `!KioskMode.isActivityVisible`, which is a *race* rather than a signal — the flag
     * goes false the instant the Activity pauses, and an explicit start from a visible Activity whose
     * `onStartCommand` lands a moment after that pause is a start that MUST go foreground.
     */
    private fun standDown(reason: String) {
        Log.w(TAG, "standing down — $reason")
        // Three independent steps, guarded independently. `stopSelf` is the one that MUST happen — an
        // instance that neither serves nor stops is the permanent latch this file's history is full of —
        // so it cannot sit behind a respawn request or an Activity launch that might throw.
        runCatching { Watchdog.requestRespawn(this, reason, counted = false) }
            .onFailure { Log.e(TAG, "could not ask for a respawn", it) }
        runCatching { KioskMode.bringToForeground(this, reason) }
            .onFailure { Log.e(TAG, "could not bring the app forward", it) }
        runCatching { stopSelf() }
            .onFailure { Log.e(TAG, "could not stop the service", it) }
    }

    /**
     * [standDown] for the paths where the five-second `startForeground` obligation may still be owed.
     *
     * `intent == null` and `ACTION_HEALTH` are the two paths where it is provably *not* owed, and they
     * call [standDown] directly. Everything else reaching a stand-down came through a real
     * `startForegroundService`, so the notification has to be posted before the instance goes — even
     * though it is posted only to be taken down a line later. The alternative is the platform killing
     * the process for the debt, which costs the same notification and a respawn cycle as well.
     */
    private fun standDownSatisfyingObligation(reason: String) {
        runCatching { goForeground(ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) }
            .onFailure { Log.w(TAG, "could not go foreground on the way out", it) }
        standDown(reason)
    }

    /**
     * Brings the service up, once. Repeat `startService` calls land here and do nothing.
     *
     * @return true when the service is up and should stay up. False means going foreground was refused
     *   and it has asked for the app instead, so the caller must return `START_NOT_STICKY` rather than
     *   inviting the system to do the same thing again.
     */
    private fun ensureStarted(): Boolean {
        if (started) return true

        // An instance whose lanes were never built cannot record, cannot process and cannot repair
        // itself. Going foreground would make it *look* healthy to `isRunning`, `Stage0Health` and the
        // home panel, which is strictly worse than being down.
        if (!lanesBuilt) {
            Log.e(TAG, "the lanes were never built — standing down")
            runCatching { ErrorLog.showError(ERR_LANES_MISSING, MSG_LANES_MISSING, SOURCE) }
            standDownSatisfyingObligation("the capture service could not build its lanes")
            return false
        }

        started = true

        // location only to begin with; the camera type is added when a capture actually fires — see
        // [promoteToCameraType].
        //
        // dataSync is deliberately NOT among these. On Android 15 a dataSync service may run for 6
        // hours in any 24 and is then killed with a fatal RemoteServiceException, which is a daily
        // outage for a unit meant to run continuously. Nothing needs it: the transfers are all
        // WorkManager's, and CPU work needs no type.
        //
        // Guarded, because this is the one call that can be refused outright: on Android 14+ a service
        // started from the background may not take the `location` type, and the refusal arrives as a
        // SecurityException out of startForeground. There is nothing to be done about the obligation at
        // that point — the platform will stop the process either way — but standing down means the app
        // is asked for on the way out, so the next start comes from a screen and succeeds.
        try {
            goForeground(ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            // Claimed HERE, not in onCreate. This is the point of no return: the instance is a real
            // foreground service, the lanes below are unconditional, and the 60-second self-heal loop
            // is about to be launched. Every reader of this flag — `AppSetup`'s "do I need to start
            // it?", `Stage0Health.repair`'s "can this device record?" — is asking about *this* moment,
            // not about whether an object was constructed. Same rule as `ProcessingLane.isPolling`:
            // the flag means what the health check is actually asking.
            isRunning = true
        } catch (e: Exception) {
            Log.e(TAG, "could not take the location foreground-service type", e)
            started = false
            standDown("the capture service was refused the location type")
            return false
        }

        @Suppress("DEPRECATION")
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "terminalscout:fleet")
            .also { it.setReferenceCounted(false); it.acquire(WAKE_LOCK_MS) }

        // The lease is renewed on a CLOCK, not per clip. Renewing between clips looks right and is not:
        // a clip that runs longer than the lease loses the lock in the middle of itself, the CPU is free
        // to idle with the screen off, and the clip stretches out — one validation clip took 5.9 hours
        // against a 6-14 minute norm that way.
        scope.launch {
            while (true) {
                delay(WAKE_LOCK_RENEW_MS)
                runCatching { wakeLock?.acquire(WAKE_LOCK_MS) }
            }
        }

        // The station map the location trigger reads. Armed here rather than from the UI: it is a
        // background job by requirement, and the service is the only thing guaranteed to start.
        //
        // Guarded: WorkManager can throw on a process whose initialisation was pre-empted, and a
        // schedule that will not arm must not cost the lanes below it. `AppSetup.armBackgroundWork` and
        // `Stage0Health.repair` both re-arm it on their own cadence, so this is not the only chance.
        runCatching { LocationMapRefreshScheduler.ensureArmed(this) }
            .onFailure { Log.e(TAG, "could not arm the station-map refresh", it) }

        // The dead-man's switch. This loop is the app's proof of life: if the process is killed in a way
        // that runs none of our code — an out-of-memory or thermal kill — nothing renews the deadline
        // and the alarm fires, which is the only thing that notices those deaths at all. Renewed on a
        // clock rather than tied to any piece of work, for the same reason as the wake lock above:
        // anything else leaves a gap the length of whatever is running.
        scope.launch {
            while (true) {
                // Guarded separately from the health check below it. This is the app's proof of life and
                // it is the half that must not be skipped: without it the dead-man alarm fires three
                // minutes later against a service that is perfectly alive.
                runCatching { Watchdog.armDeadMan(this@FleetService) }
                    .onFailure { Log.e(TAG, "could not re-arm the dead-man alarm", it) }

                // The health check for the case the ALARM cannot cover.
                //
                // The renewal above pushes the dead-man deadline out every 60 s against a 3-minute
                // deadline, so while this service is alive the alarm never fires — which is correct for a
                // liveness alarm and useless for a health check. The two halves therefore split cleanly:
                // the alarm catches a service that is gone, and this loop catches a lane that has died
                // *inside* a service that is still running. Without it, a trigger detached by a revoked
                // permission would go unnoticed, and the rows raised by a repaired fault would never be
                // cleared, because nothing would run to clear them.
                runCatching {
                    val health = Stage0Health.check(this@FleetService, includeJobs = true)
                    Stage0Health.report(health)
                    if (!health.triggerAttached || !health.stopMonitorWatching) {
                        Log.w(TAG, "stage 0 degraded inside a running service — ${health.summary()}")
                        captureLane.ensureAttached()
                    }
                    // Repaired from in here as well as from the watchdog alarm: this loop runs every
                    // 60 s and the alarm can be minutes away, so a stalled poller is back long before a
                    // backlog builds.
                    // Both processing faults, and they take different repairs — see
                    // ProcessingLane.restartPolling. This loop is the FAST path for them: it runs every
                    // 60 s on the service's own dispatcher, so it normally catches a stall long before
                    // the 3-minute alarm would. It cannot be the only path, because the thing that is
                    // wedged may be this service's scope — which is what the watchdog is for.
                    if (!health.pollerRunning) {
                        processingLane.ensurePolling()
                    } else if (health.pollerStalled) {
                        processingLane.restartPolling(
                            "silent ${health.pollerSilentMs / 60_000} min inside a run"
                        )
                    }
                }.onFailure { Log.e(TAG, "the in-service health check failed", it) }

                delay(Watchdog.DEAD_MAN_RENEW_MS)
            }
        }

        // Each lane started independently: they share nothing but a directory, so a capture lane that
        // will not come up must not stop the processing lane draining a backlog already on disk — and a
        // processing lane that will not come up must not stop the unit recording.
        crashproof(SOURCE, ContainedFaults.SERVICE, "captureLane.start") { captureLane.start() }
        crashproof(SOURCE, ContainedFaults.SERVICE, "processingLane.start") { processingLane.start() }
        Log.i(TAG, "fleet service up — capture + processing lanes running, map refresh armed")
        return true
    }

    /**
     * Adds the `camera` foreground-service type before a recording starts.
     *
     * On API 34+ a service must declare the camera type to use the camera while in the background, and
     * may only take that type if it was started from the foreground. Both halves matter here: without
     * the promotion the capture fails with the screen off, and if the promotion is refused there is no
     * point starting the camera at all — so the refusal is surfaced rather than swallowed.
     *
     * Called by [CaptureLane] on every capture, from whichever trigger asked. It is cheap to call
     * repeatedly: [cameraTypeHeld] short-circuits once the type is held, and the type is held for the
     * life of this service instance.
     *
     * @return true when the service holds the camera type and the capture may proceed.
     */
    private fun promoteToCameraType(): Boolean {
        if (cameraTypeHeld) return true
        return try {
            goForeground(
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            )
            cameraTypeHeld = true
            ErrorLog.removeErrorByKey(ERR_CAMERA_TYPE_REFUSED)
            // Logged on the SUCCESS path, not only the failure. The absence of this line is what the
            // camera-type bug looked like from the outside for months: captures worked on the test
            // device, nothing was ever logged, and nobody could see that the autonomous path had never
            // asked. One line per service instance, and it names which trigger finally asked for it.
            Log.i(TAG, "camera foreground-service type HELD — captures may use the camera in background")
            true
        } catch (e: Exception) {
            // Typically ForegroundServiceStartNotAllowedException: the service is running but was
            // started from the background, so the system will not grant it camera access. The capture
            // will fail; say why now rather than leaving an unexplained black clip.
            // This is the documented consequence of a service created while nothing was on screen: a
            // foreground service that needs a while-in-use permission cannot be created from the
            // background, and the refusal is permanent for THIS service instance. Bringing the app
            // forward does not rescue the capture in flight, but it puts the app in a state where the
            // next one succeeds.
            Log.e(TAG, "could not take the camera foreground-service type — capture will fail", e)
            ErrorLog.showError(ERR_CAMERA_TYPE_REFUSED, MSG_CAMERA_TYPE_REFUSED, SOURCE)
            FleetBus.publishStatus("Cannot use the camera in the background — open the app once.")
            KioskMode.bringToForeground(this, "camera type was refused")
            false
        }
    }

    override fun onDestroy() {
        // Step by step, all of them guarded, and `super.onDestroy()` unconditionally at the end.
        //
        // This is the shutdown path, so it runs when something has usually already gone wrong, and every
        // step releases something the next instance will want: two location subscriptions, the camera,
        // and a partial wake lock that would otherwise hold the CPU awake until the lease expires. One
        // of them throwing must not strand the rest — a leaked wake lock on a phone in a cab is a flat
        // battery, and a leaked location subscription is a readout that lies to the instance replacing
        // this one.
        isRunning = false
        Log.i(TAG, "fleet service going down")
        crashproof(SOURCE, ContainedFaults.SERVICE, "onDestroy(lifecycle)") {
            registry.currentState = Lifecycle.State.DESTROYED
        }
        crashproof(SOURCE, ContainedFaults.SERVICE, "onDestroy(captureLane)") {
            if (::captureLane.isInitialized) captureLane.stop()
        }
        crashproof(SOURCE, ContainedFaults.SERVICE, "onDestroy(processingLane)") {
            if (::processingLane.isInitialized) processingLane.stop()
        }
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
            .onFailure { Log.e(TAG, "could not release the wake lock", it) }
        runCatching { scope.cancel() }
            .onFailure { Log.e(TAG, "could not cancel the service scope", it) }
        super.onDestroy()
    }

    // ---- Notification --------------------------------------------------------------------------------- //

    private fun goForeground(type: Int) {
        val notification = buildNotification(FleetBus.status.value, FleetBus.queue.value.size)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(status: FleetBus.Status, queueSize: Int) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(status, queueSize))
    }

    private fun buildNotification(status: FleetBus.Status, queueSize: Int): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Fleet capture", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val title = when {
            status.recording -> "Recording"
            status.processing -> "Processing — $queueSize in queue"
            queueSize > 0 -> "$queueSize clip(s) queued"
            else -> "Terminal Scout Prod — ready"
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return builder
            .setContentTitle(title)
            .setContentText(status.text)
            .setStyle(Notification.BigTextStyle().bigText(status.text))
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val TAG = "FleetService"

        // No fix screen: the refusal is a platform rule about how this service instance was started,
        // and it heals when the app is next opened in the foreground.
        const val SOURCE = "Capture service"
        const val ERR_CAMERA_TYPE_REFUSED = "camera_type_refused"
        const val MSG_CAMERA_TYPE_REFUSED =
            "The camera could not be claimed. Open the app and leave it on screen."

        // Raised when this instance could not even build its lanes — a device that refused CameraX or
        // the location provider outright. Its own key rather than a contained-fault row, because it is
        // not a transient fault: nothing on this device will record until whatever it is gets fixed.
        const val ERR_LANES_MISSING = "capture_lanes_missing"
        const val MSG_LANES_MISSING =
            "The capture service could not start its camera and location parts, so nothing can be " +
                "recorded on this device."

        /**
         * Whether this service is up **and doing its job** — foreground, with its lanes started.
         *
         * A flag rather than `ActivityManager.getRunningServices`, which is deprecated and, since
         * Android 8, only ever reports the caller's own services anyway — so it would answer the same
         * question through a slower and less honest route.
         *
         * **Set in [ensureStarted], not in onCreate, and that is the whole point.** It used to mean "an
         * instance exists", which is not what a single one of its readers asks: `AppSetup` asks "do I
         * need to send a start intent?" and `Stage0Health` asks "can this device record right now?"
         * Both want *ready*. A service created by a `startService` health-check intent — which never
         * reaches this function — satisfied the old meaning and none of the intended one, and the
         * result was a permanent latch: `Stage0Health.repair` saw `serviceRunning = true` so it never
         * took its `FleetService.start` branch, `AppSetup`'s `alreadyRunning` short-circuit sent no
         * start intent either, so not even opening the app could clear it, and the only repair left was
         * the very intent that had created the thing. Nothing recorded, and the errors screen cleared
         * `ERR_SERVICE_DOWN` while the service was dead.
         *
         * So: true from the moment `startForeground` succeeds until onDestroy. Anything narrower is a
         * lie the repair paths cannot see through.
         */
        @Volatile
        var isRunning: Boolean = false
            private set


        private const val CHANNEL = "fleet"
        private const val NOTIFICATION_ID = 5100

        /** Brings the service up (or does nothing if it is already running). */
        const val ACTION_START = "com.terminalscout.app.prod.FLEET_START"

        /**
         * Fires the manual trigger's start edge, from anywhere — including a shell, with no screen
         * involved. This is the shape the autonomous trigger system uses too:
         *
         * ```
         * adb shell am start-foreground-service          *   -a com.terminalscout.app.prod.FLEET_START_CAPTURE          *   -n com.terminalscout.app/.service.FleetService          *   --es reason "bench test" --es trigger "geofence"
         * ```
         *
         * `reason` is free text and is logged with the clip; `trigger` is a [TriggerSource] wire name
         * and defaults to `external_intent` if absent or unrecognised — this door is not a button, and a
         * capture filed under one cannot be told from a tester's afterwards.
         */
        const val ACTION_START_CAPTURE = "com.terminalscout.app.prod.FLEET_START_CAPTURE"

        /** Fires the manual trigger's stop edge. */
        const val ACTION_STOP_CAPTURE = "com.terminalscout.app.prod.FLEET_STOP_CAPTURE"

        /**
         * Asks the capture lane to re-attach anything that has stopped watching.
         *
         * Sent by the Stage 0 health check when the service is up but a lane inside it is not. Plain
         * `startService`, never `startForegroundService`: the service is already foreground by the time
         * this can be useful, and the foreground variant would attach a five-second startForeground
         * obligation to a path that has no reason to satisfy one.
         */
        const val ACTION_HEALTH = "com.terminalscout.app.prod.FLEET_HEALTH"

        /**
         * Cancels a wedged processing run and restarts the poll loop. See [requestProcessingRestart].
         *
         * Its own action rather than a flag on [ACTION_HEALTH], because the health check must keep
         * being the harmless one: it is sent every time a lane looks detached, and a version of it that
         * could cancel a healthy multi-minute run in flight would be a repair worse than the fault.
         */
        const val ACTION_PROCESSING_RESTART = "com.terminalscout.app.prod.FLEET_PROCESSING_RESTART"

        /** Shuts the service down. */
        const val ACTION_STOP = "com.terminalscout.app.prod.FLEET_STOP"

        const val EXTRA_REASON = "reason"
        const val EXTRA_TRIGGER = "trigger"

        /**
         * Wake-lock lease. A timeout rather than an indefinite lock: if the service dies in a way that
         * skips [onDestroy], an indefinite partial lock would keep the CPU awake until reboot and
         * flatten the battery of a phone somebody left in a cab.
         */
        private val WAKE_LOCK_MS: Long get() = DeviceConfig.long("capture.service.wake_lock_ms", 60 * 60 * 1000L)

        /** Renewed well inside the lease so it can never lapse mid-clip. */
        private val WAKE_LOCK_RENEW_MS: Long get() = DeviceConfig.long("capture.service.wake_lock_renew_ms", 15 * 60 * 1000L)

        /**
         * Starts the service. **Call this from the foreground** (an Activity), at least once per boot:
         * the camera foreground-service type cannot be taken by a background-started service on
         * Android 14+, and a capture without it fails with the screen off.
         */
        /**
         * Stops the service and everything it owns.
         *
         * Goes through [ACTION_STOP] rather than `stopService`, because that path returns
         * `START_NOT_STICKY` — it is the one route that tells the system this was deliberate and it
         * must not restart the service. `stopService` would leave `START_STICKY` standing.
         */
        fun stop(context: Context) {
            val intent = Intent(context, FleetService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
                .onFailure { Log.w(TAG, "could not deliver the stop intent", it) }
        }

        /** Asks a running service to re-attach its lanes. A no-op when it is not running. */
        fun requestHealthCheck(context: Context) {
            val intent = Intent(context, FleetService::class.java).setAction(ACTION_HEALTH)
            runCatching { context.startService(intent) }
                .onFailure { Log.w(TAG, "could not deliver the health-check intent", it) }
        }

        /**
         * Asks a running service to cancel a wedged processing run and start the poll loop again.
         *
         * Separate from [requestHealthCheck] because the two repairs are not interchangeable: that one
         * re-attaches what has DETACHED and deliberately leaves a live job alone, which is exactly
         * wrong for a loop that is alive and stuck. See [ProcessingLane.restartPolling].
         *
         * Plain `startService`, like the health check: the service is already foreground by the time
         * this can be useful, so there is no five-second `startForeground` obligation and no
         * foreground-service type to be refused — which is what makes this legal from the watchdog's
         * broadcast, with no Activity on screen.
         */
        fun requestProcessingRestart(context: Context, reason: String = "stalled") {
            val intent = Intent(context, FleetService::class.java)
                .setAction(ACTION_PROCESSING_RESTART)
                .putExtra(EXTRA_REASON, reason)
            runCatching { context.startService(intent) }
                .onFailure { Log.w(TAG, "could not deliver the processing-restart intent", it) }
        }

        fun start(context: Context) {
            val intent = Intent(context, FleetService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
