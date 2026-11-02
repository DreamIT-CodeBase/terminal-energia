package com.terminalscout.app.prod

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.snackbar.Snackbar
import com.terminalscout.app.prod.capture.LocationMap
import com.terminalscout.app.prod.capture.LocationMapRefresh
import com.terminalscout.app.prod.config.DeviceConfig
import com.terminalscout.app.prod.databinding.FragmentMenuBinding
import com.terminalscout.app.prod.pipeline.DayNight
import com.terminalscout.app.prod.pipeline.LightPhase
import com.terminalscout.app.prod.upload.AzureMapsStore
import com.terminalscout.app.prod.download.DeviceApi
import com.terminalscout.app.prod.upload.AzureMapsTimezone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The toolbar overflow, as slabs.
 *
 * ### Why duplicate the overflow menu
 * Two reasons, and the second is the real one. An overflow menu is a small target for someone in a
 * vehicle, possibly gloved. And once the app is pinned there is no toolbar to reach — screen pinning
 * takes the system bars, so every action behind that menu becomes unreachable exactly when the device
 * is in the state it spends its life in.
 *
 * The overflow is kept as well rather than removed: it is the faster path at a desk, and the two share
 * their handlers on [MainActivity] so they cannot drift apart.
 *
 * The on-device capture screen used to sit at the top of this list. It is gone from the production
 * build — a deployed unit has no operator to watch a preview, and the manual start/stop it carried is
 * still reachable over adb through FleetService's exported intents.
 */
class MenuFragment : Fragment() {

    private var _binding: FragmentMenuBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMenuBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val host = activity as? MainActivity

        // Straight to the screen, no confirmation: it reads and changes nothing.
        binding.btnCaptureStatus.setOnClickListener {
            findNavController().navigate(R.id.CaptureStatusFragment)
        }

        binding.btnUploadResults.setOnClickListener { host?.confirmUploadResults() }
        binding.btnCleanMemory.setOnClickListener { host?.confirmCleanMemory() }

        // The image-filter switch. RUN VALIDATION and two APPLY FILTER buttons used to sit here; the
        // validation harness is gone for good — it is not something a production unit runs — and the
        // filter override went with it, because it pinned Stage 3's branch for every capture from a tap
        // nobody would remember making.
        //
        // It is back by request (2026-09-11) as two mutually exclusive checkboxes, and it IS sticky
        // again, deliberately: a field test wants the phase held across the restarts a watchdog causes.
        //
        // Two things make that safe enough to have. Both boxes off means AUTO and is the default, so an
        // untouched unit still decides per capture from the sun times and the abnormal state is the one
        // somebody had to tick. And the screen states the state: the old APPLY DAY / APPLY NIGHT buttons
        // said what a tap would do and never what the unit was set to, which is exactly why a wrong
        // setting used to go unnoticed.
        bindFilterSwitches()

