import { redirect } from 'next/navigation';

import {
  ALLOWED_EMAIL,
  auth
} from '@/auth';

import { ValidationWorkspace } from '@/components/Validation/ValidationWorkspace';
import {
  ANONYMOUS_TESTER,
  AUTH_DISABLED
} from '@/lib/flags';


// ============================================================
// VALIDATION WORKSPACE
//
// A read-only companion to /annotation: pick a run on the left,
// eyeball its frames in the middle, and check them against the
// pipeline's response.json on the right.
//
// Same gate as the annotation page: reached only with a valid
// session for the allowed account. `proxy.ts` blocks everybody
// else; this is the second check, for a bypassed proxy.
// ============================================================

export default async function ValidationPage() {

  if (AUTH_DISABLED) {
    return <ValidationWorkspace userEmail={ANONYMOUS_TESTER} />;
  }

  const session = await auth();

  const email = session?.user?.email?.toLowerCase();

  if (email !== ALLOWED_EMAIL) {
    redirect('/');
  }

  return <ValidationWorkspace userEmail={email} />;
}
