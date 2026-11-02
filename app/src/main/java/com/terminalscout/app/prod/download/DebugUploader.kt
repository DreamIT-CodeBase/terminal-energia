package com.terminalscout.app.prod.download

import android.content.Context
import com.terminalscout.app.prod.config.DeviceConfig
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * DEBUG upload of every run's artifacts, brokered through the Device API into the `debugdirectory`
 * container. One folder per run (see [newRunLabel]), so a tester can review a whole run from the blob
 * store:
 *
 *   debugdirectory/<runFolder>/
 *     ├─ video.mp4                    the recorded clip, MOVED here at the end of the run — see
 *     │                               [enqueueClip]. Every run sends it: one that read the board, one
 *     │                               that could not, and one that threw.
 *     ├─ failure.txt                  why the run produced no reading, when it produced none, plus the
 *     │                               stage, the throwable and the run's logcat tail. Absent on a run
 *     │                               that answered.
 *     ├─ crops/crop_XXXXX.png         Stage 3 input  · every board crop handed to prep
 *     ├─ prepped/prepped_XXXXX.png    Stage 3 output · every prepped (+region-boxed) canvas
 *     ├─ ocr/frame_XXXXX.json         Stage 4 · per-frame OCR
 *     ├─ consensus.json               Stage 5 · consolidation digest
 *     ├─ consolidation_review.json    Stage 5 · the verification pass, when it ran
 *     ├─ api_response.json            Stage 6 · enriched API payload
 *     ├─ pipeline_manifest.json       what the pipeline did: every detection, how far each got
 *     └─ run_manifest.json            written by the SERVER from [completeRun] — uploaded vs expected
 *
 * **Two halves.** [enqueueFiles]/[enqueueTexts] only STAGE artifacts into a durable on-disk queue
 * ([DebugUploadOutbox]) — local work, no network — and ask WorkManager for a drain; [upload] is the
 * transport, called by [DebugUploadWorker] once there is connectivity. That split is what makes the
 * upload survive a weak network: it used to run fire-and-forget in-process while the run waited up to
 * 90 s before deleting its working files, so anything slower than that was deleted before it was sent.
 * A caller now waits only for staging ([awaitIdle]), which is quick and cannot fail for want of signal.
 *
 * **Test clips' ARTIFACTS are not uploaded.** The container is the fleet's evidence of what its devices
 * really saw, and a clip replayed at a desk is not that: it would arrive as a fresh run folder holding
 * the same board a dozen times over, with a made-up location, competing for the same connection as a
 * real capture's readings. So a run over a sideloaded clip is opted out at its start — see
 * [disableForRun] — and nothing is staged for it through [enqueueFiles]/[enqueueTexts].
 *
 * The CLIP and its failure report are the exception: [enqueueClip] and [enqueueRunFailure] check only
 * [isEnabled]. A clip is MOVED rather than copied, so a suppressed one would be a file taken off the
 * processing queue and then held in the outbox forever — nothing here expires, and a staged artifact
 * leaves only by being delivered. A production device has no test clips, so the branch would buy
 * nothing and cost a permanent leak on the one device where it fired.
 *
 * **Auth is brokered.** The app no longer holds the storage account key or signs its own SAS: [upload]
 * asks [DeviceApi] for a write-only URL scoped to that one blob and PUTs to it. When no fleet secret is
 * configured the uploader is disabled and nothing is staged.
 */
object DebugUploader {
    private const val TAG = "DebugUploader"

    /** Name of the per-run failure report staged when an artifact is rejected outright. */
    private const val LOGS_FILE = "logs.txt"

    /** Name the recorded clip takes in the container. One per run folder. */
    const val CLIP_FILE = "video.mp4"

    /** Name of the report staged for a run that produced no reading. See [enqueueRunFailure]. */
    const val FAILURE_FILE = "failure.txt"

