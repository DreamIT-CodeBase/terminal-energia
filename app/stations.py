"""Station mapping.

Maps a forecourt to its coordinates and, crucially, to the **order** its fuel
types appear on the price board. The detector reports a price per board row
(`fuel_prices[].row_num` in `api_response.json`) but cannot read which fuel a
row belongs to — `fuel_type` comes back as `Unknown`. This mapping is what
turns row 2 at Tartu Ringtee Neste into "98".

The order of `fuel_types` is therefore data, not presentation: it is the
top-to-bottom order of the rows. Räpina Olerex lists 98 before 95, so the
sequence cannot be sorted or normalised.

Held as a JSON file rather than a database: it is small, changes rarely, and
belongs in version control next to the code that reads it.
"""

import json

from pathlib import Path


STATIONS_FILE = Path(__file__).parent / "data" / "stations.json"


_cache: list[dict] | None = None


def load_stations() -> list[dict]:
    """Read the mapping, once per process."""

    global _cache

    if _cache is None:
        with STATIONS_FILE.open(encoding="utf-8") as handle:
            _cache = json.load(handle).get("stations", [])

    return _cache


def find_station(
    station_id: str
) -> dict | None:

    for station in load_stations():
        if station["id"] == station_id:
            return station

    return None


def fuel_type_for_row(
    station_id: str,
    row_num: int,
) -> str | None:
    """The fuel type at a 1-based board row, or None if out of range.

    This is the reason the mapping exists: it resolves a detected row back to
    the product it priced.
    """

    station = find_station(station_id)

    if station is None:
        return None

    sequence = station.get("fuel_types") or []

    if row_num < 1 or row_num > len(sequence):
        return None

    return sequence[row_num - 1]
