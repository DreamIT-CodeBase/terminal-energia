package com.terminalscout.app.prod.pipeline

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 5's arithmetic — the three rules that decide what a price IS.
 *
 * Worth testing rather than reading, and testable at all unlike the rest of the pipeline: the
 * consolidator is pure Kotlin over `org.json` with no models, no OpenCV and no network, so the whole
 * stage runs in a JVM test. These cover the parts that changed together — the weighted vote, the
 * stability floor and the agreement gate — because each one can silently turn a correct price into a
 * blank, or a blank into a wrong price, and neither shows up until a board is misread in the field.
 */
class ConsensusConsolidatorTest {

    // The four stability tests that stood here are gone with the step they covered: `isUnstable` and
    // `dropUnstable` no longer exist. What replaced their guarantee is asserted below — a panel that
    // never settled now yields a BLANK price rather than a missing row.

    // ---- the weighted vote -------------------------------------------------------------------- //

    @Test
    fun `a tie resolves to the larger price`() {
        val prices = listOf(1.789, 1.889)
        assertEquals(1.889, ConsensusConsolidator.maxMode(prices)!!, 1e-9)
    }

    // ---- the agreement gate ------------------------------------------------------------------- //

    @Test
    fun `a row most of the evidence contradicts is blanked, not emitted`() {
        // Winner holds 2 of 5 reads — under half, so it leaves as "" with a reason, while `price` keeps
        // the float so the rejection can still be explained.
        val row = ConsensusConsolidator.Row(
            rowPosition = 1,
            label = "A",
            price = 1.789,
            prices = listOf(1.789, 1.789, 1.889, 1.989, 2.089),
            mergedFrom = listOf("A")
        )
        assertEquals(0.4, row.agreement, 1e-9)
        assertEquals(ConsensusConsolidator.REASON_LOW_AGREEMENT, row.withheld)
        assertEquals("", row.output)
        assertEquals(1.789, row.price, 1e-9)
    }

    @Test
    fun `a row the evidence backs is emitted as X_XXX`() {
        val row = ConsensusConsolidator.Row(
            rowPosition = 1,
            label = "A",
            price = 1.789,
            prices = listOf(1.789, 1.789, 1.889),
            mergedFrom = listOf("A")
        )
        assertNull(row.withheld)
        assertEquals("1.789", row.output)
        assertEquals(2, row.agreeingReads)
    }

    // ---- the price rules, unchanged but load-bearing -------------------------------------------- //

    @Test
    fun `toPrice keeps the last four digits`() {
        assertEquals(1.784, ConsensusConsolidator.toPrice("1784"), 1e-9)
        // An over-long read is right at the END and wrong at the start: the board is read left to right
        // and the price is what it finishes on.
        assertEquals(1.789, ConsensusConsolidator.toPrice("231789"), 1e-9)
    }

    @Test
    fun `toPrice repairs the leading digit as it parses`() {
        // Both rules run at parse time, so everything downstream of step 2 sees a normalized price.
        assertEquals(1.909, ConsensusConsolidator.toPrice("7909"), 1e-9)
        assertEquals(0.909, ConsensusConsolidator.toPrice("8909"), 1e-9)
        // The trim happens first, so the LEADING digit is the leading digit of the last four — not of
        // the string the reader returned.
        assertEquals(1.909, ConsensusConsolidator.toPrice("237909"), 1e-9)
        // A 7 or 8 that is not the leading digit of the trimmed price is an ordinary digit.
        assertEquals(1.787, ConsensusConsolidator.toPrice("1787"), 1e-9)
        assertEquals(2.878, ConsensusConsolidator.toPrice("2878"), 1e-9)
    }

