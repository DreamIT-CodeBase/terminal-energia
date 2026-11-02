package com.terminalscout.app.prod.pipeline

import android.content.Context
import android.util.Log
import com.terminalscout.app.prod.TestClips
import com.terminalscout.app.prod.upload.AzureMapsStore
import com.terminalscout.app.prod.config.DeviceConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Which branch of Stage 3's image processing a capture gets. */
enum class LightPhase { DAY, NIGHT }

/**
 * What was decided, and on what evidence — so a run's Logcat says why it filtered the way it did.
 *
 * @param source where the capture time came from: the clip's own name, or the clock
 * @param sunriseAtMs the day's sunrise, or null when no sun times were stored
 */
data class LightDecision(
    val phase: LightPhase,
    val capturedAtMs: Long,
    val source: String,
    val sunriseAtMs: Long? = null,
    val sunsetAtMs: Long? = null,
    val reason: String = ""
) {
    val isNight: Boolean get() = phase == LightPhase.NIGHT
}

/**
 * Decides whether a capture is a DAY or a NIGHT one, which is what selects Stage 3's filter branch.
 *
 * ### The rule
 * Both shifts happen **30 minutes EARLY**:
 *
 * ```
 *            sunrise-30m        sunset-30m
 *   ---NIGHT------|---------DAY------|------NIGHT---
 * ```
 *
 * So the day filter starts half an hour before the sun is up, and the night filter starts half an hour
 * before it goes down. The asymmetry is intentional and is not a mistake in the arithmetic: the light
 * that matters is not the sun's position but whether the board's LEDs are dominating the exposure, and
 * a board is already blooming against a dusk sky well before sunset while a dawn board stops blooming
 * about as early. Getting it wrong in the safe direction on each edge means a bloomed board is filtered
 * as a bloomed board.
 *
 * ### Failing to day
 * With no stored sun times — a device that has never had signal at 03:00, or a fresh install — the
 * answer is DAY. Not a coin flip: the night branch is actively damaging on a day board (its gamma curve
 * and erosion assume bloom that is not there), while the day branch on a night board merely reads
 * poorly. The safe default is the one that cannot wreck a readable crop.
 *
 * ### Where the capture time comes from
 * A live capture is processed within minutes of recording, so the clock is right. A REPLAYED test clip
 * is not: it was shot at dusk months ago and would be filtered as whatever time it is now, which would
 * make the night path unreachable from a desk. Those clips are named `yyyyMMdd-HHmmss-xxxx`, so the
 * stamp in the filename is used when there is one — see [captureTimeOf].
 *
 * ### There is no override
 * A menu switch used to pin this to one branch for every run, sticky across process death. On a unit
 * that records unattended that is a way to prep every capture on the wrong branch until somebody
 * notices — and the night branch actively damages a day board. The decision below is the only one.
 *
 * ### Test clips do not go through the rule at all
 * The stamp is only as good as the clock that wrote it, and the sideloaded set was recorded on a device
 * whose time may since have been changed — so a couple of the clips carry a stamp that puts them on the
 * wrong side of dusk. Which of them is night footage is a known fact about that set, not something to
 * re-derive on every run, so it is simply listed: [NIGHT_TEST_CLIPS] filter as NIGHT and every other
 * test clip as DAY, with no sun times involved. Scoped by [TestClips] — a real recording, camera capture
 * included, is always decided by the rule above.
 */
object DayNight {

    private const val TAG = "DayNight"

    /** How early each shift happens, on both edges. */
    val LEAD_MS: Long get() = DeviceConfig.long("board_prep.day_night.lead_ms", 30 * 60 * 1000L)

    /**
     * The sideloaded clips that are night footage, by run id (the file name without `.mp4`).
     *
     * Hand-maintained, and deliberately so: it is a property of this fixture set, which changes when
     * clips are pushed and not otherwise. Add a clip here when its board is lit and blooming; leave it
     * out and it gets the day branch, which is the harmless direction (see "Failing to day" above).
     */
    val NIGHT_TEST_CLIPS = setOf(
        "20260819-215942-7154",
        "20260819-215338-4aaf",
        "20260819-211629-c9bd"
    )

    /** `20260819-215338-4aaf.mp4` → the capture instant. Device-local, as the recorder wrote it. */
    private val CLIP_STAMP = Regex("""(\d{8})-(\d{6})""")

