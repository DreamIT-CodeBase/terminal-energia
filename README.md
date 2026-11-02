# Labelling UI

Next.js annotation tool for the fuel price detection pipeline.

- `/` — Microsoft sign-in. One account only.
- `/annotation` — the annotation workspace, backed by Azure Blob Storage.
- `/validation` — a read-only look at what the pipeline produced for a run.
- `/map` — the forecourts the fleet drives to, on an interactive map.

The UI never talks to Azure directly. Every storage read and write goes through
the FastAPI service in [`../system-api`](../system-api), which holds the storage
connection string.

```
browser  ──►  labelling-ui  ──►  system-api  ──►  Azure Blob Storage
             session cookie      x-api-key
```

## What the workspace does

Labelling runs down one video at a time, and the left sidebar is the shape of
the job: **Video → Frames → Boards**. Selecting a section decides what the
centre of the screen shows.

1. **Video** — the source `video.mp4`, with **Capture frame** at its top right.
   Scrub to a moment, or step a frame at a time with the arrow keys, and
   capture. **Spread 8** takes eight evenly spaced frames as a starting set to
   prune. Nothing is grabbed off the `<video>` element: the timestamp is sent to
   the system API, which decodes that exact frame from the stored clip at full
   quality and parks it in the working area.
2. **Frames** — everything the video offers. What the pipeline already
   extracted into `video-id/frames/` comes first, marked `pipeline`, followed
   by anything captured from the clip, named by the moment it came from.
   Opening one puts it on the canvas; every box drawn on it is a price
   **board**. A pipeline frame cannot be discarded — it is source data, and
   leaving it unlabelled already keeps it out of the dataset.
3. **Boards** — every board box across every frame. Opening one shows its frame
   clipped to the board box, and every box drawn there is a **price region**.
   The price for each region is typed in the right sidebar, one row per region.
   Leave it empty when the price cannot be read off the board.

No crop is cut while labelling. A board is its frame with a crop window applied,
so moving or resizing a board box just re-frames the view — the real crops are
produced once, on submit.

**Submit video** files the whole thing into the current ISO week as a YOLO
dataset and moves on to the next video. Until then, unsubmitted boxes and prices
survive a page reload through a local draft scoped to the video id, and captured
frames survive in the working area on the server.

A video with no `video.mp4` has nothing to capture from, so it never appears in
the queue, even if the pipeline extracted frames for it.

There is only one project, so there is no project picker or project name — the
video id is what scopes the work.

## The station map

`/map` edits the forecourts the fleet drives to — the same document the devices
read on their next fetch, so a correction here reaches the fleet without a
deploy. Three panes: every station on the left, searchable; the map in the
middle; the selected station on the right.

Marker colour is where the station came from, which its id records as a prefix:
**surveyed** (cyan) was entered by a person, **detected** (amber) was found by a
device that stopped somewhere unmapped, and grey came from the maps provider.

- **Move** — select a station and drag its marker, or type coordinates. Only the
  selected marker drags, so a stray drag cannot move a forecourt nobody is
  looking at.
- **Add** — the id follows the name (`Tartu Ringtee Neste` →
  `surveyed:tartu-ringtee-neste`) and is shown before saving, because the id is
  the key: an id that already exists would amend that station instead of adding
  one, so the panel refuses to save onto a taken id.
- **Board rows** — the `fuel_types` sequence, top to bottom, which is what
  resolves the fuel the detector cannot read off a row. It is data, not
  presentation: Räpina Olerex lists 98 before 95, so nothing sorts it.
- **Remove** — final. The API's response carries the only remaining copy of the
  entry, so the station's name has to be typed out first.

Edits are local until saved, and the write merges server-side: a device
upserting the same station between the load and the save keeps whatever fields
the panel never touched. A station with no coordinates cannot be drawn, so it
stays in the left rail marked as unplaced, which is where it gets a position.

Tiles come from OpenStreetMap and are fetched by the browser, so the page needs
outbound internet where it is opened. No API key or tile account is involved.

## Sign in

