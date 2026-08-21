"""Local checks that need no Azure credentials and no network.

Every failure here is one that would otherwise surface as a failed job several
minutes into a submit -- a component input renamed in the YAML but not in the
script, a `${{parent.inputs.x}}` pointing at nothing, a placeholder left in the
config. They are cheap to check and expensive to discover remotely.

    python scripts/preflight.py
    python scripts/preflight.py --allow-placeholders    # structure only

Exit code 0 means the pipelines are structurally sound and safe to submit.
"""

import argparse
import ast
import re
import sys

from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parent.parent

STAGE_PIPELINES = {
    "board": ("board_pipeline.yml", 0.0, "false"),
    "price_region": ("price_region_pipeline.yml", 1.0, "true"),
}


# ============================================================
# REPORTING
# ============================================================

class Report:

    def __init__(self):
        self.failures: list[str] = []
        self.warnings: list[str] = []

    def check(
        self,
        ok: bool,
        label: str,
        detail: str = "",
    ) -> bool:

        print(f"  {'PASS' if ok else 'FAIL'}  {label}")

        if not ok:
            if detail:
                print(f"        {detail}")
            self.failures.append(label)

        return ok

    def warn(
        self,
        label: str,
        detail: str = "",
    ) -> None:

        print(f"  WARN  {label}")

        if detail:
            print(f"        {detail}")

        self.warnings.append(label)

    def section(
        self,
        title: str,
    ) -> None:

        print()
        print(title)


# ============================================================
# CHECKS
# ============================================================

def load_yaml(
    path: Path
):

    return yaml.safe_load(path.read_text(encoding="utf-8"))


def declared_flags(
    script: Path
) -> set[str]:
    """The --flags a step script's argparse actually accepts."""

    tree = ast.parse(script.read_text(encoding="utf-8"))

    return {
        str(arg.value)[2:]
        for node in ast.walk(tree)
        if isinstance(node, ast.Call)
        and getattr(node.func, "attr", "") == "add_argument"
        for arg in node.args
        if isinstance(arg, ast.Constant) and str(arg.value).startswith("--")
    }


def check_python_compiles(
    report: Report
) -> None:

    report.section("Python syntax")

    import py_compile

    for path in sorted(list((ROOT / "src").rglob("*.py")) +
                       list((ROOT / "scripts").rglob("*.py"))):

        try:
            py_compile.compile(str(path), doraise=True, cfile=str(
                Path(__import__("tempfile").gettempdir()) / "preflight.pyc"
            ))
            ok, detail = True, ""

        except Exception as exc:
            ok, detail = False, str(exc)

        report.check(ok, f"compiles: {path.relative_to(ROOT).as_posix()}", detail)


def check_yaml_parses(
    report: Report
) -> None:

    report.section("YAML syntax")

    for path in sorted(ROOT.rglob("*.yml")):

        try:
            load_yaml(path)
            ok, detail = True, ""

        except Exception as exc:
            ok, detail = False, str(exc)

        report.check(ok, f"parses: {path.relative_to(ROOT).as_posix()}", detail)


