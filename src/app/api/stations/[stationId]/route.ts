import {
  errorResponse,
  requireUserEmail,
  systemApiJson
} from '@/lib/system-api';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';


// ============================================================
// CREATE, AMEND AND REMOVE ONE STATION
//
// The id is the key and comes from the path. A PUT merges: any
// field the body leaves out keeps its stored value, so the map
// can send only what changed. A DELETE is final -- the response
// carries the only remaining copy of the entry.
// ============================================================

export async function PUT(
  request: Request,
  context: RouteContext<'/api/stations/[stationId]'>
) {

  try {
    await requireUserEmail();

    const { stationId } = await context.params;

    const body = await request.json();

    const data = await systemApiJson(
      `/stations/${encodeURIComponent(stationId)}`,
      {
        method: 'PUT',
        headers: {
          'Content-Type': 'application/json'
        },
        body: JSON.stringify(body)
      }
    );

    return Response.json(data);

  } catch (error) {
    return errorResponse(error);
  }
}


export async function DELETE(
  _request: Request,
  context: RouteContext<'/api/stations/[stationId]'>
) {

  try {
    await requireUserEmail();

    const { stationId } = await context.params;

    const data = await systemApiJson(
      `/stations/${encodeURIComponent(stationId)}`,
      { method: 'DELETE' }
    );

    return Response.json(data);

  } catch (error) {
    return errorResponse(error);
  }
}
