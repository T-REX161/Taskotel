import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { SUPPORT_STAGES, STAGE_STYLES, recordStage } from './supportPlan.js';
import { getSeverityTone, pickMostUrgent, summarizeNeeds } from './supportRecommendations.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const read = (...parts) => fs.readFileSync(path.join(__dirname, '..', ...parts), 'utf8').replace(/\r\n/g, '\n');
const dept = (code, severity, uncovered, minutes = 0, name = code) => ({ code, name, severity, pressure: { uncoveredTasks: uncovered, waitingMinutes: minutes, waitingTasks: uncovered } });

describe('workload pressure uses amber, not red', () => {
  it('has a distinct tone per severity and falls back to Moderate', () => {
    assert.ok(getSeverityTone('CRITICAL').chip.includes('amber-100'));
    assert.ok(getSeverityTone('HIGH').chip.includes('amber-50'));
    assert.ok(getSeverityTone('MODERATE').chip.includes('gray'));
    assert.deepEqual(getSeverityTone('nope'), getSeverityTone('MODERATE'));
    for (const s of ['CRITICAL', 'HIGH', 'MODERATE']) assert.ok(!/red/.test(JSON.stringify(getSeverityTone(s))), `${s} is not red`);
  });
});

describe('the most urgent department', () => {
  it('is the highest severity, then the most uncovered tasks, then the most waiting minutes', () => {
    assert.equal(pickMostUrgent([dept('A', 'HIGH', 9), dept('B', 'CRITICAL', 1)]), 'B');
    assert.equal(pickMostUrgent([dept('A', 'HIGH', 2), dept('B', 'HIGH', 5)]), 'B');
    assert.equal(pickMostUrgent([dept('A', 'HIGH', 2, 30), dept('B', 'HIGH', 2, 90)]), 'B');
    assert.equal(pickMostUrgent([dept('B', 'HIGH', 2, 30, 'Beta'), dept('A', 'HIGH', 2, 30, 'Alpha')]), 'A', 'ties are broken by name, so it never flickers');
  });

  it('singles one out only when there is something to single out', () => {
    assert.equal(pickMostUrgent([]), null);
    assert.equal(pickMostUrgent(undefined), null);
    assert.equal(pickMostUrgent([dept('A', 'MODERATE', 1)]), null, 'a lone moderate department is not "most urgent"');
    assert.equal(pickMostUrgent([dept('A', 'HIGH', 1)]), 'A');
    assert.equal(pickMostUrgent([dept('A', 'MODERATE', 1), dept('B', 'MODERATE', 3)]), 'B');
  });
});

describe('the overview figures come from the data', () => {
  it('sums departments, uncovered and waiting tasks across supported and unsupported departments', () => {
    const data = {
      recommendations: [{ pressure: { uncoveredTasks: 3, waitingTasks: 5 } }, { pressure: { uncoveredTasks: 1, waitingTasks: 2 } }],
      unsupported: [{ pressure: { uncoveredTasks: 2, waitingTasks: 2 } }],
    };
    assert.deepEqual(summarizeNeeds(data), { departments: 3, uncovered: 6, waiting: 9 });
  });

  it('is all zeros for nothing, and tolerates missing fields', () => {
    assert.deepEqual(summarizeNeeds(null), { departments: 0, uncovered: 0, waiting: 0 });
    assert.deepEqual(summarizeNeeds({ recommendations: [{}], unsupported: [] }), { departments: 1, uncovered: 0, waiting: 0 });
  });
});

describe('active, completed and manually ended support look different', () => {
  it('classifies a record by status and end reason', () => {
    assert.equal(recordStage({ status: 'ACTIVE' }), 'active');
    assert.equal(recordStage({ status: 'COMPLETED', endReason: 'TASK_COMPLETED' }), 'completed');
    assert.equal(recordStage({ status: 'CANCELLED', endReason: 'MANUALLY_ENDED' }), 'ended');
    assert.equal(recordStage({ status: 'CANCELLED', endReason: 'TASK_CANCELLED' }), 'cancelled');
    assert.equal(recordStage(null), 'cancelled');
  });

  it('gives every stage its own style, with emerald for good and in-progress states and red nowhere', () => {
    const keys = ['recommended', 'selected', 'active', 'completed', 'ended', 'cancelled'];
    assert.equal(new Set(keys.map((k) => STAGE_STYLES[k])).size, keys.length, 'no two stages share a style');
    for (const k of ['recommended', 'selected', 'active']) assert.ok(STAGE_STYLES[k].includes('emerald'), k);
    assert.ok(!/red/.test(Object.values(STAGE_STYLES).join(' ')));
    assert.ok(SUPPORT_STAGES.some((s) => s.key === 'ended'), 'the help legend explains Ended');
  });
});

