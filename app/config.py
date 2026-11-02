import os
import re

from azure.storage.blob import BlobServiceClient


# ============================================================
# AZURE STORAGE
# ============================================================

AZURE_STORAGE_CONNECTION_STRING = os.environ.get(
    "AZURE_STORAGE_CONNECTION_STRING",
    "",
)


SOURCE_CONTAINER = os.environ.get(
    "SOURCE_CONTAINER",
    "debugdirectory",
)


ANNOTATED_CONTAINER = os.environ.get(
    "ANNOTATED_CONTAINER",
    "debugdirectoryannotated",
)


# ============================================================
# BLOB LAYOUT
#
#   video-id/frames/
#   video-id/crops/
#   video-id/prepped/
#   video-id/api_response.json
#   video-id/updated_api_response.json
#   video-id/video.mp4
# ============================================================

IMAGE_FOLDERS = (
    "frames",
    "crops",
    "prepped",
)


API_RESPONSE_FILE = "api_response.json"

UPDATED_API_RESPONSE_FILE = "updated_api_response.json"

VIDEO_FILE = "video.mp4"

ANNOTATIONS_FILE = "annotations.json"

CLASSES_FILE = "classes.txt"


# ============================================================
# DATASET STAGES
#
# One submit produces two datasets from the same work: boards
# are boxes on a captured frame, price regions are boxes inside
# the crop each board box defines. They stay separate because
# they train two single-class detectors on different images.
# ============================================================

STAGE_BOARD = "board"

STAGE_PRICE_REGION = "price_region"

STAGES = (STAGE_BOARD, STAGE_PRICE_REGION)


# ============================================================
# WORKING AREA
#
# Frames captured from a video are written here as soon as they
# are extracted, so labelling survives a page reload:
#
#   _in_progress/video-id/frames/t00012340.png
#   _in_progress/video-id/frames.json
#
# It is a reserved root prefix inside the annotated container.
# A video must not appear in that container under its own id
# until it is submitted, because "present in the annotated
# container" is what marks it finished.
# ============================================================

WORKING_PREFIX = "_in_progress"

WORKING_FRAMES_DIR = "frames"

# Records the timestamp each captured frame came from, which the
# file name alone would only carry by convention.
FRAME_MANIFEST_FILE = "frames.json"


# ============================================================
# WEEKLY SNAPSHOTS
#
# Finished work is grouped by the ISO week it was annotated in,
# so each week is a self-contained YOLO dataset:
#
#   snapshots/2026-W34/board/{images,labels}/ + data.yaml
#   snapshots/2026-W34/price_region/{images,labels}/ + data.yaml
#   snapshots/2026-W34/annotations/<video-id>.json
#
# Files carry a `<video-id>__` prefix because a week pools many
# videos and frame_00000.png is not unique across them.
# ============================================================

SNAPSHOTS_PREFIX = "snapshots"

ANNOTATIONS_DIR = "annotations"

DATA_YAML_FILE = "data.yaml"

# Separates the video id from the original file name.
DATASET_NAME_SEPARATOR = "__"

# YOLO class index per stage: each stage trains a single-class detector.
STAGE_CLASS_NAMES = {
    STAGE_BOARD: ["board"],
    STAGE_PRICE_REGION: ["price region"],
}


ALLOWED_IMAGE_EXTENSIONS = {
    ".jpg",
    ".jpeg",
    ".png",
    ".webp",
    ".bmp",
}


# ============================================================
# SERVICE AUTHENTICATION
#
# Only the labelling UI server talks to this API, so a shared
# key is enough.  The key never reaches the browser.
# ============================================================

SYSTEM_API_KEY = os.environ.get(
    "SYSTEM_API_KEY",
    "",
)


ALLOWED_ORIGINS = [
    origin.strip()
    for origin in os.environ.get(
        "ALLOWED_ORIGINS",
        "http://localhost:3000",
    ).split(",")
    if origin.strip()
]


# ============================================================
# BLOB CLIENTS
# ============================================================

_blob_service_client: BlobServiceClient | None = None


def get_blob_service_client() -> BlobServiceClient:
    """Build the blob client lazily so the app can boot without secrets."""

    global _blob_service_client

    if _blob_service_client is not None:
        return _blob_service_client

    if not AZURE_STORAGE_CONNECTION_STRING:
        raise RuntimeError(
            "AZURE_STORAGE_CONNECTION_STRING is not set."
        )

    _blob_service_client = (
        BlobServiceClient.from_connection_string(
            AZURE_STORAGE_CONNECTION_STRING
        )
    )

    return _blob_service_client


def get_source_container():
    return (
        get_blob_service_client()
        .get_container_client(SOURCE_CONTAINER)
    )


def get_annotated_container():
    return (
        get_blob_service_client()
        .get_container_client(ANNOTATED_CONTAINER)
    )


# ============================================================
# MICROSOFT FABRIC LAKEHOUSE
#
# JSON documents land in OneLake at
#   <workspace>/<lakehouse>/Files/vehicle=<v>/date=<d>/<uuid>.json
# ============================================================

AZURE_TENANT_ID = os.environ.get("AZURE_TENANT_ID", "")

AZURE_CLIENT_ID = os.environ.get("AZURE_CLIENT_ID", "")

AZURE_CLIENT_SECRET = os.environ.get("AZURE_CLIENT_SECRET", "")


FABRIC_WORKSPACE = os.environ.get(
    "FABRIC_WORKSPACE",
    "5f5fe33e-5253-44d9-be78-1efe5ae840f5",
)


FABRIC_LAKEHOUSE = os.environ.get(
    "FABRIC_LAKEHOUSE",
    "a836f844-5e9e-4919-a021-df26cfa6ab87",
)


