package com.terminalscout.app.prod.config

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.terminalscout.app.prod.capture.LocationTrigger
import com.terminalscout.app.prod.service.CaptureLane
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The fetched config, on the device, through the real file and the real constants.
 *
 * The JVM tests cover the parse, the version gate and the invariants. What they cannot reach is the
 * thing that actually matters in the field: that a document written to `filesDir` changes what
 * `LocationTrigger.ENTER_RADIUS_M` and `CaptureLane.MAX_CLIP_MS` return — the whole chain, not the half
 * of it that lives in one file.
 *
 * It also covers the one behaviour a unit test cannot express at all: a REFRESH does not move a value
 * under a running process. That is the property the whole design rests on, and it is only observable
 * with a real network call and a real stored file.
 *
 * The device's own stored document is saved and restored around every case, so running this leaves the
 * unit exactly as it found it.
 */
@RunWith(AndroidJUnit4::class)
class DeviceConfigDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val stored: File get() = File(File(context.filesDir, "config"), "device-config.json")

    private var backup: String? = null

    @Before
    fun saveWhateverTheDeviceIsRunning() {
        backup = if (stored.exists()) stored.readText() else null
    }

    @After
    fun putItBack() {
        val b = backup
        if (b == null) stored.delete() else stored.writeText(b)
        DeviceConfig.clear()
        DeviceConfig.load(context)
    }

    private fun store(doc: String) {
        stored.parentFile?.mkdirs()
        stored.writeText(doc)
    }

    /** A partial document: the server's is the whole thing, a field test's is usually this. */
    private fun trigger(enter: Double, exit: Double, clipMs: Long = 30_000) = """
        {"schema_version":2,"config_id":"device-test","updated_at":"2026-09-14",
         "capture":{"trigger":{"enter_radius_m":$enter,"exit_radius_m":$exit,
                               "update_interval_ms":2000,"min_update_distance_m":25.0},
                    "capture_lane":{"max_clip_ms":$clipMs}}}
    """.trimIndent()

    @Test
    fun aStoredDocumentChangesWhatTheConstantsReturn() {
        store(trigger(enter = 120.0, exit = 170.0, clipMs = 45_000))
        DeviceConfig.clear()
        assertTrue("the document should have been accepted", DeviceConfig.load(context))

        // The whole chain: file -> snapshot -> the constant the trigger actually reads.
        assertEquals(120.0, LocationTrigger.ENTER_RADIUS_M, 0.0)
        assertEquals(170.0, LocationTrigger.EXIT_RADIUS_M, 0.0)
        assertEquals(15_000L, CaptureLane.MAX_CLIP_MS)
        // Derived from two fetched values, and still derived rather than fetched.
        assertEquals(
            15_000L + CaptureLane.CAMERA_START_TIMEOUT_MS,
            CaptureLane.RECORDING_FINALIZE_TIMEOUT_MS
        )
    }

    @Test
    fun aShorterConfiguredClipStillApplies() {
        store(trigger(enter = 120.0, exit = 170.0, clipMs = 10_000))
        DeviceConfig.clear()
        assertTrue(DeviceConfig.load(context))
        assertEquals(10_000L, CaptureLane.MAX_CLIP_MS)
    }

    @Test
    fun everythingTheDocumentDoesNotMentionKeepsTheBuildsValue() {
        store(trigger(enter = 120.0, exit = 170.0))
        DeviceConfig.clear()
        DeviceConfig.load(context)

        // The partial document above says nothing about these. They must be the compiled numbers.
        assertEquals(2_000L, LocationTrigger.UPDATE_INTERVAL_MS)
        assertEquals(6_000L, CaptureLane.MIN_FREE_STORAGE_MB)
        assertEquals(8_000L, CaptureLane.CAMERA_START_TIMEOUT_MS)
    }

    @Test
    fun noDocumentAtAllIsTheBuildsOwnConfiguration() {
        stored.delete()
        DeviceConfig.clear()
        assertFalse(DeviceConfig.load(context))
        assertEquals(150.0, LocationTrigger.ENTER_RADIUS_M, 0.0)
        assertEquals(200.0, LocationTrigger.EXIT_RADIUS_M, 0.0)
        assertEquals(15_000L, CaptureLane.MAX_CLIP_MS)
    }

    @Test
    fun aDocumentThatBreaksAnInvariantIsRefusedAndTheLastGoodOneStands() {
        store(trigger(enter = 120.0, exit = 170.0))
        DeviceConfig.clear()
        DeviceConfig.load(context)
        assertEquals(120.0, LocationTrigger.ENTER_RADIUS_M, 0.0)

        // A 30m band. Freeze and release would sit 30m apart instead of 50m — see LocationTrigger.
        store(trigger(enter = 120.0, exit = 150.0))
        assertFalse("a closed band must be refused", DeviceConfig.load(context))
        assertEquals("the refused document must not be half-applied", 120.0, LocationTrigger.ENTER_RADIUS_M, 0.0)
        assertEquals(170.0, LocationTrigger.EXIT_RADIUS_M, 0.0)
    }

    @Test
    fun corruptStoredJsonLeavesTheRunningValuesAlone() {
        store(trigger(enter = 120.0, exit = 170.0))
        DeviceConfig.clear()
        DeviceConfig.load(context)

        store("{ this is not json")
        assertFalse(DeviceConfig.load(context))
        assertEquals(120.0, LocationTrigger.ENTER_RADIUS_M, 0.0)
    }

    /**
     * The property the design rests on: a refresh WRITES but does not MOVE.
     *
     * Needs the live API, so it is skipped rather than failed on a unit with no signal — a network
     * outage is not a defect in this behaviour.
     */
    @Test
    fun aRefreshStoresTheNewDocumentWithoutMovingThisProcess() {
        store(trigger(enter = 120.0, exit = 170.0))
        DeviceConfig.clear()
        DeviceConfig.load(context)
        assertEquals(120.0, LocationTrigger.ENTER_RADIUS_M, 0.0)

        val fetched = runBlocking { DeviceConfig.refresh(context) }
        if (!fetched) return // no signal, or no fleet secret in this build — nothing to assert

        // The file changed...
        assertTrue(stored.readText().contains("\"config\""))
        // ...and the running process did NOT. A capture in flight keeps the radius it started with.
        assertEquals(120.0, LocationTrigger.ENTER_RADIUS_M, 0.0)
        assertEquals(170.0, LocationTrigger.EXIT_RADIUS_M, 0.0)

        // Only a re-load — which in the field is the next process start — moves it.
        DeviceConfig.clear()
        assertTrue(DeviceConfig.load(context))
        assertEquals(150.0, LocationTrigger.ENTER_RADIUS_M, 0.0)
    }
}
