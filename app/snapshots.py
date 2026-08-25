"""Weekly dataset snapshots, and the working area a video passes through.

A labelling session runs entirely against one video. Frames are captured from
it into a working area, boards are drawn on those frames, and price regions
are drawn inside each board box. Nothing is written to the annotated container
under a video's own id until the whole video is submitted, because the queue
treats "filed into a week" as "finished".

Submitting files the video into the ISO week it was annotated in:

    snapshots/2026-W34/board/images/<video-id>__<frame>.png
    snapshots/2026-W34/board/labels/<video-id>__<frame>.txt
    snapshots/2026-W34/board/data.yaml
    snapshots/2026-W34/price_region/images/<video-id>__<frame>__<n>.png
    snapshots/2026-W34/price_region/labels/<video-id>__<frame>__<n>.txt
    snapshots/2026-W34/price_region/data.yaml
    snapshots/2026-W34/annotations/<video-id>.json

Each week is therefore a self-contained YOLO dataset that can be trained on
directly. A price is not something a YOLO label file can carry, so prices live
only in the per-video record under `annotations/`.

File names carry a `<video-id>__` prefix: a week pools many videos and
`t00012340.png` is not unique across them.

Working area, while a video is being labelled:

    _in_progress/video-id/frames/<frame>.png
    _in_progress/video-id/frames.json
"""

import json

from datetime import (
    datetime,
    timezone,
)

from io import BytesIO

from pathlib import PurePosixPath

from azure.core.exceptions import ResourceNotFoundError
from azure.storage.blob import ContentSettings

from PIL import Image

from app.config import (
    ANNOTATIONS_DIR,
    DATASET_NAME_SEPARATOR,
    DATA_YAML_FILE,
    FRAME_MANIFEST_FILE,
    SNAPSHOTS_PREFIX,
    STAGE_CLASS_NAMES,
    WORKING_FRAMES_DIR,
    WORKING_PREFIX,
    get_annotated_container,
)

from app.storage import (
    content_type_for,
    is_image,
)


# ============================================================
# PATHS
# ============================================================

def current_week() -> str:
    """ISO year and week, e.g. `2026-W34`."""

    return datetime.now(timezone.utc).strftime("%G-W%V")


def working_prefix(
    video_id: str
) -> str:

    return f"{WORKING_PREFIX}/{video_id}"


def working_frames_prefix(
    video_id: str
) -> str:

    return f"{working_prefix(video_id)}/{WORKING_FRAMES_DIR}"


def frame_manifest_path(
    video_id: str
) -> str:

    return f"{working_prefix(video_id)}/{FRAME_MANIFEST_FILE}"


def week_prefix(
    week: str
) -> str:

    return f"{SNAPSHOTS_PREFIX}/{week}"


def dataset_prefix(
    week: str,
    stage: str,
) -> str:

    return f"{week_prefix(week)}/{stage}"


def record_path(
    week: str,
    video_id: str,
) -> str:

    return f"{week_prefix(week)}/{ANNOTATIONS_DIR}/{video_id}.json"


def dataset_name(
    video_id: str,
    file_name: str,
) -> str:
    """`<video-id>__<file>`, unique within a week."""

    return f"{video_id}{DATASET_NAME_SEPARATOR}{file_name}"


def label_name(
    image_name: str
) -> str:

    return PurePosixPath(image_name).with_suffix(".txt").as_posix()


# ============================================================
# BLOB HELPERS
# ============================================================

def upload_text(
    container,
    blob_path: str,
    content: str,
) -> None:

    container.upload_blob(
        name=blob_path,
        data=content.encode("utf-8"),
        overwrite=True,
        content_settings=ContentSettings(
            content_type=content_type_for(blob_path)
        ),
    )


def upload_bytes(
    container,
    blob_path: str,
    data: bytes,
    content_type: str,
) -> None:

    container.upload_blob(
        name=blob_path,
        data=data,
        overwrite=True,
        content_settings=ContentSettings(content_type=content_type),
    )


def ensure_container(
    container
) -> None:

    try:
        container.create_container()

    except Exception:
        # Already exists, which is the normal case.
        pass


def read_json_blob(
    container,
    blob_path: str,
) -> dict | None:

    try:
        data = container.get_blob_client(blob_path).download_blob().readall()

    except ResourceNotFoundError:
        return None

    try:
        return json.loads(data.decode("utf-8"))

    except (UnicodeDecodeError, json.JSONDecodeError):
        return None