describe('the redesigned panel keeps its behaviour and structure', () => {
  const panel = read('components', 'workload', 'CrossDepartmentSupportPanel.jsx');
  const needs = read('components', 'workload', 'support', 'SupportNeedsList.jsx');
  const builder = read('components', 'workload', 'support', 'SupportPlanBuilder.jsx');
  const history = read('components', 'workload', 'support', 'SupportHistory.jsx');
  const overview = read('components', 'workload', 'support', 'SupportOverview.jsx');
  const preview = read('components', 'workload', 'support', 'SupportImpactPreview.jsx');

  it('has a header with a Refresh action and a compact "How this works" disclosure instead of a legend', () => {
    assert.ok(panel.includes('Cross-Department Support') && panel.includes('onClick={refreshAll}'));
    assert.ok(panel.includes('<summary') && panel.includes('How this works') && panel.includes('What these suggestions do not take into account'));
    assert.ok(!panel.includes('<ul className="flex flex-wrap gap-x-4 gap-y-1 text-[11px] text-gray-500"'), 'the always-visible legend is gone');
  });

  it('shows an overview built from live data, with a dash while loading and no hard-coded numbers', () => {
    assert.ok(panel.includes('<SupportOverview needs={needs} history={history} />'));
    assert.ok(overview.includes('summarizeNeeds(needs.data)') && overview.includes("history.data.filter((row) => row.status === 'ACTIVE')") && overview.includes("const dash = '–'"));
    assert.ok(overview.includes('Need help') && overview.includes('Uncovered tasks') && overview.includes('Active support'));
    assert.ok(overview.includes('grid grid-cols-3'), 'three compact columns, also on a phone');
  });

  it('marks the most urgent department, shows waiting, minutes and uncovered, and keeps selection accessible', () => {
    assert.ok(needs.includes('pickMostUrgent(departments)') && needs.includes('Most urgent'));
    for (const label of ['Waiting', 'Minutes', 'Uncovered']) assert.ok(needs.includes(`label="${label}"`), label);
    assert.ok(needs.includes('aria-pressed={selected}') && needs.includes('suggested helper') && needs.includes('No helpers available'));
    assert.ok(needs.includes('focus-visible:ring-2 focus-visible:ring-emerald-500'));
  });

  it('makes suggested, selected and unavailable people look different, and says the plan is a draft', () => {
    assert.ok(builder.includes('stage="selected"') && builder.includes('stage="recommended"'));
    assert.ok(builder.includes('border-emerald-500 bg-emerald-50/50') && builder.includes('bg-gray-50/60 opacity-70'), 'selected is emerald, unavailable is dimmed');
    assert.ok(!/border-dashed bg-emerald/.test(builder) && !builder.includes('border-dashed border-emerald'), 'no green dashed outlines on people');
    assert.ok(builder.includes('{blocked && <p'), 'the reason a person cannot be added is visible text, not only a tooltip');
    assert.ok(builder.includes('Draft: {plan.length} selected, not confirmed') && builder.includes('Skills and shifts are not recorded'));
  });

  it('shows validation problems in amber and keeps the primary action emerald', () => {
    assert.ok(preview.includes('border-amber-300 bg-amber-50') && preview.includes('This plan cannot be confirmed yet:'));
    assert.ok(preview.includes('bg-emerald-600 hover:bg-emerald-700') && preview.includes('Review &amp; confirm'));
  });

  it('has Active and Completed & ended tabs with counts, structured rows, and a restrained End Support', () => {
    assert.ok(history.includes('role="tablist"') && history.includes('`Active (${activeCount})`'));
    assert.ok(history.includes('Completed & ended') && history.includes('endedCount'));
    for (const field of ['row.staffName', 'departmentName(row.homeDepartmentCode)', 'departmentName(row.receivingDepartmentCode)', 'row.taskTitle', 'formatWhen(row.createdAt)', 'row.approvedBy']) {
      assert.ok(history.includes(field), field);
    }
    assert.ok(history.includes('recordStage(row)') && history.includes('data-stage={stage}'));
    assert.ok(history.includes('canEnd && canEndSupport(row)'), 'End Support only on active rows, only for Admin and Manager');
  });
});

