"""Video-first labelling: capture frames, then submit the whole video once."""

from fastapi import (
    APIRouter,
    Depends,
    HTTPException,
)

from fastapi.responses import Response

from app.frames import (
    delete_captured_frame,
    extract_frames,
    is_captured,
    list_frames,
    probe_video,
    read_frame,
)

from app.schemas import (
    CaptureFramesRequest,
    CaptureFramesResponse,
    CapturedFramesResponse,
    SubmitVideoRequest,
    SubmitVideoResponse,
    VideoProbeResponse,
)

from app.security import require_api_key

from app.storage import is_safe_video_id

from app.submission import submit_video


router = APIRouter(
    prefix="/videos",
    tags=["Labelling"],
    dependencies=[Depends(require_api_key)],
)


# A batch is bounded so a mistyped request cannot ask for a decode pass over
# thousands of timestamps.
MAX_CAPTURE_BATCH = 200


def _validated_video_id(
    video_id: str
) -> str:

    if not is_safe_video_id(video_id):
        raise HTTPException(
            status_code=400,
            detail="Invalid video id.",
        )

    return video_id


def _validated_frame_name(
    frame_name: str
) -> str:

    if "/" in frame_name or frame_name in ("", ".", ".."):
        raise HTTPException(
            status_code=400,
            detail="Invalid frame name.",
        )

    return frame_name


# ============================================================
# THE SOURCE CLIP
# ============================================================

@router.get(
    "/{video_id}/probe",
    response_model=VideoProbeResponse,
    summary="Duration and frame size of the source video",
    description=(
        "Read before offering capture controls, so the UI can space "
        "suggested captures across the clip without loading it first."
    ),
)
def get_probe(
    video_id: str
):

    video_id = _validated_video_id(video_id)

    result = probe_video(video_id)

    if result is None:
        raise HTTPException(
            status_code=404,
            detail=f"No playable video.mp4 for video '{video_id}'.",
        )

    return VideoProbeResponse(**result)


# ============================================================
# CAPTURED FRAMES
# ============================================================

@router.get(
    "/{video_id}/frames",
    response_model=CapturedFramesResponse,
    summary="Every frame this video offers for labelling",
    description=(
        "What the pipeline extracted into video-id/frames/ first, then "
        "whatever the labeller has captured from the clip on top of it. "
        "Each entry says which of the two it is."
    ),
)
def get_frames(
    video_id: str
):

    video_id = _validated_video_id(video_id)

    return CapturedFramesResponse(
        video_id=video_id,
        frames=list_frames(video_id),
    )


@router.post(
    "/{video_id}/frames",
    response_model=CaptureFramesResponse,
    summary="Capture frames from the video at the given timestamps",
    description=(
        "Decodes video.mp4 at each millisecond offset and stores the frames "
        "in the working area. Capturing the same moment twice is idempotent: "
        "a frame is named after the timestamp it came from."
    ),
)
def post_frames(
    video_id: str,
    request: CaptureFramesRequest,
):

    video_id = _validated_video_id(video_id)

    if not request.timestamps_ms:
        raise HTTPException(
            status_code=400,
            detail="No timestamps received.",
        )

    if len(request.timestamps_ms) > MAX_CAPTURE_BATCH:
        raise HTTPException(
            status_code=400,
            detail=(
                f"At most {MAX_CAPTURE_BATCH} frames can be captured in one "
                "request."
            ),
        )

    if any(ms < 0 for ms in request.timestamps_ms):
        raise HTTPException(
            status_code=400,
            detail="Timestamps must not be negative.",
        )

    result = extract_frames(video_id, request.timestamps_ms)

    if result.get("error"):
        raise HTTPException(
            status_code=404 if "No video" in result["error"] else 400,
            detail=result["error"],
        )

    if not result["captured"]:
        raise HTTPException(
            status_code=400,
            detail=(
                "No frame could be decoded at "
                f"{', '.join(result['skipped'])} ms."
            ),
        )

    return CaptureFramesResponse(
        video_id=video_id,
        frames=result["frames"],
        captured=result["captured"],
        skipped=result["skipped"],
    )


@router.get(
    "/{video_id}/frames/{frame_name}/file",
    summary="Bytes of one captured frame",
    description=(
        "Served through this API like every other image, so no storage "
        "credential reaches the browser."
    ),
)
def get_frame_file(
    video_id: str,
    frame_name: str,
):

    video_id = _validated_video_id(video_id)
    frame_name = _validated_frame_name(frame_name)

    result = read_frame(video_id, frame_name)

    if result is None:
        raise HTTPException(
            status_code=404,
            detail=f"Frame '{frame_name}' was not found.",
        )

    data, content_type, _ = result

    return Response(
        content=data,
        media_type=content_type,
        # Neither kind of frame changes once it exists: a capture is named
        # after its timestamp, and a pipeline frame is written once.
        headers={"Cache-Control": "private, max-age=3600"},
    )


@router.delete(
    "/{video_id}/frames/{frame_name}",
    response_model=CapturedFramesResponse,
    summary="Discard one captured frame",
    description=(
        "Only a capture can be discarded. Frames the pipeline extracted are "
        "source data and are not this service's to delete; leaving one "
        "unlabelled already keeps it out of the dataset."
    ),
)
def delete_frame(
    video_id: str,
    frame_name: str,
):

    video_id = _validated_video_id(video_id)
    frame_name = _validated_frame_name(frame_name)

    if not is_captured(video_id, frame_name):

        raise HTTPException(
            status_code=404,
            detail=(
                f"No captured frame '{frame_name}'. Frames extracted by the "
                "pipeline cannot be discarded."
            ),
        )

    delete_captured_frame(video_id, frame_name)

    return CapturedFramesResponse(
        video_id=video_id,
        frames=list_frames(video_id),
    )


# ============================================================
# SUBMIT
# ============================================================

@router.post(
    "/{video_id}/submit",
    response_model=SubmitVideoResponse,
    summary="File a finished video into this week",
    description=(
        "Writes every kept frame with its board labels, cuts each board box "
        "into a crop with its price-region labels, and records the prices "
        "alongside. Clears the working area, which finishes the video."
    ),
)
def post_submit(
    video_id: str,
    request: SubmitVideoRequest,
):

    video_id = _validated_video_id(video_id)

    if not request.frames:
        raise HTTPException(
            status_code=400,
            detail="No frames received.",
        )

    if not any(frame.boards for frame in request.frames):
        raise HTTPException(
            status_code=400,
            detail="No board was drawn on any frame, so there is nothing to file.",
        )

    result = submit_video(
        video_id=video_id,
        frames=request.frames,
        saved_by=request.saved_by,
    )

    if result["frames_written"] == 0:
        raise HTTPException(
            status_code=400,
            detail=(
                "None of the submitted frames could be read from the working "
                f"area: {', '.join(result['skipped'])}"
            ),
        )

    return SubmitVideoResponse(**result)
