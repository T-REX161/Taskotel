import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { easeOutCubic, legendFade, revealArcs, buildInsights, pendingPriorityTasks, tasksNeedingAttention, donutArcs, donutCenterTotal, durationPercent, formatDashboardDate, formatMinutes, greetingFor, loadPercent, overloadedStaff, sortByLoad, supportHighlights, taskStatusSegments, workloadTone } from '../utils/dashboardView.js';

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

describe('donut segment spacing', () => {
  const rows = taskStatusSegments({ pendingTasks: 1, assignedTasks: 3, inProgressTasks: 0, completedTasks: 6, cancelledTasks: 0 }, false).rows;

  it('cuts a small gap between segments but keeps each slot proportional to its count', () => {
    const arcs = donutArcs(rows, 50, 3);
    const circumference = 2 * Math.PI * 50;
    assert.deepEqual(arcs.map((a) => a.key), ['pendingTasks', 'assignedTasks', 'completedTasks'], 'zero counts have no arc');
    arcs.forEach((arc, i) => {
      const share = [0.1, 0.3, 0.6][i];
      assert.ok(Math.abs(arc.length + 3 - share * circumference) < 1e-9, `${arc.key}: its slot minus the gap`);
    });
    assert.ok(Math.abs(arcs[0].offset + 1.5) < 1e-9, 'the gap is split evenly around the slot');
    assert.ok(Math.abs(arcs[1].offset + (0.1 * circumference + 1.5)) < 1e-9);
  });

  it('a single status is one unbroken ring, and nothing is drawn without tasks', () => {
    const only = taskStatusSegments({ completedTasks: 5 }, false).rows;
    const [arc] = donutArcs(only, 50, 3);
    assert.ok(Math.abs(arc.length - 2 * Math.PI * 50) < 1e-9);
    assert.deepEqual(donutArcs(taskStatusSegments({}, false).rows, 50, 3), []);
  });

  it('the chart card keeps zero statuses visible but muted, centres the total and only draws the track when empty', () => {
    const card = read('components', 'dashboard', 'TaskStatusSummary.jsx');
    assert.ok(card.includes("row.count === 0 ? 'opacity-55'") && card.includes('animating ? Math.round(centre * eased) : centre'));
    assert.ok(card.includes('arcs.length === 0 &&'), 'no track behind the segments, so the gaps show the card in both themes');
    assert.ok(card.includes('donutArcs(rows, RADIUS, SEGMENT_GAP)'));
  });
});

