import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { assignmentStatusTone, filterControlClass, staffInitials } from './assignmentTableStyles.js';

const read = (...parts) => readFileSync(new URL(`../../${parts.join('/')}`, import.meta.url), 'utf8');
const page = read('pages', 'AssignmentsPage.jsx');
const bar = read('components', 'assignments', 'AssignmentFilters.jsx');
const badge = read('components', 'assignments', 'AssignmentStatusBadge.jsx');

// ── visual helpers ───────────────────────────────────────────────────────────────────────────────────────────

test('status badges: a soft pill with a dot in the same hue family as the Tasks table, whatever the spelling', () => {
  const hue = (s) => assignmentStatusTone(s).dot;
  assert.equal(hue('PENDING'), 'bg-amber-500');
  assert.equal(hue('ASSIGNED'), 'bg-blue-500');
  assert.equal(hue('Active'), 'bg-blue-500', 'legacy "Active" reads as Assigned');
  assert.equal(hue('in progress'), 'bg-purple-500');
  assert.equal(hue('IN_PROGRESS'), 'bg-purple-500');
  assert.equal(hue('Completed'), 'bg-emerald-500');
  assert.equal(hue('CANCELLED'), 'bg-gray-400');
  for (const unknown of [null, undefined, '', 'SOMETHING']) assert.equal(hue(unknown), 'bg-gray-400');
  assert.match(assignmentStatusTone('PENDING').badge, /^bg-amber-50 text-amber-800 ring-amber-200\/80$/);
  assert.ok(badge.includes('ring-1 ring-inset ${tone.badge}') && badge.includes('rounded-full ${tone.dot}'));
  assert.ok(badge.includes('aria-hidden="true"'), 'the dot is decoration for screen readers; the label carries the status');
});

test('staff initials for the small avatar', () => {
  assert.equal(staffInitials('Rahul Sharma'), 'RS');
  assert.equal(staffInitials('  sofia   del marino '), 'SM');
  assert.equal(staffInitials('priya'), 'PR');
  assert.equal(staffInitials(''), '?');
  assert.equal(staffInitials(null), '?');
});

test('filter fields share one height, radius and focus ring; a set filter is tinted green in both themes', () => {
  const idle = filterControlClass(false);
  const active = filterControlClass(true);
  for (const cls of [idle, active]) {
    assert.ok(cls.includes('h-9') && cls.includes('rounded-lg') && cls.includes('focus:ring-2 focus:ring-emerald-500/20 focus:border-emerald-500'));
  }
  assert.ok(idle.includes('bg-white border-gray-200') && !idle.includes('emerald-300'));
  assert.ok(active.includes('bg-emerald-50/60 border-emerald-300 text-emerald-900'));
  assert.ok(active.includes('dark:bg-emerald-500/10 dark:border-emerald-500/40'), 'dark mode gets its own tint (beats the global dark field background)');
});

// ── filter bar ───────────────────────────────────────────────────────────────────────────────────────────────

