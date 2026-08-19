"""Two-stage labelling.

Stage 1 draws board boxes on the extracted frames. Submitting it crops each
frame to its board boxes; those crops are the canvas for stage 2, which draws
price regions inside them.

A video must not appear in the annotated container under its own id until
*both* passes are done, because the UI treats "present in the annotated
container" as "finished". So stage 1 writes nothing final: its crops and its
board payload are parked in a working area, and stage 2 promotes everything
into place and clears the working area. An interrupted video therefore stays
in the queue with its crops intact, rather than disappearing half-done.

Working area, during stage 2:

    _in_progress/video-id/board_annotations.json
    _in_progress/video-id/crops/<frame>__<n>.png

Final layout, written when stage 2 is submitted:

    video-id/board/images/<frame>.png
    video-id/board/labels/<frame>.txt
    video-id/board/classes.txt
    video-id/board/annotations.json
    video-id/price_region/images/<crop>.png
    video-id/price_region/labels/<crop>.txt
    video-id/price_region/classes.txt
    video-id/price_region/annotations.json
"""

import json

from io import BytesIO

from pathlib import PurePosixPath

from azure.core.exceptions import ResourceNotFoundError
from azure.storage.blob import ContentSettings

from PIL import Image

from app.config import (
    ANNOTATIONS_FILE,
    BOARD_PAYLOAD_FILE,
    CLASSES_FILE,
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

def _working_prefix(
    video_id: str
) -> str:

    return f"{WORKING_PREFIX}/{video_id}"


def _working_crops_prefix(
    video_id: str
) -> str:

    return f"{_working_prefix(video_id)}/crops"


def _final_prefix(
    video_id: str,
    stage: str,
) -> str:

    return f"{video_id}/{stage}"


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

    container = get_annotated_container()

    if stage == STAGE_BOARD:

        # Before completion the board pass lives in the working area; after
        # completion it has been promoted to its final home.
        working = read_working_board(video_id)

        if working is not None:
            return working

    return _read_json_blob(
        container,
        f"{_final_prefix(video_id, stage)}/{ANNOTATIONS_FILE}",
    )


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
    working area is gone, so the promoted copies are listed instead and a
    completed video can still be reopened.
    """

    container = get_annotated_container()

    working = _list_prefix_images(
        container,
        _working_crops_prefix(video_id),
    )

    if working:
        return working

    return _list_prefix_images(
        container,
        f"{_final_prefix(video_id, STAGE_PRICE_REGION)}/images",
    )


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
    """Serve a crop from the working area, or from its promoted copy."""

    for path in (
        f"{_working_crops_prefix(video_id)}/{crop_name}",
        f"{_final_prefix(video_id, STAGE_PRICE_REGION)}/images/{crop_name}",
    ):
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

    container = get_annotated_container()

    working_board = read_working_board(video_id)

    final_board = _read_json_blob(
        container,
        f"{_final_prefix(video_id, STAGE_BOARD)}/{ANNOTATIONS_FILE}",
    )

    final_price_region = _read_json_blob(
        container,
        f"{_final_prefix(video_id, STAGE_PRICE_REGION)}/{ANNOTATIONS_FILE}",
    )

    board_submitted = (
        working_board is not None or
        final_board is not None
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
        "price_region_submitted": final_price_region is not None,
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
# stage 2 finishes it.
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

    # The board boxes are kept verbatim so stage 2 can promote them without
    # asking the browser for them again.
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
# Promotes the board pass and writes the price-region pass, so
# the video appears in the annotated container complete or not
# at all. The working area is cleared afterwards.
# ============================================================

def _promote_board_pass(
    video_id: str,
    payload: dict,
) -> tuple[int, list[str]]:
    """Write the stored board pass to its final home, reading frames again."""

    source = get_source_container()
    destination = get_annotated_container()

    prefix = _final_prefix(video_id, STAGE_BOARD)

    skipped: list[str] = []

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

        _upload_bytes(
            destination,
            f"{prefix}/images/{name}",
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
            f"{prefix}/labels/{_label_name(name)}",
            _yolo_from_records(boxes, class_ids[:1]),
        )

        written += 1

    _upload_text(
        destination,
        f"{prefix}/{CLASSES_FILE}",
        "\n".join(STAGE_CLASS_NAMES[STAGE_BOARD]),
    )

    _upload_text(
        destination,
        f"{prefix}/{ANNOTATIONS_FILE}",
        json.dumps(payload, indent=2),
    )

    return written, skipped


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

    prefix = _final_prefix(video_id, STAGE_PRICE_REGION)

    class_ids = _single_class_ids(images)

    crops_prefix = _working_crops_prefix(video_id)

    skipped: list[str] = []

    images_written = 0

    # Normally the crops sit in the working area. A completed video reopened
    # for correction works from the promoted copies instead.
    allowed_prefixes = (
        f"{crops_prefix}/",
        f"{prefix}/images/",
    )

    for image in images:

        # Stage 2 annotates those crops, nothing else.
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

        _upload_bytes(
            destination,
            f"{prefix}/images/{image.name}",
            crop_bytes,
            content_type_for(image.name),
        )

        _upload_text(
            destination,
            f"{prefix}/labels/{_label_name(image.name)}",
            to_yolo_lines(image.annotations, class_ids),
        )

        images_written += 1

    if images_written == 0:
        # Nothing was promoted, so the working area is left untouched and the
        # video keeps its place in the queue.
        return {
            "video_id": video_id,
            "stage": STAGE_PRICE_REGION,
            "images_written": 0,
            "crops": [],
            "skipped": skipped,
            "saved_at": now_iso(),
        }

    saved_at = now_iso()

    _upload_text(
        destination,
        f"{prefix}/{CLASSES_FILE}",
        "\n".join(STAGE_CLASS_NAMES[STAGE_PRICE_REGION]),
    )

    _upload_text(
        destination,
        f"{prefix}/{ANNOTATIONS_FILE}",
        json.dumps(
            {
                "video_id": video_id,
                "stage": STAGE_PRICE_REGION,
                "saved_at": saved_at,
                "saved_by": saved_by,
                "images": [
                    item.model_dump(by_alias=True)
                    for item in images
                ],
            },
            indent=2,
        ),
    )

    # The board pass is promoted only now, so the video appears under its own
    # id in the annotated container complete rather than half-done.
    if board_payload is not None:
        board_written, board_skipped = _promote_board_pass(
            video_id,
            board_payload,
        )
        skipped.extend(board_skipped)
    else:
        board_written = 0

    _clear_working_area(video_id)

    return {
        "video_id": video_id,
        "stage": STAGE_PRICE_REGION,
        "images_written": images_written,
        "board_images_written": board_written,
        "crops": [],
        "skipped": skipped,
        "saved_at": saved_at,
    }
