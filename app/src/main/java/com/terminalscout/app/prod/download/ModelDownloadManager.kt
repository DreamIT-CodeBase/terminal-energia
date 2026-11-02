package com.terminalscout.app.prod.download

import android.content.Context
import android.util.Log
import com.terminalscout.app.prod.ContainedFaults
import com.terminalscout.app.prod.containedIn
import com.terminalscout.app.prod.config.DeviceConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Process-wide download of the ML models into the app's private storage (see [ModelRepository]).
 * Progress is published as a [StateFlow] so the download screen can render classic per-model progress
 * bars and survive screen recreation — the download keeps running on an application-scoped coroutine
 * even if the observing fragment is torn down and re-created.
 *
 * **Brokered, not signed here.** Each model's bytes come from a short-lived URL that [DeviceApi] returns
 * for `<container>/<blob>`; the app holds no storage credential of its own. This is the half of the
 * migration that let the account key leave the APK at all — the uploader and the downloader shared that
 * key, so brokering uploads alone would have left it in place.
 *
 * **Resumable.** Bytes stream to a `.part` file that is atomically renamed on completion, so an
 * interrupted download never leaves a half-file looking "downloaded" — and a new attempt continues from
 * the bytes already there with an HTTP `Range` request rather than starting over. That matters most for
 * A multi-GB model on a slow link can outlive its own 1-hour URL, so an expiry mid-transfer
 * fetches a fresh one and picks up at the same offset.
 */
object ModelDownloadManager {

    private const val TAG = "ModelDownload"

    /** Shown under this object's rows on the errors screen. */
    const val SOURCE = "Model download"

    enum class Status { PENDING, DOWNLOADING, DONE, FAILED }

    /** Live progress for one model. */
    data class ModelProgress(
        val spec: ModelRepository.ModelSpec,
        val downloadedBytes: Long = 0L,
        val totalBytes: Long = -1L,
        val status: Status = Status.PENDING
    ) {
        /** 0..1 completion, or 0 when the total size isn't known yet (→ show an indeterminate bar). */
        val fraction: Float
            get() = if (totalBytes > 0L) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
        val hasTotal: Boolean get() = totalBytes > 0L
    }

    /** Aggregate state across all models. */
    data class State(
        val models: List<ModelProgress>,
        val allDone: Boolean = false,
        val failed: Boolean = false,
        val errorMessage: String? = null
    )

