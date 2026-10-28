import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { endReducer, initialEndState, initialSupportState, planSignature, supportHistoryView, supportReducer } from './supportPlan.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const read = (...parts) => fs.readFileSync(path.join(__dirname, '..', ...parts), 'utf8').replace(/\r\n/g, '\n');

const record = (id, status, extra = {}) => ({ id, status, staffName: 'Sofia Marino', taskTitle: 'Turnover Suite 801', ...extra });
const ready = (data) => ({ status: 'ready', data, error: '' });
const active = record('s1', 'ACTIVE');
const completed = record('s2', 'COMPLETED', { endReason: 'TASK_COMPLETED' });
const ended = record('s3', 'CANCELLED', { endReason: 'MANUALLY_ENDED' });
const endedNow = { ...active, status: 'CANCELLED', endReason: 'MANUALLY_ENDED', completedAt: '2026-10-27T12:00:00' };

describe('the main Confirmed support section follows ACTIVE support only', () => {
  it('zero records: nothing is shown', () => {
    assert.equal(supportHistoryView(ready([])), 'none');
  });

  it('zero active records but history exists: no main section, only the small history area', () => {
    assert.equal(supportHistoryView(ready([completed])), 'history');
    assert.equal(supportHistoryView(ready([ended])), 'history');
    assert.equal(supportHistoryView(ready([completed, ended])), 'history');
  });

  it('at least one active record: the main section', () => {
    assert.equal(supportHistoryView(ready([active])), 'section');
  });

  it('active records plus history: the main section (its Completed & ended tab holds the history)', () => {
    assert.equal(supportHistoryView(ready([active, completed, ended])), 'section');
  });

  it('is decided by the records the server returned, with no counts of its own', () => {
    assert.equal(supportHistoryView(ready([record('a', 'ACTIVE'), record('b', 'ACTIVE')])), 'section');
    assert.equal(supportHistoryView(ready([record('a', 'CANCELLED', { endReason: 'TASK_DELETED' })])), 'history');
  });
});

describe('confirming a plan and ending the last support', () => {
  it('a newly confirmed plan shows the section again, even when only history existed before', () => {
    const before = ready([ended]);
    const afterConfirm = ready([record('s9', 'ACTIVE'), ended]);
    assert.equal(supportHistoryView(before), 'history');
    assert.equal(supportHistoryView(afterConfirm), 'section');
  });

  it('the first confirmed plan ever shows it too', () => {
    assert.equal(supportHistoryView(ready([])), 'none');
    assert.equal(supportHistoryView(ready([active])), 'section');
  });

  it('ending the last active record hides the main section once the refreshed records say so, and keeps the history reachable', () => {
    const beforeEnd = ready([active, completed]);
    const afterEnd = ready([endedNow, completed]);
    assert.equal(supportHistoryView(beforeEnd), 'section');
    assert.equal(supportHistoryView(afterEnd), 'history');
    assert.equal(afterEnd.data.length, 2, 'both records are still there: nothing is deleted');
    assert.equal(afterEnd.data.filter((r) => r.status === 'ACTIVE').length, 0);
  });

  it('ending one of two active records keeps the main section', () => {
    assert.equal(supportHistoryView(ready([endedNow, record('s5', 'ACTIVE')])), 'section');
  });

  it('a task completing and closing its support behaves the same way', () => {
    assert.equal(supportHistoryView(ready([{ ...active, status: 'COMPLETED', endReason: 'TASK_COMPLETED' }])), 'history');
  });

  it('does not hide on the End Support click itself: it waits for the refreshed records', () => {
    const panel = read('components', 'workload', 'CrossDepartmentSupportPanel.jsx');
    const handler = panel.slice(panel.indexOf('const handleEnd'), panel.indexOf('const canEnd'));
    assert.ok(handler.includes('await endSupportAssignment(row.id)') && handler.includes('loadHistory();'), 'history is reloaded from the server');
    assert.ok(!/setHistory/.test(handler), 'the handler never edits the history locally, so visibility only ever follows server data');
  });
});

