# Test routes

The four GPX routes on the device (`/sdcard/routes/`), measured against the station table in
`location_map.db` (349 rows).

For each route: every station whose position falls within **300 m** of the driven line, with its
distance along the route and its perpendicular offset. A station is marked `*` when it is inside the
trigger's 200 m enter radius — i.e. when the app will actually fire for it.

Distances are computed point-to-polyline against the GPX track, not against a straight line between
endpoints, so they are the real driven geometry.

---

## The finding that matters most

Each GPX also carries **waypoints from a real drive of that route**, named with a run label and a price
count — `20260723-091846-b934 (4 prices)`. They are where this app's pipeline previously produced a
reading.

Across all four routes there are **29 such detections. Exactly one of them is within 200 m of a station
in the map.**

| | |
|---|---|
| detections | 29 |
| within 200 m of a mapped station | **1** |
| nearest / median / farthest | 0.1 km / **3.9 km** / 17.7 km |

The median real detection is **almost four kilometres** from the nearest station the trigger knows
about. Whatever produced those readings was not the geofence trigger firing on this station table.

Two readings, and they need separating before the map is trusted:

1. **The map does not cover these corridors.** If there are readable price boards where those waypoints
   are, the geofence will never fire for them no matter how the radius or the heading gate is tuned. The
   trigger can only be as good as the table.
2. **The waypoints came from a different capture regime** — a continuous or manual drive rather than a
   geofence one — in which case they say where boards *are* on these roads, which is still exactly the
   information needed to judge the map's coverage.

Either way the conclusion for testing is the same: **a route's value as a trigger test is set by the
`*` count below, not by how far it is.**

---

## Route A — Tartu · Põltsamaa · Järva-Jaani loop

`A_2026-07-23_tartu-poltsamaa-jarvajaani-loop_simple.gpx` — **124.0 km**, 348 track points.
Start `58.522210, 26.311640` → end `58.460250, 26.652610`.

**4 stations will fire. 1 will not, by 66 m.**

| along | offset | | station | latitude, longitude |
|---|---|---|---|---|
| 26.3 km | 46 m | `*` | Alexela Oil Põltsamaa | 58.663215, 25.990796 |
| 26.8 km | 91 m | `*` | Olerex | 58.665858, 25.984204 |
| 54.1 km | 60 m | `*` | Alexela | 58.745819, 25.768045 |
| 62.7 km | 266 m | — | Alexela | 58.708499, 25.891846 |
| 103.9 km | 84 m | `*` | Alexela Oil Laeva | 58.470460, 26.360973 |

July drive: 8 detections, 5 with prices, 15 prices total.

**What this route tests:** long isolated gaps — 27 km, then 50 km, then 41 km between firing stations.
The opposite of the Tallinn run. The pair at 26.3 / 26.8 km is 500 m apart, so it will reproduce the
blocked-neighbour case exactly once.

---

## Route B — Tartu · Paide loop

`B_2026-07-30_tartu-paide-loop_simple.gpx` — **149.9 km**.

**5 will fire. 1 near-miss at 265 m.**

| along | offset | | station | latitude, longitude |
|---|---|---|---|---|
| 29.3 km | 46 m | `*` | Alexela Oil Põltsamaa | 58.663215, 25.990796 |
| 29.7 km | 91 m | `*` | Olerex | 58.665858, 25.984204 |
| 66.6 km | 28 m | `*` | Neste | 58.900544, 25.636198 |
| 87.1 km | 60 m | `*` | Alexela | 58.745819, 25.768045 |
| 95.7 km | 265 m | — | Alexela | 58.708499, 25.891846 |
| 136.9 km | 84 m | `*` | Alexela Oil Laeva | 58.470460, 26.360973 |

July drive: 7 detections, 4 with prices, 12 prices total.

---

## Route C — Imavere · Türi west, morning

`C_2026-08-19_morning_imavere-turi-west_simple.gpx` — **58.9 km**. The shortest, and the quickest test.

**4 will fire. 1 sits at exactly 200 m — a coin flip.**

