"""Fine-tune the currently deployed detector on this week's dataset.

This is a continuation, not a fresh train: the starting weights are whatever
is registered as the latest version for this stage, so each week's model is a
descendant of the one running on the devices. On the very first run nothing is
registered yet and the starting weights are the staged `base/yolo11n.pt`.

Everything is logged into the MLflow run Azure ML already opened for this
step -- hyperparameters, per-epoch metrics, final validation metrics, and the
run folder as artifacts. Ultralytics' own MLflow integration is switched off
because it opens a second, nested run and the metrics end up split across two
places.
"""

import argparse
import json
import os
import shutil

from pathlib import Path

from common.dataset_io import find_data_yaml
from common.model_files import require_weights, write_json
from common import tracking


# ============================================================
# ARGUMENTS
# ============================================================

def parse_arguments():

    parser = argparse.ArgumentParser(
        description="Fine-tune a YOLO detector on one week's dataset."
    )

    parser.add_argument(
        "--prepared_dataset",
        required=True,
        help="Output of the prepare_data step: split, augmented, with data.yaml.",
    )

    parser.add_argument(
        "--previous_model",
        required=True,
        help=(
            "Mounted folder holding the weights to continue from -- either a "
            "registered model version or the staged base weights."
        ),
    )

    parser.add_argument(
        "--trained_model",
        required=True,
        help="Output folder for best.pt and the training telemetry.",
    )

    parser.add_argument(
        "--weights_name",
        default="",
        help=(
            "Exact .pt filename to load from previous_model. Required when the "
            "mounted folder holds weights for more than one model, as the "
            "shared base_models/ folder does."
        ),
    )

    parser.add_argument(
        "--previous_model_version",
        default="",
        help="Registered version the weights came from, for lineage. Empty on cold start.",
    )

    parser.add_argument("--stage", required=True)
    parser.add_argument("--week", default="")

    parser.add_argument("--epochs", type=int, default=50)
    parser.add_argument("--imgsz", type=int, default=640)
    parser.add_argument("--batch", type=int, default=8)
    parser.add_argument(
        "--workers",
        type=int,
        default=-1,
        help=(
            "Dataloader worker processes. -1 resolves at runtime from the "
            "compute's core count, which is the only place that number is "
            "actually known."
        ),
    )
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--patience", type=int, default=0)

    parser.add_argument(
        "--device",
        default="auto",
        help="'auto' picks GPU 0 when CUDA is available, otherwise cpu.",
    )

    return parser.parse_args()


def resolve_device(
    requested: str
) -> str | int:
    """Pick the device at runtime so one image serves CPU and GPU computes."""

    if requested and requested != "auto":
        return requested

    try:
        import torch

        if torch.cuda.is_available():
            print(f"CUDA available: {torch.cuda.get_device_name(0)}")
            return 0

    except Exception as exc:
        print(f"Could not query CUDA ({exc}); falling back to CPU.")

    print("No GPU available; training on CPU.")

    return "cpu"


def resolve_workers(
    requested: int
) -> int:
    """Dataloader processes to run, given -1 means "as many as sensible".

    Hardcoding this is wrong: the pipeline YAML is written once but the
    compute's core count is only knowable at runtime, and a number tuned for
    a 16-core box thrashes a 4-vCPU one.

    Two things bound the answer. Workers are separate processes competing with
    torch's compute threads for the same cores, so a couple are left free. And
    each worker prefetches batches, so at large imgsz the resident memory adds
    up fast -- hence the cap rather than simply taking every core.

    Ultralytics clamps this again internally, as

        nw = min(os.cpu_count() // max(nd, 1), workers, batches)

    where `batches` is the number of batches in the loader -- not the batch
    size. So on a small week the effective worker count is bounded by how many
    batches there are, and raising `batch` lowers that bound: batch 16 over
    200 images is 13 batches, hence at most 13 workers however many are asked
    for. A single-batch loader runs in-process with none.
    """

    if requested >= 0:
        return requested

    cores = os.cpu_count() or 2

    workers = max(2, min(16, cores - 2))

    print(
        f"Workers: resolved to {workers} from {cores} detected cores "
        "(-1 = auto)."
    )

    return workers


# ============================================================
# PER-EPOCH TRACKING
# ============================================================

def attach_epoch_logging(
    model
) -> None:
    """Stream Ultralytics' per-epoch metrics into the active MLflow run.

    Without this the run only gets a single final data point, which makes an
    overfitting curve invisible in the Studio UI.
    """

    def on_fit_epoch_end(trainer):

        metrics = dict(getattr(trainer, "metrics", {}) or {})

        loss_items = getattr(trainer, "label_loss_items", None)
        tloss = getattr(trainer, "tloss", None)

        if callable(loss_items) and tloss is not None:
            try:
                metrics.update(loss_items(tloss, prefix="train"))
            except Exception:
                pass

        learning_rate = getattr(trainer, "lr", None)

        if isinstance(learning_rate, dict):
            metrics.update(learning_rate)

        tracking.log_metrics(
            metrics,
            step=int(getattr(trainer, "epoch", 0)),
        )

    model.add_callback("on_fit_epoch_end", on_fit_epoch_end)


