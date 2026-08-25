import {
  errorResponse,
  requireUserEmail,
  systemApiJson
} from '@/lib/system-api';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';


// ============================================================
// LENGTH AND FRAME SIZE OF THE SOURCE CLIP
//
// Read before the video is played, so the capture controls can
// offer a spread across the clip straight away.
// ============================================================

export async function GET(
  _request: Request,
  context: RouteContext<'/api/videos/[videoId]/probe'>
) {

  try {
    await requireUserEmail();

    const { videoId } = await context.params;

    const data = await systemApiJson(
      `/videos/${encodeURIComponent(videoId)}/probe`
    );

    return Response.json(data);

  } catch (error) {
    return errorResponse(error);
  }
}