    /**
     * The capture time for [videoFile]: the stamp in its name when it has one, otherwise now.
     *
     * The stamp is what makes a replayed clip filter as the evening it was shot rather than as the
     * afternoon it is being replayed on. A clip named anything else is treated as a live capture, which
     * is what it almost certainly is.
     */
    fun captureTimeOf(videoFile: File, nowMs: Long = System.currentTimeMillis()): Pair<Long, String> {
        val m = CLIP_STAMP.find(videoFile.nameWithoutExtension) ?: return nowMs to "clock"
        val parsed = try {
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).parse("${m.groupValues[1]}-${m.groupValues[2]}")
        } catch (e: Exception) {
            null
        }
        return if (parsed == null) nowMs to "clock" else parsed.time to "clip name"
    }

    /**
     * DAY or NIGHT for a capture at [capturedAtMs], against the sun times [AzureMapsStore] holds.
     *
     * Reads the stored answer STALE-TOLERANTLY (`stored()`, not `current()`): sunrise moves by a minute
     * or two a day, so yesterday's times decide today's dusk correctly, and a run that happens before
     * the day's refresh has landed should still filter properly rather than fall back to day.
     */
    fun decide(capturedAtMs: Long, source: String = "clock"): LightDecision {
        val stored = AzureMapsStore.stored()
        val sunrise = stored?.sunriseAtMs
        val sunset = stored?.sunsetAtMs
        if (sunrise == null || sunset == null) {
            return LightDecision(
                phase = LightPhase.DAY,
                capturedAtMs = capturedAtMs,
                source = source,
                reason = if (stored == null) "no sun times stored" else "stored answer carries no sun times"
            )
        }

        val dayFrom = sunrise - LEAD_MS
        val nightFrom = sunset - LEAD_MS
        val night = capturedAtMs < dayFrom || capturedAtMs >= nightFrom
        return LightDecision(
            phase = if (night) LightPhase.NIGHT else LightPhase.DAY,
            capturedAtMs = capturedAtMs,
            source = source,
            sunriseAtMs = sunrise,
            sunsetAtMs = sunset,
            reason = "day runs ${AzureMapsStore.stamp(dayFrom)}..${AzureMapsStore.stamp(nightFrom)}"
        )
    }

    /**
     * The listed phase for [videoFile] when it is a sideloaded test clip, or null when it is not one.
     *
     * The capture time is still reported — it is what the log line shows — but nothing is decided from
     * it, which is the whole point: these stamps are not trusted (see the class doc).
     */
    private fun testClipDecision(
        context: Context,
        videoFile: File,
        nowMs: Long
    ): LightDecision? {
        if (!TestClips.isTestClip(context, videoFile)) return null

        val id = videoFile.nameWithoutExtension
        val night = id in NIGHT_TEST_CLIPS
        return LightDecision(
            phase = if (night) LightPhase.NIGHT else LightPhase.DAY,
            capturedAtMs = captureTimeOf(videoFile, nowMs).first,
            source = "test clip",
            reason = if (night) "on the night-clip list" else "not on the night-clip list"
        )
    }

    // ---- The manual filter switch ---------------------------------------------------------------- //

    private const val PHASE_DIR = "filter"
    private const val PHASE_FILE = "light_phase"

    /**
     * The filter the menu switches are set to — an OVERRIDE on Stage 3's branch, not a replacement.
     *
     * ### Three states from two checkboxes
     * The menu carries DAY FILTER and NIGHT FILTER, mutually exclusive. Both off is AUTO and is the
     * default, so the sun-times rule ([decideAutomatically]) is still what a unit does when nobody has
     * touched it. Ticking one forces that phase for every capture until it is unticked.
     *
     * This shape replaced a compile-time pin and, briefly, a two-state switch that removed the
     * automatic path altogether. Both-off-is-AUTO is what makes the override safe to have at all: the
     * abnormal state is the one somebody had to tick, and an untouched unit is never in it.
     *
     * ### The history, so nobody re-learns it the hard way
     * A menu override lived here before and was removed: it was sticky across process death, so one tap
     * nobody remembered making silently changed how a deployed unit prepped every later capture, and the
     * night branch's gamma curve and large-kernel unsharp actively damage a day board. It came back by
     * request on 2026-09-11, deliberately, with the stickiness intact — a field test wants the phase held
     * for hours, across the restarts a watchdog causes.
     *
     * What is different this time is only that it is LOUD. Every run logs the phase and that a person
     * chose it, `ImagePrepStage` repeats it on its own branch line, and the menu states it in the label
     * rather than in a checkbox somebody has to notice is ticked. The failure mode is unchanged: a unit
     * left on the wrong setting preps every capture wrongly until somebody looks. That is now a visible
     * wrong answer instead of a silent one, which is the most a switch like this can offer.
     *
     * A one-word file rather than SharedPreferences, matching [com.terminalscout.app.prod.ui.KioskMode]:
     * the app keeps no preferences, and `adb shell run-as … cat files/filter/light_phase` is what
     * explains a unit's behaviour after the fact.
     */
    private fun phaseFile(context: Context): File {
        val dir = File(context.applicationContext.filesDir, PHASE_DIR)
        // Guarded like KioskMode's: `mkdirs` throws on a volume this app cannot write, and an absent
        // file already reads as the default.
        runCatching { if (!dir.isDirectory) dir.mkdirs() }
        return File(dir, PHASE_FILE)
    }

    /**
     * The phase FORCED from the menu, or null when neither checkbox is ticked.
     *
     * Returns **null for AUTO** — neither box ticked — which is the default and the normal production
     * state. A forced phase is the exception, and the null is what makes that shape explicit at every
     * call site rather than hiding "automatic" inside a third enum value nobody handles.
     *
     * Missing or unreadable both read as AUTO. That is the setting a unit was shipped with, and the one
     * that is right wherever it happens to be; falling back to a FORCED phase would mean a filesystem
     * hiccup silently pinned the branch, which is the failure this switch has already been deleted for
     * once.
     */
    fun selectedPhase(context: Context): LightPhase? =
        runCatching {
            val f = phaseFile(context)
            if (!f.exists()) return@runCatching null
            when (f.readText().trim().uppercase()) {
                LightPhase.DAY.name -> LightPhase.DAY
                LightPhase.NIGHT.name -> LightPhase.NIGHT
                else -> null
            }
        }.getOrElse {
            Log.e(TAG, "could not read the filter setting — falling back to AUTO", it)
            null
        }

    /**
     * Sets the forced phase, or clears it back to AUTO with null. Persisted across restarts.
     *
     * The two menu checkboxes are mutually exclusive and both-off is AUTO, so the three states a user
     * can express map exactly onto the three values this takes.
     */
    fun setSelectedPhase(context: Context, phase: LightPhase?) {
        runCatching {
            if (phase == null) phaseFile(context).delete() else phaseFile(context).writeText(phase.name)
        }.onFailure { Log.e(TAG, "could not persist the filter setting", it) }
        Log.w(TAG, "image filter: ${phase?.name ?: "AUTO"}")
    }

    /**
     * Decides for a video file: a phase forced in the menu wins, otherwise the sun-times rule runs.
     */
    fun decideFor(
        context: Context,
        videoFile: File,
        nowMs: Long = System.currentTimeMillis()
    ): LightDecision {
        val forced = selectedPhase(context) ?: return decideAutomatically(context, videoFile, nowMs)
        // The capture time is still read for the record, even though it no longer picks the branch.
        val (atMs, timeSource) = captureTimeOf(videoFile, nowMs)
        val decision = LightDecision(
            phase = forced,
            capturedAtMs = atMs,
            source = timeSource,
            reason = "FORCED in the menu — the sun times were not consulted"
        )
        // Warn, not debug: a phase a person chose must never read like one the app worked out. The
        // automatic path below logs at debug, so the two are told apart by level alone in a busy log.
        Log.w(TAG, "${decision.phase} — ${decision.reason}")
        return decision
    }

    /**
     * The sun-times decision — what runs whenever neither checkbox is ticked, which is the default.
     *
     * Split out from [decideFor] so the forced path and the automatic one are separately readable and
     * separately testable, and so a caller that wants the rule regardless of the switch can ask for it.
     */
    fun decideAutomatically(
        context: Context,
        videoFile: File,
        nowMs: Long = System.currentTimeMillis()
    ): LightDecision {
        testClipDecision(context, videoFile, nowMs)?.let {
            Log.d(
                TAG,
                "${it.phase} — test clip ${videoFile.nameWithoutExtension}, ${it.reason}"
            )
            return it
        }
        val (at, source) = captureTimeOf(videoFile, nowMs)
        return decide(at, source).also {
            Log.d(
                TAG,
                "${it.phase} — captured ${AzureMapsStore.stamp(it.capturedAtMs)} (${it.source})" +
                    if (it.reason.isEmpty()) "" else ", ${it.reason}"
            )
        }
    }

    /** For logs: `NIGHT (captured 2026-08-19 21:53, day runs 05:27..20:17)`. */
    fun describe(d: LightDecision): String = buildString {
        append(d.phase.name)
        append(" (captured ").append(AzureMapsStore.stamp(d.capturedAtMs)).append(", ").append(d.source)
        if (d.sunriseAtMs != null && d.sunsetAtMs != null) {
            append(", sunrise ").append(AzureMapsStore.stamp(d.sunriseAtMs))
            append(", sunset ").append(AzureMapsStore.stamp(d.sunsetAtMs))
        } else {
            append(", ").append(d.reason)
        }
        append(")")
    }
}
