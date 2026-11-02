"""Device-facing API.

Replaces the storage account key that used to ship inside the APK. A device
authenticates here and is handed short-lived, single-blob SAS URLs, so the
signing key stays on the server.

Retry semantics, which the upload worker branches on:

    4xx  permanent. The request will never succeed as sent; drop the item.
         The one exception is 429, which is transient.
    5xx  transient. Retry with backoff.
    429  transient. Retry with backoff.

Validation failures are therefore deliberately 4xx, and anything that depends
on storage being reachable is left to surface as 5xx.
"""

from azure.core.exceptions import (
    AzureError,
    ResourceNotFoundError,
)

from fastapi import (
    APIRouter,
    Depends,
    HTTPException,
)

from app.config import (
    ARTIFACTS_CONTAINER,
    ARTIFACT_SAS_TTL_SECONDS,
    DEVICE_CONFIG_BLOB,
    DEVICE_CONFIG_CONTAINER,
    MODEL_CONTAINERS,
    MODEL_SAS_TTL_SECONDS,
    RUN_MANIFEST_FILE,
    get_blob_service_client,
)

from app.sas import (
    download_url,
    upload_url,
)

from app.schemas import (
    ArtifactUploadResponse,
    DeviceConfigResponse,
    StationDeleteResponse,
    StationListResponse,
    StationUpsertRequest,
    StationUpsertResponse,
    CompleteRunRequest,
    CompleteRunResponse,
    DeviceTokenRequest,
    DeviceTokenResponse,
    ModelDownloadResponse,
)

from app.station_ops import (
    delete_station_response,
    list_stations_response,
    upsert_station_response,
)

from app.storage import (
    is_safe_blob_path,
    is_safe_video_id,
    now_iso,
)

from app.tokens import (
    authenticate_device,
    issue_device_token,
    require_device,
)

import json


router = APIRouter(prefix="/v1", tags=["Device"])


# ============================================================
# VALIDATION
# ============================================================

def _validated_run_id(
    run_id: str
) -> str:

    # A run id is the video id the rest of the service already keys on.
    if not is_safe_video_id(run_id):
        raise HTTPException(
            status_code=400,
            detail="Invalid run id.",
        )

    return run_id


def _validated_artifact_path(
    run_id: str,
    artifact_path: str,
) -> str:
    """`frames/frame_00042.png` under a run becomes its full blob path.

    The path is what discriminates artifact types, so there is one upload
    endpoint rather than one per type. It is checked hard because it comes
    from the device.
    """

    artifact_path = artifact_path.strip("/")

    if not artifact_path:
        raise HTTPException(
            status_code=400,
            detail="Artifact path is empty.",
        )

    blob_path = f"{run_id}/{artifact_path}"

    if not is_safe_blob_path(blob_path):
        raise HTTPException(
            status_code=400,
            detail="Invalid artifact path.",
        )

    return blob_path


# ============================================================
# 1. AUTHENTICATE
# ============================================================

@router.post(
    "/auth/token",
    response_model=DeviceTokenResponse,
    summary="Exchange device credentials for a short-lived token",
    description=(
        "Without this the upload API would be an open relay. The token is "
        "presented as `Authorization: Bearer <token>` on every other /v1 call."
    ),
)
def create_token(
    request: DeviceTokenRequest
):

    # Raises 401 on a bad id or secret, in constant time.
    authenticate_device(request.device_id, request.device_secret)

    token, expires_in, expires_at = issue_device_token(request.device_id)

    return DeviceTokenResponse(
        access_token=token,
        expires_in=expires_in,
        expires_at=expires_at,
    )


# ============================================================
# 2. UPLOAD ONE ARTIFACT
# ============================================================

@router.post(
    "/runs/{run_id}/artifacts/{artifact_path:path}",
    response_model=ArtifactUploadResponse,
    summary="Broker a write-only SAS for one artifact",
    description=(
        "Returns a short-lived URL scoped to this single blob. The device "
        "PUTs the bytes straight to storage, so the payload never passes "
        "through this service and it does not scale with video size.\n\n"
        "Re-uploading the same path with the same bytes overwrites in place, "
        "so an outbox retry is a no-op rather than a duplicate."
    ),
)
def create_artifact_upload(
    run_id: str,
    artifact_path: str,
    device_id: str = Depends(require_device),
):

    run_id = _validated_run_id(run_id)

    blob_path = _validated_artifact_path(run_id, artifact_path)

    url, expires_at = upload_url(
        ARTIFACTS_CONTAINER,
        blob_path,
        ARTIFACT_SAS_TTL_SECONDS,
    )

    return ArtifactUploadResponse(
        run_id=run_id,
        artifact_path=artifact_path.strip("/"),
        blob_path=blob_path,
        container=ARTIFACTS_CONTAINER,
        upload_url=url,
        required_headers={"x-ms-blob-type": "BlockBlob"},
        expires_at=expires_at,
    )