        // The two overnight refreshes, on demand. No confirmation dialog on either: both are read-only
        // fetches that cannot destroy what is already held, so a dialog would only be a tap in the way.
        binding.btnRefreshSunTimes.setOnClickListener { refreshSunTimes() }
        binding.btnRefreshMap.setOnClickListener { refreshStationMap() }
        binding.btnRefreshConfig.setOnClickListener { refreshConfig() }
    }

    /**
     * Re-read on every resume, not just on create.
     *
     * The values behind these readouts are written by background workers at 02:00 and 03:00 and by the
     * catch-up one-shots at any hour, so the screen can go stale while it is merely backgrounded — and
     * this fragment survives that without being recreated.
     */
    override fun onResume() {
        super.onResume()
        renderSunTimes()
        renderStationMap()
    }

    /**
     * Renders the two filter checkboxes from the stored setting and writes any change straight back.
     *
     * ### Three states, two boxes
     * DAY and NIGHT are mutually exclusive and both-off means AUTO, so ticking one unticks the other and
     * unticking the ticked one returns to AUTO. The status line underneath spells out which of the three
     * is in force, because two unticked boxes look identical to a screen nobody has set up yet — and
     * "nobody has set this up" and "this is deliberately automatic" are the same state here, so it has to
     * say so in words.
     *
     * Read on every bind rather than cached: the setting outlives this fragment and persists across
     * restarts. Listeners are detached before the checked state is applied, so `isChecked = …` cannot
     * fire a handler and write back the value it has just read, or bounce between the two boxes.
     */
    private fun bindFilterSwitches() {
        val ctx = requireContext().applicationContext

        fun render(forced: LightPhase?) {
            binding.chkDayFilter.setOnCheckedChangeListener(null)
            binding.chkNightFilter.setOnCheckedChangeListener(null)

            binding.chkDayFilter.isChecked = forced == LightPhase.DAY
            binding.chkNightFilter.isChecked = forced == LightPhase.NIGHT
            binding.txtFilterStatus.setText(
                when (forced) {
                    LightPhase.DAY -> R.string.filter_status_day
                    LightPhase.NIGHT -> R.string.filter_status_night
                    null -> R.string.filter_status_auto
                }
            )

            // One handler shape for both boxes: ticking selects that phase, unticking the ticked box
            // falls back to AUTO. The other box is never touched directly — `render` redraws both from
            // the single stored value, so the two can never disagree about which is on.
            fun onToggled(phase: LightPhase, checked: Boolean) {
                val next = if (checked) phase else null
                // A second guard behind `saveEnabled="false"`: anything that re-asserts the state we
                // are already in is not a user action and must not be written. Belt and braces, because
                // the failure this prevents is a WRITE LOOP — the listener writing a value that causes
                // a re-render that fires the listener — and that spins the disk and the log forever
                // rather than failing visibly once.
                if (next == forced) return
                DayNight.setSelectedPhase(ctx, next)
                render(next)
            }
            binding.chkDayFilter.setOnCheckedChangeListener { _, c -> onToggled(LightPhase.DAY, c) }
            binding.chkNightFilter.setOnCheckedChangeListener { _, c -> onToggled(LightPhase.NIGHT, c) }
        }

        render(DayNight.selectedPhase(ctx))
    }

    // ---- reference data: the station map and the sun times --------------------------------------- //
    //
    // Both readouts describe a file or table that a nightly job owns, and neither button is the only way
    // to get that data — they exist because a field tester should not have to wait until 02:00, or read
    // logcat, to find out what a unit is holding. The refreshes run in the view's lifecycle scope, so
    // navigating away cancels the coroutine; nothing is left half-written by that, because both fetches
    // write only at the very end and through code that refuses an answer it does not believe.

    /**
     * The sun times as the app holds them — the boundary Stage 3's day/night branch is decided on.
     *
     * Times only. The provenance line that used to sit under this — which service day the answer
     * belongs to, when it was fetched, for which coordinate — was cut on 2026-09-18 to keep the panel
     * readable. It is not lost: every one of those fields is in the `AzureMapsStore` log line and in the
     * run's own artifacts.
     *
     * What that costs is worth stating, because it is the one thing this readout no longer says. Stale
     * times and today's now look identical here, and [DayNight] reads [AzureMapsStore.stored] rather
     * than `current` — so when a refresh has not landed, yesterday's times ARE what a capture is judged
     * against, and this screen will show them without a word. Tapping REFRESH SUN TIMES and reading the
     * message is how you tell the difference now.
     */
    private fun renderSunTimes() {
        // Cheap and idempotent, and this screen can be the first thing to ask: the store resolves its
        // path in `init` and answers null-with-a-warning before that has happened.
        AzureMapsStore.init(requireContext().applicationContext)
        val stored = AzureMapsStore.stored()

        if (stored == null) {
            binding.txtSunTimes.setText(R.string.sun_times_none)
            return
        }

        val rise = stored.sunriseAtMs?.let(::clock)
        val set = stored.sunsetAtMs?.let(::clock)
        binding.txtSunTimes.text = if (rise == null || set == null) {
            // A stored document whose times would not parse: worth stating, because everything
            // downstream of it falls back rather than failing, and does so silently.
            getString(R.string.sun_times_unreadable)
        } else {
            getString(R.string.sun_times, rise, set)
        }
    }

    /**
     * Forces a fetch — `force = true`, which is what makes this button do anything at all.
     *
     * [AzureMapsStore.ensureFresh] is a no-op while the stored file is today's, and throttled for
     * [AzureMapsStore.FAILED_RETRY_MS] after a failure. Both are right for the 03:00 job and both would
     * leave a manual REFRESH sitting there doing nothing visible. Forcing is safe here precisely because
     * it takes a deliberate tap: nothing calls this on a timer.
     */
    private fun refreshSunTimes() {
        val ctx = requireContext().applicationContext
        binding.btnRefreshSunTimes.isEnabled = false
        binding.btnRefreshSunTimes.setText(R.string.data_working)

        viewLifecycleOwner.lifecycleScope.launch {
            val beforeMs = AzureMapsStore.stored()?.fetchedAtMs ?: 0L
            val stored = withContext(Dispatchers.IO) {
                runCatching { AzureMapsStore.ensureFresh(ctx, force = true) }
                    .onFailure { Log.e(TAG, "sun-times refresh threw", it) }
                    .getOrNull()
            }

            binding.btnRefreshSunTimes.isEnabled = true
            binding.btnRefreshSunTimes.setText(R.string.action_refresh_sun_times)
            renderSunTimes()

            // `ensureFresh` hands back what is on disk when a fetch fails, so a non-null answer is not
            // proof of a fetch. The timestamp is: it only moves when a new document was written.
            val fetched = stored != null && stored.fetchedAtMs > beforeMs
            say(
                when {
                    fetched -> getString(
                        R.string.sun_times_refreshed,
                        stored?.sunriseAtMs?.let(::clock) ?: UNKNOWN,
                        stored?.sunsetAtMs?.let(::clock) ?: UNKNOWN
                    )
                    !AzureMapsTimezone.isConfigured -> getString(R.string.sun_times_no_key)
                    else -> getString(
                        R.string.sun_times_failed,
                        AzureMapsStore.lastError ?: getString(R.string.no_reason_given)
                    )
                }
            )
        }
    }

    /**
     * How many stations the location trigger can currently fire on.
     *
     * The sweep date came off on 2026-09-18 with the other provenance. An empty table keeps its own
     * message, because zero stations is not a quieter version of 385 — it means the trigger cannot fire
     * at all, and that has to read as a fault rather than as a count.
     */
    private fun renderStationMap() {
        val ctx = requireContext().applicationContext
        val count = LocationMap.count(ctx)
        binding.txtStationMap.text =
            if (count == 0) getString(R.string.station_map_none)
            else getString(R.string.station_map, count)
    }

    /** Runs the same API-backed sync as the 02:00 job — see [LocationMapRefresh]. */
    private fun refreshStationMap() {
        val ctx = requireContext().applicationContext
        binding.btnRefreshMap.isEnabled = false
        binding.btnRefreshMap.setText(R.string.data_working)

        viewLifecycleOwner.lifecycleScope.launch {
            val outcome = runCatching { LocationMapRefresh.runOnce(ctx) }
                .onFailure { Log.e(TAG, "station-map refresh threw", it) }
                .getOrNull()

            binding.btnRefreshMap.isEnabled = true
            binding.btnRefreshMap.setText(R.string.action_refresh_map)
            renderStationMap()

            say(
                when (outcome) {
                    is LocationMapRefresh.Outcome.Refreshed -> getString(
                        R.string.station_map_refreshed,
                        outcome.swept, outcome.inserted, outcome.updated, outcome.removed, outcome.total
                    )
                    LocationMapRefresh.Outcome.NotConfigured -> getString(R.string.station_map_no_key)
                    LocationMapRefresh.Outcome.EmptySweep -> getString(R.string.station_map_empty)
                    // A transport failure, a write that would not land, or a thrown exception. One
                    // message for all three: they differ in the log, and on the panel they are the same
                    // sentence — it did not land, what you had is still there.
                    else -> getString(R.string.station_map_failed)
                }
            )
        }
    }

    /**
     * Fetches `GET /v1/config` and stores it. **It does not apply it** — that is
     * [DeviceConfig.refresh]'s contract, not a limitation of this button: a document that took effect
     * mid-run would let one stage read one value and the next stage another, and no artifact would
     * describe what actually ran. The snackbar says so rather than letting a tap imply otherwise.
     *
     * There is no readout beside it to re-render: what document is in force is a question for the log,
     * and the snackbar is where this button says what it did.
     */
    private fun refreshConfig() {
        val ctx = requireContext().applicationContext
        binding.btnRefreshConfig.isEnabled = false
        binding.btnRefreshConfig.setText(R.string.data_working)

        viewLifecycleOwner.lifecycleScope.launch {
            val stored = withContext(Dispatchers.IO) {
                runCatching { DeviceConfig.refresh(ctx) }
                    .onFailure { Log.e(TAG, "config refresh threw", it) }
                    .getOrDefault(false)
            }

            binding.btnRefreshConfig.isEnabled = true
            binding.btnRefreshConfig.setText(R.string.action_refresh_config)

            say(
                when {
                    stored -> getString(R.string.config_refreshed)
                    // A document can also be refused for failing `DeviceConfig.validate`, which is a
                    // server-side mistake rather than a device problem. One message either way: both
                    // mean "what you are running did not change", and the log says which.
                    !DeviceApi.isConfigured -> getString(R.string.config_no_key)
                    else -> getString(R.string.config_refresh_failed)
                }
            )
        }
    }

    /**
     * Tallinn wall-clock HH:mm. The zone is the fleet's, never the device's — a phone left on a foreign
     * timezone would otherwise print sun times that disagree with the ones Stage 3 branches on.
     */
    private fun clock(atMs: Long): String =
        SimpleDateFormat("HH:mm", Locale.US)
            .apply { timeZone = AzureMapsStore.ZONE }
            .format(Date(atMs))

    /**
     * A Snackbar, but only while there is still a view to hang it on.
     *
     * `maxLines` is raised from Material's default of 2: these messages carry four counts and a clause
     * explaining the gap between two of them, and at this font size two lines cut it off mid-number —
     * which is worse than not saying it, because a truncated count still reads as a count.
     */
    private fun say(message: String) {
        val root = _binding?.root ?: return
        val bar = Snackbar.make(root, message, Snackbar.LENGTH_LONG)
        bar.view.findViewById<android.widget.TextView>(
            com.google.android.material.R.id.snackbar_text
        )?.maxLines = 5
        bar.show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private companion object {
        const val TAG = "MenuFragment"
        const val UNKNOWN = "?"
    }
}
