package com.terminalscout.app.prod.pipeline

import com.terminalscout.app.prod.BuildConfig
import com.terminalscout.app.prod.config.DeviceConfig
import android.graphics.Rect
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One price region and the digits read inside it — the unit Stage 4 produces.
 *
 * This is the binding the stage exists to create: a box that Stage 3 found, and the number that was
 * displayed in it. [box] is in the coordinate space of the prepped 640 canvas, so it indexes straight
 * into `preppedPath` with no mapping.
 *
 * @param label       the letter Stage 3 drew on the canvas (`A` = topmost box) — how the read was bound
 * @param box         the region's bounding box on the prepped canvas
 * @param digits      ONLY the characters `0-9`, exactly as displayed left to right. Empty when the
 *                    panel was found but the reader could not read it.
 * @param regionConfidence the region model's confidence in the box itself (not in the digits)
 */
data class RegionReading(
    val label: String,
    val box: Rect,
    val digits: String,
    val regionConfidence: Float
) {
    /** True when the reader actually returned digits for this box. */
    val hasDigits: Boolean get() = digits.isNotEmpty()
}

/**
 * One board image's OCR result: every price region on it, each carrying the digits read inside it.
 *
 * A frame can hold more than one board, so the unit here is a DETECTION, not a frame: [detectionId]
 * is the unique key and [frameIndex] says which photo it came out of. Together with [regions], that
 * gives the frame → bounding box → digits mapping this stage is for.
 *
 * @param videoId     the UUID linking this back to its frames/crops/prepped folders
 * @param detectionId unique id of the board this was read from (see [BoardDetection.detectionId])
 * @param frameIndex  index of the frame the board was detected in
 * @param slug        `f00012_d00` — the detection's file-naming stem
 * @param timestampUs source-video timestamp of the frame, in microseconds
 * @param regions     one entry per price region Stage 3 found, in top-to-bottom order. Regions the reader
 *                    did not report are still present, with empty digits — the mapping describes the
 *                    board, not just the successful reads.
 * @param ocrResults  [regions] rendered as JSON, in the shape
 *                    `{"readings":[{"box":"A","bbox":[x1,y1,x2,y2],"digits":"247"}]}`
 * @param preppedPath full on-disk path to this board's prepped image (prepped/<videoId>/…).
 *                    Propagated to Stage 5 so the reconstruction's "magnifying glass" can re-open the
 *                    actual photo when the text scraps alone can't settle a row.
 */
data class OcrResult(
    val videoId: String,
    val detectionId: Int,
    val frameIndex: Int,
    val slug: String,
    val timestampUs: Long,
    val regions: List<RegionReading>,
    val ocrResults: String,
    val preppedPath: String,
    /**
     * Which independent reader request this canvas was part of, 1-based.
     *
     * Stage 5 uses it to tell corroboration from repetition. Two canvases in the SAME request are not
     * two opinions: the model saw both in one context and can anchor on the first and repeat itself —
     * which is why the prompt has to forbid it in words. Two canvases in DIFFERENT requests are two
     * opinions, because the second call knows nothing of the first.
     */
    val batch: Int = 1
)

/**
 * What Stage 4 produced, and whether the reader ever actually read the board.
 *
 * [readerUnavailable] is separate from [results] being empty on purpose: a read that returned no digits
 * and a read that never happened look identical in the results alone, and only the second means the
 * capture is still owed. When it is true the caller must NOT complete the run — the clip stays on disk
 * and is read again later.
 *
 * It used to be called `serverUnreachable` and was set only when the transport failed, so a reader that
 * ANSWERED by refusing — a dead key, an exhausted quota, a wrong deployment name — came through as a
 * finished read of a board with no prices on it. [refused] is that same fact split out for the human:
 * an unreachable reader heals by itself, a refused one does not. See [ReadOutcome].
 */
