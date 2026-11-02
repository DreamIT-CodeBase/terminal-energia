package com.terminalscout.app.prod.pipeline

import android.util.Log
import com.terminalscout.app.prod.config.DeviceConfig
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Stage 5 (current build) — reduces a Stage-4 OCR response to **one consensus price per board row**,
 * deterministically. No model, no prompts, no reasoning loops: given the same OCR input it always
 * returns the same board.
 *
 * This replaces the agentic consolidation ([RowConsolidator] + [GroundedPriceReconstructor]) as the
 * live Stage 5. That path is still in the tree but is switched **OFF** — see
 * `PriceBoardPipeline.AGENTIC_CONSOLIDATION`.
 *
 * ### Input
 * The `ocr_response.json` shape built by [OcrResultStore.buildRoot] — `boards` keyed monotonically,
 * each holding `regions` with `box` (the letter Stage 3 burned onto the canvas) and `digits`. The
 * pipeline feeds it a live run; the same JSON pulled to `res/raw/ocr_response.json` replays
 * identically. Both `boards` and `regions` may also be JSON **arrays**.
 *
 * ### The seven steps
 * 1. **[parseFrames]** — normalize the input **in its original order**. Frames that detected nothing
 *    are dropped here, before they can skew anything; regions that fail to parse are skipped
 *    individually and recorded.
 * 2. **[toPrice]** — **the first thing that happens to a price, and where both price rules are
 *    applied.** Keep the last [PRICE_DIGITS] digits, place the decimal after the first of those, then
 *    repair the leading digit: `"1784"` → `1.784`, `"231789"` → `1.789`, `"7909"` → `1.909`. A read
 *    too short to be `X.XXX` is **rejected** here rather than padded, so it never enters the
 *    consensus.
 *
 *    Both rules run here so that **every price in the rest of the stage is already in the one shape**
 *    — steps 3 to 7 pool, count and merge values that are all `X.XXX` with a possible leading digit
 *    already dealt with, and none of them has to know the rules exist. It is also what makes the vote
 *    in step 5 measure the right thing: see [correctLeadDigit] for why a ghosted read must be pooled
 *    with the clean reads of the same panel rather than counted against them.
 * 3. **[mergeBoxes]** — collapse every frame's regions into one record per box label, pooling that
 *    label's reads.
 * 4. **[frameRowMaxima]** — the max row position per frame; the [maxMode] of those maxima is the
 *    board's true row count.
 * 5. *(no separate step)* — a stability drop used to sit here: a box whose reads never agreed twice was
 *    discarded before any merging. It is gone. Every box the reader returned now reaches the window
 *    merge, and a panel that never settled leaves a **blank** at step 8 rather than no row at all.
 *
 *    The trade is deliberate. The drop was the only thing that removed a box, and what it removed was
 *    real evidence: a panel read `1.789 / 1.889 / 1.989` across three requests is a panel the reader
 *    could not settle, but it is also a panel that is *there*, and dropping it shortened the board.
 *    A short board is the harder failure to see — the row is simply missing, so nothing downstream can
 *    tell a panel that was unreadable from a panel that does not exist. A blank row says which.
 *
 *    What still guards the output: [toPrice] rejects a read too short to be `X.XXX` (step 2),
 *    [windowMerge] discards boxes that fit no window (step 6), and [formatOutput] blanks anything that
 *    is not `X.XXX` (step 8). Nothing commits a malformed price; the change is only about whether an
 *    unsettled panel is absent or blank.
 * 6. **[windowMerge]** — boxes beyond the row count are extras that must be merged away or discarded.
 *    A row-count-wide window slides over the box list; each position treats its window as the base
 *    board and fits the remaining boxes into it. The modal board across all window positions wins.
 * 7. **[buildOutput]** — shape the result: answer first, diagnostics under `debug`.
 * 8. **[formatOutput]** — the same two rules again, as the final guard on the way out, so that
 *    **nothing leaves this stage in any other shape.** [formatShape] requires `X.XXX`; anything else
 *    is **blanked** rather than emitted, because a blank is visibly a non-answer while `5.0` looks
 *    like a reading and would be believed downstream. Then [correctLeadDigit] runs again.
 *
 *    Deliberately redundant with step 2. Every price here arrived through [toPrice] and is already
 *    normalized, so the correction half should never fire — it is a backstop against a future step
 *    introducing a price by another route, which is exactly what `ConsolidationReviewer` does one
 *    stage later. The shape half is not redundant at all: the pooling and merging in between produce
 *    values [toPrice] never saw.
 *
 * ### Two different numbers, deliberately
 * `row_position` is a box's slot in the canvas it was first seen on. It is an INTERNAL signal, and the
 * window merge leans on it: an alias letter minted for a panel usually lands in the same slot as the
 * panel it duplicates, which is what lets [fitPieces] fold the two together.
 *
 * `row_num` — what leaves the stage — is the panel's place on the PHYSICAL BOARD, resolved by looking
 * the box letter up in `board_order` (Stage 3 measured it over the whole run; see
 * [OcrResultStore.buildRoot]). The two diverge routinely: a canvas that missed the top panel puts the
 * second panel in slot 1, and every consumer of a reading — the payload, the review, the station's fuel
 * list — means the board's second row, not that canvas's second box. A document with no `board_order`
 * (an older fixture, a recovered offline run) falls back to the slot, which is what this always did.
 *
 * **Row order is never sorted:** frames, regions within a frame, and boxes all stay in the order the
 * input presented them. (Android's `JSONObject` preserves document order, so parse order *is* input
 * order.)
 *
 * Every mode reduction breaks frequency ties by taking the **largest** tied value; board-level ties
 * resolve to the **earliest** window.
 *
 * ### Why a sliding window at all
 * Box letters are supposed to be a cross-frame identity, but Stage 3's `SAME_BOX_IOU` gate splits one
 * physical panel into two letters whenever the board box jitters (a real, observed failure: one run
 * lettered the same two panels `A`/`B` on one frame and `C`/`D` on the next). So the box list is
 * routinely *longer* than the board has rows. Rather than trusting any single frame's lettering, every
 * contiguous run of `row_count` boxes is tried as the board and the answer the most windows agree on
 * is taken.
 *
 * ### Tolerating bad input
 * One malformed region costs one row, never the file. A region missing `box`/`digits`, holding a blank
 * label, holding unreadable digits — which is exactly how Stage 4 reports a panel the reader could not read
 * (`"digits": ""`) — or holding too few digits to be a price is skipped and recorded under
 * `debug.skipped_regions`, **keeping its row slot** so rows below it don't shift up. A frame that detected nothing is dropped before
 * [frameRowMaxima] sees it, so it cannot vote a `0` into the row count and wipe out the run.
 *
 * ### Deviations from the reference Python
 * - **Exception text for a malformed region.** Python surfaces the interpreter's own `TypeError`
 *   message (`"string indices must be integers"`); here it reads `malformed region: not a JSON
 *   object`. Same trigger, different wording.
 * - **Digest price formatting** is lossless (`1.7845` stays `1.7845`) rather than the reference's
 *   fixed 3 decimals, which would silently misprint a 4-decimal price. Display only — the JSON
 *   carries the full value either way.
 *
 * Everything else — key order in the output, the rule strings, the discard reasons, the status
 * values, the digest wording — matches the reference so the two can be diffed on identical input.
 */
