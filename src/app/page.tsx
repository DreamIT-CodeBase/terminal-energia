import { redirect } from 'next/navigation';

import {
  ALLOWED_EMAIL,
  auth,
  signIn
} from '@/auth';

import { SignInButton } from '@/components/Auth/SignInButton';
import { AUTH_DISABLED } from '@/lib/flags';


// ============================================================
// HOME / SIGN IN
//
// The only public page. A valid session for the allowed account
// goes straight through to the workspace.
// ============================================================

export default async function Home(
  props: {
    searchParams: Promise<{ error?: string }>;
  }
) {

  // Nothing to sign in to while sign-in is off.
  if (AUTH_DISABLED) {
    redirect('/annotation');
  }

  const session = await auth();

  if (
    session?.user?.email?.toLowerCase() === ALLOWED_EMAIL
  ) {
    redirect('/annotation');
  }

  const { error } = await props.searchParams;

  const signInWithMicrosoft = async () => {
    'use server';

    await signIn(
      'microsoft-entra-id',
      { redirectTo: '/annotation' }
    );
  };

  return (

    <main
      className="
        flex
        flex-1
        items-center
        justify-center
        bg-[#0d0d10]
        text-zinc-200
        px-6
      "
    >

      <div
        className="
          w-full
          max-w-md
          rounded-2xl
          border
          border-[#232326]
          bg-[#141416]
          p-8
        "
      >

        <div
          className="
            flex
            items-center
            gap-3
            mb-8
          "
        >

          <div
            className="
              flex
              items-center
              justify-center
              w-9
              h-9
              rounded
              bg-[#00bcd4]
              font-bold
              text-black
              text-xs
            "
          >
            FP
          </div>

          <div>
            <div
              className="
                text-sm
                font-black
                tracking-widest
                text-white
                uppercase
              "
            >
              Fuel Price Labelling
            </div>

            <div
              className="
                text-xs
                text-zinc-500
              "
            >
              Board and price region annotation
            </div>
          </div>

        </div>


        <h1
          className="
            text-xl
            font-semibold
            text-white
            mb-2
          "
        >
          Sign in
        </h1>

        <p
          className="
            text-sm
            text-zinc-400
            mb-6
          "
        >
          This tool is restricted to a single Microsoft account.
        </p>


        {
          error && (

            <div
              className="
                mb-6
                rounded-lg
                border
                border-red-500/40
                bg-red-500/10
                px-4
                py-3
                text-xs
                text-red-300
              "
            >
              {
                error === 'AccessDenied'
                  ? `That account is not allowed. Sign in with ${ALLOWED_EMAIL}.`
                  : 'Sign in failed. Please try again.'
              }
            </div>
          )
        }


        <form action={signInWithMicrosoft}>
          <SignInButton />
        </form>


        <p
          className="
            mt-6
            text-[11px]
            text-zinc-600
          "
        >
          Authorised account: {ALLOWED_EMAIL}
        </p>

      </div>

    </main>
  );
}
