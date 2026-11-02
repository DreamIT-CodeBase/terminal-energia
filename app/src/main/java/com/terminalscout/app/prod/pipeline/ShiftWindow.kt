package com.terminalscout.app.prod.pipeline

import android.graphics.Bitmap
import com.terminalscout.app.prod.config.DeviceConfig
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import kotlin.math.roundToInt

/** Which edge a shifted window is pushed against. */
internal enum class ShiftSide { LEFT, RIGHT }

/**
 * The neutral grey everything in this pipeline pads with.
 *
 * ONE definition, deliberately: the model-input letterbox and the shifted-window overhang have to fill
 * with the same value, because the whole point of the overhang is to present an edge object the way the
 * letterbox would have — surrounded by pad, not flush against a border. Two constants that happened to
 * both be 114 would be two things free to drift apart.
 */
internal val NEUTRAL_GREY: Int get() = DeviceConfig.int("board_prep.shift_window.neutral_grey", 114)

/**
 * How much of the frame a shifted window gives up at the far edge: the window covers
 * `1 - SHIFT_FRAC` of the frame's width.
 *
 * Cropping before the square letterbox is the entire point — the region then fills the model's input
 * instead of half of it, worth roughly 1.8x the effective resolution, which is what makes a distant
 * board detectable when the full-frame passes miss it.
 */
internal val SHIFT_FRAC: Double get() = DeviceConfig.double("board_prep.shift_window.shift_frac", 0.45)

/**
 * How far PAST the frame edge each shifted window is pushed, as a fraction of frame width, filled with
 * [NEUTRAL_GREY].
 *
 * ### The trade-off
 * An in-frame window is free: it is a view onto pixels that already exist, and materialising it costs
 * nothing. This one cannot be — a canvas has to be allocated and the overlapping frame region copied
 * into it, once per frame per shifted pass. That copy is the running price of the padding.
 *
 * What it buys: an object hard against the frame edge previously sat flush against the WINDOW border,
 * touching it on one side with no context beyond. The model was trained on letterboxed images where
 * anything near an edge has pad around it, so a flush object is geometry it never saw. Pushing the
 * window out by a quarter of the frame width puts that object inside the window with grey around it
 * instead — the shape the model expects — at the cost of one buffer per frame.
 *
 * Both windows end up `1 - SHIFT_FRAC + SHIFT_PAD_FRAC` of frame width, so left and right stay the
 * same size as each other and neither pass is quietly working at a different scale.
 */
internal val SHIFT_PAD_FRAC: Double get() = DeviceConfig.double("board_prep.shift_window.shift_pad_frac", 0.25)

/**
 * One shifted window, in the FRAME's coordinate space — Bitmap-free on purpose, so the geometry can be
 * reasoned about and tested without an Android runtime.
 *
 * [x0] is where the window's left edge sits in the frame and **is negative for [ShiftSide.LEFT]**. That
 * is the whole hazard this type exists to contain: a negative offset handed to a cropping call is not
 * an error on most array APIs, it wraps, and a window that silently takes a strip off the opposite edge
 * of the frame produces detections in the wrong place with nothing to show for it. Nothing outside this
 * file should compute a window offset; go through [ShiftWindows.geometryOf].
 */
internal data class ShiftGeometry(
    val side: ShiftSide,
    /** Window's left edge in frame coordinates. NEGATIVE for a left window. */
    val x0: Int,
    /** Window width in pixels, padding included. */
    val width: Int,
    val frameWidth: Int,
    val frameHeight: Int
) {
    /** Window's right edge in frame coordinates; past [frameWidth] for a right window. */
    val x1: Int get() = x0 + width

    /** First frame column the window actually covers. */
    val srcLeft: Int get() = x0.coerceIn(0, frameWidth)

    /** One past the last frame column the window covers. */
    val srcRight: Int get() = x1.coerceIn(0, frameWidth)

    /** Where those real pixels land inside the window canvas. */
    val destLeft: Int get() = srcLeft - x0

    /** Width of the real-pixel region. */
    val realWidth: Int get() = (srcRight - srcLeft).coerceAtLeast(0)

    /**
     * Area of the REAL pixels, which is what the area guard must divide by.
     *
     * Not the canvas area. The canvas is ~31% padding, so measuring against it would loosen the guard
     * by about 1.45x — on exactly the passes that run at no confidence threshold and lean on it hardest.
     */
    val realArea: Double get() = realWidth.toDouble() * frameHeight.toDouble()

    /** Grey pixels in the window: the overhang, times full height. */
    val padPixels: Long get() = (width - realWidth).toLong() * frameHeight.toLong()

    /** `x[-25%..55%]` — the span as a reader of a log line wants it. */
    val span: String get() = ShiftWindows.spanLabel(side)
}

