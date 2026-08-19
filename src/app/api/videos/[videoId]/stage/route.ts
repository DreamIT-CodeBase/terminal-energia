import {
  errorResponse,
  requireUserEmail,
  systemApiJson
} from '@/lib/system-api';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';


// ============================================================
// WHICH LABELLING STAGE A VIDEO IS ON
// ============================================================

export async function GET(
  _request: Request,
  context: RouteContext<'/api/videos/[videoId]/stage'>
) {

  try {
    await requireUserEmail();

    const { videoId } = await context.params;

    const data = await systemApiJson(
      `/videos/${encodeURIComponent(videoId)}/stage`
    );

    return Response.json(data);

  } catch (error) {
    return errorResponse(error);
  }
}
