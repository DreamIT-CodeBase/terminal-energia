package com.terminalscout.app.prod.pipeline

import android.graphics.Bitmap
import com.terminalscout.app.prod.config.DeviceConfig
import android.graphics.Rect
import android.util.Log
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/** Which tone curve the night enhancement applies. [GAMMA] is the default; [LOG] is the alternative. */
enum class NightTone { GAMMA, LOG }

/**
 * Every constant of the night filter, as a knob.
 *
 * The defaults are documented on the corresponding constants in [NightFilter]. Construct a copy
 * to explore, e.g.
 * `NightFilterConfig(gamma = 4.0, erodeIters = 4)`.
 */
data class NightFilterConfig(
    val tone: NightTone = NightTone.GAMMA,
    val gamma: Double = NightFilter.GAMMA,
    val logScale: Double = NightFilter.LOG_SCALE,
    /** 5 or 7; 0 skips the unsharp step entirely. Forced odd. */
    val sharpenKernel: Int = NightFilter.SHARPEN_KERNEL,
    val sharpenSigma: Double = NightFilter.SHARPEN_SIGMA,
    val sharpenStrength: Double = NightFilter.SHARPEN_STRENGTH,
    val sharpenAmount: Double = NightFilter.SHARPEN_AMOUNT,
    val otsuBlackPoint: Double = NightFilter.OTSU_BLACK_POINT,
    val otsuGamma: Double = NightFilter.OTSU_GAMMA,
    /** 3 or 5. */
    val erodeKernel: Int = NightFilter.ERODE_KERNEL,
    /** 0 skips the erosion. The default is 1 — see [NightFilter.ERODE_ITERS]. */
    val erodeIters: Int = NightFilter.ERODE_ITERS,
    val dilateKernel: Int = NightFilter.DILATE_KERNEL,
    /** 0 = off. 1 is the "light dilation" that gives stroke weight back to over-thin segments. */
    val dilateIters: Int = NightFilter.DILATE_ITERS,
    /** Balanced erode-then-dilate instead of the asymmetric pair — drops isolated specks. */
    val morphOpen: Boolean = NightFilter.MORPH_OPEN
)

/**
 * Night-time image processing — the NIGHT BRANCH of Stage 3's own steps.
 *
 * ### What it replaces, and where
 * Two of [ImagePrepStage]'s steps have a day form and a night form, and this object is the night one:
 *
 * | | day ([ImagePrepStage]) | night (here) |
 * |---|---|---|
 * | Step B, enhance | grey → CLAHE → unsharp σ1.5 | [enhance]: grey → gamma → unsharp σ2.0 |
 * | Step D, post-region | max-channel panels, ghost dimming (Otsu, black point 0.80, gamma 1.30) | [finish]: mild Otsu (black point 0.40, gamma 0.80) then erosion |
 *
 * Everything else — the single letterbox, the one region-model inference, naming, the audit, the layout
 * read and the lettered annotation — is Stage 3's and runs identically on both branches. This object
 * owns no model and reads no files; it is a pair of image operators.
 *
 * ### Why the day filter is wrong at night
 * On a bloomed night board every step of the day branch works against the digits. CLAHE has an
 * already-clipped bimodal histogram to work with, so it lifts the halo toward the stroke; a σ1.5 unsharp
 * sharpens the wrong frequency band — bloom is low-frequency — while hard-clipping the stroke shoulders;
 * and the day branch's max-channel panel render preserves colored LEDs where night greyscale would
 * lose them. Run the night branch on a day board and it is equally damaging in reverse, which
 * is why the switch is made from measured sun times rather than from the image.
 *
 * ### The night answer, in two halves
 * **In value** ([enhance]): a gamma curve pulls the blown highlight end down, then a large-kernel
 * unsharp attacks the same bloom **in space** — bloom is a low-frequency glow, so subtracting a blurred
 * copy removes the glow and leaves the stroke.
 *
 * **In shape** ([finish]): inside each detected panel, a MILD Otsu re-stretch separates stroke from
 * halo without binarising, then erosion thins the bright strokes so bloom-closed segment gaps reopen,
 * with a light dilation giving back the stroke weight the erosion took — one pass each by default.
 */
