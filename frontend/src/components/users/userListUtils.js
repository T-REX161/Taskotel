/*
 * Users page logic: filtering, ordering, badges, dates and which row actions are allowed.
 * Plain JavaScript (no JSX) so it can be unit tested directly.
 *
 * Every user account in Taskotel is either linked to a staff record (staff.userId) or is a stand-alone account
 * such as the seeded administrator. Accounts are changed only through the existing staff endpoints, so a row can
 * offer actions only when its account has a staff record.
 */
import { formatDate } from '../tasks/taskFormatters.js';
import { isActive } from '../staff/staffFormatters.js';

export const ROLE_OPTIONS = [
  { value: '', label: 'All Roles' },
  { value: 'ADMIN', label: 'Admin' },
  { value: 'MANAGER', label: 'Manager' },
  { value: 'STAFF', label: 'Staff' },
];

export const STATUS_OPTIONS = [
  { value: '', label: 'All Status' },
  { value: 'ACTIVE', label: 'Active' },
  { value: 'INACTIVE', label: 'Inactive' },
];

/** Users shown per page of the table */
export const USERS_PER_PAGE = 10;

const ROLE_RANK = { ADMIN: 0, MANAGER: 1, STAFF: 2 };

/** 'ROLE_ADMIN', 'admin', 'Admin' -> 'ADMIN'; anything unknown -> '' */
export function normalizeRole(role) {
  const value = String(role || '').trim().toUpperCase().replace(/^ROLE_/, '');
  return ROLE_RANK[value] !== undefined ? value : '';
}

/** Compact role badge: Admin purple, Manager blue, Staff (and anything else) neutral gray. */
export function roleBadgeClass(role) {
  switch (normalizeRole(role)) {
    case 'ADMIN':
      return 'bg-purple-100 text-purple-700';
    case 'MANAGER':
      return 'bg-blue-100 text-blue-700';
    default:
      return 'bg-gray-100 text-gray-700';
  }
}

export function statusBadge(active) {
  return isActive(active)
    ? { label: 'Active', className: 'bg-emerald-100 text-emerald-700' }
    : { label: 'Inactive', className: 'bg-red-50 text-red-600' };
}

/**
 * Creation date in the project's date format ("Sep 1, 2026", same as task dates), from the API's
 * LocalDateTime ("2026-09-01T10:00:00") or a [year, month, day, ...] array. Missing or unreadable: an em dash.
 */
export function formatCreatedDate(value) {
  if (!value) return '—';
  let isoDay = null;
  if (Array.isArray(value) && value.length >= 3) {
    const [year, month, day] = value;
    isoDay = `${year}-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')}`;
  } else {
    const match = String(value).match(/^(\d{4})-(\d{2})-(\d{2})/);
    if (match) isoDay = match[0];
  }
  return (isoDay && formatDate(isoDay)) || '—';
}

export function isCurrentUser(user, currentUsername) {
  if (!user || !user.username || !currentUsername) return false;
  return String(user.username).trim().toLowerCase() === String(currentUsername).trim().toLowerCase();
}

/**
 * Search (username or email, case-insensitive), role and status filters, combined with AND.
 * Empty values mean "all".
 */
export function filterUsers(users, { query = '', role = '', status = '' } = {}) {
  if (!Array.isArray(users)) return [];
  const needle = String(query).trim().toLowerCase();
  const wantedRole = normalizeRole(role);
  return users.filter((user) => {
    if (!user) return false;
    if (needle) {
      const username = String(user.username || '').toLowerCase();
      const email = String(user.email || '').toLowerCase();
      if (!username.includes(needle) && !email.includes(needle)) return false;
    }
    if (wantedRole && normalizeRole(user.role) !== wantedRole) return false;
    if (status === 'ACTIVE' && !isActive(user.active)) return false;
    if (status === 'INACTIVE' && isActive(user.active)) return false;
    return true;
  });
}

/** Admins first, then managers, then staff; A–Z by username within a role. Does not change the input. */
export function sortUsers(users) {
  if (!Array.isArray(users)) return [];
  const rank = (user) => {
    const role = normalizeRole(user?.role);
    return role ? ROLE_RANK[role] : 3;
  };
  return [...users].sort((a, b) => {
    const byRole = rank(a) - rank(b);
    if (byRole !== 0) return byRole;
    return String(a?.username || '').localeCompare(String(b?.username || ''), undefined, { sensitivity: 'base' });
  });
}

/** userId -> staff record, for linking accounts to the staff endpoints that manage them. */
export function indexStaffByUserId(staffList) {
  const byUserId = new Map();
  if (!Array.isArray(staffList)) return byUserId;
  for (const staff of staffList) {
    if (staff && staff.userId) byUserId.set(String(staff.userId), staff);
  }
  return byUserId;
}

/**
 * The actions a row may offer, in menu order, plus a note explaining anything that is not offered.
 * Mirrors the Staff page rules (the same endpoints do the work):
 *  - view and edit: any account with a staff record
 *  - deactivate (confirmed in a dialog): active accounts; never your own
 *  - activate: inactive accounts
 *  - remove (confirmed in a dialog): administrators only, inactive accounts only; never your own
 */
export function getUserActions(user, { staff = null, currentUsername = '', isAdmin = false } = {}) {
  const own = isCurrentUser(user, currentUsername);
  if (!staff) {
    return {
      actions: [],
      note: own
        ? 'This is your account. It has no staff record, so it cannot be changed from this page.'
        : 'This account has no staff record, so it cannot be changed from this page.',
    };
  }
  const active = isActive(user?.active) && isActive(staff.active);
  const actions = ['view', 'edit'];
  if (active) {
    if (!own) actions.push('deactivate');
  } else {
    actions.push('activate');
    if (isAdmin && !own) actions.push('remove');
  }
  const note = own ? 'This is your account, so it cannot be deactivated or removed here.' : null;
  return { actions, note };
}
