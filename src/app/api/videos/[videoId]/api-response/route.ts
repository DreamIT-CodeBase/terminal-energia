import {
  errorResponse,
  requireUserEmail,
  systemApiJson
} from '@/lib/system-api';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';


// ============================================================
// video-id/api_response.json
// ============================================================

export async function GET(
  _request: Request,
  context: RouteContext<'/api/videos/[videoId]/api-response'>
) {

  try {
    await requireUserEmail();

    const { videoId } = await context.params;

    const data = await systemApiJson(
      `/videos/${encodeURIComponent(videoId)}/api-response`
    );

    return Response.json(data);

  } catch (error) {
    return errorResponse(error);
  }
}
