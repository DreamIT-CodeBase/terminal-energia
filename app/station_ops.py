"""Request handling for the station document, shared by both its callers.

Two routers expose the same three operations over the same document: the
device API under ``/v1/stations``, authenticated with a fleet token, and the
labelling console under ``/stations``, authenticated with the service key.
The rules -- what a valid id is, which coordinates are in range, that
``modified_time`` is stamped here rather than taken from the caller -- belong
to the document, not to either caller, so they live here and neither router
can drift from the other.

`app.stations` holds the storage side: read, merge, conditional write. This
layer is the part that turns its outcomes into HTTP.
"""

import time

from fastapi import HTTPException

from app.schemas import (
    StationDeleteResponse,
    StationListResponse,
    StationUpsertRequest,
    StationUpsertResponse,
)

from app.stations import (
    StationNotFound,
    StationsConflict,
    StationsCorrupt,
    delete_station,
    load_stations,
    stations_etag,
    upsert_station,
)


def _store_error(
    exc: StationsCorrupt
) -> HTTPException:
    """A bad document is the server's fault, so it is a 5xx and retryable."""

    return HTTPException(
        status_code=502,
        detail=str(exc),
    )


def _contention_error(
    exc: StationsConflict
) -> HTTPException:
    """Transient by the retry table: back off and try again."""

    return HTTPException(
        status_code=429,
        detail=str(exc),
    )


def _validated_station_id(
    station_id: str
) -> str:

    station_id = station_id.strip()

    if not station_id:
        raise HTTPException(
            status_code=400,
            detail="Station id is empty.",
        )

    return station_id


def list_stations_response() -> StationListResponse:

    try:
        stations = load_stations()
        etag = stations_etag()

    except StationsCorrupt as exc:
        raise _store_error(exc) from exc

    return StationListResponse(
        version=1,
        count=len(stations),
        etag=etag,
        stations=stations,
    )


def upsert_station_response(
    station_id: str,
    body: StationUpsertRequest,
) -> StationUpsertResponse:

    station_id = _validated_station_id(station_id)

    # `exclude_unset` is what makes the write a merge: a field the caller
    # never mentioned is absent here, so the stored value survives.
    fields = body.model_dump(exclude_unset=True)

    if not fields:
        raise HTTPException(
            status_code=400,
            detail="No fields to update.",
        )

    latitude = fields.get("latitude")
    longitude = fields.get("longitude")

    if latitude is not None and not -90.0 <= latitude <= 90.0:
        raise HTTPException(
            status_code=400,
            detail="Latitude is out of range.",
        )

    if longitude is not None and not -180.0 <= longitude <= 180.0:
        raise HTTPException(
            status_code=400,
            detail="Longitude is out of range.",
        )

    # Stamped here rather than taken from the body: a device clock is not
    # trustworthy, and this records when the row was written, not observed.
    fields["modified_time"] = int(time.time() * 1000)

    try:
        stored, created = upsert_station({"id": station_id, **fields})

    except StationsCorrupt as exc:
        raise _store_error(exc) from exc

    except StationsConflict as exc:
        raise _contention_error(exc) from exc

    return StationUpsertResponse(
        created=created,
        station=stored,
        etag=stations_etag(),
        count=len(load_stations()),
    )


def delete_station_response(
    station_id: str
) -> StationDeleteResponse:

    station_id = _validated_station_id(station_id)

    try:
        removed = delete_station(station_id)

    except StationNotFound as exc:
        raise HTTPException(
            status_code=404,
            detail=f"No station with id '{station_id}'.",
        ) from exc

    except StationsCorrupt as exc:
        raise _store_error(exc) from exc

    except StationsConflict as exc:
        raise _contention_error(exc) from exc

    return StationDeleteResponse(
        deleted=True,
        station=removed,
        etag=stations_etag(),
        count=len(load_stations()),
    )
