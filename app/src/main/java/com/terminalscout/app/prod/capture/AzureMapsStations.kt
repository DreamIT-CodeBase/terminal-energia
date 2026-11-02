package com.terminalscout.app.prod.capture

import android.util.Log
import com.terminalscout.app.prod.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * STAGE 0 — `fetchLocationMap`'s transport. Pulls every Estonian fuel station out of Azure Maps.
 *
 * ### The endpoint
 *
 *     GET https://atlas.microsoft.com/search/poi/category/json
 *         ?api-version=1.0&query=petrol station&countrySet=EE
 *         &lat=..&lon=..&radius=..&limit=100
 *
 * Verified against the live service with this app's own key: results carry `position.lat/lon`,
 * `poi.name`, `poi.brands`, a full `address`, and `poi.classifications[].code == "PETROL_STATION"`
 * (category id 7311).
 *
 * ### Why a tiled grid and not one country-wide call
 * Two measured limits force it, and neither is documented in a way you would notice in time:
 *
 *  - `limit=200` is rejected with **HTTP 400**. The per-call ceiling is 100.
 *  - `ofs=100` returns **zero** results. Offset paging does not reach past the first page for this
 *    query, so it cannot be used to walk a larger set.
 *
 * A single `countrySet=EE` call therefore reports `totalResults: 100` and stops — not because Estonia
 * has 100 stations, but because that is as far as one call sees. Sweeping a grid of overlapping
 * circles and de-duplicating on the POI id found **628** distinct stations. The overlap is deliberate:
 * a station on a tile boundary has to be inside some circle, and paying for it twice is cheaper than
 * reasoning about seams.
 *
 * ### What gets thrown away
 * Three filters, all applied before anything is written:
 *
 *  - **No brand, no station.** The largest of the three by far — roughly 45% of raw hits. See
 *    [BRAND_REQUIRED_NOTE].
 *  - **Not classified `PETROL_STATION`.** `query=petrol station` is a fuzzy search and does return the
 *    occasional car wash or parking area that merely sits at one.
 *  - **No usable coordinate.** A station with no position cannot be compared against a GPS fix.
 */
object AzureMapsStations {

    private const val TAG = "AzureMapsStations"

    private const val ENDPOINT = "https://atlas.microsoft.com/search/poi/category/json"
    private const val API_VERSION = "1.0"
    private const val CATEGORY_QUERY = "petrol station"
    private const val COUNTRY = "EE"

    /** Measured ceiling. `limit=200` is an HTTP 400. */
    private const val PAGE_LIMIT = 100

    /** Metres. Chosen against the tile step so neighbouring circles overlap rather than abut. */
    private const val TILE_RADIUS_M = 45_000

    /**
     * The tile centres, covering mainland Estonia and the larger islands.
     *
     * Estonia spans roughly 57.5–59.7 N and 21.7–28.2 E. A 0.4° latitude / 1.2° longitude step at
     * these latitudes leaves every point inside at least one 45 km circle. Thirty calls, once a day.
     */
    private val TILE_LATS = listOf(57.8, 58.2, 58.6, 59.0, 59.4)
    private val TILE_LONS = listOf(22.2, 23.4, 24.6, 25.8, 27.0, 28.0)

    /**
     * Why an unbranded POI is dropped.
     *
     * A known brand is a precondition for capturing at all: this app reads branded forecourt price
     * boards, so a POI that cannot name its operator is not a place one is expected. Azure Maps leaves
     * `poi.brands` empty on roughly 45% of Estonian hits, and that set is dominated by fuel depots,
     * marina pumps and LPG resellers — none of which carry a board to read.
     *
     * The cost is accepted knowingly: a genuinely unbranded independent forecourt sits in that set too
     * and will never be captured. The number dropped is logged on every refresh rather than being
     * silent, so the size of that cost stays visible instead of becoming an assumption.
     */
    const val BRAND_REQUIRED_NOTE =
        "a POI with no brand is not a station: the set is dominated by depots and marina pumps, " +
            "which carry no price board"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** True when a key was configured at build time. Without it the fetch is skipped, not attempted. */
    val isEnabled: Boolean get() = BuildConfig.AZURE_MAPS_KEY.isNotEmpty()

