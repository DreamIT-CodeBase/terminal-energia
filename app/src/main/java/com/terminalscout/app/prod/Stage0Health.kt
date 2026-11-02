package com.terminalscout.app.prod

import android.content.Context
import android.location.LocationManager
import android.util.Log
import com.terminalscout.app.prod.capture.LocationMap
import com.terminalscout.app.prod.capture.LocationMapRefreshScheduler
import com.terminalscout.app.prod.capture.LocationTrigger
import com.terminalscout.app.prod.capture.StopRecordingMonitor
import com.terminalscout.app.prod.service.FleetService
import com.terminalscout.app.prod.service.ProcessingLane
import com.terminalscout.app.prod.ui.ErrorLog
import com.terminalscout.app.prod.ui.KioskMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Is the device actually capable of recording a clip right now?
 *
 * ### The hole this fills
 * The watchdog answers one question — *is the process alive* — and answers it well. But a process can be
 * perfectly alive with every part of Stage 0 dead inside it, and that state was invisible to everything:
 * the dead-man alarm fired every three minutes, saw a visible Activity, and went back to sleep.
 *
 * It was measured. `FleetService` was stopped on the bench and the unit sat for four minutes with the
 * capture service gone, the location trigger detached, and the home panel reading
 * `CAPTURE SERVICE : WATCHING 349`. `dumpsys` reported zero service records. Nothing noticed, because
 * nothing was asking. The only thing that would have repaired it was `AppSetup.run`, which runs on a
 * resume — that is, when a human touches the screen, which is the one thing a deployed unit never gets.
 *
 * ### What it covers, and where it stops
 * The chain from a truck passing a forecourt to an `.mp4` on disk, and no further:
 *
 * | | why it is on the list |
 * |---|---|
 * | [FleetService] | owns both lanes; nothing runs without it |
 * | [LocationTrigger] | decides that a forecourt is in range. Detached, no capture ever starts |
 * | [StopRecordingMonitor] | ends the recording and releases the frozen board. Stopped, every station is capturable at most once for the life of the process |
 * | the station map | what the trigger compares against. Empty, the comparison has no input |
 *
 * Stage 1 IS on the list, and it did not used to be. The argument for leaving it off was that a clip
 * on disk is durable — a process death costs the run in flight, not the backlog — so a stalled pipeline
 * costs time where a detached trigger costs the data outright. That is still true of the COST and was
 * never true of the coverage: `healthy` excluded the poller, `WatchdogReceiver` returned as soon as
 * `healthy` was true, and so the only thing that ever repaired the processing lane was a loop living
 * inside the very service whose scope might be the thing that is wedged. A unit could record forty
 * forecourts a day and read none of them, with a green panel, until somebody looked at the clip count.
 *
 * Two distinct faults are now reported, because they have different repairs:
 *
 * | | what it looks like | repair |
 * |---|---|---|
 * | poller DOWN | the loop ended — cancelled, or killed with its job | start it again |
 * | poller STALLED | the loop is alive and has emitted nothing for [ProcessingLane.STALL_AFTER_MS] | cancel it and start a fresh one |
 *
 * The second is the one nothing could see before: `isPolling` says the loop was claimed, and a loop
 * parked forever inside one clip satisfies that exactly as well as a working one.
 *
 * ### Repairs, and the one that needs a screen
 * [repair] is only called with a visible Activity, and that is not a detail: starting a service that
 * declares `camera` and `location` from the background is refused outright on Android 14+ and silently
 * blinded below it. When the app is *not* on screen the caller does the other thing — brings it forward,
 * and lets `AppSetup` do the repairing from there.
 */
object Stage0Health {

    private const val TAG = "Stage0Health"
    const val SOURCE = "Stage 0"