/**
 * The single definition of a shifted window: its geometry, its span text, and how to materialise it.
 *
 * Every shifted inference goes through here — the two dedicated passes AND the re-sample rung's
 * per-frame escalation — so there is one answer to "where does the window sit", and changing it changes
 * all of them at once.
 */
internal object ShiftWindows {

    /** Window width as a fraction of frame width: what is kept, plus what hangs off the edge. */
    private val WINDOW_FRAC: Double get() = 1.0 - SHIFT_FRAC + SHIFT_PAD_FRAC

    /**
     * Where [side]'s window sits over a [frameWidth] x [frameHeight] frame.
     *
     * Left runs from `-SHIFT_PAD_FRAC` to `1 - SHIFT_FRAC`; right from `SHIFT_FRAC` to
     * `1 + SHIFT_PAD_FRAC`. Both are [WINDOW_FRAC] wide.
     */
    fun geometryOf(frameWidth: Int, frameHeight: Int, side: ShiftSide): ShiftGeometry {
        val width = (frameWidth * WINDOW_FRAC).roundToInt().coerceAtLeast(1)
        val pad = (frameWidth * SHIFT_PAD_FRAC).roundToInt()
        val x0 = when (side) {
            ShiftSide.LEFT -> -pad
            ShiftSide.RIGHT -> frameWidth - width + pad
        }
        return ShiftGeometry(side, x0, width, frameWidth, frameHeight)
    }

    /**
     * The span as percentages of frame width, derived from the constants rather than written out.
     *
     * Every user-visible description of this geometry comes from here — the pass scope label, the
     * ladder banner, the class docs. Spelling the numbers into a string somewhere else is how a doc
     * comes to describe a window the code stopped using.
     */
    fun spanLabel(side: ShiftSide): String {
        val from = when (side) {
            ShiftSide.LEFT -> -SHIFT_PAD_FRAC
            ShiftSide.RIGHT -> SHIFT_FRAC
        }
        return "x[${pct(from)}..${pct(from + WINDOW_FRAC)}]"
    }

    /** `left x[-25%..55%], right x[45%..125%]` — both spans in one phrase. */
    fun spansLabel(): String =
        "left ${spanLabel(ShiftSide.LEFT)}, right ${spanLabel(ShiftSide.RIGHT)}"

    private fun pct(frac: Double): String = "${(frac * 100).roundToInt()}%"

    /**
     * Builds the window bitmap for [side] over [frame], grey overhang included.
     *
     * **Allocates.** A padded window cannot be a view onto the frame, because part of it is not in the
     * frame — so this fills a canvas with [NEUTRAL_GREY] and copies the overlapping region in, once per
     * frame per shifted pass. See [SHIFT_PAD_FRAC] for what that copy buys.
     *
     * [frame] is only ever read from. The caller keeps it and is free to run more passes over it.
     *
     * @return the canvas and its [ShiftGeometry] — the caller needs [ShiftGeometry.x0] to map boxes back
     *   and [ShiftGeometry.realArea] for the area guard, and must never recompute either itself.
     */
    fun materialise(frame: Bitmap, side: ShiftSide): Pair<Bitmap, ShiftGeometry> {
        val geom = geometryOf(frame.width, frame.height, side)
        val canvasBitmap = Bitmap.createBitmap(geom.width, frame.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(canvasBitmap)
        canvas.drawColor(Color.rgb(NEUTRAL_GREY, NEUTRAL_GREY, NEUTRAL_GREY))
        if (geom.realWidth > 0) {
            canvas.drawBitmap(
                frame,
                Rect(geom.srcLeft, 0, geom.srcRight, frame.height),
                Rect(geom.destLeft, 0, geom.destLeft + geom.realWidth, frame.height),
                null
            )
        }
        return canvasBitmap to geom
    }
}
