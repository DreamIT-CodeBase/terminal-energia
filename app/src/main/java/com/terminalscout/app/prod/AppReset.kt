package com.terminalscout.app.prod

import android.content.Context
import android.util.Log
import androidx.work.WorkManager
import com.terminalscout.app.prod.upload.AzureMapsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * "Clean memory" — returns the device to a first-install state.
 *
 * ### Why this needs to exist
 * Nothing in this app expires any more. A staged artifact leaves only when it has been uploaded, a parked
 * OCR run only when it has been read, a queued clip only when it has been processed. That is deliberate —
 * a capture taken correctly is never thrown away to save space — but it means the only bound on disk use
 * is somebody deciding the backlog is no longer worth keeping. This is that decision, made explicitly.
 *
 * ### It deletes work that was never delivered
 * This is not a cache clear. Artifacts still waiting for a network, board images still waiting to be read,
 * and clips still waiting to be processed are all destroyed, and none of it can be recovered afterwards.
 * The confirmation dialog says so in those terms; do not soften it.
 *
 * ### What survives, and why
 *  - **Vehicle / device details.** Typed by a person, and not "memory" in any sense — losing it would
 *    just mean re-entering it. [KEEP_DATABASES]
 *  - **Sideloaded test clips.** Dev fixtures in external storage, ~130 MB pushed over adb. Deleting them
 *    would turn a cleanup into a re-provisioning job.
 *
 * ### Order matters
 * Background work is cancelled FIRST. The upload worker and the model downloader both write into the
 * directories below, and one of them mid-write while a delete walks the same tree leaves a half-file that
 * looks complete — precisely the corruption the outbox's `.part` discipline exists to avoid.
 */
object AppReset {

    private const val TAG = "AppReset"

    /**
     * Every private sub-directory the app owns. Deleted whole.
     *
     * Listed explicitly rather than wiping `filesDir` wholesale: an unnamed sweep would also take
     * whatever a future feature puts there, including things that should survive a cleanup. Anything new
     * has to be added here deliberately.
     */
    private val DIRECTORIES = listOf(
        "frames",            // Stage 1 working images
        "crops",             // Stage 2 board crops
        "prepped",           // Stage 3 canvases
        // The clip currently being recorded into, if one is. Cancelling background work does not stop
        // the capture lane — it is a foreground service, not a worker — so a wipe during a capture
        // takes the part-file out from under CameraX. That is the same exposure this had when the
        // recorder wrote straight into recorded_videos/, and the outcome is better: the recording
        // finalizes with an error, the lane reports it, and no truncated clip reaches the queue.
        "staged_videos",
        "recorded_videos",   // recorded clips
        // NOTE: the Stage 0 station map is NOT wiped. It moved from a JSON file in
        // filesDir/location_mapping to the location_map.db table, which this sweep does not
        // touch — and should not: it is ~70 KB, so deleting it frees nothing, and it carries
        // last_capture_time, which is the one thing in the app that cannot be re-fetched.
        "metadata_videos",   // the provenance sidecar written beside each clip
        "ocr_pending",       // board images still owed a read
        "debug_outbox",      // artifacts still owed an upload
        "debug_manifests",   // per-run reconciliation records
        "debug_nouploads",   // markers for runs excluded from upload (test clips)
        "runs",              // the debug run picker's stored runs
        "pending_uploads",   // images staged for the readings API (see upload/UploadQueue)
        "stations",          // the station mapping — refetched at the start of the next run
        "azure_maps",        // the day's timezone/sunrise answer — refetched on the next catch-up
        // The fetched tunables. Safe to delete: `DeviceConfig` falls back to the build's own constants
        // until the next fetch lands, so a wiped device runs the APK it is, which is the state a reset
        // is asking for. Re-requested by the next `AppSetup` pass.
        "config",
        "models"             // the downloaded ML models — re-downloaded on the next launch
    )

    /**
     * Directories that existed under `filesDir` but are not in [DIRECTORIES], logged rather than deleted.
     *
     * The list above is explicit so that a cleanup can never take something a future feature meant to
     * keep — but the flip side is that a new directory is silently missed, which is how `pending_uploads`
     * was nearly left behind on a device holding 9 GB. This makes the omission visible instead.
     */
    private fun logUnknownDirs(app: Context) {
        val known = DIRECTORIES.toSet() + "profileInstalled"
        app.filesDir.listFiles { f -> f.isDirectory }
            ?.filter { it.name !in known }
            ?.forEach { Log.w(TAG, "NOT cleaned (unknown to AppReset): files/${it.name}") }
    }

