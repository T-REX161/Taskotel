import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import {
  deriveWorkloadStatus,
  getWorkloadStatusBadge,
  getWorkloadProgressBarColor,
  getDepartmentShareBarColor,
  getDepartmentShareStatus,
  getDepartmentLegendRanges,
  parseRiskThresholdAnswer,
  describeDepartmentRisk,
  isDepartmentAtRisk,
  normalizeRiskThreshold,
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



describe('getDepartmentShareBarColor (department bar colour from the percentage of total load)', () => {
  const BLUE = 'bg-blue-600 dark:bg-blue-500';
  const GREEN = 'bg-emerald-600 dark:bg-emerald-500';
  const ORANGE = 'bg-orange-600 dark:bg-orange-500';
  const RED = 'bg-red-600 dark:bg-red-500';

  it('the examples: Housekeeping 27, Front Desk 13, Maintenance 46, Food and Beverage 10, Security 4', () => {
    assert.equal(getDepartmentShareBarColor(27), GREEN);
    assert.equal(getDepartmentShareBarColor(13), BLUE);
    assert.equal(getDepartmentShareBarColor(46), GREEN);
    assert.equal(getDepartmentShareBarColor(10), BLUE);
    assert.equal(getDepartmentShareBarColor(4), BLUE);
  });

  it('every boundary: 0, 25 | 26, 50 | 51, 75 | 76, 100', () => {
    for (const [value, color] of [[0, BLUE], [25, BLUE], [26, GREEN], [50, GREEN], [51, ORANGE], [75, ORANGE], [76, RED], [100, RED]]) {
      assert.equal(getDepartmentShareBarColor(value), color, String(value));
    }
  });

  it('is not capped at 100, ignores negatives, and gives a neutral colour for a missing or invalid value', () => {
    assert.equal(getDepartmentShareBarColor(250), RED);
    assert.equal(getDepartmentShareBarColor(-5), BLUE);
    for (const bad of [null, undefined, '', NaN, 'abc']) assert.equal(getDepartmentShareBarColor(bad), 'bg-gray-400', String(bad));
  });

  it('uses the strong 600 shades on the light theme (#2563EB, #059669, #EA580C, #DC2626) and brighter 500 shades on the dark theme', () => {
    assert.ok([BLUE, GREEN, ORANGE, RED].every((c) => /^bg-[a-z]+-600 dark:bg-[a-z]+-500$/.test(c)));
  });

  it('works on the aggregated department data: the colour follows the share that is displayed', () => {
    const rows = [
      { department: 'HOUSEKEEPING', totalEstimatedDuration: 270, activeTaskCount: 1 },
      { department: 'FRONT_DESK', totalEstimatedDuration: 130, activeTaskCount: 1 },
      { department: 'MAINTENANCE', totalEstimatedDuration: 460, activeTaskCount: 1 },
      { department: 'ROOM_SERVICE', totalEstimatedDuration: 140, activeTaskCount: 1 },
    ];
    const res = calculateDepartmentWorkloads(rows, [['HOUSEKEEPING', 'Housekeeping'], ['FRONT_DESK', 'Front Desk'], ['MAINTENANCE', 'Maintenance'], ['ROOM_SERVICE', 'Room Service'], ['FOOD_AND_BEVERAGE', 'Food & Beverage'], ['SECURITY', 'Security']].map(([code, name]) => ({ code, name, active: true })));
    const colorOf = (code) => getDepartmentShareBarColor(res.find((d) => d.code === code).percentage);
    assert.equal(res.find((d) => d.code === 'HOUSEKEEPING').percentage, 27);
    assert.equal(colorOf('HOUSEKEEPING'), GREEN);
    assert.equal(colorOf('FRONT_DESK'), BLUE);
    assert.equal(colorOf('MAINTENANCE'), GREEN);
    assert.equal(colorOf('ROOM_SERVICE'), BLUE);
    assert.equal(colorOf('SECURITY'), BLUE, 'no work: 0% is LOW');
  });
});

describe('Capacity Threshold drives the AT RISK colour of the department bars', () => {
  const RED = 'bg-red-600 dark:bg-red-500';
  const shares = { Housekeeping: 27, 'Front Desk': 13, Maintenance: 46, 'Room Service': 0, 'Food & Beverage': 10, Security: 4 };
  const atRisk = (threshold) => Object.entries(shares).filter(([, p]) => getDepartmentShareStatus(p, threshold) === 'AT_RISK').map(([n]) => n);

  it('threshold 10: every department at or above 10% is red and AT RISK, exactly 10% included', () => {
    assert.deepEqual(atRisk(10), ['Housekeeping', 'Front Desk', 'Maintenance', 'Food & Beverage']);
    for (const name of atRisk(10)) assert.equal(getDepartmentShareBarColor(shares[name], 10), RED, name);
  });

  it('departments below the threshold keep their existing colour and status', () => {
    assert.equal(getDepartmentShareBarColor(0, 10), getDepartmentShareBarColor(0));
    assert.equal(getDepartmentShareBarColor(4, 10), getDepartmentShareBarColor(4));
    assert.equal(getDepartmentShareStatus(0, 10), 'LOW');
    assert.equal(getDepartmentShareStatus(4, 10), 'LOW');
  });

  it('the comparison is >=: one below the threshold is not at risk, equal and above are', () => {
    for (const threshold of [1, 10, 50, 85, 100]) {
      assert.equal(isDepartmentAtRisk(threshold - 1, threshold), false, `${threshold - 1} < ${threshold}`);
      assert.equal(isDepartmentAtRisk(threshold, threshold), true, `${threshold} = ${threshold}`);
      assert.equal(isDepartmentAtRisk(threshold + 1, threshold), true, `${threshold + 1} > ${threshold}`);
    }
  });

  it('changing the threshold changes the result: 27% is at risk at 25 and below, and not at 28', () => {
    assert.equal(getDepartmentShareStatus(27, 25), 'AT_RISK');
    assert.equal(getDepartmentShareStatus(27, 27), 'AT_RISK');
    assert.equal(getDepartmentShareStatus(27, 28), 'NORMAL');
    assert.notEqual(getDepartmentShareBarColor(27, 28), RED);
  });

  it('bar colour and status label always agree', () => {
    for (let p = 0; p <= 100; p += 1) {
      for (const threshold of [undefined, null, 1, 10, 50, 85, 100]) {
        assert.equal(getDepartmentShareBarColor(p, threshold) === RED, getDepartmentShareStatus(p, threshold) === 'AT_RISK', `${p}% @ ${threshold}`);
      }
    }
  });

  it('without a usable threshold nothing is overridden: the existing colours apply (including above 75% red)', () => {
    for (const threshold of [undefined, null, 0, 101, 10.5, '10', NaN]) {
      assert.equal(normalizeRiskThreshold(threshold), null, String(threshold));
      assert.equal(getDepartmentShareStatus(30, threshold), 'NORMAL');
      assert.equal(getDepartmentShareStatus(76, threshold), 'AT_RISK');
    }
  });

  it('a non-number percentage stays neutral grey with no status', () => {
    assert.equal(getDepartmentShareBarColor(null, 10), 'bg-gray-400');
    assert.equal(getDepartmentShareStatus('abc', 10), null);
  });
});

describe('Workload Thresholds legend follows the saved Capacity Threshold', () => {
  const show = (t) => getDepartmentLegendRanges(t).map((r) => `${r.label}: ${r.text}`);

  it('40%: LOW 0–25, NORMAL 26–39, AT RISK 40–100, and no HIGH', () => {
    assert.deepEqual(show(40), ['LOW: 0–25%', 'NORMAL: 26–39%', 'AT RISK: 40–100%']);
    assert.ok(!getDepartmentLegendRanges(40).some((r) => r.status === 'HIGH'));
  });

  it('10%: only what is left below 10 (LOW) and AT RISK from 10; NORMAL and HIGH disappear', () => {
    assert.deepEqual(show(10), ['LOW: 0–9%', 'AT RISK: 10–100%']);
  });

  it('85%: LOW, NORMAL and HIGH keep their ranges (HIGH now runs on to just under 85), AT RISK starts at 85', () => {
    assert.deepEqual(show(85), ['LOW: 0–25%', 'NORMAL: 26–50%', 'HIGH: 51–84%', 'AT RISK: 85–100%']);
  });

  it('100%: everything below 100 keeps its range and AT RISK is exactly 100%', () => {
    assert.deepEqual(show(100), ['LOW: 0–25%', 'NORMAL: 26–50%', 'HIGH: 51–99%', 'AT RISK: 100%']);
  });

  it('the ranges use plain numbers, no "Above" or "under" wording', () => {
    for (let t = 1; t <= 100; t += 1) for (const r of getDepartmentLegendRanges(t)) assert.match(r.text, /^\d+(–\d+)?%$/, `${t}: ${r.text}`);
    assert.equal(show(1)[0], 'LOW: 0%');
  });

  it('other edges: 1% leaves only 0%, 25% / 26% / 50% / 51% / 52% drop or keep the right categories', () => {
    assert.deepEqual(getDepartmentLegendRanges(1).map((r) => r.status), ['LOW', 'AT_RISK']);
    assert.deepEqual(getDepartmentLegendRanges(25).map((r) => r.status), ['LOW', 'AT_RISK']);
    assert.deepEqual(getDepartmentLegendRanges(26).map((r) => r.status), ['LOW', 'AT_RISK']);
    assert.deepEqual(getDepartmentLegendRanges(27).map((r) => r.status), ['LOW', 'NORMAL', 'AT_RISK']);
    assert.deepEqual(getDepartmentLegendRanges(50).map((r) => r.status), ['LOW', 'NORMAL', 'AT_RISK']);
    assert.deepEqual(getDepartmentLegendRanges(51).map((r) => r.status), ['LOW', 'NORMAL', 'AT_RISK']);
    assert.deepEqual(getDepartmentLegendRanges(52).map((r) => r.status), ['LOW', 'NORMAL', 'HIGH', 'AT_RISK']);
  });

  it('without a usable threshold the original four fixed ranges are shown', () => {
    for (const t of [null, undefined, 0, 101, 'x']) {
      assert.deepEqual(show(t), ['LOW: 0–25%', 'NORMAL: Above 25%–50%', 'HIGH: Above 50%–75%', 'AT RISK: Above 75%']);
    }
  });

  it('for EVERY threshold 1-100 and EVERY percentage 0-100: exactly one legend range holds it, and it is the status the bar and label use', () => {
    for (let t = 1; t <= 100; t += 1) {
      const ranges = getDepartmentLegendRanges(t);
      for (let p = 0; p <= 100; p += 1) {
        const holding = ranges.filter((r) => p >= r.from && p <= (r.to ?? 100));
        assert.equal(holding.length, 1, `${p}% @ ${t}% is in exactly one legend range`);
        assert.equal(holding[0].status, getDepartmentShareStatus(p, t), `${p}% @ ${t}%`);
        assert.equal(getDepartmentShareBarColor(p, t).startsWith('bg-red') , holding[0].status === 'AT_RISK', `${p}% @ ${t}% colour`);
      }
      // AT RISK begins exactly at the threshold, inclusive
      assert.equal(ranges.at(-1).status, 'AT_RISK');
      assert.equal(ranges.at(-1).from, t);
      assert.equal(getDepartmentShareStatus(t, t), 'AT_RISK');
      assert.notEqual(getDepartmentShareStatus(t - 1, t), 'AT_RISK');
      // every category shown can really occur, none is empty
      for (const r of ranges) assert.ok(r.to === null || r.to >= r.from, `${r.status} @ ${t}% is not empty`);
    }
  });

  it('below the threshold the existing bands and colours are kept', () => {
    for (let t = 1; t <= 100; t += 1) {
      for (let p = 0; p < t; p += 1) {
        const expected = p <= 25 ? 'LOW' : p <= 50 ? 'NORMAL' : 'HIGH';
        assert.equal(getDepartmentShareStatus(p, t), expected, `${p}% @ ${t}%`);
      }
    }
  });
});

describe('the department-risk threshold answer and its explanation', () => {
  it('a readable answer becomes ready (with the value) or off; anything else is null, never the defaults', () => {
    assert.deepEqual(parseRiskThresholdAnswer({ enabled: true, capacityThresholdPercent: 40 }), { status: 'ready', value: 40 });
    assert.deepEqual(parseRiskThresholdAnswer({ enabled: true, capacityThresholdPercent: 1 }), { status: 'ready', value: 1 });
    assert.deepEqual(parseRiskThresholdAnswer({ enabled: true, capacityThresholdPercent: 100 }), { status: 'ready', value: 100 });
    assert.deepEqual(parseRiskThresholdAnswer({ enabled: false, capacityThresholdPercent: 40 }), { status: 'off' });
    for (const bad of [null, undefined, {}, 'x', 5, { enabled: true }, { enabled: true, capacityThresholdPercent: 0 }, { enabled: true, capacityThresholdPercent: 101 },
      { enabled: true, capacityThresholdPercent: '40' }, { enabled: true, capacityThresholdPercent: 40.5 }, { enabled: 'true', capacityThresholdPercent: 40 }, { capacityThresholdPercent: 40 }]) {
      assert.equal(parseRiskThresholdAnswer(bad), null, JSON.stringify(bad));
    }
  });

  it('the explanation follows the saved threshold, and speaks only about the department cards', () => {
    assert.match(describeDepartmentRisk({ status: 'ready', value: 40 }), /AT RISK from 40%/);
    assert.match(describeDepartmentRisk({ status: 'ready', value: 10 }), /AT RISK from 10%/);
    assert.doesNotMatch(describeDepartmentRisk({ status: 'ready', value: 40 }), /85|75|480/);
    assert.match(describeDepartmentRisk({ status: 'off' }), /alert is off.*default ranges/i);
    assert.match(describeDepartmentRisk({ status: 'unavailable' }), /could not be loaded.*unavailable/);
    for (const state of [{ status: 'ready', value: 40 }, { status: 'off' }, { status: 'unavailable' }, { status: 'loading' }]) {
      assert.match(describeDepartmentRisk(state), /department card/);
      assert.doesNotMatch(describeDepartmentRisk(state), /staff member|individual/i);
    }
  });
});
