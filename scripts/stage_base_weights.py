"""Put fallback YOLO weights in the model container.

    python scripts/stage_base_weights.py

NOT needed for the normal flow. The first run of each pipeline starts from the
per-stage `.pt` in `base_models/` -- the weights behind the deployed `.tflite`
models -- named by `stages.<stage>.base_weights` in config/workspace.yml. Those
are uploaded by hand, because they are real trained models rather than
something this script could fetch.

This remains useful only for training a stage from scratch: it stages a
pristine `yolo11n.pt` so a from-COCO run needs no internet egress from the
compute and is reproducible months later. To use it, point that stage's
`base_weights` at the uploaded filename.

Needs the storage connection string for the account holding the model
container -- the same value system-api reads:

    $env:AZURE_STORAGE_CONNECTION_STRING = "..."

The download of yolo11n.pt happens locally, on your machine, where egress is
not in question.
"""

import argparse
import os
import sys
import urllib.request

from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from _config import load_config  # noqa: E402


WEIGHTS_URL = (
    "https://github.com/ultralytics/assets/releases/download/v8.3.0/yolo11n.pt"
)


def parse_arguments():

    parser = argparse.ArgumentParser(
        description="Upload cold-start YOLO weights to the model container."
    )

    parser.add_argument(
        "--container",
        default="yolo",
        help=(
            "Blob container the model datastore points at. Must match the "
            "datastore named in config/workspace.yml, and be one of "
            "MODEL_CONTAINERS in system-api/app/config.py."
        ),
    )

    parser.add_argument(
        "--local-weights",
        default=None,
        help=(
            "Path to an existing yolo11n.pt. Downloaded from the Ultralytics "
            "release if omitted."
        ),
    )

    parser.add_argument(
        "--overwrite",
        action="store_true",
        help="Replace the blob if it is already there.",
    )

    return parser.parse_args()


def fetch_weights(
    destination: Path
) -> Path:

    if destination.exists():
        print(f"Using cached {destination}")
        return destination

    destination.parent.mkdir(parents=True, exist_ok=True)

    print(f"Downloading {WEIGHTS_URL}")

    urllib.request.urlretrieve(WEIGHTS_URL, destination)

    print(f"Saved {destination} ({destination.stat().st_size / 1e6:.1f} MB)")

    return destination


def main() -> None:

    args = parse_arguments()

    config = load_config()

    blob_name = config.get("base_weights", "base/yolo11n.pt")

    connection_string = os.environ.get("AZURE_STORAGE_CONNECTION_STRING", "")

    if not connection_string:
        raise SystemExit(
            "AZURE_STORAGE_CONNECTION_STRING is not set. It is the same value "
            "system-api uses; export it and run this again."
        )

    if args.local_weights:
        weights = Path(args.local_weights)

        if not weights.exists():
            raise SystemExit(f"No such file: {weights}")

    else:
        weights = fetch_weights(
            Path(__file__).resolve().parent / ".cache" / "yolo11n.pt"
        )

    from azure.storage.blob import BlobServiceClient

    container = (
        BlobServiceClient.from_connection_string(connection_string)
        .get_container_client(args.container)
    )

    blob = container.get_blob_client(blob_name)

    if blob.exists() and not args.overwrite:

        print(
            f"{args.container}/{blob_name} already exists; leaving it alone. "
            "Pass --overwrite to replace it."
        )

        return

    print(f"Uploading to {args.container}/{blob_name}")

    with weights.open("rb") as handle:
        blob.upload_blob(handle, overwrite=True)

    print("Done.")
    print()
    print(
        "The train step will use this only until a model is registered for a "
        "stage. After the first successful run it is never read again."
    )


if __name__ == "__main__":
    main()
