package com.terminalscout.app.prod.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MovementGateTest {
    private fun fix(
        latitude: Double = 51.0,
        atSeconds: Long,
        speed: Double? = null,
        accuracy: Double? = 2.0
    ) = MovementGate.Fix(
        latitude, 0.0, atSeconds * 1_000_000_000L, speed, accuracy
    )

    private fun observe(gate: MovementGate, fix: MovementGate.Fix): Boolean =
        gate.observe(fix, fix.elapsedRealtimeNanos + 100_000_000L)

    @Test
    fun `two consecutive fixes at threshold allow capture`() {
        val gate = MovementGate()
        val threshold = 10.0 / 3.6
        assertFalse(observe(gate, fix(atSeconds = 1, speed = threshold)))
        assertTrue(observe(gate, fix(atSeconds = 3, speed = threshold)))
    }

    @Test
    fun `stopping or moving slowly resets confirmation`() {
        val gate = MovementGate()
        assertFalse(observe(gate, fix(atSeconds = 1, speed = 5.0)))
        assertFalse(observe(gate, fix(atSeconds = 3, speed = 0.0)))
        assertFalse(observe(gate, fix(atSeconds = 5, speed = 5.0)))
        assertTrue(observe(gate, fix(atSeconds = 7, speed = 5.0)))
    }

    @Test
    fun `missing speed is derived from fresh position fixes`() {
        val gate = MovementGate()
        assertFalse(observe(gate, fix(atSeconds = 1)))
        assertFalse(observe(gate, fix(latitude = 51.00027, atSeconds = 3)))
        assertTrue(observe(gate, fix(latitude = 51.00054, atSeconds = 5)))
    }

    @Test
    fun `position drift within location accuracy does not count as travel`() {
        val gate = MovementGate()
        assertFalse(observe(gate, fix(atSeconds = 1, accuracy = 15.0)))
        assertFalse(observe(gate, fix(latitude = 51.00015, atSeconds = 3, accuracy = 15.0)))
        assertFalse(observe(gate, fix(latitude = 51.00030, atSeconds = 5, accuracy = 15.0)))
    }

    @Test
    fun `old and out of order fixes cannot confirm movement`() {
        val gate = MovementGate()
        assertFalse(observe(gate, fix(atSeconds = 1, speed = 5.0)))
        assertFalse(gate.observe(fix(atSeconds = 3, speed = 5.0), 20_000_000_000L))
        assertFalse(observe(gate, fix(atSeconds = 5, speed = 5.0)))
        assertFalse(observe(gate, fix(atSeconds = 4, speed = 5.0)))
        assertFalse(observe(gate, fix(atSeconds = 6, speed = 5.0)))
        assertTrue(observe(gate, fix(atSeconds = 8, speed = 5.0)))
    }

    @Test
    fun `long gap discards the prior moving fix`() {
        val gate = MovementGate()
        assertFalse(observe(gate, fix(atSeconds = 1, speed = 5.0)))
        assertFalse(observe(gate, fix(atSeconds = 20, speed = 5.0)))
        assertTrue(observe(gate, fix(atSeconds = 22, speed = 5.0)))
    }

    @Test
    fun `reported stationary speed is not overridden by position jitter`() {
        val gate = MovementGate()
        assertFalse(observe(gate, fix(atSeconds = 1, speed = 0.0)))
        assertFalse(observe(gate, fix(latitude = 51.00027, atSeconds = 3, speed = 0.0)))
        assertFalse(observe(gate, fix(latitude = 51.00054, atSeconds = 5, speed = 0.0)))
    }

    @Test
    fun `reset requires a fresh pair before another capture`() {
        val gate = MovementGate()
        assertFalse(observe(gate, fix(atSeconds = 1, speed = 5.0)))
        assertTrue(observe(gate, fix(atSeconds = 3, speed = 5.0)))
        gate.reset()
        assertFalse(observe(gate, fix(atSeconds = 5, speed = 5.0)))
        assertTrue(observe(gate, fix(atSeconds = 7, speed = 5.0)))
    }
}
