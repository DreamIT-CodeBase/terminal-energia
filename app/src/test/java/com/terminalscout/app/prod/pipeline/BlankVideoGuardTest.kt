package com.terminalscout.app.prod.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlankVideoGuardTest {

    @Test
    fun `samples five evenly spaced timestamps without seeking to duration`() {
        assertEquals(
            listOf(0L, 2_499_999L, 4_999_999L, 7_499_999L, 9_999_999L),
            BlankVideoGuard.sampleTimestamps(10_000_000L)
        )
    }

    @Test
    fun `short duration sample timestamps are distinct`() {
        assertEquals(listOf(0L, 1L, 2L), BlankVideoGuard.sampleTimestamps(3L))
        assertTrue(BlankVideoGuard.sampleTimestamps(0L).isEmpty())
    }

    @Test
    fun `near black and near white flat samples are blank`() {
        assertTrue(BlankVideoGuard.isBlankFrame(BlankVideoGuard.FrameStats(0.0, 0.0)))
        assertTrue(BlankVideoGuard.isBlankFrame(BlankVideoGuard.FrameStats(253.0, 0.5)))
        assertFalse(BlankVideoGuard.isBlankFrame(BlankVideoGuard.FrameStats(5.0, 0.0)))
        assertFalse(BlankVideoGuard.isBlankFrame(BlankVideoGuard.FrameStats(250.0, 0.0)))
    }

    @Test
    fun `one textured sample allows dark or bright real footage`() {
        val black = BlankVideoGuard.FrameStats(1.0, 0.1)
        val white = BlankVideoGuard.FrameStats(254.0, 0.1)
        assertFalse(
            BlankVideoGuard.shouldReject(
                listOf(black, black, BlankVideoGuard.FrameStats(8.0, 5.0), black, black)
            )
        )
        assertFalse(
            BlankVideoGuard.shouldReject(
                listOf(white, white, BlankVideoGuard.FrameStats(247.0, 4.0), white, white)
            )
        )
    }

    @Test
    fun `rejects only when enough decoded samples are all flat black or white`() {
        val black = BlankVideoGuard.FrameStats(1.0, 0.1)
        val white = BlankVideoGuard.FrameStats(254.0, 0.1)
        assertTrue(BlankVideoGuard.shouldReject(listOf(black, black, black, black, black)))
        assertTrue(BlankVideoGuard.shouldReject(listOf(white, white, white)))
        assertFalse(BlankVideoGuard.shouldReject(listOf(black, black)))
        assertFalse(BlankVideoGuard.shouldReject(emptyList()))
    }

    @Test
    fun `measures grayscale mean and population standard deviation`() {
        val stats = BlankVideoGuard.measure(intArrayOf(0xff000000.toInt(), 0xffffffff.toInt()))
        assertEquals(127.5, stats.meanLuminance, 0.001)
        assertEquals(127.5, stats.standardDeviation, 0.001)
    }
}
