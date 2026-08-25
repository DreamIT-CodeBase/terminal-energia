"""Frames captured from a video, extracted server side at a timestamp.

The labelling UI no longer works from whatever the pipeline happened to
extract into `video-id/frames/`. The labeller scrubs the source `video.mp4`
and picks the moments worth annotating, and each pick is decoded here with
PyAV so the frame is exact and full quality rather than whatever a browser
canvas happened to have on screen.

Captured frames are written straight into the working area, so a reload
picks the session back up:

    _in_progress/video-id/frames/t00012340.png
    _in_progress/video-id/frames.json

The file name carries the timestamp in milliseconds, which makes capturing
the same moment twice idempotent instead of a duplicate.
"""

import json

from fractions import Fraction

from io import BytesIO

import av

from azure.core.exceptions import ResourceNotFoundError

from app.config import (
    VIDEO_FILE,
    get_annotated_container,
)

from app.snapshots import (
    download_annotated_blob,
    ensure_container,
    frame_manifest_path,
    list_prefix_images,
    read_json_blob,
    upload_bytes,
    upload_text,
    working_frames_prefix,
)

from app.storage import (
    download_source_blob,
    now_iso,
)


# A capture is named after the moment it was taken from. Eight digits covers
# a little over 27 hours, far past any clip this pipeline sees.
FRAME_NAME_DIGITS = 8


def frame_name_for(
    timestamp_ms: int
) -> str:

    return f"t{timestamp_ms:0{FRAME_NAME_DIGITS}d}.png"


def timestamp_of(
    frame_name: str
) -> int | None:
    """The millisecond offset a captured frame was taken from, or None."""

    stem = frame_name.rsplit(".", 1)[0]

    if not stem.startswith("t") or not stem[1:].isdigit():
        return None

    return int(stem[1:])


# ============================================================
# SOURCE VIDEO
#
# One labeller works through one video at a time, so a single
# entry cache spares re-downloading the clip on every capture.
# ============================================================

_video_cache: tuple[str, bytes] | None = None


def _video_bytes(
    video_id: str
) -> bytes | None:

    global _video_cache

    if _video_cache is not None and _video_cache[0] == video_id:
        return _video_cache[1]

    result = download_source_blob(f"{video_id}/{VIDEO_FILE}")

    if result is None:
        return None

    data, _ = result

    _video_cache = (video_id, data)

    return data


def forget_video(
    video_id: str
) -> None:
    """Drop the cached clip, once its work is filed away."""

    global _video_cache

    if _video_cache is not None and _video_cache[0] == video_id:
        _video_cache = None


# ============================================================
# PROBE
# ============================================================

def probe_video(
    video_id: str
) -> dict | None:
    """Duration and frame size of the source clip, for the capture controls."""

    data = _video_bytes(video_id)

    if data is None:
        return None

    try:
        with av.open(BytesIO(data)) as container:

            stream = container.streams.video[0]

            duration_ms = _duration_ms(container, stream)

            rate = stream.average_rate

            return {
                "video_id": video_id,
                "duration_ms": duration_ms,
                "width": stream.codec_context.width,
                "height": stream.codec_context.height,
                "fps": float(rate) if rate else None,
            }

    except (av.FFmpegError, IndexError, ValueError):
        return None


def _duration_ms(
    container,
    stream,
) -> int:

    if stream.duration is not None and stream.time_base:
        return int(stream.duration * stream.time_base * 1000)

    if container.duration is not None:
        return int(container.duration / av.time_base * 1000)

    return 0


# ============================================================
# EXTRACT
# ============================================================

def _decode_at(
    container,
    stream,
    timestamp_ms: int,
):
    """Decode the frame shown at a millisecond offset.

    Seeking lands on the keyframe at or before the target, so decoding
    continues forward from there until the requested moment is reached. That
    is what makes the capture land on the frame the labeller was looking at
    rather than the nearest keyframe.
    """

    time_base = stream.time_base or Fraction(1, 1000)

    start = stream.start_time or 0

    target = start + int(round((timestamp_ms / 1000) / time_base))

    container.seek(target, stream=stream, backward=True, any_frame=False)

    chosen = None

    for frame in container.decode(stream):

        chosen = frame

        if frame.pts is not None and frame.pts >= target:
            break

    return chosen