    /** Databases wiped: run/upload state. */
    /**
     * Databases a clean removes.
     *
     * `location_map.db` is here by instruction, and it is the expensive one: the 349 stations re-fetch
     * from Azure Maps in about fifteen seconds, but `last_capture_time` is history that cannot be
     * rebuilt from anywhere. A clean therefore forgets which forecourts have been captured.
     *
     * `error_log.db` is deliberately NOT here. A clean reclaims space from working files; the problems
     * the log lists do not stop being true because somebody freed some disk.
     */
    private val DROP_DATABASES = listOf("uploads.db", "location_map.db")

    /** Databases preserved: the vehicle/device details a person typed in. */
    private val KEEP_DATABASES = listOf("user.db")

    /** What a cleanup removed, for the confirmation shown afterwards. */
    data class Report(val bytesFreed: Long, val itemsDeleted: Int) {
        val megabytesFreed: Long get() = bytesFreed / 1_048_576
    }

    /**
     * Wipes everything and returns what was freed. Must not run on the main thread — this walks and
     * deletes gigabytes.
     */
    suspend fun wipe(context: Context): Report = withContext(Dispatchers.IO) {
        val app = context.applicationContext

        // FIRST: stop anything that writes to what we are about to delete.
        try {
            WorkManager.getInstance(app).cancelAllWork().result.get()
            Log.i(TAG, "cancelled all background work")
        } catch (e: Exception) {
            Log.w(TAG, "could not cancel background work — continuing", e)
        }

        // The stored day's answer goes too, or a "starting over" run would still read what the previous
        // one fetched. Costs nothing: the next catch-up re-fetches it.
        AzureMapsStore.clear()

        logUnknownDirs(app)

        var bytes = 0L
        var items = 0

        for (name in DIRECTORIES) {
            val dir = File(app.filesDir, name)
            if (!dir.exists()) continue
            val size = sizeOf(dir)
            val count = dir.walkTopDown().count { it.isFile }
            if (dir.deleteRecursively()) {
                bytes += size
                items += count
                Log.i(TAG, "removed $name (${size / 1_048_576} MB, $count file(s))")
            } else {
                Log.w(TAG, "could not fully remove $name")
            }
        }

        // The cache is wiped by CONTENTS, not by deleting the directory itself: the OS owns that node and
        // several libraries hold a handle to it (the LiteRT-LM engine takes cacheDir at construction).
        app.cacheDir.listFiles()?.forEach { entry ->
            val size = if (entry.isDirectory) sizeOf(entry) else entry.length()
            val count = if (entry.isDirectory) entry.walkTopDown().count { it.isFile } else 1
            if (entry.deleteRecursively()) {
                bytes += size
                items += count
            }
        }

        // The on-device OCR replay fixture, in external files storage. `testclips/` next to it is left
        // alone on purpose — see the class note.
        (app.getExternalFilesDir(null))?.let { ext ->
            val fixture = File(ext, com.terminalscout.app.prod.pipeline.OcrResultStore.FILE_NAME)
            if (fixture.isFile) {
                bytes += fixture.length()
                if (fixture.delete()) items++
            }
        }

        for (db in DROP_DATABASES) {
            // The journal/WAL siblings must go too, or SQLite reopens the old contents from them.
            for (suffix in listOf("", "-journal", "-wal", "-shm")) {
                val f = app.getDatabasePath(db + suffix)
                if (f.isFile) {
                    bytes += f.length()
                    if (f.delete()) items++
                }
            }
            Log.i(TAG, "dropped database $db")
        }

        Log.i(TAG, "clean finished — freed ${bytes / 1_048_576} MB across $items file(s); " +
            "kept ${KEEP_DATABASES.joinToString()}")
        Report(bytes, items)
    }

    /** Total bytes currently held by everything [wipe] would delete — for the confirmation dialog. */
    suspend fun currentUsage(context: Context): Long = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        var bytes = DIRECTORIES.sumOf { sizeOf(File(app.filesDir, it)) }
        bytes += sizeOf(app.cacheDir)
        bytes
    }

    private fun sizeOf(dir: File): Long =
        if (!dir.exists()) 0L else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
