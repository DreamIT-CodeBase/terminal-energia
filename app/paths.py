"""Partition-key validation and lakehouse path construction."""

import re
import uuid

from datetime import (
    date,
    datetime,
    timezone,
)


# Hive-style partition values: kept boring so the path is always safe and
# Spark/Fabric can read the partition back without escaping games.
_SAFE_VALUE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")


class InvalidPartitionValue(ValueError):
    """Raised when a vehicle/date value cannot be used as a partition value."""


def clean_vehicle(
    value: str
) -> str:

    value = (value or "").strip()

    if not _SAFE_VALUE.match(value):
        raise InvalidPartitionValue(
            "vehicle must be 1-128 chars of letters, digits, dot, underscore "
            "or hyphen, starting with a letter or digit"
        )

    return value


def clean_date(
    value: str | None
) -> str:
    """Normalise to YYYY-MM-DD. Defaults to today (UTC) when omitted."""

    if value is None or not str(value).strip():
        return datetime.now(timezone.utc).date().isoformat()

    value = str(value).strip()

    try:
        return date.fromisoformat(value).isoformat()

    except ValueError as exc:
        raise InvalidPartitionValue(
            f"date must be an ISO date (YYYY-MM-DD), got {value!r}"
        ) from exc


def build_relative_path(
    vehicle: str,
    date_value: str,
    filename: str,
) -> str:
    """vehicle=<v>/date=<d>/<filename>, relative to the lakehouse root."""

    return f"vehicle={vehicle}/date={date_value}/{filename}"


def new_filename() -> str:

    return f"{uuid.uuid4()}.json"
