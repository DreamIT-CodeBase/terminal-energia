# Field tests

Simulated drives against the real trigger. One section per run, newest last. Each records what was
driven, what the unit actually did, and what changed as a result.

The point of writing these down is that Stage 0's failures are **quiet** — a missed station leaves no
error, no crash and no gap anyone would notice from the outside. The only way they surface is by driving
a known route and counting what came back.

---

## Run 1 — Tallinn southern approach, 2026-08-30

### Setup

| | |
|---|---|
| Device | OnePlus Nord AC2001, Android 12 |
| Simulator | Lockito (`fr.dvilleneuve.lockito`), set as the system mock location app |
| App build | trigger unchanged — a 200 m circle, fixes every 2 s at `PRIORITY_HIGH_ACCURACY` |
| Speed | 17.78 m/s (64 km/h), constant |
| Fix accuracy | ±2–3 m throughout |
| Route | 15.3 km straight line, `59.312124,24.681801` → `59.444242,24.752360` |

The route was not chosen by eye. It is the straight line that passes within 190 m of the **most stations
in the table** — found by testing every station pair 8–45 km apart against all 349 stations. Ten
stations qualify.

**No app changes were needed to run this.** Lockito drives the fused provider, which is what
`LocationTrigger` already reads. Two preconditions mattered: the app holds `ACCESS_BACKGROUND_LOCATION`,
and Lockito is exempt from battery optimisation.

Kiosk mode was left **on**, and the simulation ran fine underneath it — Lockito does not need the
foreground. Earlier instructions to release kiosk first were unnecessary.

### What came back

| # | station | along | result | clip |
|---|---|---|---|---|
| 1 | Alexela Oil Tammemäe | 0.0 km | good | 18 s · 89 MB |
| 2 | Alexela | 6.9 km | good | 16 s · 81 MB |
| 3 | Euro Oil Ääsmäe | 6.9 km | **never captured** | — |
| 4 | Circle K Nõmme | 9.2 km | good | 20 s · 105 MB |
| 5 | Circle K Järve automaatjaam | 9.3 km | **tail only** | 4 s · 18 MB |
| 6 | Alexela | 11.6 km | good | 36 s · 185 MB |
| 7 | Olerex | 11.9 km | **tail only** | 4 s · 18 MB |
| 8 | Alexela Oil Vana-Lõuna | 12.5 km | **never fired** | — |
| 9 | Alexela Oil Videviku | 12.8 km | good | 15 s · 80 MB |
| 10 | Circle K Linnahalli | 15.3 km | hit the 120 s cap | 120 s · 630 MB |

**5 of 10 stations produced a usable clip.** Total on disk after the run: 10 clips, 1.52 GB. (Two
further clips at 08:12 — 290 MB and 20 MB — predate the log capture and could not be attributed; the
device's log ring buffer had rotated. The buffer is now raised to 32 MB so the next run keeps its
history.)

### What worked, and had never been tested before

**The stop edge.** Every previous test was stationary, so `StopRecordingMonitor` had never once released
a board — clips had only ever ended by hitting the 120 s cap. Six clean departures this run:

```
08:20:40  RELEASE — left Alexela — 270m > 250m
08:22:51  RELEASE — left Circle K Nõmme — 286m > 250m
08:22:56  RELEASE — left Circle K Järve automaatjaam — 256m > 250m
08:25:19  RELEASE — left Alexela — 437m > 250m
08:25:25  RELEASE — left OLEREX — 251m > 250m
08:26:17  RELEASE — left ALEXELA Oil Videviku — 263m > 250m
```

Clips ended at 15–36 s and 80–185 MB instead of the 120 s / 630 MB a capped clip costs. Freeze and
unfreeze paired correctly every time.

**The autonomous path.** Every capture logged `trigger=geofence` — real GPS driving the decision, not a
button.

**The 120 s cap.** The final clip is **correct behaviour, not a failure**: the route ends at Linnahalli,
so the vehicle parked inside the radius and never reached 250 m. A parked vehicle gets exactly one
capped clip and the freeze holds. That is what the cap is for. It cost 630 MB.

### Finding 1 — a station blocks its neighbour, then gets a useless tail

The clearest result of the run, and it happened three times. Alexela and Euro Oil Ääsmäe sit **30 m
apart**:

