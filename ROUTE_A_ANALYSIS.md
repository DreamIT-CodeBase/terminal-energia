# Route A — what is actually on this road

`A_2026-07-23_tartu-poltsamaa-jarvajaani-loop_simple.gpx`

| | |
|---|---|
| Length | **124.0 km**, 348 track points |
| Start | `58.522210, 26.311640` (Tartu) |
| End | `58.460250, 26.652610` (Tartu) |
| Shape | loop, Tartu → Põltsamaa → Järva-Jaani → back |
| Station table | `location_map.db`, 349 rows |

Distances below are **point-to-polyline against the GPX track**, not to a straight line between
endpoints — so "offset" is how far the station sits from the road you actually drive, and "km" is arc
length from the start.

---

## 1. Every station near the road

Eight stations lie within 3 km of the driven line. Four are inside the trigger's 200 m radius.

| km | offset | fires | station | brand | latitude, longitude |
|---|---|---|---|---|---|
| 26.3 | **46 m** | ✅ | Alexela Oil Põltsamaa | Alexela | 58.663215, 25.990796 |
| 26.8 | **91 m** | ✅ | Olerex | Olerex | 58.665858, 25.984204 |
| 54.1 | **60 m** | ✅ | Alexela | Alexela | 58.745819, 25.768045 |
| 54.2 | 1619 m | — | Olerex | Olerex | 58.731760, 25.763110 |
| 54.3 | 1404 m | — | Alexela | Alexela | 58.733432, 25.765312 |
| 62.7 | 266 m | — | Alexela | Alexela | 58.708499, 25.891846 |
| 70.5 | 1639 m | — | Alexela | Alexela | 58.653066, 25.967208 |
| 103.9 | **84 m** | ✅ | Alexela Oil Laeva | Alexela | 58.470460, 26.360973 |

### By distance band

| band | count | what it means from the driver's seat |
|---|---|---|
| ≤ 200 m | **4** | fires today |
| 200–500 m | **1** | you drive straight past it and see the board; nothing is recorded |
| 500 m – 1 km | 0 | — |
| 1–2 km | **3** | a deliberate detour, not something you pass |
| 2–3 km | 0 | — |

### The number that matters

**Five stations are genuinely roadside. The app captures four of them.**

Not "4 of 8" — the three at 1.4–1.6 km are off in Järva-Jaani / Imavere and are not on this drive in any
meaningful sense. The single real miss is the **Alexela at 62.7 km, 266 m off** — close enough to see
from the car, 66 m outside the radius, and it records nothing.

That station is a near-miss at **265–266 m on routes A, B and D alike**. No route in the set ever
captures it. It is the clearest argument in the data for a speed-scaled enter radius: at 90 km/h an
eight-second approach puts the radius at 200 m, and 266 m is only 10.6 seconds.

### An offset belongs to the route, not the station

Two of the three "detour" stations — `58.731760, 25.763110` and `58.733432, 25.765312` — are **roadside
on routes C and D**, at 96–200 m. Same stations, different road. A station is never simply near or far;
it is near or far *for a given drive*, which is why coverage has to be judged per route rather than per
station.

---

## 2. The gaps

Four firing stations across 124 km, and they are not evenly spread:

```
start ──32 min── ✅26.3  ─1 min─ ✅26.8 ──33 min── ✅54.1 ──────60 min────── ✅103.9 ──24 min── end
```

| leg | distance | at 50 km/h |
|---|---|---|
| start → Põltsamaa | 26.3 km | 32 min |
| Põltsamaa → Olerex | **0.5 km** | 1 min |
| Olerex → Alexela (54.1) | 27.3 km | 33 min |
| Alexela → Laeva | **49.8 km** | **60 min** |
| Laeva → end | 20.1 km | 24 min |

Two consequences for testing:

- **A full run is ~2½ hours for four captures.** Most of it is dead time. If the goal is to exercise the
  trigger rather than the route, route C (58.9 km, 4 firing stations) or the Tartu end of route D is a
  far better use of the clock.
