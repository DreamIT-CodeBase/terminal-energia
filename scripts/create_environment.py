"""Build or rebuild the shared pipeline environment.

    python scripts/create_environment.py                # always create a version
    python scripts/create_environment.py --if-changed   # only when the spec moved
    python scripts/create_environment.py --status        # report, change nothing

The components reference the environment as `yolo-pipeline-env@latest`, so a
rebuild is picked up without editing any component YAML.

`--if-changed` exists because deploy.sh runs on every sync and this image is
expensive: torch plus TensorFlow is a long build. The decision is made by
stamping each version with a hash of the conda file and the base image, then
comparing that stamp against the local files. The stamp lives on the
environment itself rather than in a local dotfile, so it is correct from any
machine and any checkout.
"""

import argparse
import hashlib
import sys

from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from _config import REPO_ROOT, get_ml_client, load_config  # noqa: E402


ENVIRONMENT_NAME = "yolo-pipeline-env"

# Must match environments/environment.yml. preflight.py asserts they agree.
BASE_IMAGE = "mcr.microsoft.com/azureml/openmpi4.1.0-ubuntu22.04"

SPEC_TAG = "spec_sha256"


def parse_arguments():

    parser = argparse.ArgumentParser(
        description="Create or update the shared pipeline environment."
    )

    parser.add_argument(
        "--if-changed",
        action="store_true",
        help="Skip the build when the registered spec already matches local files.",
    )

    parser.add_argument(
        "--status",
        action="store_true",
        help="Report whether a rebuild is needed, then exit without changing anything.",
    )

    return parser.parse_args()


def spec_hash(
    conda_file: Path
) -> str:
    """Fingerprint of everything that determines the image."""

    digest = hashlib.sha256()

    digest.update(BASE_IMAGE.encode("utf-8"))
    digest.update(b"\0")
    digest.update(conda_file.read_bytes())

    return digest.hexdigest()


def latest_environment(
    ml_client
):
    """The newest registered version, or None if the name is unused."""

    try:
        return ml_client.environments.get(name=ENVIRONMENT_NAME, label="latest")

    except Exception:
        pass

    # Older SDKs do not accept label="latest" for environments.
    try:
        versions = list(ml_client.environments.list(name=ENVIRONMENT_NAME))

    except Exception:
        return None

    if not versions:
        return None

    def sort_key(item):

        try:
            return (1, int(item.version))

        except (TypeError, ValueError):
            return (0, 0)

    newest = sorted(versions, key=sort_key)[-1]

    try:
        return ml_client.environments.get(
            name=ENVIRONMENT_NAME,
            version=newest.version,
        )

    except Exception:
        return newest


def main() -> int:

    from azure.ai.ml.entities import Environment

    args = parse_arguments()

    config = load_config()

    conda_file = REPO_ROOT / "environments" / "conda.yml"

    if not conda_file.exists():
        raise SystemExit(f"Missing {conda_file}")

    local_hash = spec_hash(conda_file)

    ml_client = get_ml_client(config)

    existing = latest_environment(ml_client)

    registered_hash = (getattr(existing, "tags", None) or {}).get(SPEC_TAG)

    print(f"Environment:      {ENVIRONMENT_NAME}")
    print(f"Base image:       {BASE_IMAGE}")
    print(f"Local spec hash:  {local_hash[:16]}")

    if existing is None:
        print("Registered:       (none yet)")

    else:
        print(f"Registered:       version {existing.version}")
        print(
            "Registered hash:  "
            + (registered_hash[:16] if registered_hash else "(untagged)")
        )

    up_to_date = registered_hash == local_hash and existing is not None

    if args.status:
        print()
        print(
            "UP TO DATE -- no rebuild needed."
            if up_to_date
            else "REBUILD NEEDED -- the local spec differs from the registered one."
        )
        return 0

    if up_to_date and args.if_changed:
        print()
        print(
            f"Spec unchanged; keeping version {existing.version}. "
            "Pass no --if-changed to force a new version."
        )
        return 0

    if registered_hash is None and existing is not None and args.if_changed:
        print()
        print(
            "The registered version predates spec stamping, so a rebuild cannot "
            "be ruled out. Creating a new stamped version."
        )

    environment = Environment(
        name=ENVIRONMENT_NAME,
        description=(
            "Shared environment for the weekly board and price-region YOLO "
            "pipelines: albumentations dataset prep, Ultralytics training and "
            "evaluation, and int8 TFLite export. CPU-only."
        ),
        # Must stay in step with environments/environment.yml.
        image=BASE_IMAGE,
        conda_file=str(conda_file),
        tags={
            SPEC_TAG: local_hash,
            "accelerator": "cpu",
            "managed_by": "ml-workspace/scripts/create_environment.py",
        },
    )

    print()
    print("Creating a new environment version...")

    created = ml_client.environments.create_or_update(environment)

    print()
    print(f"Name:    {created.name}")
    print(f"Version: {created.version}")
    print()
    print(
        "The image builds asynchronously. Watch it in ML Studio under "
        "Environments and wait for the build to succeed before submitting -- a "
        "job queued against a failed image just fails too.\n"
        "Components pin @latest, so nothing else needs changing."
    )

    return 0


if __name__ == "__main__":
    sys.exit(main())
