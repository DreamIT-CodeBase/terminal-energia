package com.terminalscout.app.prod.pipeline

import android.content.Context
import com.terminalscout.app.prod.config.DeviceConfig
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Build
import android.media.MediaMetadataRetriever
import android.os.StatFs
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max

/** A single extracted frame written to disk as a PNG. No bitmap is retained in memory. */
data class FrameFile(
    /** Sequential frame index within the video (0-based). Unique within a [FrameExtractionResult]. */
    val index: Int,
    /** Source-video timestamp of this frame, in microseconds. */
    val timestampUs: Long,
    /** The PNG file on disk. */
    val file: File
)

/**
 * What the container says about the source video. Read once up front (the equivalent of the
 * reference pipeline's `probe()`), so later stages can reason about time — the re-sample pass needs
 * the duration to clamp its windows, and the rest is logged for diagnosis.
 */
data class VideoMeta(
    val durationUs: Long,
    val width: Int,
    val height: Int,
    /** Native frame rate, or 0.0 when the container doesn't report enough to derive it. */
    val nativeFps: Double
)

/**
 * The result of sampling one video into frames on disk.
 *
 * Directory layout (all under app-internal temp storage — device flash, not RAM):
 *
 *   framesRoot/
 *     <videoId>/            <- folder name = the video's UUID
 *       frame_00000.jpg     <- file name = the frame's id (its index)
 *       frame_00001.jpg
 *       ...
 *
 * [sourceVideo] and [meta] are carried so a later stage can come back for MORE frames without
 * re-probing: Stage 2's re-sample pass (pass 5) asks for extra frames inside narrow time windows,
 * and those land in this same folder, continuing the index sequence.
 */
data class FrameExtractionResult(
    /** UUID that names this video's frame folder. */
    val videoId: String,
    /** The folder holding every frame PNG for this video. */
    val framesDir: File,
    /** Every extracted frame, in ascending time order. */
    val frames: List<FrameFile>,
    /** The clip these frames were sampled from — the re-sample pass reopens it. */
    val sourceVideo: File,
    /** Container metadata read once by the probe. */
    val meta: VideoMeta
)

/**
 * Lets Stage 2 come back to Stage 1 for extra frames it didn't know it would need.
 *
 * The board detector uses this to probe isolated gaps when the base grid finds nothing, or to
 * re-sample around possible boards at a higher rate. Only Stage 1 knows how to pull frames out
 * of a video, so Stage 2 asks through this interface rather than opening the container itself.
 */
interface FrameRefiner {
    /**
     * Samples [samplesPerSecond] fps inside a window of ±[halfWidthUs] around each timestamp in
     * [centersUs] (overlapping windows merged, all clamped to the video's duration), skipping any
     * timestamp the base grid already covered so no frame is decoded or scored twice.
     *
     * New frames are written into the SAME folder as [extraction], continuing its index sequence.
     *
     * @param newestFirst walk the windows — and the ticks inside them — from the END of the clip
     *   backwards. The same set of timestamps is produced either way; only the order changes. Stage 2
     *   uses this when topping up its board count: later frames are nearer the board and so clearer,
     *   and are the ones Stage 4 keeps, so filling from the end reaches a usable set sooner.
     * @return only the NEWLY extracted frames, in the order they were taken — ascending time by
     *   default, descending when [newestFirst] is set (empty if there's nothing to add).
     */
    suspend fun extractWindows(
        extraction: FrameExtractionResult,
        centersUs: List<Long>,
        halfWidthUs: Long,
        samplesPerSecond: Double,
        newestFirst: Boolean = false
    ): List<FrameFile>
}

/**
 * Stage 1 — segmentation. Samples a video into PNG frames on disk.
 *
 * Frames are written as **JPEG** (quality [FRAME_JPEG_QUALITY]), not PNG — see [writeFrame] for the
 * reasoning and for what was measured. Crops and prepped canvases remain PNG.
 *
 * [extractFrames] takes a video path (a recorded clip or a sample video) and samples it at
 * [DEFAULT_SAMPLES_PER_SECOND] fps (one frame every ~0.067 s), writing each frame to disk as a PNG and
 * recycling its bitmap immediately so nothing accumulates in RAM.
 *
 * [extractWindows] is the second entry point: a targeted, denser re-sample of a few narrow time
 * windows, called back into by Stage 2's re-sample pass. See [FrameRefiner].
 */
