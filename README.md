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

1. Lists every `video-id/` folder in the `debugdirectory` container.
2. For the selected video, loads the images in `frames/`, `crops/` or
   `prepped/`, switchable from the tabs above the image list.
3. Shows `api_response.json` for the video in the right sidebar.
4. Restores annotations already saved for that video, so a video can be
   reopened and corrected. Unsaved work survives a page reload through a local
   draft scoped to the video id.
5. **Save to Azure** writes the annotated images and their YOLO labels to the
   `debugdirectoryannotated` container under `video-id/`.

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
| `GET /api/videos/[videoId]` | Images grouped by folder |
| `GET /api/videos/[videoId]/api-response` | `api_response.json` |
| `GET /api/videos/[videoId]/file/[...path]` | Image bytes for the canvas |
| `GET /api/videos/[videoId]/annotations` | Previously saved annotations |
| `POST /api/videos/[videoId]/annotations` | Save images and YOLO labels |

## Docker

```bash
docker build -t labelling-ui ./labelling-ui
docker run -p 3000:3000 --env-file ./labelling-ui/.env.local labelling-ui
```

The app runs as a Node server — it is no longer a static export, so it can no
longer be mounted as static files by the training API.