    /**
     * Sweeps the grid and returns the de-duplicated stations.
     *
     * Returns null when the fetch could not be trusted — no key, or every tile failed. That is
     * different from an empty list, and the caller must not overwrite a good map with either.
     * A *partial* sweep (some tiles failed, some succeeded) is reported as a success with fewer
     * stations, because the alternative is discarding hundreds of good records over one timeout.
     */
    suspend fun fetchAll(): List<FuelStation>? = withContext(Dispatchers.IO) {
        if (!isEnabled) {
            Log.w(TAG, "no Azure Maps key configured — cannot fetch the location map")
            return@withContext null
        }

        val byId = LinkedHashMap<String, FuelStation>()
        var tilesOk = 0
        var tilesFailed = 0
        var rawHits = 0
        var rejectedBrandless = 0
        var rejectedOther = 0

        for (lat in TILE_LATS) {
            for (lon in TILE_LONS) {
                val body = try {
                    get(url(lat, lon))
                } catch (e: Exception) {
                    tilesFailed++
                    Log.w(TAG, "tile $lat/$lon failed: ${e.message}")
                    continue
                }
                tilesOk++
                val results = runCatching {
                    JSONObject(body).optJSONArray("results")
                }.getOrNull() ?: continue

                for (i in 0 until results.length()) {
                    val r = results.optJSONObject(i) ?: continue
                    rawHits++
                    // Counted separately from the other rejections: this filter removes ~45% of hits,
                    // and folding it into a single "rejected" number would hide how much it costs.
                    if (brandOf(r) == null) {
                        rejectedBrandless++
                        continue
                    }
                    val station = toStation(r)
                    if (station == null) {
                        rejectedOther++
                        continue
                    }
                    byId.putIfAbsent(station.id, station)
                }
            }
        }

        Log.i(
            TAG,
            "grid sweep: $tilesOk tile(s) ok, $tilesFailed failed — $rawHits raw hit(s), " +
                "$rejectedBrandless dropped for no brand, $rejectedOther dropped otherwise, " +
                "${byId.size} unique branded station(s)"
        )

        if (tilesOk == 0) {
            Log.e(TAG, "every tile failed — reporting failure so the stored map is kept")
            return@withContext null
        }
        byId.values.toList()
    }

    private fun url(lat: Double, lon: Double): String = buildString {
        append(ENDPOINT)
        append("?api-version=").append(API_VERSION)
        append("&query=").append(CATEGORY_QUERY.replace(" ", "%20"))
        append("&countrySet=").append(COUNTRY)
        append("&lat=").append(lat)
        append("&lon=").append(lon)
        append("&radius=").append(TILE_RADIUS_M)
        append("&limit=").append(PAGE_LIMIT)
        append("&subscription-key=").append(BuildConfig.AZURE_MAPS_KEY)
    }

    private fun get(url: String): String {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                // The key is in the query string, so the URL must never reach a log.
                throw IllegalStateException("HTTP ${response.code}: ${body.take(200)}")
            }
            return body
        }
    }

    /**
     * The operator named on a raw result, or null when it names none.
     *
     * Its own function so the brand filter can be applied — and counted — before the fuller
     * [toStation] conversion runs.
     */
    private fun brandOf(result: JSONObject): String? =
        result.optJSONObject("poi")
            ?.optJSONArray("brands")
            ?.optJSONObject(0)
            ?.optString("name")
            ?.takeIf { it.isNotBlank() }

    /**
     * One POI result to a [FuelStation], or null when it is not one we can use.
     *
     * Re-checks the brand rather than trusting the caller, so the invariant on [FuelStation.brand]
     * holds however this is reached.
     */
    private fun toStation(result: JSONObject): FuelStation? {
        val id = result.optString("id").takeIf { it.isNotBlank() } ?: return null
        val poi = result.optJSONObject("poi") ?: return null

        val classifications = poi.optJSONArray("classifications")
        var isPetrolStation = false
        if (classifications != null) {
            for (i in 0 until classifications.length()) {
                if (classifications.optJSONObject(i)?.optString("code") == "PETROL_STATION") {
                    isPetrolStation = true
                    break
                }
            }
        }
        if (!isPetrolStation) return null

        val position = result.optJSONObject("position") ?: return null
        if (!position.has("lat") || !position.has("lon")) return null
        val lat = position.optDouble("lat")
        val lon = position.optDouble("lon")
        if (lat.isNaN() || lon.isNaN()) return null

        // Re-checked rather than trusted from the caller, so the precondition holds however this is
        // reached.
        val brand = brandOf(result) ?: return null

        return FuelStation(
            id = id,
            name = poi.optString("name").takeIf { it.isNotBlank() } ?: id,
            latitude = lat,
            longitude = lon,
            brand = brand
        )
    }
}
