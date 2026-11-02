"""Station mapping, held in blob storage.

Maps a forecourt to its coordinates and brand. The document lives at
``configurations/stations-data/stations.json`` rather than in the image
because the fleet writes to it: a device that stops somewhere unmapped
upserts the forecourt it found, and every other device picks it up on its
next fetch.

``id`` is the key. Ids carry their provenance as a prefix -- ``surveyed:``
for a forecourt entered by hand, ``detected:`` for one a device discovered --
and bare ids come from the maps provider. Nothing here parses that prefix; it
is there for a human reading the file.

Writes are a read-modify-write of the whole document, because that is the
shape the document has: one JSON array, not one blob per station. Two devices
upserting at the same moment would otherwise lose one of the two, so the
write is conditional on the etag it read and retried when it loses the race.
"""

import json
import threading
import time

from azure.core import MatchConditions

from azure.core.exceptions import (
    ResourceExistsError,
    ResourceModifiedError,
    ResourceNotFoundError,
)

from app.config import (
    STATIONS_BLOB,
    STATIONS_ENRICHED_BLOB,
    STATIONS_CACHE_TTL_SECONDS,
    STATIONS_CONTAINER,
    STATIONS_WRITE_ATTEMPTS,
    get_blob_service_client,
)


class StationsConflict(RuntimeError):
    """The document changed under every write attempt."""


class StationsCorrupt(RuntimeError):
    """The stored document is not the JSON array this expects."""


# The blob is one array. A dict envelope is accepted on read so an older
# ``{"stations": [...]}`` document still loads, and it is written back in the
# shape it arrived in rather than silently reshaped.
_ENVELOPE_KEY = "stations"


_lock = threading.Lock()

# (stations, etag, envelope_or_None, fetched_at)
_cache: tuple[list[dict], str | None, dict | None, float] | None = None


def _blob_client():
    return (
        get_blob_service_client()
        .get_container_client(STATIONS_CONTAINER)
        .get_blob_client(STATIONS_BLOB)
    )


def load_enriched_stations() -> list[dict]:
    """Read the validation-only enriched station document."""

    try:
        downloader = (
            get_blob_service_client()
            .get_container_client(STATIONS_CONTAINER)
            .get_blob_client(STATIONS_ENRICHED_BLOB)
            .download_blob()
        )
        raw = downloader.readall()

    except ResourceNotFoundError:
        return []

    stations, _ = _parse(raw, require_id=False)

    return stations


def upsert_enriched_station(
    latitude: float,
    longitude: float,
    name: str | None,
    fuel_types: list[dict],
    deleted_row_nums: list[int] | None = None,
    tolerance: float = 0.001,
) -> tuple[dict, bool]:
    """Merge row corrections and deletions into a coordinate-matched record."""

    blob_client = (
        get_blob_service_client()
        .get_container_client(STATIONS_CONTAINER)
        .get_blob_client(STATIONS_ENRICHED_BLOB)
    )

    for _ in range(max(1, STATIONS_WRITE_ATTEMPTS)):

        try:
            downloader = blob_client.download_blob()
            raw = downloader.readall()
            stations, envelope = _parse(raw, require_id=False)
            etag = downloader.properties.etag

        except ResourceNotFoundError:
            stations, envelope, etag = [], None, None

        index = next(
            (
                position
                for position, station in enumerate(stations)
                if isinstance(station.get("latitude"), (int, float)) and
                isinstance(station.get("longitude"), (int, float)) and
                abs(station["latitude"] - latitude) <= tolerance and
                abs(station["longitude"] - longitude) <= tolerance
            ),
            None
        )

        created = index is None

        if created:
            stored = {
                "latitude": latitude,
                "longitude": longitude,
                "name": name,
                "fuel_types": [dict(item) for item in fuel_types],
            }
            updated = [*stations, stored]

        else:
            existing = dict(stations[index])
            existing_types = existing.get("fuel_types") or []
            merged_types = [
                (
                    {"row_num": row + 1, "fuel_type": value}
                    if isinstance(value, str)
                    else dict(value)
                )
                for row, value in enumerate(existing_types)
            ]

            by_row = {
                item["row_num"]: item
                for item in merged_types
                if isinstance(item.get("row_num"), int)
            }

            for row_num in deleted_row_nums or []:
                by_row.pop(row_num, None)

            for item in fuel_types:
                by_row[item["row_num"]] = dict(item)

            merged_types = [
                by_row[row]
                for row in sorted(by_row)
            ]

            stored = {
                **existing,
                "latitude": latitude,
                "longitude": longitude,
                "name": name if name is not None else existing.get("name"),
                "fuel_types": merged_types,
            }
            updated = list(stations)
            updated[index] = stored

        payload = _serialise(updated, envelope)

        try:
            if etag is None:
                blob_client.upload_blob(payload, overwrite=False)
            else:
                blob_client.upload_blob(
                    payload,
                    overwrite=True,
                    etag=etag,
                    match_condition=MatchConditions.IfNotModified,
                )

        except (ResourceModifiedError, ResourceExistsError):
            continue

        return stored, created

    raise StationsConflict(
        "The enriched station list is being written too often to update safely."
    )