    const val ERR_SERVICE_DOWN = "stage0_service_down"
    private const val MSG_SERVICE_DOWN =
        "The capture service is not running, so nothing is being recorded."
    const val ERR_TRIGGER_DOWN = "stage0_trigger_down"
    private const val MSG_TRIGGER_DOWN =
        "The location trigger is not watching. The device will drive past a forecourt without recording."
    const val ERR_POLLER_DOWN = "stage1_poller_down"
    private const val MSG_POLLER_DOWN =
        "Video processing has stopped watching for new clips. Recording still works, but nothing is " +
            "being read from the clips already on the device."
    const val ERR_STOP_MONITOR_DOWN = "stage0_stop_monitor_down"
    private const val MSG_STOP_MONITOR_DOWN =
        "The stop-recording watch is off. A captured board is never released, so each station can be " +
            "captured at most once until the app restarts."

    /**
     * What is up and what is not.
     *
     * Every field is read from a `@Volatile` flag or an in-memory table, so this costs nothing and is
     * safe to call from a broadcast receiver, which must return in seconds.
     */
    data class Report(
        val serviceRunning: Boolean,
        val triggerAttached: Boolean,
        val stopMonitorWatching: Boolean,
        /**
         * Whether Stage 1's disk poller is running.
         *
         * The one thing on this report that is not Stage 0. It is here because the failure has the same
         * shape as a dead trigger and the same invisibility: the service is up, the readout looks
         * healthy, and clips accumulate unread. It is deliberately **not** part of [healthy] — a stalled
         * poller costs time, a detached trigger costs the data — but it is repaired and reported.
         */
        val pollerRunning: Boolean,
        /**
         * Whether that poller is alive but no longer getting anywhere.
         *
         * The fault [pollerRunning] cannot express. A loop wedged inside one clip — a socket with no
         * timeout, a native decode that never returns, a coroutine parked on a lock — keeps its flag
         * set and its state PROCESSING for the life of the process. See [ProcessingLane.isStalled].
         */
        val pollerStalled: Boolean = false,
        /** How long the processing loop has been silent, in ms. For the log line, not for a decision. */
        val pollerSilentMs: Long = 0L,
        /** What the processing loop is doing, for the readout. See [ProcessingLane.PollState]. */
        val pollState: ProcessingLane.PollState = ProcessingLane.PollState.STOPPED,
        val stationsMapped: Int,
        val gpsEnabled: Boolean = false,
        val kioskEnabled: Boolean = false,
        /**
         * WorkManager schedules that are live, and how many there should be including the service.
         *
         * Carried rather than recomputed on every read: answering it means four blocking queries against
         * WorkManager's database, which is fine three times an hour and absurd twice a second. The slow
         * callers refresh it; the fast ones carry the last known value forward. See [check].
         */
        val jobsArmed: Int = 0,
        val jobsTotal: Int = 5
    ) {
        /** The home panel's `n/5`: the periodic jobs, plus the capture service itself. */
        val servicesUp: Int get() = jobsArmed + if (serviceRunning) 1 else 0

        /** True when a forecourt passed right now would actually produce a clip. */
        val healthy: Boolean
            get() = serviceRunning && triggerAttached && stopMonitorWatching && stationsMapped > 0

        /**
         * True when clips on disk are actually being read.
         *
         * Kept separate from [healthy] rather than folded into it, because the home panel's headline is
         * "can this unit capture" and a backlog does not stop it capturing. But it is repaired with
         * exactly the same urgency — see [needsRepair], which is what the watchdog now asks.
         */
        val processing: Boolean get() = serviceRunning && pollerRunning && !pollerStalled

        /**
         * Whether anything on this report warrants a repair pass.
         *
         * The question [WatchdogReceiver] used to ask was `healthy`, which does not mention the
         * processing lane — so an alarm that fired into a unit with a wedged poller logged "stage 0
         * healthy" and went back to sleep, every three minutes, indefinitely.
         */
        val needsRepair: Boolean get() = !healthy || !processing

        /** One line for the log. Names the first thing that is wrong, in the order it breaks the chain. */
        fun summary(): String = when {
            !serviceRunning -> "capture service DOWN"
            !triggerAttached -> "location trigger NOT WATCHING"
            !stopMonitorWatching -> "stop-recording watch OFF"
            stationsMapped == 0 -> "station map EMPTY"
            // After the capture chain, because a unit that cannot record has a worse problem than a
            // unit that cannot read — but before "healthy", because it is not healthy.
            !pollerRunning -> "video processing DOWN"
            pollerStalled -> "video processing STALLED — silent for ${pollerSilentMs / 60_000} min"
            else -> "healthy — watching $stationsMapped station(s)"
        }
    }

