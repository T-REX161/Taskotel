import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { donutArcs, durationPercent, formatMinutes, greetingFor, sortByLoad, taskStatusSegments, workloadTone } from '../utils/dashboardView.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const read = (...parts) => fs.readFileSync(path.join(__dirname, '..', ...parts), 'utf8').replace(/\r\n/g, '\n');

describe('task status chart data', () => {
  const data = { pendingTasks: 2, assignedTasks: 1, inProgressTasks: 1, completedTasks: 4, cancelledTasks: 2 };

  it('counts and shares come from the summary, cancelled included for Admin and Manager', () => {
    const { total, rows } = taskStatusSegments(data, false);
    assert.equal(total, 10);
    assert.deepEqual(rows.map((r) => [r.label, r.count, r.percent]), [['Pending', 2, 20], ['Assigned', 1, 10], ['In Progress', 1, 10], ['Completed', 4, 40], ['Cancelled', 2, 20]]);
  });

  it('Staff get no cancelled row, and missing values are zero, never invented', () => {
    assert.equal(taskStatusSegments(data, true).rows.some((r) => r.key === 'cancelledTasks'), false);
    const empty = taskStatusSegments({}, false);
    assert.equal(empty.total, 0);
    assert.ok(empty.rows.every((r) => r.count === 0 && r.percent === 0));
    assert.deepEqual(donutArcs(empty.rows, 50), []);
  });

  it('donut arcs add up to the full circle and follow each other', () => {
    const { rows } = taskStatusSegments(data, false);
    const arcs = donutArcs(rows, 50);
    const circumference = 2 * Math.PI * 50;
    assert.equal(arcs.length, 5);
    assert.ok(Math.abs(arcs.reduce((sum, a) => sum + a.length, 0) - circumference) < 1e-9);
    assert.ok(Math.abs(arcs[1].offset + arcs[0].length) < 1e-9);
  });
});

describe('workload display helpers', () => {
  it('bands match the backend thresholds and unknown values fall back safely', () => {
    assert.equal(workloadTone('AT_RISK').label, 'At risk');
    assert.equal(workloadTone('HIGH').label, 'High');
    assert.equal(workloadTone(undefined).label, 'Low');
  });

  it('durations are formatted and clamped', () => {
    assert.equal(formatMinutes(0), '0m');
    assert.equal(formatMinutes(45), '45m');
    assert.equal(formatMinutes(120), '2h');
    assert.equal(formatMinutes(150), '2h 30m');
    assert.equal(formatMinutes(null), '0m');
    assert.equal(durationPercent(180), 50);
    assert.equal(durationPercent(900), 100);
    assert.equal(durationPercent(-5), 0);
  });

  it('busiest first, then by name, and the input is not changed', () => {
    const input = [{ staffName: 'B', totalEstimatedDuration: 10 }, { staffName: 'A', totalEstimatedDuration: 10 }, { staffName: 'C', totalEstimatedDuration: 200 }];
    assert.deepEqual(sortByLoad(input).map((r) => r.staffName), ['C', 'A', 'B']);
    assert.equal(input[0].staffName, 'B');
    assert.deepEqual(sortByLoad(null), []);
  });

  it('greets by time of day', () => {
    assert.deepEqual([greetingFor(8), greetingFor(14), greetingFor(21)], ['Good morning', 'Good afternoon', 'Good evening']);
  });
});

describe('header', () => {
  const header = read('components', 'layout', 'Header.jsx');

  it('the messages, notifications and settings icons are gone; search stays', () => {
    for (const gone of ['MessageIcon', 'BellIcon', 'SettingsIcon', 'aria-label="Messages"', 'aria-label="Notifications"', 'aria-label="Settings"']) {
      assert.ok(!header.includes(gone), gone);
    }
    assert.ok(header.includes('placeholder="Search..."') && header.includes('<SearchIcon'));
  });

  it('the theme toggle is untouched and still sits just before the divider', () => {
    assert.ok(header.includes('const { isDark, toggleTheme } = useTheme();'));
    assert.ok(header.indexOf('Theme toggle') < header.indexOf('{/* Divider */}') && header.indexOf('{/* Divider */}') < header.indexOf('Current User Avatar'));
  });

  it('shows the signed-in user: initials avatar, green dot, name and role', () => {
    assert.ok(header.includes('getUsername()') && header.includes('getUserRole()'));
    assert.ok(header.includes('{initials}') && header.includes('{displayName}') && header.includes('{roleLabel}') && header.includes('bg-emerald-500'));
    assert.ok(!header.includes("'Administrator'") && !header.includes("|| 'ADMIN'"), 'nothing falls back to Admin');
  });

  it('describeUser derives initials, name and role from the login, never assumes Admin', () => {
    const src = header.slice(header.indexOf('export function describeUser'), header.indexOf('export default function Header'));
    const describeUser = new Function(`${src.replace('export function', 'function')}; return describeUser;`)();
    assert.deepEqual(describeUser('admin', 'ADMIN'), { displayName: 'Admin', initials: 'AD', roleLabel: 'Admin' });
    assert.deepEqual(describeUser('maria', 'MANAGER'), { displayName: 'Maria', initials: 'MA', roleLabel: 'Manager' });
    assert.deepEqual(describeUser('rocky', 'STAFF'), { displayName: 'Rocky', initials: 'RO', roleLabel: 'Staff' });
    assert.deepEqual(describeUser(null, null), { displayName: 'Account', initials: 'AC', roleLabel: '' });
  });

  it('the shell no longer passes handlers for the removed icons', () => {
    const shell = read('components', 'layout', 'AppShell.jsx');
    assert.ok(!/onSettingsClick|onNotificationClick|onMessagesClick/.test(shell));
  });
});

describe('dashboard page', () => {
  const page = read('pages', 'Dashboard.jsx');

  it('greets the authenticated user and loads per-person workload for Admin and Manager only', () => {
    assert.ok(page.includes('describeUser(getUsername(), userRole)') && page.includes('greetingFor('));
    assert.ok(page.includes("if (getUserRole() !== 'STAFF') loadStaffWorkload();"));
    assert.ok(page.includes('staffWorkload={isStaff ? undefined : staffWorkload}'));
  });

  it('uses the existing API and has no literal sample figures in the new components', () => {
    for (const file of ['SummaryCards.jsx', 'TaskStatusSummary.jsx', 'StaffWorkloadList.jsx', 'WorkloadOverview.jsx']) {
      const source = read('components', 'dashboard', file);
      assert.ok(!/(value|count): [1-9]\d*\b/.test(source), `${file} has no literal figures`);
    }
    assert.ok(read('services', 'api.js').includes("apiFetch('/api/workload')"));
  });

  it('routing and the favicon link are unchanged', () => {
    const app = read('App.jsx');
    assert.ok(app.includes('<Route path="/dashboard" element={<Dashboard />} />') && app.includes('<Route path="/login" element={<LoginPage />} />'));
    assert.ok(read('..', 'index.html').includes('rel="icon"'));
  });
});
