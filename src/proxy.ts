import { auth } from '@/auth';

/**
 * `proxy` replaces the old `middleware` convention in Next.js 16.
 *
 * The `authorized` callback in `src/auth.ts` decides who may continue;
 * unauthenticated requests are sent back to the sign-in page at `/`.
 */
export default auth;

export const config = {
  // Everything except the auth endpoints, the login page and static assets.
  matcher: [
    '/annotation/:path*',
    '/api/videos/:path*'
  ]
};
