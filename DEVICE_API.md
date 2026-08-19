# Device API — integration spec

Everything the Android app needs to stop holding the storage account key.

**Base URL**

```
https://systemapi.yellowdesert-82b2825f.uksouth.azurecontainerapps.io
```

**Fleet secret** — the one credential the APK embeds. Any device presenting it
can authenticate; there is no per-device registration.

The value is deliberately **not** in this file, because this file is in git.
Read it from the deployment:

```bash
az containerapp secret show -n systemapi -g terminal-scout --secret-name fleet-secret --query value -o tsv
```

or from `system-api/.env` locally (gitignored).

## What changes in the app

| Before | After |
| --- | --- |
| `azure.storage.connectionString` in the APK | `FLEET_SECRET` only |
| `AzureBlobAuth` signs SAS on-device | **Delete it.** The server signs |
| `DebugUploader.upload(blobPath, file)` PUTs to a self-signed URL | Same PUT, to a URL from endpoint 2 |
| `ModelDownloadManager` builds its own blob URL | GETs a URL from endpoint 4 |
| `DebugUploadOutbox` / worker / scheduler | **Unchanged** — same one-blob-per-item granularity |

The server never sees artifact bytes. It hands out a short-lived URL scoped to
a single blob and the device talks to Azure directly, so the API does not scale
with video size.

---

## 1. `POST /v1/auth/token`

Exchange the fleet secret for a short-lived bearer token. Every other endpoint
requires it. Without this the upload API would be an open relay.

**Request**

```json
{ "deviceId": "IOT-TRK-0A9D75", "deviceSecret": "<fleet secret>" }
```

`deviceId` is a **label, not a credential**. It is recorded on the token and in
run manifests for attribution. Must be 1–128 chars of letters, digits, dot,
underscore or hyphen, starting with a letter or digit.

**Response `200`**

```json
{
  "access_token": "eyJhbGciOiJIUzI1NiIs...",
  "token_type": "Bearer",
  "expires_in": 3600,
  "expires_at": "2026-08-18T13:25:06.382292Z"
}
```

**Errors**

| Code | Meaning |
| --- | --- |
| `400` | Malformed `deviceId` |
| `401` | Wrong fleet secret |
| `422` | Missing field |

**Client notes**

- Cache the token and reuse it for its full hour; don't fetch one per upload.
- Refresh on `401`, then retry the original request **once**. A second `401`
  means the fleet secret is wrong — stop, don't loop.
- Refresh slightly early (say at 55 min) to avoid a race mid-upload.

---

## 2. `POST /v1/runs/{runId}/artifacts/{path}`

Get a write-only URL for **one** artifact. This is the workhorse — call it once
per file, immediately before uploading that file.

There is deliberately **one endpoint, not one per artifact type**. The path
already discriminates, so new artifact kinds need no API change.

- `runId` — the run/video id, e.g. `20260818-1200-abcd`. No slashes.
- `path` — path within the run, e.g. `frames/frame_00042.png`,
  `ocr/f00012_d00.json`, `video.mp4`, `crops/crop_0.png`.

**Headers**: `Authorization: Bearer <token>`. No body.

**Response `200`**

```json
{
  "run_id": "20260818-1200-abcd",
  "artifact_path": "frames/frame_00042.png",
  "blob_path": "20260818-1200-abcd/frames/frame_00042.png",
  "container": "debugdirectory",
  "upload_url": "https://memoryterminalscout.blob.core.windows.net/debugdirectory/20260818-1200-abcd/frames/frame_00042.png?se=...&sp=cw&sv=...&sr=b&sig=...",
  "method": "PUT",
  "required_headers": { "x-ms-blob-type": "BlockBlob" },
  "expires_at": "2026-08-18T12:40:07.591024Z"
}
```

**Then upload the bytes yourself:**

```
PUT <upload_url>
x-ms-blob-type: BlockBlob
Content-Type: <mime type of the file>
<raw file bytes>
```

Azure returns `201 Created` on success. **`x-ms-blob-type: BlockBlob` is
mandatory** — Azure rejects the PUT without it.

**Errors**

| Code | Meaning |
| --- | --- |
| `400` | Bad `runId` or path (traversal, empty, `//`) |
| `401` | Missing, expired or invalid token |

**Client notes**

- The URL is valid for **15 minutes**. Request it immediately before the PUT,
  never cache it across retries — an outbox item retried an hour later must get
  a fresh URL.
- The SAS grants `create`+`write` on that one blob only. No read, no delete,
  no listing.
- **Uploads are idempotent.** Same path + same bytes overwrites in place, so an
  outbox retry is a no-op, not a duplicate.

---

## 3. `POST /v1/runs/{runId}/complete`

Declare what the run produced, once every artifact has been uploaded.

Without this the server cannot tell "the run produced 14 artifacts and all
arrived" from "it produced 14 and 11 arrived" — it has no idea what it *should*
have received. This turns that into a query.

**Request**

```json
{
  "artifacts": [
    { "path": "video.mp4", "sizeBytes": 46416854 },
    { "path": "frames/frame_00000.png" },
    { "path": "ocr/f00012_d00.json" },
    { "path": "api_response.json" }
  ],
  "deviceId": "IOT-TRK-0A9D75"
}
```

`sizeBytes` and `deviceId` are optional. List **every** artifact the run
produced, including ones whose upload failed — that is the point.

**Response `200`**

```json
{
  "run_id": "20260818-1200-abcd",
  "complete": false,
  "expected_count": 14,
  "received_count": 11,
  "missing": ["consolidation_review.json", "ocr/f00012_d00.json", "reconstructor.json"],
  "unexpected": [],
  "completed_at": "2026-08-18T12:40:07Z"
}
```