object ConsensusConsolidator {

    const val ROW_COUNT_RULE = "mode of per-frame max row position, frequency ties -> largest"
    const val STABILITY_RULE = "blank the output unless MIN_REPEATS reads agreed on the price"
    const val MATCH_RULE = "same row position, else same price, else discard"
    const val OUTPUT_FORMAT_RULE = "commit only X.XXX; anything else is blanked"
    const val LEAD_DIGIT_RULE = "on a committed X.XXX price, a leading 7 becomes 1 and a leading 8 becomes 0"

    /**
     * The leading-digit substitutions [correctLeadDigit] applies. See that function for why these two
     * and no others, and why the mapping is one-directional.
     *
     * A map rather than a `when`, so the rule is one piece of data that the rule string, the tests and
     * the artifact all read from — adding a digit here should not mean editing four places, and a
     * mapping that disagreed with its own documented rule string is the failure worth designing out.
     */
    val LEAD_DIGIT_FIXES: Map<Char, Char> = mapOf('7' to '1', '8' to '0')
    /**
     * How a box's price is chosen: the value read most often, largest on a tie.
     *
     * A plain count of what the reader returned, and nothing else. The reader used to be asked for a
     * `legible` verdict on each of its own readings, which then both weighted this vote and could drop a
     * box outright — a model deciding what reached the output, on evidence no one could recount. Removed:
     * a reading either repeats across frames or it does not, and that is checkable.
     */
    const val VOTE_RULE = "mode of the prices read; ties -> largest"
    const val AGREEMENT_RULE = "blank the output when fewer than MIN_AGREEMENT of the reads back the winner"

    // The corroboration rule used to sit here — "blank the output unless two INDEPENDENT reader
    // requests agree on the price". It is gone, because the arithmetic behind it could not be
    // satisfied: Stage 4 splits its pool into requests of `OcrStage.BATCH_SIZE` (five), so any board
    // whose gates left it five canvases or fewer was read in exactly ONE request and could never reach
    // two. Every row of such a board was blanked — a capture with prices on the canvases and none in
    // the payload, reported as a complete run. A gate that fails on a whole class of perfectly good
    // input is not a safeguard.
    //
    // What guarded against the failure it was aimed at — a model reading one canvas wrong and then
    // repeating itself — is [MIN_REPEATS], below, which is now enforced: a price has to be read more
    // than once before it commits, from anywhere in the pool. The per-row `corroborating_batches` and
    // `batches_seen` counts are still reported, because knowing how a price was distributed across the
    // requests is worth having; they simply no longer decide anything.

    const val REASON_UNSTABLE = "price unstable across frames"
    const val REASON_UNMATCHED = "no matching row position or price"
    const val REASON_LOW_AGREEMENT = "winning price holds too few of the reads"
    const val REASON_UNREPEATED =
        "the price was read only once — no second read agreed with it"

    /**
     * How many reads must agree on a price before it is committed.
     *
     * Two is the least that can be called corroboration. A price read ONCE is not a consensus, it is one
     * observation, and nothing downstream can weigh it — a board built on it is asserted rather than
     * measured. That is the case that turned a pedestrian crossing sign into two prices.
     *
     * **This is enforced again.** It was declared, documented, and printed in the output's own `rules`
     * block while nothing applied it: the step that used it — a stability drop before the window merge —
     * had been removed, and the batch-corroboration gate that replaced it demanded two separate reader
     * REQUESTS, which a board read in one request could never produce. So the threshold moved onto the
     * committed row, where it says the thing it always meant: two reads of this panel, from anywhere in
     * the pool, arrived at this price.
     */
    val MIN_REPEATS: Int get() = DeviceConfig.int("consensus.min_repeats", 2)

    /**
     * The share of the pooled READS the winning price must hold for the row to be emitted.
     *
     * At a half, a price that most of the evidence contradicts leaves as `""` instead of as a number
     * nothing downstream can question.
     */
    val MIN_AGREEMENT: Double get() = DeviceConfig.double("consensus.min_agreement", 0.5)

    // ----------------------------------------------------------------------- //
    // types
    // ----------------------------------------------------------------------- //

    /**
     * One successfully parsed region: a label, the slot it held, the price read in it, and WHICH
     * independent reader request read it.
     *
     * [batch] is reported, not judged. It decided whether a row shipped while the corroboration gate
     * existed; that gate is gone (see the note where its rule string used to be) because a board read in
     * a single request could never satisfy it. A document written before Stage 4 split its requests
     * carries no batch and reads as batch 1 throughout, which is now harmless rather than fatal.
     */
    data class ParsedRegion(
        val label: String,
        val rowPosition: Int,
        val price: Double,
        val batch: Int = 1
    )

    /**
     * One frame that detected at least one region.
     *
     * [regionCount] is the count of regions **detected**, not of regions that parsed: detection is the
     * evidence for the row count even when OCR could not read some of them.
     */
    data class Frame(
        val key: String,
        val regionCount: Int,
        val regions: List<ParsedRegion>,
        /** The reader request this frame was read in; 1 when the document does not say. */
        val batch: Int = 1
    )

    /** A region that could not be parsed, and why. It still held its row slot. */
    data class SkippedRegion(val frame: String, val region: String, val rowPosition: Int, val reason: String)

    /** A frame that contributed nothing, and why. */
    data class EmptyFrame(val frame: String, val reason: String)

    /**
     * A region whose leading digit [toPrice] repaired on the way in — see [correctLeadDigit].
     *
     * Recorded because the repair happens BEFORE the vote, which means the digits the reader actually
     * returned exist nowhere else by the time anything downstream is looked at. Without this a board
     * read entirely as `7.909` would ship `1.909` with every artifact agreeing and nothing anywhere
     * saying a substitution had taken place.
     *
     * [digits] is the reader's own string, untrimmed, so the record says what was read rather than
     * what it was turned into.
     */
    data class LeadDigitCorrection(
        val frame: String,
        val region: String,
        val rowPosition: Int,
        val from: Char,
        val to: Char,
        val digits: String
    )

    /** [parseFrames]'s result: the usable frames plus everything that was left out. */
    data class Parsed(
        val frames: List<Frame>,
        val emptyFrames: List<EmptyFrame>,
        val skipped: List<SkippedRegion>,
        /** Regions whose leading digit was repaired at parse time. Reporting only — see the type. */
        val corrections: List<LeadDigitCorrection> = emptyList()
    )

    /** One box label's pooled reads across every frame it appeared on. */
    data class Box(
        val label: String,
        val rowPosition: Int,
        /** Consensus of [prices] — their [maxMode]. */
        val price: Double,
        val prices: List<Double>,
        /** Parallel to [prices]: which reader request each one came from. */
        val batches: List<Int> = emptyList()
    )

