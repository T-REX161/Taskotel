// Pure helpers for Admin-controlled additional departments for Managers (full management since the multi-department change).
//
// The server decides everything that matters: only an Admin may read or change grants; a grant only lets a Manager SEE another
// department's staff, workload, tasks and assignments AND to manage them (create, edit, assign, reassign, status, delete...) exactly as
// for their primary department; every operation is checked against the Manager's authorized departments. These helpers only decide how
// answers are worded. Hiding is convenience, not the control.

export const VIEW_ACCESS_ADMIN_ONLY_MESSAGE = 'Only an Admin can view or change a Manager’s department access.';
/** What the Admin dialog calls a department a Manager manages besides their own. */
export const ADDITIONAL_DEPARTMENTS_LABEL = 'Additional departments (full management)';
export const FULL_MANAGEMENT_LABEL = 'Full management';

const normalize = (value) => String(value || '').trim().toUpperCase();

/** The view-access control is offered to an Admin, for a Manager account, and to nobody else. */
export function canManageViewAccess(viewerRole, targetUser) {
  return normalize(viewerRole) === 'ADMIN' && normalize(targetUser?.role) === 'MANAGER';
}

/** The codes currently granted, from the GET/PUT answer. */
export function grantedCodes(access) {
  return (Array.isArray(access?.departments) ? access.departments : []).map((grant) => grant?.departmentCode).filter(Boolean);
}

/**
 * The rows of the dialog, sorted by name: every department with whether it is the Manager's own (always included, not selectable),
 * currently granted, and selectable. An inactive department cannot be newly granted, but one already granted stays listed (and can be
 * removed) so nothing is hidden from the Admin.
 */
export function viewAccessChoices(departments, access) {
  const primary = access?.primaryDepartmentCode || '';
  const granted = new Set(grantedCodes(access));
  const rows = new Map();
  for (const department of Array.isArray(departments) ? departments : []) {
    if (!department?.code) continue;
    rows.set(department.code, { code: department.code, name: department.name || department.code, active: department.active !== false });
  }
  // A granted department missing from the department list (should not happen) is still shown, from the grant itself
  for (const grant of Array.isArray(access?.departments) ? access.departments : []) {
    if (grant?.departmentCode && !rows.has(grant.departmentCode)) {
      rows.set(grant.departmentCode, { code: grant.departmentCode, name: grant.departmentName || grant.departmentCode, active: grant.active !== false });
    }
  }
  return [...rows.values()]
    .map((row) => {
      const isPrimary = row.code === primary;
      const isGranted = granted.has(row.code);
      return { ...row, primary: isPrimary, granted: isGranted, selectable: !isPrimary && (row.active || isGranted) };
    })
    .filter((row) => row.primary || row.active || row.granted)
    .sort((a, b) => (a.primary === b.primary ? a.name.localeCompare(b.name) : a.primary ? -1 : 1));
}

/** Whether the selection differs from what is saved (order does not matter). */
export function selectionChanged(savedCodes, selectedCodes) {
  const saved = new Set(savedCodes || []);
  const selected = new Set(selectedCodes || []);
  if (saved.size !== selected.size) return true;
  for (const code of selected) if (!saved.has(code)) return true;
  return false;
}

/**
 * Whether a person's details show their additional view-only departments: only an Admin looking at a Manager who has an account
 * (the grants are read from the Admin-only endpoint, keyed by the account). Everyone else sees the details exactly as before.
 */
export function showsAdditionalAccess(viewerRole, staff) {
  return canManageViewAccess(viewerRole, staff) && Boolean(staff?.userId);
}

/**
 * The additional departments to list in a Manager's details, by name, from the GET answer: never the primary department (the server
 * leaves it out already; it is dropped here as well, against the answer and against the record being shown), never twice.
 */
export function additionalAccessRows(access, staffDepartmentCode = '') {
  const primary = new Set([access?.primaryDepartmentCode, staffDepartmentCode].filter(Boolean));
  const seen = new Set();
  const rows = [];
  for (const grant of Array.isArray(access?.departments) ? access.departments : []) {
    const code = grant?.departmentCode;
    if (!code || primary.has(code) || seen.has(code)) continue;
    seen.add(code);
    rows.push({ code, name: grant.departmentName || code, active: grant.active !== false });
  }
  return rows.sort((a, b) => a.name.localeCompare(b.name));
}

/** One line for an audit record. */
export function describeViewAccessChange(change) {
  const name = change?.departmentName || change?.departmentCode || 'A department';
  if (change?.action === 'GRANTED') return `Access to ${name} granted`;
  if (change?.action === 'REVOKED') {
    return change?.changeSource === 'PRIMARY_DEPARTMENT_CHANGE'
      ? `Access to ${name} removed (it became the Manager’s own department)`
      : `Access to ${name} removed`;
  }
  if (change?.action === 'UPGRADED') return `Access to ${name} upgraded from view only to full management`;
  return `Access to ${name} changed`;
}

/** What the dialog says about the saved access. */
export function viewAccessSummary(access) {
  const names = (Array.isArray(access?.departments) ? access.departments : []).map((grant) => grant.departmentName || grant.departmentCode);
  if (names.length === 0) return 'Own department only.';
  return `Own department, plus full management of ${names.join(', ')}.`;
}

