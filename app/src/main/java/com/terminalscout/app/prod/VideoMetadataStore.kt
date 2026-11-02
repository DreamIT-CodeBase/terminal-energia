package com.terminalscout.app.prod

import android.content.Context
import android.util.Log
import com.terminalscout.app.prod.upload.LocationFix
import com.terminalscout.app.prod.upload.Station
import com.terminalscout.app.prod.upload.StationDirectory
import org.json.JSONObject
import java.io.File

/**
 * What is known about a recorded clip, beside the clip.
 *
 * ### Why this replaced the queue
 * The processing queue used to be the record of a clip's existence: `video_queue/00000000…3.json` held an
 * id, a status and a source, and the drain worked from that list. It has been replaced by polling
 * `recorded_videos/` directly — the clip on disk *is* the work item — which leaves nothing to carry the
 * things the pipeline needs and a filename cannot say: which station the capture was for, where the
 * vehicle was, and which device recorded it.
 *
 * So the metadata moved to its own file, written the moment the clip is queued, named after the clip:
 *
 * ```
 * files/recorded_videos/8a6ea825-….mp4
 * files/metadata_videos/8a6ea825-….json
 * ```
 *
 * One JSON per clip, same basename. That pairing is the whole design — there is no index, no id counter
 * and no list to keep in step with the directory, so the directory cannot disagree with itself.
 *
 * ### Metadata is optional, deliberately
 * [read] returns null when there is no sidecar, and the pipeline runs anyway. A sideloaded test clip has
 * no metadata and never will; a clip whose sidecar write failed is still a clip worth processing. Losing
 * provenance costs attribution on one reading. Refusing to process would cost the reading entirely.
 *
 * ### Both files die together
 * A processed clip is deleted, and [delete] removes the sidecar with it. Neither is kept as a tombstone:
 * the record of what was read lives in the uploaded payload and the stored run, which outlive both.
 */
object VideoMetadataStore {

    private const val TAG = "VideoMetadata"

    /** Sidecar directory, alongside `recorded_videos`. Created on demand. */
    const val DIR = "metadata_videos"

    /**
     * What was known when the clip was recorded.
     *
     * ### Two positions, and they are not interchangeable
     * The **station** coordinate is where the board is, from the location map — it is what the capture
     * was aimed at, and it is null only for a manual or adb capture that named no board. The
     * **vehicle** coordinate is where the device actually was when the recorder rolled, from the last
     * GPS fix ([com.terminalscout.app.prod.capture.VehicleFix]).
     *
     * The difference between them is the *lead distance* — how far short of the board recording began
     * — which is the number the trigger geometry work needs and which neither coordinate can give on
     * its own. An earlier version of this class documented one field as the vehicle's and stored the
     * station's; recording both is what settles it.
     *
     * @param videoName the clip's file name, so a sidecar found on its own still says what it belonged to.
     * @param stationId null for a manual or adb capture — there is no board.
     * @param vehicleAccuracyM the fix's horizontal error, which bounds what the lead distance derived
     *   from it means. Null when the provider gave none.
     * @param vehicleFixAtMs when that fix was taken, for ageing it against [capturedAtMs]. A stationary
     *   vehicle stops producing fixes (`MIN_UPDATE_DISTANCE_M`), so an old fix is not a wrong one — but
     *   a reader has to be able to tell. Null with the rest when there was no fix at all, which is the
     *   normal state of a capture in the first seconds of a cold process.
     */
    data class Metadata(
        val videoName: String,
        val stationId: String?,
        val stationName: String?,
        val stationLatitude: Double?,
        val stationLongitude: Double?,
        val vehicleLatitude: Double?,
        val vehicleLongitude: Double?,
        val vehicleAccuracyM: Float?,
        val vehicleFixAtMs: Long?,
        val deviceId: String,
        val capturedAtMs: Long
    ) {

        /**
         * The vehicle's fix as the payload's location block wants it, or null when there was none.
         *
         * This is the fix the payload must carry. The alternative — asking the OS for a fix while the
         * clip is being processed — answers a different question: where the truck is NOW, which on a
         * backlog is the next forecourt, the depot, or a motorway thirty kilometres on. Speed and
         * heading are absent because the sidecar does not record them; a null there reads as "not
         * known", which is honest, where a zero would read as "stationary".
         */
        fun vehicleFix(): LocationFix? {
            val lat = vehicleLatitude ?: return null
            val lon = vehicleLongitude ?: return null
            return LocationFix(
                latitude = lat,
                longitude = lon,
                speedKmph = null,
                headingDeg = null,
                accuracyM = vehicleAccuracyM
            )
        }

        /**
         * The board this capture was aimed at, in the shape the payload's `station` block takes — or
         * null when the capture named no board (a manual or adb capture), which leaves the GPS match to
         * answer instead.
         *
         * Better than re-deriving it at processing time in two ways. The trigger did not guess: it fired
         * on THIS station id, so the id is a record rather than a nearest-neighbour inference. And the
         * distance is measured from [vehicleFix] to the board, which is the capture's real lead distance
         * — the number the trigger geometry work needs — instead of the distance from wherever the
         * device happened to be when the clip was read.
         *
         * `fuelTypes` is deliberately empty: the sidecar does not carry the board's fuel order, and
         * `EnrichmentStage` fills names only from a list it actually has. An empty list names nothing
         * rather than naming wrongly.
         */
        fun boardMatch(vehicle: LocationFix?): StationDirectory.Match? {
            val id = stationId ?: return null
            val lat = stationLatitude ?: return null
            val lon = stationLongitude ?: return null
            val leadMetres = vehicle?.let {
                StationDirectory.distanceMetres(it.latitude, it.longitude, lat, lon)
            } ?: 0.0
            return StationDirectory.Match(
                station = Station(
                    id = id,
                    name = stationName ?: id,
                    latitude = lat,
                    longitude = lon,
                    fuelTypes = emptyList(),
                    note = null
                ),
                distanceMetres = leadMetres,
                // One candidate, and it is not a candidate: the trigger recorded which board it fired
                // on. `unambiguous` is true in the only sense the field is read — nothing here can name
                // a row from a fuel list, because there is no fuel list.
                candidatesInRange = 1,
                fuelTypesUnambiguous = true
            )
        }
    }

