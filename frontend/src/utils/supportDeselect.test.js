import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { initialSupportState, planSignature, supportReducer } from './supportPlan.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const read = (...parts) => fs.readFileSync(path.join(__dirname, '..', ...parts), 'utf8').replace(/\r\n/g, '\n');

const options = {
  tasks: [{ id: 't1' }, { id: 't2' }],
  maxHelpers: 2,
  donors: [{ code: 'FRONT_DESK', name: 'Front Desk', maxLendable: 2, canLend: true, staff: [{ staffId: 'a' }, { staffId: 'b' }] }],
};
const run = (...actions) => actions.reduce((state, action) => supportReducer(state, action), initialSupportState);
const select = (code = 'HOUSEKEEPING') => ({ type: 'SELECT_DEPARTMENT', code });
const add = (staffId) => ({ type: 'ADD_STAFF', options, donorCode: 'FRONT_DESK', staffId });
const preview = (state) => ({ type: 'PREVIEW_READY', signature: planSignature(state.department, state.plan), preview: { valid: true, problems: [], fingerprint: 'fp' } });

describe('selecting a department', () => {
  it('selects it, and selecting the same one through SELECT_DEPARTMENT changes nothing', () => {
    const selected = run(select());
    assert.equal(selected.department, 'HOUSEKEEPING');
    assert.equal(supportReducer(selected, select()), selected);
  });

  it('switching to a different department starts a fresh plan', () => {
    const withStaff = run(select(), add('a'));
    const switched = supportReducer(withStaff, select('MAINTENANCE'));
    assert.equal(switched.department, 'MAINTENANCE');
    assert.deepEqual(switched.plan, []);
  });
});

describe('deselecting a department with no draft', () => {
  it('clears the selection straight away, with nothing left over', () => {
    const cleared = run(select(), { type: 'REQUEST_DESELECT' });
    assert.equal(cleared.department, null);
    assert.deepEqual(cleared.plan, []);
    assert.equal(cleared.discardPending, false);
    assert.deepEqual(cleared.preview, initialSupportState.preview);
    assert.deepEqual(cleared.confirm, initialSupportState.confirm);
    assert.equal(cleared.notice, '');
  });

  it('does nothing when no department is selected', () => {
    assert.equal(supportReducer(initialSupportState, { type: 'REQUEST_DESELECT' }), initialSupportState);
  });

  it('the department can be selected again afterwards', () => {
    assert.equal(run(select(), { type: 'REQUEST_DESELECT' }, select('MAINTENANCE')).department, 'MAINTENANCE');
  });
});

describe('deselecting a department that has a draft plan', () => {
  const draft = () => run(select(), add('a'), add('b'));

  it('asks first and keeps everything as it is', () => {
    const asked = supportReducer(draft(), { type: 'REQUEST_DESELECT' });
    assert.equal(asked.discardPending, true);
    assert.equal(asked.department, 'HOUSEKEEPING');
    assert.deepEqual(asked.plan.map((p) => p.staffId), ['a', 'b']);
  });

  it('cancelling the question keeps the department and the draft exactly', () => {
    const before = draft();
    const kept = run(select(), add('a'), add('b'), { type: 'REQUEST_DESELECT' }, { type: 'CANCEL_DISCARD' });
    assert.equal(kept.discardPending, false);
    assert.equal(kept.department, before.department);
    assert.deepEqual(kept.plan, before.plan);
  });

  it('discarding clears the department, the draft, the preview and any notice', () => {
    const state = draft();
    const cleared = [{ type: 'REQUEST_DESELECT' }, { type: 'CONFIRM_DISCARD' }].reduce(supportReducer, supportReducer(state, preview(state)));
    assert.equal(cleared.department, null);
    assert.deepEqual(cleared.plan, []);
    assert.equal(cleared.discardPending, false);
    assert.deepEqual(cleared.preview, initialSupportState.preview, 'no stale preview');
    assert.deepEqual(cleared.confirm, initialSupportState.confirm);
  });

  it('a stale discard or cancel (no question open) is ignored', () => {
    const state = draft();
    assert.equal(supportReducer(state, { type: 'CONFIRM_DISCARD' }), state, 'cannot discard without being asked');
    assert.equal(supportReducer(state, { type: 'CANCEL_DISCARD' }), state);
  });

  it('refuses to clear anything while the plan is being reviewed or submitted', () => {
    const reviewing = supportReducer(supportReducer(draft(), preview(draft())), { type: 'OPEN_REVIEW' });
    // the preview was for an equal plan, so the review can open
    assert.equal(reviewing.confirm.status, 'reviewing');
    assert.equal(supportReducer(reviewing, { type: 'REQUEST_DESELECT' }), reviewing);
    const submitting = supportReducer(reviewing, { type: 'CONFIRM_START' });
    assert.equal(supportReducer(submitting, { type: 'REQUEST_DESELECT' }), submitting);
  });

  it('a late preview for the discarded plan is ignored', () => {
    const state = draft();
    const stale = preview(state);
    const cleared = run(select(), add('a'), add('b'), { type: 'REQUEST_DESELECT' }, { type: 'CONFIRM_DISCARD' });
    assert.equal(supportReducer(cleared, stale), cleared);
  });
});