def _parse(
    raw: bytes,
    require_id: bool = True
) -> tuple[list[dict], dict | None]:
    """Split the document into its station list and its envelope, if any."""

    try:
        document = json.loads(raw)

    except ValueError as exc:
        raise StationsCorrupt(
            "The stored station list is not valid JSON."
        ) from exc

    if isinstance(document, list):
        stations, envelope = document, None

    elif isinstance(document, dict):
        stations = document.get(_ENVELOPE_KEY)
        envelope = {
            key: value
            for key, value in document.items()
            if key != _ENVELOPE_KEY
        }

    else:
        raise StationsCorrupt(
            "The stored station list is neither an array nor an object."
        )

    if not isinstance(stations, list):
        raise StationsCorrupt(
            "The stored station list is not an array."
        )

    for station in stations:
        if not isinstance(station, dict):
            raise StationsCorrupt(
                "Every station must be an object."
            )

        if require_id and not station.get("id"):
            raise StationsCorrupt(
                "Every station must have a non-empty id."
            )

    return stations, envelope


def _serialise(
    stations: list[dict],
    envelope: dict | None,
) -> bytes:

    document = (
        stations
        if envelope is None
        else {**envelope, _ENVELOPE_KEY: stations}
    )

    return json.dumps(
        document,
        ensure_ascii=False,
        indent=2,
    ).encode("utf-8")


def _fetch() -> tuple[list[dict], str | None, dict | None]:
    """Read the document. A missing blob is an empty list, not an error."""

    try:
        downloader = _blob_client().download_blob()
        raw = downloader.readall()

    except ResourceNotFoundError:
        return [], None, None

    stations, envelope = _parse(raw)

    return stations, downloader.properties.etag, envelope


def invalidate() -> None:

    global _cache

    with _lock:
        _cache = None


def _load(
    force: bool = False
) -> tuple[list[dict], str | None, dict | None]:

    global _cache

    with _lock:
        if (
            not force
            and _cache is not None
            and time.monotonic() - _cache[3] < STATIONS_CACHE_TTL_SECONDS
        ):
            return _cache[0], _cache[1], _cache[2]

    stations, etag, envelope = _fetch()

    with _lock:
        _cache = (stations, etag, envelope, time.monotonic())

    return stations, etag, envelope


def load_stations(
    force: bool = False
) -> list[dict]:
    """Every station, in stored order."""

    return _load(force)[0]


def stations_etag() -> str | None:
    """Changes whenever the document does, so a device can cache against it."""

    etag = _load()[1]

    return (etag or "").strip('"') or None


def find_station(
    station_id: str
) -> dict | None:

    for station in load_stations():
        if station.get("id") == station_id:
            return station

    return None


def fuel_type_for_row(
    station_id: str,
    row_num: int,
) -> str | None:
    """The fuel type at a 1-based board row, or None if not known.

    The detector reports a price per board row but cannot read which fuel that
    row is for, so this resolves it. It returns None for any station with no
    ``fuel_types`` sequence, which is most of them: the sequence is surveyed
    by hand and exists only where someone has stood in front of the board.
    """

    station = find_station(station_id)

    if station is None:
        return None

    sequence = station.get("fuel_types") or []

    if row_num < 1 or row_num > len(sequence):
        return None

    return sequence[row_num - 1]


class StationNotFound(LookupError):
    """No station with that id."""


def _write(
    mutate
) -> object:
    """Apply `mutate` to the stored list and write the whole document back.

    `mutate(stations)` returns `(updated_list, result)`. It runs against a
    fresh read every attempt, so it must be free of side effects: losing the
    etag race means it is called again on top of whatever the winner stored.
    """

    last_error: Exception | None = None

    for _ in range(max(1, STATIONS_WRITE_ATTEMPTS)):

        # Always off a fresh read: a retry that reused the cache would rebuild
        # the same losing document.
        stations, etag, envelope = _load(force=True)

        updated, result = mutate(stations)

        payload = _serialise(updated, envelope)

        try:
            if etag is None:
                # No blob yet, so the create is itself the race: overwrite=False
                # means only one of two concurrent creates can win.
                _blob_client().upload_blob(
                    payload,
                    overwrite=False,
                )

            else:
                _blob_client().upload_blob(
                    payload,
                    overwrite=True,
                    etag=etag,
                    match_condition=MatchConditions.IfNotModified,
                )

        except (ResourceModifiedError, ResourceExistsError) as exc:
            # Someone else wrote between the read and the write. Re-read and
            # reapply on top of what they stored.
            last_error = exc
            invalidate()
            continue

        invalidate()

        return result

    raise StationsConflict(
        "The station list is being written too often to update safely."
    ) from last_error


def _index_of(
    stations: list[dict],
    station_id: str,
) -> int | None:

    return next(
        (
            position
            for position, existing in enumerate(stations)
            if existing.get("id") == station_id
        ),
        None,
    )


def upsert_station(
    station: dict
) -> tuple[dict, bool]:
    """Write one station into the document, keyed by ``id``.

    Returns the stored entry and whether it was created. Existing fields are
    merged, not replaced: a device that knows only a better position must not
    blank out a ``fuel_types`` sequence someone surveyed by hand.
    """

    station_id = station["id"]

    def mutate(stations):

        index = _index_of(stations, station_id)

        created = index is None

        merged = (
            dict(station)
            if created
            else {**stations[index], **station}
        )

        updated = list(stations)

        if created:
            updated.append(merged)
        else:
            updated[index] = merged

        return updated, (merged, created)

    return _write(mutate)


def delete_station(
    station_id: str
) -> dict:
    """Remove one station and return the entry that was removed.

    Raises `StationNotFound` if there is nothing to remove, so a caller can
    tell a real delete from a no-op rather than reporting both as success.
    """

    def mutate(stations):

        index = _index_of(stations, station_id)

        if index is None:
            # Raised inside the retry loop on purpose: a concurrent delete of
            # the same id means it really is gone, and re-reading confirms it.
            raise StationNotFound(station_id)

        updated = list(stations)

        removed = updated.pop(index)

        return updated, removed

    return _write(mutate)