describe('second redesign: composition', () => {
  const panel = read('components', 'workload', 'CrossDepartmentSupportPanel.jsx');
  const builder = read('components', 'workload', 'support', 'SupportPlanBuilder.jsx');
  const history = read('components', 'workload', 'support', 'SupportHistory.jsx');
  const preview = read('components', 'workload', 'support', 'SupportImpactPreview.jsx');
  const needs = read('components', 'workload', 'support', 'SupportNeedsList.jsx');

  it('gives the staff area the free width and the plan a fixed, sensible column', () => {
    assert.ok(builder.includes('lg:grid-cols-[minmax(0,1fr)_22rem] xl:grid-cols-[minmax(0,1fr)_24rem]'), 'staff flexible, plan 22 to 24rem');
    assert.ok(builder.includes('grid-cols-[repeat(auto-fill,minmax(min(100%,13.5rem),1fr))]'), 'staff cards fill the width in an even grid');
    assert.ok(builder.includes('items-start'), 'the plan is not stretched to the height of the staff list');
  });

  it('does not use a sticky plan, because the page shell cannot make it work', () => {
    const shell = read('components', 'layout', 'AppShell.jsx');
    assert.ok(shell.includes('<main className="flex-1 min-w-0 p-4 sm:p-6 overflow-y-auto'), 'main is overflow-y-auto, so sticky inside it never engages while the window scrolls');
    assert.ok(!/sticky/.test(builder.replace(/\/\*[\s\S]*?\*\//g, '')), 'no sticky classes: they would do nothing and mislead');
  });

  it('uses a neutral surface for the plan and the cards, and no tinted container around the department area', () => {
    assert.ok(builder.includes('rounded-2xl border border-gray-200 bg-gray-50'));
    assert.ok(!panel.includes('bg-emerald-50/20') && panel.includes('border-t border-gray-200 pt-6'));
    assert.ok(!needs.includes('hover:border-emerald') && !needs.includes('ring-emerald-500/40'));
  });

  it('makes staff cards equal in height with the action pinned to the bottom', () => {
    assert.ok(builder.includes('flex flex-col gap-2.5 rounded-xl border p-3') && builder.includes('mt-auto w-full'));
    assert.ok(builder.includes('w-9 h-9 rounded-full'), 'one avatar size everywhere');
  });

  it('shows the plan status and count, a short empty state, and never says a draft is confirmed', () => {
    assert.ok(builder.includes('of ${options.maxHelpers} helper') && builder.includes('No helpers selected'));
    assert.ok(builder.includes('Choose who will help') && builder.includes('Nothing is assigned until you confirm.'));
    const shown = builder.replace(/\/\*[\s\S]*?\*\//g, '');
    assert.ok(!/\bconfirmed\b/i.test(shown.replace(/not confirmed/g, '')), 'on screen, "confirmed" only appears negated');
  });

  it('puts the impact preview and the one confirm action inside the plan panel, and keeps every validation message', () => {
    assert.ok(panel.includes('planFooter={(') && panel.includes('empty={state.plan.length === 0}'));
    assert.ok(builder.includes('{planFooter && <div'));
    assert.ok(preview.includes('Add at least one helper to see the impact.') && preview.includes('This plan cannot be confirmed yet:') && preview.includes('Full breakdown'));
    assert.ok(preview.includes('must keep at least ${donor.reserveRequired} free') || preview.includes('must keep at least'), 'the donor reserve stays visible');
    assert.equal(panel.split('Review &amp; confirm').length - 1, 0, 'the confirm button lives in the preview component, once');
    assert.equal(preview.split('Review &amp; confirm').length - 1, 1);
  });

  it('shows history as one list with a shared column grid, a header row on wide screens, and stacked rows on a phone', () => {
    assert.ok(history.includes('lg:grid lg:grid-cols-[') && history.includes('divide-y divide-gray-200'));
    for (const column of ['Staff member', 'Department', 'Task', 'Status']) assert.ok(history.includes(column), column);
    assert.ok(history.includes('lg:hidden text-[11px]'), 'labels appear only when the columns are gone');
  });
});
