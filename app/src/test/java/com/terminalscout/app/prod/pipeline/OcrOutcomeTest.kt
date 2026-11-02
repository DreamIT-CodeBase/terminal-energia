package com.terminalscout.app.prod.pipeline

import android.graphics.Rect
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Stage 4 reports when the reader does not read — the distinction the whole run turns on.
 *
 * The bug these pin: a reader that ANSWERED by refusing (a dead key, an exhausted quota, a wrong
 * deployment name) came back as `reachedServer = true` with every canvas blank, because the server had
 * indeed answered. Stage 4 counted that as a finished read, the pipeline marked the run complete, an
 * empty payload was enqueued for upload, and the clip was deleted behind it — so a single wrong
 * character in `local.properties` would have turned a fleet's footage into a stream of forecourts with
 * no prices, permanently and silently.
 *
 * [OcrStageResult.readerUnavailable] is now the answer to "was the board read at all", and nothing
 * completes a run without it being false.
 */
class OcrOutcomeTest {

    /** A reader that answers every request with one fixed outcome. */
    private class FakeReader(
        private val outcome: ReadOutcome,
        override val isEnabled: Boolean = true,
        private val digits: String = "1789"
    ) : BatchOcrReader {
        var requests = 0
            private set

        override suspend fun readBoxDigitsBatch(canvasPaths: List<String>): BatchRead {
            requests++
            val readings = canvasPaths.map {
                if (outcome == ReadOutcome.READ) {
                    BoxReading("", listOf(BoxDigits("A", digits, true)))
                } else {
                    BatchOcrReader.EMPTY
                }
            }
            return BatchRead(readings, outcome)
        }
    }

    /** A reader that throws instead of answering — the failure that used to cancel its siblings. */
    private class ThrowingReader(private val failFrom: Int = 1) : BatchOcrReader {
        override val isEnabled = true
        var requests = 0
            private set

        override suspend fun readBoxDigitsBatch(canvasPaths: List<String>): BatchRead {
            requests++
            if (requests >= failFrom) throw IllegalStateException("reader blew up")
            return BatchRead(
                canvasPaths.map { BoxReading("", listOf(BoxDigits("A", "1789", true))) },
                ReadOutcome.READ
            )
        }
    }

    private fun canvas(index: Int) = PreppedFrame(
        detectionId = index,
        frameIndex = index,
        rank = 0,
        timestampUs = index * 100_000L,
        preppedPath = "/prepped/$index.png",
        gateClarityScore = 1.0,
        finalClarityScore = 1.0,
        regions = listOf(PriceRegion(Rect(0, 0, 10, 10), 0.9f, "A")),
        confidence = 0.9f,
        slug = "f%05d_d00".format(index)
    )

    private fun pool(n: Int) = (1..n).map { canvas(it) }

    @Test
    fun `a refused request is not a read`() = runBlocking {
        // The finding. One request (a five-canvas pool), refused outright.
        val result = OcrStage(FakeReader(ReadOutcome.REFUSED)).run(pool(5))
        assertTrue("a 4xx must not pass as a read", result.readerUnavailable)
        assertTrue("and must be reported as a refusal, not a lost connection", result.refused)
        assertTrue(result.results.isEmpty())
    }

    @Test
    fun `an unreachable reader is reported as unavailable but not refused`() = runBlocking {
        val result = OcrStage(FakeReader(ReadOutcome.UNREACHABLE)).run(pool(5))
        assertTrue(result.readerUnavailable)
        assertFalse("no signal is not a configuration fault", result.refused)
    }

    @Test
    fun `no key configured is a refusal, not an empty read`() = runBlocking {
        // The second half of the same bug: with no key the stage returned a plain empty result, whose
        // `readerUnavailable` was false — so every clip on the device was written out as a forecourt
        // with no prices and then deleted.
        val result = OcrStage(FakeReader(ReadOutcome.READ, isEnabled = false)).run(pool(5))
        assertTrue(result.readerUnavailable)
        assertTrue(result.refused)
        assertTrue(result.results.isEmpty())
        // The pool is still reported, so the run can say how many images went unread.
        assertEquals(5, result.sentFrames.size)
    }

    @Test
    fun `a successful read is available and produces one result per canvas`() = runBlocking {
        val result = OcrStage(FakeReader(ReadOutcome.READ)).run(pool(5))
        assertFalse(result.readerUnavailable)
        assertFalse(result.refused)
        assertEquals(5, result.results.size)
        assertEquals("1789", result.results.first().regions.first().digits)
    }

    @Test
    fun `a canvas with no price region is nothing to read, and not a reader failure`() = runBlocking {
        // A board whose canvases carry no region is a real fact about the board — there is nothing to
        // bind a reading to — and must NOT be reported as the reader being unavailable, or a clip would
        // be retried forever over a board that has nothing on it.
        val bare = pool(3).map { it.copy(regions = emptyList()) }
        val reader = FakeReader(ReadOutcome.READ)
        val result = OcrStage(reader).run(bare)
        assertFalse(result.readerUnavailable)
        assertTrue(result.results.isEmpty())
        assertEquals("the reader should not have been called at all", 0, reader.requests)
    }

    @Test
    fun `one request throwing does not cost the requests that succeeded`() = runBlocking {
        // 12 canvases split into three requests of five, five and two. The reader throws from the
        // second one on. `coroutineScope` used to cancel the siblings and rethrow, so ONE bad request
        // discarded the reads that had already come back and Stage 4 returned nothing.
        val result = OcrStage(ThrowingReader(failFrom = 2)).run(pool(12))
        assertFalse("a partial read is still a read", result.readerUnavailable)
        assertEquals("the first request's five canvases survive", 5, result.results.size)
        assertEquals(5, result.sentFrames.size)
    }

    @Test
    fun `every request throwing leaves the board unread`() = runBlocking {
        val result = OcrStage(ThrowingReader(failFrom = 1)).run(pool(12))
        assertTrue(result.readerUnavailable)
        assertFalse("a throw is not an answer, so it is not a refusal", result.refused)
    }

    @Test
    fun `a five-canvas board can commit a price — one request is enough`() {
        // The corroboration gate's arithmetic, pinned end to end. Stage 4 splits the pool into requests
        // of OcrStage.BATCH_SIZE (five), so a board left with five canvases or fewer is read in exactly
        // ONE request and could never reach two independent ones. Every row of every such board was
        // blanked, and the capture was uploaded and deleted as a forecourt with no prices.
        assertEquals(5, OcrStage.BATCH_SIZE)
        val frames = (1..5).map { i ->
            org.json.JSONObject()
                .put("region_count", 1)
                .put("batch", 1)
                .put(
                    "regions",
                    org.json.JSONObject().put(
                        "1",
                        org.json.JSONObject().put("box", "A").put("digits", "1789")
                    )
                )
                .let { "f$i" to it }
        }
        val boards = org.json.JSONObject().apply { frames.forEach { (k, v) -> put(k, v) } }
        val ocr = org.json.JSONObject().put("video_id", "one-batch").put("boards", boards)
        val rows = org.json.JSONObject(ConsensusConsolidator.reduce(ocr).json).getJSONArray("rows")
        assertEquals(1, rows.length())
        assertEquals("1.789", rows.getJSONObject(0).getString("price_out"))
    }
}
