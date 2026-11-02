package com.terminalscout.app.prod.upload

import com.terminalscout.app.prod.pipeline.AggregatedReading
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The readings-API body's shape, pinned.
 *
 * This is a wire format someone else consumes, so the things worth a test are the promises the schema
 * makes rather than the values of one run: that `boards` is always an array and always used, that a
 * capture's facts appear once at the root, and that a key with nothing to say is present and `null`
 * rather than missing. A consumer written against a key that silently disappears is a consumer that
 * breaks on the quiet clip, not on the interesting one.
 */
class ReadingPayloadTest {

    private val config = VehicleConfig(deviceId = "dev-1", vehicleId = "veh-1")
    private val fix = LocationFix(
        latitude = 58.3776, longitude = 26.7290, speedKmph = null, headingDeg = null, accuracyM = 12.5f
    )

    private fun row(rowNum: Int, fuel: String, box: String, price: String) =
        AggregatedReading(
            fuelType = fuel, price = price, votes = 0, totalReads = 0, rowNum = rowNum, box = box
        )

    private fun board(
        ordinal: Int,
        count: Int,
        boardId: Int,
        rows: List<AggregatedReading>,
        seen: Pair<Double, Double>? = 3.1 to 6.8
    ) = ReadingPayload.BoardReading(
        ordinal = ordinal,
        count = count,
        boardId = boardId,
        imagesRead = 14,
        seenFromS = seen?.first,
        seenToS = seen?.second,
        readings = rows
    )

    private fun capture(boards: List<ReadingPayload.BoardReading>): JSONObject =
        ReadingPayload.buildCapture(
            config = config,
            location = fix,
            boards = boards,
            timestampMs = 1_787_000_000_000L,
            captureId = "vid-abc",
            station = null
        )

    // ---------------------------------------------------------------- the shape itself

    @Test
    fun `one board still sends a boards array`() {
        val root = capture(listOf(board(1, 1, 0, listOf(row(1, "95", "A", "1.679")))))
        val boards = root.getJSONArray("boards")
        assertEquals(1, boards.length())
        assertEquals(1, root.getInt("board_count"))
        // The common case must use the same shape as the rare one, or the multi-board path is never
        // exercised and every consumer has to handle two schemas.
        assertEquals(1, boards.getJSONObject(0).getInt("count"))
    }

    @Test
    fun `two boards travel on ONE body, each with its own prices`() {
        val root = capture(
            listOf(
                board(1, 2, 0, listOf(row(1, "95", "A", "1.679"))),
                board(2, 2, 3, listOf(row(1, "D", "B", "1.549"), row(2, "98", "C", "1.799")))
            )
        )
        assertEquals(2, root.getInt("board_count"))
        val boards = root.getJSONArray("boards")
        assertEquals(2, boards.length())
        assertEquals(1, boards.getJSONObject(0).getJSONArray("fuel_prices").length())
        assertEquals(2, boards.getJSONObject(1).getJSONArray("fuel_prices").length())
        // The tracker's id is carried, and is NOT the ordinal: board 2 of the capture is track 3.
        assertEquals(2, boards.getJSONObject(1).getInt("ordinal"))
        assertEquals(3, boards.getJSONObject(1).getInt("board_id"))
    }

    @Test
    fun `capture facts appear once at the root, not per board`() {
        val root = capture(
            listOf(board(1, 2, 0, emptyList()), board(2, 2, 1, emptyList()))
        )
        assertEquals("dev-1", root.getString("device_id"))
        assertEquals("veh-1", root.getString("vehicle_id"))
        assertEquals("vid-abc", root.getString("capture_id"))
        assertEquals("2.0", root.getString("schema_version"))
        assertTrue(root.has("timestamp"))
        assertTrue(root.getJSONObject("location").getDouble("latitude") > 58.0)
        // Two bodies repeating these was what made one video look like a double submission.
        val first = root.getJSONArray("boards").getJSONObject(0)
        for (key in listOf("device_id", "vehicle_id", "timestamp", "location", "station", "capture_id")) {
            assertTrue("boards[] must not repeat $key", !first.has(key))
        }
    }

    @Test
    fun `an empty capture is a body with an empty boards array`() {
        // A clip that found nothing is a fact about that stretch of road. It must be reportable without
        // inventing a board.
        val root = capture(emptyList())
        assertEquals(0, root.getInt("board_count"))
        assertEquals(0, root.getJSONArray("boards").length())
    }

