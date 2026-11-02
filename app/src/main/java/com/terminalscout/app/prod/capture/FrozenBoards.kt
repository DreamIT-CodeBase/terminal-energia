package com.terminalscout.app.prod.capture

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * STAGE 0 — the frozen list. Which price boards must not be captured again yet.
 *
 * ### The race this exists to stop
 * The location trigger fires whenever the device is inside 80 m of a station, and it fires on every
 * fix. Without this, a truck that stops *at* a forecourt captures a clip, finishes it, is still inside
 * 80 m, and captures another. And another. Forever, until it drives away.
 *
 * A board is frozen when a capture **starts** for it, and stays frozen until the device is clearly past
 * it — 130 m, even if its recording ended earlier. So one visit produces one capture,
 * and a second capture needs a second visit.
 *
 * ### Deliberately in memory only
 * This is not persisted, and that is the right trade for what it does: its whole job is to cap captures
 * within a single visit to a station. A process death mid-visit clears the list and the trigger may
 * capture that station once more — one extra clip, in the rare case that the service was killed while
 * parked at a forecourt. Persisting it would buy that one clip at the cost of a schema, a migration,
 * and a stale-freeze failure mode where a board is frozen on disk and nothing is ever coming to unfreeze
 * it.
 *
 * ### Who touches it
 * | | |
 * |---|---|
 * | [LocationTrigger] | reads — a frozen board is not a candidate |
 * | `CaptureLane` (captureVideo) | **freezes**, when a capture actually begins |
 * | [StopRecordingMonitor] (stopRecording) | **unfreezes**, when the device leaves 130 m |
 *
 * Nothing else may write to it. The asymmetry is the design: the thing that starts a capture owns the
 * freeze, and the thing that watches for departure owns the release.
 */
object FrozenBoards {

    private const val TAG = "FrozenBoards"

    /** Station id → when it was frozen, epoch ms. Concurrent because the writers are different lanes. */
    private val frozen = ConcurrentHashMap<String, Long>()

    /**
     * Freezes a board. Returns false when it was already frozen, which is not an error — a second
     * capture request for a board mid-visit is exactly what this prevents.
     */
    fun freeze(stationId: String, atMs: Long): Boolean {
        val previous = frozen.putIfAbsent(stationId, atMs)
        if (previous != null) {
            Log.d(TAG, "$stationId was already frozen")
            return false
        }
        Log.i(TAG, "FROZE $stationId — ${frozen.size} board(s) frozen")
        return true
    }

    /**
     * Releases a board so it can be captured again. Returns false when it was not frozen.
     *
     * Called only by [StopRecordingMonitor], on leaving the board's 130 m.
     */
    fun unfreeze(stationId: String): Boolean {
        val removed = frozen.remove(stationId) != null
        if (removed) Log.i(TAG, "UNFROZE $stationId — ${frozen.size} board(s) still frozen")
        return removed
    }

    fun isFrozen(stationId: String): Boolean = frozen.containsKey(stationId)

    /** Snapshot of the frozen ids. A copy, so the monitor can unfreeze while iterating. */
    fun ids(): Set<String> = frozen.keys.toSet()

    fun frozenAtMs(stationId: String): Long? = frozen[stationId]

    val size: Int get() = frozen.size

    /**
     * Empties the list.
     *
     * Called when the capture lane shuts down: a freeze only means anything while something is watching
     * for the departure that clears it, and once the monitor is gone every remaining entry is a board
     * that would stay frozen for the life of the process with nothing coming to release it.
     */
    fun clear() {
        if (frozen.isEmpty()) return
        Log.i(TAG, "clearing ${frozen.size} frozen board(s) — nothing is watching for departures now")
        frozen.clear()
    }
}