class VideoFrameExtractor(private val context: Context) : FrameRefiner {

    /**
     * Convenience overload taking a video path string. See [extractFrames].
     */
    suspend fun extractFrames(
        videoPath: String,
        samplesPerSecond: Int = DEFAULT_SAMPLES_PER_SECOND
    ): FrameExtractionResult = extractFrames(File(videoPath), samplesPerSecond)

    /**
     * Samples [videoFile] — a recorded clip or a sample video — at [samplesPerSecond] fps (one frame
     * every `1 / samplesPerSecond` s) and writes every frame to disk as a PNG under a fresh
     * UUID-named folder. Each decoded bitmap is compressed and then recycled immediately, so no
     * frames accumulate in RAM.
     *
     * @return the [FrameExtractionResult] describing the folder and the ordered frame files.
     * @throws IllegalArgumentException if [samplesPerSecond] <= 0 or the video is missing/empty.
     */
    suspend fun extractFrames(
        videoFile: File,
        samplesPerSecond: Int = DEFAULT_SAMPLES_PER_SECOND
    ): FrameExtractionResult = withContext(Dispatchers.IO) {
        require(samplesPerSecond > 0) { "samplesPerSecond must be > 0 (was $samplesPerSecond)" }
        require(videoFile.exists() && videoFile.length() > 0L) {
            "Video file is missing or empty: ${videoFile.absolutePath}"
        }

        // ---- Room to work in, checked BEFORE a single frame is written ----
        //
        // This stage is what fills the device: a UHD frame is about a megabyte of JPEG and a
        // full-length clip at [DEFAULT_SAMPLES_PER_SECOND] is some 1,800 of them, so a run can want a
        // couple of gigabytes that nothing had accounted for. `CaptureLane`'s floor is a CAPTURE
        // decision made minutes earlier and against a different number — it asks whether recording is
        // wise, not whether this clip can be extracted right now, and by the time a backlogged clip
        // reaches here the queue behind it has taken the space the floor was measured against.
        //
        // Refused rather than attempted, and the refusal is a throw: Stage 1's caller ends the run and
        // leaves the clip on disk, so the capture is still owed and is read once there is room. Filling
        // the volume instead takes down every other run with it — and it used to, because the failure
        // arrived as a half-written frame partway through and the clip was deleted behind it.
        val freeMbBefore = freeMegabytes()
        if (freeMbBefore < MIN_FREE_TO_START_MB) {
            throw IOException(
                "not enough room to extract frames: ${freeMbBefore}MB free, need " +
                    "${MIN_FREE_TO_START_MB}MB — the clip is left for a later run"
            )
        }

        // folder name = video id (UUID); file names = frame id (its index).
        val videoId = UUID.randomUUID().toString()
        val framesDir = File(framesRootDir(), videoId)

        val retriever = MediaMetadataRetriever()
        val frames = mutableListOf<FrameFile>()
        var meta = VideoMeta(0L, 0, 0, 0.0)
        /** Frames skipped back to back. Reset by any frame that works — see the loop's catch. */
        var consecutiveFailures = 0
        /**
         * The first frame fault of the run, kept as the cause when the run gives up.
         *
         * The FIRST, not the most recent, for the reason `Watchdog.pending` keeps the first asker: the
         * later failures are consequences of whatever the first one was, and reporting the last one
         * sends whoever reads it to the wrong place.
         */
        var firstFrameFailure: Throwable? = null
        try {
            retriever.setDataSource(videoFile.absolutePath)
            meta = probe(retriever)
            rejectIfBlankVideo(retriever, meta)
            framesDir.mkdirs()

            val stepUs = 1_000_000L / samplesPerSecond
            val rotationFix = sidewaysFixFor(videoFile)
            if (rotationFix != 0f) {
                Log.w(TAG, "${videoFile.name} is a known sideways clip — rotating frames ${rotationFix}°")
            }

            var index = 0
            var timeUs = 0L
            while (timeUs < meta.durationUs) {
                // Re-checked as the extraction runs, not only before it. The estimate above is an
                // estimate — a longer clip, a denser grid, a fuller volume than the one measured a
                // second ago — and the whole point of the floor is to stop before the device is full
                // rather than to discover it from a write that failed. Every
                // [SPACE_CHECK_EVERY_FRAMES] frames, because StatFs is a syscall and this loop runs
                // over a thousand times.
                if (index > 0 && index % SPACE_CHECK_EVERY_FRAMES == 0) {
                    val freeMb = freeMegabytes()
                    if (freeMb < MIN_FREE_RESERVE_MB) {
                        throw IOException(
                            "ran out of room after $index frame(s): ${freeMb}MB free, reserve is " +
                                "${MIN_FREE_RESERVE_MB}MB — the clip is left for a later run"
                        )
                    }
                }
                // ONE frame's failure is one frame, not the clip.
                //
                // A decode can fail on its own: a damaged GOP part-way through a recording the encoder
                // did not finish cleanly, a timestamp the extractor seeks to and cannot resolve, a
                // transient allocation failure on a frame at the wrong moment. Left unguarded, any one
                // of those abandoned the whole extraction — and the clip is then retried from the start
                // on the next poll and fails at the same frame, forever, at full CPU. A board is on
                // screen for dozens of frames, so losing a handful costs nothing readable.
                //
                // What is NOT tolerated is a run failing frame after frame: that is a broken container
                // or a full volume, not one bad moment, and continuing would walk the entire duration
                // to produce nothing. [MAX_CONSECUTIVE_FRAME_FAILURES] in a row ends the run with the
                // first cause attached, so the errors screen names what actually went wrong rather
                // than "no frames were extracted".
                try {
                    val bitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                    if (bitmap != null) {
                        frames.add(writeFrame(framesDir, index, timeUs, bitmap, rotationFix))
                        index++
                    }
                    consecutiveFailures = 0
                } catch (t: Throwable) {
                    // `Throwable`: the likely fault here is an `OutOfMemoryError` decoding a UHD frame,
                    // which is an `Error`, and skipping one frame is a far better answer to it than
                    // losing the recording. See `ERROR_HANDLING.md` §1.
                    consecutiveFailures++
                    if (firstFrameFailure == null) firstFrameFailure = t
                    Log.w(
                        TAG,
                        "frame at ${timeUs / 1000}ms could not be extracted " +
                            "($consecutiveFailures in a row) — skipping it",
                        t
                    )
                    if (consecutiveFailures >= MAX_CONSECUTIVE_FRAME_FAILURES) {
                        throw IOException(
                            "extraction gave up after $consecutiveFailures consecutive frame " +
                                "failures with ${frames.size} frame(s) written — the clip is left " +
                                "for a later run",
                            firstFrameFailure
                        )
                    }
                }
                timeUs += stepUs
            }
        } finally {
            retriever.release()
        }

        if (firstFrameFailure != null) {
            Log.w(
                TAG,
                "${videoFile.name}: ${frames.size} frame(s) extracted, some timestamps were skipped",
                firstFrameFailure
            )
        }

        Log.d(
            TAG,
            "Extracted ${frames.size} frame(s) at $samplesPerSecond fps → ${framesDir.absolutePath} " +
                "[${meta.width}x${meta.height}, ${"%.2f".format(meta.durationUs / 1_000_000.0)}s, " +
                "native ${"%.3f".format(meta.nativeFps)} fps]"
        )
        FrameExtractionResult(videoId, framesDir, frames, videoFile, meta)
    }

