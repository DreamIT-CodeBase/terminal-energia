package com.terminalscout.app.prod

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.terminalscout.app.prod.databinding.FragmentVehicleFormBinding
import com.terminalscout.app.prod.upload.UserStore
import com.terminalscout.app.prod.upload.VehicleConfig

/**
 * First screen of the flow: the vehicle/device details the pipeline can't infer. Shown every time —
 * empty the first time and prefilled from the SQLite `user` table ([UserStore]) once details exist
 * (there's no separate "create" step; the same form inserts-or-updates the single user row).
 *
 * Location is REQUIRED: every reading is stamped with a GPS fix, so tapping "Proceed" persists the
 * details and then requests the location permission — the flow only advances to the download screen
 * once location access is granted.
 */
class VehicleFormFragment : Fragment() {

    private var _binding: FragmentVehicleFormBinding? = null
    private val binding get() = _binding!!

    private lateinit var store: UserStore

    private companion object {
        /** Shown under this screen's rows on the errors screen. */
        const val SOURCE = "Device details"
    }

    // Registered at construction (required by the Activity Result API). Location is mandatory, so we
    // only advance once it's granted.
    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            ) {
                // Nothing to advance to: the save already happened and this screen has returned home.
                // The grant matters to the pipeline, not to this flow.
            } else {
                Toast.makeText(
                    requireContext(),
                    "Location permission is required to record fuel-price readings.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentVehicleFormBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        store = UserStore(requireContext())

        // Device ID is read from the device itself and shown read-only — never user-editable.
        // Both reads guarded, and independently. `UserStore.load` opens `user.db` and resolves its
        // columns with `getColumnIndexOrThrow`, so a corrupt file or a schema that does not match throws
        // here — in `onViewCreated`, with no frame of ours above it, which is a crash of the screen.
        //
        // That matters more on this screen than on any other: the capture service is held until device
        // details exist, so this form is the only way to clear that hold. A `user.db` bad enough to
        // crash the prefill would make the unit unrecordable AND make the one repair for it unusable.
        // An empty field is a perfectly good starting point; a dead screen is not.
        crashproof(SOURCE, ContainedFaults.DEVICE_DETAILS, "onViewCreated(deviceId)") {
            binding.inputDeviceId.setText(store.deviceId())
        }
        // Prefill the editable fields from SQLite if the user has saved details before.
        crashproof(SOURCE, ContainedFaults.DEVICE_DETAILS, "onViewCreated(prefill)") {
            store.load()?.let { current ->
                binding.inputVehicleId.setText(current.vehicleId)
            }
        }

        binding.buttonSaveConfig.setOnClickListener {
            // Only the vehicle id (plus the device-derived device id) is captured on this screen.
            //
            // Guarded, and the result is CHECKED rather than discarded. A click listener that throws is
            // a crash, so containing it is not optional — but going home afterwards as though the write
            // had happened would be worse than the crash: the operator would leave believing the unit
            // is set up, the capture service would stay held, and nothing on the way out would say so.
            // So a failed save stays on this screen and says what to read.
            val saved = crashproofOr(SOURCE, ContainedFaults.DEVICE_DETAILS, "saveConfig", false) {
                store.save(
                    VehicleConfig(
                        deviceId = store.deviceId(),
                        vehicleId = binding.inputVehicleId.text?.toString().orEmpty()
                    )
                )
                true
            }
            if (!saved) {
                Toast.makeText(
                    requireContext(),
                    "The device details could not be saved. Open VIEW ERRORS for the detail.",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }
            // Location is still asked for here — a reading with no GPS fix cannot be attributed to a
            // station — but it no longer gates anything: AppSetup owns the permission chain now, and
            // this screen's job ends at saving. Asking is a convenience, not a checkpoint.
            //
            // Guarded for that reason: a device that cannot show the dialog must not cost the save that
            // has already succeeded, which is the whole point of this screen.
            crashproof(SOURCE, ContainedFaults.DEVICE_DETAILS, "requestLocation") {
                if (!hasLocationPermission()) {
                    locationPermissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                }
            }
            onSaved()
        }
    }

    // Fails CLOSED — a read that threw reports "not granted", so the worst case is asking for a
    // permission that is already held, which shows no dialog and costs nothing.
    private fun hasLocationPermission(): Boolean =
        crashproofOr(SOURCE, ContainedFaults.DEVICE_DETAILS, "hasLocationPermission", false) {
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
        }

    /**
     * Confirms the write and returns to the home panel.
     *
     * It used to advance to the model-download screen, which was the next step when this form was the
     * app's start destination. It is now one of five things reachable from home, so "next" is home —
     * and the home readout is where the effect of saving shows up: the capture service is held until
     * device details exist, so SERVICE flips from held to up on the way back.
     */
    private fun onSaved() {
        // Three steps, guarded together: the write has already succeeded by the time this runs, so
        // anything that fails from here is cosmetic — a toast that will not show, a setup pass that
        // will report its own faults, a navigation the operator can repeat with the back gesture. None
        // of it is worth a crash on the screen that has just fixed the device's identity.
        crashproof(SOURCE, ContainedFaults.DEVICE_DETAILS, "onSaved") {
            Toast.makeText(requireContext(), R.string.vehicle_saved, Toast.LENGTH_SHORT).show()
            // Re-run setup so the held service starts and the error clears without waiting for a
            // relaunch.
            AppSetup.run(requireContext())
            // goHome rather than popBackStack: this screen is also reachable from the errors list, and
            // popping there would drop the operator back onto the error that sent them here.
            (activity as? MainActivity)?.goHome()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
