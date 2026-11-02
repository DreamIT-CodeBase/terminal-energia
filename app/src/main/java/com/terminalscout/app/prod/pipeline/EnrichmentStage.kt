package com.terminalscout.app.prod.pipeline

import android.util.Log
import com.terminalscout.app.prod.upload.LocationFix
import com.terminalscout.app.prod.upload.ReadingPayload
import com.terminalscout.app.prod.upload.Station
import com.terminalscout.app.prod.upload.StationDirectory
import com.terminalscout.app.prod.upload.VehicleConfig
import com.terminalscout.app.prod.config.DeviceConfig
import org.json.JSONObject

/**
 * Stage 6 — enrichment. Turns Stage 5's cleaned readings JSON into the API-ready payload.
 *
 * Three entry points, in the order a run uses them:
 *
 *   1. [matchStation] — once per capture, before any board is enriched: which forecourt was this?
 *   2. [enrichBoard]  — once per board: parse that board's `{"readings":[...]}` and name its rows.
 *   3. [buildCapture] — once per capture: every board's rows in ONE readings-API body.
 *
 * ### Why it is split that way
 * A video can show more than one price post, and each is prepped, read and consolidated separately —
 * but they were filmed from one place at one time by one device, so everything except the prices is a
 * property of the CAPTURE rather than of the board. Resolving the station per board would run the same
 * lookup twice to reach the same answer; emitting a body per board produced two readings that a server
 * cannot tell from one reading submitted twice. So the capture-level facts are established once, each
 * board contributes a [ReadingPayload.BoardReading], and one body goes out. See [ReadingPayload].
 *
 * **The rows get their fuel names here, and nowhere else.** Stage 4 reads digits only — asking a reader
 * for names as well cost accuracy on the digits, which is the thing that matters — so a consolidated row
 * arrives knowing its POSITION on the board and nothing about what it sells. The names come from the
 * station: the GPS fix is matched to the nearest forecourt in [StationDirectory], and that station's
 * `fuel_types`, which is ordered top to bottom as its board is, names the rows by position. See
 * [nameRows].
 *
 * **That is now the FALLBACK, not the source.** Stage 3's layout read (Step G) reports the token printed
 * beside each panel, and Stage 5 puts it on the row it belongs to — so a row usually arrives here already
 * named by the board itself, and the station's list only fills what the board could not tell us.
 *
 * KNOWN GAPS (to be filled later from the local SQLite tables): per-frame detections — board
 * bounding boxes, detection confidence, clarity/sharpness/contrast, and the per-frame reader rows —
 * are not preserved all the way to this stage yet, so the payload's `detections` array is currently
 * empty. Once those are persisted per-frame in SQLite, they get joined back in here.
 */
class EnrichmentStage {

    /**
     * Step 1, once per capture: which forecourt was this?
     *
     * Needs both a fix and a stored mapping; either missing means null, and the rows are then named by
     * the board alone. Returns null unconditionally while [STATION_LOOKUP_ENABLED] is off.
     *
     * @param location GPS fix at capture time, or null
     * @param stations the stored station mapping; empty when none has ever been fetched
     */
    fun matchStation(location: LocationFix?, stations: List<Station>): StationDirectory.Match? {
        if (!STATION_LOOKUP_ENABLED) return null
        if (location == null) {
            Log.d(TAG, "no GPS fix — rows will be named by the board alone")
            return null
        }
        if (stations.isEmpty()) {
            Log.d(TAG, "no station mapping stored — rows will be named by the board alone")
            return null
        }
        val match = StationDirectory.nearest(stations, location.latitude, location.longitude)
        // The station is recorded either way; the NAMES need the pick to have been unambiguous. Several
        // forecourts can sit inside the radius with different fuel lists, and nearest-wins cannot choose
        // between them at distances below GPS error — two stations in the mapping are 1 m apart.
        if (match != null && !match.fuelTypesUnambiguous) {
            Log.w(TAG, "${match.candidatesInRange} candidate stations disagree on fuels — recording " +
                "${match.station.id} but filling no names from it")
        }
        return match
    }

