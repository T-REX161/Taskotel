import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  buildDepartmentWorkloads, calculateDepartmentWorkloads, deriveWorkloadStatus, getDepartmentStatusBadge, getDepartmentStatusLabel,
} from './workloadUtils.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const read = (...parts) => fs.readFileSync(path.join(__dirname, ...parts), 'utf8').replace(/\r\n/g, '\n');

// Department workload UTILIZATION is calculated by the server (assigned minutes over working capacity, with the department thresholds).
// The screen shows those figures and never rebuilds them from the staff rows; the individual staff thresholds are not applied to a department.
const departments = [
  { code: 'HOUSEKEEPING', name: 'Housekeeping', colorKey: 'green' },
  { code: 'MAINTENANCE', name: 'Maintenance', colorKey: 'purple' },
];
const summary = [
  { code: 'HOUSEKEEPING', name: 'Housekeeping', assignedMinutes: 220, capacityMinutes: 1440, eligibleStaffCount: 3, utilizationPercent: 15.3, status: 'LOW',
    activeTaskCount: 3, staffCount: 3, unassignedTaskCount: 1, unassignedMinutes: 30 },
  { code: 'MAINTENANCE', name: 'Maintenance', assignedMinutes: 400, capacityMinutes: 480, eligibleStaffCount: 1, utilizationPercent: 83.3, status: 'AT_RISK',
    activeTaskCount: 2, staffCount: 1, unassignedTaskCount: 0, unassignedMinutes: 0 },
];

