package com.terminalscout.app.prod.upload

import android.graphics.Rect
import com.terminalscout.app.prod.config.ModelConfig
import com.terminalscout.app.prod.pipeline.AggregatedReading
import com.terminalscout.app.prod.pipeline.FuelPrice
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Builds the JSON bodies POSTed to the two APIs.
 *
 * The readings payload is deliberately sparse: only values the run actually produced are emitted —
 * blank/absent fields are omitted rather than sent as null/placeholder. Detections are a per-frame
 * list, one entry for each frame that was forwarded to the reader, carrying that frame's pipeline
 * metrics (board box, confidence, resolution, clarity/sharpness/contrast) plus what was read off it.
 *
 * ### One body per CAPTURE, not per board
 * A video can show more than one price post — a forecourt with two brands, or a board and its
 * neighbour across the road — and each is prepped, read and consolidated separately. Their prices
 * therefore arrive as [boards], an array on one body, rather than as two bodies.
 *
 * Two bodies was the old shape and it could not be consumed safely: both carried the same device, the
 * same vehicle, the same location and a timestamp a second or two apart, with nothing saying which
 * physical board either described. That is indistinguishable from the same reading submitted twice,
 * which is exactly the thing a server is entitled to discard.
 *
 * The array is **always present and always used**, length 1 for the ordinary one-board clip. A shape
 * that only appears on rare inputs is a shape that is never exercised, and the consumer would have to
 * carry both.
 */
object ReadingPayload {

    /**
     * `2.0` — the capture/boards shape above. `1.x` was one body per board with the fuel rows at the
     * root and no board identity at all.
     */
    const val SCHEMA_VERSION = "2.0"
    /** Combined model identifier, centralized in [ModelConfig]. */
    val MODEL_VERSION: String get() = ModelConfig.PAYLOAD_MODEL_VERSION

    /**
     * One board's prices and identity, as it appears in the capture's `boards` array.
     *
     * @param ordinal    the board's place in the capture, 1-based. Counts RESULTS — not the tracker's
     *                   board id, which numbers every track it followed including weak ones that were
     *                   dropped before they were ever read.
     * @param count      how many boards the capture produced. `ordinal` runs 1..count.
     * @param boardId    the tracker's own id for this board. Carried because it is what the run's debug
     *                   artifacts are suffixed with (`_bNN`), so a row here can be traced to the images
     *                   it came from.
     * @param imagesRead how many board canvases the reader was given for this board.
     * @param seenFromS  when the board first and last appeared in the video, in seconds. Both null when
     *                   the capture produced no board at all.
     * @param readings   this board's consolidated rows.
     */
    data class BoardReading(
        val ordinal: Int,
        val count: Int,
        val boardId: Int,
        val imagesRead: Int,
        val seenFromS: Double?,
        val seenToS: Double?,
        val readings: List<AggregatedReading>,
        val detections: List<FrameDetection> = emptyList()
    )

    /**
     * Builds one capture's fuel-price payload for the readings API, with a **deterministic schema**:
     * every key is always present, and a missing value is emitted as JSON `null` (not omitted). Arrays
     * (`boards`, and each board's `fuel_prices` and `detections`) are always present, empty when there
     * is nothing to report.
     *
     * @param captureId the video this came from. The same id appears on every debug artifact of the run,
     *   and it is what ties this body to the images behind it.
     */
    fun buildCapture(
        config: VehicleConfig,
        location: LocationFix?,
        boards: List<BoardReading>,
        timestampMs: Long,
        captureId: String,
        station: StationDirectory.Match? = null
    ): JSONObject {
        val root = JSONObject()

        root.put("device_id", strOrNull(config.deviceId))
        root.put("vehicle_id", strOrNull(config.vehicleId))
        root.put("timestamp", isoUtc(timestampMs))
        root.put("schema_version", SCHEMA_VERSION)
        root.put("model_version", MODEL_VERSION)
        root.put("capture_id", strOrNull(captureId))
        root.put("location", locationBlock(location) ?: JSONObject.NULL)
        // Which forecourt this capture was matched to, and how far the fix was from it. Null when there
        // was no GPS fix, no stored mapping, or nothing inside StationDirectory.MAX_MATCH_METERS — and
        // in that case the rows carry no fuel_type either, unless the board itself printed one.
        //
        // Capture-level, and matched ONCE: every board in one video was filmed from the same place, so
        // resolving it per board would spend the same lookup twice to reach the same answer. Worth
        // knowing that a board across the road is therefore attributed to the near forecourt — the fix
        // cannot tell them apart, and inventing a second station for it would be a guess.
        root.put("station", stationBlock(station) ?: JSONObject.NULL)

        root.put("board_count", boards.size)
        val boardArray = JSONArray()
        boards.forEach { boardArray.put(boardJson(it)) }
        root.put("boards", boardArray)

        return root
    }

    /** One `boards[]` entry: which board this was, and what it said. */
    private fun boardJson(b: BoardReading): JSONObject = JSONObject().apply {
        put("ordinal", b.ordinal)
        put("count", b.count)
        put("board_id", b.boardId)
        put("images_read", b.imagesRead)
        // A two-element [from, to] window in seconds, or null when the capture produced no board. An
        // array rather than two keys because it is one fact — when this board was on screen.
        put(
            "seen_s",
            if (b.seenFromS == null || b.seenToS == null) JSONObject.NULL
            else JSONArray().apply { put(round2(b.seenFromS)); put(round2(b.seenToS)) }
        )

        val fuelArray = JSONArray()
        b.readings.forEach { r ->
            fuelArray.put(JSONObject().apply {
                put("row_num", if (r.rowNum >= 1) r.rowNum else JSONObject.NULL)
                put("fuel_type", strOrNull(r.fuelType))
                // The Stage 3 box letter the price was read from, kept beside the name rather than in
                // it: it identifies the panel on the prepped canvas, so a row can be checked against
                // the image it came from even when the name is absent.
                put("box", strOrNull(r.box))
                put("price", priceValue(r.price))
                put("raw_text", strOrNull(r.price))
            })
        }
        put("fuel_prices", fuelArray)

        val detectionArray = JSONArray()
        b.detections.forEach { detectionArray.put(detectionJson(it)) }
        put("detections", detectionArray)
    }

