package com.terminalscout.app.prod.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SparseProbeScheduleTest {
    @Test
    fun `probes one midpoint per three gaps rather than scanning the whole clip`() {
        val base = (0L..15L).map { it * 66_666L }
        val ticks = SparseProbeSchedule.timestamps(base, 1_000_000L, 30.0)

        assertEquals(listOf(33_333L, 233_331L, 433_329L, 633_327L, 833_325L), ticks)
        assertTrue(ticks.all { tick -> base.none { kotlin.math.abs(it - tick) <= 16_666L } })
    }

    @Test
    fun `skips gaps without room for a new frame and timestamps past the video end`() {
        assertEquals(emptyList<Long>(), SparseProbeSchedule.timestamps(listOf(0L), 200_000L, 30.0))
        assertEquals(
            listOf(33_333L),
            SparseProbeSchedule.timestamps(listOf(0L, 66_666L, 100_000L, 133_333L, 200_000L), 140_000L, 30.0)
        )
    }
}
