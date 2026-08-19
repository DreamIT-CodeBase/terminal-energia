# Labelling System API

FastAPI service that owns the platform's data-plane credentials. It does two
jobs:

1. **Labelling storage** — every Azure Blob Storage operation for the labelling
   UI. The storage connection string lives here and nowhere else; the browser
   and the Next.js app never see it.
2. **Fabric lakehouse** — stores JSON documents in OneLake, partitioned by
   vehicle and date.
3. **Device API** — brokers short-lived, single-blob SAS URLs so the storage
   account key can be removed from the Android app.

```
browser  ──►  labelling-ui (Next.js, Microsoft login)  ──►  system-api  ──►  Azure Blob Storage
                          session cookie                      x-api-key
```

## Containers

**Source — `debugdirectory`** (read only)

```
video-id/frames/          annotatable images
video-id/crops/           annotatable images
video-id/prepped/         annotatable images
video-id/api_response.json
video-id/video.mp4
```

**Destination — `debugdirectoryannotated`** (written by this API)

```
video-id/images/<folder>/<file>       copy of every annotated image
video-id/labels/<folder>/<file>.txt   YOLO labels for that image
video-id/classes.txt                  class names, one per line, index = YOLO class id
video-id/annotations.json             full payload, used to reopen a video
```

`<folder>` is `frames`, `crops` or `prepped`, so images with the same file name
in different source folders never collide.

## Labelling endpoints

Every `/videos` route requires the `x-api-key` header.

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/health` | Liveness check |
| `GET` | `/videos` | Every video id, with an `annotated` flag |
| `GET` | `/videos/{video_id}` | Images grouped by folder, plus `api_response.json` / `video.mp4` presence |
| `GET` | `/videos/{video_id}/api-response` | Parsed `api_response.json` |
| `GET` | `/videos/{video_id}/file/{blob_path}` | Raw bytes of one blob (image or video) |
| `GET` | `/videos/{video_id}/annotations` | Previously saved `annotations.json` |
| `POST` | `/videos/{video_id}/annotations` | Save annotated images and YOLO labels |

Image bytes are streamed through the API rather than handed out as SAS URLs, so
no CORS rule is needed on the storage account and access stays behind the login.

### Save payload

```json
{
  "classes": [{ "id": "class-board", "name": "board", "color": "#3B82F6" }],
  "images": [
    {
      "name": "0001.jpg",
      "path": "video-id/frames/0001.jpg",
      "folder": "frames",
      "width": 1920,
      "height": 1080,
      "annotations": [
        { "classId": "class-board", "type": "rect", "coordinates": [0.1, 0.2, 0.3, 0.4] }
      ]
    }
  ],
  "savedBy": "chirag@dreamitcs.com"
}
```

Coordinates are normalised to `0-1`. For `rect` they are `[x, y, w, h]` with
`x, y` at the top-left; the API converts them to YOLO centre form on write.


## Fabric lakehouse

JSON documents are written to OneLake at:

```
<workspace>/<lakehouse>/Files/vehicle=<vehicle>/date=<date>/<uuid>.json
```

`vehicle` and `date` are Hive-style partition keys, so Spark and Fabric can read
the partitions back without escaping games. `vehicle` must be 1-128 characters of
letters, digits, dot, underscore or hyphen, starting with a letter or digit.
`date` is an ISO date and defaults to today (UTC) when omitted. Each document
gets a fresh UUID filename, created with `If-None-Match: *` so two concurrent
writers of the same path cannot silently overwrite each other.

| Method | Path | Purpose |
| --- | --- | --- |
| `POST` | `/files` | Envelope form: `{"vehicle": ..., "date": ..., "data": {...}}`. Only `data` is stored |
| `POST` | `/files/raw` | Body stored byte for byte; partition keys come from the query string |
| `POST` | `/files/upload` | Multipart `.json` upload; partition keys from the query string |
| `GET` | `/health/fabric` | Verify credentials and lakehouse reachability |

All of them require the same `x-api-key` header as the `/videos` routes.

```bash
curl -X POST http://localhost:8100/files   -H "x-api-key: $SYSTEM_API_KEY"   -H "Content-Type: application/json"   -d '{"vehicle": "ABC123", "data": {"price": 1.85}}'
```

```bash
# The document is stored exactly as sent, so nothing rewrites it.
curl -X POST "http://localhost:8100/files/raw?vehicle=ABC123&date=2026-07-25"   -H "x-api-key: $SYSTEM_API_KEY"   -H "Content-Type: application/json"   --data-binary @document.json
```

The response carries the stored location:

```json
{
  "vehicle": "ABC123",
  "date": "2026-07-25",
  "filename": "dfe2ef8c-0317-45d6-9cd3-a64ef0cc708f.json",
  "path": "<lakehouse>/Files/vehicle=ABC123/date=2026-07-25/<uuid>.json",
  "relative_path": "vehicle=ABC123/date=2026-07-25/<uuid>.json",
  "abfss_url": "abfss://<workspace>@onelake.dfs.fabric.microsoft.com/...",
  "bytes_written": 15
}
```

Bodies over 10 MB are rejected with `413`. Invalid partition values give `422`,
malformed JSON gives `400`.

### Fabric authentication

Set `AZURE_TENANT_ID`, `AZURE_CLIENT_ID` and `AZURE_CLIENT_SECRET` for a service
principal, which needs Contributor (or at least Write) on the Fabric workspace.
Leave all three empty and the writer falls back to `DefaultAzureCredential`,
which picks up the container app's managed identity in Azure, or your `az login`
locally.


## Device API (`/v1`)

> Integrating the Android app? [`DEVICE_API.md`](DEVICE_API.md) is the
> request/response spec, with the live base URL and client notes.

The app used to hold `azure.storage.connectionString` and HMAC-sign SAS tokens
on-device, in `AzureBlobAuth`. That key covered both debug uploads *and* model
downloads, so removing it needs both paths brokered — otherwise the key stays in
the APK and nothing is gained.

These endpoints replace it. The device authenticates, then asks for a URL per
operation; the server signs it and the device talks to Azure directly.

```
device ──1── POST /v1/auth/token ──────────────► token (short-lived)
       ──2── POST /v1/runs/{id}/artifacts/{path} ► signed PUT URL
       ──3── PUT bytes ─────────────────────────► Azure Blob Storage
       ──4── POST /v1/runs/{id}/complete ───────► manifest, gaps reported
