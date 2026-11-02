package com.terminalscout.app.prod.pipeline

import android.util.Log

/**
 * A human-readable trace of the consolidator agent's reasoning, built up as the agent runs and shown
 * on the debug dashboard so a tester can see HOW it decided: which steps ran, which tools were
 * called and what facts they returned, when the generator proposed, and when the harness fired a
 * wall to commit or recapture.
 *
 * This is a diagnostics side-channel only — it never influences the agent's output. Each line is also
 * mirrored to Logcat (tag "AgentTrace"), and — if [onLine] is set — pushed out live as it's produced
 * so the UI can STREAM the reasoning as the agent runs rather than showing it all at the end.
 */
class AgentTrace(private val onLine: ((String) -> Unit)? = null) {
    private val sb = StringBuilder()

    /** A top-level step / phase header, e.g. "GROUPING", "TOOLS", "GENERATOR", "HARNESS". */
    fun phase(title: String) = line("\n▶ $title")

    /** A TOOL call and the FACT it returned. */
    fun tool(name: String, fact: String) = line("   🔧 $name → $fact")

    /** A SKILL step and its result. */
    fun skill(name: String, detail: String) = line("   ⚙ $name → $detail")

    /** A GENERATOR (model) proposal. */
    fun generator(detail: String) = line("   🧠 $detail")

    /** A WALL firing (enforcement) and its verdict. */
    fun wall(name: String, verdict: String) = line("   🧱 $name: $verdict")

    /** The harness COMMITTING a value. */
    fun commit(detail: String) = line("   ✅ COMMIT $detail")

    /** The harness ordering a RECAPTURE (honest uncertainty — never a guess). */
    fun recapture(detail: String) = line("   ⛔ RECAPTURE — $detail")

    /** A plain sub-note under the current phase. */
    fun note(detail: String) = line("   $detail")

    private fun line(s: String) {
        sb.append(s).append('\n')
        Log.d(TAG, s.trim())
        onLine?.invoke(s)   // stream this line to the UI as it's produced
    }

    fun text(): String = sb.toString().trim().ifEmpty { "(no reasoning recorded)" }

    companion object {
        private const val TAG = "AgentTrace"
    }
}
