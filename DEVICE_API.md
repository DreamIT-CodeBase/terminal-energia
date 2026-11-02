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

The forecourt list: coordinates, brand, and — where anyone has surveyed it —
the order fuel types appear on the price board.

Served from `configurations/stations-data/stations.json`, which the fleet also
**writes** to (section 5a), so this is the live list rather than one baked
into the image.

`fuel_types` is what resolves a detected row back to a product. The detector
reports a price per board row (`fuel_prices[].row_num`) but cannot read which
fuel that row is for, so `fuel_type` comes back as `Unknown`. The position of
a code in `fuel_types` is the row number, counting from the top.

**Headers**: `Authorization: Bearer <token>`.

**Response `200`**

```json
{
  "version": 1,
  "count": 470,
  "etag": "0x8DF122043C4C79A",
  "stations": [
    {
      "id": "surveyed:tartu-kvissentali-olerex",
      "name": "Tartu Kvissentali Olerex",
      "latitude": 58.39867,
      "longitude": 26.70944,
      "brand": "Olerex",
      "modified_time": 1789363291818,
      "fuel_types": null,
      "note": null
    }
  ]
}
```

An id carries its provenance as a prefix: `surveyed:` for a forecourt entered
by hand, `detected:` for one a device discovered, and a bare id for one that
came from the maps provider. Nothing parses the prefix — it is there for a
human reading the file.

**Client notes**

- **Never sort `fuel_types`.** The order is the board layout, not a
  presentation choice — Räpina Olerex lists `98` before `95`.
- `fuel_types` is `null` or empty for most stations, and rows there cannot be
  resolved. Only a forecourt someone has stood in front of has a sequence.
- Match a run to a station by comparing `location.latitude` / `longitude` from
  `api_response.json` against this list. Two pairs of entries sit within ~1 m
  of each other (see below), so nearest-point matching alone will not pick
  between them.
- Cache against `etag`; it changes whenever the document does. The list is
  read far more often than it is written.
- Unknown fields are passed through, not dropped — the server does not own
  this schema on its own.

---

## 5a. `PUT /v1/stations/{id}`

Create or amend one station. This is how a device records a forecourt it
stopped at that was not on the list.

The server reads the stored document, replaces the entry with this `id` — or
appends it if there is none — and writes the whole document back. Concurrent
writers are safe: the write is conditional on the etag that was read, and the
loser re-reads and reapplies rather than overwriting.

**Headers**: `Authorization: Bearer <token>`, `Content-Type: application/json`.

**Request**

```json
{
  "name": "Tartu Ringtee Neste",
  "latitude": 58.37529,
  "longitude": 26.67856,
  "brand": "Neste",
  "fuel_types": ["95", "98", "D", "MYD"],
  "note": null
}
```

Every field is optional. `id` comes from the path, and `modified_time` is
stamped by the server — a device clock is not trustworthy, and the value
records when the row was written, not when it was observed.

**Response `200`**

```json
{
  "created": false,
  "count": 470,
  "etag": "0x8DF12210A31B4C2",
  "station": { "id": "...", "name": "...", "modified_time": 1789363965115 }
}
```

**Errors**

| Status | Meaning |
| --- | --- |
| `400` | Empty id, no fields to update, or a latitude/longitude out of range |
| `401` | Missing, expired or invalid token |
| `429` | Too many concurrent writers. Transient — back off and retry |
| `502` | The stored document is not a JSON array of objects with ids |

**Client notes**

- **Fields are merged, not replaced.** Anything omitted keeps its stored
  value, so reporting a better position cannot blank out a `fuel_types`
  sequence someone surveyed by hand. To clear a field, send it explicitly.
- `created` tells you whether the station was new. Use it to decide whether
  the discovery is worth surfacing.
- Prefix a newly discovered id with `detected:` so its provenance stays
  visible in the file.
- Each call rewrites a ~90 KB document. Batch what you learn about a stop
  into one call rather than one call per field.

### Data caveats

| Station | Issue |
| --- | --- |
| `poltsamaa-jetoil`, `ahja-olerex` | Fuel sequence unknown, `fuel_types` is empty |
| `valga-olerex` | Lists `DD`, kept verbatim. Probably a typo for `D`, but not corrected on a guess |
| `puhu-risti-olerex` / `poltsamaa-olerex` | 1.3 m apart with identical sequences — likely the same forecourt under two names |
| `puhu-risti-alexela` / `poltsamaa-alexela` | 1.1 m apart with identical sequences — likewise |
| `tartu-kvissentali-circle-k` | Source latitude was `58,40682` (comma as decimal point); read as `58.40682` |


---

## 5b. `DELETE /v1/stations/{id}`

Remove one station. Same read-modify-write as the upsert, under the same etag
check, so a delete cannot silently revert someone else's concurrent change.

**Headers**: `Authorization: Bearer <token>`.

**Response `200`**

```json
{
  "deleted": true,
  "count": 469,
  "etag": "0x8DF1223F8B21A04",
  "station": {
    "id": "detected:some-forecourt",
    "name": "Some Forecourt",
    "latitude": 58.1,
    "longitude": 26.2,
    "modified_time": 1789364219715
  }
}
```

**Errors**

| Status | Meaning |
| --- | --- |
| `400` | Empty id |
| `401` | Missing, expired or invalid token |
| `404` | No station with that id. Nothing was removed |
| `429` | Too many concurrent writers. Transient — back off and retry |
| `502` | The stored document is not a JSON array of objects with ids |

**Client notes**

- A station that is not there is a `404`, not a quiet success — the caller is
  told that nothing was removed rather than being left to assume it worked.
- `station` is the entry as it stood. **Nothing else keeps a copy**, so it is
  the only way to put the row back; log it if the delete might be wrong.
- There is no soft delete and no undo. The document is overwritten.

---

## 6. `GET /v1/config`

The device configuration document: the tunables the app used to compile in.
Holding them in one blob means the fleet can be re-tuned without a release.

Served from `configurations/device-config/device-config.default.json` in blob
storage. The `config` object is passed through **unvalidated** — a new tunable
reaches the fleet by editing the blob, not by releasing the server.

**Headers**: `Authorization: Bearer <token>`.

**Response `200`**

```json
{
  "config_id": "default",
  "schema_version": 2,
  "updated_at": "2026-09-14",
  "etag": "0x8DE0F2A1B3C4D5E",
  "last_modified": "2026-09-14T05:23:44+00:00",
  "config": {
    "schema_version": 2,
    "config_id": "default",
    "device_id": "default",
    "capture": { "trigger": { "enter_radius_m": 150.0 } }
  }
}
```

`config` is the stored document verbatim; the fields beside it are the
envelope, lifted out so a device can tell one revision from another without
parsing the body.

**Errors**

| Status | Meaning |
| --- | --- |
| `401` | Missing, expired or invalid token |
| `404` | No configuration blob. Permanent — ship the defaults compiled in |
| `502` | The stored blob is not a JSON object. Transient: it may be mid-edit |

**Client notes**

- Cache against `etag`; it changes whenever the document does.
- Nothing here is safe to apply mid-capture or mid-run. A run that started
  under one set of values must finish under them, or the artifacts it writes
  describe a pipeline that never existed.
- A `404` is not a failure to recover from — fall back to the compiled-in
  defaults and carry on.
- `config_id` is `default` when the fleet-wide document is being served.


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