`complete: true` with an empty `missing` means everything arrived. The same
payload is written to `{runId}/run_manifest.json`, so gaps stay visible later.

**Errors**

| Code | Meaning |
| --- | --- |
| `400` | Empty manifest, or an invalid path in it |
| `401` | Missing, expired or invalid token |
| `503` | Storage unreachable — retry |

**Client notes**

- Call it once per run, after the outbox drains.
- A non-empty `missing` is **not** an error response — it's a successful `200`
  reporting a gap. Log it, and consider re-queuing those artifacts.
- Safe to call again; it overwrites the manifest.

---

## 4. `GET /v1/models/{container}/{blob}`

Get a read-only URL for one model file. This is the endpoint that lets the
storage key leave the APK entirely — `ModelDownloadManager` used the same key as
the uploader, so brokering uploads alone would have left it in place.

**Available models**

| Path | Size |
| --- | --- |
| `llm/gemma-4-e2b-001.litertlm` | 2468.2 MB |
| `llm/gemma-4-e4b-001.litertlm` | 3490.0 MB |
| `yolo/board-detection-001.tflite` | 10.1 MB |
| `yolo/price-region-002.tflite` | 10.1 MB |

**Headers**: `Authorization: Bearer <token>`.

**Response `200`**

```json
{
  "name": "yolo/board-detection-001.tflite",
  "blob_path": "board-detection-001.tflite",
  "container": "yolo",
  "download_url": "https://memoryterminalscout.blob.core.windows.net/yolo/board-detection-001.tflite?se=...&sp=r&sv=...&sr=b&sig=...",
  "size_bytes": 10606700,
  "content_type": "application/octet-stream",
  "expires_at": "2026-08-18T13:25:08.185557Z"
}
```

Then `GET <download_url>` for the bytes — no auth header, the SAS carries it.

**Errors**

| Code | Meaning |
| --- | --- |
| `400` | Not in `<container>/<blob>` form |
| `404` | Unknown container (only `llm` and `yolo` are reachable) or missing blob |
| `401` | Missing, expired or invalid token |

**Client notes**

- Use `size_bytes` for progress and to skip a download you already have.
- The URL is valid for **1 hour**. A 2.4 GB Gemma download on a slow link can
  outlast that — support HTTP range resume, and on `403` (expired) request a
  fresh URL and continue from the byte offset rather than restarting.

---

---

## 5. `GET /v1/stations`

The forecourt mapping: coordinates, and the order fuel types appear on each
price board.

This is what resolves a detected row back to a product. The detector reports a
price per board row (`fuel_prices[].row_num`) but cannot read which fuel that
row is for, so `fuel_type` comes back as `Unknown`. The position of a code in
`fuel_types` is the row number, counting from the top.

**Headers**: `Authorization: Bearer <token>`.

**Response `200`**

```json
{
  "version": 1,
  "count": 41,
  "stations": [
    {
      "id": "tartu-ringtee-neste",
      "name": "Tartu Ringtee Neste",
      "latitude": 58.37529,
      "longitude": 26.67856,
      "fuel_types": ["95", "98", "D", "MyD"],
      "note": null
    }
  ]
}
```

So at Tartu Ringtee Neste, row 1 is `95`, row 2 is `98`, row 4 is `MyD`.

**Client notes**

- **Never sort `fuel_types`.** The order is the board layout, not a
  presentation choice — Räpina Olerex lists `98` before `95`.
- An empty `fuel_types` means the sequence is not known for that station;
  rows there cannot be resolved.
- Match a run to a station by comparing `location.latitude` / `longitude` from
  `api_response.json` against this list. Two pairs of entries sit within ~1 m
  of each other (see below), so nearest-point matching alone will not pick
  between them.
- The list is small and changes rarely — fetch it once per session and cache.

### Data caveats

| Station | Issue |
| --- | --- |
| `poltsamaa-jetoil`, `ahja-olerex` | Fuel sequence unknown, `fuel_types` is empty |
| `valga-olerex` | Lists `DD`, kept verbatim. Probably a typo for `D`, but not corrected on a guess |
| `tartu-ringtee-neste` / `peetri-neste` | Spell the same product `MyD` and `MYD` |
| `puhu-risti-olerex` / `poltsamaa-olerex` | 1.3 m apart with identical sequences — likely the same forecourt under two names |
| `puhu-risti-alexela` / `poltsamaa-alexela` | 1.1 m apart with identical sequences — likewise |
| `tartu-kvissentali-circle-k` | Source latitude was `58,40682` (comma as decimal point); read as `58.40682` |


## Retry semantics

`DebugUploadWorker` already branches on retry-vs-drop, so the API means it:

| Status | Action |
| --- | --- |
| `4xx` except `429` | **Permanent.** Will never succeed as sent — drop the item |
| `429` | **Transient.** Back off and retry |
| `5xx` | **Transient.** Back off and retry |

Getting this backwards either wedges the queue on a poison artifact or silently
discards good data.

`401` is the one to special-case: refresh the token and retry once before
treating it as permanent.

## End-to-end sequence

```
1.  POST /v1/auth/token                                  -> token (cache 1h)
2.  for each artifact:
      POST /v1/runs/{runId}/artifacts/{path}             -> upload_url
      PUT  <upload_url>  (x-ms-blob-type: BlockBlob)     -> 201
3.  POST /v1/runs/{runId}/complete  {artifacts: [...]}   -> missing: []
```

## Security notes

- The fleet secret is recoverable from the APK by anyone who wants it. That is
  accepted: it is revocable and only buys scoped, minutes-long SAS URLs, unlike
  the account key it replaces.
- Revocation is **fleet-wide** — there is no way to cut off one device without
  an app release.
- Do not log `upload_url` or `download_url` in full; the `sig` parameter is a
  live credential until it expires.
