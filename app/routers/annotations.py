from fastapi import (
    APIRouter,
    Depends,
    HTTPException,
)

from app.schemas import (
    SaveAnnotationsRequest,
    SaveAnnotationsResponse,
)

from app.security import require_api_key

from app.storage import (
    is_safe_video_id,
    read_saved_annotations,
    save_video_annotations,
)


router = APIRouter(
    prefix="/videos",
    tags=["Annotations"],
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
# READ SAVED ANNOTATIONS
# ============================================================

@router.get(
    "/{video_id}/annotations",
    summary="Return the annotations already saved for one video",
    description=(
        "Reads video-id/annotations.json from the annotated "
        "container so a video can be reopened and corrected."
    ),
)
def get_annotations(
    video_id: str
):

    video_id = _validated_video_id(video_id)

    payload = read_saved_annotations(video_id)

    if payload is None:
        raise HTTPException(
            status_code=404,
            detail=f"No saved annotations for video '{video_id}'.",
        )

    return payload


# ============================================================
# SAVE ANNOTATIONS
# ============================================================

@router.post(
    "/{video_id}/annotations",
    response_model=SaveAnnotationsResponse,
    summary="Save annotated images and YOLO labels",
    description=(
        "Copies the annotated images and writes their YOLO label "
        "files to the annotated container under video-id/."
    ),
)
def save_annotations(
    video_id: str,
    request: SaveAnnotationsRequest,
):

    video_id = _validated_video_id(video_id)

    if not request.classes:
        raise HTTPException(
            status_code=400,
            detail="At least one label class is required.",
        )

    if not request.images:
        raise HTTPException(
            status_code=400,
            detail="No annotated images received.",
        )

    result = save_video_annotations(
        video_id=video_id,
        classes=request.classes,
        images=request.images,
        saved_by=request.saved_by,
    )

    if result["images_written"] == 0:
        raise HTTPException(
            status_code=400,
            detail=(
                "None of the submitted images could be read from "
                f"the source container: {', '.join(result['skipped'])}"
            ),
        )

    return SaveAnnotationsResponse(**result)
