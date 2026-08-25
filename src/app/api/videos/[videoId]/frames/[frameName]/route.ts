import {
  errorResponse,
  requireUserEmail,
  systemApiJson
} from '@/lib/system-api';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';


// ============================================================
// DISCARD ONE CAPTURED FRAME
//
// Returns what is left, so the caller never has to guess at the
// state of the working area.
// ============================================================

export async function DELETE(
  _request: Request,
  context: RouteContext<'/api/videos/[videoId]/frames/[frameName]'>
) {

  try {
    await requireUserEmail();

    const { videoId, frameName } = await context.params;

    const data = await systemApiJson(
      `/videos/${encodeURIComponent(videoId)}` +
      `/frames/${encodeURIComponent(frameName)}`,
      { method: 'DELETE' }
    );

    return Response.json(data);

  } catch (error) {
    return errorResponse(error);
  }
}
