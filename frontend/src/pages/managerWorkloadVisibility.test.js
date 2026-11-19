import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  calculateDepartmentWorkloads,
  calculateWorkloadSummary,
  restrictWorkloadToDepartments,
  sortWorkloadAlphabetically,
} from '../utils/workloadUtils.js';

const here = path.dirname(fileURLToPath(import.meta.url));
const read = (...parts) => fs.readFileSync(path.join(here, ...parts), 'utf8');
const page = read('WorkloadPage.jsx');
const api = read('..', 'services', 'api.js');

// What GET /api/workload/departments answers a Manager who manages Housekeeping (primary) and Maintenance (granted, nobody works there)
const managerDepartments = [
  { code: 'HOUSEKEEPING', name: 'Housekeeping', staffCount: 3, activeTasks: 2, active: true, managed: true, iconKey: 'building', colorKey: 'gray' },
  { code: 'MAINTENANCE', name: 'Maintenance', staffCount: 0, activeTasks: 0, active: true, managed: true },
];
// What GET /api/workload answers the same Manager: the staff of those two departments only
const row = (id, name, department, minutes, tasks = 1) => ({
  staffId: id, staffName: name, department, departmentName: department, availability: 'AVAILABLE', activeTaskCount: tasks, totalEstimatedDuration: minutes,
});
const managerRows = [row('1', 'Ana', 'HOUSEKEEPING', 60), row('2', 'Ben', 'HOUSEKEEPING', 120), row('3', 'Cy', 'HOUSEKEEPING', 0, 0)];

describe("a Manager's Workload page shows the authorized departments only", () => {
  it('one card per authorized department, a granted department with no workload included, and nothing else', () => {
    const cards = calculateDepartmentWorkloads(managerRows, managerDepartments, { strict: true });
    assert.deepEqual(cards.map((c) => c.code), ['HOUSEKEEPING', 'MAINTENANCE']);
    assert.deepEqual(cards.map((c) => c.name), ['Housekeeping', 'Maintenance']);
    assert.equal(cards[0].totalMinutes, 180);
    assert.equal(cards[0].activeTaskCount, 2, "the active tasks of the rows: 1 + 1 + 0");
    assert.equal(cards[0].staffCount, 3, "the department's own staff count, from the server");
    assert.equal(cards[1].totalMinutes, 0);
    assert.equal(cards[1].staffCount, 0);
    const text = JSON.stringify(cards);
    for (const unauthorized of ['Security', 'SECURITY', 'Front Desk', 'FRONT_DESK', 'Room Service', 'Food & Beverage']) {
      assert.ok(!text.includes(unauthorized), `${unauthorized} must not appear`);
    }
  });

  it('percentages and the total are built from the authorized departments only', () => {
    const cards = calculateDepartmentWorkloads(managerRows, managerDepartments, { strict: true });
    assert.deepEqual(cards.map((c) => c.percentage), [100, 0]);
    assert.ok(cards.every((c) => c.overallMinutes === 180));
    assert.equal(calculateWorkloadSummary(managerRows).totalWorkload, 180);
  });

  it('with two authorized departments at work the percentages add up over those two only', () => {
    const departments = [...managerDepartments];
    const rows = [row('1', 'Ana', 'HOUSEKEEPING', 60), row('2', 'Mo', 'MAINTENANCE', 180)];
    const cards = calculateDepartmentWorkloads(rows, departments, { strict: true });
    assert.deepEqual(cards.map((c) => [c.code, c.totalMinutes, c.percentage]), [['HOUSEKEEPING', 60, 25], ['MAINTENANCE', 180, 75]]);
  });

  it('a row of any other department (stale or forged) creates no card and counts towards no percentage', () => {
    const withStray = [...managerRows, row('9', 'Stray', 'SECURITY', 500, 4)];
    const cards = calculateDepartmentWorkloads(withStray, managerDepartments, { strict: true });
    assert.deepEqual(cards.map((c) => c.code), ['HOUSEKEEPING', 'MAINTENANCE']);
    assert.deepEqual(cards.map((c) => c.percentage), [100, 0], 'the stray 500 minutes are not in the total');
    assert.ok(!JSON.stringify(cards).includes('SECURITY'));
    // The same stray row also leaves the table, the summary and the page count
    const visible = restrictWorkloadToDepartments(withStray, managerDepartments);
    assert.equal(visible.length, 3);
    assert.equal(calculateWorkloadSummary(visible).totalWorkload, 180);
  });

  it('a department list that could not be loaded never falls back to the built-in departments: cards come from the rows only', () => {
    assert.deepEqual(calculateDepartmentWorkloads([], [], { strict: true }), []);
    assert.deepEqual(calculateDepartmentWorkloads([], null, { strict: true }), []);
    const fromRows = calculateDepartmentWorkloads(managerRows, [], { strict: true });
    assert.deepEqual(fromRows.map((c) => c.code), ['HOUSEKEEPING']);
    assert.ok(!JSON.stringify(fromRows).includes('FRONT_DESK'));
  });

  it('staff pages and counts follow the authorized staff: unauthorized staff are not in the table, the count or the page count', () => {
    const authorized = Array.from({ length: 20 }, (_, i) => row(`a${i}`, `Staff ${String(i).padStart(2, '0')}`, 'HOUSEKEEPING', 10));
    const strays = Array.from({ length: 5 }, (_, i) => row(`x${i}`, `Other ${i}`, 'SECURITY', 10));
    const visible = sortWorkloadAlphabetically(restrictWorkloadToDepartments([...authorized, ...strays], managerDepartments));
    assert.equal(visible.length, 20, 'the staff count');
    assert.equal(Math.max(1, Math.ceil(visible.length / 10)), 2, 'two pages of ten, not three');
    assert.ok(visible.every((r) => r.department === 'HOUSEKEEPING'));
    assert.ok(!JSON.stringify(visible).includes('Other'));
  });

  it('restrictWorkloadToDepartments: case-insensitive, safe with odd input, and a missing list leaves the (server-scoped) rows as they are', () => {
    const rows = [row('1', 'Ana', 'housekeeping', 10), row('2', 'Zed', 'SECURITY', 10), null, { staffId: '3' }];
    assert.deepEqual(restrictWorkloadToDepartments(rows, managerDepartments).map((r) => r.staffId), ['1']);
    assert.deepEqual(restrictWorkloadToDepartments(rows, []), rows);
    assert.deepEqual(restrictWorkloadToDepartments(rows, null), rows);
    assert.deepEqual(restrictWorkloadToDepartments(null, managerDepartments), []);
  });
});

