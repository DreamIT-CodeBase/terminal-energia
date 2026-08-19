import 'server-only';

import { auth } from '@/auth';


/**
 * Server-side client for the FastAPI system API. The API key is read from the
 * environment on every call and never leaves the Next.js server, so the browser
 * can only reach storage through an authenticated session.
 */

export const SYSTEM_API_URL = (
  process.env.SYSTEM_API_URL ||
  'http://127.0.0.1:8100'
).replace(/\/$/, '');


const SYSTEM_API_KEY =
  process.env.SYSTEM_API_KEY ||
  '';


export class SystemApiError extends Error {

  status: number;

  constructor(
    message: string,
    status: number
  ) {
    super(message);
    this.name = 'SystemApiError';
    this.status = status;
  }
}


const buildHeaders = (
  extra?: HeadersInit
): HeadersInit => {

  return {
    'x-api-key': SYSTEM_API_KEY,
    ...extra
  };
};


/**
 * Every route handler starts here: no valid session, no request to the API.
 * Returns the signed-in address so it can be recorded with saved annotations.
 */
export const requireUserEmail = async (): Promise<string> => {

  const session = await auth();

  const email = session?.user?.email;

  if (!email) {
    throw new SystemApiError('Unauthorized', 401);
  }

  return email;
};


const assertConfigured = () => {

  if (!SYSTEM_API_KEY) {
    throw new SystemApiError(
      'SYSTEM_API_KEY is not set on the labelling UI server.',
      500
    );
  }
};


const readErrorDetail = async (
  response: Response
): Promise<string> => {

  try {
    const body = await response.json();

    if (typeof body?.detail === 'string') {
      return body.detail;
    }

    if (body?.detail) {
      return JSON.stringify(body.detail);
    }

  } catch {
    // Non-JSON error bodies fall through to the status text.
  }

  return `System API responded with ${response.status}.`;
};


export const systemApiJson = async <T>(
  path: string,
  init?: RequestInit
): Promise<T> => {

  assertConfigured();

  const response = await fetch(
    `${SYSTEM_API_URL}${path}`,
    {
      ...init,
      headers: buildHeaders(init?.headers),
      cache: 'no-store'
    }
  );

  if (!response.ok) {
    throw new SystemApiError(
      await readErrorDetail(response),
      response.status
    );
  }

  return response.json() as Promise<T>;
};


/**
 * Used for image and video bytes, which are streamed straight through to the
 * browser instead of being buffered into JSON.
 */
export const systemApiRaw = async (
  path: string,
  // Forwarded so a media element can request byte ranges of video.mp4.
  range?: string | null
): Promise<Response> => {

  assertConfigured();

  return fetch(
    `${SYSTEM_API_URL}${path}`,
    {
      headers: buildHeaders(
        range ? { Range: range } : undefined
      ),
      cache: 'no-store'
    }
  );
};


export const errorResponse = (
  error: unknown
): Response => {

  if (error instanceof SystemApiError) {

    return Response.json(
      { error: error.message },
      { status: error.status }
    );
  }

  console.error('System API request failed:', error);

  return Response.json(
    {
      error:
        error instanceof Error
          ? error.message
          : 'The system API could not be reached.'
    },
    { status: 502 }
  );
};
