import {
  errorResponse,
  requireUserEmail,
  systemApiJson
} from '@/lib/system-api';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';


// ============================================================
// FILE A FINISHED VIDEO INTO THIS WEEK
//
// One request closes out the whole video: the frames worth
// keeping, the boards on them, and the prices inside each board.
// ============================================================

export async function POST(
  request: Request,
  context: RouteContext<'/api/videos/[videoId]/submit'>
) {

  try {
    const email = await requireUserEmail();

    const { videoId } = await context.params;

    const body = await request.json();

    const data = await systemApiJson(
      `/videos/${encodeURIComponent(videoId)}/submit`,
      {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json'
        },
        body: JSON.stringify({
          ...body,
          // Taken from the session, never from the client.
          savedBy: email
        })
      }
    );

    return Response.json(data);

  } catch (error) {
    return errorResponse(error);
  }
}
