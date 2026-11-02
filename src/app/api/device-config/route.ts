import {
  deviceConfigJson,
  errorResponse,
  requireUserEmail
} from '@/lib/system-api';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';


// ============================================================
// DEVICE CONFIGURATION
//
// The fleet's tunables, read from the device route (/v1/config)
// with a token brokered from the fleet secret on the server. The
// map uses it for the capture trigger radius; the object is
// passed through whole.
// ============================================================

export async function GET() {

  try {
    await requireUserEmail();

    const data = await deviceConfigJson();

    return Response.json(data);

  } catch (error) {
    return errorResponse(error);
  }
}
