import { redirect } from 'next/navigation';

import {
  ALLOWED_EMAIL,
  auth
} from '@/auth';

import { MapWorkspace } from '@/components/Map/MapWorkspace';
import {
  ANONYMOUS_TESTER,
  AUTH_DISABLED
} from '@/lib/flags';


// ============================================================
// STATION MAP
//
// The forecourts the fleet drives to, on one map: move a marker
// to correct a position, edit the board row order a surveyor
// recorded, add a station nobody has mapped, remove one that is
// gone. Writes go to the same document the devices read.
//
// Same gate as the other two workspaces: a valid session for the
// allowed account. `proxy.ts` blocks everybody else; this is the
// second check, for a bypassed proxy.
// ============================================================

export default async function MapPage() {

  if (AUTH_DISABLED) {
    return <MapWorkspace userEmail={ANONYMOUS_TESTER} />;
  }

  const session = await auth();

  const email = session?.user?.email?.toLowerCase();

  if (email !== ALLOWED_EMAIL) {
    redirect('/');
  }

  return <MapWorkspace userEmail={email} />;
}