- **The 0.5 km pair at 26.3 / 26.8 km will reproduce the blocked-neighbour failure** seen on the Tallinn
  run: the first station captures, the second is refused for "already recording" every 2 s, and is
  either lost or gets a 4-second tail as the vehicle departs. Watch that pair specifically.

---

## 3. The July drive of this same route — and the problem it exposes

The GPX carries eight waypoints from a real drive on 2026-07-23, each named with a pipeline run label
and a price count. All eight sit **3–36 m from the driven line**, so they are on-road vehicle positions,
not off-route markers.

| km | off-route | prices | position |
|---|---|---|---|
| 18.6 | 4 m | 0 | 58.634880, 26.111483 |
| 33.9 | 4 m | 0 | 58.702361, 25.901445 |
| 36.7 | 5 m | **2** | 58.721414, 25.871872 |
| 48.7 | 3 m | 0 | 58.792439, 25.758077 |
| 80.4 | 18 m | **3** | 58.631316, 26.136060 |
| 97.4 | 3 m | **4** | 58.522199, 26.311600 |
| 110.5 | 5 m | **4** | 58.439588, 26.456544 |
| 124.0 | 36 m | **2** | 58.460530, 26.652937 |

**Eight detections, five with prices, 15 prices total.**

Now compare where they happened with where the map has stations:

```
detections     18.6      33.9  36.7   48.7            80.4        97.4      110.5   124.0
map stations              26.3 26.8         54.1                        103.9
```

**Not one of the eight is within 200 m of a mapped station.** Measured as straight-line distance to the
nearest station in the table, the eight detections sit at **0.88, 1.84, 5.22, 6.43, 6.53, 6.83, 7.66 and
9.12 km** — a median of about 6 km. The single closest, at 880 m, is the one that returned **0 prices**;
every detection that actually yielded prices was 1.8 km or more from any station the map knows about.

Across all four routes: 29 detections, **one** within 200 m of a mapped station, median **3.9 km**. The
overlap is partial and route-dependent — route B has three detections inside 600 m of a station, route C
none closer than 2.6 km — so this is a coverage gap in specific corridors rather than a uniformly wrong
table. See `ROUTES.md` and the per-route analyses.

### What that means

The geofence trigger on this route will fire at four places where the July drive recorded nothing, and
will stay silent at all eight places where it produced readings — including the five that yielded
prices.

Two readings, and they must be separated before the map is trusted:

1. **The station table does not cover this corridor.** If readable boards exist where those waypoints
   are, no radius and no heading gate will ever reach them. The trigger cannot outperform its table.
2. **The waypoints came from a different capture regime** — continuous or interval capture rather than
   geofence. In that case they are not evidence about triggering, but they *are* direct evidence about
   where readable boards sit on this road, which is the same thing the map is supposed to encode.

Either way the conclusion is the same, and it outranks the trigger work: **before tuning the radius or
adding a heading gate, confirm that the station table describes the roads being driven.** Tuning a
trigger against a table that does not match the road is optimising the wrong half of the system.

---

## 4. What to watch on this run

| km | what to look for |
|---|---|
| 26.3 → 26.8 | the blocked-neighbour case. Expect one clean capture and one refusal storm, then either nothing or a ~4 s tail clip |
| 54.1 | an isolated capture — the clean baseline for clip length and release distance |
| 62.7 | **silence.** Confirm nothing fires for the 266 m Alexela; that absence is the data point |
| 103.9 | second isolated capture. Compare its release distance against 54.1's — if neither overshoots, the 437 m overshoot on the Tallinn run was CPU starvation from the processing lane, which is now decoupled |
| anywhere | releases should land at 250–290 m. Anything beyond ~300 m means location callbacks are being delayed |

## Method

Point-to-polyline distance in a local equirectangular projection centred on the route, accurate to well
under a metre at these latitudes. Reproduce with:

```
adb exec-out run-as com.terminalscout.app.prod cat databases/location_map.db > location_map.db
adb pull /sdcard/routes/A_2026-07-23_tartu-poltsamaa-jarvajaani-loop_simple.gpx
```