export function viewAccessErrorMessage(error, fallback = 'The department access could not be saved. Please try again.') {
  const status = error?.status;
  const server = typeof error?.message === 'string' ? error.message.trim() : '';
  if (status === 403) return VIEW_ACCESS_ADMIN_ONLY_MESSAGE;
  if (status === 404) return 'This user could not be found. It may have been removed; refresh the list.';
  if (status === 400 || status === 409) return server || fallback;
  if (status >= 500) return 'A server error occurred. Nothing was changed. Please try again later.';
  return server || fallback;
}

/**
 * The set to send when an Admin revokes ONE additional department: what is saved now, minus that department. Built from the saved grants,
 * not from unsaved ticks, so a revocation never also saves other pending edits. Null when there is nothing to revoke: the Manager's own
 * department (never removable; the server refuses it too), a department that is not granted, or no code.
 */
export function revokeSelection(access, code) {
  if (!code || code === access?.primaryDepartmentCode) return null;
  const saved = grantedCodes(access);
  if (!saved.includes(code)) return null;
  return saved.filter((granted) => granted !== code);
}

/**
 * How a department row of the dialog reads: 'primary' (own department, locked), 'granted' (saved grant), 'adding' (ticked, not
 * saved yet), 'removing' (granted, unticked, not saved yet) or 'none'.
 */
export function choiceState(choice, savedCodes, selectedCodes) {
  if (choice?.primary) return 'primary';
  const saved = (savedCodes || []).includes(choice?.code);
  const selected = (selectedCodes || []).includes(choice?.code);
  if (saved) return selected ? 'granted' : 'removing';
  return selected ? 'adding' : 'none';
}

/**
 * The audit records as compact rows, most recent first (the server already orders them so; sorted again so the order never depends on
 * it). Read-only: there is nothing here, or anywhere, that removes a record.
 */
export function viewAccessHistoryRows(history) {
  const time = (change) => {
    const value = new Date(change?.changedAt).getTime();
    return Number.isNaN(value) ? 0 : value;
  };
  return (Array.isArray(history) ? history : [])
    .filter(Boolean)
    .map((change, index) => ({ change, index }))
    .sort((a, b) => time(b.change) - time(a.change) || a.index - b.index)
    .map(({ change }) => ({
      id: change.id,
      action: change.action === 'GRANTED' ? 'Access granted'
        : change.action === 'REVOKED' ? 'Access revoked'
        : change.action === 'UPGRADED' ? 'Upgraded to full management'
        : 'Access changed',
      tone: change.action === 'GRANTED' ? 'granted' : change.action === 'REVOKED' ? 'revoked' : change.action === 'UPGRADED' ? 'upgraded' : 'other',
      department: change.departmentName || change.departmentCode || 'A department',
      actor: change.changedByUsername || 'Unknown',
      automatic: change.changeSource === 'PRIMARY_DEPARTMENT_CHANGE',
      changedAt: change.changedAt,
    }));
}

/** The Manager View Access dialog shows this many of the newest records; the full history opens in its own dialog. */
export const HISTORY_PREVIEW_SIZE = 10;
/** Records fetched per "Load more" in the full history. */
export const HISTORY_PAGE_SIZE = 20;
export const HISTORY_FILTERS = [
  { key: 'ALL', label: 'All', count: 'all' },
  { key: 'GRANTED', label: 'Granted', count: 'granted' },
  { key: 'REVOKED', label: 'Revoked', count: 'revoked' },
];

/** Whether the preview needs a "View all history" action: only when there are more records than it shows. */
export function hasMoreHistory(page) {
  return Number(page?.totalElements) > HISTORY_PREVIEW_SIZE;
}

/**
 * The records loaded so far plus the next page, without duplicates: a change saved while the Admin is reading moves later pages by one,
 * so a record can come back twice; it is listed once, and the order (newest first) is kept.
 */
export function appendHistoryPage(loaded, next) {
  const seen = new Set((Array.isArray(loaded) ? loaded : []).map((change) => change?.id));
  const added = (Array.isArray(next?.items) ? next.items : []).filter((change) => change && !seen.has(change.id));
  return [...(Array.isArray(loaded) ? loaded : []), ...added];
}

/** The Profile page lists departments only for a signed-in Manager (an Admin covers every department; Staff have their own records only). */
export function showsProfileDepartments(role) {
  return normalize(role) === 'MANAGER';
}

/**
 * The departments in a signed-in Manager's own profile header. The primary one comes from the profile (the department of the Manager's own
 * staff record, null when there is none or it is inactive). The additional ones are those the server marks `managed` in the Manager's own
 * department list (GET /api/departments, scoped to the caller): never the primary department, never twice, sorted by name.
 */
export function profileDepartments(profile, departments) {
  const primaryCode = profile?.departmentCode || '';
  const primary = primaryCode ? { code: primaryCode, name: profile?.departmentName || primaryCode } : null;
  const seen = new Set(primaryCode ? [primaryCode] : []);
  const additional = [];
  for (const department of Array.isArray(departments) ? departments : []) {
    const code = department?.code;
    if (!code || department?.managed !== true || seen.has(code)) continue;
    seen.add(code);
    additional.push({ code, name: department.name || code, active: department.active !== false });
  }
  additional.sort((a, b) => a.name.localeCompare(b.name));
  return { primary, additional };
}
