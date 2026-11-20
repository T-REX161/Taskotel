import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { latestActiveTasks } from '../../utils/myWorkload.js';

const src = (file) => readFileSync(fileURLToPath(new URL(`../../${file}`, import.meta.url)), 'utf8').replace(/\r\n/g, '\n');

test('the Dashboard shows the Staff view to Staff only; the Admin / Manager layout and components are untouched', () => {
  const page = src('pages/Dashboard.jsx');
  assert.ok(page.includes('{isStaff ? (\n        <StaffDashboardView data={data} tasks={tasks} />\n      ) : (\n        <>'));
  for (const adminPart of ['<SummaryCards data={data} isStaff={isStaff} />', '<WorkloadOverview', '<TaskStatusSummary', '<OperationsInsights insights={insights} />', '<DepartmentBreakdown data={data} />']) {
    assert.ok(page.includes(adminPart), adminPart);
  }
  // the shared dashboard components are not edited for this change
  assert.ok(!src('components/dashboard/SummaryCards.jsx').includes('StaffDashboard'));
  assert.ok(!src('components/dashboard/WorkloadOverview.jsx').includes('StaffDashboard'));
  assert.ok(!src('components/dashboard/TaskStatusSummary.jsx').includes('StaffDashboard'));
});

test('a Staff member loads only their own summary and their own task list; refresh and polling are unchanged', () => {
  const page = src('pages/Dashboard.jsx');
  assert.ok(page.includes("...(getUserRole() !== 'STAFF' ? [loadStaffWorkload(), loadRecommendations(), loadTasks()] : []),"), 'no organisation-wide sources for Staff');
  assert.ok(page.includes("...(getUserRole() === 'STAFF' ? [loadTasks()] : []),"), 'the only extra Staff source is their own tasks (the server returns just those)');
  assert.ok(page.includes('startPolling({ refresh: () => refreshRef.current(true), intervalMs: DASHBOARD_REFRESH_MS })'));
});

test('the Staff dashboard has exactly four figures and none of the detailed workload ones', () => {
  const view = src('components/dashboard/StaffDashboardParts.jsx');
  const titles = [...view.matchAll(/<StatCard title="([^"]+)"/g)].map((m) => m[1]);
  assert.deepEqual(titles, ['My Active Tasks', 'Pending', 'In Progress', 'Completed']);
  for (const gone of ['Workload Duration', 'Workload Status', 'My Availability', 'Task Status Overview', 'Recent Assignments']) assert.ok(!view.includes(gone), gone);
});

test('My Tasks previews at most 3 own tasks, flags overdue and missing due dates, and has the specified empty state', () => {
  const view = src('components/dashboard/StaffDashboardParts.jsx');
  assert.ok(view.includes('latestActiveTasks(tasks?.data, 3)'));
  assert.ok(view.includes('isTaskOverdue(task)') && view.includes('>Overdue<') && view.includes("task.dueDate ? formatDueDate(task.dueDate, task.deadlineTime) : 'No due date'"));
  assert.ok(view.includes('No active tasks') && view.includes('Your assigned tasks will appear here.'));
  assert.ok((view.match(/<Link to="\/tasks"/g) || []).length === 2, 'View All Tasks link (with tasks and in the empty state)');
  assert.ok(!view.includes('Quick Access') && !view.includes('/workload') && !view.includes('/profile'), 'no Quick Access section or shortcut cards');
  assert.ok(view.includes('<MyTasksSection tasks={tasks} />') && !view.includes('lg:col-span'), 'My Tasks takes the full width');
  assert.ok(!/<table|getStaff\b|getAssignments|getWorkload\b|staffWorkload/.test(view), 'no staff list, no other people, no extra data sources');
});

test('latestActiveTasks: the newest active tasks first, at most the limit, nothing completed or cancelled', () => {
  const tasks = [
    { id: 1, title: 'old', status: 'PENDING', createdAt: '2026-01-01T10:00:00' },
    { id: 2, title: 'newest', status: 'ASSIGNED', createdAt: '2026-03-01T10:00:00' },
    { id: 3, title: 'done', status: 'COMPLETED', createdAt: '2026-04-01T10:00:00' },
    { id: 4, title: 'mid', status: 'IN_PROGRESS', createdAt: '2026-02-01T10:00:00' },
    { id: 5, title: 'cancelled', status: 'CANCELLED', createdAt: '2026-05-01T10:00:00' },
    { id: 6, title: 'older', status: 'PENDING', createdAt: '2025-12-01T10:00:00' },
  ];
  assert.deepEqual(latestActiveTasks(tasks, 3).map((t) => t.id), [2, 4, 1]);
  assert.deepEqual(latestActiveTasks(tasks).length, 3);
  assert.deepEqual(latestActiveTasks(tasks, 0), []);
  assert.deepEqual(latestActiveTasks(null), []);
  assert.deepEqual(latestActiveTasks([{ id: 9, title: 'b', status: 'PENDING' }, { id: 8, title: 'a', status: 'PENDING' }]).map((t) => t.id), [8, 9], 'no createdAt: stable by title');
});
