import {
  errorResponse,
  requireUserEmail,
  systemApiRaw
} from '@/lib/system-api';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';


// ============================================================
// BYTES OF ONE BOARD CROP
//
// Stage 2 draws on these, so they are streamed through the
// session-protected server exactly like the source frames.
// ============================================================

export async function GET(
  _request: Request,
  context: RouteContext<'/api/videos/[videoId]/crop/[cropName]'>
) {

  try {
    await requireUserEmail();

    const { videoId, cropName } = await context.params;

    const upstream = await systemApiRaw(
      `/videos/${encodeURIComponent(videoId)}` +
      `/crop/${encodeURIComponent(cropName)}`
    );

    if (!upstream.ok) {
      return Response.json(
        { error: `Crop could not be read (${upstream.status}).` },
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
          'Cache-Control': 'private, max-age=3600'
        }
      }
    );

  } catch (error) {
    return errorResponse(error);
  }
}
