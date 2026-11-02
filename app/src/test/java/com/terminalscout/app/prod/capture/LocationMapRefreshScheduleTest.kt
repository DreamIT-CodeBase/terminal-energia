package com.terminalscout.app.prod.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Pins the 02:00 Europe/Tallinn arithmetic behind `fetchLocationMap`'s daily schedule.
 *
 * Worth testing rather than eyeballing, because the interesting inputs are the two days a year when
 * the wall clock says 02:00 twice or not at all — and Estonia's DST switches happen at 03:00 local,
 * which is close enough to 02:00 that "it obviously works" is not an argument.
 */
class LocationMapRefreshScheduleTest {

    private val tallinn = TimeZone.getTimeZone("Europe/Tallinn")

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        Calendar.getInstance(tallinn).apply {
            clear()
            set(y, mo - 1, d, h, mi, 0)
        }.timeInMillis

    private fun hourIn(zone: TimeZone, atMs: Long): Int =
        Calendar.getInstance(zone).apply { timeInMillis = atMs }.get(Calendar.HOUR_OF_DAY)

    private fun stampOf(atMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
            .apply { timeZone = tallinn }
            .format(java.util.Date(atMs))

    @Test
    fun `next refresh is always in the future`() {
        val samples = listOf(
            at(2026, 3, 14, 1, 59),
            at(2026, 3, 14, 2, 0),
            at(2026, 3, 14, 2, 1),
            at(2026, 3, 14, 13, 30),
            at(2026, 3, 14, 23, 59)
        )
        samples.forEach { now ->
            val next = LocationMapRefreshScheduler.nextRefreshAfter(now)
            assertTrue("next=${stampOf(next)} must be after now=${stampOf(now)}", next > now)
        }
    }

    @Test
    fun `next refresh lands on 02 00 Tallinn`() {
        val samples = listOf(
            at(2026, 1, 15, 9, 0),
            at(2026, 6, 15, 9, 0),
            at(2026, 11, 15, 9, 0)
        )
        samples.forEach { now ->
            val next = LocationMapRefreshScheduler.nextRefreshAfter(now)
            assertEquals("landed on ${stampOf(next)}", 2, hourIn(tallinn, next))
        }
    }

    @Test
    fun `from midday the next refresh is the following morning, under 24h away`() {
        val now = at(2026, 6, 15, 12, 0)
        val next = LocationMapRefreshScheduler.nextRefreshAfter(now)
        val hoursAway = (next - now) / 3_600_000.0
        assertTrue("expected under 24h, got $hoursAway", hoursAway in 1.0..24.0)
        assertEquals(2, hourIn(tallinn, next))
    }

    @Test
    fun `just before 02 00 the next refresh is minutes away, not a day`() {
        val now = at(2026, 6, 15, 1, 55)
        val next = LocationMapRefreshScheduler.nextRefreshAfter(now)
        val minutesAway = (next - now) / 60_000
        assertTrue("expected ~5 minutes, got $minutesAway", minutesAway in 1..10)
    }

    @Test
    fun `spring forward — the day Estonia loses an hour still resolves to 02 00`() {
        // Estonia moves 03:00 -> 04:00 on the last Sunday of March (2026-03-29).
        val next = LocationMapRefreshScheduler.nextRefreshAfter(at(2026, 3, 28, 12, 0))
        assertEquals(2, hourIn(tallinn, next))
        assertTrue(next > at(2026, 3, 28, 12, 0))
    }

    @Test
    fun `autumn back — the day Estonia repeats an hour still resolves to 02 00`() {
        // Estonia moves 04:00 -> 03:00 on the last Sunday of October (2026-10-25).
        val next = LocationMapRefreshScheduler.nextRefreshAfter(at(2026, 10, 24, 12, 0))
        assertEquals(2, hourIn(tallinn, next))
        assertTrue(next > at(2026, 10, 24, 12, 0))
    }

    @Test
    fun `stepping day by day across a whole year never stalls or skips`() {
        var now = at(2026, 1, 1, 12, 0)
        val end = at(2026, 12, 31, 12, 0)
        var fires = 0
        while (now < end) {
            val next = LocationMapRefreshScheduler.nextRefreshAfter(now)
            assertTrue("stalled at ${stampOf(now)}", next > now)
            assertEquals("off-hour at ${stampOf(next)}", 2, hourIn(tallinn, next))
            now = next
            fires++
            assertTrue("runaway after $fires iterations", fires < 400)
        }
        // 365 days, one fire each, give or take the boundaries.
        assertTrue("expected ~364 fires, got $fires", fires in 360..367)
    }

    @Test
    fun `the schedule is an hour clear of the existing 03 00 sun-times job`() {
        // Two grid sweeps on one radio wake is the thing this separation avoids; if either hour moves,
        // this is the test that says so.
        val next = LocationMapRefreshScheduler.nextRefreshAfter(at(2026, 6, 15, 12, 0))
        assertEquals(2, hourIn(tallinn, next))
    }
}
