"""Image and YOLO-label IO, plus the letterbox both stages train on.

Lifted from the two notebook scripts that produced the current board and
price-region models, so the pipeline reproduces their geometry exactly. The
only behavioural change is that nothing here prints or exits -- the callers
decide what to log.
"""

from pathlib import Path

import cv2
import numpy as np


IMAGE_EXTENSIONS = {
    ".jpg",
    ".jpeg",
    ".png",
    ".bmp",
    ".webp",
    ".tif",
    ".tiff",
}


# Flat mid-grey. Matches the padding colour Ultralytics letterboxes with, so a
# padded edge looks the same to the model as one it saw during pretraining.
PAD_VALUE = 114


# ============================================================
# IMAGE IO
#
# cv2.imread/imwrite go through a non-Unicode path API on some
# platforms and silently return None for names the labelling
# system happily produces (video ids, `__` separators, spaces).
# Going via numpy sidesteps that entirely.
# ============================================================

def imread(
    path: Path
):

    data = np.fromfile(str(path), dtype=np.uint8)

    if data.size == 0:
        return None

    return cv2.imdecode(data, cv2.IMREAD_COLOR)


def imwrite(
    path: Path,
    image,
) -> bool:

    ok, buffer = cv2.imencode(path.suffix, image)

    if not ok:
        return False

    buffer.tofile(str(path))

    return True


def list_images(
    folder: Path
) -> list[Path]:

    if not folder.exists():
        return []

    return sorted(
        item
        for item in folder.iterdir()
        if item.is_file()
        and item.suffix.lower() in IMAGE_EXTENSIONS
    )


def to_grayscale_3ch(
    image
):
    """Grayscale, kept 3-channel so the model input shape never changes."""

    return cv2.cvtColor(
        cv2.cvtColor(image, cv2.COLOR_BGR2GRAY),
        cv2.COLOR_GRAY2BGR,
    )


# ============================================================
# LABEL IO -- YOLO normalised [cls, cx, cy, w, h]
# ============================================================

def read_boxes(
    label_file: Path
) -> tuple[list[int], list[list[float]]]:
    """Return (class_labels, bboxes), clamped into [0, 1].

    A missing or empty file yields two empty lists, which is meaningful: the
    image is a background sample and is kept, not dropped.
    """

    class_labels: list[int] = []
    bboxes: list[list[float]] = []

    if not label_file.exists():
        return class_labels, bboxes

    for line in label_file.read_text().splitlines():

        line = line.strip()

        if not line:
            continue

        parts = line.split()

        if len(parts) < 5:
            continue

        try:
            cls = int(float(parts[0]))
            cx, cy, w, h = (float(value) for value in parts[1:5])

        except ValueError:
            continue

        cx = min(max(cx, 0.0), 1.0)
        cy = min(max(cy, 0.0), 1.0)
        w = min(max(w, 0.0), 1.0)
        h = min(max(h, 0.0), 1.0)

        # A box that clamped down to nothing is not a box.
        if w <= 0.0 or h <= 0.0:
            continue

        class_labels.append(cls)
        bboxes.append([cx, cy, w, h])

    return class_labels, bboxes


def write_boxes(
    label_file: Path,
    class_labels,
    bboxes,
) -> None:

    lines = [
        f"{int(cls)} {box[0]:.6f} {box[1]:.6f} {box[2]:.6f} {box[3]:.6f}"
        for cls, box in zip(class_labels, bboxes)
    ]

    label_file.write_text(
        "\n".join(lines) + ("\n" if lines else "")
    )


# ============================================================
# LETTERBOX
# ============================================================

def letterbox(
    image,
    class_labels,
    bboxes,
    size: int,
    pad_value: int = PAD_VALUE,
):
    """Fit onto a square grey canvas, aspect preserved, and remap the boxes.

    The longer side hits `size`; the remainder is padded. Odd remainder pixels
    fall to the right and bottom.
    """

    height, width = image.shape[:2]

    scale = min(size / width, size / height)

    new_width = round(width * scale)
    new_height = round(height * scale)

    # Shrinking wants area averaging; enlarging wants cubic.
    interpolation = cv2.INTER_AREA if scale < 1 else cv2.INTER_CUBIC

    resized = cv2.resize(
        image,
        (new_width, new_height),
        interpolation=interpolation,
    )

    canvas = np.full((size, size, 3), pad_value, dtype=np.uint8)

    left = (size - new_width) // 2
    top = (size - new_height) // 2

    canvas[top:top + new_height, left:left + new_width] = resized

    remapped = [
        [
            (cx * new_width + left) / size,
            (cy * new_height + top) / size,
            bw * new_width / size,
            bh * new_height / size,
        ]
        for cx, cy, bw, bh in bboxes
    ]

    return canvas, list(class_labels), remapped


