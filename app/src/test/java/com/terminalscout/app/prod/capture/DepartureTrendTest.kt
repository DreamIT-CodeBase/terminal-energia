package com.terminalscout.app.prod.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DepartureTrendTest {
    @Test
    fun `stops after 50 metres traveled from closest approach with sustained departure`() {
        val trend = DepartureTrend()
        assertFalse(trend.observe(150.0, 0.0))
        assertFalse(trend.observe(90.0, 60.0))
        assertFalse(trend.observe(70.0, 20.0))
        assertFalse(trend.observe(75.0, 20.0))
        assertFalse(trend.observe(82.0, 29.0))
        assertTrue(trend.observe(90.0, 1.0))
    }

    @Test
    fun `50 metres of travel alone does not stop an approaching vehicle`() {
        val trend = DepartureTrend()
        assertFalse(trend.observe(140.0, 0.0))
        assertFalse(trend.observe(110.0, 30.0))
        assertFalse(trend.observe(80.0, 30.0))
        assertFalse(trend.observe(70.0, 10.0))
    }

    @Test
    fun `stops for an off axis station without entering a 50 metre station radius`() {
        val trend = DepartureTrend()
        assertFalse(trend.observe(145.0, 0.0))
        assertFalse(trend.observe(105.0, 40.0))
        assertFalse(trend.observe(110.0, 20.0))
        assertTrue(trend.observe(122.0, 30.0))
    }

    @Test
    fun `a closer approach resets the travel counter and departure trend`() {
        val trend = DepartureTrend()
        assertFalse(trend.observe(100.0, 0.0))
        assertFalse(trend.observe(110.0, 30.0))
        assertFalse(trend.observe(120.0, 19.0))
        assertFalse(trend.observe(95.0, 35.0))
        assertFalse(trend.observe(101.0, 20.0))
        assertFalse(trend.observe(107.0, 20.0))
        assertTrue(trend.observe(113.0, 10.0))
    }

    @Test
    fun `gps jitter or a single increase does not confirm departure`() {
        val trend = DepartureTrend()
        assertFalse(trend.observe(100.0, 0.0))
        assertFalse(trend.observe(106.0, 30.0))
        assertFalse(trend.observe(105.0, 20.0))
        assertFalse(trend.observe(106.0, 20.0))
        assertFalse(trend.observe(109.0, 10.0))
        assertTrue(trend.observe(116.0, 10.0))
    }
}
