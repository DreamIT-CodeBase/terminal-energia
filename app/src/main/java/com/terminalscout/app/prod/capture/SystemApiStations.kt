package com.terminalscout.app.prod.capture

import android.content.Context
import android.util.Log
import com.terminalscout.app.prod.download.DeviceApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * STAGE 0 — `fetchLocationMap`'s transport, through our own server.
 *
 * Replaces [AzureMapsStations] as the source of the nightly station sweep. Everything around it is
 * unchanged: [LocationMapRefreshScheduler] still aims 02:00 Europe/Tallinn, [LocationMapRefreshWorker]
 * still runs it, and [LocationMap.upsertAll] still writes it as an upsert that never touches
 * `last_capture_time`. Only where the rows come from has moved.
 *
 * ### Why the sweep moved off the device
 * The grid sweep it replaces was thirty Azure Maps calls per device per night, each carrying
 * `AZURE_MAPS_KEY` in its query string — and a `BuildConfig` string is a constant in the APK's dex, so
 * every unit in the fleet shipped a recoverable copy of that key. The tiling, the 100-result page
 * ceiling, the `ofs=100` dead end, the brand and classification filters and the POI de-duplication are
 * all still real problems; they are now somebody else's, solved once on the server instead of thirty
 * times a night on every phone. See [AzureMapsStations]' class note for what that sweep had to do.
 *
 * The device gains three things it could not have had: a curated list (the hand-surveyed forecourts
 * arrive merged in, under the `surveyed:` / `detected:` ids the retired APK seed used), a correction
 * that reaches the fleet without a release, and one fewer live credential in the APK.
 *
 * That merge is why the seed could be retired on 2026-09-18: a curated coordinate now reaches the
 * fleet by being served, not by being compiled in. Whatever the server does NOT serve is deleted on
 * the next successful sweep — see [LocationMap.upsertAll].
 *
 * ### The contract with the worker is EXACTLY the old one
 * `null` means "could not be trusted" — no API configured, transport failure, or a body that would not
 * parse. An empty list means the server answered and had nothing. The two are different on purpose and
 * the worker must keep treating them differently: [LocationMap.upsertAll] refuses an empty sweep, so a
 * device that has swept once keeps its map through any number of bad nights. Returning an empty list
 * where the old code returned null would not erase the map today, but it would move the only thing
 * standing between a bad response and an erased map into one refusal in one function.
 *
 * ### What is dropped, and what is deliberately not
 * A row with no id, or with no usable coordinate, is dropped: the id is the primary key that carries
 * `last_capture_time` across sweeps, and a station with no position cannot be compared against a GPS
 * fix. Both are counted in the log.
 *
 * A row with a **blank brand is kept**, which is the one filter that did not survive the move. On the
 * Azure path a brandless POI was a depot or a marina pump and dropping it removed ~45% of raw hits; on
 * this path the server has already made that call, and a blank brand means the operator publishing the
 * list chose to publish a row without one. Dropping it here would silently shrink a fleet's map by a
 * rule the fleet operator cannot see. The count is logged instead, so the size of that set stays
 * visible. The live list has none.
 */
object SystemApiStations {

    private const val TAG = "SystemApiStations"

    /**
     * True when the app can reach the API at all — a base URL and a fleet secret were compiled in.
     *
     * Mirrors [AzureMapsStations.isEnabled] so the worker's skip is the same shape: a missing
     * credential is not a retry, because no amount of backoff produces one that was never built in.
     */
    val isEnabled: Boolean get() = DeviceApi.isConfigured

    /**
     * `GET /v1/stations`, parsed into the station list.
     *
     * Returns null when the answer could not be trusted; see the class note on why that is not an empty
     * list. The bearer token, its refresh and the one retry on `401` all belong to [DeviceApi].
     */
    suspend fun fetchAll(context: Context): List<FuelStation>? = withContext(Dispatchers.IO) {
        if (!isEnabled) {
            Log.w(TAG, "no API base URL / fleet secret — cannot fetch the location map")
            return@withContext null
        }

        when (val res = DeviceApi.stations(context)) {
            is DeviceApi.ApiResult.Ok -> parse(res.value)
            DeviceApi.ApiResult.Transient -> {
                Log.w(TAG, "station fetch failed transiently — keeping the stored map")
                null
            }
            is DeviceApi.ApiResult.Permanent -> {
                // Permanent here is a wrong fleet secret or a route that no longer exists. Still null:
                // it is a reason to keep the stored map, not a reason to replace it with nothing.
                Log.e(TAG, "station fetch refused (HTTP ${res.code}): ${res.detail}")
                null
            }
        }
    }

    /**
     * `{"version","count","etag","stations":[…]}` → the stations the device can use.
     *
     * Internal rather than private so the parse can be tested without a network: it is the half of this
     * file that decides what the device believes, and the half most likely to meet a body it was not
     * written for.
     *
     * Returns null for a body that is not the document at all — unparseable, or carrying no `stations`
     * array. A document WITH the array and nothing usable in it returns empty, which is a different
     * fact and the worker treats it differently.
     */
    internal fun parse(body: String): List<FuelStation>? {
        val arr = try {
            JSONObject(body).optJSONArray("stations")
        } catch (e: Exception) {
            Log.e(TAG, "station response did not parse as JSON", e)
            return null
        }
        if (arr == null) {
            Log.e(TAG, "station response carried no 'stations' array — keeping the stored map")
            return null
        }

        val out = ArrayList<FuelStation>(arr.length())
        val seen = HashSet<String>(arr.length())
        var noId = 0
        var noCoordinate = 0
        var duplicate = 0
        var blankBrand = 0

        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id").trim()
            if (id.isEmpty()) {
                noId++
                continue
            }
            // The id is the primary key that carries last_capture_time across sweeps, so a repeat would
            // otherwise write one station's position over another's history.
            if (!seen.add(id)) {
                duplicate++
                continue
            }
            if (!o.has("latitude") || !o.has("longitude")) {
                noCoordinate++
                continue
            }
            val lat = o.optDouble("latitude")
            val lon = o.optDouble("longitude")
            if (lat.isNaN() || lon.isNaN()) {
                noCoordinate++
                continue
            }
            val brand = o.optString("brand").trim()
            if (brand.isEmpty()) blankBrand++

            out += FuelStation(
                id = id,
                // The id rather than an empty string: `name` reaches the log and the status line, and a
                // blank there is a row nobody can identify.
                name = o.optString("name").trim().ifEmpty { id },
                latitude = lat,
                longitude = lon,
                brand = brand
            )
        }

        // `fuel_types` and `note` are on the wire and deliberately not read: the board states its own
        // fuels (Stage 3 Step G) and `upload/StationDirectory` owns the naming fallback from its own
        // copy of this document. Two stores for one fact is how they start disagreeing.
        Log.i(
            TAG,
            "station fetch: ${arr.length()} row(s) served, ${out.size} usable" +
                (if (noId > 0) ", $noId with no id" else "") +
                (if (duplicate > 0) ", $duplicate duplicate id(s)" else "") +
                (if (noCoordinate > 0) ", $noCoordinate with no coordinate" else "") +
                (if (blankBrand > 0) ", $blankBrand with no brand (kept)" else "")
        )
        return out
    }
}
