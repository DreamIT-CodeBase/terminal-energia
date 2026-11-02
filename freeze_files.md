# Frozen files

These files are **finalized**. Do not edit them without explicit permission from Chirag — asked for and
given, in that conversation, for that specific change.

## The rule

- **Do not edit a frozen file.** Not to tidy it, not to rename something, not "while I'm in here", not
  as a side effect of a refactor somewhere else.
- If a change genuinely looks necessary, **stop and say so**: name the file, the line, what is wrong,
  what the change would be, and what breaks if it is not made. Then wait.
- Reading them, quoting them, and building against them is always fine. The freeze is on writing.
- A change to a frozen file that was approved does **not** unfreeze it. The next change needs asking
  again.

## The list

| File | Finalized | Why it is frozen |
|---|---|---|
| `app/src/main/java/com/terminalscout/app/prod/capture/LocationMapRefreshScheduler.kt` | 2026-09-08 | Arms the 02:00 Tallinn station sweep. `ExistingPeriodicWorkPolicy.UPDATE` preserves `lastEnqueueTime`, and the catch-up uses `KEEP` not `REPLACE` — which is what stops repeated launches cancelling and re-queueing a fetch forever. |
| `app/src/main/java/com/terminalscout/app/prod/capture/LocationMapRefresh.kt` | 2026-09-18 | The sweep itself, and **the write must stay an upsert.** It never deletes and never touches `last_capture_time`. If this ever becomes a wholesale replace, the TS mapping evaporates at 02:00 and capture history goes with it. It also refuses an empty sweep, so a failed fetch leaves the stored rows standing. Lifted out of the worker on 2026-09-18 so the menu's REFRESH STATION MAP button runs exactly this and not a second copy of it — the freeze moved with the code. |
| `app/src/main/java/com/terminalscout/app/prod/capture/LocationMapRefreshWorker.kt` | 2026-09-08 | Now only the WorkManager adapter over `LocationMapRefresh`: it runs the sweep and maps the outcome onto a `Result`. Still frozen for the mapping — `retry()` rather than `failure()` on anything that could come good is what gives a failed fetch another chance inside the day instead of waiting for tomorrow's 02:00, and `success()` on a build with no credentials is what stops it re-firing for the life of the install. |
| `app/src/main/java/com/terminalscout/app/prod/capture/AzureMapsStations.kt` | 2026-09-08 | The sweep's transport — every Estonian branded station out of Azure Maps. What the 349 swept rows are made of, and the boundary where a provider change would land. |
| `app/src/main/java/com/terminalscout/app/prod/upload/StationDirectory.kt` | 2026-09-08 | `distanceMetres` — the app's single haversine. Used by the trigger, the stop monitor and the route tooling, so one implementation of the earth's radius exists and it is the one that is tested. A second copy anywhere is how two parts of Stage 0 start disagreeing about how far away a station is. |
| `app/src/main/java/com/terminalscout/app/prod/upload/UserStore.kt` | 2026-09-08 | The device's identity. `exists()` is one of the two gates that hold the capture service, and `deviceId()` derives the fleet id from `ANDROID_ID` rather than the stored column — so a payload's device id cannot drift from the hardware it was recorded on. A wrong edit produces readings the API cannot attribute to anything. |
| `app/src/main/java/com/terminalscout/app/prod/upload/VehicleConfig.kt` | 2026-09-08 | The identity record's type. Small, and it is stamped onto every uploaded reading; a renamed or reordered field is a silent change to what the server receives. |
| `app/src/main/java/com/terminalscout/app/prod/upload/UploadDbHelper.kt` | 2026-09-08 | `uploads.db` schema, verified by `ensureTables`. Its `onUpgrade` is **deliberately destructive** — it drops and recreates, on the argument that both tables are transient upload buffers. That argument is only true while they stay transient; anything durable added to this file breaks it. |
| `app/src/main/java/com/terminalscout/app/prod/capture/LocationMapDbHelper.kt` | 2026-09-08 | `location_map.db` schema, and the one migration in the app that must never be destructive. `last_capture_time` cannot be re-derived from anywhere — not from Azure, not from the TS mapping, not from the readings API. A schema bump that drops this table erases capture history permanently. It is a separate file with a separate version counter from `uploads.db` for exactly that reason. |
| `app/src/main/java/com/terminalscout/app/prod/download/ModelRepository.kt` | 2026-09-08 | Which detection models must be on disk before the pipeline can read anything. `ALL` excludes Gemma on purpose (Stage 4 reads off-device, so its ~3.7 GB would be a long wait for a file nothing opens) and drops the board detector while it is bundled in the APK. "Completing" that list would gate a fresh unit on gigabytes it never uses. |
| `app/src/main/java/com/terminalscout/app/prod/download/ModelDownloadManager.kt` | 2026-09-08 | Fetches the models, and since the download screen was removed it is reachable **only** from `AppSetup`. Its object-level scope is what lets a download survive with no screen open. Break it and a fresh unit captures perfectly and fails every clip downstream, with nothing on the readout to say why. |
| `app/src/main/java/com/terminalscout/app/prod/VehicleFormFragment.kt` | 2026-09-08 | The only screen that writes the device identity, and therefore the only way to clear the hold on the capture service. It re-runs setup on save so the service starts without waiting for a relaunch. |

