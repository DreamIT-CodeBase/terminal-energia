"""Decide whether the newly trained model replaces the deployed one.

Both models are validated here, in this step, on the same held-out split from
this week's dataset. That matters: the metrics the train step reported were
measured on this week's split, but the deployed model's numbers were measured
on whatever split its own week produced. Comparing those two figures directly
would be comparing scores from two different exams.

The verdict is written to decision.json and every later step reads it, so
"did the new model win" has exactly one definition.

A margin is required rather than a bare improvement. Run-to-run variance on a
small weekly dataset is easily a few thousandths of mAP, and swapping the
model the whole fleet downloads on that kind of noise is churn, not progress.
"""

import argparse
import json

from pathlib import Path

from common.dataset_io import find_data_yaml
from common.model_files import (
    DECISION_FILE,
    find_weights,
    read_json,
    write_json,
)
from common import tracking


METRIC_CHOICES = ("mAP50-95", "mAP50", "precision", "recall")


# ============================================================
# ARGUMENTS
# ============================================================

def parse_arguments():

    parser = argparse.ArgumentParser(
        description="Compare the new model against the deployed one."
    )

    parser.add_argument("--prepared_dataset", required=True)
    parser.add_argument("--previous_model", required=True)
    parser.add_argument("--trained_model", required=True)
    parser.add_argument("--evaluation", required=True)

    parser.add_argument("--stage", required=True)
    parser.add_argument("--week", default="")

    parser.add_argument(
        "--weights_name",
        default="",
        help="Exact .pt filename to load from previous_model. See train.py.",
    )

    parser.add_argument(
        "--has_previous",
        default="false",
        help=(
            "Whether previous_model is a real registered model rather than the "
            "cold-start base weights. False forces promotion."
        ),
    )

    parser.add_argument(
        "--metric",
        default="mAP50-95",
        choices=METRIC_CHOICES,
    )

    parser.add_argument(
        "--margin",
        type=float,
        default=0.005,
        help="How much the new model must beat the old one by to be promoted.",
    )

    parser.add_argument("--imgsz", type=int, default=640)
    parser.add_argument("--device", default="auto")

    return parser.parse_args()


def as_bool(
    value
) -> bool:

    return str(value).strip().lower() in {"1", "true", "yes", "y", "on"}


def resolve_device(
    requested: str
) -> str | int:

    if requested and requested != "auto":
        return requested

    try:
        import torch

        if torch.cuda.is_available():
            return 0

    except Exception:
        pass

    return "cpu"


def has_held_out_validation(
    dataset_dir: Path
) -> bool:
    """Whether the comparison below is measured on unseen images.

    prepare_data records this in stats.json. It matters because with no
    held-out split, `val` in data.yaml points back at the train images, so the
    newly trained model is being scored on data it was just fitted to -- it
    will win essentially always, and by a margin that means nothing. The
    comparison still runs; it is just labelled for what it is.
    """

    stats_path = dataset_dir / "stats.json"

    if stats_path.exists():
        try:
            return bool(read_json(stats_path).get("held_out_validation", True))

        except Exception:
            pass

    # No stats file: infer from the layout.
    val_images = dataset_dir / "val" / "images"

    return val_images.exists() and any(val_images.glob("*.*"))


# ============================================================
# VALIDATION
# ============================================================

def validate(
    weights: Path,
    data_yaml: Path,
    imgsz: int,
    device,
    label: str,
    output_dir: Path,
    run_name: str,
) -> dict:

    from ultralytics import YOLO

    print()
    print("=" * 60)
    print(f"Validating {label}: {weights}")
    print("=" * 60)

    # project/name are pinned to the step output for the same reason as in
    # train.py: the default lands in the read-only code snapshot. The two
    # models also need separate run folders so neither overwrites the other's
    # plots.
    results = YOLO(str(weights)).val(
        data=str(data_yaml),
        imgsz=imgsz,
        device=device,
        split="val",
        verbose=True,
        project=str(output_dir),
        name=run_name,
        exist_ok=True,
    )

    box = getattr(results, "box", None)

    def value(name):

        raw = getattr(box, name, None) if box is not None else None

        try:
            return float(raw)

        except (TypeError, ValueError):
            return 0.0

    metrics = {
        "mAP50-95": value("map"),
        "mAP50": value("map50"),
        "precision": value("mp"),
        "recall": value("mr"),
    }

    print(f"{label} metrics: {json.dumps(metrics, indent=2)}")

    return metrics


# ============================================================
# MAIN
# ============================================================

