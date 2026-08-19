"""Store JSON documents in the Microsoft Fabric lakehouse.

Files land at:  <lakehouse>/Files/vehicle=<vehicle>/date=<date>/<uuid>.json
"""

import json

from typing import Any

from fastapi import (
    APIRouter,
    Depends,
    File,
    HTTPException,
    Query,
    Request,
    Response,
    UploadFile,
)

from fastapi.concurrency import run_in_threadpool

from app import config

from app.fabric import (
    FabricError,
    get_lakehouse_writer,
)

from app.paths import (
    InvalidPartitionValue,
    build_relative_path,
    clean_date,
    clean_vehicle,
    new_filename,
)

from app.schemas import (
    UploadRequest,
    UploadResponse,
)

from app.security import require_api_key


router = APIRouter(
    prefix="/files",
    tags=["Fabric Lakehouse"],
    dependencies=[Depends(require_api_key)],
)


# ============================================================
# HELPERS
# ============================================================

def _partition(
    vehicle: str,
    date_value: str | None,
) -> tuple[str, str]:

    try:
        return clean_vehicle(vehicle), clean_date(date_value)

    except InvalidPartitionValue as exc:
        raise HTTPException(
            status_code=422,
            detail=str(exc),
        ) from exc


def _validate_json(
    payload: bytes
) -> None:

    try:
        json.loads(payload.decode("utf-8"))

    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise HTTPException(
            status_code=400,
            detail=f"Body is not valid UTF-8 JSON: {exc}",
        ) from exc


async def _store(
    vehicle: str,
    date_value: str | None,
    payload: bytes,
) -> UploadResponse:

    vehicle, date_value = _partition(vehicle, date_value)

    if not payload:
        raise HTTPException(
            status_code=400,
            detail="Empty request body",
        )

    if len(payload) > config.MAX_BODY_BYTES:
        raise HTTPException(
            status_code=413,
            detail=f"Payload exceeds {config.MAX_BODY_BYTES} bytes",
        )

    filename = new_filename()

    relative = build_relative_path(vehicle, date_value, filename)

    try:
        # The Fabric SDK is synchronous, so it runs off the event loop.
        full_path = await run_in_threadpool(
            get_lakehouse_writer().upload_json,
            relative,
            payload,
        )

    except FabricError as exc:
        raise HTTPException(
            status_code=exc.status_code,
            detail=str(exc),
        ) from exc

    account_host = config.ONELAKE_ACCOUNT_URL.split("://", 1)[-1]

    return UploadResponse(
        vehicle=vehicle,
        date=date_value,
        filename=filename,
        path=full_path,
        relative_path=relative,
        abfss_url=(
            f"abfss://{config.FABRIC_WORKSPACE}@{account_host}/{full_path}"
        ),
        bytes_written=len(payload),
    )


# ============================================================
# ENVELOPE FORM
# ============================================================

@router.post(
    "",
    response_model=UploadResponse,
    status_code=201,
    summary="Store a JSON document (envelope form)",
    description=(
        "Send `{\"vehicle\": ..., \"date\": ..., \"data\": {...}}`. "
        "Only `data` is written to the lakehouse; `vehicle` and `date` "
        "become the partition folders."
    ),
)
async def create_file(
    body: UploadRequest
):

    payload = json.dumps(
        body.data,
        ensure_ascii=False,
        default=str,
    ).encode("utf-8")

    return await _store(body.vehicle, body.date, payload)


# ============================================================
# RAW BODY
# ============================================================

@router.post(
    "/raw",
    response_model=UploadResponse,
    status_code=201,
    summary="Store a JSON document sent as the whole body",
    description=(
        "The request body is stored byte for byte. Partition keys come "
        "from the query string, so the stored document stays untouched."
    ),
)
async def create_file_raw(
    request: Request,
    vehicle: str = Query(
        ...,
        description="Partition value for vehicle=",
    ),
    date: str | None = Query(
        None,
        description="YYYY-MM-DD; defaults to today UTC",
    ),
):

    payload = await request.body()

    _validate_json(payload)

    return await _store(vehicle, date, payload)


# ============================================================
# MULTIPART UPLOAD
# ============================================================

@router.post(
    "/upload",
    response_model=UploadResponse,
    status_code=201,
    summary="Store an uploaded .json file (multipart)",
)
async def upload_file(
    file: UploadFile = File(
        ...,
        description="A JSON file",
    ),
    vehicle: str = Query(
        ...,
        description="Partition value for vehicle=",
    ),
    date: str | None = Query(
        None,
        description="YYYY-MM-DD; defaults to today UTC",
    ),
):

    payload = await file.read()

    _validate_json(payload)

    return await _store(vehicle, date, payload)


# ============================================================
# LAKEHOUSE REACHABILITY
# ============================================================

health_router = APIRouter(
    tags=["Fabric Lakehouse"],
    dependencies=[Depends(require_api_key)],
)


@health_router.get(
    "/health/fabric",
    summary="Verify credentials and lakehouse reachability",
)
async def health_fabric(
    response: Response
) -> dict[str, Any]:

    target = {
        "workspace": config.FABRIC_WORKSPACE,
        "lakehouse_path": config.lakehouse_path(),
        "account_url": config.ONELAKE_ACCOUNT_URL,
    }

    try:
        await run_in_threadpool(get_lakehouse_writer().check_access)

    except FabricError as exc:
        response.status_code = 503

        return {
            "status": "unavailable",
            "detail": str(exc),
            "target": target,
        }

    return {
        "status": "ok",
        "target": target,
    }
