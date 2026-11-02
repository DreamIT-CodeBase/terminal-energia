package com.terminalscout.app.prod

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.terminalscout.app.prod.databinding.FragmentErrorsBinding
import com.terminalscout.app.prod.ui.AppError
import com.terminalscout.app.prod.ui.ErrorFix
import com.terminalscout.app.prod.ui.ErrorLog
import com.terminalscout.app.prod.ui.ErrorLogAdapter
import kotlinx.coroutines.launch

/**
 * The error log, on its own screen.
 *
 * ### Why it stopped being a banner
 * It used to hover over every screen. That worked while there were two entries and stopped working as
 * more of the app learned to report into it: four already covered a third of the display, and the list
 * only grows as more subsystems are wired up. A banner that large is not a warning, it is an
 * obstruction — so the list moved here and the home panel carries the count instead.
 *
 * The count is what makes that trade honest. Moving the errors behind a button would hide them; a
 * button that says how many there are does not.
 *
 * ### Rows are still the fix
 * Tapping a row opens the screen that resolves *that* problem, exactly as the banner did — a battery
 * exemption and a missing permission live behind different intents, so a single "open settings" would
 * be wrong for half of them.
 */
class ErrorsFragment : Fragment() {

    private var _binding: FragmentErrorsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentErrorsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // goHome rather than popBackStack, so the button does what it says however this screen was
        // reached — including from a row tap that went out to Settings and came back.
        binding.btnBackHome.setOnClickListener { (activity as? MainActivity)?.goHome() }

        val adapter = ErrorLogAdapter { error -> openFixFor(error) }
        binding.errorsList.layoutManager = LinearLayoutManager(requireContext())
        binding.errorsList.adapter = adapter

        // Live rather than a snapshot: an error can clear while this screen is open — the user goes to
        // Settings, grants the thing, comes back — and a stale list would send them to fix it twice.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                ErrorLog.errors.collect { errors ->
                    // Newest first. Sorted on the timestamp rather than reversing the list, so the
                    // order is a stated intention and survives any future change to how the store
                    // appends. ErrorLog itself keeps insertion order, which is what the count reads.
                    adapter.submit(errors.sortedByDescending { it.atMs })
                    binding.errorsBanner.text = if (errors.isEmpty()) {
                        getString(R.string.errors_none_banner)
                    } else {
                        resources.getQuantityString(R.plurals.errors_count, errors.size, errors.size)
                    }
                    val empty = errors.isEmpty()
                    binding.errorsEmpty.visibility = if (empty) View.VISIBLE else View.GONE
                    binding.errorsList.visibility = if (empty) View.GONE else View.VISIBLE
                    // The hint tells you to tap a row; with no rows it is instruction for nothing.
                    binding.errorsHint.visibility = if (empty) View.GONE else View.VISIBLE
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-checks every condition and clears whatever has been fixed, so simply opening this screen
        // brings the list up to date rather than showing what was true when the error was raised.
        AppSetup.run(requireContext())
    }

    /**
     * Opens whatever resolves this row, when anything does.
     *
     * Most of the log has no fix — a failed map fetch, a camera that would not bind — and those rows
     * say so rather than opening a screen that cannot help. The previous version sent them all to a
     * support mailbox, which made every unfixable fault look like it had an action.
     */
    private fun openFixFor(error: AppError) {
        val ctx = context ?: return
        val fix = error.known?.fix
        if (fix == null) {
            Toast.makeText(ctx, R.string.errors_no_fix, Toast.LENGTH_SHORT).show()
            return
        }
        when (fix) {
            ErrorFix.APP_PERMISSIONS -> AppSetup.openPermissionSettings(ctx)
            ErrorFix.BATTERY_OPTIMISATION -> AppSetup.requestIgnoreBatteryOptimizations(ctx)
            ErrorFix.OVERLAY_PERMISSION -> AppSetup.requestOverlayPermission(ctx)
            ErrorFix.LOCATION_SETTINGS -> openSettings(ctx, Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            ErrorFix.STORAGE_SETTINGS -> openSettings(ctx, Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
            ErrorFix.DEVICE_INFO -> findNavController().navigate(R.id.VehicleFormFragment)
            ErrorFix.EXACT_ALARMS -> AppSetup.requestExactAlarmPermission(ctx)
        }
    }

    /** Opens a system settings screen, falling back to the app's own page if the device lacks it. */
    private fun openSettings(ctx: android.content.Context, action: String) {
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(intent) }
            .onFailure { AppSetup.openPermissionSettings(ctx) }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
