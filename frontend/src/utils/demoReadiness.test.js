import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { SHIFT_MINUTES, calculateWorkloadPercentage } from './workloadUtils.js';
import {
  DASHBOARD_LOAD_FAILED_MESSAGE,
  NO_ACTIVE_DEPARTMENT_SERVER_MESSAGE,
  dashboardLoadErrorMessage,
  durationPercent,
  loadPercent,
} from './dashboardView.js';
import { countTasksByStatus, isTaskOverdue } from '../components/tasks/taskFormatters.js';
import { isAssignmentTaskFinished } from './assignmentUtils.js';

const read = (...parts) => readFileSync(new URL(`../${parts.join('/')}`, import.meta.url), 'utf8');

// ── 1. One workload percentage everywhere (Dashboard = Workload page = Assign dialog) ──────────────────────────────

test('workload %: the Dashboard and the Workload page give the same number for the same minutes (one 480-minute shift)', () => {
  assert.equal(SHIFT_MINUTES, 480);
  for (const minutes of [0, 1, 45, 120, 240, 270, 300, 360, 361, 479, 480, 600, 2000]) {
    assert.equal(loadPercent(minutes), calculateWorkloadPercentage(minutes), `${minutes} min`);
  }
  assert.equal(loadPercent(240), 50);
  assert.equal(loadPercent(360), 75); // the At-risk threshold is three quarters of a shift
  assert.equal(durationPercent(480), 100);
  assert.equal(durationPercent(960), 100);
});

test('workload %: the dashboard views no longer use their own 360-minute scale', () => {
  const list = read('components', 'dashboard', 'StaffWorkloadList.jsx');
  const table = read('components', 'dashboard', 'StaffWorkloadTable.jsx');
  assert.ok(list.includes('aria-valuemax={SHIFT_MINUTES}') && list.includes('{loadPercent(row.totalEstimatedDuration)}%'));
  assert.ok(table.includes('width: `${durationPercent(data.workload.totalEstimatedDuration)}%`'));
  for (const source of [list, table, read('utils', 'dashboardView.js')]) {
    assert.doesNotMatch(source, /\/ 360\)|aria-valuemax=\{360\}/);
  }
  // the Workload page and the Assign dialog keep the shared shift percentage
  assert.ok(read('pages', 'WorkloadPage.jsx').includes('calculateWorkloadPercentage(safeDuration, 480)'));
});

// ── 2. Overdue marker on the Tasks list ───────────────────────────────────────────────────────────────────────────

const NOW = new Date(2026, 10, 14, 15, 30); // 14 Nov 2026, 15:30 local

test('overdue: an open task due before today is overdue; today only once its deadline time has passed', () => {
  assert.equal(isTaskOverdue({ status: 'ASSIGNED', dueDate: '2026-10-13' }, NOW), true);
  assert.equal(isTaskOverdue({ status: 'PENDING', dueDate: '2026-11-13', deadlineTime: '23:59:00' }, NOW), true);
  assert.equal(isTaskOverdue({ status: 'IN_PROGRESS', dueDate: '2026-11-14', deadlineTime: '15:29:00' }, NOW), true);
  assert.equal(isTaskOverdue({ status: 'IN_PROGRESS', dueDate: '2026-11-14', deadlineTime: '15:30' }, NOW), false);
  assert.equal(isTaskOverdue({ status: 'ASSIGNED', dueDate: '2026-11-14' }, NOW), false, 'due today with no time: not overdue until tomorrow');
  assert.equal(isTaskOverdue({ status: 'ASSIGNED', dueDate: '2026-11-15', deadlineTime: '00:01' }, NOW), false);
  assert.equal(isTaskOverdue({ status: 'ASSIGNED', dueDate: '2027-01-01' }, NOW), false);
});

test('overdue: finished tasks, tasks without a due date and junk are never overdue', () => {
  assert.equal(isTaskOverdue({ status: 'COMPLETED', dueDate: '2026-10-01' }, NOW), false);
  assert.equal(isTaskOverdue({ status: 'CANCELLED', dueDate: '2026-10-01' }, NOW), false);
  assert.equal(isTaskOverdue({ status: 'ASSIGNED', dueDate: null, deadlineTime: '08:00' }, NOW), false);
  for (const task of [null, undefined, {}, { status: 'ASSIGNED', dueDate: 'soon' }, { status: 'ASSIGNED', dueDate: '2026-11' }]) {
    assert.equal(isTaskOverdue(task, NOW), false, JSON.stringify(task));
  }
});

test('overdue: the Tasks table marks overdue rows next to the unchanged due date', () => {
  const table = read('components', 'tasks', 'TasksTable.jsx');
  assert.ok(table.includes('const overdue = isTaskOverdue(task);'));
  assert.match(table, /data-testid="task-overdue"[^>]*>\s*Overdue\s*<\/span>/);
  assert.equal((table.match(/\{overdueBadge\}/g) || []).length, 2, 'shown with a date, with or without a time');
});

// ── 3. Tasks summary cards add up ─────────────────────────────────────────────────────────────────────────────────

