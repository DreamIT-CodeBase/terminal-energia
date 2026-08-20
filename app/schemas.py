from typing import Any, Literal

from pydantic import (
    BaseModel,
    Field,
)


VideoFolder = Literal["frames", "crops", "prepped"]

AnnotationType = Literal["rect", "polygon", "polyline", "point"]


# ============================================================
# LISTING
# ============================================================

class VideoSummary(BaseModel):
    video_id: str
    annotated: bool
    image_count: int = 0
    # True once the price-region pass has been submitted, which finishes
    # the video. The UI drops finished videos from its list.
    completed: bool = False


class VideoListResponse(BaseModel):
    videos: list[VideoSummary]


class BlobAsset(BaseModel):
    name: str
    path: str
    size: int


class VideoAssetsResponse(BaseModel):
    video_id: str
    folders: dict[str, list[BlobAsset]]
    has_api_response: bool
    video_path: str | None


# ============================================================
# ANNOTATIONS
# ============================================================

class AnnotationClass(BaseModel):
    id: str
    name: str
    color: str


class AnnotationRecord(BaseModel):
    class_id: str = Field(alias="classId")
    type: AnnotationType

    # Normalised (0-1) coordinates, exactly as drawn on the canvas.
    # - rect:     [x, y, w, h] with x, y at the top-left
    # - polygon:  [x1, y1, x2, y2, ...]
    # - polyline: [x1, y1, x2, y2, ...]
    # - point:    [x, y]
    coordinates: list[float]

    model_config = {"populate_by_name": True}


class AnnotatedImage(BaseModel):
    name: str
    path: str
    folder: VideoFolder
    width: int
    height: int
    annotations: list[AnnotationRecord]


class SaveAnnotationsRequest(BaseModel):
    classes: list[AnnotationClass]
    images: list[AnnotatedImage]
    saved_by: str = Field(default="unknown", alias="savedBy")

    model_config = {"populate_by_name": True}


class SaveAnnotationsResponse(BaseModel):
    video_id: str
    images_written: int
    labels_written: int
    skipped: list[str]
    saved_at: str


class SavedAnnotationsResponse(BaseModel):
    video_id: str
    saved_at: str
    saved_by: str
    classes: list[AnnotationClass]
    images: list[AnnotatedImage]


# ============================================================
# FABRIC LAKEHOUSE
# ============================================================

class UploadRequest(BaseModel):
    vehicle: str = Field(
        ...,
        description="Partition value for vehicle=",
        examples=["ABC123"],
    )

    date: str | None = Field(
        None,
        description=(
            "Partition value for date= as YYYY-MM-DD. "
            "Defaults to today (UTC)."
        ),
        examples=["2026-07-25"],
    )

    data: Any = Field(
        ...,
        description="The JSON document to store, verbatim.",
    )


class UploadResponse(BaseModel):
    vehicle: str
    date: str
    filename: str

    path: str = Field(
        ...,
        description="Full path inside the workspace filesystem",
    )

    relative_path: str = Field(
        ...,
        description="Path relative to the lakehouse root",
    )

    abfss_url: str
    bytes_written: int


# ============================================================
# TWO-STAGE LABELLING
# ============================================================

class StageImage(BaseModel):
    """One frame (stage 1) or one crop (stage 2) with its boxes."""

    name: str
    path: str
    width: int = 0
    height: int = 0
    annotations: list[AnnotationRecord]


class SubmitStageRequest(BaseModel):
    images: list[StageImage]
    saved_by: str = Field(default="unknown", alias="savedBy")

    model_config = {"populate_by_name": True}


class SubmitStageResponse(BaseModel):
    video_id: str
    stage: str
    # The ISO week the finished video was filed into, e.g. 2026-W34.
    # Only set when the price-region pass completes.
    week: str | None = None
    images_written: int
    # Board frames promoted from the working area when stage 2 completes.
    board_images_written: int = 0
    crops: list[BlobAsset]
    skipped: list[str]
    saved_at: str


class StageStateResponse(BaseModel):
    video_id: str
    stage: str
    board_submitted: bool
    price_region_submitted: bool
    crops: list[BlobAsset]


# ============================================================
# DEVICE API (/v1)
# ============================================================

class DeviceTokenRequest(BaseModel):
    device_id: str = Field(..., alias="deviceId", examples=["IOT-TRK-0A9D75"])
    device_secret: str = Field(..., alias="deviceSecret")

    model_config = {"populate_by_name": True}


class DeviceTokenResponse(BaseModel):
    access_token: str
    token_type: str = "Bearer"
    expires_in: int
    expires_at: str


class ArtifactUploadResponse(BaseModel):
    run_id: str
    artifact_path: str
    blob_path: str
    container: str
    # PUT the bytes straight here; the server never sees them.
    upload_url: str
    method: str = "PUT"
    # Azure requires this header on a block blob PUT.
    required_headers: dict[str, str]
    expires_at: str


class ManifestArtifact(BaseModel):
    path: str
    size_bytes: int | None = Field(default=None, alias="sizeBytes")

    model_config = {"populate_by_name": True}


class CompleteRunRequest(BaseModel):
    artifacts: list[ManifestArtifact]
    device_id: str | None = Field(default=None, alias="deviceId")

    model_config = {"populate_by_name": True}


class CompleteRunResponse(BaseModel):
    run_id: str
    complete: bool
    expected_count: int
    received_count: int
    # Declared but never arrived: the gap the manifest exists to expose.
    missing: list[str]
    # Arrived but not declared.
    unexpected: list[str]
    completed_at: str


class ModelDownloadResponse(BaseModel):
    name: str
    blob_path: str
    container: str
    download_url: str
    size_bytes: int
    content_type: str
    expires_at: str


# ============================================================
# STATION MAPPING
# ============================================================

class Station(BaseModel):
    id: str
    name: str
    latitude: float
    longitude: float

    # Top-to-bottom order of the rows on the price board, so the position in
    # this list is the row number. Never sort it: Räpina Olerex lists 98
    # before 95. Empty when the sequence is not known for that station.
    fuel_types: list[str]

    note: str | None = None


class StationListResponse(BaseModel):
    version: int
    count: int
    stations: list[Station]
