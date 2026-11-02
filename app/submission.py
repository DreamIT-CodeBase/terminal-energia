"""Filing a finished video into the week it was annotated in.

One submit closes out a whole video. Everything the labeller produced is in
the request: which frames to keep, the board boxes drawn on each of them, and
the price regions drawn inside each board box.

A frame may be one the pipeline extracted into `video-id/frames/` or one the
labeller captured from the clip. Which it is only decides where its bytes are
read from, which `read_frame` resolves; both end up in the same dataset, and
the record notes where each came from.

Crops are produced here rather than during labelling. The UI shows a board by
displaying its frame clipped to the board box, so moving or resizing a board
never invalidates a crop that has already been written; the real crop PNG is
cut exactly once, at submit, from coordinates that are final.

Region coordinates arrive normalised against the board crop, not the frame,
which is what the price-region dataset needs and what the clipped view was
already drawing in.
"""

from pathlib import PurePosixPath

from app.config import (
    STAGE_BOARD,
    STAGE_PRICE_REGION,
    get_annotated_container,
)

from app.frames import (
    forget_video,
    read_frame,
)

from app.snapshots import (
    clear_working_area,
    crop_to_box,
    current_week,
    data_yaml,
    dataset_name,
    dataset_prefix,
    ensure_container,
    label_name,
    record_path,
    upload_bytes,
    upload_text,
)

from app.storage import now_iso

import json


# Both datasets train a single-class detector, so every box is class 0.
CLASS_INDEX = 0


def _yolo_lines(
    boxes: list,
) -> str:
    """YOLO rows from normalised top-left [x, y, w, h] boxes."""

    lines = []

    for box in boxes:

        coordinates = box.coordinates

        if len(coordinates) < 4:
            continue

        x, y, w, h = coordinates[:4]

        if w <= 0 or h <= 0:
            continue

        lines.append(
            " ".join(
                [
                    str(CLASS_INDEX),
                    f"{x + w / 2:.6f}",
                    f"{y + h / 2:.6f}",
                    f"{w:.6f}",
                    f"{h:.6f}",
                ]
            )
        )

    return "\n".join(lines)


def submit_video(
    video_id: str,
    frames: list,
    saved_by: str,
) -> dict:

    destination = get_annotated_container()

    ensure_container(destination)

    week = current_week()

    board_prefix = dataset_prefix(week, STAGE_BOARD)
    region_prefix = dataset_prefix(week, STAGE_PRICE_REGION)

    skipped: list[str] = []

    frames_written = 0
    crops_written = 0
    regions_written = 0
    boards_without_regions = 0

    filed_frames: list[dict] = []

    for frame in frames:

        if not frame.boards:
            # Nothing was drawn on this frame, so it is not part of the
            # dataset. Not an error: capturing more than you use is normal.
            continue

        # A frame is either one the pipeline extracted or one the labeller
        # captured. The resolver reports which, and recording it keeps the
        # provenance of every dataset image.
        result = read_frame(video_id, frame.name)

        if result is None:
            skipped.append(frame.name)
            continue

        frame_bytes, _, origin = result

        stored_frame = dataset_name(video_id, frame.name)

        upload_bytes(
            destination,
            f"{board_prefix}/images/{stored_frame}",
            frame_bytes,
            "image/png",
        )

        upload_text(
            destination,
            f"{board_prefix}/labels/{label_name(stored_frame)}",
            _yolo_lines(frame.boards),
        )

        frames_written += 1

        stem = PurePosixPath(frame.name).stem

        filed_boards: list[dict] = []

        for index, board in enumerate(frame.boards):

            crop_name = f"{stem}__{index:02d}.png"

            try:
                crop_bytes = crop_to_box(frame_bytes, board.coordinates)

            except (ValueError, OSError):
                skipped.append(f"{frame.name} board {index + 1}")
                continue

            stored_crop = dataset_name(video_id, crop_name)

            upload_bytes(
                destination,
                f"{region_prefix}/images/{stored_crop}",
                crop_bytes,
                "image/png",
            )

            upload_text(
                destination,
                f"{region_prefix}/labels/{label_name(stored_crop)}",
                _yolo_lines(board.regions),
            )

            crops_written += 1
            regions_written += len(board.regions)

            if not board.regions:
                boards_without_regions += 1

            filed_boards.append(
                {
                    "crop_name": crop_name,
                    "dataset_name": stored_crop,
                    "coordinates": board.coordinates,
                    "regions": [
                        {
                            "coordinates": region.coordinates,
                            "price": region.price,
                        }
                        for region in board.regions
                    ],
                }
            )

        filed_frames.append(
            {
                "name": frame.name,
                "dataset_name": stored_frame,
                "origin": origin,
                "timestamp_ms": frame.timestamp_ms,
                "width": frame.width,
                "height": frame.height,
                "boards": filed_boards,
            }
        )

    saved_at = now_iso()

    if frames_written == 0:
        # Nothing was filed, so the working area is left alone and the video
        # keeps its place in the queue.
        return {
            "video_id": video_id,
            "week": None,
            "frames_written": 0,
            "crops_written": 0,
            "regions_written": 0,
            "boards_without_regions": 0,
            "skipped": skipped,
            "saved_at": saved_at,
        }

    upload_text(destination, f"{board_prefix}/data.yaml", data_yaml(STAGE_BOARD))

    upload_text(
        destination,
        f"{region_prefix}/data.yaml",
        data_yaml(STAGE_PRICE_REGION),
    )

    # Prices cannot live in a YOLO label file, so this record is the only
    # place the whole annotation survives intact.
    upload_text(
        destination,
        record_path(week, video_id),
        json.dumps(
            {
                "video_id": video_id,
                "week": week,
                "saved_at": saved_at,
                "saved_by": saved_by,
                "frames": filed_frames,
            },
            indent=2,
        ),
    )

    clear_working_area(video_id)

    forget_video(video_id)

    return {
        "video_id": video_id,
        "week": week,
        "frames_written": frames_written,
        "crops_written": crops_written,
        "regions_written": regions_written,
        "boards_without_regions": boards_without_regions,
        "skipped": skipped,
        "saved_at": saved_at,
    }
