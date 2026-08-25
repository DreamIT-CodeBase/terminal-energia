import { redirect } from 'next/navigation';

import {
  ALLOWED_EMAIL,
  auth
} from '@/auth';

import { AnnotationWorkspace } from '@/components/Annotation/AnnotationWorkspace';
import {
  ANONYMOUS_TESTER,
  AUTH_DISABLED,
  SHOW_ALL_VIDEOS
} from '@/lib/flags';


// ============================================================
// ANNOTATION WORKSPACE
//
// Reached only with a valid session for the allowed account.
// `proxy.ts` already blocks everybody else; this is the second
// check, for the case where the proxy is bypassed.
// ============================================================

export default async function AnnotationPage() {

  if (AUTH_DISABLED) {
    return (
      <AnnotationWorkspace
        userEmail={ANONYMOUS_TESTER}
        authDisabled
        showAllVideos={SHOW_ALL_VIDEOS}
      />
    );
  }

  const session = await auth();

  const email = session?.user?.email?.toLowerCase();

  if (email !== ALLOWED_EMAIL) {
    redirect('/');
  }

  return (
    <AnnotationWorkspace
      userEmail={email}
      showAllVideos={SHOW_ALL_VIDEOS}
    />
  );
}