    /** Attempts per blob within one drain, including the first. Longer outages are WorkManager's job. */
    private val MAX_ATTEMPTS: Int get() = DeviceConfig.int("diagnostics.debug_upload.max_attempts", 3)
    /** Waits between attempts; its size is what makes [MAX_ATTEMPTS] meaningful. */
    private val RETRY_BACKOFF_MS: LongArray
        get() = DeviceConfig.longArray(
            "diagnostics.debug_upload.retry_backoff_ms", longArrayOf(500L, 1_500L)
        )
    /** Backstop on how long a caller waits for STAGING before deleting its working files anyway. */
    private val AWAIT_IDLE_MS: Long get() = DeviceConfig.long("diagnostics.debug_upload.await_idle_ms", 60_000L)

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)   // clips can be tens of MB
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    // Application-scoped: uploads outlive the fragment that started the run.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Every staging batch still running, so a caller can [awaitIdle] before deleting the files it just
     * handed over. Without this the working clip was deleted the moment the pipeline returned, while
     * the copy into the outbox was often still reading it.
     */
    private val inFlight = mutableListOf<Job>()

    /**
     * Whether artifacts are staged at all. The server owns the container now, so there is nothing to
     * create and nothing to sign — only the fleet secret to have been built in.
     */
    val isEnabled: Boolean
        get() = DeviceApi.isConfigured.also {
            if (!it) Log.w(TAG, "no fleet secret configured — debug upload disabled")
        }

    // `localSink` used to sit here: a directory to write artifacts INTO instead of uploading them,
    // set for the length of a validation run so a replayed clip's artifacts stayed off the blob store.
    //
    // It was a process-wide slot, and this process runs the capture service at the same time. A real
    // capture being processed while a validation run held the slot had ITS artifacts diverted into the
    // validation folder — never staged, never uploaded, absent from the fleet's evidence container with
    // nothing anywhere saying why. A dev-only mode cannot be allowed to reach into production work, and
    // the harness it existed for is gone: this is the production build.

    /** One empty file per opted-out run — see [disableForRun]. */
    private const val OPT_OUT_DIR = "debug_nouploads"

    /**
     * How long an opt-out marker is kept. It has to outlive the run it belongs to by a wide margin: the
     * offline OCR drain can stage a recovered read hours later ([enqueueTexts] with `declare = false`),
     * and that late artifact must be dropped too. A week of dev runs is a few hundred bytes.
     */
    private val OPT_OUT_TTL_MS: Long
        get() = DeviceConfig.long("diagnostics.debug_upload.opt_out_ttl_ms", 7 * 24 * 60 * 60 * 1000L)

    private fun optOutDir(context: Context): File =
        File(context.applicationContext.filesDir, OPT_OUT_DIR).apply { mkdirs() }

    /**
     * Declares that nothing from [runFolder] is to be uploaded, for as long as anything might try.
     *
     * Called once at the start of a run over a sideloaded test clip. It is recorded ON DISK rather than
     * held in memory because the things that stage a run's artifacts do not all live in the run: the
     * pending-OCR worker comes back for one long after the process that ran the clip is gone, and an
     * in-memory flag would let exactly that artifact through.
     *
     * A marker is a file whose NAME is the run folder, which is already a timestamped id, so this also
     * prunes its own directory as it goes rather than needing a sweep of its own.
     */
    fun disableForRun(context: Context, runFolder: String) {
        val dir = optOutDir(context)
        try {
            File(dir, runFolder).createNewFile()
            Log.i(TAG, "debug upload OFF for run $runFolder (test clip)")
        } catch (e: Exception) {
            // Worth a warning rather than a throw: the run itself is fine, it just may upload artifacts
            // a dev did not want in the container.
            Log.w(TAG, "could not opt $runFolder out of upload", e)
        }
        val cutoff = System.currentTimeMillis() - OPT_OUT_TTL_MS
        dir.listFiles { f -> f.isFile && f.lastModified() < cutoff }?.forEach { it.delete() }
    }

    /** Whether [runFolder] was opted out by [disableForRun]. */
    fun isDisabledForRun(context: Context, runFolder: String): Boolean =
        File(optOutDir(context), runFolder).exists()

    /**
     * Whether a batch of [count] artifacts for [runFolder] should be staged at all: the shared gate in
     * front of both enqueue paths.
     */
    private fun shouldStage(context: Context, runFolder: String, count: Int): Boolean {
        if (!isEnabled || count == 0) return false
        if (isDisabledForRun(context, runFolder)) {
            Log.d(TAG, "not staging $count artifact(s) — $runFolder is opted out of upload")
            return false
        }
        return true
    }

    /** A human-friendly, collision-safe run folder name: `yyyyMMdd-HHmmss-<4hex>`. */
    fun newRunLabel(): String {
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return "$ts-${UUID.randomUUID().toString().take(4)}"
    }

    /**
     * Hands on-disk files over for upload as `<runFolder>/<relPath>` each: they are staged into the
     * durable outbox, byte-for-byte, and a drain is requested.
     *
     * Returns immediately; the staging itself runs in the background. A caller that is about to DELETE
     * these files must [awaitIdle] first — staging reads the originals.
     */
    fun enqueueFiles(context: Context, runFolder: String, files: List<Pair<String, File>>) {
        if (!shouldStage(context, runFolder, files.size)) return
        val app = context.applicationContext
        track(scope.launch {
            val produced = mutableListOf<Pair<String, Long>>()
            for ((rel, f) in files) {
                // A file that is missing or still empty used to be skipped in silence, which is the
                // hardest kind of gap to explain later — an artifact absent from the blob store with
                // nothing anywhere saying why. It is still DECLARED below, so the run's completion
                // reports it as missing instead of the server never hearing of it.
                if (!f.exists() || f.length() == 0L) {
                    Log.w(TAG, "skipped $runFolder/$rel — ${if (f.exists()) "empty" else "missing"}")
                    produced += rel to 0L
                    continue
                }
                produced += rel to f.length()
                DebugUploadOutbox.stage(app, runFolder, rel, f)
            }
            DebugRunManifest.record(app, runFolder, produced)
            DebugUploadScheduler.requestImmediate(app)
        })
    }

    /**
     * Hands the recorded CLIP over for upload as `<runFolder>/[CLIP_FILE]`, by **moving** it.
     *
     * Called once per run, at the end, whatever the outcome — a run that read the board, a run that
     * could not, and a run that threw all send their clip. It is the last thing that happens to a clip
     * on this device: after this the file is in the outbox, `recorded_videos/` no longer holds it, and
     * [DebugUploadWorker] delivers it and deletes it on WorkManager's terms.
     *
     * ### Why a move, and why this is not the staging that was removed
     * `video.mp4` used to be COPIED into the outbox at the START of a run, and that is what was removed:
     * a few hundred megabytes of clip became a few hundred more, taken at the moment the run was about
     * to write a couple of gigabytes of frames. A move at the END has neither problem — it is a rename
     * within `filesDir`, so it duplicates nothing and it happens when the run's frames are already
     * gone. See [DebugUploadOutbox.stageMove].
     *
     * ### It ignores the per-run opt-out on purpose
     * Every other staging path goes through [shouldStage], which suppresses a run marked by
     * [disableForRun]. This one checks [isEnabled] only. The opt-out exists for sideloaded test clips,
     * and a clip that is moved but never uploaded would sit in the outbox forever — the outbox has no
     * age cut-off and a staged artifact leaves only by being delivered. A production device has no test
     * clips to opt out, so the branch would buy nothing and cost a permanent leak.
     *
     * Returns true when the clip is in the outbox and gone from [clip]. A false return means the clip is
     * still where it was and the caller must leave it alone.
     */
    fun enqueueClip(context: Context, runFolder: String, clip: File): Boolean {
        if (!isEnabled) {
            Log.d(TAG, "not moving ${clip.name} — debug upload is disabled")
            return false
        }
        if (!clip.exists()) {
            Log.w(TAG, "not moving ${clip.name} — it is already gone")
            return false
        }
        val bytes = clip.length()
        val app = context.applicationContext
        // Synchronous, unlike every other enqueue path. The caller's next act is to stop tracking this
        // clip, so it has to know whether the move happened before it does — and a move is a rename,
        // which is fast enough to do on the caller's IO thread. The BACKGROUND part of an upload is the
        // network, and that was always the worker's.
        if (!DebugUploadOutbox.stageMove(app, runFolder, CLIP_FILE, clip)) return false
        DebugRunManifest.record(app, runFolder, listOf(CLIP_FILE to bytes))
        DebugUploadScheduler.requestImmediate(app)
        return true
    }

    /**
     * Stages the `failure.txt` for a run that did not deliver a reading, beside its clip.
     *
     * Declared like any other artifact, so the run's completion reports it if it never arrives. It
     * carries the stage, the throwable and the run's logcat tail — see [FailureLog.captureRun] — which
     * on the device exist only in a log nobody is attached to.
     *
     * Ignores the per-run opt-out for the same reason [enqueueClip] does: a failure report with no clip
     * beside it explains nothing, and a clip with no report is a video nobody can account for.
     */
    fun enqueueRunFailure(
        context: Context,
        runFolder: String,
        clip: String,
        clipBytes: Long,
        outcome: String,
        stage: String?,
        cause: Throwable?
    ) {
        if (!isEnabled) return
        val app = context.applicationContext
        val body = FailureLog.captureRun(runFolder, clip, clipBytes, outcome, stage, cause)
        // Synchronous, like [enqueueClip] and unlike the batch paths. Staging this in the background
        // raced the drain: the clip is the only other thing in the folder, so a drain that emptied it
        // before this landed would find the run owing nothing, call `completeRun`, and clear the
        // manifest — and the report would then arrive as an artifact of a run the server had already
        // reconciled. It is one small text write; there is nothing to gain by deferring it.
        DebugUploadOutbox.stageText(app, runFolder, FAILURE_FILE, body)
        DebugRunManifest.record(app, runFolder, listOf(FAILURE_FILE to body.length.toLong()))
        DebugUploadScheduler.requestImmediate(app)
        Log.w(TAG, "staged $runFolder/$FAILURE_FILE ($outcome${stage?.let { ", stage $it" }.orEmpty()})")
    }

    /**
     * Suspends until everything handed over so far has been **staged**, or [timeoutMs] elapses.
     *
     * Exists so a caller can delete its working files as soon as they are safely copied into the
     * outbox: the clip is the largest artifact and was routinely deleted out from under its own upload,
     * which is why videos went missing while smaller files did not.
     *
     * This used to wait for the NETWORK, which meant a slow connection either stalled the queue or —
     * once the bound elapsed — let the run delete artifacts that had never been sent. Staging is local,
     * so the wait is now short and cannot be defeated by a bad signal; the timeout survives only as a
     * backstop against a stuck disk.
     */
    suspend fun awaitIdle(timeoutMs: Long = AWAIT_IDLE_MS) {
        val finished = withTimeoutOrNull(timeoutMs) {
            while (true) {
                val pending = synchronized(inFlight) { inFlight.toList() }
                if (pending.isEmpty()) break
                pending.joinAll()   // more batches may have been queued meanwhile; the loop re-checks
            }
            true
        }
        if (finished == null) {
            val left = synchronized(inFlight) { inFlight.size }
            Log.w(TAG, "still $left staging batch(es) running after ${timeoutMs}ms — continuing anyway")
        }
    }

    /** Remembers a batch until it completes, so [awaitIdle] knows what is outstanding. */
    private fun track(job: Job) {
        synchronized(inFlight) { inFlight += job }
        job.invokeOnCompletion { synchronized(inFlight) { inFlight -= job } }
    }

    /** Hand an in-memory JSON/text blob over for upload as `<runFolder>/<relPath>`. */
    fun enqueueText(context: Context, runFolder: String, relPath: String, text: String) =
        enqueueTexts(context, runFolder, listOf(relPath to text))

    /** Hand several in-memory JSON/text blobs over under one run folder. */
    /**
     * @param declare whether these artifacts join the run's expected set for `/complete`. Pass FALSE for
     *   artifacts produced AFTER a run was already reconciled — a recovered offline read, say. The
     *   server overwrites a run's manifest on each completion, so re-declaring a handful of late files
     *   would replace an accurate "18 of 20 arrived" record with a misleading "9 of 9, all present".
     */
    fun enqueueTexts(
        context: Context,
        runFolder: String,
        items: List<Pair<String, String>>,
        declare: Boolean = true
    ) {
        if (!shouldStage(context, runFolder, items.size)) return
        val app = context.applicationContext
        track(scope.launch {
            for ((rel, txt) in items) DebugUploadOutbox.stageText(app, runFolder, rel, txt)
            if (declare) DebugRunManifest.record(
                app, runFolder, items.map { (rel, txt) -> rel to txt.toByteArray().size.toLong() }
            )
            DebugUploadScheduler.requestImmediate(app)
        })
    }

    /**
     * Uploads ONE staged artifact, called by [DebugUploadWorker] once WorkManager says there is a
     * network.
     *
     * Two steps now: ask [DeviceApi] for a URL scoped to this one blob, then PUT the bytes straight to
     * Azure. The grant is fetched **per attempt and never cached** — it expires in 15 minutes, and an
     * outbox item retried an hour later would otherwise PUT with a dead signature and be abandoned as
     * though the artifact itself were bad.
     *
     * @param blobPath `<runFolder>/<relPath>` — the outbox's own key, split here into the run id and the
     *                 artifact path the API wants.
     * @return what the caller should do with the staged file — see [Outcome].
     */
    suspend fun upload(context: Context, blobPath: String, file: File): Outcome {
        if (!isEnabled) {
            lastRejectionReason = "no fleet secret configured"
            return Outcome.ABANDONED
        }
        if (!file.exists() || file.length() == 0L) {
            lastRejectionReason = if (file.exists()) "staged file is empty" else "staged file is gone"
            return Outcome.ABANDONED
        }

        val slash = blobPath.indexOf('/')
        if (slash <= 0 || slash == blobPath.length - 1) {
            Log.w(TAG, "malformed staged path '$blobPath' — dropping")
            lastRejectionReason = "malformed staged path '$blobPath'"
            return Outcome.ABANDONED
        }
        val runId = blobPath.substring(0, slash)
        val relPath = blobPath.substring(slash + 1)
        val contentType = contentTypeFor(relPath)

        return put(context, runId, relPath, contentType) {
            file.asRequestBody(contentType.toMediaType())
        }
    }

    /**
     * Why the most recent artifact was rejected, for [recordRejection] to quote.
     *
     * A single slot rather than a map because a drain uploads one artifact at a time and the worker
     * records the rejection immediately after [upload] returns. It is only ever a diagnostic string.
     */
    @Volatile
    private var lastRejectionReason: String = "unknown"

    /** What happened to one blob, and so what should happen to the staged file behind it. */
    enum class Outcome {
        /** In the container — delete the staged copy. */
        UPLOADED,

        /** A transient failure (no network, timeout, 5xx) — keep it and come back later. */
        RETRY,

        /**
         * The request itself is wrong (bad SAS, missing container, malformed name) and will fail the
         * same way forever. Drop the staged file: keeping it would block the whole outbox behind an
         * artifact that can never be sent.
         */
        ABANDONED
    }

    /**
     * Gets a fresh grant and PUTs one block blob, retrying a failure that might not repeat.
     *
     * A single dropped connection used to lose an artifact permanently: every failure was caught,
     * logged and forgotten, with nothing recording that anything was owed. Mobile networks drop often
     * enough that this alone accounts for scattered gaps in a run's folder. These few quick attempts
     * ride out a blip; anything longer is [Outcome.RETRY] and becomes WorkManager's problem, since the
     * staged file is still on disk.
     *
     * Only **retryable** failures are retried — a network error, a timeout, a 408/429, or a 5xx. A 403
     * means the signature is dead or the path is not the one it was signed for, and will fail exactly
     * the same way three times, so it is reported once and abandoned.
     *
     * `x-ms-blob-type: BlockBlob` is sent from the grant's own `required_headers` rather than hardcoded,
     * so the server stays free to change what it requires. It is genuinely mandatory — Azure answers a
     * PUT without it `400 MissingRequiredHeader`, which was verified against the live endpoint.
     *
     * [bodyFactory] rather than a body: an OkHttp [RequestBody] is not reliably reusable across calls,
     * so each attempt builds a fresh one.
     */
    private suspend fun put(
        context: Context,
        runId: String,
        relPath: String,
        contentType: String,
        bodyFactory: () -> RequestBody
    ): Outcome {
        val label = "$runId/$relPath"

        for (attempt in 1..MAX_ATTEMPTS) {
            val retryIn = RETRY_BACKOFF_MS.getOrNull(attempt - 1)

            // Fetched inside the loop on purpose: a grant lives 15 minutes, so a retry after a long
            // backoff (or a WorkManager re-run hours later) needs its own.
            val grant = when (val g = DeviceApi.artifactUpload(context, runId, relPath)) {
                is DeviceApi.ApiResult.Ok -> g.value
                is DeviceApi.ApiResult.Permanent -> {
                    Log.w(TAG, "no upload grant for $label (HTTP ${g.code}) — dropping: ${g.detail}")
                    lastRejectionReason = "no upload grant: HTTP ${g.code} ${g.detail}"
                    return Outcome.ABANDONED
                }
                DeviceApi.ApiResult.Transient -> {
                    if (retryIn == null) return Outcome.RETRY
                    delay(retryIn)
                    continue
                }
            }

            try {
                val req = Request.Builder()
                    .url(grant.uploadUrl)
                    .apply { grant.requiredHeaders.forEach { (k, v) -> header(k, v) } }
                    .header("Content-Type", contentType)
                    .put(bodyFactory())
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        Log.d(TAG, "uploaded $label (HTTP ${resp.code}${attemptSuffix(attempt)})")
                        return Outcome.UPLOADED
                    }
                    val detail = resp.body?.string()?.take(200)
                    if (!isRetryable(resp.code)) {
                        Log.w(TAG, "upload rejected $label HTTP ${resp.code}${attemptSuffix(attempt)}: $detail")
                        lastRejectionReason = "storage rejected the PUT: HTTP ${resp.code} $detail"
                        return Outcome.ABANDONED
                    }
                    if (retryIn == null) {
                        Log.w(TAG, "upload failed $label HTTP ${resp.code}${attemptSuffix(attempt)}: $detail")
                        return Outcome.RETRY
                    }
                    Log.w(TAG, "upload retrying $label after HTTP ${resp.code} (attempt $attempt)")
                }
            } catch (e: Exception) {
                if (retryIn == null) {
                    Log.w(TAG, "upload error $label${attemptSuffix(attempt)}", e)
                    return Outcome.RETRY
                }
                Log.w(TAG, "upload retrying $label after ${e.javaClass.simpleName} (attempt $attempt)")
            }
            delay(retryIn ?: return Outcome.RETRY)
        }
        return Outcome.RETRY
    }

    /**
     * Stages a `logs.txt` explaining why [blobPath] was rejected, so a failure that will never be
     * retried is still diagnosable from the run's own folder.
     *
     * The body carries the reason and this process's logcat tail ([FailureLog]) — the reader's
     * per-canvas output, the detector's ladder, the HTTP codes, whatever was written before the
     * rejection. Those lines otherwise exist only on a device nobody is attached to.
     *
     * Declared in the manifest like any other artifact, so `complete` expects it and its absence is
     * itself reported. A rejection of `logs.txt` is deliberately NOT recorded: the log about the log
     * failing would be rejected in turn, and one useless file is better than an endless chain of them.
     */
    fun recordRejection(context: Context, blobPath: String) {
        val slash = blobPath.indexOf('/')
        if (slash <= 0) return
        val runFolder = blobPath.substring(0, slash)
        val relPath = blobPath.substring(slash + 1)
        if (relPath == LOGS_FILE) {
            Log.w(TAG, "$LOGS_FILE was itself rejected — not logging that, to avoid a loop")
            return
        }
        val body = FailureLog.capture(runFolder, relPath, lastRejectionReason)
        // enqueueText stages it and declares it; the run cannot have been completed yet, since the
        // artifact that just failed was still owed when this drain started.
        enqueueText(context, runFolder, LOGS_FILE, body)
        Log.w(TAG, "staged $runFolder/$LOGS_FILE for the rejected $relPath")
    }

    /**
     * Tells the server what [runFolder] produced, then forgets the manifest.
     *
     * Called once a run has nothing left in the outbox — see [DebugUploadWorker]. A non-empty `missing`
     * is a successful reconciliation reporting a gap, not a failure, so it is logged loudly and the
     * manifest is still cleared: the artifacts behind those names are gone from the queue either way, so
     * keeping it would only leave a file nothing can act on.
     */
    suspend fun completeRun(context: Context, runFolder: String): Boolean {
        val expected = DebugRunManifest.expected(context, runFolder)
        if (expected.isEmpty()) {
            DebugRunManifest.clear(context, runFolder)
            return true
        }
        return when (val res = DeviceApi.completeRun(context, runFolder, expected)) {
            is DeviceApi.ApiResult.Ok -> {
                val r = res.value
                if (r.complete) {
                    Log.d(TAG, "run $runFolder complete — all ${r.expectedCount} artifact(s) arrived")
                } else {
                    Log.w(
                        TAG,
                        "run $runFolder INCOMPLETE — ${r.receivedCount}/${r.expectedCount} arrived, " +
                            "missing: ${r.missing.joinToString()}"
                    )
                }
                DebugRunManifest.clear(context, runFolder)
                true
            }
            is DeviceApi.ApiResult.Permanent -> {
                Log.w(TAG, "run $runFolder could not be completed (HTTP ${res.code}) — giving up: ${res.detail}")
                DebugRunManifest.clear(context, runFolder)
                true
            }
            // 503 storage-unreachable and the like: keep the manifest and try on the next drain.
            DeviceApi.ApiResult.Transient -> {
                Log.d(TAG, "run $runFolder completion deferred — will retry")
                false
            }
        }
    }

    private fun attemptSuffix(attempt: Int) = if (attempt > 1) ", attempt $attempt" else ""

    /** Worth trying again: transient transport or server-side problems, never a malformed request. */
    private fun isRetryable(code: Int): Boolean = code == 408 || code == 429 || code >= 500

    private fun contentTypeFor(rel: String): String = when {
        rel.endsWith(".png", true) -> "image/png"
        rel.endsWith(".jpg", true) || rel.endsWith(".jpeg", true) -> "image/jpeg"
        rel.endsWith(".mp4", true) -> "video/mp4"
        rel.endsWith(".json", true) -> "application/json"
        else -> "application/octet-stream"
    }
}
