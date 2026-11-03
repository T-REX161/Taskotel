/**
 * Changing the email address (Profile > Contact Information): the pure logic behind the dialog.
 *
 * The server does the real checks (and the account is always the signed-in one); this is for fast, clear feedback. Nothing here
 * stores anything in the browser. The verification code only ever lives in the dialog's own state.
 */
import { confirmEmailChange, requestEmailChange } from '../services/api.js';

export const EMAIL_CHANGE_MESSAGES = {
  required: 'Enter the new email address.',
  invalid: 'Enter a valid email address.',
  same: 'That is already your email address.',
  codeRequired: 'Enter the 6-digit code.',
  sendFailed: 'Could not send the verification code. Please try again.',
  verifyFailed: 'Could not verify the code. Please try again.',
  changed: 'Email address updated.',
};

/** The same shape the server accepts: one address, no spaces, a dotted domain. */
const EMAIL_PATTERN = /^[A-Za-z0-9._%+'-]+@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}$/;
const MAX_LENGTH = 254;
const MAX_MESSAGE_LENGTH = 200;

/** Trimmed and lower-case, as the server stores it. */
export const normalizeEmail = (value) => String(value ?? '').trim().toLowerCase();

/** The message to show for `value` as a new address, or null when it can be sent. */
export function validateNewEmail(value, currentEmail = '') {
  const email = normalizeEmail(value);
  if (!email) return EMAIL_CHANGE_MESSAGES.required;
  if (email.length > MAX_LENGTH || !EMAIL_PATTERN.test(email)) return EMAIL_CHANGE_MESSAGES.invalid;
  if (email === normalizeEmail(currentEmail)) return EMAIL_CHANGE_MESSAGES.same;
  return null;
}

/** Only the digits, at most six: what a code box may hold. */
export const onlyDigits = (value) => String(value ?? '').replace(/\D/g, '').slice(0, 6);

export const isCompleteCode = (value) => /^\d{6}$/.test(String(value ?? ''));

/** A message safe to show: the server's own short text for a refusal it explained (400, 403, 409, 429), otherwise the fallback. */
export function emailChangeErrorMessage(error, fallback) {
  const text = typeof error?.message === 'string' ? error.message.trim() : '';
  const explained = [400, 403, 409, 429].includes(error?.status);
  return explained && text && text.length <= MAX_MESSAGE_LENGTH ? text : fallback;
}

/** Whole seconds from `nowMs` until `deadlineMs`, never negative. */
export const secondsLeft = (deadlineMs, nowMs) => Math.max(0, Math.ceil((deadlineMs - nowMs) / 1000));

/** 585 -> "9:45" */
export function formatCountdown(totalSeconds) {
  const seconds = Math.max(0, Math.floor(totalSeconds));
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;
}

/**
 * Asks the server to send a code to `newEmail` (also used to resend). Resolves with what the screen needs: the masked address and
 * the moments the code expires and may be resent. Rejects with the server's error, or with a status-0 error holding the field
 * message when the address is not worth sending (nothing is sent then).
 */
export async function sendEmailChangeCode(newEmail, currentEmail, { send = requestEmailChange, now = () => Date.now() } = {}) {
  const problem = validateNewEmail(newEmail, currentEmail);
  if (problem) throw Object.assign(new Error(problem), { status: 0 });
  const answer = await send(normalizeEmail(newEmail));
  const at = now();
  return {
    maskedEmail: answer?.pendingEmailMasked || '',
    expiresAt: at + (Number(answer?.expiresInSeconds) || 600) * 1000,
    resendAt: at + (Number(answer?.resendAvailableInSeconds) || 60) * 1000,
  };
}

/** Verifies the code. Resolves with the updated profile only after the server confirmed the change; rejects otherwise. */
export async function verifyEmailChangeCode(code, { confirm = confirmEmailChange } = {}) {
  if (!isCompleteCode(code)) throw Object.assign(new Error(EMAIL_CHANGE_MESSAGES.codeRequired), { status: 0 });
  return confirm(code);
}