    /**
     * Step 2, once per board: that board's consolidated rows, named and ready for the capture body.
     *
     * @param reconstructedJson Stage 5's cleaned `{"readings":[{row_num,box,fuel_type,price}]}` JSON
     * @param ordinal           this board's place in the capture, 1-based
     * @param count             how many boards the capture produced
     * @param boardId           the tracker's id, which the run's `_bNN` artifacts are named after
     * @param imagesRead        how many canvases the reader was given for this board
     * @param seenFromS         when the board first appeared, in seconds; null if there was no board
     * @param seenToS           when it was last seen, in seconds; null if there was no board
     * @param station           the capture's station, from [matchStation]
     */
    fun enrichBoard(
        reconstructedJson: String,
        ordinal: Int,
        count: Int,
        boardId: Int,
        imagesRead: Int,
        seenFromS: Double?,
        seenToS: Double?,
        station: StationDirectory.Match?
    ): ReadingPayload.BoardReading {
        val parsed = parseReadings(reconstructedJson)
        // Names are filled from the station's list only when the candidates agreed on one; otherwise the
        // rows keep whatever the board itself printed, and the box letter behind that.
        val namingStation = station?.takeIf { it.fuelTypesUnambiguous }?.station
        val readings = nameRows(parsed, namingStation)
        Log.d(
            TAG,
            "board $ordinal/$count (b%02d): ${readings.size} row(s) from $imagesRead image(s)".format(boardId) +
                (station?.let { " at ${it.station.id} (${it.distanceMetres.toInt()}m)" } ?: " with no station")
        )
        return ReadingPayload.BoardReading(
            ordinal = ordinal,
            count = count,
            boardId = boardId,
            imagesRead = imagesRead,
            seenFromS = seenFromS,
            seenToS = seenToS,
            readings = readings,
            // GAP: per-frame detections will come from the SQLite tables later.
            detections = emptyList()
        )
    }

    /**
     * Step 3, once per capture: every board of one video in ONE readings-API body.
     *
     * @param boards      one entry per board, in capture order, from [enrichBoard]
     * @param config      device/vehicle identity
     * @param location    GPS fix at capture time, or null
     * @param timestampMs capture time (epoch millis)
     * @param captureId   the video's id — what ties this body to the run's images
     * @param station     the capture's station, from [matchStation]
     * @return the enriched readings-API payload as pretty JSON
     */
    fun buildCapture(
        boards: List<ReadingPayload.BoardReading>,
        config: VehicleConfig,
        location: LocationFix?,
        timestampMs: Long,
        captureId: String,
        station: StationDirectory.Match?
    ): String {
        val payload = ReadingPayload.buildCapture(
            config = config,
            location = location,
            boards = boards,
            timestampMs = timestampMs,
            captureId = captureId,
            station = station
        )
        Log.d(
            TAG,
            "Capture payload built: ${boards.size} board(s), " +
                "${boards.sumOf { it.readings.size }} row(s) total" +
                (station?.let { " at ${it.station.id}" } ?: " with no station") +
                (if (STATION_LOOKUP_ENABLED) "" else " (station lookup OFF)")
        )
        return payload.toString(2)
    }

    /**
     * The precedence, highest first: the name Stage 3 read off the board, then the station's list by
     * position, then **nothing** — a row neither source can name keeps a blank `fuel_type`, which the
     * payload sends as `null`.
     *
     * There is deliberately no third rung. The box letter used to fill that slot and it was wrong in
     * the way that is hardest to see downstream: `"A"` is not a fuel, but it arrives in the fuel field
     * as a perfectly ordinary non-null string, so nothing consuming the payload could distinguish a
     * panel whose fuel is known from one whose fuel is merely identified. The letter is already in the
     * payload's own `box` field, beside the name, for exactly the purpose it was being borrowed for.
     *
     * Names each row from the station's board order: row 1 takes `fuel_types[0]`, row 2 `fuel_types[1]`,
     * and so on.
     *
     * The link between the two sides is the ROW NUMBER, because position is the only thing they have in
     * common — the station knows what its board shows top to bottom, and Stage 5 knows which row it read.
     * Nothing else links them. So the lookup is `fuel_types[rowNum - 1]`, and NOT the row's index in this
     * list: Stage 5 emits the board's own row numbers, which skip a panel whose price never settled. A
     * board emitting rows 1 and 3 has three panels and the second one is simply unread — indexing by
     * position would hand row 3 the station's SECOND fuel and mis-name it, and every row below a gap with
     * it. Two rows can never take the same fuel this way either, since their row numbers differ.
     *
     * **A row past the end of `fuel_types` keeps a blank name.** The counts disagree often: a station may
     * list five fuels where only three rows were read, or two where three were. Naming as far as the two
     * lists line up and stopping there means a name is either right or absent, never invented — and a
     * blank becomes JSON `null` in the payload, which reads as "not known" rather than as a fuel.
     *
     * A row with no usable position is left unnamed for the same reason: it cannot be lined up at all.
     */
    private fun nameRows(readings: List<AggregatedReading>, station: Station?): List<AggregatedReading> {
        val matched = station
        val fuels = matched?.fuelTypes.orEmpty()

        // A name Stage 3 READ OFF THE BOARD wins. It is what that panel actually says, whereas the
        // station's list is a stored ordering matched to this board by position — right about the board
        // in general, and wrong the moment a panel is added, removed or reordered on the forecourt.
        // The station list therefore fills the gaps rather than overwriting the readings.
        val fromBoard = readings.count { it.fuelType.isNotBlank() }
        if (matched == null || fuels.isEmpty()) {
            if (matched != null) {
                Log.d(TAG, "${matched.id} lists no fuel types — keeping only the $fromBoard name(s) read off the board")
            }
            // Left BLANK, which becomes JSON `null` in the payload. The letter is not a fuel name and
            // must not be sent as one — see the note above and
            // [ConsensusConsolidator.buildReadingsJson], which states the same rule from the other
            // side: "putting 'A' in the payload's fuel field would be a fact nothing checked".
            //
            // Nothing is lost by not doing it. The letter travels in its OWN field — `box` in the
            // payload, which `ReadingPayload` emits beside `fuel_type` precisely so a row can be
            // checked against the prepped canvas when the name is absent. Copying it into the name
            // field as well added no information and destroyed the one distinction that matters: a
            // consumer could no longer tell a panel whose fuel is known from one whose fuel is not,
            // because both arrived as a non-null string.
            return readings
        }

        // Sorted by board position; rows without one go last and stay unnamed.
        val ordered = readings.sortedBy { if (it.rowNum >= 1) it.rowNum else Int.MAX_VALUE }
        var named = 0
        var kept = 0
        val out = ordered.map { r ->
            if (r.fuelType.isNotBlank()) {
                kept++
                return@map r
            }
            // The board's row, minus one — the station's list is in board order, so row N is its Nth
            // entry however many rows this read actually produced.
            val slot = r.rowNum - 1
            val fuel = if (r.rowNum >= 1 && slot < fuels.size) fuels[slot] else ""
            if (fuel.isNotEmpty()) named++
            // Past the end of the station's list the row keeps its BLANK name, which is what the
            // paragraph four above this promises and what the code here used to contradict: it
            // substituted the box letter, so the payload's fuel field shipped "C". A name is either
            // right or absent, never invented.
            if (fuel.isEmpty()) r else r.copy(fuelType = fuel)
        }
        Log.d(
            TAG,
            "$kept row(s) named by the board itself, $named filled from ${station.id} " +
                "[${fuels.joinToString()}]: " +
                out.joinToString { "${it.rowNum}=${it.fuelType.ifEmpty { "-" }}" }
        )
        return out
    }

