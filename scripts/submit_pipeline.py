"""Submit one week's training pipeline. The normal entry point.

    python scripts/submit_pipeline.py --stage board
    python scripts/submit_pipeline.py --stage price_region --week 2026-W34
    python scripts/submit_pipeline.py --stage board --dry-run

What it does before submitting:

  1. Registers (or reuses) the versioned data asset for the week, and pins the
     pipeline to that exact version. The YAML default is `@latest`, which is
     fine for a manual run but makes a scheduled run unreproducible -- you
     could not tell afterwards which week's data a job actually consumed.

  2. Resolves what to continue training from. If a model is registered for the
     stage, the latest version is used and `has_previous` is set, which arms
     the promotion margin in the evaluate step. If nothing is registered, the
     staged base weights are used and the new model is promoted
     unconditionally -- there is nothing to compare against.

  3. Fills in the compute and the model-container binding from
     config/workspace.yml, so the placeholders in the pipeline YAML never
     need editing.

After a successful run it reconciles the registry: publish_model registers
through MLflow from inside the job, and if that call did not go through, the
model is registered here from the immutable copy in the model container. The
blobs the fleet downloads are already correct either way; this only keeps next
week's starting point findable.
"""

import argparse
import sys

from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from _config import (  # noqa: E402
    ConfigError,
    REPO_ROOT,
    current_week,
    get_ml_client,
    load_config,
    model_datastore_uri,
    stage_config,
)

from create_data_asset import ensure_data_asset  # noqa: E402


# ============================================================
# ARGUMENTS
# ============================================================

def parse_arguments():

    parser = argparse.ArgumentParser(
        description="Submit a weekly YOLO training pipeline."
    )

    parser.add_argument(
        "--stage",
        required=True,
        choices=("board", "price_region"),
    )

    parser.add_argument(
        "--week",
        default=None,
        help="ISO week, e.g. 2026-W34. Defaults to the current UTC week.",
    )

    parser.add_argument(
        "--data-asset-version",
        default=None,
        help="Use an existing data asset version instead of registering one.",
    )

    parser.add_argument(
        "--new-data-version",
        action="store_true",
        help="Force a new data asset version for a week already registered.",
    )

    # --- hyperparameter overrides, for a one-off run ---
    parser.add_argument("--epochs", type=int, default=None)
    parser.add_argument("--imgsz", type=int, default=None)
    parser.add_argument("--batch", type=int, default=None)
    parser.add_argument("--margin", type=float, default=None)
    parser.add_argument("--per-image", type=int, default=None)
    parser.add_argument("--device", default=None)

    parser.add_argument(
        "--no-augment",
        action="store_true",
        help="Skip offline augmentation for this run.",
    )

    parser.add_argument(
        "--no-register",
        action="store_true",
        help="Publish to the model container but do not touch the registry.",
    )

    parser.add_argument(
        "--force-cold-start",
        action="store_true",
        help=(
            "Ignore the registered model and train from the staged base "
            "weights. The result is promoted unconditionally, so this "
            "replaces the deployed model -- use it to reset a stage."
        ),
    )

    parser.add_argument(
        "--no-wait",
        action="store_true",
        help="Submit and return instead of streaming the logs.",
    )

    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Resolve and print everything, but do not submit.",
    )

    parser.add_argument(
        "--validate",
        action="store_true",
        help=(
            "Resolve the job and have the workspace validate it, then exit "
            "without submitting. Catches what --dry-run cannot: a missing data "
            "asset, an unknown compute, a bad datastore, a component the "
            "service rejects."
        ),
    )

    parser.add_argument(
        "--reconcile-registry",
        action="store_true",
        help=(
            "Skip submitting; just register the model container's copy for "
            "this week if the registry is missing it."
        ),
    )

    return parser.parse_args()


# ============================================================
# PREVIOUS MODEL
# ============================================================