## Unfrozen — Stage 0, 2026-09-09

Released by Chirag so the faults found in the Stage 0 audit can actually be fixed. The whole capture
chain from process start to a clip on disk, plus the poller that consumes it:

| File | Unfrozen | Why |
|---|---|---|
| `app/src/main/java/com/terminalscout/app/prod/App.kt` | 2026-09-09 | Stage 0 audit |
| `app/src/main/java/com/terminalscout/app/prod/AppSetup.kt` | 2026-09-09 | Stage 0 audit — `wipeOnFreshInstall` deletes unprocessed clips and orphans their sidecars; the dead `video_queue` entries |
| `app/src/main/java/com/terminalscout/app/prod/Watchdog.kt` | 2026-09-09 | Stage 0 audit — the attempt chain does not count uncounted respawns |
| `app/src/main/java/com/terminalscout/app/prod/WatchdogReceiver.kt` | 2026-09-09 | Stage 0 audit |
| `app/src/main/java/com/terminalscout/app/prod/Stage0Health.kt` | 2026-09-09 | Stage 0 audit — the report cannot see a latched capture lane or a full disk |
| `app/src/main/java/com/terminalscout/app/prod/CameraCaptureManager.kt` | 2026-09-09 | Stage 0 audit — `release()` abandons `finishHandler` |
| `app/src/main/java/com/terminalscout/app/prod/service/FleetService.kt` | 2026-09-09 | Stage 0 audit — `ACTION_HEALTH` can leave a created service with no lanes |
| `app/src/main/java/com/terminalscout/app/prod/service/CaptureLane.kt` | 2026-09-09 | Stage 0 audit — `capturing` has no timeout; the camera-roll copy runs on the main thread |
| `app/src/main/java/com/terminalscout/app/prod/service/ProcessingLane.kt` | 2026-09-09 | Stage 0 audit — the poller claims a clip that is still recording, and a thrown run deletes it |
| `app/src/main/java/com/terminalscout/app/prod/capture/CaptureTrigger.kt` | 2026-09-09 | Stage 0 audit |
| `app/src/main/java/com/terminalscout/app/prod/capture/LocationTrigger.kt` | 2026-09-09 | Stage 0 audit |
| `app/src/main/java/com/terminalscout/app/prod/capture/StopRecordingMonitor.kt` | 2026-09-09 | Stage 0 audit — `stop()` clears the shared frozen list unconditionally |
| `app/src/main/java/com/terminalscout/app/prod/capture/FrozenBoards.kt` | 2026-09-09 | Stage 0 audit |
| `app/src/main/java/com/terminalscout/app/prod/ui/KioskMode.kt` | 2026-09-09 | Stage 0 audit |

**Still frozen, and deliberately not part of this release:** the station-map supply chain
(`SurveyedStations`, `LocationMapRefreshScheduler`, `LocationMapRefreshWorker`, `AzureMapsStations`,
`LocationMapDbHelper`, `StationDirectory`) and the pre-Stage 0 identity and model files
(`UserStore`, `VehicleConfig`, `UploadDbHelper`, `ModelRepository`, `ModelDownloadManager`,
`VehicleFormFragment`). They feed Stage 0 but none of the audit's findings is in them.

The notes sections below are **kept**. The freeze is lifted; the reasoning is not — every note still
records something that would break if it were undone, so read the note before changing the line it is
about.

## Notes on `AppSetup.kt`

> **Unfrozen 2026-09-09** (Stage 0 release). These notes stand as design rationale, not as a write lock.

