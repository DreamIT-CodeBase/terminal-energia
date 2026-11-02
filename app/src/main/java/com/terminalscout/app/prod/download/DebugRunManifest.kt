package com.terminalscout.app.prod.download

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Remembers what each run PRODUCED, so `POST /v1/runs/{runId}/complete` can be told — including the
 * artifacts that never made it.
 *
 * ### Why this has to exist separately
 * The outbox is the queue, and a successful upload DELETES the staged file. By the time a run has
 * drained there is nothing left on disk to describe it, and an artifact that was abandoned as
 * un-sendable leaves no trace at all. So the very files most worth reporting — the missing ones — are
 * exactly the ones the outbox can no longer name. Recording the expected set at STAGING time is the only
 * point where it is all still known.
 *
 * ### Kept deliberately tiny and short-lived
 * One line per artifact, `relPath<TAB>sizeBytes`, in a directory of its own:
 *
 *   filesDir/debug_manifests/<runFolder>.tsv
 *
 * A run's file is DELETED the moment its completion is acknowledged, and for no other reason — there is
 * no age sweep, because a run that has not been reconciled yet is exactly the one worth keeping. A
 * manifest for a 40-artifact run is well under 2 KB.
 *
 * It lives outside `debug_outbox/` on purpose: [DebugUploadOutbox.pending] treats every file under that
 * root as a blob owed to the server, so a manifest stored there would be uploaded as though it were an
 * artifact.
 */
object DebugRunManifest {

    private const val TAG = "DebugRunManifest"
    private const val DIR = "debug_manifests"

    private fun dir(context: Context): File =
        File(context.applicationContext.filesDir, DIR).apply { mkdirs() }

    private fun fileFor(context: Context, runFolder: String): File =
        File(dir(context), "$runFolder.tsv")

    /**
     * Appends artifacts to [runFolder]'s expected set.
     *
     * Appending rather than rewriting because a run stages in several batches as its stages complete;
     * each call adds what it knows. Duplicate paths are harmless — [expected] de-duplicates on read.
     */
    fun record(context: Context, runFolder: String, artifacts: List<Pair<String, Long>>) {
        if (artifacts.isEmpty()) return
        try {
            val f = fileFor(context, runFolder)
            f.appendText(artifacts.joinToString("") { (path, size) -> "$path\t$size\n" })
        } catch (e: Exception) {
            // A missing manifest costs reconciliation for this run, not the run itself.
            Log.w(TAG, "could not record manifest for $runFolder", e)
        }
    }

    /** Every run with an outstanding manifest, oldest first — the order runs should be completed in. */
    fun runsAwaitingCompletion(context: Context): List<String> =
        dir(context).listFiles { f -> f.isFile && f.name.endsWith(".tsv") }
            ?.sortedBy { it.lastModified() }
            ?.map { it.name.removeSuffix(".tsv") }
            ?: emptyList()

    /**
     * [runFolder]'s expected artifacts as `relPath → sizeBytes`, first mention of each path winning.
     *
     * A line that cannot be parsed is skipped rather than failing the whole manifest: reporting most of
     * a run beats reporting none of it.
     */
    fun expected(context: Context, runFolder: String): List<Pair<String, Long>> {
        val f = fileFor(context, runFolder)
        if (!f.isFile) return emptyList()
        return try {
            val seen = LinkedHashMap<String, Long>()
            f.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                val tab = line.lastIndexOf('\t')
                val path = if (tab < 0) line.trim() else line.substring(0, tab)
                val size = if (tab < 0) 0L else line.substring(tab + 1).trim().toLongOrNull() ?: 0L
                if (path.isNotEmpty()) seen.putIfAbsent(path, size)
            }
            seen.map { (p, s) -> p to s }
        } catch (e: Exception) {
            Log.w(TAG, "could not read manifest for $runFolder", e)
            emptyList()
        }
    }

    /** Drops [runFolder]'s manifest once the server has acknowledged its completion. */
    fun clear(context: Context, runFolder: String) {
        val f = fileFor(context, runFolder)
        if (f.exists() && !f.delete()) Log.w(TAG, "could not delete manifest for $runFolder")
    }

}
