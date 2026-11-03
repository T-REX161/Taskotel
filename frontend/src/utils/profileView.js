/**
 * What the Profile page shows for the signed-in account, as pure functions (so they can be tested without a browser).
 * Everything comes from the profile the server sent: nothing here invents a contact detail or a verification result.
 */

/** The existing access-scope wording for each system role. */
export function accessScopeFor(role) {
  if (role === 'ADMIN') return 'Full Administrator Access';
  if (role === 'MANAGER') return 'Operations & Management Access';
  return 'Standard Staff Access';
}

/** 'on' (true), 'off' (false) or 'unknown' (not loaded yet) for a yes/no account fact. */
export const flagState = (value) => (value === true ? 'on' : value === false ? 'off' : 'unknown');

/** Active / Inactive / Loading… for the account status. */
export function accountStatus(profile) {
  const state = flagState(profile?.active);
  return { state, label: { on: 'Active', off: 'Inactive', unknown: 'Loading…' }[state] };
}

/** Verified / Not verified / Loading…: "verified" only when the backend says so. */
export function emailVerification(profile) {
  const state = flagState(profile?.emailVerified);
  return { state, label: { on: 'Verified', off: 'Not verified', unknown: 'Loading…' }[state] };
}

/** The registered email address, or null while the profile has not loaded (or has none). */
export function emailAddress(profile) {
  const email = typeof profile?.email === 'string' ? profile.email.trim() : '';
  return email || null;
}

/** The stored phone number, or null when there is none (never a made-up value). */
export function phoneNumber(profile) {
  const phone = typeof profile?.phone === 'string' ? profile.phone.trim() : '';
  return phone || null;
}
