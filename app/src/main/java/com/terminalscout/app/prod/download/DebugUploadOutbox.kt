package com.terminalscout.app.prod.download

import android.content.Context
import com.terminalscout.app.prod.config.DeviceConfig
import android.util.Log
import java.io.File

/**
 * The durable queue behind the debug upload: a directory of artifacts still owed to the blob store.
 *
 *   filesDir/debug_outbox/<runFolder>/<relPath>
 *
 * **The directory IS the queue** — one staged file is one pending blob, its path under the outbox root
 * is its path in the container, and a successful upload deletes it. There is no table to keep in step
 * with the files, and nothing to recover after a crash: whatever is still on disk is still owed.
 *
 * Why a queue at all: uploads used to run fire-and-forget from the pipeline's own process, and the run
 * would wait up to 90 s for them before deleting its working files. Anything slower than that was
 * deleted **un-uploaded** — on a weak connection the artifacts simply did not arrive. Staging is local
 * and takes seconds, so the run can delete its originals as soon as it has handed them over, and
 * [DebugUploadWorker] gets them to Azure on WorkManager's terms (waiting for connectivity, retrying
 * with backoff, surviving the process being killed).
 *
 * **Artifacts are staged byte-for-byte — nothing is re-encoded.** Staging them as JPEG was tried and
 * reverted: the prepped canvases are the exact bytes handed to the reader, and a lossy copy of the
 * model's input is not evidence of what the model saw. JPEG puts its ringing on high-contrast edges,
 * which is precisely what a seven-segment digit is, so the one artifact you would reach for to explain
 * a misread is the one it could quietly corrupt. The bandwidth was the cheaper thing to spend.
 *
 * **Nothing here expires.** A staged artifact leaves only by being uploaded. There is no size cap and
 * no age cut-off, so a long outage holds everything — and a device kept offline long enough will
 * fill up. That is deliberate: an artifact deleted un-sent is gone for good, while a full device
 * is a visible problem with a person able to act on it.
 */
object DebugUploadOutbox {

    private const val TAG = "DebugOutbox"

    /** Extension marking a staged file that is still being written. See [publish]. */
    private const val PARTIAL_SUFFIX = ".part"

    /**
     * Extension marking a staged file the server REJECTED outright (a 4xx that is not a rate limit).
     *
     * Such a file is renamed rather than deleted. Deleting it would unblock the queue at the cost of
     * losing the artifact for good, and an artifact only leaves this device once it has actually been
     * delivered. Renaming takes it out of [pending] so it cannot stall the drain behind it.
     *
     * **It is never retried.** A 4xx that is not a rate limit means the request itself is wrong, and it
     * will be exactly as wrong on the next attempt — re-arming it every drain only spent battery and
     * data to fail identically. The file stays here, and [DebugUploader] stages a `logs.txt` alongside
     * it so the reason is recoverable. Clean memory is what removes it.
     *
     * Note this is NOT how a lack of network is handled: that is a transient outcome, the artifact stays
     * in [pending], and WorkManager brings the drain back when there is a connection.
     */
    private const val REJECTED_SUFFIX = ".rejected"

    /** How long a `.part` file is given before it is treated as abandoned. */
    private val STALE_PARTIAL_MS: Long get() = DeviceConfig.long("diagnostics.debug_upload.stale_partial_ms", 60 * 60 * 1000L)

    /** Root of the queue. */
    fun dir(context: Context): File = File(context.filesDir, "debug_outbox").apply { mkdirs() }

    /** Stages one on-disk artifact, unchanged, for upload as `<runFolder>/<relPath>`. */
    fun stage(context: Context, runFolder: String, relPath: String, src: File) {
        try {
            publish(destFor(context, runFolder, relPath)) { src.copyTo(it, overwrite = true) }
        } catch (e: Exception) {
            Log.w(TAG, "could not stage $runFolder/$relPath", e)
        }
    }

