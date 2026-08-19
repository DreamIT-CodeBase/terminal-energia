from fastapi import (
    APIRouter,
    Depends,
    HTTPException,
    Request,
)

from fastapi.responses import Response

from app.schemas import (
    VideoAssetsResponse,
    VideoListResponse,
    VideoSummary,
)

from app.security import require_api_key

from app.storage import (
    download_source_blob,
    download_source_blob_range,
    is_safe_blob_path,
    is_safe_video_id,
    list_annotated_state,
    parse_range_header,
    list_video_assets,
    list_videos as list_source_videos,
    read_api_response,
)


router = APIRouter(
    prefix="/videos",
    tags=["Videos"],
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
# LIST VIDEOS
# ============================================================

@router.get(
    "",
    response_model=VideoListResponse,
    summary="List every video id in the debug directory",
)
def list_videos():

    annotated, completed = list_annotated_state()

    return VideoListResponse(
        videos=[
            VideoSummary(
                video_id=video["video_id"],
                annotated=video["video_id"] in annotated,
                image_count=video["image_count"],
                completed=video["video_id"] in completed,
            )
            for video in list_source_videos()
        ]
    )


# ============================================================
# ONE VIDEO
# ============================================================

@router.get(
    "/{video_id}",
    response_model=VideoAssetsResponse,
    summary="List the frames, crops and prepped images of one video",
)
def get_video(
    video_id: str
):

    video_id = _validated_video_id(video_id)

    assets = list_video_assets(video_id)

    is_empty = (
        not assets["has_api_response"]
        and assets["video_path"] is None
        and all(
            len(files) == 0
            for files in assets["folders"].values()
        )
    )

    if is_empty:
        raise HTTPException(
            status_code=404,
            detail=f"No data found for video '{video_id}'.",
        )

    return VideoAssetsResponse(**assets)


# ============================================================
# API RESPONSE JSON
# ============================================================

@router.get(
    "/{video_id}/api-response",
    summary="Return the api_response.json of one video",
)
def get_api_response(
    video_id: str
):

    video_id = _validated_video_id(video_id)

    payload = read_api_response(video_id)

    if payload is None:
        raise HTTPException(
            status_code=404,
            detail="api_response.json was not found or is not valid JSON.",
        )

    return payload


# ============================================================
# IMAGE BYTES
#
# Streaming through this API keeps the storage credentials on
# the server and removes the need for a CORS rule on Azure.
# ============================================================

@router.get(
    "/{video_id}/file/{blob_path:path}",
    summary="Return the bytes of one blob belonging to a video",
    description=(
        "Supports HTTP range requests, so a browser can start playing "
        "video.mp4 and seek within it without downloading the whole file."
    ),
)
def get_video_file(
    video_id: str,
    blob_path: str,
    request: Request,
):

    video_id = _validated_video_id(video_id)

    full_path = f"{video_id}/{blob_path}"

    if not is_safe_blob_path(full_path):
        raise HTTPException(
            status_code=400,
            detail="Invalid blob path.",
        )

    requested = parse_range_header(request.headers.get("range"))

    # ------------------------------------------------------------
    # PARTIAL CONTENT
    # ------------------------------------------------------------

    if requested is not None:

        start, end = requested

        result = download_source_blob_range(full_path, start, end)

        if result is None:
            raise HTTPException(
                status_code=404,
                detail=f"Blob '{full_path}' was not found.",
            )

        data, content_type, start, end, total = result

        return Response(
            content=data,
            status_code=206,
            media_type=content_type,
            headers={
                "Content-Range": f"bytes {start}-{end}/{total}",
                "Accept-Ranges": "bytes",
                "Content-Length": str(len(data)),
                "Cache-Control": "private, max-age=3600",
            },
        )

    # ------------------------------------------------------------
    # WHOLE BLOB
    # ------------------------------------------------------------

    result = download_source_blob(full_path)

    if result is None:
        raise HTTPException(
            status_code=404,
            detail=f"Blob '{full_path}' was not found.",
        )

    data, content_type = result

    return Response(
        content=data,
        media_type=content_type,
        headers={
            # Advertised so media elements switch to ranged requests.
            "Accept-Ranges": "bytes",
            # Blobs are immutable once written by the pipeline.
            "Cache-Control": "private, max-age=3600",
        },
    )