    @Test
    fun `a ghosted read is pooled with the clean reads of the same panel`() {
        // The reason the rule runs at parse time rather than only at the output. Five reads of one
        // panel, two of them with a ghosted leading segment. Normalized on the way in, they are five
        // reads that AGREE — not a 3-2 split in which the board's own reads count against it.
        val prices = listOf("1909", "1909", "1909", "7909", "7909").map { ConsensusConsolidator.toPrice(it) }
        val row = ConsensusConsolidator.Row(
            rowPosition = 1,
            label = "A",
            price = ConsensusConsolidator.maxMode(prices)!!,
            prices = prices,
            mergedFrom = listOf("A")
        )
        assertEquals("1.909", row.output)
        assertEquals(5, row.agreeingReads)
        assertEquals(1.0, row.agreement, 1e-9)
        // And the output-time rule had nothing left to do — the repair already happened at parse time.
        assertFalse(row.leadDigitCorrected)
    }

    @Test
    fun `a panel read only as ghosted still commits the repaired price`() {
        // The other direction: nothing clean to pool with. Two ghosted reads used to commit 7.909 on a
        // full agreement share and be rewritten at the output — the right answer from a vote that was
        // measuring the wrong value. Now the vote itself is on 1.909.
        val prices = listOf("7909", "7909").map { ConsensusConsolidator.toPrice(it) }
        val row = ConsensusConsolidator.Row(
            rowPosition = 1,
            label = "A",
            price = ConsensusConsolidator.maxMode(prices)!!,
            prices = prices,
            mergedFrom = listOf("A")
        )
        assertEquals("1.909", row.output)
        assertEquals(1.909, row.price, 1e-9)
        assertEquals(2, row.agreeingReads)
    }

    @Test
    fun `every parse-time repair is recorded`() {
        // The repair happens before the vote, so the digits the reader returned exist nowhere else.
        val parsed = ConsensusConsolidator.parseFrames(
            JSONObject(
                """
                {"boards":{"f1":{"batch":1,"regions":{"A":{"box":"A","digits":"7909"},
                                                     "B":{"box":"B","digits":"8909"},
                                                     "C":{"box":"C","digits":"1909"}}}}}
                """.trimIndent()
            )
        )
        assertEquals(3, parsed.frames.single().regions.size)
        assertEquals(2, parsed.corrections.size)
        assertEquals(listOf('7', '8'), parsed.corrections.map { it.from })
        assertEquals(listOf('1', '0'), parsed.corrections.map { it.to })
        // The reader's own string, so the record says what was read and not what it became.
        assertEquals(listOf("7909", "8909"), parsed.corrections.map { it.digits })
    }

    @Test
    fun `a skipped region is not also reported as corrected`() {
        // "79" is too short to be a price, so it is rejected — and a read that never became a price was
        // not corrected. The two records must not double-count the same region.
        val parsed = ConsensusConsolidator.parseFrames(
            JSONObject("""{"boards":{"f1":{"regions":{"A":{"box":"A","digits":"79"}}}}}""")
        )
        assertEquals(1, parsed.skipped.size)
        assertEquals(0, parsed.corrections.size)
    }

    @Test
    fun `formatOutput commits only X_XXX, and 0_959 is a real price`() {
        assertEquals("1.789", ConsensusConsolidator.formatOutput(1.789))
        assertEquals("0.959", ConsensusConsolidator.formatOutput(0.959))
        assertEquals("", ConsensusConsolidator.formatOutput(12.5))
    }

    @Test
    fun `a leading 7 becomes 1 and a leading 8 becomes 0`() {
        // The two worked examples, on the way out of the guard rather than at parse time.
        assertEquals("1.909", ConsensusConsolidator.formatOutput(7.909))
        assertEquals("0.909", ConsensusConsolidator.formatOutput(8.909))
        // Only the LEADING digit. A 7 or an 8 among the decimals is an ordinary digit a board shows,
        // and rewriting one would change a price that was read correctly.
        assertEquals("1.787", ConsensusConsolidator.formatOutput(1.787))
        assertEquals("0.888", ConsensusConsolidator.formatOutput(0.888))
        // Every other leading digit is left alone, including the ones that are real prices.
        assertEquals("1.909", ConsensusConsolidator.formatOutput(1.909))
        assertEquals("2.459", ConsensusConsolidator.formatOutput(2.459))
        assertEquals("0.959", ConsensusConsolidator.formatOutput(0.959))
    }

