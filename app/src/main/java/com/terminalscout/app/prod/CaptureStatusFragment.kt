package com.terminalscout.app.prod

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.terminalscout.app.prod.databinding.FragmentCaptureStatusBinding
import com.terminalscout.app.prod.service.FleetBus
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * CAPTURE STATUS — what the unit is pointed at, and what it is holding.
 *
 * ### Why there is no camera preview here
 * The obvious version of this screen shows the video. This deliberately does not, for two reasons.
 * Attaching a preview binds the camera for as long as the screen is open, which is heat on a device
 * whose runs have been killed by heat. And it answers the wrong question: a picture tells you the lens
 * is working, whereas what an operator standing at a truck needs to know is *which board the recorder
 * is committed to* and *how much the queue is costing* — both of which are text.
 *
 * ### It reads the bus and nothing else
 * Every value comes from [FleetBus], which the service publishes into. Nothing here touches the lane,
 * the queue store or the files on disk — the size of each clip rides on the row precisely so a screen
 * never has to stat a file the service owns.
 *
 * ### The only thing it polls
 * A one-second tick, and only to advance the ROLLING counter, which is derived from a timestamp rather
 * than pushed. Everything else repaints when the bus changes and not otherwise.
 */
class CaptureStatusFragment : Fragment() {

    private var _binding: FragmentCaptureStatusBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCaptureStatusBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnBackHome.setOnClickListener { (activity as? MainActivity)?.goHome() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                FleetBus.activeCapture.collect { renderActive(it) }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                FleetBus.queue.collect { renderQueue(it) }
            }
        }

        // The ROLLING counter is the one value nothing publishes: it is the difference between now and
        // a timestamp, so it has to be advanced rather than awaited.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    FleetBus.activeCapture.value?.let { renderActive(it) }
                    delay(1_000L)
                }
            }
        }
    }

    /** The board the recorder is committed to, or a plain statement that there isn't one. */
    private fun renderActive(active: FleetBus.ActiveCapture?) {
        val view = _binding ?: return
        if (active == null) {
            view.nowCapturing.text = getString(R.string.capture_status_idle)
            return
        }
        val seconds = ((System.currentTimeMillis() - active.startedAtMs) / 1000).coerceAtLeast(0)
        view.nowCapturing.text = buildString {
            appendLine("BOARD     : " + active.stationName)
            // A manual capture has no board, so it has no position. Printing "0.000000" would be a
            // coordinate in the Gulf of Guinea rather than an absence.
            appendLine("LATITUDE  : " + (active.latitude?.let { "%.6f".format(Locale.US, it) } ?: "—"))
            appendLine("LONGITUDE : " + (active.longitude?.let { "%.6f".format(Locale.US, it) } ?: "—"))
            appendLine("TRIGGER   : " + active.trigger.wireName)
            appendLine("ROLLING   : ${seconds}s")
            append("REASON    : " + active.reason)
        }
    }

    /** What the queue holds and what it costs. */
    private fun renderQueue(rows: List<FleetBus.QueueRow>) {
        val view = _binding ?: return
        val bytes = rows.sumOf { it.bytes }
        val running = rows.firstOrNull { it.processing }

        // No DONE and no FAILED any more. A finished clip is deleted, so "done" is the absence of a row;
        // a failed clip stays on disk and is retried, so it is indistinguishable from a waiting one.
        // Showing either would be inventing a state the disk cannot report.
        view.queueSummary.text = buildString {
            appendLine("VIDEOS    : ${rows.size}")
            appendLine("ON DISK   : " + humanSize(bytes))
            appendLine("WAITING   : ${rows.size - (if (running != null) 1 else 0)}")
            append("PROCESSING: " + (running?.name ?: getString(R.string.capture_status_polling)))
        }

        view.queueRows.text = if (rows.isEmpty()) {
            getString(R.string.capture_status_queue_empty)
        } else {
            rows.joinToString("\n") { r ->
                "%-24s %-9s %8s".format(
                    Locale.US,
                    r.name.take(24),
                    if (r.processing) "RUNNING" else "WAITING",
                    humanSize(r.bytes)
                )
            }
        }
    }

    /**
     * Bytes as something an operator can act on.
     *
     * MB below a gigabyte and GB above it, because the numbers that matter here span three orders of
     * magnitude — a single clip is tens of MB and a queue that is about to stop the unit recording is
     * tens of GB, and one unit cannot show both readably.
     */
    private fun humanSize(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> "%.2f GB".format(Locale.US, bytes / 1_000_000_000.0)
        bytes >= 1_000_000L -> "%d MB".format(Locale.US, bytes / 1_000_000L)
        bytes > 0L -> "%d KB".format(Locale.US, bytes / 1_000L)
        else -> "—"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
