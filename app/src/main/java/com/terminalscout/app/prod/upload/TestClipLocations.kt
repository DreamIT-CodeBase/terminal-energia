package com.terminalscout.app.prod.upload

import android.content.Context
import android.util.Log
import com.terminalscout.app.prod.TestClips
import org.json.JSONObject
import java.io.File

/**
 * Where each sideloaded test clip was recorded, so replaying one behaves like capturing there.
 *
 * ### Why a replay needs a location at all
 * Stage 6 names the price rows from the nearest station's board order, which needs a GPS fix from the
 * capture. A recorded clip has one; a clip replayed off the device has none — the phone is on a desk in a
 * different country — so station matching could never fire on the test set, and the naming step could
 * never be exercised on real footage.
 *
 * These clips came from the debug container, where each run folder kept the `api_response.json` written at
 * capture time, with the fix inside it. This file is that data extracted back out: given a clip, the
 * position the vehicle was actually standing at.
 *
 * ### Where it lives
 *
 *   <external files>/testclips/locations.json
 *
 * Beside the clips it describes, pushed with them over adb, and read fresh on every lookup so editing it
 * needs no reinstall. External storage rather than private files because that is where the clips already
 * are and where `adb push` can reach; it is dev-only fixture data, and `AppReset` leaves it alone for the
 * same reason it leaves the clips alone.
 *
 * ### Scope
 * Applies ONLY to files inside `testclips/`. A clip the file does not mention, and any real recording,
 * falls through to the live GPS fix — a replay must never be able to fake the location of a genuine
 * capture.
 */
object TestClipLocations {

    private const val TAG = "TestClipLocations"
    private const val FILE = "locations.json"

    /**
     * The recorded fix for [videoFile], or null when it is not a test clip or has no entry.
     *
     * Keyed on the file name without its extension, which is the run id the clip was exported under —
     * the same id that names its folder in the debug container.
     */
    fun forVideo(context: Context, videoFile: File): LocationFix? {
        // Only ever applies to the test-clip directory.
        if (!TestClips.isTestClip(context, videoFile)) return null

        val f = File(TestClips.dir(context), FILE)
        if (!f.isFile) return null

        val key = videoFile.nameWithoutExtension
        return try {
            val entry = JSONObject(f.readText()).optJSONObject(key)
            if (entry == null) {
                Log.d(TAG, "no recorded location for test clip $key")
                return null
            }
            if (!entry.has("latitude") || !entry.has("longitude")) {
                Log.w(TAG, "entry for $key has no coordinates")
                return null
            }
            val fix = LocationFix(
                latitude = entry.getDouble("latitude"),
                longitude = entry.getDouble("longitude"),
                // The exported payloads carry accuracy only — no speed or heading was recorded, and
                // inventing either would put made-up numbers in a field a consumer may trust.
                speedKmph = null,
                headingDeg = null,
                accuracyM = if (entry.has("gps_accuracy_m")) {
                    entry.getDouble("gps_accuracy_m").toFloat()
                } else null
            )
            Log.i(TAG, "replaying $key as captured at ${fix.latitude},${fix.longitude}")
            fix
        } catch (e: Exception) {
            Log.w(TAG, "could not read $FILE", e)
            null
        }
    }
}
