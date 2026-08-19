import {
  errorResponse,
  requireUserEmail,
  systemApiJson
} from '@/lib/system-api';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';


// Only the two passes of the flow are forwarded; anything else is rejected
// here rather than handed to the system API.
const STAGES = ['board', 'price-region'];


// ============================================================
// SUBMIT ONE STAGE
// ============================================================

export async function POST(
  request: Request,
  context: RouteContext<'/api/videos/[videoId]/stage/[stage]'>
) {

  try {
    const email = await requireUserEmail();

    const { videoId, stage } = await context.params;

    if (!STAGES.includes(stage)) {
      return Response.json(
        { error: `Unknown stage '${stage}'.` },
        { status: 400 }
      );
    }

    const body = await request.json();

    const data = await systemApiJson(
      `/videos/${encodeURIComponent(videoId)}/stage/${stage}`,
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