Things that look like tidying but are not, so nobody is tempted:

- **It is idempotent on purpose.** It runs on every launch and every resume and repairs what is missing.
  There is deliberately no "first run" flag: a flag records that setup *happened*, not that the device is
  *set up*.
- **Nothing in it may throw.** Every step is wrapped and a failure becomes a row on the errors screen —
  see `ERROR_HANDLING.md`, which is written from this file. `Throwable`, not `Exception`, and that is not
  habit: an escape here crash-loops the unit. Removing a wrapper is never a cleanup.
- **Steps are independent.** One failing must not skip the rest. A device that cannot read its identity
  still gets its folders made and its jobs armed.
- **`guard` must not clear its key.** Several steps share `setup_failed`; `runSteps` clears it once at the
  top of the pass. Making `guard` clear on success would let one step erase a row another just raised.
- **It does not request permissions.** That needs an Activity and a result callback, so `MainActivity`
  owns the asking and hands the answer back by calling `run` again.
- **The service is held** until the device details exist *and* location is granted. Both holds exist
  because of a real failure — an unattributable reading, and a `SecurityException` out of
  `startForeground` respectively.
- **The service is only started when it is not already running.** That is what lets the readout tell the
  truth on the same pass instead of showing a false "a background service is not running" at every launch.
- **Kiosk is forced on at every launch**, and yields only to a release made from the menu during that
  process. Removing the session hold would make the menu switch flick back under the operator's finger;
  removing the force would leave a unit that neither holds its screen nor restarts itself.
- **`wipeOnFreshInstall` only fires when the versionCode changes.** That is what makes a restart preserve
  the queue, the station map and `last_capture_time` instead of wiping them.
- **Error messages are constants** so the raise and the clear match exactly. Interpolating a value into
  one produces a row nothing can ever remove.
- **`ensureModels` is the only thing that downloads a model.** The download screen was removed precisely
  because a screen can only download while somebody is looking at it, which a deployed unit never has.
  It starts a download and returns false while one runs — "not ready" and "not downloading" are
  deliberately the same answer, because what it costs the operator is identical. It fetches
  `ModelRepository.ALL`, which excludes Gemma on purpose; do not "complete" that list without reading why.

## Notes on the watchdog files

> **Unfrozen 2026-09-09** (Stage 0 release). These notes stand as design rationale, not as a write lock.

The recovery path is shaped by one platform rule — a foreground service needing `camera` or `location`
cannot be created from the background — and almost every line below exists because of it. Read
`STAGE0_FLOW.md` §1 before changing any of it.

- **The alarm wakes the Activity, never the service.** A watchdog that restarted `FleetService` directly
  would produce a service that is alive and blind: Android 14+ throws, Android 12 starts it with no
  camera and no GPS. `MainActivity.onResume` runs `AppSetup`, and *that* starts the service, from the
  foreground, where it is allowed to be.
- **`WatchdogReceiver` must stay a receiver.** The decision has to be made when the alarm *fires*, not
  when it was set: by then the app is often already back, and relaunching would shove the task in front
  of whatever the operator is doing. An Activity `PendingIntent` has no opportunity to check, and no
  opportunity to re-arm the deadline. It must also stay declared in the manifest — the process it wakes
  is usually dead, and a runtime-registered receiver dies with it.
- **One alarm slot, deliberately.** Scheduling the 5 s respawn replaces a dead-man deadline sitting
  further out, which is the precedence wanted; a healthy start replaces it back. Two slots would race and
  could raise the Activity twice.
- **`counted = false` is not a detail.** One death regularly asks for a restart twice — the crash
  handler, then the service coming back a second later. Charging both would halve the guard for a single
  fault.
- **`pending` keeps the FIRST reason, not the most recent.** The second asker is a consequence of the
  first, and letting it overwrite made the errors screen name the service restart for what was actually
  an exception — sending whoever read it to the wrong place.
- **`MAX_ATTEMPTS` is load-bearing.** It caps a *loop*, not a lifetime: the chain resets after ten
  minutes without a crash, so a unit that crashes once a fortnight never approaches it. Removing it would
  not make the app more resilient, it would make a startup crash unrecoverable and hot.
- **Every path checks the kiosk switch**, which `AppSetup` now forces on. An app that reopens itself
  forever and cannot be dismissed is untestable and indistinguishable from malware; `EXIT DEVICE` calls
  `disarm` for the same reason.
