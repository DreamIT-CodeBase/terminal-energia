# ============================================================
# THE SHARED PIPELINE IMAGE
#
# A Dockerfile rather than the simpler `image:` + `conda_file:`
# pair, for one reason.
#
# Ultralytics depends on `opencv-python`, while this environment
# also asks for `opencv-python-headless`. Both ship the same
# `cv2/` directory, so pip installs both and whichever lands
# second owns cv2 -- an ordering the spec does not control. When
# the GUI wheel won, every step that imports cv2 (prepare_data,
# train, evaluate) died before doing any work:
#
#   ImportError: libxcb.so.1: cannot open shared object file
#
# because the base image carries no X libraries.
#
# Fixed from both ends below: the libraries are installed, AND
# the GUI wheel is removed so headless is unambiguously the cv2
# that gets imported. The `conda_file:` form cannot do the first
# half -- it offers no hook to run apt -- which is why this file
# exists at all.
# ============================================================

FROM mcr.microsoft.com/azureml/openmpi4.1.0-ubuntu22.04

# Azure ML activates whatever this points at for every job that
# uses the image. It is the contract a build-context environment
# has to satisfy in place of `conda_file:`.
ENV AZUREML_CONDA_ENVIRONMENT_PATH=/azureml-envs/yolo-pipeline

# The X/GL libraries the OpenCV wheels link against. Kept even
# though the GUI wheel is removed below: they cost little, and
# they stop the next dependency that wants libGL from failing the
# same way on a Friday.
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        libxcb1 \
        libgl1 \
        libglib2.0-0 \
        libsm6 \
        libxext6 \
        libxrender1 && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

COPY conda.yml /tmp/conda.yml

RUN conda env create -p "$AZUREML_CONDA_ENVIRONMENT_PATH" -f /tmp/conda.yml && \
    conda clean --all --yes

# Make headless the only cv2.
#
# The version is read back out of the resolved environment rather
# than pinned here, so conda.yml stays the single place a version
# is chosen. --force-reinstall is load-bearing: the GUI wheel
# overwrote files headless had already written, so uninstalling it
# takes those files with it and headless must be laid down again.
#
# The import at the end makes a broken image fail the build,
# rather than a job failing twenty minutes into a submit.
RUN set -eux; \
    PIP="$AZUREML_CONDA_ENVIRONMENT_PATH/bin/pip"; \
    HEADLESS="$("$PIP" show opencv-python-headless | awk '/^Version:/{print $2}')"; \
    test -n "$HEADLESS"; \
    "$PIP" uninstall -y opencv-python; \
    "$PIP" install --no-deps --force-reinstall --no-cache-dir \
        "opencv-python-headless==$HEADLESS"; \
    "$AZUREML_CONDA_ENVIRONMENT_PATH/bin/python" -c \
        "import cv2, albumentations, ultralytics; print('cv2', cv2.__version__)"

ENV PATH=$AZUREML_CONDA_ENVIRONMENT_PATH/bin:$PATH