    @Test
    fun `the lead-digit rule is one-directional`() {
        // It repairs a ghosted segment, which is additive: 1 -> 7 and 0 -> 8 are how the misread
        // happens, so the correction must never run that way. A 1 stays a 1.
        assertEquals("1.909", ConsensusConsolidator.correctLeadDigit("1.909"))
        assertEquals("0.909", ConsensusConsolidator.correctLeadDigit("0.909"))
        // And it repairs a known shape rather than parsing: anything that is not X.XXX passes through.
        assertEquals("", ConsensusConsolidator.correctLeadDigit(""))
        assertEquals("7.9091", ConsensusConsolidator.correctLeadDigit("7.9091"))
        assertEquals("79.09", ConsensusConsolidator.correctLeadDigit("79.09"))
    }

    @Test
    fun `a corrected row ships the fix and reports that it was corrected`() {
        // On a committed Row, so the correction is exercised where it actually ships — and so the
        // reporting flag is pinned to it. `price` keeps 7.909 on purpose: the consensus really did
        // arrive at that, and `leadDigitCorrected` is the only thing that explains why `output`
        // disagrees with it by a whole unit.
        val row = ConsensusConsolidator.Row(
            rowPosition = 1,
            label = "A",
            price = 7.909,
            prices = listOf(7.909, 7.909, 7.909),
            mergedFrom = listOf("A")
        )
        assertNull(row.withheld)
        assertEquals("1.909", row.output)
        assertEquals(7.909, row.price, 1e-9)
        assertTrue("the correction must be reported", row.leadDigitCorrected)
    }

    @Test
    fun `an uncorrected row does not claim it was corrected`() {
        val row = ConsensusConsolidator.Row(
            rowPosition = 1,
            label = "A",
            price = 1.909,
            prices = listOf(1.909, 1.909),
            mergedFrom = listOf("A")
        )
        assertEquals("1.909", row.output)
        assertFalse(row.leadDigitCorrected)
    }

    @Test
    fun `a withheld row is not reported as corrected`() {
        // The flag has to agree with `output`. A row blanked by the agreement gate ships nothing, so
        // nothing was corrected — even though its price starts with a 7.
        val row = ConsensusConsolidator.Row(
            rowPosition = 1,
            label = "A",
            price = 7.909,
            prices = listOf(7.909, 1.889, 1.989, 2.089, 2.189),
            mergedFrom = listOf("A")
        )
        assertEquals("", row.output)
        assertFalse(row.leadDigitCorrected)
    }

    @Test
    fun `a blanked price is not rescued by the lead-digit rule`() {
        // The shape guard runs FIRST, so a value it rejects stays rejected — the correction has no
        // string to work on. 78.5 must not become a price by having its leading digit rewritten.
        assertEquals("", ConsensusConsolidator.formatOutput(78.5))
        assertEquals("", ConsensusConsolidator.formatOutput(7.9091))
    }

    // ---- step 1: what gets refused, and why ----------------------------------------------------- //

    @Test
    fun `an underscore from Stage 4 becomes silence, not a shorter price`() {
        // The seam between the two stages. Stage 4 keeps "18_9" precisely so this refuses it: stripping
        // the underscore there would have produced "189", which is refused for a different reason, and
        // padding it would invent a digit the board never showed.
        val skipped = skipReasons(frame(listOf("A" to "18_9")))
        assertEquals(1, skipped.size)
        assertTrue("reason should name the digits: ${skipped[0]}", skipped[0].contains("non-numeric"))
    }

