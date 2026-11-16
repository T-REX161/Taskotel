import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { authorizedDepartments } from '../utils/managedDepartments.js';
import { departmentCount, totalOfKnownCounts } from '../utils/departmentDisplay.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const page = fs.readFileSync(path.join(__dirname, 'DepartmentsPage.jsx'), 'utf8').replace(/\r\n/g, '\n');

const card = (code, extra = {}) => ({ code, name: code, active: true, staffCount: 4, activeTasks: 2, ...extra });
const managed = (code, extra = {}) => card(code, { managed: true, ...extra });
// What the server sends a Manager: only their managed departments. The tests below also feed the page an answer that still holds
// unauthorized departments, to prove nothing of them can reach a card or a total.
const unauthorized = (code) => ({ code, name: code, active: true, managed: false, staffCount: null, activeTasks: null });

const totals = (departments) => ({
  totalDepartments: departments.filter((d) => d.active !== false).length,
  staff: departmentCount(totalOfKnownCounts(departments, 'staffCount')),
  tasks: departmentCount(totalOfKnownCounts(departments, 'activeTasks')),
});

describe('Departments page: a Manager sees only the departments they manage', () => {
  it('one authorized department: one card, exact totals', () => {
    const shown = authorizedDepartments('MANAGER', [managed('HOME'), unauthorized('A'), unauthorized('B')]);
    assert.deepEqual(shown.map((d) => d.code), ['HOME']);
    assert.deepEqual(totals(shown), { totalDepartments: 1, staff: 4, tasks: 2 });
  });

  it('several authorized departments: exactly those cards, totals are their sums and nothing else', () => {
    const shown = authorizedDepartments('MANAGER', [managed('HOME'), managed('G1', { staffCount: 1, activeTasks: 7 }), unauthorized('A'), managed('G2', { staffCount: 2, activeTasks: 0 })]);
    assert.deepEqual(shown.map((d) => d.code), ['HOME', 'G1', 'G2']);
    assert.deepEqual(totals(shown), { totalDepartments: 3, staff: 7, tasks: 9 });
  });

  it('a revoked grant or an inactive department is simply absent from the next answer and from every total', () => {
    const before = authorizedDepartments('MANAGER', [managed('HOME'), managed('G1')]);
    assert.equal(totals(before).totalDepartments, 2);
    // The next server answer no longer holds G1 (revoked, or inactive): the page state is replaced, not merged
    const after = authorizedDepartments('MANAGER', [managed('HOME')]);
    assert.deepEqual(after.map((d) => d.code), ['HOME']);
    assert.deepEqual(totals(after), { totalDepartments: 1, staff: 4, tasks: 2 });
  });

  it('only a real true for managed counts; a missing or odd flag is not authorization', () => {
    const shown = authorizedDepartments('MANAGER', [card('X'), card('Y', { managed: 'true' }), card('Z', { managed: 1 }), null, managed('OK')]);
    assert.deepEqual(shown.map((d) => d.code), ['OK']);
    assert.deepEqual(authorizedDepartments('MANAGER', null), []);
  });

  it('Admin sees every department and Staff keep their list unchanged', () => {
    const all = [card('A'), card('B', { active: false }), card('C')];
    assert.equal(authorizedDepartments('ADMIN', all), all, 'returned untouched');
    const staffList = [{ code: 'A', name: 'A', active: true, staffCount: null, activeTasks: null }, { code: 'B', name: 'B', active: true, staffCount: null, activeTasks: null }];
    assert.equal(authorizedDepartments('STAFF', staffList), staffList, 'returned untouched');
    assert.equal(totals(authorizedDepartments('ADMIN', all)).totalDepartments, 2, 'an inactive department is not counted, as before');
    assert.equal(totals(staffList).staff, '—', 'Staff still read the figures as unavailable');
  });

  it('the page keeps only the authorized list in state, so cards and totals cannot include anything else', () => {
    assert.ok(page.includes('setDepartments(authorizedDepartments(getUserRole(), data));'));
    assert.ok(page.includes("import { authorizedDepartments } from '../utils/managedDepartments';"));
    // every card and total reads from that state
    assert.ok(page.includes('{departments.map((dept) => {'));
    assert.ok(page.includes("departments.filter((dept) => dept.active !== false).length"));
  });
});