- **The receiver must not health-check on the "app is not on screen" branch.** The repair starts a
  service that declares `camera` and `location`, and a background start of that is refused on Android
  14+ and silently blinded below it. With no Activity up, raising one *is* the repair — `AppSetup` then
  does the rest from a position where it is allowed to.
- **`armDeadMan` sits in a `finally`, on every path including a failure.** It is what makes the check
  recur. The service renews the same deadline every 60 s while it is alive, so on exactly the occasions
  this matters — the service is gone — the receiver is the only thing re-arming it.
- **The alarm and the service's loop cover different faults, and neither covers the other's.** The
  service pushes the dead-man deadline out every 60 s against a 3-minute deadline, so while the service
  lives the alarm can *never* fire. That is why the in-service loop exists: the alarm catches a service
  that is gone, the loop catches a lane that has died inside one that is still running. A first attempt
  put both in the receiver and the rows raised by a repaired fault were never cleared, because nothing
  ran to clear them.
- **`Stage0Health.check(includeJobs = true)` is expensive** — four blocking WorkManager queries. Only
  the slow callers pass true. The home panel polls twice a second and must keep carrying the last count
  forward, or it would put a database read on the main thread every two seconds.
- **The station map is repaired but not reported by `Stage0Health`.** `AppSetup` owns that row; two
  writers on one condition is how a message ends up disagreeing with itself.
- **The trigger and stop-watch rows are gated on the service being up.** With the service down those
  two are *supposed* to be detached, and three rows for one fault buries the one that matters.
- **The panel renders from `Stage0Health.state`, not from a cached `AppSetup.Status`.** That is the whole
  point of the flow: the screen and the repair read the same value, so the panel cannot claim `5/5`
  about a service the watchdog has already found dead. It used to, and it was measured doing it.
- **`App.onCreate` holds two calls and should hold no more.** Setup, permissions and the service belong
  to `AppSetup`, and they need a visible Activity to be correct — none of which is true here.

## Notes on the capture files

> **Unfrozen 2026-09-09** (Stage 0 release). These notes stand as design rationale, not as a write lock.

- **`LocationTrigger.onFix` skips frozen boards BEFORE the nearest-station search.** Filtering afterwards
  lets a frozen station sitting closest hide the one behind it — two forecourts across a road, the first
  captured, the second never seen because it was never nearest.
- **`EXIT_RADIUS_M` must stay `ENTER_RADIUS_M + 50`.** That band is the hysteresis; close it and a board
  freezes and releases on the same distance, chattering on every fix. A test pins the width.
- **Only the nearest station is ever a candidate, one per fix.** That is the mechanical root of the
  blocked-neighbour behaviour recorded in `DECISIONS.md`. It is a decision, not an oversight.
- **`bearing` and `speed` are on every fix and currently discarded.** Reading them is what a heading gate
  or a plausibility gate would need. No new data source is required — only a wider `onFix` signature.
- **`StopRecordingMonitor` keeps its own subscription rather than sharing the trigger's.** The fused
  provider merges concurrent requests, so a second listener at the same interval costs essentially
  nothing, and the two functions stay independent — one can detach without blinding the other.
- **Both `isAttached` and `isWatching` are guarded by an identity check on detach.** Two service instances
  overlap for a moment whenever the app recovers itself, and their `onDestroy`/`onCreate` are not ordered.
  Clearing unconditionally let a dying instance switch off a readout the live one had just switched on.
- **`FrozenBoards` is in memory and stays there.** Read the class note before "fixing" that.
- **`CameraCaptureManager` records FHD first and falls back down the ladder.** Resolution is the ceiling
  on everything downstream: a board 30 m away is ~197 px wide at UHD, ~99 px at FHD, ~66 px at HD and
  ~33 px at SD, and no later stage can recover detail the encoder never captured. `ClarityGate` rejects
  a crop under 32 px, so SD puts a distant board on the threshold — the ladder keeps it only as a last
  resort, because a capture that fails to start is worse than a low one.
  **This led with UHD until 2026-09-10.** The old argument was that the clip is transient, so only the
  frames mattered — Stage 1's PNGs being ~97% of the disk per clip. That premise is gone: a clip is now
  MOVED to the debug container and uploaded rather than deleted, so its size is mobile data on every
  single trigger, observed at ~630 MB per 2-minute capture. FHD is a quarter of the bytes and keeps a 3x
  margin over the clarity gate. Lowering resolution also shrinks the Stage 1 frames, which scale with
  pixel count, so the whole run's disk drops with it.
  **Not measured:** whether UHD read boards FHD misses. Nobody has A/B'd the two on one forecourt. If
  distant boards start going missing, `CAPTURE_QUALITY_LADDER` is the first thing to put back.
