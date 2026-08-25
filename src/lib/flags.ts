/**
 * Temporary switches, kept together so they are easy to find and turn back on.
 *
 * Both of these loosen the tool for a supervised testing window. Neither is a
 * deployment mode. Removing the environment variables restores the normal
 * behaviour with no code change.
 */


/**
 * Takes Microsoft sign-in out of the way so testers can reach the tool without
 * an Entra account.
 *
 * With it on the app is **open**: every gate is bypassed, so anyone with the
 * URL can browse the source videos and submit annotations into storage.
 *
 * Nothing about the auth setup is removed — the Entra provider, the session
 * cookie and the allow-list all stay wired up.
 */
export const AUTH_DISABLED =
  process.env.AUTH_DISABLED === 'true';


/**
 * Stamped on annotations saved while sign-in is off, in place of the signed-in
 * address, so work submitted during the open window stays identifiable
 * afterwards.
 */
export const ANONYMOUS_TESTER = 'tester@auth-disabled';


/**
 * Offers every video in the debug directory, including ones already written to
 * the annotated container.
 *
 * Normally the queue is work still to do, so anything annotated drops out.
 * With this on a video can be labelled again, and submitting overwrites what
 * is already in that week's snapshot for it. Deliberate: it lets several
 * testers work through the same material.
 */
export const SHOW_ALL_VIDEOS =
  process.env.SHOW_ALL_VIDEOS === 'true';