data class OcrStageResult(
    val results: List<OcrResult>,
    val readerUnavailable: Boolean = false,
    /**
     * True when the reader (or this device) refused the request rather than failing to reach it — no key
     * configured, a 4xx, a reply with no answer in it, canvases gone from disk.
     *
     * Reported so the trace, the errors screen and `cloud_calls.json` can say which of the two it was: a
     * retry of an unreachable read succeeds once there is signal, and a retry of a refused one does not
     * until somebody changes the configuration.
     */
    val refused: Boolean = false,
    /**
     * The DISTINCT canvases actually sent to the reader, in time order.
     *
     * Not the same as the pool: the pool is what was eligible, and the sampler draws a subset of it. Not
     * the same as [results] either, since a canvas drawn into two requests appears once here and twice
     * there. This is the list to park when the reader is unreachable and the one to report as "passed to
     * OCR".
     */
    val sentFrames: List<PreppedFrame> = emptyList()
)

/**
 * Stage 4 — OCR. Reads the DIGITS out of each price region on each prepped board image.
 *
 * The single entry point [run] takes Stage 3's output ([PreppedFrame]s), loops it, reads each prepped
 * canvas from disk, reads it, and returns one [OcrResult] per board image. Each bitmap is freed
 * before the next iteration.
 *
 * **Digits only.** The model is no longer asked for fuel names or row numbers, and no longer has to
 * segment the board: Stage 3 already located every price panel, outlined it in red and tagged it with
 * a letter. The reader is asked one narrow question per box — what digits are inside it — and to
 * name the letter each answer came from.
 *
 * **The letter is the binding key.** [bindToRegions] matches each returned letter to the
 * [PriceRegion] Stage 3 drew it on, which is what makes the mapping robust: relying on output ORDER
 * instead would silently shift every price the moment the model skipped or added a row. A region
 * the reader never mentions still appears in the result with empty digits, so a read failure is visible
 * rather than absent.
 *
 * **One request for all canvases.** The off-device reader sends every prepped canvas in a single
 * batched call ([BatchOcrReader.readBoxDigitsBatch]), because the prompt costs about four times what a
 * canvas image does — so repeating it per canvas was the bulk of the spend. This is a deliberate
 * departure from reading one image per conversation, which is how the reference prototype's
 * on-device reader worked.
 *
 * Batching is only sound because attribution survives it: each image is labelled in the request and the
 * reply is per-image, so a price still belongs to the canvas it was read from. That matters more here
 * than anywhere else in the pipeline — Stage 5 exists to resolve disagreement BETWEEN frames, and a
 * reader that merged them would quietly destroy the vote it depends on.
 *
 */