describe('department workload utilization cards', () => {
  it('uses the server figures as given: assigned minutes, capacity, utilization, status, counts and backlog', () => {
    const cards = buildDepartmentWorkloads(summary, departments);
    assert.deepEqual(cards.map((c) => [c.code, c.assignedMinutes, c.capacityMinutes, c.utilizationPercent, c.status, c.activeTaskCount, c.staffCount, c.eligibleStaffCount, c.unassignedTaskCount, c.unassignedMinutes]),
      [['HOUSEKEEPING', 220, 1440, 15.3, 'LOW', 3, 3, 3, 1, 30], ['MAINTENANCE', 400, 480, 83.3, 'AT_RISK', 2, 1, 1, 0, 0]]);
    assert.equal(cards[0].colorKey, 'green', 'the department identity colour key is kept for the bar');
  });

  it('a department of the list with no summary row has no capacity and zeros; a row of a department not in the list is not shown (Manager scope)', () => {
    const cards = buildDepartmentWorkloads([summary[0], { code: 'SECURITY', assignedMinutes: 999, capacityMinutes: 480, utilizationPercent: 207.9, status: 'AT_RISK' }], departments);
    assert.deepEqual(cards.map((c) => [c.code, c.assignedMinutes, c.status, c.utilizationPercent]), [['HOUSEKEEPING', 220, 'LOW', 15.3], ['MAINTENANCE', 0, 'NO_CAPACITY', null]]);
    assert.ok(!JSON.stringify(cards).includes('SECURITY') && !JSON.stringify(cards).includes('999'));
  });

  it('zero capacity is explicit: utilization stays null (never 0% or Infinity) and the status is NO_CAPACITY', () => {
    const [card] = buildDepartmentWorkloads([{ code: 'MAINTENANCE', assignedMinutes: 90, capacityMinutes: 0, eligibleStaffCount: 0, utilizationPercent: null, status: 'NO_CAPACITY', activeTaskCount: 1, staffCount: 1 }], departments.slice(1));
    assert.equal(card.utilizationPercent, null);
    assert.equal(card.status, 'NO_CAPACITY');
    assert.equal(getDepartmentStatusLabel('NO_CAPACITY'), 'NO CAPACITY');
    assert.ok(getDepartmentStatusBadge('NO_CAPACITY').includes('bg-gray-100'));
  });

  it('missing, null or negative figures count as zero, and empty input gives no cards', () => {
    const cards = buildDepartmentWorkloads([{ code: 'HOUSEKEEPING', assignedMinutes: null, activeTaskCount: -2 }], departments);
    assert.deepEqual(cards.map((c) => [c.assignedMinutes, c.activeTaskCount, c.staffCount, c.unassignedTaskCount]), [[0, 0, 0, 0], [0, 0, 0, 0]]);
    assert.deepEqual(buildDepartmentWorkloads([], []), []);
    assert.deepEqual(buildDepartmentWorkloads(null, null), []);
  });

  it('status labels and badge colours: the four severities use the status colours, never the department identity colours', () => {
    assert.deepEqual(['LOW', 'NORMAL', 'HIGH', 'AT_RISK'].map(getDepartmentStatusLabel), ['LOW', 'NORMAL', 'HIGH', 'AT RISK']);
    for (const [status, hue] of [['LOW', 'blue'], ['NORMAL', 'emerald'], ['HIGH', 'orange'], ['AT_RISK', 'red']]) {
      assert.ok(getDepartmentStatusBadge(status).includes('bg-' + hue + '-100'), status);
    }
  });

  it('the individual staff thresholds are unchanged and separate from the department bands', () => {
    // 400 minutes is AT_RISK for one person; the same 400 minutes over a 480 minute capacity is a department status the server decides
    assert.equal(deriveWorkloadStatus(120), 'LOW');
    assert.equal(deriveWorkloadStatus(240), 'NORMAL');
    assert.equal(deriveWorkloadStatus(360), 'HIGH');
    assert.equal(deriveWorkloadStatus(361), 'AT_RISK');
    assert.ok(!read('workloadUtils.js').includes('deriveDepartmentStatus'), 'the screen does not decide a department status itself');
  });

  it('the Workload page uses the summary for Admin and Manager, keeps the staff-row cards only for Staff, and shows every required figure', () => {
    const page = read('..', 'pages', 'WorkloadPage.jsx');
    assert.ok(page.includes('getDepartmentWorkloadSummary()') && page.includes('buildDepartmentWorkloads(departmentSummary || [], departments)'));
    assert.ok(page.includes("const usesDepartmentSummary = ['ADMIN', 'MANAGER'].includes(getUserRole());"));
    assert.ok(page.includes('if (usesDepartmentSummary) return buildDepartmentWorkloads'), 'summary first');
    assert.ok(page.includes('calculateDepartmentWorkloads(visibleWorkload, departments, { strict: isManager })'), 'the Staff path is unchanged');
    assert.ok(page.includes('departmentWorkloads.reduce((sum, dept) => sum + dept.assignedMinutes, 0)'));
    // the card is the original one: name + minutes, a bar, '% of total load' and 'tasks • staff'; none of the newer additions are on it
    assert.ok(page.includes('{dept.totalMinutes} mins') && page.includes('{dept.percentage}% of total load') && page.includes('{dept.activeTaskCount}') && page.includes('{dept.staffCount} staff'));
    assert.ok(page.includes('style={{ width: `${Math.min(dept.percentage, 100)}%` }}') && page.includes('${barColor}'));
    const cards = page.slice(page.indexOf('aria-label="Workload by department"'), page.indexOf('</section>', page.indexOf('aria-label="Workload by department"')));
    for (const gone of ['NO CAPACITY', 'No capacity', 'capacityMinutes', 'eligibleStaffCount', 'utilized', 'unassigned', 'backlog', 'getDepartmentStatus']) assert.ok(!cards.includes(gone), 'not on the card: ' + gone);
  });

  it('the card percentage is each department share of the total assigned department minutes (not capacity utilization)', () => {
    const rows = [['HOUSEKEEPING', 270], ['FRONT_DESK', 130], ['MAINTENANCE', 460], ['SECURITY', 140]].map(([code, assignedMinutes]) => ({ code, assignedMinutes, capacityMinutes: 4800, utilizationPercent: 1 }));
    const cards = buildDepartmentWorkloads(rows, rows.map(({ code }) => ({ code, name: code })));
    assert.deepEqual(cards.map((c) => c.percentage), [27, 13, 46, 14]);
    assert.deepEqual(cards.map((c) => c.totalMinutes), [270, 130, 460, 140]);
    assert.equal(cards[0].overallMinutes, 1000);
    assert.deepEqual(buildDepartmentWorkloads([], [{ code: 'A', name: 'A' }]).map((c) => c.percentage), [0], 'no workload: 0%, no division by zero');
  });

  it('the staff-row roll-up used for Staff still behaves as before (one person: one row)', () => {
    const rows = [{ staffId: '1', staffName: 'Ana', department: 'HOUSEKEEPING', totalEstimatedDuration: 60, activeTaskCount: 1 }];
    const [card] = calculateDepartmentWorkloads(rows, [departments[0]]).filter((c) => c.code === 'HOUSEKEEPING');
    assert.equal(card.totalMinutes, 60);
  });

  it('the service call goes to the department-summary endpoint', () => {
    assert.ok(read('..', 'services', 'api.js').includes("apiFetch('/api/workload/department-summary')"));
  });
});
