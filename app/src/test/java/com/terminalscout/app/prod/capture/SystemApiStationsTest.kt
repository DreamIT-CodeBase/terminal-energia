package com.terminalscout.app.prod.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the device is willing to believe `GET /v1/stations` said.
 *
 * The rule running through all of it: null and empty are different answers. Null is "this response
 * could not be trusted", and `LocationMap.upsertAll` refusing an empty sweep is the LAST line of
 * defence, not the first — a parse that returns empty where it should return null moves the whole
 * protection of the stored map into one refusal in one other function.
 */
class SystemApiStationsTest {

    /** One record in the shape the live endpoint serves, verified against it. */
    private fun record(
        id: String = "surveyed:tartu-kvissentali-olerex",
        name: String = "Tartu Kvissentali Olerex",
        lat: String = "58.39867",
        lon: String = "26.70944",
        brand: String = "Olerex"
    ) = """{"id":"$id","name":"$name","latitude":$lat,"longitude":$lon,"brand":"$brand",""" +
        """"modified_time":1789363291818,"fuel_types":null,"note":null}"""

    private fun body(vararg records: String) =
        """{"version":1,"count":${records.size},"etag":"0x8DF122C600A43E4",""" +
            """"stations":[${records.joinToString(",")}]}"""

    @Test
    fun `a live-shaped record parses into a station`() {
        val stations = SystemApiStations.parse(body(record()))
        assertNotNull(stations)
        assertEquals(1, stations!!.size)
        val s = stations[0]
        assertEquals("surveyed:tartu-kvissentali-olerex", s.id)
        assertEquals("Tartu Kvissentali Olerex", s.name)
        assertEquals(58.39867, s.latitude, 1e-9)
        assertEquals(26.70944, s.longitude, 1e-9)
        assertEquals("Olerex", s.brand)
        // Never read off the wire: "never captured" is the only truthful value for a row this device
        // has not captured at, and the sweep must not be able to assert otherwise.
        assertNull(s.lastCaptureTimeMs)
    }

    @Test
    fun `the server's modified_time is not carried into the row`() {
        // Our column means "the last refresh that touched this row" and drives the staleness check.
        // The server's field means "when the record last changed server-side". Same name, different
        // fact — and `upsertAll` stamps the sweep's own timestamp on every row it writes.
        val s = SystemApiStations.parse(body(record()))!!.single()
        assertEquals(0L, s.modifiedTimeMs)
    }

    @Test
    fun `an unparseable body is null, not empty`() {
        assertNull(SystemApiStations.parse("<html>502 Bad Gateway</html>"))
        assertNull(SystemApiStations.parse(""))
    }

    @Test
    fun `a document with no stations array is null`() {
        // A 200 carrying some other document is not a sweep that found nothing.
        assertNull(SystemApiStations.parse("""{"version":1,"count":0}"""))
    }

    @Test
    fun `an empty stations array is empty, not null`() {
        // The server answered and had nothing. The worker refuses to apply it, but for a different
        // reason than a broken response, and the log has to be able to tell them apart.
        val stations = SystemApiStations.parse("""{"version":1,"count":0,"stations":[]}""")
        assertNotNull(stations)
        assertTrue(stations!!.isEmpty())
    }

    @Test
    fun `a row with no id is dropped`() {
        // The id is the primary key that carries last_capture_time across sweeps.
        val stations = SystemApiStations.parse(body(record(id = ""), record(id = "keep")))
        assertEquals(listOf("keep"), stations!!.map { it.id })
    }

    @Test
    fun `a repeated id is dropped, first one wins`() {
        val stations = SystemApiStations.parse(
            body(record(id = "dup", name = "First"), record(id = "dup", name = "Second"))
        )
        assertEquals(1, stations!!.size)
        assertEquals("First", stations[0].name)
    }

    @Test
    fun `a row with no usable coordinate is dropped`() {
        val missing = """{"id":"no-position","name":"Nowhere","brand":"Olerex"}"""
        val nulls = """{"id":"null-position","name":"Nowhere","latitude":null,"longitude":null,"brand":"Olerex"}"""
        val stations = SystemApiStations.parse(body(missing, nulls, record(id = "keep")))
        assertEquals(listOf("keep"), stations!!.map { it.id })
    }

    @Test
    fun `a blank brand is kept, not dropped`() {
        // The one filter that did not survive the move off Azure Maps. The server has already decided
        // what counts as a station; dropping its rows here would shrink a fleet's map by a rule the
        // fleet operator cannot see.
        val stations = SystemApiStations.parse(body(record(id = "unbranded", brand = "")))
        assertEquals(1, stations!!.size)
        assertEquals("", stations[0].brand)
    }

    @Test
    fun `a blank name falls back to the id`() {
        // `name` reaches the log and the status line; a blank there is a row nobody can identify.
        val stations = SystemApiStations.parse(body(record(id = "abc123", name = "")))
        assertEquals("abc123", stations!!.single().name)
    }
}