```

| Method | Path | Purpose |
| --- | --- | --- |
| `POST` | `/v1/auth/token` | Fleet secret in, short-lived bearer token out |
| `POST` | `/v1/runs/{run_id}/artifacts/{path}` | Write-only SAS for one artifact |
| `POST` | `/v1/runs/{run_id}/complete` | Declare the manifest; the response names what is missing |
| `GET` | `/v1/models/{container}/{blob}` | Read-only SAS for one model file, e.g. `yolo/board-detection-001.tflite` |

### Broker, not proxy

The bytes never pass through this service. A run is 100 MB+ of video plus 4K
frame PNGs, and proxying all of it would make the API scale with video size for
no security gain — the vulnerability was the signing key on the device, not the
PUT. So `/artifacts` returns a SAS and the app keeps its existing PUT.

Each SAS is scoped to **one blob**, with `create`+`write` only — no read, no
delete, no listing — and expires in 15 minutes. A leaked upload URL lets someone
overwrite one artifact of one run until it expires.

### One artifact endpoint, not one per type

The path already discriminates (`frames/frame_00042.png`, `ocr/f00012_d00.json`),
and the artifact set churns — crops, prepped, `run_manifest.json` and
`consolidation_review.json` have all been added at various points. Under
per-type endpoints each of those is an API change; under path-based, none are.
It also maps 1:1 onto `DebugUploader.upload(blobPath, file)` and the existing
outbox, so the worker and scheduler need no changes.

### Retry semantics

The upload worker branches on retry-vs-drop, so the status codes mean it:

| Status | Meaning |
| --- | --- |
| `4xx` (except `429`) | Permanent. The request will never succeed as sent — drop it |
| `429` | Transient. Back off and retry |
| `5xx` | Transient. Back off and retry |

Validation failures are therefore always 4xx, and anything depending on storage
being reachable surfaces as 5xx. Getting this backwards either wedges the queue
on a poison artifact or silently discards good data.

**Uploads are idempotent.** The same path with the same bytes overwrites in
place, so an outbox retry is a no-op rather than a duplicate.

### Why `complete` earns its place

Without it the server cannot tell "the run produced 14 artifacts and all
arrived" from "it produced 14 and 11 arrived" — it has no idea what it *should*
have received. The manifest turns that into a query:

```json
{
  "complete": false,
  "expected_count": 14,
  "received_count": 11,
  "missing": ["consolidation_review.json", "ocr/f00012_d00.json", "reconstructor.json"],
  "unexpected": []
}
```

It is also written to `run_id/run_manifest.json`, so a gap is visible later
rather than being stumbled on weeks afterwards.

### Devices

Authentication is a **shared fleet secret**: `FLEET_SECRET` is embedded in the
APK, and any device presenting it gets a token. Devices are not registered
individually, so provisioning a new tracker needs no server-side change.

The `deviceId` sent alongside it is a **label, not an identity**. It is recorded
on the token and in run manifests for attribution, but anything holding the
fleet secret can claim any id. It is validated for shape only — 1-128 chars of
letters, digits, dot, underscore or hyphen — so it cannot carry a path or junk
into a manifest.

An empty `FLEET_SECRET` means no device can authenticate, which fails closed.

**Revocation is fleet-wide.** There is no way to cut off one tracker:

| To do this | Rotate | Effect |
| --- | --- | --- |
| Stop new tokens being issued | `FLEET_SECRET` | Needs an app release carrying the new secret. Tokens already issued stay valid until they expire |
| Cut off every device immediately | `DEVICE_TOKEN_SECRET` | Every outstanding token becomes invalid at once; devices re-authenticate with the fleet secret they already have |

Rotate both together to revoke a leaked secret with no window.

This is a deliberate trade: no registration overhead in exchange for no
per-device revocation. If the fleet grows past a few dozen trackers, or a single
device needs cutting off without an app release, this is the piece to revisit —
per-device credentials or a provisioning endpoint backed by a registry.

This is deliberately separate from the readings endpoint the business payload
uses: same service and auth, different endpoint, because debug artifacts and
readings have different lifetimes and retention.

## Running locally

```bash
cd system-api
python -m venv .venv && .venv/Scripts/activate    # Windows
pip install -r requirements.txt