def extract_frames(
    video_id: str,
    timestamps_ms: list[int],
) -> dict:
    """Capture one frame per timestamp and store them in the working area.

    The clip is opened once for the whole batch, and timestamps are visited in
    order, so grabbing a spread of frames across a video costs one decode pass
    rather than one per frame.
    """

    data = _video_bytes(video_id)

    if data is None:
        return {
            "frames": [],
            "skipped": [str(ms) for ms in timestamps_ms],
            "error": f"No {VIDEO_FILE} for video '{video_id}'.",
        }

    container_client = get_annotated_container()

    ensure_container(container_client)

    prefix = working_frames_prefix(video_id)

    captured: list[dict] = []
    skipped: list[str] = []

    try:
        with av.open(BytesIO(data)) as source:

            stream = source.streams.video[0]

            # Lets FFmpeg pick slice or frame threading for the codec, which
            # is the difference between a snappy batch and a slow one.
            stream.thread_type = "AUTO"

            duration_ms = _duration_ms(source, stream)

            wanted = sorted(
                {
                    max(
                        0,
                        min(int(ms), duration_ms - 1)
                        if duration_ms > 0
                        else int(ms),
                    )
                    for ms in timestamps_ms
                }
            )

            for timestamp_ms in wanted:

                try:
                    frame = _decode_at(source, stream, timestamp_ms)

                except (av.FFmpegError, ValueError):
                    skipped.append(str(timestamp_ms))
                    continue

                if frame is None:
                    skipped.append(str(timestamp_ms))
                    continue

                image = frame.to_image()

                buffer = BytesIO()
                image.save(buffer, format="PNG")

                payload = buffer.getvalue()

                name = frame_name_for(timestamp_ms)
                path = f"{prefix}/{name}"

                upload_bytes(container_client, path, payload, "image/png")

                captured.append(
                    {
                        "name": name,
                        "path": path,
                        "timestamp_ms": timestamp_ms,
                        "width": image.width,
                        "height": image.height,
                        "size": len(payload),
                    }
                )

    except (av.FFmpegError, IndexError) as error:

        return {
            "frames": [],
            "skipped": [str(ms) for ms in timestamps_ms],
            "error": f"The video could not be decoded: {error}",
        }

    if captured:
        _merge_manifest(video_id, captured)

    return {
        "frames": list_captured_frames(video_id),
        "captured": captured,
        "skipped": skipped,
        "error": None,
    }


# ============================================================
# MANIFEST
#
# The blobs are the truth about which frames exist; the manifest
# only adds the size and timestamp that a listing cannot see.
# ============================================================

def _read_manifest(
    video_id: str
) -> dict[str, dict]:

    payload = read_json_blob(
        get_annotated_container(),
        frame_manifest_path(video_id),
    )

    if not payload:
        return {}

    return {
        entry["name"]: entry
        for entry in payload.get("frames", [])
        if entry.get("name")
    }


def _write_manifest(
    video_id: str,
    entries: list[dict],
) -> None:

    container = get_annotated_container()

    # Discarding the last frame empties the working area, so the manifest goes
    # with it rather than being left behind describing nothing.
    if not entries:

        try:
            container.delete_blob(frame_manifest_path(video_id))

        except ResourceNotFoundError:
            pass

        return

    upload_text(
        container,
        frame_manifest_path(video_id),
        json.dumps(
            {
                "video_id": video_id,
                "updated_at": now_iso(),
                "frames": entries,
            },
            indent=2,
        ),
    )


def _merge_manifest(
    video_id: str,
    captured: list[dict],
) -> None:

    known = _read_manifest(video_id)

    for entry in captured:
        known[entry["name"]] = entry

    _write_manifest(
        video_id,
        sorted(known.values(), key=lambda entry: entry["name"]),
    )


def list_captured_frames(
    video_id: str
) -> list[dict]:
    """Every frame captured for this video, oldest moment first."""

    blobs = list_prefix_images(
        get_annotated_container(),
        working_frames_prefix(video_id),
    )

    known = _read_manifest(video_id)

    frames = []

    for blob in blobs:

        entry = known.get(blob["name"], {})

        frames.append(
            {
                "name": blob["name"],
                "path": blob["path"],
                "timestamp_ms": entry.get("timestamp_ms")
                or timestamp_of(blob["name"])
                or 0,
                "width": entry.get("width", 0),
                "height": entry.get("height", 0),
                "size": blob["size"],
            }
        )

    frames.sort(key=lambda frame: frame["timestamp_ms"])

    return frames


# ============================================================
# READ AND DELETE
# ============================================================

def read_captured_frame(
    video_id: str,
    frame_name: str,
) -> tuple[bytes, str] | None:

    if "/" in frame_name:
        return None

    return download_annotated_blob(
        f"{working_frames_prefix(video_id)}/{frame_name}"
    )


def delete_captured_frame(
    video_id: str,
    frame_name: str,
) -> bool:

    if "/" in frame_name:
        return False

    container = get_annotated_container()

    try:
        container.delete_blob(
            f"{working_frames_prefix(video_id)}/{frame_name}"
        )

    except ResourceNotFoundError:
        return False

    known = _read_manifest(video_id)

    known.pop(frame_name, None)

    _write_manifest(
        video_id,
        sorted(known.values(), key=lambda entry: entry["name"]),
    )

    return True
