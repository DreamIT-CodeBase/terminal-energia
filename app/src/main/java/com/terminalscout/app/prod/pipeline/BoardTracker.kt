package com.terminalscout.app.prod.pipeline

import android.graphics.Bitmap
import com.terminalscout.app.prod.config.DeviceConfig
import android.graphics.Color
import android.graphics.Rect
import android.util.Log
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * One box the model emitted, whether or not its crop survived the clarity gate.
 *
 * Board identity is tracked over ALL detections, gated or not. Gating first would fragment a dim
 * board's track on every frame its crop failed the clarity check, and hand it a fresh id each time it
 * came back.
 *
 * @param signature the crop's hue-saturation histogram — see [BoardTracker.signature]
 * @param aspect    width / height, which barely changes as the car drives past a board
 * @param written   whether the crop cleared the clarity gate and reached disk
 * @param cropFile  the file, when it was written. Renamed in place once the board id is known.
 * @param boardId   -1 until [BoardTracker.assign] has run
 */
class TrackedDetection(
    val frameIndex: Int,
    val timestampUs: Long,
    val box: Rect,
    val confidence: Float,
    val signature: FloatArray,
    val aspect: Float,
    var written: Boolean = false,
    var cropFile: File? = null,
    var boardId: Int = -1,
    /**
     * Caller payload the tracker never reads: the box's rank within its frame (0 = strongest) and the
     * rung that found it. Carried here so one record is the whole story of one box, and Stage 2 can
     * build its [BoardDetection]s from these once the ids are final.
     */
    val rank: Int = 0,
    val pass: DetectPass = DetectPass.HIGH
) {
    val timeS: Double get() = timestampUs / 1_000_000.0
}

/** One physical price post, with every detection that resolved to it. */
class TrackedBoard(
    val boardId: Int,
    val detections: List<TrackedDetection>,
    val signature: FloatArray
) {
    /** Crops of this board that cleared every guardrail — what the per-board floor counts. */
    val crops: Int get() = detections.count { it.written }

    val frames: Int get() = detections.map { it.frameIndex }.toSet().size

    val firstSeenUs: Long get() = detections.minOf { it.timestampUs }
    val lastSeenUs: Long get() = detections.maxOf { it.timestampUs }

    val meanConfidence: Float get() = detections.map { it.confidence }.average().toFloat()

    /**
     * Too few detections to be a board the car actually drove past. The model latches onto
     * reflections, and a reflection lives for a frame or two; a real board is in shot for a second or
     * more.
     */
    val weak: Boolean get() = detections.size < BoardTracker.WEAK_TRACK_DETS

    /**
     * Dominant hue in degrees — the human-readable form of the signature, for eyeballing whether two
     * boards really are different brands.
     */
    val hue: Float
        get() {
            if (signature.size != BoardTracker.SIG_BINS_H * BoardTracker.SIG_BINS_S) return -1f
            var best = 0
            var bestMass = -1f
            for (h in 0 until BoardTracker.SIG_BINS_H) {
                var mass = 0f
                for (s in 0 until BoardTracker.SIG_BINS_S) mass += signature[h * BoardTracker.SIG_BINS_S + s]
                if (mass > bestMass) { bestMass = mass; best = h }
            }
            return (best + 0.5f) * (360f / BoardTracker.SIG_BINS_H)
        }

    /** `b01 x14 crops @2.1-6.4s hue 47` — one board, for the log. */
    fun summary(): String = "b%02d x%d crops @%.1f-%.1fs hue %.0f%s".format(
        boardId, crops, firstSeenUs / 1e6, lastSeenUs / 1e6, hue, if (weak) " WEAK" else ""
    )
}

