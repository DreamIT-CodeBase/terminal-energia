import {
  errorResponse,
  requireUserEmail,
  systemApiJson
} from '@/lib/system-api';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';


// ============================================================
// FRAMES, CROPS AND PREPPED IMAGES OF ONE VIDEO
// ============================================================

export async function GET(
  _request: Request,
  context: RouteContext<'/api/videos/[videoId]'>
) {

  try {
    await requireUserEmail();

    const { videoId } = await context.params;

    const data = await systemApiJson(
      `/videos/${encodeURIComponent(videoId)}`
    );

    return Response.json(data);

  } catch (error) {
    return errorResponse(error);
  }
}
