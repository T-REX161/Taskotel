// Pure helpers for Admin-controlled, view-only department access for Managers.
//
// The server decides everything that matters: only an Admin may read or change grants; a grant only lets a Manager SEE another
// department's staff, workload, tasks and assignments; every write (create, edit, assign, reassign, status, delete...) is still checked
// against the Manager's own department alone, so a view-only record is refused by the API whatever the page offers. These helpers only
// decide what the pages offer and how answers are worded. Hiding is convenience, not the control.

export const VIEW_ACCESS_ADMIN_ONLY_MESSAGE = 'Only an Admin can view or change a Manager’s department access.';
export const VIEW_ONLY_LABEL = 'View only';
export const VIEW_ONLY_HINT = 'You can view this department’s records but not change them. Access was granted by an Administrator.';

const normalize = (value) => String(value || '').trim().toUpperCase();

/** The view-access control is offered to an Admin, for a Manager account, and to nobody else. */
export function canManageViewAccess(viewerRole, targetUser) {
  return normalize(viewerRole) === 'ADMIN' && normalize(targetUser?.role) === 'MANAGER';
}

/** True only for a record the server marked as visible through a view-only grant. Anything else (missing, non-boolean) is not. */
export function isViewOnly(record) {
  return record?.viewOnly === true;
}

/**
 * The records of the Manager's own department only: drops what is visible through a view-only grant. The Dashboard uses it so its figures
 * stay about the primary department (the server's dashboard summary is primary-only as well). Anything that is not an array is returned
 * unchanged.
 */
export function withoutViewOnly(records) {
  return Array.isArray(records) ? records.filter((record) => !isViewOnly(record)) : records;
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

/** One line for an audit record. */
export function describeViewAccessChange(change) {
  const name = change?.departmentName || change?.departmentCode || 'A department';
  if (change?.action === 'GRANTED') return `View access to ${name} granted`;
  if (change?.action === 'REVOKED') {
    return change?.changeSource === 'PRIMARY_DEPARTMENT_CHANGE'
      ? `View access to ${name} removed (it became the Manager’s own department)`
      : `View access to ${name} removed`;
  }
  return `View access to ${name} changed`;
}

/** What the dialog says about the saved access. */
export function viewAccessSummary(access) {
  const names = (Array.isArray(access?.departments) ? access.departments : []).map((grant) => grant.departmentName || grant.departmentCode);
  if (names.length === 0) return 'Own department only.';
  return `Own department, plus view-only access to ${names.join(', ')}.`;
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