class OcrStage(
    /**
     * The remote reader. Injectable so a test can hand in a fake, or an experiment a second model,
     * without touching this stage — the prompt and the batching live in [BatchOcrReader] either way.
     */
    private val remoteReader: BatchOcrReader = LunaOcrReader()
) {


    /**
     * Runs OCR over every prepped board image and returns the per-region readings. Returns an empty
     * list when no reader is configured. Must run off the main thread.
     *
     * The result is reviewed by a second model, but at the END of
     * Stage 5 ([ConsolidationReviewer]) rather than here: a per-box override at this point rewrote
     * every frame's reading with one value, which erased the cross-frame disagreement Stage 5 exists
     * to resolve — a correct `1914` seen in one canvas was overwritten in all of them by a wrong
     * `1834`. Reviewing the consolidated answer instead leaves the voting intact.
     */
    suspend fun run(prepped: List<PreppedFrame>): OcrStageResult {
        val ready = remoteReader.isEnabled
        Log.d(TAG, "reader: ${BuildConfig.AZURE_AI_MODEL} on the Responses API, effort xhigh")
        if (!ready) {
            // NOT an empty read. With no key the board was never looked at, and returning a plain empty
            // result told the pipeline the run was complete — so every clip on the device was written
            // out as a forecourt with no prices, uploaded, and then deleted. The reader is reported
            // unavailable instead: the clip stays owed until a build with a key reads it.
            Log.e(TAG, "no cloud key configured — Stage 4 CANNOT read; the clip stays owed")
            return OcrStageResult(
                emptyList(), readerUnavailable = true, refused = true, sentFrames = prepped
            )
        }

        // A canvas with no price region has nothing to bind a reading to — reading it would only
        // produce prices that belong to no box, which is exactly what this stage must not emit.
        val readable = prepped.filter { it.regions.isNotEmpty() }
        prepped.filter { it.regions.isEmpty() }.forEach {
            Log.d(TAG, "${it.slug}: no price regions on this canvas — skipping the read")
        }
        if (readable.isEmpty()) {
            Log.w(TAG, "no canvas carries a price region — nothing to read")
            return OcrStageResult(emptyList())
        }

        val outcome = readBatched(readable)

        Log.d(
            TAG,
            "OCR produced ${outcome.results.size} read(s) over ${outcome.sentFrames.size} canvas(es) " +
                "of a ${readable.size}-canvas pool — " +
                "${outcome.results.sumOf { r -> r.regions.count { it.hasDigits } }} of " +
                "${outcome.results.sumOf { it.regions.size }} price region(s) read"
        )
        return outcome
    }

    /**
     * The cloud path: EVERY readable canvas in ONE request.
     *
     * One request rather than one per canvas, because the prompt costs several times what a canvas image
     * does — sending it once rather than once per image is the bulk of the saving, measured at about a
     * quarter of the input tokens. Batching is only sound because attribution survives it: each image is labelled
     * `Image N:` in the request and the reply is per-image, so a price still belongs to the canvas it
     * was read from. That matters more here than anywhere else in the pipeline — Stage 5 exists to
     * resolve disagreement BETWEEN frames, and a reader that merged them would destroy the vote it
     * depends on.
     */
    private suspend fun readBatched(readable: List<PreppedFrame>): OcrStageResult = coroutineScope {
        // Split in the order Stage 3 WALKED its crops — confidence bucket first, newest-first inside a
        // bucket — not the ascending time it returns them in. Contiguous, so request 1 holds the
        // strongest evidence this board produced: if a request is going to be lost, the one worth losing
        // is the last.
        val ranked = readable.sortedWith(
            ImagePrepStage.byConfidenceThenNewest({ it.confidence }, { it.timestampUs })
        )
        val groups = ranked.chunked(BATCH_SIZE)
        Log.d(
            TAG,
            "reading ${ranked.size} canvas(es) as ${groups.size} independent request(s) of up to " +
                "$BATCH_SIZE, in parallel"
        )

        // In PARALLEL. The requests are independent by design — that is the entire reason the pool is
        // split — so serialising them only multiplied the latency of the slowest stage in the pipeline.
        // The reader retries its own 429s, which is the load this could otherwise create.
        // Each request is contained INSIDE its own async, and that is load-bearing rather than tidy.
        // `coroutineScope` cancels every sibling the moment one child throws and then rethrows out of
        // the whole block — so a single request that threw (a reader bug, an OOM building a body, an
        // encoder fault on one canvas) discarded the two requests that had already succeeded and left
        // Stage 4 with nothing. A throw is exactly what this stage exists to tolerate: three separate
        // requests are the reason the pool is split at all, and one of them failing is a weaker vote,
        // not the end of the board.
        val batches = groups
            .mapIndexed { index, group ->
                index to async {
                    try {
                        remoteReader.readBoxDigitsBatch(group.map { it.preppedPath })
                    } catch (c: CancellationException) {
                        // Real cancellation — the service is going down, or a sibling was cancelled by
                        // something outside this block. Must propagate, or the scope never finishes.
                        throw c
                    } catch (t: Throwable) {
                        // `Throwable`, so an `OutOfMemoryError` costs ONE request rather than the board.
                        //
                        // A batch reads every canvas of its group off disk and base64-encodes them into
                        // a single JSON body, which is several times the images' own size — and the
                        // requests run CONCURRENTLY here, so the peak is every group's body at once.
                        // That makes OOM this stage's characteristic failure, and it is an `Error`:
                        // left as `Exception` it escaped the `async`, cancelled the sibling requests
                        // through `coroutineScope`, and lost a board that two thirds of the evidence
                        // had already been gathered for. Containing it keeps the votes that did arrive,
                        // which is exactly what splitting the pool into three requests is for.
                        Log.e(
                            TAG,
                            "request ${index + 1} of ${groups.size} threw — " +
                                "${group.size} canvas(es) lost, the other requests stand",
                            t
                        )
                        // UNREACHABLE, not REFUSED: a thrown request never got an answer, and there is
                        // nothing here to say the reader would refuse the same canvases next time.
                        BatchRead(group.map { BatchOcrReader.EMPTY }, ReadOutcome.UNREACHABLE)
                    }
                }
            }
            .map { (index, deferred) -> index to deferred.await() }

        val results = mutableListOf<OcrResult>()
        val sent = mutableListOf<PreppedFrame>()
        var lost = 0
        // Requests the reader REFUSED rather than failed to reach. Counted separately only so the run
        // can say which it was; both are equally "not read".
        var refused = 0

        for ((index, batch) in batches) {
            val group = groups[index]
            val batchNo = index + 1
            if (!batch.read) {
                // ONE group failing no longer abandons the board.
                //
                // This used to return empty and park the whole run, discarding every canvas the other
                // requests had already read. Stage 5 settles each panel by pooling its reads and taking
                // the mode, so a board read by two requests of three degrades from fourteen votes to ten
                // — weaker, and still a vote. Losing all fourteen because one request 500'd is worse.
                lost++
                if (batch.outcome == ReadOutcome.REFUSED) refused++
                Log.w(
                    TAG,
                    "reader ${batch.outcome.name.lowercase()} on request $batchNo of ${groups.size} — " +
                        "${group.size} canvas(es) lost, continuing with the rest"
                )
                continue
            }
            sent += group
            group.forEachIndexed { position, pf ->
                batch.readings.getOrNull(position)?.let { results += resultFor(pf, it, batchNo) }
            }
        }

        // Unavailable only when NOTHING got through. A partial read is a read, and reporting it as a
        // reader failure would park a board that has usable prices.
        if (lost == groups.size) {
            Log.e(TAG, "every request failed ($refused refused) — the board was NOT read")
            return@coroutineScope OcrStageResult(
                emptyList(), readerUnavailable = true, refused = refused > 0, sentFrames = readable
            )
        }
        if (lost > 0) {
            Log.w(TAG, "$lost of ${groups.size} request(s) lost — reading the board on what returned")
        }
        OcrStageResult(results, readerUnavailable = false, sentFrames = sent)
    }


    /** Binds one canvas's answer to the regions Stage 3 drew, and packages it as a result. */
    private fun resultFor(pf: PreppedFrame, reading: BoxReading, batch: Int = 1): OcrResult {
        val regions = bindToRegions(pf.regions, reading.boxes)
        // `-` is a box the reader said nothing about, which Stage 5 treats differently from a box it
        // returned digits for. Tagged with the request that read it, so a disagreement can be traced to
        // which independent read produced it.
        Log.d(
            TAG,
            "${pf.slug} (batch $batch): " +
                regions.joinToString { "${it.label}=${it.digits.ifEmpty { "-" }}" }
        )
        return OcrResult(
            videoId = videoIdOf(pf.preppedPath),
            detectionId = pf.detectionId,
            frameIndex = pf.frameIndex,
            slug = pf.slug,
            timestampUs = pf.timestampUs,
            regions = regions,
            ocrResults = regionsToJson(regions),
            preppedPath = pf.preppedPath,
            batch = batch
        )
    }


    companion object {
        private const val TAG = "OcrStage"

        internal fun bindToRegions(
            regions: List<PriceRegion>,
            readings: List<BoxDigits>
        ): List<RegionReading> {
            val byLabel = readings.associateBy { it.label }
            val bound = regions.map { region ->
                val read = byLabel[region.label]
                RegionReading(
                    label = region.label,
                    box = region.box,
                    digits = read?.digits.orEmpty(),
                    // A region the reader never mentioned comes through with empty digits, which is
                    // the whole of what this stage knows about it. It does not carry a verdict.
                    regionConfidence = region.confidence
                )
            }
            val known = regions.mapTo(HashSet()) { it.label }
            val unmatched = readings.filter { it.label !in known }
            if (unmatched.isNotEmpty()) {
                Log.w(TAG, "Dropping ${unmatched.size} reading(s) for unknown box(es): " +
                    unmatched.joinToString { "${it.label}=${it.digits}" })
            }
            return bound
        }

        /**
         * Renders the bound regions as `{"readings":[{"box","bbox","digits"}]}`. The box coordinates are
         * written into the JSON so the uploaded debug artifact is self-describing — the mapping can be
         * read back without also having the [PreppedFrame] objects in hand.
         */
        internal fun regionsToJson(regions: List<RegionReading>): String {
            val arr = JSONArray()
            for (r in regions) {
                arr.put(JSONObject().apply {
                    put("box", r.label)
                    put("bbox", JSONArray().apply {
                        put(r.box.left); put(r.box.top); put(r.box.right); put(r.box.bottom)
                    })
                    put("digits", r.digits)
                })
            }
            return JSONObject().put("readings", arr).toString()
        }

        /** Derives the video id from a prepped path: prepped/<videoId>/prepped_fXXXXX_dXX.png → "<videoId>". */
        internal fun videoIdOf(path: String): String = File(path).parentFile?.name ?: "unknown"

        // NOTE: the reference prototype carried a `USE_CLOUD_OCR` switch here, added to test whether
        // its crashes came from the LiteRT-LM native engine. This build has no on-device reader for it
        // to switch to, so the flag and the branch it guarded are both gone.

        // NOTE: a `USE_LUNA_OCR` flag used to sit here, switching between [LunaOcrReader] on the
        // Responses API and `ClaudeOcrReader` on the Anthropic Messages endpoint. It was true, and had
        // been true for the life of the build, so the other branch was compiled and never run — which
        // is exactly where a bug sits unnoticed (that reader carried this file's 4xx fault too). The
        // Claude reader is deleted and the flag with it; a second model goes in through this class's
        // `remoteReader` parameter, which is what it is for.
        //
        // Luna costs more per read: its deliberation is billed out of the output budget, and at high
        // effort that is the large majority of its output tokens.

        /**
         * Canvases per reader request.
         *
         * The pool is split into requests of this size and each is sent SEPARATELY, so agreement between
         * them is evidence rather than an echo. It used to be one request for the whole pool, and the
         * prompt carried a line telling the model not to copy a reading from one image to another — that
         * line exists because it did. A model shown a dozen near-identical crops in one context can
         * anchor on the first and repeat itself, and the consolidator counted that as a dozen
         * confirmations.
         *
         * Five is small enough that a request cannot build much of an internal consensus, and with the
         * pool at [PriceBoardPipeline.OCR_FRAMES] = 14 it yields three independent reads of the board —
         * 5 + 5 + 4 — enough for a majority to settle a disagreement rather than merely record one.
         *
         * A FIXED size, not three even groups: a board whose gates left it seven canvases is split 5 + 2
         * rather than 3 + 2 + 2, because the first request should carry as much of the best evidence as
         * it can hold. The tail group is whatever is left.
         *
         * The cost is the prompt, paid once per request instead of once per pool. Images dominate the
         * bill, so three requests of four cost close to what one request of twelve would.
         */
        val BATCH_SIZE: Int get() = DeviceConfig.int("ocr.run.batch_size", 5)
    }
}