```
08:20:24  FIRE Alexela 191m → recording, board frozen
08:20:26  Euro Oil Ääsmäe 180m → refused — already recording
08:20:28  170m → refused        08:20:34  181m → refused
08:20:30  167m → refused        08:20:36  198m → refused
08:20:40  Alexela released at 270m — clip done
          Euro Oil is now out of range. Never captured.
```

The blocked station **is never frozen** — the freeze only happens when a recorder rolls — so it fires
every 2 s, is refused every time, and either drops out of range unrecorded, or, if the lane frees up in
time, is captured *as the vehicle leaves*:

```
08:22:51  Circle K Nõmme clip ends (20 s)
08:22:53  Järve fires again → captures → released 4 s later at 256 m  → 4 s, 18 MB
```

Two 4-second clips, both pointing at a receding forecourt.

**Open question the footage must answer:** if two boards 30 m apart are both in frame, one clip covers
both and only the bookkeeping is wrong (`last_capture_time` is stamped for one station and not the
other). If they are not both in frame, every second pump in a cluster is being missed systematically.

### Finding 2 — a mapped station never triggered at all

`Alexela Oil Vana-Lõuna` produced no fire *and no refusal*, so the vehicle never came within 200 m of
it. Its 167 m off-axis figure was measured against a straight geodesic; Lockito follows real roads, and
the actual path passed wider.

This is the honest version of the "the circle misses stations" concern — **not a sampling failure, a
geometry one.** Every fire that did happen landed at 167–198 m with ±2 m accuracy, exactly where a 200 m
circle should fire.

### Finding 3 — one departure check overshot by 187 m

Five releases landed at 251–286 m. One landed at **437 m**, and that clip ran 36 s instead of ~18 s.
That is roughly 10 s of missed location callbacks, and it happened while the processing lane was
draining a queue that had grown to nine clips.

Suspected cause: Stage 1 starving the location callbacks under CPU load. **This is the observation that
prompted decoupling Stage 0 from Stage 1** (below). Worth confirming on the next run — if the overshoot
does not recur with an empty queue, the diagnosis holds.

### What changed as a result

**Stage 0 and Stage 1 are now decoupled.** Storing a clip no longer starts processing:

- `ProcessingLane.add()` no longer kicks the drain — a capture queues and stops there.
- `ProcessingLane.start()` no longer kicks either — a restart restores the queue without draining it.
- Stage 1 begins only when asked: `ProcessingLane.requestDrain()`, reachable as
  `FLEET_PROCESS` over adb.
- The offline-resume kick stays: it continues a drain that *was* asked for and was held for lack of
  network.

The cost is deliberate and must be designed around: **nothing processes a clip unless it is asked to**,
so a queue left unasked grows until the 22 GB floor stops the unit recording. What does the asking is a
policy decision that is still open.

**A capture status screen was added** (Menu → Capture status) showing the board currently being recorded
with its name and coordinates, and the queue with its size and depth. It reads `FleetBus` only.

### What this says about the trigger redesign

The run supports the concern about a fixed 200 m circle but **relocates the cause**. The GPS sampling
rate was never the problem: at 2 s and 64 km/h the vehicle moves 36 m between fixes, and every station
that fired did so between 167 m and 198 m with 2–3 m accuracy.

What cost five stations was:

1. **No notion of direction.** The trigger fires whether the board is ahead through the windscreen or
   behind in the mirror. Both 4-second tails would have been suppressed by a heading gate.
2. **No handling of clustered sites.** One capture at a time, and the blocked station is never frozen,
   so it is either lost or captured too late.
3. **Route geometry**, for which a circle of any radius is the wrong primitive.

So the proposal in the *Trigger Geometry* note stands, with a third item added: **capture the nearest
unfrozen board and do something deliberate with the blocked one** — queue it, or freeze it as covered —
rather than dropping it.

### Next run

- Empty the queue first, to test Finding 3.
- Log heading, speed and the off-axis angle to the board on every fire. That number is what sets the
  heading gate's threshold, and it cannot be chosen honestly without it.
- Note the stations that produce nothing at all. Those are the ones that need an approach corridor, and
  they are invisible unless recorded as they are passed.