object NightFilter {

    private const val TAG = "NightFilter"

    // ---- Step B: tone --------------------------------------------------------------------------- //
    /**
     * Measured on a 40-crop night folder, clipped fraction of the grey image:
     * gamma 1.0 → 19.6%, 2.2 → 13.5%, **3.0 → 9.0%**, 4.0 → 9.0%, 5.0 → 9.0%, 6.0 → 2.5%.
     *
     * The plateau between 3 and 5 is not a measurement artefact: a pixel at a true 255 is INVARIANT
     * under gamma (255^g / 255^(g-1) = 255), so gamma can only compress the 250-254 shoulder, never
     * recover a saturated core. Past about 3.0 it is buying shoulder, not cores, and it darkens the
     * dim-but-lit strokes while doing so.
     *
     * 3.0 is therefore the default — the start of the plateau, and short of where lit strokes start
     * being sacrificed. Region detection is indifferent to the choice: 40/40 crops detected at every
     * value from 1.0 to 6.0, best confidence 0.844-0.849 throughout, so gamma is free to be tuned for
     * legibility alone.
     */
    val GAMMA: Double get() = DeviceConfig.double("board_prep.night_filter.gamma", 3.0)

    /** Used only with [NightTone.LOG]: `out = scale·ln(1 + in)`. Useful range 20-50. */
    val LOG_SCALE: Double get() = DeviceConfig.double("board_prep.night_filter.log_scale", 30.0)

    // ---- Step B: unsharp ------------------------------------------------------------------------ //
    /**
     * The kernel is LARGE where the day unsharp is small. Its σ1.5 targets fine detail; at σ2.0 with a
     * 5x5 kernel the blurred copy is a fair model of the halo itself, so what gets subtracted is the
     * halo rather than the noise floor.
     */
    val SHARPEN_KERNEL: Int get() = DeviceConfig.int("board_prep.night_filter.sharpen_kernel", 5)
    val SHARPEN_SIGMA: Double get() = DeviceConfig.double("board_prep.night_filter.sharpen_sigma", 2.0)

    /** Weight on the blurred copy. Useful range 0.4-0.8. */
    val SHARPEN_STRENGTH: Double get() = DeviceConfig.double("board_prep.night_filter.sharpen_strength", 0.6)

    /**
     * Weight on the original. The specified formula is `original − 0.6·blurred`, i.e. amount 1.0, and at
     * those weights the two do not sum to 1 (1.0 − 0.6 = 0.4) so the operator darkens as much as it
     * sharpens. Measured on one crop's three panels, in-box mean after the full pipeline:
     *
     * | amount | in-box mean | clipped | |
     * |---|---|---|---|
     * | 1.0 | 21.2 | 0.0% | separated, but strokes are dim grey |
     * | 1.3 | 42.1 | 0.0% | |
     * | **1.6** | **70.8** | **0.7%** | separated AND bright — best read |
     * | 1.8 | 70.2 | 19.8% | strokes blow out again |
     *
     * 1.6 is where the two weights sum to 1, so the sharpen stops doubling as a darkener, and it is the
     * last value before clipping returns.
     *
     * Measured cost to region detection across 40 crops: 40/40 still detected at every setting tried,
     * 116-122 regions against 122 unsharpened, best confidence 0.808-0.846 against 0.846. Real but
     * small, and no board is lost.
     */
    val SHARPEN_AMOUNT: Double get() = DeviceConfig.double("board_prep.night_filter.sharpen_amount", 1.6)

