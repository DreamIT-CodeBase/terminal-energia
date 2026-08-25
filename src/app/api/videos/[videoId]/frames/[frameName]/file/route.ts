import {
  errorResponse,
  requireUserEmail,
  systemApiRaw
} from '@/lib/system-api';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';


// ============================================================
// BYTES OF ONE CAPTURED FRAME
//
// Streamed through the session-protected server exactly like
// the source video, so no storage credential reaches the page.
// ============================================================

export async function GET(
  _request: Request,
  context: RouteContext<'/api/videos/[videoId]/frames/[frameName]/file'>
) {

  try {
    await requireUserEmail();

    const { videoId, frameName } = await context.params;

    const upstream = await systemApiRaw(
      `/videos/${encodeURIComponent(videoId)}` +
      `/frames/${encodeURIComponent(frameName)}/file`
    );

    if (!upstream.ok) {
      return Response.json(
        { error: `Frame could not be read (${upstream.status}).` },
        { status: upstream.status }
      );
    }

    return new Response(
      upstream.body,
      {
        status: 200,
        headers: {
          'Content-Type':
            upstream.headers.get('content-type') || 'image/png',
          // A capture is named after its timestamp and never changes, so the
          // same name always means the same bytes.
          'Cache-Control': 'private, max-age=3600'
        }
      }
    );

  } catch (error) {
    return errorResponse(error);
  }
}
