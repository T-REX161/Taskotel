/**
 * Changing an account's system role (Users page, Administrators only): the pure logic behind the dialog.
 *
 * The server enforces every rule (Administrator only, never your own account, never the last active Administrator, a valid Manager record,
 * the password for anything involving ADMIN). This only mirrors them for fast, clear feedback and for what the dialog says.
 */
import { changeUserRole } from '../services/api.js';

export const ROLES = Object.freeze(['STAFF', 'MANAGER', 'ADMIN']);
export const ROLE_LABELS = Object.freeze({ STAFF: 'Staff', MANAGER: 'Manager', ADMIN: 'Administrator' });

export const ROLE_MESSAGES = {
  password: 'Enter your password to confirm this change.',
  choose: 'Choose the new role.',
  failed: 'The role could not be changed. Please try again.',
};

const norm = (role) => String(role || '').trim().toUpperCase().replace(/^ROLE_/, '');

/** Only an Administrator, and never for their own account (the server refuses both too). */
export function canChangeRole(viewerRole, user, currentUsername = '') {
  if (norm(viewerRole) !== 'ADMIN' || !user) return false;
  if (!ROLES.includes(norm(user.role))) return false;
  return String(user.username || '').toLowerCase() !== String(currentUsername || '').toLowerCase();
}

/** The roles an account can move to: every role except its current one. */
export function roleChoices(currentRole) {
  const current = norm(currentRole);
  return ROLES.filter((role) => role !== current);
}

/** A change to or from ADMIN asks the Administrator for their password again. */
export function requiresPassword(from, to) {
  return norm(from) === 'ADMIN' || norm(to) === 'ADMIN';
}

/** What an account can do in each role, in plain words (shown before the change is confirmed). */
export function roleExplanation(role) {
  switch (norm(role)) {
    case 'ADMIN':
      return 'Full system access: all departments, all users, roles, settings and system configuration.';
    case 'MANAGER':
      return 'Manages their own department (taken from their staff record) and any additional departments an Administrator grants. No user, role or system management.';
    default:
      return 'Sees and updates only their own tasks, workload and profile. No management access.';
  }
}

/** What else the change does to the account, so nothing is a surprise. */
export function consequences(from, to) {
  const notes = [];
  if (norm(from) === 'MANAGER') notes.push('Any additional departments granted to this Manager are removed.');
  if (norm(to) === 'MANAGER') notes.push('Their own department comes from their staff record. No additional department is granted.');
  if (norm(to) === 'ADMIN') notes.push('They will have full control of the system, including changing other roles.');
  if (norm(from) === 'ADMIN') notes.push('They lose all administrator access.');
  notes.push('Their staff record, department, tasks and history are kept.');
  notes.push('Their current sessions end: they must sign in again.');
  return notes;
}

/** The message to show for this choice, or '' when it can be sent. */
export function validateRoleChange({ from, to, password }) {
  if (!ROLES.includes(norm(to)) || norm(to) === norm(from)) return ROLE_MESSAGES.choose;
  if (requiresPassword(from, to) && !String(password || '')) return ROLE_MESSAGES.password;
  return '';
}

/** A message safe to show: the server's own short text for a refusal it explained (400, 403, 404, 409, 429), otherwise the fallback. */
export function roleChangeErrorMessage(error, fallback = ROLE_MESSAGES.failed) {
  const text = typeof error?.message === 'string' ? error.message.trim() : '';
  const explained = [400, 403, 404, 409, 429].includes(error?.status);
  return explained && text && text.length <= 300 ? text : fallback;
}

/**
 * Sends the change. Resolves with the updated account only after the server confirmed; rejects otherwise. A choice that is not worth
 * sending is refused with a status-0 error holding the message, and nothing is sent. The password goes only for ADMIN-involving changes.
 */
export async function submitRoleChange({ userId, from, to, password }, { save = changeUserRole } = {}) {
  const problem = validateRoleChange({ from, to, password });
  if (problem) throw Object.assign(new Error(problem), { status: 0 });
  return save(userId, norm(to), requiresPassword(from, to) ? password : undefined);
}
