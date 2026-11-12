import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { buildInsights } from './dashboardView.js';
import { recommendationsTurnedOff } from './supportRecommendations.js';

const read = (...parts) => readFileSync(new URL(`../${parts.join('/')}`, import.meta.url), 'utf8');
const ready = (data) => ({ status: 'ready', data });
const base = { isStaff: false, staffWorkload: ready([]), tasks: ready([]), recommendations: ready({ recommendations: [], unsupported: [] }) };
const alert = (code, name, inWork, total, threshold = 85) => ({ departmentCode: code, departmentName: name, staffInWork: inWork, operationalStaff: total, percentInWork: Math.floor((inWork * 100) / total), thresholdPercent: threshold });

test('a capacity warning from the dashboard summary appears as an insight', () => {
  const result = buildInsights({ ...base, capacityAlerts: [alert('FD', 'Front Desk', 9, 10)] });
  assert.equal(result.state, 'items');
  const item = result.items.find((i) => i.kind === 'capacity');
  assert.ok(item);
  assert.match(item.title, /1 department at capacity limit/);
  assert.match(item.detail, /Front Desk · 9 of 10 available staff in work \(90%, limit 85%\)/);
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
  const result = buildInsights({ ...base, capacityAlerts: [alert('FD', 'Front Desk', 10, 10), alert('HK', 'Housekeeping', 9, 10)] });
  const capacity = result.items.filter((i) => i.kind === 'capacity');
  assert.equal(capacity.length, 1);
  assert.match(capacity[0].title, /2 departments at capacity limit/);
  assert.equal(capacity[0].tone, 'red', 'a department fully in work is shown as urgent');
});

test('the capacity insight is not cut off by the other three insights', () => {
  const result = buildInsights({
    isStaff: false,
    staffWorkload: ready([{ staffId: 'a', staffName: 'A', workloadStatus: 'AT_RISK', totalEstimatedDuration: 500 }]),
    tasks: ready([{ id: 't', status: 'PENDING', priority: 'URGENT', title: 'x' }]),
    recommendations: ready({ recommendations: [], unsupported: [{ departmentCode: 'X', departmentName: 'X', severity: 'CRITICAL', pressure: { uncoveredTasks: 2 } }] }),
    capacityAlerts: [alert('FD', 'Front Desk', 9, 10)],
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