describe('Admin and Staff see the page exactly as before', () => {
  it('without strict mode there is no built-in fallback, and a card for any department in the rows is unchanged', () => {
    const fallback = calculateDepartmentWorkloads([], []);
    assert.equal(fallback.length, 0, 'no department is invented: a removed built-in must never come back');
    const adminCards = calculateDepartmentWorkloads([row('1', 'Ana', 'SECURITY', 50)], [{ code: 'SECURITY', name: 'Security', active: true }]);
    assert.deepEqual(adminCards.map((c) => c.code), ['SECURITY']);
    const dynamic = calculateDepartmentWorkloads([row('1', 'Ana', 'SPA', 50)], [{ code: 'SECURITY', name: 'Security', active: true }]);
    assert.deepEqual(dynamic.map((c) => c.code).sort(), ['SECURITY', 'SPA'], 'a row of an unlisted department still gets its card for an Admin');
  });
});

describe('WorkloadPage wiring', () => {
  it("takes the cards from the server's department list for this account, never from GET /api/departments", () => {
    assert.ok(page.includes('const departmentsPromise = getWorkloadDepartments();'));
    assert.ok(!/\bgetDepartments\b/.test(page));
    assert.ok(api.includes("apiFetch('/api/workload/departments')"), 'the Workload department list has its own endpoint');
    assert.ok(/export const getWorkloadDepartments = async \(\) => \{\s*const departments = await apiFetch\('\/api\/workload\/departments'\);/.test(api));
  });

  it("builds the summary, the cards, the table and the pages from one set of rows, restricted to a Manager's departments", () => {
    assert.ok(page.includes("const isManager = getUserRole() === 'MANAGER';"));
    assert.ok(page.includes('isManager ? restrictWorkloadToDepartments(workload, departments) : workload'));
    assert.ok(page.includes('calculateWorkloadSummary(visibleWorkload)'));
    assert.ok(page.includes('calculateDepartmentWorkloads(visibleWorkload, departments, { strict: isManager })'));
    assert.ok(page.includes('sortWorkloadAlphabetically(visibleWorkload)'));
    assert.ok(page.includes('Math.ceil(sortedWorkload.length / pageSize)'));
    // The raw rows are never passed on to anything that is shown
    for (const raw of ['calculateWorkloadSummary(workload)', 'calculateDepartmentWorkloads(workload', 'sortWorkloadAlphabetically(workload)']) {
      assert.ok(!page.includes(raw), raw);
    }
  });

  it("never keeps a Manager's older department list beside fresh rows, so a revoked department's card cannot linger (see workloadRefresh.test.js)", () => {
    assert.ok(page.includes('decideWorkloadRefresh({'));
    assert.ok(!page.includes('keepPreviousDepartments'), 'the old inline rule is gone');
    assert.ok(page.includes('if (decision.departments !== null) setDepartments(decision.departments);'));
  });

  it('renders one card for each entry of the computed list and nothing else', () => {
    assert.ok(page.includes('{departmentWorkloads.map((dept) => {'));
    assert.ok(page.includes('key={dept.code}'));
    assert.equal(page.split('departmentWorkloads.map(').length - 1, 1, 'no second source of department cards');
  });
});
