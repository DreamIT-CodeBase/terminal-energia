# ml-workspace — weekly YOLO training pipelines

Two Azure ML pipelines, one per detector, sharing one compute and one
environment. Each week they turn the labelling system's annotated snapshot
into a candidate model, compare it against the model currently on the devices,
and publish it only if it is actually better.

```
snapshots/<week>/<stage>/          (annotated container, written by system-api)
        │
        │  create_data_asset.py    → <stage>-annotated-week:<week>
        ▼
  ┌─────────────────┐
  │ prepare_data    │  letterbox + augment; no split (all images train)
  └────────┬────────┘
           ▼
  ┌─────────────────┐
  │ train           │  continue from the registered model  ──── MLflow
  └────────┬────────┘
           ▼
  ┌─────────────────┐
  │ evaluate        │  score BOTH models on the same data → decision.json
  └────────┬────────┘
           ▼
  ┌─────────────────┐
  │ export_tflite   │  int8, calibrated on this week's data   (skipped if not promoted)
  └────────┬────────┘
           ▼
  ┌─────────────────┐
  │ publish_model   │  → model container + workspace registry
  └─────────────────┘
```

## Layout

| Path | What it is |
|---|---|
| `config/workspace.yml` | **The only file you must edit.** Subscription, RG, workspace, compute, datastore names. |
| `environments/` | The one shared environment: `conda.yml` + the AzureML spec. |
| `components/` | Five reusable command components, one per step. |
| `pipelines/` | `board_pipeline.yml`, `price_region_pipeline.yml`. Hyperparameters live here. |
| `src/` | The step scripts. Uploaded whole as each component's snapshot. |
| `src/common/` | Image/label IO, the letterbox, the augmentation set, MLflow helpers. |
| `scripts/` | Operational entry points you run from your machine. |
| `deploy.sh` | Sync + verify: preflight, rebuild the environment if it changed, validate both pipelines against the workspace. Never submits training. |

## One-time setup

```powershell
# 1. Fill in config/workspace.yml (6 values marked REPLACE_ME).

# 2. Build the shared environment and verify everything lines up.
#    Wait for the image build to go green in ML Studio → Environments
#    before step 4.
./deploy.sh

# 3. Stage the cold-start weights. Needs AZURE_STORAGE_CONNECTION_STRING.
$env:AZURE_STORAGE_CONNECTION_STRING = "..."
python scripts/stage_base_weights.py

# 4. First run of each pipeline. Nothing is registered yet, so both
#    cold-start from the staged weights and promote unconditionally.
python scripts/submit_pipeline.py --stage board
python scripts/submit_pipeline.py --stage price_region
```

## Every week

```powershell
python scripts/submit_pipeline.py --stage board
python scripts/submit_pipeline.py --stage price_region
```

Defaults to the current UTC ISO week. Add `--week 2026-W34` to backfill,
`--dry-run` to see exactly what would be submitted without submitting it.

## What each stage trains on

The two detectors are not two configurations of the same thing — they see
different images, so they get different sizes.

| | board | price_region |
|---|---|---|
| Input images | full camera frames | board crops from stage 1 |
| Class name | `board` | `price region` |
| **Colour** | **colour** (`grayscale_prob: 0.0`) | **grayscale** (`grayscale_prob: 1.0`) |
| `imgsz` | 960 | 640 |
| `epochs` | 100 | 50 |
| Data asset | `board-annotated-week` | `price-region-annotated-week` |
| Registry name | `board-detector` | `price-region-detector` |

The colour split is not a tuning choice, it mirrors what the device does at
inference time. Board detection runs on the raw colour frame, so the board
detector trains in colour. The device then grayscales each board crop before
running price-region detection, so that detector trains on grayscale — feeding
it colour would train it on a distribution it never sees in production.

The class names are taken verbatim from `STAGE_CLASS_NAMES` in
[../system-api/app/config.py](../system-api/app/config.py) — including the
space in `price region` — so the pipeline trains the same class string the
labelling system wrote into the snapshot.

## Where the promoted model lands

Inside the model container (the one `MODEL_CONTAINERS` in system-api exposes
as `yolo`):

```
detectors/<stage>/latest.json              ← manifest: version, metrics, sha256
detectors/<stage>/latest_int8.tflite       ← what the app downloads
detectors/<stage>/latest.pt                ← next week's starting point
detectors/<stage>/versions/<week>/...      ← immutable copy, for rollback
```

