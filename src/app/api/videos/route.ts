import {
  errorResponse,
  requireUserEmail,
  systemApiJson
} from '@/lib/system-api';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';


// ============================================================
// LIST EVERY VIDEO IN THE DEBUG DIRECTORY CONTAINER
// ============================================================

export async function GET() {

  try {
    await requireUserEmail();

    const data = await systemApiJson('/videos');

    return Response.json(data);

  } catch (error) {
    return errorResponse(error);
  }
}
