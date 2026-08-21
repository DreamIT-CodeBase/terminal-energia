"""Turn one week's annotated snapshot into a trainable YOLO dataset.

The labelling system writes a week as a flat pair of folders --

    snapshots/<week>/<stage>/images/<video-id>__<frame>.png
    snapshots/<video-id>__<frame>.txt under labels/
    snapshots/<week>/<stage>/data.yaml

-- and its data.yaml deliberately points `train` and `val` at the same images,
because carving a split is left to whoever trains.

This step's job is the offline augmentation. Ultralytics' own augmentation is
generic; these transforms model the specific ways a handheld phone camera
distorts a forecourt board, so they are applied here, once, and written to
disk.

Validation: `--val_split 0` (the current setting) holds nothing back -- every
annotated image trains, and validation is expected to arrive from a separate
datastore rather than being carved out of the week. data.yaml's `val` then
points at the train images, because Ultralytics needs a val target to run at
all; `held_out_validation: false` in stats.json is what tells evaluate.py that
the numbers it computes describe the training set.

With a non-zero split, the split is taken on ORIGINALS, before augmentation,
and only the train side is augmented. Both matter: splitting after augmenting
would put variants of one source image on both sides, and augmenting the
validation side would score the model on images no camera will ever produce.
"""

import argparse
import json
import random
import shutil

from pathlib import Path

import numpy as np

from common.augment import AugmentConfig, build_transform

from common.dataset_io import (
    imread,
    imwrite,
    letterbox,
    list_images,
    read_boxes,
    to_grayscale_3ch,
    write_boxes,
    write_data_yaml,
)

from common import tracking


# ============================================================
# ARGUMENTS
# ============================================================

def parse_arguments():

    parser = argparse.ArgumentParser(
        description="Split, letterbox and augment one week's snapshot."
    )

    parser.add_argument(
        "--annotated_week",
        required=True,
        help=(
            "Mounted data asset for one week and stage. Expected to contain "
            "images/ and labels/ folders."
        ),
    )

    parser.add_argument(
        "--prepared_dataset",
        required=True,
        help="Output folder for the split, augmented YOLO dataset.",
    )

    parser.add_argument(
        "--class_name",
        required=True,
        help="Single class name written into data.yaml, e.g. board.",
    )

    parser.add_argument("--imgsz", type=int, default=640)
    parser.add_argument("--val_split", type=float, default=0.2)
    parser.add_argument("--seed", type=int, default=42)

    parser.add_argument(
        "--augment",
        default="true",
        help="Whether to generate augmented variants of the train split.",
    )

    parser.add_argument(
        "--per_image",
        type=int,
        default=6,
        help="Augmented variants generated per labelled train image.",
    )

    parser.add_argument(
        "--grayscale_prob",
        type=float,
        default=1.0,
        help=(
            "Fraction of augmented variants converted to grayscale. Originals "
            "are always grayscaled to match, so 0.0 here still leaves a "
            "grayscale dataset -- set --grayscale_originals false as well to "
            "train in colour."
        ),
    )

    parser.add_argument(
        "--grayscale_originals",
        default="true",
        help="Whether the letterboxed originals are converted to grayscale.",
    )

    return parser.parse_args()


def as_bool(
    value
) -> bool:

    return str(value).strip().lower() in {"1", "true", "yes", "y", "on"}


# ============================================================
# INPUT DISCOVERY
#
# The mount is normally <root>/images and <root>/labels, but a
# data asset created one level too high, or a snapshot copied
# with an extra wrapper folder, is a cheap mistake to absorb.
# ============================================================

def locate_source(
    root: Path
) -> tuple[Path, Path]:

    if not root.exists():
        raise FileNotFoundError(
            f"The annotated_week input does not exist: {root}"
        )

    candidates = [root]

    candidates.extend(
        item
        for item in sorted(root.iterdir())
        if item.is_dir()
    )

    for candidate in candidates:

        images = candidate / "images"
        labels = candidate / "labels"

        if images.is_dir():
            return images, labels

    raise FileNotFoundError(
        f"No images/ folder found under {root}. "
        f"Contents: {sorted(str(item) for item in root.rglob('*'))[:50]}"
    )