    private fun dir(context: Context): File =
        File(context.applicationContext.filesDir, DIR).apply { mkdirs() }

    /** `<clip basename>.json` — the pairing this store depends on. */
    fun fileFor(context: Context, videoName: String): File =
        File(dir(context), videoName.substringBeforeLast('.') + ".json")

    /**
     * Writes the sidecar. Never throws.
     *
     * A failure is logged and nothing else: the clip is already on disk and worth processing without its
     * provenance, so a sidecar that could not be written must not take the capture down with it.
     */
    fun write(context: Context, metadata: Metadata) {
        try {
            val json = JSONObject().apply {
                put("video", metadata.videoName)
                put("station_id", metadata.stationId ?: JSONObject.NULL)
                put("station_name", metadata.stationName ?: JSONObject.NULL)
                put("station_latitude", metadata.stationLatitude ?: JSONObject.NULL)
                put("station_longitude", metadata.stationLongitude ?: JSONObject.NULL)
                put("vehicle_latitude", metadata.vehicleLatitude ?: JSONObject.NULL)
                put("vehicle_longitude", metadata.vehicleLongitude ?: JSONObject.NULL)
                put("vehicle_accuracy_m", metadata.vehicleAccuracyM ?: JSONObject.NULL)
                put("vehicle_fix_at_ms", metadata.vehicleFixAtMs ?: JSONObject.NULL)
                put("device_id", metadata.deviceId)
                put("captured_at_ms", metadata.capturedAtMs)
            }
            fileFor(context, metadata.videoName).writeText(json.toString())
            Log.d(TAG, "wrote metadata for ${metadata.videoName}")
        } catch (e: Exception) {
            Log.e(TAG, "could not write metadata for ${metadata.videoName}", e)
        }
    }

    /** The sidecar for a clip, or null when there is none or it will not parse. */
    fun read(context: Context, videoName: String): Metadata? {
        val f = fileFor(context, videoName)
        if (!f.isFile) return null
        return try {
            val o = JSONObject(f.readText())
            Metadata(
                videoName = o.optString("video", videoName),
                stationId = o.optString("station_id").takeIf { it.isNotBlank() && it != "null" },
                stationName = o.optString("station_name").takeIf { it.isNotBlank() && it != "null" },
                // `latitude` / `longitude` are the pre-2026-09-09 keys, which held the STATION's
                // position under an unqualified name. Read as a fallback so a sidecar written by the
                // previous build — one sitting in the queue across an update — still resolves its
                // station rather than losing it. Those clips have no vehicle position and never will.
                stationLatitude = optDouble(o, "station_latitude", "latitude"),
                stationLongitude = optDouble(o, "station_longitude", "longitude"),
                vehicleLatitude = optDouble(o, "vehicle_latitude"),
                vehicleLongitude = optDouble(o, "vehicle_longitude"),
                vehicleAccuracyM = optDouble(o, "vehicle_accuracy_m")?.toFloat(),
                vehicleFixAtMs = if (o.isNull("vehicle_fix_at_ms")) null
                    else o.optLong("vehicle_fix_at_ms").takeIf { it != 0L },
                deviceId = o.optString("device_id"),
                capturedAtMs = o.optLong("captured_at_ms")
            )
        } catch (e: Exception) {
            // A corrupt sidecar is treated as no sidecar. The clip still gets processed.
            Log.e(TAG, "could not read metadata for $videoName — processing without it", e)
            null
        }
    }

    /**
     * The first of [keys] the object actually carries, or null.
     *
     * `optDouble` returns `NaN` for a missing key and `0.0` for an explicit JSON null, and 0,0 is a
     * real coordinate in the Gulf of Guinea — so absence has to be tested with `has`/`isNull` rather
     * than inferred from the value. The varargs form is what lets the station keys fall back to the
     * old unqualified ones without writing that check out twice.
     */
    private fun optDouble(o: JSONObject, vararg keys: String): Double? {
        for (k in keys) {
            if (o.has(k) && !o.isNull(k)) return o.optDouble(k).takeIf { !it.isNaN() }
        }
        return null
    }

    /** Removes the sidecar. Called when its clip is deleted; the two always go together. */
    fun delete(context: Context, videoName: String) {
        runCatching { fileFor(context, videoName).delete() }
            .onFailure { Log.w(TAG, "could not delete metadata for $videoName", it) }
    }
}