    private val _state = MutableStateFlow(
        Report(
            serviceRunning = false,
            triggerAttached = false,
            stopMonitorWatching = false,
            pollerRunning = false,
            stationsMapped = 0
        )
    )

    /**
     * The live answer, for anything that wants to render it.
     *
     * Published by every [check], wherever it came from — the service's 60-second loop, the watchdog
     * alarm, or the home panel's own poll. That is the point: the panel and the repair now read the same
     * number, so the screen cannot say `5/5` about a service the watchdog has already found dead. It
     * used to, because the readout was a cached `AppSetup.Status` refreshed only when somebody touched
     * the screen — which on an unattended unit is never.
     */
    val state: StateFlow<Report> = _state.asStateFlow()

    /**
     * Reads the chain and publishes it to [state]. Never throws; an unreadable component reports as
     * down, which is the safe answer.
     *
     * @param includeJobs whether to re-query WorkManager. True for the slow callers that can afford four
     *   blocking database reads; false for the panel's poll, which runs every couple of seconds and
     *   carries the last known count forward instead.
     */
    fun check(context: Context, includeJobs: Boolean = false): Report {
        val previous = _state.value
        val report = try {
            val jobs = if (includeJobs) {
                runCatching { AppSetup.backgroundServices(context) }
                    .getOrElse { previous.jobsArmed + (if (previous.serviceRunning) 1 else 0) to previous.jobsTotal }
            } else {
                null
            }
            Report(
                serviceRunning = FleetService.isRunning,
                triggerAttached = LocationTrigger.isAttached,
                stopMonitorWatching = StopRecordingMonitor.isWatching,
                pollerRunning = ProcessingLane.isPolling,
                pollerStalled = ProcessingLane.isStalled,
                pollerSilentMs = ProcessingLane.silentForMs,
                pollState = ProcessingLane.pollState,
                stationsMapped = LocationMap.count(context),
                gpsEnabled = isGpsEnabled(context),
                kioskEnabled = KioskMode.isEnabled(context),
                // backgroundServices() counts the service in its total; this splits it back out, because
                // the service's liveness is already a field of its own and must not be counted twice.
                jobsArmed = jobs?.let { (up, _) -> (up - if (FleetService.isRunning) 1 else 0).coerceAtLeast(0) }
                    ?: previous.jobsArmed,
                jobsTotal = jobs?.second ?: previous.jobsTotal
            )
        } catch (t: Throwable) {
            // Throwable, not Exception. This is read by the home panel's poll twice a second and by the
            // watchdog receiver on an alarm; an Error escaping either would be a crash in a health check.
            // Reporting everything as down is the safe answer — it triggers a repair, and a repair of
            // something that was working is a no-op.
            Log.e(TAG, "could not read Stage 0 health — reporting it as down", t)
            Report(
                serviceRunning = false,
                triggerAttached = false,
                stopMonitorWatching = false,
                pollerRunning = false,
                stationsMapped = 0,
                jobsArmed = 0,
                jobsTotal = previous.jobsTotal
            )
        }
        _state.value = report
        return report
    }

    /** Whether location services are on at the device level. Cheap, and it changes without warning. */
    @Suppress("SwallowedException")
    private fun isGpsEnabled(context: Context): Boolean = try {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        lm != null && (
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            )
    } catch (e: Exception) {
        false
    }

