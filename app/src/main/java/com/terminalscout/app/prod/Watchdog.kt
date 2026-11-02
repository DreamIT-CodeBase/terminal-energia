package com.terminalscout.app.prod

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.terminalscout.app.prod.ui.ErrorLog
import com.terminalscout.app.prod.ui.KioskMode
import com.terminalscout.app.prod.config.DeviceConfig
import java.io.File
import kotlin.system.exitProcess

/**
 * Brings the app back after it dies, and keeps both of its lanes working while it lives. The
 * "unkillable soldier".
 *
 * ### It covers the processing lane too
 * The alarm's original question was "is the process alive", and the health check added "can this unit
 * still record". Neither asked whether anything was READING the clips already on the device — so the
 * video processing lane had no watchdog at all: it was repaired only by a 60-second loop inside
 * `FleetService`, which is no help when what is wedged is that service's own coroutine scope, and
 * `WatchdogReceiver` skipped it entirely because `Stage0Health.Report.healthy` does not mention it.
 *
 * Both of its failure shapes are now watched on every tick, on both branches of the receiver:
 * a loop that ENDED is started again, and a loop that is alive but has emitted nothing for
 * `ProcessingLane.STALL_AFTER_MS` has its run cancelled and the loop restarted. Neither repair needs a
 * visible Activity — see [WatchdogReceiver] — which is what makes it work on a unit nobody looks at.
 *
 * ### The two deaths this covers, and the one it cannot
 * | death | how it is noticed | how it comes back |
 * |---|---|---|
 * | **Uncaught exception** | [install]'s handler runs on the dying thread | it schedules a +5 s alarm before the process goes |
 * | **System kill** (memory, thermal) | nothing of ours runs — the process is simply gone | the dead-man alarm ([armDeadMan]), re-armed every minute by the service, fires because nothing renewed it. `FleetService`'s own `START_STICKY` restart usually beats it by minutes and asks for the fast path itself |
 * | **Force-stop from Settings** | — | **not covered, and cannot be.** A force-stopped app's alarms are cancelled and its receivers stay disabled until a human launches it. Only a second app or a device-owner DPC can undo that |
 *
 * ### Why the alarm starts the ACTIVITY and never the service
 * From the permissions pass, verified against the platform docs: a foreground service that needs a
 * while-in-use permission — `camera` or `location`, and `FleetService` declares both — **cannot be
 * created while the app is in the background**, exemptions included. On Android 14+ the attempt throws
 * immediately; below that the service starts and silently has no camera or location. A watchdog that
 * restarted the service directly would therefore produce a live service that can neither see nor
 * record, which is worse than being down because the home readout would call it healthy.
 *
 * The only path that works is the one this implements:
 *
 * ```
 * process dies
 *      |
 * alarm fires, WatchdogReceiver runs        <- in the background
 *      |
 * startActivity(MainActivity)               <- allowed: SYSTEM_ALERT_WINDOW is the documented
 *      |                                       background-activity-start exemption
 * MainActivity visible -> AppSetup.run() -> FleetService started from the foreground
 *      |
 * camera and location work
 * ```
 *
 * That is what makes [KioskMode]'s overlay permission load-bearing rather than cosmetic.
 *
 * ### The off switch
 * Every path checks [KioskMode.isEnabled]. An app that reopens itself forever and cannot be dismissed
 * is indistinguishable from malware and impossible to test, so the menu's kiosk switch turns the
 * watchdog off with it — the same one-word file, and the same promise the menu already makes ("the app
 * will not reopen itself"). "Exit device" calls [disarm] for the same reason.
 *
 * ### The loop guard
 * A crash on startup plus an unconditional restart is an infinite loop that fills the log and cooks the
 * device. After [MAX_ATTEMPTS] restarts inside a rolling [STABLE_WINDOW_MS] the watchdog stands down
 * and says so on the errors screen. Ten minutes of not crashing resets the count, so it caps a *loop*,
 * not the unit's lifetime.
 */
object Watchdog {

    private const val TAG = "Watchdog"

    /** Shown under this object's rows on the errors screen. */
    const val SOURCE = "Watchdog"

    /** How long after a death the app comes back. */
    val RESPAWN_DELAY_MS: Long get() = DeviceConfig.long("capture.watchdog.respawn_delay_ms", 5_000L)

    /** Restarts allowed inside one [STABLE_WINDOW_MS] chain before the watchdog stands down. */
    val MAX_ATTEMPTS: Int get() = DeviceConfig.int("capture.watchdog.max_attempts", 10)

    /**
     * How long the app must stay up for the attempt count to reset.
     *
     * The counter is a chain, not a total: each restart has to follow the last one inside this window
     * to extend it. A unit that crashes once a fortnight never approaches the cap; one that is
     * crash-looping reaches it in under a minute.
     */
    val STABLE_WINDOW_MS: Long get() = DeviceConfig.long("capture.watchdog.stable_window_ms", 10 * 60 * 1000L)