- **The orientation listener is load-bearing on a hand-held device.** Without it CameraX writes a portrait
  rotation flag over already-upright landscape pixels, `MediaMetadataRetriever` obeys it, and the board
  detector — trained on upright boards — finds nothing. Observed on a real clip.
- **`LocationMapRefreshWorker`'s write must stay an upsert**, and it must keep refusing an empty sweep.
  Both are what make the TS mapping and `last_capture_time` survive a bad fetch or a schema change.
- **`LocationMapRefreshWorker` now fetches from `GET /v1/stations`, not Azure Maps.** Approved edit,
  2026-09-14, asked for and given: four lines swapping `AzureMapsStations` for `SystemApiStations`, plus
  the KDoc naming it. Nothing else in the file moved — the null/empty distinction, the single
  `sweptAtMs`, the upsert and the empty-sweep refusal are all exactly as they were, because the new
  transport was written to the old one's contract deliberately. The file stays frozen; the next change
  needs asking again.
- **Four constants in frozen files now read through `DeviceConfig`.** Approved edit, 2026-09-14, asked
  for and given: `LocationMapRefreshScheduler.REFRESH_HOUR` / `STALE_AFTER_MS`,
  `ModelDownloadManager.MAX_CONTINUATIONS`, `StationDirectory.MAX_MATCH_METERS`. One line each —
  `const val X = v` became `val X: T get() = DeviceConfig.t("path", v)`, with the compiled number kept
  as the fallback, so a device with no fetched document runs exactly what it ran before. Nothing else in
  those files moved. All three files stay frozen; the next change needs asking again.
  - `StationDirectory.MAX_MATCH_METERS` is the one to think twice about before tuning it from the
    server. It is not a search radius, it is the cap that stops a capture 40 km from any forecourt being
    labelled with one — raising it does not find more stations, it invents provenance for readings that
    have none.
  - `LocationMapRefreshScheduler.REFRESH_HOUR` moving does **not** re-aim a live schedule by itself. The
    periodic work is re-armed with `UPDATE` on every service start, which is what reads the new hour; a
    device that never restarts keeps the old one until it does.
- **`AzureMapsStations` is now unreferenced and is kept anyway.** It is the record of why the grid sweep
  had to be tiled — the 100-result page ceiling, the `ofs=100` dead end, the brand filter that removed
  ~45% of hits — and the server now has to solve exactly those problems. Deleting it deletes the
  reasoning, not just the code.

## Notes on the pre-Stage 0 files

- **`LocationMapDbHelper.onUpgrade` must stay non-destructive, and `UploadDbHelper.onUpgrade` may stay
  destructive.** That asymmetry is the whole reason they are two files with two version counters.
  `location_map.db` holds `last_capture_time`, which exists nowhere else on earth; `uploads.db` holds
  queues that are re-derivable from the artifacts on disk. Copying either pattern to the other file is a
  data-loss bug that no test would catch.
- **`UserStore.deviceId()` reads `ANDROID_ID`, not the stored column.** Deliberate: the fleet id then
  cannot drift from the hardware, even if the stored record is stale or was copied from another device.
  The stored `device_id` column exists for display, not for identity.
- **`UserStore.exists()` failing closed is load-bearing.** `AppSetup` treats an unreadable `user.db` as
  "no details", which holds the capture service. Making it fail open would upload readings the API cannot
  attribute.
- **`ModelRepository.ALL` is a computed property, not a constant list.** It drops the board detector while
  `ModelConfig.Yolo.BUNDLED_ASSET` is set and never contains Gemma. Both omissions are documented at the
  declaration — read them before adding an entry.
- **`ModelDownloadManager`'s scope carries a `CoroutineExceptionHandler`, and `runDownloads` catches
  `Throwable`.** Approved edit, 2026-09-09, for the no-crash pass. `SupervisorJob` does not stop an
  unhandled exception reaching the thread's uncaught-exception handler, and the seeding pass and every
  `publish` sit outside the per-model `try` — so a download fault was a process death, on every launch
  and every resume, with no screen and nobody to retry it. `CancellationException` is rethrown ahead of
  the `Throwable` catch: it is not a failure and must not be reported as one.
