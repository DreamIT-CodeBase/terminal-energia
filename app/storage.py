import json
import mimetypes

from datetime import (
    datetime,
    timezone,
)

from pathlib import PurePosixPath

from azure.core.exceptions import ResourceNotFoundError

from app.config import (
    ALLOWED_IMAGE_EXTENSIONS,
    ANNOTATIONS_FILE,
    API_RESPONSE_FILE,
    CLASSES_FILE,
    IMAGE_FOLDERS,
    STAGE_PRICE_REGION,
    VIDEO_FILE,
    WORKING_PREFIX,
    get_annotated_container,
    get_source_container,
)


# ============================================================
# PATH SAFETY
#
# Blob paths arrive from the labelling UI, so they are checked
# before they are handed to the SDK.
# ============================================================

def is_safe_video_id(
    video_id: str
) -> bool:

    if not video_id or len(video_id) > 512:
        return False

    if "/" in video_id or "\\" in video_id:
        return False

    return video_id not in {".", ".."}


def is_safe_blob_path(
    blob_path: str
) -> bool:

    if not blob_path or len(blob_path) > 1024:
        return False

    if blob_path.startswith("/") or "\\" in blob_path:
        return False

    return all(
        segment and segment not in {".", ".."}
        for segment in blob_path.split("/")
    )


def is_image(
    file_name: str
) -> bool:

    return (
        PurePosixPath(file_name).suffix.lower()
        in ALLOWED_IMAGE_EXTENSIONS
    )


def content_type_for(
    file_name: str
) -> str:

    guessed, _ = mimetypes.guess_type(file_name)

    return guessed or "application/octet-stream"


def now_iso() -> str:

    return (
        datetime.now(timezone.utc)
        .isoformat()
        .replace("+00:00", "Z")
    )


# ============================================================
# LIST VIDEO IDS
#
# Every top level prefix of the source container is one video.
# ============================================================

def list_videos() -> list[dict]:
    """Every video id, newest first, with how many annotatable images it holds.

    Video ids start with a UTC timestamp (``20260810-122718-0b1a``), so sorting
    the id in reverse is a temporal sort, newest first.

    Not every video in the debug directory has been through the frame
    extraction stage -- plenty hold only JSON -- so the count lets the UI open
    on one that actually has something to annotate.
    """

    container = get_source_container()

    counts: dict[str, int] = {}

    for blob in container.list_blobs():

        parts = blob.name.split("/")

        video_id = parts[0]

        if not video_id:
            continue

        counts.setdefault(video_id, 0)

        if (
            len(parts) >= 3
            and parts[1] in IMAGE_FOLDERS
            and is_image(parts[-1])
        ):
            counts[video_id] += 1

    return [
        {"video_id": video_id, "image_count": counts[video_id]}
        for video_id in sorted(counts, reverse=True)
    ]


def list_annotated_state() -> tuple[set[str], set[str]]:
    """Which videos have been written to, and which are finished.

    A video is finished once the price-region pass has been submitted, which
    is the last step of the flow. Both sets come from one listing.
    """

    container = get_annotated_container()

    annotated: set[str] = set()
    completed: set[str] = set()

    try:
        for blob in container.list_blobs():

            head = blob.name.split("/", 1)[0]

            if not head or head == WORKING_PREFIX:
                # Half-finished work is parked under the working prefix and
                # must not make a video look annotated.
                continue

            annotated.add(head)

            if blob.name == (
                f"{head}/{STAGE_PRICE_REGION}/{ANNOTATIONS_FILE}"
            ):
                completed.add(head)

    except ResourceNotFoundError:
        # The annotated container has not been created yet.
        return set(), set()

    return annotated, completed


# ============================================================
# LIST ONE VIDEO
# ============================================================

