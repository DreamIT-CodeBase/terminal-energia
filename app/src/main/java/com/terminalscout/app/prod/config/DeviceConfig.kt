package com.terminalscout.app.prod.config

import android.content.Context
import android.util.Log
import com.terminalscout.app.prod.download.DeviceApi
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * PRE-STAGE 0 — the fetched tunables. The one place the app reads a number that the server may change.
 *
 * `GET /v1/config` serves a document of every constant the pipeline was willing to expose, grouped by
 * stage. This object stores it, validates it, and hands out one value at a time. Everything else in the
 * app keeps its constant *declaration* — the name, the type, the KDoc explaining what it costs — and
 * only the value moves:
 *
 *     const val ENTER_RADIUS_M = 80.0
 *     // becomes
 *     val ENTER_RADIUS_M: Double get() = DeviceConfig.double(PATH, 80.0)
 *
 * The compiled number stays at the declaration as the fallback, so **a device with no config, an
 * unreachable server, or a rejected document behaves exactly like the build it is running.** That is
 * the property that makes this safe to ship: the config can only move a value the app already had a
 * working answer for.
 *
 * ### The snapshot does not move under a running pipeline
 * [load] reads the stored file into an immutable snapshot ONCE per process, at setup. [refresh] fetches
 * and writes the file but **does not swap the snapshot** — the new values take effect at the next
 * process start. That is deliberate and it is the whole reason this is safe:
 *
 *  - A run that starts under one set of values must finish under them. Half of Stage 3's geometry
 *    reading one `shift_frac` while the rest reads another produces a canvas that no artifact describes.
 *  - A capture holds the camera from fire to release. A trigger radius that changes between the two
 *    edges strands a frozen board.
 *
 * A unit relaunches often — the watchdog raises the Activity, the operator opens the app, the process
 * dies and comes back — so "next process start" is hours, not weeks.
 *
 * ### A bad document is refused whole, never merged
 * [validate] enforces relationships required for coherent behavior, such as the exit radius being
 * outside the enter radius, weight sets summing to 1, storage floors' ordering, and the detector
 * ladder's ordering. It does not impose local capture-radius, polling-interval, distance, or clip
 * ceilings: those values are owned by the API configuration.
 *
 * ### Paths are strings, and that is the honest cost
 * `double("capture.trigger.enter_radius_m", 80.0)` is looked up by path. A typo silently falls back to
 * the compiled value rather than failing, which is the safe direction but a quiet one — so an absent
 * path is logged once, and [pathsRead] exposes every path the process has asked for so a test can check
 * them against the shipped document.
 */
object DeviceConfig {

    private const val TAG = "DeviceConfig"

    /** The only schema this build knows how to read. A document at any other version is refused. */
    const val SUPPORTED_SCHEMA_VERSION = 2

    private const val DIR = "config"
    private const val FILE = "device-config.json"

    /**
     * The live snapshot: the `config` object of the stored document, or null when there is none.
     *
     * Volatile and replaced wholesale rather than mutated, so a reader either sees the whole previous
     * document or the whole new one. [load] is the only writer.
     */
    @Volatile
    private var config: JSONObject? = null

    /** Parsed values, keyed by path. Safe because [config] is replaced, never edited in place. */
    private val cache = HashMap<String, Any>()

    /** Every path this process has asked for, whether or not the document had it. */
    private val asked = LinkedHashSet<String>()

    /** Paths already reported absent, so a value read in a loop logs once rather than per frame. */
    private val warned = HashSet<String>()

    /** Where the document came from, for the log and the readout. */
    @Volatile
    var describe: String = "built-in defaults"
        private set

    /** True once a stored document has been loaded and accepted. */
    val isLoaded: Boolean get() = config != null

    // ---- lifecycle ----------------------------------------------------------------------------- //

    private fun dir(context: Context): File =
        File(context.filesDir, DIR).apply { if (!exists()) mkdirs() }

    private fun file(context: Context): File = File(dir(context), FILE)

