import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createElement as h } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import {
  AssignmentsPageSkeleton, DepartmentsPageSkeleton, LoadingRegion, StaffPageSkeleton, TasksPageSkeleton, UsersPageSkeleton, WorkloadPageSkeleton,
} from './PageSkeletons.js';

// Stage 5D: page-level loading layouts. Rendered with React and compared with the loaded pages' own markup.
const read = (path) => readFileSync(new URL(`../../${path}`, import.meta.url), 'utf8').replace(/\r\n/g, '\n');
const render = (type, props = {}) => renderToStaticMarkup(h(type, props));
const count = (html, pattern) => (html.match(pattern) || []).length;
const headings = (html) => [...html.matchAll(/<th class="([^"]*)">([^<]*)<\/th>/g)].map((m) => ({ cls: m[1], label: m[2] }));
const cols = (html) => [...html.matchAll(/<col class="([^"]*)"\/>/g)].map((m) => m[1]);

const PAGES = {
  Tasks: { Skeleton: TasksPageSkeleton, label: 'Loading tasks and assignments...', file: 'pages/TasksPage.jsx', cond: 'isLoading' },
  Staff: { Skeleton: StaffPageSkeleton, label: 'Loading staff directory and workload...', file: 'pages/StaffPage.jsx', cond: 'isLoading' },
  Assignments: { Skeleton: AssignmentsPageSkeleton, label: 'Loading assignments...', file: 'pages/AssignmentsPage.jsx', cond: 'isLoading && !hasLoaded' },
  Workload: { Skeleton: WorkloadPageSkeleton, label: 'Loading staff workload data...', file: 'pages/WorkloadPage.jsx', cond: 'isLoading' },
  Departments: { Skeleton: DepartmentsPageSkeleton, label: 'Loading departments...', file: 'pages/DepartmentsPage.jsx', cond: 'isLoading' },
  Users: { Skeleton: UsersPageSkeleton, label: 'Loading users…', file: 'pages/UsersPage.jsx', cond: 'loading && !hasLoaded' },
};

describe('every page skeleton: one busy region, one status message, nothing else announced or focusable', () => {
  for (const [name, { Skeleton, label }] of Object.entries(PAGES)) {
    it(name, () => {
      const html = render(Skeleton, { label });
      assert.ok(html.startsWith('<div aria-busy="true" data-loading-region="" class="'), 'the loading content region is aria-busy');
      assert.equal(count(html, /aria-busy=/g), 1);
      assert.equal(count(html, /role="status"/g), 1, 'one status message');
      assert.ok(html.endsWith(`<p role="status" class="sr-only">${label}</p></div>`), 'concise, screen-reader only, last (outside every hidden part)');
      assert.doesNotMatch(html, /aria-live|role="alert"/, 'no other live region');
      assert.doesNotMatch(html, /<(button|input|select|a|textarea)\b|tabindex/, 'nothing focusable');
      const placeholders = count(html, /data-skeleton=""/g);
      assert.ok(placeholders > 10, `${placeholders} placeholders`);
      assert.equal(count(html, /aria-hidden="true" data-skeleton=""/g), placeholders, 'every placeholder is hidden from assistive technology');
      for (const table of html.match(/<table[^>]*>/g) || []) assert.match(table, /aria-hidden="true"/, 'tables are decorative');
    });
  }

  it('LoadingRegion puts the message after the content, so it never adds spacing in a space-y container', () => {
    assert.equal(render(LoadingRegion, { label: 'Loading x', className: 'space-y-6', children: h('span', null, 'A') }),
      '<div aria-busy="true" data-loading-region="" class="space-y-6"><span>A</span><p role="status" class="sr-only">Loading x</p></div>');
  });
});

