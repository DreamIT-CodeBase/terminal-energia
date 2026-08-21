"""Put the promoted model where the fleet and the registry can see it.

Two destinations, for two different readers:

  * The model container, mounted as `published_model`. This is what the
    device API serves -- `GET /v1/models/yolo/<model_root>/<stage>/...` --
    so the paths written here are a contract with the Android app, not an
    internal detail.

  * The Azure ML model registry, so next week's train step has a versioned
    parent to continue from and the run that produced it is traceable.

If the deployed model won the comparison, this step does nothing. The
container already holds that model and re-uploading it would move the
`latest.json` timestamp for no reason, which would look to the app like a new
model is available when nothing changed.

Layout written into the model container:

    <model_root>/<stage>/latest.json              <- manifest, polled by the API
    <model_root>/<stage>/latest_int8.tflite        <- what the app downloads
    <model_root>/<stage>/latest.pt                 <- next week's starting point
    <model_root>/<stage>/versions/<week>/...       <- immutable copy, for rollback

`latest.*` is overwritten in place every promotion. The `versions/` copy is
what makes a rollback possible, so it is never overwritten by a different week.
"""

import argparse
import hashlib
import shutil

from datetime import datetime, timezone

from pathlib import Path

from common.model_files import (
    find_tflite,
    find_weights,
    read_decision,
    read_json,
    write_json,
)

from common import tracking


# ============================================================
# ARGUMENTS
# ============================================================

def parse_arguments():

    parser = argparse.ArgumentParser(
        description="Publish and register the promoted model."
    )

    parser.add_argument("--trained_model", required=True)
    parser.add_argument("--tflite_model", required=True)
    parser.add_argument("--evaluation", required=True)

    parser.add_argument(
        "--published_model",
        required=True,
        help="Output bound to the model container the device API serves.",
    )

    parser.add_argument(
        "--publish_record",
        required=True,
        help="Output folder for this step's own record of what it did.",
    )

    parser.add_argument("--stage", required=True)
    parser.add_argument("--model_name", required=True)

    parser.add_argument(
        "--model_root",
        default="detectors",
        help="Prefix inside the model container, e.g. detectors.",
    )

    parser.add_argument(
        "--week",
        default="",
        help="ISO week label, used as the immutable version folder name.",
    )

    parser.add_argument("--class_name", default="")
    parser.add_argument("--imgsz", type=int, default=640)

    parser.add_argument(
        "--register",
        default="true",
        help="Register the promoted model in the workspace registry.",
    )

    return parser.parse_args()


def as_bool(
    value
) -> bool:

    return str(value).strip().lower() in {"1", "true", "yes", "y", "on"}


def sha256_of(
    path: Path
) -> str:
    """Digest the app can verify a download against."""

    digest = hashlib.sha256()

    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)

    return digest.hexdigest()


def now_iso() -> str:

    return datetime.now(timezone.utc).isoformat(timespec="seconds")


# ============================================================
# REGISTRY
# ============================================================

def register_with_mlflow(
    model_dir: Path,
    model_name: str,
    stage: str,
    week: str,
) -> str | None:
    """Register the promoted weights as a new version, via MLflow.

    Azure ML backs the MLflow registry, and the tracking credentials the job
    already holds cover it -- so this needs no extra identity on the compute.

    Deliberately non-fatal. The blobs in the model container are what the
    fleet actually downloads; if registration fails the release still went
    out, and submit_pipeline.py reconciles the registry afterwards.
    """

    try:
        import mlflow

        # Logging first forces the run into existence, so the id lookup below
        # cannot come back empty in a job that has one.
        mlflow.log_artifacts(str(model_dir), artifact_path="model")

        run_id = tracking.active_run_id()

        if run_id is None:
            print("[register] no MLflow run to register from; skipping.")
            return None

        result = mlflow.register_model(
            model_uri=f"runs:/{run_id}/model",
            name=model_name,
        )

        version = str(getattr(result, "version", "") or "")

        # Tagged with the week so submit_pipeline.py can tell this version
        # already exists. Without the tag its reconcile pass cannot see the
        # registration and would register the same model a second time every
        # single run.
        if version:
            try:
                from mlflow.tracking import MlflowClient

                client = MlflowClient()

                for key, value in (("stage", stage), ("week", week)):
                    client.set_model_version_tag(
                        name=model_name,
                        version=version,
                        key=key,
                        value=value,
                    )

            except Exception as exc:
                print(
                    f"[register] registered v{version} but could not tag it: "
                    f"{exc}. submit_pipeline.py may register a duplicate; "
                    "delete the extra version if it does."
                )

        print(f"[register] {model_name} registered as version {version}.")

        return version or None

    except Exception as exc:
        print(
            f"[register] could not register {model_name}: {exc}\n"
            "[register] The model container was still updated. Run "
            "scripts/submit_pipeline.py --reconcile-registry, or register the "
            "job output by hand, to catch the registry up."
        )

        return None


# ============================================================
# MAIN
# ============================================================

