import {
  errorResponse,
  requireUserEmail,
  systemApiJson
} from '@/lib/system-api';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';


// ============================================================
// EVERY STATION, FOR THE MAP
//
// A few hundred entries in one call: the map draws all of them
// at once, so there is nothing to page through.
// ============================================================

export async function GET() {

  try {
    await requireUserEmail();

    const data = await systemApiJson('/stations');

    return Response.json(data);

  } catch (error) {
    return errorResponse(error);
  }
}
