/**
 * Optional department rules for Staff screens. Pure functions, no React: the forms, the Staff list and the tests share them.
 *
 * Every account role (STAFF, MANAGER, ADMIN) may have no department until an Admin assigns one. Only an Admin may create, assign or clear it;
 * a Manager only ever works with their own department's Staff. A Manager with no department gets no department-scoped access (the server
 * refuses it) until an Admin assigns one; an Admin keeps full access either way. The server enforces all of this; the screens simply do not
 * offer what it would refuse.
 */

export const UNASSIGNED_LABEL = 'Unassigned';
export const NONE_OPTION_LABEL = 'None (Unassigned)';
/** The value of the "Unassigned" entry in the Staff department filter (never a real department code). */
export const UNASSIGNED_FILTER = 'UNASSIGNED';

/** True when the record has no usable department (null, undefined, empty or blank). */
export function hasNoDepartment(department) {
  return typeof department !== 'string' || department.trim() === '';
}

/** What the Staff screens show for a department; "Unassigned", never None, null, undefined or an empty cell. */
export function departmentLabel(department, format) {
  if (hasNoDepartment(department)) return UNASSIGNED_LABEL;
  const label = typeof format === 'function' ? format(department) : department;
  return label || UNASSIGNED_LABEL;
}

/** The department sent to the server: the chosen code, or null for "None (Unassigned)". */
export function departmentPayload(value) {
  return hasNoDepartment(value) ? null : value.trim();
}

/**
 * Whether the department select offers "None (Unassigned)": an Admin only, for an account of any role. The role of the account being
 * created or edited does not matter (it is accepted so callers can state which account they mean).
 */
// eslint-disable-next-line no-unused-vars -- accountRole documents the call site; every role may be unassigned
export function canChooseNoDepartment(viewerRole, accountRole) {
  return viewerRole === 'ADMIN';
}

/** Whether the Staff record belongs to a Manager account with no department (that Manager cannot reach any department-scoped data yet). */
export function isUnassignedManager(staff) {
  return Boolean(staff) && staff.role === 'MANAGER' && hasNoDepartment(staff.department);
}

export const UNASSIGNED_MANAGER_NOTICE =
  'This Manager has no department, so they cannot access any department-scoped data (Staff, tasks, workload) until an Admin assigns one.';

/** A message for a form that must still have a department, or '' when the choice is acceptable. */
export function departmentProblem({ viewerRole, accountRole = 'STAFF', department }) {
  if (!hasNoDepartment(department)) return '';
  return canChooseNoDepartment(viewerRole, accountRole) ? '' : 'Department is required.';
}

/** Department filter on the Staff list: '' is every department, UNASSIGNED_FILTER is Staff without one. Safe for a missing department. */
export function matchesDepartmentFilter(staff, selectedDepartment) {
  if (!selectedDepartment) return true;
  if (selectedDepartment === UNASSIGNED_FILTER) return hasNoDepartment(staff?.department);
  return !hasNoDepartment(staff?.department) && staff.department.toUpperCase() === selectedDepartment.toUpperCase();
}

/** Whether the Staff list's department filter should offer "Unassigned" (Admin only; a Manager never sees unassigned Staff). */
export function canFilterUnassigned(viewerRole) {
  return viewerRole === 'ADMIN';
}
