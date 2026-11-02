package com.terminalscout.app.prod.capture

/**
 * Tracks the closest observed approach to a station, then confirms 50 m of travel away.
 * Three increasing station-distance fixes (two increases) must span at least 10 m.
 */
internal class DepartureTrend {
    private var closestMetres: Double? = null
    private var previousMetres: Double? = null
    private var riseStartMetres: Double? = null
    private var consecutiveIncreases = 0
    private var travelMetres = 0.0

    fun observe(metres: Double, stepMetres: Double): Boolean {
        val closest = closestMetres
        if (closest == null || metres < closest) {
            closestMetres = metres
            travelMetres = 0.0
            consecutiveIncreases = 0
            riseStartMetres = null
            previousMetres = metres
            return false
        }
        travelMetres += stepMetres
        val previous = previousMetres
        if (previous != null && metres > previous) {
            if (consecutiveIncreases == 0) riseStartMetres = previous
            consecutiveIncreases++
        } else {
            consecutiveIncreases = 0
            riseStartMetres = null
        }
        previousMetres = metres
        return travelMetres >= 50.0 &&
            consecutiveIncreases >= 2 &&
            metres - (riseStartMetres ?: metres) >= 10.0
    }
}