describe('clearing the selection keeps what is not part of the draft', () => {
  const created = [{ id: 'sup-1', staffName: 'Sofia Marino', taskTitle: 'Turnover Suite 801' }];
  const afterConfirm = supportReducer(run(select(), add('a')), { type: 'CONFIRM_SUCCEEDED', created });

  it('keeps the outcome banner of an earlier confirmation', () => {
    const cleared = supportReducer(afterConfirm, { type: 'REQUEST_DESELECT' });
    assert.deepEqual(cleared.result, { created });
    assert.equal(cleared.department, null);
  });

  it('the reducer holds no confirmed support, assignments or tasks: those are server data it never touches', () => {
    for (const key of Object.keys(initialSupportState)) assert.ok(['department', 'plan', 'notice', 'preview', 'confirm', 'result', 'discardPending', 'reservePrompt'].includes(key), key);
    const source = read('utils', 'supportPlan.js');
    const deselect = source.slice(source.indexOf("case 'REQUEST_DESELECT'"), source.indexOf("case 'ADD_STAFF'"));
    assert.ok(!/fetch|apiFetch|endSupport|confirmSupport|getSupport/.test(deselect), 'deselecting makes no API call');
  });
});

describe('panel wiring', () => {
  const panel = read('components', 'workload', 'CrossDepartmentSupportPanel.jsx');
  const dialog = read('components', 'workload', 'support', 'SupportDiscardDialog.jsx');

  it('clicking the selected department card again deselects it, and a different card selects it', () => {
    assert.ok(panel.includes("dispatch(code === state.department ? { type: 'REQUEST_DESELECT' } : { type: 'SELECT_DEPARTMENT', code })"));
  });

  it('offers a subtle Clear selection action while a department is selected, disabled during confirmation', () => {
    assert.ok(panel.includes('Clear selection') && panel.includes("onClick={() => dispatch({ type: 'REQUEST_DESELECT' })}"));
    assert.ok(panel.includes('disabled={confirming}'));
    assert.ok(panel.indexOf('Clear selection') > panel.indexOf('{state.department && ('), 'only inside the selected-department area');
  });

  it('asks before discarding a draft, and Keep plan or Escape cancel', () => {
    assert.ok(panel.includes('state.discardPending && (') && panel.includes("onKeep={() => dispatch({ type: 'CANCEL_DISCARD' })}") && panel.includes("onDiscard={() => dispatch({ type: 'CONFIRM_DISCARD' })}"));
    assert.ok(dialog.includes("event.key === 'Escape'") && dialog.includes('onClick={onKeep}') && dialog.includes('Keep plan') && dialog.includes('Discard draft'));
    assert.ok(dialog.includes('keepRef.current?.focus()'), 'focus starts on the safe choice');
    assert.ok(dialog.includes('role="dialog"') && dialog.includes('aria-modal="true"') && dialog.includes("event.key === 'Tab'"));
  });

  it('says nothing is assigned by a draft, so nothing else changes', () => {
    assert.ok(dialog.includes('Nothing has been assigned for this draft'));
  });

  it('discarding calls no API and leaves the history and the confirmed support alone', () => {
    const discard = panel.slice(panel.indexOf('{state.discardPending && ('), panel.indexOf('{confirming && state.preview.data'));
    assert.ok(!/loadHistory|endSupportAssignment|confirmSupportPlan|apiFetch/.test(discard));
    assert.ok(panel.includes('<SupportHistory') && panel.includes('history={history}'), 'the history stays on screen with no department selected');
  });
});
