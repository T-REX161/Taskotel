import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { deriveWorkloadStatus, getWorkloadProgressBarColor, getWorkloadStatusBadge } from './workloadUtils.js';
import { COLOR_STYLES, getDepartmentStyle } from './departmentStyles.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const read = (...parts) => fs.readFileSync(path.join(__dirname, ...parts), 'utf8').replace(/\r\n/g, '\n');

// The bands are decided by the backend (WorkloadDto.deriveStatus); the screen only repeats them in its legends. These keep the legends and the
// frontend's own copy of the rule (used for projections) in step with the backend: LOW 0-120, NORMAL 121-240, HIGH 241-360, AT_RISK above 360.
describe('workload threshold labels', () => {
  it('the Workload page has no per-staff legend card (the minute bands live in the dashboard table and the backend)', () => {
    const page = read('..', 'pages', 'WorkloadPage.jsx');
    for (const gone of ['Per-Staff Workload Thresholds', 'LOW: 0 – 120 mins', 'NORMAL: 121 – 240 mins', 'HIGH: 241 – 360 mins', 'AT RISK: &gt; 360 mins', '480m Shift Baseline']) {
      assert.ok(!page.includes(gone), gone);
    }
    assert.ok(page.includes('Staff Workload Distribution</h2>'), 'the staff section stays');
  });

  it('the dashboard distribution table states the same bands', () => {
    const table = read('..', 'components', 'dashboard', 'StaffWorkloadTable.jsx');
    for (const label of ['0 – 120 min', '121 – 240 min', '241 – 360 min', '&gt; 360 min']) {
      assert.ok(table.includes(`>${label}</td>`), label);
    }
    assert.ok(!/300 min/.test(table), 'no 300 minute bound in the table');
  });

  it('department bars use the department\'s own colour (its badge colour), never a position and never a staff status', () => {
    const page = read('..', 'pages', 'WorkloadPage.jsx');
    assert.ok(page.includes('const getDepartmentBarColor = (dept) => getDepartmentStyle(dept).swatch;') && page.includes('const barColor = getDepartmentBarColor(dept);'));
    assert.ok(!/palette\[index/.test(page), 'no colour by position');
    // the same department gives the same colour whatever else is in the list, and it is the swatch of the badge's colour key
    for (const [code, key] of [['HOUSEKEEPING', 'green'], ['FRONT_DESK', 'blue'], ['MAINTENANCE', 'purple'], ['ROOM_SERVICE', 'amber'], ['FOOD_AND_BEVERAGE', 'orange'], ['SECURITY', 'cyan']]) {
      const style = getDepartmentStyle({ code });
      assert.equal(style.colorKey, key, code);
      assert.equal(style.swatch, COLOR_STYLES[key].swatch, code);
      assert.equal(style.classes, COLOR_STYLES[key].classes, code + ': the badge classes come from the same key');
    }
    assert.equal(getDepartmentStyle({ code: 'SPA', colorKey: 'rose' }).swatch, 'bg-rose-500', 'a custom department uses its stored colour');
    // the department section never classifies a total with the staff thresholds
    const start = page.indexOf('aria-label="Workload by department"');
    const section = page.slice(start, page.indexOf('</section>', start));
    for (const helper of ['deriveWorkloadStatus', 'getWorkloadStatusBadge', 'getWorkloadProgressBarColor', 'workloadStatus', 'UtilizationMeter']) {
      assert.ok(!section.includes(helper), 'the department section does not use ' + helper);
    }
    assert.ok(section.includes('Operational load across hotel functional units') && section.includes('Calculated from active staff workloads.'));
  });

  it('the department legend is its own card after the summary and before the departments; the staff status colours are unchanged', () => {
    const page = read('..', 'pages', 'WorkloadPage.jsx');
    assert.equal(page.split('Department Workload Thresholds:').length, 2, 'one department legend');
    const summary = page.indexOf('aria-label="Workload summary"');
    const deptLegend = page.indexOf('Department Workload Thresholds:');
    const departments = page.indexOf('aria-label="Workload by department"');
    const support = page.indexOf('<CrossDepartmentSupportPanel');
    const staff = page.indexOf('Staff Workload Distribution</h2>');
    assert.ok(summary < deptLegend && deptLegend < departments && departments < support && support < staff, 'summary, department legend, departments, support, staff');
    const card = 'className="bg-white rounded-xl p-3 border border-gray-100 shadow-2xs flex flex-wrap items-center justify-between gap-3 text-xs"';
    const cardAt = page.lastIndexOf(card, deptLegend);
    assert.ok(cardAt > 0 && deptLegend - cardAt < 700, 'the department legend sits in its own bordered, shadowed card');
    for (const text of ['LOW: 0 – 25%', 'NORMAL: &gt; 25 – 50%', 'HIGH: &gt; 50 – 75%', 'AT RISK: &gt; 75%', '480 min per available staff member']) assert.ok(page.includes(text), text);
    assert.ok(page.includes('{usesDepartmentSummary && ('), 'the department legend is for Admin and Manager');
    assert.deepEqual(['LOW', 'NORMAL', 'HIGH', 'AT_RISK'].map(getWorkloadProgressBarColor), ['bg-blue-500', 'bg-emerald-500', 'bg-orange-500', 'bg-red-500']);
    for (const [status, hue] of [['LOW', 'blue'], ['NORMAL', 'emerald'], ['HIGH', 'orange'], ['AT_RISK', 'red']]) {
      assert.ok(getWorkloadStatusBadge(status).includes('bg-' + hue + '-100'), status);
    }
    const meter = read('..', 'components', 'workload', 'UtilizationMeter.jsx');
    for (const pill of ["LOW: 'bg-blue-100 text-blue-700'", "NORMAL: 'bg-emerald-100 text-emerald-700'", "HIGH: 'bg-orange-100 text-orange-700'", "AT_RISK: 'bg-red-100 text-red-700'"]) assert.ok(meter.includes(pill), pill);
  });

  it('the frontend projection rule has the same edges as the legends', () => {
    const edges = [[120, 'LOW'], [121, 'NORMAL'], [240, 'NORMAL'], [241, 'HIGH'], [360, 'HIGH'], [361, 'AT_RISK']];
    for (const [minutes, expected] of edges) assert.equal(deriveWorkloadStatus(minutes), expected, `${minutes} minutes`);
  });
});