    /**
     * **Moves** one on-disk artifact into the queue as `<runFolder>/<relPath>`, leaving nothing behind.
     *
     * The counterpart to [stage], and the difference is the whole point: [stage] copies, which is right
     * for a working file the run still needs, and wrong for a recorded clip. A clip is the largest thing
     * this app holds — hundreds of megabytes — and copying one meant carrying two of it on the same
     * volume the next run's frames need. That is why the clip was never staged at all (see the note at
     * Stage 1 in `PriceBoardPipeline`). A move costs nothing: `recorded_videos/` and `debug_outbox/` are
     * both under `filesDir`, so this is a rename, exactly as publishing a finished recording is.
     *
     * Returns true when the artifact is in the queue and gone from [src].
     *
     * Falls back to copy-then-delete if the rename fails. That should not happen on one volume, but the
     * failure mode if it did matters more than the cost of handling it: returning false would leave the
     * caller holding a clip it is about to stop tracking, and losing footage is the one outcome this
     * whole path exists to prevent.
     */
    fun stageMove(context: Context, runFolder: String, relPath: String, src: File): Boolean {
        if (!src.exists()) {
            Log.w(TAG, "cannot move $runFolder/$relPath — the source is gone")
            return false
        }
        val dest = destFor(context, runFolder, relPath)
        val partial = File(dest.parentFile, "${dest.name}$PARTIAL_SUFFIX")
        // Into `.part` first and renamed into place, for the same reason every other staged file is:
        // the worker walks this directory while the run is still writing to it, and a half-arrived clip
        // under its final name would be uploaded truncated and deleted as done.
        try {
            if (!src.renameTo(partial)) {
                Log.w(TAG, "could not rename $relPath into the outbox — copying instead")
                src.copyTo(partial, overwrite = true)
                if (!src.delete()) Log.w(TAG, "copied $relPath but could not delete the original")
            }
            if (!partial.renameTo(dest)) {
                partial.delete()
                Log.e(TAG, "could not publish $relPath into the outbox — it is LOST")
                return false
            }
            Log.d(TAG, "moved $relPath into the outbox as $runFolder/$relPath (${dest.length()} bytes)")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "could not move $runFolder/$relPath into the outbox", e)
            return false
        }
    }

    /** Stages an in-memory JSON/text artifact for upload as `<runFolder>/<relPath>`. */
    fun stageText(context: Context, runFolder: String, relPath: String, text: String) {
        try {
            publish(destFor(context, runFolder, relPath)) { it.writeText(text) }
        } catch (e: Exception) {
            Log.w(TAG, "could not stage $runFolder/$relPath", e)
        }
    }

    /**
     * Writes via a `.part` file and renames it into place, so a staged artifact appears **complete or
     * not at all**.
     *
     * A run stages while the worker drains, and both walk the same directory. Writing straight to the
     * final name means the worker can pick up a half-copied clip, upload the truncated bytes, and delete
     * the file as done — a corrupt blob that nothing would ever correct. A rename within one directory
     * is atomic, and [pending] ignores `.part`.
     */
    private fun publish(dest: File, write: (File) -> Unit) {
        val partial = File(dest.parentFile, "${dest.name}$PARTIAL_SUFFIX")
        write(partial)
        if (!partial.renameTo(dest)) {
            // Rare (dest held open, say). The partial would otherwise sit there being skipped forever.
            partial.delete()
            Log.w(TAG, "could not publish ${dest.name} into the outbox")
        }
    }

    /**
     * Deletes `.part` files left by a process that died mid-write. [pending] skips them, so without this
     * they would be skipped for good rather than retried — and the artifact is long gone by then, so
     * there is nothing to finish; only the bytes to reclaim.
     */
    /**
     * Parks a rejected artifact for good: out of the drain, never retried, bytes kept.
     *
     * **A rejected CLIP is deleted instead.** The keep-the-bytes rule is right for every artifact this
     * was written for — a JSON, a canvas, a manifest — where the cost of holding one forever is
     * kilobytes and the value of still having it is the whole diagnosis. A clip inverts both sides: it
     * is hundreds of megabytes pinned until somebody runs Clean memory, and it is the one artifact whose
     * *reason* for being rejected is fully recoverable without it, because [DebugUploader.recordRejection]
     * stages a `logs.txt` beside it carrying the server's verbatim reason and the run's logcat tail.
     * Keeping rejected clips would fill a device that is otherwise working perfectly.
     */
    fun reject(context: Context, file: File) {
        if (isClip(file)) {
            val bytes = file.length()
            if (file.delete()) {
                Log.w(TAG, "server rejected ${file.name} ($bytes bytes) — DELETED; the reason is in logs.txt")
            } else {
                Log.w(TAG, "could not delete the rejected clip ${file.name} — leaving it queued")
            }
            return
        }
        val parked = File(file.parentFile, "${file.name}$REJECTED_SUFFIX")
        if (file.renameTo(parked)) {
            Log.w(TAG, "server rejected ${file.name} — parked, not retried; kept until Clean memory")
        } else {
            Log.w(TAG, "could not park rejected ${file.name} — leaving it queued")
        }
    }

    /** Whether a staged artifact is a recorded clip, which [reject] treats differently. */
    private fun isClip(file: File): Boolean = file.name.endsWith(".mp4", ignoreCase = true)

    /** How many artifacts are parked as rejected, for the pending-upload summary. */
    fun rejectedCount(context: Context): Int =
        dir(context).walkTopDown().count { it.isFile && it.name.endsWith(REJECTED_SUFFIX) }

    fun discardStalePartials(context: Context) {
        val cutoff = System.currentTimeMillis() - STALE_PARTIAL_MS
        dir(context).walkTopDown()
            .filter { it.isFile && it.name.endsWith(PARTIAL_SUFFIX) && it.lastModified() < cutoff }
            .forEach { if (it.delete()) Log.w(TAG, "discarded incomplete staged file ${it.name}") }
    }

    /**
     * Everything still owed, as `blobPath → file`, **oldest run first** so a long backlog drains in the
     * order it was produced rather than leaving the earliest run permanently last in line.
     */
    fun pending(context: Context): List<Pair<String, File>> {
        val root = dir(context)
        val runs = root.listFiles { f -> f.isDirectory }?.sortedBy { it.lastModified() } ?: return emptyList()
        return runs.flatMap { run ->
            run.walkTopDown()
                .filter {
                    it.isFile &&
                        !it.name.endsWith(PARTIAL_SUFFIX) &&      // still being written
                        !it.name.endsWith(REJECTED_SUFFIX)       // parked, re-armed next drain
                }
                .map { blobPathOf(root, it) to it }
                .toList()
        }
    }

    /**
     * Run folders that still owe the server something — counting artifacts parked as rejected, which a
     * later pass may still deliver. A run is finished only when it appears in neither this nor [pending].
     */
    fun owedRuns(context: Context): Set<String> {
        val root = dir(context)
        return root.listFiles { f -> f.isDirectory }
            ?.filter { run ->
                run.walkTopDown().any {
                    it.isFile &&
                        !it.name.endsWith(PARTIAL_SUFFIX) &&
                        // A rejected artifact is NOT owed: it will never be sent, so a run holding
                        // one is finished as far as this device is concerned. Counting it as owed
                        // would block that run's completion forever and strand its manifest — and
                        // the whole point of completing it is to report the artifact as missing.
                        !it.name.endsWith(REJECTED_SUFFIX)
                }
            }
            ?.mapTo(HashSet()) { it.name }
            ?: emptySet()
    }

    /** Drops run folders left empty by a successful drain, so the outbox doesn't collect husks. */
    fun removeEmptyRunDirs(context: Context) {
        dir(context).listFiles { f -> f.isDirectory }?.forEach { run ->
            if (run.walkTopDown().none { it.isFile }) run.deleteRecursively()
        }
    }

    /** `filesDir/debug_outbox/<runFolder>/<rel>` → `<runFolder>/<rel>`, always with `/` separators. */
    private fun blobPathOf(root: File, file: File): String =
        file.relativeTo(root).path.replace(File.separatorChar, '/')

    private fun destFor(context: Context, runFolder: String, relPath: String): File =
        File(dir(context), "$runFolder/$relPath").apply { parentFile?.mkdirs() }

    /**
     * What the outbox is holding, for the upload prompt.
     *
     * @param files    artifacts still waiting to be delivered
     * @param bytes    their total size
     * @param runs     how many runs they belong to
     * @param rejected artifacts parked as permanently rejected; these will NEVER be sent, so they are
     *   counted separately rather than folded into a figure that implies they might be
     */
    data class Backlog(val files: Int, val bytes: Long, val runs: Int, val rejected: Int) {
        val megabytes: Long get() = bytes / 1_048_576
        val isEmpty: Boolean get() = files == 0
    }

    /** Measures the outbox without touching it. */
    fun backlog(context: Context): Backlog {
        val pendingFiles = pending(context)
        return Backlog(
            files = pendingFiles.size,
            bytes = pendingFiles.sumOf { (_, f) -> f.length() },
            runs = pendingFiles.mapTo(HashSet()) { (path, _) -> path.substringBefore('/') }.size,
            rejected = rejectedCount(context)
        )
    }

    /** Total bytes still owed — for logging how much a long outage is holding. */
    fun sizeOf(context: Context): Long =
        dir(context).walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