- **`VehicleFormFragment` guards its two `UserStore` reads and checks the result of the save.**
  Approved edit, 2026-09-09, same pass. `load()` resolves columns with `getColumnIndexOrThrow`, so a
  `user.db` bad enough to throw used to crash the one screen that can repair the device's identity — the
  unit was then unrecordable *and* unfixable. The save's result is **checked**, not just contained: on a
  failure the screen stays put and says to read the errors list, because going home would leave the
  operator believing a held service was set up.
- **`ModelDownloadManager` has no screen any more.** Its `state` and `retry()` are currently unread; they
  are kept as the hook for showing download progress on the readout. Deleting them is not a cleanup, it
  removes the only per-model progress and failure detail in the app.

## Notes on the service files

> **Unfrozen 2026-09-09** (Stage 0 release). These notes stand as design rationale, not as a write lock.

- **`FleetService.onStartCommand` stands down on `intent == null` and on nothing else.** That is precisely
  how `START_STICKY` re-creates a service, and on that path nobody called `startForegroundService()`, so
  there is no five-second obligation to satisfy. An earlier version keyed on `!KioskMode.isActivityVisible`
  — a *race*, not a signal — and an explicit start landing just after a pause stood down without ever
  reaching `startForeground`. The platform killed the process, the watchdog respawned it into the same
  race, and the unit crash-looped to the ten-attempt guard.
- **`goForeground` is wrapped.** An Android 14+ refusal of the `location` type must stand down, not die
  unexplained.
- **The 60-second loop in `ensureStarted` is a health check, not just a wake-lock renewal.** It is the
  only thing that catches a lane dying inside a live service, because the service's own renewal keeps the
  dead-man alarm from ever firing while it lives.
- **`CaptureLane` asks for the camera type, not `FleetService.fireCaptureStart`.** The lane is the one
  place every capture passes through; `fireCaptureStart` is reached from two doors and the autonomous
  trigger uses neither. Moving the promotion back would restore a bug that failed in the worst available
  shape — service up, readout green, clips black.
- **The board is frozen when the recorder rolls, never when the trigger fires.** Binding can fail, and a
  board frozen for a capture that never rolled loses the station for the whole visit.
- **`previewAttached` and `FleetBus.hasObservers` are separate conditions.** They answer different
  questions; collapsing them makes the pipeline decode preview bitmaps nobody asked for.
- **`ProcessingLane.add()` and `start()` must not kick the drain.** The one remaining `kick()` outside
  `requestDrain()` is the offline resume, which continues a drain that *was* asked for. Removing it would
  strand an asked-for drain at the first clip that lost signal.
- **`KioskMode.releasedThisSession` is in memory on purpose.** Its whole meaning is "for as long as this
  process lives" — a crash, a kill or EXIT DEVICE and a fresh open all put the unit back to holding its
  own screen, which are exactly the states nobody is present for.

## Notes on the TS mapping

- **`NAMED` and `DETECTED` are not the same kind of data.** Named entries are surveyed forecourts.
  Detected entries are *vehicle positions* where the pipeline produced a reading — offset from the board
  by an unknown lead distance, possibly on the wrong carriageway. Merging the two lists would erase that
  distinction and it is the only thing recording how much each coordinate can be trusted.
- **Ids carry provenance (`surveyed:` / `detected:`) and must keep doing so.** Promoting a detected
  position to a named one inserts a new row rather than renaming, so `last_capture_time` history is never
  rewritten by a rename.
- **Seeding goes through `LocationMap.upsertAll` and must stay an upsert.** It never deletes and never
  writes `last_capture_time`, which is what makes it safe to re-run on every launch and what stops the
  02:00 Azure sweep erasing it.
- **Deliberate duplicates.** Two 1 m pairs in `NAMED`, three `DETECTED` entries within 300 m of a named
  site. Each costs one refused capture; deleting the wrong one of a pair costs a board forever. They are
  commented individually — read the comment before removing anything.
- **Fuel-type sequences were dropped on purpose.** The pipeline reads the sequence off the board. Storing
  it here would create a second source of truth for something the board already states.

## Adding to this list

One row per file, with the date it was finalized and the reason in the same voice as the entry above —
what would actually go wrong, not "important file".
