package com.terminalscout.app.prod.upload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Calendar

/**
 * The 03:00 Europe/Tallinn boundary arithmetic, the timestamp parser, and the file round trip.
 *
 * Worth a test rather than a read-through: this is `Calendar` maths at `minSdk 24` (no `java.time`), the
 * DST cases are the ones that would silently misfire twice a year, and every expected epoch below was
 * computed independently with Python's `zoneinfo` rather than from the code under test.
 *
 * A plain JVM test: `android.util.Log` is neutralised by `unitTests.isReturnDefaultValues`, the store's
 * file API takes a plain directory through [AzureMapsStore.initAt] so no `Context` is needed, and the
 * real `org.json` is on the unit-test classpath (the stub in `android.jar` parses nothing).
 */
class AzureMapsStoreTest {

    // 2026-08-23, Estonian summer time (UTC+3).
    private val aug23At1200 = 1_787_475_600_000L
    private val aug23At0300 = 1_787_443_200_000L
    private val aug22At0300 = 1_787_356_800_000L
    private val aug24At0300 = 1_787_529_600_000L
    private val aug23At0130 = 1_787_437_800_000L

    /** Noon on the two 2026 EU switch days: 2026-03-29 (EEST) and 2026-10-25 (EET). */
    private val springNoon = 1_774_774_800_000L
    private val autumnNoon = 1_792_922_400_000L

    @Test
    fun `midday belongs to the service day that started at 0300 the same morning`() {
        assertEquals(aug23At0300, AzureMapsStore.serviceDayStart(aug23At1200))
        assertEquals(aug24At0300, AzureMapsStore.nextRefreshAfter(aug23At1200))
    }

    @Test
    fun `before 0300 still belongs to the previous day`() {
        assertEquals(aug22At0300, AzureMapsStore.serviceDayStart(aug23At0130))
        assertEquals(aug23At0300, AzureMapsStore.nextRefreshAfter(aug23At0130))
    }

    @Test
    fun `0300 exactly starts the new service day`() {
        assertEquals(aug23At0300, AzureMapsStore.serviceDayStart(aug23At0300))
        assertEquals(aug24At0300, AzureMapsStore.nextRefreshAfter(aug23At0300))
    }

    @Test
    fun `the boundary is its own service day start`() {
        val start = AzureMapsStore.serviceDayStart(aug23At1200)
        assertEquals(start, AzureMapsStore.serviceDayStart(start))
    }

    @Test
    fun `now always sits inside its own service day, DST switches included`() {
        // Every half hour across three windows: a fortnight of plain summer, and four days centred on
        // each 2026 switch. This is the invariant the cache actually depends on — a gap or an overlap
        // would mean a day with no refresh or two.
        for (centre in listOf(aug23At1200, springNoon, autumnNoon)) {
            var t = centre - 7L * 24 * 3600 * 1000
            val end = centre + 7L * 24 * 3600 * 1000
            while (t < end) {
                val start = AzureMapsStore.serviceDayStart(t)
                val next = AzureMapsStore.nextRefreshAfter(t)
                assertTrue("start ${AzureMapsStore.stamp(start)} after now ${AzureMapsStore.stamp(t)}", start <= t)
                assertTrue("next ${AzureMapsStore.stamp(next)} not after now ${AzureMapsStore.stamp(t)}", next > t)
                t += 30 * 60 * 1000L
            }
        }
    }

    @Test
    fun `a switch day is still one service day, its boundary within an hour of 0300`() {
        // The exact instant is NOT pinned on these two days: 03:00 Tallinn is skipped in spring and
        // happens twice in autumn, and Calendar resolves both leniently — spring lands on 03:00 EEST
        // (an hour early in pre-switch terms), which is why the day measures 24 hours rather than 23.
        // Immaterial for a daily refresh; what matters is that there is exactly one boundary and it is
        // in the small hours, so the refresh never drifts into working time.
        for ((label, noon) in listOf("spring" to springNoon, "autumn" to autumnNoon)) {
            val start = AzureMapsStore.serviceDayStart(noon)
            val hours = (AzureMapsStore.nextRefreshAfter(noon) - start) / 3_600_000
            assertTrue("$label day length ${hours}h", hours in 23..25)

            val cal = Calendar.getInstance(AzureMapsStore.ZONE).apply { timeInMillis = start }
            assertTrue(
                "$label boundary at ${AzureMapsStore.stamp(start)}",
                cal.get(Calendar.HOUR_OF_DAY) in 2..4
            )
            assertEquals("$label boundary minute", 0, cal.get(Calendar.MINUTE))
        }
    }

    @Test
    fun `delay to the next refresh is the gap to the next 0300`() {
        assertEquals(
            aug24At0300 - aug23At1200,
            AzureMapsStore.nextRefreshAfter(aug23At1200) - aug23At1200
        )
    }

    @Test
    fun `azure timestamps parse, offset respected`() {
        assertEquals(1_787_451_154_000L, AzureMapsStore.parseIso8601("2026-08-23T05:12:34+03:00"))
        assertEquals(1_787_461_954_000L, AzureMapsStore.parseIso8601("2026-08-23T05:12:34Z"))
        assertNull(AzureMapsStore.parseIso8601("not a timestamp"))
        assertNull(AzureMapsStore.parseIso8601(null))
    }

    /**
     * The seven-digit fraction the live endpoint actually returns. `SSS` would read `1119448` as that
     * many milliseconds and put sunrise eighteen minutes late, so this pins the truncation instead.
     */
    @Test
    fun `a seven digit fraction is discarded, not added as millis`() {
        assertEquals(
            1_787_453_855_000L,
            AzureMapsStore.parseIso8601("2026-08-23T05:57:35.1119448+03:00")
        )
        assertEquals(
            1_787_507_275_000L,
            AzureMapsStore.parseIso8601("2026-08-23T20:47:55.4717385+03:00")
        )
    }

