import {
  errorResponse,
  requireUserEmail,
  systemApiJson
} from '@/lib/system-api';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';


// ============================================================
// FRAMES CAPTURED SO FAR
// ============================================================

export async function GET(
  _request: Request,
  context: RouteContext<'/api/videos/[videoId]/frames'>
) {

  try {
    await requireUserEmail();

    const { videoId } = await context.params;

    const data = await systemApiJson(
      `/videos/${encodeURIComponent(videoId)}/frames`
    );

    return Response.json(data);

  } catch (error) {
    return errorResponse(error);
  }
}


// ============================================================
// CAPTURE FRAMES AT THE GIVEN TIMESTAMPS
//
// The browser sends moments, not pixels: the system API decodes
// the exact frames from the stored video and parks them in the
// working area.
// ============================================================

export async function POST(
  request: Request,
  context: RouteContext<'/api/videos/[videoId]/frames'>
) {

  try {
    await requireUserEmail();

    const { videoId } = await context.params;

    const body = await request.json();

    const data = await systemApiJson(
      `/videos/${encodeURIComponent(videoId)}/frames`,
      {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json'
        },
        body: JSON.stringify(body)
      }
    );

    return Response.json(data);

  } catch (error) {
    return errorResponse(error);
  }
}
