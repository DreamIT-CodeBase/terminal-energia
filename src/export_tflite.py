"""Compile the promoted model to an int8 TFLite package for the app.

Only runs for real when the evaluate step promoted the new model. If the
deployed model won, its .tflite is already sitting in the model container and
re-exporting a byte-identical file would just burn compute.

The .pt is copied into this step's output before exporting, because
Ultralytics writes the export next to the weights file it was given and the
trained_model input is mounted read-only.

int8 quantisation is calibrated against this week's dataset rather than the
sample set Ultralytics falls back to. Calibrating on the wrong distribution is
how an int8 model loses accuracy the fp32 model had.
"""

import argparse
import shutil

from pathlib import Path

from common.dataset_io import find_data_yaml
from common.model_files import find_weights, read_decision, write_json
from common import tracking


SKIP_MARKER = "export_skipped.json"


# ============================================================
# ARGUMENTS
# ============================================================

def parse_arguments():

    parser = argparse.ArgumentParser(
        description="Export the promoted model to int8 TFLite."
    )

    parser.add_argument("--trained_model", required=True)
    parser.add_argument("--evaluation", required=True)
    parser.add_argument("--prepared_dataset", required=True)
    parser.add_argument("--tflite_model", required=True)

    parser.add_argument("--imgsz", type=int, default=640)

    parser.add_argument(
        "--int8",
        default="true",
        help="Quantise to int8. False exports fp32/fp16 TFLite instead.",
    )

    return parser.parse_args()


def as_bool(
    value
) -> bool:

    return str(value).strip().lower() in {"1", "true", "yes", "y", "on"}


# ============================================================
# MAIN
# ============================================================

def main() -> None:

    args = parse_arguments()

    output_dir = Path(args.tflite_model)
    output_dir.mkdir(parents=True, exist_ok=True)

    decision = read_decision(Path(args.evaluation))

    if not decision.get("promote"):

        print(
            "The deployed model won the comparison, so there is nothing new "
            "to export."
        )

        write_json(
            output_dir / SKIP_MARKER,
            {
                "exported": False,
                "reason": decision.get("reason", "not promoted"),
            },
        )

        return

    weights = find_weights(Path(args.trained_model))

    if weights is None:
        raise FileNotFoundError(
            f"No .pt found in {args.trained_model}."
        )

    data_yaml = find_data_yaml(Path(args.prepared_dataset))

    use_int8 = as_bool(args.int8)

    staged = output_dir / "best.pt"

    shutil.copy2(weights, staged)

    print("=" * 60)
    print(f"Exporting {staged}")
    print(f"  imgsz:       {args.imgsz}")
    print(f"  int8:        {use_int8}")
    print(f"  calibration: {data_yaml}")
    print("=" * 60)

    from ultralytics import YOLO

    exported = YOLO(str(staged)).export(
        format="tflite",
        int8=use_int8,
        imgsz=args.imgsz,
        data=str(data_yaml),
    )

    print(f"Ultralytics reported the export at: {exported}")

    # Ultralytics puts the file under best_saved_model/. Hoisting it to a
    # predictable name means publish_model does not have to guess, and the
    # blob the app downloads has a stable path.
    candidates = sorted(output_dir.rglob("*_int8.tflite"))

    if not candidates:
        candidates = sorted(output_dir.rglob("*.tflite"))

    if not candidates:
        raise FileNotFoundError(
            "The export produced no .tflite file. Contents: "
            f"{sorted(str(item) for item in output_dir.rglob('*'))[:50]}"
        )

    source = candidates[0]

    final_name = "best_int8.tflite" if use_int8 else "best_float32.tflite"

    final_path = output_dir / final_name

    if source.resolve() != final_path.resolve():
        shutil.copy2(source, final_path)

    size_bytes = final_path.stat().st_size

    print(f"Wrote {final_path} ({size_bytes / 1e6:.2f} MB)")

    write_json(
        output_dir / "export.json",
        {
            "exported": True,
            "file": final_name,
            "int8": use_int8,
            "imgsz": args.imgsz,
            "size_bytes": size_bytes,
            "source_weights": weights.name,
            "stage": decision.get("stage"),
            "week": decision.get("week"),
        },
    )

    tracking.log_metrics({"tflite_size_bytes": float(size_bytes)})

    tracking.set_tags(
        {
            "step": "export_tflite",
            "tflite_int8": use_int8,
            "tflite_imgsz": args.imgsz,
        }
    )


if __name__ == "__main__":
    main()
