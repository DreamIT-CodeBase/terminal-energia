package com.terminalscout.app.prod

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.terminalscout.app.prod.service.FleetService
import com.terminalscout.app.prod.ui.ErrorLog
import com.terminalscout.app.prod.ui.KioskMode

/**
 * What the watchdog alarm wakes. The app's only unattended health check.
 *
 * It answers three questions every three minutes, in this order:
 *
 *  1. **Is the app on screen?** If not, put it there — that is the recovery path for a process that
 *     died, and the whole reason the alarm exists.
 *  2. **Is the video processing lane still reading clips?** If not, restart it. This is asked on BOTH
 *     branches, because unlike the capture repair it does not need an Activity — see below.
 *  3. **Is Stage 0 actually capable of recording?** If not, repair it.
 *
 * The second question was added after the first proved insufficient on the bench. `FleetService` was
 * stopped with the app left running, and the unit sat for four minutes with the capture service gone,
 * the location trigger detached, and the home panel reading `CAPTURE SERVICE : WATCHING 349`. The alarm
 * fired on time, logged *"alarm fired but the app is up — re-arming"*, and went back to sleep. It was
 * asking whether the **process** was alive, which it was; nothing anywhere was asking whether the
 * process could still do its job.
 *
 * ### Why a receiver rather than an Activity PendingIntent
 * Both questions have to be answered at the moment the alarm *fires*, not when it was scheduled. By then
 * the app is often already back — a `START_STICKY` restart usually beats the alarm — and relaunching
 * would shove the task in front of whatever the operator is doing. The kiosk switch may have been turned
 * off since. And the alarm slot has to be re-armed either way, which a PendingIntent aimed at an Activity
 * has no opportunity to do.
 *
 * ### It never starts the service from the background
 * A background-started foreground service may hold neither `camera` nor `location` — see the note on
 * [Watchdog]. So the CAPTURE repair splits on visibility: with the app on screen a service start is
 * legal and [Stage0Health.repair] performs it directly; with the app absent, raising the Activity is the
 * whole job, and `MainActivity.onResume` runs `AppSetup`, which repairs everything from a position where
 * it is allowed to.
 *
 * ### The processing lane is repaired on both branches, and that is not an inconsistency
 * Restarting the poll loop needs no foreground-service type and no Activity: the service is already
 * running, the intent is a plain `startService` to a live instance, and the loop is a coroutine in that
 * instance's own scope. Nothing about it can be refused in the background.
 *
 * It has to be that way, because of what the alternative was. `healthy` deliberately excludes the
 * poller — a backlog does not stop a unit capturing — and this receiver returned the moment `healthy`
 * was true, so a dead or wedged processing lane was never repaired from here at all. The only thing
 * that ever repaired it was a 60-second loop inside `FleetService` itself, which cannot help when the
 * thing that is stuck is that service's own scope. A unit could therefore record all day and read
 * nothing, indefinitely, with a green panel: the clips pile up, the readout says PROCESSING, and every
 * alarm agrees everything is fine. The lane is now watched with the same persistence as the camera.
 *
 * The process is usually dead when this runs after a crash, so `App.onCreate` has just executed and the
 * crash handler is already reinstalled by the time this method is entered.
 */
class WatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        // Nothing here may throw. This runs in the app process, so an escape is a crash — of the very
        // process this class exists to keep alive — and the watchdog would then respawn into it.
        try {
            ErrorLog.init(context)

            if (!KioskMode.isActivityVisible) {
                Log.w(TAG, "the app is not on screen — bringing it back")
                // Silently does nothing when the kiosk switch is off, which is the documented way to stop
                // the app reopening itself. armDeadMan then cancels the alarm, so this does not repeat
                // every three minutes for a unit that was deliberately released.
                KioskMode.bringToForeground(context, "watchdog alarm")
                // No CAPTURE repair on this branch: it needs a visible Activity, and there isn't one
                // yet. AppSetup runs the moment the Activity resumes and does the same work better.
                //
                // The processing lane is different and is repaired here and now. It needs no Activity
                // and no foreground start, and waiting for the Activity would mean not repairing it at
                // all on a unit whose screen nobody ever touches — which is every deployed unit.
                repairProcessing(context, Stage0Health.check(context))
                return
            }

            // The app is up. That used to be the end of it.
            // includeJobs: this tick is minutes apart, so it can afford the WorkManager queries — and
            // it is the only thing refreshing that count while nobody is looking at the screen.
            val report = Stage0Health.check(context, includeJobs = true)
            Stage0Health.report(report)

            // `needsRepair`, not `healthy`. `healthy` answers "could this unit record a forecourt it
            // drove past", which is silent about whether anything is READING what it already recorded —
            // so this returned early on a unit with a dead processing lane and reported it as fine.
            if (!report.needsRepair) {
                Log.d(TAG, "stage 0 healthy — ${report.summary()}")
                return
            }

            Log.w(TAG, "DEGRADED — ${report.summary()}")
            val repaired = Stage0Health.repair(context, report)
            if (repaired != null) Log.i(TAG, "repair: $repaired")
        } catch (t: Throwable) {
            Log.e(TAG, "the health check itself failed", t)
        } finally {
            // Always, on every path including a failure: this is what makes the check recur. The service
            // renews the same deadline every 60 s while it is alive, but a dead service renews nothing —
            // so on exactly the occasions this matters, the receiver is the only thing re-arming it.
            runCatching { Watchdog.armDeadMan(context) }
        }
    }

    /**
     * The half of the repair that needs no Activity: the video processing lane.
     *
     * Kept out of [Stage0Health.repair] on this branch rather than calling that, because the rest of
     * what it does — starting the service, asking the capture lane to re-attach — is precisely what
     * must NOT happen from the background. This is the subset that can.
     *
     * Both faults are covered. A poller that ENDED is started again; a poller that is alive and has
     * gone silent past [com.terminalscout.app.prod.service.ProcessingLane.STALL_AFTER_MS] has its run
     * cancelled and the loop restarted, which is the only thing that frees a lane wedged inside one
     * clip. Nothing happens when the service itself is down: there is no lane to repair, and bringing
     * the app forward (above) is what fixes that.
     */
    private fun repairProcessing(context: Context, report: Stage0Health.Report) {
        if (!report.serviceRunning) return
        when {
            !report.pollerRunning -> {
                Log.w(TAG, "video processing is DOWN with no screen — restarting the poll loop")
                FleetService.requestHealthCheck(context)
            }
            report.pollerStalled -> {
                Log.e(
                    TAG,
                    "video processing STALLED — silent ${report.pollerSilentMs / 60_000} min, " +
                        "cancelling the run and restarting the loop"
                )
                FleetService.requestProcessingRestart(
                    context,
                    "watchdog: silent ${report.pollerSilentMs / 60_000} min"
                )
            }
            else -> Unit
        }
    }

    private companion object {
        const val TAG = "WatchdogReceiver"
    }
}