    // ---- Step D: per-box Otsu, mild ------------------------------------------------------------- //
    /**
     * Black point, as a fraction of that box's own Otsu split. Lower than the day dimming's 0.80
     * so dim night strokes are not clipped before the re-stretch.
     * Everything at or below it clips to black; everything above is re-stretched.
     *
     * "Mild" is what separates this from a hard binarisation: the output stays continuous-tone, so the
     * digit keeps its shoulder and a reader sees a stroke rather than a silhouette, and a bloom-merged
     * pair of digits is not frozen into one solid blob.
     */
    val OTSU_BLACK_POINT: Double get() = DeviceConfig.double("board_prep.night_filter.otsu_black_point", 0.40)

    /**
     * 0.80 lifts dim night strokes a little more than the previous neutral (1.0) re-stretch.
     * Unlike the day branch's 1.30, this lifts any surviving halo too.
     */
    val OTSU_GAMMA: Double get() = DeviceConfig.double("board_prep.night_filter.otsu_gamma", 0.80)

    // ---- Step D: erosion ------------------------------------------------------------------------ //
    /**
     * Grey erosion (a min filter), not binary — the Otsu above does not binarise. It thins the bright
     * strokes, which is what reopens a segment gap that bloom had closed, and run hard enough it is the
     * most direct way to eat whatever halo survived the enhancement.
     *
     * Erosion and dilation are separate knobs rather than one opening, because the point is an
     * ASYMMETRIC pair: erode to eat the halo, then dilate ONCE to give back stroke weight. [MORPH_OPEN]
     * balances them, which is a different operation, kept for dropping isolated specks. Measured on one
     * crop's three panels at amount 1.6:
     *
     * | | in-box mean | |
     * |---|---|---|
     * | erode 2 | 70.8 | digits separate, strokes still full |
     * | erode 3 | 58.4 | gaps fully open, strokes thinning |
     * | **erode 3 + dilate 1** | **70.4** | gaps open AND stroke weight restored |
     * | erode 4 | 13.7 | over-eaten at this stroke width |
     *
     * The default is now 1 erosion + 1 dilation, to preserve more of the night strokes. The table
     * above describes measurements with the older, stronger settings, not this new default.
     *
     * The kernel stays 3: OpenCV's 3x3 ellipse is already the gentle cross (it leaves the diagonals
     * alone), and there is nothing between it and no erosion at all.
     */
    val ERODE_KERNEL: Int get() = DeviceConfig.int("board_prep.night_filter.erode_kernel", 3)
    val ERODE_ITERS: Int get() = DeviceConfig.int("board_prep.night_filter.erode_iters", 1)
    val DILATE_KERNEL: Int get() = DeviceConfig.int("board_prep.night_filter.dilate_kernel", 3)
    val DILATE_ITERS: Int get() = DeviceConfig.int("board_prep.night_filter.dilate_iters", 1)
    val MORPH_OPEN: Boolean get() = DeviceConfig.boolean("board_prep.night_filter.morph_open", false)

    /** A pixel is "clipped" at or above this. `threshold` is exclusive, hence 249 for `>= 250`. */
    private val CLIP_FLOOR: Double get() = DeviceConfig.double("board_prep.night_filter.clip_floor", 249.0)

    /** Loads OpenCV's bundled native lib once. Both operators need it, so failure is a pass-through. */
    private val openCvReady: Boolean by lazy {
        OpenCVLoader.initLocal().also { ok ->
            if (!ok) Log.e(TAG, "OpenCV failed to init — night crops will pass through unfiltered")
        }
    }

    // ---------------------------------------------------------------------------------------- //
    // Step B — the night enhancement
    // ---------------------------------------------------------------------------------------- //

