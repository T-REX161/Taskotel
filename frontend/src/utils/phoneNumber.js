/**
 * Editing the phone number (Profile > Contact Information): the pure logic behind the dialog.
 *
 * The server does the real checks (and the account is always the signed-in one); this is for fast, clear feedback. The number is
 * only saved, never verified: there is no SMS step, so nothing here (or in the dialog) calls it verified.
 */
import { updatePhone } from '../services/api.js';

export const PHONE_MESSAGES = {
  required: 'Enter your phone number.',
  invalid: 'Enter a valid phone number, for example +91 98765 43210.',
  same: 'That is already your phone number.',
  saveFailed: 'Could not save your phone number. Please try again.',
  changed: 'Phone number updated.',
};

export const PHONE_MAX_LENGTH = 50;
const MIN_DIGITS = 7;
const MAX_DIGITS = 15;
const SHAPE = /^\+?[0-9(][0-9 ().-]*$/;
const MAX_MESSAGE_LENGTH = 200;

/** Trimmed, with runs of whitespace collapsed to one space: the form the server stores. */
export const normalizePhone = (value) => String(value ?? '').trim().replace(/\s+/g, ' ');

function bracketsBalanced(value) {
  let open = 0;
  for (const c of value) {
    if (c === '(') open += 1;
    if (c === ')' && (open -= 1) < 0) return false;
  }
  return open === 0;
}

/** The message to show for `value` as a new number, or null when it can be saved. */
export function validatePhone(value, currentPhone = '') {
  const phone = normalizePhone(value);
  if (!phone) return PHONE_MESSAGES.required;
  const digits = (phone.match(/\d/g) || []).length;
  if (phone.length > PHONE_MAX_LENGTH || !SHAPE.test(phone) || digits < MIN_DIGITS || digits > MAX_DIGITS || !bracketsBalanced(phone)) return PHONE_MESSAGES.invalid;
  if (currentPhone && phone === normalizePhone(currentPhone)) return PHONE_MESSAGES.same;
  return null;
}

/** A message safe to show: the server's own short text for a refusal it explained (400, 403, 429), otherwise the fallback. */
export function phoneErrorMessage(error, fallback) {
  const text = typeof error?.message === 'string' ? error.message.trim() : '';
  const explained = [400, 403, 429].includes(error?.status);
  return explained && text && text.length <= MAX_MESSAGE_LENGTH ? text : fallback;
}

/**
 * Saves the number. Resolves with the updated profile only after the server confirmed; rejects otherwise. A number that is not
 * worth sending is refused with a status-0 error holding the field message, and nothing is sent.
 */
export async function savePhone(value, currentPhone, { save = updatePhone } = {}) {
  const problem = validatePhone(value, currentPhone);
  if (problem) throw Object.assign(new Error(problem), { status: 0 });
  return save(normalizePhone(value));
}