    /** The matched station, or null when the capture could not be tied to one. */
    private fun stationBlock(match: StationDirectory.Match?): JSONObject? = match?.let {
        JSONObject().apply {
            put("id", it.station.id)
            put("name", strOrNull(it.station.name))
            put("latitude", it.station.latitude)
            put("longitude", it.station.longitude)
            put("distance_m", Math.round(it.distanceMetres))
            // Both recorded so a consumer can see WHY a row may be unnamed despite a station.
            put("candidates_in_range", it.candidatesInRange)
            put("fuel_types_unambiguous", it.fuelTypesUnambiguous)
            put("fuel_types", JSONArray().apply { it.station.fuelTypes.forEach { f -> put(f) } })
            put("note", it.station.note?.let { n -> strOrNull(n) } ?: JSONObject.NULL)
        }
    }

    /** One `detections[]` entry for a frame that was sent to the reader. Absent metrics omitted. */
    private fun detectionJson(d: FrameDetection): JSONObject = JSONObject().apply {
        put("frame_index", d.frameIndex)
        if (d.timestampUs > 0L) put("timestamp_us", d.timestampUs)
        bboxArray(d.boardBbox)?.let { put("board_bbox", it) }
        put("board_detection_confidence", round2(d.confidence.toDouble()))
        if (d.imageWidth > 0 && d.imageHeight > 0) put("image_resolution", "${d.imageWidth}x${d.imageHeight}")
        put("clarity_score", round2(d.clarityScore))
        put("sharpness", round2(d.sharpness))
        put("contrast", round2(d.contrast))
        // `ocr_readings`, not `gemma_readings`. The key named the on-device model this pipeline has
        // never shipped, and it has never actually been emitted either — `detections` is always empty
        // — so there is no consumer for the old spelling to break.
        val read = readingsArray(d.readings)
        if (read.length() > 0) put("ocr_readings", read)
    }

    /** The raw fuel rows the reader read off a single frame. */
    private fun readingsArray(readings: List<FuelPrice>): JSONArray = JSONArray().apply {
        readings.forEach { fp ->
            val row = JSONObject()
            if (fp.rowNum >= 1) row.put("row_num", fp.rowNum)
            putIfPresent(row, "fuel_type", fp.fuelType)
            putIfPresent(row, "price", fp.price)
            if (row.length() > 0) put(row)
        }
    }

    /**
     * Metadata blob stored with (and later POSTed alongside) a discarded low-confidence frame image
     * for the separate images API.
     */
    fun buildDiscardedFrameMetadata(
        config: VehicleConfig,
        location: LocationFix?,
        frameIndex: Int,
        timestampUs: Long,
        confidence: Float,
        clarityScore: Double,
        capturedAtMs: Long
    ): JSONObject = JSONObject().apply {
        putIfPresent(this, "device_id", config.deviceId)
        putIfPresent(this, "vehicle_id", config.vehicleId)
        put("schema_version", SCHEMA_VERSION)
        put("model_version", MODEL_VERSION)
        put("timestamp", isoUtc(capturedAtMs))
        put("reason", "low_board_confidence")
        put("frame_index", frameIndex)
        if (timestampUs > 0L) put("frame_timestamp_us", timestampUs)
        put("board_detection_confidence", round2(confidence.toDouble()))
        put("clarity_score", round2(clarityScore))
        locationBlock(location)?.let { put("location", it) }
    }

    /**
     * Location block, or null when there's no fix (the caller emits JSON `null` for the whole block).
     * Within a present block the schema is fixed: `gps_accuracy_m` is emitted as `null` when unknown.
     */
    private fun locationBlock(location: LocationFix?): JSONObject? {
        if (location == null) return null
        return JSONObject().apply {
            put("latitude", location.latitude)
            put("longitude", location.longitude)
            put("gps_accuracy_m", location.accuracyM?.let { round2(it.toDouble()) } ?: JSONObject.NULL)
        }
    }

    /**
     * The price field: a numeric value when the price is a plain number; the "*#…" best-guess string
     * kept VERBATIM (never converted to a number) when the reconstructor flagged it as a guess; JSON
     * `null` when blank.
     */
    private fun priceValue(price: String): Any {
        val p = price.trim()
        if (p.isEmpty()) return JSONObject.NULL
        if (p.startsWith("*#")) return p          // best guess — keep as a string, do not convert
        return p.toDoubleOrNull() ?: JSONObject.NULL
    }

    /** The trimmed string, or JSON `null` when blank — keeps the payload schema deterministic. */
    private fun strOrNull(value: String): Any = value.trim().ifEmpty { JSONObject.NULL }

    private fun bboxArray(box: Rect?): JSONArray? {
        if (box == null) return null
        return JSONArray().apply {
            put(box.left); put(box.top); put(box.right); put(box.bottom)
        }
    }

    private fun putIfPresent(obj: JSONObject, key: String, value: String) {
        val v = value.trim()
        if (v.isNotEmpty()) obj.put(key, v)
    }

    private fun isoUtc(ms: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date(ms))
    }

    private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0
}
