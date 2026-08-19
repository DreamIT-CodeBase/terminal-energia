import {
  errorResponse,
  requireUserEmail,
  systemApiRaw
} from '@/lib/system-api';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';


// ============================================================
// IMAGE AND VIDEO BYTES
//
// Streamed through the session-protected server so the browser
// never needs storage credentials, a SAS token, or a CORS rule
// on the storage account.
// ============================================================

export async function GET(
  request: Request,
  context: RouteContext<'/api/videos/[videoId]/file/[...path]'>
) {

  try {
    await requireUserEmail();

    const { videoId, path } = await context.params;

    const blobPath = path
      .map(segment => encodeURIComponent(segment))
      .join('/');

    const upstream = await systemApiRaw(
      `/videos/${encodeURIComponent(videoId)}/file/${blobPath}`,
      // video.mp4 is fetched in ranges so playback starts immediately and
      // seeking does not re-download the whole clip.
      request.headers.get('range')
    );

    if (!upstream.ok) {

      return Response.json(
        { error: `Blob could not be read (${upstream.status}).` },
        { status: upstream.status }
      );
    }

    const headers = new Headers({
      'Content-Type':
        upstream.headers.get('content-type') ||
        'application/octet-stream',
      'Cache-Control': 'private, max-age=3600'
    });

    // Range metadata has to survive the hop, or the browser cannot seek.
    for (const header of [
      'accept-ranges',
      'content-range',
      'content-length'
    ]) {
      const value = upstream.headers.get(header);
      if (value) headers.set(header, value);
    }

    return new Response(
      upstream.body,
      {
        // 206 for a partial response, 200 for a whole blob.
        status: upstream.status,
        headers
      }
    );

  } catch (error) {
    return errorResponse(error);
  }
}
