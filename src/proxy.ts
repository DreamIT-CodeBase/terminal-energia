import { NextResponse } from 'next/server';
import { auth } from '@/auth';
import { AUTH_DISABLED } from '@/lib/flags';

/**
 * `proxy` replaces the old `middleware` convention in Next.js 16.
 *
 * The `authorized` callback in `src/auth.ts` decides who may continue;
 * unauthenticated requests are sent back to the sign-in page at `/`.
 *
 * While AUTH_DISABLED is set, every request passes straight through so
 * testers can reach the tool without an Entra account.
 */
export default AUTH_DISABLED
  ? () => NextResponse.next()
  : auth;

export const config = {
  // Everything except the auth endpoints, the login page and static assets.
  matcher: [
    '/annotation/:path*',
    '/map/:path*',
    '/api/videos/:path*',
    '/api/stations/:path*',
    '/api/stations-enriched'
  ]
};