    /**
     * Raises a row for each part that is down and clears the ones that have come back.
     *
     * Both directions on every tick, so a repair that worked removes its own row three minutes later
     * without anything having to remember it was raised.
     *
     * The station map is deliberately not reported here — `AppSetup` already owns that row, and two
     * writers on one condition is how a message ends up disagreeing with itself.
     */
    fun report(report: Report) {
        raise(report.serviceRunning, ERR_SERVICE_DOWN, MSG_SERVICE_DOWN)
        // Only meaningful while the service is up: with the service down, the lanes are *supposed* to be
        // detached, and three rows for one fault buries the one that matters.
        raise(report.serviceRunning && report.triggerAttached, ERR_TRIGGER_DOWN, MSG_TRIGGER_DOWN)
        raise(
            report.serviceRunning && report.stopMonitorWatching,
            ERR_STOP_MONITOR_DOWN,
            MSG_STOP_MONITOR_DOWN
        )
        // ONE row for both processing faults, deliberately: an operator can do nothing different
        // about a poller that stopped and a poller that is stuck, and two rows for one symptom is how
        // the screen becomes unreadable. Which of the two it is goes to the log and the summary.
        raise(
            report.serviceRunning && report.pollerRunning && !report.pollerStalled,
            ERR_POLLER_DOWN, MSG_POLLER_DOWN
        )
    }

    private fun raise(ok: Boolean, key: String, message: String) {
        runCatching {
            if (ok) ErrorLog.removeErrorByKey(key) else ErrorLog.showError(key, message, SOURCE)
        }
    }

    /**
     * Puts back whatever is missing. **Only call this with a visible Activity** — see the class note.
     *
     * @return a description of what was done, or null when there was nothing to do.
     */
    fun repair(context: Context, report: Report): String? {
        // `needsRepair`, not `healthy`: the processing lane is deliberately outside `healthy` — a
        // backlog does not stop a unit capturing — and must still be repaired, which is exactly the
        // distinction that let a wedged poller sit unrepaired between three-minute alarms.
        if (!report.needsRepair) return null
        val done = mutableListOf<String>()

        try {
            when {
                // The whole-lane repair. Starting the service brings the trigger, the stop monitor and
                // the processing queue up with it, so it covers three faults at once.
                !report.serviceRunning -> {
                    FleetService.start(context)
                    done += "started the capture service"
                }

                // The service is up but a lane is not. Restarting the service would not help — it is
                // already started and `ensureStarted` returns early — so it is asked to re-attach.
                // Reachable in practice: the location permission being revoked at runtime detaches the
                // trigger and leaves everything else running.
                !report.triggerAttached || !report.stopMonitorWatching -> {
                    FleetService.requestHealthCheck(context)
                    done += "asked the capture lane to re-attach"
                }
            }

            // Stage 1's poller, in both of its failure shapes. Repaired through the service for the
            // same reason as the lanes: the service owns the coroutine scope the loop runs in, so
            // nothing outside it can restart it. The intent is a plain `startService` to a service that
            // is already up — no foreground start, no camera or location type — so unlike the capture
            // repair it is legal from the background, which is what lets the watchdog fix this without
            // a visible Activity.
            if (report.serviceRunning && !report.pollerRunning) {
                FleetService.requestHealthCheck(context)
                done += "restarted the video-processing poller"
            } else if (report.serviceRunning && report.pollerStalled) {
                // A different repair, because `ensurePolling` would look at a live job and do nothing.
                // The service turns this into `ProcessingLane.restartPolling`.
                FleetService.requestProcessingRestart(context)
                done += "restarted a stalled video-processing run " +
                    "(silent ${report.pollerSilentMs / 60_000} min)"
            }

            if (report.stationsMapped == 0) {
                LocationMapRefreshScheduler.requestCatchUp(context)
                done += "requested the station map"
            }

            // Re-armed on every tick, not only on a fault: a task-killer can cancel a WorkManager
            // schedule without touching anything this can see. All four schedules use KEEP or UPDATE,
            // and UPDATE preserves lastEnqueueTime, so re-arming cannot reset a period or starve a job.
            AppSetup.armBackgroundWork(context)
        } catch (t: Throwable) {
            // Same rule as [check]: this runs on the watchdog's broadcast thread, where an escape is a
            // process death of the very app the receiver exists to keep alive. Whatever was repaired
            // before the fault stands; the rest is retried on the next tick, three minutes later.
            Log.e(TAG, "a Stage 0 repair failed", t)
        }

        return done.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }
}
