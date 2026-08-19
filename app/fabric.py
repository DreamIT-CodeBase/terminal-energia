"""OneLake / Fabric lakehouse writer.

Thin wrapper around the ADLS Gen2 (DFS) API that OneLake exposes:

    <account>/<workspace>/<lakehouse>/Files/vehicle=<v>/date=<d>/<uuid>.json
"""

import logging
import threading

from azure.core import MatchConditions

from azure.core.exceptions import (
    AzureError,
    ClientAuthenticationError,
    ResourceExistsError,
    ResourceNotFoundError,
)

from azure.identity import (
    ClientSecretCredential,
    DefaultAzureCredential,
)

from azure.storage.filedatalake import DataLakeServiceClient

from app import config


log = logging.getLogger(__name__)


class FabricError(RuntimeError):
    """Upload failed for a reason the caller should hear about."""

    def __init__(
        self,
        message: str,
        *,
        status_code: int = 502,
    ):
        super().__init__(message)
        self.status_code = status_code


class LakehouseWriter:
    """Uploads bytes into a lakehouse. Safe to share across requests.

    The credential and service client are built once and reused so tokens are
    cached instead of re-fetched on every upload.
    """

    def __init__(self):
        self._lock = threading.Lock()
        self._service: DataLakeServiceClient | None = None

    # ========================================================
    # WIRING
    # ========================================================

    def _credential(self):

        if (
            config.AZURE_TENANT_ID
            and config.AZURE_CLIENT_ID
            and config.AZURE_CLIENT_SECRET
        ):
            return ClientSecretCredential(
                tenant_id=config.AZURE_TENANT_ID,
                client_id=config.AZURE_CLIENT_ID,
                client_secret=config.AZURE_CLIENT_SECRET,
            )

        # Falls back to managed identity / az login, which is what runs when
        # this is hosted in Azure and no service principal is configured.
        log.info("No service principal configured; using DefaultAzureCredential")

        return DefaultAzureCredential()

    def _service_client(self) -> DataLakeServiceClient:

        if self._service is None:
            with self._lock:
                if self._service is None:
                    self._service = DataLakeServiceClient(
                        account_url=config.ONELAKE_ACCOUNT_URL,
                        credential=self._credential(),
                    )

        return self._service

    # ========================================================
    # OPERATIONS
    # ========================================================

    def upload_json(
        self,
        relative_path: str,
        payload: bytes,
    ) -> str:
        """Write ``payload`` at ``relative_path`` under the lakehouse root.

        Returns the full path within the workspace filesystem.
        """

        full_path = f"{config.lakehouse_path()}/{relative_path}"

        try:
            file_system = self._service_client().get_file_system_client(
                config.FABRIC_WORKSPACE
            )

            file_client = file_system.get_file_client(full_path)

            # create -> append -> flush, rather than upload_data(). On OneLake,
            # upload_data(overwrite=False) skips the create and appends to a
            # file that does not exist yet, which fails with PathNotFound.
            # create_file also brings the vehicle=/date= parent folders into
            # existence, so no separate mkdir is needed.
            #
            # IfMissing turns the create into If-None-Match:* -- a concurrent
            # writer of the same path loses instead of silently overwriting.
            # (Pass no `etag` alongside it; the SDK leaks that kwarg to the
            # transport and raises TypeError.)
            file_client.create_file(match_condition=MatchConditions.IfMissing)
            file_client.append_data(payload, offset=0, length=len(payload))
            file_client.flush_data(len(payload))

        except ClientAuthenticationError as exc:
            log.exception("Fabric authentication failed")

            raise FabricError(
                "Authentication to OneLake failed -- check the service "
                "principal credentials and its access to the Fabric workspace.",
                status_code=502,
            ) from exc

        except ResourceExistsError as exc:
            # A UUID collision, which effectively never happens.
            raise FabricError(
                f"File already exists at {full_path}",
                status_code=409,
            ) from exc

        except ResourceNotFoundError as exc:
            log.exception("Path not found writing %s", full_path)

            raise FabricError(
                f"OneLake could not resolve {full_path} -- check that the "
                f"workspace ({config.FABRIC_WORKSPACE}) and lakehouse path "
                f"({config.lakehouse_path()}) exist and the identity can "
                f"see them.",
            ) from exc

        except AzureError as exc:
            log.exception("Upload to %s failed", full_path)

            raise FabricError(
                f"Upload to OneLake failed: {exc}"
            ) from exc

        log.info("Wrote %d bytes to %s", len(payload), full_path)

        return full_path

    def check_access(self) -> None:
        """Verify the service can authenticate and see the lakehouse root."""

        try:
            file_system = self._service_client().get_file_system_client(
                config.FABRIC_WORKSPACE
            )

            (
                file_system
                .get_directory_client(config.lakehouse_path())
                .get_directory_properties()
            )

        except ClientAuthenticationError as exc:
            raise FabricError(f"Authentication failed: {exc}") from exc

        except AzureError as exc:
            raise FabricError(
                f"Cannot reach {config.lakehouse_path()}: {exc}"
            ) from exc

    def close(self) -> None:

        if self._service is not None:
            try:
                self._service.close()

            except Exception:
                # Best-effort shutdown.
                pass

            self._service = None


# ============================================================
# SHARED INSTANCE
#
# Built lazily, like the blob clients, so the app can boot
# without Fabric credentials configured.
# ============================================================

_writer: LakehouseWriter | None = None

_writer_lock = threading.Lock()


def get_lakehouse_writer() -> LakehouseWriter:

    global _writer

    if _writer is None:
        with _writer_lock:
            if _writer is None:
                _writer = LakehouseWriter()

    return _writer


def close_lakehouse_writer() -> None:

    global _writer

    if _writer is not None:
        _writer.close()
        _writer = None
