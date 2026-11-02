package com.terminalscout.app.prod.config

import org.json.JSONObject
import com.terminalscout.app.prod.pipeline.NightFilterConfig
import com.terminalscout.app.prod.pipeline.ImagePrepStage
import com.terminalscout.app.prod.service.CaptureLane
import com.terminalscout.app.prod.capture.LocationTrigger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What the device is willing to believe `GET /v1/config` said, and what it does when it does not.
 *
 * Two rules run through all of it:
 *
 *  1. **A value the config cannot supply is the build's own.** Every accessor takes the compiled
 *     constant as its fallback, so a device with no document, an unreadable one, or a refused one runs
 *     exactly the build it is running. Any test that leaves a value undefined must still get a number.
 *  2. **A document is applied whole or not at all.** The invariants below used to be held by the
 *     compiler — moving the constants into a file moved that responsibility here, and clamping one
 *     value would leave the fleet running a configuration nobody wrote.
 */
class DeviceConfigTest {

    /** Expected config fixture; its capture settings must match the compiled capture contract. */
    private val expectedConfig: String by lazy {
        File("src/test/resources/device-config.default.json").readText()
    }

    @After
    fun tearDown() = DeviceConfig.clear()

    // ---- the fallback contract ----------------------------------------------------------------- //

    @Test
    fun `with no document every read is the build's own value`() {
        DeviceConfig.clear()
        assertEquals(80.0, DeviceConfig.double("capture.trigger.enter_radius_m", 80.0), 0.0)
        assertEquals(130.0, DeviceConfig.double("capture.trigger.exit_radius_m", 130.0), 0.0)
        assertEquals(1_500L, DeviceConfig.long("capture.trigger.update_interval_ms", 1_500L))
        assertEquals(25_000L, CaptureLane.MAX_CLIP_MS)
        val night = NightFilterConfig()
        assertEquals(0.40, night.otsuBlackPoint, 0.0)
        assertEquals(0.80, night.otsuGamma, 0.0)
        assertEquals(1, night.erodeIters)
        assertEquals(0.80, ImagePrepStage.GHOST_BLACK_POINT, 0.0)
        assertEquals(1.30, ImagePrepStage.GHOST_GAMMA, 0.0)
        assertTrue(DeviceConfig.boolean("board_prep.image_prep.llm_layout_enabled", true))
        assertFalse(DeviceConfig.isLoaded)
    }

    @Test
    fun `a path the document does not have falls back`() {
        assertTrue(DeviceConfig.apply(expectedConfig, "test"))
        // Silently, and on purpose: a build that reads a key the document dropped keeps working.
        assertEquals(7.5, DeviceConfig.double("capture.trigger.no_such_key", 7.5), 0.0)
    }

    @Test
    fun `a value of the wrong type falls back rather than throwing`() {
        val doc = """{"schema_version":2,"capture":{"trigger":{"enter_radius_m":"soon"}}}"""
        assertTrue(DeviceConfig.apply(doc, "test"))
        assertEquals(80.0, DeviceConfig.double("capture.trigger.enter_radius_m", 80.0), 0.0)
    }

    // ---- the expected config document ---------------------------------------------------------- //