    /** One resolved board row: the consensus price plus which box labels fed it. */
    data class Row(
        val rowPosition: Int,
        val label: String,
        val price: Double,
        val prices: List<Double>,
        val mergedFrom: List<String>,
        /** Parallel to [prices]: which reader request each one came from. */
        val batches: List<Int> = emptyList(),
        /**
         * This panel's place on the PHYSICAL board, 1 = top — the number that leaves the stage as
         * `row_num`. Resolved from [label] against the document's `board_order`; 0 until it is, and
         * [boardRowOr] falls back to [rowPosition] for a document that carries no order.
         */
        val boardRow: Int = 0
    ) {
        /** [boardRow] when it is known, else the box slot — never 0, so a consumer always has a row. */
        val boardRowOr: Int get() = if (boardRow >= 1) boardRow else rowPosition

        val reads: Int get() = prices.size

        /** How many pooled reads agree with the committed [price] — a count, for the report. */
        val agreeingReads: Int get() = prices.count { pricesMatch(it, price) }

        /**
         * Share of the pooled reads backing the committed [price], 0..1.
         *
         * A plain count. It was a weighted share while the reader's own legibility verdict discounted
         * some reads; that verdict is gone, so this is what it always effectively measured.
         */
        val agreement: Double
            get() = if (prices.isEmpty()) 0.0 else round3(agreeingReads.toDouble() / prices.size)

        /**
         * How many DIFFERENT reader requests arrived at the committed [price].
         *
         * A request counts if ANY of its reads matched. Reported only — it decided whether a row shipped
         * until that gate proved unsatisfiable for a board read in a single request; it is kept because
         * how a price spread across the requests is the first thing to look at when one is wrong.
         */
        val corroboratingBatches: Int
            get() = prices.indices
                .filter { pricesMatch(prices[it], price) }
                .mapNotNull { batches.getOrNull(it) }
                .distinct()
                .size

        /**
         * Why this row's price is not being emitted, or null when it is.
         *
         * Two gates, in order of how damning they are. **Repetition first**: a price read once is one
         * observation, and a model that misreads a panel says so as confidently as it says anything —
         * so a single read is exactly the shape of a confident mistake and cannot commit. Then
         * agreement: a winner that most of the pooled evidence contradicts.
         *
         * Both count READS and not requests. The gate that counted requests could not be satisfied by a
         * board read in one request, which is most boards — see the note where the corroboration rule
         * used to be.
         */
        val withheld: String?
            get() = when {
                agreeingReads < MIN_REPEATS -> REASON_UNREPEATED
                agreement < MIN_AGREEMENT -> REASON_LOW_AGREEMENT
                else -> null
            }

        /**
         * The price as it leaves Stage 5: `X.XXX`, or `""` when it fails [formatOutput] **or** when too
         * little of the evidence backs it. [price] keeps the float either way, so a blanked row can
         * still be explained.
         */
        val output: String get() = if (withheld != null) "" else formatOutput(price)

        /**
         * Whether [correctLeadDigit] rewrote this row's leading digit on the way out.
         *
         * Exists so the correction is never silent. [output] is what ships and [price] keeps the float
         * the consensus actually arrived at, so the two disagree by a whole unit on a corrected row —
         * and without this flag the only way to notice would be to spot that disagreement by eye in the
         * artifact. Read by the digest and by `lead_digit_corrected` in the JSON.
         */
        val leadDigitCorrected: Boolean
            get() = withheld == null && formatShape(price).firstOrNull() in LEAD_DIGIT_FIXES.keys
    }

    /**
     * A box that did not make it onto the board: it never settled on a price ([REASON_UNSTABLE], with a
     * null [price]) or it fit no row of the winning board ([REASON_UNMATCHED]).
     */
    data class Discard(
        val label: String,
        val rowPosition: Int,
        val price: Double?,
        val prices: List<Double>,
        val reason: String
    )

    /** One position of the sliding window: its base boxes and the board they produced. */
    data class WindowPosition(
        val start: Int,
        val base: List<String>,
        val merges: Int,
        val board: List<Row>,
        val discarded: List<Discard>
    )

    /** The whole window sweep: every position tried, and the modal board that won. */
    data class Analysis(
        val extraRows: Int,
        val windows: List<WindowPosition>,
        /** How many windows produced the winning board; null when no window slid (no extras). */
        val modeCount: Int?,
        val winningStart: Int?,
        val board: List<Row>,
        val discarded: List<Discard>
    )

    /** Stage-5 output in the three shapes the pipeline needs. */
    data class Result(
        /** `ok` · `incomplete` (fewer rows than the row count) · `no_stable_rows` · `empty`. */
        val status: String,
        val rowCount: Int,
        val rows: List<Row>,
        val discarded: List<Discard>,
        /** The full response — answer first, diagnostics under `debug`. The debug artifact. */
        val json: String,
        /** `{"readings":[{row_num,box,fuel_type,price}]}` — Stage 6's contract. */
        val readingsJson: String,
        /** Human-readable digest, streamed to the dashboard in place of the agent's reasoning. */
        val report: String
    )

    /** Raised for a region that cannot be parsed; the message is the recorded reason. */
    class RegionParseException(message: String) : Exception(message)

    // ----------------------------------------------------------------------- //
    // primitives
    // ----------------------------------------------------------------------- //

    /**
     * Exactly how many digits a price has: one integer digit and three decimals — `X.XXX`.
     *
     * A read LONGER than this is not a bigger number, it is a read that picked up something that is
     * not part of the price — a neighbouring panel's digit, a pump number, a stray lit segment to the
     * left. Every board here shows a single integer digit, so the price is always the RIGHTMOST digits
     * and the extras are always on the left, which is why the trim takes the tail.
     *
     * A read SHORTER than this is a misread — the reader dropped a digit — and is rejected outright
     * rather than being padded into something plausible.
     */
    val PRICE_DIGITS: Int get() = DeviceConfig.int("consensus.price_digits", 4)

    /**
     * Turns a digit string into a price: keep the **last [PRICE_DIGITS] digits**, then place the
     * decimal point after the first of those.
     *
     * ```
     * "1784"   -> 1.784     (exactly 4 digits)
     * "231789" -> 1.789     (trimmed to "1789", then the decimal)
     * "247"    -> rejected  (too few digits to be X.XXX)
     * "5"      -> rejected
     * ```
     *
     * **A short read is rejected here, not blanked later.** It would otherwise enter the pooling as a
     * plausible number — `"247"` becoming `2.47` — where it can vote, win a mode, and commit a price
     * the board never showed. By the time the guard at the far end sees it, the digit width is gone:
     * `2.47` from `"247"` is indistinguishable from `2.47` from `"2470"`. Rejecting at the point of
     * parse is the only place the distinction still exists, and it keeps bad data out of the consensus
     * instead of correcting for it afterwards. The rejection is recorded under `debug.skipped_regions`
     * with its reason, exactly like an unreadable panel.
     *
     * **Consequence:** a genuine four-decimal price (`X.XXXX`) read as five digits is trimmed to four
     * and comes out as a different number — `"17845"` becomes `7.845`, not `1.7845`. That is correct
     * only while every board carries three decimals; change [PRICE_DIGITS] to 5 if a four-decimal
     * board ever appears.
     *
     * Throws [RegionParseException] when [digits] is empty, holds anything other than `0-9`, or is
     * shorter than [PRICE_DIGITS] — an unread panel (`""`) is reported the same way.
     */
    fun toPrice(digits: String): Double {
        val d = digits.trim()
        if (d.isEmpty() || !d.all { it in '0'..'9' }) {
            throw RegionParseException("non-numeric digits: '$digits'")
        }
        if (d.length < PRICE_DIGITS) {
            throw RegionParseException("too few digits for X.XXX: '$digits'")
        }
        val price = d.takeLast(PRICE_DIGITS)
        // The X.XXX string is built, then normalized, and the normalized value is what enters the
        // stage. Placing the decimal after the first of four digits IS the X.XXX shape — so by the time
        // this returns, every price in Stage 5 has both rules already applied to it and no later step
        // has to think about either. See [correctLeadDigit].
        return correctLeadDigit("${price[0]}.${price.substring(1)}").toDouble()
    }