The app already has a working path to these — `GET /v1/models/{name}` in
[../system-api/app/routers/devices.py](../system-api/app/routers/devices.py)
brokers a read-only SAS for `yolo/detectors/price_region/latest_int8.tflite`.

**Still to do (the fourth item on your list):** a `/v1/models/latest` endpoint
that reads `detectors/<stage>/latest.json` and returns the version, size and
sha256, so the app can tell whether it needs to re-download. `latest.json` is
written in exactly the shape that endpoint needs — paths are already relative
to the container, matching how the device addresses a model.

## Design notes

**No validation split, for now.** `val_split: 0.0` — every annotated image
trains, and validation is expected to arrive from a separate datastore rather
than being carved out of the week. Ultralytics still needs a `val` target to
run, so `data.yaml` points it at the train images, which is the same
placeholder the labelling system writes into its own snapshot `data.yaml`.

`prepare_data` records `held_out_validation: false` in `stats.json`, and
`evaluate` reads it and refuses to present the comparison as evidence. See
*Validation is not wired up yet* below for what that costs.

The split machinery is intact, not deleted: pass `--val_split 0.2` and you get
a held-out split again, taken on the *originals* before augmentation (splitting
afterwards would put variants of one source image on both sides, and
validation would be scoring near-duplicates of training data).

**Why both models are re-scored in `evaluate`.** The deployed model's recorded
metrics were measured on whatever data its own week used. Comparing that number
against one from this week would be comparing scores from two different exams,
so both models are run against the same data here — whatever that data
currently is.

**Why a margin, not just "better".** Run-to-run variance on a small weekly
dataset is easily a few thousandths of mAP. Swapping the model the whole fleet
downloads on that is churn, not progress. Default: `mAP50-95` must improve by
`≥ 0.005`. Change `margin` in the pipeline YAML, or pass `--margin` for one run.

**Why data asset creation is a script, not a step.** An Azure ML pipeline
resolves its input assets when the job is *created*, not while it runs — a
component that registered an asset could not then feed it to the training step
in the same job. `submit_pipeline.py` registers it and pins the exact version,
so you can always tell which week's data a run consumed.

## Compute is CPU-only — measured, not guessed

The compute is `terminal-scout-train`, a **ComputeInstance on Standard_E4ds_v4:
4 vCPU / 32 GiB**. Azure vCPUs are hyperthreads, so that is **2 physical
cores**. Both pipelines pin `device: cpu`.

Measured YOLO11n forward+backward on a 16-core box, one process per thread
count, batch 8:

| threads | 640 px s/img | speedup | 960 px s/img |
|---|---|---|---|
| 1 | 3.49 | 1.0x | 7.59 |
| 2 | 1.93 | 1.8x | 4.40 |
| 4 | 1.12 | 3.1x | 1.75 |
| 8 | 1.01 | **3.5x (peak)** | 0.73* |
| 16 | 1.14 | 3.1x | 1.25 |
| 22 | 1.23 | 2.8x | 1.68 |

\* anomalous — almost certainly turbo/thermal variance, since 960 px cannot
genuinely beat 640 px. Treat the *shape* as the result, not that cell.

**Throughput peaks around 8 threads and then goes backwards.** 22 threads is
slower than 4. Cores are not the lever: torch already spreads work across them
(it defaults its thread pool to the core count, and Ultralytics does not
override it), but these convolutions are memory-bandwidth bound, `yolo11n` is
the *nano* model so each layer carries too little work to amortise its
thread-join barrier, and BatchNorm/SiLU/concat/upsample are pure bandwidth with
no arithmetic to parallelise. FP32 also means no AMX/bf16 acceleration.

Since the compute is 2 physical cores, the honest reference row is **4
threads**: 1.75 s/img at 960, 1.12 s/img at 640.

### What that means for a week

Today's snapshot holds **16 annotated images per stage** (2 finished videos), so
after a 20% val split and 6x augmentation a train epoch is ~91 images:

| | today (16 originals) | at 100 originals | at 200 |
|---|---|---|---|
| board, 960 px, 50 ep | **~2.2 h** | ~13.6 h | ~27 h |
| price_region, 640 px, 50 ep | **~1.4 h** | ~8.7 h | ~17 h |

At current volume this fits comfortably. It stops fitting a weekly cadence
somewhere around 100-150 originals per stage — at which point the levers are
`imgsz` 960 -> 640 for board (~1.6x), `per_image` 6 -> 2 (~2.3x), or moving to
a GPU compute, which would be roughly 20-50x and make the question disappear.