cp .env.example .env        # then fill in the real account key and API key
uvicorn app.main:app --reload --port 8100
```

Interactive docs: <http://localhost:8100/docs>

## Configuration

| Variable | Default | Purpose |
| --- | --- | --- |
| `AZURE_STORAGE_CONNECTION_STRING` | — | Required. Storage account holding both containers |
| `SOURCE_CONTAINER` | `debugdirectory` | Read-only source data |
| `ANNOTATED_CONTAINER` | `debugdirectoryannotated` | Created on first save |
| `SYSTEM_API_KEY` | — | Required. Shared key the labelling UI sends |
| `ALLOWED_ORIGINS` | `http://localhost:3000` | Comma separated CORS origins |
| `AZURE_TENANT_ID` | — | Fabric service principal. Empty falls back to managed identity |
| `AZURE_CLIENT_ID` | — | Fabric service principal |
| `AZURE_CLIENT_SECRET` | — | Fabric service principal |
| `FABRIC_WORKSPACE` | workspace GUID | From the lakehouse URL |
| `FABRIC_LAKEHOUSE` | lakehouse GUID | From the lakehouse URL |
| `FABRIC_ROOT_PATH` | `Files` | Root the `vehicle=`/`date=` partitions hang off |
| `ONELAKE_ACCOUNT_URL` | OneLake DFS endpoint | Change only for sovereign clouds |
| `DEVICE_TOKEN_SECRET` | — | Signs device tokens. Rotate to invalidate every outstanding token |
| `FLEET_SECRET` | — | Shared secret embedded in the APK. Empty disables the device API |
| `DEVICE_TOKEN_TTL_SECONDS` | `3600` | Device token lifetime |
| `ARTIFACT_SAS_TTL_SECONDS` | `900` | Upload URL lifetime |
| `MODEL_SAS_TTL_SECONDS` | `3600` | Model download URL lifetime |
| `MODEL_CONTAINERS` | `llm,yolo` | Containers the model endpoint may serve from |

## Docker

```bash
docker build -t labelling-system-api ./system-api
docker run -p 8100:8100 --env-file ./system-api/.env labelling-system-api
```

## Deploying to Azure Container Apps

```bash
cd system-api
./deploy.sh
```

The script is idempotent — run it again to ship a new build.

It builds the image inside the existing container registry of the
`terminal-scout` resource group (so no local Docker daemon is needed), creates a
Container Apps environment and container app both named `system-api`, stores
`AZURE_STORAGE_CONNECTION_STRING`, `SYSTEM_API_KEY` and `AZURE_CLIENT_SECRET`
from `.env` as Container App secrets, and polls `/health` until the new revision
serves traffic. If no service principal is set in `.env`, the Fabric variables
are left off and the app falls back to its managed identity.

The image is tagged with the current git short SHA, plus `latest`.

Registry pulls use a system-assigned managed identity with the `AcrPull` role.
If that role assignment fails for lack of permission, the script falls back to
registry admin credentials; you can force that path with `USE_ACR_ADMIN=1`.

| Override | Purpose |
| --- | --- |
| `RESOURCE_GROUP` | Target resource group. Defaults to `terminal-scout` |
| `ACR_NAME` | Which registry to use, if the group has more than one |
| `ALLOWED_ORIGINS` | CORS origins. Set this to the labelling UI's URL once it is deployed |
| `USE_ACR_ADMIN=1` | Use registry admin credentials instead of a managed identity |

Ingress is external, because the labelling UI server calls this API from
outside the environment. The `x-api-key` header is what keeps it private, so
treat `SYSTEM_API_KEY` as a real secret.

After the first deploy, point the UI at the printed URL:

```
SYSTEM_API_URL=https://system-api.<region>.azurecontainerapps.io
```

then re-run with the UI's origin so CORS allows it:

```bash
ALLOWED_ORIGINS=https://<labelling-ui-host> ./deploy.sh
```

Logs:

```bash
az containerapp logs show -n system-api -g terminal-scout --follow
```
