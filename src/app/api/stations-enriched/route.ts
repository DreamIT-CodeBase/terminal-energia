import {
  errorResponse,
  requireUserEmail,
  systemApiJson
} from '@/lib/system-api';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';


export async function GET() {

  try {
    await requireUserEmail();

    const data = await systemApiJson('/stations-enriched');

    return Response.json(data);

  } catch (error) {
    return errorResponse(error);
  }
}


export async function PUT(request: Request) {

  try {
    await requireUserEmail();

    const body = await request.json();
    const data = await systemApiJson(
      '/stations-enriched',
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