    /**
     * The substitution [toPrice] would apply to [digits], or null when it would leave them alone.
     *
     * Exists only so the parse loop can RECORD the correction — `toPrice` returns a bare `Double` and
     * the original leading digit is gone by then. Reads the same last-[PRICE_DIGITS] trim `toPrice`
     * does, so the two cannot disagree about which digit is the leading one.
     *
     * Returns null for a digit string `toPrice` would reject; a read that never becomes a price was
     * not corrected, it was skipped, and the two are recorded separately.
     */
    fun leadDigitFixFor(digits: String): Pair<Char, Char>? {
        val d = digits.trim()
        if (d.length < PRICE_DIGITS || !d.all { it in '0'..'9' }) return null
        val lead = d.takeLast(PRICE_DIGITS)[0]
        return LEAD_DIGIT_FIXES[lead]?.let { lead to it }
    }

    /** Most frequent value; on a tie in frequency, the largest of the tied ones. */
    fun maxMode(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val counts = values.groupingBy { it }.eachCount()
        val top = counts.values.max()
        return counts.entries.filter { it.value == top }.maxOf { it.key }
    }

    /** [maxMode] over ints — used for the row count. */
    fun maxModeInt(values: List<Int>): Int? {
        if (values.isEmpty()) return null
        val counts = values.groupingBy { it }.eachCount()
        val top = counts.values.max()
        return counts.entries.filter { it.value == top }.maxOf { it.key }
    }

    /**
     * Price equality — exact, and the ONE rule in this file.
     *
     * It used to round to six decimals "so float representation can't split a match", which sounds
     * prudent and was in fact a second, looser rule sitting beside a stricter one: [maxMode] groups
     * prices with `groupingBy`, i.e. on exact `Double` equality, and it is [maxMode] that picks the
     * winner every one of these comparisons is then made against. Two prices could therefore be
     * counted as different when the vote was taken and the same when the agreement was measured — a
     * row whose `agreement` was computed over a value the vote never grouped.
     *
     * Exact is right here rather than merely simpler, because a price is never CALCULATED. Every one of
     * them comes out of [toPrice], which parses a digit string — `"1789"` → `1.789` — and parsing the
     * same digits always yields the same bits. Nothing averages, scales or accumulates them, so there
     * is no arithmetic anywhere for a representation error to enter through: two prices are equal
     * exactly when the reader returned the same digits, which is the thing this is meant to test.
     */
    fun pricesMatch(a: Double, b: Double): Boolean = a == b

    private fun round3(v: Double): Double = Math.round(v * 1000.0) / 1000.0

    /** `1.784` → `"1.784"`, `5.0` → `"5"`. Lossless, no trailing zeros. Diagnostics only. */
    fun formatPrice(p: Double): String =
        BigDecimal.valueOf(p).stripTrailingZeros().toPlainString()

    /**
     * **The output guard.** A committed price may have exactly one shape — `X.XXX`, a single integer
     * digit and three decimals. Anything else is **blanked**: this returns `""` rather than a number.
     *
     * ```
     * 1.784 -> "1.784"     1.8  -> "1.800"     (a real X.XXX price, trailing zero restored)
     * 0.959 -> "0.959"                         (a LEADING ZERO is a valid price, see below)
     * 5.0   -> ""          12.34 -> ""         (no decimals, or two integer digits)
     * 1.7845 -> ""                             (finer than X.XXX can hold)
     * ```
     *
     * **The integer digit may be zero.** This once demanded `>= 1.0`, on the assumption that a board
     * always shows a leading digit of 1 or more. It does not — `0.959` is a real price — and the
     * assumption did active harm: the guard blanked a correct reading, the blank became a gap in the
     * consolidated answer, and the verification pass then "filled" that gap with a plausible but wrong
     * `1.959`. A guard is meant to reject what a board cannot display, and `0.959` is something a board
     * displays. The rule is the SHAPE, not the magnitude.
     *
     * This is the last thing that happens to a price, deliberately: everything upstream is free to
     * pool, vote and merge, and this decides whether the result is a shape a fuel board can actually
     * display. Emitting nothing beats emitting a number that cannot be a price — a blank is visibly a
     * non-answer, whereas `5.0` looks like a reading and would be believed downstream.
     *
     * It pairs with the width check in [toPrice], which is the half of the rule that has to run at
     * parse time: a short read is rejected there, before it can vote, because the digit width no
     * longer exists by the time a price reaches here. What this guard still catches is a leading-zero
     * read (`"0247"` → `0.247`, no integer digit) and anything a future change might let through.
     */
    fun formatOutput(price: Double): String =
        // The guard runs FIRST and the correction second, on the string the guard produced. Two
        // functions rather than one because they answer different questions — "is this a shape a board
        // can show" and "is this leading digit one a board can show" — and because
        // [Row.leadDigitCorrected] has to be able to ask what the price looked like before the
        // substitution without re-deriving the guard.
        correctLeadDigit(formatShape(price))

    /**
     * The shape half of the output guard: `X.XXX` or `""`, with no digit substitution.
     *
     * Split out of [formatOutput] so the pre-correction string is available to
     * [Row.leadDigitCorrected]. Callers wanting the price as it SHIPS want [formatOutput].
     */
    fun formatShape(price: Double): String {
        // Exactly one integer digit — which may be 0. Negative is impossible from toPrice, and is
        // rejected here rather than trusted, since this is the last thing standing before the payload.
        if (price < 0.0 || price >= 10.0) return ""
        // No finer than thousandths — a price carrying more precision than X.XXX did not come from a
        // board this pipeline can read.
        val thousandths = Math.round(price * 1000.0)
        if (kotlin.math.abs(price * 1000.0 - thousandths) > 1e-6) return ""
        return String.format(Locale.US, "%.3f", price)
    }

