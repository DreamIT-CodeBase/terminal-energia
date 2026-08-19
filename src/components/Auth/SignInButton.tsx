'use client';

import React from 'react';
import { useFormStatus } from 'react-dom';
import { Loader2 } from 'lucide-react';


/**
 * Submit button for the sign-in server action. It lives in its own client
 * component so the home page can stay a server component.
 */
export const SignInButton: React.FC = () => {

  const { pending } = useFormStatus();

  return (

    <button
      type="submit"
      disabled={pending}
      className="
        flex
        w-full
        items-center
        justify-center
        gap-3
        rounded-lg
        bg-[#2f2f33]
        hover:bg-[#3a3a40]
        disabled:opacity-50
        px-4
        py-3
        text-sm
        font-semibold
        text-white
        transition-colors
      "
    >

      {
        pending
          ? (
            <Loader2
              className="
                w-4
                h-4
                animate-spin
              "
            />
          )
          : (
            <MicrosoftLogo />
          )
      }

      {
        pending
          ? 'Redirecting to Microsoft...'
          : 'Sign in with Microsoft'
      }

    </button>
  );
};


const MicrosoftLogo: React.FC = () => (

  <svg
    width="16"
    height="16"
    viewBox="0 0 16 16"
    aria-hidden="true"
  >
    <rect x="0" y="0" width="7" height="7" fill="#F25022" />
    <rect x="9" y="0" width="7" height="7" fill="#7FBA00" />
    <rect x="0" y="9" width="7" height="7" fill="#00A4EF" />
    <rect x="9" y="9" width="7" height="7" fill="#FFB900" />
  </svg>
);