    // ---------------------------------------------------------------- deterministic keys

    @Test
    fun `a board with no rows keeps its arrays, empty`() {
        val b = capture(listOf(board(1, 1, 0, emptyList()))).getJSONArray("boards").getJSONObject(0)
        assertEquals(0, b.getJSONArray("fuel_prices").length())
        assertEquals(0, b.getJSONArray("detections").length())
        assertEquals(14, b.getInt("images_read"))
    }

    @Test
    fun `seen_s is a from-to pair, and null when there was no board`() {
        val seen = capture(listOf(board(1, 1, 0, emptyList(), seen = 3.14159 to 6.8)))
            .getJSONArray("boards").getJSONObject(0).getJSONArray("seen_s")
        assertEquals(2, seen.length())
        // Rounded to 2dp like every other measured value in the payload.
        assertEquals(3.14, seen.getDouble(0), 1e-9)
        assertEquals(6.8, seen.getDouble(1), 1e-9)

        val none = capture(listOf(board(1, 1, -1, emptyList(), seen = null)))
            .getJSONArray("boards").getJSONObject(0)
        assertTrue("seen_s must be present and null, never absent", none.has("seen_s"))
        assertTrue(none.isNull("seen_s"))
    }

    @Test
    fun `a row carries its box letter beside the fuel name, not instead of it`() {
        val prices = capture(listOf(board(1, 1, 0, listOf(row(2, "95", "B", "1.679")))))
            .getJSONArray("boards").getJSONObject(0).getJSONArray("fuel_prices")
        val r = prices.getJSONObject(0)
        assertEquals(2, r.getInt("row_num"))
        assertEquals("95", r.getString("fuel_type"))
        // The letter identifies the panel on the prepped canvas, so a row can be checked against the
        // image it came from even when the name is absent.
        assertEquals("B", r.getString("box"))
        assertEquals(1.679, r.getDouble("price"), 1e-9)
    }

    @Test
    fun `an unnamed row reports null rather than an empty string`() {
        val r = capture(listOf(board(1, 1, 0, listOf(row(-1, "", "", "")))))
            .getJSONArray("boards").getJSONObject(0)
            .getJSONArray("fuel_prices").getJSONObject(0)
        assertTrue(r.isNull("row_num"))
        assertTrue(r.isNull("fuel_type"))
        assertTrue(r.isNull("box"))
        assertTrue(r.isNull("price"))
    }

    @Test
    fun `a best-guess price stays a string and is never coerced to a number`() {
        val r = capture(listOf(board(1, 1, 0, listOf(row(1, "95", "A", "*#1.679")))))
            .getJSONArray("boards").getJSONObject(0)
            .getJSONArray("fuel_prices").getJSONObject(0)
        assertEquals("*#1.679", r.getString("price"))
        // raw_text keeps what the pipeline settled on, verbatim, either way.
        assertEquals("*#1.679", r.getString("raw_text"))
    }

    @Test
    fun `a price that is not a number becomes null, and keeps its raw text`() {
        val r = capture(listOf(board(1, 1, 0, listOf(row(1, "95", "A", "1.6X9")))))
            .getJSONArray("boards").getJSONObject(0)
            .getJSONArray("fuel_prices").getJSONObject(0)
        assertTrue(r.isNull("price"))
        assertEquals("1.6X9", r.getString("raw_text"))
    }

    @Test
    fun `station is present and null when the capture matched none`() {
        val root = capture(listOf(board(1, 1, 0, emptyList())))
        assertTrue(root.has("station"))
        assertTrue(root.isNull("station"))
    }

    @Test
    fun `no location still emits the key`() {
        val root = ReadingPayload.buildCapture(
            config = config,
            location = null,
            boards = listOf(board(1, 1, 0, emptyList())),
            timestampMs = 1_787_000_000_000L,
            captureId = "vid-abc",
            station = null
        )
        assertTrue(root.has("location"))
        assertTrue(root.isNull("location"))
    }

    @Test
    fun `timestamp is UTC ISO-8601 with a Z`() {
        val ts = capture(listOf(board(1, 1, 0, emptyList()))).getString("timestamp")
        assertTrue("unexpected timestamp: $ts", ts.matches(Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z""")))
    }
}
