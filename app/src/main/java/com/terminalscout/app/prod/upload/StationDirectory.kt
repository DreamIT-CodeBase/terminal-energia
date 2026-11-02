package com.terminalscout.app.prod.upload

import android.content.Context
import android.util.Log
import com.terminalscout.app.prod.download.DeviceApi
import com.terminalscout.app.prod.config.DeviceConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One fuel station from `GET /v1/stations`.
 *
 * @param fuelTypes the fuels this station sells, **in the order they appear on its price board, top to
 *   bottom**. That ordering is the whole reason this list is usable: Stage 4 reads digits only and Stage 5
 *   knows a row's POSITION but not its name, so position is the one thing the two sides share.
 * @param note free text from the server; carried through but not interpreted.
 */
data class Station(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val fuelTypes: List<String>,
    val note: String?
)

/**
 * The station mapping, stored on device.
 *
 * ### What it is for
 * A price board's rows carry no names — Stage 4 reads digits and nothing else, deliberately, because
 * asking a reader for names as well cost accuracy on the digits. So the names have to come from
 * somewhere else, and a station's own board layout is the natural source: match the capture's GPS fix to
 * a station, and its `fuel_types` gives the rows their names by position.
 *
 * ### On disk, refreshed per run
 *
 *   filesDir/stations/stations.json     the last response received, verbatim
 *
 * [refresh] is called at the start of every run and simply overwrites that file when the fetch succeeds.
 * When it fails — no signal, server down — the stored copy stands and the run uses it. So a device that
 * has fetched once keeps working offline forever, and one that never has just gets unnamed rows.
 *
 * A directory rather than a loose file so it fits the shape the rest of the app uses: `AppReset` cleans
 * whole named directories, and its unknown-location warning only looks at directories.
 */
object StationDirectory {

    private const val TAG = "StationDirectory"
    private const val DIR = "stations"
    private const val FILE = "stations.json"

    /**
     * How near the GPS fix must be for a station to be the one that was photographed.
     *
     * 500 m is "at, or driving past, this forecourt". A cap is essential rather than tidy: the mapping
     * covers a whole country, so without one the nearest station is ALWAYS found — a capture 40 km from
     * any forecourt would be confidently labelled with one, and its rows named from that station's board.
     */
    val MAX_MATCH_METERS: Double
        get() = DeviceConfig.double("upload.enrichment.max_match_meters", 500.0)

    private const val EARTH_RADIUS_M = 6_371_000.0

    private fun dir(context: Context): File =
        File(context.applicationContext.filesDir, DIR).apply { mkdirs() }

    private fun file(context: Context): File = File(dir(context), FILE)

    /**
     * Fetches the mapping and replaces the stored copy. Safe to call on every run.
     *
     * A failure is not an error worth propagating: the caller carries on with whatever is already on
     * disk. Written via a temporary file and renamed, so a fetch interrupted half-way cannot leave a
     * truncated mapping that parses to a handful of stations.
     *
     * @return true when the stored copy was replaced.
     */
    suspend fun refresh(context: Context): Boolean = withContext(Dispatchers.IO) {
        when (val res = DeviceApi.stations(context)) {
            is DeviceApi.ApiResult.Ok -> try {
                val dest = file(context)
                val tmp = File(dest.parentFile, "$FILE.part")
                tmp.writeText(res.value)
                if (!tmp.renameTo(dest)) {
                    tmp.delete()
                    Log.w(TAG, "could not publish the station mapping")
                    return@withContext false
                }
                Log.d(TAG, "station mapping refreshed (${parse(res.value).size} station(s))")
                true
            } catch (e: Exception) {
                Log.w(TAG, "could not store the station mapping", e)
                false
            }
            is DeviceApi.ApiResult.Permanent -> {
                Log.w(TAG, "station mapping refused (HTTP ${res.code}) — keeping the stored copy")
                false
            }
            DeviceApi.ApiResult.Transient -> {
                Log.d(TAG, "station mapping unavailable — keeping the stored copy")
                false
            }
        }
    }

    /** The stored mapping, or an empty list when nothing has been fetched yet. */
    fun load(context: Context): List<Station> {
        val f = file(context)
        if (!f.isFile) return emptyList()
        return try {
            parse(f.readText())
        } catch (e: Exception) {
            Log.w(TAG, "stored station mapping is unreadable", e)
            emptyList()
        }
    }