    /**
     * Greyscale → tone curve → unsharp mask, at the crop's NATIVE size.
     *
     * The night counterpart of [ImagePrepStage.prepImage], with the same contract: a grey bitmap at the
     * input's own size, no resize — Stage 3's letterbox is where the single resample belongs. Passes the
     * input through (as a copy) if OpenCV is unavailable.
     *
     * "Clipped" — the percentage of pixels at 250 or above — is logged before and after, because it is
     * the bloom in one number and the only cheap way to see whether the curve did anything on this
     * board.
     */
    fun enhance(crop: Bitmap, config: NightFilterConfig = NightFilterConfig()): Bitmap {
        if (!openCvReady) return crop.copy(crop.config ?: Bitmap.Config.ARGB_8888, false)

        val src = Mat()
        val grey = Mat()
        val toned = Mat()
        val sharp = Mat()
        try {
            Utils.bitmapToMat(crop, src)                                  // RGBA
            Imgproc.cvtColor(src, grey, Imgproc.COLOR_RGBA2GRAY)
            val clippedIn = clippedPercent(grey)

            toneCurve(grey, toned, config)
            unsharp(toned, sharp, config)

            Log.d(
                TAG,
                "night enhance: %dx%d, clipped %.2f%% -> %.2f%%, mean %.0f"
                    .format(crop.width, crop.height, clippedIn, clippedPercent(sharp),
                        Core.mean(sharp).`val`[0])
            )
            val out = Bitmap.createBitmap(sharp.width(), sharp.height(), Bitmap.Config.ARGB_8888)
            try {
                Utils.matToBitmap(sharp, out)
            } catch (t: Throwable) {
                // Freed before the fallback below copies the crop, so the failure path does not hold
                // an enhanced image it has no way to hand back while asking for another of the same
                // size. Allocated at the CROP's native size, not the 640 canvas's.
                out.recycle()
                throw t
            }
            return out
        } catch (t: Throwable) {
            // Fails OPEN, passing the unenhanced crop through: a night board that was not tone-mapped
            // is harder to read, not unreadable, and Stage 3 goes on to letterbox and dim it either
            // way. `NightFilter` is an improvement to an image, never the decision about it.
            //
            // `Throwable` because every call in this function is native OpenCV, where the two faults
            // that actually happen are `UnsatisfiedLinkError` (the library did not load) and
            // `OutOfMemoryError` (a `Mat` the size of a close-up board), and both are `Error`. A
            // filter that cannot run must not be able to cost a capture.
            Log.w(TAG, "night enhancement failed — passing the crop through", t)
            return crop.copy(crop.config ?: Bitmap.Config.ARGB_8888, false)
        } finally {
            src.release(); grey.release(); toned.release(); sharp.release()
        }
    }

    /**
     * Gamma correction, or the log transform alternative.
     *
     * Through a 256-entry LUT rather than per pixel: the input is uint8, so the LUT is exact and costs
     * one table build per call.
     */
    private fun toneCurve(grey: Mat, dst: Mat, config: NightFilterConfig) {
        val lut = Mat(1, 256, CvType.CV_8UC1)
        try {
            val data = ByteArray(256) { i ->
                val v = if (config.tone == NightTone.LOG) {
                    config.logScale * ln(1.0 + i)
                } else {
                    255.0 * (i / 255.0).pow(config.gamma)
                }
                v.roundToInt().coerceIn(0, 255).toByte()
            }
            lut.put(0, 0, data)
            Core.LUT(grey, lut, dst)
        } finally {
            lut.release()
        }
    }

    /**
     * Unsharp mask: `out = amount·grey − strength·blur(grey)`.
     *
     * Note the shape: a weighted DIFFERENCE, not the day branch's `grey + amount·(grey − blur)`. The two
     * weights are independent here, which is what lets 1.6/0.6 sum to 1 and stop the operator doubling
     * as a darkener.
     *
     * Done in float and narrowed once at the end. In 8-bit the intermediate would clip at every pixel
     * where the high-pass goes negative — which is the whole halo, the very thing this pushes toward
     * black.
     */
    private fun unsharp(toned: Mat, dst: Mat, config: NightFilterConfig) {
        if (config.sharpenKernel <= 0) {
            toned.copyTo(dst)
            return
        }
        val k = if (config.sharpenKernel % 2 == 1) config.sharpenKernel else config.sharpenKernel + 1
        val blur = Mat()
        val tonedF = Mat()
        val blurF = Mat()
        val outF = Mat()
        try {
            Imgproc.GaussianBlur(toned, blur, Size(k.toDouble(), k.toDouble()), config.sharpenSigma)
            toned.convertTo(tonedF, CvType.CV_32F)
            blur.convertTo(blurF, CvType.CV_32F)
            Core.addWeighted(tonedF, config.sharpenAmount, blurF, -config.sharpenStrength, 0.0, outF)
            // convertTo saturates, which is the single clip to 0..255.
            outF.convertTo(dst, CvType.CV_8U)
        } finally {
            blur.release(); tonedF.release(); blurF.release(); outF.release()
        }
    }

