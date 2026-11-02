package com.terminalscout.app.prod.capture

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.terminalscout.app.prod.download.DeviceApi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The station sweep against the live API, from the device.
 *
 * The JVM test covers what the parse does with a body. This covers the half that only exists on a real
 * unit: that the fleet secret in this build mints a token, that `GET /v1/stations` answers it, and that
 * what comes back is a list this device could actually trigger on.
 *
 * Read-only. It does not write `location_mapping` — the upsert is the worker's job and this must not be
 * able to disturb `last_capture_time` on a unit that has been recording.
 *
 * Skipped rather than failed when the build carries no fleet secret or the unit has no signal: neither
 * is a defect in the code under test.
 */
@RunWith(AndroidJUnit4::class)
class SystemApiStationsDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun theLiveEndpointServesStationsThisDeviceCanUse() {
        if (!DeviceApi.isConfigured) return

        val stations = runBlocking { SystemApiStations.fetchAll(context) } ?: return

        // Estonia has hundreds of forecourts; a handful means a truncated or filtered answer.
        assertTrue("expected a country-sized list, got ${stations.size}", stations.size > 300)

        // Every row has to be usable by the trigger, which compares coordinates and nothing else.
        assertTrue(stations.all { it.id.isNotBlank() })
        assertTrue(stations.all { it.latitude != 0.0 && it.longitude != 0.0 })
        // Estonia's bounding box, roughly. A row outside it would be a coordinate swap or a bad parse.
        assertTrue(stations.all { it.latitude in 57.0..60.0 && it.longitude in 21.0..29.0 })
        // The id is the primary key that carries last_capture_time across sweeps.
        assertTrue("duplicate ids", stations.map { it.id }.toSet().size == stations.size)

        // The hand-surveyed sites arrive merged in, under the ids the retired APK seed used — which is
        // why that seed could be deleted rather than merely stopped.
        assertTrue(stations.any { it.id.startsWith("surveyed:") })
        assertNotNull(stations.firstOrNull { it.brand.isNotBlank() })
    }
}