    /**
     * Cheap preflight before extracting the full frame grid. A handful of scaled samples catches
     * solid-color camera/transfer failures without rejecting genuinely dark or bright footage that
     * still has texture.
     */
    private fun rejectIfBlankVideo(retriever: MediaMetadataRetriever, meta: VideoMeta) {
        val timestamps = BlankVideoGuard.sampleTimestamps(meta.durationUs)
        if (timestamps.isEmpty()) return

        val samples = mutableListOf<BlankVideoGuard.FrameStats>()
        for (timestampUs in timestamps) {
            val bitmap = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    retriever.getScaledFrameAtTime(
                        timestampUs,
                        MediaMetadataRetriever.OPTION_CLOSEST,
                        BlankVideoGuard.SAMPLE_WIDTH,
                        BlankVideoGuard.SAMPLE_HEIGHT
                    )
                } else {
                    retriever.getFrameAtTime(timestampUs, MediaMetadataRetriever.OPTION_CLOSEST)
                }
            } catch (e: Exception) {
                Log.w(TAG, "blank-video preflight sample at ${timestampUs / 1000}ms failed — skipping", e)
                null
            } ?: continue

            try {
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                samples += BlankVideoGuard.measure(pixels)
            } finally {
                bitmap.recycle()
            }
        }

        Log.d(
            TAG,
            "blank-video preflight: ${samples.size}/${timestamps.size} sample(s), " +
                samples.joinToString { "mean=${"%.1f".format(it.meanLuminance)} " +
                    "std=${"%.2f".format(it.standardDeviation)}" }
        )
        if (BlankVideoGuard.shouldReject(samples)) {
            throw IOException(
                "video appears to contain only solid black/white frames " +
                    "(${samples.size} samples, mean luminance and texture below thresholds)"
            )
        }
    }

    /**
     * Denser re-sample of the windows around [centersUs]. See [FrameRefiner.extractWindows].
     *
     * A timestamp is skipped when the base grid already has a frame within half a step of it, which
     * is what stops the two grids from double-decoding (and double-scoring) the same moment. With the
     * base grid at 15 fps and the re-scan starting at 20, the two grids coincide every 1/5 s — every
     * fourth 20 fps tick lands on a base tick — and those are exactly the ones already on disk.
     */
    override suspend fun extractWindows(
        extraction: FrameExtractionResult,
        centersUs: List<Long>,
        halfWidthUs: Long,
        samplesPerSecond: Double,
        newestFirst: Boolean
    ): List<FrameFile> = withContext(Dispatchers.IO) {
        require(samplesPerSecond > 0.0) { "samplesPerSecond must be > 0 (was $samplesPerSecond)" }
        if (centersUs.isEmpty()) return@withContext emptyList()

        val merged = mergeWindows(centersUs, halfWidthUs, extraction.meta.durationUs)
        if (merged.isEmpty()) return@withContext emptyList()
        // Latest window first when asked; the ticks inside each are walked backwards to match.
        val windows = if (newestFirst) merged.asReversed() else merged

        val stepUs = (1_000_000.0 / samplesPerSecond).toLong().coerceAtLeast(1L)
        // A tick this close to an already-extracted frame IS that frame — don't decode it again.
        val dedupeUs = stepUs / 2
        val existing = extraction.frames.map { it.timestampUs }.sorted()
        var nextIndex = (extraction.frames.maxOfOrNull { it.index } ?: -1) + 1

        // The top-up loop pulls MORE frames from the same clip, so a sideways clip needs the same fix
        // here — otherwise the extra frames would be the only upright ones, or the only sideways ones.
        val rotationFix = sidewaysFixFor(extraction.sourceVideo)

        val retriever = MediaMetadataRetriever()
        val added = mutableListOf<FrameFile>()
        var consecutiveFailures = 0
        try {
            retriever.setDataSource(extraction.sourceVideo.absolutePath)
            // Labelled so the tolerance below can abandon the whole pass, not just the window it is
            // in. A bare `break` left the remaining windows to be walked and fail in turn.
            windows@ for (window in windows) {
                // Ticks are snapped onto the dense grid — start rounded UP going forwards, end rounded
                // DOWN going backwards — so both directions visit exactly the same timestamps and stay
                // aligned across windows.
                var timeUs = if (newestFirst) {
                    (window.last / stepUs) * stepUs
                } else {
                    ((window.first + stepUs - 1) / stepUs) * stepUs
                }
                while (if (newestFirst) timeUs >= window.first else timeUs <= window.last) {
                    if (!coveredBy(existing, timeUs, dedupeUs)) {
                        // Per frame, as in [extractFrames]: the extra frames are independent of each
                        // other, so one that will not decode costs that timestamp and nothing else.
                        // Without this a single bad frame fell to the catch below and abandoned every
                        // remaining window — and this pass exists precisely because Stage 2 found
                        // something worth looking at more closely in those windows.
                        try {
                            val bitmap =
                                retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                            if (bitmap != null) {
                                added.add(
                                    writeFrame(extraction.framesDir, nextIndex, timeUs, bitmap, rotationFix)
                                )
                                nextIndex++
                            }
                            consecutiveFailures = 0
                        } catch (t: Throwable) {
                            consecutiveFailures++
                            Log.w(
                                TAG,
                                "re-sampled frame at ${timeUs / 1000}ms failed " +
                                    "($consecutiveFailures in a row) — skipping it",
                                t
                            )
                            // Nothing is thrown when the tolerance runs out, unlike the base grid: this
                            // pass is a top-up and the run is fine without it. It simply stops asking.
                            if (consecutiveFailures >= MAX_CONSECUTIVE_FRAME_FAILURES) {
                                Log.e(
                                    TAG,
                                    "abandoning the re-sample after $consecutiveFailures consecutive " +
                                        "failures — the base grid stands"
                                )
                                break@windows
                            }
                        }
                    }
                    timeUs += if (newestFirst) -stepUs else stepUs
                }
            }
        } catch (t: Throwable) {
            // The base grid is already on disk; a failed re-sample must not sink the run.
            //
            // `Throwable` rather than `Exception`, because the fault this is most likely to catch is an
            // `OutOfMemoryError` — this pass runs AFTER a full extraction, on the fullest heap of the
            // run — and a top-up that could not be afforded is not worth the clip. See
            // `ERROR_HANDLING.md` §1 and the same argument in `Crashproof.kt`.
            Log.e(TAG, "Windowed re-sample failed after ${added.size} extra frame(s)", t)
        } finally {
            retriever.release()
        }

        Log.d(
            TAG,
            "Re-sampled ${added.size} extra frame(s) at $samplesPerSecond fps over " +
                windows.joinToString { "%.2f-%.2fs".format(it.first / 1e6, it.last / 1e6) }
        )
        added
    }

    /**
     * Compresses [bitmap] to `frame_<index>.jpg` in [dir], frees it, and returns its record.
     * [extraRotation] is applied first — see [sidewaysFixFor].
     *
     * ### Why JPEG, and why this stage only
     * These frames were PNG at quality 100, which is **lossless — the quality argument was ignored by
     * the platform entirely**, so there was no knob to turn. At UHD that is roughly 10 MB per frame and,
     * at [DEFAULT_SAMPLES_PER_SECOND], about 3.7 GB for a 25-second clip: some 97% of a run's disk cost,
     * against ~125 MB for the video it came from. It is the reason `CaptureLane` refuses to record below
     * a 22 GB floor.
     *
     * JPEG at [FRAME_JPEG_QUALITY] is about a tenth of that. The loss is affordable because of what
     * happens next: Stage 2 letterboxes every frame down to 640x640 before inference, so detail below
     * that scale is discarded by the detector anyway, and q90 artefacts sit below the noise floor of a
     * board photographed from a moving vehicle.
     *
     * **Resolution is not touched.** Downscaling would be the other obvious saving and it is off the
     * table: a board 30 m away is ~197 px wide at UHD and ~33 px at SD, and Stage 3 upscales the crop
     * toward 600 px with a cubic kernel that sharpens edges and invents no detail. Fewer bytes per pixel
     * is affordable; fewer pixels is not.
     *
     * Crops and prepped canvases stay PNG. They are small, they are what Stage 4 actually reads, and
     * they are re-compressed from these frames rather than from the video — so a second lossy pass on
     * the one image the model sees would be paying twice for the same saving.
     */
    private fun writeFrame(
        dir: File,
        index: Int,
        timeUs: Long,
        bitmap: Bitmap,
        extraRotation: Float
    ): FrameFile {
        val outFile = File(dir, "frame_%05d.jpg".format(index))
        // EVERY exit recycles, and that is the whole reason this is a `finally` rather than two calls
        // at the end.
        //
        // A UHD frame is ~33 MB of native heap. The recycles used to sit after the write, so any throw
        // between here and there — `rotate`'s `createBitmap` on a full heap, a `compress` onto a volume
        // that filled up mid-frame, an `IOException` opening the file — left the frame allocated with
        // no reference to it. Nothing leaks one frame in isolation: the extraction loop calls this over
        // a thousand times per clip, and the first failure is normally a condition that repeats on the
        // next frame too. That is a process death by our own hand a few dozen frames later, in
        // `Bitmap.createBitmap`, blaming a frame that was fine.
        //
        // `rotate` is inside the try for the same reason — it is the allocation most likely to be the
        // thing that throws, and its own failure must still free the source.
        var upright: Bitmap? = null
        try {
            upright = if (extraRotation == 0f) bitmap else rotate(bitmap, extraRotation)
            FileOutputStream(outFile).use { out ->
                upright.compress(Bitmap.CompressFormat.JPEG, FRAME_JPEG_QUALITY, out)
            }
        } finally {
            // free the frame's RAM immediately, whether or not it reached the disk
            upright?.let { if (it !== bitmap) it.recycle() }
            bitmap.recycle()
        }
        return FrameFile(index, timeUs, outFile)
    }

    /** Rotates [src] by [degrees] clockwise (negative = anti-clockwise). */
    private fun rotate(src: Bitmap, degrees: Float): Bitmap =
        Bitmap.createBitmap(
            src, 0, 0, src.width, src.height,
            Matrix().apply { postRotate(degrees) },
            true
        )

    /** Reads duration/resolution/frame rate off an already-opened [retriever]. */
    private fun probe(retriever: MediaMetadataRetriever): VideoMeta {
        fun meta(key: Int): String? = retriever.extractMetadata(key)
        val durationUs = (meta(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1000L
        val width = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val height = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        // Derive the rate from the frame count when the container carries one; CAPTURE_FRAMERATE is
        // only set on slow-motion recordings, so it can't be relied on.
        val frameCount = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toLongOrNull() ?: 0L
        val nativeFps = if (frameCount > 0 && durationUs > 0) frameCount / (durationUs / 1_000_000.0) else 0.0
        return VideoMeta(durationUs, width, height, nativeFps)
    }

    /** True when [sortedTimestamps] already holds a frame within [toleranceUs] of [timeUs]. */
    private fun coveredBy(sortedTimestamps: List<Long>, timeUs: Long, toleranceUs: Long): Boolean =
        sortedTimestamps.any { abs(it - timeUs) <= toleranceUs }

    /**
     * Free space on the volume this stage writes to, in MB.
     *
     * Reads `filesDir` specifically: the frames, the crops and the canvases all land there, and it is
     * the volume that fills. Fails CLOSED — an unreadable figure reads as no room — which is the
     * opposite of `CaptureLane`'s choice and right for the opposite reason: refusing to extract costs a
     * delay and nothing else, because the clip stays on disk either way, where refusing to record costs
     * the forecourt outright.
     */
    private fun freeMegabytes(): Long = try {
        val stat = StatFs(context.filesDir.absolutePath)
        (stat.availableBlocksLong * stat.blockSizeLong) / 1_048_576L
    } catch (e: Exception) {
        Log.e(TAG, "could not read free storage — treating the volume as full", e)
        0L
    }

    /** Root folder that holds one UUID sub-folder of PNG frames per extracted video. */
    /** Files storage, not cache — see [RunScratch]; the OS must not reclaim a run mid-flight. */
    private fun framesRootDir(): File = File(context.filesDir, "frames").apply { mkdirs() }

    companion object {
        private const val TAG = "VideoFrameExtractor"

        /** 15 fps = one frame every ~0.067 s. */
        val DEFAULT_SAMPLES_PER_SECOND: Int get() = DeviceConfig.int("extraction.samples_per_second", 15)

        /**
         * Free space required before extraction starts, in MB.
         *
         * Sized to leave headroom for capture and extraction: the default `CaptureLane.MAX_CLIP_MS` is
         * 25 s, which at [DEFAULT_SAMPLES_PER_SECOND] is up to 375 frames. The API may configure longer
         * clips; a UHD frame at [FRAME_JPEG_QUALITY] is
         * roughly a megabyte. Three gigabytes also leaves room for longer sideloaded clips and the
         * crops and canvases Stages 2 and 3 add on top. It is below `CaptureLane`'s 6 GB capture floor,
         * so a device that was allowed to record is normally allowed to process.
         *
         * It is deliberately NOT the old worst case quoted in `CaptureLane`: that figure (10.8-21.6 GB)
         * was measured when frames were written as lossless PNG. They are JPEG now, at about a tenth of
         * the bytes, so a floor sized for the PNG era would refuse perfectly workable devices.
         */
        val MIN_FREE_TO_START_MB: Long get() = DeviceConfig.long("extraction.min_free_to_start_mb", 3_000L)

        /**
         * The point during extraction at which the run gives up, in MB.
         *
         * Lower than [MIN_FREE_TO_START_MB] on purpose: a run that has already begun has frames on disk
         * and abandoning it wastes that work, so it is worth continuing while there is real room left.
         * A gigabyte is where that stops being true — below it the writes themselves start failing, and
         * a failure part-way through a frame is a harder thing to reason about than a clean stop.
         */
        val MIN_FREE_RESERVE_MB: Long get() = DeviceConfig.long("extraction.min_free_reserve_mb", 1_000L)

        /** How often, in frames, the reserve is re-checked. ~4 s of footage at 15 fps. */
        val SPACE_CHECK_EVERY_FRAMES: Int get() = DeviceConfig.int("extraction.space_check_every_frames", 60)

        /**
         * Consecutive frame failures that end an extraction.
         *
         * A tolerance, not a budget: it counts failures IN A ROW, and any frame that works resets it,
         * so a clip with a damaged patch every few seconds still extracts in full. Thirty at 15 fps is
         * two seconds of footage that yielded nothing — past that the fault is the container or the
         * volume rather than the moment, and walking the remaining duration would burn the CPU of a
         * device that has other clips waiting to produce a folder with nothing in it.
         *
         * Sized well above a single bad GOP and well below a clip's frame count on purpose. Making it
         * 1 would restore the old behaviour of one bad frame costing the recording; removing the cap
         * would let a broken clip decode its whole duration on every poll, forever.
         */
        val MAX_CONSECUTIVE_FRAME_FAILURES: Int get() = DeviceConfig.int("extraction.max_consecutive_frame_failures", 30)

        /**
         * JPEG quality for an extracted frame.
         *
         * 90 rather than 100 because the top of the range spends most of its bytes on detail the
         * detector throws away — Stage 2 letterboxes to 640x640 before inference — while 90 keeps
         * artefacts below the noise floor of a moving-vehicle photograph. Lower than about 85 starts
         * putting visible blocking on the digits, which is the one thing in the frame that matters.
         *
         * This is the knob PNG never had: `compress(PNG, 100, …)` ignored its quality argument, because
         * PNG is lossless.
         */
        val FRAME_JPEG_QUALITY: Int get() = DeviceConfig.int("extraction.frame_jpeg_quality", 90)

        // ---- TEMPORARY, for debugging -------------------------------------------------------- //
        // These clips were recorded with the phone held SIDEWAYS, before CameraCaptureManager tracked
        // device orientation. CameraX wrote a portrait rotation flag over already-upright landscape
        // pixels, so MediaMetadataRetriever dutifully applies it and tips them over: the extracted
        // frames come out with the sky on the RIGHT and the road on the LEFT. The board detector is
        // trained on upright boards and finds nothing at all in that state — on one of these clips the
        // full-frame passes returned zero boards and only the 0.01 shifted rungs fired, on noise.
        //
        // The metadata is wrong ON DISK, so nothing can infer this automatically; the clips are simply
        // named here. New recordings do not need this — the orientation listener sets the flag
        // correctly — so this list should not grow, and should be deleted once these clips are retired.
        private val SIDEWAYS_CLIPS = setOf(
            "20260729-214944-f50f",
            "20260729-215057-f3a7",
            "20260729-215241-1c93",
        )

        /** Anti-clockwise 90°, verified against a real frame: puts the sky back at the top. */
        private val SIDEWAYS_FIX_DEGREES: Float get() = DeviceConfig.float("extraction.sideways_fix_degrees", -90f)

        /** Extra rotation [videoFile]'s frames need to come out upright; 0 for everything else. */
        private fun sidewaysFixFor(videoFile: File): Float =
            if (videoFile.nameWithoutExtension in SIDEWAYS_CLIPS) SIDEWAYS_FIX_DEGREES else 0f

        /**
         * `[t - halfWidth, t + halfWidth]` around each timestamp in [centersUs], overlaps merged and
         * everything clamped into `[0, durationUs]`. A [durationUs] of 0 means "unknown" and leaves
         * the upper bound alone.
         */
        fun mergeWindows(centersUs: List<Long>, halfWidthUs: Long, durationUs: Long): List<LongRange> {
            if (centersUs.isEmpty()) return emptyList()
            val spans = centersUs
                .map { max(0L, it - halfWidthUs) to (it + halfWidthUs) }
                .sortedBy { it.first }

            val merged = mutableListOf<LongRange>()
            for ((lo, hi) in spans) {
                val last = merged.lastOrNull()
                if (last != null && lo <= last.last) {
                    merged[merged.lastIndex] = last.first..max(last.last, hi)
                } else {
                    merged.add(lo..hi)
                }
            }
            if (durationUs <= 0L) return merged
            return merged.mapNotNull { w ->
                val hi = minOf(w.last, durationUs)
                if (hi > w.first) w.first..hi else null
            }
        }
    }
}
