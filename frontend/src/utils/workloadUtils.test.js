import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import {
  deriveWorkloadStatus,
  getWorkloadStatusBadge,
  getWorkloadProgressBarColor,
  calculateWorkloadPercentage,
  calculateProjectedWorkload,
  calculateWorkloadSummary,
  calculateDepartmentWorkloads,
  SUPPORTED_DEPARTMENTS,
  compareWorkloadByName,
  sortWorkloadAlphabetically,
} from './workloadUtils.js';

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
    it('returns all supported backend departments with 0 minutes when list is empty', () => {
      const res = calculateDepartmentWorkloads([]);
      assert.strictEqual(res.length, 6);
      const codes = res.map((d) => d.code);
      assert.ok(codes.includes('HOUSEKEEPING'));
      assert.ok(codes.includes('FRONT_DESK'));
      assert.ok(codes.includes('MAINTENANCE'));
      assert.ok(codes.includes('ROOM_SERVICE'));
      assert.ok(codes.includes('FOOD_AND_BEVERAGE'));
      assert.ok(codes.includes('SECURITY'));

      res.forEach((d) => {
        assert.strictEqual(d.totalMinutes, 0);
        assert.strictEqual(d.activeTaskCount, 0);
        assert.strictEqual(d.percentage, 0);
      });
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


