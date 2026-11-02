package com.terminalscout.app.prod.capture

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The frozen list is pure in-memory logic with no Android surface, so unlike the SQLite store it can
 * be tested properly here rather than only on the device.
 *
 * What matters is the asymmetry: a capture freezes, a departure releases, and a second freeze of an
 * already-frozen board must be a no-op rather than a refresh — otherwise a truck sitting at a forecourt
 * would keep pushing the freeze forward and it would never mean anything.
 */
class FrozenBoardsTest {

    @Before
    fun clean() = FrozenBoards.clear()

    @After
    fun tidy() = FrozenBoards.clear()

    @Test
    fun `starts empty`() {
        assertEquals(0, FrozenBoards.size)
        assertTrue(FrozenBoards.ids().isEmpty())
        assertFalse(FrozenBoards.isFrozen("anything"))
    }

    @Test
    fun `freezing a board makes it frozen`() {
        assertTrue(FrozenBoards.freeze("poi-1", 1_000L))
        assertTrue(FrozenBoards.isFrozen("poi-1"))
        assertEquals(1, FrozenBoards.size)
        assertEquals(1_000L, FrozenBoards.frozenAtMs("poi-1"))
    }

    @Test
    fun `freezing an already-frozen board is refused and does not move the timestamp`() {
        // This is the guard that makes the freeze mean "since the visit began". If a repeat freeze
        // refreshed the time, a vehicle parked in range would keep resetting it.
        assertTrue(FrozenBoards.freeze("poi-1", 1_000L))
        assertFalse(FrozenBoards.freeze("poi-1", 9_999L))
        assertEquals(1_000L, FrozenBoards.frozenAtMs("poi-1"))
        assertEquals(1, FrozenBoards.size)
    }

    @Test
    fun `unfreezing releases the board`() {
        FrozenBoards.freeze("poi-1", 1_000L)
        assertTrue(FrozenBoards.unfreeze("poi-1"))
        assertFalse(FrozenBoards.isFrozen("poi-1"))
        assertEquals(0, FrozenBoards.size)
        assertNull(FrozenBoards.frozenAtMs("poi-1"))
    }

    @Test
    fun `unfreezing a board that was not frozen is reported, not thrown`() {
        // The monitor releases on distance and can reach a board twice — once on the fix that crossed
        // 250m and once on a retry — so this has to be survivable.
        assertFalse(FrozenBoards.unfreeze("never-frozen"))
    }

    @Test
    fun `several boards can be frozen at once`() {
        // The reason the stop edge moved out of the trigger: a single armed-station field could not
        // express two forecourts passed inside a minute.
        FrozenBoards.freeze("a", 1L)
        FrozenBoards.freeze("b", 2L)
        FrozenBoards.freeze("c", 3L)
        assertEquals(3, FrozenBoards.size)
        assertEquals(setOf("a", "b", "c"), FrozenBoards.ids())
    }

    @Test
    fun `releasing one board leaves the others frozen`() {
        FrozenBoards.freeze("a", 1L)
        FrozenBoards.freeze("b", 2L)
        FrozenBoards.unfreeze("a")
        assertFalse(FrozenBoards.isFrozen("a"))
        assertTrue(FrozenBoards.isFrozen("b"))
        assertEquals(setOf("b"), FrozenBoards.ids())
    }

    @Test
    fun `the id snapshot is a copy, so a release mid-iteration is safe`() {
        // StopRecordingMonitor iterates ids() and unfreezes inside the loop.
        FrozenBoards.freeze("a", 1L)
        FrozenBoards.freeze("b", 2L)
        val snapshot = FrozenBoards.ids()
        for (id in snapshot) FrozenBoards.unfreeze(id)
        assertEquals(2, snapshot.size)
        assertEquals(0, FrozenBoards.size)
    }

    @Test
    fun `clear empties everything`() {
        FrozenBoards.freeze("a", 1L)
        FrozenBoards.freeze("b", 2L)
        FrozenBoards.clear()
        assertEquals(0, FrozenBoards.size)
    }

    @Test
    fun `a released board can be frozen again — a second visit is a second capture`() {
        FrozenBoards.freeze("poi-1", 1_000L)
        FrozenBoards.unfreeze("poi-1")
        assertTrue(FrozenBoards.freeze("poi-1", 5_000L))
        assertEquals(5_000L, FrozenBoards.frozenAtMs("poi-1"))
    }

    /**
     * A capture that never rolled must leave the board available.
     *
     * The regression this pins: the freeze used to happen in `beginCapture`, before the camera was
     * asked to bind. A bind failure then froze a board for a capture that never existed, costing that
     * station for the whole visit. The freeze now lives in `rollRecording`'s `onStarted`, which fires
     * on CameraX's Start event — so every earlier failure path leaves the board alone.
     */
    @Test
    fun `a capture that never started leaves the board capturable`() {
        val station = "poi-camera-broken"

        // beginCapture accepted the signal and set up state, then the camera failed to bind. Nothing
        // froze, because nothing rolled.
        assertFalse(FrozenBoards.isFrozen(station))

        // The next fix, two seconds later, must still find it a candidate.
        assertTrue("a bind failure must not cost the station", !FrozenBoards.isFrozen(station))

        // And when the camera does come up, THAT is what freezes it.
        assertTrue(FrozenBoards.freeze(station, 1_000L))
        assertTrue(FrozenBoards.isFrozen(station))
    }

    /**
     * The loop this whole mechanism exists to stop, played out against the real API.
     *
     * A vehicle parks inside 80 m: the trigger fires, the lane freezes, the clip finishes, the vehicle
     * is *still* inside 80 m. Every subsequent fix must find the board frozen and produce nothing.
     */
    @Test
    fun `a parked vehicle cannot produce a second capture`() {
        val station = "poi-parked"
        var capturesStarted = 0

        // fix 1 — arrives inside the radius, nothing frozen yet
        if (!FrozenBoards.isFrozen(station)) {
            capturesStarted++
            FrozenBoards.freeze(station, 1_000L)
        }
        // fixes 2..30 — still parked, still inside the radius
        repeat(29) {
            if (!FrozenBoards.isFrozen(station)) {
                capturesStarted++
                FrozenBoards.freeze(station, 2_000L)
            }
        }

        assertEquals("30 fixes inside the radius must produce exactly one capture", 1, capturesStarted)

        // The vehicle finally leaves: the monitor releases the board.
        FrozenBoards.unfreeze(station)
        if (!FrozenBoards.isFrozen(station)) capturesStarted++
        assertEquals("a genuine second visit captures again", 2, capturesStarted)
    }
}
