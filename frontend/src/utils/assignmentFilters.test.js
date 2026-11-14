import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import {
  ASSIGNED_TIME_OPTIONS,
  EMPTY_ASSIGNMENT_FILTERS,
  filterAssignments,
  getPaginationState,
  hasActiveAssignmentFilters,
  matchesAssignedTime,
  normalizeSearchText,
  parseAssignedAt,
  resolveAssignmentDepartmentCode,
} from './assignmentUtils.js';
import { BUILT_IN_DEPARTMENT_NAMES } from './departmentRegistry.js';

const read = (...parts) => readFileSync(new URL(`../${parts.join('/')}`, import.meta.url), 'utf8');

// 14 Nov 2026, 15:30 local. Timestamps are written the way the backend sends them (local date-time, no zone).
const NOW = new Date(2026, 10, 14, 15, 30);
const at = (daysAgo, time = '10:00:00') => {
  const d = new Date(2026, 10, 14 - daysAgo);
  const pad = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${time}`;
};

const tasks = [
  { id: 't1', title: 'Room Cleaning', department: 'HOUSEKEEPING' },
  { id: 't2', title: 'Front Desk Assistance', department: 'FRONT_DESK' },
  { id: 't3', title: 'AC Maintenance', department: 'MAINTENANCE' },
];
const workload = [{ staffId: 's9', department: 'SECURITY' }];
const assignments = [
  { id: 'a1', taskId: 't1', taskTitle: 'Room Cleaning', staffId: 's1', staffName: 'Rahul Sharma', assignedAt: at(0, '09:15:00.123456') },
  { id: 'a2', taskId: 't2', taskTitle: 'Front Desk Assistance', staffId: 's2', staffName: 'Sofia Marino', assignedAt: at(3) },
  { id: 'a3', taskId: 't3', taskTitle: 'AC Maintenance', staffId: 's3', staffName: 'Omar Haddad', assignedAt: at(12) },
  { id: 'a4', taskId: 't1', taskTitle: 'Room Cleaning', staffId: 's4', staffName: 'Priya Nair', assignedAt: at(45) },
  { id: 'a5', taskId: 'gone', taskTitle: 'Night Patrol', staffId: 's9', staffName: 'Lena Fischer', assignedAt: null },
  { id: 'a6', taskId: 't2', taskTitle: 'Guest Check-in', staffId: 's5', staffName: 'Rahul Verma', assignedAt: 'not a date', department: 'FRONT_DESK' },
];
const run = (filters) => filterAssignments(assignments, { ...EMPTY_ASSIGNMENT_FILTERS, ...filters }, { tasks, workload, referenceDate: NOW }).map((a) => a.id);

// ── search ───────────────────────────────────────────────────────────────────────────────────────────────────

test('search matches the task title or the staff member, case-insensitive', () => {
  assert.deepEqual(run({ search: 'room' }), ['a1', 'a4']);
  assert.deepEqual(run({ search: 'ROOM CLEANING' }), ['a1', 'a4']);
  assert.deepEqual(run({ search: 'rahul' }), ['a1', 'a6'], 'staff name');
  assert.deepEqual(run({ search: 'Sofia' }), ['a2']);
  assert.deepEqual(run({ search: 'patrol' }), ['a5'], 'a record without a known department or time still matches by text');
});

test('search trims and collapses whitespace; spaces alone do not filter', () => {
  assert.deepEqual(run({ search: '   omar   ' }), ['a3']);
  assert.deepEqual(run({ search: 'front    desk' }), ['a2']);
  assert.deepEqual(run({ search: '     ' }), ['a1', 'a2', 'a3', 'a4', 'a5', 'a6']);
  assert.equal(normalizeSearchText('  Room \t  Cleaning '), 'room cleaning');
  assert.equal(normalizeSearchText(null), '');
});

test('search does not match other fields (department, status, ids)', () => {
  assert.deepEqual(run({ search: 'housekeeping' }), []);
  assert.deepEqual(run({ search: 'a1' }), []);
});

// ── department ───────────────────────────────────────────────────────────────────────────────────────────────

test('department: matches the code shown in the table (own field, else the task, else the staff member)', () => {
  assert.deepEqual(run({ department: 'HOUSEKEEPING' }), ['a1', 'a4']);
  assert.deepEqual(run({ department: 'FRONT_DESK' }), ['a2', 'a6']);
  assert.deepEqual(run({ department: 'SECURITY' }), ['a5'], 'falls back to the staff member\'s department');
  assert.deepEqual(run({ department: 'ROOM_SERVICE' }), []);
  assert.equal(resolveAssignmentDepartmentCode(assignments[0], tasks, workload), 'HOUSEKEEPING');
  assert.equal(resolveAssignmentDepartmentCode({ taskId: 'x', staffId: 'y' }, tasks, workload), '');
  assert.equal(resolveAssignmentDepartmentCode(null), '');
});

// ── assigned time ────────────────────────────────────────────────────────────────────────────────────────────

test('assigned time: the options, in order', () => {
  assert.deepEqual(ASSIGNED_TIME_OPTIONS.map((o) => o.label), ['Any Time', 'Today', 'Last 7 Days', 'Last 30 Days', 'Older']);
  assert.equal(ASSIGNED_TIME_OPTIONS[0].value, '');
});

test('assigned time: Today / Last 7 Days / Last 30 Days / Older use the assigned timestamp', () => {
  assert.deepEqual(run({ assignedTime: 'TODAY' }), ['a1']);
  assert.deepEqual(run({ assignedTime: 'LAST_7_DAYS' }), ['a1', 'a2']);
  assert.deepEqual(run({ assignedTime: 'LAST_30_DAYS' }), ['a1', 'a2', 'a3']);
  assert.deepEqual(run({ assignedTime: 'OLDER' }), ['a4']);
});

test('assigned time: range edges follow the local calendar', () => {
  const m = (when, range) => matchesAssignedTime(when, range, NOW);
  assert.equal(m(at(0, '00:00:00'), 'TODAY'), true);
  assert.equal(m(at(1, '23:59:59'), 'TODAY'), false);
  assert.equal(m(at(6, '00:00:00'), 'LAST_7_DAYS'), true, 'today and the 6 days before');
  assert.equal(m(at(7, '23:59:59'), 'LAST_7_DAYS'), false);
  assert.equal(m(at(29, '00:00:00'), 'LAST_30_DAYS'), true, 'today and the 29 days before');
  assert.equal(m(at(30, '23:59:59'), 'LAST_30_DAYS'), false);
  assert.equal(m(at(30, '23:59:59'), 'OLDER'), true);
  assert.equal(m(at(29, '00:00:00'), 'OLDER'), false);
  assert.equal(m(at(-1, '08:00:00'), 'TODAY'), false, 'a time later than today is in no range');
  assert.equal(m(at(-1, '08:00:00'), 'OLDER'), false);
  assert.equal(m(new Date(2026, 10, 14, 8, 0), 'TODAY'), true, 'a Date works too');
});

test('assigned time: missing or invalid timestamps appear only under Any Time', () => {
  for (const bad of [null, undefined, '', '   ', 'not a date', 42, {}, new Date('x')]) {
    assert.equal(parseAssignedAt(bad), null, String(bad));
    for (const range of ['TODAY', 'LAST_7_DAYS', 'LAST_30_DAYS', 'OLDER']) {
      assert.equal(matchesAssignedTime(bad, range, NOW), false, `${String(bad)} / ${range}`);
    }
    assert.equal(matchesAssignedTime(bad, '', NOW), true);
  }
  assert.ok(!run({ assignedTime: 'OLDER' }).includes('a5') && !run({ assignedTime: 'OLDER' }).includes('a6'));
  assert.equal(parseAssignedAt('2026-11-13T11:54:44.936581').getMinutes(), 54, 'the backend\'s microsecond timestamps are read');
});

// ── combined, clearing, empty ────────────────────────────────────────────────────────────────────────────────

test('combined: search, department and assigned time must all match', () => {
  assert.deepEqual(run({ search: 'room', department: 'HOUSEKEEPING', assignedTime: 'LAST_30_DAYS' }), ['a1']);
  assert.deepEqual(run({ search: 'rahul', department: 'FRONT_DESK' }), ['a6']);
  assert.deepEqual(run({ department: 'HOUSEKEEPING', assignedTime: 'OLDER' }), ['a4']);
});

test('empty result: filters that match nothing give an empty list', () => {
  assert.deepEqual(run({ search: 'zzz' }), []);
  assert.deepEqual(run({ search: 'sofia', department: 'HOUSEKEEPING' }), []);
  assert.deepEqual(run({ department: 'MAINTENANCE', assignedTime: 'TODAY' }), []);
  assert.deepEqual(filterAssignments(null, { search: 'x' }), []);
});

test('clearing: no filter returns the complete list itself, unchanged and in order', () => {
  assert.equal(hasActiveAssignmentFilters(EMPTY_ASSIGNMENT_FILTERS), false);
  assert.equal(hasActiveAssignmentFilters({ search: '  ', department: '', assignedTime: '' }), false);
  for (const f of [{ search: 'a' }, { department: 'SECURITY' }, { assignedTime: 'TODAY' }]) {
    assert.equal(hasActiveAssignmentFilters({ ...EMPTY_ASSIGNMENT_FILTERS, ...f }), true, JSON.stringify(f));
  }
  assert.equal(filterAssignments(assignments, EMPTY_ASSIGNMENT_FILTERS, { tasks, workload }), assignments);
  assert.equal(assignments.length, 6, 'filtering never changes the loaded list');
});

// ── pagination over the filtered list ───────────────────────────────────────────────────────────────────────

test('pagination runs on the filtered results and a filter change starts at page 1', () => {
  const many = Array.from({ length: 25 }, (_, i) => ({ id: `m${i}`, taskTitle: i % 2 ? 'Laundry' : 'Lobby check', staffName: 'Amit', assignedAt: at(0) }));
  const filtered = filterAssignments(many, { ...EMPTY_ASSIGNMENT_FILTERS, search: 'laundry' }, { referenceDate: NOW });
  assert.equal(filtered.length, 12);
  // the page was 3 of the full list; the filter resets it to 1 and paginates the 12 matches
  const page = getPaginationState({ totalItems: filtered.length, currentPage: 1, pageSize: 10 });
  assert.deepEqual([page.page, page.totalPages, page.rangeStart, page.rangeEnd], [1, 2, 1, 10]);
  assert.deepEqual(filtered.slice(page.startIndex, page.endIndex).every((a) => a.taskTitle === 'Laundry'), true);
  // a stale page beyond the filtered list is clamped (the page's existing guard)
  assert.equal(getPaginationState({ totalItems: filtered.length, currentPage: 3, pageSize: 10 }).page, 2);
});

// ── wiring: page and filter bar ─────────────────────────────────────────────────────────────────────────────

const page = read('pages', 'AssignmentsPage.jsx');
const bar = read('components', 'assignments', 'AssignmentFilters.jsx');

test('page: filters the already-loaded list (no request per keystroke) and paginates the result', () => {
  assert.ok(page.includes('filterAssignments(assignments, filters, { tasks, workload })'));
  assert.ok(page.includes('getPaginationState({ totalItems: filteredAssignments.length, currentPage, pageSize })'));
  assert.ok(page.includes('filteredAssignments.slice(pagination.startIndex, pagination.endIndex)'));
  assert.ok(page.includes('calculateAssignmentSummary({ assignments, tasks })'), 'summary cards still use the full list');
  assert.doesNotMatch(bar, /services\/api|fetch\(/, 'the filter bar makes no data request of its own besides the department list hook');
});

test('page: every filter change and Clear Filters go back to page 1; clearing restores the full list', () => {
  assert.match(page, /const handleFilterChange = \(key, value\) => \{\s*setFilters\(\(prev\) => \(\{ \.\.\.prev, \[key\]: value \}\)\);\s*setCurrentPage\(1\);/);
  assert.match(page, /const handleClearFilters = \(\) => \{\s*setFilters\(EMPTY_ASSIGNMENT_FILTERS\);\s*setCurrentPage\(1\);/);
  assert.match(page, /<AssignmentFilters\s+filters=\{filters\}\s+onChange=\{handleFilterChange\}\s+onClear=\{handleClearFilters\}\s+isActive=\{filtersActive\}/);
});

test('page: controls sit under the heading and above the table; the match count is shown', () => {
  const heading = page.indexOf('Current Staff Assignments</h2>');
  const controls = page.indexOf('<AssignmentFilters');
  const table = page.indexOf('<table className="w-full min-w-[760px] text-left">');
  assert.ok(heading > 0 && heading < controls && controls < table);
  assert.ok(page.includes('data-testid="assignment-match-count"'));
  assert.match(page, /\{filteredAssignments\.length\}<\/span> of \{assignments\.length\}/);
});

test('page: a helpful empty state when nothing matches, separate from "no assignments at all"', () => {
  assert.match(page, /assignments\.length === 0 \? \([\s\S]*?No assignments found[\s\S]*?\) : filteredAssignments\.length === 0 \? \([\s\S]*?No assignments match your filters[\s\S]*?onClick=\{handleClearFilters\}[\s\S]*?Clear Filters/);
  assert.ok(page.includes('{filteredAssignments.length > 0 && ('), 'no pagination footer for an empty result');
});

test('filter bar: search is the wide field, then Department and Assigned Time; Clear Filters only while active', () => {
  assert.ok(bar.indexOf('placeholder="Search by task or staff member..."') < bar.indexOf('aria-label="Filter by department"'));
  assert.ok(bar.indexOf('aria-label="Filter by department"') < bar.indexOf('aria-label="Filter by assigned time"'));
  assert.ok(bar.includes('className="relative flex-1 w-full min-w-0"'), 'search grows to fill the row');
  assert.ok(bar.includes('className="w-full md:w-48"') && bar.includes('className="w-full md:w-44"'), 'dropdowns keep a fixed width from md up');
  assert.ok(bar.includes('flex flex-col md:flex-row'), 'stacks on small screens');
  assert.match(bar, /\{isActive && \(\s*<button[\s\S]*?onClick=\{onClear\}[\s\S]*?Clear Filters/);
});

test('filter bar: departments come from the app (names and codes), not a hardcoded list', () => {
  assert.ok(bar.includes('const { departments } = useDepartments();'));
  assert.ok(bar.includes('<option value="">All Departments</option>') && bar.includes('<DepartmentOptions departments={departments} />'));
  assert.deepEqual(Object.values(BUILT_IN_DEPARTMENT_NAMES), ['Housekeeping', 'Front Desk', 'Maintenance', 'Room Service', 'Food & Beverage', 'Security']);
  for (const name of Object.values(BUILT_IN_DEPARTMENT_NAMES)) assert.ok(!bar.includes(name), `${name} is not typed into the filter bar`);
  assert.ok(bar.includes('ASSIGNED_TIME_OPTIONS.map((option) =>'));
});
