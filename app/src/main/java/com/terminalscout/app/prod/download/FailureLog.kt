package com.terminalscout.app.prod.download

import android.os.Process
import com.terminalscout.app.prod.config.DeviceConfig
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Captures the app's own logcat around a failure, so a rejected artifact arrives with the reason
 * it was rejected attached.
 *
 * ### Why logcat and not a log file
 * The app already logs richly through `android.util.Log` from every stage — the detector's ladder,
 * the reader's per-canvas results, the uploader's HTTP codes. Reproducing that into a second sink
 * would mean wrapping every call site and would still miss anything logged by a library. Reading
 * logcat back gets the real thing, including the lines written before anyone knew a failure was
 * coming.
 *
 * ### What an app is allowed to read
 * Since Android 4.1 an app may read **only its own** log entries; `READ_LOGS` for anything wider is
 * a privileged permission we neither have nor want. So this filters to this process's PID, which is
 * both what we are permitted to see and exactly what is wanted — a diagnostic for this run, not a
 * dump of the device.
 *
 * The buffer is also finite and shared: on a busy device the earliest lines of a long run may
 * already have rotated out by the time a failure happens. What comes back is the tail, not a
 * guarantee of the whole story, and [capture] says so in the file it writes.
 *
 * ### It must never make things worse
 * Every failure here is swallowed. This runs at the moment something has ALREADY gone wrong; a
 * crash or a hang while collecting diagnostics would turn a recoverable upload rejection into a
 * lost run. `logcat -d` drains and exits rather than streaming, so it cannot block.
 */
object FailureLog {

    private const val TAG = "FailureLog"

    /** Lines kept from the tail of the buffer. Enough for a run's worth of stage logging. */
    private val MAX_LINES: Int get() = DeviceConfig.int("diagnostics.failure_log.max_lines", 600)

    /** Hard ceiling on the text, so one pathological run cannot stage a huge artifact. */
    private val MAX_CHARS: Int get() = DeviceConfig.int("diagnostics.failure_log.max_chars", 256 * 1024)

    /** Seconds to wait for logcat to drain before giving up on it. */
    private val TIMEOUT_S: Long get() = DeviceConfig.long("diagnostics.failure_log.timeout_s", 10L)

    /** Marks where the logcat tail begins, so [captureRun] can insert a stack ahead of it. */
    private const val LOGCAT_MARKER = "LOGCAT TAIL\n"

    /**
     * Builds the `logs.txt` body for one artifact the server REJECTED.
     *
     * @param runFolder the run the failure belongs to
     * @param artifact  the artifact path that was rejected
     * @param reason    what the server said, verbatim
     */
    fun capture(runFolder: String, artifact: String, reason: String): String = report(
        title = "UPLOAD FAILURE",
        fields = listOf(
            "run" to runFolder,
            "artifact" to artifact,
            "reason" to reason
        ),
        note = listOf(
            "This artifact was rejected outright and will NOT be retried — a rejection",
            "that is permanent stays permanent, and re-attempting it every drain would",
            "only spend battery and data to fail identically. It stays on the device",
            "until Clean memory removes it."
        )
    )

