# Decisions

Choices that were made deliberately and would look like bugs or oversights to anyone reading the code
cold. Newest first. Each entry says what was decided, what it costs, and what would have to change to
revisit it.

This is not a changelog. It records the *reasoning* that does not survive in a diff.

---

## 2026-10-05 — Enter at 80 m, re-arm at 130 m, and cap clips at 25 s

The enter radius is 80 m and the exit radius remains 50 m farther out, at 130 m. The tighter entry
reduces captures of nearby stations; it also means stations more than 80 m off the road will not
trigger, and there is less approach footage. The existing 50 m hysteresis band is preserved.

The default location interval is 1.5 s, but the API owns the effective interval. The app no longer
rejects an API interval based on a locally chosen speed/radius assumption, and the provider's minimum
interval follows the API interval rather than applying a separate 1 s local/configured floor.

The clip limit's fallback rises from 15 s to 25 s. The app no longer clamps a configured value to 25 s;
the API owns the effective limit. The existing storage preflight remains in place, but longer UHD
clips create more extracted frames and can take longer to process.

The server document must be updated with `capture.trigger.enter_radius_m = 80`,
`capture.trigger.exit_radius_m = 130`, `capture.trigger.update_interval_ms = 1500`, and
`capture.capture_lane.max_clip_ms = 25000`. `min_update_distance_m = 25` is the current API value;
the app does not impose a separate radius-relative limit on it.

---

## 2026-09-28 — Lower the clip ceiling to 15 s

The compiled CameraX ceiling is now 15 s to bound recording and downstream video processing.
Stored/server configurations can still shorten it, but cannot raise it; the existing server
document specifies 60 s. The finalize deadline remains derived from the effective ceiling.
Previously recorded or sideloaded clips are still processed at their original lengths.

---

## 2026-09-28 — Probe sparse gaps before refining board detection

The high- and low-confidence full-frame passes now run the model once per base frame and apply
both thresholds to that output. Low-confidence crops still count only when the entire
high-confidence pass has no usable hits; shifted windows retain their existing gate.

Previously a clip with no usable detections was repeatedly re-extracted across its entire
duration at 20–30 fps, with up to three model inferences per added frame. It now probes one
timestamp between every three base-frame pairs at 30 fps. If that produces a possible hit
(including a weak track), the existing 20–30 fps escalation searches windows around the hits.
If it produces nothing, the clip reports no board rather than exhaustively scanning the video.
This makes empty clips cheaper at the cost of potentially missing a board visible only in
unprobed gaps. Compare both processing time and board recall on real driving clips before
changing the probe density.

---

## 2026-09-28 — Successful station refreshes reconcile with the API

The menu refresh and nightly job now share an atomic reconciliation: upsert stations returned by
`GET /v1/stations`, preserving their `last_capture_time`, then delete all IDs absent from that response
in the same transaction. A failed or empty fetch leaves the stored map intact. The UI reports how many
rows were removed. This replaces the previous rule that retained unserved rows indefinitely; at the
observed refresh, 384 of 951 stored rows were no longer served.

---

## 2026-09-28 — Cap clips at 30 s

The compiled CameraX clip ceiling is now 30 s. A stored or fetched device configuration can
request a shorter clip, but cannot raise the ceiling: the existing server document still specifies
60 s, and changing only the compiled fallback would leave configured devices recording for a minute.
The recorder's finalize deadline remains derived from the effective ceiling.

---

## 2026-09-18 — The APK stops shipping stations, and the leftovers are deleted

**Deleted** `capture/SurveyedStations.kt` (41 named + 43 detected), its `AppSetup` step, its error key
and its freeze entry
**Added** `LocationMap.purgeBundled`, called from `AppSetup` and from `LocationMapRefresh`
**Also** two menu buttons and a sun-times readout — see below

`GET /v1/stations` is the only source of the station map now. The seed that re-asserted 84 hand-surveyed
sites on every setup pass is gone, which closes the "left open, deliberately" note on the 2026-09-14
entry: the APK no longer gets to win a coordinate argument with the server, because it no longer has an
opinion.

### The delete is the point, and it is the exception to the rule

`upsertAll` never deletes. That is load-bearing and stays. But it means retiring the seeder on its own
would have left 83 rows on every deployed unit for the life of the install, and nothing would ever have
removed them. So there is exactly one deliberate delete in this table, and it is scoped so that it
cannot become a second one:

    (id LIKE 'surveyed:%' OR id LIKE 'detected:%') AND modified_time < <last sweep>