def resolve_previous_model(
    ml_client,
    config: dict,
    stage: str,
    force_cold_start: bool,
) -> tuple[str, str, bool, str]:
    """Return (path, version, has_previous, weights_name) to continue from.

    Precedence is: the latest registered model for the stage, else the base
    weights in the model container.

    The base weights are the .pt behind the deployed .tflite, so `has_previous`
    stays True for them -- there genuinely is a previous model, and the compare
    step should hold the new one against it. That is the difference from a real
    cold start, which only `--force-cold-start` now produces.

    `weights_name` is returned because the base folder holds both detectors.
    """

    settings = stage_config(config, stage)

    model_name = settings["model_name"]

    base_folder = model_datastore_uri(
        config,
        config.get("base_weights_folder", "base_models").strip("/") + "/",
    )

    base_name = settings.get("base_weights", "")

    if not base_name:
        raise ConfigError(
            f"stages.{stage}.base_weights is not set in config/workspace.yml. "
            "It must name the exact .pt inside "
            f"{config.get('base_weights_folder', 'base_models')!r}, because "
            "that folder holds weights for both detectors."
        )

    if force_cold_start:

        print(
            f"--force-cold-start: ignoring any registered {model_name}, "
            f"training from {base_folder}{base_name} and promoting the result "
            "unconditionally."
        )

        return base_folder, "", False, base_name

    try:
        model = ml_client.models.get(name=model_name, label="latest")

    except Exception as exc:

        print(
            f"No registered {model_name} yet ({type(exc).__name__}); "
            f"starting from the deployed base weights {base_name}."
        )

        return base_folder, "", True, base_name

    version = str(model.version)

    # The Model entity's own path is a datastore URI, which a uri_folder input
    # takes directly. Going through `azureml:name:version` instead would force
    # the input type to custom_model, and the components would then need two
    # input types for what is the same folder of weights.
    path = getattr(model, "path", None)

    if not path:

        print(
            f"Registered {model_name}:{version} has no resolvable path; "
            f"falling back to the base weights {base_name}."
        )

        return base_folder, "", True, base_name

    print(f"Continuing from registered {model_name}:{version}")
    print(f"  path: {path}")

    # A registered model folder holds exactly one model's weights, so the
    # normal best.pt preference applies and no name is needed.
    return path, version, True, ""


# ============================================================
# REGISTRY RECONCILIATION
# ============================================================

def registry_has_week(
    ml_client,
    model_name: str,
    week: str,
) -> str | None:

    try:
        for model in ml_client.models.list(name=model_name):

            detail = ml_client.models.get(
                name=model_name,
                version=model.version,
            )

            if (detail.tags or {}).get("week") == week:
                return str(detail.version)

    except Exception:
        return None

    return None


def reconcile_registry(
    ml_client,
    config: dict,
    stage: str,
    week: str,
) -> None:
    """Register this week's published copy if the registry is missing it.

    Reads from the immutable `versions/<week>/` folder in the model container
    rather than from a job output, so it works long after the job is gone.
    """

    from azure.ai.ml.constants import AssetTypes
    from azure.ai.ml.entities import Model

    settings = stage_config(config, stage)

    model_name = settings["model_name"]

    existing = registry_has_week(ml_client, model_name, week)

    if existing:
        print(f"{model_name} already has a version tagged week={week} (v{existing}).")
        return

    model_root = config.get("model_root", "detectors")

    path = model_datastore_uri(
        config,
        f"{model_root}/{stage}/versions/{week}/",
    )

    print(f"Registering {model_name} from {path}")

    try:
        created = ml_client.models.create_or_update(
            Model(
                name=model_name,
                path=path,
                type=AssetTypes.CUSTOM_MODEL,
                description=(
                    f"{stage} detector promoted from the ISO week {week} "
                    "training run."
                ),
                tags={"stage": stage, "week": week, "source": "reconcile"},
            )
        )

        print(f"Registered {created.name}:{created.version}")

    except Exception as exc:
        print(
            f"Could not register {model_name} from {path}: {exc}\n"
            "This is only a problem if the run did promote a model -- if the "
            "deployed model won, there is nothing at that path and nothing to "
            "register."
        )


