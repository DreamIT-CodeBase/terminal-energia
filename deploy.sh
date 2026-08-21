#!/usr/bin/env bash
#
# Sync local changes to the Azure ML workspace.
#
#   ./deploy.sh                    # preflight, environment if changed, validate both
#   ./deploy.sh --check            # local checks only, no Azure, no changes
#   ./deploy.sh --stage board      # only touch the board pipeline
#   ./deploy.sh --force-env        # rebuild the environment even if unchanged
#   ./deploy.sh --skip-validate    # preflight + environment, skip the workspace check
#
# Most of this repo needs no deploy step. The pipelines reference components by
# local path, and components reference code by local path, so Azure ML
# re-uploads `src/` and re-reads the component YAML on every submit -- a change
# to a .py file is live on the next run with no action here.
#
# What genuinely needs syncing is the environment: conda.yml changes do NOT
# reach the workspace until a new environment version is built. That is what
# this script is really for. Around it, it front-loads the failures that would
# otherwise cost you a submit: structural mistakes (preflight, no credentials
# needed) and workspace-level ones (validate, which asks the service to check
# the job without running it).
#
# Deliberately never submits a training job. Deploying and training are
# separate decisions, and training here costs hours of CPU.

set -euo pipefail

cd "$(dirname "$0")"

PYTHON="${PYTHON:-python}"

STAGES=(board price_region)
CHECK_ONLY=0
FORCE_ENV=0
SKIP_ENV=0
SKIP_VALIDATE=0

RED=''; GREEN=''; YELLOW=''; BOLD=''; RESET=''
if [ -t 1 ]; then
  RED=$'\033[31m'; GREEN=$'\033[32m'; YELLOW=$'\033[33m'
  BOLD=$'\033[1m'; RESET=$'\033[0m'
fi

say()  { printf '%s\n' "$*"; }
step() { printf '\n%s==> %s%s\n' "$BOLD" "$*" "$RESET"; }
ok()   { printf '%s    ok%s  %s\n' "$GREEN" "$RESET" "$*"; }
warn() { printf '%s  warn%s  %s\n' "$YELLOW" "$RESET" "$*"; }
die()  { printf '\n%s  FAILED%s  %s\n' "$RED" "$RESET" "$*" >&2; exit 1; }

usage() {
  # Print the header comment block: everything from line 2 up to the first
  # line that is not a comment. Beats a hardcoded line range, which silently
  # drifts every time the header is edited.
  awk 'NR==1 {next} /^#/ {sub(/^# ?/, ""); print; next} {exit}' "$0"
  exit 0
}

while [ $# -gt 0 ]; do
  case "$1" in
    --check|--check-only) CHECK_ONLY=1 ;;
    --force-env)          FORCE_ENV=1 ;;
    --skip-env)           SKIP_ENV=1 ;;
    --skip-validate)      SKIP_VALIDATE=1 ;;
    --stage)
      shift
      [ $# -gt 0 ] || die "--stage needs a value (board or price_region)"
      case "$1" in
        board|price_region) STAGES=("$1") ;;
        both)               STAGES=(board price_region) ;;
        *) die "unknown stage '$1' (expected board, price_region or both)" ;;
      esac
      ;;
    -h|--help) usage ;;
    *) die "unknown option '$1' (try --help)" ;;
  esac
  shift
done

command -v "$PYTHON" >/dev/null 2>&1 \
  || die "'$PYTHON' not on PATH. Set PYTHON=/path/to/python and retry."


# ============================================================
# 1. PREFLIGHT -- local, no credentials, no network
# ============================================================

step "Preflight (local structural checks)"

PREFLIGHT_ARGS=()
[ "$CHECK_ONLY" -eq 1 ] && PREFLIGHT_ARGS+=(--allow-placeholders)

if ! "$PYTHON" scripts/preflight.py "${PREFLIGHT_ARGS[@]+"${PREFLIGHT_ARGS[@]}"}"; then
  die "preflight found problems. Nothing was sent to the workspace."
fi

if [ "$CHECK_ONLY" -eq 1 ]; then
  step "Summary"
  ok "local checks passed"
  say ""
  say "  --check does not contact Azure. Drop it to sync the environment and"
  say "  validate the pipelines against the workspace."
  exit 0
fi


# ============================================================
# 2. ENVIRONMENT -- the one thing that does not self-propagate
# ============================================================

if [ "$SKIP_ENV" -eq 1 ]; then
  step "Environment"
  warn "skipped (--skip-env). conda.yml changes will NOT be live."
else
  step "Environment (rebuild only if conda.yml or the base image changed)"

  ENV_ARGS=(--if-changed)
  [ "$FORCE_ENV" -eq 1 ] && ENV_ARGS=()

  if ! "$PYTHON" scripts/create_environment.py "${ENV_ARGS[@]+"${ENV_ARGS[@]}"}"; then
    die "could not sync the environment. Check your Azure login and config/workspace.yml."
  fi
fi


# ============================================================
# 3. VALIDATE -- ask the workspace to check each pipeline
# ============================================================

VALIDATED=()
FAILED=()

if [ "$SKIP_VALIDATE" -eq 1 ]; then
  step "Validation"
  warn "skipped (--skip-validate)"
else
  for stage in "${STAGES[@]}"; do
    step "Validate pipeline: $stage"

    # --validate resolves the data asset for the current week, so a week with
    # nothing annotated yet is a legitimate failure to report rather than hide.
    if "$PYTHON" scripts/submit_pipeline.py --stage "$stage" --validate; then
      VALIDATED+=("$stage")
    else
      FAILED+=("$stage")
      warn "$stage did not validate (see above)"
    fi
  done
fi


# ============================================================
# 4. SUMMARY
# ============================================================

step "Summary"

ok "preflight passed"

if [ "$SKIP_ENV" -eq 1 ]; then
  warn "environment not synced"
else
  ok "environment in sync"
fi

for stage in ${VALIDATED[@]+"${VALIDATED[@]}"}; do
  ok "$stage pipeline validates against the workspace"
done

for stage in ${FAILED[@]+"${FAILED[@]}"}; do
  printf '%s  FAIL%s  %s pipeline did not validate\n' "$RED" "$RESET" "$stage"
done

say ""
say "Propagates automatically on the next submit -- nothing to do:"
say "  src/*.py            re-uploaded and re-hashed every submit"
say "  components/*.yml    read from disk every submit"
say "  pipelines/*.yml     read at submit, never stored server-side"
say "  config/*.yml        only the local scripts read it"
say ""
say "Run training when you are ready (deploy.sh never does):"
for stage in "${STAGES[@]}"; do
  say "  $PYTHON scripts/submit_pipeline.py --stage $stage"
done

if [ ${#FAILED[@]} -gt 0 ]; then
  say ""
  die "${#FAILED[@]} pipeline(s) did not validate."
fi

say ""
ok "ready"