A row the last sweep returned carries the sweep's timestamp, so the clause reads "the server did not
return this". The server *does* still serve one of the old ids; it sits at the sweep timestamp and is
invisible to the delete. Without that clause the purge and the sweep would fight — sweep re-inserts,
next setup pass deletes, `last_capture_time` reset on every launch.

It needs no "already done" flag: after the first run nothing matches. On a table whose newest rows ARE
the old seed (a device that upgraded before its next sweep) it deletes nothing and waits for the sweep,
which is the safe direction to fail in. Observed exactly that on the test unit — the setup pass declined,
the catch-up sweep eleven seconds later purged 83.

### What it cost, measured rather than assumed

The unit went 468 → 385 rows. Of the 83 removed, **41 had no API station within 550 m** — the lane a
capture occupies — so the trigger will not fire at those sites any more. Most are `detected:stationNN`;
the furthest from any served station is `Jarvakandi HEPA` at 18.7 km. The other 42 are within 550 m of a
row the API serves, so those forecourts keep their coverage under a different id.

**The 2026-09-14 entry's "41 surveyed, 42 detected, 386 from Azure" no longer describes the feed.** The
live answer on 2026-09-18 is 385 stations of which exactly **one** carries a `surveyed:` id. Whatever
merge produced 469 is not what the server returns today. That is the fact this decision was actually
taken against, and it is the one to re-check first if the coverage loss above ever matters: if the merge
comes back, those sites return on their own and nothing here needs revisiting.

### To revisit

The surveyed coordinates are in git history at `capture/SurveyedStations.kt`. Bringing them back means
restoring the file and its `AppSetup` step — but the honest fix is the server serving them, which is
what the curated feed was for.

### The menu grew a REFERENCE DATA section

Two buttons — refresh the station map, refresh the sun times — and a readout above each. The sweep moved
out of `LocationMapRefreshWorker` into `LocationMapRefresh` so the button and the 02:00 job cannot drift
apart; the worker is now the WorkManager adapter over it and the freeze moved with the code.

The sun-times button forces the fetch (`force = true`), because unforced it is a no-op while the file is
today's and throttled after a failure — both correct for 03:00, both would make a button that appears to
do nothing. The readout names the day the stored times belong to, and says so when they are not today's:
`DayNight` reads `stored()` and not `current()`, so yesterday's times really are what a capture is judged
against until a refresh lands, and a readout that hid that would be describing a different app.


## 2026-09-14 — The tunables are fetched, and the build is the fallback

**Added** `config/DeviceConfig.kt`, `DeviceApi.config()`, `DeviceConfigTest`
**Changed** 34 files: 177 constants now read through `DeviceConfig`
**Endpoint** `GET /v1/config` — `{config_id, schema_version, etag, config:{…}}`

### The shape

Every converted constant keeps its declaration, its name, its type and the KDoc that explains what it
costs. Only the value moves:

    const val ENTER_RADIUS_M = 150.0
    val ENTER_RADIUS_M: Double get() = DeviceConfig.double("capture.trigger.enter_radius_m", 150.0)

**The compiled number stays, as the fallback.** That is the property that makes this shippable: a
device with no document, no signal, a corrupt file or a refused document behaves exactly like the build
it is running. A fetched config can only move a value the app already had a working answer for — it can
never introduce a value the app has never run.

### Why the snapshot does not move under a running pipeline

`load()` reads the stored file into the snapshot once, as `AppSetup`'s first step. `refresh()` fetches
and writes the file and **does not** swap the snapshot; the new values take effect at the next process
start.

The alternative — applying a fetched document immediately — breaks two things that cannot be made safe
by trying harder. A run that starts under one `shift_frac` and finishes under another produces a canvas
that no artifact describes. A capture holds the camera from fire to release, so a radius that changes
between those two edges strands a frozen board. A unit relaunches constantly (the watchdog raises the
Activity, the operator opens the app, the process dies and returns), so "next process start" is hours,
not weeks.

### A bad document is refused whole

