package com.terminalscout.app.prod

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.terminalscout.app.prod.databinding.FragmentHomeBinding
import android.Manifest
import android.content.pm.PackageManager
import com.terminalscout.app.prod.service.FleetBus
import com.terminalscout.app.prod.service.ProcessingLane
import com.terminalscout.app.prod.ui.ErrorLog
import com.terminalscout.app.prod.ui.KioskMode
import com.terminalscout.app.prod.config.DeviceConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The home panel: four slabs, in the order a unit is used.
 *
 * ### Why the home screen is not the capture screen any more
 * `FirstFragment` — the camera preview, the queue, the stage timeline — is a debug tool, and it was
 * the first thing anyone saw. On a deployed unit that is backwards: the operator's whole job is set it
 * up, lock it down, and leave it. So the tools moved behind [MenuFragment] and this took the front,
 * which is also what makes the app usable once it is pinned and the toolbar overflow is unreachable.
 *
 * ### The status line
 * One line, refreshed on every resume, saying whether the thing is actually working. It reads from
 * [AppSetup.run] rather than from a cached value, because the interesting failures — a revoked
 * permission, a service the system killed — happen while this screen is not looking.
 */
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    /**
     * Last setup result. Kept only for the things setup owns and the health check does not — currently
     * nothing on the readout, which now renders entirely from [Stage0Health].
     */
    private var lastStatus: AppSetup.Status? = null

    /** Live from [FleetBus]: true while the camera is actually rolling. */
    private var recording: Boolean = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnSetupDevice.setOnClickListener {
            findNavController().navigate(R.id.VehicleFormFragment)
        }

        binding.btnOpenMenu.setOnClickListener {
            findNavController().navigate(R.id.MenuFragment)
        }

        binding.btnViewErrors.setOnClickListener {
            findNavController().navigate(R.id.ErrorsFragment)
        }

        // The count is what makes moving the list off this screen honest: an error behind a button is
        // hidden; an error behind a button that says how many there are is not. Collected rather than
        // read on resume, so something raised while this screen is open shows straight away.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                ErrorLog.errors.collect { errors -> renderErrorButton(errors.size) }
            }
        }

        // The readout follows the health check, which is the same thing the watchdog repairs from. Any
        // check — the service's 60-second loop, the watchdog alarm, or the poll below — publishes into
        // this flow and the panel repaints. A StateFlow drops duplicate values, so a device sitting
        // healthy repaints nothing at all.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                Stage0Health.state.collect { renderReadout(it) }
            }
        }

        // While somebody is actually looking, ask more often than the background checks do. Every field
        // it reads is a @Volatile flag or an in-memory table — no database, no WorkManager query — so
        // this is close to free, and it is what makes the panel honest in the seconds after a fault
        // rather than up to a minute later.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    context?.let { Stage0Health.check(it) }
                    delay(READOUT_POLL_MS)
                }
            }
        }

        // The camera line is the only value the health check cannot see: recording is a moment, not a
        // state anything polls for, so it still comes from the bus.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                FleetBus.status.collect { bus ->
                    recording = bus.recording
                    renderReadout(Stage0Health.state.value)
                }
            }
        }

        binding.btnKiosk.setOnClickListener {
            (activity as? MainActivity)?.enterKioskMode()
            refresh()
        }

        binding.btnExitDevice.setOnClickListener {
            (activity as? MainActivity)?.confirmExitDevice()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    /**
     * Repaints the status line and the kiosk button.
     *
     * The kiosk button doubles as the indicator — there is no separate light, because a panel with one
     * button and one lamp saying the same thing is a panel with a lamp that can disagree with it.
     */
    private fun refresh() {
        val ctx = context ?: return
        lastStatus = AppSetup.run(ctx)
        // includeJobs: a resume is a good moment to pay for the WorkManager queries, and it means the
        // count is right the instant the panel opens rather than at the next background tick.
        renderReadout(Stage0Health.check(ctx, includeJobs = true))

        binding.btnKiosk.text = getString(
            if (KioskMode.isEnabled(ctx)) R.string.home_exit_kiosk else R.string.home_enter_kiosk
        )
    }

    // There is deliberately no "start capture service" button, and no screen behind one.
    //
    // It implied a decision the operator does not get to make and could not have made correctly: the
    // service holds the location trigger, the stop-recording monitor and the processing queue, and a unit
    // that is powered on is a unit that should be watching. Setup already starts it on every launch once
    // the device info and the location grant are in place, so the button's only real effect was to send a
    // start intent to a service that was already running.
    //
    // Worse, it read as the switch that armed capture — which meant a restart looked like it had disarmed
    // the unit, because nothing came back saying "pressed". Nothing is armed by hand now, so there is no
    // such state to lose. What replaced it is the CAPTURE SERVICE line in the readout below, which
    // reports what the trigger is actually doing rather than what somebody last pressed.

    /**
     * Paints the readout.
     *
     * The camera line goes first because it is the only value that moves on its own; the other three
     * change when somebody does something. Labels are padded to one column so a changed value is
     * visible without reading the labels, which only works because the theme is monospace.
     */
    private fun renderReadout(health: Stage0Health.Report) {
        val ctx = context ?: return
        binding.homeStatus.text = buildString {
            appendLine("CAMERA CAPTURE      : " + cameraState(ctx, health))
            appendLine("KIOSK STATUS        : " + if (health.kioskEnabled) "ON" else "OFF")
            appendLine("GPS                 : " + if (health.gpsEnabled) "ON" else "OFF")
            appendLine("BACKGROUND SERVICES : ${health.servicesUp}/${health.jobsTotal}")
            appendLine("CAPTURE SERVICE     : " + captureServiceState(health))
            append("VIDEO PROCESSING    : " + videoProcessingState(health))
        }
    }

    /**
     * The capture service, reported through the location trigger — the part of it that decides whether
     * anything is ever recorded.
     *
     * The station count rides along rather than getting its own line: on its own it said how many
     * forecourts were mapped, which is only interesting as an input to this. "WATCHING 349" answers
     * both, and "MAP EMPTY" is the case where the count mattered.
     *
     * Ordered so the first true statement is the most actionable: a stopped service is a different
     * problem from an attached trigger with nothing to compare against.
     */
    private fun captureServiceState(health: Stage0Health.Report): String = when {
        !health.serviceRunning -> "STOPPED"
        !health.triggerAttached -> "NOT WATCHING"
        // The stop-recording watch being off is not visible anywhere else, and it is not harmless: a
        // captured board is never released, so every station becomes capturable once and then silently
        // stops. It reads as a distinct state rather than hiding inside WATCHING.
        !health.stopMonitorWatching -> "NO STOP WATCH"
        health.stationsMapped == 0 -> "MAP EMPTY"
        else -> "WATCHING ${health.stationsMapped}"
    }

    /**
     * What Stage 1's poller is doing.
     *
     * Its own line rather than a sixth slot in BACKGROUND SERVICES, which counts things Android
     * schedules or hosts — four WorkManager jobs and one foreground service. The poller is a coroutine
     * inside that service, so folding it into the fraction would make `5/6` mean either "the service is
     * dead" or "one loop inside it stalled": two very different faults behind one number.
     *
     * Four states, because "running" is not one thing. A poller that is idle is a healthy unit with
     * nothing to do; a poller refusing to process for lack of a connection is a unit accumulating
     * unread footage. Collapsing those would hide the one somebody has to act on.
     */
    private fun videoProcessingState(health: Stage0Health.Report): String = when {
        !health.serviceRunning -> "SERVICE DOWN"
        else -> when (health.pollState) {
            ProcessingLane.PollState.STOPPED -> "STOPPED"
            ProcessingLane.PollState.PROCESSING -> "RUNNING A CLIP"
            ProcessingLane.PollState.OFFLINE_HELD -> "HELD — OFFLINE"
            ProcessingLane.PollState.POLLING -> "POLLING"
        }
    }

    /**
     * What the camera is doing, in the order the answers matter.
     *
     * "Not recording" is three different situations and they need different words: no permission is a
     * setup fault, service down means nothing can capture at all, and idle means everything is ready
     * and no forecourt is in range. Collapsing them into one "OFF" would hide the first two, which are
     * the ones somebody has to act on.
     */
    private fun cameraState(ctx: android.content.Context, health: Stage0Health.Report): String = when {
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED -> "NO PERMISSION"
        !health.serviceRunning -> "SERVICE DOWN"
        recording -> "RECORDING"
        else -> "IDLE"
    }

    /**
     * Puts the count on the button and turns it red when there is anything to see.
     *
     * Set in code rather than by swapping styles: a MaterialButton takes its stroke and text colour
     * from the style it was inflated with, so switching styles at runtime means re-inflating the view.
     */
    private companion object {
        /**
         * How often the panel re-reads the health flags while it is on screen.
         *
         * Two seconds because the readout is the only thing an operator standing at the truck can use to
         * tell whether the unit is working, and a status line that is thirty seconds stale is a status
         * line that lies. It is affordable only because every field behind it is an in-memory flag.
         */
        val READOUT_POLL_MS: Long get() = DeviceConfig.long("diagnostics.readout.poll_ms", 2_000L)
    }

    private fun renderErrorButton(count: Int) {
        val ctx = context ?: return
        val label = getString(R.string.home_view_errors)
        binding.btnViewErrors.text = if (count == 0) label else "$label  [$count]"

        val on = count > 0
        binding.btnViewErrors.setTextColor(
            ContextCompat.getColor(ctx, if (on) R.color.ts_red else R.color.ts_text)
        )
        binding.btnViewErrors.strokeColor = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(ctx, if (on) R.color.ts_red else R.color.ts_border_strong)
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
