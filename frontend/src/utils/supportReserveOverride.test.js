import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  buildPlanRequest,
  canReview,
  donorOverrideLeft,
  hasReserveAcknowledgement,
  initialSupportState,
  needsReserveOverride,
  planSignature,
  reserveRiskRows,
  supportReducer,
  whyCannotAdd,
} from './supportPlan.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const read = (...parts) => fs.readFileSync(path.join(__dirname, '..', ...parts), 'utf8').replace(/\r\n/g, '\n');

const person = (staffId, name) => ({ staffId, name });
// FRONT_DESK has one free person (last): normal capacity 0, override capacity 1.
// MAINTENANCE has three free: normal capacity 2.
// SECURITY has one free person but its own waiting work needs them: no override either.
const options = {
  tasks: [{ id: 't1' }, { id: 't2' }, { id: 't3' }],
  maxHelpers: 3,
  donors: [
    { code: 'FRONT_DESK', name: 'Front Desk', freeStaff: 1, maxLendable: 0, overrideLendable: 1, canLend: false, staff: [person('fd1', 'Sofia Marino')] },
    { code: 'MAINTENANCE', name: 'Maintenance', freeStaff: 3, maxLendable: 2, overrideLendable: 3, canLend: true, staff: [person('m1', 'Ana Torres'), person('m2', 'Ben Okafor'), person('m3', 'Cam Reyes')] },
    { code: 'SECURITY', name: 'Security', freeStaff: 1, maxLendable: 0, overrideLendable: 0, canLend: false, staff: [person('s1', 'Rocky')] },
  ],
};
const start = supportReducer(initialSupportState, { type: 'SELECT_DEPARTMENT', code: 'HOUSEKEEPING' });
const add = (state, donorCode, staffId, extra = {}) => supportReducer(state, { type: 'ADD_STAFF', options, donorCode, staffId, ...extra });
const prompt = (state, p) => supportReducer(state, { type: 'OPEN_RESERVE_PROMPT', options, prompt: p });
const readyPreview = (state, data) => supportReducer(state, { type: 'PREVIEW_READY', signature: planSignature(state.department, state.plan), preview: { valid: true, problems: [], warnings: [], canOverrideReserve: false, fingerprint: 'fp', ...data } });

describe('the last free staff member is offered, but only with an acknowledgement', () => {
  it('is allowed by the rules, as a different kind of "yes"', () => {
    assert.equal(whyCannotAdd(options, [], 'FRONT_DESK', 'fd1'), '', 'Add to plan is not disabled');
    assert.equal(needsReserveOverride(options, [], 'FRONT_DESK', 'fd1'), true);
    assert.deepEqual(donorOverrideLeft(options, []), { FRONT_DESK: 1, MAINTENANCE: 3, SECURITY: 0 });
  });

  it('is not needed while a reserve remains: normal borrowing is unchanged', () => {
    assert.equal(needsReserveOverride(options, [], 'MAINTENANCE', 'm1'), false);
    const one = add(start, 'MAINTENANCE', 'm1');
    assert.deepEqual(one.plan.map((p) => p.staffId), ['m1']);
    assert.equal(one.plan[0].reserveAcknowledged, undefined, 'no acknowledgement flag on a normal loan');
    assert.equal(one.reservePrompt, null);
    assert.equal('acknowledgeReserveOverride' in buildPlanRequest(one), false, 'the request is exactly as before');
    assert.equal(hasReserveAcknowledgement(one.plan), false);
  });

  it('the third Maintenance person (the last free one) is the one that needs it', () => {
    let state = add(add(start, 'MAINTENANCE', 'm1'), 'MAINTENANCE', 'm2');
    assert.equal(needsReserveOverride(options, state.plan, 'MAINTENANCE', 'm3'), true);
    state = add(state, 'MAINTENANCE', 'm3');
    assert.equal(state.plan.length, 2, 'not added silently');
    assert.match(state.notice, /Acknowledge the staffing risk/);
  });

  it('the department\'s own waiting work is a hard floor: no override is offered when the server says none', () => {
    assert.equal(needsReserveOverride(options, [], 'SECURITY', 's1'), false);
    assert.match(whyCannotAdd(options, [], 'SECURITY', 's1'), /reserve/);
    assert.equal(add(start, 'SECURITY', 's1').plan.length, 0);
  });

  it('a server that does not send overrideLendable offers no override at all (older backend)', () => {
    const older = { ...options, donors: options.donors.map(({ overrideLendable, ...rest }) => rest) };
    assert.equal(needsReserveOverride(older, [], 'FRONT_DESK', 'fd1'), false);
    assert.match(whyCannotAdd(older, [], 'FRONT_DESK', 'fd1'), /reserve/);
  });
});

