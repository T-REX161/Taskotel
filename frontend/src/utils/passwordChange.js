/**
 * Change Password (Settings > Security Settings): the pure logic behind the form.
 *
 * The rules come from the shared password policy (the same one first-time setup uses); the server checks them again, so this is
 * only for fast feedback. Passwords live only in the form's state: nothing here stores or logs them.
 */
import { changePassword } from '../services/api.js';
import { evaluatePasswordRules } from './passwordPolicy.js';

export const PASSWORD_MESSAGES = {
  currentRequired: 'Current password is required.',
  newRequired: 'New password is required.',
  newWeak: 'Password does not satisfy all the requirements below.',
  newTooLong: 'Password must be 72 characters or fewer.',
  newSame: 'New password must be different from your current password.',
  confirmRequired: 'Please confirm your new password.',
  confirmMismatch: 'New password and confirmation do not match.',
  saved: 'Your password has been changed.',
  failed: 'Could not change your password. Please try again.',
};

const MAX_MESSAGE_LENGTH = 200;
const utf8Length = (value) => new TextEncoder().encode(value).length;

/** Field errors for the form; an empty object means it can be sent. */
export function validatePasswordChange({ currentPassword = '', newPassword = '', confirmPassword = '' } = {}) {
  const errors = {};
  if (!currentPassword) errors.currentPassword = PASSWORD_MESSAGES.currentRequired;

  if (!newPassword) errors.newPassword = PASSWORD_MESSAGES.newRequired;
  else if (!evaluatePasswordRules(newPassword).isValid) errors.newPassword = PASSWORD_MESSAGES.newWeak;
  else if (utf8Length(newPassword) > 72) errors.newPassword = PASSWORD_MESSAGES.newTooLong;
  else if (currentPassword && newPassword === currentPassword) errors.newPassword = PASSWORD_MESSAGES.newSame;

  if (!confirmPassword) errors.confirmPassword = PASSWORD_MESSAGES.confirmRequired;
  else if (newPassword !== confirmPassword) errors.confirmPassword = PASSWORD_MESSAGES.confirmMismatch;
  return errors;
}

/**
 * A message safe to show for a failed request: the server's own text for a refusal it explained (400 wrong/weak/same password,
 * 403 setup pending, 429 too many tries) when it is short, otherwise the generic one. Never anything from a 5xx.
 */
export function passwordErrorMessage(error) {
  const text = typeof error?.message === 'string' ? error.message.trim() : '';
  const explained = [400, 403, 429].includes(error?.status);
  return explained && text && text.length <= MAX_MESSAGE_LENGTH ? text : PASSWORD_MESSAGES.failed;
}

/**
 * Sends the change. Resolves only after the server confirmed it (so "saved" is never shown for a failure); rejects with the
 * server's error, or with a field-error object (status 0) when the form is invalid and nothing was sent.
 */
export async function submitPasswordChange(values, { send = changePassword } = {}) {
  const errors = validatePasswordChange(values);
  if (Object.keys(errors).length) throw Object.assign(new Error('Invalid form'), { status: 0, errors });
  await send({
    currentPassword: values.currentPassword,
    newPassword: values.newPassword,
    confirmPassword: values.confirmPassword,
  });
}