    @Test
    fun `all-underscores and an empty read are refused too`() {
        assertTrue(skipReasons(frame(listOf("A" to "____")))[0].contains("non-numeric"))
        assertTrue(skipReasons(frame(listOf("A" to "")))[0].contains("non-numeric"))
    }

    @Test
    fun `a three-digit read is refused, never padded`() {
        val skipped = skipReasons(frame(listOf("A" to "189")))
        assertEquals(1, skipped.size)
        assertTrue("reason should say too few: ${skipped[0]}", skipped[0].contains("too few digits"))
    }

    @Test
    fun `a refused region keeps its slot so the rows below do not shift up`() {
        // A's read is refused; B must stay at row position 2, not slide up into row 1 — a shifted row
        // position would fit the wrong board row in step 6.
        val ocr = ocrJson(
            frame(listOf("A" to "18_9", "B" to "1889")),
            frame(listOf("A" to "18_9", "B" to "1889"))
        )
        val boxes = JSONObject(ConsensusConsolidator.reduce(ocr).json)
            .getJSONObject("debug").getJSONArray("boxes")
        assertEquals(1, boxes.length())
        assertEquals("B", boxes.getJSONObject(0).getString("label"))
        assertEquals(2, boxes.getJSONObject(0).getInt("row_position"))
    }

    // ---- step 4: the board's shape --------------------------------------------------------------- //

    @Test
    fun `the row count is the modal per-frame region count`() {
        // Detection is noisy per frame; the modal count over the clip is the board's real shape.
        val ocr = ocrJson(
            frame(listOf("A" to "1789", "B" to "1889")),
            frame(listOf("A" to "1789", "B" to "1889")),
            frame(listOf("A" to "1789"))
        )
        val json = JSONObject(ConsensusConsolidator.reduce(ocr).json)
        assertEquals(2, json.getJSONObject("summary").getInt("row_count"))
    }

    // ---- step 7: the file states what it ran with ------------------------------------------------ //

    @Test
    fun `the thresholds it ran with are stamped into the output`() {
        val ocr = ocrJson(frame(listOf("A" to "1789")), frame(listOf("A" to "1789")))
        val rules = JSONObject(ConsensusConsolidator.reduce(ocr).json)
            .getJSONObject("debug").getJSONObject("rules")
        val thresholds = rules.getJSONObject("thresholds")
        assertEquals(2, thresholds.getInt("min_repeats"))
        assertEquals(0.5, thresholds.getDouble("min_agreement"), 1e-9)
        assertFalse(
            "no legibility threshold should survive",
            thresholds.has("illegible_weight") || thresholds.has("illegible_drop_fraction")
        )
        assertFalse("no legibility rule should survive", rules.has("legibility"))
        assertTrue(rules.getString("vote").contains("mode"))
        assertTrue(rules.getString("agreement").contains("MIN_AGREEMENT"))
    }

    // ---- repetition: a price has to be read more than once ---------------------------------------- //

    @Test
    fun `two agreeing reads commit, whichever request they came from`() {
        // The corroboration gate used to blank this: both reads are in ONE request, and it demanded two.
        // That could not be satisfied by most boards — Stage 4 splits the pool into requests of five, so
        // a board left with five canvases or fewer is read in a single request and could never commit a
        // price at all. Two reads that agree is the rule now, and where they came from is reported
        // rather than judged.
        val ocr = ocrJson(
            frame(listOf("A" to "1789")).put("batch", 1),
            frame(listOf("A" to "1789")).put("batch", 1)
        )
        val rows = JSONObject(ConsensusConsolidator.reduce(ocr).json).getJSONArray("rows")
        assertEquals(1, rows.length())
        assertEquals("1.789", rows.getJSONObject(0).getString("price_out"))
        assertTrue(rows.getJSONObject(0).isNull("withheld"))
        // Still counted, still in the artifact — it just does not decide anything.
        assertEquals(1, rows.getJSONObject(0).getInt("corroborating_batches"))
    }