    @Test
    fun `the expected config document is accepted and its values are in force`() {
        assertTrue(DeviceConfig.apply(expectedConfig, "test"))
        assertTrue(DeviceConfig.isLoaded)
        assertEquals(80.0, DeviceConfig.double("capture.trigger.enter_radius_m", -1.0), 0.0)
        assertEquals(130.0, DeviceConfig.double("capture.trigger.exit_radius_m", -1.0), 0.0)
        assertEquals(1_500L, DeviceConfig.long("capture.trigger.update_interval_ms", -1L))
        assertEquals(25_000L, DeviceConfig.long("capture.capture_lane.max_clip_ms", -1L))
        assertEquals(25_000L, CaptureLane.MAX_CLIP_MS)
        assertEquals(
            25_000L + CaptureLane.CAMERA_START_TIMEOUT_MS,
            CaptureLane.RECORDING_FINALIZE_TIMEOUT_MS
        )
        assertEquals(15, DeviceConfig.int("extraction.samples_per_second", -1))
        // Retained from the config response fixture: the escalation floor moved off 0.001, the region
        // cap off 5, and the reader's reasoning allowance off 12k.
        assertEquals(listOf(0.08, 0.01), DeviceConfig.doubleList("board_prep.image_prep.conf_escalation", emptyList()))
        assertEquals(7, DeviceConfig.int("board_prep.image_prep.max_regions", -1))
        assertEquals(50_000, DeviceConfig.int("ocr.luna_ocr.reasoning_allowance", -1))
        // Night settings are intentionally unchanged by this capture-only config change.
        val night = NightFilterConfig()
        assertEquals(0.60, night.otsuBlackPoint, 0.0)
        assertEquals(1.0, night.otsuGamma, 0.0)
        assertEquals(2, night.erodeIters)
        assertEquals(0.60, ImagePrepStage.GHOST_BLACK_POINT, 0.0)
        assertEquals(0.70, ImagePrepStage.GHOST_GAMMA, 0.0)
    }

    @Test
    fun `a configured clip limit above the fallback is preserved`() {
        assertTrue(DeviceConfig.apply(
            """{"schema_version":2,"capture":{"capture_lane":{"max_clip_ms":30000}}}""",
            "test"
        ))
        assertEquals(30_000L, CaptureLane.MAX_CLIP_MS)
        assertEquals(
            30_000L + CaptureLane.CAMERA_START_TIMEOUT_MS,
            CaptureLane.RECORDING_FINALIZE_TIMEOUT_MS
        )
    }

    @Test
    fun `the envelope and the bare document load the same way`() {
        // The server wraps the document in {config_id, etag, config:{…}}; a file written by hand for a
        // field test is just the document. Both have to work, or the field copy is untestable.
        val bare = JSONObject(expectedConfig).getJSONObject("config").toString()
        assertTrue(DeviceConfig.apply(bare, "bare"))
        assertEquals(80.0, DeviceConfig.double("capture.trigger.enter_radius_m", -1.0), 0.0)
    }

    // ---- the version gate ---------------------------------------------------------------------- //

    @Test
    fun `a document at an unknown schema is refused`() {
        // A v3 may have MOVED a path this build reads, and a silently-missing path is a silently
        // different pipeline. Refusing is the point of having a version.
        assertFalse(DeviceConfig.apply("""{"schema_version":3,"capture":{}}""", "test"))
        assertFalse(DeviceConfig.isLoaded)
    }

    @Test
    fun `a document with no schema at all is refused`() {
        assertFalse(DeviceConfig.apply("""{"capture":{"trigger":{"enter_radius_m":150.0}}}""", "test"))
    }

    @Test
    fun `an unparseable body is refused`() {
        assertFalse(DeviceConfig.apply("<html>502</html>", "test"))
    }

    // ---- the relationships required for coherent behavior ------------------------------------- //

    private fun withTrigger(enter: Double, exit: Double, interval: Long = 1_500): JSONObject =
        JSONObject(
            """{"schema_version":2,"capture":{"trigger":{"enter_radius_m":$enter,
               "exit_radius_m":$exit,"update_interval_ms":$interval,"min_update_distance_m":25.0}}}"""
        )

    @Test
    fun `the expected config breaks no invariant`() {
        val doc = JSONObject(expectedConfig).getJSONObject("config")
        assertEquals(emptyList<String>(), DeviceConfig.validate(doc))
    }

    @Test
    fun `an exit radius inside the enter radius is refused`() {
        assertTrue(DeviceConfig.validate(withTrigger(130.0, 80.0)).isNotEmpty())
    }

