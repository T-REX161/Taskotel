import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import {
  DEPARTMENT_CHANGE_LOCKED_HINT,
  MANAGER_DEPARTMENT_LOAD_FAILED_MESSAGE,
  MANAGER_NO_DEPARTMENT_MESSAGE,
  canChangeTaskDepartment,
  createTaskDepartmentAccess,
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