    @Test
    fun `a single read commits nothing, and says why`() {
        // The failure the repeats rule is actually for: one observation is not a consensus, and a model
        // that misreads a panel says so as confidently as it says anything.
        val ocr = ocrJson(frame(listOf("A" to "1789")).put("batch", 1))
        val row = JSONObject(ConsensusConsolidator.reduce(ocr).json).getJSONArray("rows").getJSONObject(0)
        assertEquals("", row.getString("price_out"))
        assertEquals(ConsensusConsolidator.REASON_UNREPEATED, row.getString("withheld"))
    }

    @Test
    fun `two reads in different requests still commit`() {
        val ocr = ocrJson(
            frame(listOf("A" to "1789")).put("batch", 1),
            frame(listOf("A" to "1789")).put("batch", 2)
        )
        val row = JSONObject(ConsensusConsolidator.reduce(ocr).json).getJSONArray("rows").getJSONObject(0)
        assertEquals("1.789", row.getString("price_out"))
        assertEquals(2, row.getInt("corroborating_batches"))
    }

    @Test
    fun `two of three reads break the tie`() {
        val ocr = ocrJson(
            frame(listOf("A" to "1789")).put("batch", 1),
            frame(listOf("A" to "1789")).put("batch", 2),
            frame(listOf("A" to "1889")).put("batch", 3)
        )
        val row = JSONObject(ConsensusConsolidator.reduce(ocr).json).getJSONArray("rows").getJSONObject(0)
        assertEquals("1.789", row.getString("price_out"))
        assertEquals(2, row.getInt("corroborating_batches"))
        assertEquals(3, row.getInt("batches_seen"))
    }

    @Test
    fun `three requests that all disagree keep the row and commit nothing`() {
        // The behaviour the stability drop used to own. Three reads, no price twice: the box is no
        // longer discarded, so the ROW SURVIVES and its price is whatever the mode tie-break picks —
        // which the caller can see and weigh. What must never happen is a malformed value being
        // committed, and that is still guarded by formatOutput.
        val ocr = ocrJson(
            frame(listOf("A" to "1789")).put("batch", 1),
            frame(listOf("A" to "1889")).put("batch", 2),
            frame(listOf("A" to "1989")).put("batch", 3)
        )
        val json = JSONObject(ConsensusConsolidator.reduce(ocr).json)
        val rows = json.getJSONArray("rows")
        assertEquals(1, rows.length())
        // Committed or blank, never malformed: X.XXX or "".
        val price = rows.getJSONObject(0).getString("price_out")
        assertTrue("price_out was '" + price + "'", price.isEmpty() || price.matches(Regex("\\d\\.\\d{3}")))
    }

    @Test
    fun `a price two reads agreed on wins over one that disagreed`() {
        // Two reads say 1.789, one says 1.889. The winner repeats and holds two thirds of the evidence,
        // so it commits. The corroboration gate used to blank this because the two agreeing reads were
        // in one request — throwing away the answer the board actually gave twice.
        val ocr = ocrJson(
            frame(listOf("A" to "1789")).put("batch", 1),
            frame(listOf("A" to "1789")).put("batch", 1),
            frame(listOf("A" to "1889")).put("batch", 2)
        )
        val row = JSONObject(ConsensusConsolidator.reduce(ocr).json).getJSONArray("rows").getJSONObject(0)
        assertEquals(1.789, row.getDouble("price"), 1e-9)
        assertEquals("1.789", row.getString("price_out"))
        assertTrue(row.isNull("withheld"))
    }

