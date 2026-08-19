import {
  errorResponse,
  requireUserEmail,
  systemApiJson
} from '@/lib/system-api';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';


// ============================================================
// READ PREVIOUSLY SAVED ANNOTATIONS
// ============================================================

export async function GET(
  _request: Request,
  context: RouteContext<'/api/videos/[videoId]/annotations'>
) {

  try {
    await requireUserEmail();

    const { videoId } = await context.params;

    const data = await systemApiJson(
      `/videos/${encodeURIComponent(videoId)}/annotations`
    );

    return Response.json(data);

  } catch (error) {
    return errorResponse(error);
  }
}


// ============================================================
// SAVE ANNOTATED IMAGES AND YOLO LABELS
// ============================================================

export async function POST(
  request: Request,
  context: RouteContext<'/api/videos/[videoId]/annotations'>
) {

  try {
    const email = await requireUserEmail();

    const { videoId } = await context.params;

    const body = await request.json();

    const data = await systemApiJson(
      `/videos/${encodeURIComponent(videoId)}/annotations`,
      {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json'
        },
        body: JSON.stringify({
          ...body,
          // The signed-in address is taken from the session, never the client.
          savedBy: email
        })
      }
    );

    return Response.json(data);

  } catch (error) {
    return errorResponse(error);
  }
}
