package com.terminalscout.app.prod.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * The shifted window's geometry, on the JVM.
 *
 * [ShiftWindows.geometryOf] is deliberately Bitmap-free so this can run without an Android runtime —
 * the arithmetic is where the hazards are (a negative offset, an area guard measuring the wrong thing),
 * and none of them need pixels to demonstrate. The pixel-level claims — that the overhang really is
 * grey, and that the source frame is untouched — are in the instrumented test beside this one.
 */
class ShiftWindowTest {

    private val W = 1920
    private val H = 1080

    // ---------------------------------------------------------------- spans and widths

    @Test
    fun `left window runs from minus a quarter to fifty five percent`() {
        val g = ShiftWindows.geometryOf(W, H, ShiftSide.LEFT)
        assertEquals(-480, g.x0)
        assertEquals(1056, g.x1)
        assertEquals(1536, g.width)
    }

    @Test
    fun `right window runs from forty five percent to a hundred and twenty five`() {
        val g = ShiftWindows.geometryOf(W, H, ShiftSide.RIGHT)
        assertEquals(864, g.x0)
        assertEquals(2400, g.x1)
        assertEquals(1536, g.width)
    }

    @Test
    fun `both windows are the same width, so neither pass works at a different scale`() {
        val l = ShiftWindows.geometryOf(W, H, ShiftSide.LEFT)
        val r = ShiftWindows.geometryOf(W, H, ShiftSide.RIGHT)
        assertEquals(l.width, r.width)
        assertEquals(((1.0 - SHIFT_FRAC + SHIFT_PAD_FRAC) * W).roundToInt(), l.width)
    }

    // ---------------------------------------------------------------- padding

    @Test
    fun `grey pixels per window are exactly the overhang`() {
        val expected = (SHIFT_PAD_FRAC * W).roundToInt().toLong() * H
        for (side in ShiftSide.entries) {
            val g = ShiftWindows.geometryOf(W, H, side)
            assertEquals("$side overhang", expected, g.padPixels)
        }
    }

    @Test
    fun `the overhang is outside the frame on the expected side`() {
        val l = ShiftWindows.geometryOf(W, H, ShiftSide.LEFT)
        assertTrue("left window starts before the frame", l.x0 < 0)
        assertEquals("left window's real pixels start at column 0", 0, l.srcLeft)
        assertEquals("and land past the pad inside the canvas", 480, l.destLeft)

        val r = ShiftWindows.geometryOf(W, H, ShiftSide.RIGHT)
        assertTrue("right window ends past the frame", r.x1 > W)
        assertEquals("right window's real pixels end at the frame edge", W, r.srcRight)
        assertEquals("and start at the canvas's own left edge", 0, r.destLeft)
    }

    // ---------------------------------------------------------------- the area guard

    @Test
    fun `real area is the in-frame part, not the padded canvas`() {
        val expected = (1.0 - SHIFT_FRAC) * W * H
        for (side in ShiftSide.entries) {
            val g = ShiftWindows.geometryOf(W, H, side)
            assertEquals("$side real area", expected, g.realArea, 1.0)
            // The canvas is bigger: dividing by IT would loosen the guard by this factor.
            val canvasArea = g.width.toDouble() * H
            assertEquals("the loosening this avoids", 1.45, canvasArea / g.realArea, 0.02)
        }
    }

    @Test
    fun `a box that passes against the canvas can still fail against real pixels`() {
        val g = ShiftWindows.geometryOf(W, H, ShiftSide.LEFT)
        // A box covering 20% of the real pixels — over the 15% guard, and it must stay over it.
        val boxArea = 0.20 * g.realArea
        assertTrue("fails against real pixels", boxArea / g.realArea >= 0.15)
        assertTrue(
            "would have passed against the padded canvas",
            boxArea / (g.width.toDouble() * H) < 0.15
        )
    }

    // ---------------------------------------------------------------- clamping

    /** What `saveCrops` does to a box once the window offset has been applied. */
    private fun clampToFrame(x1: Float, x2: Float): Pair<Int, Int> =
        x1.toInt().coerceIn(0, W) to x2.toInt().coerceIn(0, W)

    @Test
    fun `a box straddling the frame edge keeps the pixels it has`() {
        val g = ShiftWindows.geometryOf(W, H, ShiftSide.LEFT)
        // A box at canvas x 380..680 maps to frame -100..200: half in the pad, half real.
        val (left, right) = clampToFrame(380f + g.x0, 680f + g.x0)
        assertEquals(0, left)
        assertEquals(200, right)
        assertTrue("survives, trimmed to real pixels", right - left >= 2)
    }

    @Test
    fun `a box wholly inside the padding is dropped`() {
        val g = ShiftWindows.geometryOf(W, H, ShiftSide.LEFT)
        // Canvas x 100..300 maps to frame -380..-180: no real pixels at all.
        val (left, right) = clampToFrame(100f + g.x0, 300f + g.x0)
        assertEquals(0, left)
        assertEquals(0, right)
        assertTrue("collapses to nothing, so saveCrops drops it", right - left < 2)
    }

    @Test
    fun `the same holds past the right edge`() {
        val g = ShiftWindows.geometryOf(W, H, ShiftSide.RIGHT)
        // Canvas x 1400..1500 maps to frame 2264..2364 — entirely past the frame.
        val (left, right) = clampToFrame(1400f + g.x0, 1500f + g.x0)
        assertEquals(W, left)
        assertEquals(W, right)
        assertTrue(right - left < 2)
    }

    // ---------------------------------------------------------------- reported text

    @Test
    fun `span labels are derived from the constants`() {
        assertEquals("x[-25%..55%]", ShiftWindows.spanLabel(ShiftSide.LEFT))
        assertEquals("x[45%..125%]", ShiftWindows.spanLabel(ShiftSide.RIGHT))
        assertEquals("left x[-25%..55%], right x[45%..125%]", ShiftWindows.spansLabel())
    }

    @Test
    fun `the geometry carries its own span text, so a log line cannot describe the wrong window`() {
        assertEquals(
            ShiftWindows.spanLabel(ShiftSide.LEFT),
            ShiftWindows.geometryOf(W, H, ShiftSide.LEFT).span
        )
    }

    // ---------------------------------------------------------------- odd frames

    @Test
    fun `a tiny frame still yields a usable window`() {
        val g = ShiftWindows.geometryOf(4, 4, ShiftSide.LEFT)
        assertTrue("width is at least one pixel", g.width >= 1)
        assertTrue("real width never exceeds the frame", g.realWidth <= 4)
        assertTrue("real area is never negative", g.realArea >= 0.0)
    }
}
