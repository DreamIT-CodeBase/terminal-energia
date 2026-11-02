package com.terminalscout.app.prod.capture

/**
 * STAGE 0 — the last GPS fix the app saw, in memory.
 *
 * ### Why this exists
 * A clip's sidecar records the **station's** coordinate, which answers "which board was this aimed
 * at" and nothing else. The number the trigger geometry work actually needs is the *lead distance* —
 * how far short of the board the recorder rolled — and that is the difference between the station's
 * position and the **vehicle's** at that instant. Neither the lane nor the metadata store had the
 * second one: the fix lived inside [LocationTrigger.onFix] and was discarded as soon as the
 * comparison against the map was done.
 *
 * So the fix is parked here on its way past. Both subscribers write to it — [LocationTrigger] and
 * [StopRecordingMonitor] — because either can be the one receiving updates at the moment a capture
 * rolls, and a lane that read only the trigger's would record nothing for a manual capture taken
 * while the trigger was detached.
 *
 * ### In memory, and that is correct
 * Same reasoning as [FrozenBoards]. A fix is a statement about *now*; one recovered from disk after a
 * restart describes where the vehicle was before the process died, which is exactly the number that
 * must not be written into a clip's provenance as though it were current. A cold start therefore has
 * no fix, [last] is null, and the sidecar says so — see [VehicleFix.Fix].
 *
 * ### Nothing here decides anything
 * It is a value holder on purpose. No radius, no staleness rule, no filtering: [atMs] travels with
 * every fix so a reader can age it themselves, and a parked vehicle's hour-old fix is not wrong —
 * `MIN_UPDATE_DISTANCE_M` means a stationary truck legitimately stops producing them. Dropping a fix
 * for being old would throw away a correct position and record nothing in its place.
 */
object VehicleFix {

    /**
     * One fix.
     *
     * @param accuracyM the provider's horizontal accuracy, or null if it did not supply one. Kept
     *   because it bounds what the lead distance derived from this fix can be said to mean — a 15 m
     *   error on a 40 m lead is most of the answer.
     * @param atMs wall-clock time of the fix, for ageing it against a clip's `captured_at_ms`.
     */
    data class Fix(
        val latitude: Double,
        val longitude: Double,
        val accuracyM: Float?,
        val atMs: Long
    )

    /** The most recent fix, or null before the first one arrives in this process. */
    @Volatile
    var last: Fix? = null
        private set

    /** Called from a location callback on every fix. Cheap by design — this is on the fix path. */
    fun record(latitude: Double, longitude: Double, accuracyM: Float?) {
        last = Fix(latitude, longitude, accuracyM, System.currentTimeMillis())
    }

    /** Forgets the last fix. For tests; nothing in the app clears this. */
    fun clear() {
        last = null
    }
}
