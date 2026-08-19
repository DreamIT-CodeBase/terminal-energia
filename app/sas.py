"""Short-lived, single-blob SAS URLs.

The server signs these so the device never holds the account key. Each URL is
scoped to one blob, one set of permissions and a few minutes of validity, which
is the smallest useful grant: a leaked upload URL lets someone overwrite one
artifact of one run until it expires, and nothing else.
"""

from datetime import (
    datetime,
    timedelta,
    timezone,
)

from azure.storage.blob import (
    BlobSasPermissions,
    generate_blob_sas,
)

from app.config import get_blob_service_client


def _account() -> tuple[str, str]:
    """Account name and key, taken from the configured connection string."""

    service = get_blob_service_client()

    credential = service.credential

    account_key = getattr(credential, "account_key", None)

    if not account_key:
        raise RuntimeError(
            "The storage credential has no account key, so SAS URLs cannot "
            "be signed. Configure AZURE_STORAGE_CONNECTION_STRING with a key."
        )

    return service.account_name, account_key


def _blob_url(
    container: str,
    blob_path: str,
    sas: str,
) -> str:

    service = get_blob_service_client()

    return (
        f"{service.url.rstrip('/')}/{container}/{blob_path}?{sas}"
    )


def _sign(
    container: str,
    blob_path: str,
    permissions: BlobSasPermissions,
    ttl_seconds: int,
) -> tuple[str, str]:
    """Return ``(url, expires_at_iso)`` for one blob."""

    account_name, account_key = _account()

    expires_at = (
        datetime.now(timezone.utc) +
        timedelta(seconds=ttl_seconds)
    )

    sas = generate_blob_sas(
        account_name=account_name,
        container_name=container,
        blob_name=blob_path,
        account_key=account_key,
        permission=permissions,
        expiry=expires_at,
    )

    return (
        _blob_url(container, blob_path, sas),
        expires_at.isoformat().replace("+00:00", "Z"),
    )


def upload_url(
    container: str,
    blob_path: str,
    ttl_seconds: int,
) -> tuple[str, str]:
    """A write-only grant for one blob.

    `create` and `write` only: no read, no delete, no listing. Re-uploading
    the same bytes to the same path overwrites in place, which is what makes
    the outbox's retries harmless.
    """

    return _sign(
        container,
        blob_path,
        BlobSasPermissions(create=True, write=True),
        ttl_seconds,
    )


def download_url(
    container: str,
    blob_path: str,
    ttl_seconds: int,
) -> tuple[str, str]:
    """A read-only grant for one blob."""

    return _sign(
        container,
        blob_path,
        BlobSasPermissions(read=True),
        ttl_seconds,
    )
