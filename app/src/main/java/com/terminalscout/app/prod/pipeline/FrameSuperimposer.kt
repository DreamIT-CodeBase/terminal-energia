package com.terminalscout.app.prod.pipeline

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect

data class LocatedFrame(
    val bitmap: Bitmap,
    val priceLocations: List<Rect>
)

/**
 * Step 5 of the pipeline: superimpose all frames using the detected price locations.
 */
object FrameSuperimposer {

    fun superimpose(frames: List<LocatedFrame>): Bitmap? {
        if (frames.isEmpty()) return null
        if (frames.size == 1) return frames.first().bitmap

        val reference = frames.maxByOrNull { it.priceLocations.size } ?: frames.first()
        val width = reference.bitmap.width
        val height = reference.bitmap.height

        // Long accumulators so we don't overflow Int across many frames.
        val sumR = LongArray(width * height)
        val sumG = LongArray(width * height)
        val sumB = LongArray(width * height)
        var contributing = 0

        for (frame in frames) {
            val (dx, dy) = estimateOffset(reference.priceLocations, frame.priceLocations)
            val aligned = translate(frame.bitmap, dx, dy, width, height) ?: continue

            val pixels = IntArray(width * height)
            aligned.getPixels(pixels, 0, width, 0, 0, width, height)
            for (i in pixels.indices) {
                val p = pixels[i]
                // Explicitly converted the Int outputs to Long to resolve the array setter compiler mismatch
                sumR[i] += Color.red(p).toLong()
                sumG[i] += Color.green(p).toLong()
                sumB[i] += Color.blue(p).toLong()
            }
            contributing++
            if (aligned !== frame.bitmap) aligned.recycle()
        }

        if (contributing == 0) return reference.bitmap

        val outPixels = IntArray(width * height)
        for (i in outPixels.indices) {
            val r = (sumR[i] / contributing).toInt()
            val g = (sumG[i] / contributing).toInt()
            val b = (sumB[i] / contributing).toInt()
            outPixels[i] = Color.rgb(r, g, b)
        }

        return Bitmap.createBitmap(outPixels, width, height, Bitmap.Config.ARGB_8888)
    }

    /** Average centroid shift between matched (nearest-neighbour) price-location boxes. */
    private fun estimateOffset(reference: List<Rect>, candidate: List<Rect>): Pair<Int, Int> {
        if (reference.isEmpty() || candidate.isEmpty()) return 0 to 0

        var sumDx = 0
        var sumDy = 0
        var matches = 0
        val used = BooleanArray(candidate.size)

        for (ref in reference) {
            val refCx = ref.centerX()
            val refCy = ref.centerY()
            var bestIdx = -1
            var bestDist = Int.MAX_VALUE
            for ((idx, cand) in candidate.withIndex()) {
                if (used[idx]) continue
                val dx = cand.centerX() - refCx
                val dy = cand.centerY() - refCy
                val dist = dx * dx + dy * dy
                if (dist < bestDist) {
                    bestDist = dist
                    bestIdx = idx
                }
            }
            if (bestIdx >= 0) {
                used[bestIdx] = true
                sumDx += candidate[bestIdx].centerX() - refCx
                sumDy += candidate[bestIdx].centerY() - refCy
                matches++
            }
        }

        if (matches == 0) return 0 to 0
        return (sumDx / matches) to (sumDy / matches)
    }

    /** Shifts [bitmap] by (-dx, -dy) onto a canvas of [targetW]x[targetH] so it lines up with the reference. */
    private fun translate(bitmap: Bitmap, dx: Int, dy: Int, targetW: Int, targetH: Int): Bitmap? {
        return try {
            val out = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            canvas.drawBitmap(bitmap, -dx.toFloat(), -dy.toFloat(), null)
            out
        } catch (e: Exception) {
            null
        }
    }
}