def extract_metrics(
    results
) -> dict:
    """The four numbers the promotion decision and the run history care about."""

    box = getattr(results, "box", None)

    if box is None:
        return {}

    def value(name):

        raw = getattr(box, name, None)

        try:
            return float(raw)

        except (TypeError, ValueError):
            return None

    metrics = {
        "mAP50-95": value("map"),
        "mAP50": value("map50"),
        "precision": value("mp"),
        "recall": value("mr"),
    }

    return {
        key: number
        for key, number in metrics.items()
        if number is not None
    }


# ============================================================
# MAIN
# ============================================================

def main() -> None:

    args = parse_arguments()

    dataset_dir = Path(args.prepared_dataset)
    output_dir = Path(args.trained_model)

    output_dir.mkdir(parents=True, exist_ok=True)

    data_yaml = find_data_yaml(dataset_dir)

    starting_weights = require_weights(
        Path(args.previous_model),
        "the previous model",
        args.weights_name or None,
    )

    device = resolve_device(args.device)

    workers = resolve_workers(args.workers)

    is_cold_start = not args.previous_model_version

    print("=" * 60)
    print(f"Stage:            {args.stage}")
    print(f"Week:             {args.week or '(unset)'}")
    print(f"Dataset:          {data_yaml}")
    print(f"Starting weights: {starting_weights}")
    print(
        "Lineage:          "
        + (
            "cold start from staged base weights"
            if is_cold_start
            else f"continuing registered version {args.previous_model_version}"
        )
    )
    print(f"Device:           {device}")
    print("=" * 60)

    tracking.set_tags(
        {
            "stage": args.stage,
            "week": args.week,
            "step": "train",
            "cold_start": is_cold_start,
            "previous_model_version": args.previous_model_version or "none",
        }
    )

    tracking.log_params(
        {
            "train.epochs": args.epochs,
            "train.imgsz": args.imgsz,
            "train.batch": args.batch,
            "train.workers": workers,
            "train.seed": args.seed,
            "train.device": device,
            "train.starting_weights": starting_weights.name,
            "train.previous_model_version": args.previous_model_version or "none",
        }
    )

    from ultralytics import YOLO
    from ultralytics import settings as ultralytics_settings

    # Ultralytics reads this when the trainer builds its callback list, so it
    # has to be off before train() is called -- not merely before the import.
    # Left on, its callback opens a second, nested MLflow run and the metrics
    # end up split between that run and the one this step logs to.
    try:
        ultralytics_settings.update({"mlflow": False})

    except Exception as exc:
        print(f"Could not disable the Ultralytics MLflow callback: {exc}")

    model = YOLO(str(starting_weights))

    attach_epoch_logging(model)

    # Ultralytics writes into <project>/<name>. Pointing project at the output
    # mount means weights, plots and results.csv land in the step output
    # directly, with no copy step and nothing left behind on the compute.
    run_dir = output_dir / "run"

    train_kwargs = dict(
        data=str(data_yaml),
        epochs=args.epochs,
        imgsz=args.imgsz,
        batch=args.batch,
        workers=workers,
        seed=args.seed,
        device=device,
        project=str(output_dir),
        name="run",
        exist_ok=True,
        plots=True,
        verbose=True,
    )

    # patience=0 disables Ultralytics' early stopping. Left off by default
    # because a week's dataset is small and the run is short anyway.
    if args.patience > 0:
        train_kwargs["patience"] = args.patience

    model.train(**train_kwargs)

    best_weights = run_dir / "weights" / "best.pt"

    if not best_weights.exists():
        raise FileNotFoundError(
            f"Training finished but {best_weights} was not written."
        )

    # ------------------------------------------------------------
    # Validate the selected weights on the held-out split.
    #
    # model.train() already reports validation metrics, but they come from
    # the last epoch rather than from best.pt. Re-validating gives the
    # numbers that actually describe the artefact being promoted.
    # ------------------------------------------------------------

    best_model = YOLO(str(best_weights))

    # project/name are pinned to the step output on purpose. Left unset,
    # Ultralytics writes this run under ./runs/detect/ -- which is the code
    # snapshot directory, not an output. That litters the snapshot at best and
    # fails outright if the snapshot mount is read-only.
    validation = best_model.val(
        data=str(data_yaml),
        imgsz=args.imgsz,
        device=device,
        split="val",
        verbose=True,
        project=str(output_dir),
        name="val",
        exist_ok=True,
    )

    metrics = extract_metrics(validation)

    print("Validation metrics for best.pt:")
    print(json.dumps(metrics, indent=2))

    tracking.log_metrics(metrics, prefix="new/")

    # A stable copy at a predictable path, so the downstream steps do not have
    # to know how Ultralytics lays out a run folder.
    shutil.copy2(best_weights, output_dir / "best.pt")

    write_json(
        output_dir / "metrics.json",
        {
            "stage": args.stage,
            "week": args.week,
            "epochs": args.epochs,
            "imgsz": args.imgsz,
            "batch": args.batch,
            "workers": workers,
            "device": str(device),
            "cold_start": is_cold_start,
            "previous_model_version": args.previous_model_version or None,
            "starting_weights": starting_weights.name,
            "mlflow_run_id": tracking.active_run_id(),
            "metrics": metrics,
        },
    )

    tracking.log_artifacts(run_dir, artifact_path="training_run")

    print(f"Wrote {output_dir / 'best.pt'}")


if __name__ == "__main__":
    main()
