import 'server-only';

import { auth } from '@/auth';
import { ANONYMOUS_TESTER, AUTH_DISABLED } from '@/lib/flags';


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


// The fleet credential. Most console routes use the x-api-key above, but the
// device configuration is only served on the device route (`/v1/config`), which
// takes a device token. So this one read authenticates as a device: the secret
// stays on the server and never reaches the browser, like the API key.
const FLEET_SECRET =
  process.env.FLEET_SECRET ||
  '';


// The device id the console presents when it authenticates for the config read.
// Any value matching the API's id pattern works; it only labels the token.
const CONSOLE_DEVICE_ID =
  process.env.CONSOLE_DEVICE_ID ||
  'labelling-console';


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

  // Sign-in is off for testing, so there is no session to read. Saved work is
  // attributed to the tester placeholder rather than a real account.
  if (AUTH_DISABLED) {
    return ANONYMOUS_TESTER;
  }

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


// ============================================================
// DEVICE CONFIGURATION
//
// Served only on the device route, so the console authenticates
// as a device to read it: swap the fleet secret for a short-lived
// token, then GET /v1/config with it. The token is cached across
// requests and refreshed a little before it expires.
// ============================================================

interface DeviceToken {
  token: string;
  // Epoch millis when the cached token stops being usable.
  expiresAtMs: number;
}

let cachedDeviceToken: DeviceToken | null = null;

// Refreshed this long before expiry, so a call never races the clock.
const TOKEN_REFRESH_MARGIN_MS = 30_000;


const fetchDeviceToken = async (): Promise<string> => {

  const now = Date.now();

  if (
    cachedDeviceToken &&
    cachedDeviceToken.expiresAtMs - TOKEN_REFRESH_MARGIN_MS > now
  ) {
    return cachedDeviceToken.token;
  }

  if (!FLEET_SECRET) {
    throw new SystemApiError(
      'FLEET_SECRET is not set on the labelling UI server.',
      500
    );
  }

  const response = await fetch(
    `${SYSTEM_API_URL}/v1/auth/token`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        deviceId: CONSOLE_DEVICE_ID,
        deviceSecret: FLEET_SECRET
      }),
      cache: 'no-store'
    }
  );

  if (!response.ok) {
    throw new SystemApiError(
      await readErrorDetail(response),
      response.status
    );
  }

  const data = await response.json() as {
    access_token: string;
    expires_in?: number;
  };

  cachedDeviceToken = {
    token: data.access_token,
    expiresAtMs: now + (data.expires_in ?? 300) * 1000
  };

  return cachedDeviceToken.token;
};


/**
 * The device configuration document (`/v1/config`), read with a device token
 * brokered from the fleet secret. A 401 -- a token the API rejects -- is retried
 * once with a fresh token, in case the cached one was invalidated server-side.
 */
export const deviceConfigJson = async <T>(): Promise<T> => {

  const request = async (): Promise<Response> =>
    fetch(
      `${SYSTEM_API_URL}/v1/config`,
      {
        headers: {
          Authorization: `Bearer ${await fetchDeviceToken()}`
        },
        cache: 'no-store'
      }
    );

  let response = await request();

  if (response.status === 401) {
    // Drop the cached token and try once more with a fresh one.
    cachedDeviceToken = null;
    response = await request();
  }

  if (!response.ok) {
    throw new SystemApiError(
      await readErrorDetail(response),
      response.status
    );
  }

  return response.json() as Promise<T>;
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