    /**
     * Rewrites an impossible leading digit on an `X.XXX` price: **7 becomes 1, 8 becomes 0.**
     *
     * ```
     * 7.909 -> 1.909        8.909 -> 0.909
     * 1.909 -> 1.909        2.459 -> 2.459     (untouched — a leading 1 or 2 is a real price)
     * ```
     *
     * ### Why these two digits, and only these two
     * It is a seven-segment misread, corrected where the misread is unambiguous. A price post shows its
     * digits as lit segments, and the fault this pipeline actually meets is a segment reading as lit
     * when it is not — a ghost the dimming in Stage 3 exists to crush, surviving on a bright board or a
     * reflection. On a seven-segment glyph:
     *
     *  - a **1** with a spurious top bar and top-left stroke reads as a **7**;
     *  - a **0** with a spurious middle bar reads as an **8**.
     *
     * Both are additive — the wrong glyph is the right one plus segments — which is why the substitution
     * only ever runs in that direction. It does not turn a 1 into a 7, and it must not: that would be
     * inventing a reading rather than repairing one.
     *
     * ### Why it is safe to assert
     * A leading 7 or 8 is not a price a fuel board can display in this fleet's market. Litre prices run
     * from about 0.9 to about 2.5, so `7.909` and `8.909` are not readings that need weighing against
     * the alternative — they are impossible, and the only question is what was actually on the board.
     * The digits either side of them are read correctly and left alone, which is what makes the answer
     * a single value rather than a guess.
     *
     * ### It runs TWICE, at both ends of the stage, on purpose
     *
     * **At parse time, in [toPrice] — this is the important one.** Every price entering Stage 5 is
     * normalized to `X.XXX` with its leading digit already repaired, so every later step works on
     * prices that are all in the one shape and no step has to reason about a ghosted digit.
     *
     * That placement is what makes the VOTE correct, which was the argument for doing it here rather
     * than only at the output. A board read five times, twice with a ghosted leading segment, produces
     * `1.909, 1.909, 1.909, 7.909, 7.909`. Uncorrected those are five reads split three-to-two: the
     * mode is `1.909` with an agreement share of 0.6, and two of the board's own reads are counted as
     * evidence AGAINST it. But they are not evidence against it — they are the same panel showing the
     * same price with one bad segment. Normalized first, the row is 5/5 on `1.909`, which is what the
     * evidence actually says. In the other direction the effect is larger still: a panel read only
     * twice, both times ghosted, used to commit `7.909` on a full agreement share and be rewritten at
     * the output — the right answer, arrived at by a vote that was measuring the wrong thing.
     *
     * **At output time, in [formatOutput] — a backstop.** Every Stage 5 price has come through
     * [toPrice], so this should never fire; it is here because "should never" is a claim about code
     * that will keep changing, and because the shape guard beside it would otherwise commit `7.909`
     * quite happily — the SHAPE is valid, one integer digit and three decimals. The choice at the
     * output was never between correcting and rejecting; it was between correcting and shipping
     * `7.909` as a fuel price.
     *
     * `ConsolidationReviewer` applies it a third time, because a reviewed row replaces Stage 5's and
     * the model's answer never passes through [toPrice].
     *
     * ### Every correction is recorded
     * A price that changes between what the reader said and what ships cannot be silent. Parse-time
     * corrections go to `debug.lead_digit_corrections` with the frame, the region and the digits the
     * reader returned, and are counted in the digest; an output-time correction — the backstop firing —
     * shows as [Row.leadDigitCorrected], `lead_digit_corrected` in the artifact, and a `7->1` marker
     * on the digest's row.
     *
     * @param formatted an `X.XXX` string as [formatOutput] produces. Anything else is returned
     *   unchanged: this repairs a known shape and is not a parser.
     */
    fun correctLeadDigit(formatted: String): String {
        if (formatted.length != 5 || formatted[1] != '.') return formatted
        val corrected = LEAD_DIGIT_FIXES[formatted[0]] ?: return formatted
        return corrected + formatted.substring(1)
    }

    // ----------------------------------------------------------------------- //
    // input normalization
    // ----------------------------------------------------------------------- //

    /**
     * `(key, value)` pairs from a JSON object or array, **in input order**.
     *
     * Anything else — missing, null, a scalar — reads as empty rather than throwing, so a malformed
     * container costs its own subtree and nothing else. Array indices become the 1-based keys
     * `"1"`, `"2"`, … so diagnostics name them the same way an object would.
     */
    fun entries(container: Any?): List<Pair<String, Any?>> = when (container) {
        is JSONObject -> container.keys().asSequence().map { it to container.opt(it) }.toList()
        is JSONArray -> (0 until container.length()).map { (it + 1).toString() to container.opt(it) }
        else -> emptyList()
    }

    /** The region's box label. Throws if absent or blank. */
    private fun regionLabel(region: JSONObject): String {
        if (!region.has("box")) throw RegionParseException("missing key: 'box'")
        val label = region.get("box")
        if (label !is String || label.isBlank()) {
            throw RegionParseException("unusable box label: ${pyRepr(label)}")
        }
        return label.trim()
    }

    /**
     * The price read in this region. A JSON number reads the same as the equivalent string, and an
     * unusable value is reported with the ORIGINAL value in the reason, not its stringification.
     */
    private fun regionPrice(region: JSONObject): Double {
        if (!region.has("digits")) throw RegionParseException("missing key: 'digits'")
        val raw = region.get("digits")
        val text = if (raw == JSONObject.NULL) "None" else raw.toString()
        val d = text.trim()
        if (d.isEmpty() || !d.all { it in '0'..'9' }) {
            throw RegionParseException("non-numeric digits: ${pyRepr(raw)}")
        }
        // Reported with the ORIGINAL value, so the skip reason names what the reader actually said.
        if (d.length < PRICE_DIGITS) {
            throw RegionParseException("too few digits for X.XXX: ${pyRepr(raw)}")
        }
        return toPrice(d)
    }

    /**
     * `board_order` as `label → 1-based board row`, or empty when the document does not carry it.
     *
     * A duplicate label keeps its first position: the order is meant to name each panel once, and a
     * repeat would otherwise silently move a row.
     */
    fun boardRows(ocr: JSONObject): Map<String, Int> {
        val arr = ocr.optJSONArray("board_order") ?: return emptyMap()
        val out = LinkedHashMap<String, Int>()
        for (i in 0 until arr.length()) {
            val label = arr.optString(i).trim()
            if (label.isNotEmpty()) out.putIfAbsent(label, out.size + 1)
        }
        return out
    }

    /**
     * Normalizes frames and regions, in input order, tolerating bad data.
     *
     * Frames whose detection found no regions are dropped up front: they carry no prices and a zero
     * row count would otherwise get a vote it has not earned. A region that cannot be parsed is
     * skipped on its own and recorded, so one bad region costs one row rather than the whole file.
     */
    fun parseFrames(ocr: JSONObject): Parsed {
        val frames = mutableListOf<Frame>()
        val emptyFrames = mutableListOf<EmptyFrame>()
        val skipped = mutableListOf<SkippedRegion>()
        val corrections = mutableListOf<LeadDigitCorrection>()

        for ((frameKey, frame) in entries(ocr.opt("boards"))) {
            // Which reader request read this frame. Absent on a document written before Stage 4 split
            // its requests: those all land in batch 1, which correctly leaves every price on such a file
            // uncorroborated rather than silently passing it.
            val frameBatch = if (frame is JSONObject) frame.optInt("batch", 1) else 1
            val regionEntries = entries(if (frame is JSONObject) frame.opt("regions") else null)
            if (regionEntries.isEmpty()) {
                emptyFrames.add(
                    EmptyFrame(
                        frameKey,
                        if (frame is JSONObject) "no regions detected" else "frame is not an object"
                    )
                )
                continue
            }

            val regions = mutableListOf<ParsedRegion>()
            // row_position is the region's slot in input order. A region that fails to parse still
            // holds its slot, so rows below it do not shift up.
            for ((i, entry) in regionEntries.withIndex()) {
                val (regionKey, region) = entry
                val rowPosition = i + 1
                try {
                    if (region !is JSONObject) {
                        throw RegionParseException("malformed region: not a JSON object")
                    }
                    // Label first, then digits — the reference evaluates them in that order, so a
                    // region broken both ways is reported by its label problem.
                    val label = regionLabel(region)
                    // A `legible` field may still be present in an OCR document written before the flag
                    // was removed. It is IGNORED rather than honoured: the reader's verdict on its own
                    // reading no longer decides anything, and a fixture must consolidate the same way
                    // today's live run does.
                    regions.add(ParsedRegion(label, rowPosition, regionPrice(region), frameBatch))
                    // Noted AFTER the parse succeeded, so a region that was skipped is not also
                    // reported as corrected. Reporting only — `regionPrice` has already applied it.
                    leadDigitFixFor(region.optString("digits"))?.let { (from, to) ->
                        corrections.add(
                            LeadDigitCorrection(
                                frameKey, regionKey, rowPosition, from, to,
                                region.optString("digits")
                            )
                        )
                    }
                } catch (e: RegionParseException) {
                    skipped.add(SkippedRegion(frameKey, regionKey, rowPosition, e.message.orEmpty()))
                }
            }

            frames.add(Frame(frameKey, regionEntries.size, regions, frameBatch))
        }

        return Parsed(frames, emptyFrames, skipped, corrections)
    }

