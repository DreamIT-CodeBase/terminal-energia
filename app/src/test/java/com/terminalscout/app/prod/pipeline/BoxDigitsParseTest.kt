package com.terminalscout.app.prod.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 4's sanitiser — what the app is willing to believe the reader said.
 *
 * The rule running through all of it: this stage never guesses. It reports what the model said and how
 * sure the model was, and it makes an unreadable panel look different from a readable one. Every case
 * below is one where a "helpful" normalisation would turn a visible non-answer into a plausible wrong
 * price that nothing downstream could catch.
 */
class BoxDigitsParseTest {

    private fun parse(json: String) = OcrPrompts.parseBoxDigitsJson(json)

    @Test
    fun `an underscore is kept, not stripped`() {
        // "18_9" -> "189" would be four positions read as three: a shorter, plausible, WRONG price that
        // Stage 5 would then vote on happily. Kept, it fails the numeric check and the row is skipped.
        val boxes = parse("""{"readings":[{"box":"A","digits":"18_9","legible":false}]}""")
        assertEquals(1, boxes.size)
        assertEquals("18_9", boxes[0].digits)
    }

    @Test
    fun `everything that is not a digit or an underscore goes`() {
        val boxes = parse("""{"readings":[{"box":"A","digits":"2.47 EUR","legible":true}]}""")
        assertEquals("247", boxes[0].digits)
    }

    @Test
    fun `a label survives decoration`() {
        val boxes = parse("""{"readings":[{"box":"(b)","digits":"1789","legible":true}]}""")
        assertEquals("B", boxes[0].label)
    }

    @Test
    fun `legible defaults to false when the field is absent`() {
        // Silence is not a claim of confidence.
        val boxes = parse("""{"readings":[{"box":"A","digits":"1789"}]}""")
        assertEquals(1, boxes.size)
        assertTrue("a read with no verdict must not count as confident", !boxes[0].legible)
    }

    @Test
    fun `an underscore or an empty read is never legible, whatever the model claims`() {
        val underscore = parse("""{"readings":[{"box":"A","digits":"1_89","legible":true}]}""")
        assertTrue(!underscore[0].legible)

        val empty = parse("""{"readings":[{"box":"A","digits":"","legible":true}]}""")
        assertTrue(!empty[0].legible)
    }

    @Test
    fun `a confident full read is legible`() {
        val boxes = parse("""{"readings":[{"box":"A","digits":"1789","legible":true}]}""")
        assertTrue(boxes[0].legible)
    }

    @Test
    fun `one letter answered twice with different digits drops both`() {
        // Picking the first would be a silent coin flip. The model contradicted itself about this box,
        // so neither answer is evidence — while B, which it was consistent about, survives.
        val boxes = parse(
            """{"readings":[
                {"box":"A","digits":"1789","legible":true},
                {"box":"B","digits":"1889","legible":true},
                {"box":"A","digits":"1889","legible":true}
            ]}"""
        )
        assertEquals(listOf("B"), boxes.map { it.label })
    }

    @Test
    fun `one letter answered twice the same way is kept once`() {
        val boxes = parse(
            """{"readings":[
                {"box":"A","digits":"1789","legible":true},
                {"box":"A","digits":"1789","legible":true}
            ]}"""
        )
        assertEquals(1, boxes.size)
        assertEquals("1789", boxes[0].digits)
    }

    @Test
    fun `a reply wrapped in prose or fences still parses`() {
        val boxes = parse("""Here you go: ```json {"readings":[{"box":"A","digits":"1789","legible":true}]} ```""")
        assertEquals(1, boxes.size)
    }

    @Test
    fun `nothing readable yields nothing rather than a guess`() {
        assertEquals(0, parse("""{"readings":[]}""").size)
        assertEquals(0, parse("not json at all").size)
    }

    @Test
    fun `the prompt asks for every field the parser reads, and for nothing else`() {
        // The reader is asked for digits and a box letter. It is NOT asked whether to believe itself:
        // a model's verdict on its own reading used to weight the vote and could delete a row, which is
        // a model deciding what reaches the output on evidence nothing could recount. Pinned from the
        // prompt's side, in both shapes, because that is where it would quietly come back.
        val single = OcrPrompts.DIGITS_ONLY_PROMPT
        val batched = BatchOcrReader.batchPrompt(3)
        assertFalse("single-image schema must not ask for legible", single.contains("legible"))
        assertFalse("batched schema must not ask for legible", batched.contains("legible"))
        assertTrue("the underscore convention must be in the rules", single.contains("Use '_' for a digit POSITION"))
        assertTrue("board-specific rules must be present", single.contains("FOUR digits"))
    }

    @Test
    fun `clipped first zero rule is shared by single and batched prompts`() {
        val rule = "0 vs 1 at the FIRST digit position:"
        for (prompt in listOf(OcrPrompts.DIGITS_ONLY_PROMPT, BatchOcrReader.batchPrompt(3))) {
            assertTrue(prompt.contains(rule))
            assertTrue(prompt.contains("TOP and BOTTOM horizontal bars extending LEFT"))
            assertTrue(prompt.contains("if the bars cannot be seen, mark the position unreadable with '_'"))
        }
    }
}