# Root inside the lakehouse that the vehicle=/date= partitions hang off.
FABRIC_ROOT_PATH = os.environ.get("FABRIC_ROOT_PATH", "Files")


# OneLake DFS endpoint. Change only for sovereign clouds.
ONELAKE_ACCOUNT_URL = os.environ.get(
    "ONELAKE_ACCOUNT_URL",
    "https://onelake.dfs.fabric.microsoft.com",
)


# Largest JSON document accepted in one request.
MAX_BODY_BYTES = 10 * 1024 * 1024


def _looks_like_guid(value: str) -> bool:

    parts = value.split("-")

    if len(parts) != 5 or [len(part) for part in parts] != [8, 4, 4, 4, 12]:
        return False

    return all(char in "0123456789abcdefABCDEF-" for char in value)


def lakehouse_path() -> str:
    """Directory prefix inside the workspace filesystem.

    A lakehouse referenced by name needs the ``.Lakehouse`` suffix; a GUID does
    not. The suffix is added only when the value is not a bare GUID.
    """

    lakehouse = FABRIC_LAKEHOUSE.rstrip("/")

    if (
        not _looks_like_guid(lakehouse)
        and not lakehouse.lower().endswith(".lakehouse")
    ):
        lakehouse = f"{lakehouse}.Lakehouse"

    root = FABRIC_ROOT_PATH.strip("/")

    return f"{lakehouse}/{root}" if root else lakehouse


# ============================================================
# DEVICE API (/v1)
#
# The on-device app used to hold the storage account key and
# sign SAS tokens itself. These settings replace that: a device
# authenticates here, and the server brokers short-lived,
# single-blob SAS URLs so the key never ships in the APK.
# ============================================================

# Signs the short-lived device tokens.
DEVICE_TOKEN_SECRET = os.environ.get("DEVICE_TOKEN_SECRET", "")


DEVICE_TOKEN_TTL_SECONDS = int(
    os.environ.get("DEVICE_TOKEN_TTL_SECONDS", "3600")
)


# Upload SAS: minutes, not hours. One blob, write only.
ARTIFACT_SAS_TTL_SECONDS = int(
    os.environ.get("ARTIFACT_SAS_TTL_SECONDS", "900")
)


# Model SAS: longer, because a model download is large and slow.
MODEL_SAS_TTL_SECONDS = int(
    os.environ.get("MODEL_SAS_TTL_SECONDS", "3600")
)


# Models are split across containers by kind -- `llm` holds the Gemma
# weights, `yolo` the detectors -- so a model is addressed as
# `<container>/<blob>`. The allow list stops a device reaching any other
# container through this endpoint.
MODEL_CONTAINERS = tuple(
    name.strip()
    for name in os.environ.get("MODEL_CONTAINERS", "llm,yolo").split(",")
    if name.strip()
)


# Debug artifacts land in the same container the labelling UI reads, so a
# run id is a video id.
ARTIFACTS_CONTAINER = os.environ.get(
    "ARTIFACTS_CONTAINER",
    SOURCE_CONTAINER,
)


RUN_MANIFEST_FILE = "run_manifest.json"


# ============================================================
# DEVICE CONFIGURATION
#
# The tunables the app used to ship as compiled-in constants
# live in one JSON document in blob storage, so the fleet can
# be re-tuned without a release. The device pulls it from
# /v1/config; the blob is the single source of truth.
#
#   configurations/device-config/device-config.default.json
#
# `device-config/` is a folder inside the shared configurations
# container, not a container of its own.
# ============================================================

DEVICE_CONFIG_CONTAINER = os.environ.get(
    "DEVICE_CONFIG_CONTAINER",
    "configurations",
)


DEVICE_CONFIG_BLOB = os.environ.get(
    "DEVICE_CONFIG_BLOB",
    "device-config/device-config.default.json",
)


# ============================================================
# STATION MAPPING
#
# Held in blob storage rather than in the image, because the
# fleet writes to it: a device that stops somewhere unmapped
# upserts the forecourt it found.
#
#   configurations/stations-data/stations.json
#
# `stations-data/` is a folder inside the shared configurations
# container, not a container of its own.
# ============================================================

STATIONS_CONTAINER = os.environ.get(
    "STATIONS_CONTAINER",
    "configurations",
)


STATIONS_BLOB = os.environ.get(
    "STATIONS_BLOB",
    "stations-data/stations.json",
)


STATIONS_ENRICHED_BLOB = os.environ.get(
    "STATIONS_ENRICHED_BLOB",
    "stations-data/stations-enriched.json",
)


# The document is small and read far more often than written, so it is held
# in process for a short while. A write busts the cache immediately, so this
# only bounds how stale a *different* worker can be.
STATIONS_CACHE_TTL_SECONDS = int(
    os.environ.get("STATIONS_CACHE_TTL_SECONDS", "60")
)


# An upsert is a read-modify-write of the whole document, so two devices
# writing at once would lose one of them. The write is conditional on the
# etag and retried on conflict; this bounds the retries.
STATIONS_WRITE_ATTEMPTS = int(
    os.environ.get("STATIONS_WRITE_ATTEMPTS", "5")
)


# One secret shared by the whole fleet. The device id that comes with it is a
# label for attribution, not a credential, so any device holding this secret
# can authenticate. Rotating it cuts off every device at once, which is the
# trade accepted in exchange for not registering them individually.
FLEET_SECRET = os.environ.get("FLEET_SECRET", "")


# Device ids land in tokens and run manifests, so they are kept boring.
DEVICE_ID_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")


def is_valid_device_id(
    device_id: str
) -> bool:

    return bool(DEVICE_ID_PATTERN.match(device_id or ""))
