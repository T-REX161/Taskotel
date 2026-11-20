import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { activeTasksOf, isSupportTask, personalWorkload, taskBreakdown } from './myWorkload.js';

const src = (file) => readFileSync(fileURLToPath(new URL(`../${file}`, import.meta.url)), 'utf8');

test('personalWorkload reads the server record and measures it against the 480-minute shift', () => {
  const w = personalWorkload({ workload: { totalEstimatedDuration: 240, activeTaskCount: 3, workloadStatus: 'normal', availability: 'AVAILABLE' } });
  assert.deepEqual(w, { activeTasks: 3, minutes: 240, capacity: 480, percent: 50, status: 'NORMAL', availability: 'AVAILABLE', homeDepartment: null, supportDepartmentName: null });
});

test('personalWorkload keeps cross-department support minutes (already in the server total) and clamps the bar at 100%', () => {
  const w = personalWorkload({ workload: { totalEstimatedDuration: 600, activeTaskCount: 5, workloadStatus: 'AT_RISK', supportDepartmentName: 'Security' } });
  assert.equal(w.minutes, 600);
  assert.equal(w.percent, 100);
  assert.equal(w.status, 'AT_RISK');
  assert.equal(w.supportDepartmentName, 'Security');
});

test('personalWorkload returns null (never invents figures) when there is no record', () => {
  for (const bad of [null, undefined, {}, { workload: null }, 'x']) assert.equal(personalWorkload(bad), null);
});

test('activeTasksOf keeps only active tasks: soonest due first, undated last, then most urgent', () => {
  const tasks = [
    { id: 1, title: 'done', status: 'COMPLETED', dueDate: '2026-01-01' },
    { id: 2, title: 'undated', status: 'PENDING', priority: 'URGENT' },
    { id: 3, title: 'later', status: 'ASSIGNED', dueDate: '2026-02-02', priority: 'LOW' },
    { id: 4, title: 'soon low', status: 'IN_PROGRESS', dueDate: '2026-02-01', priority: 'LOW' },
    { id: 5, title: 'soon urgent', status: 'PENDING', dueDate: '2026-02-01', priority: 'URGENT' },
    { id: 6, title: 'cancelled', status: 'CANCELLED' },
  ];
  assert.deepEqual(activeTasksOf(tasks).map((t) => t.id), [5, 4, 3, 2]);
  assert.deepEqual(activeTasksOf(null), []);
});

test('taskBreakdown counts each task once by its real status and ignores cancelled / unknown ones', () => {
  const tasks = [
    { id: 1, status: 'PENDING' }, { id: 2, status: 'ASSIGNED' }, { id: 3, status: 'ASSIGNED' }, { id: 4, status: 'IN_PROGRESS' },
    { id: 5, status: 'COMPLETED' }, { id: 6, status: 'COMPLETED' }, { id: 5, status: 'COMPLETED' }, // id 5 repeated: counted once
    { id: 7, status: 'CANCELLED' }, { id: 8, status: 'WHATEVER' }, null,
  ];
  assert.deepEqual(taskBreakdown(tasks), { pending: 1, assigned: 2, inProgress: 1, completed: 2, total: 6 });
});

test('taskBreakdown is all zeros for no tasks or bad input (nothing invented)', () => {
  for (const none of [[], null, undefined, 'x', {}]) assert.deepEqual(taskBreakdown(none), { pending: 0, assigned: 0, inProgress: 0, completed: 0, total: 0 });
});

test('isSupportTask is true only for a task of another department than the person home department', () => {
  const me = { homeDepartment: 'HOUSEKEEPING' };
  assert.equal(isSupportTask({ department: 'SECURITY' }, me), true);
  assert.equal(isSupportTask({ department: 'HOUSEKEEPING' }, me), false);
  assert.equal(isSupportTask({ department: 'SECURITY' }, { homeDepartment: null }), false);
  assert.equal(isSupportTask({}, me), false);
});

test('the page handles an empty list, missing due dates and missing durations without inventing values', () => {
  const page = src('pages/MyWorkloadPage.jsx');
  assert.ok(page.includes('data-testid="no-active-tasks"') && page.includes('No active tasks') && page.includes('Go to My Tasks'), 'intentional empty state with a link to My Tasks');
  assert.ok(page.includes("task.dueDate ? formatDueDate(task.dueDate, task.deadlineTime) : 'No due date'"));
  assert.ok(page.includes("formatDuration(task.estimatedDuration, 'No estimate')"));
  assert.ok(page.includes('{personal.minutes} / {personal.capacity} mins'), 'minutes shown as used / 480');
  assert.ok(page.includes('My Task Breakdown') && page.includes('taskBreakdown(tasks)'), 'breakdown comes from the own task list');
});

test('the Staff My Workload page shows only personal data and requests nothing department-wide', () => {
  const page = src('pages/MyWorkloadPage.jsx');
  for (const forbidden of ['Workload by Department', 'Total Department Load', 'Staff Workload Distribution', 'Workload Thresholds']) {
    assert.ok(!page.toLowerCase().includes(forbidden.toLowerCase()), `not shown to Staff: ${forbidden}`);
  }
  assert.ok(!/getStaffWorkload|getWorkloadDepartments|getStaff\b|getAssignments|getRecommendations/.test(page), 'no organisation-wide data sources');
  assert.ok(page.includes('getDashboardSummary()') && page.includes('getTasks()'), 'only the own summary and the own tasks');
  assert.ok(page.includes('personal.capacity') && page.includes('personal.percent') && !/\b480\b/.test(page), 'the shift length and percentage come from the shared helper, not a number typed in the page');
  assert.ok(!/workloadStatus\s*=|status\s*=\s*(percent|personal\.percent)/.test(page) && page.includes('getWorkloadProgressBarColor(personal.status)'), 'the status is the server\'s; the page never recomputes it');
  assert.ok(page.includes('startPolling({ refresh: () => refreshRef.current(true), intervalMs: REFRESH_MS })') && page.includes('const REFRESH_MS = 30000;'), 'keeps the 30-second refresh of the Workload page');
  assert.ok(page.includes('if (!silent) setError('), 'a failed background refresh keeps what is on screen');
});

test('/workload shows My Workload to Staff only; Admin and Manager keep the department Workload page', () => {
  const route = src('pages/WorkloadRoute.jsx');
  assert.ok(route.includes("normalizeRole(getUserRole()) === 'STAFF' ? <MyWorkloadPage /> : <WorkloadPage />"));
  assert.ok(src('App.jsx').includes('<Route path="/workload" element={<WorkloadRoute />} />'));
});