def list_video_assets(
    video_id: str
) -> dict:

    container = get_source_container()

    folders: dict[str, list[dict]] = {
        folder: []
        for folder in IMAGE_FOLDERS
    }

    has_api_response = False
    video_path = None

    prefix = f"{video_id}/"

    for blob in container.list_blobs(name_starts_with=prefix):

        relative = blob.name[len(prefix):]

        if relative == API_RESPONSE_FILE:
            has_api_response = True
            continue

        if relative == VIDEO_FILE:
            video_path = blob.name
            continue

        if "/" not in relative:
            continue

        folder, file_name = relative.split("/", 1)

        if folder not in folders:
            continue

        if not is_image(file_name):
            continue

        folders[folder].append(
            {
                "name": file_name,
                "path": blob.name,
                "size": blob.size or 0,
            }
        )

    for folder in folders:
        folders[folder].sort(key=lambda item: item["name"])

    return {
        "video_id": video_id,
        "folders": folders,
        "has_api_response": has_api_response,
        "video_path": video_path,
    }


# ============================================================
# READ BLOBS
# ============================================================

def download_source_blob(
    blob_path: str
) -> tuple[bytes, str] | None:
    """Return the bytes and content type of a blob, or None if missing."""

    container = get_source_container()

    blob_client = container.get_blob_client(blob_path)

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


# Largest slice returned for a single range request. A media element that
# asks for "bytes=0-" would otherwise pull the whole file into memory before
# anything reaches the browser.
RANGE_CHUNK_BYTES = 4 * 1024 * 1024


def download_source_blob_range(
    blob_path: str,
    start: int,
    end: int | None,
) -> tuple[bytes, str, int, int, int] | None:
    """Read part of a blob.

    Returns ``(data, content_type, start, end, total)`` with ``end``
    inclusive, or None when the blob does not exist. Ranges beyond the blob
    are clamped, and each response is capped at RANGE_CHUNK_BYTES so video
    playback starts without buffering the entire file.
    """

    blob_client = get_source_container().get_blob_client(blob_path)

    try:
        properties = blob_client.get_blob_properties()

    except ResourceNotFoundError:
        return None

    total = properties.size or 0

    if total == 0:
        return b"", content_type_for(blob_path), 0, 0, 0

    start = max(0, min(start, total - 1))

    if end is None or end >= total:
        end = total - 1

    if end < start:
        end = start

    if end - start + 1 > RANGE_CHUNK_BYTES:
        end = start + RANGE_CHUNK_BYTES - 1

    length = end - start + 1

    data = blob_client.download_blob(
        offset=start,
        length=length,
    ).readall()

    settings = properties.content_settings

    content_type = (
        settings.content_type
        if settings and settings.content_type
        else content_type_for(blob_path)
    )

    return data, content_type, start, end, total


def parse_range_header(
    value: str | None
) -> tuple[int, int | None] | None:
    """Parse a single ``bytes=start-end`` range. Anything else is ignored."""

    if not value:
        return None

    value = value.strip()

    if not value.startswith("bytes=") or "," in value:
        return None

    spec = value[len("bytes="):].strip()

    if "-" not in spec:
        return None

    start_text, _, end_text = spec.partition("-")

    try:
        if not start_text:
            # A suffix range (`bytes=-500`) is served from the start instead;
            # media elements do not rely on it.
            return None

        start = int(start_text)

        end = int(end_text) if end_text else None

    except ValueError:
        return None

    if start < 0 or (end is not None and end < start):
        return None

    return start, end


def read_api_response(
    video_id: str
) -> dict | list | None:

    result = download_source_blob(
        f"{video_id}/{API_RESPONSE_FILE}"
    )

    if result is None:
        return None

    data, _ = result

    try:
        return json.loads(data.decode("utf-8"))

    except (UnicodeDecodeError, json.JSONDecodeError):
        return None


