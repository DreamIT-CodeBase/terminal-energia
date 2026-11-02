package com.terminalscout.app.prod.pipeline

import com.terminalscout.app.prod.upload.Station
import com.terminalscout.app.prod.upload.StationDirectory
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Stage 6's naming, where the board's rows meet the station's fuel list.
 *
 * The two sides are joined by POSITION and nothing else, so every case here is about a row landing on the
 * right entry of that list. Worth a test rather than a read because a wrong name is the one failure mode
 * nothing downstream can detect: a price is checkable against the image it came from, whereas "95" sitting
 * on the diesel row is a plausible-looking row that only the forecourt could contradict.
 */
class EnrichmentStageTest {

    @Test
    fun `a gap in the rows does not shift the names below it up a panel`() {
        // The board has three panels; the middle one's price never settled, so Stage 5 emits rows 1 and 3.
        // Row 3 is the board's THIRD panel and must take the station's third fuel — indexing by the row's
        // place in this list would hand it "98", the second.
        val board = enrich(
            readings = listOf(1 to "1.789", 3 to "1.989"),
            fuels = listOf("95", "98", "D")
        )
        assertEquals(listOf("95", "D"), board.readings.map { it.fuelType })
    }

    @Test
    fun `contiguous rows are named straight down the station's list`() {
        val board = enrich(
            readings = listOf(1 to "1.789", 2 to "1.889", 3 to "1.989"),
            fuels = listOf("95", "98", "D")
        )
        assertEquals(listOf("95", "98", "D"), board.readings.map { it.fuelType })
    }

    @Test
    fun `a row past the end of the station's list keeps a blank name`() {
        // The station lists two fuels, the board read three rows. The third is not invented — and the
        // box letter is not a name either, so it is left blank and the payload sends null. The letter
        // still travels in `box`, which is asserted below.
        val board = enrich(
            readings = listOf(1 to "1.789", 2 to "1.889", 3 to "1.989"),
            fuels = listOf("95", "98")
        )
        assertEquals(listOf("95", "98", ""), board.readings.map { it.fuelType })
        assertEquals(listOf("A", "B", "C"), board.readings.map { it.box })
    }

    @Test
    fun `with no station at all, only the board's own names survive`() {
        // The live configuration — STATION_LOOKUP_ENABLED is off, so `station` is null and there is no
        // list to fall back to. Row 1 was never named by Stage 3 and stays blank; row 2's name was read
        // off the board and is kept. Row 1 used to ship "A" in the fuel field.
        val json = """
            {"readings":[
              {"row_num":1,"box":"A","fuel_type":"","price":"1.789"},
              {"row_num":2,"box":"B","fuel_type":"ECOFORCE","price":"1.889"}
            ]}
        """.trimIndent()
        val board = EnrichmentStage().enrichBoard(
            reconstructedJson = json,
            ordinal = 1, count = 1, boardId = 0, imagesRead = 4,
            seenFromS = 0.0, seenToS = 1.0,
            station = null
        )
        assertEquals(listOf("", "ECOFORCE"), board.readings.map { it.fuelType })
        assertEquals(listOf("A", "B"), board.readings.map { it.box })
    }

    @Test
    fun `a name the board itself carries is never overwritten by the station`() {
        // Step G read "ECOFORCE" off row 2. That is what the panel says; the station's stored list is
        // right about the board in general and wrong the moment a panel is changed on the forecourt.
        val json = """
            {"readings":[
              {"row_num":1,"box":"A","fuel_type":"","price":"1.789"},
              {"row_num":2,"box":"B","fuel_type":"ECOFORCE","price":"1.889"}
            ]}
        """.trimIndent()
        val board = EnrichmentStage().enrichBoard(
            reconstructedJson = json,
            ordinal = 1, count = 1, boardId = 0, imagesRead = 4,
            seenFromS = 0.0, seenToS = 1.0,
            station = match(listOf("95", "98"))
        )
        assertEquals(listOf("95", "ECOFORCE"), board.readings.map { it.fuelType })
    }

    // ---- fixtures ----------------------------------------------------------------------------- //

    /** Stage 5's output for [readings] as `row_num to price`, with the box letter implied by the row. */
    private fun enrich(
        readings: List<Pair<Int, String>>,
        fuels: List<String>
    ) = EnrichmentStage().enrichBoard(
        reconstructedJson = readings.joinToString(
            prefix = """{"readings":[""",
            postfix = "]}"
        ) { (row, price) ->
            """{"row_num":$row,"box":"${'A' + row - 1}","fuel_type":"","price":"$price"}"""
        },
        ordinal = 1,
        count = 1,
        boardId = 0,
        imagesRead = 4,
        seenFromS = 0.0,
        seenToS = 1.0,
        station = match(fuels)
    )

    private fun match(fuels: List<String>) = StationDirectory.Match(
        station = Station(
            id = "TEST-1",
            name = "Test station",
            latitude = 59.4,
            longitude = 24.7,
            fuelTypes = fuels,
            note = null
        ),
        distanceMetres = 12.0
    )
}