def create_layout(
    dataset_dir: Path,
    with_validation: bool,
) -> None:

    if dataset_dir.exists():
        # The output mount starts empty in a normal run; this only matters on
        # a local re-run against a reused folder.
        for split in ("train", "val"):
            shutil.rmtree(dataset_dir / split, ignore_errors=True)

    # No empty val/ folder when nothing is held out -- a split that looks
    # present but is empty is worse than one that is plainly absent.
    splits = ("train", "val") if with_validation else ("train",)

    for split in splits:
        (dataset_dir / split / "images").mkdir(parents=True, exist_ok=True)
        (dataset_dir / split / "labels").mkdir(parents=True, exist_ok=True)


# ============================================================
# SPLIT
# ============================================================

def split_originals(
    image_files: list[Path],
    val_split: float,
    seed: int,
) -> set[Path]:
    """Hold out a fraction of the originals for validation.

    `val_split <= 0` holds nothing out, deliberately: every annotated image is
    used for training, and validation comes from a separate source. The early
    return matters -- the rounding guard below would otherwise still steal one
    image from a small week.

    Otherwise, if the fraction rounds to zero but there is more than one image,
    one is still held out; a split that silently vanished would leave the
    comparison step measuring nothing.
    """

    if val_split <= 0:
        return set()

    shuffled = list(image_files)

    random.Random(seed).shuffle(shuffled)

    val_count = int(len(shuffled) * val_split)

    if val_count == 0 and len(shuffled) > 1:
        val_count = 1

    return set(shuffled[:val_count])


# ============================================================
# MAIN
# ============================================================