Sign-in uses Auth.js (NextAuth v5) with the Microsoft Entra ID provider, and is
restricted to `chirag@dreamitcs.com`. The address is checked twice: in the
`signIn` callback, so no session cookie is ever issued for another account, and
again in `proxy.ts` on every request to `/annotation`, `/map` and the
`/api/videos/*` and `/api/stations/*` routes.

### Azure app registration

In the Azure portal, **Microsoft Entra ID → App registrations → New
registration**:

| Field | Value |
| --- | --- |
| Supported account types | Single tenant (accounts in this organizational directory only) |
| Redirect URI (Web) | `http://localhost:3000/api/auth/callback/microsoft-entra-id` |

Add the production redirect URI too, e.g.
`https://your-host/api/auth/callback/microsoft-entra-id`.

Then create a client secret under **Certificates & secrets**, and copy:

- **Application (client) ID** → `AUTH_MICROSOFT_ENTRA_ID_ID`
- **Client secret value** → `AUTH_MICROSOFT_ENTRA_ID_SECRET`
- **Directory (tenant) ID** → into `AUTH_MICROSOFT_ENTRA_ID_ISSUER`

No API permissions beyond the default `openid profile email User.Read` are
needed.

## Running locally

Start the system API first (see [`../system-api/README.md`](../system-api/README.md)):

```bash
cd system-api
uvicorn app.main:app --reload --port 8100
```

Then the UI:

```bash
cd labelling-ui
npm install

cp .env.example .env.local     # then fill in the real values
npx auth secret                # writes AUTH_SECRET into .env.local

npm run dev
```

Open <http://localhost:3000>.

## Configuration

| Variable | Purpose |
| --- | --- |
| `AUTH_SECRET` | Signs the session cookie. Required |
| `AUTH_MICROSOFT_ENTRA_ID_ID` | Application (client) ID |
| `AUTH_MICROSOFT_ENTRA_ID_SECRET` | Client secret |
| `AUTH_MICROSOFT_ENTRA_ID_ISSUER` | `https://login.microsoftonline.com/<tenant-id>/v2.0` |
| `ALLOWED_EMAIL` | The single allowed account. Defaults to `chirag@dreamitcs.com` |
| `AUTH_URL` | Public URL of the app. Needed in production |
| `SYSTEM_API_URL` | Base URL of the FastAPI service. Defaults to `http://127.0.0.1:8100` |
| `SYSTEM_API_KEY` | Must match `SYSTEM_API_KEY` in the system API |

`SYSTEM_API_KEY` is read only on the server and is never sent to the browser.

## Internal routes

Every route below requires a valid session and simply forwards to the system API
with the service key attached.

| Route | Purpose |
| --- | --- |
| `GET /api/videos` | Video ids in `debugdirectory` |
| `GET /api/videos/[videoId]` | What the source container holds for one video |
| `GET /api/videos/[videoId]/api-response` | `api_response.json` |
| `GET /api/videos/[videoId]/file/[...path]` | Video bytes, range requests included |
| `GET /api/videos/[videoId]/probe` | Duration, frame size and frame rate |
| `GET /api/videos/[videoId]/frames` | Every frame on offer, pipeline and captured |
| `POST /api/videos/[videoId]/frames` | Capture frames at the given timestamps |
| `GET /api/videos/[videoId]/frames/[frameName]/file` | Bytes of one frame |
| `DELETE /api/videos/[videoId]/frames/[frameName]` | Discard one captured frame |
| `POST /api/videos/[videoId]/submit` | File the video into this week |
| `GET /api/stations` | Every station, with the document's etag |
| `GET /api/stations-enriched` | Enriched station fuel types for validation |
| `PUT /api/stations-enriched` | Upsert enriched fuel types by latitude/longitude |
| `PUT /api/stations/[stationId]` | Create or amend one station |
| `DELETE /api/stations/[stationId]` | Remove one station |

## Docker

```bash
docker build -t labelling-ui ./labelling-ui
docker run -p 3000:3000 --env-file ./labelling-ui/.env.local labelling-ui
```

The app runs as a Node server — it is no longer a static export, so it can no
longer be mounted as static files by the training API.
