// Pure helpers for the Admin-only "Manager department" dialog (Users page). The server decides everything that matters (only an Admin may
// assign or read the history, the department must exist and be active, the transfer rules); these helpers only decide what is
// offered and how server answers are worded. Nothing here is a security control: hiding is for convenience, the API refuses anyway.

export const ADMIN_ONLY_MESSAGE = 'Only an Admin can view or change a Manager’s department.';
export const NOT_MANAGER_MESSAGE = 'Departments are assigned to Manager accounts only.';

const normalize = (value) => String(value || '').trim().toUpperCase();

/** The department control is offered to an Admin, for a Manager account, and to nobody else. */
export function canManageManagerDepartment(viewerRole, targetUser) {
  return normalize(viewerRole) === 'ADMIN' && normalize(targetUser?.role) === 'MANAGER';
}

/** The audit history is shown to an Admin only. */
export function canViewManagerDepartmentHistory(viewerRole) {
  return normalize(viewerRole) === 'ADMIN';
}

/** Active departments the Manager can be moved to, current one excluded, sorted by name. Inactive departments are never offered. */
export function departmentChoices(departments, currentCode) {
  return (Array.isArray(departments) ? departments : [])
    .filter((d) => d && d.code && d.active !== false && d.code !== currentCode)
    .map((d) => ({ code: d.code, name: d.name || d.code }))
    .sort((a, b) => a.name.localeCompare(b.name));
}

/** A message for the selection, or '' when it can be submitted. */
export function validateSelection(currentCode, selectedCode) {
  if (!selectedCode) return 'Choose a department.';
  if (selectedCode === currentCode) return 'This Manager is already in that department.';
  return '';
}

/** Whether the Save button is enabled: a valid choice and no request already in flight (prevents repeated submissions). */
export function canSubmit({ currentCode, selectedCode, submitting }) {
  return !submitting && validateSelection(currentCode, selectedCode) === '';
}

/** What the dialog says about the Manager's current department, never inventing one. */
export function currentDepartmentText(info) {
  if (!info) return 'Not available';
  if (!info.departmentName && !info.departmentCode) return 'No department';
  return info.departmentName || info.departmentCode;
}

/** Why the Manager would currently be refused department access (from the server flags), or '' when valid. */
export function validityNote(info) {
  if (!info || info.valid) return '';
  const reasons = [];
  if (info.accountActive === false) reasons.push('the account is inactive');
  if (info.staffActive === false) reasons.push('the staff record is inactive');
  if (info.departmentActive === false) reasons.push('the department is inactive');
  return reasons.length
    ? `This Manager has no department access right now: ${reasons.join(', ')}.`
    : 'This Manager has no department access right now.';
}

/** One history line: "Housekeeping → Kitchen". Names are the snapshots stored with the record, so they stay correct after renames. */
export function describeChange(change) {
  const from = change?.previousDepartmentName || change?.previousDepartmentCode || 'Unknown';
  const to = change?.newDepartmentName || change?.newDepartmentCode || 'Unknown';
  return `${from} → ${to}`;
}

/** Who made the change, e.g. "by admin", or "by an Admin" when the record carries no name. */
export function describeChangedBy(change) {
  return change?.changedByUsername ? `by ${change.changedByUsername}` : 'by an Admin';
}

/** Words for a failed assign / read, built from the HTTP status and the server message, never from guesses about the cause. */
export function managerDepartmentErrorMessage(error, fallback = 'The department could not be changed. Please try again.') {
  const status = error?.status;
  const server = typeof error?.message === 'string' ? error.message.trim() : '';
  if (status === 403) return ADMIN_ONLY_MESSAGE;
  if (status === 404) return 'This user could not be found. It may have been removed; refresh the list.';
  if (status === 400 || status === 409) return server || fallback;
  if (status >= 500) return 'A server error occurred. Nothing was changed. Please try again later.';
  return server || fallback;
}