/**
 * Board identity — which physical price post each detection belongs to.
 *
 * ### Why it exists
 * One video can show more than one board — different brand, different prices — and every stage
 * downstream of Stage 2 treats the run as ONE board: Stage 3 fits every canvas to a single remembered
 * tag order, and Stage 5 pools every reading into one price list. Without an id, two brands' panels
 * are named against each other and their prices blend into one answer.
 *
 * ### Three signals, all free next to an inference
 *  1. **same-frame exclusion** — two boxes in one frame ARE different boards. A hard constraint, and
 *     the one thing that stops the merge pass ever collapsing two real boards into one.
 *  2. **spatial continuity** — IoU against a constant-velocity prediction, plus centroid distance
 *     normalised by the box diagonal.
 *  3. **colour signature** — an 8x8 hue-saturation histogram of the crop. Value is dropped so dusk and
 *     LED lighting do not dominate. This is the brand-native signal: Shell yellow/red and BP green
 *     separate on it trivially.
 *
 * Neither 2 nor 3 alone is enough. Spatial handles two same-brand boards side by side, where colour is
 * blind. Colour handles a board leaving frame and re-entering, where spatial has lost the thread and
 * would otherwise either fragment the board or hand its id to the other one.
 *
 * ### Run it from scratch, never incrementally
 * [assign] is called after the ladder and again after every re-sample rung. The tracker walks
 * detections in time order while the re-sample pass deliberately walks frames newest-first, so it
 * cannot be fed as it goes; and a rung that fills the gap between two fragments of one board should
 * MERGE them, which a from-scratch pass does for free. It is pure geometry over a few hundred records,
 * so re-running it per rung costs nothing next to one inference.
 */
object BoardTracker {

    private const val TAG = "BoardTracker"

    // ---- appearance ----------------------------------------------------------------------- //
    val SIG_BINS_H: Int get() = DeviceConfig.int("detection.tracker.sig_bins_h", 8)
    val SIG_BINS_S: Int get() = DeviceConfig.int("detection.tracker.sig_bins_s", 8)
    /** Crops are downsampled to this before histogramming. */
    private val SIG_CROP_PX: Int get() = DeviceConfig.int("detection.tracker.sig_crop_px", 32)

    // ---- association ---------------------------------------------------------------------- //
    /** Cost weights, summing to 1. */
    private val W_IOU: Float get() = DeviceConfig.float("detection.tracker.w_iou", 0.40f)
    private val W_CENTROID: Float get() = DeviceConfig.float("detection.tracker.w_centroid", 0.20f)
    private val W_SIG: Float get() = DeviceConfig.float("detection.tracker.w_sig", 0.30f)
    private val W_ASPECT: Float get() = DeviceConfig.float("detection.tracker.w_aspect", 0.10f)

    /** Cost above this starts a new track instead. */
    private val ASSOC_GATE: Float get() = DeviceConfig.float("detection.tracker.assoc_gate", 0.55f)

    /** Unmatched for longer than this and a track goes dormant. */
    private val ACTIVE_GAP_US: Long get() = DeviceConfig.long("detection.tracker.active_gap_us", 1_000_000L)

    /** ...and is forgotten entirely after this. */
    private val DORMANT_GAP_US: Long get() = DeviceConfig.long("detection.tracker.dormant_gap_us", 8_000_000L)

    /** Dormant revival is appearance-only, so a tight gate. */
    private val REVIVE_GATE: Float get() = DeviceConfig.float("detection.tracker.revive_gate", 0.30f)

    /** Two never-co-occurring tracks this close in colour are one board. */
    private val MERGE_SIG_DIST: Float get() = DeviceConfig.float("detection.tracker.merge_sig_dist", 0.18f)

    /** ...and must also be roughly the same shape. */
    private val MERGE_ASPECT_LOG: Float get() = DeviceConfig.float("detection.tracker.merge_aspect_log", 0.35f)

    /** Fewer detections than this and the track is probably a reflection. */
    val WEAK_TRACK_DETS: Int get() = DeviceConfig.int("detection.tracker.weak_track_dets", 3)

    /** IoU above which two boxes in ONE frame are the same board found twice. */
    private val DEDUPE_IOU: Float get() = DeviceConfig.float("detection.tracker.dedupe_iou", 0.45f)

    // ---- appearance ---------------------------------------------------------------------------- //

