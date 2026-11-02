package com.terminalscout.app.prod

import android.content.Context
import com.terminalscout.app.prod.config.DeviceConfig
import java.io.FileOutputStream
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps every completed run's Stage-6 payload **and a few of its board images** on disk, so the
 * dashboard can show any earlier run and a tester can compare two runs side by side.
 *
 * One directory per run under `runs/`, named by the completion time so a directory listing is already
 * in order. Each run gets its OWN entry — re-running the same clip does not overwrite the previous
 * one, because comparing a clip against its own earlier run is the whole point.
 *
 * **The frames are copied, not referenced.** The originals live in the cache directory, which Android
 * may clear at any time and which the next run overwrites; a run's images have to outlive that or the
 * picker would show gaps for exactly the older runs it exists to display.
 *
 * Nothing here is load-bearing: a read or write failure is logged and swallowed, and the dashboard
 * simply shows fewer runs, or a run without its images.
 */
class RunOutputStore(private val context: Context) {

    /**
     * One completed run.
     *
     * @param id           the directory name, unique per run and used to re-select after a refresh
     * @param name         the clip's name, as the queue showed it
     * @param finishedAtMs when the run completed
     * @param payload      the Stage-6 enriched payload, exactly as it was rendered
     * @param frames       board images kept for this run, oldest first (may be empty)
     */
    data class Entry(
        val id: String,
        val name: String,
        val finishedAtMs: Long,
        val payload: String,
        val frames: List<File>
    ) {
        /** What the dropdown shows: `20260730-134136-cefb · 09 Aug 18:42`. */
        val label: String
            get() = "$name  ·  ${TIME_FORMAT.format(Date(finishedAtMs))}"
    }

    /**
     * Saves one run and returns its entry, or null if it could not be written.
     *
     * @param framePaths the board images sent to OCR, in time order. The **latest**
     *   [FRAMES_PER_RUN] are kept — the last frames are nearest the board and the clearest, which is
     *   what makes a run identifiable at a glance.
     */
    fun save(name: String, payload: String, framePaths: List<String>): Entry? {
        val finishedAt = System.currentTimeMillis()
        val id = "%013d_%s".format(finishedAt, name.replace(NON_FILENAME, "_").take(60))
        return try {
            val dir = File(runsDir(), id).apply { mkdirs() }
            File(dir, RUN_JSON).writeText(
                JSONObject().apply {
                    put("name", name)
                    put("finished_at_ms", finishedAt)
                    put("payload", payload)
                }.toString()
            )

            val kept = framePaths.takeLast(FRAMES_PER_RUN).mapIndexedNotNull { i, path ->
                val src = File(path)
                if (!src.isFile) return@mapIndexedNotNull null
                thumbnail(src, File(dir, "frame_%02d.jpg".format(i)))
            }

            prune()
            Log.d(TAG, "stored run $id with ${kept.size} board image(s)")
            Entry(id, name, finishedAt, payload, kept)
        } catch (e: Exception) {
            Log.e(TAG, "could not save run output for $name", e)
            null
        }
    }

    /** Every stored run, **newest first**. Unreadable runs are skipped, not fatal. */
    fun list(): List<Entry> {
        migrateLegacy()
        val dirs = runsDir().listFiles { f -> f.isDirectory } ?: return emptyList()
        return dirs.sortedByDescending { it.name }.mapNotNull { dir ->
            try {
                val o = JSONObject(File(dir, RUN_JSON).readText())
                Entry(
                    id = dir.name,
                    name = o.optString("name", dir.name),
                    finishedAtMs = o.optLong("finished_at_ms"),
                    payload = o.optString("payload"),
                    // Both extensions: board images are written as downscaled JPEG now, but runs stored
                    // by an earlier build are full-size PNG and should keep showing in the picker.
                    frames = dir.listFiles { f ->
                        f.isFile && (f.name.endsWith(".jpg") || f.name.endsWith(".png"))
                    }?.sortedBy { it.name }.orEmpty()
                )
            } catch (e: Exception) {
                Log.w(TAG, "skipping unreadable run ${dir.name}", e)
                null
            }
        }
    }

    /**
     * Drops the oldest runs past [MAX_RUNS], images and all — the directory and everything in it.
     *
     * Runs carry board images now (roughly half a megabyte each), so this bounds disk as well as the
     * dropdown. Pruning happens on every save, so the store is self-limiting rather than needing a
     * clean-up pass.
     */
    private fun prune() {
        val dirs = runsDir().listFiles { f -> f.isDirectory } ?: return
        if (dirs.size <= MAX_RUNS) return
        dirs.sortedByDescending { it.name }.drop(MAX_RUNS).forEach { old ->
            if (!old.deleteRecursively()) Log.w(TAG, "could not prune ${old.name}")
        }
    }