`workers: -1` resolves to **2** on this 4-vCPU instance — which is exactly why
it is computed at runtime rather than hardcoded. A hardcoded 16 would have
oversubscribed 2 physical cores. Ultralytics clamps it again against the batch
*count*, so with batch 16 over ~91 images (6 batches) the effective ceiling is
6 anyway.

Batch 16 at 960 px is ~177 MB of input tensor before activations; 32 GiB is
ample.

### Validation is not wired up yet

`val_split: 0.0`, pending a separate validation datastore. Three consequences,
none of which stop a run:

1. **The promotion gate is not measuring generalisation.** Both models are
   scored on the training images, so the newly trained one is being tested on
   data it was just fitted to. It will win essentially every week, by a margin
   that means nothing. `evaluate` prints a banner saying so and stamps
   `held_out_validation: false` / `metrics_trustworthy: false` into
   `decision.json`, so a promotion is never silently presented as evidence.
   In practice this makes the pipeline "always ship the newest model" until a
   validation source exists.

2. **`best.pt` is chosen on training fitness.** Ultralytics selects best-epoch
   weights by validation score; with `val` pointing at the train set, that
   score climbs monotonically, so `best.pt` tends to be the last epoch. There
   is no early-stopping signal either — which is partly why `patience: 0`.

3. **Nothing detects overfitting.** With 6x augmentation on a small week this
   is a real risk, and currently invisible.

When the validation datastore arrives, wiring it in is small: add it as a
second `uri_folder` input on `prepare_data`, copy it into `val/`, and point
`write_data_yaml(val_relative=...)` back at `val/images`. `evaluate` needs no
change — it keys off `held_out_validation` in `stats.json`.

**One shared environment.** Torch and TensorFlow are in one image, per your
choice. The pins in `environments/conda.yml` are load-bearing — `numpy` is
squeezed from both sides. If it ever stops resolving, give
`components/export_tflite.yml` its own environment; nothing else depends on
them sharing an image.

## Propagating local changes to the workspace

Most of this repo needs no "deploy" step — the pipeline YAML references
components by **local path** (`component: ../components/train.yml`), and those
reference code by local path (`code: ../src`). Azure ML therefore re-reads and
re-uploads them on every submit, registering an anonymous component version
keyed by a content hash. Change a `.py` file, submit, and the new code runs.

| You changed | What to do |
|---|---|
| `src/*.py` | **Nothing.** Re-snapshotted and re-hashed on every submit. |
| `components/*.yml` | **Nothing.** Read from disk at submit time. |
| `pipelines/*.yml` | **Nothing.** Read at submit; never stored server-side. |
| `config/workspace.yml` | **Nothing.** Only the local scripts read it. |
| `environments/conda.yml` | **Run `python scripts/create_environment.py`**, then wait for the image build to go green in Studio → Environments. Components pin `@latest`, so the next submit picks it up. |
| A new week of annotations | `submit_pipeline.py` registers the data asset version for you. |

Two things that will surprise you if you don't know them:

**Step reuse.** Azure ML caches step outputs keyed on the component hash plus
its inputs. Edit only `evaluate.py`, and `prepare_data` and `train` come back
instantly as *"reused"* while `evaluate` onward re-runs — which is exactly what
you want, and makes iterating on the later steps cheap. Resubmit with nothing
changed and the whole pipeline is reused. To force a genuine re-run, add to the
pipeline's `settings:`

```yaml
  force_rerun: true
```