# ============================================================
# VALIDATION
# ============================================================

def validate_job(
    ml_client,
    job,
) -> bool:
    """Ask the workspace to check the job without running it.

    --dry-run only proves the YAML assembled locally. This proves the things
    that are only knowable server-side, which is most of what actually goes
    wrong on a first submit: the data asset version does not exist, the
    compute name is wrong, the datastore is not attached, the environment
    never finished building.
    """

    print()
    print("Validating against the workspace...")

    try:
        result = ml_client.jobs.validate(job)

    except Exception as exc:
        print(f"  Could not run validation: {type(exc).__name__}: {exc}")
        return False

    passed = getattr(result, "passed", None)

    messages = (
        getattr(result, "error_messages", None)
        or getattr(result, "errors", None)
        or {}
    )

    if passed:
        print("  VALID -- the workspace accepts this job.")
        return True

    print("  INVALID:")

    if isinstance(messages, dict):
        for location, message in messages.items():
            print(f"    {location}: {message}")

    elif messages:
        print(f"    {messages}")

    else:
        print(f"    {result}")

    return False


# ============================================================
# SUBMIT
# ============================================================

def build_job(
    config: dict,
    stage: str,
    week: str,
    data_asset,
    previous_path: str,
    previous_version: str,
    has_previous: bool,
    weights_name: str,
    args,
):
    """Load the pipeline YAML with the environment-specific bits substituted.

    The substitutions go through `params_override` rather than by assigning
    onto `job.inputs` after loading. That is not a style preference: assigning
    a bare `Input(...)` leaves an object the SDK only half understands --
    `jobs.validate()` then dies with "'Input' object has no attribute
    '_to_job_input'", because the loader normally wraps each input in a
    `PipelineInput` and the hand-assigned one skips that. `params_override`
    applies the change at the YAML layer, before the entity is constructed, so
    the result is indistinguishable from a job that was authored this way. It
    is the same mechanism behind `az ml job create --set`.

    Note the key shapes differ: a structured input needs the leaf
    (`inputs.annotated_week.path`), a literal takes the whole key
    (`inputs.week`).
    """

    from azure.ai.ml import load_job

    settings = stage_config(config, stage)

    pipeline_path = REPO_ROOT / settings["pipeline"]

    overrides: list[dict] = [
        {"settings.default_compute": f"azureml:{config['compute']}"},

        # Pin the exact data asset version rather than leaving the YAML's
        # @latest, so the run records which week's data it consumed.
        {"inputs.annotated_week.path":
            f"azureml:{data_asset.name}:{data_asset.version}"},

        {"inputs.previous_model.path": previous_path},

        {"inputs.previous_model_version": previous_version},
        {"inputs.has_previous": "true" if has_previous else "false"},
        {"inputs.weights_name": weights_name},
        {"inputs.week": week},
        {"inputs.model_root": config.get("model_root", "detectors")},

        {"outputs.published_model.path": model_datastore_uri(config)},

        {"display_name": f"{stage} weekly training {week}"},

        {"tags": {
            "stage": stage,
            "week": week,
            "data_asset": f"{data_asset.name}:{data_asset.version}",
            "previous_model_version": previous_version or "cold-start",
        }},
    ]

    if args.no_register:
        overrides.append({"inputs.register": "false"})

    if args.no_augment:
        overrides.append({"inputs.augment": "false"})

    for name, value in (
        ("epochs", args.epochs),
        ("imgsz", args.imgsz),
        ("batch", args.batch),
        ("margin", args.margin),
        ("per_image", args.per_image),
        ("device", args.device),
    ):
        if value is not None:
            overrides.append({f"inputs.{name}": value})

    return load_job(source=str(pipeline_path), params_override=overrides)