describe('asking, cancelling and accepting', () => {
  it('opens the warning for a person who needs it, and not otherwise', () => {
    const asked = prompt(start, { kind: 'add', donorCode: 'FRONT_DESK', staffId: 'fd1' });
    assert.deepEqual(asked.reservePrompt, { kind: 'add', donorCode: 'FRONT_DESK', staffId: 'fd1' });
    assert.equal(asked.plan.length, 0, 'asking adds nobody');
    assert.equal(prompt(start, { kind: 'add', donorCode: 'MAINTENANCE', staffId: 'm1' }), start, 'no warning when a reserve remains');
    assert.equal(prompt(start, { kind: 'add', donorCode: 'SECURITY', staffId: 's1' }), start, 'and none for a person no override can help');
  });

  it('Cancel leaves the draft exactly as it was, with no acknowledgement', () => {
    const before = add(start, 'MAINTENANCE', 'm1');
    const cancelled = [prompt(before, { kind: 'add', donorCode: 'FRONT_DESK', staffId: 'fd1' }), { type: 'CANCEL_RESERVE_PROMPT' }].reduce((s, a) => (a.type ? supportReducer(s, a) : a));
    assert.equal(cancelled.reservePrompt, null);
    assert.deepEqual(cancelled.plan, before.plan);
    assert.equal(hasReserveAcknowledgement(cancelled.plan), false);
  });

  it('Continue adds the person marked as acknowledged, and the request then carries the acknowledgement', () => {
    const asked = prompt(start, { kind: 'add', donorCode: 'FRONT_DESK', staffId: 'fd1' });
    const accepted = supportReducer(asked, { type: 'CONFIRM_RESERVE_PROMPT', options });
    assert.equal(accepted.reservePrompt, null);
    assert.deepEqual(accepted.plan.map((p) => [p.staffId, p.reserveAcknowledged]), [['fd1', true]]);
    assert.equal(buildPlanRequest(accepted).acknowledgeReserveOverride, true);
    assert.equal(accepted.preview.status, 'loading', 'the plan is previewed again');
  });

  it('accepting confirms nothing: no review is opened and the confirm state is untouched', () => {
    const accepted = supportReducer(prompt(start, { kind: 'add', donorCode: 'FRONT_DESK', staffId: 'fd1' }), { type: 'CONFIRM_RESERVE_PROMPT', options });
    assert.equal(accepted.confirm.status, 'idle');
    assert.equal(canReview(accepted), false, 'review stays closed until the server previews the plan as valid');
    assert.equal(accepted.result, null);
  });

  it('after acknowledging, the normal preview and review steps apply', () => {
    const accepted = supportReducer(prompt(start, { kind: 'add', donorCode: 'FRONT_DESK', staffId: 'fd1' }), { type: 'CONFIRM_RESERVE_PROMPT', options });
    const previewed = readyPreview(accepted, { warnings: [{ code: 'RESERVE_OVERRIDE', message: 'Front Desk will be left with 0 free staff.' }] });
    assert.equal(canReview(previewed), true);
    assert.equal(previewed.preview.data.warnings[0].code, 'RESERVE_OVERRIDE', 'the warning stays in the preview');
    assert.equal(supportReducer(previewed, { type: 'OPEN_REVIEW' }).confirm.status, 'reviewing');
  });

  it('removing the acknowledged person removes the acknowledgement from the request', () => {
    const accepted = supportReducer(prompt(start, { kind: 'add', donorCode: 'FRONT_DESK', staffId: 'fd1' }), { type: 'CONFIRM_RESERVE_PROMPT', options });
    const removed = supportReducer(accepted, { type: 'REMOVE_STAFF', staffId: 'fd1' });
    assert.equal('acknowledgeReserveOverride' in buildPlanRequest(removed), false);
  });

  it('a stray Continue with no question open does nothing', () => {
    assert.equal(supportReducer(start, { type: 'CONFIRM_RESERVE_PROMPT', options }), start);
    assert.equal(supportReducer(start, { type: 'CANCEL_RESERVE_PROMPT' }), start);
  });

  it('cannot be opened while a plan is being reviewed or submitted', () => {
    const accepted = supportReducer(prompt(start, { kind: 'add', donorCode: 'FRONT_DESK', staffId: 'fd1' }), { type: 'CONFIRM_RESERVE_PROMPT', options });
    const reviewing = supportReducer(readyPreview(accepted), { type: 'OPEN_REVIEW' });
    assert.equal(prompt(reviewing, { kind: 'plan' }), reviewing);
  });

  it('"Use suggestions" never adds a person who would use up a reserve', () => {
    const suggestions = [{ staffId: 'fd1', homeDepartmentCode: 'FRONT_DESK' }, { staffId: 'm1', homeDepartmentCode: 'MAINTENANCE' }];
    const used = supportReducer(start, { type: 'USE_SUGGESTIONS', options, suggestions });
    assert.deepEqual(used.plan.map((p) => p.staffId), ['m1'], 'only the ordinary one is added in bulk');
  });
});

