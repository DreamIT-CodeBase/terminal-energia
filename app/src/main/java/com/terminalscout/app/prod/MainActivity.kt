package com.terminalscout.app.prod

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.appcompat.app.AlertDialog
import com.google.android.material.snackbar.Snackbar
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.navigation.NavOptions
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import com.terminalscout.app.prod.databinding.ActivityMainBinding
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.work.WorkManager
import com.terminalscout.app.prod.ui.ErrorLog
import com.terminalscout.app.prod.ui.KioskMode
import kotlinx.coroutines.launch
import com.terminalscout.app.prod.download.DebugUploadOutbox
import com.terminalscout.app.prod.download.DebugUploadScheduler
import com.terminalscout.app.prod.service.FleetService
import com.terminalscout.app.prod.upload.UploadScheduler

class MainActivity : AppCompatActivity() {

    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Guarded, and what survives a failure here is the part that matters: the capture service does
        // not live in this Activity. A layout that will not inflate leaves a blank panel and a unit that
        // still records, where a throw leaves a crash — and the watchdog would relaunch this same
        // Activity five seconds later and crash again, which is the loop the ten-attempt guard exists
        // to stop rather than a state to accept.
        crashproof(SOURCE, ContainedFaults.ACTIVITY, "onCreate") {
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)

            setSupportActionBar(binding.toolbar)