def read_saved_annotations(
    video_id: str
) -> dict | None:

    container = get_annotated_container()

    blob_client = container.get_blob_client(
        f"{video_id}/{ANNOTATIONS_FILE}"
    )

    try:
        data = blob_client.download_blob().readall()

    except ResourceNotFoundError:
        return None

    try:
        return json.loads(data.decode("utf-8"))

    except (UnicodeDecodeError, json.JSONDecodeError):
        return None


# ============================================================
# YOLO LABELS
# ============================================================

def to_yolo_lines(
    annotations: list,
    class_ids: list[str],
) -> str:
    """`class_index x_center y_center width height`, all normalised."""

    lines = []

    for annotation in annotations:

        if annotation.class_id not in class_ids:
            continue

        class_index = class_ids.index(annotation.class_id)

        if annotation.type == "rect":

            x, y, w, h = annotation.coordinates[:4]

            lines.append(
                " ".join(
                    [
                        str(class_index),
                        f"{x + w / 2:.6f}",
                        f"{y + h / 2:.6f}",
                        f"{w:.6f}",
                        f"{h:.6f}",
                    ]
                )
            )

            continue

        # Polygons, polylines and points keep their raw normalised points.
        lines.append(
            " ".join(
                [str(class_index)]
                + [
                    f"{value:.6f}"
                    for value in annotation.coordinates
                ]
            )
        )

    return "\n".join(lines)


# ============================================================
# WRITE ONE VIDEO
#
#   video-id/images/<folder>/<file>
#   video-id/labels/<folder>/<file>.txt
#   video-id/classes.txt
#   video-id/annotations.json
# ============================================================

def _upload_text(
    container,
    blob_path: str,
    content: str,
) -> None:

    from azure.storage.blob import ContentSettings

    container.upload_blob(
        name=blob_path,
        data=content.encode("utf-8"),
        overwrite=True,
        content_settings=ContentSettings(
            content_type=content_type_for(blob_path)
        ),
    )


def save_video_annotations(
    video_id: str,
    classes: list,
    images: list,
    saved_by: str,
) -> dict:

    from azure.storage.blob import ContentSettings

    source = get_source_container()
    destination = get_annotated_container()

    try:
        destination.create_container()

    except Exception:
        # Already exists, which is the normal case.
        pass

    class_ids = [item.id for item in classes]

    skipped: list[str] = []

    images_written = 0
    labels_written = 0

    for image in images:

        # The image must genuinely belong to this video.
        if (
            not is_safe_blob_path(image.path)
            or not image.path.startswith(f"{video_id}/")
        ):
            skipped.append(image.name)
            continue

        result = None

        try:
            result = source.get_blob_client(
                image.path
            ).download_blob().readall()

        except ResourceNotFoundError:
            skipped.append(image.name)
            continue

        destination.upload_blob(
            name=f"{video_id}/images/{image.folder}/{image.name}",
            data=result,
            overwrite=True,
            content_settings=ContentSettings(
                content_type=content_type_for(image.name)
            ),
        )

        images_written += 1

        label_name = PurePosixPath(image.name).with_suffix(".txt").as_posix()

        _upload_text(
            destination,
            f"{video_id}/labels/{image.folder}/{label_name}",
            to_yolo_lines(image.annotations, class_ids),
        )

        labels_written += 1

    _upload_text(
        destination,
        f"{video_id}/{CLASSES_FILE}",
        "\n".join(item.name for item in classes),
    )

    saved_at = now_iso()

    payload = {
        "video_id": video_id,
        "saved_at": saved_at,
        "saved_by": saved_by,
        "classes": [item.model_dump() for item in classes],
        "images": [
            item.model_dump(by_alias=True)
            for item in images
        ],
    }

    _upload_text(
        destination,
        f"{video_id}/{ANNOTATIONS_FILE}",
        json.dumps(payload, indent=2),
    )

    return {
        "video_id": video_id,
        "images_written": images_written,
        "labels_written": labels_written,
        "skipped": skipped,
        "saved_at": saved_at,
    }