describe('the warning numbers', () => {
  it('says how many free staff the affected department has now and will have left', () => {
    assert.deepEqual(reserveRiskRows({ kind: 'add', donorCode: 'FRONT_DESK', staffId: 'fd1' }, options, [], null),
      [{ donorName: 'Front Desk', personName: 'Sofia Marino', freeNow: 1, freeAfter: 0 }]);
  });

  it('counts the people already in the plan from that department', () => {
    const plan = [{ staffId: 'm1', taskId: 't1' }, { staffId: 'm2', taskId: 't2' }];
    assert.deepEqual(reserveRiskRows({ kind: 'add', donorCode: 'MAINTENANCE', staffId: 'm3' }, options, plan, null),
      [{ donorName: 'Maintenance', personName: 'Cam Reyes', freeNow: 1, freeAfter: 0 }]);
  });

  it('for a plan-level prompt it reads the server preview: only departments left below their reserve', () => {
    const preview = { donors: [
      { name: 'Front Desk', reserveRequired: 1, before: { freeStaff: 1 }, after: { freeStaff: 0 } },
      { name: 'Maintenance', reserveRequired: 1, before: { freeStaff: 3 }, after: { freeStaff: 2 } },
    ] };
    assert.deepEqual(reserveRiskRows({ kind: 'plan' }, options, [], preview), [{ donorName: 'Front Desk', personName: '', freeNow: 1, freeAfter: 0 }]);
    assert.deepEqual(reserveRiskRows(null, options, [], preview), []);
  });
});

describe('stale and concurrent changes', () => {
  const acknowledged = () => supportReducer(prompt(start, { kind: 'add', donorCode: 'FRONT_DESK', staffId: 'fd1' }), { type: 'CONFIRM_RESERVE_PROMPT', options });

  it('the acknowledgement is part of the plan signature, so an answer for the plan without it is ignored', () => {
    const accepted = acknowledged();
    const staleSignature = planSignature('HOUSEKEEPING', [{ staffId: 'fd1', taskId: accepted.plan[0].taskId }]);
    assert.notEqual(staleSignature, planSignature(accepted.department, accepted.plan));
    const ignored = supportReducer(accepted, { type: 'PREVIEW_READY', signature: staleSignature, preview: { valid: true, problems: [], warnings: [], fingerprint: 'old' } });
    assert.equal(ignored, accepted);
  });

  it('when the server later says the plan is blocked by the reserve, the manager can review the risk, and nothing is confirmed', () => {
    // a plan without acknowledgement that the server now says only the reserve blocks (the donor shrank since)
    let state = add(start, 'MAINTENANCE', 'm1');
    state = readyPreview(state, { valid: false, problems: [{ code: 'DONOR_RESERVE', message: 'Maintenance would be left with 0 free staff' }], canOverrideReserve: true });
    assert.equal(canReview(state), false, 'an invalid plan cannot be reviewed');
    const asked = supportReducer(state, { type: 'OPEN_RESERVE_PROMPT', options, prompt: { kind: 'plan' } });
    assert.deepEqual(asked.reservePrompt, { kind: 'plan' });
    const accepted = supportReducer(asked, { type: 'CONFIRM_RESERVE_PROMPT', options });
    assert.equal(hasReserveAcknowledgement(accepted.plan), true);
    assert.equal(accepted.preview.status, 'loading', 're-previewed by the server, which decides again');
    assert.equal(accepted.confirm.status, 'idle');
  });

  it('no review prompt is offered when the server says an acknowledgement cannot help', () => {
    let state = add(start, 'MAINTENANCE', 'm1');
    state = readyPreview(state, { valid: false, problems: [{ code: 'DONOR_RESERVE', message: 'x' }], canOverrideReserve: false });
    assert.equal(supportReducer(state, { type: 'OPEN_RESERVE_PROMPT', options, prompt: { kind: 'plan' } }), state);
  });

  it('a failed confirmation (out of date) keeps the draft and its acknowledgement and recalculates the preview, so an updated warning shows', () => {
    const reviewing = supportReducer(readyPreview(acknowledged()), { type: 'OPEN_REVIEW' });
    const submitting = supportReducer(reviewing, { type: 'CONFIRM_START' });
    const failed = supportReducer(submitting, { type: 'CONFIRM_FAILED', message: 'This plan is out of date', problems: [{ code: 'STALE_PLAN' }], stale: true });
    assert.equal(failed.confirm.stale, true);
    assert.equal(failed.preview.status, 'loading', 'the preview is recalculated against the current data');
    assert.equal(hasReserveAcknowledgement(failed.plan), true, 'the draft is kept as it was');
  });

  it('clearing the selection also clears any open warning', () => {
    const asked = prompt(start, { kind: 'add', donorCode: 'FRONT_DESK', staffId: 'fd1' });
    const cleared = [{ type: 'REQUEST_DESELECT' }].reduce(supportReducer, asked);
    assert.equal(cleared.department, null);
    assert.equal(cleared.reservePrompt, null);
  });
});

