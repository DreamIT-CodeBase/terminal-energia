package com.terminalscout.app.prod.capture

import android.util.Log

/**
 * STAGE 0 — the capture trigger contract.
 *
 * The app runs unattended on a truck: nobody taps anything, and the decision to record a forecourt is
 * taken by the vehicle's own signals. This is the seam those decisions enter through — the one place —
 * which is what let the autonomous trigger land without touching the camera, the queue or the pipeline.
 *
 * ### The shape
 * A [CaptureTrigger] is a source of decisions. It is handed a [CaptureTriggerSink] and calls
 * [CaptureTriggerSink.requestStart] / [CaptureTriggerSink.requestStop] when it wants a clip. It knows
 * nothing about CameraX, the queue, or whether a clip is already recording — the capture lane owns all
 * of that and is free to refuse. A trigger is a *request*, never a command.
 *
 * ### Start AND stop
 * Both edges belong to the trigger (the auto-capture system decides "board in view" and "board out of
 * view"), which is also exactly how the debug buttons behave today. The consequence is that clip length
 * is not bounded by the trigger contract, so the capture lane enforces its own hard ceiling — see
 * `CaptureLane.MAX_CLIP_MS`. A trigger that never calls stop costs one capped clip, not the device's
 * whole flash.
 *
 * ### Two implementations, registered side by side
 * [LocationTrigger] is the autonomous one: it compares every GPS fix against the station map and asks
 * for a clip inside a forecourt's radius. [ManualTrigger] is the debug screen's Start/Stop buttons and
 * the `adb` intents, and stays registered beside it — which is what a tester needs in order to compare
 * an automatic capture against a deliberate one.
 *
 * ### Still unanswered: whether to fire at all
 * Night, rain, a full disk and a long offline backlog are all reasons to skip a station rather than
 * record a clip nothing can read. [LocationTrigger] weighs none of them; the storage floor in
 * `CaptureLane` is the only one of the four that refuses a capture today, and it refuses it in the lane
 * rather than in the trigger. This is the one decision the placeholder class that used to sit at the
 * bottom of this file was holding open, and deleting it does not close it.
 */
interface CaptureTrigger {

    /** Short stable name, for logs and for the `trigger` field on the clip. */
    val name: String

    /**
     * Starts listening and routes decisions to [sink]. Called once, on the capture lane's thread,
     * while the service is starting. Implementations register their own listeners here.
     */
    fun attach(sink: CaptureTriggerSink)

    /** Stops listening and releases whatever [attach] registered. Must be safe to call twice. */
    fun detach()
}

/**
 * What a trigger is allowed to ask for. Deliberately narrow: two verbs and a reason string.
 *
 * The reason is free text and is logged with the clip. It is how a capture that turned out to be
 * useless can be traced back to the condition that fired it, which is the only way an auto-capture
 * policy can be tuned from field data rather than from guesswork.
 */
interface CaptureTriggerSink {

    /**
     * Asks for a recording to begin.
     *
     * May be refused — the camera can already be recording, permissions can be missing, the device can
     * be out of storage. A refusal is logged, never thrown: a trigger firing into a busy lane is normal
     * operation, not an error.
     *
     * @param stationId when the decision was made about a specific station in the location map, that
     *   station's id. The lane carries it through and stamps `last_capture_time` on that row **once a
     *   clip has actually been written** — which is why the id travels with the request rather than the
     *   trigger recording it itself: only the lane knows whether a capture happened. Null for a trigger
     *   with no station behind it, which is every manual capture.
     */
    fun requestStart(source: TriggerSource, reason: String, stationId: String? = null)

    /**
     * Asks for the in-flight recording to end and its clip to be queued.
     *
     * A no-op when nothing is recording. The lane also stops itself at `MAX_CLIP_MS` regardless, so a
     * trigger that dies mid-capture does not strand the camera.
     *
     * @param stationId when the stop is about a specific station — the caller has left that board's
     *   radius — that station's id. The lane then stops **only** if the recording in flight belongs to
     *   it. This is not a nicety: several boards can be frozen at once, and an unqualified stop would
     *   regularly truncate one station's clip because the vehicle left the range of a different one.
     *   Null means "stop whatever is recording", which is what a manual stop means.
     */
    fun requestStop(reason: String, stationId: String? = null)
}