def check_wiring(
    report: Report
) -> None:
    """Pipeline job inputs must match the component contracts they invoke."""

    report.section("Pipeline / component wiring")

    for pipeline_path in sorted((ROOT / "pipelines").glob("*.yml")):

        pipeline = load_yaml(pipeline_path)

        pipeline_inputs = set(pipeline.get("inputs") or {})
        pipeline_outputs = set(pipeline.get("outputs") or {})
        jobs = pipeline.get("jobs") or {}

        name = pipeline_path.name

        for job_name, job in jobs.items():

            component_path = (pipeline_path.parent / job["component"]).resolve()

            if not report.check(
                component_path.exists(),
                f"{name}:{job_name} component file exists",
                str(component_path),
            ):
                continue

            component = load_yaml(component_path)

            component_inputs = set(component.get("inputs") or {})
            component_outputs = set(component.get("outputs") or {})

            given_inputs = set(job.get("inputs") or {})
            given_outputs = set(job.get("outputs") or {})

            extra = given_inputs - component_inputs

            report.check(
                not extra,
                f"{name}:{job_name} passes only declared inputs",
                f"undeclared: {sorted(extra)}",
            )

            required = {
                key
                for key, spec in (component.get("inputs") or {}).items()
                if "default" not in (spec or {})
            }

            missing = required - given_inputs

            report.check(
                not missing,
                f"{name}:{job_name} supplies every required input",
                f"missing: {sorted(missing)}",
            )

            unbound = component_outputs - given_outputs

            report.check(
                not unbound,
                f"{name}:{job_name} binds every component output",
                f"unbound: {sorted(unbound)}",
            )

            # Every ${{parent.*}} reference must resolve.
            dangling: list[str] = []

            for key, value in (job.get("inputs") or {}).items():

                if not isinstance(value, str):
                    continue

                dangling += [
                    f"{key} -> parent.inputs.{ref}"
                    for ref in re.findall(r"\$\{\{parent\.inputs\.(\w+)\}\}", value)
                    if ref not in pipeline_inputs
                ]

                dangling += [
                    f"{key} -> parent.jobs.{upstream}"
                    for upstream, _ in re.findall(
                        r"\$\{\{parent\.jobs\.(\w+)\.outputs\.(\w+)\}\}", value
                    )
                    if upstream not in jobs
                ]

            for key, value in (job.get("outputs") or {}).items():

                if isinstance(value, str):
                    dangling += [
                        f"{key} -> parent.outputs.{ref}"
                        for ref in re.findall(
                            r"\$\{\{parent\.outputs\.(\w+)\}\}", value
                        )
                        if ref not in pipeline_outputs
                    ]

            report.check(
                not dangling,
                f"{name}:{job_name} all parent references resolve",
                f"dangling: {dangling}",
            )


def check_commands(
    report: Report
) -> None:
    """A component's command line must match its script's argparse."""

    report.section("Component command lines")

    for component_path in sorted((ROOT / "components").glob("*.yml")):

        component = load_yaml(component_path)

        command = component["command"]

        declared_inputs = set(component.get("inputs") or {})
        declared_outputs = set(component.get("outputs") or {})

        name = component_path.name

        used_inputs = set(re.findall(r"inputs\.(\w+)", command))
        used_outputs = set(re.findall(r"outputs\.(\w+)", command))

        report.check(
            not (used_inputs - declared_inputs),
            f"{name} command references only declared inputs",
            f"undeclared: {sorted(used_inputs - declared_inputs)}",
        )

        report.check(
            not (used_outputs - declared_outputs),
            f"{name} command references only declared outputs",
            f"undeclared: {sorted(used_outputs - declared_outputs)}",
        )

        unused = (declared_inputs - used_inputs) | (declared_outputs - used_outputs)

        if unused:
            report.warn(
                f"{name} declares unused inputs/outputs",
                f"never referenced in command: {sorted(unused)}",
            )

        match = re.search(r"python\s+(\S+\.py)", command)

        if not report.check(bool(match), f"{name} names a script"):
            continue

        script = ROOT / "src" / match.group(1)

        if not report.check(
            script.exists(),
            f"{name} script exists: src/{match.group(1)}",
        ):
            continue

        passed = set(re.findall(r"--([a-z0-9_]+)", command))
        accepted = declared_flags(script)

        unknown = passed - accepted

        report.check(
            not unknown,
            f"{name} passes only flags {script.name} accepts",
            f"unknown: {sorted('--' + flag for flag in unknown)}",
        )


def check_config(
    report: Report,
    allow_placeholders: bool,
) -> None:

    report.section("Configuration")

    sys.path.insert(0, str(ROOT / "scripts"))

    import _config

    config_path = _config.CONFIG_PATH

    if not report.check(config_path.exists(), "config/workspace.yml exists"):
        return

    raw = load_yaml(config_path) or {}

    placeholders = sorted(_config._find_placeholders(raw))

    if placeholders:

        detail = "still to fill in: " + ", ".join(placeholders)

        if allow_placeholders:
            report.warn("config/workspace.yml has no placeholders", detail)
        else:
            report.check(False, "config/workspace.yml has no placeholders", detail)

    else:
        report.check(True, "config/workspace.yml has no placeholders")

    # The week format must match the labelling system, or the data asset
    # points at a snapshot prefix that does not exist.
    from datetime import datetime, timezone

    expected = datetime.now(timezone.utc).strftime("%G-W%V")

    report.check(
        _config.current_week() == expected,
        f"ISO week format matches system-api ({expected})",
    )

    for stage, (pipeline_name, _, _) in STAGE_PIPELINES.items():

        settings = (raw.get("stages") or {}).get(stage) or {}

        report.check(
            settings.get("pipeline") == f"pipelines/{pipeline_name}",
            f"config names the right pipeline for {stage}",
            f"got: {settings.get('pipeline')!r}",
        )

        report.check(
            bool(settings.get("model_name")) and bool(settings.get("data_asset")),
            f"config names model + data asset for {stage}",
        )


