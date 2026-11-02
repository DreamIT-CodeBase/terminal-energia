package com.terminalscout.app.prod.capture

import com.terminalscout.app.prod.upload.StationDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the parts of Stage 0's location map that are pure logic: the station record's own semantics,
 * the two radii the trigger decides on, and the distance maths behind them.
 *
 * **The SQLite behaviour is deliberately not tested here.** These are plain JVM unit tests, and this
 * module sets `unitTests.isReturnDefaultValues = true` — so every `SQLiteDatabase` call would return a
 * silent default rather than doing anything, and an upsert test would pass while asserting nothing.
 * That is exactly the trap the `org.json` test dependency exists to avoid elsewhere. The table's real
 * behaviour (upsert preserving `last_capture_time`, removing unserved rows) is covered by
 * `LocationMapDeviceTest` on Android instead.
 */
class LocationMapTest {

    // ---- The station record -------------------------------------------------------------------- //

    @Test
    fun `a station that has never been captured carries null, not zero`() {
        // "never captured" and "captured at the epoch" are different facts, and a 0 sentinel collapses
        // them. The column is NULL-able for this reason, and the default has to match.
        val fresh = FuelStation("poi-1", "OLEREX", 58.4, 26.7, brand = "Olerex")
        assertNull(fresh.lastCaptureTimeMs)
    }

    @Test
    fun `brand is a required field, not an optional one`() {
        // Enforced by the type: a station reaches the table only if Azure named its operator, so there
        // is no default to fall back to and no null to check downstream.
        val s = FuelStation("poi-1", "OLEREX", 58.4, 26.7, brand = "Olerex")
        assertEquals("Olerex", s.brand)
        assertTrue(s.brand.isNotBlank())
    }

    @Test
    fun `modified time defaults to zero so an unstamped record is obvious`() {
        val fresh = FuelStation("poi-1", "OLEREX", 58.4, 26.7, brand = "Olerex")
        assertEquals(0L, fresh.modifiedTimeMs)
    }

    @Test
    fun `identity is the poi id, not the coordinate pair`() {
        // The refresh matches rows on id precisely so a nudged coordinate does not read as a new
        // station and silently reset its capture history.
        val before = FuelStation("poi-1", "OLEREX", 58.40000, 26.70000, brand = "Olerex", lastCaptureTimeMs = 111L)
        val nudged = before.copy(latitude = 58.40004, longitude = 26.70004)
        assertEquals(before.id, nudged.id)
        assertEquals(111L, nudged.lastCaptureTimeMs)
    }

    @Test
    fun `a renamed station keeps its identity and its capture history`() {
        val before = FuelStation("poi-1", "OLEREX", 58.4, 26.7, brand = "Olerex", lastCaptureTimeMs = 222L)
        val renamed = before.copy(name = "Olerex Tartu Kvissentali")
        assertEquals(before.id, renamed.id)
        assertEquals(222L, renamed.lastCaptureTimeMs)
    }

    // ---- The radii the trigger decides on ------------------------------------------------------ //

    @Test
    fun `exit radius is outside the enter radius so the edges cannot chatter`() {
        assertTrue(
            "hysteresis requires EXIT > ENTER, or GPS noise flips the state on the boundary",
            LocationTrigger.EXIT_RADIUS_M > LocationTrigger.ENTER_RADIUS_M
        )
    }

    @Test
    fun `the radii are 80 and 130 metres`() {
        // Reduced from 150/200 on 2026-10-05 — see DECISIONS.md. Pinned rather than merely ordered
        // because the value is a deliberate trade against lane occupancy, not a free parameter.
        assertEquals(80.0, LocationTrigger.ENTER_RADIUS_M, 0.0)
        assertEquals(130.0, LocationTrigger.EXIT_RADIUS_M, 0.0)
    }

    @Test
    fun `default polling interval is 1500 milliseconds`() {
        assertEquals(1_500L, LocationTrigger.UPDATE_INTERVAL_MS)
    }

    // ---- Distance maths, reused from StationDirectory ------------------------------------------ //

    @Test
    fun `distance to a station is measured with the shared haversine`() {
        // Two known Tartu forecourts from the bundled list, ~900 m apart.
        val d = StationDirectory.distanceMetres(58.39867, 26.70944, 58.40682, 26.70912)
        assertTrue("expected roughly 900m, got $d", d > 800 && d < 1000)
    }

    /**
     * The three zones the trigger distinguishes, at distances chosen to be unambiguous.
     *
     * Deliberately NOT testing a point on the boundary: one degree of latitude is ~111.3 km, so
     * picking an offset that lands exactly on 80 m is arithmetic luck, not behaviour. What matters is
     * that the three zones classify in the right order.
     */
    @Test
    fun `the three zones classify correctly`() {
        fun northOf(degrees: Double) =
            StationDirectory.distanceMetres(58.4, 26.7, 58.4 + degrees, 26.7)

        val inside = northOf(0.0005)      // ~56 m — arms a capture
        val band = northOf(0.0009)        // ~100 m — past enter, not yet past exit: holds
        val outside = northOf(0.0014)     // ~156 m — stops a capture

        assertTrue("$inside should arm", inside < LocationTrigger.ENTER_RADIUS_M)

        assertTrue("$band should be past the enter radius", band > LocationTrigger.ENTER_RADIUS_M)
        assertTrue("$band should NOT yet stop", band < LocationTrigger.EXIT_RADIUS_M)

        assertTrue("$outside should stop", outside > LocationTrigger.EXIT_RADIUS_M)

        assertTrue("zones must be ordered", inside < band && band < outside)
    }

    @Test
    fun `the nearest of several stations is the one that arms`() {
        // The trigger takes a minimum over the whole map; this pins that the comparison it uses orders
        // the candidates the way the arming code assumes.
        val lat = 58.4
        val lon = 26.7
        val candidates = listOf(
            FuelStation("far", "Far", 58.45, 26.7, brand = "Neste"),
            FuelStation("near", "Near", 58.4009, 26.7, brand = "Olerex"),
            FuelStation("mid", "Mid", 58.41, 26.7, brand = "Circle K")
        )
        val nearest = candidates.minByOrNull {
            StationDirectory.distanceMetres(lat, lon, it.latitude, it.longitude)
        }
        assertEquals("near", nearest?.id)
    }
}