    /**
     * The dead-man's switch: if nothing renews the alarm within this, the app is assumed dead.
     *
     * Deliberately much longer than [RESPAWN_DELAY_MS]. It is the backstop for the deaths that run
     * none of our code — an out-of-memory or thermal kill — where there is nobody left to ask for the
     * fast path.
     */
    val DEAD_MAN_MS: Long get() = DeviceConfig.long("capture.watchdog.dead_man_ms", 3 * 60 * 1000L)

    /** How often [service.FleetService] pushes the dead-man deadline out. Well inside [DEAD_MAN_MS]. */
    val DEAD_MAN_RENEW_MS: Long get() = DeviceConfig.long("capture.watchdog.dead_man_renew_ms", 60 * 1000L)

    // One alarm slot, deliberately. Scheduling the +5 s respawn REPLACES a dead-man deadline sitting
    // further out, which is exactly the precedence wanted; arming the dead-man on a healthy start
    // replaces it back. Two slots would race and could put the activity up twice.
    private const val REQUEST_CODE = 5101
    private const val ACTION_RESPAWN = "com.terminalscout.app.prod.WATCHDOG_RESPAWN"

    private const val DIR = "watchdog"
    private const val FILE = "state"

    const val ERR_RESPAWN = "watchdog_respawn"
    const val ERR_CRASH = "watchdog_crash"
    const val ERR_GAVE_UP = "watchdog_gave_up"
    const val ERR_INEXACT = "watchdog_inexact_alarms"

    private const val MSG_GAVE_UP =
        "The app restarted itself 10 times in a row and has stopped trying. Something is failing at " +
            "startup — open the app by hand and read the errors above this one."
    private const val MSG_INEXACT =
        "Exact alarms are not permitted for this app, so an automatic restart may be delayed by " +
            "several minutes. Allow the app to run unrestricted."

    // ---- The crash handler ---------------------------------------------------------------------- //

