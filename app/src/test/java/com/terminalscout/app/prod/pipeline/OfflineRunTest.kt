package com.terminalscout.app.prod.pipeline

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a run with no network actually produces, end of Stage 4 onward.
 *
 * Stage 4 reads off-device. When the board cannot be read, `OcrStage` returns
 * `readerUnavailable = true` with **no results**, and `PriceBoardPipeline` carries ON — there is no early
 * return on that path. So Stages 5 and 6 run over an empty OCR document, and these tests pin what comes
 * out of them: an answer shaped exactly like "this board had no readable prices", which is a different
 * fact from "this board was never read".
 *
 * That the two are indistinguishable HERE is the reason the flag has to travel separately, and why
 * `ProcessingLane` will not complete a run without it being false. See [OcrOutcomeTest] for the flag
 * itself, including the refusal case that used to come through as a finished read.
 *
 * The parking itself, and what the recovery worker does with a parked run, are Android-side and verified
 * against the code rather than here; this covers the part a JVM can settle, which is the part that
 * reaches the API.
 */
class OfflineRunTest {

    /** What `OcrResultStore.buildRoot` produces when Stage 4 returned nothing at all. */
    private fun emptyOcrDocument(): JSONObject =
        JSONObject()
            .put("source", "PriceBoardPipeline · Stage 4 OCR")
            .put("video_id", "offline-run")
            .put("boards", JSONObject())

    @Test
    fun `an unreachable reader leaves Stage 5 an empty document, not an error`() {
        val result = ConsensusConsolidator.reduce(emptyOcrDocument())
        val json = JSONObject(result.json)
        assertEquals("empty", json.getString("status"))
        assertEquals(0, json.getJSONArray("rows").length())
        assertEquals(0, json.getJSONObject("summary").getJSONObject("input").getInt("frames_in"))
    }

    @Test
    fun `Stage 5 still emits a readings document, with no readings in it`() {
        // This is the shape Stage 6 enriches and the API body carries. It is well-formed and empty —
        // which is why an offline run is indistinguishable downstream from a board nothing could be read
        // on, and why the run has to be held rather than completed.
        val readings = JSONObject(ConsensusConsolidator.reduce(emptyOcrDocument()).readingsJson)
        assertTrue(readings.has("readings"))
        assertEquals(0, readings.getJSONArray("readings").length())
    }

    @Test
    fun `Stage 6 turns that into a board block reporting zero prices`() {
        val board = EnrichmentStage().enrichBoard(
            reconstructedJson = ConsensusConsolidator.reduce(emptyOcrDocument()).readingsJson,
            ordinal = 1,
            count = 1,
            boardId = 0,
            imagesRead = 0,
            seenFromS = null,
            seenToS = null,
            station = null
        )
        assertEquals(0, board.readings.size)
    }

    @Test
    fun `a document that DID reach the reader is not confused with one that did not`() {
        // The counterpart, so the assertions above are about emptiness and not about the helper: the same
        // consolidator over a document with two agreeing reads emits the price.
        val frame = JSONObject().put("region_count", 1).put(
            "regions",
            JSONObject().put("1", JSONObject().put("box", "A").put("digits", "1789"))
        )
        // Two reads of the box, which is what Stage 5 requires before it commits a price (MIN_REPEATS).
        // They are tagged as two separate requests here because a live run splits its pool that way;
        // since the corroboration gate was removed, one request reading it twice would commit too.
        val ocr = emptyOcrDocument().put(
            "boards",
            JSONObject()
                .put("f1", JSONObject(frame.toString()).put("batch", 1))
                .put("f2", JSONObject(frame.toString()).put("batch", 2))
        )
        val rows = JSONObject(ConsensusConsolidator.reduce(ocr).json).getJSONArray("rows")
        assertEquals(1, rows.length())
        assertEquals("1.789", rows.getJSONObject(0).getString("price_out"))
    }
}