            val navController = findNavController(R.id.nav_host_fragment_content_main)
            appBarConfiguration = AppBarConfiguration(navController.graph)
            setupActionBarWithNavController(navController, appBarConfiguration)
            binding.toolbar.subtitle = "v${BuildConfig.VERSION_NAME}"
        }

        // Setup is NOT run from here. Everything startup needs — tables, schedulers, the initial map
        // fetch, the capture service — lives in AppSetup and is idempotent, and it is driven from
        // `onResume` instead: three of the four permission prompts are settings screens, so the user
        // leaves the app and comes back, and that return is the only signal there is. `onCreate` fires
        // once and would miss every one of them.
    }

    /**
     * Asks for the permissions Stage 0 needs, then re-runs setup with the answer.
     *
     * Registered as a field so it exists before `onCreate` returns, which the Activity Result API
     * requires. The result is not inspected for granted/denied: [AppSetup.run] re-reads the real
     * permission state, which is the only thing that is actually true — a user can grant one of three
     * and revoke another from the notification shade while the dialog is open.
     */
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { afterPermissionAnswer() }

    /**
     * The setup pass. Runs the non-UI work, then closes whichever gap is next in order.
     *
     * Ordered deliberately: the ordinary permissions first, and background location only once the
     * foreground grant is held — Android 11+ denies a background request outright if it arrives before
     * or alongside the foreground one, with no dialog shown at all.
     */
    /**
     * Returns to the home panel, clearing whatever is stacked above it.
     *
     * `popUpTo(HomeFragment, inclusive = true)` followed by navigating to it leaves exactly one home
     * entry on the stack, rather than a second one on top of the first — which is what a plain
     * `navigate` would do and what makes Back appear to do nothing.
     *
     * Every finished action funnels through here: clean memory, saving details, uploading, and leaving
     * the errors screen. They are all things you do once and then step away from, so ending on the
     * screen that launched them would leave the operator to find their own way back.
     */
    internal fun goHome() {
        // Guarded: `navigate` throws when the graph does not hold the destination — which is what a
        // failed `onCreate` leaves behind — and every finished action funnels through here from a click
        // listener, where a throw is a crash of the screen the operator is standing in front of.
        crashproof(SOURCE, ContainedFaults.ACTIVITY, "goHome") {
            val nav = findNavController(R.id.nav_host_fragment_content_main)
            if (nav.currentDestination?.id == R.id.HomeFragment) return@crashproof
            nav.navigate(
                R.id.HomeFragment,
                null,
                NavOptions.Builder().setPopUpTo(R.id.HomeFragment, true).build()
            )
        }
    }

    /**
     * Which gaps have already been put in front of the user this launch.
     *
     * Without this the chain below would be a trap: three of the four prompts leave the app, so the
     * user returns through `onResume`, which would prompt again — and a declined permission would throw
     * them straight back out, forever. Asking once per launch and then leaving it to the error banner is
     * the difference between setup and a nag loop.
     */
    private val promptedThisLaunch = mutableSetOf<String>()

    /**
     * Runs setup and asks for the next thing that is missing.
     *
     * Driven from `onResume` rather than `onCreate`, because three of the four prompts are settings
     * screens: the user leaves the app to grant them and comes back, and that return is the only signal
     * we get. Each resume advances the chain by one, so the sequence completes itself with no callbacks
     * to thread together.
     */
    private fun syncSetup() {
        // `AppSetup.run` cannot throw by contract — it reports a degraded status instead — so the guard
        // here is for the prompt chain, which starts Activities and can be refused by the device.
        crashproof(SOURCE, ContainedFaults.ACTIVITY, "syncSetup") {
            promptNextGap(AppSetup.run(this))
        }
    }

    /**
     * Asks for the first outstanding thing, at most once per launch.
     *
     * The order is not cosmetic. Runtime permissions come first because background location cannot be
     * granted until foreground location is — the settings screen does not even offer "Allow all the
     * time" before then. The two special permissions follow, each on its own screen.
     *
     * `promptedThisLaunch.add()` returns false once a gap has been asked about, so that branch stops
     * matching and the `when` falls through to the next one.
     */
    private fun promptNextGap(status: AppSetup.Status) = crashproof(
        SOURCE, ContainedFaults.ACTIVITY, "promptNextGap"
    ) {
        when {
            status.missingPermissions.isNotEmpty() && promptedThisLaunch.add(GAP_RUNTIME) ->
                permissionLauncher.launch(status.missingPermissions.toTypedArray())

            !status.backgroundLocationGranted && promptedThisLaunch.add(GAP_BACKGROUND) ->
                AppSetup.openPermissionSettings(this)

            !status.batteryExempt && promptedThisLaunch.add(GAP_BATTERY) ->
                AppSetup.requestIgnoreBatteryOptimizations(this)

            !status.canDrawOverlays && promptedThisLaunch.add(GAP_OVERLAY) ->
                AppSetup.requestOverlayPermission(this)

            !status.exactAlarmsAllowed && promptedThisLaunch.add(GAP_EXACT_ALARM) ->
                AppSetup.requestExactAlarmPermission(this)
        }
    }

    /**
     * The permission dialog does not always stop the Activity, so `onResume` cannot be relied on to
     * follow it. This is the one callback the chain still needs.
     */
    private fun afterPermissionAnswer() = syncSetup()

    /**
     * Re-runs setup when the user comes back from Settings.
     *
     * The background-location grant is made outside the app, so nothing calls back into it — returning
     * to the screen is the only signal that something may have changed. [AppSetup.run] both raises and
     * clears its own errors, so this is all the reporting the screen needs to do.
     */
    override fun onResume() {
        super.onResume()
        // Recorded before anything else, and outside the guard: `KioskMode.bringToForeground` consults
        // it, and a service waking mid-resume must not decide the app is absent and launch a second
        // copy. It cannot throw, and it must not be skipped by anything that can.
        KioskMode.onActivityVisible()
        // Separately guarded, because they are independent repairs and the second is the one that
        // brings the service up: a device that refuses screen pinning must still run setup.
        crashproof(SOURCE, ContainedFaults.ACTIVITY, "onResume(pinning)") { applyScreenPinning() }
        crashproof(SOURCE, ContainedFaults.ACTIVITY, "onResume(setup)") { syncSetup() }
    }

    override fun onPause() {
        // Cannot throw, and `super` runs regardless — a paused Activity that still reports itself
        // visible would have every watchdog path believe the app is on screen when it is not.
        crashproof(SOURCE, ContainedFaults.ACTIVITY, "onPause") { KioskMode.onActivityHidden() }
        super.onPause()
    }

    /**
     * Puts the app back on screen if something took it off.
     *
     * `onStop` rather than `onPause`: pause fires for a dialog or the permission prompt, and relaunching
     * then would fight the very screens setup is trying to show. Stop means the activity is genuinely
     * no longer visible — Home was pressed, or another app came forward.
     */
    override fun onStop() {
        super.onStop()
        crashproof(SOURCE, ContainedFaults.ACTIVITY, "onStop") {
            if (!isFinishing) KioskMode.bringToForeground(this, "activity stopped")
        }
    }

    /**
     * Enters screen pinning, if kiosk mode is on and we are not already pinned.
     *
     * Without a device policy controller this is *pinned* mode rather than true lock task: the system
     * shows a one-time confirmation and the user can still leave deliberately. That is why the relaunch
     * in [onStop] is the load-bearing half and this only reduces how often it fires.
     *
     * `startLockTask()` may only be called while the activity is foreground, which is why this lives in
     * `onResume` and nowhere else.
     */
    private fun applyScreenPinning() = crashproof(
        SOURCE, ContainedFaults.ACTIVITY, "applyScreenPinning"
    ) {
        if (!KioskMode.isEnabled(this)) return@crashproof
        val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return@crashproof
        // `lockTaskModeState` can throw on manufacturer builds that gutted lock-task support. Reading it
        // as "not pinned" is the safe answer: `startLockTask` below is already allowed to be refused.
        val already = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
            } else {
                @Suppress("DEPRECATION") am.isInLockTaskMode
            }
        }.getOrDefault(false)
        if (already) return@crashproof
        runCatching { startLockTask() }
            .onFailure { Log.w(TAG, "screen pinning refused", it) }
    }

    /**
     * Turns kiosk mode on from the home panel and pins immediately.
     *
     * Separate from [toggleKioskMode] because the home button is not a toggle in the menu sense: it
     * reads its own state and calls one of the two directions, so the wording on the slab is always
     * what pressing it will do.
     */
    internal fun enterKioskMode() {
        if (KioskMode.isEnabled(this)) {
            releaseKioskMode()
        } else {
            KioskMode.setEnabled(this, true)
            applyScreenPinning()
            invalidateOptionsMenu()
        }
    }

    /** Stops the app holding itself on screen and releases pinning. */
    private fun releaseKioskMode() {
        // Written BEFORE stopLockTask so the onStop that follows cannot read a stale "on" and drag the
        // app straight back.
        KioskMode.setEnabled(this, false)
        runCatching { stopLockTask() }.onFailure { Log.d(TAG, "not pinned, nothing to release") }
        invalidateOptionsMenu()
    }

    /**
     * "Exit device" — asks first, because this stops the unit doing its job.
     */
    internal fun confirmExitDevice() {
        AlertDialog.Builder(this)
            .setTitle("Exit device?")
            .setMessage(
                "Stops capture, processing and every background job, and closes the app. " +
                    "Nothing is recorded until someone opens it again — this device cannot " +
                    "restart itself after a reboot."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Exit") { _, _ -> exitDevice() }
            .show()
    }

    /**
     * Shuts everything down and leaves.
     *
     * The order is the whole of it, and getting it wrong makes the app un-exitable:
     *
     *  1. **Kiosk off first.** It is what reopens the app. Stopping anything else while it is still on
     *     just means the app comes back and restarts it all.
     *  2. **Release pinning**, or the user is left on a pinned screen with no app.
     *  3. **Stop the service**, which takes the capture lane, the location trigger and the frozen-list
     *     monitor down with it. `ACTION_STOP` returns `START_NOT_STICKY`, so the system will not bring
     *     it back — that is the one path in the service that opts out of restart.
     *  4. **Cancel WorkManager**, which is the only part that would otherwise survive: uploads, the
     *     02:00 map refresh and the 03:00 sun-times job all run outside the service and outlive it.
     *  5. **finishAffinity**, so the task goes rather than the activity.
     *
     * The next launch re-arms everything through AppSetup, which is why none of this needs undoing.
     */
    private fun exitDevice() {
        Log.i(TAG, "EXIT DEVICE — stopping every component")
        KioskMode.setEnabled(this, false)
        // Before the service goes: the watchdog is the one component that would undo all of this. Its
        // dead-man alarm is armed at all times while the app is healthy, so leaving it set would put the
        // app back on screen within three minutes of the operator deliberately shutting it down.
        // Turning the kiosk switch off above already stops it acting, but the alarm is cancelled too
        // rather than left to fire into a no-op.
        Watchdog.disarm(this)
        runCatching { stopLockTask() }

        FleetService.stop(this)
        runCatching { WorkManager.getInstance(applicationContext).cancelAllWork() }
            .onFailure { Log.e(TAG, "could not cancel background work", it) }

        ErrorLog.clear()
        finishAffinity()
    }

    /**
     * The menu switch. Turning it off is the only way to get the device back.
     *
     * Order matters on the way out: the setting is written first, so the `onStop` that follows the user
     * leaving does not read a stale "on" and drag the app back.
     */
    private fun toggleKioskMode() {
        val turningOn = !KioskMode.isEnabled(this)
        KioskMode.setEnabled(this, turningOn)
        if (turningOn) {
            applyScreenPinning()
            Snackbar.make(binding.root, "The app will stay on screen.", Snackbar.LENGTH_LONG)
                .show()
        } else {
            runCatching { stopLockTask() }
                .onFailure { Log.d(TAG, "not pinned, nothing to release") }
            Snackbar.make(
                binding.root,
                "Released. The app will not reopen itself — capture stops if you leave.",
                Snackbar.LENGTH_LONG
            ).show()
        }
        invalidateOptionsMenu()
    }

    private companion object {
        const val TAG = "MainActivity"

        /** Shown under this screen's rows on the errors screen. */
        const val SOURCE = "Home screen"

        // Keys for promptedThisLaunch. Not the permission names: two of these are not permissions the
        // system would recognise, and one covers a group of three.
        const val GAP_RUNTIME = "runtime"
        const val GAP_BACKGROUND = "background-location"
        const val GAP_BATTERY = "battery"
        const val GAP_OVERLAY = "overlay"
        const val GAP_EXACT_ALARM = "exact-alarm"
    }

    // `toggleFilterOverride` used to sit here, pinning Stage 3's day/night branch from the menu.
    // Removed with the menu buttons: the override was sticky across process death, so one tap changed
    // how every later capture was prepped until somebody thought to tap it again — and the night
    // branch damages a day board. `DayNight` decides per clip, from the clip's own capture time.

    // `confirmRunValidation`, `startValidation` and `openAllFilesAccess` used to sit here — the
    // dialog that committed a unit to hours of unattended CPU over the sideloaded test set, and the
    // All-files-access page its results needed. The harness is deleted: a production unit does not
    // replay a test set, and the switches it flipped (`DebugUploader.localSink`, `FilterOverride`)
    // were process-wide, so a validation run could divert a REAL capture's artifacts into its own
    // folder and prep that capture on the wrong branch.

    /**
     * Asks before pushing whatever the debug outbox is holding.
     *
     * It asks because of what it spends rather than what it risks: a backlog is measured in hundreds of
     * megabytes of board crops and canvases, and a unit on a metered SIM should not send that because
     * somebody brushed a slab. The dialog states the size, so the decision is made against a number.
     *
     * Both drains are asked for, not just the artifacts: a unit whose outbox has fallen behind usually
     * has readings waiting too, and the operator's intent is "send what you are holding" rather than
     * "send one of the two things you are holding". Neither is forced — [DebugUploadScheduler] and
     * [UploadScheduler] enqueue expedited work with a network constraint, so the drain still waits for
     * a connection rather than failing without one.
     */
    internal fun confirmUploadResults() {
        lifecycleScope.launch {
            val backlog = withContext(Dispatchers.IO) { DebugUploadOutbox.backlog(this@MainActivity) }

            if (backlog.isEmpty) {
                Snackbar.make(
                    binding.root,
                    if (backlog.rejected > 0) {
                        "Nothing waiting to upload (${backlog.rejected} artifact(s) were rejected and " +
                            "will not be retried)."
                    } else {
                        "Nothing waiting to upload — everything has been sent."
                    },
                    Snackbar.LENGTH_LONG
                ).show()
                return@launch
            }

            // Built before the dialog rather than interpolated into it: a conditional paragraph
            // inside a raw string is where a message ends up with a stray blank line or a brace in it.
            val rejectedNote = if (backlog.rejected == 0) "" else
                "\n\nA further ${backlog.rejected} artifact(s) were rejected by the server and will " +
                    "NOT be retried; clean memory is what removes those."

            AlertDialog.Builder(this@MainActivity)
                .setTitle("Upload results now?")
                .setMessage(
                    """
                    ${backlog.files} artifact(s) from ${backlog.runs} run(s) are waiting — about
                    ${backlog.megabytes} MB.

                    Readings waiting to be sent go with them. Both drains wait for a connection, so
                    nothing is lost if there is no signal right now — this only moves them to the front
                    of the queue.
                    """.trimIndent() + rejectedNote
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Upload") { _, _ ->
                    runCatching { DebugUploadScheduler.requestImmediate(this@MainActivity) }
                        .onFailure { Log.e("MainActivity", "could not ask for an artifact drain", it) }
                    runCatching { UploadScheduler.requestImmediate(this@MainActivity) }
                        .onFailure { Log.e("MainActivity", "could not ask for a readings drain", it) }
                    Log.i(
                        "MainActivity",
                        "upload requested — ${backlog.files} artifact(s), ${backlog.megabytes} MB"
                    )
                    Snackbar.make(
                        binding.root,
                        "Upload requested — it continues in the background.",
                        Snackbar.LENGTH_LONG
                    ).show()
                }
                .show()
        }
    }

    internal fun confirmCleanMemory() {
        lifecycleScope.launch {
            val mb = AppReset.currentUsage(this@MainActivity) / 1_048_576

            AlertDialog.Builder(this@MainActivity)
                .setTitle("Clean memory?")
                .setMessage(
                    """
                    This frees about $mb MB and returns the app to a fresh install.

                    It DELETES, permanently:
                    •  every stored run and its board images
                    •  recorded clips, including any still waiting to be processed
                    •  board images saved offline that have not been read yet
                    •  debug artifacts that have not finished uploading
                    •  the downloaded ML models

                    Anything not yet uploaded or read is lost and cannot be recovered.

                    You will be taken back to the start, and the models are downloaded again as you go
                    through setup.

                    Your vehicle / device details and any sideloaded test clips are kept.

                    Do this while no video is being processed.
                    """.trimIndent()
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clean memory") { _, _ -> cleanMemory() }
                .show()
        }
    }

    /**
     * Performs the wipe, then drops the user back at the app's FIRST screen.
     *
     * The whole first-launch flow is replayed from there — vehicle details, then the download screen,
     * then capture — which is what "renew from scratch" means: the same sequence a fresh install goes
     * through, in the same order, rather than being dropped into the middle of it.
     *
     * The back stack is cleared with it ([androidx.navigation.NavOptions.Builder.setPopUpTo] on the graph
     * itself, inclusive). Without that, Back from the first screen would return to a capture screen whose
     * models, queues and stored runs have all just been deleted underneath it.
     */
    private fun cleanMemory() {
        val progress = AlertDialog.Builder(this)
            .setTitle("Cleaning…")
            .setMessage("Removing stored runs, queues and models.")
            .setCancelable(false)
            .show()

        lifecycleScope.launch {
            val report = try {
                AppReset.wipe(this@MainActivity)
            } catch (e: Exception) {
                Log.e("MainActivity", "clean memory failed", e)
                null
            }
            progress.dismiss()

            if (report == null) {
                Snackbar.make(binding.root, "Clean failed — see the log.", Snackbar.LENGTH_LONG).show()
                return@launch
            }

            // The wipe cancelled ALL background work to stop anything writing mid-delete, and that took
            // the recurring drains with it. Re-armed through AppSetup rather than one call per
            // scheduler: this list had already fallen behind — it re-armed three of the four and would
            // have left the 02:00 station-map refresh dead until the next launch.
            AppSetup.armBackgroundWork(this@MainActivity)

            // Back to home with nothing left on the stack. It used to land on the vehicle form, which
            // was the start destination when this was written; the details survive a clean, so sending
            // the operator to re-enter them was asking for a change that was not needed.
            goHome()
            Snackbar.make(
                binding.root,
                "Freed ${report.megabytesFreed} MB — starting over.",
                Snackbar.LENGTH_LONG
            ).show()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        return navController.navigateUp(appBarConfiguration)
                || super.onSupportNavigateUp()
    }
}