describe('what stays visible, and who may do it', () => {
  const panel = read('components', 'workload', 'CrossDepartmentSupportPanel.jsx');
  const builder = read('components', 'workload', 'support', 'SupportPlanBuilder.jsx');
  const preview = read('components', 'workload', 'support', 'SupportImpactPreview.jsx');
  const confirm = read('components', 'workload', 'support', 'SupportConfirmDialog.jsx');
  const dialog = read('components', 'workload', 'support', 'SupportReserveOverrideDialog.jsx');
  const api = read('services', 'api.js');

  it('the warning dialog names the department, the numbers, Cancel and Continue, and says nothing is assigned yet', () => {
    assert.ok(dialog.includes('Use the last free staff member?') && dialog.includes('Staffing risk'));
    assert.ok(dialog.includes('{row.donorName}') && dialog.includes('{row.freeNow} free staff') && dialog.includes('Math.max(0, row.freeAfter)'));
    assert.ok(dialog.includes('Cancel') && dialog.includes('Continue'));
    assert.ok(dialog.includes('Continuing does not assign anything') && dialog.includes('the person stays in their home department'));
  });

  it('is an accessible modal: focus on Cancel, Escape and the backdrop cancel, Tab stays inside', () => {
    assert.ok(dialog.includes('role="dialog"') && dialog.includes('aria-modal="true"') && dialog.includes('cancelRef.current?.focus()'));
    assert.ok(dialog.includes("event.key === 'Escape'") && dialog.includes('onClick={onCancel}') && dialog.includes("event.key === 'Tab'"));
  });

  it('keeps Add to plan enabled for the last free person, and routes the click to the warning', () => {
    assert.ok(builder.includes('needsReserveOverride(options, plan, donor.code, person.staffId)'));
    assert.ok(builder.includes('else if (risky) onRequestOverride(donor.code, person.staffId);'));
    assert.ok(builder.includes('disabled={disabled || Boolean(blocked)}'), 'only a real block disables it');
    assert.ok(builder.includes('Needs your acknowledgement') && builder.includes('Reserve override acknowledged'));
  });

  it('shows the risk in the draft plan and in the preview, and again in the final confirmation dialog', () => {
    assert.ok(builder.includes('Staffing risk acknowledged:'), 'in the draft plan row');
    assert.ok(preview.includes('Staffing risk acknowledged') && preview.includes('data.warnings'), 'in the preview');
    assert.ok(confirm.includes('Staffing risk you acknowledged') && confirm.includes('preview.warnings'), 'in the confirm dialog');
  });

  it('shows the override in the confirmation result', () => {
    assert.ok(panel.includes('row.reserveOverride === true') && panel.includes('Reserve override acknowledged for'));
  });

  it('only sends the acknowledgement when it was given, to the same endpoints as before', () => {
    assert.ok(api.includes("'/api/support/preview', { method: 'POST'") && api.includes("'/api/support/assignments', { method: 'POST'"));
    const fn = read('utils', 'supportPlan.js');
    assert.ok(fn.includes('if (hasReserveAcknowledgement(state.plan)) request.acknowledgeReserveOverride = true;'));
  });

  it('is only reachable for Admin and Manager: the whole panel is behind the role check', () => {
    const page = read('pages', 'WorkloadPage.jsx');
    assert.ok(page.includes("['ADMIN', 'MANAGER'].includes(getUserRole()) && <CrossDepartmentSupportPanel"));
    assert.ok(panel.includes('<SupportReserveOverrideDialog') && !panel.includes('createAssignment'), 'no ordinary assignment endpoint is used');
  });

  it('the draft never confirms by itself: Continue dispatches only the acknowledgement', () => {
    assert.ok(panel.includes("onContinue={() => dispatch({ type: 'CONFIRM_RESERVE_PROMPT', options: options.data })}"));
    assert.equal(panel.split('confirmSupportPlan(').length - 1, 1, 'still exactly one place confirms');
  });
});