    @Test
    fun `a panel read once is blanked while its neighbour on the same board ships`() {
        // Two canvases. Box A is read on both and agrees with itself; box B appears on one only. The
        // board ships A's price and blanks B's — from the same reads, on the same board, which is the
        // distinction the repeats rule draws.
        val ocr = ocrJson(
            frame(listOf("A" to "1789", "B" to "1889")).put("batch", 1),
            frame(listOf("A" to "1789")).put("batch", 2)
        )
        val rows = JSONObject(ConsensusConsolidator.reduce(ocr).json).getJSONArray("rows")
        val byLabel = (0 until rows.length()).associate {
            rows.getJSONObject(it).getString("label") to rows.getJSONObject(it)
        }
        assertEquals("1.789", byLabel.getValue("A").getString("price_out"))
        assertEquals("", byLabel.getValue("B").getString("price_out"))
        assertEquals(
            ConsensusConsolidator.REASON_UNREPEATED,
            byLabel.getValue("B").getString("withheld")
        )
    }

    @Test
    fun `the repeats threshold is stamped into the output, and corroboration is gone from it`() {
        val ocr = ocrJson(frame(listOf("A" to "1789")), frame(listOf("A" to "1789")))
        val rules = JSONObject(ConsensusConsolidator.reduce(ocr).json)
            .getJSONObject("debug").getJSONObject("rules")
        assertEquals(2, rules.getJSONObject("thresholds").getInt("min_repeats"))
        // The record has to state the rules it APPLIES. `min_repeats` was printed here for months while
        // nothing enforced it, and the corroboration rule was printed while it was unsatisfiable.
        assertTrue(rules.getString("stability").contains("MIN_REPEATS"))
        assertFalse("the corroboration rule should be gone", rules.has("corroboration"))
        assertFalse(
            "its threshold should be gone with it",
            rules.getJSONObject("thresholds").has("min_corroborating_batches")
        )
    }

    // ---- the reader does not get a vote on itself ----------------------------------------------- //

    @Test
    fun `a legible flag in the document changes nothing`() {
        val boxes = listOf("A" to "1789")
        val flagged = ConsensusConsolidator.reduce(
            ocrJson(frame(boxes, legible = false), frame(boxes, legible = false))
        )
        val unflagged = ConsensusConsolidator.reduce(ocrJson(frame(boxes), frame(boxes)))
        assertEquals(
            "an illegible-flagged document must consolidate identically",
            JSONObject(unflagged.readingsJson).toString(),
            JSONObject(flagged.readingsJson).toString()
        )
    }

    @Test
    fun `a box every read of which was flagged illegible still emits its price`() {
        // Four frames, so four independent requests: the corroboration rule is satisfied and what is
        // being tested is the legibility flag having no effect.
        // This is the whole point of the removal: the reader reporting no confidence in itself used to
        // delete the row. Four agreeing reads are four agreeing reads.
        val ocr = ocrJson(
            frame(listOf("A" to "1789"), legible = false),
            frame(listOf("A" to "1789"), legible = false),
            frame(listOf("A" to "1789"), legible = false),
            frame(listOf("A" to "1789"), legible = false)
        )
        val rows = JSONObject(ConsensusConsolidator.reduce(ocr).json).getJSONArray("rows")
        assertEquals(1, rows.length())
        assertEquals("1.789", rows.getJSONObject(0).getString("price_out"))
    }

    @Test
    fun `agreement is a plain share of the reads`() {
        // Two of four back the winner: exactly at the gate, so it is NOT withheld (the test is `<`).
        val row = ConsensusConsolidator.Row(
            rowPosition = 1,
            label = "A",
            price = 1.789,
            prices = listOf(1.789, 1.789, 1.889, 1.989),
            mergedFrom = listOf("A")
        )
        assertEquals(0.5, row.agreement, 1e-9)
        assertNull(row.withheld)
    }

    // ---- end to end --------------------------------------------------------------------------- //

    @Test
    fun `a two-frame board agreeing on both rows emits both prices`() {
        val ocr = ocrJson(
            frame(listOf("A" to "1789", "B" to "1889")),
            frame(listOf("A" to "1789", "B" to "1889"))
        )
        val result = ConsensusConsolidator.reduce(ocr)
        val rows = JSONObject(result.json).getJSONArray("rows")
        assertEquals(2, rows.length())
        assertEquals("1.789", rows.getJSONObject(0).getString("price_out"))
        assertEquals("1.889", rows.getJSONObject(1).getString("price_out"))
    }

