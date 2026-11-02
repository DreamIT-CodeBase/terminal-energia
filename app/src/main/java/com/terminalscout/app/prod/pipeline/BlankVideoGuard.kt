package com.terminalscout.app.prod.pipeline

/**
 * Rejects a video only when every usable sample is nearly uniform black or white.
 *
 * Brightness alone is not enough: real night footage can be dark and real daylight footage can be
 * bright. Requiring very low luminance standard deviation keeps textured footage eligible.
 */
internal object BlankVideoGuard {

    data class FrameStats(val meanLuminance: Double, val standardDeviation: Double)

    const val SAMPLE_COUNT = 5
    const val MIN_SUCCESSFUL_SAMPLES = 3
    const val SAMPLE_WIDTH = 160
    const val SAMPLE_HEIGHT = 90
    const val BRIGHTNESS_THRESHOLD = 5.0
    const val STANDARD_DEVIATION_THRESHOLD = 1.5
    private const val MAX_BRIGHTNESS = 255.0

    /** Evenly spaced timestamps in `[0, durationUs)`, with duplicates removed for very short clips. */
    fun sampleTimestamps(durationUs: Long): List<Long> {
        if (durationUs <= 0L) return emptyList()
        val lastTimestamp = durationUs - 1L
        return (0 until SAMPLE_COUNT)
            .map { index -> lastTimestamp * index / (SAMPLE_COUNT - 1) }
            .distinct()
    }

    fun isBlankFrame(stats: FrameStats): Boolean =
        stats.standardDeviation < STANDARD_DEVIATION_THRESHOLD &&
            (stats.meanLuminance < BRIGHTNESS_THRESHOLD ||
                stats.meanLuminance > MAX_BRIGHTNESS - BRIGHTNESS_THRESHOLD)

    /**
     * Missing samples do not count as evidence that a clip is blank. Require at least three decoded
     * frames, then reject only if all decoded samples meet the solid-black/white test.
     */
    fun shouldReject(samples: List<FrameStats>): Boolean =
        samples.size >= MIN_SUCCESSFUL_SAMPLES && samples.all(::isBlankFrame)

    /** Population mean and standard deviation of the sampled bitmap's grayscale luminance. */
    fun measure(bitmapPixels: IntArray): FrameStats {
        require(bitmapPixels.isNotEmpty()) { "bitmapPixels must not be empty" }
        var sum = 0.0
        var sumSquares = 0.0
        for (pixel in bitmapPixels) {
            val luminance =
                0.299 * ((pixel shr 16) and 0xff) +
                    0.587 * ((pixel shr 8) and 0xff) +
                    0.114 * (pixel and 0xff)
            sum += luminance
            sumSquares += luminance * luminance
        }
        val mean = sum / bitmapPixels.size
        val variance = (sumSquares / bitmapPixels.size - mean * mean).coerceAtLeast(0.0)
        return FrameStats(mean, kotlin.math.sqrt(variance))
    }
}