def main() -> None:

    args = parse_arguments()

    record_dir = Path(args.publish_record)
    record_dir.mkdir(parents=True, exist_ok=True)

    decision = read_decision(Path(args.evaluation))

    tracking.set_tags(
        {
            "stage": args.stage,
            "week": args.week,
            "step": "publish_model",
        }
    )

    # ------------------------------------------------------------
    # Nothing to do when the deployed model won.
    # ------------------------------------------------------------

    if not decision.get("promote"):

        reason = decision.get("reason", "the new model was not promoted")

        print(f"Not publishing: {reason}")

        write_json(
            record_dir / "published.json",
            {
                "published": False,
                "registered_version": None,
                "reason": reason,
                "stage": args.stage,
                "week": args.week,
            },
        )

        tracking.set_tags({"published": False})

        return

    weights = find_weights(Path(args.trained_model))

    if weights is None:
        raise FileNotFoundError(
            f"No .pt found in {args.trained_model}."
        )

    tflite = find_tflite(Path(args.tflite_model))

    if tflite is None:
        raise FileNotFoundError(
            "The model was promoted but no .tflite was produced. The app "
            "cannot use a .pt, so this is treated as a failure rather than a "
            "partial release. Check the export_tflite step's log."
        )

    # ------------------------------------------------------------
    # Assemble the release locally, then copy it into place.
    # ------------------------------------------------------------

    week = args.week or "unversioned"

    container_root = Path(args.published_model)

    stage_dir = container_root / args.model_root / args.stage
    version_dir = stage_dir / "versions" / week

    stage_dir.mkdir(parents=True, exist_ok=True)
    version_dir.mkdir(parents=True, exist_ok=True)

    tflite_name = "latest_int8.tflite"

    shutil.copy2(weights, stage_dir / "latest.pt")
    shutil.copy2(tflite, stage_dir / tflite_name)

    shutil.copy2(weights, version_dir / "best.pt")
    shutil.copy2(tflite, version_dir / tflite.name)

    tflite_size = (stage_dir / tflite_name).stat().st_size
    tflite_hash = sha256_of(stage_dir / tflite_name)

    weights_size = (stage_dir / "latest.pt").stat().st_size

    # ------------------------------------------------------------
    # Register, then write the manifest -- so the manifest can name
    # the registry version the app is being handed.
    # ------------------------------------------------------------

    registered_version = None

    if as_bool(args.register):
        registered_version = register_with_mlflow(
            version_dir,
            args.model_name,
            args.stage,
            week,
        )

    training_metrics = {}

    metrics_file = Path(args.trained_model) / "metrics.json"

    if metrics_file.exists():
        try:
            training_metrics = read_json(metrics_file)
        except Exception:
            training_metrics = {}

    stage_prefix = f"{args.model_root}/{args.stage}"

    manifest = {
        "stage": args.stage,
        "model_name": args.model_name,
        "registered_version": registered_version,
        "week": week,
        "class_name": args.class_name,
        "imgsz": args.imgsz,
        "promoted_at": now_iso(),
        "mlflow_run_id": tracking.active_run_id(),

        # Paths are relative to the model container, because that is how the
        # device addresses a model: GET /v1/models/<container>/<path>.
        "files": {
            "tflite": {
                "path": f"{stage_prefix}/{tflite_name}",
                "size_bytes": tflite_size,
                "sha256": tflite_hash,
            },
            "pytorch": {
                "path": f"{stage_prefix}/latest.pt",
                "size_bytes": weights_size,
            },
        },

        "version_prefix": f"{stage_prefix}/versions/{week}",

        "metrics": decision.get("new_metrics") or {},
        "previous_metrics": decision.get("old_metrics"),
        "decision": {
            "metric": decision.get("metric"),
            "margin": decision.get("margin"),
            "reason": decision.get("reason"),
            "cold_start": decision.get("cold_start"),
        },
        "training": {
            "epochs": training_metrics.get("epochs"),
            "previous_model_version": training_metrics.get(
                "previous_model_version"
            ),
        },
    }

    write_json(stage_dir / "latest.json", manifest)
    write_json(version_dir / "metadata.json", manifest)

    write_json(
        record_dir / "published.json",
        {
            "published": True,
            "registered_version": registered_version,
            "stage": args.stage,
            "week": week,
            "manifest_path": f"{stage_prefix}/latest.json",
            "tflite_path": f"{stage_prefix}/{tflite_name}",
            "sha256": tflite_hash,
        },
    )

    tracking.set_tags(
        {
            "published": True,
            "registered_version": registered_version or "unregistered",
        }
    )

    print()
    print("=" * 60)
    print("Published.")
    print(f"  manifest: {stage_prefix}/latest.json")
    print(f"  tflite:   {stage_prefix}/{tflite_name} ({tflite_size / 1e6:.2f} MB)")
    print(f"  weights:  {stage_prefix}/latest.pt")
    print(f"  rollback: {stage_prefix}/versions/{week}/")
    print(f"  registry: {args.model_name} v{registered_version or '(not registered)'}")
    print("=" * 60)


if __name__ == "__main__":
    main()