    /**
     * Reads the stored document into the snapshot. Call once, early in setup, before anything reads a
     * value — [AppSetup] does it as its first step.
     *
     * Never throws and never leaves a half-applied document: a file that is missing, unreadable,
     * unparseable or invalid leaves the snapshot exactly as it was, which on a fresh device means the
     * compiled defaults.
     *
     * @return true when a stored document is now in force
     */
    fun load(context: Context): Boolean {
        val f = file(context)
        if (!f.exists()) {
            Log.i(TAG, "no stored config — the build's own constants are in force")
            return false
        }
        val body = try {
            f.readText()
        } catch (e: Exception) {
            Log.e(TAG, "could not read the stored config — keeping ${describe}", e)
            return false
        }
        return apply(body, "stored file")
    }

    /**
     * Validates a document and makes it the snapshot.
     *
     * Internal so the parse, the version gate and the invariants can be tested without a filesystem —
     * they are the half of this file that decides what the fleet runs.
     */
    internal fun apply(body: String, from: String): Boolean {
        val parsed = parse(body) ?: return false
        val problems = validate(parsed)
        if (problems.isNotEmpty()) {
            // Refused WHOLE. See the class note: a config half-applied is a configuration nobody wrote.
            Log.e(TAG, "config from $from refused — ${problems.joinToString("; ")}")
            return false
        }
        synchronized(cache) {
            config = parsed
            cache.clear()
            warned.clear()
        }
        describe = "$from (schema ${parsed.optInt("schema_version")}, " +
            "config_id ${parsed.optString("config_id").ifEmpty { "?" }}, " +
            "updated ${parsed.optString("updated_at").ifEmpty { "?" }})"
        Log.i(TAG, "config in force: $describe")
        return true
    }

    /**
     * The `config` object out of the response envelope, or null when the body is not one.
     *
     * Accepts the envelope `{config_id, schema_version, etag, config:{…}}` and the bare document, so a
     * file written by hand for a field test loads the same way the server's does.
     */
    private fun parse(body: String): JSONObject? {
        val root = try {
            JSONObject(body)
        } catch (e: Exception) {
            Log.e(TAG, "config did not parse as JSON", e)
            return null
        }
        val doc = root.optJSONObject("config") ?: root
        val version = doc.optInt("schema_version", -1)
        if (version != SUPPORTED_SCHEMA_VERSION) {
            // Refusing an unknown version is the point of having one: a v3 document may have moved a
            // path this build reads, and a silently-missing path is a silently-different pipeline.
            Log.e(
                TAG,
                "config schema $version is not $SUPPORTED_SCHEMA_VERSION — refusing it; " +
                    "this build keeps its own constants"
            )
            return null
        }
        return doc
    }

