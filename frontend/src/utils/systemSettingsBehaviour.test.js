import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { buildInsights } from './dashboardView.js';
import { recommendationsTurnedOff } from './supportRecommendations.js';

const read = (...parts) => readFileSync(new URL(`../${parts.join('/')}`, import.meta.url), 'utf8');
const ready = (data) => ({ status: 'ready', data });
const base = { isStaff: false, staffWorkload: ready([]), tasks: ready([]), recommendations: ready({ recommendations: [], unsupported: [] }) };
// A capacity alert as the dashboard summary sends it: remaining minutes against staff capacity (staff x 480 min)
const alert = (code, name, remaining, staff, threshold = 85) => ({
  departmentCode: code, departmentName: name, remainingMinutes: remaining, capacityMinutes: staff * 480, eligibleStaff: staff,
  utilizationPercent: staff > 0 ? Math.round((remaining * 1000) / (staff * 480)) / 10 : null, thresholdPercent: threshold, noCapacity: staff === 0,
});

test('a capacity warning from the dashboard summary appears as an insight', () => {
  const result = buildInsights({ ...base, capacityAlerts: [alert('FD', 'Front Desk', 900, 2)] });
  assert.equal(result.state, 'items');
  const item = result.items.find((i) => i.kind === 'capacity');
  assert.ok(item);
  assert.match(item.title, /1 department at capacity limit/);
  assert.match(item.detail, /Front Desk · 93\.8% of capacity in use \(900 of 960 min, limit 85%\)/);
  assert.equal(item.details.type, 'capacity');
});

test('no warnings (alert off, or nothing at the threshold) leaves no capacity insight, and a missing field is harmless', () => {
  for (const capacityAlerts of [[], undefined, null]) {
    const result = buildInsights({ ...base, capacityAlerts });
    assert.equal(result.state, 'clear');
    assert.ok(!result.items.some((i) => i.kind === 'capacity'));
  }
});

test('one entry per department: several alerts are one insight with the count, never duplicate rows', () => {
  const result = buildInsights({ ...base, capacityAlerts: [alert('FD', 'Front Desk', 960, 2), alert('HK', 'Housekeeping', 900, 2)] });
  const capacity = result.items.filter((i) => i.kind === 'capacity');
  assert.equal(capacity.length, 1);
  assert.match(capacity[0].title, /2 departments at capacity limit/);
  assert.equal(capacity[0].tone, 'red', 'a department at or above its whole capacity is shown as urgent');
});

test('the capacity insight is not cut off by the other three insights', () => {
  const result = buildInsights({
    isStaff: false,
    staffWorkload: ready([{ staffId: 'a', staffName: 'A', workloadStatus: 'AT_RISK', totalEstimatedDuration: 500 }]),
    tasks: ready([{ id: 't', status: 'PENDING', priority: 'URGENT', title: 'x' }]),
    recommendations: ready({ recommendations: [], unsupported: [{ departmentCode: 'X', departmentName: 'X', severity: 'CRITICAL', pressure: { uncoveredTasks: 2 } }] }),
    capacityAlerts: [alert('FD', 'Front Desk', 900, 2)],
  });
  assert.ok(result.items.length <= 4);
  assert.ok(result.items.some((i) => i.kind === 'capacity'));
});

test('recommendationsTurnedOff recognises the server note and nothing else', () => {
  assert.equal(recommendationsTurnedOff({ limitations: ['Workload recommendations are turned off in System Settings (Auto-Balancing Trigger). No suggestions are being made.'] }), true);
  assert.equal(recommendationsTurnedOff({ limitations: ['Skills are not recorded in Taskotel.'] }), false);
  assert.equal(recommendationsTurnedOff(null), false);
});

test('the Settings page no longer promises automatic reassignment and says what each switch really does', () => {
  const page = read('pages', 'SettingsPage.jsx');
  assert.ok(!/Automatically suggest reassignments/.test(page));
  assert.ok(!/auto-balancing rules/i.test(page));
  assert.match(page, /no task is ever moved automatically/);
  assert.match(page, /does not end support that already exists/);
  assert.ok(!/adjacent departments during surge/.test(page));
});

test('the dashboard feeds the summary alerts to the insights card, and the support panel explains a switched-off recommendation list', () => {
  assert.match(read('pages', 'Dashboard.jsx'), /capacityAlerts: data\?\.capacityAlerts/);
  assert.match(read('components', 'dashboard', 'OperationsInsights.jsx'), /type === 'capacity'/);
  const panel = read('components', 'workload', 'CrossDepartmentSupportPanel.jsx');
  assert.match(panel, /recommendationsTurnedOff\(needs\.data\)/);
  assert.match(panel, /turned off in System Settings/);
});

test('the insight shows the limit the server sent, so a changed threshold shows up after the next refresh (nothing is hardcoded to 85)', () => {
  const at = (threshold) => buildInsights({ ...base, capacityAlerts: [alert('FD', 'Front Desk', 288, 1, threshold)] }).items.find((i) => i.kind === 'capacity');
  assert.match(at(60).detail, /60% of capacity in use \(288 of 480 min, limit 60%\)/);
  assert.match(at(50).detail, /limit 50%/);
  // The same department stops being listed once the server no longer reports it (threshold raised above its percentage)
  assert.equal(buildInsights({ ...base, capacityAlerts: [] }).items.find((i) => i.kind === 'capacity'), undefined);
  assert.ok(!/\b85\b/.test(read('utils', 'dashboardView.js').split('\n').filter((l) => /capacity/i.test(l)).join('\n')), 'no hardcoded 85 in the capacity insight');
});

test('a department at or above 100% of its capacity, or with work and no staff, is urgent (red); one at the threshold below 100% is a warning (amber)', () => {
  const tone = (remaining, staff) => buildInsights({ ...base, capacityAlerts: [alert('FD', 'Front Desk', remaining, staff, 50)] }).items.find((i) => i.kind === 'capacity').tone;
  assert.equal(tone(432, 1), 'amber');
  assert.equal(tone(480, 1), 'red');
  assert.equal(tone(100, 0), 'red');
  const none = buildInsights({ ...base, capacityAlerts: [alert('FD', 'Front Desk', 100, 0, 50)] }).items.find((i) => i.kind === 'capacity');
  assert.match(none.detail, /100 min of work and no available staff/);
});

test('the dashboard refreshes the summary on a timer, so alerts follow a changed threshold without a reload', () => {
  const page = read('pages', 'Dashboard.jsx');
  assert.match(page, /startPolling/);
  assert.match(page, /getDashboardSummary\(\)/);
});