def check_intent(
    report: Report
) -> None:
    """Settings that encode a decision, not a preference.

    These are asserted because a well-meaning edit that flips one of them
    would train a model on a distribution the device never produces, and
    nothing downstream would complain.
    """

    report.section("Pipeline intent")

    for stage, (pipeline_name, grayscale_prob, grayscale_originals) in (
        STAGE_PIPELINES.items()
    ):

        inputs = load_yaml(ROOT / "pipelines" / pipeline_name)["inputs"]

        report.check(
            inputs.get("device") == "cpu",
            f"{stage}: device is cpu",
            f"got: {inputs.get('device')!r}",
        )

        report.check(
            inputs.get("grayscale_prob") == grayscale_prob
            and inputs.get("grayscale_originals") == grayscale_originals,
            f"{stage}: colour handling matches the device "
            f"({'grayscale' if grayscale_prob else 'colour'})",
            f"got grayscale_prob={inputs.get('grayscale_prob')!r}, "
            f"grayscale_originals={inputs.get('grayscale_originals')!r}",
        )

        report.check(
            inputs.get("stage") == stage,
            f"{stage}: stage name matches system-api STAGES",
        )

    # The snapshot layout keys on these exact class names.
    board = load_yaml(ROOT / "pipelines" / "board_pipeline.yml")["inputs"]
    region = load_yaml(ROOT / "pipelines" / "price_region_pipeline.yml")["inputs"]

    report.check(
        board.get("class_name") == "board"
        and region.get("class_name") == "price region",
        "class names match STAGE_CLASS_NAMES in system-api",
        f"got {board.get('class_name')!r} and {region.get('class_name')!r}",
    )


def check_snapshot_hygiene(
    report: Report
) -> None:

    report.section("Code snapshot")

    amlignore = ROOT / "src" / ".amlignore"

    report.check(
        amlignore.exists(),
        "src/.amlignore exists (repo-root .amlignore does not apply here)",
    )

    # Only a warning: .amlignore keeps these out of the upload anyway.
    strays = [
        path.relative_to(ROOT).as_posix()
        for path in ROOT.glob("src/**/*")
        if path.name == "__pycache__" or path.suffix in {".pyc", ".pt", ".tflite"}
    ]

    if strays:
        report.warn(
            "build artifacts present under src/",
            f"ignored on upload, but worth cleaning: {strays[:6]}",
        )
    else:
        report.check(True, "no build artifacts under src/")

    image_spec = load_yaml(ROOT / "environments" / "environment.yml").get("image", "")

    creator = (ROOT / "scripts" / "create_environment.py").read_text(encoding="utf-8")

    report.check(
        image_spec in creator,
        "environment.yml and create_environment.py agree on the base image",
        f"environment.yml says {image_spec!r}",
    )


# ============================================================
# MAIN
# ============================================================

def main() -> int:

    parser = argparse.ArgumentParser(
        description="Local structural checks for the training pipelines."
    )

    parser.add_argument(
        "--allow-placeholders",
        action="store_true",
        help=(
            "Treat unfilled config values as a warning. Lets the structural "
            "checks run before the workspace details are known."
        ),
    )

    args = parser.parse_args()

    print("=" * 70)
    print("PREFLIGHT")
    print("=" * 70)

    report = Report()

    check_python_compiles(report)
    check_yaml_parses(report)
    check_wiring(report)
    check_commands(report)
    check_intent(report)
    check_snapshot_hygiene(report)
    check_config(report, args.allow_placeholders)

    print()
    print("=" * 70)

    if report.warnings:
        print(f"{len(report.warnings)} warning(s):")
        for warning in report.warnings:
            print(f"  - {warning}")
        print()

    if report.failures:
        print(f"PREFLIGHT FAILED -- {len(report.failures)} problem(s):")
        for failure in report.failures:
            print(f"  - {failure}")
        print("=" * 70)
        return 1

    print("PREFLIGHT PASSED")
    print("=" * 70)

    return 0


if __name__ == "__main__":
    sys.exit(main())