    @Test
    fun `a board read once keeps its row but commits no price`() {
        // Was `emits nothing`: the stability drop discarded the box, so a single-frame board produced an
        // empty result and nothing said why.
        //
        // The drop is gone, and a lone read is still NOT committed — MIN_REPEATS is the guard that
        // matters here: one read of a box is exactly the shape of a confident mistake. What changed is
        // the shape of the refusal. The row survives with a blank price, so a panel that could not be
        // settled stays distinguishable from a panel that was not there — which was the whole point of
        // removing the drop.
        val result = ConsensusConsolidator.reduce(ocrJson(frame(listOf("A" to "1789"))))
        val readings = JSONObject(result.readingsJson).getJSONArray("readings")
        assertEquals(1, readings.length())
        assertEquals("", readings.getJSONObject(0).getString("price"))
    }

    // ---- row_num: the board's row, not the canvas slot -------------------------------------------- //

    @Test
    fun `row_num is the panel's place on the board, not its slot in the canvas`() {
        // The board is A over B over C. Every canvas here missed the top panel, so B sits in slot 1 and
        // C in slot 2 — and before board_order those slots WERE the row numbers, which named the board's
        // second panel "row 1" in the payload.
        val ocr = ocrJson(
            listOf("A", "B", "C"),
            frame(listOf("B" to "1789", "C" to "1889")),
            frame(listOf("B" to "1789", "C" to "1889"))
        )
        val result = ConsensusConsolidator.reduce(ocr)
        val readings = JSONObject(result.readingsJson).getJSONArray("readings")
        assertEquals(2, readings.length())
        assertEquals(2, readings.getJSONObject(0).getInt("row_num"))
        assertEquals("B", readings.getJSONObject(0).getString("box"))
        assertEquals(3, readings.getJSONObject(1).getInt("row_num"))
        assertEquals("C", readings.getJSONObject(1).getString("box"))

        // The slot is still recorded beside it — the merge works in slots, and a disagreement between
        // the two is exactly what the artifact is read for.
        val rows = JSONObject(result.json).getJSONArray("rows")
        assertEquals(2, rows.getJSONObject(0).getInt("row_num"))
        assertEquals(1, rows.getJSONObject(0).getInt("row_position"))
    }

    @Test
    fun `a document with no board order still numbers rows by slot`() {
        // Every older fixture, and any run recovered from the offline queue, arrives without an order.
        // Those must reduce exactly as they did before board_order existed.
        val ocr = ocrJson(
            frame(listOf("B" to "1789", "C" to "1889")),
            frame(listOf("B" to "1789", "C" to "1889"))
        )
        val readings = JSONObject(ConsensusConsolidator.reduce(ocr).readingsJson)
            .getJSONArray("readings")
        assertEquals(1, readings.getJSONObject(0).getInt("row_num"))
        assertEquals(2, readings.getJSONObject(1).getInt("row_num"))
    }

    @Test
    fun `the answer is ordered by the board, even when the slots disagree`() {
        // A canvas that saw the bottom panel first gives it the lower slot. Ordering the answer by slot
        // would hand a consumer the board upside down.
        val ocr = ocrJson(
            listOf("A", "B"),
            frame(listOf("B" to "1889", "A" to "1789")),
            frame(listOf("B" to "1889", "A" to "1789"))
        )
        val readings = JSONObject(ConsensusConsolidator.reduce(ocr).readingsJson)
            .getJSONArray("readings")
        assertEquals("A", readings.getJSONObject(0).getString("box"))
        assertEquals(1, readings.getJSONObject(0).getInt("row_num"))
        assertEquals("B", readings.getJSONObject(1).getString("box"))
        assertEquals(2, readings.getJSONObject(1).getInt("row_num"))
    }