    // ---------------------------------------------------------------------------------------- //
    // Step D — the night post-region work
    // ---------------------------------------------------------------------------------------- //

    /**
     * Mild per-box Otsu, then erosion, inside the detected regions only. **Mutates [canvas] in place.**
     *
     * The night counterpart of [ImagePrepStage]'s ghost dimming, with the same contract: it reads the
     * canvas's own pixels, so it MUST run before the red boxes and letter plates are painted over them,
     * and it returns the number of boxes it processed.
     *
     * Scoped to the boxes for the same reason the day dimming is: working the whole canvas would eat the
     * letterbox pad edge and any unrelated forecourt signage.
     *
     * @return how many boxes were processed
     */
    fun finish(
        canvas: Bitmap,
        regions: List<PriceRegion>,
        config: NightFilterConfig = NightFilterConfig()
    ): Int {
        if (!openCvReady || regions.isEmpty()) return 0
        val full = Mat()
        val grey = Mat()
        try {
            Utils.bitmapToMat(canvas, full)                               // RGBA
            Imgproc.cvtColor(full, grey, Imgproc.COLOR_RGBA2GRAY)         // canvas content is grey

            val boxes = regions.map { it.box }
            val done = otsuRegions(grey, boxes, config)
            erodeRegions(grey, boxes, config)

            val rgba = Mat()
            try {
                Imgproc.cvtColor(grey, rgba, Imgproc.COLOR_GRAY2RGBA)
                Utils.matToBitmap(rgba, canvas)
            } finally {
                rgba.release()
            }
            return done
        } catch (t: Throwable) {
            // Fails OPEN, leaving the canvas as Step C made it — same policy and same `Throwable`
            // reasoning as `enhance` above. This is per-region morphology on an image that is already
            // usable; refusing to return a canvas because the polish failed would be losing a board
            // to make it prettier.
            Log.w(TAG, "night post-region work failed — leaving the canvas as it was", t)
            return 0
        } finally {
            full.release(); grey.release()
        }
    }

    /**
     * A mild, per-box, Otsu-split re-stretch inside the boxes only.
     *
     * The split is computed per ROI rather than once for the whole canvas, so a uniformly dim board is
     * rescaled on its own terms instead of being wiped out. Pixels outside every box are untouched.
     */
    private fun otsuRegions(grey: Mat, boxes: List<Rect>, config: NightFilterConfig): Int {
        var done = 0
        for (box in boxes) {
            val roi = clampedSubmat(grey, box) ?: continue
            val tmp = Mat()
            try {
                // Otsu on this box alone -> the brightness separating stroke from bloom. Only the split
                // value is wanted; the binary mask it also produces is discarded.
                val split = Imgproc.threshold(
                    roi, tmp, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU
                )
                val lut = blackPointGammaLut(split * config.otsuBlackPoint, config.otsuGamma)
                try {
                    Core.LUT(roi, lut, roi)                       // in-place, through the submat view
                } finally {
                    lut.release()
                }
                done++
            } finally {
                tmp.release(); roi.release()
            }
        }
        return done
    }