| along | offset | | station | latitude, longitude |
|---|---|---|---|---|
| 3.7 km | 177 m | `*` | Alexela | 58.745819, 25.768045 |
| 5.0 km | 96 m | `*` | Alexela | 58.733432, 25.765312 |
| 5.2 km | 200 m | — | Olerex | 58.731760, 25.763110 |
| 35.0 km | 30 m | `*` | Alexela Oil Türi | 58.801457, 25.429420 |
| 37.3 km | 54 m | `*` | Olerex | 58.814125, 25.403561 |

August drive: 4 detections, 3 with prices, 8 prices total.

**What this route tests:** the boundary. The Olerex at 5.2 km is on the radius to the metre — whether it
fires is decided by GPS noise, which is the single best demonstration of why a hard radius is the wrong
primitive. Three stations inside 1.5 km will also stack the blocked-neighbour case.

---

## Route D — Türi · Põltsamaa · Tartu, evening

`D_2026-08-19_evening_turi-poltsamaa-tartu_simple.gpx` — **119.7 km**. **The richest route by far.**

**15 will fire. 2 near-misses.**

| along | offset | | station | latitude, longitude |
|---|---|---|---|---|
| 24.5 km | 200 m | `*` | Olerex | 58.731760, 25.763110 |
| 24.7 km | 98 m | `*` | Alexela | 58.733432, 25.765312 |
| 26.1 km | 94 m | `*` | Alexela | 58.745819, 25.768045 |
| 34.6 km | 266 m | — | Alexela | 58.708499, 25.891846 |
| 42.5 km | 91 m | `*` | Olerex | 58.665858, 25.984204 |
| 43.0 km | 51 m | `*` | Alexela Oil Põltsamaa | 58.663215, 25.990796 |
| 75.9 km | 84 m | `*` | Alexela Oil Laeva | 58.470460, 26.360973 |
| 98.6 km | 53 m | `*` | Neste | 58.375012, 26.678623 |
| 100.1 km | 96 m | `*` | Neste | 58.361834, 26.675402 |
| 100.1 km | 124 m | `*` | Alexela | 58.360943, 26.674736 |
| 100.8 km | 63 m | `*` | Alexela Oil Lõunakeskus | 58.357557, 26.680046 |
| 101.0 km | 249 m | — | Alexela | 58.356495, 26.686721 |
| 101.4 km | 74 m | `*` | Alexela | 58.360200, 26.689915 |
| 103.7 km | 147 m | `*` | Alexela Oil | 58.371820, 26.721422 |
| 104.4 km | 186 m | `*` | Circle K Tartu Turu | 58.377164, 26.731684 |
| 107.4 km | 22 m | `*` | Olerex | 58.398686, 26.709520 |
| 108.3 km | 70 m | `*` | Circle K Tartu Kvissentali | 58.406774, 26.709566 |

August drive: 10 detections, 7 with prices, 22 prices total.

**What this route tests, and why it should be next:** the last 10 km through Tartu holds **ten stations**,
several within 200 m of *each other*. That is the blocked-neighbour failure at full strength — with a
single capture lane and no queueing of blocked boards, most of that cluster cannot be captured. It is
the sternest available test of the clustering problem and the strongest evidence for whatever is built
to fix it.

---

## Summary

| route | length | will fire | near-miss | July/Aug detections | with prices |
|---|---|---|---|---|---|
| A — Põltsamaa · Järva-Jaani | 124.0 km | 4 | 1 | 8 | 5 |
| B — Paide | 149.9 km | 5 | 1 | 7 | 4 |
| C — Türi west | 58.9 km | 4 | 1 | 4 | 3 |
| **D — Türi · Põltsamaa · Tartu** | 119.7 km | **15** | 2 | 10 | 7 |

One station — `Alexela 58.708499, 25.891846` — is a near-miss on **A, B and D**, at 265–266 m every
time. It sits just outside the radius on three separate routes and will never be captured on any of
them. It is the clearest single argument for a speed-scaled radius: at 90 km/h an 8-second lead puts the
enter radius at 200 m, but 266 m is only 10.6 seconds of approach.

## Method

Distances are point-to-polyline against the GPX track in a local equirectangular projection, which is
accurate to well under a metre at these latitudes and scales. "Along" is arc length from the route
start. Station coordinates are from `location_map.db` as pulled from the device.

Reproduce with the script in the scratchpad, or against a fresh pull of the table:

```
adb exec-out run-as com.terminalscout.app.prod cat databases/location_map.db > location_map.db
adb pull /sdcard/routes/<route>.gpx
```
