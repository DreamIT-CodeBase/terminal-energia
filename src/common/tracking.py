"""MLflow logging that cannot take a step down with it.

Inside an Azure ML job the tracking URI is already pointed at the workspace
and a run is already active, so these helpers never start or end a run --
they only write into the one the job gave them.

Every call is guarded. Losing a metric is annoying; failing a two-hour
training job because the tracking service hiccuped is not acceptable.
"""

import os
import re


# Guarded so the step scripts can be run locally, outside the pipeline
# environment, to debug a dataset problem. Inside a job mlflow is always
# present and every call below goes through as normal.
try:
    import mlflow

except ImportError:  # pragma: no cover
    mlflow = None

    print(
        "[tracking] mlflow is not installed; metrics will not be recorded. "
        "This is expected only when running a step script by hand."
    )


# MLflow rejects metric names outside this set. Ultralytics emits names like
# `metrics/mAP50-95(B)`, so the parentheses have to go.
_UNSAFE_METRIC_CHARS = re.compile(r"[^A-Za-z0-9_\-./ :]")


def metric_name(
    raw: str
) -> str:

    return _UNSAFE_METRIC_CHARS.sub("_", raw).strip("_")


def log_params(
    params: dict
) -> None:

    if mlflow is None:
        return

    try:
        mlflow.log_params(
            {
                key: ("" if value is None else str(value))[:500]
                for key, value in params.items()
            }
        )

    except Exception as exc:
        print(f"[tracking] could not log params: {exc}")


def log_metrics(
    metrics: dict,
    step: int | None = None,
    prefix: str = "",
) -> None:

    cleaned: dict[str, float] = {}

    for key, value in metrics.items():

        try:
            number = float(value)

        except (TypeError, ValueError):
            continue

        # NaN and inf are rejected by the tracking store.
        if number != number or number in (float("inf"), float("-inf")):
            continue

        cleaned[metric_name(f"{prefix}{key}")] = number

    if not cleaned or mlflow is None:
        return

    try:
        mlflow.log_metrics(cleaned, step=step)

    except Exception as exc:
        print(f"[tracking] could not log metrics: {exc}")


def set_tags(
    tags: dict
) -> None:

    if mlflow is None:
        return

    try:
        mlflow.set_tags(
            {
                key: ("" if value is None else str(value))[:500]
                for key, value in tags.items()
            }
        )

    except Exception as exc:
        print(f"[tracking] could not set tags: {exc}")


def log_artifacts(
    local_dir,
    artifact_path: str | None = None,
) -> None:

    if mlflow is None:
        return

    try:
        mlflow.log_artifacts(str(local_dir), artifact_path=artifact_path)

    except Exception as exc:
        print(f"[tracking] could not log artifacts from {local_dir}: {exc}")


def active_run_id() -> str | None:
    """The run to attach artifacts to.

    `mlflow.active_run()` is None until something in the process actually
    logs, so asking too early reports "no run" inside a job that definitely
    has one. Azure ML puts the id in the environment, which is the reliable
    answer -- registration depends on getting this right.
    """

    if mlflow is None:
        return None

    try:
        run = mlflow.active_run()

        if run is not None:
            return run.info.run_id

    except Exception:
        pass

    for variable in ("MLFLOW_RUN_ID", "AZUREML_RUN_ID"):

        value = os.environ.get(variable)

        if value:
            return value

    return None
