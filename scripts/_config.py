"""Shared config loading and workspace connection for the scripts.

config/workspace.yml is the single source of truth for everything
environment-specific. The scripts read it and push the values into the
pipeline job at submit time, so the YAML placeholders never have to be
edited unless you bypass these scripts entirely.
"""

from datetime import datetime, timezone

from pathlib import Path

import yaml


REPO_ROOT = Path(__file__).resolve().parent.parent

CONFIG_PATH = REPO_ROOT / "config" / "workspace.yml"

PLACEHOLDER = "REPLACE_ME"


class ConfigError(RuntimeError):
    pass


def current_week() -> str:
    """ISO year and week, e.g. `2026-W34`.

    Deliberately identical to `current_week()` in system-api/app/stages.py --
    the labelling system names its snapshot folders with this, so any drift
    here would silently point the data asset at a week that does not exist.
    """

    return datetime.now(timezone.utc).strftime("%G-W%V")


def load_config(
    path: Path | None = None
) -> dict:

    config_path = path or CONFIG_PATH

    if not config_path.exists():
        raise ConfigError(f"Config file not found: {config_path}")

    config = yaml.safe_load(config_path.read_text()) or {}

    missing = sorted(_find_placeholders(config))

    if missing:
        raise ConfigError(
            f"{config_path} still has placeholder values at: "
            + ", ".join(missing)
            + ".\nFill these in before running anything against the workspace."
        )

    return config


def _find_placeholders(
    node,
    trail: str = "",
) -> list[str]:

    found: list[str] = []

    if isinstance(node, dict):
        for key, value in node.items():
            found.extend(
                _find_placeholders(value, f"{trail}.{key}" if trail else str(key))
            )

    elif isinstance(node, str) and PLACEHOLDER in node:
        found.append(trail or "(root)")

    return found


def stage_config(
    config: dict,
    stage: str,
) -> dict:

    stages = config.get("stages") or {}

    if stage not in stages:
        raise ConfigError(
            f"Unknown stage '{stage}'. Known stages: {sorted(stages)}"
        )

    return stages[stage]


def get_ml_client(
    config: dict
):

    from azure.ai.ml import MLClient
    from azure.identity import DefaultAzureCredential

    azure = config["azure"]

    return MLClient(
        credential=DefaultAzureCredential(),
        subscription_id=azure["subscription_id"],
        resource_group_name=azure["resource_group"],
        workspace_name=azure["workspace_name"],
    )


def snapshot_uri(
    config: dict,
    week: str,
    stage: str,
) -> str:
    """Datastore URI for one week's snapshot of one stage.

    Mirrors the layout documented in system-api/app/stages.py:
        snapshots/<week>/<stage>/{images,labels}/
    """

    datastore = config["datastores"]["annotated"]

    return (
        f"azureml://datastores/{datastore}/paths/"
        f"snapshots/{week}/{stage}/"
    )


def model_datastore_uri(
    config: dict,
    suffix: str = "",
) -> str:

    datastore = config["datastores"]["models"]

    return f"azureml://datastores/{datastore}/paths/{suffix.lstrip('/')}"