    // ----------------------------------------------------------------------- //
    // stages
    // ----------------------------------------------------------------------- //

    /** Pools each label's reads across frames, in **first-appearance order** — never sorted. */
    fun mergeBoxes(frames: List<Frame>): List<Box> {
        val merged = LinkedHashMap<String, MutableBox>()
        for (frame in frames) {
            for (region in frame.regions) {
                val box = merged.getOrPut(region.label) { MutableBox(region.label, region.rowPosition) }
                box.prices.add(region.price)
                box.batches.add(region.batch)
            }
        }
        // LinkedHashMap preserves first-appearance order — deliberately never sorted.
        return merged.values.map { r ->
            Box(r.label, r.rowPosition, maxMode(r.prices)!!, r.prices.toList(), r.batches.toList())
        }
    }

    /** Max row position per frame — its region count, as positions run `1..n`. */
    fun frameRowMaxima(frames: List<Frame>): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        frames.forEach { out[it.key] = it.regionCount }
        return out
    }

    // isUnstable / dropUnstable used to live here — step 5, the stability drop. Removed, not disabled:
    // a predicate nothing calls is a predicate that gets called again by accident. The reasoning, and
    // what replaced the guard it provided, is in the class note.

    /**
     * Fits each piece into [window], treated as the base board format.
     *
     * A piece merges into the first base row with the same `row_position`; failing that, into the
     * first base row with a matching price. A piece that satisfies neither is discarded. Price
     * matching uses each base row's **pre-pool** price throughout, so the order pieces arrive in
     * can't move the target mid-sweep — the consensus is re-derived only once every piece has landed.
     */
    fun fitPieces(window: List<Box>, pieces: List<Box>): Pair<List<Row>, List<Discard>> {
        val base = window.map {
            FitRow(it.label, it.rowPosition, it.price, it.prices.toMutableList(), it.batches.toMutableList())
        }
        val discarded = mutableListOf<Discard>()

        for (piece in pieces) {
            val target = base.firstOrNull { it.rowPosition == piece.rowPosition }
                ?: base.firstOrNull { pricesMatch(it.basePrice, piece.price) }
            if (target == null) {
                discarded.add(
                    Discard(piece.label, piece.rowPosition, piece.price, piece.prices, REASON_UNMATCHED)
                )
                continue
            }
            target.mergedFrom.add(piece.label)
            target.prices.addAll(piece.prices)
            target.batches.addAll(piece.batches)
        }

        // Re-derive the consensus price now that reads have been pooled.
        val rows = base.map {
            Row(
                it.rowPosition, it.label,
                maxMode(it.prices) ?: it.basePrice,
                it.prices.toList(), it.mergedFrom.toList(), it.batches.toList()
            )
        }
        return rows to discarded
    }

    /** Identity of a board, ignoring labels and pooled reads: row position → price. */
    fun boardSignature(board: List<Row>): List<Pair<Int, Double>> =
        board.map { it.rowPosition to it.price }.sortedWith(compareBy({ it.first }, { it.second }))

    /** Slides a [rowCount]-wide window over [boxes]; the modal board wins. */
    fun windowMerge(boxes: List<Box>, rowCount: Int): Analysis {
        val extraRows = boxes.size - rowCount

        if (extraRows <= 0) {
            // Nothing to merge away: the boxes ARE the board.
            val board = boxes.map {
                Row(it.rowPosition, it.label, it.price, it.prices, listOf(it.label), it.batches)
            }
            return Analysis(0, emptyList(), null, null, board, emptyList())
        }

        val windows = (0..extraRows).map { start ->
            val window = boxes.subList(start, start + rowCount)
            val pieces = boxes.subList(0, start) + boxes.subList(start + rowCount, boxes.size)
            val (board, discarded) = fitPieces(window, pieces)
            WindowPosition(start, window.map { it.label }, pieces.size - discarded.size, board, discarded)
        }

        // Mode over whole boards; a frequency tie resolves to the earliest window.
        val counts = windows.groupingBy { boardSignature(it.board) }.eachCount()
        val top = counts.values.max()
        val winner = windows.first { counts[boardSignature(it.board)] == top }

        return Analysis(extraRows, windows, top, winner.start, winner.board, winner.discarded)
    }

    // ----------------------------------------------------------------------- //
    // entry point + output shaping
    // ----------------------------------------------------------------------- //

    /** Runs the whole reduction over one Stage-4 OCR response. */
    fun reduce(ocr: JSONObject, fuelNames: Map<String, String> = emptyMap()): Result {
        val parsed = parseFrames(ocr)
        val boxes = mergeBoxes(parsed.frames)
        val maxima = frameRowMaxima(parsed.frames)
        val rowCount = maxModeInt(maxima.values.toList())
        val result = buildOutput(ocr, parsed, boxes, maxima, rowCount, fuelNames)
        Log.d(
            TAG,
            "status=${result.status} rows=${result.rowCount} boxes=${boxes.size} " +
                "discarded=${result.discarded.size} skippedRegions=${parsed.skipped.size} " +
                "emptyFrames=${parsed.emptyFrames.size} " +
                "blanked=${result.rows.count { it.output.isEmpty() }} " +
                "prices=${result.rows.map { it.output.ifEmpty { "-" } }}"
        )
        return result
    }

    /** Assembles the response: answer first, diagnostics under `debug`. */
    fun buildOutput(
        ocr: JSONObject,
        parsed: Parsed,
        boxes: List<Box>,
        maxima: Map<String, Int>,
        rowCount: Int?,
        /**
         * Box letter → the fuel token Stage 3's layout read off the board, when it read one. Empty when
         * Step G did not run or was refused, which is when the row's name has to come from elsewhere.
         */
        fuelNames: Map<String, String> = emptyMap()
    ): Result {
        val framesIn = entries(ocr.opt("boards")).size

        // Intake counters, shared by both exits. Key order matches the reference.
        fun intake(unstableDropped: Int, extraRows: Int) = JSONObject().apply {
            put("frames_in", framesIn)
            put("frames_used", parsed.frames.size)
            put("frames_empty", parsed.emptyFrames.size)
            put("regions", maxima.values.sum())
            put("regions_skipped", parsed.skipped.size)
            // How many reads had a ghosted leading digit repaired on the way in. A non-zero count here
            // is a headline fact about the board: every price behind it is a digit different from what
            // the reader returned.
            put("regions_lead_corrected", parsed.corrections.size)
            put("boxes", boxes.size)
            put("unstable_dropped", unstableDropped)
            put("extra_rows", extraRows)
        }

        fun diagnostics() = JSONObject().apply {
            put("rules", JSONObject().apply {
                put("row_count", ROW_COUNT_RULE)
                put("stability", STABILITY_RULE)
                put("merge_match", MATCH_RULE)
                put("vote", VOTE_RULE)
                put("agreement", AGREEMENT_RULE)
                put("output_format", OUTPUT_FORMAT_RULE)
                put("lead_digit", LEAD_DIGIT_RULE)
                put("thresholds", JSONObject().apply {
                    put("min_repeats", MIN_REPEATS)
                    put("min_agreement", MIN_AGREEMENT)
                })
            })
            put("row_count", JSONObject().apply {
                put("per_frame_max", JSONObject().apply { maxima.forEach { (k, v) -> put(k, v) } })
                put("chosen", rowCount ?: JSONObject.NULL)
            })
            put("empty_frames", JSONArray().apply {
                parsed.emptyFrames.forEach {
                    put(JSONObject().apply { put("frame", it.frame); put("reason", it.reason) })
                }
            })
            put("skipped_regions", JSONArray().apply {
                parsed.skipped.forEach {
                    put(JSONObject().apply {
                        put("frame", it.frame)
                        put("region", it.region)
                        put("row_position", it.rowPosition)
                        put("reason", it.reason)
                    })
                }
            })
            put("lead_digit_corrections", JSONArray().apply {
                parsed.corrections.forEach {
                    put(JSONObject().apply {
                        put("frame", it.frame)
                        put("region", it.region)
                        put("row_position", it.rowPosition)
                        // What the reader said, and what it was read as instead. Both, because the
                        // repaired value is everywhere else in this artifact and the original is
                        // nowhere else at all.
                        put("digits", it.digits)
                        put("from", it.from.toString())
                        put("to", it.to.toString())
                    })
                }
            })
            put("boxes", boxesJson(boxes))
        }

        // No stability drop. Every box goes forward — see step 5 in the class note for why the drop
        // was removed and what still guards the committed price.
        //
        // `lost` stays in the shape it had, and empty: the JSON's `discarded` array and the
        // `unstable_dropped` counter are part of this stage's contract with the dashboard and the
        // replay fixtures, and a field that vanished would read as a parse failure rather than a zero.
        val stable = boxes
        val lost = emptyList<Discard>()

        if (stable.isEmpty() || rowCount == null || rowCount == 0) {
            // "no_stable_rows" is now only reachable via a null/zero row count, since nothing drops a
            // box for instability any more. Kept as a distinct status: a run with boxes but no usable
            // row count is a different failure from a run with no boxes at all.
            val status = if (boxes.isNotEmpty()) "no_stable_rows" else "empty"
            val json = JSONObject().apply {
                putHeader(ocr)
                put("status", status)
                put("summary", JSONObject().apply {
                    put("row_count", rowCount ?: 0)
                    put("prices", JSONArray())
                    put("discarded", lost.size)
                    put("input", intake(0, 0))
                })
                put("rows", JSONArray())
                put("discarded", discardsJson(lost))
                put("debug", diagnostics())
            }
            val result = Result(
                status = status,
                rowCount = rowCount ?: 0,
                rows = emptyList(),
                discarded = lost,
                json = json.toString(2),
                readingsJson = JSONObject().put("readings", JSONArray()).toString(),
                report = ""
            )
            return result.copy(report = buildReport(result, parsed, json))
        }

        val analysis = windowMerge(stable, rowCount)
        // The merge is done; give every surviving row the board row its LETTER stands for, and order the
        // answer by that. Sorting on the slot would order the answer by where each panel happened to sit
        // on the canvas it was first seen on, which is not the board's order whenever the two diverge.
        val ranks = boardRows(ocr)
        val rows = analysis.board
            .map { r -> ranks[r.label]?.let { r.copy(boardRow = it) } ?: r }
            .sortedBy { it.boardRowOr }
        val discarded = lost + analysis.discarded
        val totalWindows = analysis.windows.size
        val windowAgreement = if (totalWindows > 0) "${analysis.modeCount}/$totalWindows" else "n/a"
        val status = if (rows.size == rowCount) "ok" else "incomplete"

        val json = JSONObject().apply {
            putHeader(ocr)
            put("status", status)
            put("summary", JSONObject().apply {
                put("row_count", rowCount)
                put("prices", JSONArray().apply { rows.forEach { put(it.output) } })
                put("discarded", discarded.size)
                put("format_blanked", rows.count { it.output.isEmpty() })
                put("window_agreement", windowAgreement)
                put("input", intake(0, analysis.extraRows))
            })
            put("rows", JSONArray().apply {
                rows.forEach { r ->
                    put(JSONObject().apply {
                        // Both, and never folded together: `row_num` is the board's row (what ships),
                        // `row_position` the canvas slot the merge worked in. When they disagree, that
                        // disagreement is the thing worth seeing in the artifact.
                        put("row_num", r.boardRowOr)
                        put("row_position", r.rowPosition)
                        put("label", r.label)
                        // `price` is what the consensus produced; `price_out` is what actually leaves
                        // the stage once the X.XXX guard has run. Keeping both makes a blanked row
                        // diagnosable — you can see the value that was rejected.
                        put("price", r.price)
                        put("price_out", r.output)
                        put("format_ok", r.output.isNotEmpty())
                        // True when the leading digit was rewritten on the way out — see
                        // [correctLeadDigit]. Recorded per row because `price` and `price_out` then
                        // differ by a whole unit, and this is what says that was deliberate rather
                        // than a bug somewhere between them.
                        put("lead_digit_corrected", r.leadDigitCorrected)
                        put("reads", r.reads)
                        put("agreeing_reads", r.agreeingReads)
                        // A plain share of the pooled reads: agreeing_reads / reads.
                        put("agreement", r.agreement)
                        // How many INDEPENDENT reader requests produced this price, and out of how many
                        // saw the box at all. A row blanked for corroboration is argued with from here.
                        put("corroborating_batches", r.corroboratingBatches)
                        put("batches_seen", r.batches.distinct().size)
                        put("withheld", r.withheld ?: JSONObject.NULL)
                        put("merged_from", JSONArray().apply { r.mergedFrom.forEach { put(it) } })
                    })
                }
            })
            put("discarded", discardsJson(discarded))
            put("debug", diagnostics().apply {
                put("window", JSONObject().apply {
                    put("size", rowCount)
                    put("winning_start", analysis.winningStart ?: JSONObject.NULL)
                    put("positions", JSONArray().apply {
                        analysis.windows.forEach { w ->
                            put(JSONObject().apply {
                                put("start", w.start)
                                put("base", JSONArray().apply { w.base.forEach { put(it) } })
                                put("merges", w.merges)
                                put("discarded", JSONArray().apply { w.discarded.forEach { put(it.label) } })
                                // compact board identity, for spotting disagreement
                                put("rows", JSONArray().apply {
                                    w.board.sortedBy { it.rowPosition }.forEach { r ->
                                        put(JSONObject().apply {
                                            put("row_position", r.rowPosition)
                                            put("price", r.price)
                                        })
                                    }
                                })
                            })
                        }
                    })
                })
            })
        }

        val result = Result(
            status = status,
            rowCount = rowCount,
            rows = rows,
            discarded = discarded,
            json = json.toString(2),
            readingsJson = buildReadingsJson(rows, fuelNames),
            report = ""
        )
        return result.copy(report = buildReport(result, parsed, json, windowAgreement))
    }

    /**
     * Stage 6's contract: `{"readings":[{row_num,box,fuel_type,price}]}`.
     *
     * `row_num` is the panel's place on the BOARD (see the class doc), so a consumer counting rows top to
     * bottom counts the same rows the board has. It can therefore skip a number — row 2 missing means the
     * board's second panel never settled on a price — which is the honest shape: renumbering the survivors
     * 1..N would quietly move every row below a dropped one up a panel.
     *
     * `fuel_type` comes from Stage 3's layout read ([BoardLayout]) — the token printed on the board
     * beside that panel, keyed by the box letter this row settled on. Stage 4 is digits-only and reads no
     * names, so this is the only place a name can come from that the board itself asserted.
     *
     * Blank when Step G did not run, was refused, or could not read that panel's name. It is never
     * invented from the letter: putting "A" in the payload's fuel field would be a fact nothing checked.
     * The letter travels in `box` regardless, and [EnrichmentStage] fills a blank from the station's
     * fuel list — where the board's own reading is absent, not where it disagrees.
     */
    private fun buildReadingsJson(rows: List<Row>, fuelNames: Map<String, String>): String {
        val arr = JSONArray()
        rows.forEach { r ->
            arr.put(JSONObject().apply {
                put("row_num", r.boardRowOr)
                put("box", r.label)
                put("fuel_type", fuelNames[r.label].orEmpty())
                // The output guard: X.XXX or blank. Stage 6 turns a blank into a JSON null.
                put("price", r.output)
            })
        }
        return JSONObject().put("readings", arr).toString()
    }

    // ----------------------------------------------------------------------- //
    // digest
    // ----------------------------------------------------------------------- //

    /** Human-readable digest; the JSON carries the full detail. */
    private fun buildReport(
        result: Result,
        parsed: Parsed,
        json: JSONObject,
        windowAgreement: String = "n/a"
    ): String = buildString {
        if (result.rows.isEmpty()) {
            appendLine("no rows resolved (${result.status})")
            appendDiscards(result.discarded)
            appendIntake(json, parsed)
            return@buildString
        }

        appendLine("board · ${result.rows.size}/${result.rowCount} rows · $windowAgreement windows agreed")
        result.rows.forEach { r ->
            val agree = String.format(Locale.US, "%.0f%%", r.agreement * 100)
            // A blanked row shows what was rejected, not just a gap — otherwise the digest would give
            // no hint why a row came out empty.
            val shown = if (r.output.isNotEmpty()) r.output
            else "(blank: ${formatPrice(r.price)} is not X.XXX)"
            // A corrected row says so, and says which substitution ran. The digest is where somebody
            // reads a board back, and a price that arrived as 7.909 and ships as 1.909 with nothing
            // marking it would look like the reader had simply got it right.
            val corrected = if (r.leadDigitCorrected) {
                val was = formatShape(r.price).first()
                "  [$was->${LEAD_DIGIT_FIXES[was]} lead digit]"
            } else ""
            appendLine(
                // The BOARD's row, so the digest reads the way the board does. On an input carrying no
                // board_order this is the slot, which is what the reference produces.
                "  row ${r.boardRowOr}  ${r.label}  ${shown.padStart(7)}" +
                    "   (${r.reads} reads, $agree agree, from ${r.mergedFrom.joinToString("+")})" +
                    corrected
            )
        }

        if (result.discarded.isEmpty()) appendLine("  discarded: none") else appendDiscards(result.discarded)

        appendIntake(json, parsed)
        if (result.status != "ok") appendLine("  status: ${result.status}")
    }.trimEnd()

    private fun StringBuilder.appendDiscards(discarded: List<Discard>) {
        discarded.forEach { d ->
            val reads = d.prices.joinToString(", ") { formatPrice(it) }
            appendLine("  discarded ${d.label} (row ${d.rowPosition}) — ${d.reason} [reads: $reads]")
        }
    }

    /** Data-quality lines: what was dropped on the way in. */
    private fun StringBuilder.appendIntake(json: JSONObject, parsed: Parsed) {
        val i = json.getJSONObject("summary").getJSONObject("input")
        appendLine(
            "  from ${i.getInt("frames_used")}/${i.getInt("frames_in")} frames, " +
                "${i.getInt("regions")} regions, ${i.getInt("boxes")} boxes " +
                "(${i.getInt("unstable_dropped")} unstable, ${i.getInt("extra_rows")} extra)"
        )
        // Before the skips, because it changes how every price above this line should be read.
        if (parsed.corrections.isNotEmpty()) {
            val byFix = parsed.corrections.groupingBy { "${it.from}->${it.to}" }.eachCount()
            appendLine(
                "  lead digit repaired on ${parsed.corrections.size} read(s): " +
                    byFix.entries.sortedBy { it.key }.joinToString { "${it.value}x ${it.key}" }
            )
        }
        parsed.emptyFrames.forEach { appendLine("  skipped frame ${it.frame} — ${it.reason}") }
        parsed.skipped.forEach {
            appendLine(
                "  skipped frame ${it.frame} region ${it.region} " +
                    "(row ${it.rowPosition}) — ${it.reason}"
            )
        }
    }

    // ----------------------------------------------------------------------- //
    // helpers
    // ----------------------------------------------------------------------- //

    /** A label's reads while they are still being pooled. */
    private class MutableBox(val label: String, val rowPosition: Int) {
        val batches = mutableListOf<Int>()
        val prices = mutableListOf<Double>()
        val weights = mutableListOf<Double>()
    }

    /** A base row while pieces are being fitted into it. [basePrice] never changes during the fit. */
    private class FitRow(
        val label: String,
        val rowPosition: Int,
        val basePrice: Double,
        val prices: MutableList<Double>,
        val batches: MutableList<Int>
    ) {
        val mergedFrom = mutableListOf(label)
    }

    /** Python's `repr` for the values a JSON label can hold, so reasons read the same. */
    private fun pyRepr(v: Any?): String = when {
        v == null || v == JSONObject.NULL -> "None"
        v is String -> "'$v'"
        v is Boolean -> if (v) "True" else "False"
        else -> v.toString()
    }

    private fun JSONObject.putHeader(ocr: JSONObject) {
        put("source", ocr.optString("source"))
        put("video_id", ocr.optString("video_id"))
    }

    private fun boxesJson(boxes: List<Box>) = JSONArray().apply {
        boxes.forEach { b ->
            put(JSONObject().apply {
                put("label", b.label)
                put("row_position", b.rowPosition)
                put("price", b.price)
                put("prices", JSONArray().apply { b.prices.forEach { put(it) } })
            })
        }
    }

    private fun discardsJson(discarded: List<Discard>) = JSONArray().apply {
        discarded.forEach { d ->
            put(JSONObject().apply {
                put("label", d.label)
                put("row_position", d.rowPosition)
                put("price", d.price ?: JSONObject.NULL)
                put("prices", JSONArray().apply { d.prices.forEach { put(it) } })
                put("reason", d.reason)
            })
        }
    }

    private const val TAG = "ConsensusConsolidator"
}
