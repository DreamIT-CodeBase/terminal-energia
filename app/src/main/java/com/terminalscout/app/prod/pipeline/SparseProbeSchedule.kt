package com.terminalscout.app.prod.pipeline

/**
 * Choose one dense-grid timestamp inside every third gap of the base extraction.
 * The selected ticks can be passed as isolated windows to [FrameRefiner.extractWindows].
 */
internal object SparseProbeSchedule {
    fun timestamps(baseTimesUs: List<Long>, durationUs: Long, fps: Double): List<Long> {
        require(fps > 0.0) { "probe fps must be positive" }
        if (baseTimesUs.size < 2 || durationUs <= 0L) return emptyList()

        val stepUs = (1_000_000.0 / fps).toLong().coerceAtLeast(1L)
        return baseTimesUs.sorted().zipWithNext().filterIndexed { index, _ -> index % 3 == 0 }
            .mapNotNull { (before, after) ->
                val midpoint = before + (after - before) / 2
                val tick = ((midpoint + stepUs / 2) / stepUs) * stepUs
                if (tick > before + stepUs / 2 &&
                    tick < after - stepUs / 2 &&
                    tick < durationUs
                ) tick else null
            }
            .distinct()
    }
}
