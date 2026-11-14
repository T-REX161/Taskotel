import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import {
  DEPARTMENT_CHANGE_LOCKED_HINT,
  MANAGER_DEPARTMENT_LOAD_FAILED_MESSAGE,
  MANAGER_NO_DEPARTMENT_MESSAGE,
  canChangeTaskDepartment,
  createTaskDepartmentAccess,
  createTaskFormDataNeeded,
  managerDepartmentFromProfile,
} from './taskDepartment.js';

const read = (...parts) => readFileSync(new URL(`../${parts.join('/')}`, import.meta.url), 'utf8');
const profile = (departmentCode, departmentName) => ({ username: 'm', role: 'MANAGER', departmentCode, departmentName });

// ── behaviour of the helpers ─────────────────────────────────────────────────────────────────────────────────

test('the no-department message is exactly the approved wording', () => {
  assert.equal(MANAGER_NO_DEPARTMENT_MESSAGE, 'Your account is not assigned to an active department. Ask an Admin to assign one before creating tasks.');
});

test('Manager with a department: ready with its code and name (name falls back to the code)', () => {
  assert.deepEqual(managerDepartmentFromProfile(profile('FRONT_DESK', 'Front Desk')), { status: 'ready', code: 'FRONT_DESK', name: 'Front Desk' });
  assert.deepEqual(managerDepartmentFromProfile(profile(' SECURITY ', '')), { status: 'ready', code: 'SECURITY', name: 'SECURITY' });
});

test('Manager without an active department: "none" (the server sends null)', () => {
  assert.deepEqual(managerDepartmentFromProfile(profile(null, null)), { status: 'none' });
  assert.deepEqual(managerDepartmentFromProfile(profile('', null)), { status: 'none' });
});

test('profile failure: no profile, junk, or a profile without the field is an error, never guessed as "none" or Housekeeping', () => {
  for (const value of [null, undefined, 'x', 42, {}, { username: 'm', role: 'MANAGER' }]) {
    assert.deepEqual(managerDepartmentFromProfile(value), { status: 'error' }, JSON.stringify(value));
  }
});

test('Create Task access by role and profile state', () => {
  // Admin (and any non-Manager) keeps the existing selector whatever the profile says
  for (const state of [undefined, { status: 'loading' }, { status: 'none' }, { status: 'error' }]) {
    assert.equal(createTaskDepartmentAccess('ADMIN', state), 'select');
  }
  assert.equal(createTaskDepartmentAccess('MANAGER', { status: 'loading' }), 'loading');
  assert.equal(createTaskDepartmentAccess('MANAGER', undefined), 'loading');
  assert.equal(createTaskDepartmentAccess('MANAGER', { status: 'ready', code: 'FRONT_DESK', name: 'Front Desk' }), 'locked');
  assert.equal(createTaskDepartmentAccess('MANAGER', { status: 'none' }), 'none');
  assert.equal(createTaskDepartmentAccess('MANAGER', { status: 'error' }), 'error');
});

test('Edit Task: Admin may change the department, a Manager may not', () => {
  assert.equal(canChangeTaskDepartment('ADMIN'), true);
  assert.equal(canChangeTaskDepartment('MANAGER'), false);
});

// ── wiring: Create Task page ─────────────────────────────────────────────────────────────────────────────────

