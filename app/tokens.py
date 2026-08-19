"""Short-lived device tokens.

The fleet shares one secret. A device presents it once and receives a token
that expires; everything under /v1 then carries that token. A leaked APK
therefore yields a revocable fleet credential that only buys scoped, minutes-
long SAS URLs, rather than the storage account key itself.

The device id travelling with the secret is a **label, not an identity**: it is
recorded on tokens and run manifests for attribution, but anything holding the
fleet secret can claim any id. Revocation is fleet-wide by design — rotate
FLEET_SECRET to cut every device off.
"""

import hmac

from datetime import (
    datetime,
    timedelta,
    timezone,
)

import jwt

from fastapi import (
    Depends,
    HTTPException,
)

from fastapi.security import (
    HTTPAuthorizationCredentials,
    HTTPBearer,
)

from app.config import (
    DEVICE_TOKEN_SECRET,
    DEVICE_TOKEN_TTL_SECONDS,
    FLEET_SECRET,
    is_valid_device_id,
)


ALGORITHM = "HS256"

AUDIENCE = "device"


bearer_scheme = HTTPBearer(auto_error=False)


class DeviceAuthError(HTTPException):
    """401 with the header a client needs to know what to send."""

    def __init__(
        self,
        detail: str
    ):
        super().__init__(
            status_code=401,
            detail=detail,
            headers={"WWW-Authenticate": "Bearer"},
        )


def _require_secret() -> str:

    if not DEVICE_TOKEN_SECRET:
        raise HTTPException(
            status_code=500,
            detail="DEVICE_TOKEN_SECRET is not configured on the server.",
        )

    return DEVICE_TOKEN_SECRET


# ============================================================
# ISSUE
# ============================================================

def authenticate_device(
    device_id: str,
    device_secret: str,
) -> None:
    """Check the fleet secret, in constant time.

    The id is only validated for shape: it labels the caller, it does not
    authenticate it.
    """

    if not FLEET_SECRET:
        raise HTTPException(
            status_code=500,
            detail="FLEET_SECRET is not configured on the server.",
        )

    if not is_valid_device_id(device_id):
        raise HTTPException(
            status_code=400,
            detail=(
                "Device id must be 1-128 chars of letters, digits, dot, "
                "underscore or hyphen, starting with a letter or digit."
            ),
        )

    if not hmac.compare_digest(device_secret, FLEET_SECRET):
        raise DeviceAuthError("Invalid fleet secret.")


def issue_device_token(
    device_id: str
) -> tuple[str, int, str]:
    """Return ``(token, expires_in_seconds, expires_at_iso)``."""

    secret = _require_secret()

    now = datetime.now(timezone.utc)

    expires_at = now + timedelta(seconds=DEVICE_TOKEN_TTL_SECONDS)

    token = jwt.encode(
        {
            "sub": device_id,
            "aud": AUDIENCE,
            "iat": int(now.timestamp()),
            "exp": int(expires_at.timestamp()),
        },
        secret,
        algorithm=ALGORITHM,
    )

    return (
        token,
        DEVICE_TOKEN_TTL_SECONDS,
        expires_at.isoformat().replace("+00:00", "Z"),
    )


# ============================================================
# VERIFY
# ============================================================

def require_device(
    credentials: HTTPAuthorizationCredentials | None = Depends(bearer_scheme),
) -> str:
    """Resolve the calling device id, or raise 401."""

    secret = _require_secret()

    if credentials is None or not credentials.credentials:
        raise DeviceAuthError("Missing bearer token.")

    try:
        payload = jwt.decode(
            credentials.credentials,
            secret,
            algorithms=[ALGORITHM],
            audience=AUDIENCE,
        )

    except jwt.ExpiredSignatureError as exc:
        raise DeviceAuthError("Token has expired.") from exc

    except jwt.InvalidTokenError as exc:
        raise DeviceAuthError("Token is not valid.") from exc

    device_id = payload.get("sub")

    if not device_id:
        raise DeviceAuthError("Token carries no device id.")

    # There is no per-device registry to check against: the token's signature
    # is the whole claim. Rotating DEVICE_TOKEN_SECRET invalidates every
    # outstanding token at once.
    return device_id