    /**
     * The station nearest [latitude]/[longitude], or null when the closest is further than
     * [MAX_MATCH_METERS] or there is nothing to search.
     *
     * Every station inside the radius is a candidate, not just the winner, because at these distances the
     * winner is not always trustworthy: two pairs in the mapping sit **1 m apart**, and four stations fall
     * within 500 m of one of the test captures. GPS error comfortably exceeds those gaps, so "nearest"
     * cannot decide between them. [Match.fuelTypesUnambiguous] reports whether it needs to — when every
     * candidate lists the same fuels, which station won makes no difference to the names.
     *
     * A station without coordinates is skipped rather than treated as (0, 0) — the equator is a long way
     * from Estonia, but "nearest" over a small enough list would still eventually return it.
     */
    fun nearest(
        stations: List<Station>,
        latitude: Double,
        longitude: Double
    ): Match? {
        val ranked = stations
            .map { it to distanceMetres(latitude, longitude, it.latitude, it.longitude) }
            .sortedBy { (_, d) -> d }
        val (closest, closestMetres) = ranked.firstOrNull() ?: return null

        if (closestMetres > MAX_MATCH_METERS) {
            Log.d(TAG, "nearest station ${closest.id} is ${closestMetres.toInt()}m away — outside " +
                "${MAX_MATCH_METERS.toInt()}m, treating the capture as off-station")
            return null
        }

        val candidates = ranked.filter { (_, d) -> d <= MAX_MATCH_METERS }.map { (s, _) -> s }
        val agreed = candidates.map { it.fuelTypes }.distinct().size == 1
        if (!agreed) {
            Log.w(
                TAG,
                "${candidates.size} stations within ${MAX_MATCH_METERS.toInt()}m disagree on fuels — " +
                    candidates.joinToString(" | ") { "${it.id}=${it.fuelTypes}" } +
                    "; recording ${closest.id} but leaving rows unnamed"
            )
        } else if (candidates.size > 1) {
            Log.d(TAG, "${candidates.size} stations within range, all listing ${closest.fuelTypes} — " +
                "the pick does not affect the names")
        }
        return Match(closest, closestMetres, candidates.size, agreed)
    }

    /**
     * A matched station and how far the fix was from it.
     *
     * @param candidatesInRange how many stations fell inside [MAX_MATCH_METERS], the winner included
     * @param fuelTypesUnambiguous true when every candidate lists the same fuels, so naming rows from
     *   [station] is safe even if the nearest-wins pick was arbitrary. False means the candidates
     *   genuinely differ and no name can be trusted — the station is still recorded, the rows are not
     *   named. Always true when there was only one candidate.
     */
    data class Match(
        val station: Station,
        val distanceMetres: Double,
        val candidatesInRange: Int = 1,
        val fuelTypesUnambiguous: Boolean = true
    )

    /**
     * Great-circle distance in metres (haversine).
     *
     * Haversine rather than [android.location.Location.distanceBetween] so this stays plain Kotlin and
     * unit-testable off-device. At 500 m the difference between a spherical and an ellipsoidal model is
     * centimetres — far below GPS error.
     */
    fun distanceMetres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        // min(1.0, …) guards asin against a value a hair over 1 from floating-point error.
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))
    }

    /**
     * Parses the `{"version","count","stations":[…]}` response.
     *
     * A station without an id or without coordinates is dropped: it can be neither matched nor
     * attributed, so keeping it would only let it win a "nearest" comparison it has no business in.
     */
    private fun parse(json: String): List<Station> {
        val arr = JSONObject(json).optJSONArray("stations") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").trim()
            if (id.isEmpty()) return@mapNotNull null
            if (!o.has("latitude") || !o.has("longitude")) return@mapNotNull null
            val fuels = o.optJSONArray("fuel_types") ?: JSONArray()
            Station(
                id = id,
                name = o.optString("name").trim().ifEmpty { id },
                latitude = o.optDouble("latitude"),
                longitude = o.optDouble("longitude"),
                // Order is preserved exactly as sent — it IS the row mapping.
                fuelTypes = (0 until fuels.length()).map { fuels.optString(it).trim() }
                    .filter { it.isNotEmpty() },
                note = o.optString("note").trim().ifEmpty { null }
            )
        }
    }
}
