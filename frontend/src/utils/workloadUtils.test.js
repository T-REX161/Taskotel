import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import {
  deriveWorkloadStatus,
  getWorkloadStatusBadge,
  getWorkloadProgressBarColor,
  getDepartmentLevelBarColor,
  departmentLevelLabel,
  formatUtilization,
  parseDepartmentCapacityAnswer,
  describeDepartmentRisk,
  getDepartmentLegendRanges,
  levelForUtilization,
  calculateWorkloadPercentage,
  calculateProjectedWorkload,
  calculateWorkloadSummary,
  calculateDepartmentWorkloads,
  sortWorkloadAlphabetically } from './workloadUtils.js';

describe('workloadUtils', () => {
  describe('deriveWorkloadStatus', () => {
    it('returns LOW for 0 to 120 minutes', () => {
      assert.strictEqual(deriveWorkloadStatus(0), 'LOW');
      assert.strictEqual(deriveWorkloadStatus(45), 'LOW');
      assert.strictEqual(deriveWorkloadStatus(120), 'LOW');
    });

    it('returns NORMAL for 121 to 240 minutes', () => {
      assert.strictEqual(deriveWorkloadStatus(121), 'NORMAL');
      assert.strictEqual(deriveWorkloadStatus(180), 'NORMAL');
      assert.strictEqual(deriveWorkloadStatus(240), 'NORMAL');
    });

    it('returns HIGH for 241 to 360 minutes', () => {
      assert.strictEqual(deriveWorkloadStatus(241), 'HIGH');
      assert.strictEqual(deriveWorkloadStatus(300), 'HIGH');
      assert.strictEqual(deriveWorkloadStatus(360), 'HIGH');
    });

    it('returns AT_RISK for > 360 minutes', () => {
      assert.strictEqual(deriveWorkloadStatus(361), 'AT_RISK');
      assert.strictEqual(deriveWorkloadStatus(480), 'AT_RISK');
      assert.strictEqual(deriveWorkloadStatus(600), 'AT_RISK');
    });

    it('handles string numbers, null, undefined, and negative inputs safely', () => {
      assert.strictEqual(deriveWorkloadStatus('90'), 'LOW');
      assert.strictEqual(deriveWorkloadStatus('200'), 'NORMAL');
      assert.strictEqual(deriveWorkloadStatus('350'), 'HIGH');
      assert.strictEqual(deriveWorkloadStatus('400'), 'AT_RISK');
      assert.strictEqual(deriveWorkloadStatus(null), 'LOW');
      assert.strictEqual(deriveWorkloadStatus(undefined), 'LOW');
      assert.strictEqual(deriveWorkloadStatus(-10), 'LOW');
      assert.strictEqual(deriveWorkloadStatus('invalid'), 'LOW');
    });
  });

  describe('getWorkloadStatusBadge and getWorkloadProgressBarColor', () => {
    it('returns correct badge classes for each status', () => {
      assert.ok(getWorkloadStatusBadge('LOW').includes('text-blue-800'));
      assert.ok(getWorkloadStatusBadge('NORMAL').includes('text-emerald-800'));
      assert.ok(getWorkloadStatusBadge('HIGH').includes('text-orange-800'));
      assert.ok(getWorkloadStatusBadge('AT_RISK').includes('text-red-800'));
      assert.ok(getWorkloadStatusBadge('UNKNOWN').includes('text-gray-800'));
    });

    it('returns correct progress bar color classes for each status', () => {
      assert.strictEqual(getWorkloadProgressBarColor('LOW'), 'bg-blue-500');
      assert.strictEqual(getWorkloadProgressBarColor('NORMAL'), 'bg-emerald-500');
      assert.strictEqual(getWorkloadProgressBarColor('HIGH'), 'bg-orange-500');
      assert.strictEqual(getWorkloadProgressBarColor('AT_RISK'), 'bg-red-500');
      assert.strictEqual(getWorkloadProgressBarColor(null), 'bg-gray-400');
    });
  });

  describe('calculateWorkloadPercentage', () => {
    it('calculates percentage of a standard 480-minute shift correctly', () => {
      assert.strictEqual(calculateWorkloadPercentage(0), 0);
      assert.strictEqual(calculateWorkloadPercentage(120), 25);
      assert.strictEqual(calculateWorkloadPercentage(240), 50);
      assert.strictEqual(calculateWorkloadPercentage(360), 75);
      assert.strictEqual(calculateWorkloadPercentage(480), 100);
    });

    it('clamps percentage to 100% when exceeding shift duration', () => {
      assert.strictEqual(calculateWorkloadPercentage(540), 100);
      assert.strictEqual(calculateWorkloadPercentage(800), 100);
    });

    it('handles negative or invalid minutes safely', () => {
      assert.strictEqual(calculateWorkloadPercentage(-50), 0);
      assert.strictEqual(calculateWorkloadPercentage('invalid'), 0);
      assert.strictEqual(calculateWorkloadPercentage(null), 0);
    });
  });

  describe('calculateProjectedWorkload', () => {
    const mockWorkloadList = [
      {
        staffId: 'staff-1',
        staffName: 'Maria Santos',
        department: 'HOUSEKEEPING',
        totalEstimatedDuration: 90,
        activeTaskCount: 2,
        workloadStatus: 'LOW',
      },
      {
        staffId: 'staff-2',
        staffName: 'Rahul Sharma',
        department: 'HOUSEKEEPING',
        totalEstimatedDuration: 210,
        activeTaskCount: 3,
        workloadStatus: 'NORMAL',
      },
      {
        staffId: 'staff-3',
        staffName: 'Carlos Rodriguez',
        department: 'MAINTENANCE',
        totalEstimatedDuration: 330,
        activeTaskCount: 4,
        workloadStatus: 'HIGH',
      },
    ];

    it('returns null if staffId or task is missing', () => {
      assert.strictEqual(
        calculateProjectedWorkload({ staffId: '', task: { id: 't1' }, workloadList: mockWorkloadList }),
        null
      );
      assert.strictEqual(
        calculateProjectedWorkload({ staffId: 'staff-1', task: null, workloadList: mockWorkloadList }),
        null
      );
    });

    it('returns MISSING_WORKLOAD_DATA when workloadList is null, undefined, or not an array', () => {
      const resNull = calculateProjectedWorkload({
        staffId: 'staff-1',
        task: { id: 't1', estimatedDuration: 30 },
        workloadList: null,
      });
      assert.strictEqual(resNull.hasData, false);
      assert.strictEqual(resNull.reason, 'MISSING_WORKLOAD_DATA');

      const resUndefined = calculateProjectedWorkload({
        staffId: 'staff-1',
        task: { id: 't1', estimatedDuration: 30 },
        workloadList: undefined,
      });
      assert.strictEqual(resUndefined.hasData, false);
      assert.strictEqual(resUndefined.reason, 'MISSING_WORKLOAD_DATA');
    });

    it('returns STAFF_NOT_FOUND when selected staff is not in workloadList', () => {
      const res = calculateProjectedWorkload({
        staffId: 'non-existent-staff',
        task: { id: 't1', estimatedDuration: 30 },
        workloadList: mockWorkloadList,
      });
      assert.strictEqual(res.hasData, false);
      assert.strictEqual(res.reason, 'STAFF_NOT_FOUND');
    });

    it('calculates initial assignment projected workload correctly', () => {
      const task = { id: 'task-101', estimatedDuration: 45 };
      const res = calculateProjectedWorkload({
        staffId: 'staff-1',
        task,
        currentAssignment: null,
        workloadList: mockWorkloadList,
      });

      assert.strictEqual(res.hasData, true);
      assert.strictEqual(res.currentMinutes, 90);
      assert.strictEqual(res.currentActiveTasks, 2);
      assert.strictEqual(res.currentStatus, 'LOW');
      assert.strictEqual(res.taskDuration, 45);
      assert.strictEqual(res.durationAdded, 45);
      assert.strictEqual(res.tasksAdded, 1);
      assert.strictEqual(res.projectedMinutes, 135);
      assert.strictEqual(res.projectedActiveTasks, 3);
      assert.strictEqual(res.projectedStatus, 'NORMAL'); // 135 > 120 -> NORMAL
      assert.strictEqual(res.isAlreadyAssigned, false);
      assert.strictEqual(res.isNewAssignment, true);
    });

    it('calculates reassignment projected workload on target staff without duplicate addition', () => {
      const task = { id: 'task-102', estimatedDuration: 60 };
      // Currently assigned to staff-1, being reassigned to staff-2
      const currentAssignment = { id: 'asg-1', taskId: 'task-102', staffId: 'staff-1' };

      const res = calculateProjectedWorkload({
        staffId: 'staff-2',
        task,
        currentAssignment,
        workloadList: mockWorkloadList,
      });

      assert.strictEqual(res.hasData, true);
      assert.strictEqual(res.currentMinutes, 210);
      assert.strictEqual(res.currentActiveTasks, 3);
      assert.strictEqual(res.currentStatus, 'NORMAL');
      assert.strictEqual(res.taskDuration, 60);
      assert.strictEqual(res.durationAdded, 60);
      assert.strictEqual(res.projectedMinutes, 270); // 210 + 60 = 270
      assert.strictEqual(res.projectedActiveTasks, 4);
      assert.strictEqual(res.projectedStatus, 'HIGH'); // 270 > 240 -> HIGH
      assert.strictEqual(res.isAlreadyAssigned, false);
      assert.strictEqual(res.isNewAssignment, true);
    });

    it('does not add task duration again if task is already assigned to the selected staff member', () => {
      const task = { id: 'task-103', estimatedDuration: 45 };
      // Currently assigned to staff-1, and staff-1 is selected
      const currentAssignment = { id: 'asg-2', taskId: 'task-103', staffId: 'staff-1' };

      const res = calculateProjectedWorkload({
        staffId: 'staff-1',
        task,
        currentAssignment,
        workloadList: mockWorkloadList,
      });

      assert.strictEqual(res.hasData, true);
      assert.strictEqual(res.currentMinutes, 90);
      assert.strictEqual(res.currentActiveTasks, 2);
      assert.strictEqual(res.durationAdded, 0); // Not added again!
      assert.strictEqual(res.tasksAdded, 0);
      assert.strictEqual(res.projectedMinutes, 90); // Projected equals current
      assert.strictEqual(res.projectedActiveTasks, 2);
      assert.strictEqual(res.projectedStatus, 'LOW');
      assert.strictEqual(res.isAlreadyAssigned, true);
      assert.strictEqual(res.isNewAssignment, false);
    });

    it('calculates transition to AT_RISK correctly when exceeding 360 minutes', () => {
      const task = { id: 'task-104', estimatedDuration: 40 };
      const res = calculateProjectedWorkload({
        staffId: 'staff-3', // current: 330 min
        task,
        currentAssignment: null,
        workloadList: mockWorkloadList,
      });

      assert.strictEqual(res.currentMinutes, 330);
      assert.strictEqual(res.currentStatus, 'HIGH');
      assert.strictEqual(res.durationAdded, 40);
      assert.strictEqual(res.projectedMinutes, 370); // 330 + 40 = 370
      assert.strictEqual(res.projectedStatus, 'AT_RISK'); // > 360 -> AT_RISK
    });

    it('handles zero-duration tasks safely without adding false duration', () => {
      const taskWithZero = { id: 'task-105', estimatedDuration: 0 };
      const res = calculateProjectedWorkload({
        staffId: 'staff-1',
        task: taskWithZero,
        currentAssignment: null,
        workloadList: mockWorkloadList,
      });

      assert.strictEqual(res.taskDuration, 0);
      assert.strictEqual(res.durationAdded, 0);
      assert.strictEqual(res.projectedMinutes, 90);
      assert.strictEqual(res.projectedActiveTasks, 3);
      assert.strictEqual(res.projectedStatus, 'LOW');
    });

    it('handles missing or non-numeric task estimated duration safely', () => {
      const taskNull = { id: 'task-106', estimatedDuration: null };
      const res = calculateProjectedWorkload({
        staffId: 'staff-1',
        task: taskNull,
        currentAssignment: null,
        workloadList: mockWorkloadList,
      });

      assert.strictEqual(res.taskDuration, 0);
      assert.strictEqual(res.durationAdded, 0);
      assert.strictEqual(res.projectedMinutes, 90);
    });

    it('dynamically reflects changes when selected staff member changes', () => {
      const task = { id: 'task-107', estimatedDuration: 30 };

      // Select Staff 1
      const res1 = calculateProjectedWorkload({
        staffId: 'staff-1',
        task,
        workloadList: mockWorkloadList,
      });
      assert.strictEqual(res1.currentMinutes, 90);
      assert.strictEqual(res1.projectedMinutes, 120);
      assert.strictEqual(res1.projectedStatus, 'LOW');

      // Change selection to Staff 2
      const res2 = calculateProjectedWorkload({
        staffId: 'staff-2',
        task,
        workloadList: mockWorkloadList,
      });
      assert.strictEqual(res2.currentMinutes, 210);
      assert.strictEqual(res2.projectedMinutes, 240);
      assert.strictEqual(res2.projectedStatus, 'NORMAL');

      // Change selection to Staff 3
      const res3 = calculateProjectedWorkload({
        staffId: 'staff-3',
        task,
        workloadList: mockWorkloadList,
      });
      assert.strictEqual(res3.currentMinutes, 330);
      assert.strictEqual(res3.projectedMinutes, 360);
      assert.strictEqual(res3.projectedStatus, 'HIGH');
    });
  });

  describe('calculateWorkloadSummary', () => {
    it('returns zeros for empty or null workload list', () => {
      const emptyRes = calculateWorkloadSummary([]);
      assert.deepStrictEqual(emptyRes, {
        totalActiveTasks: 0,
        totalWorkload: 0,
        availableStaff: 0,
        unavailableStaff: 0,
      });

      const nullRes = calculateWorkloadSummary(null);
      assert.deepStrictEqual(nullRes, {
        totalActiveTasks: 0,
        totalWorkload: 0,
        availableStaff: 0,
        unavailableStaff: 0,
      });
    });

    it('calculates total active tasks, workload minutes, and availability correctly', () => {
      const sampleList = [
        { activeTaskCount: 2, totalEstimatedDuration: 90, availability: 'AVAILABLE' },
        { activeTaskCount: 1, totalEstimatedDuration: 60, availability: 'AVAILABLE' },
        { activeTaskCount: 0, totalEstimatedDuration: 0, availability: 'UNAVAILABLE' },
        { activeTaskCount: 3, totalEstimatedDuration: 180, availability: 'AVAILABLE' },
      ];

      const res = calculateWorkloadSummary(sampleList);
      assert.strictEqual(res.totalActiveTasks, 6);
      assert.strictEqual(res.totalWorkload, 330);
      assert.strictEqual(res.availableStaff, 3);
      assert.strictEqual(res.unavailableStaff, 1);
    });

    it('handles string numbers and negative inputs safely', () => {
      const sampleList = [
        { activeTaskCount: '2', totalEstimatedDuration: '90', availability: 'available' },
        { activeTaskCount: -1, totalEstimatedDuration: -20, availability: 'UNAVAILABLE' },
      ];

      const res = calculateWorkloadSummary(sampleList);
      assert.strictEqual(res.totalActiveTasks, 2);
      assert.strictEqual(res.totalWorkload, 90);
      assert.strictEqual(res.availableStaff, 1);
      assert.strictEqual(res.unavailableStaff, 1);
    });
  });

  describe('calculateDepartmentWorkloads', () => {
    it('invents no department when there is no list and no workload: nothing is shown instead of the old built-in six', () => {
      assert.deepStrictEqual(calculateDepartmentWorkloads([]), []);
      assert.deepStrictEqual(calculateDepartmentWorkloads([], []), []);
    });

    it('shows exactly the departments of the list: a built-in an Admin removed does not come back, a new one appears', () => {
      const list = [{ code: 'HOUSEKEEPING', name: 'Housekeeping' }, { code: 'SPA', name: 'Spa' }];
      const res = calculateDepartmentWorkloads([], list);
      assert.deepStrictEqual(res.map((d) => d.code).sort(), ['HOUSEKEEPING', 'SPA']);
      assert.ok(!res.some((d) => d.code === 'SECURITY' || d.code === 'ROOM_SERVICE'));
    });

    it('aggregates workload by department consistent with staff workload totals', () => {
      const sampleWorkload = [
        { department: 'HOUSEKEEPING', totalEstimatedDuration: 120, activeTaskCount: 2 },
        { department: 'HOUSEKEEPING', totalEstimatedDuration: 80, activeTaskCount: 1 },
        { department: 'FRONT_DESK', totalEstimatedDuration: 60, activeTaskCount: 1 },
        { department: 'MAINTENANCE', totalEstimatedDuration: 140, activeTaskCount: 2 },
      ];

      const res = calculateDepartmentWorkloads(sampleWorkload);
      const totalFromDepts = res.reduce((acc, d) => acc + d.totalMinutes, 0);
      const totalFromStaff = sampleWorkload.reduce((acc, s) => acc + s.totalEstimatedDuration, 0);
      assert.strictEqual(totalFromDepts, totalFromStaff, 'Department sum must equal staff sum (400)');

      const housekeeping = res.find((d) => d.code === 'HOUSEKEEPING');
      assert.strictEqual(housekeeping.totalMinutes, 200);
      assert.strictEqual(housekeeping.activeTaskCount, 3);
      assert.strictEqual(housekeeping.percentage, 50); // 200 / 400 = 50%

      const frontDesk = res.find((d) => d.code === 'FRONT_DESK');
      assert.strictEqual(frontDesk.totalMinutes, 60);
      assert.strictEqual(frontDesk.percentage, 15); // 60 / 400 = 15%
    });
  });

  describe('sortWorkloadAlphabetically and compareWorkloadByName', () => {
    it('sorts staff members alphabetically in ascending A–Z order', () => {
      const unsorted = [
        { staffId: '1', staffName: 'Zara Phillips' },
        { staffId: '2', staffName: 'Carlos Rodriguez' },
        { staffId: '3', staffName: 'Alice Walker' },
        { staffId: '4', staffName: 'Bob Vance' },
      ];
      const sorted = sortWorkloadAlphabetically(unsorted);
      assert.deepStrictEqual(
        sorted.map((s) => s.staffName),
        ['Alice Walker', 'Bob Vance', 'Carlos Rodriguez', 'Zara Phillips']
      );
    });

    it('performs case-insensitive locale-aware comparison', () => {
      const mixedCase = [
        { staffId: '1', staffName: 'charlie Day' },
        { staffId: '2', staffName: 'Alice Walker' },
        { staffId: '3', staffName: 'bob vance' },
        { staffId: '4', staffName: 'David Wallace' },
      ];
      const sorted = sortWorkloadAlphabetically(mixedCase);
      assert.deepStrictEqual(
        sorted.map((s) => s.staffName),
        ['Alice Walker', 'bob vance', 'charlie Day', 'David Wallace']
      );
    });

    it('places staff with missing, null, or empty names safely at the end', () => {
      const withMissingNames = [
        { staffId: '1', staffName: '' },
        { staffId: '2', staffName: 'Bob Vance' },
        { staffId: '3', staffName: null },
        { staffId: '4', staffName: 'Alice Walker' },
        { staffId: '5' },
      ];
      const sorted = sortWorkloadAlphabetically(withMissingNames);
      assert.strictEqual(sorted[0].staffName, 'Alice Walker');
      assert.strictEqual(sorted[1].staffName, 'Bob Vance');
      // The remaining 3 should be at the end
      const remainingIds = sorted.slice(2).map((s) => s.staffId);
      assert.ok(remainingIds.includes('1'));
      assert.ok(remainingIds.includes('3'));
      assert.ok(remainingIds.includes('5'));
    });

    it('supports alternative display name properties (fullName, name)', () => {
      const items = [
        { staffId: '1', fullName: 'Zoe Saldana' },
        { staffId: '2', name: 'Aaron Paul' },
        { staffId: '3', staffName: 'Bryan Cranston' },
      ];
      const sorted = sortWorkloadAlphabetically(items);
      assert.strictEqual(sorted[0].name, 'Aaron Paul');
      assert.strictEqual(sorted[1].staffName, 'Bryan Cranston');
      assert.strictEqual(sorted[2].fullName, 'Zoe Saldana');
    });

    it('is stable and deterministic when names are identical', () => {
      const duplicates = [
        { staffId: 'staff-b', staffName: 'John Doe' },
        { staffId: 'staff-a', staffName: 'John Doe' },
      ];
      const sorted = sortWorkloadAlphabetically(duplicates);
      assert.strictEqual(sorted[0].staffId, 'staff-a');
      assert.strictEqual(sorted[1].staffId, 'staff-b');
    });

    it('does not sort by workload, task count, department, or input order', () => {
      const workloadItems = [
        { staffId: '1', staffName: 'Zara', totalEstimatedDuration: 10, activeTaskCount: 1, department: 'FRONT_DESK' },
        { staffId: '2', staffName: 'Aaron', totalEstimatedDuration: 400, activeTaskCount: 5, department: 'HOUSEKEEPING' },
        { staffId: '3', staffName: 'Mike', totalEstimatedDuration: 200, activeTaskCount: 2, department: 'MAINTENANCE' },
      ];
      const sorted = sortWorkloadAlphabetically(workloadItems);
      // Aaron has highest workload (400) and tasks (5), but is sorted first alphabetically
      assert.strictEqual(sorted[0].staffName, 'Aaron');
      assert.strictEqual(sorted[1].staffName, 'Mike');
      assert.strictEqual(sorted[2].staffName, 'Zara');
    });

    it('returns an empty array when given non-array, null, or undefined inputs', () => {
      assert.deepStrictEqual(sortWorkloadAlphabetically(null), []);
      assert.deepStrictEqual(sortWorkloadAlphabetically(undefined), []);
      assert.deepStrictEqual(sortWorkloadAlphabetically('not an array'), []);
    });

    it('does not mutate the original array', () => {
      const original = [
        { staffId: '1', staffName: 'Zara' },
        { staffId: '2', staffName: 'Alice' },
      ];
      const copy = [...original];
      const sorted = sortWorkloadAlphabetically(original);
      assert.deepStrictEqual(original, copy);
      assert.notStrictEqual(sorted, original);
    });
  });
});



