package com.terminalscout.app.prod

import android.util.Log
import com.terminalscout.app.prod.ui.ErrorLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler

/**
 * The last line of defence at a platform callback boundary.
 *
 * ### Why this exists as its own thing
 * Stage 0 runs unattended, and almost none of it is called by our own code. A GPS fix arrives on a
 * fused-provider callback; a recording finalizes on a CameraX executor; a deadline fires on a `Handler`;
 * a service is created, started and destroyed by the system; a coroutine is resumed by a dispatcher.
 * **A throwable that reaches any one of those frames is a process death**, and the app then has to be
 * brought back by the watchdog — five seconds of blindness at best, and a crash loop at worst, because
 * whatever threw is usually still true when it comes back.
 *
 * The rule this file enforces: *nothing the app does to itself may kill the app.* An external kill is
 * fine and is what [Watchdog] is for. A fault of our own is not, and there is no fault in Stage 0 worth
 * a process death — a missed fix, a dropped clip and an unwritten sidecar are all survivable, and the
 * unit keeps working for every station after the one that failed.
 *
 * ### What it does with the failure
 * Both halves, always:
 *
 *  - **Logcat gets the throwable**, with a stack trace and the name of the boundary it came out of.
 *    That is the only place the detail can go, and it is where somebody diagnosing a unit looks.
 *  - **The errors screen gets a row**, one per component, so the fault is visible on the device
 *    without a cable. The message is a CONSTANT per component for the reason every message in this app
 *    is — see `ERROR_HANDLING.md`: the raise and the clear have to match exactly, and interpolating a
 *    throwable's text would produce a row nothing could ever remove and a fresh one on every repeat.
 *
 * ### `Throwable`, not `Exception`, and that is deliberate
 * An `OutOfMemoryError` on a device that is extracting UHD frames is not hypothetical, and a
 * `NoClassDefFoundError` out of a missing Play-services split is not either. Both are `Error`, both are
 * survivable *at a callback boundary* — the callback is abandoned and the next one runs — and neither is
 * worth taking the process down for. Catching `Error` is a bad thing to do in general and the better of
 * the two things available here, which is the same argument `AppSetup.run` already makes.
 *
 * ### Where it does NOT belong
 * This is for boundaries, not for logic. Wrapping a step *inside* a callback in one of these swallows a
 * fault the caller needed to know about and leaves the lane in a half-state; the boundary is the place
 * where "abandon this one thing and carry on" is a complete and correct answer. Every call site names
 * the boundary it is guarding in [what] for exactly that reason — if the name is not a callback, an
 * event or a lifecycle method, it is probably in the wrong place.
 */

/**
 * Runs [block] at a callback boundary, containing anything it throws.
 *
 * @param source the component as the errors screen names it, e.g. `CaptureLane.SOURCE`. Constant.
 * @param key    the component's stable error key. One row per component, so a component that faults
 *               repeatedly does not stack rows and a component that has never faulted has none.
 * @param what   the boundary being guarded, for the log line only — "onLocationResult", "onDestroy".
 *
 * Returns `Unit`, deliberately. A boundary guard has no answer to give: there is nothing to be done
 * about a callback that failed except run the next one, and returning a Boolean here only invited call
 * sites to become expression bodies whose type quietly changed from `Unit` to `Boolean`. A caller that
 * genuinely needs to know whether the block ran wants [crashproofOr] with a Boolean fallback, which
 * states the safe answer at the call site instead of leaving it to be inferred.
 */
fun crashproof(source: String, key: String, what: String, block: () -> Unit) {
    try {
        block()
    } catch (t: Throwable) {
        reportContained(source, key, what, t)
    }
}

/**
 * [crashproof] for a boundary that has to answer with a value.
 *
 * @param fallback what the caller gets when [block] threw. Chosen at each call site to fail in the
 *   direction that is safe, exactly as `AppSetup.step` does: "nothing is missing" is not a safe answer
 *   to a permission read that failed, and "there is no room" is not a safe answer to a disk read.
 */