def main() -> None:

    args = parse_arguments()

    evaluation_dir = Path(args.evaluation)
    evaluation_dir.mkdir(parents=True, exist_ok=True)

    data_yaml = find_data_yaml(Path(args.prepared_dataset))

    device = resolve_device(args.device)

    new_weights = find_weights(Path(args.trained_model))

    if new_weights is None:
        raise FileNotFoundError(
            f"No .pt found in the trained model folder {args.trained_model}."
        )

    has_previous = as_bool(args.has_previous)

    old_weights = find_weights(
        Path(args.previous_model),
        args.weights_name or None,
    )

    held_out = has_held_out_validation(Path(args.prepared_dataset))

    if not held_out:
        print()
        print("!" * 68)
        print("No held-out validation set: `val` points at the train images.")
        print("The metrics below therefore describe the TRAINING set, and the")
        print("new model is scored on data it was just fitted to -- so it will")
        print("almost always win, by a margin that is not evidence of")
        print("generalisation. Provisional until a validation datastore exists.")
        print("!" * 68)

    tracking.set_tags(
        {
            "stage": args.stage,
            "week": args.week,
            "step": "evaluate",
            "metric": args.metric,
        }
    )

    new_metrics = validate(
        new_weights,
        data_yaml,
        args.imgsz,
        device,
        "new model",
        evaluation_dir,
        "val_new",
    )

    tracking.log_metrics(new_metrics, prefix="new/")

    # ------------------------------------------------------------
    # Cold start: nothing is deployed, so there is nothing to beat.
    # The base COCO weights would score near zero on a single custom
    # class, and reporting that as "the model we beat" would be
    # misleading rather than informative.
    # ------------------------------------------------------------

    if not has_previous or old_weights is None:

        reason = (
            "No previous model is registered for this stage, so the newly "
            "trained model is promoted unconditionally."
        )

        print()
        print(reason)

        decision = {
            "stage": args.stage,
            "week": args.week,
            "metric": args.metric,
            "margin": args.margin,
            "promote": True,
            "winner": "new",
            "cold_start": True,
            "held_out_validation": held_out,
            "metrics_trustworthy": held_out,
            "reason": reason,
            "new_metrics": new_metrics,
            "old_metrics": None,
            "delta": None,
            "mlflow_run_id": tracking.active_run_id(),
        }

        write_json(evaluation_dir / DECISION_FILE, decision)

        tracking.log_metrics({"promoted": 1.0})
        tracking.set_tags(
            {
                "promote": True,
                "cold_start": True,
                "held_out_validation": held_out,
            }
        )

        print(json.dumps(decision, indent=2))

        return

    old_metrics = validate(
        old_weights,
        data_yaml,
        args.imgsz,
        device,
        "deployed model",
        evaluation_dir,
        "val_old",
    )

    tracking.log_metrics(old_metrics, prefix="old/")

    new_value = new_metrics.get(args.metric, 0.0)
    old_value = old_metrics.get(args.metric, 0.0)

    delta = new_value - old_value

    promote = delta >= args.margin

    reason = (
        f"{args.metric} moved {delta:+.5f} "
        f"({old_value:.5f} -> {new_value:.5f}); "
        f"margin required is {args.margin:+.5f}. "
        + (
            "Promoting the new model."
            if promote
            else "Keeping the deployed model."
        )
        + (
            ""
            if held_out
            else " NOTE: measured on the training set (no held-out split), "
                 "so this margin is not evidence of generalisation."
        )
    )

    print()
    print("=" * 60)
    print(reason)
    print("=" * 60)

    decision = {
        "stage": args.stage,
        "week": args.week,
        "metric": args.metric,
        "margin": args.margin,
        "promote": promote,
        "winner": "new" if promote else "old",
        "cold_start": False,
        "held_out_validation": held_out,
        "metrics_trustworthy": held_out,
        "reason": reason,
        "new_metrics": new_metrics,
        "old_metrics": old_metrics,
        "delta": {
            key: new_metrics.get(key, 0.0) - old_metrics.get(key, 0.0)
            for key in METRIC_CHOICES
        },
        "mlflow_run_id": tracking.active_run_id(),
    }

    write_json(evaluation_dir / DECISION_FILE, decision)

    tracking.log_metrics(
        {
            "promoted": 1.0 if promote else 0.0,
            f"delta/{args.metric}": delta,
        }
    )

    tracking.set_tags(
        {
            "promote": promote,
            "winner": decision["winner"],
            "held_out_validation": held_out,
        }
    )

    print(json.dumps(decision, indent=2))


if __name__ == "__main__":
    main()
