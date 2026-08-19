import {
  errorResponse,
  requireUserEmail,
  systemApiJson
} from '@/lib/system-api';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';


// The two passes of the flow, named as the system API expects them.
const STAGES = ['board', 'price_region'];


// ============================================================
// BOXES ALREADY SUBMITTED FOR ONE STAGE
//
// Read when a video is reopened, so previously drawn boxes come
// back onto the canvas instead of starting from blank.
// ============================================================

export async function GET(
  _request: Request,
  context: RouteContext<'/api/videos/[videoId]/stage/[stage]/annotations'>
) {

  try {
    await requireUserEmail();

    const { videoId, stage } = await context.params;

    if (!STAGES.includes(stage)) {
      return Response.json(
        { error: `Unknown stage '${stage}'.` },
        { status: 400 }
      );
    }

    const data = await systemApiJson(
      `/videos/${encodeURIComponent(videoId)}/stage/${stage}/annotations`
    );

    return Response.json(data);

  } catch (error) {
    return errorResponse(error);
  }
}