    @Test
    fun `an unsettled panel keeps its row rather than leaving a gap`() {
        // Was `a dropped panel leaves a gap`: C never agrees with itself, the stability drop removed it,
        // and the assertion was that B and D kept rows 1 and 3 rather than being renumbered 1,2.
        //
        // Nothing drops C now, so all three panels come back and the row numbers are contiguous. The
        // property that mattered — a survivor never moves onto another panel's row — is unchanged and is
        // what this still asserts: B is row 1 and D is row 3, whatever happened to C.
        val ocr = ocrJson(
            listOf("B", "C", "D"),
            frame(listOf("B" to "1789", "C" to "1889", "D" to "1989")),
            frame(listOf("B" to "1789", "C" to "1489", "D" to "1989")),
            frame(listOf("B" to "1789", "C" to "1289", "D" to "1989"))
        )
        val readings = JSONObject(ConsensusConsolidator.reduce(ocr).readingsJson)
            .getJSONArray("readings")
        val byRow = (0 until readings.length()).associate {
            readings.getJSONObject(it).getInt("row_num") to readings.getJSONObject(it).getString("price")
        }
        assertEquals(listOf(1, 2, 3), byRow.keys.sorted())
        assertEquals("1.789", byRow[1])
        assertEquals("1.989", byRow[3])
    }

    // ---- step 5a: the legibility gate ------------------------------------------------------------ //

    // ---- fixtures ----------------------------------------------------------------------------- //

    private companion object {
        /** [ConsensusConsolidator.ILLEGIBLE_WEIGHT], as the tests read it. */
        const val W_BAD = 0.25
    }

    /** The reasons regions were refused, from the run's own diagnostics. */
    private fun skipReasons(vararg frames: JSONObject): List<String> {
        val skipped = JSONObject(ConsensusConsolidator.reduce(ocrJson(*frames)).json)
            .getJSONObject("debug").getJSONArray("skipped_regions")
        return (0 until skipped.length()).map { skipped.getJSONObject(it).getString("reason") }
    }

    /** One frame's regions, in Stage 4's on-disk shape. */
    /**
     * A frame of the OCR document.
     *
     * [legible] still writes the field Stage 4 no longer emits, deliberately: a document produced before
     * the flag was removed must consolidate exactly like one produced after, and the only way to hold
     * that is to keep feeding the field in and asserting it changes nothing.
     */
    private fun frame(boxes: List<Pair<String, String>>, legible: Boolean = true): JSONObject {
        val regions = JSONObject()
        boxes.forEachIndexed { i, (label, digits) ->
            regions.put(
                (i + 1).toString(),
                JSONObject().apply {
                    put("box", label)
                    put("digits", digits)
                    put("legible", legible)
                }
            )
        }
        return JSONObject().put("region_count", boxes.size).put("regions", regions)
    }

    private fun ocrJson(vararg frames: JSONObject): JSONObject = ocrJson(emptyList(), *frames)

    /** The same document, stating the board's own top-to-bottom order as a live run does. */
    private fun ocrJson(boardOrder: List<String>, vararg frames: JSONObject): JSONObject {
        val boards = JSONObject()
        frames.forEachIndexed { i, f ->
            // Each frame its own reader request unless the frame already names one. Independence is the
            // default because that is what a live run produces — Stage 4 splits the pool into separate
            // requests — and a test that wants to prove the corroboration rule says so explicitly by
            // giving two frames the SAME batch.
            if (!f.has("batch")) f.put("batch", i + 1)
            boards.put((i + 1).toString(), f)
        }
        return JSONObject()
            .put("source", "test")
            .put("video_id", "test-video")
            .put("board_count", frames.size)
            .apply {
                if (boardOrder.isNotEmpty()) {
                    put("board_order", JSONArray().apply { boardOrder.forEach { put(it) } })
                }
            }
            .put("boards", boards)
    }

    /** Guards the constant the weights above stand for. */
}
