"""Two-stage labelling, written out as weekly dataset snapshots.

Stage 1 draws board boxes on the extracted frames. Submitting it crops each
frame to its board boxes; those crops are the canvas for stage 2, which draws
price regions inside them.

Nothing final is written until stage 2 completes. Stage 1 parks its crops and
its board payload in a working area, so a video interrupted between the passes
stays in the queue with its crops intact instead of disappearing half-done.

Finishing a video files it into the ISO week it was annotated in:

    snapshots/2026-W34/board/images/<video-id>__<frame>.png
    snapshots/2026-W34/board/labels/<video-id>__<frame>.txt
    snapshots/2026-W34/board/data.yaml
    snapshots/2026-W34/price_region/images/<video-id>__<crop>.png
    snapshots/2026-W34/price_region/labels/<video-id>__<crop>.txt
    snapshots/2026-W34/price_region/data.yaml
    snapshots/2026-W34/annotations/<video-id>.json

Each week is therefore a self-contained YOLO dataset that can be trained on
directly. The two stages are kept apart because they train two different
single-class detectors on different images -- full frames for boards, crops
for price regions -- so they cannot share one data.yaml.

File names carry a `<video-id>__` prefix: a week pools many videos and
`frame_00000.png` is not unique across them.

Working area, during stage 2:

    _in_progress/video-id/board_annotations.json
    _in_progress/video-id/crops/<frame>__<n>.png
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
    BOARD_PAYLOAD_FILE,
    DATASET_NAME_SEPARATOR,
    DATA_YAML_FILE,
    SNAPSHOTS_PREFIX,
    STAGE_BOARD,
    STAGE_CLASS_NAMES,
    STAGE_PRICE_REGION,
    WORKING_PREFIX,
    get_annotated_container,
    get_source_container,
)

from app.storage import (
    content_type_for,
    is_image,
    is_safe_blob_path,
    now_iso,
    to_yolo_lines,
)


# ============================================================
# PATHS
# ============================================================

def current_week() -> str:
    """ISO year and week, e.g. `2026-W34`."""

    return datetime.now(timezone.utc).strftime("%G-W%V")


def _working_prefix(
    video_id: str
) -> str:

    return f"{WORKING_PREFIX}/{video_id}"


def _working_crops_prefix(
    video_id: str
) -> str:

    return f"{_working_prefix(video_id)}/crops"


def _week_prefix(
    week: str
) -> str:

    return f"{SNAPSHOTS_PREFIX}/{week}"


def _dataset_prefix(
    week: str,
    stage: str,
) -> str:

    return f"{_week_prefix(week)}/{stage}"


def _record_path(
    week: str,
    video_id: str,
) -> str:

    return f"{_week_prefix(week)}/{ANNOTATIONS_DIR}/{video_id}.json"


def dataset_name(
    video_id: str,
    file_name: str,
) -> str:
    """`<video-id>__<file>`, unique within a week."""

    return f"{video_id}{DATASET_NAME_SEPARATOR}{file_name}"


def _label_name(
    image_name: str
) -> str:

    return PurePosixPath(image_name).with_suffix(".txt").as_posix()


# ============================================================
# HELPERS
# ============================================================

def _upload_text(
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


def _upload_bytes(
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


def _ensure_container(
    container
) -> None:

    try:
        container.create_container()

    except Exception:
        # Already exists, which is the normal case.
        pass


def _read_json_blob(
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


def _single_class_ids(
    images: list
) -> list[str]:
    """Each stage trains a single-class detector, so every box is index 0."""

    for image in images:
        for annotation in image.annotations:
            return [annotation.class_id]

    return []


def _data_yaml(
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

        blob = container.get_blob_client(_record_path(week, video_id))

        try:
            if blob.exists():
                return week

        except ResourceNotFoundError:
            continue

    return None


def read_snapshot_record(
    video_id: str
) -> dict | None:
    """The stored annotations for a finished video."""

    week = find_snapshot_week(video_id)

    if week is None:
        return None

    return _read_json_blob(
        get_annotated_container(),
        _record_path(week, video_id),
    )


# ============================================================
# READ STATE
# ============================================================

def read_working_board(
    video_id: str
) -> dict | None:
    """The board payload parked by stage 1, if stage 2 is still outstanding."""

    return _read_json_blob(
        get_annotated_container(),
        f"{_working_prefix(video_id)}/{BOARD_PAYLOAD_FILE}",
    )


def read_stage_annotations(
    video_id: str,
    stage: str,
) -> dict | None:
    """Boxes submitted for one stage, wherever they currently live."""

    if stage == STAGE_BOARD:

        # Before completion the board pass sits in the working area; after
        # completion it is part of the week's record.
        working = read_working_board(video_id)

        if working is not None:
            return working

    record = read_snapshot_record(video_id)

    if record is None:
        return None

    stage_record = record.get("stages", {}).get(stage)

    if stage_record is None:
        return None

    return {
        "video_id": video_id,
        "stage": stage,
        "saved_at": record.get("saved_at"),
        "saved_by": record.get("saved_by"),
        "images": stage_record.get("images", []),
    }


def _list_prefix_images(
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


def list_board_crops(
    video_id: str
) -> list[dict]:
    """The crops stage 2 annotates.

    Normally these sit in the working area. Once the video is finished the
    working area is gone, so the snapshot copies are listed instead and a
    completed video can still be reopened. Snapshot names carry the video id
    prefix, which is stripped back off so the caller sees the original name.
    """

    container = get_annotated_container()

    working = _list_prefix_images(
        container,
        _working_crops_prefix(video_id),
    )

    if working:
        return working

    week = find_snapshot_week(video_id)

    if week is None:
        return []

    marker = dataset_name(video_id, "")

    return [
        {
            "name": item["name"][len(marker):],
            "path": item["path"],
            "size": item["size"],
        }
        for item in _list_prefix_images(
            container,
            f"{_dataset_prefix(week, STAGE_PRICE_REGION)}/images",
        )
        if item["name"].startswith(marker)
    ]


def download_annotated_blob(
    blob_path: str
) -> tuple[bytes, str] | None:
    """Read a blob from the annotated container, for serving crops."""

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


def download_crop(
    video_id: str,
    crop_name: str,
) -> tuple[bytes, str] | None:
    """Serve a crop from the working area, or from its snapshot copy."""

    candidates = [f"{_working_crops_prefix(video_id)}/{crop_name}"]

    week = find_snapshot_week(video_id)

    if week is not None:
        candidates.append(
            f"{_dataset_prefix(week, STAGE_PRICE_REGION)}/images/"
            f"{dataset_name(video_id, crop_name)}"
        )

    for path in candidates:

        if not is_safe_blob_path(path):
            continue

        result = download_annotated_blob(path)

        if result is not None:
            return result

    return None


def current_stage(
    video_id: str
) -> dict:
    """Where this video is in the two-pass flow, and what stage 2 works on."""

    working_board = read_working_board(video_id)

    record = read_snapshot_record(video_id)

    board_submitted = (
        working_board is not None or
        record is not None
    )

    crops = list_board_crops(video_id) if board_submitted else []

    # Stage 2 only becomes reachable once stage 1 has produced crops.
    stage = (
        STAGE_PRICE_REGION
        if board_submitted and crops
        else STAGE_BOARD
    )

    return {
        "video_id": video_id,
        "stage": stage,
        "board_submitted": board_submitted,
        "price_region_submitted": record is not None,
        "crops": crops,
    }


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


# ============================================================
# STAGE 1: BOARDS
#
# Nothing final is written here. The crops and the board boxes
# go to the working area, so the video stays in the queue until
# stage 2 files it into a week.
# ============================================================

def submit_board_stage(
    video_id: str,
    images: list,
    saved_by: str,
) -> dict:

    source = get_source_container()
    destination = get_annotated_container()

    _ensure_container(destination)

    crops_prefix = _working_crops_prefix(video_id)

    skipped: list[str] = []
    crops: list[dict] = []

    accepted: list = []

    for image in images:

        if (
            not is_safe_blob_path(image.path)
            or not image.path.startswith(f"{video_id}/")
        ):
            skipped.append(image.name)
            continue

        try:
            frame_bytes = (
                source
                .get_blob_client(image.path)
                .download_blob()
                .readall()
            )

        except ResourceNotFoundError:
            skipped.append(image.name)
            continue

        stem = PurePosixPath(image.name).stem

        produced = False

        for index, annotation in enumerate(image.annotations):

            try:
                crop_bytes = crop_to_box(frame_bytes, annotation.coordinates)

            except (ValueError, OSError):
                skipped.append(f"{image.name} box {index + 1}")
                continue

            crop_name = f"{stem}__{index:02d}.png"

            crop_path = f"{crops_prefix}/{crop_name}"

            _upload_bytes(destination, crop_path, crop_bytes, "image/png")

            crops.append(
                {
                    "name": crop_name,
                    "path": crop_path,
                    "size": len(crop_bytes),
                }
            )

            produced = True

        if produced:
            accepted.append(image)

    saved_at = now_iso()

    # The board boxes are kept verbatim so stage 2 can file them into the week
    # without asking the browser for them again.
    _upload_text(
        destination,
        f"{_working_prefix(video_id)}/{BOARD_PAYLOAD_FILE}",
        json.dumps(
            {
                "video_id": video_id,
                "stage": STAGE_BOARD,
                "saved_at": saved_at,
                "saved_by": saved_by,
                "images": [
                    item.model_dump(by_alias=True)
                    for item in accepted
                ],
            },
            indent=2,
        ),
    )

    return {
        "video_id": video_id,
        "stage": STAGE_BOARD,
        "images_written": len(accepted),
        "crops": crops,
        "skipped": skipped,
        "saved_at": saved_at,
    }


# ============================================================
# STAGE 2: PRICE REGIONS
#
# Files both passes into this week's snapshot, so the video
# appears in the annotated container complete or not at all.
# ============================================================

def _yolo_from_records(
    records: list[dict],
    class_ids: list[str],
) -> str:
    """YOLO lines from plain dicts, as stored in the working payload."""

    lines = []

    for record in records:

        class_id = record.get("classId")

        if class_id not in class_ids:
            continue

        index = class_ids.index(class_id)

        coordinates = record.get("coordinates") or []

        if record.get("type") == "rect" and len(coordinates) >= 4:

            x, y, w, h = coordinates[:4]

            lines.append(
                " ".join(
                    [
                        str(index),
                        f"{x + w / 2:.6f}",
                        f"{y + h / 2:.6f}",
                        f"{w:.6f}",
                        f"{h:.6f}",
                    ]
                )
            )

    return "\n".join(lines)


def _file_board_pass(
    video_id: str,
    week: str,
    payload: dict,
) -> tuple[int, list[str], list[dict]]:
    """Write the stored board pass into the week, re-reading the frames."""

    source = get_source_container()
    destination = get_annotated_container()

    prefix = _dataset_prefix(week, STAGE_BOARD)

    skipped: list[str] = []
    filed: list[dict] = []

    written = 0

    for image in payload.get("images", []):

        name = image.get("name")
        path = image.get("path")

        if (
            not name
            or not path
            or not is_safe_blob_path(path)
            or not path.startswith(f"{video_id}/")
        ):
            skipped.append(str(name))
            continue

        try:
            frame_bytes = (
                source
                .get_blob_client(path)
                .download_blob()
                .readall()
            )

        except ResourceNotFoundError:
            skipped.append(name)
            continue

        stored = dataset_name(video_id, name)

        _upload_bytes(
            destination,
            f"{prefix}/images/{stored}",
            frame_bytes,
            content_type_for(name),
        )

        boxes = image.get("annotations", [])

        class_ids = [
            box.get("classId")
            for box in boxes
            if box.get("classId")
        ]

        _upload_text(
            destination,
            f"{prefix}/labels/{_label_name(stored)}",
            _yolo_from_records(boxes, class_ids[:1]),
        )

        written += 1

        filed.append({**image, "dataset_name": stored})

    _upload_text(destination, f"{prefix}/{DATA_YAML_FILE}", _data_yaml(STAGE_BOARD))

    return written, skipped, filed


def _clear_working_area(
    video_id: str
) -> None:

    container = get_annotated_container()

    prefix = f"{_working_prefix(video_id)}/"

    try:
        for blob in list(container.list_blobs(name_starts_with=prefix)):
            try:
                container.delete_blob(blob.name)
            except ResourceNotFoundError:
                pass

    except ResourceNotFoundError:
        return


def submit_price_region_stage(
    video_id: str,
    images: list,
    saved_by: str,
) -> dict:

    destination = get_annotated_container()

    _ensure_container(destination)

    board_payload = read_working_board(video_id)

    week = current_week()

    prefix = _dataset_prefix(week, STAGE_PRICE_REGION)

    class_ids = _single_class_ids(images)

    crops_prefix = _working_crops_prefix(video_id)

    # Normally the crops sit in the working area. A completed video reopened
    # for correction works from its snapshot copies instead.
    allowed_prefixes = (f"{crops_prefix}/", f"{SNAPSHOTS_PREFIX}/")

    skipped: list[str] = []
    filed: list[dict] = []

    images_written = 0

    for image in images:

        if (
            not is_safe_blob_path(image.path)
            or not image.path.startswith(allowed_prefixes)
        ):
            skipped.append(image.name)
            continue

        try:
            crop_bytes = (
                destination
                .get_blob_client(image.path)
                .download_blob()
                .readall()
            )

        except ResourceNotFoundError:
            skipped.append(image.name)
            continue

        stored = dataset_name(video_id, image.name)

        _upload_bytes(
            destination,
            f"{prefix}/images/{stored}",
            crop_bytes,
            content_type_for(image.name),
        )

        _upload_text(
            destination,
            f"{prefix}/labels/{_label_name(stored)}",
            to_yolo_lines(image.annotations, class_ids),
        )

        images_written += 1

        filed.append(
            {**image.model_dump(by_alias=True), "dataset_name": stored}
        )

    if images_written == 0:
        # Nothing was filed, so the working area is left untouched and the
        # video keeps its place in the queue.
        return {
            "video_id": video_id,
            "stage": STAGE_PRICE_REGION,
            "images_written": 0,
            "board_images_written": 0,
            "crops": [],
            "skipped": skipped,
            "saved_at": now_iso(),
        }

    _upload_text(
        destination,
        f"{prefix}/{DATA_YAML_FILE}",
        _data_yaml(STAGE_PRICE_REGION),
    )

    # The board pass is filed only now, so the video enters the week complete
    # rather than half-done.
    board_written = 0
    board_filed: list[dict] = []

    if board_payload is not None:
        board_written, board_skipped, board_filed = _file_board_pass(
            video_id,
            week,
            board_payload,
        )
        skipped.extend(board_skipped)

    saved_at = now_iso()

    _upload_text(
        destination,
        _record_path(week, video_id),
        json.dumps(
            {
                "video_id": video_id,
                "week": week,
                "saved_at": saved_at,
                "saved_by": saved_by,
                "stages": {
                    STAGE_BOARD: {"images": board_filed},
                    STAGE_PRICE_REGION: {"images": filed},
                },
            },
            indent=2,
        ),
    )

    _clear_working_area(video_id)

    return {
        "video_id": video_id,
        "stage": STAGE_PRICE_REGION,
        "week": week,
        "images_written": images_written,
        "board_images_written": board_written,
        "crops": [],
        "skipped": skipped,
        "saved_at": saved_at,
    }