describe('a draft plan never makes the section visible', () => {
  it('selecting a department, adding staff and previewing change nothing', () => {
    const options = { tasks: [{ id: 't1' }], maxHelpers: 1, donors: [{ code: 'FRONT_DESK', name: 'Front Desk', maxLendable: 1, canLend: true, staff: [{ staffId: 'a' }] }] };
    let state = supportReducer(initialSupportState, { type: 'SELECT_DEPARTMENT', code: 'HOUSEKEEPING' });
    state = supportReducer(state, { type: 'ADD_STAFF', options, donorCode: 'FRONT_DESK', staffId: 'a' });
    state = supportReducer(state, { type: 'PREVIEW_READY', signature: planSignature(state.department, state.plan), preview: { valid: true, problems: [], fingerprint: 'fp' } });
    assert.equal(state.plan.length, 1, 'there really is a draft with a ready preview');
    assert.equal(supportHistoryView(ready([])), 'none', 'no records: still nothing');
    assert.equal(supportHistoryView(ready([ended])), 'history', 'only history: still just the small history area');
  });

  it('the check reads the server history only, never the plan state', () => {
    const source = read('utils', 'supportPlan.js');
    const fn = source.slice(source.indexOf('export function supportHistoryView'), source.indexOf('/** A support record as a stage key'));
    assert.ok(!/plan|department|preview|confirm/.test(fn.replace(/\/\*[\s\S]*?\*\//g, '')), 'no draft state is read');
  });
});

describe('loading and error', () => {
  it('loading shows nothing, so no empty section flashes', () => {
    assert.equal(supportHistoryView({ status: 'loading', data: [], error: '' }), 'none');
  });

  it('an error shows the section with its message, so a failure is not mistaken for "no support"', () => {
    assert.equal(supportHistoryView({ status: 'error', data: [], error: 'Network error' }), 'section');
  });

  it('a refresh keeps what is on screen: loadHistory never flips back to loading', () => {
    const panel = read('components', 'workload', 'CrossDepartmentSupportPanel.jsx');
    const load = panel.slice(panel.indexOf('const loadHistory'), panel.indexOf('const loadOptions'));
    assert.ok(!load.includes("status: 'loading'") && load.includes("status: 'ready'") && load.includes("status: 'error'"));
  });
});

describe('the history area and the unchanged parts', () => {
  const panel = read('components', 'workload', 'CrossDepartmentSupportPanel.jsx');
  const history = read('components', 'workload', 'support', 'SupportHistory.jsx');

  it('renders the main section only for the section view, and a collapsed Support history area only for the history view', () => {
    assert.ok(panel.includes("{historyView === 'section' && ("));
    assert.ok(panel.includes("{historyView === 'history' && ("));
    assert.equal(panel.split('<SupportHistory').length - 1, 2, 'one main section, one history area');
    const area = panel.slice(panel.indexOf("{historyView === 'history' && ("), panel.indexOf("{['reviewing', 'submitting', 'error']"));
    assert.ok(area.includes('<details') && !area.includes('<details open') && area.includes('Support history ({history.data.length})'), 'collapsed by default');
    assert.ok(area.includes('historyOnly') && !area.includes('onEnd='), 'ended records have no End Support (only the remove action is passed)');
  });

  it('the history-only list has no Confirmed support heading, no tabs and no "no one is on support" message', () => {
    assert.ok(history.includes('historyOnly = false') && history.includes("useState(historyOnly ? 'ended' : 'active')"));
    assert.ok(history.includes('{!historyOnly && ('), 'the heading and tabs are skipped');
    assert.ok(history.includes("aria-label={historyOnly ? 'Support history' : 'Support assignments'}"));
  });

  it('keeps the Active and Completed & ended tabs and End Support in the main section', () => {
    assert.ok(history.includes('role="tablist"') && history.includes('Completed & ended') && history.includes('`Active (${activeCount})`'));
    assert.ok(history.includes('canEnd && canEndSupport(row)') && history.includes('End Support'));
  });

  it('confirming, ending and refreshing still reload the records that decide the view', () => {
    assert.ok(panel.includes("dispatch({ type: 'CONFIRM_SUCCEEDED', created });") && panel.includes('loadHistory();'));
    const finallyBlock = panel.slice(panel.indexOf('} finally {', panel.indexOf('endSupportAssignment(row.id)')));
    assert.ok(finallyBlock.includes('loadHistory();'));
    assert.ok(/refreshAll = useCallback\(\(\) => \{[\s\S]*loadHistory\(\);/.test(panel));
  });

  it('the End Support dialog flow is untouched', () => {
    assert.equal(endReducer(initialEndState, { type: 'END_OPEN', row: active }).status, 'reviewing');
    assert.equal(endReducer(initialEndState, { type: 'END_OPEN', row: ended }).status, 'idle');
  });
});