    /**
     * Brand-native appearance descriptor: an 8x8 hue-saturation histogram.
     *
     * Value is deliberately dropped. The same board is filmed at noon, at dusk and under its own LEDs
     * inside one corpus, and V moves far more across those than the brand's actual colours do.
     * Computed on a 32x32 downsample, so it costs microseconds — a rounding error next to the
     * inference it rides along with.
     *
     * L1-normalised, so the crop's pixel size cannot influence a comparison: the same board is a few
     * dozen pixels wide on approach and a few hundred by the time the car is alongside it.
     *
     * Hand-rolled rather than OpenCV's `calcHist` so Stage 2 keeps no dependency on the native library
     * — the binning is the same, with hue taken over 0..360 where OpenCV halves it into 0..180.
     */
    fun signature(crop: Bitmap): FloatArray {
        val hist = FloatArray(SIG_BINS_H * SIG_BINS_S)
        if (crop.width <= 0 || crop.height <= 0) return hist
        val small = try {
            Bitmap.createScaledBitmap(crop, SIG_CROP_PX, SIG_CROP_PX, true)
        } catch (e: Exception) {
            Log.w(TAG, "could not downsample a crop for its signature", e)
            return hist
        }
        try {
            val pixels = IntArray(SIG_CROP_PX * SIG_CROP_PX)
            small.getPixels(pixels, 0, SIG_CROP_PX, 0, 0, SIG_CROP_PX, SIG_CROP_PX)
            val hsv = FloatArray(3)
            for (p in pixels) {
                Color.RGBToHSV((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF, hsv)
                val h = ((hsv[0] / 360f) * SIG_BINS_H).toInt().coerceIn(0, SIG_BINS_H - 1)
                val s = (hsv[1] * SIG_BINS_S).toInt().coerceIn(0, SIG_BINS_S - 1)
                hist[h * SIG_BINS_S + s]++
            }
        } finally {
            if (small !== crop) small.recycle()
        }
        val total = hist.sum()
        if (total <= 0f) return hist
        for (i in hist.indices) hist[i] /= total
        return hist
    }

    /** `1 - histogram intersection`. 0 = identical, 1 = no shared colour at all. */
    fun signatureDistance(a: FloatArray, b: FloatArray): Float {
        if (a.isEmpty() || b.isEmpty() || a.size != b.size) return 1f
        var shared = 0f
        for (i in a.indices) shared += min(a[i], b[i])
        return max(0f, 1f - shared)
    }

    /**
     * Scale-free shape difference. A board's aspect ratio barely changes as the car drives past it; a
     * reflection's changes constantly.
     */
    private fun aspectCost(a: Float, b: Float): Float =
        if (a <= 0f || b <= 0f) 1f else min(1f, abs(ln(a / b)))

    private fun iou(a: Rect, b: Rect): Float {
        val w = max(0, min(a.right, b.right) - max(a.left, b.left))
        val h = max(0, min(a.bottom, b.bottom) - max(a.top, b.top))
        val inter = w.toFloat() * h
        val union = a.width().toFloat() * a.height() + b.width().toFloat() * b.height() - inter
        return if (union > 0f) inter / union else 0f
    }

    private fun centroidX(b: Rect) = (b.left + b.right) / 2f
    private fun centroidY(b: Rect) = (b.top + b.bottom) / 2f
    private fun diagonal(b: Rect) = max(1f, hypot(b.width().toFloat(), b.height().toFloat()))

    // ---- tracking ------------------------------------------------------------------------------ //

    /** One candidate board, accumulating detections in time order. */
    private class Track(det: TrackedDetection) {
        val detections = mutableListOf(det)
        val frames = mutableSetOf(det.frameIndex)
        var signature = det.signature.copyOf()
        var aspect = det.aspect
        var lastBox = det.box
        var lastUs = det.timestampUs
        var cx = centroidX(det.box)
        var cy = centroidY(det.box)
        var vx = 0f
        var vy = 0f

        fun predict(atUs: Long): Pair<Float, Float> {
            val dt = max(0f, (atUs - lastUs) / 1_000_000f)
            return (cx + vx * dt) to (cy + vy * dt)
        }

        /**
         * Where the last box would be if the board kept moving as it was.
         *
         * The camera is in a moving car, so a board sweeps across the frame; at 6 m/s a board can shift
         * most of its own width between two frames of the 15 fps grid. Comparing IoU against the raw
         * last box, rather than a predicted one, is how a fast pan gets read as two boards.
         */
        fun predictedBox(atUs: Long): Rect {
            val (px, py) = predict(atUs)
            val hw = lastBox.width() / 2f
            val hh = lastBox.height() / 2f
            return Rect((px - hw).toInt(), (py - hh).toInt(), (px + hw).toInt(), (py + hh).toInt())
        }

        fun add(det: TrackedDetection) {
            val dt = (det.timestampUs - lastUs) / 1_000_000f
            val nx = centroidX(det.box)
            val ny = centroidY(det.box)
            if (dt > 1e-6f) {
                vx = 0.5f * vx + 0.5f * (nx - cx) / dt
                vy = 0.5f * vy + 0.5f * (ny - cy) / dt
            }
            cx = nx; cy = ny
            lastBox = det.box
            lastUs = det.timestampUs
            // EMA rather than the latest frame's histogram: one frame of windscreen glare should not
            // redefine what the board's colours are.
            for (i in signature.indices) signature[i] = 0.8f * signature[i] + 0.2f * det.signature[i]
            aspect = 0.8f * aspect + 0.2f * det.aspect
            detections.add(det)
            frames.add(det.frameIndex)
        }
    }

    /** Cost of calling [det] the next sighting of [track], in 0..1. */
    private fun assocCost(track: Track, det: TrackedDetection): Float {
        val overlap = iou(track.predictedBox(det.timestampUs), det.box)
        val (px, py) = track.predict(det.timestampUs)
        val dist = min(
            1f,
            hypot(centroidX(det.box) - px, centroidY(det.box) - py) / diagonal(det.box)
        )
        return W_IOU * (1f - overlap) +
            W_CENTROID * dist +
            W_SIG * signatureDistance(track.signature, det.signature) +
            W_ASPECT * aspectCost(det.aspect, track.aspect)
    }

    /**
     * Appearance only. A dormant track's position tells you nothing: the board left the frame and came
     * back, so where it WAS is not where it IS. Colour and shape are all that survive the gap, which is
     * why the gate is tighter.
     */
    private fun reviveCost(track: Track, det: TrackedDetection): Float =
        0.75f * signatureDistance(track.signature, det.signature) +
            0.25f * aspectCost(det.aspect, track.aspect)

    /**
     * Collapses one board found several times in one frame to one representative.
     *
     * Five passes run over overlapping frame grids and the two shifted windows overlap across the
     * middle of the frame, so a board sitting there arrives as two or three boxes at the same frame
     * index. Nothing is discarded — each copy has its own file on disk and still needs the id — so the
     * duplicates are mapped onto their representative rather than dropped.
     *
     * A written crop outranks an unwritten one at equal confidence: the representative should be the
     * copy that has a file to rename.
     */
    private fun dedupeFrame(records: List<TrackedDetection>): List<Pair<TrackedDetection, List<TrackedDetection>>> {
        if (records.size < 2) return records.map { it to listOf(it) }
        fun score(d: TrackedDetection) = d.confidence + if (d.written) 1f else 0f

        val pool = records.sortedByDescending { score(it) }.toMutableList()
        val reps = mutableListOf<TrackedDetection>()
        while (pool.isNotEmpty()) {
            val best = pool.removeAt(0)
            reps.add(best)
            pool.removeAll { iou(best.box, it.box) > DEDUPE_IOU }
        }
        val groups = reps.associateWith { mutableListOf(it) }
        for (r in records) {
            if (r in reps) continue
            val best = reps.maxByOrNull { iou(it.box, r.box) } ?: continue
            groups[best]?.add(r)
        }
        return reps.map { it to (groups[it] ?: mutableListOf(it)) }
    }

    /**
     * Resolves [records] into boards, writing [TrackedDetection.boardId] onto every one of them.
     *
     * Greedy one-to-one association over time-sorted frames, then a merge pass. Greedy on sorted cost
     * rather than Hungarian: a forecourt shows two or three boards, and at that size the optimal
     * assignment and the greedy one agree.
     *
     * @return the boards, ordered by first sighting — so `b00` is the board the car reached first.
     */
    fun assign(records: List<TrackedDetection>): List<TrackedBoard> {
        if (records.isEmpty()) return emptyList()
        records.forEach { it.boardId = -1 }

        val byFrame = records.groupBy { it.frameIndex }
        val tracks = mutableListOf<Track>()
        val allGroups = mutableListOf<Pair<TrackedDetection, List<TrackedDetection>>>()

        for (frame in byFrame.keys.sorted()) {
            val pairs = dedupeFrame(byFrame.getValue(frame))
            if (pairs.isEmpty()) continue
            allGroups += pairs
            val atUs = pairs.first().first.timestampUs

            val candidates = mutableListOf<Triple<Float, Int, Int>>()   // cost, track, detection
            tracks.forEachIndexed { ti, track ->
                val gap = atUs - track.lastUs
                if (gap > DORMANT_GAP_US) return@forEachIndexed   // too long silent to be the same board
                val fresh = gap <= ACTIVE_GAP_US
                pairs.forEachIndexed { di, (rep, _) ->
                    val cost = if (fresh) assocCost(track, rep) else reviveCost(track, rep)
                    if (cost <= (if (fresh) ASSOC_GATE else REVIVE_GATE)) {
                        candidates.add(Triple(cost, ti, di))
                    }
                }
            }
            candidates.sortBy { it.first }

            val usedTracks = mutableSetOf<Int>()
            val usedDets = mutableSetOf<Int>()
            for ((_, ti, di) in candidates) {
                if (ti in usedTracks || di in usedDets) continue
                // SAME-FRAME EXCLUSION. One track takes at most one box per frame, because two boxes in
                // one frame are two different boards. This is free, it is a hard fact rather than a
                // threshold, and it is what makes the whole thing safe.
                usedTracks.add(ti)
                usedDets.add(di)
                tracks[ti].add(pairs[di].first)
            }
            pairs.forEachIndexed { di, (rep, _) ->
                if (di !in usedDets) tracks.add(Track(rep))
            }
        }

        val merged = mergeTracks(tracks)

        // Ordered by first sighting, so b00 is the board the car reached first.
        val roots = merged.sortedBy { t -> t.detections.minOf { it.timestampUs } }
        // Ids land on the representatives first, then propagate to the duplicates they stood in for —
        // each of those has its own file to rename.
        roots.forEachIndexed { bid, track -> track.detections.forEach { it.boardId = bid } }
        for ((rep, members) in allGroups) {
            for (m in members) if (m !== rep) m.boardId = rep.boardId
        }

        return roots.mapIndexed { bid, track ->
            TrackedBoard(
                boardId = bid,
                detections = records.filter { it.boardId == bid }
                    .sortedWith(compareBy({ it.frameIndex }, { -it.confidence })),
                signature = track.signature
            )
        }
    }

    /**
     * Folds tracks that are one board seen either side of an occlusion.
     *
     * Two tracks that never share a frame MAY be one board; two that ever share a frame never can be,
     * at any signature distance. That asymmetry is the point: it lets colour stitch a re-entry back
     * together without letting it collapse two brands parked side by side.
     */
    private fun mergeTracks(tracks: MutableList<Track>): List<Track> {
        val parent = IntArray(tracks.size) { it }
        fun find(i: Int): Int {
            var n = i
            while (parent[n] != n) {
                parent[n] = parent[parent[n]]
                n = parent[n]
            }
            return n
        }

        var merges = 0
        for (i in tracks.indices) {
            for (j in i + 1 until tracks.size) {
                val ri = find(i)
                val rj = find(j)
                if (ri == rj) continue
                val a = tracks[ri]
                val b = tracks[rj]
                if (a.frames.intersect(b.frames).isNotEmpty()) continue          // co-occur: never one
                if (signatureDistance(a.signature, b.signature) > MERGE_SIG_DIST) continue
                if (aspectCost(a.aspect, b.aspect) > MERGE_ASPECT_LOG) continue

                val wa = a.detections.size.toFloat()
                val wb = b.detections.size.toFloat()
                for (k in a.signature.indices) {
                    a.signature[k] = (a.signature[k] * wa + b.signature[k] * wb) / (wa + wb)
                }
                a.aspect = (a.aspect * wa + b.aspect * wb) / (wa + wb)
                a.detections.addAll(b.detections)
                a.frames.addAll(b.frames)
                parent[rj] = ri
                merges++
            }
        }
        if (merges > 0) Log.d(TAG, "$merges track(s) merged across an occlusion")
        return tracks.indices.filter { find(it) == it }.map { tracks[it] }
    }
}