    /**
     * Moves runs written by the earlier flat layout (`runs/<id>.json`) into the current one
     * (`runs/<id>/run.json`).
     *
     * Without this those runs are invisible to [list] AND to [prune], which only look at directories —
     * so they would sit on disk forever, listed by nothing. Migrating rather than deleting keeps their
     * payloads comparable; they simply have no board images, which the picker already handles.
     */
    private fun migrateLegacy() {
        val strays = runsDir().listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return
        for (old in strays) {
            try {
                val dir = File(runsDir(), old.name.removeSuffix(".json"))
                if (dir.exists()) { old.delete(); continue }   // already migrated
                dir.mkdirs()
                old.copyTo(File(dir, RUN_JSON), overwrite = true)
                old.delete()
                Log.d(TAG, "migrated legacy run ${dir.name}")
            } catch (e: Exception) {
                Log.w(TAG, "could not migrate legacy run ${old.name}", e)
            }
        }
    }

    /**
     * Writes a downscaled JPEG copy of one board image.
     *
     * These exist only so a tester can recognise which run is which in the picker — they are never read
     * by a model and never uploaded, so the full 640 canvas is far more than the job needs. A long edge
     * of [THUMB_MAX_PX] as JPEG is roughly a tenth of the PNG, which matters now that everything else on
     * this device is kept until it is delivered: the debug picker should not be competing for space with
     * artifacts that still have somewhere to go.
     *
     * JPEG is safe HERE specifically because nothing decides anything from these pixels. The staged
     * upload copies stay lossless PNG for the opposite reason — they are the evidence of what the reader
     * actually saw.
     */
    private fun thumbnail(src: File, dest: File): File? = try {
        val full = BitmapFactory.decodeFile(src.path)
        if (full == null) {
            Log.w(TAG, "could not decode board image ${src.path}")
            null
        } else {
            // Both bitmaps freed on EVERY exit, which is what the `finally` is for.
            //
            // The recycles used to sit after the write, so a throw in `createScaledBitmap` or in
            // `compress` — a full volume, a heap with no room for the scaled copy — leaked the decoded
            // board image, and the catch below then reported a thumbnail that could not be written.
            // This runs once per board image of every stored run, so a device meeting the same fault
            // repeatedly leaks its way to a kill. Exactly the pattern
            // `VideoFrameExtractor.writeFrame` had.
            var small: Bitmap? = null
            try {
                val scale = THUMB_MAX_PX.toFloat() / maxOf(full.width, full.height)
                small = if (scale >= 1f) full else Bitmap.createScaledBitmap(
                    full, (full.width * scale).toInt().coerceAtLeast(1),
                    (full.height * scale).toInt().coerceAtLeast(1), true
                )
                FileOutputStream(dest).use { small.compress(Bitmap.CompressFormat.JPEG, THUMB_QUALITY, it) }
            } finally {
                small?.let { if (it !== full) it.recycle() }
                full.recycle()
            }
            dest
        }
    } catch (t: Throwable) {
        // `Throwable`: this decodes a board image and allocates a scaled copy of it, so
        // `OutOfMemoryError` is a real way for it to fail — and it is called from `save`, which is
        // called from the processing lane AFTER a reading has already been produced. Losing a thumbnail
        // on the run picker is the correct price; the reading is filed and uploaded either way.
        Log.w(TAG, "could not write thumbnail for ${src.path}", t)
        null
    }

    private fun runsDir(): File = File(context.filesDir, "runs").apply { mkdirs() }

    companion object {
        private const val TAG = "RunOutputStore"

        /**
         * How many runs are kept. The newest [MAX_RUNS] survive; older ones are deleted outright,
         * payload and board images together, so nothing accumulates on the device.
         */
        private val MAX_RUNS: Int get() = DeviceConfig.int("diagnostics.run_output_store.max_runs", 10)

        /** Board images kept per run — enough to recognise the board, few enough to stay cheap. */
        val FRAMES_PER_RUN: Int get() = DeviceConfig.int("diagnostics.run_output_store.frames_per_run", 5)

        private const val RUN_JSON = "run.json"

        /** Long edge of a stored board thumbnail, in pixels. Enough to tell two forecourts apart. */
        private val THUMB_MAX_PX: Int get() = DeviceConfig.int("diagnostics.run_output_store.thumb_max_px", 320)

        /** JPEG quality for those thumbnails. */
        private val THUMB_QUALITY: Int get() = DeviceConfig.int("diagnostics.run_output_store.thumb_quality", 80)
        private val NON_FILENAME = Regex("""[^A-Za-z0-9._-]""")
        private val TIME_FORMAT = SimpleDateFormat("dd MMM HH:mm", Locale.US)
    }
}
