"""Two-stage labelling: boards on the frames, then price regions on the crops."""

from fastapi import (
    APIRouter,
    Depends,
    HTTPException,
)

from fastapi.responses import Response

from app.config import (
    STAGE_BOARD,
    STAGE_PRICE_REGION,
)

from app.schemas import (
    StageStateResponse,
    SubmitStageRequest,
    SubmitStageResponse,
)

from app.security import require_api_key

from app.stages import (
    current_stage,
    download_crop,
    list_board_crops,
    read_stage_annotations,
    submit_board_stage,
    submit_price_region_stage,
)

from app.storage import is_safe_video_id


router = APIRouter(
    prefix="/videos",
    tags=["Labelling Stages"],
    dependencies=[Depends(require_api_key)],
)


def _validated_video_id(
    video_id: str
) -> str:

    if not is_safe_video_id(video_id):
        raise HTTPException(
            status_code=400,
            detail="Invalid video id.",
        )

    return video_id


# ============================================================
# WHERE THIS VIDEO IS IN THE FLOW
# ============================================================

@router.get(
    "/{video_id}/stage",
    response_model=StageStateResponse,
    summary="Which labelling stage a video is on",
    description=(
        "Returns the stage the UI should open, whether each stage has "
        "been submitted, and the crops that stage 2 annotates."
    ),
)
def get_stage(
    video_id: str
):

    video_id = _validated_video_id(video_id)

    return StageStateResponse(**current_stage(video_id))


# ============================================================
# PREVIOUSLY SUBMITTED BOXES FOR ONE STAGE
# ============================================================

@router.get(
    "/{video_id}/stage/{stage}/annotations",
    summary="Boxes already submitted for one stage",
)
def get_stage_annotations(
    video_id: str,
    stage: str,
):

    video_id = _validated_video_id(video_id)

    if stage not in (STAGE_BOARD, STAGE_PRICE_REGION):
        raise HTTPException(
            status_code=400,
            detail=f"Unknown stage '{stage}'.",
        )

    payload = read_stage_annotations(video_id, stage)

    if payload is None:
        raise HTTPException(
            status_code=404,
            detail=f"Stage '{stage}' has not been submitted for this video.",
        )

    return payload


# ============================================================
# CROP BYTES
#
# Stage 2 draws on these, so they are served the same way as
# the source frames: through the API, never via a SAS URL.
# ============================================================

@router.get(
    "/{video_id}/crop/{crop_name}",
    summary="Bytes of one board crop",
)
def get_crop(
    video_id: str,
    crop_name: str,
):

    video_id = _validated_video_id(video_id)

    if "/" in crop_name:
        raise HTTPException(
            status_code=400,
            detail="Invalid crop name.",
        )

    result = download_crop(video_id, crop_name)

    if result is None:
        raise HTTPException(
            status_code=404,
            detail=f"Crop '{crop_name}' was not found.",
        )

    data, content_type = result

    return Response(
        content=data,
        media_type=content_type,
        headers={"Cache-Control": "private, max-age=3600"},
    )


# ============================================================
# SUBMIT A STAGE
# ============================================================

@router.post(
    "/{video_id}/stage/board",
    response_model=SubmitStageResponse,
    summary="Submit the board pass and crop the frames",
    description=(
        "Stores every annotated frame with its board labels, then crops "
        "each frame to its board boxes. The crops are returned and become "
        "the images for the price-region pass."
    ),
)
def submit_board(
    video_id: str,
    request: SubmitStageRequest,
):

    video_id = _validated_video_id(video_id)

    if not request.images:
        raise HTTPException(
            status_code=400,
            detail="No annotated frames received.",
        )

    result = submit_board_stage(
        video_id=video_id,
        images=request.images,
        saved_by=request.saved_by,
    )

    if result["images_written"] == 0:
        raise HTTPException(
            status_code=400,
            detail=(
                "None of the submitted frames could be read: "
                f"{', '.join(result['skipped'])}"
            ),
        )

    if not result["crops"]:
        raise HTTPException(
            status_code=400,
            detail=(
                "No crops could be produced from the board boxes, so there "
                "is nothing to label for price regions."
            ),
        )

    return SubmitStageResponse(**result)


@router.post(
    "/{video_id}/stage/price-region",
    response_model=SubmitStageResponse,
    summary="Submit the price-region pass",
    description=(
        "Stores every annotated crop with its price-region labels. This "
        "completes the video."
    ),
)
def submit_price_region(
    video_id: str,
    request: SubmitStageRequest,
):

    video_id = _validated_video_id(video_id)

    if not request.images:
        raise HTTPException(
            status_code=400,
            detail="No annotated crops received.",
        )

    if not list_board_crops(video_id):
        raise HTTPException(
            status_code=409,
            detail=(
                "The board pass has not produced any crops for this video, "
                "so the price-region pass cannot be submitted yet."
            ),
        )

    result = submit_price_region_stage(
        video_id=video_id,
        images=request.images,
        saved_by=request.saved_by,
    )

    if result["images_written"] == 0:
        raise HTTPException(
            status_code=400,
            detail=(
                "None of the submitted crops could be read: "
                f"{', '.join(result['skipped'])}"
            ),
        )

    return SubmitStageResponse(**result)
