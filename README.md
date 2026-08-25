# Labelling UI

Next.js annotation tool for the fuel price detection pipeline.

- `/` — Microsoft sign-in. One account only.
- `/annotation` — the annotation workspace, backed by Azure Blob Storage.

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
2. **Frames** — the frames captured so far. Opening one puts it on the canvas;
   every box drawn on it is a price **board**.
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
the queue.

There is only one project, so there is no project picker or project name — the
video id is what scopes the work.

## Sign in

Sign-in uses Auth.js (NextAuth v5) with the Microsoft Entra ID provider, and is
restricted to `chirag@dreamitcs.com`. The address is checked twice: in the
`signIn` callback, so no session cookie is ever issued for another account, and
again in `proxy.ts` on every request to `/annotation` and `/api/videos/*`.

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
| `GET /api/videos/[videoId]/frames` | Frames captured so far |
| `POST /api/videos/[videoId]/frames` | Capture frames at the given timestamps |
| `GET /api/videos/[videoId]/frames/[frameName]/file` | Bytes of one captured frame |
| `DELETE /api/videos/[videoId]/frames/[frameName]` | Discard one captured frame |
| `POST /api/videos/[videoId]/submit` | File the video into this week |

## Docker

```bash
docker build -t labelling-ui ./labelling-ui
docker run -p 3000:3000 --env-file ./labelling-ui/.env.local labelling-ui
```

The app runs as a Node server — it is no longer a static export, so it can no
longer be mounted as static files by the training API.
