package com.terminalscout.app.prod.pipeline

import android.content.Context
import android.util.Log
import java.io.File

/**
 * The working sub-trees Stages 1–3 write while processing ONE clip:
 *
 *   filesDir/frames/<videoId>/    a full-resolution PNG of every sampled frame
 *   filesDir/crops/<videoId>/     every detected board, cropped at native size
 *   filesDir/prepped/<videoId>/   the 640 canvases Stage 4 reads
 *
 * All three are keyed by the same `videoId` (the UUID [VideoFrameExtractor] mints per clip), so one
 * run's whole working set is exactly those three directories — which is what makes [delete] a single
 * call rather than a search.
 *
 * **These used to live forever.** A UHD clip sampled at 15 fps leaves ~220 full-resolution PNGs behind,
 * several hundred megabytes for one video, and nothing ever removed them. A queue of a few clips
 * filled the device, and then two things went wrong at once: writes began failing, and Android
 * started reclaiming `cacheDir` — which is where clips still WAITING in the queue were kept, so it
 * deleted their source videos out from under them. Every artifact is uploaded to the debug container,
 * so once that has happened the local copies have no further use.
 *
 * **They are no longer in `cacheDir` either.** The same reclaim that ate queued clips could equally
 * take a run's crops or canvases mid-pipeline — most likely on exactly the full device where a long
 * offline backlog is already waiting. Nothing in this app's working set may be deleted by anyone but
 * this app, so all three roots moved to files storage alongside the clips.
 */
object RunScratch {

    private const val TAG = "RunScratch"

    /** The cache roots that hold one `<videoId>` sub-directory per processed clip. */
    private val ROOTS = listOf("frames", "crops", "prepped")

    /**
     * The runs that are in flight right now, by video id.
     *
     * This replaced an age cut-off, and the difference is the difference between guessing and knowing.
     * [sweepUnclaimed] used to spare any directory touched within the last ten minutes, on the argument
     * that a live run touches its own files — which is false for most of a run's length: Stage 4 sends
     * the canvases and then waits, and a reader on high effort over three requests routinely takes
     * longer than ten minutes with nothing written in the meantime. A sweep landing in that window
     * deleted a LIVE run's frames, crops and canvases out from under it.
     *
     * A claim cannot get that wrong: a directory is either owned by a run this process is executing, or
     * it is owed to nobody. A process death clears the set, which is exactly the state where everything
     * left behind IS an orphan.
     */
    private val live = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * Marks [videoId]'s working set as owned by a run in flight, so no sweep may touch it.
     *
     * Call as soon as the run has somewhere to write — Stage 1 announces the id through
     * [com.terminalscout.app.prod.pipeline.PipelineEvent.ScratchOpened] for exactly this purpose — and
     * pair it with [release] or [delete] on every exit, including the failures.
     */
    fun claim(videoId: String) {
        live.add(videoId)
        Log.d(TAG, "claimed scratch for $videoId (${live.size} run(s) in flight)")
    }

    /** Gives up the claim without deleting anything. [delete] does this for you. */
    fun release(videoId: String) {
        live.remove(videoId)
    }

    /**
     * Deletes one run's frames/crops/prepped directories.
     *
     * Call only once the run's artifacts have been uploaded ([com.terminalscout.app.prod.download.DebugUploader.awaitIdle])
     * AND anything keeping copies of its images has finished — [com.terminalscout.app.prod.RunOutputStore]
     * copies board images out of `prepped/`, so deleting first would leave the run picker with gaps.
     *
     * @return bytes freed, for the log.
     */
    fun delete(context: Context, videoId: String): Long {
        // Released first: the directories are about to go, so holding the claim past this point could
        // only keep a later sweep away from files that no longer exist.
        release(videoId)
        var freed = 0L
        for (root in ROOTS) {
            // Per root, for the same reason [sweepUnclaimed] does it per directory: `frames/` is where
            // nearly all of a run's bytes are, and it is the first of [ROOTS] — so a fault measuring or
            // deleting it used to leave `crops/` and `prepped/` behind as well. The clip's own delete
            // in `ProcessingLane` happens either way, so nothing would come back to try again.
            try {
                val dir = File(File(context.filesDir, root), videoId)
                if (!dir.exists()) continue
                freed += sizeOf(dir)
                if (!dir.deleteRecursively()) Log.w(TAG, "could not delete ${dir.absolutePath}")
            } catch (t: Throwable) {
                Log.e(TAG, "could not clear $root/$videoId — carrying on with the other roots", t)
            }
        }
        Log.d(TAG, "cleared scratch for $videoId — freed ${freed / 1_048_576} MB")
        return freed
    }

    /**
     * Deletes leftovers from runs that never got to clean up after themselves: a run cancelled
     * mid-pipeline, or a process the system killed part-way through a queue. Without this the [delete]
     * call at the end of a run would cover only the runs that finished normally — which are not the ones
     * that filled the device.
     *
     * **A directory is spared if and only if a run has [claim]ed it.** There is no age cut-off any more:
     * see [live] for the live run this timer used to delete. Anything unclaimed is owed to nobody — a
     * run in this process would have claimed it, and a run in a previous process is gone — so it can be
     * removed with no guess about how recently something touched it.
     */
    fun sweepUnclaimed(context: Context) {
        val claimed = live.toSet()
        var freed = 0L
        var swept = 0
        var spared = 0
        for (root in ROOTS) {
            val dirs = File(context.filesDir, root).listFiles { f -> f.isDirectory } ?: continue
            for (dir in dirs) {
                if (dir.name in claimed) {
                    spared++
                    continue
                }
                // Per directory, so one that will not measure or will not delete does not end the
                // sweep.
                //
                // The caller wraps this whole function — see `ProcessingLane.start` — so a throw here
                // was never a crash. What it was is worse in its own way and invisible: the sweep
                // abandoned every directory after the one that failed, INCLUDING the other roots, and
                // this is the only thing that reclaims the frames of runs the system killed part-way
                // through. Frames are ~97% of a run's disk, so a sweep that gives up early is how a
                // device arrives at the capture floor and stops recording — reported as "not enough
                // room", with nothing saying that gigabytes of abandoned scratch are the reason.
                try {
                    freed += sizeOf(dir)
                    if (dir.deleteRecursively()) swept++
                    else Log.w(TAG, "could not sweep ${dir.absolutePath}")
                } catch (t: Throwable) {
                    Log.e(TAG, "could not sweep ${dir.absolutePath} — carrying on with the rest", t)
                }
            }
        }
        if (swept > 0 || spared > 0) {
            Log.d(
                TAG,
                "swept $swept orphaned run director(ies) — freed ${freed / 1_048_576} MB" +
                    if (spared > 0) "; spared $spared belonging to ${claimed.size} live run(s)" else ""
            )
        }
    }

    private fun sizeOf(dir: File): Long =
        dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
}