test('filter bar: the fields sit directly in the card (no outer container styling); each field highlights green while it holds a value', () => {
  assert.ok(bar.includes('<div className="flex flex-col md:flex-row md:items-center gap-2 w-full">'),
    'one row from md up, stacked on a phone, with only a gap between the fields');
  const outer = bar.slice(bar.indexOf('return ('), bar.indexOf('{/* 1. Search'));
  assert.doesNotMatch(outer, /border|bg-|shadow|rounded|\bp-|px-|py-|emerald/, 'no outer border, background, shadow, rounding or padding');
  assert.ok(bar.includes('gap-1.5 h-9 px-3 rounded-lg'), 'Clear Filters matches the shorter fields');
  assert.ok(page.includes('<div className="px-6 pt-5 pb-3 space-y-3">'), 'less space between the filters and the table header');
  assert.ok(bar.includes("const searchActive = Boolean(String(filters.search || '').trim());"));
  assert.ok(bar.includes('className={`${filterControlClass(searchActive)} pl-10 pr-4 placeholder-gray-400`}'));
  assert.ok(bar.includes('className={`${filterControlClass(Boolean(filters.department))} px-3 cursor-pointer`}'));
  assert.ok(bar.includes('className={`${filterControlClass(Boolean(filters.assignedTime))} px-3 cursor-pointer`}'));
  // behaviour unchanged: same handlers, labels and Clear Filters gate
  assert.ok(bar.includes("onChange={(e) => onChange('search', e.target.value)}"));
  assert.ok(bar.includes("onChange={(e) => onChange('department', e.target.value)}"));
  assert.ok(bar.includes("onChange={(e) => onChange('assignedTime', e.target.value)}"));
  assert.match(bar, /\{isActive && \(\s*<button[\s\S]*?onClick=\{onClear\}[\s\S]*?<CloseIcon className="w-3\.5 h-3\.5" aria-hidden="true" \/>\s*Clear Filters/);
});

// ── heading, count, table ────────────────────────────────────────────────────────────────────────────────────

test('heading: a larger title with the subtitle, and the count as a quiet pill that turns green while filtering', () => {
  assert.ok(page.includes('<h2 className="text-lg font-semibold tracking-tight text-gray-900">Current Staff Assignments</h2>'));
  assert.ok(page.includes('Monitor and manage your team&apos;s active task assignments.'));
  assert.match(page, /filtersActive \? 'border-emerald-200 bg-emerald-50 text-emerald-800' : 'border-gray-200 bg-gray-50 text-gray-600'/);
  assert.ok(page.includes('aria-live="polite"\n              data-testid="assignment-match-count"'));
});

test('table: stronger header, roomier rows with hover, readable long text, and a stable layout on small screens', () => {
  assert.ok(page.includes('<table className="w-full min-w-[760px] text-left">'), 'columns keep their layout; the card scrolls sideways on a phone');
  assert.ok(page.includes('bg-emerald-50/50 text-[11px] font-semibold uppercase tracking-wider whitespace-nowrap text-gray-600 border-y border-gray-200/70'), 'header labels stay on one line');
  assert.match(page, /className=\{`transition-colors duration-150 hover:bg-emerald-50\/40 \$\{isMenuOpen \? 'bg-emerald-50\/40' : ''\}`\}/);
  assert.ok(page.includes('line-clamp-2" title={taskTitle}>{taskTitle}</p>'), 'long titles wrap to two lines with the full text in a tooltip');
  assert.ok(page.includes('truncate font-medium text-gray-700" title={staffName}>{staffName}</span>'), 'long names are truncated with a tooltip');
  assert.ok(page.includes('initials={staffInitials(staffName)}'));
  assert.ok(page.includes('<AssignmentStatusBadge status={statusRaw} label={statusLabel} />'));
});

test('actions: a quiet ⋮ button that lifts on hover, keeps its focus ring and stays highlighted while its menu is open', () => {
  assert.ok(page.includes("'border-emerald-300 bg-emerald-50 text-emerald-700 shadow-xs'"));
  assert.ok(page.includes("'border-transparent bg-transparent text-gray-400 hover:border-gray-200 hover:bg-white hover:text-gray-700 hover:shadow-xs dark:hover:bg-gray-100'"));
  assert.ok(page.includes('focus-visible:ring-2 focus-visible:ring-emerald-500 focus-visible:ring-offset-1'));
});

// ── nothing but the look changed ─────────────────────────────────────────────────────────────────────────────

test('logic untouched: filters, page reset, pagination, summary and permission checks are exactly as before', () => {
  for (const line of [
    'filterAssignments(assignments, filters, { tasks, workload })',
    'getPaginationState({ totalItems: filteredAssignments.length, currentPage, pageSize })',
    'filteredAssignments.slice(pagination.startIndex, pagination.endIndex)',
    'calculateAssignmentSummary({ assignments, tasks })',
    "const canManageAssignments = userRole === 'ADMIN' || userRole === 'MANAGER';",
    'onClick={(e) => handleActionsButtonClick(e, assignment)}',
    'if (assignment && !isAssignmentTaskFinished(assignment, tasks)) handleOpenReassignModal(assignment);',
    '{canManageAssignments && isAssignmentTaskFinished(activeMenu.assignment, tasks) && (',
    '{canManageAssignments && !isAssignmentTaskFinished(activeMenu.assignment, tasks) && (',
  ]) {
    assert.ok(page.includes(line), line);
  }
  assert.match(page, /const handleFilterChange = \(key, value\) => \{\s*setFilters\(\(prev\) => \(\{ \.\.\.prev, \[key\]: value \}\)\);\s*setCurrentPage\(1\);/);
  assert.match(page, /const handleClearFilters = \(\) => \{\s*setFilters\(EMPTY_ASSIGNMENT_FILTERS\);\s*setCurrentPage\(1\);/);
});

test('the user\'s saved theme is respected: these files never read or write the theme preference', () => {
  for (const source of [page, bar, badge, read('components', 'assignments', 'assignmentTableStyles.js')]) {
    assert.doesNotMatch(source, /useTheme|taskotel-theme|themePreference|localStorage/);
  }
});