# ============================================================
# DATASET DESCRIPTOR
# ============================================================

def write_data_yaml(
    dataset_dir: Path,
    class_name: str,
    val_relative: str = "val/images",
) -> Path:
    """Ultralytics descriptor for a prepared dataset.

    `path` is written absolute, but it is only valid inside this step. The
    dataset folder is a mounted Azure ML output and every step mounts it at a
    different absolute location, so a later step reading this file verbatim
    looks for images under prepare_data's job directory and finds nothing.
    Downstream steps therefore re-pin the root through `pinned_data_yaml`
    below; the value here records where the dataset was written, and is not a
    path any other container can use.

    `val_relative` points at the held-out split when there is one. With no
    split it points back at the train images: Ultralytics needs a `val` target
    to run at all, and this is the same placeholder the labelling system writes
    into its own snapshot data.yaml. Metrics measured that way describe the
    training set rather than generalisation, which is why prepare_data records
    `held_out_validation` and evaluate.py refuses to present the comparison as
    evidence.
    """

    yaml_path = dataset_dir / "data.yaml"

    yaml_path.write_text(
        "\n".join(
            [
                "# Generated by the weekly YOLO pipeline (prepare_data).",
                f"path: {dataset_dir.resolve().as_posix()}",
                "train: train/images",
                f"val: {val_relative}",
                "",
                "nc: 1",
                "names:",
                f"  0: {class_name}",
                "",
            ]
        )
    )

    return yaml_path


def find_data_yaml(
    dataset_dir: Path
) -> Path:

    for name in ("data.yaml", "data.yml", "dataset.yaml", "dataset.yml"):

        candidate = dataset_dir / name

        if candidate.exists():
            return candidate

    matches = sorted(dataset_dir.rglob("data.yaml"))

    if matches:
        return matches[0]

    raise FileNotFoundError(
        f"No data.yaml found under {dataset_dir}."
    )


def pinned_data_yaml(
    dataset_dir: Path,
    work_dir: Path | None = None,
) -> Path:
    """The prepared dataset's data.yaml, with its root valid in THIS container.

    Each step mounts the prepared dataset somewhere different: prepare_data
    writes it under its own job directory, and train, evaluate and
    export_tflite each see it at another path again. The absolute `path`
    write_data_yaml baked in is correct only inside the step that produced it,
    so handing the file straight to Ultralytics in a later step fails with

        Dataset '.../data.yaml' images not found, missing path
        '/mnt/azureml/cr/j/<the-earlier-job-id>/.../prepared_dataset/train/images'

    The root is therefore re-pinned here, at read time, to the directory this
    container actually sees. Letting Ultralytics infer it would also work --
    with `path` absent it falls back to the yaml file's own parent -- but that
    is its current behaviour rather than a contract, and a change there would
    show up as a silently wrong dataset instead of an error.

    The root is taken from the located file's own parent rather than from
    `dataset_dir`, so it stays correct even when the yaml is found one level
    down by the rglob fallback in find_data_yaml.

    Written to `work_dir` (the step's cwd by default) rather than over the
    original, because an input mount is not necessarily writable.
    """

    source = find_data_yaml(dataset_dir)

    root = source.parent.resolve().as_posix()

    # Line-based rather than a yaml round-trip: it keeps the generated header
    # comment, and the only key that must change is one this repo writes.
    kept = [
        line
        for line in source.read_text().splitlines()
        if not line.startswith("path:")
    ]

    destination = (work_dir or Path.cwd()) / "data.pinned.yaml"

    destination.write_text(
        chr(10).join(
            [
                f"# Re-pinned for this step from {source}.",
                f"path: {root}",
            ]
            + kept
        )
        + chr(10)
    )

    return destination