    /**
     * Builds the `failure.txt` body for a RUN that did not deliver a reading.
     *
     * The counterpart to [capture], and kept separate because the two failures are nothing alike: that
     * one is the server refusing an artifact the device produced, this one is the device never getting
     * to an artifact at all. They share a file only because both want the same logcat tail underneath.
     *
     * @param runFolder the run the clip was processed under
     * @param clip      the clip's filename
     * @param clipBytes its size, so a report read without the clip beside it still says how big it was
     * @param outcome   `incomplete` (the run answered and could not read the board) or `threw`
     * @param stage     the pipeline stage that named itself as failed, when one did
     * @param cause     the throwable, when the run threw
     */
    fun captureRun(
        runFolder: String,
        clip: String,
        clipBytes: Long,
        outcome: String,
        stage: String?,
        cause: Throwable?
    ): String {
        val body = report(
            title = "RUN FAILURE",
            fields = listOf(
                "run" to runFolder,
                "clip" to clip,
                "clip bytes" to clipBytes.toString(),
                "outcome" to outcome,
                "stage" to (stage ?: "(none named)"),
                "cause" to (cause?.let { "${it.javaClass.name}: ${it.message ?: "(no message)"}" }
                    ?: "(did not throw)")
            ),
            note = listOf(
                "This clip did not produce a reading. It has been MOVED into the upload outbox",
                "alongside this report rather than left on disk: a clip that fails once tends to fail",
                "identically on every retry, and that retry was a five-second loop at full CPU. The",
                "footage is the evidence for why it failed, so it leaves the device with the reason",
                "attached rather than being deleted or reprocessed forever."
            )
        )
        // The stack goes between the header and the logcat tail: what threw is the first thing a reader
        // wants, and the 600 lines below are the context for it — not the other way round.
        val stack = cause?.let { t ->
            buildString {
                appendLine("STACK")
                appendLine("-".repeat(78))
                appendLine(t.stackTraceToString().take(MAX_CHARS))
                appendLine("-".repeat(78))
            }
        }.orEmpty()
        return body.substringBefore(LOGCAT_MARKER) + stack + LOGCAT_MARKER +
            body.substringAfter(LOGCAT_MARKER)
    }

    /**
     * The shared shape: a banner, labelled fields, a note saying what becomes of this failure, and this
     * process's logcat tail.
     */
    private fun report(
        title: String,
        fields: List<Pair<String, String>>,
        note: List<String>
    ): String {
        val width = fields.maxOfOrNull { it.first.length } ?: 0
        val header = buildString {
            appendLine("=".repeat(78))
            appendLine(title)
            appendLine("=".repeat(78))
            fields.forEach { (k, v) -> appendLine("${k.padEnd(width)} : $v") }
            appendLine("${"captured".padEnd(width)} : ${java.util.Date()}")
            appendLine("${"pid".padEnd(width)} : ${Process.myPid()}")
            appendLine()
            note.forEach { appendLine(it) }
            appendLine()
            appendLine("Below is this process's own logcat tail. Android only lets an app read its")
            appendLine("own entries, and the buffer is finite and shared, so the earliest lines of a")
            appendLine("long run may already have rotated out.")
            appendLine("-".repeat(78))
            append(LOGCAT_MARKER)
        }
        return header + (logcatTail() ?: "(logcat unavailable — see the note above)\n")
    }

    /**
     * This process's logcat tail, or null if it could not be read.
     *
     * `-d` dumps and exits; without it the command would stream forever and this would hang.
     */
    private fun logcatTail(): String? = try {
        val process = ProcessBuilder(
            listOf("logcat", "-d", "-v", "time", "--pid=${Process.myPid()}")
        ).redirectErrorStream(true).start()

        val lines = ArrayDeque<String>(MAX_LINES)
        BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                // A ring buffer: keep the LAST MAX_LINES without holding the whole dump in memory.
                if (lines.size == MAX_LINES) lines.removeFirst()
                lines.addLast(line)
            }
        }
        process.waitFor(TIMEOUT_S, java.util.concurrent.TimeUnit.SECONDS)
        process.destroy()

        if (lines.isEmpty()) {
            // Older devices reject --pid; fall back to an unfiltered dump, which the platform still
            // scopes to this app's own UID.
            Log.d(TAG, "no lines with --pid — retrying without it")
            unfilteredTail()
        } else {
            lines.joinToString("\n").take(MAX_CHARS) + "\n"
        }
    } catch (e: Exception) {
        Log.w(TAG, "could not read logcat", e)
        null
    }

    private fun unfilteredTail(): String? = try {
        val process = ProcessBuilder(listOf("logcat", "-d", "-v", "time"))
            .redirectErrorStream(true).start()
        val lines = ArrayDeque<String>(MAX_LINES)
        BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                if (lines.size == MAX_LINES) lines.removeFirst()
                lines.addLast(line)
            }
        }
        process.waitFor(TIMEOUT_S, java.util.concurrent.TimeUnit.SECONDS)
        process.destroy()
        if (lines.isEmpty()) null else lines.joinToString("\n").take(MAX_CHARS) + "\n"
    } catch (e: Exception) {
        Log.w(TAG, "could not read logcat unfiltered either", e)
        null
    }
}
