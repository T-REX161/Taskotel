import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import {
  isToday,
  formatAssignedAt,
  splitAssignedAt,
  formatAssignmentStatus,
  getAssignmentStatusBadge,
  resolveAssignmentDepartment,
  calculateAssignmentSummary,
  getEligibleUnassignedTasks,
} from './assignmentUtils.js';

describe('assignmentUtils', () => {
  const refDate = new Date('2026-10-15T10:30:00Z');

  describe('isToday', () => {
    it('returns true when date matches referenceDate calendar day', () => {
      const todayISO = '2026-10-15T08:30:00Z';
      assert.strictEqual(isToday(todayISO, refDate), true);
    });

    it('returns false when date is yesterday or tomorrow', () => {
      assert.strictEqual(isToday('2026-10-14T12:00:00Z', refDate), false);
      assert.strictEqual(isToday('2026-10-16T12:00:00Z', refDate), false);
    });

    it('returns true for strings starting with "Today"', () => {
      assert.strictEqual(isToday('Today, 08:30 AM', refDate), true);
      assert.strictEqual(isToday('today at 9:00', refDate), true);
    });

    it('returns false for null, undefined, empty, or invalid dates', () => {
      assert.strictEqual(isToday(null, refDate), false);
      assert.strictEqual(isToday(undefined, refDate), false);
      assert.strictEqual(isToday('', refDate), false);
      assert.strictEqual(isToday('not-a-date', refDate), false);
    });
  });

  describe('formatAssignedAt', () => {
    it('formats today timestamp into "Today, h:mm AM/PM"', () => {
      const d = new Date(refDate);
      d.setHours(8, 30, 0, 0);
      const result = formatAssignedAt(d.toISOString(), refDate);
      assert.strictEqual(result, 'Today, 8:30 AM');
    });

    it('formats past date into "MMM D, YYYY, h:mm AM/PM"', () => {
      const pastDate = new Date('2026-10-10T14:45:00');
      const result = formatAssignedAt(pastDate, refDate);
      assert.strictEqual(result, 'Oct 10, 2026, 2:45 PM');
    });

    it('preserves already formatted strings like "Today, 08:30 AM"', () => {
      assert.strictEqual(formatAssignedAt('Today, 08:30 AM', refDate), 'Today, 08:30 AM');
    });

    it('returns "-" for null, undefined, or empty string', () => {
      assert.strictEqual(formatAssignedAt(null, refDate), '-');
      assert.strictEqual(formatAssignedAt(undefined, refDate), '-');
      assert.strictEqual(formatAssignedAt('', refDate), '-');
    });
  });

  describe('splitAssignedAt', () => {
    it('splits a past date into date line and time line without reformatting', () => {
      const formatted = formatAssignedAt(new Date('2026-10-16T14:29:00'), refDate);
      assert.strictEqual(formatted, 'Oct 16, 2026, 2:29 PM');
      assert.deepStrictEqual(splitAssignedAt(formatted), { date: 'Oct 16, 2026', time: '2:29 PM' });
    });

    it('splits same-day values into "Today" and the time', () => {
      const d = new Date(refDate);
      d.setHours(8, 30, 0, 0);
      assert.deepStrictEqual(splitAssignedAt(formatAssignedAt(d.toISOString(), refDate)), { date: 'Today', time: '8:30 AM' });
      assert.deepStrictEqual(splitAssignedAt('Today, 08:30 AM'), { date: 'Today', time: '08:30 AM' });
    });

    it('keeps the two parts identical to the single-line text (only the separator is removed)', () => {
      const formatted = formatAssignedAt(new Date('2026-01-05T00:05:00'), refDate);
      const { date, time } = splitAssignedAt(formatted);
      assert.strictEqual(`${date}, ${time}`, formatted);
      assert.deepStrictEqual([date, time], ['Jan 5, 2026', '12:05 AM']);
    });

    it('returns "-" and unparseable text whole on a single line', () => {
      assert.deepStrictEqual(splitAssignedAt(formatAssignedAt(null, refDate)), { date: '-', time: '' });
      assert.deepStrictEqual(splitAssignedAt(formatAssignedAt('not-a-date', refDate)), { date: 'not-a-date', time: '' });
      assert.deepStrictEqual(splitAssignedAt(undefined), { date: '', time: '' });
    });
  });

  describe('formatAssignmentStatus', () => {
    it('formats backend TaskStatus enum values correctly', () => {
      assert.strictEqual(formatAssignmentStatus('PENDING'), 'Pending');
      assert.strictEqual(formatAssignmentStatus('ASSIGNED'), 'Assigned');
      assert.strictEqual(formatAssignmentStatus('IN_PROGRESS'), 'In Progress');
      assert.strictEqual(formatAssignmentStatus('COMPLETED'), 'Completed');
      assert.strictEqual(formatAssignmentStatus('CANCELLED'), 'Cancelled');
    });

    it('formats legacy display strings correctly', () => {
      assert.strictEqual(formatAssignmentStatus('Active'), 'Active');
      assert.strictEqual(formatAssignmentStatus('In Progress'), 'In Progress');
      assert.strictEqual(formatAssignmentStatus('Completed'), 'Completed');
    });

    it('returns "-" for null or empty status', () => {
      assert.strictEqual(formatAssignmentStatus(null), '-');
      assert.strictEqual(formatAssignmentStatus(''), '-');
    });
  });

  describe('getAssignmentStatusBadge', () => {
    it('returns blue badge for ASSIGNED and Active', () => {
      assert.strictEqual(getAssignmentStatusBadge('ASSIGNED'), 'bg-blue-100 text-blue-800');
      assert.strictEqual(getAssignmentStatusBadge('Active'), 'bg-blue-100 text-blue-800');
    });

    it('returns blue badge for IN_PROGRESS and In Progress', () => {
      assert.strictEqual(getAssignmentStatusBadge('IN_PROGRESS'), 'bg-blue-100 text-blue-800');
      assert.strictEqual(getAssignmentStatusBadge('In Progress'), 'bg-blue-100 text-blue-800');
    });

    it('returns emerald badge for COMPLETED', () => {
      assert.strictEqual(getAssignmentStatusBadge('COMPLETED'), 'bg-emerald-100 text-emerald-800');
      assert.strictEqual(getAssignmentStatusBadge('Completed'), 'bg-emerald-100 text-emerald-800');
    });

    it('returns amber badge for PENDING', () => {
      assert.strictEqual(getAssignmentStatusBadge('PENDING'), 'bg-amber-100 text-amber-800');
    });

    it('returns gray badge for CANCELLED', () => {
      assert.strictEqual(getAssignmentStatusBadge('CANCELLED'), 'bg-gray-100 text-gray-700');
    });

    it('returns default badge for unknown or null status', () => {
      assert.strictEqual(getAssignmentStatusBadge(null), 'bg-gray-100 text-gray-800');
      assert.strictEqual(getAssignmentStatusBadge('UNKNOWN'), 'bg-gray-100 text-gray-800');
    });
  });

  describe('resolveAssignmentDepartment', () => {
    const tasks = [
      { id: 't-1', title: 'Task 1', department: 'HOUSEKEEPING' },
      { id: 't-2', title: 'Task 2', department: 'FRONT_DESK' },
    ];
    const workload = [
      { staffId: 's-1', staffName: 'Staff 1', department: 'MAINTENANCE' },
      { staffId: 's-2', staffName: 'Staff 2', department: 'SECURITY' },
    ];

    it('resolves department directly from assignment if present', () => {
      const assignment = { id: 'a-1', department: 'ROOM_SERVICE' };
      assert.strictEqual(resolveAssignmentDepartment(assignment, tasks, workload), 'Room Service');
    });

    it('resolves department from associated task via taskId', () => {
      const assignment = { id: 'a-1', taskId: 't-1', staffId: 's-other' };
      assert.strictEqual(resolveAssignmentDepartment(assignment, tasks, workload), 'Housekeeping');
    });

    it('resolves department from staff workload if task has no department or is missing', () => {
      const assignment = { id: 'a-1', taskId: 't-unknown', staffId: 's-1' };
      assert.strictEqual(resolveAssignmentDepartment(assignment, tasks, workload), 'Maintenance');
    });

    it('returns "-" when department cannot be resolved from any source', () => {
      const assignment = { id: 'a-1', taskId: 't-unknown', staffId: 's-unknown' };
      assert.strictEqual(resolveAssignmentDepartment(assignment, tasks, workload), '-');
    });

    it('handles null assignment gracefully', () => {
      assert.strictEqual(resolveAssignmentDepartment(null, tasks, workload), '-');
    });
  });

  describe('calculateAssignmentSummary', () => {
    it('returns zeros for empty assignments and tasks', () => {
      const result = calculateAssignmentSummary({ assignments: [], tasks: [], referenceDate: refDate });
      assert.deepStrictEqual(result, {
        totalAssignments: 0,
        activeAssignments: 0,
        completedToday: 0,
        unassignedTasks: 0,
      });
    });

    it('correctly calculates counts matching real backend statuses', () => {
      const todayISO = refDate.toISOString();
      const pastISO = '2026-10-10T10:00:00Z';

      const assignments = [
        // 2 active assignments
        { id: 'a-1', taskId: 't-1', taskStatus: 'ASSIGNED', assignedAt: todayISO },
        { id: 'a-2', taskId: 't-2', taskStatus: 'IN_PROGRESS', assignedAt: todayISO },
        // 1 pending assignment (also active)
        { id: 'a-3', taskId: 't-3', taskStatus: 'PENDING', assignedAt: todayISO },
        // 1 completed today
        { id: 'a-4', taskId: 't-4', taskStatus: 'COMPLETED', assignedAt: todayISO },
        // 1 completed in the past
        { id: 'a-5', taskId: 't-5', taskStatus: 'COMPLETED', assignedAt: pastISO },
        // 1 cancelled
        { id: 'a-6', taskId: 't-6', taskStatus: 'CANCELLED', assignedAt: todayISO },
      ];

      const tasks = [
        { id: 't-1', status: 'ASSIGNED' },
        { id: 't-2', status: 'IN_PROGRESS' },
        { id: 't-3', status: 'PENDING' },
        { id: 't-4', status: 'COMPLETED' },
        { id: 't-5', status: 'COMPLETED' },
        { id: 't-6', status: 'CANCELLED' },
        // 2 unassigned tasks
        { id: 't-7', status: 'PENDING' },
        { id: 't-8', status: 'PENDING' },
        // 1 cancelled task with no assignment (should NOT count as unassigned)
        { id: 't-9', status: 'CANCELLED' },
        // 1 completed task with no assignment (should NOT count as unassigned)
        { id: 't-10', status: 'COMPLETED' },
      ];

      const summary = calculateAssignmentSummary({ assignments, tasks, referenceDate: refDate });

      assert.strictEqual(summary.totalAssignments, 6, 'Total assignments must be 6');
      assert.strictEqual(summary.activeAssignments, 3, 'Active assignments (ASSIGNED, IN_PROGRESS, PENDING) must be 3');
      assert.strictEqual(summary.completedToday, 1, 'Completed today must be 1');
      assert.strictEqual(summary.unassignedTasks, 2, 'Unassigned tasks (t-7 and t-8) must be 2');
    });

    it('supports legacy mock format statuses (Active, In Progress, Completed)', () => {
      const assignments = [
        { id: '1', taskId: 't-1', status: 'Active', assignedAt: 'Today, 08:30 AM' },
        { id: '2', taskId: 't-2', status: 'In Progress', assignedAt: 'Today, 08:30 AM' },
        { id: '3', taskId: 't-3', status: 'Completed', assignedAt: 'Today, 08:30 AM' },
      ];
      const tasks = [
        { id: 't-1', status: 'ASSIGNED' },
        { id: 't-2', status: 'IN_PROGRESS' },
        { id: 't-3', status: 'COMPLETED' },
        { id: 't-4', status: 'PENDING' },
      ];

      const summary = calculateAssignmentSummary({ assignments, tasks, referenceDate: refDate });
      assert.strictEqual(summary.totalAssignments, 3);
      assert.strictEqual(summary.activeAssignments, 2);
      assert.strictEqual(summary.completedToday, 1);
      assert.strictEqual(summary.unassignedTasks, 1);
    });

    it('infers status from associated task if assignment status is missing', () => {
      const assignments = [
        { id: 'a-1', taskId: 't-1' }, // no status on assignment
      ];
      const tasks = [
        { id: 't-1', status: 'IN_PROGRESS' },
      ];

      const summary = calculateAssignmentSummary({ assignments, tasks, referenceDate: refDate });
      assert.strictEqual(summary.totalAssignments, 1);
      assert.strictEqual(summary.activeAssignments, 1);
      assert.strictEqual(summary.unassignedTasks, 0);
    });

    it('uses task updatedAt for completed today when present', () => {
      const assignments = [
        { id: 'a-1', taskId: 't-1', taskStatus: 'COMPLETED', assignedAt: '2026-10-10T10:00:00Z' },
      ];
      const tasks = [
        // Task completed today, even though assigned earlier
        { id: 't-1', status: 'COMPLETED', updatedAt: refDate.toISOString() },
      ];

      const summary = calculateAssignmentSummary({ assignments, tasks, referenceDate: refDate });
      assert.strictEqual(summary.completedToday, 1, 'Should count as completed today due to task.updatedAt');
    });

    it('handles non-array parameters gracefully without crashing', () => {
      const summary = calculateAssignmentSummary({ assignments: null, tasks: null });
      assert.deepStrictEqual(summary, {
        totalAssignments: 0,
        activeAssignments: 0,
        completedToday: 0,
        unassignedTasks: 0,
      });
    });
  });

  describe('getEligibleUnassignedTasks', () => {
    it('returns empty array when tasks is null, undefined, or empty', () => {
      assert.deepStrictEqual(getEligibleUnassignedTasks(), []);
      assert.deepStrictEqual(getEligibleUnassignedTasks({ tasks: null, assignments: null }), []);
      assert.deepStrictEqual(getEligibleUnassignedTasks({ tasks: [], assignments: [] }), []);
    });

    it('filters out tasks that are present in assignments', () => {
      const tasks = [
        { id: 't-1', title: 'Task 1', status: 'PENDING' },
        { id: 't-2', title: 'Task 2', status: 'PENDING' },
      ];
      const assignments = [
        { id: 'a-1', taskId: 't-1' },
      ];

      const result = getEligibleUnassignedTasks({ tasks, assignments });
      assert.strictEqual(result.length, 1);
      assert.strictEqual(result[0].id, 't-2');
    });

    it('filters out completed and cancelled tasks even if unassigned', () => {
      const tasks = [
        { id: 't-1', title: 'Pending Task', status: 'PENDING' },
        { id: 't-2', title: 'Completed Task', status: 'COMPLETED' },
        { id: 't-3', title: 'Cancelled Task', status: 'CANCELLED' },
      ];
      const assignments = [];

      const result = getEligibleUnassignedTasks({ tasks, assignments });
      assert.strictEqual(result.length, 1);
      assert.strictEqual(result[0].id, 't-1');
    });

    it('filters out tasks with ASSIGNED or IN_PROGRESS status without active assignment record', () => {
      const tasks = [
        { id: 't-1', title: 'Pending Task', status: 'PENDING' },
        { id: 't-2', title: 'Assigned Task', status: 'ASSIGNED' },
        { id: 't-3', title: 'In Progress Task', status: 'IN_PROGRESS' },
      ];
      const assignments = [];

      const result = getEligibleUnassignedTasks({ tasks, assignments });
      assert.strictEqual(result.length, 1);
      assert.strictEqual(result[0].id, 't-1');
    });
  });
});

