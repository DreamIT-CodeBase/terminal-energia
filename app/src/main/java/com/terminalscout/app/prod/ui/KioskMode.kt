package com.terminalscout.app.prod.ui

import android.content.Context
import android.content.Intent
import android.util.Log
import com.terminalscout.app.prod.ContainedFaults
import com.terminalscout.app.prod.crashproof
import com.terminalscout.app.prod.crashproofOr
import java.io.File

/**
 * Whether the app is holding itself on screen, and whether it may put itself back.
 *
 * ### Why the app has to stay on screen at all
 * A foreground service that needs the camera **cannot be created while the app is in the background**
 * — the platform rule is "you must call `startForegroundService()` while your app has a visible
 * activity", and it applies even to apps that are otherwise exempt from background-start restrictions.
 * There is no way to inherit that privilege later. So for a unit that records around the clock, the
 * only reliable arrangement is: the screen never leaves this app, therefore the service is always
 * started from a visible activity, therefore the camera is always available.
 *
 * That is the whole reason this exists. It is not a UI preference.
 *
 * ### Two mechanisms, doing different jobs
 * | | |
 * |---|---|
 * | **Relaunch** | If the activity stops, it is started again. Needs `SYSTEM_ALERT_WINDOW`, which is the documented exemption from the background-activity-start restriction. |
 * | **Screen pinning** | `startLockTask()` keeps the user from navigating away in the first place. Without a device policy controller this is *pinned* mode: the system prompts once, and the user can still exit deliberately. |
 *
 * Relaunch is the load-bearing one — pinning only reduces how often it has to fire.
 *
 * ### It must be switchable off, and the switch has to survive a kill
 * An app that reopens itself forever and cannot be dismissed is indistinguishable from malware, and
 * would be untestable besides. The menu turns it off; [enabled] is stored on disk so that decision
 * survives the process being killed — which matters precisely because the thing being disabled is what
 * restarts the process.
 *
 * A one-word file rather than SharedPreferences: the app keeps no
 * preferences, and a file is what `adb shell cat` can read when explaining a unit's behaviour after
 * the fact.
 */
object KioskMode {

    private const val TAG = "KioskMode"
    private const val DIR = "kiosk"
    private const val FILE = "auto_foreground"

    private const val ON = "on"
    private const val OFF = "off"

    /** Set while a MainActivity instance is between onResume and onPause. */
    @Volatile
    private var activityVisible: Boolean = false

    private fun file(context: Context): File {
        val dir = File(context.filesDir, DIR)
        // Guarded: `mkdirs` throws SecurityException on a volume this app cannot write, and every
        // watchdog path reads this file. A directory that could not be made leaves the file simply
        // absent, which [isEnabled] already treats as "on" — the safe answer.
        runCatching { if (!dir.isDirectory) dir.mkdirs() }
        return File(dir, FILE)
    }

    /**
     * Whether the app should hold itself on screen.
     *
     * Defaults to **on**: a freshly provisioned unit is meant to be capturing, and a default of off
     * would mean every new device silently sits there doing nothing until somebody found the menu.
     */
    fun isEnabled(context: Context): Boolean =
        // Fails OPEN, on purpose, and the fallback is the whole reason this is guarded rather than left
        // to throw: an unreadable switch must mean "hold the screen and keep restarting", because the
        // alternative is a unit that quietly stops being a kiosk because of a filesystem hiccup. It is
        // also read from the crash handler's respawn path, where a throw would replace the crash being
        // recorded with a different one.
        crashproofOr(SOURCE, ContainedFaults.KIOSK, "isEnabled", true) {
            val f = file(context)
            if (!f.exists()) return@crashproofOr true
            f.readText().trim() != OFF
        }

    fun setEnabled(context: Context, enabled: Boolean) {
        crashproof(SOURCE, ContainedFaults.KIOSK, "setEnabled") {
            // Set BEFORE the write and outside its guard: the in-memory half is what holds the menu
            // switch for this process, and it must take effect even when the disk write fails.
            releasedThisSession = !enabled
            runCatching { file(context).writeText(if (enabled) ON else OFF) }
                .onFailure { Log.e(TAG, "could not persist the kiosk setting", it) }
            Log.i(TAG, if (enabled) "auto-foreground ON" else "auto-foreground OFF — the app can be left")
        }
    }

    /**
     * Whether somebody turned kiosk off from the menu since this process started.
     *
     * `AppSetup` forces kiosk back on at every launch, so the stored setting no longer survives a
     * restart — that is the point of forcing it. This flag is what stops the force from also undoing
     * the button *while the operator is standing there*: setup runs again the moment the home panel
     * repaints, so without it the switch would flick back to ON under the technician's finger and the
     * app would be unserviceable.
     *
     * In memory only, and deliberately. Its whole meaning is "for as long as this process lives".
     * Anything that ends the process — a crash, a kill, EXIT DEVICE and a fresh open — is exactly the
     * moment the unit should be back to holding its own screen.
     */
    @Volatile
    private var releasedThisSession: Boolean = false

    /** See [releasedThisSession]. Read by `AppSetup.ensureKioskMode`. */
    val wasReleasedThisSession: Boolean get() = releasedThisSession

    // ---- Visibility tracking -------------------------------------------------------------------- //

    fun onActivityVisible() {
        activityVisible = true
    }

    fun onActivityHidden() {
        activityVisible = false
    }

    /** True while a MainActivity is resumed — i.e. while the camera could be taken. */
    val isActivityVisible: Boolean get() = activityVisible

    // ---- Relaunch ------------------------------------------------------------------------------- //

    /**
     * Brings the app back to the front.
     *
     * `NEW_TASK` plus `SINGLE_TOP` so this reuses the existing activity when there is one rather than
     * stacking a second copy; `REORDER_TO_FRONT` covers the case where the task exists but is behind
     * something else.
     *
     * Silently does nothing when the feature is switched off. Failure is logged rather than thrown:
     * this is called from a service, and a device that refuses the launch (no overlay permission) must
     * degrade to "not on screen", not to a crash.
     */
    fun bringToForeground(context: Context, reason: String) {
        // Whole-body guard, because of who calls it: a service standing down, the watchdog receiver on
        // an alarm, and the camera-type refusal path. Every one of those is already handling a fault,
        // and a throw out of the recovery would turn a recoverable state into a process death.
        crashproof(SOURCE, ContainedFaults.KIOSK, "bringToForeground") {
            if (!isEnabled(context)) {
                Log.d(TAG, "not bringing the app forward ($reason) — auto-foreground is off")
                return@crashproof
            }
            if (activityVisible) return@crashproof

            val intent = Intent().apply {
                setClassName(context, MAIN_ACTIVITY)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                )
            }
            val ok = runCatching { context.startActivity(intent) }.isSuccess
            Log.i(TAG, if (ok) "bringing the app forward — $reason" else "could not bring the app forward")
        }
    }

    /** Named rather than imported, so this object stays free of a dependency on the UI package. */
    private const val MAIN_ACTIVITY = "com.terminalscout.app.prod.MainActivity"

    /** Shown under this object's rows on the errors screen. */
    const val SOURCE = "Kiosk"
}