describe('each skeleton follows its loaded page: same containers, columns and widths', () => {
  it('Tasks: 5 summary cards, the 4-field filter row and the 8-column table, in the components\' own classes', () => {
    const html = render(TasksPageSkeleton);
    const cards = read('components/tasks/TaskSummaryCards.jsx');
    const filters = read('components/tasks/TaskFilters.jsx');
    const table = read('components/tasks/TasksTable.jsx');
    assert.ok(cards.includes('"grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-5 gap-4 sm:gap-6 w-full"') && html.includes('class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-5 gap-4 sm:gap-6 w-full"'));
    assert.equal(count(html, /class="bg-white rounded-xl p-5 sm:p-6 shadow-sm border border-gray-100 flex items-center space-x-4"/g), 5);
    assert.ok(cards.includes('className="bg-white rounded-xl p-5 sm:p-6 shadow-sm border border-gray-100 flex items-center space-x-4"'));
    assert.ok(filters.includes('className="flex flex-col md:flex-row items-center gap-3 w-full"'));
    for (const width of ['md:w-44', 'md:w-48']) assert.ok(filters.includes(`"w-full ${width}"`) && html.includes(width));
    assert.ok(read('pages/TasksPage.jsx').includes('className="bg-white rounded-xl shadow-sm border border-gray-200 overflow-hidden w-full"'));
    const ths = headings(html);
    assert.equal(ths.length, 8);
    for (const { cls, label } of ths) assert.ok(table.includes(`<th className="${cls}">${label}</th>`), `column "${label}" matches TasksTable`);
    assert.equal(count(html, /<tr class="h-\[65px\]">/g), 6, 'rows at the loaded row height');
  });

  it('Staff: the 4-field filter row and the 7-column table', () => {
    const html = render(StaffPageSkeleton);
    const filters = read('components/staff/StaffFilters.jsx');
    for (const width of ['md:w-48', 'md:w-40', 'md:w-36']) assert.ok(filters.includes(`"w-full ${width}"`) && html.includes(width), width);
    const table = read('components/staff/StaffTable.jsx');
    assert.ok(table.includes('className="w-full text-left border-collapse table-fixed min-w-[700px] lg:min-w-0"') && html.includes('class="w-full text-left border-collapse table-fixed min-w-[700px] lg:min-w-0"'));
    const ths = headings(html);
    assert.equal(ths.length, 7);
    for (const { cls, label } of ths) assert.ok(table.includes(`<th className="${cls}">${label}</th>`), `column "${label}" matches StaffTable`);
    assert.equal(count(html, /<tr class="h-\[73px\]">/g), 6);
  });

  it('Assignments: 4 summary cards, the list card with its heading and filters, and the 6-column table', () => {
    const html = render(AssignmentsPageSkeleton);
    const page = read('pages/AssignmentsPage.jsx');
    for (const cls of ['grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4 lg:gap-5', 'bg-white rounded-xl p-5 shadow-sm border border-gray-200/70 flex items-center gap-4',
      'bg-white rounded-xl shadow-sm border border-gray-200/70 overflow-hidden', 'px-6 pt-5 pb-3 space-y-3', 'w-full min-w-[760px] text-left']) {
      assert.ok(page.includes(`"${cls}"`) && html.includes(`class="${cls}"`), cls);
    }
    assert.equal(count(html, /class="bg-white rounded-xl p-5 shadow-sm border border-gray-200\/70 flex items-center gap-4"/g), 4);
    const ths = headings(html);
    assert.deepEqual(ths.map((t) => t.label), ['Task', 'Staff Member', 'Department', 'Assigned Time', 'Status', 'Actions']);
    for (const { cls, label } of ths) assert.ok(page.includes(`<th className="${cls}">${label}</th>`), `column "${label}" matches the page`);
    assert.ok(html.includes('h-9 rounded-lg'), 'filter fields at the 36px height of the Assignments filters');
  });

  it('Workload: summary, legend, departments and the distribution table (md and up) or cards (phones); support frame only when shown', () => {
    const page = read('pages/WorkloadPage.jsx');
    const html = render(WorkloadPageSkeleton);
    for (const cls of ['grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4', 'bg-white rounded-xl p-4 shadow-2xs border border-gray-100 flex items-center space-x-3.5',
      'bg-white rounded-xl border border-gray-100 shadow-2xs px-4 py-3 space-y-2.5', 'grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-2', 'grid grid-cols-[repeat(auto-fill,minmax(min(100%,17rem),1fr))] gap-4',
      'min-w-0 bg-white rounded-xl shadow-2xs border border-gray-100 overflow-hidden', 'block md:hidden divide-y divide-gray-100', 'w-full text-left border-collapse table-fixed']) {
      assert.ok(page.includes(cls) && html.includes(cls), cls);
    }
    assert.ok(page.includes('bg-white rounded-xl shadow-2xs border border-gray-100 px-4 pt-3 pb-4 sm:px-5 sm:pt-3.5 sm:pb-5 space-y-3'));
    assert.deepEqual(cols(html), ['w-[24%] lg:w-[25%]', 'w-[16%] lg:w-[18%]', 'w-[11%] lg:w-[10%]', 'w-[12%] lg:w-[10%]', 'w-[18%] lg:w-[22%]', 'w-[19%] lg:w-[15%]']);
    for (const width of cols(html)) assert.ok(page.includes(`<col className="${width}" />`), width);
    assert.deepEqual(headings(html).map((t) => t.label), ['Staff', 'Department', 'Tasks', 'Workload', 'Utilization', 'Availability']);
    assert.equal(count(html, /<tr class="h-\[66px\]">/g), 5);
    assert.ok(!html.includes('rounded-2xl'), 'no support frame by default');
    const withSupport = render(WorkloadPageSkeleton, { showSupport: true });
    assert.ok(withSupport.includes('min-w-0 bg-white rounded-2xl shadow-2xs border border-gray-100 px-4 pt-4 pb-5 sm:px-6 sm:pt-5 sm:pb-6 space-y-6'));
    assert.ok(read('components/workload/CrossDepartmentSupportPanel.jsx').includes('min-w-0 bg-white rounded-2xl shadow-2xs border border-gray-100 px-4 pt-4 pb-5 sm:px-6 sm:pt-5 sm:pb-6 space-y-6'));
  });

  it('Departments: 3 summary cards and the department card grid', () => {
    const page = read('pages/DepartmentsPage.jsx');
    const html = render(DepartmentsPageSkeleton);
    for (const cls of ['grid grid-cols-1 md:grid-cols-3 gap-6', 'bg-white rounded-xl px-6 py-4 shadow-sm border border-gray-100 flex items-center space-x-4',
      'grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-5 lg:gap-6', 'flex items-center gap-4 px-5 pt-5 pb-4', 'mx-5 border-t border-gray-100', 'px-5 pt-4 pb-5 space-y-3.5']) {
      assert.ok(page.includes(cls) && html.includes(`class="${cls}"`), cls);
    }
    assert.ok(page.includes('min-w-0 bg-white rounded-2xl border border-gray-200/80 shadow-sm'));
    assert.equal(count(html, /class="min-w-0 bg-white rounded-2xl border border-gray-200\/80 shadow-sm"/g), 6);
  });

  it('Users: the same filter placeholders as before, and the 5-column table in its card instead of one block', () => {
    const html = render(UsersPageSkeleton);
    const page = read('pages/UsersPage.jsx');
    const table = read('components/users/UsersTable.jsx');
    assert.ok(html.includes('class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-[minmax(0,1.35fr)_minmax(0,1fr)_minmax(0,1fr)] gap-3 sm:gap-4"'));
    assert.equal(count(html, /class="animate-pulse bg-gray-200 h-11 rounded-xl[^"]*"/g), 3, 'the three 44px filter placeholders, as before');
    assert.ok(table.includes('className="bg-white rounded-xl border border-gray-200 shadow-sm overflow-hidden"') && html.includes('class="bg-white rounded-xl border border-gray-200 shadow-sm overflow-hidden"'), 'the card UsersTable draws');
    assert.ok(page.includes('<UsersTable'));
    for (const width of cols(html)) assert.ok(table.includes(`<col className="${width}" />`), width);
    for (const { cls, label } of headings(html)) assert.ok(table.includes(`<th scope="col" className="${cls}">${label}</th>`), `column "${label}" matches UsersTable`);
    assert.equal(count(html, /<tr class="h-\[77px\]">/g), 6);
  });
});

describe('the pages', () => {
  it('each shows its real header above its skeleton while the first load runs (no copy of the header, nothing faked)', () => {
    for (const [name, { file, cond, label }] of Object.entries(PAGES)) {
      const page = read(file);
      const header = name === 'Departments' || name === 'Users' ? '{header}' : '{pageHeader}';
      const tag = `<${name}PageSkeleton label="${label}"`;
      assert.match(page, new RegExp(`if \\(${cond.replace(/[()&!]/g, '\\$&')}\\) \\{\\s*return \\(\\s*<div className="w-full space-y-\\d">\\s*${header.replace(/[{}]/g, '\\$&')}\\s*${tag.replace(/[.]/g, '\\.')}`), name);
      if (header === '{pageHeader}') {
        assert.equal(page.split('{pageHeader}').length - 1, 2, `${name}: the loaded page renders the same header`);
        assert.ok(page.includes('const pageHeader = ('), name);
      }
      assert.doesNotMatch(page, /<Spinner size="(lg|md)"/, `${name}: no page spinner left`);
    }
  });

  it('skeletons are for the first load only: the refreshes stay silent, and two pages that reloaded visibly now keep their content', () => {
    const tasks = read('pages/TasksPage.jsx');
    assert.ok(tasks.includes('if (showLoading) setIsLoading(true);') && (tasks.match(/fetchTasksData\(false\)/g) || []).length >= 5, 'Tasks: refreshes after a change pass false');
    const staff = read('pages/StaffPage.jsx');
    assert.ok(staff.includes('if (showLoading) setIsLoading(true);') && (staff.match(/fetchStaffData\(false\)/g) || []).length >= 5);
    assert.match(read('pages/WorkloadPage.jsx'), /if \(!isBackground\) \{\s*setIsLoading\(true\);/, 'Workload: polling and tab returns are background loads');
    assert.ok(read('pages/DepartmentsPage.jsx').includes('fetchDepartments(true)'), 'Departments: silent after a save');
    const users = read('pages/UsersPage.jsx');
    assert.ok(users.includes('if (showLoading) setLoading(true);') && users.includes('setHasLoaded(true);') && users.includes('const [hasLoaded, setHasLoaded] = useState(false);'));
    const assignments = read('pages/AssignmentsPage.jsx');
    assert.match(assignments, /setAssignments\(Array\.isArray\(assignmentsData\) \? assignmentsData : \[\]\);\s*setHasLoaded\(true\);/, 'only a successful load counts');
    assert.ok(assignments.includes('const [hasLoaded, setHasLoaded] = useState(false);'));
  });

  it('a failed first load still shows the existing error panel with its retry', () => {
    for (const [file, line] of [
      ['pages/TasksPage.jsx', '<LoadError title="Failed to load tasks" message={error} onRetry={() => fetchTasksData(true)} retryLabel="Retry" />'],
      ['pages/AssignmentsPage.jsx', '<LoadError title="Failed to load assignments" message={error} onRetry={fetchData} retryLabel="Try Again" />'],
      ['pages/WorkloadPage.jsx', '<LoadError title="Failed to load staff workload" message={error} onRetry={() => fetchData()} retryLabel="Try Again" />'],
      ['pages/DepartmentsPage.jsx', '<LoadError title="Failed to load departments" message={error} onRetry={() => fetchDepartments()} retryLabel="Retry" />'],
      ['pages/StaffPage.jsx', 'onClick={() => fetchStaffData(true)}'],
      ['pages/UsersPage.jsx', 'onClick={() => fetchUsers()}'],
    ]) {
      const page = read(file);
      assert.ok(page.includes(line), file);
      assert.ok(page.indexOf('PageSkeleton label=') < page.indexOf('  if (error) {'), `${file}: loading is decided first, then the error, as before`);
    }
  });

  it('the Dashboard is untouched in this stage', () => {
    const dashboard = read('pages/Dashboard.jsx');
    assert.ok(dashboard.includes('<Spinner size="lg" className="text-emerald-600" />') && !dashboard.includes('PageSkeleton'));
  });
});