**Don't register the components by name.** If you ever run `az ml component
create` and switch the pipelines to `component: azureml:yolo_train@latest`,
automatic propagation stops: you would have to bump `version:` in the component
YAML by hand on every code change, and forgetting means silently running stale
code. The local-path form avoids that entirely — the version bumps in the
component YAMLs are only there for when you *want* a named, pinned version.

### Why the Studio looks empty before the first run

A pipeline YAML is **not an asset** in Azure ML v2 — it is a job definition
read from disk at submit time. So until you submit something:

| Studio section | what you see |
|---|---|
| Jobs → Pipeline jobs | *empty* — nothing has run |
| Assets → Components | *empty* — see below |
| Assets → Data | the week's data assets |
| Assets → Environments | `yolo-pipeline-env` |

Components are empty **by design**, and it is the flip side of the local-path
choice above: because the pipelines reference `component: ../components/x.yml`,
Azure ML registers each step as an *anonymous*, hash-keyed component version at
submit time. Anonymous versions do not appear in the asset list. That is
precisely what makes a `.py` edit go live with no deploy step — the cost is
that there is nothing to browse until a job exists.

`deploy.sh` validating successfully is therefore not the same as "the pipeline
is now in the workspace". Validation asks the service to check the job and
deliberately stores nothing. It proves the workspace *accepts* the definition —
compute, datastores, data asset versions, environment all resolve — which is
the useful part.

On the first `submit_pipeline.py` run, the pipeline job appears under
**Jobs → Pipeline jobs** with the full step graph, and each step exposes its
anonymous component and code snapshot.

If you ever do want the components browsable as named assets, register them and
point the pipelines at `azureml:<name>:<version>` — but then every code change
needs a version bump, and forgetting one silently runs stale code. Worth it for
a shared, audited pipeline; not worth it while iterating.

Also note `src/.amlignore` controls what actually gets uploaded in the code
snapshot. The repo-root `.amlignore` does **not** apply, because `code: ../src`
makes `src/` the snapshot root.

### deploy.sh

`./deploy.sh` is the one command that covers all of the above. It does three
things and deliberately never submits a training job — deploying and training
are separate decisions, and training here costs hours of CPU.

1. **Preflight** (`scripts/preflight.py`) — local, no credentials, no network.
   Checks every `.py` compiles, every `.yml` parses, each pipeline's job inputs
   match the component contract they invoke, every `${{parent.*}}` reference
   resolves, each component's command line only passes flags its script
   accepts, and the config has no placeholders left. Also asserts the
   decisions that nothing downstream would catch: `device: cpu`, board in
   colour, price-region in grayscale, class names matching `STAGE_CLASS_NAMES`.
2. **Environment**, only if it changed. Each version is stamped with a hash of
   `conda.yml` plus the base image; if the stamp matches, the build is skipped.
   The stamp lives on the environment, not in a local dotfile, so it is right
   from any machine.
3. **Validate** — `ml_client.jobs.validate()` on each pipeline. This is the
   part `--dry-run` cannot do: it catches a data asset version that does not
   exist, a wrong compute name, an unattached datastore, an environment that
   never finished building.

```powershell
./deploy.sh                  # all three, both pipelines
./deploy.sh --check          # preflight only — no Azure, no changes
./deploy.sh --stage board    # one pipeline
./deploy.sh --force-env      # rebuild the environment even if unchanged
./deploy.sh --skip-validate   # sync only
```

Exit code is non-zero if anything fails, so it drops straight into CI. Set
`PYTHON=/path/to/python` if `python` is not the interpreter you want.

Or check a single pipeline by hand:

```powershell
python scripts/submit_pipeline.py --stage board --dry-run    # resolve locally
python scripts/submit_pipeline.py --stage board --validate   # ask the workspace
```

## Useful flags

| Flag | Why |
|---|---|
| `--dry-run` | Print the fully resolved job without submitting. |
| `--week 2026-W34` | Backfill, or re-run a specific week. |
| `--new-data-version` | More videos were annotated into an already-registered week. |
| `--epochs`, `--imgsz`, `--batch`, `--margin`, `--per-image`, `--device` | One-off overrides. |
| `--data-asset-version 2026-W34` | Reuse an existing asset version instead of registering one. |
| `--no-augment` | Skip offline augmentation (fast smoke test). |
| `--force-cold-start` | Reset a stage: retrain from base weights, promote unconditionally. |
| `--no-wait` | Submit and return instead of streaming logs. |
| `--reconcile-registry` | Register the published copy if the in-job registration didn't land. |
| `--no-register` | Publish to the model container but leave the registry alone. |

## Troubleshooting

**`prepare_data`: "No images found"** — the week has no *finished* videos. A
video only enters a snapshot once **both** labelling passes are submitted;
until then it sits under `_in_progress/`.

**"The validation split is empty"** — the week has one image or fewer. Nothing
to compare against, so the run stops rather than publishing an unvalidated
model.

**`publish_model`: "no .tflite was produced"** — the export chain failed but
the model was promoted. Deliberately fatal: the app cannot load a `.pt`, and
publishing weights the fleet can't use would look like a successful release.

**Registration warnings in `publish_model`** — the model container was still
updated, so the release went out. Run `--reconcile-registry` to catch the
registry up.