def main() -> None:

    args = parse_arguments()

    do_augment = as_bool(args.augment)
    gray_originals = as_bool(args.grayscale_originals)

    source_root = Path(args.annotated_week)
    dataset_dir = Path(args.prepared_dataset)

    hold_out_validation = args.val_split > 0

    source_images, source_labels = locate_source(source_root)

    print(f"Reading images from {source_images}")
    print(f"Reading labels from {source_labels}")

    create_layout(dataset_dir, hold_out_validation)

    image_files = list_images(source_images)

    if not image_files:
        raise FileNotFoundError(
            f"No images found in {source_images}. "
            "Was the data asset created for a week that has no finished "
            "videos in it?"
        )

    val_set = split_originals(image_files, args.val_split, args.seed)

    if hold_out_validation:
        print(
            f"Found {len(image_files)} originals -> "
            f"{len(image_files) - len(val_set)} train / {len(val_set)} val."
        )

    else:
        print(
            f"Found {len(image_files)} originals -> all to train; "
            "no validation split (val_split=0)."
        )
        print(
            "  data.yaml will point `val` at the train images so Ultralytics "
            "can run. Reported metrics therefore describe the TRAINING set, "
            "not generalisation, until a validation datastore is wired in."
        )

    # ------------------------------------------------------------
    # Letterbox every original into its split.
    # ------------------------------------------------------------

    unreadable: list[str] = []
    background_count = 0

    # (source path, classes, boxes) for the labelled train images only --
    # augmenting a background image produces nothing useful.
    train_pairs: list[tuple[Path, list[int], list[list[float]]]] = []

    for image_file in image_files:

        split = "val" if image_file in val_set else "train"

        label_file = source_labels / f"{image_file.stem}.txt"

        classes, boxes = read_boxes(label_file)

        base = imread(image_file)

        if base is None:
            unreadable.append(image_file.name)
            continue

        canvas, canvas_classes, canvas_boxes = letterbox(
            base,
            classes,
            boxes,
            args.imgsz,
        )

        if gray_originals:
            canvas = to_grayscale_3ch(canvas)

        imwrite(
            dataset_dir / split / "images" / f"{image_file.stem}.png",
            canvas,
        )

        # Written even when empty: an empty .txt is how YOLO is told "this
        # image is a deliberate background sample", not "label missing".
        write_boxes(
            dataset_dir / split / "labels" / f"{image_file.stem}.txt",
            canvas_classes,
            canvas_boxes,
        )

        if not boxes:
            background_count += 1

        elif split == "train":
            train_pairs.append((image_file, classes, boxes))

    if unreadable:
        print(f"Skipped {len(unreadable)} unreadable images: {unreadable[:10]}")

    if background_count:
        print(
            f"{background_count} images had no boxes and were kept as "
            "background samples (not augmented)."
        )

    # ------------------------------------------------------------
    # Augment the train split.
    # ------------------------------------------------------------

    made = 0
    dropped = 0

    if do_augment and args.per_image > 0 and train_pairs:

        transform = build_transform(
            AugmentConfig(
                seed=args.seed,
                grayscale=args.grayscale_prob,
            )
        )

        train_images = dataset_dir / "train" / "images"
        train_labels = dataset_dir / "train" / "labels"

        for image_path, classes, boxes in train_pairs:

            base = imread(image_path)

            if base is None:
                continue

            for index in range(args.per_image):

                result = transform(
                    image=base,
                    bboxes=boxes,
                    class_labels=classes,
                )

                aug_image = result["image"]
                aug_boxes = result["bboxes"]
                aug_classes = result["class_labels"]

                if len(aug_boxes) == 0:
                    # A tilt or crop pushed every box out of frame. Keeping it
                    # would silently add a mislabelled background image.
                    dropped += 1
                    continue

                aug_image, aug_classes, aug_boxes = letterbox(
                    aug_image,
                    aug_classes,
                    aug_boxes,
                    args.imgsz,
                )

                stem = f"aug_{image_path.stem}_{index:02d}"

                imwrite(train_images / f"{stem}.png", aug_image)
                write_boxes(train_labels / f"{stem}.txt", aug_classes, aug_boxes)

                made += 1

        print(
            f"Generated {made} augmented train images "
            f"({dropped} discarded for empty labels)."
        )

    elif not do_augment:
        print("Augmentation disabled; train split holds originals only.")

    # ------------------------------------------------------------
    # Normalise class ids.
    #
    # Each stage trains a single-class detector, so every box is
    # class 0 regardless of what the annotation tool recorded.
    # ------------------------------------------------------------

    for split in (("train", "val") if hold_out_validation else ("train",)):

        for label_path in (dataset_dir / split / "labels").glob("*.txt"):

            lines = label_path.read_text().splitlines()

            standardised = [
                " ".join(["0", *line.split()[1:]])
                for line in lines
                if line.split()
            ]

            label_path.write_text(
                "\n".join(standardised) + ("\n" if standardised else "")
            )

    yaml_path = write_data_yaml(
        dataset_dir,
        args.class_name,
        val_relative="val/images" if hold_out_validation else "train/images",
    )

    print(f"Wrote {yaml_path}")

    # ------------------------------------------------------------
    # Stats.
    # ------------------------------------------------------------

    train_count = len(list((dataset_dir / "train" / "images").glob("*.*")))

    val_images = dataset_dir / "val" / "images"

    val_count = len(list(val_images.glob("*.*"))) if val_images.exists() else 0

    stats = {
        "class_name": args.class_name,
        "imgsz": args.imgsz,
        "seed": args.seed,
        "val_split": args.val_split,
        "augmented": do_augment,
        "per_image": args.per_image if do_augment else 0,
        "grayscale_prob": args.grayscale_prob,
        "originals_found": len(image_files),
        "originals_unreadable": len(unreadable),
        "background_images": background_count,
        "augmented_generated": made,
        "augmented_discarded": dropped,
        "train_images": train_count,
        "val_images": val_count,

        # Read by evaluate.py: with no held-out split, `val` is the train set
        # and the promotion comparison is measured on data the new model was
        # fitted to. The flag is what lets that be stated rather than assumed.
        "held_out_validation": hold_out_validation,
    }

    (dataset_dir / "stats.json").write_text(json.dumps(stats, indent=2) + "\n")

    print(json.dumps(stats, indent=2))

    if hold_out_validation and val_count == 0:
        raise RuntimeError(
            "A validation split was requested but came out empty, so the new "
            "model could not be compared against the current one. Check that "
            "the week's snapshot actually contains finished videos."
        )

    tracking.log_params(
        {
            "prepare.class_name": args.class_name,
            "prepare.imgsz": args.imgsz,
            "prepare.val_split": args.val_split,
            "prepare.seed": args.seed,
            "prepare.augment": do_augment,
            "prepare.per_image": args.per_image,
            "prepare.grayscale_prob": args.grayscale_prob,
        }
    )

    tracking.log_metrics(
        {
            key: value
            for key, value in stats.items()
            if isinstance(value, (int, float)) and not isinstance(value, bool)
        },
        prefix="dataset/",
    )


if __name__ == "__main__":
    main()