    // No total-call timeout (a model can be multi-GB); rely on connect/read timeouts for stalls.
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    /**
     * The download scope. Object-level on purpose — see the class note: a transfer has to survive with
     * no screen open, so it cannot hang off a lifecycle.
     *
     * **The handler is what stops a failed download being a process death.** `SupervisorJob` only keeps
     * a failing child from cancelling its siblings; the exception still goes to the scope's
     * `CoroutineExceptionHandler`, and the default one hands it to the thread's uncaught-exception
     * handler. [runDownloads] guards each model's transfer individually, but the seeding pass above the
     * loop and every `publish` sit outside those guards, and an `OutOfMemoryError` part-way through a
     * multi-gigabyte body is not an `Exception` at all. `AppSetup.ensureModels` calls [ensureStarted] on
     * every launch and every resume, so without this a download fault is a crash loop on a deployed
     * unit rather than a model that failed to arrive — and the retry is the next setup pass either way.
     */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            containedIn(SOURCE, ContainedFaults.MODEL_DOWNLOAD, "model download")
    )

    /**
     * How many times one model may re-open its connection — covering both a dropped transfer and an
     * expired URL.
     *
     * Bounded because both failures look the same from here (the body simply stops arriving), so without
     * a cap a model that can never finish would retry forever. Generous because a multi-GB download over
     * mobile legitimately needs several continuations.
     */
    private val MAX_CONTINUATIONS: Int
        get() = DeviceConfig.int("setup.models.download_max_continuations", 24)

    /**
     * Asks the API for a fresh download URL for [spec].
     *
     * A permanent failure (unknown model, bad fleet secret) is raised as [IOException] so it lands in
     * [runDownloads]'s existing per-model FAILED handling and surfaces on the screen with a Retry button,
     * rather than being retried silently against a server that will keep refusing.
     */
    private suspend fun grantFor(context: Context, spec: ModelRepository.ModelSpec): DeviceApi.DownloadGrant =
        when (val res = DeviceApi.modelDownload(context, spec.name)) {
            is DeviceApi.ApiResult.Ok -> res.value
            is DeviceApi.ApiResult.Permanent ->
                throw IOException("no download URL for ${spec.name} (HTTP ${res.code}): ${res.detail}")
            DeviceApi.ApiResult.Transient ->
                throw IOException("could not reach the API for ${spec.name}")
        }

    private val _state = MutableStateFlow(
        State(models = ModelRepository.ALL.map { ModelProgress(it) })
    )
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null

    /**
     * Start downloading any missing models if not already in progress. Idempotent — calling it while
     * a download is running (or after it finished) does nothing. Call again via [retry] after a
     * failure to resume.
     */
    fun ensureStarted(context: Context) {
        val appContext = context.applicationContext
        if (job?.isActive == true) return
        job = scope.launch { runDownloads(appContext) }
    }

    /** Re-attempt after a failure. The previous job has ended, so this starts a fresh pass. */
    fun retry(context: Context) = ensureStarted(context)

    private suspend fun runDownloads(context: Context) {
        // Seed from disk so already-present models show as done immediately.
        var progress = ModelRepository.ALL.map { spec ->
            if (ModelRepository.isDownloaded(context, spec)) {
                val len = ModelRepository.file(context, spec).length()
                ModelProgress(spec, len, len, Status.DONE)
            } else {
                ModelProgress(spec, status = Status.PENDING)
            }
        }
        publish(progress)

        progress.forEachIndexed { i, mp ->
            if (mp.status == Status.DONE) return@forEachIndexed
            try {
                downloadOne(context, mp.spec) { downloaded, total ->
                    progress = progress.replaceAt(i) {
                        it.copy(downloadedBytes = downloaded, totalBytes = total, status = Status.DOWNLOADING)
                    }
                    publish(progress)
                }
                progress = progress.replaceAt(i) { it.copy(status = Status.DONE) }
                publish(progress)
            } catch (c: CancellationException) {
                // Not a failure, and it must not be reported as one: cancellation is how a scope stops
                // its children, it arrives as a throwable through every suspension point in the
                // transfer, and swallowing it here would leave a download that has been told to stop
                // still writing to disk. Rethrown so the pass actually ends.
                throw c
            } catch (t: Throwable) {
                // Throwable, not Exception. A multi-gigabyte body decoded into memory is the one place
                // in this app where OutOfMemoryError is an ordinary outcome rather than a curiosity,
                // and an Error escaping here reaches the thread's uncaught-exception handler — a crash
                // for a failed download, on a device with no screen and nobody to retry it.
                Log.e(TAG, "Download failed for ${mp.spec.key}", t)
                progress = progress.replaceAt(i) { it.copy(status = Status.FAILED) }
                publish(progress, failed = true, error = t.localizedMessage ?: "Download failed")
                return
            }
        }
        publish(progress)
    }

    /**
     * Streams [spec] to a `.part` file, resuming where a previous attempt stopped, then atomically
     * renames it into place.
     *
     * The `.part` file is deliberately NOT deleted up front: whatever is already there are bytes of this
     * exact model version (the filename carries the version), so they are kept and the transfer continues
     * from that offset with `Range: bytes=<have>-`.
     *
     * The loop exists because a long download fails in two ways that look identical from here — the
     * connection drops, or the signed URL expires mid-transfer — and both end with the body simply
     * stopping short of `size_bytes`. A `401`/`403` gets a fresh URL; anything else short just reconnects
     * from the current offset. A continuation that transfers nothing counts as a stall, so a genuinely
     * stuck download fails instead of spinning.
     */
    private suspend fun downloadOne(
        context: Context,
        spec: ModelRepository.ModelSpec,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ) {
        val dest = ModelRepository.file(context, spec)
        val part = File(dest.parentFile, spec.fileName + ".part")

        var grant = grantFor(context, spec)
        val total = grant.sizeBytes
        onProgress(part.length(), total)

        var continuations = 0
        while (total <= 0 || part.length() < total) {
            val from = part.length()
            if (continuations++ > MAX_CONTINUATIONS) {
                throw IOException("gave up on ${spec.name} after $MAX_CONTINUATIONS continuation(s) at $from bytes")
            }

            val request = Request.Builder().url(grant.downloadUrl).apply {
                // Absent on the first attempt so a server that ignores ranges still behaves.
                if (from > 0) header("Range", "bytes=$from-")
            }.build()

            var refreshUrl = false
            client.newCall(request).execute().use { response ->
                when {
                    // The SAS died mid-transfer. Everything downloaded so far is still good.
                    response.code == 401 || response.code == 403 -> {
                        Log.d(TAG, "${spec.key}: download URL expired at $from bytes — refreshing")
                        refreshUrl = true
                    }
                    // A range request that the server would not honour: start this file over rather than
                    // appending to bytes it is about to duplicate.
                    from > 0 && response.code == 200 -> {
                        Log.w(TAG, "${spec.key}: range ignored — restarting from 0")
                        part.delete()
                    }
                    !response.isSuccessful ->
                        throw IOException("HTTP ${response.code} for ${spec.name}")
                    else -> {
                        val body = response.body ?: throw IOException("empty body for ${spec.name}")
                        appendTo(part, body.byteStream(), from, total, onProgress)
                    }
                }
            }
            if (refreshUrl) grant = grantFor(context, spec)

            // No size to compare against means one pass is all we get; trust it and stop.
            if (total <= 0) break
            if (part.length() == from && !refreshUrl) {
                Log.w(TAG, "${spec.key}: no progress at $from bytes")
            }
        }

        if (!part.renameTo(dest)) {
            // Rename can fail across some filesystems; fall back to a copy.
            part.copyTo(dest, overwrite = true)
            part.delete()
        }
        Log.i(TAG, "Downloaded ${spec.key} -> ${dest.path} (${dest.length()} bytes)")
    }

    /** Appends a response body to [part] from byte [from], reporting progress against [total]. */
    private fun appendTo(
        part: File,
        input: java.io.InputStream,
        from: Long,
        total: Long,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ) {
        input.use { source ->
            // append = true: this may be a continuation, and truncating would discard the resume.
            FileOutputStream(part, from > 0).use { output ->
                val buffer = ByteArray(1 shl 16) // 64 KB
                var downloaded = from
                var lastPublished = from
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    downloaded += read
                    // Throttle UI updates to ~every 512 KB so the flow isn't spammed.
                    if (downloaded - lastPublished >= (512L * 1024L)) {
                        onProgress(downloaded, total)
                        lastPublished = downloaded
                    }
                }
                output.flush()
                onProgress(downloaded, total)
            }
        }
    }

    private fun publish(
        models: List<ModelProgress>,
        failed: Boolean = false,
        error: String? = null
    ) {
        _state.value = State(
            models = models,
            allDone = !failed && models.all { it.status == Status.DONE },
            failed = failed,
            errorMessage = error
        )
    }

    private inline fun List<ModelProgress>.replaceAt(
        index: Int,
        transform: (ModelProgress) -> ModelProgress
    ): List<ModelProgress> = mapIndexed { i, mp -> if (i == index) transform(mp) else mp }
}