def download_annotated_blob(
    blob_path: str
) -> tuple[bytes, str] | None:
    """Read a blob from the annotated container, for serving captured frames."""

    blob_client = get_annotated_container().get_blob_client(blob_path)

    try:
        downloader = blob_client.download_blob()
        data = downloader.readall()

    except ResourceNotFoundError:
        return None

    settings = downloader.properties.content_settings

    content_type = (
        settings.content_type
        if settings and settings.content_type
        else content_type_for(blob_path)
    )

    return data, content_type


def list_prefix_images(
    container,
    prefix: str,
) -> list[dict]:

    items = []

    try:
        for blob in container.list_blobs(name_starts_with=f"{prefix}/"):

            name = blob.name[len(prefix) + 1:]

            if not name or "/" in name or not is_image(name):
                continue

            items.append(
                {
                    "name": name,
                    "path": blob.name,
                    "size": blob.size or 0,
                }
            )

    except ResourceNotFoundError:
        return []

    items.sort(key=lambda item: item["name"])

    return items


def clear_working_area(
    video_id: str
) -> None:

    container = get_annotated_container()

    prefix = f"{working_prefix(video_id)}/"

    try:
        for blob in list(container.list_blobs(name_starts_with=prefix)):
            try:
                container.delete_blob(blob.name)
            except ResourceNotFoundError:
                pass

    except ResourceNotFoundError:
        return


# ============================================================
# DATASET DESCRIPTOR
# ============================================================

def data_yaml(
    stage: str
) -> str:
    """Ultralytics dataset descriptor for one stage of one week.

    No held-out split is made here: `val` points at the same images, and
    carving out a validation set is left to whoever trains on it.
    """

    names = STAGE_CLASS_NAMES[stage]

    lines = [
        f"# {stage} dataset, generated by the labelling system API.",
        "path: .",
        "train: images",
        "val: images",
        "",
        f"nc: {len(names)}",
        "names:",
    ]

    lines.extend(
        f"  {index}: {name}"
        for index, name in enumerate(names)
    )

    return "\n".join(lines) + "\n"


# ============================================================
# SNAPSHOT LOOKUP
#
# A finished video lives in exactly one week. Weeks are few, so
# they are walked as prefixes rather than listing every blob.
# ============================================================

def list_weeks() -> list[str]:

    container = get_annotated_container()

    weeks = []

    try:
        for prefix in container.walk_blobs(
            name_starts_with=f"{SNAPSHOTS_PREFIX}/",
            delimiter="/",
        ):
            week = prefix.name[len(SNAPSHOTS_PREFIX) + 1:].rstrip("/")

            if week:
                weeks.append(week)

    except ResourceNotFoundError:
        return []

    return sorted(weeks)


def find_snapshot_week(
    video_id: str
) -> str | None:
    """The week a finished video was filed into, if any."""

    container = get_annotated_container()

    for week in list_weeks():

        blob = container.get_blob_client(record_path(week, video_id))

        try:
            if blob.exists():
                return week

        except ResourceNotFoundError:
            continue

    return None


def read_snapshot_record(
    video_id: str
) -> dict | None:
    """The stored record for a finished video, prices included."""

    week = find_snapshot_week(video_id)

    if week is None:
        return None

    return read_json_blob(
        get_annotated_container(),
        record_path(week, video_id),
    )


# ============================================================
# CROP
# ============================================================

def crop_to_box(
    image_bytes: bytes,
    box: list[float],
) -> bytes:
    """Crop to a normalised [x, y, w, h] box and return PNG bytes."""

    with Image.open(BytesIO(image_bytes)) as image:

        width, height = image.size

        x, y, w, h = box[:4]

        # Normalised coordinates can run slightly outside the frame when a box
        # is dragged past an edge, so they are clamped before cropping.
        left = max(0, min(width, round(x * width)))
        top = max(0, min(height, round(y * height)))
        right = max(0, min(width, round((x + w) * width)))
        bottom = max(0, min(height, round((y + h) * height)))

        if right - left < 1 or bottom - top < 1:
            raise ValueError("Box is too small to crop.")

        cropped = image.crop((left, top, right, bottom))

        buffer = BytesIO()
        cropped.convert("RGB").save(buffer, format="PNG")

        return buffer.getvalue()
