import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

// Stage 2 of the animation plan: the existing animations and transitions use the shared motion system (src/theme/motion.cjs).
const SRC = fileURLToPath(new URL('../', import.meta.url));
const components = [];
(function walk(dir) {
  for (const name of readdirSync(dir)) {
    const full = join(dir, name);
    if (statSync(full).isDirectory()) walk(full);
    else if (/\.jsx$/.test(name)) components.push({ file: relative(SRC, full).replace(/\\/g, '/'), text: readFileSync(full, 'utf8') });
  }
})(SRC);
const read = (file) => components.find((c) => c.file === file).text;
const filesWith = (pattern) => components.filter((c) => pattern.test(c.text)).map((c) => c.file).sort();
const count = (pattern) => components.reduce((n, c) => n + (c.text.match(pattern) || []).length, 0);

test('no undefined animate-fadeIn is left; entrances use the shared animate-fade-in', () => {
  assert.deepEqual(filesWith(/animate-fadeIn\b/), []);
  // 38 after Stage 2; Stage 3 moved modal backdrops, row menus and success banners to their entrance / exit pairs
  // (theme/presence.js); Stage 4 replaced the Workload page's own fade with the shared page fade (theme/pageTransition.js).
  // What is left: inline alerts on the sign-in pages and in the assign form.
  assert.equal(count(/animate-fade-in\b/g), 11, 'every former use converted');
  assert.ok(read('components/tasks/EditTaskModal.jsx').includes('backdrop-blur-xs ${backdropMotion(closing)}'));
  assert.ok(read('components/tasks/TasksTable.jsx').includes('select-none ${menuMotion(menuPresence.closing)}'));
  assert.ok(read('pages/TasksPage.jsx').includes('${toastMotion(notice.closing)}'));
});