describe('workload display helpers', () => {
  it('bands match the backend thresholds and unknown values fall back safely', () => {
    assert.deepEqual(['LOW', 'NORMAL', 'HIGH', 'AT_RISK'].map((s) => workloadTone(s).label), ['LOW', 'NORMAL', 'HIGH', 'AT RISK']);
    assert.equal(workloadTone(undefined).label, 'LOW');
    assert.ok(workloadTone('HIGH').row.includes('bg-amber-50') && workloadTone('AT_RISK').row.includes('bg-red-50'), 'only HIGH and AT_RISK rows get the warning treatment');
    assert.ok(!workloadTone('LOW').row.includes('bg-') && !workloadTone('NORMAL').row.includes('bg-'));
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

  it('shows load as a rounded share of the 360-minute threshold, and the date like the reference', () => {
    assert.deepEqual([loadPercent(0), loadPercent(180), loadPercent(270), loadPercent(999)], [0, 50, 75, 100]);
    assert.equal(formatDashboardDate(new Date(2026, 8, 29)), 'Tue, 29 Sep 2026');
  });

  it('greets by time of day', () => {
    assert.deepEqual([greetingFor(8), greetingFor(14), greetingFor(21)], ['Good morning', 'Good afternoon', 'Good evening']);
  });
});

describe('operational highlights', () => {
  it('lists only people on HIGH or AT_RISK, most loaded first', () => {
    const list = [
      { staffId: 'a', staffName: 'A', workloadStatus: 'LOW', totalEstimatedDuration: 30 },
      { staffId: 'b', staffName: 'B', workloadStatus: 'HIGH', totalEstimatedDuration: 300 },
      { staffId: 'c', staffName: 'C', workloadStatus: 'AT_RISK', totalEstimatedDuration: 400 },
      { staffId: 'd', staffName: 'D', workloadStatus: 'NORMAL', totalEstimatedDuration: 200 },
    ];
    assert.deepEqual(overloadedStaff(list).map((r) => r.staffId), ['c', 'b']);
    assert.deepEqual(overloadedStaff([{ workloadStatus: 'LOW' }]), []);
    assert.deepEqual(overloadedStaff(null), []);
  });

  it('takes departments from the existing recommendations only, most severe first, and shows none when there is no pressure', () => {
    const rec = {
      recommendations: [{ departmentCode: 'HK', departmentName: 'Housekeeping', severity: 'HIGH', pressure: { uncoveredTasks: 2 } }],
      unsupported: [{ departmentCode: 'FD', departmentName: 'Front Desk', severity: 'CRITICAL', pressure: { uncoveredTasks: 1 } }],
    };
    assert.deepEqual(supportHighlights(rec).map((d) => [d.code, d.severity, d.uncoveredTasks, d.canBeHelped]), [['FD', 'CRITICAL', 1, false], ['HK', 'HIGH', 2, true]]);
    assert.deepEqual(supportHighlights({ recommendations: [], unsupported: [] }), []);
    assert.deepEqual(supportHighlights(null), []);
  });

  it('the donut centre is the status total, and is left out when it disagrees with the summary total', () => {
    assert.equal(donutCenterTotal({ totalTasks: 11 }, 11, false), 11);
    assert.equal(donutCenterTotal({ totalTasks: 12 }, 11, false), null);
    assert.equal(donutCenterTotal({}, 4, true), 4, 'staff have no organisation total to compare with');
  });

  it('the insight sources are requested for Admin and Manager only; Staff never request them', () => {
    const page = read('pages', 'Dashboard.jsx');
    assert.ok(page.includes("getUserRole() !== 'STAFF' ? [loadStaffWorkload(), loadRecommendations(), loadTasks()] : []"));
    assert.ok(!page.includes('<OperationalHighlights'), 'the old strip is replaced by the Operations Insights card');
  });
});

describe('dashboard layout balance', () => {
  const list = read('components', 'dashboard', 'StaffWorkloadList.jsx');
  const page = read('pages', 'Dashboard.jsx');

  it('the staff workload has no inner scroll area: it shows the most loaded people and links to the full list', () => {
    assert.ok(!/max-h-|overflow-y-|overflow-auto/.test(list), 'the page scrolls, not the panel');
    assert.ok(list.includes('export const DASHBOARD_ROW_LIMIT = 7;') && list.includes('all.slice(0, DASHBOARD_ROW_LIMIT)'));
    assert.ok(list.includes('View all {all.length} in Workload') && list.includes('to="/workload"') && list.includes('all.length > rows.length'));
  });

  it('each panel keeps the height its content needs: the grid is top-aligned and the chart card is not stretched', () => {
    assert.ok(page.includes('grid grid-cols-1 lg:grid-cols-12 gap-5 items-start'));
    assert.ok(!page.includes('items-stretch') && !page.includes('levelPanels'));
    const card = read('components', 'dashboard', 'TaskStatusSummary.jsx');
    assert.ok(!card.includes('h-full flex') && !card.includes('flex-1'), 'neither the card nor its content is made to fill the height of the row');
  });

  it('keeps the hierarchy: heading, KPI cards, the workload panel, then the chart with the insights beneath it', () => {
    const order = ['<h1', '<SummaryCards', '<WorkloadOverview', '<TaskStatusSummary', '<OperationsInsights'].map((s) => page.indexOf(s));
    assert.ok(order.every((i, n) => i > -1 && (n === 0 || i > order[n - 1])), order.join(','));
  });
});

describe('scroll position and the sticky header', () => {
  it('a page opens at its top: the shell resets the window and <main> scroll on every route change', () => {
    const shell = read('components', 'layout', 'AppShell.jsx');
    assert.ok(shell.includes('window.scrollTo(0, 0);') && shell.includes('mainRef.current?.scrollTo?.(0, 0);'));
    assert.ok(shell.includes('}, [location.pathname]);'), 'runs when the path changes, not on every render');
  });

  it('a reload does not bring back an old scroll offset', () => {
    const shell = read('components', 'layout', 'AppShell.jsx');
    assert.ok(shell.includes("window.history.scrollRestoration = 'manual';"));
  });

  it('anything scrolled into view clears the 64px sticky header', () => {
    const css = read('index.css');
    assert.ok(css.includes('scroll-padding-top: 5rem;'));
    assert.ok(read('components', 'layout', 'Header.jsx').includes('<header className="h-16 '), 'header is still 64px (4rem), so 5rem leaves a margin');
  });
});

describe('compact spacing', () => {
  it('uses tighter, consistent gaps between the sections and inside the cards, with no fixed heights', () => {
    const page = read('pages', 'Dashboard.jsx');
    assert.ok(page.includes('<div className="w-full space-y-5">') && page.includes('lg:grid-cols-12 gap-5'));
    assert.ok(read('components', 'dashboard', 'SummaryCards.jsx').includes('gap-3') && read('components', 'dashboard', 'SummaryCards.jsx').includes('min-h-[5.75rem]'), 'a minimum, not a fixed height');
    for (const file of ['SummaryCards.jsx', 'OperationsInsights.jsx', 'StaffWorkloadList.jsx', 'WorkloadOverview.jsx', 'TaskStatusSummary.jsx']) {
      const classes = read('components', 'dashboard', file).split(/[\s"'`]+/);
      const fixed = classes.filter((c) => c !== 'h-2' && c.startsWith('h-[') || c === 'h-screen' || (c !== 'h-2' && /^h-[0-9]/.test(c) && !classes.includes('w-' + c.slice(2))));
      assert.deepEqual(fixed, [], file + ' has no fixed height (icon and avatar boxes are square w-N h-N)');
    }
  });
});

describe('staff workload column alignment', () => {
  const list = read('components', 'dashboard', 'StaffWorkloadList.jsx');

  it('every row uses one shared column template with fixed tracks for all but the name and bar columns', () => {
    assert.ok(list.includes("const ROW_GRID = 'grid-cols-[2.75rem_minmax(0,1fr)_4.5rem] sm:grid-cols-[2.75rem_minmax(0,1fr)_minmax(6rem,1.3fr)_2.75rem_4.5rem_4.5rem] lg:grid-cols-[2.75rem_minmax(0,1fr)_4.5rem] min-[1200px]:grid-cols-[2.75rem_minmax(0,1fr)_minmax(6rem,1.3fr)_2.75rem_4.5rem_4.5rem]';"));
    assert.equal(list.split('${ROW_GRID}').length - 1, 1, 'applied to the row, once');
    assert.ok(!list.includes('grid-cols-[auto') && !list.includes('_auto'), 'no auto-sized track, whose width would depend on the row content');
  });

  it('percentage, badge and task column have fixed widths and the last column is right-aligned', () => {
    assert.ok(list.includes('w-11 shrink-0 text-right text-sm tabular-nums') && list.includes('w-[4.5rem] shrink-0 inline-flex justify-center'));
    assert.ok(list.includes('w-[4.5rem] text-right tabular-nums sm:order-last lg:order-none min-[1200px]:order-last'));
  });

  it('long names and departments are truncated with a tooltip instead of pushing columns', () => {
    assert.ok(list.includes('truncate" title={row.staffName}') && list.includes('truncate" title={department}'));
    assert.ok(list.includes('<div className="min-w-0 text-left">'));
  });

  it('on a phone the bar, percentage and badge get their own line; from sm up they are separate grid columns', () => {
    assert.ok(list.includes('col-span-3 col-start-1 flex items-center gap-3 sm:contents lg:flex min-[1200px]:contents'));
    assert.ok(list.includes('flex-1 sm:flex-none sm:w-full lg:flex-1 lg:w-auto min-[1200px]:flex-none min-[1200px]:w-full h-2'));
  });
});

describe('task status card layout', () => {
  const card = read('components', 'dashboard', 'TaskStatusSummary.jsx');

  it('the donut and legend are one centred group: side by side, wrapping only when the card cannot hold both', () => {
    assert.ok(card.includes('mt-5 flex flex-wrap items-center justify-center gap-x-5 gap-y-5'));
    assert.ok(card.includes('min-w-[10.5rem] max-w-full divide-y'), 'the legend takes only the width it needs');
    assert.ok(!card.includes('gap-x-8') && !card.includes('min-w-[14rem]'), 'the wide gap and legend minimum that forced stacking are gone');
  });

  it('the donut is square and sized in steps so it fits next to the legend at each card width', () => {
    assert.ok(card.includes('aspect-square w-48 min-[1200px]:w-40 min-[1320px]:w-44 min-[1400px]:w-52 min-[1440px]:w-56'));
  });

  it('title, subtitle, centred total, labels, counts, percentages and the zero-count muting are unchanged', () => {
    for (const text of ['Task Status Overview', "isStaff ? 'Your tasks by status' : 'All tasks by status'", '{total} in total', 'animating ? Math.round(centre * eased) : centre', '{row.label}', '{row.count}', '{row.percent}%', "row.count === 0 ? 'opacity-55'"]) {
      assert.ok(card.includes(text), text);
    }
  });
});

describe('operations insights', () => {
  const ready = (data) => ({ status: 'ready', data });
  const person = (staffId, staffName, workloadStatus, totalEstimatedDuration) => ({ staffId, staffName, workloadStatus, totalEstimatedDuration });
  const task = (status, priority) => ({ id: status + priority, status, priority });
  const rec = (departmentCode, departmentName, severity, uncoveredTasks) => ({ departmentCode, departmentName, severity, pressure: { uncoveredTasks } });
  const base = { isStaff: false, staffWorkload: ready([]), tasks: ready([]), recommendations: ready({ recommendations: [], unsupported: [] }) };

  it('counts only PENDING tasks of URGENT or HIGH priority; other pending tasks and other priorities are not alerts', () => {
    assert.deepEqual(pendingPriorityTasks([task('PENDING', 'URGENT'), task('PENDING', 'HIGH'), task('PENDING', 'HIGH'), task('PENDING', 'MEDIUM'), task('PENDING', 'LOW'), task('ASSIGNED', 'URGENT'), task('COMPLETED', 'HIGH')]), { urgent: 1, high: 2, total: 3 });
    assert.deepEqual(pendingPriorityTasks(null), { urgent: 0, high: 0, total: 0 });
    const { items } = buildInsights({ ...base, tasks: ready([task('PENDING', 'LOW'), task('PENDING', 'MEDIUM')]) });
    assert.deepEqual(items, [], 'a pile of ordinary pending tasks is not an alert');
  });

  it('builds one row per kind from the real data, with the existing destinations only', () => {
    const result = buildInsights({
      ...base,
      staffWorkload: ready([person('a', 'Omar', 'AT_RISK', 400), person('b', 'Rahul', 'HIGH', 300), person('c', 'Priya', 'LOW', 10)]),
      tasks: ready([task('PENDING', 'URGENT'), task('PENDING', 'HIGH')]),
      recommendations: ready({ recommendations: [rec('HK', 'Housekeeping', 'HIGH', 2)], unsupported: [] }),
    });
    assert.equal(result.state, 'items');
    const byKey = Object.fromEntries(result.items.map((i) => [i.key, i]));
    assert.deepEqual(Object.keys(byKey).sort(), ['support', 'tasks', 'workload']);
    assert.equal(byKey.workload.to, '/workload');
    assert.equal(byKey.support.to, '/workload');
    assert.equal(byKey.tasks.to, '/tasks');
    assert.equal(byKey.workload.title, '2 people on high workload');
    assert.equal(byKey.workload.detail, 'Omar (at risk), Rahul (high)');
    assert.equal(byKey.support.title, '1 department may need support');
    assert.equal(byKey.support.detail, 'Housekeeping · High, 2 uncovered');
    assert.equal(byKey.tasks.title, '2 high-priority tasks pending');
    assert.equal(byKey.tasks.detail, '1 urgent · 1 high');
  });

  it('ranks by seriousness and never shows more than three rows', () => {
    const critical = buildInsights({ ...base, staffWorkload: ready([person('b', 'Rahul', 'HIGH', 300)]), recommendations: ready({ recommendations: [], unsupported: [rec('FD', 'Front Desk', 'CRITICAL', 3)] }), tasks: ready([task('PENDING', 'HIGH')]) });
    assert.deepEqual(critical.items.map((i) => i.key), ['support', 'workload', 'tasks'], 'a critical department outranks high workload and high-priority tasks');
    const atRisk = buildInsights({ ...base, staffWorkload: ready([person('a', 'A', 'AT_RISK', 400)]), tasks: ready([task('PENDING', 'URGENT')]), recommendations: ready({ recommendations: [rec('X', 'X', 'MODERATE', 1)], unsupported: [] }) });
    assert.deepEqual(atRisk.items.map((i) => i.key), ['workload', 'tasks', 'support']);
    assert.ok(atRisk.items.length <= 3);
  });

  it('says "clear" only when every source loaded; a failed or loading source is never read as "no alerts"', () => {
    assert.equal(buildInsights(base).state, 'clear');
    assert.equal(buildInsights({ ...base, tasks: { status: 'error', data: [] } }).state, 'unavailable');
    assert.deepEqual(buildInsights({ ...base, tasks: { status: 'error', data: [] } }).unavailable, ['tasks']);
    assert.equal(buildInsights({ ...base, recommendations: { status: 'loading', data: null } }).state, 'loading');
  });

  it('shows what it has, and notes what is missing, when one source fails', () => {
    const result = buildInsights({ ...base, staffWorkload: ready([person('a', 'Omar', 'HIGH', 300)]), recommendations: { status: 'error', data: null } });
    assert.equal(result.state, 'items');
    assert.deepEqual(result.unavailable, ['support']);
  });

  it('Staff get only their own workload alert, built from their own summary, and nothing else', () => {
    const own = (workloadStatus) => buildInsights({
      isStaff: true,
      own: { workloadStatus, activeTaskCount: 3, totalEstimatedDuration: 300 },
      staffWorkload: ready([person('x', 'Colleague', 'AT_RISK', 999)]),
      tasks: ready([task('PENDING', 'URGENT')]),
      recommendations: ready({ recommendations: [rec('HK', 'Housekeeping', 'CRITICAL', 9)], unsupported: [] }),
    });
    assert.equal(own('LOW').state, 'hidden');
    assert.equal(own('NORMAL').state, 'hidden');
    const high = own('HIGH');
    assert.deepEqual(high.items.map((i) => [i.key, i.title, i.to]), [['own-workload', 'Your workload is high', '/workload']]);
    assert.equal(high.items[0].detail, '3 active tasks · 300 min estimated');
    assert.equal(own('AT_RISK').items[0].title, 'Your workload is at risk');
    assert.ok(!JSON.stringify(high).includes('Colleague') && !JSON.stringify(high).includes('Housekeeping'), "no one else's data leaks in");
  });

  it('expanding a row is inline: one open at a time, button semantics, no navigation from the row', () => {
    const card = read('components', 'dashboard', 'OperationsInsights.jsx');
    assert.ok(card.includes('<button type="button" aria-expanded={open} aria-controls={panelId}'));
    assert.ok(card.includes('setOpenKey(open ? null : item.key)'), 'one key: opening another closes the previous, same row toggles');
    assert.ok(!card.includes('<Link to={item.to}') && !card.includes('navigate('), 'rows do not navigate');
    assert.ok(card.includes('rotate-90') && card.includes('motion-reduce:transition-none') && card.includes('motion-safe:'));
  });

  it('details come from the same data: tasks are PENDING urgent/high only, urgent first, capped; workload rows carry real fields', () => {
    const tasks = [
      { id: 1, title: 'B', status: 'PENDING', priority: 'HIGH' }, { id: 2, title: 'A', status: 'PENDING', priority: 'URGENT', departmentName: 'Housekeeping' },
      { id: 3, title: 'C', status: 'PENDING', priority: 'LOW' }, { id: 4, title: 'D', status: 'ASSIGNED', priority: 'URGENT' },
    ];
    assert.deepEqual(tasksNeedingAttention(tasks).map((r) => [r.id, r.priority]), [[2, 'URGENT'], [1, 'HIGH']]);
    const r = buildInsights({ ...base, tasks: ready(tasks) }).items[0];
    assert.equal(r.details.type, 'tasks');
    assert.equal(r.details.viewAll, '/tasks');
    const w = buildInsights({ ...base, staffWorkload: ready([{ staffId: 'a', staffName: 'Omar', departmentName: 'Housekeeping', workloadStatus: 'AT_RISK', totalEstimatedDuration: 420, activeTaskCount: 5 }]) }).items[0];
    assert.deepEqual(w.details.rows[0], { id: 'a', name: 'Omar', department: 'Housekeeping', percent: 100, status: 'AT_RISK', activeTasks: 5 });
    const many = Array.from({ length: 9 }, (_, i) => person('s' + i, 'N' + i, 'HIGH', 300));
    const m = buildInsights({ ...base, staffWorkload: ready(many) }).items[0];
    assert.equal(m.details.rows.length, 4); assert.equal(m.details.total, 9);
    const s = buildInsights({ ...base, recommendations: ready({ recommendations: [{ ...rec('HK', 'Housekeeping', 'HIGH', 2), reasons: ['No free staff'] }], unsupported: [] }) }).items[0];
    assert.equal(s.details.rows[0].reason, 'No free staff');
  });

  it('Staff details hold only their own record and no View all to org-wide data', () => {
    const own = buildInsights({ isStaff: true, own: { workloadStatus: 'HIGH', activeTaskCount: 2, totalEstimatedDuration: 300 }, ...{ staffWorkload: ready([person('x', 'Colleague', 'AT_RISK', 999)]) } }).items[0];
    assert.equal(own.details.rows.length, 1); assert.equal(own.details.viewAll, null);
    assert.ok(!JSON.stringify(own).includes('Colleague'));
  });

  it('never calls anything overdue', () => {
    const source = read('utils', 'dashboardView.js');
    const logic = source.slice(source.indexOf('// ── Operations Insights')).split('\n').filter((l) => !l.trim().startsWith('//') && !l.trim().startsWith('*') && !l.trim().startsWith('/*')).join('\n');
    assert.ok(!/overdue/i.test(logic), 'no overdue claim is made');
  });

  it('the card renders states plainly, expands each row inline, and hides for Staff with nothing to show', () => {
    const card = read('components', 'dashboard', 'OperationsInsights.jsx');
    assert.ok(card.includes("if (state === 'hidden') return null;") && card.includes('Nothing needs attention right now.') && card.includes('could not be loaded'));
    assert.ok(card.includes('aria-expanded={open}') && card.includes('Operations Insights') && card.includes('What needs attention now'));
    assert.ok(read('pages', 'Dashboard.jsx').includes('<OperationsInsights insights={insights} />'));
  });
});

describe('chart entrance animation', () => {
  const rows = [{ key: 'a', stroke: 's-a', count: 6 }, { key: 'b', stroke: 's-b', count: 0 }, { key: 'c', stroke: 's-c', count: 3 }, { key: 'd', stroke: 's-d', count: 2 }];
  const arcs = donutArcs(rows, 54, 3);
  const C = 2 * Math.PI * 54;

  it('reveals the ring clockwise: nothing at 0, the exact final arcs at 1, true proportions in between', () => {
    assert.ok(revealArcs(arcs, 0, 54).every((a) => a.length === 0));
    assert.equal(revealArcs(arcs, 1, 54), arcs, 'the final frame is the data-driven arcs untouched');
    const mid = revealArcs(arcs, 0.3, 54);
    assert.ok(mid[0].length > 0 && mid[0].length <= arcs[0].length, 'the first segment grows first');
    assert.equal(mid[mid.length - 1].length, 0, 'later segments have not started');
    mid.forEach((a, i) => { assert.equal(a.offset, arcs[i].offset); assert.equal(a.key, arcs[i].key); assert.ok(Math.abs(a.length + a.gap - C) < 1e-9); });
    let previous = -1;
    for (let p = 0; p <= 1.0001; p += 0.1) { const shown = revealArcs(arcs, p, 54).reduce((s, a) => s + a.length, 0); assert.ok(shown >= previous - 1e-9, 'never shrinks'); previous = shown; }
  });

  it('easing and legend fade stay within 0..1 and finish at 1', () => {
    assert.equal(easeOutCubic(0), 0); assert.equal(easeOutCubic(1), 1); assert.equal(easeOutCubic(2), 1);
    assert.ok(easeOutCubic(0.5) > 0.5);
    assert.equal(legendFade(0, 0), 0);
    assert.ok(legendFade(0.5, 0) > legendFade(0.5, 3), 'later rows start later');
    for (let i = 0; i < 5; i++) assert.equal(legendFade(1, i), 1);
  });

  it('plays on mount only, respects reduced motion, and cleans up its frame request', () => {
    const c = read('components', 'dashboard', 'TaskStatusSummary.jsx');
    assert.ok(c.includes('const ENTRANCE_MS = 900;') && c.includes('requestAnimationFrame(tick)') && c.includes('cancelAnimationFrame(frame)'));
    assert.ok(c.includes('}, []);'), 'the effect depends on nothing, so re-renders and data updates never restart it');
    assert.ok(c.includes('(prefers-reduced-motion: reduce)') && c.includes('prefersReducedMotion() ? 1 : 0'));
    assert.ok(c.includes('animating ? Math.round(centre * eased) : centre'), 'the count settles on the real total');
    assert.ok(c.includes('shownArcs') && c.includes('revealArcs(arcs, eased, RADIUS)'));
  });
});

describe('visual polish', () => {
  it('the pale green page tint applies to the Dashboard route only and the theme tokens are untouched', () => {
    const css = read('index.css');
    assert.ok(css.includes("html:not(.dark) main[data-page='Dashboard']"));
    assert.ok(read('components', 'layout', 'AppShell.jsx').includes('<main ref={mainRef} data-page={currentNav} className="flex-1 min-w-0 p-4 sm:p-6 overflow-y-auto bg-surface-page">'));
  });

  it('workload rows show the backend state and animate the bar; the donut shows the total in the middle', () => {
    const list = read('components', 'dashboard', 'StaffWorkloadList.jsx');
    assert.ok(list.includes('{tone.label}') && list.includes('transition-[width]') && list.includes('data-workload={row.workloadStatus}'));
    assert.ok(read('components', 'dashboard', 'TaskStatusSummary.jsx').includes('animating ? Math.round(centre * eased) : centre'));
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
    assert.ok(page.includes('describeUser(getUsername(), userRole)') && page.includes('Welcome back, {welcomeName}!') && page.includes('formatDashboardDate(new Date())'));
    assert.ok(page.includes("getUserRole() !== 'STAFF' ? [loadStaffWorkload(), loadRecommendations(), loadTasks()] : []"));
    assert.ok(page.includes('staffWorkload={isStaff ? undefined : staffWorkload}'));
  });

  it('uses the existing API and has no literal sample figures in the new components', () => {
    for (const file of ['SummaryCards.jsx', 'TaskStatusSummary.jsx', 'StaffWorkloadList.jsx', 'WorkloadOverview.jsx']) {
      const source = read('components', 'dashboard', file);
      assert.ok(!/(value|count): [1-9]\d*\b/.test(source), `${file} has no literal figures`);
    }
    assert.ok(read('services', 'api.js').includes("apiFetch('/api/workload')"));
  });

  it('follows the reference layout: two KPI rows of four, then Staff Workload beside Task Status Overview', () => {
    const cards = read('components', 'dashboard', 'SummaryCards.jsx');
    for (const title of ['Total Staff', 'Available Staff', 'Unavailable Staff', 'Active Assignments', 'Pending Tasks', 'Assigned Tasks', 'In Progress', 'Completed']) assert.ok(cards.includes(title), title);
    assert.ok(cards.includes('lg:grid-cols-4'));
    assert.ok(read('components', 'dashboard', 'TaskStatusSummary.jsx').includes('Task Status Overview'));
    assert.ok(page.includes('lg:col-span-7') && page.includes('lg:col-span-5'));
    assert.ok(!page.includes('<DepartmentOverview'), 'no hard-coded department tiles');
    assert.ok(page.includes('{isStaff && <AssignmentsOverview'), 'only Staff get recent assignments');
  });

  it('routing and the favicon link are unchanged', () => {
    const app = read('App.jsx');
    assert.ok(app.includes('<Route path="/dashboard" element={<Dashboard />} />') && app.includes('<Route path="/login" element={<LoginPage />} />'));
    assert.ok(read('..', 'index.html').includes('rel="icon"'));
  });
});