The invariants used to be held by the compiler: the 50 m hysteresis band, the two weight sets summing to
1, the storage floors' ordering, the detector ladder loosening at each rung, `min_regions <=
max_regions`, a fix interval that cannot skip the radius. Moving the constants into a file moved that
responsibility into `DeviceConfig.validate`, which refuses the document **in full** if any of them
breaks. Clamping one value would leave the fleet running a configuration nobody wrote.

The schema version gates the same way: anything but 2 is refused, because a v3 may have MOVED a path
this build reads and a silently-missing path is a silently-different pipeline.

### The cost, stated plainly

Paths are strings, so a typo falls back silently — the safe direction, but an invisible one. Two things
pay for that: an absent path is logged once per process, and `DeviceConfigTest` scans the app's own
source for every `DeviceConfig.x("path")` call and asserts each one exists in the document the API is
actually serving (saved at `app/src/test/resources/device-config.default.json`). That test is the only
place that knows both halves.

Three constants stopped being `const` because they are derived from converted ones —
`RECORDING_FINALIZE_TIMEOUT_MS`, `ShiftWindow.WINDOW_FRAC`, `BoardDetector.LETTERBOX_FILL`. They stay
derived at read time, which is what stops them drifting from their inputs.

### The frozen four

`LocationMapRefreshScheduler.REFRESH_HOUR` and `STALE_AFTER_MS`, `ModelDownloadManager.MAX_CONTINUATIONS`
and `StationDirectory.MAX_MATCH_METERS` live in frozen files and were converted the same way, as a
separate approved edit on the same day. One line each, the fallback unchanged, nothing else in those
files touched. They stay frozen.

`MAX_MATCH_METERS` is the one of the four worth a second look before it is ever tuned: it is the cap
that stops a capture 40 km from any forecourt being confidently labelled with one, so raising it
remotely does not widen a search, it invents provenance.

The only constants now left in code are `AzureMapsStations`' `PAGE_LIMIT` and `TILE_RADIUS_M`, and they
are dead — the sweep moved to `/v1/stations` earlier the same day.

---

## 2026-09-14 — The nightly station sweep moved to our own API

**Was** `LocationMapRefreshWorker` → `AzureMapsStations.fetchAll()` → 30 Azure Maps grid calls
**Now** `LocationMapRefreshWorker` → `SystemApiStations.fetchAll()` → `GET /v1/stations`
**Added** `capture/SystemApiStations.kt`, `SystemApiStationsTest`
**Files** `capture/LocationMapRefreshWorker.kt` (frozen; edit approved by Chirag in-conversation)

### Why

Three reasons, in the order they matter.

**The key.** Each tile call carried `AZURE_MAPS_KEY` in its query string, and a `BuildConfig` string is
a constant in the APK's dex — `strings` on the file finds it in seconds. Every unit in the fleet shipped
a recoverable copy of a live credential. It is now held server-side and the device carries only the
fleet secret, which is revocable.

**The list is curated now.** The server merges the hand-surveyed forecourts into the feed under the same
`surveyed:` / `detected:` ids `SurveyedStations` seeds, so a correction reaches the fleet without a
release. The live feed is 469 stations: 41 surveyed, 42 detected, 386 from Azure.

**The sweep's problems become one problem.** Tiling, the `limit=100` ceiling, the `ofs=100` dead end,
the brand and classification filters, POI de-duplication — solved once on the server instead of thirty
calls a night on every phone.

### What deliberately did NOT change

Everything around the transport. The scheduler still aims 02:00 Tallinn with the same DST override, the
worker still returns `retry()` on null and on empty, `upsertAll` still stamps one `sweptAtMs` and still
never writes `last_capture_time`, rows are still never deleted. `SystemApiStations` was written to
`AzureMapsStations`' contract — null means "not to be trusted", empty means "the server had nothing" —
precisely so the worker's four-line edit could not become a behavioural one.

### One filter did not survive

A blank brand used to mean a depot or a marina pump and was dropped; it removed ~45% of raw Azure hits.
On this path the server has already decided what counts as a station, so a brandless row is kept and
counted in the log instead. Dropping it would shrink a fleet's map by a rule the fleet operator cannot
see. The live feed has none.

### Left open, deliberately

`SurveyedStations.seed` still runs on every setup pass, and the ids it writes now also arrive in the
feed. Same ids, so the upsert cannot duplicate them — but the APK copy and the server copy can disagree
about a coordinate, and the seed runs AFTER the fetch, so the APK wins. That ordering was chosen when
the other source was raw Azure Maps and deserves deciding again on its own.

---

## 2026-09-14 — Enter radius 150 m, exit radius 200 m, clip ceiling 60 s

**Was** `ENTER_RADIUS_M = 250.0`, `EXIT_RADIUS_M = 300.0`, `MAX_CLIP_MS = 120_000`
**Now** `ENTER_RADIUS_M = 150.0`, `EXIT_RADIUS_M = 200.0`, `MAX_CLIP_MS = 60_000`
**Files** `capture/LocationTrigger.kt`, `service/CaptureLane.kt`

### Why

Requested by Chirag. It reverses the 2026-08-30 raise and goes 50 m below the original specification,
and the arithmetic in that entry is the arithmetic here, read the other way.

**The radii.** A capture holds the one camera lane from fire to release, which is `ENTER + EXIT` of
travel: 350 m now, against 550 m at the old radii and 450 m at the specified ones. Every metre of that
span is a stretch in which a second station is refused for "already recording", so the shorter span
blocks fewer neighbours on the clustered sections the route analyses keep failing on.

**What it costs** is approach footage on boards set back from the road, which is what the 250 m radius
bought. A fixed radius means the further off-axis a board sits, the shorter the chord through the circle:
a station 100 m off-axis now gets ~4.4 s of approach at 90 km/h before the closest point, against ~9 s at
250 m. Anything further off-axis than 150 m does not trigger at all — including the three stations at
249, 265 and 266 m that 2026-08-30 was partly chosen to bring in. Those three are given up knowingly.

The 50 m hysteresis band is unchanged and still load-bearing; `EXIT_RADIUS_M` moved with the enter radius
rather than staying put, because a closed band chatters on every fix.

**The clip ceiling.** 120 s was a specified requirement; 60 s is the new one. Reaching the cap means the
vehicle parked inside the radius rather than driving past, and the field runs show real departures ending
clips at 15-36 s — so the halving costs footage only on visits that were already going to be capped. It
also halves the worst-case downstream cost, from 10.8-21.6 GB of extracted PNG frames to 5.4-10.8 GB,
which brings a full-length clip closer to what `MIN_FREE_STORAGE_MB` actually admits a capture on.

### To revisit

The radii are pinned by a test (`LocationMapTest`) rather than merely ordered, so a change has to be
deliberate. The evidence that would reopen it is the opposite of the evidence that closed it: boards
missed on a route because the trigger fired too late for the approach, rather than neighbours refused
because a lane was still held.

---

## 2026-09-08 — Stage 1 is a disk poller, and the queue is gone

**Rewrote** `service/ProcessingLane.kt`
**Added** `VideoMetadataStore.kt`
**Deleted** `VideoQueueStore.kt`, `VideoQueueTypes.kt`, `CameraCaptureManager.sweepOrphanedCaptures`,
`FleetService.ACTION_PROCESS` / `requestProcessing` / `removeQueued`

### The loop

```
poll recorded_videos/ every 5 s
     → nothing there?  poll again
     → a clip?         stop polling, run the whole pipeline, delete the clip,
                       wait 10 s, resume polling