# ============================================================
# 3. DECLARE THE RUN COMPLETE
# ============================================================

@router.post(
    "/runs/{run_id}/complete",
    response_model=CompleteRunResponse,
    summary="Declare the artifact manifest for a run",
    description=(
        "Uploads are individually retried and can silently fall short. "
        "Declaring what the run produced turns 'did everything arrive?' "
        "into a query instead of a guess: the response names what is "
        "missing, and the manifest is stored alongside the run."
    ),
)
def complete_run(
    run_id: str,
    request: CompleteRunRequest,
    device_id: str = Depends(require_device),
):

    run_id = _validated_run_id(run_id)

    if not request.artifacts:
        raise HTTPException(
            status_code=400,
            detail="The manifest lists no artifacts.",
        )

    expected: list[str] = []

    for artifact in request.artifacts:

        path = artifact.path.strip("/")

        if not path or not is_safe_blob_path(f"{run_id}/{path}"):
            raise HTTPException(
                status_code=400,
                detail=f"Invalid artifact path in manifest: {artifact.path!r}",
            )

        expected.append(path)

    container = (
        get_blob_service_client()
        .get_container_client(ARTIFACTS_CONTAINER)
    )

    prefix = f"{run_id}/"

    received: set[str] = set()

    try:
        for blob in container.list_blobs(name_starts_with=prefix):

            name = blob.name[len(prefix):]

            if name and name != RUN_MANIFEST_FILE:
                received.add(name)

    except ResourceNotFoundError:
        received = set()

    expected_set = set(expected)

    missing = sorted(expected_set - received)

    unexpected = sorted(received - expected_set)

    completed_at = now_iso()

    manifest = {
        "run_id": run_id,
        "device_id": request.device_id or device_id,
        "completed_at": completed_at,
        "complete": not missing,
        "expected": expected,
        "missing": missing,
        "unexpected": unexpected,
    }

    try:
        container.upload_blob(
            name=f"{prefix}{RUN_MANIFEST_FILE}",
            data=json.dumps(manifest, indent=2).encode("utf-8"),
            overwrite=True,
        )

    except AzureError as exc:
        # Storage is unreachable: transient, so the device should retry.
        raise HTTPException(
            status_code=503,
            detail=f"Could not store the run manifest: {exc}",
        ) from exc

    return CompleteRunResponse(
        run_id=run_id,
        complete=not missing,
        expected_count=len(expected_set),
        received_count=len(received),
        missing=missing,
        unexpected=unexpected,
        completed_at=completed_at,
    )


# ============================================================
# 4. DOWNLOAD A MODEL
#
# The last use of the account key on the device. With this the
# key can be removed from the APK entirely.
# ============================================================

@router.get(
    "/models/{name:path}",
    response_model=ModelDownloadResponse,
    summary="Broker a read-only SAS for one model file",
    description=(
        "The device pulled models with the same account key it used for "
        "uploads, so removing the key means brokering these too. Returns a "
        "short-lived read-only URL for one blob."
    ),
)
def get_model(
    name: str,
    device_id: str = Depends(require_device),
):

    full = name.strip("/")

    if not full or not is_safe_blob_path(full) or "/" not in full:
        raise HTTPException(
            status_code=400,
            detail=(
                "A model is addressed as <container>/<blob>, for example "
                "yolo/board-detection-001.tflite."
            ),
        )

    container_name, _, blob_path = full.partition("/")

    # Only the model containers are reachable here, never the debug data.
    if container_name not in MODEL_CONTAINERS:
        raise HTTPException(
            status_code=404,
            detail=f"Unknown model container '{container_name}'.",
        )

    blob_client = (
        get_blob_service_client()
        .get_container_client(container_name)
        .get_blob_client(blob_path)
    )

    try:
        properties = blob_client.get_blob_properties()

    except ResourceNotFoundError as exc:
        raise HTTPException(
            status_code=404,
            detail=f"Model '{full}' was not found.",
        ) from exc

    url, expires_at = download_url(
        container_name,
        blob_path,
        MODEL_SAS_TTL_SECONDS,
    )

    settings = properties.content_settings

    return ModelDownloadResponse(
        name=full,
        blob_path=blob_path,
        container=container_name,
        download_url=url,
        size_bytes=properties.size or 0,
        content_type=(
            settings.content_type
            if settings and settings.content_type
            else "application/octet-stream"
        ),
        expires_at=expires_at,
    )