    /**
     * The relationships that used to be guaranteed by the compiler, checked before a document is
     * allowed to take effect. Returns one message per broken rule; empty means usable.
     *
     * Only structural relationships required for coherent operation. Capture tuning values are not
     * locally clamped or rejected based on build-time assumptions.
     */
    internal fun validate(doc: JSONObject): List<String> {
        val problems = ArrayList<String>()

        fun num(path: String): Double? {
            val node = resolve(doc, path)
            return (node as? Number)?.toDouble()
        }

        val enter = num("capture.trigger.enter_radius_m")
        val exit = num("capture.trigger.exit_radius_m")
        if (enter != null && exit != null) {
            if (exit <= enter) {
                problems += "exit_radius_m ($exit) must be outside enter_radius_m ($enter)"
            }
        }

        val sharp = num("detection.clarity_gate.sharpness_weight")
        val contrast = num("detection.clarity_gate.contrast_weight")
        if (sharp != null && contrast != null && kotlin.math.abs(sharp + contrast - 1.0) > 1e-6) {
            problems += "clarity gate weights must sum to 1 (got ${sharp + contrast})"
        }

        val weights = listOf("w_iou", "w_centroid", "w_sig", "w_aspect")
            .mapNotNull { num("detection.tracker.$it") }
        if (weights.size == 4 && kotlin.math.abs(weights.sum() - 1.0) > 1e-6) {
            problems += "tracker weights must sum to 1 (got ${weights.sum()})"
        }

        val start = num("extraction.min_free_to_start_mb")
        val reserve = num("extraction.min_free_reserve_mb")
        if (start != null && reserve != null && start <= reserve) {
            problems += "min_free_to_start_mb ($start) must exceed min_free_reserve_mb ($reserve)"
        }

        val confThres = num("detection.detector.conf_thres")
        val fallback = num("detection.detector.fallback_conf")
        val nopass = num("detection.detector.nopass_conf")
        if (confThres != null && fallback != null && fallback >= confThres) {
            problems += "fallback_conf ($fallback) must be looser than conf_thres ($confThres)"
        }
        if (fallback != null && nopass != null && nopass >= fallback) {
            problems += "nopass_conf ($nopass) must be looser than fallback_conf ($fallback)"
        }

        val minRegions = num("board_prep.image_prep.min_regions")
        val maxRegions = num("board_prep.image_prep.max_regions")
        if (minRegions != null && maxRegions != null && minRegions > maxRegions) {
            problems += "min_regions ($minRegions) above max_regions ($maxRegions) discards every canvas"
        }

        return problems
    }

