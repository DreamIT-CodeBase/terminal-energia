package com.terminalscout.app.prod.pipeline

import android.graphics.Bitmap
import com.terminalscout.app.prod.config.DeviceConfig
import android.util.Log
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.imgproc.Imgproc
import kotlin.math.min

/**
 * Clarity score for one image.
 *
 * @param sharpness  variance of the Laplacian (higher = sharper; the single best blur signal)
 * @param contrast   std-dev of grayscale intensities (higher = stronger text/background separation)
 * @param score      combined 0..1 OCR-suitability score
 * @param isSuitable whether the image clears the gate (usable for the reader stage)
 * @param reason     why it failed, empty when it passed — so a rejection can be explained, not just counted
 */
data class ClarityScore(
    val sharpness: Double,
    val contrast: Double,
    val score: Double,
    val isSuitable: Boolean,
    val reason: String = ""
)

/**
 * The clarity gate: is this image legible enough to be worth carrying further?
 *
 * Pure classical CV, no ML. Two independent conditions must BOTH hold — an absolute blur floor, and a
 * combined sharpness/contrast score floor — on top of a hard resolution floor. These constants are
 * tuned to LED price-board crops and are inputs, not universal truths.
 *
 * **Where this runs.** It is a **Stage 2 guardrail**, applied to each board crop the detector cuts out,
 * alongside the confidence gate, NMS, the area guard and the minimum-size check. A crop that fails is
 * never written to disk and never counts toward the run's board tally — which is what lets Stage 2
 * decide it has too few USABLE boards and re-sample the video more densely to find more.
 *
 * It used to live at the top of Stage 3, which was too late: by then the frames were already chosen
 * and a blurred run could only shrink, never recover.
 *
 * Stage 3 still SCORES images (both the incoming crop and the finished canvas) for dashboard
 * telemetry, but no longer gates on the result — everything it is handed has already passed here.
 *
 * The gate fails **open** on any unexpected error — an image is passed through rather than silently
 * lost — but the resolution floor is a deliberate hard reject, not an error.
 */
object ClarityGate {

    private const val TAG = "ClarityGate"

    /** Images smaller than this on their shortest side can't hold legible text. */
    val MIN_DIMENSION_PX: Int get() = DeviceConfig.int("detection.clarity_gate.min_dimension_px", 32)
    /** Laplacian variance at/above which sharpness counts as fully sharp. Normaliser, not a threshold. */
    private val SHARPNESS_REF: Double get() = DeviceConfig.double("detection.clarity_gate.sharpness_ref", 150.0)
    /** Absolute blur floor — below this the image is rejected however well it scores elsewhere. */
    val SHARPNESS_HARD_MIN: Double get() = DeviceConfig.double("detection.clarity_gate.sharpness_hard_min", 60.0)
    /** Grayscale std-dev at/above which contrast counts as fully strong. Normaliser, not a threshold. */
    private val CONTRAST_REF: Double get() = DeviceConfig.double("detection.clarity_gate.contrast_ref", 60.0)
    /** Combined-score gate the image must clear to be considered OCR-suitable. */
    val SCORE_THRESHOLD: Double get() = DeviceConfig.double("detection.clarity_gate.score_threshold", 0.5)
    private val SHARPNESS_WEIGHT: Double get() = DeviceConfig.double("detection.clarity_gate.sharpness_weight", 0.6)
    private val CONTRAST_WEIGHT: Double get() = DeviceConfig.double("detection.clarity_gate.contrast_weight", 0.4)

    /** Loads OpenCV's bundled native lib once; if it fails the gate passes everything through. */
    private val openCvReady: Boolean by lazy {
        OpenCVLoader.initLocal().also { ok ->
            if (!ok) Log.e(TAG, "OpenCV failed to init — the clarity gate will pass everything")
        }
    }

    /**
     * Scores [bitmap] and says whether it clears the gate.
     *
     * `sharpness` is the VARIANCE of the Laplacian. [Core.meanStdDev] returns a standard deviation, so
     * it is squared exactly once here — squaring it again is the classic way to get this wrong.
     */
    fun score(bitmap: Bitmap): ClarityScore {
        if (!openCvReady) return ClarityScore(0.0, 0.0, 1.0, true, "OpenCV unavailable, passed through")

        // Resolution floor: too small to carry legible characters, no need to measure sharpness.
        if (min(bitmap.width, bitmap.height) < MIN_DIMENSION_PX) {
            return ClarityScore(
                0.0, 0.0, 0.0, false,
                "under ${MIN_DIMENSION_PX}px (${bitmap.width}x${bitmap.height})"
            )
        }

        val src = Mat()
        val gray = Mat()
        val laplacian = Mat()
        return try {
            Utils.bitmapToMat(bitmap, src)
            Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)

            // --- 1. Sharpness: variance of the Laplacian (std^2) ---
            Imgproc.Laplacian(gray, laplacian, CvType.CV_64F)
            val lapStd = MatOfDouble()
            val lapMean = MatOfDouble()
            Core.meanStdDev(laplacian, lapMean, lapStd)
            val lapStdVal = lapStd.toArray().firstOrNull() ?: 0.0
            val sharpness = lapStdVal * lapStdVal

            // --- 2. Contrast: std-dev of grayscale intensities ---
            val gStd = MatOfDouble()
            val gMean = MatOfDouble()
            Core.meanStdDev(gray, gMean, gStd)
            val contrast = gStd.toArray().firstOrNull() ?: 0.0

            lapStd.release(); lapMean.release(); gStd.release(); gMean.release()

            val sharpNorm = (sharpness / SHARPNESS_REF).coerceIn(0.0, 1.0)
            val contrastNorm = (contrast / CONTRAST_REF).coerceIn(0.0, 1.0)
            val combined = SHARPNESS_WEIGHT * sharpNorm + CONTRAST_WEIGHT * contrastNorm

            // Passes only if it is not hard-blurry AND its combined score clears the gate.
            val blurOk = sharpness >= SHARPNESS_HARD_MIN
            val scoreOk = combined >= SCORE_THRESHOLD
            val reason = when {
                !blurOk -> "too blurred (sharpness %.0f < %.0f)".format(sharpness, SHARPNESS_HARD_MIN)
                !scoreOk -> "score %.2f < %s".format(combined, SCORE_THRESHOLD)
                else -> ""
            }
            ClarityScore(sharpness, contrast, combined, blurOk && scoreOk, reason)
        } catch (e: Exception) {
            // Fail open — never lose an image to an unexpected CV error.
            Log.e(TAG, "clarity scoring failed — passing image through", e)
            ClarityScore(0.0, 0.0, 1.0, true, "gate errored, passed through")
        } finally {
            src.release(); gray.release(); laplacian.release()
        }
    }
}
