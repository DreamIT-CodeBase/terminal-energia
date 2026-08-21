"""Offline augmentation, applied to the train split only.

The transform set and its magnitudes come straight from the price-region
notebook script. They model what the phone camera actually does to a
forecourt board -- handheld tilt, off-axis perspective, exposure swings,
motion blur, sensor noise, and downscaling from a distant shot -- rather
than the generic flip/mosaic Ultralytics applies on its own.

Both detectors run this. The board detector sees full frames and the
price-region detector sees crops, but the distortions are the same camera's.
"""

from dataclasses import dataclass

import albumentations as A
import cv2


@dataclass(frozen=True)
class AugmentConfig:
    """Probabilities and magnitudes for one augmentation pass.

    `tilt` and `perspective` are mutually exclusive -- they go into a OneOf --
    so their probabilities should sum to at most 1.0.
    """

    seed: int = 42

    # --- probabilities ---
    grayscale: float = 1.0
    tilt: float = 0.40
    perspective: float = 0.35
    aspect: float = 0.60
    exposure: float = 0.80
    color: float = 0.60
    blur: float = 0.50
    noise: float = 0.50
    downscale: float = 0.40

    # --- magnitudes ---
    max_degrees: float = 14.0       # tilt angle
    max_warp: float = 0.10          # perspective corner jitter, fraction
    max_aspect: float = 0.45        # per-axis scale delta
    min_downscale: float = 0.35     # lowest resolution fraction


def build_transform(
    config: AugmentConfig
) -> A.Compose:
    """One Compose that draws a random subset of transforms per call."""

    return A.Compose(
        [
            # --- geometric: boxes are transformed with the pixels ---
            A.OneOf(
                [
                    A.Rotate(
                        limit=config.max_degrees,
                        border_mode=cv2.BORDER_REPLICATE,
                        p=config.tilt,
                    ),
                    A.Perspective(
                        scale=(0.05, config.max_warp),
                        border_mode=cv2.BORDER_REPLICATE,
                        fit_output=False,
                        p=config.perspective,
                    ),
                ],
                p=min(1.0, config.tilt + config.perspective),
            ),

            # Anisotropic scale: changes aspect ratio, boxes tracked.
            A.Affine(
                scale={
                    "x": (1 - config.max_aspect, 1 + config.max_aspect),
                    "y": (1 - config.max_aspect, 1 + config.max_aspect),
                },
                keep_ratio=False,
                fit_output=True,
                border_mode=cv2.BORDER_REPLICATE,
                p=config.aspect,
            ),

            # --- photometric: boxes unchanged ---
            A.RandomBrightnessContrast(
                brightness_limit=0.35,
                contrast_limit=0.35,
                p=config.exposure,
            ),
            A.HueSaturationValue(
                hue_shift_limit=25,
                sat_shift_limit=40,
                val_shift_limit=30,
                p=config.color,
            ),
            A.GaussianBlur(
                blur_limit=(3, 11),
                p=config.blur,
            ),
            A.GaussNoise(
                std_range=(0.03, 0.12),
                p=config.noise,
            ),
            A.Downscale(
                scale_range=(config.min_downscale, 0.85),
                interpolation_pair={
                    "downscale": cv2.INTER_AREA,
                    "upscale": cv2.INTER_LINEAR,
                },
                p=config.downscale,
            ),

            # --- production-style normalisation ---
            # The device preprocesses to grayscale before inference, so the
            # training set is normalised the same way. Kept 3-channel.
            A.ToGray(
                num_output_channels=3,
                p=config.grayscale,
            ),
        ],
        bbox_params=A.BboxParams(
            format="yolo",
            label_fields=["class_labels"],
            min_visibility=0.1,
            min_area=1,
            clip=True,
        ),
        seed=config.seed,
    )
