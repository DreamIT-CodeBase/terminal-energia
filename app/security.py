import hmac

from fastapi import (
    Header,
    HTTPException,
)

from app.config import SYSTEM_API_KEY


API_KEY_HEADER = "x-api-key"


def require_api_key(
    x_api_key: str | None = Header(
        default=None,
        alias=API_KEY_HEADER,
    ),
) -> None:
    """Reject every caller that does not present the shared service key."""

    if not SYSTEM_API_KEY:
        raise HTTPException(
            status_code=500,
            detail="SYSTEM_API_KEY is not configured on the server.",
        )

    if not x_api_key:
        raise HTTPException(
            status_code=401,
            detail="Missing API key.",
        )

    # Constant-time compare so the key cannot be guessed byte by byte.
    if not hmac.compare_digest(x_api_key, SYSTEM_API_KEY):
        raise HTTPException(
            status_code=401,
            detail="Invalid API key.",
        )