    @Test
    fun `exit must remain outside enter but the band width is api controlled`() {
        assertTrue(DeviceConfig.validate(withTrigger(80.0, 80.0)).isNotEmpty())
        assertEquals(emptyList<String>(), DeviceConfig.validate(withTrigger(80.0, 300.0)))
        assertEquals(emptyList<String>(), DeviceConfig.validate(withTrigger(80.0, 130.0)))
    }

    @Test
    fun `api owns capture interval even when it exceeds prior local assumptions`() {
        val doc = withTrigger(80.0, 130.0, interval = 2_000)
        assertEquals(emptyList<String>(), DeviceConfig.validate(doc))
        assertTrue(DeviceConfig.apply(doc.toString(), "test"))
        assertEquals(2_000L, LocationTrigger.UPDATE_INTERVAL_MS)
    }

    @Test
    fun `weights that do not sum to one are refused`() {
        val clarity = JSONObject(
            """{"schema_version":2,"detection":{"clarity_gate":
               {"sharpness_weight":0.9,"contrast_weight":0.4}}}"""
        )
        assertTrue(DeviceConfig.validate(clarity).isNotEmpty())

        val tracker = JSONObject(
            """{"schema_version":2,"detection":{"tracker":
               {"w_iou":0.4,"w_centroid":0.2,"w_sig":0.3,"w_aspect":0.3}}}"""
        )
        assertTrue(DeviceConfig.validate(tracker).isNotEmpty())
    }

    @Test
    fun `storage floors the wrong way round are refused`() {
        // Extraction would start only to abort part-way through, leaving scratch frames behind.
        val doc = JSONObject(
            """{"schema_version":2,"extraction":{"min_free_to_start_mb":500,"min_free_reserve_mb":1000}}"""
        )
        assertTrue(DeviceConfig.validate(doc).isNotEmpty())
    }

    @Test
    fun `a detector ladder that does not loosen is refused`() {
        val doc = JSONObject(
            """{"schema_version":2,"detection":{"detector":
               {"conf_thres":0.38,"fallback_conf":0.5,"nopass_conf":0.01}}}"""
        )
        assertTrue(DeviceConfig.validate(doc).isNotEmpty())
    }

    @Test
    fun `a refused document leaves the previous one in force`() {
        assertTrue(DeviceConfig.apply(expectedConfig, "good"))
        assertFalse(DeviceConfig.apply("""{"schema_version":3}""", "bad"))
        assertEquals(80.0, DeviceConfig.double("capture.trigger.enter_radius_m", -1.0), 0.0)
    }

    // ---- the paths the code actually reads ----------------------------------------------------- //

    /**
     * Every `DeviceConfig.x("path", …)` in the app's source must exist in the expected config.
     *
     * This is the test that pays for string paths. A typo falls back silently at runtime — the safe
     * direction, but an invisible one, and a constant that quietly stops tracking the config is exactly
     * the bug this whole mechanism is supposed to prevent. Reading the source is crude and it is the
     * only place that knows both halves.
     */
    @Test
    fun `every path read by the app exists in the expected config`() {
        val doc = JSONObject(expectedConfig).getJSONObject("config")
        val call = Regex("""DeviceConfig\.(?:double|float|int|long|boolean|doubleList|floatArray|longArray)\(\s*"([a-z0-9_.]+)"""")
        val paths = File("src/main/java/com/terminalscout/app/prod")
            .walkTopDown()
            .filter { it.extension == "kt" }
            .flatMap { call.findAll(it.readText()) }
            .map { it.groupValues[1] }
            .toSortedSet()

        assertTrue("no DeviceConfig reads found — the scan is broken, not the code", paths.size > 100)

        val missing = paths.filter { path ->
            var node: Any? = doc
            for (part in path.split('.')) {
                node = (node as? JSONObject)?.opt(part) ?: return@filter true
            }
            false
        }
        assertEquals("paths read by the app but absent from the document", emptyList<String>(), missing)
    }
}