fun <T> crashproofOr(source: String, key: String, what: String, fallback: T, block: () -> T): T = try {
    block()
} catch (t: Throwable) {
    reportContained(source, key, what, t)
    fallback
}

/**
 * [crashproof] for a boundary whose body suspends.
 *
 * A separate function because a `suspend` lambda is a different type, not because the rule is different.
 *
 * **`CancellationException` is rethrown, never contained**, and that is the one place this file's "catch
 * everything" rule bends. Cancellation is not a fault: it is how a scope shuts its children down, it
 * travels as a throwable through every suspension point in the block, and swallowing it would leave a
 * coroutine that has been told to stop still running — and would put a fault row on the errors screen
 * every time the service is destroyed.
 */
suspend fun crashproofSuspend(source: String, key: String, what: String, block: suspend () -> Unit) {
    try {
        block()
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        reportContained(source, key, what, t)
    }
}

/**
 * A [CoroutineExceptionHandler] that contains a coroutine's fault instead of crashing the process.
 *
 * **Every long-lived scope in Stage 0 needs one, and a `SupervisorJob` is not a substitute.** A
 * supervisor only stops a failing child cancelling its siblings; the exception still goes to the
 * scope's `CoroutineExceptionHandler`, and the default one hands it to the thread's
 * uncaught-exception handler — which is a process death, from a `launch` nobody was waiting on. That is
 * how the capture lane's `requestStart` and the service's wake-lock loop could each have taken the app
 * down.
 *
 * It is the scope's backstop, not its error handling: every block launched into these scopes is wrapped
 * at its own boundary as well, so reaching this handler means the fault was in the wrapping or at a
 * suspension point outside it. It still has to exist, because "we wrapped everything" is a claim about
 * code that will keep changing.
 */
fun containedIn(source: String, key: String, what: String): CoroutineExceptionHandler =
    CoroutineExceptionHandler { _, t -> reportContained(source, key, what, t) }

/**
 * Records a contained fault. Never throws — it is reached from a `catch`, and a reporter that can fail
 * would replace the fault it is describing with a different one.
 */
private fun reportContained(source: String, key: String, what: String, t: Throwable) {
    runCatching {
        Log.e(source, "$what failed — contained, the process survives", t)
    }
    runCatching {
        ErrorLog.showError(key, containedMessage(source), source)
    }
}

/**
 * The row's text. Derived from [source] alone, so it is constant for a given component and the clear
 * side can reproduce it exactly.
 */
private fun containedMessage(source: String): String =
    "Something inside $source failed unexpectedly. It was contained and the app is still running, but " +
        "that part may have skipped what it was doing. The detail is in the device log."

/**
 * Error keys for contained faults, one per component.
 *
 * Separate keys rather than one shared "internal fault", because these are the rows that say which half
 * of the unit is misbehaving, and a single key would let the first fault hide every later one behind
 * `showError`'s de-duplication.
 *
 * Nothing clears these automatically. That is on purpose: a contained fault means a callback was
 * abandoned, and there is no later moment at which the app can honestly say it did not happen. They go
 * when the log is cleared deliberately.
 */
object ContainedFaults {
    const val APP = "contained_app"
    const val CAPTURE_LANE = "contained_capture_lane"
    const val CAMERA = "contained_camera"
    const val LOCATION_TRIGGER = "contained_location_trigger"
    const val STOP_MONITOR = "contained_stop_monitor"
    const val SERVICE = "contained_fleet_service"
    const val PROCESSING_LANE = "contained_processing_lane"
    const val WATCHDOG = "contained_watchdog"
    const val KIOSK = "contained_kiosk"
    const val ACTIVITY = "contained_activity"
    const val HEALTH = "contained_stage0_health"

    // Pre-Stage 0, and both are on the frozen list — see `freeze_files.md`. They are here because a
    // fault in either one used to be a process death: the model download runs in an object-level scope
    // with no screen, and the details form is the only writer of the device identity.
    const val MODEL_DOWNLOAD = "contained_model_download"
    const val DEVICE_DETAILS = "contained_device_details"
}