def render_binding(
    value
) -> str:
    """Best-effort display string for one pipeline input or output.

    `PipelineInput.path` RAISES for an input holding a literal rather than a
    path-bearing Input -- it does not return None -- so `getattr(value,
    "path", None)` is not enough to probe it. Everything here is therefore
    guarded, and the private `_data` fallback is deliberate: there is no
    public accessor that works for both shapes.
    """

    try:
        path = value.path

        if path:
            return str(path)

    except Exception:
        pass

    if isinstance(value, (str, int, float, bool)):
        return str(value)

    data = getattr(value, "_data", None)

    if isinstance(data, (str, int, float, bool)):
        return str(data)

    try:
        nested = getattr(data, "path", None)

        if nested:
            return str(nested)

    except Exception:
        pass

    return repr(value)


def describe(
    job,
    stage: str,
    week: str,
) -> None:
    """Print the resolved job. Purely informational.

    Wrapped end to end because this is a display helper: an earlier version
    raised while formatting a literal input and took the whole validation down
    with it. Nothing cosmetic should be able to fail a deploy.
    """

    try:
        print()
        print("=" * 70)
        print(f"Stage: {stage}    Week: {week}")
        print(f"Compute: {job.settings.default_compute}")
        print("-" * 70)

        for name, value in job.inputs.items():
            print(f"  in  {name:<24} {render_binding(value)}")

        for name, value in job.outputs.items():
            print(f"  out {name:<24} {render_binding(value)}")

        print("=" * 70)

    except Exception as exc:
        print(f"(could not render the job summary: {type(exc).__name__}: {exc})")


# ============================================================
# MAIN
# ============================================================

def main() -> None:

    args = parse_arguments()

    config = load_config()

    week = args.week or current_week()

    ml_client = get_ml_client(config)

    if args.reconcile_registry:
        reconcile_registry(ml_client, config, args.stage, week)
        return

    settings = stage_config(config, args.stage)

    # ------------------------------------------------------------
    # 1. The week's data asset.
    # ------------------------------------------------------------

    if args.data_asset_version:

        data_asset = ml_client.data.get(
            name=settings["data_asset"],
            version=args.data_asset_version,
        )

        print(f"Using existing data asset {data_asset.name}:{data_asset.version}")

    else:

        data_asset = ensure_data_asset(
            config=config,
            stage=args.stage,
            week=week,
            force_new=args.new_data_version,
            ml_client=ml_client,
        )

    # ------------------------------------------------------------
    # 2. What to continue training from.
    # ------------------------------------------------------------

    (
        previous_path,
        previous_version,
        has_previous,
        weights_name,
    ) = resolve_previous_model(
        ml_client,
        config,
        args.stage,
        args.force_cold_start,
    )

    # ------------------------------------------------------------
    # 3. Build, describe, submit.
    # ------------------------------------------------------------

    job = build_job(
        config=config,
        stage=args.stage,
        week=week,
        data_asset=data_asset,
        previous_path=previous_path,
        previous_version=previous_version,
        has_previous=has_previous,
        weights_name=weights_name,
        args=args,
    )

    describe(job, args.stage, week)

    if args.dry_run:
        print("--dry-run: nothing submitted.")
        return

    if args.validate:
        if not validate_job(ml_client, job):
            raise SystemExit(1)
        print("--validate: nothing submitted.")
        return

    submitted = ml_client.jobs.create_or_update(job)

    print()
    print(f"Submitted: {submitted.name}")
    print(f"Studio:    {submitted.studio_url}")

    if args.no_wait:
        print()
        print(
            "Not waiting. Once it finishes, run with --reconcile-registry to "
            "make sure the promoted model is registered."
        )
        return

    print()
    ml_client.jobs.stream(submitted.name)

    final = ml_client.jobs.get(submitted.name)

    print()
    print(f"Job {final.name} finished with status: {final.status}")

    if final.status != "Completed":
        raise SystemExit(1)

    if not args.no_register:
        print()
        reconcile_registry(ml_client, config, args.stage, week)


if __name__ == "__main__":
    main()
