"""Finding weights inside a mounted folder, and the decision record.

A `previous_model` input can be three different shapes depending on where it
came from -- a registered model version, the cold-start `base/` prefix on the
model datastore, or a previous step's output folder -- so every step resolves
weights the same forgiving way rather than assuming a layout.
"""

import json

from pathlib import Path


DECISION_FILE = "decision.json"

METRICS_FILE = "metrics.json"


def find_weights(
    location: Path,
    preferred_name: str | None = None,
) -> Path | None:
    """The .pt to load from a mounted file or folder, or None.

    `preferred_name` names one file exactly and is required whenever the
    mounted folder holds weights for more than one model. The base-model
    folder does: `base_models/` contains both detectors, and the glob fallback
    below sorts alphabetically, so without a name the price-region run would
    silently start from `board-detection-base.pt`. Never rely on the fallback
    to disambiguate a shared folder.

    Otherwise `best.pt` wins over `last.pt` wins over anything else, because
    an Ultralytics run folder contains all of them and only `best.pt` was
    selected on validation.
    """

    if location is None:
        return None

    if not location.exists():
        return None

    if location.is_file():
        return location if location.suffix.lower() == ".pt" else None

    if preferred_name:

        exact = location / preferred_name

        if exact.is_file():
            return exact

        # Also accept it nested, so a registered model folder works the same.
        matches = sorted(location.rglob(preferred_name))

        if matches:
            return matches[0]

        # Deliberately do NOT fall through to the glob: a named file that is
        # absent means the wrong folder was mounted, and guessing would start
        # training from another model's weights.
        return None

    for preferred in ("best.pt", "last.pt"):

        matches = sorted(location.rglob(preferred))

        if matches:
            return matches[0]

    matches = sorted(location.rglob("*.pt"))

    return matches[0] if matches else None


def require_weights(
    location: Path,
    what: str,
    preferred_name: str | None = None,
) -> Path:

    weights = find_weights(location, preferred_name)

    if weights is None:
        named = f" named {preferred_name!r}" if preferred_name else ""

        raise FileNotFoundError(
            f"No .pt file{named} found for {what} under {location}. "
            f"Contents: {sorted(str(item) for item in location.rglob('*'))[:50]}"
        )

    return weights


def find_tflite(
    location: Path
) -> Path | None:

    if location is None or not location.exists():
        return None

    for pattern in ("*_int8.tflite", "*.tflite"):

        matches = sorted(location.rglob(pattern))

        if matches:
            return matches[0]

    return None


# ============================================================
# DECISION RECORD
#
# evaluate writes it; export_tflite and publish_model read it.
# Passing the verdict as a file rather than recomputing it in
# each step keeps one definition of "did the new model win".
# ============================================================

def write_json(
    path: Path,
    payload: dict,
) -> None:

    path.parent.mkdir(parents=True, exist_ok=True)

    path.write_text(json.dumps(payload, indent=2) + "\n")


def read_json(
    path: Path
) -> dict:

    return json.loads(path.read_text())


def read_decision(
    evaluation_dir: Path
) -> dict:

    decision_path = evaluation_dir / DECISION_FILE

    if not decision_path.exists():
        raise FileNotFoundError(
            f"{DECISION_FILE} was not found in {evaluation_dir}. "
            "The evaluate step must run before this one."
        )

    return read_json(decision_path)
