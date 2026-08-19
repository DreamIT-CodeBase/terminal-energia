import NextAuth from 'next-auth';
import MicrosoftEntraID from 'next-auth/providers/microsoft-entra-id';
import { NextResponse } from 'next/server';


/**
 * The single account allowed into the tool. Everybody else is turned away in
 * the `signIn` callback, before a session cookie is ever issued.
 */
export const ALLOWED_EMAIL = (
  process.env.ALLOWED_EMAIL ||
  'chirag@dreamitcs.com'
).toLowerCase();


/**
 * Entra ID puts the sign-in address in different claims depending on the
 * account type, so every candidate is checked.
 */
const emailFromProfile = (
  profile: Record<string, unknown> | undefined | null
): string | null => {

  if (!profile) {
    return null;
  }

  const candidates = [
    profile.email,
    profile.preferred_username,
    profile.upn,
    profile.unique_name
  ];

  for (const candidate of candidates) {

    if (
      typeof candidate === 'string' &&
      candidate.includes('@')
    ) {
      return candidate.toLowerCase();
    }
  }

  return null;
};


export const { handlers, auth, signIn, signOut } = NextAuth({

  providers: [
    MicrosoftEntraID
  ],

  pages: {
    signIn: '/',
    error: '/'
  },

  session: {
    strategy: 'jwt'
  },

  callbacks: {

    // Gate 1: refuse to mint a session for anybody but the allowed account.
    signIn: async ({ profile }) => {

      const email = emailFromProfile(
        profile as Record<string, unknown> | undefined
      );

      return email === ALLOWED_EMAIL;
    },

    jwt: async ({ token, profile }) => {

      const email = emailFromProfile(
        profile as Record<string, unknown> | undefined
      );

      if (email) {
        token.email = email;
      }

      return token;
    },

    session: async ({ session, token }) => {

      if (token.email && session.user) {
        session.user.email = token.email;
      }

      return session;
    },

    // Gate 2: checked on every protected request, so a session issued for any
    // other address (for example before the allow list changed) is still
    // rejected.
    authorized: async ({ request, auth: session }) => {

      const allowed =
        session?.user?.email?.toLowerCase() === ALLOWED_EMAIL;

      if (allowed) {
        return true;
      }

      // Data routes answer with JSON so the workspace can show a real error
      // instead of parsing the sign-in page as a response body.
      if (request.nextUrl.pathname.startsWith('/api/')) {

        return NextResponse.json(
          { error: 'Unauthorized' },
          { status: 401 }
        );
      }

      // Pages fall back to the default redirect to the sign-in page.
      return false;
    }
  }
});