# ============================================================
# 5. STATION MAPPING
#
# The handling lives in `app.station_ops`, shared with the
# console router that exposes the same document under
# `/stations` to the labelling UI. Only the credential differs.
# ============================================================

@router.get(
    "/stations",
    response_model=StationListResponse,
    summary="Forecourts, their coordinates and their board row order",
    description=(
        "Read from `configurations/stations-data/stations.json`, which the "
        "fleet also writes to, so this is the live list rather than one "
        "baked into the image. "
        "Where a station carries `fuel_types`, the position of a code in it "
        "is the board row, counting from the top -- that is what resolves "
        "the `Unknown` the detector reports, since it reads a price per row "
        "but not which fuel the row is for. The order is data, not "
        "presentation: Rapina Olerex lists 98 before 95, so it must never be "
        "sorted. Absent or empty means the sequence is not known, which is "
        "the case for every station nobody has surveyed on foot."
    ),
)
def list_stations(
    device_id: str = Depends(require_device),
):

    return list_stations_response()


@router.put(
    "/stations/{station_id}",
    response_model=StationUpsertResponse,
    summary="Create or amend one station, keyed by id",
    description=(
        "Reads the stored document, replaces the entry with this `id` -- or "
        "appends it if there is none -- and writes the whole document back. "
        "A device that stops somewhere unmapped uses this to record the "
        "forecourt it found. "
        "Fields are **merged**, not replaced: anything omitted keeps its "
        "stored value, so a device reporting a better position cannot blank "
        "out a `fuel_types` sequence someone surveyed by hand. "
        "The write is conditional on the etag that was read, so two devices "
        "writing at once cannot lose each other's work; the loser re-reads "
        "and reapplies."
    ),
)
def put_station(
    station_id: str,
    body: StationUpsertRequest,
    device_id: str = Depends(require_device),
):

    return upsert_station_response(station_id, body)


@router.delete(
    "/stations/{station_id}",
    response_model=StationDeleteResponse,
    summary="Remove one station, keyed by id",
    description=(
        "Reads the stored document, drops the entry with this `id`, and "
        "writes the whole document back. Like the upsert, the write is "
        "conditional on the etag that was read, so a delete cannot silently "
        "revert someone else's concurrent change. "
        "A station that is not there is a `404`, not a quiet success -- the "
        "caller is told nothing was removed."
    ),
)
def remove_station(
    station_id: str,
    device_id: str = Depends(require_device),
):

    return delete_station_response(station_id)


# ============================================================
# 6. DEVICE CONFIGURATION
#
# The tunables the app used to compile in. Holding them in one
# blob means the fleet can be re-tuned without a release.
# ============================================================

@router.get(
    "/config",
    response_model=DeviceConfigResponse,
    summary="The device configuration document",
    description=(
        "Returns `configurations/device-config/device-config.default.json` "
        "from blob storage whole. "
        "The `config` object is passed through unvalidated, so a new tunable "
        "reaches the fleet by editing the blob rather than by releasing the "
        "server. `etag` changes whenever the document does, which is what a "
        "device should cache against."
    ),
)
def get_device_config(
    device_id: str = Depends(require_device),
):

    blob_client = (
        get_blob_service_client()
        .get_container_client(DEVICE_CONFIG_CONTAINER)
        .get_blob_client(DEVICE_CONFIG_BLOB)
    )

    try:
        downloader = blob_client.download_blob()
        raw = downloader.readall()

    except ResourceNotFoundError as exc:
        # 404 is permanent: the blob is missing, so retrying cannot help.
        raise HTTPException(
            status_code=404,
            detail=(
                f"No configuration at "
                f"'{DEVICE_CONFIG_CONTAINER}/{DEVICE_CONFIG_BLOB}'."
            ),
        ) from exc

    try:
        document = json.loads(raw)

    except ValueError as exc:
        # Bad content is the server's problem, not the device's, so this is a
        # 5xx and the device is right to retry -- the blob may be mid-edit.
        raise HTTPException(
            status_code=502,
            detail="The stored configuration is not valid JSON.",
        ) from exc

    if not isinstance(document, dict):
        raise HTTPException(
            status_code=502,
            detail="The stored configuration is not a JSON object.",
        )

    properties = downloader.properties

    last_modified = properties.last_modified

    return DeviceConfigResponse(
        config_id=document.get("config_id"),
        schema_version=document.get("schema_version"),
        updated_at=document.get("updated_at"),
        etag=(properties.etag or "").strip('"') or None,
        last_modified=(
            last_modified.isoformat() if last_modified else None
        ),
        config=document,
    )