    /**
     * Thins the bright strokes, inside the detected regions only. Erosion first, then the optional light
     * dilation, applied as one pass per box rather than two passes over the canvas — so a box's dilation
     * can never reach across into a neighbour that has not been eroded yet.
     *
     * Each ROI is COPIED out, morphed, and copied back. Morphology on a submat view would read the
     * pixels just outside the box for its border, so the halo this is meant to eat would leak back in
     * from beyond the box edge.
     */
    private fun erodeRegions(grey: Mat, boxes: List<Rect>, config: NightFilterConfig) {
        val doErode = config.erodeKernel > 0 && config.erodeIters > 0
        val doDilate = config.dilateKernel > 0 && config.dilateIters > 0
        if (boxes.isEmpty() || (!doErode && !doDilate)) return

        val ek = if (doErode) {
            Imgproc.getStructuringElement(
                Imgproc.MORPH_ELLIPSE,
                Size(config.erodeKernel.toDouble(), config.erodeKernel.toDouble())
            )
        } else null
        val dk = if (doDilate) {
            Imgproc.getStructuringElement(
                Imgproc.MORPH_ELLIPSE,
                Size(config.dilateKernel.toDouble(), config.dilateKernel.toDouble())
            )
        } else null
        val anchor = Point(-1.0, -1.0)   // kernel centre

        try {
            for (box in boxes) {
                val roi = clampedSubmat(grey, box) ?: continue
                // Separate source and destination for every pass. OpenCV does not promise in-place
                // morphology the way it does for per-pixel LUTs, and a filter engine that reused the
                // buffer would feed already-eroded rows into the next row's neighbourhood.
                var work = roi.clone()
                try {
                    if (doErode && ek != null) {
                        val eroded = Mat()
                        if (config.morphOpen) {
                            Imgproc.morphologyEx(work, eroded, Imgproc.MORPH_OPEN, ek, anchor, config.erodeIters)
                        } else {
                            Imgproc.erode(work, eroded, ek, anchor, config.erodeIters)
                        }
                        work.release()
                        work = eroded
                    }
                    if (doDilate && dk != null) {
                        val dilated = Mat()
                        Imgproc.dilate(work, dilated, dk, anchor, config.dilateIters)
                        work.release()
                        work = dilated
                    }
                    work.copyTo(roi)
                } finally {
                    work.release(); roi.release()
                }
            }
        } finally {
            ek?.release(); dk?.release()
        }
    }

    // ---- helpers ------------------------------------------------------------------------------- //

    /** Percentage of pixels at 250 or above — the bloom, in one number. */
    private fun clippedPercent(m: Mat): Double {
        if (m.empty()) return 0.0
        val mask = Mat()
        try {
            Imgproc.threshold(m, mask, CLIP_FLOOR, 255.0, Imgproc.THRESH_BINARY)
            return 100.0 * Core.countNonZero(mask) / (m.rows().toDouble() * m.cols())
        } finally {
            mask.release()
        }
    }

    /**
     * A view into [m] over [box], clamped to the image; null when the clamp leaves nothing. The caller
     * must release it — writes through it DO reach the parent.
     */
    private fun clampedSubmat(m: Mat, box: Rect): Mat? {
        val x = box.left.coerceIn(0, m.cols() - 1)
        val y = box.top.coerceIn(0, m.rows() - 1)
        val w = box.width().coerceIn(0, m.cols() - x)
        val h = box.height().coerceIn(0, m.rows() - y)
        if (w <= 0 || h <= 0) return null
        return m.submat(y, y + h, x, x + w)
    }

    /**
     * A 1x256 8-bit LUT: intensities at or below [black] map to 0, and those above are stretched across
     * 0..255 with exponent [gamma] (1.0 = linear).
     */
    private fun blackPointGammaLut(black: Double, gamma: Double): Mat {
        val span = max(1.0, 255.0 - black)
        val data = ByteArray(256) { i ->
            val t = ((i - black) / span).coerceIn(0.0, 1.0)
            (255.0 * t.pow(gamma)).roundToInt().coerceIn(0, 255).toByte()
        }
        return Mat(1, 256, CvType.CV_8UC1).apply { put(0, 0, data) }
    }
}
