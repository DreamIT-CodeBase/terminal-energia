package com.terminalscout.app.prod

import android.app.Application
import android.util.Log
import com.terminalscout.app.prod.ui.ErrorLog

/**
 * The first code in the process, whatever woke it.
 *
 * Two things have to be true before anything else runs, and neither can wait for an Activity — the
 * process is regularly started by the watchdog alarm or by a `START_STICKY` service restart, with no
 * screen involved at all:
 *
 *  1. **The error log is open.** It is a SQLite table, and a fault raised before it is opened is
 *     reported into nothing. [AppSetup.run] also calls `init`, which is idempotent — this just moves
 *     the moment earlier, to the only point that is genuinely unconditional.
 *  2. **The crash handler is installed.** A crash before [Watchdog.install] would take the process
 *     down with nothing recorded and nothing scheduled to bring it back, which is precisely the
 *     failure the watchdog exists to cover.
 *
 * Nothing else belongs here. Setup, permissions and the service are `AppSetup`'s, and they need a
 * visible Activity to be correct.
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        // Guarded SEPARATELY, not as one block, and neither may take the process down.
        //
        // This is the first frame of the process and there is nothing above it to catch anything: a
        // throw here is a startup crash, the watchdog respawns into the same call five seconds later,
        // and the unit loops until the ten-attempt guard stands it down — with a completely dark
        // errors screen, because the thing that would have recorded the fault is the thing that failed.
        //
        // Separately, because they are independent and the second is the more important: a corrupt
        // `error_log.db` must not stop the crash handler being installed, and an app with no error log
        // still records, still uploads, and still comes back from a crash.
        //
        // `crashproof` cannot be used for the first of these — it reports through ErrorLog, which is
        // precisely what may be broken — so this one place does it by hand.
        runCatching { ErrorLog.init(this) }
            .onFailure { Log.e(TAG, "the error log would not open — faults go to Logcat only", it) }
        runCatching { Watchdog.install(this) }
            .onFailure { Log.e(TAG, "the crash handler would not install — a crash will not respawn", it) }
        Log.i(TAG, "process up — error log open, watchdog armed")
    }

    private companion object {
        const val TAG = "App"
    }
}