test('summary cards: every status is counted, so the cards add up to the total', () => {
  const tasks = ['PENDING', 'ASSIGNED', 'ASSIGNED', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED', 'CANCELLED'].map((status) => ({ status }));
  const counts = countTasksByStatus(tasks);
  assert.deepEqual(counts, { total: 7, pending: 1, assigned: 2, inProgress: 1, completed: 1, cancelled: 2 });
  assert.equal(counts.pending + counts.assigned + counts.inProgress + counts.completed + counts.cancelled, counts.total);
  // the real demo data shape: 15 assigned and 3 pending used to show Total 18 but only 3 across the cards
  const demo = countTasksByStatus([...Array(15).fill({ status: 'ASSIGNED' }), ...Array(3).fill({ status: 'PENDING' })]);
  assert.equal(demo.assigned, 15);
  assert.deepEqual(countTasksByStatus(null), { total: 0, pending: 0, assigned: 0, inProgress: 0, completed: 0, cancelled: 0 });
});

test('summary cards: an Assigned card, and the cancelled count named under the total only when there are any', () => {
  const cards = read('components', 'tasks', 'TaskSummaryCards.jsx');
  for (const title of ['Total Tasks', 'Pending', 'Assigned', 'In Progress', 'Completed']) {
    assert.ok(cards.includes(`title: '${title}'`), title);
  }
  assert.ok(cards.includes("note: counts.cancelled > 0 ? `incl. ${counts.cancelled} cancelled` : null,"));
  assert.ok(cards.includes('useMemo(() => countTasksByStatus(tasks), [tasks])'));
});

// ── 4. Reassign is not offered for a finished task on the Assignments page ───────────────────────────────────────

test('reassign: a completed or cancelled task is finished; the loaded task list wins over the row', () => {
  const tasks = [{ id: 't1', status: 'COMPLETED' }, { id: 't2', status: 'ASSIGNED' }, { id: 't3', status: 'CANCELLED' }];
  assert.equal(isAssignmentTaskFinished({ taskId: 't1', taskStatus: 'ASSIGNED' }, tasks), true);
  assert.equal(isAssignmentTaskFinished({ taskId: 't3' }, tasks), true);
  assert.equal(isAssignmentTaskFinished({ taskId: 't2', taskStatus: 'COMPLETED' }, tasks), false);
  assert.equal(isAssignmentTaskFinished({ taskId: 'gone', taskStatus: 'COMPLETED' }, tasks), true, 'falls back to the row status');
  assert.equal(isAssignmentTaskFinished({ taskId: 'gone', taskStatus: 'IN_PROGRESS' }, []), false);
  assert.equal(isAssignmentTaskFinished(null, tasks), false);
});

test('reassign: the Assignments menu shows a disabled item for a finished task and never opens its dialog', () => {
  const page = read('pages', 'AssignmentsPage.jsx');
  assert.match(page, /canManageAssignments && isAssignmentTaskFinished\(activeMenu\.assignment, tasks\) && \(\s*<button[\s\S]*?disabled\s*title="Cannot reassign completed or cancelled task"/);
  assert.match(page, /canManageAssignments && !isAssignmentTaskFinished\(activeMenu\.assignment, tasks\) && \(\s*<button[\s\S]*?onClick=\{handleMenuReassign\}/);
  assert.ok(page.includes('if (assignment && !isAssignmentTaskFinished(assignment, tasks)) handleOpenReassignModal(assignment);'));
  assert.ok(page.includes(`querySelectorAll('[role="menuitem"]:not([disabled])')`), 'arrow keys skip the disabled item');
});

// ── 5. The dashboard explains a refusal instead of a generic failure ─────────────────────────────────────────────

test('dashboard error: a Manager without an active department is told why and what to do', () => {
  const err = Object.assign(new Error(NO_ACTIVE_DEPARTMENT_SERVER_MESSAGE), { status: 403 });
  assert.equal(dashboardLoadErrorMessage(err), 'Your account is not assigned to an active department. Ask an Admin to assign you to a department.');
  // the constant is exactly the backend's wording (CurrentUserScopeResolver.MANAGER_NOT_ASSIGNED_MESSAGE)
  const resolver = readFileSync(new URL('../../../backend/src/main/java/com/hotel/workload/security/CurrentUserScopeResolver.java', import.meta.url), 'utf8');
  assert.ok(resolver.includes(`MANAGER_NOT_ASSIGNED_MESSAGE = "${NO_ACTIVE_DEPARTMENT_SERVER_MESSAGE}";`));
});

test('dashboard error: any other refusal shows its own message; everything else keeps the generic one', () => {
  assert.equal(dashboardLoadErrorMessage(Object.assign(new Error('Staff record not found'), { status: 403 })), 'Staff record not found');
  assert.equal(dashboardLoadErrorMessage(Object.assign(new Error('A server error occurred.'), { status: 500 })), DASHBOARD_LOAD_FAILED_MESSAGE);
  assert.equal(dashboardLoadErrorMessage(new Error('Failed to fetch')), DASHBOARD_LOAD_FAILED_MESSAGE);
  assert.equal(dashboardLoadErrorMessage(Object.assign(new Error('  '), { status: 403 })), DASHBOARD_LOAD_FAILED_MESSAGE);
  assert.equal(dashboardLoadErrorMessage(undefined), DASHBOARD_LOAD_FAILED_MESSAGE);
  assert.equal(DASHBOARD_LOAD_FAILED_MESSAGE, 'Unable to load dashboard data. Please try again.');
  assert.ok(read('pages', 'Dashboard.jsx').includes('if (!silent) setError(dashboardLoadErrorMessage(err));'));
});
