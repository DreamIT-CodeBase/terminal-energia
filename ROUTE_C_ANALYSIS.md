# Route C — what is actually on this road

`C_2026-08-19_morning_imavere-turi-west_simple.gpx`

| | |
|---|---|
| Length | **58.9 km**, 172 track points |
| Start | `58.735250, 25.830030` (Imavere) |
| End | `58.809710, 25.069490` (west of Türi) |
| Shape | one-way, west |
| Station table | `location_map.db`, 349 rows |

Distances are **point-to-polyline against the GPX track**. "Offset" is how far the station sits from the
road actually driven; "km" is arc length from the start.

---

## 1. Every station near the road

Only five stations lie within 3 km of this line — and **nothing at all between 500 m and 3 km.** The
sparsest route in the set, and the cleanest: every station near this road is either genuinely roadside
or absent entirely.

| km | offset | fires | station | latitude, longitude |
|---|---|---|---|---|
| 3.7 | **177 m** | ✅ | Alexela | 58.745819, 25.768045 |
| 5.0 | **96 m** | ✅ | Alexela | 58.733432, 25.765312 |
| 5.2 | **200 m** | ⚠️ | Olerex | 58.731760, 25.763110 |
| 35.0 | **30 m** | ✅ | Alexela Oil Türi | 58.801457, 25.429420 |
| 37.3 | **54 m** | ✅ | Olerex | 58.814125, 25.403561 |

### By distance band

| band | count | from the driver's seat |
|---|---|---|
| ≤ 200 m | **4** | fires today |
| 200–500 m | **1** | on the radius — see below |
| 500 m – 3 km | **0** | nothing |

### The station on the boundary

The Olerex at 5.2 km sits at **200 m to the metre**. `ENTER_RADIUS_M = 200.0` and the comparison is
`nearestM <= ENTER_RADIUS_M`, so whether it fires is decided by whichever side of 200.0 the arithmetic
lands on for a given fix — which is decided by GPS noise of ±2–3 m.

**That makes this route the sharpest available demonstration of why a hard radius is the wrong
primitive.** The same station, on the same road, at the same speed, will fire on some passes and not
others. Nothing about the site changed; only the noise did. Run this route twice and compare.

It is also the tightest cluster in the set: **three stations inside 1.5 km** (3.7, 5.0, 5.2 km), all
roadside. With one capture lane and no queueing of blocked boards, expect at most two of those three to
produce a clip, and one of those to be a short tail.

---

## 2. The gaps

```
start ──4 min── ✅3.7 ─2 min─ ✅5.0 ⚠️5.2 ──36 min── ✅35.0 ─3 min─ ✅37.3 ──26 min── end
```

| leg | distance | at 50 km/h |
|---|---|---|
| start → Alexela | 3.7 km | 4 min |
| Alexela → Alexela | **1.3 km** | 2 min |
| Alexela → Olerex (boundary) | **0.2 km** | 15 s |
| Olerex → Türi | 30.0 km | 36 min |
| Türi → Olerex | 2.3 km | 3 min |
| Olerex → end | 21.6 km | 26 min |

**About 70 minutes for four captures — the fastest trigger test in the set**, and the only one where
useful behaviour happens in the first six minutes. Route A needs 32 minutes before anything fires at
all.

If the objective is to exercise the trigger rather than survey a corridor, **drive the first 6 km of
this route and stop.** Three stations, one of them on the radius boundary, one blocked-neighbour pair —
all the interesting failure modes inside four kilometres.

---

## 3. The August drive of this same route

Four waypoints from 2026-08-19, all 1–9 m from the driven line.

| km | off-route | prices | position |
|---|---|---|---|
| 0.0 | 1 m | **4** | 58.735257, 25.830035 |
| 14.2 | 9 m | **3** | 58.688082, 25.659274 |
| 40.2 | 3 m | **1** | 58.813991, 25.359035 |
| 58.9 | 3 m | 0 | 58.809731, 25.069526 |

**Four detections, three with prices, 8 prices total.**

### The worst map agreement of the four routes

Straight-line distance from each detection to the nearest mapped station:

| distance | prices | nearest station |
|---|---|---|
| 2.56 km | **1** | Olerex |
| 3.74 km | **4** | Alexela |
| 7.72 km | **3** | Olerex |
| 17.71 km | 0 | Alexela |

**Nothing closer than 2.6 km.** Every reading this route ever produced came from somewhere the station
table does not describe, and the three that yielded prices are 2.6, 3.7 and 7.7 km from the nearest
mapped site.

The geofence trigger will fire four times on this route, at four places that have never produced a
recorded reading, and will stay silent at all four places that have.

That does not mean the four firing stations are worthless — nobody has driven this route *with the
geofence trigger* yet, which is exactly what the run is for. But it does mean this route cannot
corroborate the map, and a null result here says more about coverage than about the trigger.

---

## 4. What to watch

| km | what to look for |
|---|---|
| 3.7 → 5.2 | the tightest cluster in the set. Three roadside stations in 1.5 km; count how many produce a usable clip rather than a tail |
| **5.2** | **the boundary.** Does the 200 m Olerex fire? Run the route twice and see whether the answer changes. That is the experiment |
| 35.0 → 37.3 | a 2.3 km pair — far enough apart that both should capture cleanly. If one is blocked, the lane is holding the camera longer than it needs to |
| anywhere | releases at 250–290 m. Beyond ~300 m means delayed location callbacks |

## Method

Point-to-polyline distance in a local equirectangular projection centred on the route. Reproduce with:

```
adb exec-out run-as com.terminalscout.app.prod cat databases/location_map.db > location_map.db
adb pull /sdcard/routes/C_2026-08-19_morning_imavere-turi-west_simple.gpx
```
