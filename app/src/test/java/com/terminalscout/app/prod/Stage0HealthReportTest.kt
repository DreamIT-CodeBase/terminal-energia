package com.terminalscout.app.prod

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the watchdog is allowed to conclude from a health report.
 *
 * The bug these pin is a one-word one: `WatchdogReceiver` asked `report.healthy` and returned when it
 * was true, and `healthy` says nothing about the video processing lane. So a unit whose poller was dead
 * — or alive and wedged inside one clip — was reported as "stage 0 healthy" by every alarm, every three
 * minutes, forever. Clips piled up on the device and nothing anywhere disagreed.
 *
 * `healthy` still means what it always meant, because the home panel's headline depends on it: could
 * this unit record a forecourt it drove past. [Stage0Health.Report.needsRepair] is the question the
 * watchdog asks now, and it covers both lanes.
 */
class Stage0HealthReportTest {

    private fun report(
        serviceRunning: Boolean = true,
        triggerAttached: Boolean = true,
        stopMonitorWatching: Boolean = true,
        pollerRunning: Boolean = true,
        pollerStalled: Boolean = false,
        pollerSilentMs: Long = 0L,
        stationsMapped: Int = 349
    ) = Stage0Health.Report(
        serviceRunning = serviceRunning,
        triggerAttached = triggerAttached,
        stopMonitorWatching = stopMonitorWatching,
        pollerRunning = pollerRunning,
        pollerStalled = pollerStalled,
        pollerSilentMs = pollerSilentMs,
        stationsMapped = stationsMapped
    )

    @Test
    fun `a unit with everything up needs no repair`() {
        val r = report()
        assertTrue(r.healthy)
        assertTrue(r.processing)
        assertFalse(r.needsRepair)
        assertTrue(r.summary().startsWith("healthy"))
    }

    @Test
    fun `a dead poller does not make the unit unable to record, and still needs repair`() {
        val r = report(pollerRunning = false)
        // The distinction that has to survive: recording is unaffected, so the panel must not claim the
        // unit cannot capture — but something is broken and the watchdog has to act on it.
        assertTrue("a backlog does not stop the camera", r.healthy)
        assertFalse(r.processing)
        assertTrue("this used to return false and the alarm went back to sleep", r.needsRepair)
        assertTrue(r.summary().contains("video processing DOWN"))
    }

    @Test
    fun `a stalled poller needs repair even though it is 'running'`() {
        // The fault `pollerRunning` cannot express: the loop is claimed, its state is PROCESSING, and it
        // has not moved in an hour.
        val r = report(pollerStalled = true, pollerSilentMs = 61 * 60 * 1000L)
        assertTrue(r.pollerRunning)
        assertFalse(r.processing)
        assertTrue(r.needsRepair)
        assertTrue(r.summary().contains("STALLED"))
        assertTrue("the log line should say how long", r.summary().contains("61 min"))
    }

    @Test
    fun `the capture chain is still named first when both lanes are broken`() {
        // Order matters in one line of log: a unit that cannot record has the worse problem, and the
        // summary names the first thing that breaks the chain.
        val r = report(triggerAttached = false, pollerRunning = false)
        assertFalse(r.healthy)
        assertFalse(r.processing)
        assertTrue(r.needsRepair)
        assertTrue(r.summary().contains("location trigger NOT WATCHING"))
    }

    @Test
    fun `nothing about the processing lane is claimed while the service is down`() {
        // With no service there is no lane to repair, and the fix is the same one the capture fault
        // needs: bring the app forward. `processing` must not report true off the back of a stale flag.
        val r = report(serviceRunning = false)
        assertFalse(r.processing)
        assertTrue(r.needsRepair)
        assertTrue(r.summary().contains("capture service DOWN"))
    }

    @Test
    fun `servicesUp counts the service alongside the periodic jobs`() {
        // Unrelated to the above, and pinned because the panel's n/5 reads from it: the jobs count is
        // carried separately from the service's own liveness and must not double-count it.
        val r = Stage0Health.Report(
            serviceRunning = true,
            triggerAttached = true,
            stopMonitorWatching = true,
            pollerRunning = true,
            stationsMapped = 1,
            jobsArmed = 4
        )
        assertTrue(r.servicesUp == 5)
    }
}