    @Test
    fun `daylight window brackets the day`() {
        val stored = storedFromLiveBody()
        assertEquals(180, stored.timezone?.effectiveOffsetMinutes)
        assertNotNull(stored.sunriseAtMs)
        assertEquals(true, stored.isDaylightAt(aug23At1200))
        assertEquals(false, stored.isDaylightAt(aug23At0300))
        assertEquals(null, stored.copy(timezone = null).isDaylightAt(aug23At1200))
    }

    /**
     * The whole point of the file being the source of truth: what one process writes, a later one reads
     * back with the fields intact — including the envelope's service-day tag, which is what stops a
     * second call being made the same day.
     */
    @Test
    fun `write then read back, in a fresh directory`() {
        AzureMapsStore.initAt(tempDir())
        assertTrue(AzureMapsStore.path.endsWith(AzureMapsStore.FILE_NAME))
        assertNull(AzureMapsStore.stored())

        val saved = AzureMapsStore.save(
            AzureMapsStore.buildDocument(
                serviceDayStartMs = aug23At0300,
                fetchedAtMs = aug23At0300 + 10_000,
                query = AzureMapsTimezone.DEFAULT_QUERY,
                response = JSONObject(LIVE_BODY)
            )
        )
        assertTrue("save() reported failure", saved)

        val stored = AzureMapsStore.stored()
        assertNotNull(stored)
        assertEquals(aug23At0300, stored!!.serviceDayStartMs)
        assertEquals(aug23At0300 + 10_000, stored.fetchedAtMs)
        assertEquals(AzureMapsTimezone.DEFAULT_QUERY, stored.query)
        assertEquals("Europe/Tallinn", stored.timezone?.timezoneId)
        assertEquals("2026-08-23T05:57:35.1119448+03:00", stored.sunrise)

        // The response is kept verbatim, so a field this build ignores is still on disk.
        assertEquals("2025b", AzureMapsStore.json()?.getString("Version"))
        assertTrue(AzureMapsStore.rawJson!!.contains("Sunset"))
    }

    @Test
    fun `only today's file counts as current`() {
        AzureMapsStore.initAt(tempDir())
        AzureMapsStore.save(
            AzureMapsStore.buildDocument(
                serviceDayStartMs = aug23At0300,
                fetchedAtMs = aug23At0300,
                query = AzureMapsTimezone.DEFAULT_QUERY,
                response = JSONObject(LIVE_BODY)
            )
        )
        // Any instant inside 2026-08-23's service day: current. The day after: stale, so the next
        // ensureFresh fetches instead of serving it.
        assertNotNull(AzureMapsStore.current(aug23At1200))
        assertNotNull(AzureMapsStore.current(aug23At0300))
        assertNull(AzureMapsStore.current(aug24At0300))
        assertNull(AzureMapsStore.current(aug23At0130))

        // ...and stored() still hands the stale answer back, which is what keeps yesterday's sunrise
        // usable when today's fetch cannot happen.
        assertNotNull(AzureMapsStore.stored())
    }

    @Test
    fun `a corrupt file reads as absent rather than throwing`() {
        AzureMapsStore.initAt(tempDir())
        File(AzureMapsStore.path).writeText("{ this is not json")
        assertNull(AzureMapsStore.stored())
        assertNull(AzureMapsStore.json())
        assertNull(AzureMapsStore.rawJson)
    }

    @Test
    fun `clear removes the file`() {
        AzureMapsStore.initAt(tempDir())
        AzureMapsStore.save(
            AzureMapsStore.buildDocument(
                aug23At0300, aug23At0300, AzureMapsTimezone.DEFAULT_QUERY, JSONObject(LIVE_BODY)
            )
        )
        assertNotNull(AzureMapsStore.stored())
        AzureMapsStore.clear()
        assertNull(AzureMapsStore.stored())
    }

    /** A private directory per test, so the shared `object`'s path never crosses between them. */
    private fun tempDir(): File =
        Files.createTempDirectory("azure-maps-test").toFile().apply { deleteOnExit() }

    /** The live body, read back through the same envelope the store writes. */
    private fun storedFromLiveBody(): AzureMapsStore.Stored {
        AzureMapsStore.initAt(tempDir())
        AzureMapsStore.save(
            AzureMapsStore.buildDocument(
                serviceDayStartMs = aug23At0300,
                fetchedAtMs = aug23At0300,
                query = AzureMapsTimezone.DEFAULT_QUERY,
                response = JSONObject(LIVE_BODY)
            )
        )
        return AzureMapsStore.stored()!!
    }

    private companion object {
        const val LIVE_BODY = """
            {"Version":"2025b","ReferenceUtcTimestamp":"2026-08-23T15:09:06.4007064Z","TimeZones":[
              {"Id":"Europe/Tallinn",
               "Names":{"ISO6391LanguageCode":"en","Generic":"Eastern European Time",
                        "Standard":"Eastern European Standard Time","Daylight":"Eastern European Summer Time"},
               "ReferenceTime":{"Tag":"EEST","StandardOffset":"02:00:00","DaylightSavings":"01:00:00",
                                "WallTime":"2026-08-23T18:09:06.4007064+03:00","PosixTzValidYear":2026,
                                "PosixTz":"EET-2EEST,M3.5.0/3,M10.4.0/4",
                                "Sunrise":"2026-08-23T05:57:35.1119448+03:00",
                                "Sunset":"2026-08-23T20:47:55.4717385+03:00"}}]}
        """
    }
}