test('no infinite decorative motion: status dots are static; only real loading keeps animating', () => {
  // written as a pattern so this file itself does not make Tailwind generate the class
  assert.deepEqual(filesWith(new RegExp('animate-' + 'ping\\b')), [], 'no endlessly pinging success dots');
  // animate-pulse only on loading skeletons and on the archive button while archiving. Since Stage 5C the skeletons use the shared
  // components/common/Skeleton.js (the only other place the pulse is written), so in .jsx files only the archive button remains.
  assert.deepEqual(filesWith(/animate-pulse\b/), ['components/workload/support/SupportHistory.jsx']);
  assert.ok(read('components/workload/support/SupportHistory.jsx').includes("archivingId === row.id ? 'animate-pulse' : ''"), 'only while in progress');
  assert.ok(readFileSync(join(SRC, 'components/common/Skeleton.js'), 'utf8').includes("['animate-pulse',"), 'the shared skeleton pulses');
  assert.ok(read('pages/UsersPage.jsx').includes('<UsersPageSkeleton label="Loading users…" />'), 'loading skeleton kept (the shared page layout since Stage 5D)');
  assert.equal((read('pages/SettingsPage.jsx').match(/<Skeleton /g) || []).length, 3, 'system settings skeleton kept');
  for (const file of ['pages/AssignmentsPage.jsx', 'pages/CreateTaskPage.jsx', 'pages/TasksPage.jsx', 'pages/StaffPage.jsx', 'components/common/DashboardPlaceholder.jsx']) {
    assert.match(read(file), /rounded-full bg-emerald-500" \/>/, `${file}: static status dot`);
  }
});

test('spinners still animate while an operation is in progress', () => {
  // Before Stage 5C: 29 spinner SVGs (11 local Spinner functions, 14 inline spinners, 3 on the sign-in pages, 1 refresh icon).
  // The 11 functions (15 uses) and 14 inline spinners now use the shared components/common/Spinner.js (which carries animate-spin);
  // what is left in .jsx: the three sign-in pages' button spinners and the support panel's spinning refresh icon (a different graphic).
  // Stage 5D: the page loaders of Tasks, Staff, Assignments, Workload and Departments became page skeletons (6 → 1 page spinner: the
  // Dashboard's, reserved for Stage 5E), so 24 shared spinner uses remain: that one, and buttons, dialogs and inline messages.
  assert.equal(count(/animate-spin\b/g), 4, 'every loading spinner kept');
  assert.equal(count(/<Spinner\b/g), 24, 'shared spinner uses: 15 former local-function calls and 9 former inline spinners');
  assert.deepEqual(filesWith(/function Spinner|const Spinner =/), [], 'no local copies left');
  assert.ok(readFileSync(join(SRC, 'components/common/Spinner.js'), 'utf8').includes("['animate-spin',"), 'the shared spinner spins');
  assert.ok(read('pages/LoginPage.jsx').includes('animate-spin'));
  assert.ok(read('pages/Dashboard.jsx').includes('<Spinner size="lg" className="text-emerald-600" />'), 'the Dashboard loader is unchanged until Stage 5E');
});

test('no misleading hover motion on things that cannot be clicked; real controls keep their feedback', () => {
  assert.deepEqual(filesWith(/hover:scale-105/), [], 'auth logos no longer grow on hover');
  for (const file of ['pages/LoginPage.jsx', 'pages/ForgotPasswordPage.jsx', 'pages/SetCredentialsPage.jsx', 'pages/EmailVerificationPage.jsx']) {
    assert.match(read(file), /<TaskotelLogo className="[^"]*" \/>/);
    assert.doesNotMatch(read(file).match(/<TaskotelLogo className="([^"]*)"/)[1], /hover|transition|transform/, file);
  }
  assert.ok(read('pages/AssignmentsPage.jsx').includes('className="bg-white rounded-xl p-5 shadow-sm border border-gray-200/70 flex items-center gap-4"'), 'summary cards do not lift');
  assert.ok(read('pages/DepartmentsPage.jsx').includes('min-w-0 bg-white rounded-2xl border border-gray-200/80 shadow-sm ${isInactive'), 'department cards do not lift');
  // genuine buttons keep their hover feedback
  assert.ok(read('pages/LoginPage.jsx').includes('hover:shadow-lg hover:shadow-emerald-900/25'));
  assert.ok(read('pages/ProfilePage.jsx').includes('group-hover:brightness-90 group-hover:shadow-md'), 'the clickable profile picture keeps its hover');
});

test('no transition-all: each transition names what actually changes', () => {
  assert.deepEqual(filesWith(/\btransition-all\b/), []);
  // progress bars animate their width (and colour where the colour follows the status), at the shared slow duration
  assert.equal(count(/transition-\[width\] duration-slow ease-standard/g), 5, 'fixed-colour bars: 4 staff table bars and the dashboard list bar');
  assert.equal(count(/transition-\[width,background-color\] duration-slow ease-standard/g), 5, 'status-coloured bars');
  assert.ok(read('components/dashboard/StaffWorkloadList.jsx').includes('transition-[width] duration-slow ease-standard'), 'dashboard bar 250ms, not 500ms');
  assert.ok(read('pages/LoginPage.jsx').includes('transition-[color,background-color,border-color,box-shadow,opacity]'), 'inputs and buttons: colours and focus ring');
  assert.ok(read('pages/ProfilePage.jsx').includes('transition-[filter,box-shadow] duration-moderate') && read('pages/ProfilePage.jsx').includes('transition-[opacity,transform] duration-moderate'));
});

test('durations come from the shared tokens; the theme switch keeps its own timing', () => {
  assert.deepEqual(filesWith(/\bduration-(75|100|150|200|300|500|700|1000)\b/), [], 'no numbered durations left in components');
  assert.ok(read('components/layout/Sidebar.jsx').includes('transition-transform duration-moderate ease-in-out'), 'drawer: same 200ms, same easing');
  const css = readFileSync(join(SRC, 'index.css'), 'utf8');
  assert.ok(css.includes('--theme-transition-duration: 300ms;') && css.includes('--theme-reveal-duration: 900ms;'), 'theme switch untouched');
});
