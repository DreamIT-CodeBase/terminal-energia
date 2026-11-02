package com.terminalscout.app.prod.capture

import com.terminalscout.app.prod.upload.StationDirectory

/** Requires two fresh, consecutive fixes at or above 10 km/h before an automatic start. */
internal class MovementGate {
    data class Fix(
        val latitude: Double,
        val longitude: Double,
        val elapsedRealtimeNanos: Long,
        val speedMetresPerSecond: Double?,
        val accuracyMetres: Double?
    )

    private var previous: Fix? = null
    private var movingFixes = 0

    fun reset() {
        previous = null
        movingFixes = 0
    }

    fun observe(fix: Fix, nowNanos: Long): Boolean {
        if (fix.elapsedRealtimeNanos > nowNanos ||
            nowNanos - fix.elapsedRealtimeNanos > MAX_FIX_GAP_NANOS ||
            !fix.latitude.isFinite() || !fix.longitude.isFinite()
        ) {
            reset()
            return false
        }
        val earlier = previous
        val elapsedNanos = earlier?.let { fix.elapsedRealtimeNanos - it.elapsedRealtimeNanos }
        val speed = fix.speedMetresPerSecond ?: if (
            earlier != null && elapsedNanos != null &&
            elapsedNanos > 0 && elapsedNanos <= MAX_FIX_GAP_NANOS
        ) {
            val distance = StationDirectory.distanceMetres(
                earlier.latitude, earlier.longitude, fix.latitude, fix.longitude
            )
            val uncertainty = (earlier.accuracyMetres ?: 0.0) + (fix.accuracyMetres ?: 0.0)
            (distance - uncertainty).coerceAtLeast(0.0) * 1_000_000_000.0 / elapsedNanos
        } else null

        val contiguous = earlier != null && elapsedNanos != null &&
            elapsedNanos > 0 && elapsedNanos <= MAX_FIX_GAP_NANOS
        movingFixes = if (speed != null && speed.isFinite() &&
            speed >= MIN_SPEED_METRES_PER_SECOND &&
            (earlier == null || elapsedNanos != null && elapsedNanos > 0)
        ) {
            if (contiguous) movingFixes + 1 else 1
        } else 0
        previous = fix
        return movingFixes >= 2
    }

    private companion object {
        const val MAX_FIX_GAP_NANOS = 10_000_000_000L
        const val MIN_SPEED_METRES_PER_SECOND = 10.0 / 3.6
    }
}
