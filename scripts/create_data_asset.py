"""Register one week's annotated snapshot as a versioned data asset.

    python scripts/create_data_asset.py --stage board
    python scripts/create_data_asset.py --stage price_region --week 2026-W34

The asset is a `uri_folder` pointing at

    snapshots/<week>/<stage>/

on the annotated datastore. Nothing is copied -- the asset is a named,
versioned reference to blobs the labelling system already wrote, which is why
creating one is cheap enough to do every week.

The version string is the ISO week itself rather than an incrementing number,
so `board-annotated-week:2026-W34` says what it is without a lookup. Re-running
for the same week reuses that version by default; pass --new-version when more
videos were annotated into a week that was already registered, and you get
`2026-W34.2`.

This is a separate step from submitting the pipeline because an Azure ML
pipeline resolves its input assets when the job is created, not while it runs.
submit_pipeline.py calls into here for you, so you rarely need to run it
directly -- it is useful on its own for registering a backfill.
"""

import argparse
import sys

from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from _config import (  # noqa: E402
    current_week,
    get_ml_client,
    load_config,
    snapshot_uri,
    stage_config,
)


def parse_arguments():

    parser = argparse.ArgumentParser(
        description="Register a week's snapshot as a versioned data asset."
    )

    parser.add_argument(
        "--stage",
        required=True,
        choices=("board", "price_region"),
    )

    parser.add_argument(
        "--week",
        default=None,
        help="ISO week, e.g. 2026-W34. Defaults to the current UTC week.",
    )

    parser.add_argument(
        "--new-version",
        action="store_true",
        help=(
            "Force a new version even if this week is already registered. Use "
            "when more videos were annotated into an already-registered week."
        ),
    )

    return parser.parse_args()


# ============================================================
# VERSIONING
# ============================================================

def existing_versions(
    ml_client,
    name: str,
) -> list[str]:

    try:
        return [asset.version for asset in ml_client.data.list(name=name)]

    except Exception:
        # No asset with this name yet, which is the first-run case.
        return []


def pick_version(
    ml_client,
    name: str,
    week: str,
    force_new: bool,
) -> tuple[str, bool]:
    """Return (version, already_exists)."""

    versions = set(existing_versions(ml_client, name))

    if week not in versions:
        return week, False

    if not force_new:
        return week, True

    suffix = 2

    while f"{week}.{suffix}" in versions:
        suffix += 1

    return f"{week}.{suffix}", False


# ============================================================
# MAIN
# ============================================================

def ensure_data_asset(
    config: dict,
    stage: str,
    week: str,
    force_new: bool,
    ml_client=None,
):
    """Create (or find) the data asset for a week and return it."""

    from azure.ai.ml.constants import AssetTypes
    from azure.ai.ml.entities import Data

    ml_client = ml_client or get_ml_client(config)

    settings = stage_config(config, stage)

    name = settings["data_asset"]

    path = snapshot_uri(config, week, stage)

    version, already_exists = pick_version(ml_client, name, week, force_new)

    if already_exists:

        print(
            f"Data asset {name}:{version} already exists; reusing it.\n"
            "Pass --new-version if this week gained more annotated videos."
        )

        return ml_client.data.get(name=name, version=version)

    print(f"Creating data asset {name}:{version}")
    print(f"  path: {path}")

    asset = Data(
        name=name,
        version=version,
        type=AssetTypes.URI_FOLDER,
        path=path,
        description=(
            f"Annotated {stage} snapshot for ISO week {week}, written by the "
            "labelling system. Images and YOLO labels, unsplit -- the pipeline's "
            "prepare_data step carves the train/val split."
        ),
        tags={
            "stage": stage,
            "week": week,
            "source": "labelling-system-snapshot",
        },
    )

    created = ml_client.data.create_or_update(asset)

    print(f"Created {created.name}:{created.version}")

    return created


def main() -> None:

    args = parse_arguments()

    config = load_config()

    week = args.week or current_week()

    asset = ensure_data_asset(
        config=config,
        stage=args.stage,
        week=week,
        force_new=args.new_version,
    )

    print()
    print(f"Use it as: azureml:{asset.name}:{asset.version}")
    print()
    print(
        "If the pipeline's prepare_data step then reports 'No images found', "
        "the week has no finished videos in it -- a video only lands in a "
        "snapshot once BOTH labelling passes are submitted."
    )


if __name__ == "__main__":
    main()