    /**
     * Fetches `GET /v1/config` and stores it, **without** changing what this process is running.
     *
     * Storing rather than applying is the whole contract — see the class note. A fetched document is
     * validated before it is written, so a device never stores one it would refuse to load.
     *
     * @return true when a new document was stored
     */
    suspend fun refresh(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (!DeviceApi.isConfigured) {
            Log.d(TAG, "no API base URL / fleet secret — config refresh skipped")
            return@withContext false
        }
        when (val res = DeviceApi.config(context)) {
            is DeviceApi.ApiResult.Ok -> {
                val body = res.value
                val doc = parse(body)
                if (doc == null) {
                    Log.w(TAG, "fetched config is not usable — the stored one stands")
                    return@withContext false
                }
                val problems = validate(doc)
                if (problems.isNotEmpty()) {
                    Log.e(
                        TAG,
                        "fetched config refused, not stored — ${problems.joinToString("; ")}"
                    )
                    return@withContext false
                }
                try {
                    // The server's document verbatim, envelope and all: what is on disk should be what
                    // arrived, not this build's reading of it.
                    file(context).writeText(body)
                    Log.i(
                        TAG,
                        "config stored (schema ${doc.optInt("schema_version")}, " +
                            "updated ${doc.optString("updated_at")}) — in force at the next start"
                    )
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "could not store the fetched config", e)
                    false
                }
            }
            DeviceApi.ApiResult.Transient -> {
                Log.w(TAG, "config fetch failed transiently — the stored one stands")
                false
            }
            is DeviceApi.ApiResult.Permanent -> {
                Log.e(TAG, "config fetch refused (HTTP ${res.code}): ${res.detail}")
                false
            }
        }
    }

    /**
     * Object-level scope, for the one thing here that outlives a caller: [requestRefresh].
     *
     * `AppSetup` is synchronous and runs from `onResume`, so it cannot await a network call — and must
     * not, because a config fetch that blocked setup would hold the capture service behind it. The
     * handler and the `Throwable` catch are not belt-and-braces: `SupervisorJob` does not stop an
     * unhandled exception reaching the thread's uncaught-exception handler, and this is launched on
     * every launch and every resume.
     */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, t ->
            Log.e(TAG, "config refresh died", t)
        }
    )

    @Volatile
    private var refreshing = false

    /**
     * Asks for a fresh document in the background. Returns immediately; nothing waits on it.
     *
     * Idempotent across the several setup passes a single launch makes: a refresh already in flight is
     * not joined by a second.
     */
    fun requestRefresh(context: Context) {
        if (refreshing) return
        refreshing = true
        val app = context.applicationContext
        scope.launch {
            try {
                refresh(app)
            } catch (t: Throwable) {
                Log.e(TAG, "config refresh failed", t)
            } finally {
                refreshing = false
            }
        }
    }

    /** Drops the snapshot. For tests, and for `AppReset`. */
    fun clear() {
        synchronized(cache) {
            config = null
            cache.clear()
            warned.clear()
            asked.clear()
        }
        describe = "built-in defaults"
    }

    // ---- reads --------------------------------------------------------------------------------- //

    /**
     * The value at [path], or [fallback] when this device has no document, the document has no such
     * path, or the value is not the type asked for.
     *
     * [fallback] is the build's own constant, so every read has a working answer before the network
     * does. Cached per path: a value read inside a per-frame loop costs one hash lookup after the first.
     */
    fun double(path: String, fallback: Double): Double = cached(path) { it.toDoubleOrNull() } ?: fallback

    fun float(path: String, fallback: Float): Float =
        (cached(path) { it.toDoubleOrNull() } ?: return fallback).toFloat()

    fun int(path: String, fallback: Int): Int =
        (cached(path) { it.toDoubleOrNull() } ?: return fallback).toInt()

    fun long(path: String, fallback: Long): Long =
        (cached(path) { it.toDoubleOrNull() } ?: return fallback).toLong()

    fun boolean(path: String, fallback: Boolean): Boolean {
        val node = lookup(path) ?: return fallback
        return (node as? Boolean) ?: fallback
    }

    /** A list of numbers — `conf_escalation`, `retry_backoff_ms`. Falls back whole, never per element. */
    fun doubleList(path: String, fallback: List<Double>): List<Double> {
        val arr = lookup(path) as? JSONArray ?: return fallback
        val out = ArrayList<Double>(arr.length())
        for (i in 0 until arr.length()) {
            val v = arr.opt(i) as? Number ?: return fallback
            out += v.toDouble()
        }
        return if (out.isEmpty()) fallback else out
    }

    fun floatArray(path: String, fallback: FloatArray): FloatArray {
        val list = doubleList(path, emptyList())
        return if (list.isEmpty()) fallback else FloatArray(list.size) { list[it].toFloat() }
    }

    fun longArray(path: String, fallback: LongArray): LongArray {
        val list = doubleList(path, emptyList())
        return if (list.isEmpty()) fallback else LongArray(list.size) { list[it].toLong() }
    }

    /**
     * JSON numbers arrive as Int, Long or Double depending on how they were written, so every numeric
     * read goes through one conversion rather than each accessor guessing.
     */
    private inline fun cached(path: String, convert: (Number) -> Double?): Double? {
        val node = lookup(path) ?: return null
        val n = node as? Number ?: return null
        return convert(n)
    }

    private fun Number.toDoubleOrNull(): Double? = this.toDouble().takeIf { !it.isNaN() }

    /** Resolves [path] against the snapshot, remembering it was asked for. */
    private fun lookup(path: String): Any? {
        synchronized(cache) {
            asked += path
            cache[path]?.let { return it }
            val doc = config ?: return null
            val node = resolve(doc, path)
            if (node == null) {
                if (warned.add(path)) {
                    Log.w(TAG, "config has no '$path' — the build's own value stands")
                }
                return null
            }
            cache[path] = node
            return node
        }
    }

    /** `a.b.c` against a document. Null when any segment is missing or is not an object. */
    private fun resolve(doc: JSONObject, path: String): Any? {
        val parts = path.split('.')
        var node: Any = doc
        for ((i, part) in parts.withIndex()) {
            val obj = node as? JSONObject ?: return null
            val next = obj.opt(part) ?: return null
            if (next == JSONObject.NULL) return null
            node = next
            if (i == parts.lastIndex) return node
        }
        return null
    }

    /** Every path asked for so far. A test uses it to check the code's paths against the document. */
    fun pathsRead(): Set<String> = synchronized(cache) { LinkedHashSet(asked) }
}