    /**
     * Installs the uncaught-exception handler. Called once, from [App.onCreate].
     *
     * Chains to whatever handler was already there rather than replacing it, so the crash still reaches
     * Logcat and would still reach a crash reporter if one is ever added. This only adds a record and
     * an alarm on the way past.
     */
    fun install(context: Context) {
        val app = context.applicationContext
        // Guarded read: some hosts install a handler that throws from its own accessor, and losing the
        // chain is survivable where losing this whole method is not.
        val previous = runCatching { Thread.getDefaultUncaughtExceptionHandler() }.getOrNull()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Every step here runs on a thread that is already dying, so every step is guarded: a
            // failure while recording the crash must not replace the crash with a different one.
            val description = runCatching { describe(error) }.getOrDefault("unknown fault")
            runCatching { recordCrash(app, description) }
            runCatching { requestRespawn(app, description) }
            runCatching { Log.e(TAG, "uncaught on ${thread.name} — $description", error) }
            if (previous != null) previous.uncaughtException(thread, error) else exitProcess(2)
        }
        Log.i(TAG, "installed — a crash respawns the app after ${RESPAWN_DELAY_MS / 1000}s")
    }

    /**
     * One line naming the throwable and the first frame inside this app.
     *
     * The app's own frame rather than the top of the stack: the top is usually a platform or library
     * class that looks identical for a dozen unrelated faults, whereas the first `com.terminalscout`
     * frame is the file to open.
     */
    private fun describe(error: Throwable): String {
        val cause = error.javaClass.simpleName
        val message = error.message?.take(120)?.replace('\n', ' ')
        val frame = error.stackTrace.firstOrNull { it.className.startsWith("com.terminalscout") }
        val where = frame?.let {
            " at " + it.className.substringAfterLast('.') + "." + it.methodName + ":" + it.lineNumber
        }.orEmpty()
        return if (message.isNullOrBlank()) cause + where else "$cause: $message$where"
    }

    /**
     * Writes the crash to `error_log.db`, so it is on the errors screen after the restart.
     *
     * Removed and re-raised rather than raised alone: the table holds one row per key, so a second,
     * different crash would otherwise be swallowed by the first and the screen would show a fault that
     * has already been fixed instead of the one happening now.
     */
    private fun recordCrash(context: Context, description: String) {
        ErrorLog.init(context)
        ErrorLog.removeErrorByKey(ERR_CRASH)
        ErrorLog.showError(ERR_CRASH, "The app crashed — $description", SOURCE)
    }

    // ---- Scheduling ------------------------------------------------------------------------------ //

    /**
     * Asks for a restart in [RESPAWN_DELAY_MS].
     *
     * @param counted whether this consumes one of [MAX_ATTEMPTS]. True for a crash, which is the thing
     *   that can loop. False for a restart that is *recovering* from a death already counted — a
     *   `START_STICKY` service coming back with no screen — where charging it again would spend the
     *   budget twice for one fault.
     * @return true when an alarm was set.
     */
    @Synchronized
    fun requestRespawn(context: Context, reason: String, counted: Boolean = true): Boolean =
        // Contained, and the fallback says "no alarm was set" — which is the truth when this fails and
        // is what the caller has to act on. Reached from the uncaught-exception handler on a thread that
        // is already dying, where a second throwable would replace the crash being recorded with a
        // completely unrelated one and lose the reason the app is coming back.
        crashproofOr(SOURCE, ContainedFaults.WATCHDOG, "requestRespawn", false) {
            respawnNow(context, reason, counted)
        }

    /** [requestRespawn]'s body, split out so the guard above wraps it without rewriting its returns. */
    private fun respawnNow(context: Context, reason: String, counted: Boolean): Boolean {
        val app = context.applicationContext
        if (!KioskMode.isEnabled(app)) {
            Log.i(TAG, "not respawning ($reason) — the kiosk switch is off")
            return false
        }

        val now = System.currentTimeMillis()
        val state = readState(app)
        val count = when {
            !counted -> state.count
            now - state.lastAtMs > STABLE_WINDOW_MS -> 1
            else -> state.count + 1
        }

        if (count > MAX_ATTEMPTS) {
            writeState(app, State(count = count, lastAtMs = now, pending = null))
            cancel(app)
            ErrorLog.init(app)
            ErrorLog.showError(ERR_GAVE_UP, MSG_GAVE_UP, SOURCE)
            Log.e(TAG, "standing down — $count restarts inside ${STABLE_WINDOW_MS / 60_000} minutes")
            return false
        }

        writeState(
            app,
            State(
                count = count,
                lastAtMs = if (counted) now else state.lastAtMs,
                // FIRST reason wins, not the most recent one.
                //
                // A single death regularly asks twice: the crash handler names the exception, then the
                // START_STICKY service restart lands a second later and names itself. Both are true, but
                // only the first is the cause — the second is something the first made happen. Letting
                // the later one overwrite produced a restart row on the errors screen reading "the
                // capture service restarted with no screen" for what was actually a
                // RemoteServiceException, which sends whoever reads it looking in the wrong place.
                //
                // `pending` is cleared by noteAppUp the moment the app is back, so this cannot hold on
                // to a stale reason across two separate deaths.
                pending = state.pending ?: reason
            )
        )
        schedule(app, RESPAWN_DELAY_MS, exact = true)
        Log.w(TAG, "respawn in ${RESPAWN_DELAY_MS / 1000}s — $reason (attempt $count of $MAX_ATTEMPTS)")
        return true
    }

    /**
     * Pushes the dead-man deadline out to now + [DEAD_MAN_MS].
     *
     * Called on every setup pass and once a minute by the service. Inexact on purpose: this is a
     * three-minute backstop, exactness buys nothing at that scale, and the inexact call needs no
     * permission at all.
     */
    fun armDeadMan(context: Context) {
        crashproof(SOURCE, ContainedFaults.WATCHDOG, "armDeadMan") {
            val app = context.applicationContext
            if (!KioskMode.isEnabled(app)) {
                cancel(app)
                return@crashproof
            }
            schedule(app, DEAD_MAN_MS, exact = false)
        }
    }

    /**
     * Cancels the alarm and forgets the attempt chain. Used by "exit device".
     *
     * The count is reset with it, because whatever starts the app next will be a human opening it,
     * which is the definition of a fresh chain.
     */
    @Synchronized
    fun disarm(context: Context) {
        crashproof(SOURCE, ContainedFaults.WATCHDOG, "disarm") {
            val app = context.applicationContext
            cancel(app)
            writeState(app, State(count = 0, lastAtMs = 0L, pending = null))
            Log.i(TAG, "disarmed — the app will not restart itself")
        }
    }

    /**
     * Records that the app is up, and re-arms the dead-man.
     *
     * Called from [AppSetup.run], which is the one thing that happens on every launch however that
     * launch came about. A pending reason left in the state file means the last exit was not a clean
     * one, so the restart is written to the error log here — from a live process after the fact,
     * rather than from the dying one. That is what makes the record survive a death that ran none of
     * our code.
     */
    @Synchronized
    fun noteAppUp(context: Context) = crashproof(
        SOURCE, ContainedFaults.WATCHDOG, "noteAppUp"
    ) {
        val app = context.applicationContext
        val state = readState(app)
        val stable = System.currentTimeMillis() - state.lastAtMs > STABLE_WINDOW_MS

        state.pending?.let { reason ->
            ErrorLog.init(app)
            ErrorLog.removeErrorByKey(ERR_RESPAWN)
            val chain = if (state.count > 0) {
                " ${state.count} restart(s) in the last ${STABLE_WINDOW_MS / 60_000} minutes."
            } else {
                ""
            }
            ErrorLog.showError(ERR_RESPAWN, "The app restarted itself after: $reason.$chain", SOURCE)
            Log.w(TAG, "came back after: $reason")
        }

        writeState(
            app,
            State(
                count = if (stable) 0 else state.count,
                lastAtMs = state.lastAtMs,
                pending = null
            )
        )
        if (stable) ErrorLog.removeErrorByKey(ERR_GAVE_UP)
        armDeadMan(app)
    }

    // ---- AlarmManager ---------------------------------------------------------------------------- //

    private fun schedule(context: Context, delayMs: Long, exact: Boolean) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: run {
            Log.e(TAG, "no AlarmManager — nothing will bring the app back")
            return
        }
        // ELAPSED_REALTIME rather than RTC: these clocks are set from the network, and a jump backwards
        // would park an RTC alarm in the future for as long as the offset lasted.
        val at = SystemClock.elapsedRealtime() + delayMs
        val pending = pendingIntent(context)
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()

        try {
            if (exact && canExact) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending)
            }
        } catch (e: SecurityException) {
            // Exact alarms revoked between the check and the call. Inexact still brings the app back,
            // it just takes longer — strictly better than scheduling nothing.
            Log.w(TAG, "exact alarm refused — falling back to inexact", e)
            runCatching { am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending) }
        }

        // Reported only on the fast path, because that is the one the delay is visible on. The
        // dead-man is inexact by choice and would raise this every minute for no reason.
        //
        // Guarded on its own, AFTER the alarm is set: the alarm is the job and the row about it is a
        // courtesy, so an error log that will not open must not cost the restart it was describing.
        runCatching {
            if (exact) {
                ErrorLog.init(context)
                if (canExact) ErrorLog.removeErrorByKey(ERR_INEXACT)
                else ErrorLog.showError(ERR_INEXACT, MSG_INEXACT, SOURCE)
            }
        }.onFailure { Log.w(TAG, "could not report the alarm's exactness", it) }
    }

    private fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        runCatching { am.cancel(pendingIntent(context)) }
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, WatchdogReceiver::class.java).setAction(ACTION_RESPAWN)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    // ---- State ------------------------------------------------------------------------------------ //

    /**
     * Everything that reads or writes the state file is `@Synchronized`, on this object's monitor.
     *
     * Two threads reach it and one of them is dying: the crash handler runs [requestRespawn] on the
     * failing thread while the service's 60-second loop can be inside [noteAppUp] on another. Each of
     * those is a read, a decision and a write, so without one monitor across the whole sequence the
     * loser's write is computed from a state the winner has already replaced — a lost attempt count,
     * which is the one number that stops a startup crash looping. The monitor is reentrant, so
     * [readState] and [writeState] carrying it too costs nothing and means no future caller can reach
     * the file unguarded.
     *
     * @param count    restarts in the current chain.
     * @param lastAtMs when the last counted restart was asked for.
     * @param pending  why the app is expected to come back, cleared by [noteAppUp] once it has. Its
     *                 presence after a launch is the only evidence of a death that ran none of our code.
     */
    private data class State(val count: Int, val lastAtMs: Long, val pending: String?)

    private fun file(context: Context): File =
        File(File(context.filesDir, DIR).apply { mkdirs() }, FILE)

    /**
     * A plain key=value file, matching `KioskMode`.
     *
     * The app keeps no SharedPreferences, and this one in particular has to be readable with
     * `adb shell run-as ... cat` when explaining why a unit in a truck restarted itself nine times.
     */
    @Synchronized
    private fun readState(context: Context): State = try {
        val text = file(context).takeIf { it.exists() }?.readText().orEmpty()
        val values = text.lineSequence()
            .mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 } }
            .associate { it[0].trim() to it[1].trim() }
        State(
            count = values["count"]?.toIntOrNull() ?: 0,
            lastAtMs = values["lastAtMs"]?.toLongOrNull() ?: 0L,
            pending = values["pending"]?.takeIf { it.isNotBlank() }
        )
    } catch (e: Exception) {
        Log.w(TAG, "could not read the watchdog state — starting a fresh chain", e)
        State(0, 0L, null)
    }

    @Synchronized
    private fun writeState(context: Context, state: State) {
        runCatching {
            file(context).writeText(
                "count=${state.count}\n" +
                    "lastAtMs=${state.lastAtMs}\n" +
                    "pending=${state.pending.orEmpty()}\n"
            )
        }.onFailure { Log.e(TAG, "could not persist the watchdog state", it) }
    }
}