/**
 * Why a clip was captured. Travels with the queue entry, survives a process kill (it is written into
 * the queue's JSON), and is the field that will tell a fleet operator whether a reading came from the
 * automatic system or from somebody holding the phone.
 *
 * [wireName] is the persisted/serialised form — the enum's own name is a Kotlin detail and renaming a
 * constant must not silently reinterpret clips already sitting on a device's queue.
 */
enum class TriggerSource(val wireName: String) {

    /** The debug screen's Start/Stop buttons. The only source that exists today. */
    MANUAL_BUTTON("manual_button"),

    /** `adb shell am startservice …` — an unattended test capture with no screen involved. */
    EXTERNAL_INTENT("external_intent"),

    // ---- Not implemented. Declared so the auto-capture system has names to fill in. ------------ //

    /** The truck entered the catchment of a known forecourt. */
    GEOFENCE("geofence"),

    /** A time-of-day / route-position rule fired. */
    SCHEDULE("schedule"),

    /** Vehicle telemetry says the truck is slowing into a stop. */
    MOTION("motion"),

    /** A live detector on the preview stream believes a price board is in view. */
    LIVE_DETECTION("live_detection");

    companion object {
        /**
         * Parses a persisted [wireName], reading anything unrecognised as [fallback].
         *
         * The fallback is a parameter rather than a constant because the honest answer depends on where
         * the string came from, and getting it wrong mislabels the clip for good: an `adb` capture sent
         * with no `trigger` extra used to read as [MANUAL_BUTTON], filing an unattended bench capture
         * under a button nobody pressed. A caller that knows which door the request came through says so.
         */
        fun fromWire(wire: String?, fallback: TriggerSource = MANUAL_BUTTON): TriggerSource =
            entries.firstOrNull { it.wireName == wire } ?: fallback
    }
}

/**
 * The debug screen's Start/Stop buttons, and the equivalent adb intents.
 *
 * There is no listener to register: the service receives an Intent and calls [fire] / [release]
 * directly. It is still expressed as a [CaptureTrigger] so that the manual path and the automatic path
 * enter the capture lane through exactly one door — otherwise the automatic system would arrive at a
 * lane that had only ever been exercised by a code path it does not share.
 */
class ManualTrigger : CaptureTrigger {

    override val name: String = "manual"

    private var sink: CaptureTriggerSink? = null

    override fun attach(sink: CaptureTriggerSink) {
        this.sink = sink
    }

    override fun detach() {
        sink = null
    }

    /** Button (or intent) asked to start. Dropped with a log if the lane is not attached. */
    fun fire(source: TriggerSource, reason: String) {
        val s = sink
        if (s == null) {
            Log.w(TAG, "start ignored — trigger not attached to a capture lane")
            return
        }
        // No station id: a button press is not a decision about a particular forecourt, so a manual
        // capture must not stamp last_capture_time on anything.
        s.requestStart(source, reason, stationId = null)
    }

    /** Button (or intent) asked to stop. Dropped with a log if the lane is not attached. */
    fun release(reason: String) {
        val s = sink
        if (s == null) {
            Log.w(TAG, "stop ignored — trigger not attached to a capture lane")
            return
        }
        // No station id: a button press is not about a particular board, so it stops whatever is
        // rolling.
        s.requestStop(reason, stationId = null)
    }

    private companion object {
        const val TAG = "ManualTrigger"
    }
}

// AutoCaptureTrigger used to sit here: a placeholder implementation, registered but inert, so the
// wiring would be exercised before the real logic existed. LocationTrigger IS that logic and has been
// registered since 2026-08-30, so the placeholder was a second dead implementation of an interface with
// two live ones — and it was never in CaptureLane's `triggers` list, so it exercised nothing.
//
// The one open question it was carrying — whether to fire at all, given night, rain, a full disk or an
// offline backlog — is recorded on the interface above instead, where a reader will actually meet it.