```

One coroutine, sequentially. "Stop polling while processing" is not a flag anyone maintains — it is
where the code is, which is why the loop needs no lock.

### Why the durable queue went

`video_queue/<id>.json` was a **second list of work items beside the directory**, and two lists can
disagree: an entry pointing at a file somebody deleted, a file no entry claims. Both were real states the
old code had to defend against — that is what `sweepOrphanedCaptures` and `referencedPaths()` existed
for.

The clip on disk already *is* the work item. Processing it means deleting it, so what remains in
`recorded_videos/` is exactly what is still owed. Nothing was lost in durability: the queue's whole
purpose was surviving a process death, and a file in `filesDir` does that by itself.

### Provenance moved to a sidecar

A filename cannot carry a station. `metadata_videos/<same basename>.json`, written by `CaptureLane`
when the clip finalizes and while it is still in `staged_videos/` — before the move that publishes it,
so the poller cannot claim a clip whose sidecar is not there yet. Holds `station_id`, `station_name`,
`station_latitude`, `station_longitude`, `vehicle_latitude`, `vehicle_longitude`, `vehicle_accuracy_m`,
`vehicle_fix_at_ms`, `device_id`, `captured_at_ms`.

**Two positions, deliberately** (2026-09-09). The station coordinate says which board the clip was
aimed at; the vehicle coordinate is the last GPS fix at the instant the recorder rolled, snapshotted in
`CaptureLane.rollRecording` from `VehicleFix`. Their difference is the *lead distance* — how far short
of the board recording began — which is the number the trigger geometry work needs and which neither
coordinate gives alone. Until this change the sidecar stored only the station's, under the unqualified
keys `latitude`/`longitude`, which `VideoMetadataStore` documented as the vehicle's. `read` still
accepts those old keys as the station's, so a sidecar left in the queue across an update is not orphaned.

Either position can be null and the sidecar says so: a manual capture names no board, and a capture in
the first seconds of a cold process has had no fix — `VehicleFix` is in memory, like `FrozenBoards`, so
a fix never survives a restart to be written into a clip as though it were current.

**Metadata is optional.** No sidecar → the clip is processed anyway, losing station attribution on one
reading. A test clip never has one. Refusing to process would cost the reading entirely.

Both files die together when a run completes.

### It is not triggered and it triggers nothing

`ACTION_PROCESS` is gone; the 5-second poll replaced it. Stage 0 records a clip and stops — the two
lanes share a directory and nothing else, and as of 2026-09-09 that is literally true: the
`onClipCaptured` callback is gone with the camera-roll copy that was its only subscriber. A capture's
last act is moving the clip out of `staged_videos/` into `recorded_videos/`.

**The camera-roll copy was removed.** Every finished clip used to be copied into `DCIM/Camera` from the
capture lane's completion callback, which runs on the main thread — a few hundred megabytes of stream
copy blocking the UI thread on every capture, and double the disk cost at the moment the disk is
fullest, on a volume the capture lane's storage floor does not measure. It existed so a tester could
pull a clip without `run-as`; nothing in the app ever read it.

The **10 s** gap replaced the 30 s thermal cooldown. The thermal argument belonged to a lane that started
the next run immediately; this one is also gated by the poll.

### Offline

The poller keeps polling. The **pipeline** is held, because a run with no network produces a well-formed
payload with no prices and nothing downstream can contradict a reading the server has accepted. Clips
accumulate on disk. Data delayed, not data wrong.

### Closed: a clip that always fails

This was the highest-risk open item in the app — a clip whose run threw stayed on disk, the next poll
picked it up five seconds later, forever, at full CPU on a device that has been thermally killed before.
It is fixed, by the mechanism this section always said would fix it: **the loop is removed by removing
the file.**

Every run now hands its clip to the debug upload outbox as its last act, whatever the outcome. It is a
**move**, not a copy — `recorded_videos/` and `debug_outbox/` are both under `filesDir`, so it is a
rename that duplicates nothing and costs the pipeline nothing. `DebugUploadWorker` delivers it on
WorkManager's terms and deletes it once the bytes have actually arrived. A run that could not read the
board, or that threw, sends a `failure.txt` alongside it naming the outcome, the stage, the throwable and
the run's logcat tail.

The same change closed the quieter fault on the success path: a clip used to be deleted as soon as the
pipeline answered, while its reading was still a pending row in `uploads.db`. A permanent failure of that
upload destroyed the only footage of the capture. The clip now outlives the run.

**What it costs.** An incomplete run used to leave the clip for a later re-read that could recover real
prices; that recovery is gone, and such a capture files no reading at all. Given up knowingly — the
re-read produced the same refusal often enough to be a CPU loop rather than a second chance, and a clip
its reader refused is exactly the footage a human should be looking at. Note the plain-offline case never
reached this path anyway: the loop declines to start a run without a network.

Every successful capture now also ships its full clip, which is a real and ongoing bandwidth cost. A
rejected clip is **deleted** rather than parked `.rejected` like every other artifact — the reason
survives in the `logs.txt` staged beside it, and hundreds of megabytes pinned until Clean Memory is not
a trade worth making for bytes whose diagnosis is already recoverable.

Two paths still leave a clip on disk, both deliberate: a run superseded by a restarted loop (the live
loop owns that clip), and an outbox that refused it (disabled, or the move failed) — where retrying
beats destroying a recording nothing has a copy of. `clip_stuck` now fires on consecutive failing
*clips* rather than retries of one, because the same clip can no longer recur; it means the unit is
failing everything.

### Readout

`QueueRow` lost `status`, `trigger` and `isTestClip`; it carries `processing: Boolean`. The capture
status screen shows `VIDEOS / ON DISK / WAITING / PROCESSING` and no DONE or FAILED — a finished clip is
deleted, and a failed one is indistinguishable from a waiting one because it is retried. Showing either
would invent a state the disk cannot report.

---

## 2026-09-08 — 43 detected positions added to the TS mapping (now 84 rows)
**Added** `SurveyedStations.DETECTED`, `SOURCE_DETECTED`
**`ALL` is now** `NAMED + DETECTED` — 41 + 43 = 84 rows

### Why

The named survey covers forecourts somebody visited. It does **not** cover the roads the fleet actually
drives: 29 pipeline detections across four recorded routes, one within 200 m of an Azure-swept station,
median 3.9 km away. Whatever was being read on those roads, no map described it.

These 43 positions come from `api_response.json` across 63 recorded runs, grouped where runs fell within
150 m. They are places where **a reading was actually produced** — a stronger claim than "a station
exists somewhere near here".

### What they are not

**Vehicle positions, not board positions.** The coordinate is where the phone was when the pipeline
recorded the fix, so it is offset from the forecourt by however far the vehicle had travelled, and it can
sit on the wrong carriageway. Accuracy in the source data is ~13 m, but the offset is not accuracy — it
is an unknown lead distance.

So they are leads, not survey. Promote one to `NAMED` the moment somebody stands at it with a phone.
Names are placeholders (`station1`…`station43`) because the operators are unknown.

### Provenance is in the id

`surveyed:tartu-ringtee-olerex` versus `detected:station34`. Visible in a log line and a `sqlite3` dump
without a join — and it makes the eventual merge safe: promoting a detected position inserts a new row
rather than renaming the old, so `last_capture_time` history is never rewritten.

### Overlaps kept deliberately

Three of the 43 fall within 300 m of a named site:

| placeholder | distance | named site |
|---|---|---|
| `station35` | 31 m | Tartu Kvissentali Olerex |
| `station43` | 154 m | Tartu Lounakeskuse Circle K |
| `station17` | 294 m | Poltsamaa Olerex |

Almost certainly the same forecourt seen from the road. Kept, because a duplicate costs one refused
capture while guessing wrong about which coordinate is the real board costs the board.

`station34` is the most-seen position in the set — **15 runs** — which makes it the best-evidenced lead
and the first one worth naming.

### What it costs

84 upserts per setup pass rather than 41, on the main thread, several times per launch. Still trivial
against an indexed primary key, but it is no longer free; if a frame trace ever points at setup, this is
the thing to gate behind a row count.

Clustering gets worse again: 8 pairs inside the detected list are under 550 m of each other, on top of
the 17 among the named set. Every such pair is one capture and one refusal. Accepted under the
blocked-neighbour decision — **and the reason that decision now needs verifying rather than assuming.**

---

## 2026-08-30 — 41 hand-surveyed stations seeded on every setup pass

**Added** `capture/SurveyedStations.kt`
**Touched** `AppSetup.kt` — one import, one constant pair, one `step()` call

### Why

`location_mapping` is filled from Azure Maps at 02:00 daily. That gives coverage, not confirmation: the
route analyses found **29 real pipeline detections across four drives and one of them within 200 m of a
mapped station**, median distance 3.9 km. The sweep describes Estonia. It does not describe the boards
this fleet reads.

The surveyed list is the other way round — 41 sites somebody stood in front of, coordinate taken at the
forecourt. It is seeded on every `AppSetup` pass, so a wiped database, a failed migration or a fresh
install gets them back without anybody asking.

### It cannot destroy anything

Through `LocationMap.upsertAll`, which never deletes and never writes `last_capture_time`. So:

- the 02:00 sweep cannot erase a surveyed row
- a re-seed cannot erase capture history
- a surveyed row and a swept row for the same forecourt coexist as two rows

Ids are `surveyed:<slug>` derived from the name, which makes the seed idempotent and makes provenance
obvious in a log line or a `sqlite3` dump.

### Fuel-type sequences were dropped

The source list carried 95 / 98 / D / LPG / CNG / AdBlue per site. Stage 0 compares coordinates and
nothing else, and the pipeline reads the sequence off the board rather than trusting a stored
expectation. Storing it would create a second source of truth for something the board already states.

### Four judgement calls in the source data

| entry | issue | decision |
|---|---|---|
| Tartu Kvissentali Circle K | written `58,40682,26.70912` — comma for a decimal point | read as `58.40682` — puts it 90 m from the Olerex on the same road, consistent with the pair |
| Põltsamaa Jetoil | annotated "NO FOUND", coordinate given | **included** — a coordinate is what the trigger needs; commented so it is easy to delete |
| Ahja Olerex | annotated "NOT FOUND", coordinate given | **included**, same reasoning |
| Puhu risti / Põltsamaa Olerex and Alexela | 1.3 m and 1.1 m apart — two names, or one site twice | **both kept.** A duplicate costs one refused capture; a wrongly-deleted site costs a board forever |

Diacritics are stripped from the stored names (`Lounakeskuse`, not `Lõunakeskuse`) because ids and names
end up in log lines and `adb` output, and every one of those has mangled `õ` and `ä` in this project.

### What it costs

**17 of the 41 sites sit closer to another surveyed site than the 550 m a capture occupies the lane
for.** Verified by computing every pair:

```
   1.1 m  Puhu risti Alexela          / Poltsamaa Alexela
   1.3 m  Puhu risti Olerex           / Poltsamaa Olerex
  57.8 m  Tartu Ringtee Circle K      / Tartu Ropka Neste
 195.9 m  Tartu Lounakeskuse Circle K / Tartu Lounakeskuse Neste
 251.8 m  Peetri Neste                / Peetri Olerex
 ... 12 more under 550 m