test('Create Task: a Manager is never defaulted to Housekeeping; the Admin default is unchanged', () => {
  const page = read('pages', 'CreateTaskPage.jsx');
  assert.match(page, /department: isManager \? '' : 'HOUSEKEEPING',/);
  // the "first active department" fallback is skipped for a Manager, so it cannot replace their own department
  assert.match(page, /useEffect\(\(\) => \{\s*if \(isManager\) return;\s*if \(activeDepartments\.length > 0/);
});

test('Create Task: the Manager department is loaded fresh from the profile before the form is shown', () => {
  const page = read('pages', 'CreateTaskPage.jsx');
  assert.match(page, /managerDepartmentFromProfile\(await loadCurrentProfile\(\{ force: true \}\)\)/);
  assert.match(page, /useState\(\{ status: isManager \? 'loading' : 'not-needed' \}\)/);
  // the form (and so any department field) renders only for Admin ('select') or a known Manager department ('locked')
  assert.match(page, /\{\(departmentAccess === 'select' \|\| departmentAccess === 'locked'\) && \(\s*<CreateTaskForm/);
  assert.match(page, /departmentAccess === 'loading' && \(/);
  assert.match(page, /lockedDepartment=\{departmentAccess === 'locked' \? \{ code: managerDepartment\.code, name: managerDepartment\.name \} : null\}/);
});

test('Create Task: Manager without a department sees the message and no form; a failed profile read offers Try again', () => {
  const page = read('pages', 'CreateTaskPage.jsx');
  assert.match(page, /departmentAccess === 'none' && \([\s\S]*?\{MANAGER_NO_DEPARTMENT_MESSAGE\}/);
  assert.match(page, /departmentAccess === 'error' && \([\s\S]*?\{MANAGER_DEPARTMENT_LOAD_FAILED_MESSAGE\}[\s\S]*?Try again/);
  assert.ok(MANAGER_DEPARTMENT_LOAD_FAILED_MESSAGE.length > 0);
});

test('Create Task: a Manager cannot change or submit another department (change ignored, submit guarded)', () => {
  const page = read('pages', 'CreateTaskPage.jsx');
  assert.match(page, /if \(name === 'department' && isManager\) return;/);
  assert.match(page, /if \(isManager && departmentAccess !== 'locked'\) \{/);
});

// ── wiring: form and edit modal ──────────────────────────────────────────────────────────────────────────────

test('CreateTaskForm: locked department is a disabled single-option field; the Admin selector is unchanged', () => {
  const form = read('components', 'tasks', 'CreateTaskForm.jsx');
  assert.match(form, /lockedDepartment = null,/);
  assert.match(form, /\{lockedDepartment \? \(/);
  assert.match(form, /data-testid="locked-department"\s*value=\{lockedDepartment\.code\}\s*disabled/);
  assert.match(form, /<option value=\{lockedDepartment\.code\}>\{lockedDepartment\.name\}<\/option>/);
  // the Admin branch is the original selector
  assert.match(form, /onChange=\{onInputChange\}\s*disabled=\{isSubmitting \|\| isTaskLocked\}[\s\S]*?<DepartmentOptions departments=\{departments\} activeOnly \/>/);
});

test('EditTaskModal: department locked for a Manager, editable for an Admin (default), nothing else changed', () => {
  const modal = read('components', 'tasks', 'EditTaskModal.jsx');
  assert.match(modal, /canChangeDepartment = true,/);
  assert.match(modal, /data-testid="edit-task-department"\s*disabled=\{isSubmitting \|\| !canChangeDepartment\}/);
  assert.match(modal, /\{!canChangeDepartment && <p className="text-xs text-gray-500">\{DEPARTMENT_CHANGE_LOCKED_HINT\}<\/p>\}/);
  assert.ok(DEPARTMENT_CHANGE_LOCKED_HINT.length > 0);
  // the other fields still use the shared helper whose only disabling is while saving
  assert.match(modal, /const field = \(name\) => \(\{\s*value: formData\[name\] \?\? '',\s*onChange: \(e\) => onFormChange\(name, e\.target\.value\),\s*disabled: isSubmitting,/);
  const tasksPage = read('pages', 'TasksPage.jsx');
  assert.match(tasksPage, /canChangeDepartment=\{canChangeTaskDepartment\(userRole\)\}/);
});

// ── Create Task form data: requested only when the form will be shown ────────────────────────────────────────
// What the page asks for, for a role and the Manager profile state it holds: the four form reads, or nothing.
const FORM_READS = ['departments', 'staff', 'tasks', 'assignments'];
const formReads = (role, managerDepartment) =>
  (createTaskFormDataNeeded(createTaskDepartmentAccess(role, managerDepartment)) ? FORM_READS : []);

test('department-less Manager: once the profile says there is no department, none of the form data is requested', () => {
  const state = managerDepartmentFromProfile(profile(null, null));
  assert.equal(createTaskDepartmentAccess('MANAGER', state), 'none');
  assert.deepEqual(formReads('MANAGER', state), []);
  assert.deepEqual(formReads('MANAGER', managerDepartmentFromProfile(profile('', null))), []);
});

test('Manager: nothing is requested while the profile is loading or after it failed; Try again that finds a department loads it all', () => {
  assert.deepEqual(formReads('MANAGER', { status: 'loading' }), []);
  assert.deepEqual(formReads('MANAGER', undefined), []);
  const failed = managerDepartmentFromProfile(null);
  assert.deepEqual(formReads('MANAGER', failed), []);
  // retry (the existing Try again) resolves to a department: the form and its data follow
  assert.deepEqual(formReads('MANAGER', managerDepartmentFromProfile(profile('FRONT_DESK', 'Front Desk'))), FORM_READS);
});

test('regression: Manager with a department loads all the form data', () => {
  assert.deepEqual(formReads('MANAGER', managerDepartmentFromProfile(profile('SECURITY', 'Security'))), FORM_READS);
});

test('regression: Admin loads all the form data at once, whatever the (unused) Manager state', () => {
  for (const state of [{ status: 'not-needed' }, undefined, { status: 'loading' }, { status: 'none' }, { status: 'error' }]) {
    assert.deepEqual(formReads('ADMIN', state), FORM_READS, JSON.stringify(state));
  }
  assert.equal(createTaskFormDataNeeded('select'), true);
  assert.equal(createTaskFormDataNeeded('locked'), true);
  for (const access of ['loading', 'none', 'error', undefined, null, '']) {
    assert.equal(createTaskFormDataNeeded(access), false, String(access));
  }
});

test('Create Task page: departments, staff, tasks and assignments are all gated on the form being shown', () => {
  const page = read('pages', 'CreateTaskPage.jsx');
  assert.match(page, /const formDataNeeded = createTaskFormDataNeeded\(departmentAccess\);/);
  // departments: the shared hook, held back until needed (and no longer the unconditional call)
  assert.match(page, /useDepartments\(\{ enabled: formDataNeeded \}\)/);
  assert.doesNotMatch(page, /useDepartments\(\)/);
  // staff, tasks, assignments: one effect, skipped until needed, re-run when it becomes needed
  assert.match(page,
    /useEffect\(\(\) => \{\s*if \(!formDataNeeded\) return undefined;[\s\S]*?getStaff\(\),\s*getTasks\(\),\s*getAssignments\(\),[\s\S]*?\}, \[formDataNeeded\]\);/);
  // and that effect is the only place they are called
  for (const call of ['getStaff(', 'getTasks(', 'getAssignments(']) {
    assert.equal(page.split(call).length - 1, 1, `${call} must be called only inside the gated effect`);
  }
  // a genuine failure (Admin, or a Manager with a department) is still reported as before
  assert.match(page, /console\.error\('Failed to load staff and assignment data:', err\);/);
  // the explanation, the hidden form and Try again are unchanged
  assert.match(page, /departmentAccess === 'none' && \([\s\S]*?\{MANAGER_NO_DEPARTMENT_MESSAGE\}/);
  assert.match(page, /\{\(departmentAccess === 'select' \|\| departmentAccess === 'locked'\) && \(\s*<CreateTaskForm/);
  assert.match(page, /onClick=\{\(\) => loadManagerDepartment\(\)\.catch\(\(\) => setManagerDepartment\(\{ status: 'error' \}\)\)\}/);
});

test('useDepartments: optional "enabled" holds the request back; every other caller still loads at once', () => {
  const hook = read('hooks', 'useDepartments.js');
  assert.match(hook, /export default function useDepartments\(\{ enabled = true \} = \{\}\) \{/);
  assert.match(hook, /useEffect\(\(\) => \{\s*if \(!enabled\) return;\s*reload\(\);\s*\}, \[enabled, reload\]\);/);
  for (const file of [
    ['components', 'staff', 'AddStaffModal.jsx'],
    ['components', 'staff', 'EditStaffModal.jsx'],
    ['components', 'staff', 'StaffFilters.jsx'],
    ['components', 'tasks', 'EditTaskModal.jsx'],
    ['components', 'tasks', 'TaskFilters.jsx'],
  ]) {
    assert.match(read(...file), /= useDepartments\(\);/, file.join('/'));
  }
});
