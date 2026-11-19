import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { managedDepartmentList } from './managedDepartments.js';
import { breakdownRows, coveringLine } from './departmentBreakdown.js';

const read = (...parts) => readFileSync(new URL(`../${parts.join('/')}`, import.meta.url), 'utf8').replace(/\r\n/g, '\n');
const dept = (code, managed) => ({ code, name: code, active: true, managed });

// ── the departments a Manager's forms and filters offer ───────────────────────────────────────────────────

test('a Manager is offered only the departments they manage, their own first', () => {
  const list = [dept('FRONT_DESK', true), dept('HOUSEKEEPING', false), dept('SECURITY', true), dept('MAINTENANCE', false)];
  assert.deepEqual(managedDepartmentList(list, 'SECURITY').map((d) => d.code), ['SECURITY', 'FRONT_DESK']);
  assert.deepEqual(managedDepartmentList(list, 'front_desk').map((d) => d.code), ['FRONT_DESK', 'SECURITY'], 'case does not matter');
  assert.deepEqual(managedDepartmentList(list, '').map((d) => d.code), ['FRONT_DESK', 'SECURITY'], 'no profile: the order the server gave');
});

test('only a real true for "managed" counts: a department the Manager does not manage is never offered', () => {
  const list = [dept('A', 'true'), dept('B', 1), { code: 'C', name: 'C' }, null, dept('D', true)];
  assert.deepEqual(managedDepartmentList(list, 'D').map((d) => d.code), ['D']);
  assert.deepEqual(managedDepartmentList(null, 'D'), []);
});

test('useDepartments: a Manager gets the managed list; nobody gets a built-in fallback, not even while loading or when it fails', () => {
  const hook = read('hooks', 'useDepartments.js');
  assert.ok(hook.includes("const isManager = getUserRole() === 'MANAGER';"));
  assert.ok(hook.includes('useState([])') && !hook.includes('FALLBACK_DEPARTMENTS') && !hook.includes('BUILT_IN_DEPARTMENT_NAMES'));
  assert.ok(hook.includes('managedDepartmentList(list, profile?.departmentCode)'));
  assert.ok(hook.includes('setDepartments(Array.isArray(list) ? list : [])'), 'Admin and Staff get exactly the list the server returned');
});

test('every department dropdown and filter goes through the shared hook, so each one follows the Manager rule', () => {
  for (const file of [
    ['components', 'staff', 'AddStaffModal.jsx'],
    ['components', 'staff', 'EditStaffModal.jsx'],
    ['components', 'staff', 'StaffFilters.jsx'],
    ['components', 'tasks', 'EditTaskModal.jsx'],
    ['components', 'tasks', 'TaskFilters.jsx'],
    ['components', 'assignments', 'AssignmentFilters.jsx'],
    ['pages', 'CreateTaskPage.jsx'],
  ]) {
    assert.ok(read(...file).includes('useDepartments('), file.join('/'));
  }
});

test('Add Staff starts on the first offered department, which for a Manager is their own', () => {
  const modal = read('components', 'staff', 'AddStaffModal.jsx');
  assert.ok(modal.includes('setFormData((prev) => ({ ...prev, department: activeDepartments[0].code }));'));
});

// ── the Manager dashboard ─────────────────────────────────────────────────────────────────────────────────

const entry = (code, name, primary, extra = {}) => ({
  departmentCode: code, departmentName: name, primary, totalStaff: 4, availableStaff: 3, unavailableStaff: 1, inactiveStaff: 0,
  totalTasks: 9, pendingTasks: 2, assignedTasks: 1, inProgressTasks: 1, completedTasks: 5, cancelledTasks: 0, totalActiveAssignments: 2,
  totalStaffWithWorkload: 4, lowWorkloadStaff: 1, normalWorkloadStaff: 2, highWorkloadStaff: 1, atRiskStaff: 0, ...extra,
});

test('the breakdown shows with two or more departments, in the order the server sent (primary first)', () => {
  const data = { departmentBreakdown: [entry('FRONT_DESK', 'Front Desk', true), entry('SECURITY', 'Security', false, { totalStaff: 7 })] };
  const rows = breakdownRows(data);
  assert.deepEqual(rows.map((r) => r.code), ['FRONT_DESK', 'SECURITY']);
  assert.equal(rows[0].primary, true);
  assert.equal(rows[0].openTasks, 4, 'pending + assigned + in progress');
  assert.equal(rows[1].totalStaff, 7);
  assert.equal(coveringLine(data), 'Covering Front Desk and Security');
  assert.equal(coveringLine({ departmentBreakdown: [entry('A', 'A', true), entry('B', 'B', false), entry('C', 'C', false)] }), 'Covering A, B and C');
});

test('one department, no breakdown (Admin and Staff never get one), or odd data: nothing is shown and nothing breaks', () => {
  assert.deepEqual(breakdownRows({ departmentBreakdown: [entry('A', 'A', true)] }), []);
  for (const data of [null, undefined, {}, { departmentBreakdown: null }, { departmentBreakdown: 'x' }, { departmentBreakdown: [null, {}] }]) {
    assert.deepEqual(breakdownRows(data), []);
    assert.equal(coveringLine(data), '');
  }
});

test('missing or non-numeric figures read as 0, never NaN or undefined', () => {
  const rows = breakdownRows({ departmentBreakdown: [entry('A', 'A', true, { totalStaff: undefined, pendingTasks: 'x' }), entry('B', null, false)] });
  assert.equal(rows[0].totalStaff, 0);
  assert.equal(rows[0].openTasks, 2);
  assert.equal(rows[1].name, 'B', 'a missing name falls back to the code');
});

test('the breakdown table renders nothing without rows, lists every row with its figures and marks the primary one', () => {
  const component = read('components', 'dashboard', 'DepartmentBreakdown.jsx');
  assert.ok(component.includes('const rows = breakdownRows(data);') && component.includes('if (rows.length === 0) return null;'));
  assert.ok(component.includes('{rows.map((row) => ('));
  assert.ok(component.includes('data-testid={`department-breakdown-${row.code}`}'));
  assert.ok(component.includes('{row.primary && '));
  for (const figure of ['row.totalStaff', 'row.availableStaff', 'row.openTasks', 'row.activeAssignments', 'row.highWorkloadStaff']) assert.ok(component.includes(figure), figure);
});

test('the Dashboard shows the covering line and the breakdown from the summary alone', () => {
  const page = read('pages', 'Dashboard.jsx');
  assert.ok(page.includes('coveringLine(data)') && page.includes('<DepartmentBreakdown data={data} />'));
});

// ── Cross-Department Assistance: figures follow what the server sent ──────────────────────────────────────

test('the preview says figures are withheld only for lending departments without figures, not for the Manager managed ones', () => {
  const preview = read('components', 'workload', 'support', 'SupportImpactPreview.jsx');
  assert.ok(preview.includes('const donorCodes = new Set(donors.map((d) => d.code));'));
  assert.ok(preview.includes('!donorCodes.has(item.homeDepartmentCode)'));
  assert.ok(preview.includes('{donors.map((donor) => ('), 'managed donors with figures are drawn');
});