describe('department risk is capacity based and decided by the server: the helpers the Workload page uses', () => {
  const RED = 'bg-red-600 dark:bg-red-500';

  const entry = (overrides = {}) => ({
    code: 'HOUSEKEEPING', name: 'Housekeeping', remainingMinutes: 600, assignedMinutes: 400, waitingMinutes: 200, activeTasks: 3, waitingTasks: 1,
    eligibleStaff: 2, helpersIn: 0, lentOut: 0, capacityMinutes: 960, utilizationPercent: 62.5, level: 'HIGH', atRisk: false, noCapacity: false, ...overrides,
  });
  const answer = (overrides = {}) => ({ enabled: true, capacityThresholdPercent: 85, shiftMinutes: 480, departments: [entry()], ...overrides });

  it('maps each level to the colour of its tile, and anything else to neutral grey', () => {
    assert.equal(getDepartmentLevelBarColor('LOW'), 'bg-blue-600 dark:bg-blue-500');
    assert.equal(getDepartmentLevelBarColor('NORMAL'), 'bg-emerald-600 dark:bg-emerald-500');
    assert.equal(getDepartmentLevelBarColor('HIGH'), 'bg-orange-600 dark:bg-orange-500');
    assert.equal(getDepartmentLevelBarColor('AT_RISK'), RED);
    for (const unknown of [undefined, null, '', 'CRITICAL', 'at_risk']) assert.equal(getDepartmentLevelBarColor(unknown), 'bg-gray-400', String(unknown));
  });

  it('labels a card with its level, and NO STAFF when it has work and nobody to do it', () => {
    assert.equal(departmentLevelLabel(entry({ level: 'AT_RISK', atRisk: true })), 'AT RISK');
    assert.equal(departmentLevelLabel(entry({ level: 'LOW' })), 'LOW');
    assert.equal(departmentLevelLabel(entry({ level: 'HIGH', noCapacity: true, utilizationPercent: null, eligibleStaff: 0 })), 'NO STAFF');
    assert.equal(departmentLevelLabel(null), null);
    assert.equal(departmentLevelLabel({ level: 'nonsense' }), null);
  });

  it('formats a utilization to one decimal without a trailing .0, and n/a when there is no capacity', () => {
    assert.equal(formatUtilization(66.7), '66.7%');
    assert.equal(formatUtilization(100), '100%');
    assert.equal(formatUtilization(104.2), '104.2%');
    assert.equal(formatUtilization(0), '0%');
    assert.equal(formatUtilization(null), 'n/a');
    assert.equal(formatUtilization(undefined), 'n/a');
  });

  it('reads a complete answer, and treats anything incomplete or invalid as unreadable (null), never as zeros or defaults', () => {
    const ok = parseDepartmentCapacityAnswer(answer());
    assert.equal(ok.status, 'ready');
    assert.equal(ok.enabled, true);
    assert.equal(ok.threshold, 85);
    assert.equal(ok.shiftMinutes, 480);
    assert.equal(ok.departments.length, 1);
    assert.equal(parseDepartmentCapacityAnswer(answer({ enabled: false })).enabled, false);
    assert.equal(parseDepartmentCapacityAnswer(answer({ departments: [] })).departments.length, 0, 'no departments is a valid answer');
    assert.equal(parseDepartmentCapacityAnswer(answer({ departments: [entry({ utilizationPercent: null, noCapacity: true, level: 'AT_RISK', atRisk: true, eligibleStaff: 0, capacityMinutes: 0 })] })).status, 'ready');
    const bad = [
      null, undefined, {}, 'x', 5, [],
      answer({ enabled: 'true' }), answer({ enabled: undefined }),
      answer({ capacityThresholdPercent: 0 }), answer({ capacityThresholdPercent: 101 }), answer({ capacityThresholdPercent: '85' }), answer({ capacityThresholdPercent: 85.5 }),
      answer({ shiftMinutes: 0 }), answer({ shiftMinutes: '480' }),
      answer({ departments: null }), answer({ departments: {} }),
      answer({ departments: [null] }), answer({ departments: [entry({ code: '' })] }), answer({ departments: [entry({ name: 5 })] }),
      answer({ departments: [entry({ remainingMinutes: -1 })] }), answer({ departments: [entry({ eligibleStaff: 1.5 })] }), answer({ departments: [entry({ activeTasks: '3' })] }),
      answer({ departments: [entry({ utilizationPercent: '62.5' })] }), answer({ departments: [entry({ utilizationPercent: -1 })] }),
      answer({ departments: [entry({ level: 'CRITICAL' })] }), answer({ departments: [entry({ atRisk: 'no' })] }), answer({ departments: [entry({ noCapacity: undefined })] }),
      answer({ departments: [entry(), entry({ level: undefined })] }),
    ];
    for (const value of bad) assert.equal(parseDepartmentCapacityAnswer(value), null, JSON.stringify(value));
  });

  it('explains the levels in terms of utilization and the threshold, speaks only about departments, and says when it is unavailable or the alert is off', () => {
    const ready = describeDepartmentRisk({ status: 'ready', enabled: true, threshold: 40 });
    assert.match(ready, /remaining task minutes as a share of its staff capacity/);
    assert.match(ready, /480-minute shift/);
    assert.match(ready, /AT RISK from 40%/);
    assert.doesNotMatch(ready, /total workload|hotel's total|individual|staff member/i, 'no share-of-total wording, and nothing about individual staff statuses');
    assert.match(describeDepartmentRisk({ status: 'ready', enabled: true, threshold: 10 }), /AT RISK from 10%/);
    assert.match(describeDepartmentRisk({ status: 'ready', enabled: false, threshold: 40 }), /alert is off, so no department is marked AT RISK/i);
    assert.match(describeDepartmentRisk({ status: 'unavailable' }), /could not be loaded.*unavailable/);
  });

  describe('the legend lists exactly the levels that can occur, in plain numbers', () => {
    const shown = (threshold, enabled = true) => getDepartmentLegendRanges(threshold, enabled).map((t) => `${t.label} ${t.text}`);

    it('40%: LOW 0-25, NORMAL 25-40, AT RISK 40+, and no HIGH', () => {
      assert.deepEqual(shown(40), ['LOW 0–25%', 'NORMAL 25–40%', 'AT RISK 40%+']);
    });
    it('10%: only what is left below the risk level (LOW up to 10) and AT RISK from 10; NORMAL and HIGH are impossible and absent', () => {
      assert.deepEqual(shown(10), ['LOW 0–10%', 'AT RISK 10%+']);
    });
    it('85%: LOW, NORMAL and HIGH keep their ranges (HIGH runs on to 85), AT RISK starts at 85', () => {
      assert.deepEqual(shown(85), ['LOW 0–25%', 'NORMAL 25–50%', 'HIGH 50–85%', 'AT RISK 85%+']);
    });
    it('100%: AT RISK starts at exactly 100', () => {
      assert.deepEqual(shown(100), ['LOW 0–25%', 'NORMAL 25–50%', 'HIGH 50–100%', 'AT RISK 100%+']);
    });
    it('edges: 1, 25, 26, 50 and 51 drop or keep the right levels', () => {
      assert.deepEqual(getDepartmentLegendRanges(1).map((t) => t.status), ['LOW', 'AT_RISK']);
      assert.deepEqual(getDepartmentLegendRanges(25).map((t) => t.status), ['LOW', 'AT_RISK']);
      assert.deepEqual(getDepartmentLegendRanges(26).map((t) => t.status), ['LOW', 'NORMAL', 'AT_RISK']);
      assert.deepEqual(getDepartmentLegendRanges(50).map((t) => t.status), ['LOW', 'NORMAL', 'AT_RISK']);
      assert.deepEqual(getDepartmentLegendRanges(51).map((t) => t.status), ['LOW', 'NORMAL', 'HIGH', 'AT_RISK']);
    });
    it('with the alert off there is no AT RISK tile and HIGH is everything above 50%', () => {
      assert.deepEqual(shown(40, false), ['LOW 0–25%', 'NORMAL 25–50%', 'HIGH 50%+']);
    });
    it('every text is plain numbers: no "Above" or "under" wording', () => {
      for (let t = 1; t <= 100; t += 1) for (const tile of getDepartmentLegendRanges(t)) assert.match(tile.text, /^\d+(–\d+)?%\+?$/, `${t}: ${tile.text}`);
    });

    it('for EVERY threshold 1-100, alert on or off, and EVERY utilization 0 to 250 in steps of 0.1: its level is a tile in the legend, and every tile is reachable', () => {
      for (const enabled of [true, false]) {
        for (let t = 1; t <= 100; t += 1) {
          const tiles = getDepartmentLegendRanges(t, enabled).map((x) => x.status);
          const reached = new Set();
          for (let tenths = 0; tenths <= 2500; tenths += 1) {
            const u = tenths / 10;
            const level = levelForUtilization(u, t, enabled);
            assert.ok(tiles.includes(level), `${u}% at threshold ${t} (alert ${enabled}) is ${level}, which the legend does not show: ${tiles}`);
            reached.add(level);
          }
          assert.deepEqual([...reached].sort(), [...tiles].sort(), `threshold ${t} (alert ${enabled}): the legend shows exactly the levels that can occur`);
        }
      }
    });
  });

  it('the level rule is the server\'s: AT RISK from the threshold inclusive, then LOW up to 25, NORMAL up to 50, HIGH above', () => {
    assert.equal(levelForUtilization(25, 85), 'LOW');
    assert.equal(levelForUtilization(25.1, 85), 'NORMAL');
    assert.equal(levelForUtilization(50, 85), 'NORMAL');
    assert.equal(levelForUtilization(50.1, 85), 'HIGH');
    assert.equal(levelForUtilization(84.9, 85), 'HIGH');
    assert.equal(levelForUtilization(85, 85), 'AT_RISK');
    assert.equal(levelForUtilization(104.2, 85), 'AT_RISK');
    assert.equal(levelForUtilization(12, 10), 'AT_RISK', 'a low threshold overrides the bands');
    assert.equal(levelForUtilization(9.9, 10), 'LOW');
    assert.equal(levelForUtilization(150, 85, false), 'HIGH', 'alert off: never AT RISK');
    assert.equal(levelForUtilization(null, 85), null);
  });
});