    /** Parses Stage 5's cleaned readings JSON into [AggregatedReading]s for the payload builder. */
    private fun parseReadings(json: String): List<AggregatedReading> {
        return try {
            val arr = JSONObject(json).optJSONArray("readings") ?: return emptyList()
            // Keep every reconstructed row (deterministic 1:1 mapping) — a blank price becomes a
            // JSON null in the payload rather than dropping the row.
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                AggregatedReading(
                    // The name Stage 5 put on this row, which is the token Stage 3 READ OFF THE BOARD
                    // beside that panel. Blank when the layout read did not run, was refused, or could
                    // not make out that panel's name — [nameRows] then fills it from the station, and
                    // failing that leaves it blank, which the payload sends as null.
                    fuelType = o.optString("fuel_type").trim(),
                    // The box letter, kept as its own field. Used exactly as drawn, NOT re-sequenced into
                    // A, B, C: the letter identifies the box on the prepped canvas, so a row can be
                    // checked against the image it came from. In 11 of 33 real runs the letters are not in
                    // row order — one board read E, B, C top to bottom — and renumbering them would break
                    // that correspondence.
                    box = o.optString("box").trim(),
                    price = o.optString("price").trim(),
                    votes = 0,
                    totalReads = 0,
                    rowNum = o.optInt("row_num", -1)
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not parse reconstructed JSON for enrichment: ${json.take(120)}", e)
            emptyList()
        }
    }

    companion object {

        /**
         * **TEMPORARY — off for now, by request.**
         *
         * `false` skips the whole station step: no `/v1/stations` refresh at the start of a run, no
         * nearest-forecourt lookup, and `station` is null in the payload.
         *
         * **Each row's `fuel_type` is then whatever Stage 3's layout read off the board, or `null`.**
         * With the lookup off there is no station list to fall back to, so this flag decides how most
         * rows are named — which is why it matters that the fallback is a null and not the box letter.
         * It used to be the letter, so in this configuration — the live one — every unnamed row shipped
         * `"A"`, `"B"`, `"C"` in the fuel field.
         *
         * Turning it back on restores GPS-matched naming — the nearest station within
         * [StationDirectory.MAX_MATCH_METERS], its `fuel_types` applied by row position, gated on the
         * candidates agreeing. Nothing was deleted to switch it off; both paths stay compiled.
         *
         * Worth knowing before re-enabling: against the current 41-station mapping only 3 of 59 recorded
         * test captures fall inside 500 m, so most runs produced no names at all. 17 of them sit at
         * 664-732 m from `tartu-kvissentali-circle-k`, just outside the radius.
         */
        val STATION_LOOKUP_ENABLED: Boolean get() = DeviceConfig.boolean("upload.enrichment.station_lookup_enabled", false)
        private const val TAG = "EnrichmentStage"
    }
}
