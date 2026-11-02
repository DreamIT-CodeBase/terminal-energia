# Route B — what is actually on this road

`B_2026-07-30_tartu-paide-loop_simple.gpx`

| | |
|---|---|
| Length | **149.9 km**, 424 track points |
| Start | `58.498080, 26.330980` (Tartu) |
| End | `58.459970, 26.540780` (Tartu) |
| Shape | loop, Tartu → Põltsamaa → Mäo/Paide → Järva-Jaani → back |
| Station table | `location_map.db`, 349 rows |

Distances are **point-to-polyline against the GPX track**. "Offset" is how far the station sits from the
road actually driven; "km" is arc length from the start.

---

## 1. Every station near the road

Ten stations lie within 3 km of the driven line. Five fire.

| km | offset | fires | station | latitude, longitude |
|---|---|---|---|---|
| 29.3 | **46 m** | ✅ | Alexela Oil Põltsamaa | 58.663215, 25.990796 |
| 29.7 | **91 m** | ✅ | Olerex | 58.665858, 25.984204 |
| 66.6 | **28 m** | ✅ | Neste | 58.900544, 25.636198 |
| 66.7 | 2287 m | — | Neste Mäo A24 | 58.919043, 25.614236 |
| 87.1 | **60 m** | ✅ | Alexela | 58.745819, 25.768045 |
| 87.2 | 1618 m | — | Olerex | 58.731760, 25.763110 |
| 87.3 | 1404 m | — | Alexela | 58.733432, 25.765312 |
| 95.7 | 265 m | — | Alexela | 58.708499, 25.891846 |
| 103.5 | 1638 m | — | Alexela | 58.653066, 25.967208 |
| 136.9 | **84 m** | ✅ | Alexela Oil Laeva | 58.470460, 26.360973 |

### By distance band

| band | count | from the driver's seat |
|---|---|---|
| ≤ 200 m | **5** | fires today |
| 200–500 m | **1** | driven straight past, seen, not recorded |
| 500 m – 1 km | 0 | — |
| 1–2 km | **3** | a detour |
| 2–3 km | **1** | a detour |

### The number that matters

**Six stations are genuinely roadside. The app captures five.**

The one miss is the same **Alexela at 95.7 km, 265 m off** that route A misses at 266 m and route D at
266 m. Three routes, three near-misses, never once captured — the strongest single case in the whole
data set for a speed-scaled enter radius.

Note `Neste Mäo A24` at 66.7 km / 2287 m sitting almost alongside the `Neste` that fires at 28 m. Two
Neste sites 2.3 km apart, one on the road and one off it: a reminder that brand and proximity say
nothing about whether a station is drivable-past.

---

## 2. The gaps

Five firing stations across 149.9 km:

```
start ──35 min── ✅29.3 ─1 min─ ✅29.7 ──44 min── ✅66.6 ──25 min── ✅87.1 ──60 min── ✅136.9 ──16 min── end
```

| leg | distance | at 50 km/h |
|---|---|---|
| start → Põltsamaa | 29.3 km | 35 min |
| Põltsamaa → Olerex | **0.5 km** | 1 min |
| Olerex → Neste (Mäo) | 36.8 km | 44 min |
| Neste → Alexela (87.1) | 20.5 km | 25 min |
| Alexela → Laeva | **49.8 km** | 60 min |
| Laeva → end | 13.0 km | 16 min |

**A full lap is about 3 hours for five captures.** The longest route in the set and not the richest — D
returns three times as many captures in 30 fewer kilometres.

The 0.5 km pair at 29.3 / 29.7 km is the same Põltsamaa pair route A has, and will reproduce the
blocked-neighbour failure identically.

---

## 3. The July drive of this same route

Seven waypoints from 2026-07-30, all 0–124 m from the driven line, so all on-road vehicle positions.

| km | off-route | prices | position |
|---|---|---|---|
| 37.2 | 38 m | **3** | 58.706074, 25.900267 |
| 42.8 | 0 m | 0 | 58.736738, 25.826552 |
| 66.7 | 124 m | 0 | 58.902161, 25.632848 |
| 70.9 | 14 m | **4** | 58.880147, 25.690941 |
| 103.7 | 1 m | **3** | 58.664910, 25.984102 |
| 133.3 | 4 m | 0 | 58.498088, 26.331054 |
| 149.9 | 22 m | **2** | 58.460144, 26.540590 |

**Seven detections, four with prices, 12 prices total.**

### Where the map does and does not agree

Straight-line distance from each detection to the nearest mapped station:

| distance | prices | nearest station |
|---|---|---|
| **0.11 km** | **3** | Olerex |
| **0.26 km** | 0 | Neste |
| **0.56 km** | **3** | Alexela |
| 3.52 km | 0 | Alexela |
| 3.53 km | 0 | Alexela Oil Laeva |
| 3.88 km | **4** | Neste |
| 10.51 km | **2** | Alexela Oil Laeva |

**This is the best map agreement of the four routes.** Three detections land within 560 m of a station,
two of them with prices — the Paide/Mäo corridor is genuinely covered. The detection at 103.7 km, 110 m
from the Olerex, is the *only* case across all four routes where a real reading and a mapped station
essentially coincide.

The other four are 3.5–10.5 km out, so coverage is partial even here. But route B is the one route where
the geofence trigger and the historical readings would have overlapped at all, which makes it the best
available route for testing whether a firing station actually yields prices.

---

## 4. What to watch

| km | what to look for |
|---|---|
| 29.3 → 29.7 | blocked-neighbour case — one capture, then a refusal storm, then nothing or a ~4 s tail |
| 66.6 | isolated capture, and the closest station to a real 0-price detection. Does the clip show a board? |
| 87.1 | isolated capture — clean baseline for clip length and release distance |
| 95.7 | **silence.** Confirm nothing fires for the 265 m Alexela |
| 103.7 | the July drive read 3 prices here, 110 m from the Olerex that fires at 29.7 km — check whether this position is anywhere near a firing station on this pass |
| 136.9 | final capture. Compare release distance with 87.1's |
| anywhere | releases should land at 250–290 m; beyond ~300 m means delayed location callbacks |

## Method

Point-to-polyline distance in a local equirectangular projection centred on the route. Reproduce with:

```
adb exec-out run-as com.terminalscout.app.prod cat databases/location_map.db > location_map.db
adb pull /sdcard/routes/B_2026-07-30_tartu-paide-loop_simple.gpx
```
