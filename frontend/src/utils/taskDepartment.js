/**
 * Which department a new or edited task may use, by role. The server decides (a Manager can only create tasks in their own department and
 * cannot move a task to another one); these helpers only stop the screens from offering what the server would refuse.
 *
 * - Admin: any active department (unchanged).
 * - Manager: their own department, read from their own profile (departmentCode / departmentName), shown locked. Until it is known, or if it
 *   cannot be loaded, the form is not shown at all, so no editable selector is ever exposed. A Manager without an active department cannot
 *   create tasks.
 */

export const MANAGER_NO_DEPARTMENT_MESSAGE =
  'Your account is not assigned to an active department. Ask an Admin to assign one before creating tasks.';

export const MANAGER_DEPARTMENT_LOAD_FAILED_MESSAGE =
  'Your department could not be loaded, so a task cannot be created right now. Please try again.';

export const MANAGER_DEPARTMENT_LOCKED_HINT = 'Managers create tasks in their own department.';

export const DEPARTMENT_CHANGE_LOCKED_HINT = 'Only an Admin can move a task to another department.';

/**
 * The Manager's own department from their profile:
 * - { status: 'ready', code, name } when the profile names an active department;
 * - { status: 'none' } when the profile says there is none (null);
 * - { status: 'error' } when there is no profile, or it does not carry the field at all (never guessed as "none").
 */
export function managerDepartmentFromProfile(profile) {
  if (!profile || typeof profile !== 'object' || !Object.prototype.hasOwnProperty.call(profile, 'departmentCode')) {
    return { status: 'error' };
  }
  const code = typeof profile.departmentCode === 'string' ? profile.departmentCode.trim() : '';
  if (!code) return { status: 'none' };
  const name = typeof profile.departmentName === 'string' && profile.departmentName.trim() ? profile.departmentName.trim() : code;
  return { status: 'ready', code, name };
}

/**
 * What the Create Task page shows for this role:
 * 'select' (Admin and anyone not a Manager: the existing selector), or for a Manager 'loading', 'error', 'none' (cannot create) or 'locked'.
 */
export function createTaskDepartmentAccess(role, managerDepartment) {
  if (role !== 'MANAGER') return 'select';
  const status = managerDepartment?.status;
  if (status === 'ready') return 'locked';
  if (status === 'none') return 'none';
  if (status === 'error') return 'error';
  return 'loading';
}

/**
 * Whether the Create Task page should load the data only its form uses (departments, staff, tasks, assignments): only when the form is
 * shown, that is for an Admin ('select', at once, as before) or a Manager whose own department is known ('locked'). Never while a
 * Manager's profile is loading or failed, and never for a Manager without an active department (the server would refuse every one of
 * those reads, and the page shows only the explanation).
 */
export function createTaskFormDataNeeded(access) {
  return access === 'select' || access === 'locked';
}

/** Whether the Edit Task modal lets this role change the department (Admin yes; a Manager never). */
export function canChangeTaskDepartment(role) {
  return role !== 'MANAGER';
}
