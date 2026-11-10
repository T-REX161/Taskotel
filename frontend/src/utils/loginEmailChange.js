/**
 * Starting a verified login email change for a Staff member (Admin, or a Manager for their own department). Pure helpers, so the rules
 * the edit screen follows can be tested without a browser. The backend decides everything; these only shape what is shown.
 *
 * The change never takes effect here: a code goes to the NEW address and the Staff member must confirm it themselves, signed in. Until
 * then the active login email is unchanged and the new address is only "pending".
 */

const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

export const SENT_MESSAGE =
  'Verification code sent to the new address. The login email stays the same until the staff member confirms it themselves from their own account.';

/** Only Staff accounts that are active may be given a change (the server also enforces this, with department rules). */
export function canRequestEmailChange(staff) {
  return Boolean(staff) && staff.role === 'STAFF' && staff.active !== false;
}

/** Whole minutes until the code expires, rounded up, or 0 once it has. */
export function minutesLeft(expiresAt, now = Date.now()) {
  const at = Date.parse(expiresAt);
  return Number.isFinite(at) && at > now ? Math.ceil((at - now) / 60000) : 0;
}

/** The pending change the list reports for a staff member, or null when there is none or its code has expired. */
export function pendingChange(staff, now = Date.now()) {
  if (!staff || !staff.pendingEmailMasked || !staff.pendingEmailExpiresAt) return null;
  return minutesLeft(staff.pendingEmailExpiresAt, now) > 0 ? { masked: staff.pendingEmailMasked, expiresAt: staff.pendingEmailExpiresAt } : null;
}

/** The pending change a successful request describes (the answer carries the masked address and how long the code lives). */
export function pendingFromResponse(response, now = Date.now()) {
  if (!response || !response.pendingEmailMasked) return null;
  const seconds = Number(response.expiresInSeconds);
  return { masked: response.pendingEmailMasked, expiresAt: new Date(now + (Number.isFinite(seconds) ? seconds : 600) * 1000).toISOString() };
}

/** Client-side check before asking; the server validates again. Returns an error message, or '' when fine. */
export function validateNewEmail(value, currentEmail) {
  const email = String(value || '').trim();
  if (!email) return 'Enter the new login email address.';
  if (!EMAIL.test(email)) return 'Enter a valid email address.';
  if (currentEmail && email.toLowerCase() === String(currentEmail).trim().toLowerCase()) return 'That is already this account\'s login email address.';
  return '';
}

/** A clear message for each way the request can fail. Never claims the email changed. */
export function emailChangeErrorMessage(error) {
  const status = error && error.status;
  const message = error && typeof error.message === 'string' ? error.message : '';
  if (status === 429) return message || 'Too many requests. Please wait a few minutes and try again.';
  if (status === 404) return 'This staff member was not found.';
  if (status === 403) return message || 'You do not have permission to change this login email.';
  if (status === 409 || status === 400) return message || 'The request could not be completed.';
  if (status >= 500) return 'The verification email could not be sent. Nothing was changed. Please try again.';
  return message || 'The request failed. Nothing was changed. Please try again.';
}