```

Every one of those pairs will produce one capture and one "already recording" refusal. That is accepted
under the blocked-neighbour decision below — the pipeline attributes a neighbour's board from a shared
clip. **If that attribution does not in fact happen, this list makes the problem 17 times worse than a
single pair did**, and the trigger will need to defer blocked stations after all.

### To revisit

If the surveyed rows prove better than the swept ones, the 02:00 sweep is a liability rather than a
backstop and should be scoped to corridors the survey has not covered. That is answerable once a drive
has run against both: compare readings credited to `surveyed:*` ids against readings credited to swept
ids.

---

## 2026-08-30 — Enter radius 250 m, exit radius 300 m

**Was** `ENTER_RADIUS_M = 200.0`, `EXIT_RADIUS_M = 250.0`
**Now** `ENTER_RADIUS_M = 250.0`, `EXIT_RADIUS_M = 300.0`
**File** `capture/LocationTrigger.kt`

### Why

Not station count — **approach footage**. A fixed radius means the further a board sits off the road,
the shorter the chord through the circle and the later the trigger fires. Measured at 90 km/h:

| offset from road | approach before closest point @ 200 m | @ 250 m |
|---|---|---|
| 100 m | 6.9 s | 11.5 s |
| 150 m | 5.3 s | 10.0 s |
| 190 m | **2.5 s** | 8.1 s |

A board 190 m off the road was getting two and a half seconds of footage. That is the failure the
change addresses.

Simulated against the four recorded routes, 250 m also brings in stations that were missed on every
route: one at **249 m** (route D, 101.0 km) enters outright, and two at **265 / 266 m** — the Alexela at
`58.708499, 25.891846`, a near-miss on routes A, B *and* D and never captured on any of them — come
within GPS noise of firing.

The exit radius moves with it. **It must always be `ENTER + 50`.** That 50 m band is the hysteresis; a
board that freezes and releases on the same distance chatters on every fix. There is now a test pinning
the band width, not just the ordering.

### What it costs

Lane occupancy. A capture holds the camera from fire to release: **550 m of travel** now, up from 450 m.
A station closer than that to its neighbour will be refused with "already recording".

On route D's Tartu cluster — ten stations in 9.7 km with gaps of 100, 600, 600, 700 and 900 m — this
takes the count of too-short gaps from **1 of 8 to 3 of 8**. The change helps rural high-offset stations
and makes dense urban clusters worse. That trade was made knowingly.

### To revisit

Simulation says `LEAD_SECONDS = 8` on a speed-scaled radius would have gained **nothing** at any legal
speed: it pins to a 200 m floor until 90 km/h and reaches only 244 m at 110 km/h, while the misses sit
at 249–266 m. A speed-scaled radius only starts earning its keep at `LEAD_SECONDS = 12`, and that
worsens clustering further. A flat 250 m is the simpler answer and was chosen over it.

---

## 2026-08-30 — The blocked neighbour is not a bug

**Decision** When two stations are close enough that a capture for one refuses a capture for the other,
the second station is **not** captured on that visit, and that is accepted. No queueing, no second lane,
no deferred capture.

### Why

The pipeline after Stage 1 handles it. A clip is not a clip *of one station* — it is footage of a
stretch of road, and a board belonging to the neighbouring station may well be in frame. Deciding
which board a reading belongs to is a downstream job that already exists, and duplicating that judgement
in the trigger would put the same decision in two places.

### What it means in practice

Observed on the Tallinn run of 2026-08-30: Alexela and Euro Oil Ääsmäe sit 30 m apart. Alexela captured;
Euro Oil fired every 2 s for ten seconds, was refused every time, and dropped out of range. Its
`last_capture_time` stays null.

So this is **expected behaviour, not a defect to be logged**:

- `CaptureLane: start (geofence: …) refused — already recording` in the log, repeatedly
- a station in the map with no `last_capture_time` after a drive that passed it
- fewer clips than stations on a clustered route

None of that raises an error row, and none of it should.

### The one thing to keep an eye on

A blocked station is never frozen — the freeze happens only when a recorder actually rolls — so it fires
on every fix while it is in range. That is a stream of log lines and a wasted comparison, not a fault.
If the lane frees up while the station is still in range, it *will* capture, late, producing a short
departure clip. Those short clips are the visible symptom of this decision.

### To revisit

If the downstream stages turn out **not** to attribute a neighbour's board from another station's clip,
this decision is wrong and the trigger needs to defer the blocked station rather than drop it. That is a
question for whoever owns Stage 4 attribution, and it is answerable by checking whether a clip captured
at station A ever yields a reading credited to station B.

---

## 2026-08-30 — Stage 0 does not start Stage 1

**Decision** Storing a clip and processing it are separate events. `ProcessingLane.add()` no longer
kicks the drain, and `ProcessingLane.start()` no longer kicks it either. Stage 1 begins only when
something explicitly asks (`requestDrain()`, reachable as the `FLEET_PROCESS` intent).

### Why

The two lanes compete for the same CPU, thermal budget and disk. On the Tallinn drive one departure
check overshot its release by **187 m** — roughly ten seconds of missed location callbacks — while the
queue was draining nine clips. The pipeline was stealing from the camera, which is the wrong way round:
a missed station is data lost forever, a delayed clip is data delayed.

### What it costs

**Nothing processes unless asked.** A queue left unasked grows until the 22 GB floor stops the unit
recording altogether. There is currently no automatic asker — the gap is known and open.

The offline-resume kick remains, because it continues a drain that *was* asked for and was held for lack
of network.

---

## 2026-08-30 — Kiosk mode is forced on at every launch

**Decision** `AppSetup.ensureKioskMode` turns kiosk back on at every launch whatever the stored setting
says. A release from the menu holds only for the life of the process.

### Why

Kiosk is not a UI preference. It is what holds the app on screen, which is the only way a `camera`
foreground service can be started at all, and it is the switch every watchdog path checks before
bringing the app back. A unit sitting with kiosk off will not record with the screen asleep and will not
recover from a crash — and nothing about the truck says so.

### What it costs

The stored setting no longer survives a restart, so "off" is a temporary state rather than a property of
the device. The session-scoped hold (`KioskMode.wasReleasedThisSession`) is what stops setup flicking
the switch back under a technician's finger; without it the device could not be serviced.

---

## 2026-08-29 — The camera FGS type is promoted by the lane, not the service

**Decision** `CaptureLane` asks for the `camera` foreground-service type immediately before binding the
camera. `FleetService.fireCaptureStart` no longer promotes at all.

### Why

`promoteToCameraType()` was reachable only from the debug button and the adb intent. The autonomous path
went `LocationTrigger → CaptureLane.beginCapture → camera` and never touched the service — so **the one
path the product depends on was the one path that never promoted.** On Android 14+ that means the
service holds only `location` while an automatic capture opens the camera, and it fails in the worst
available shape: the service stays up, the readout still says WATCHING, and the clips are black.

A "single entry point" that two of three callers bypass is not a single entry point.

### What it costs

Nothing measurable. The promotion is idempotent and short-circuits once held. A refusal now aborts the
capture rather than rolling a camera that will write a black clip; the board is not frozen at that
point, so the trigger asks again on the next fix.

The success path logs `camera foreground-service type HELD` — the absence of that line is what this bug
looked like from outside for months.
