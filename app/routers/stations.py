"""Station mapping for the labelling console.

The same three operations the fleet has under `/v1/stations`, on the same
document, reached with the service key instead of a device token. Separate
routes rather than a second credential on the device ones: the fleet secret
stays in the fleet, and a console edit is never mistaken for a device
reporting what it found.

Everything of substance is in `app.station_ops`, shared with the device
router, so the two cannot validate differently.
"""

from fastapi import (
    APIRouter,
    Depends,
)

from app.schemas import (
    EnrichedStationUpsertRequest,
    EnrichedStationUpsertResponse,
    EnrichedStationListResponse,
    StationDeleteResponse,
    StationListResponse,
    StationUpsertRequest,
    StationUpsertResponse,
)

from app.security import require_api_key

from app.station_ops import (
    delete_station_response,
    list_stations_response,
    upsert_station_response,
)

from app.stations import load_enriched_stations, upsert_enriched_station


router = APIRouter(
    prefix="/stations",
    tags=["Stations"],
    dependencies=[Depends(require_api_key)],
)


@router.get(
    "",
    response_model=StationListResponse,
    summary="Every forecourt, for the map",
    description=(
        "The whole document in one call -- a few hundred entries -- because "
        "the map draws all of them at once and there is nothing to page "
        "through. "
        "Where a station carries `fuel_types`, the position of a code in it "
        "is the board row, counting from the top. The order is data, not "
        "presentation: Rapina Olerex lists 98 before 95, so it must never be "
        "sorted."
    ),
)
def list_stations_route():

    return list_stations_response()


@router.get(
    "-enriched",
    response_model=EnrichedStationListResponse,
    summary="Read enriched stations for validation",
    description=(
        "Reads the separate stations-enriched.json document. This GET route "
        "does not modify the station mapping document."
    ),
)
def list_enriched_stations_route():

    stations = load_enriched_stations()

    return EnrichedStationListResponse(
        version=1,
        count=len(stations),
        etag=None,
        stations=stations,
    )


@router.put(
    "-enriched",
    response_model=EnrichedStationUpsertResponse,
    summary="Upsert enriched station fuel types by coordinates",
    description=(
        "Matches latitude and longitude within 0.001 degrees per coordinate. "
        "Fuel types are merged by row number; `deleted_row_nums` explicitly "
        "removes rows that were deleted in the labelling UI."
    ),
)
def put_enriched_station_route(
    body: EnrichedStationUpsertRequest,
):

    station, created = upsert_enriched_station(
        latitude=body.latitude,
        longitude=body.longitude,
        name=body.name,
        fuel_types=[
            item.model_dump()
            for item in body.fuel_types
        ],
        deleted_row_nums=body.deleted_row_nums,
    )

    return EnrichedStationUpsertResponse(
        created=created,
        station=station,
    )


@router.put(
    "/{station_id}",
    response_model=StationUpsertResponse,
    summary="Create or amend one station, keyed by id",
    description=(
        "Fields are **merged**, not replaced: anything omitted keeps its "
        "stored value. To clear a field, send it as `null` explicitly. "
        "The write is conditional on the etag that was read, so a console "
        "edit and a device upsert landing together cannot lose each other's "
        "work; the loser re-reads and reapplies."
    ),
)
def put_station_route(
    station_id: str,
    body: StationUpsertRequest,
):

    return upsert_station_response(station_id, body)


@router.delete(
    "/{station_id}",
    response_model=StationDeleteResponse,
    summary="Remove one station, keyed by id",
    description=(
        "The removed entry comes back in the response and is the only copy "
        "left -- nothing else keeps one. A station that is not there is a "
        "`404`, not a quiet success."
    ),
)
def delete_station_route(
    station_id: str,
):

    return delete_station_response(station_id)
