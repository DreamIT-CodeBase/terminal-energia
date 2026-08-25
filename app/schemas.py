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
    # True once the video has been submitted, which finishes it. The UI drops
    # finished videos from its list.
    completed: bool = False
    # Frames are captured from video.mp4, so a video without one cannot be
    # labelled at all and the UI hides it.
    has_video: bool = False


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
# FRAME CAPTURE
#
# The labeller scrubs video.mp4 and picks moments; each pick is
# decoded server side and parked in the working area.
# ============================================================

class VideoProbeResponse(BaseModel):
    video_id: str
    # Milliseconds, so the UI can offer a spread of evenly spaced captures
    # without waiting for the browser to load metadata.
    duration_ms: int
    width: int
    height: int
    fps: float | None = None


class CapturedFrame(BaseModel):
    name: str
    path: str
    # The moment in the clip this frame was taken from.
    timestamp_ms: int
    width: int = 0
    height: int = 0
    size: int = 0


class CapturedFramesResponse(BaseModel):
    video_id: str
    frames: list[CapturedFrame]


class CaptureFramesRequest(BaseModel):
    timestamps_ms: list[int] = Field(alias="timestampsMs")

    model_config = {"populate_by_name": True}


class CaptureFramesResponse(BaseModel):
    video_id: str
    # Every frame the video now has, not just the ones this call produced.
    frames: list[CapturedFrame]
    captured: list[CapturedFrame]
    # Timestamps that could not be decoded, as milliseconds.
    skipped: list[str]


# ============================================================
# SUBMIT
#
# One request closes out a video: the frames worth keeping, the
# boards drawn on them, and the price regions inside each board.
# ============================================================

class SubmitRegion(BaseModel):
    # Normalised (0-1) [x, y, w, h] against the BOARD CROP, not the frame.
    coordinates: list[float]

    # Left empty when the labeller could not read the price off the board.
    price: float | None = None


class SubmitBoard(BaseModel):
    # Normalised (0-1) [x, y, w, h] against the frame.
    coordinates: list[float]

    regions: list[SubmitRegion] = []


class SubmitFrame(BaseModel):
    # File name in the working area, e.g. t00012340.png.
    name: str
    timestamp_ms: int = Field(default=0, alias="timestampMs")
    width: int = 0
    height: int = 0

    boards: list[SubmitBoard] = []

    model_config = {"populate_by_name": True}


class SubmitVideoRequest(BaseModel):
    frames: list[SubmitFrame]
    saved_by: str = Field(default="unknown", alias="savedBy")

    model_config = {"populate_by_name": True}


class SubmitVideoResponse(BaseModel):
    video_id: str
    # The ISO week the video was filed into, e.g. 2026-W34.
    week: str | None = None
    frames_written: int
    crops_written: int
    regions_written: int
    # Boards submitted with no price region inside them: valid, but worth
    # calling out because they land in the dataset as background samples.
    boards_without_regions: int = 0
    skipped: list[str]
    saved_at: str


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
