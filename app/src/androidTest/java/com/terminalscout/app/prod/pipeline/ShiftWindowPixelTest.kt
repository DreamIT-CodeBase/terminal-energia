/*
 * WARNING — running the instrumented suite UNINSTALLS AND REINSTALLS THE APP.
 *
 * `connectedDebugAndroidTest` tears the app down when it finishes, which deletes app storage: the
 * sideloaded test clips under `files/testclips/` (2.4 GB of validation corpus) and the downloaded
 * models under `files/models/`. Both then have to be pushed and re-downloaded by hand, and All-files
 * access has to be re-granted because the uid changes. That is not hypothetical — it happened once and
 * cost the corpus.
 *
 * Run these only when the device holds nothing you would mind losing.
 */

package com.terminalscout.app.prod.pipeline

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

/**
 * The two claims about [ShiftWindows.materialise] that only real pixels can settle: the overhang is
 * actually neutral grey, and the source frame comes back untouched.
 *
 * Instrumented rather than JVM because `Bitmap` is Android — the geometry itself is tested on the JVM
 * in `ShiftWindowTest`, which is where every arithmetic claim lives.
 */
@RunWith(AndroidJUnit4::class)
class ShiftWindowPixelTest {

    private val W = 320
    private val H = 180

    /** A frame with a distinct colour per column, so a wrapped or shifted copy is obvious. */
    private fun frame(): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        for (x in 0 until W) {
            val c = Color.rgb(x % 256, (x * 7) % 256, (x * 13) % 256)
            for (y in 0 until H) bmp.setPixel(x, y, c)
        }
        return bmp
    }

    private fun greyCount(bmp: Bitmap): Long {
        var n = 0L
        for (x in 0 until bmp.width) {
            for (y in 0 until bmp.height) {
                val p = bmp.getPixel(x, y)
                if (Color.red(p) == NEUTRAL_GREY &&
                    Color.green(p) == NEUTRAL_GREY &&
                    Color.blue(p) == NEUTRAL_GREY
                ) n++
            }
        }
        return n
    }

    @Test
    fun greyPixelsMatchTheOverhang() {
        val src = frame()
        val expected = (SHIFT_PAD_FRAC * W).roundToInt().toLong() * H
        for (side in ShiftSide.entries) {
            val (window, geom) = ShiftWindows.materialise(src, side)
            try {
                assertEquals("$side canvas width", geom.width, window.width)
                assertEquals("$side grey pixels", expected, greyCount(window))
                assertEquals("$side grey pixels == geometry's own count", geom.padPixels, greyCount(window))
            } finally {
                window.recycle()
            }
        }
        src.recycle()
    }

    @Test
    fun realPixelsLandWhereTheGeometrySaysAndAreNotWrapped() {
        val src = frame()
        val (window, geom) = ShiftWindows.materialise(src, ShiftSide.LEFT)
        try {
            // The column just inside the pad must be the frame's column 0 — NOT a strip wrapped off
            // the frame's right edge, which is what a negative slice would silently produce.
            assertEquals(src.getPixel(0, 0), window.getPixel(geom.destLeft, 0))
            assertEquals(src.getPixel(10, 5), window.getPixel(geom.destLeft + 10, 5))
            assertNotEquals(src.getPixel(W - 1, 0), window.getPixel(0, 0))
        } finally {
            window.recycle()
        }
        src.recycle()
    }

    @Test
    fun theSourceFrameIsUnchanged() {
        val src = frame()
        val before = IntArray(W * H).also { src.getPixels(it, 0, W, 0, 0, W, H) }
        for (side in ShiftSide.entries) {
            ShiftWindows.materialise(src, side).first.recycle()
        }
        val after = IntArray(W * H).also { src.getPixels(it, 0, W, 0, 0, W, H) }
        assertEquals("frame width", W, src.width)
        assertEquals("frame height", H, src.height)
        for (i in before.indices) {
            if (before[i] != after[i]) {
                throw AssertionError("frame pixel $i changed: ${before[i]} -> ${after[i]}")
            }
        }
        src.recycle()
    }
}
