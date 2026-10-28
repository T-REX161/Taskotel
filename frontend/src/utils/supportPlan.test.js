import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import {
  MAX_PLAN_SIZE,
  beforeAfter,
  buildPlanRequest,
  canReview,
  describeEndReason,
  describeProblems,
  donorCapacityLeft,
  initialSupportState,
  nextFreeTaskId,
  planSignature,
  stageOf,
  supportReducer,
  whyCannotAdd,
} from './supportPlan.js';
import { calculateDepartmentWorkloads } from './workloadUtils.js';

const tasks = [
  { id: 't1', title: 'Urgent turnover', priority: 'URGENT', estimatedDuration: 60 },
  { id: 't2', title: 'Deep clean', priority: 'HIGH', estimatedDuration: 90 },
  { id: 't3', title: 'Restock', priority: 'MEDIUM', estimatedDuration: 45 },
];
const options = {
  tasks,
  maxHelpers: 3,
  donors: [
    { code: 'MAINTENANCE', name: 'Maintenance', maxLendable: 2, canLend: true, staff: [{ staffId: 'a' }, { staffId: 'b' }, { staffId: 'c' }] },
    { code: 'SECURITY', name: 'Security', maxLendable: 0, canLend: false, staff: [{ staffId: 'z' }] },
  ],
};
const start = supportReducer(initialSupportState, { type: 'SELECT_DEPARTMENT', code: 'HOUSEKEEPING' });
const add = (state, staffId, donorCode = 'MAINTENANCE') => supportReducer(state, { type: 'ADD_STAFF', options, donorCode, staffId });
const readyPreview = (state, valid = true) => {
  const preview = { valid, problems: [], items: [], fingerprint: 'fp', receiving: null, donors: [] };
  return supportReducer(state, { type: 'PREVIEW_READY', signature: planSignature(state.department, state.plan), preview });
};

describe('plan building', () => {
  it('selecting a department starts an empty plan and re-selecting it keeps the plan', () => {
    assert.equal(start.department, 'HOUSEKEEPING');
    assert.deepEqual(start.plan, []);
    const withStaff = add(start, 'a');
    assert.equal(supportReducer(withStaff, { type: 'SELECT_DEPARTMENT', code: 'HOUSEKEEPING' }), withStaff);
    assert.deepEqual(supportReducer(withStaff, { type: 'SELECT_DEPARTMENT', code: 'FRONT_DESK' }).plan, [], 'a different department starts over');
  });

  it('selecting staff gives each the most important task nobody else has', () => {
    const plan = add(add(start, 'a'), 'b').plan;
    assert.deepEqual(plan, [{ staffId: 'a', taskId: 't1' }, { staffId: 'b', taskId: 't2' }]);
    assert.equal(nextFreeTaskId(tasks, plan), 't3');
    assert.equal(nextFreeTaskId(tasks, [...plan, { staffId: 'c', taskId: 't3' }]), null);
  });

  it('changing the plan invalidates the preview and starts a new one', () => {
    const ready = readyPreview(add(start, 'a'));
    assert.equal(ready.preview.status, 'ready');
    const after = add(ready, 'b');
    assert.equal(after.preview.status, 'loading');
    assert.equal(after.preview.data, null);
  });

  it('a person can be removed, and the task can be changed to a free one only', () => {
    let state = add(add(start, 'a'), 'b');
    state = supportReducer(state, { type: 'SET_TASK', staffId: 'a', taskId: 't3' });
    assert.deepEqual(state.plan[0], { staffId: 'a', taskId: 't3' });
    const clash = supportReducer(state, { type: 'SET_TASK', staffId: 'a', taskId: 't2' });
    assert.equal(clash.plan[0].taskId, 't3', 'task t2 already belongs to b');
    assert.match(clash.notice, /already has a helper/);
    assert.deepEqual(supportReducer(state, { type: 'REMOVE_STAFF', staffId: 'b' }).plan, [{ staffId: 'a', taskId: 't3' }]);
    const cleared = supportReducer(state, { type: 'CLEAR_PLAN' });
    assert.deepEqual(cleared.plan, []);
    assert.equal(cleared.preview.status, 'idle');
  });

  it('adding the same person twice changes nothing', () => {
    const once = add(start, 'a');
    assert.equal(add(once, 'a'), once);
  });

  it('refuses a person whose department would lose its reserve, with the reason', () => {
    const full = add(add(start, 'a'), 'b');
    const blocked = add(full, 'c');
    assert.deepEqual(blocked.plan, full.plan);
    assert.match(blocked.notice, /reserve/);
    assert.deepEqual(donorCapacityLeft(options, full.plan), { MAINTENANCE: 0, SECURITY: 0 });
    assert.match(whyCannotAdd(options, [], 'SECURITY', 'z'), /reserve/);
    assert.equal(whyCannotAdd(options, [], 'MAINTENANCE', 'a'), '');
  });

  it('never selects more helpers than the department can use or than a plan allows', () => {
    const small = { ...options, maxHelpers: 1 };
    const one = supportReducer(start, { type: 'ADD_STAFF', options: small, donorCode: 'MAINTENANCE', staffId: 'a' });
    const two = supportReducer(one, { type: 'ADD_STAFF', options: small, donorCode: 'MAINTENANCE', staffId: 'b' });
    assert.equal(two.plan.length, 1);
    assert.match(two.notice, /uncovered tasks/);
    assert.ok(MAX_PLAN_SIZE >= 1);
  });

  it('"use suggestions" adds recommended people that fit and says when nothing more can be added', () => {
    const suggestions = [
      { staffId: 'a', homeDepartmentCode: 'MAINTENANCE' },
      { staffId: 'b', homeDepartmentCode: 'MAINTENANCE' },
      { staffId: 'c', homeDepartmentCode: 'MAINTENANCE' },
    ];
    const used = supportReducer(start, { type: 'USE_SUGGESTIONS', options, suggestions });
    assert.deepEqual(used.plan.map((p) => p.staffId), ['a', 'b'], 'the third would break the reserve');
    const again = supportReducer(used, { type: 'USE_SUGGESTIONS', options, suggestions });
    assert.match(again.notice, /Nothing more/);
  });
});

describe('preview, review and confirmation', () => {
  it('builds the request the API expects, with the fingerprint when there is one', () => {
    const state = add(start, 'a');
    assert.deepEqual(buildPlanRequest(state), { receivingDepartment: 'HOUSEKEEPING', items: [{ staffId: 'a', taskId: 't1' }] });
    assert.equal(buildPlanRequest(state, 'abc').fingerprint, 'abc');
  });

  it('ignores a preview that belongs to an older plan (stale responses)', () => {
    const state = add(start, 'a');
    const oldSignature = planSignature('HOUSEKEEPING', []);
    const stale = supportReducer(state, { type: 'PREVIEW_READY', signature: oldSignature, preview: { valid: true } });
    assert.equal(stale, state);
    assert.equal(supportReducer(state, { type: 'PREVIEW_FAILED', signature: oldSignature, message: 'x' }), state);
    assert.equal(readyPreview(state).preview.status, 'ready');
  });

  it('shows a failed preview as an error', () => {
    const state = add(start, 'a');
    const failed = supportReducer(state, { type: 'PREVIEW_FAILED', signature: planSignature('HOUSEKEEPING', state.plan), message: 'Server down' });
    assert.equal(failed.preview.status, 'error');
    assert.equal(failed.preview.error, 'Server down');
    assert.equal(canReview(failed), false);
  });

  it('review is possible only for a non-empty plan whose preview is valid', () => {
    const state = add(start, 'a');
    assert.equal(canReview(state), false, 'preview still loading');
    assert.equal(canReview(readyPreview(state, false)), false, 'preview found problems');
    assert.equal(canReview(readyPreview(state, true)), true);
    assert.equal(supportReducer(state, { type: 'OPEN_REVIEW' }), state, 'cannot open the dialog before the preview is ready');
  });

  it('confirmation needs the dialog: open, then confirm; cancelling changes nothing', () => {
    const ready = readyPreview(add(start, 'a'));
    const reviewing = supportReducer(ready, { type: 'OPEN_REVIEW' });
    assert.equal(reviewing.confirm.status, 'reviewing');
    const cancelled = supportReducer(reviewing, { type: 'CANCEL_REVIEW' });
    assert.equal(cancelled.confirm.status, 'idle');
    assert.deepEqual(cancelled.plan, ready.plan, 'the plan is kept');
    assert.equal(supportReducer(ready, { type: 'CONFIRM_START' }), ready, 'no confirmation without the dialog');
    assert.equal(supportReducer(reviewing, { type: 'CONFIRM_START' }).confirm.status, 'submitting');
  });

  it('cannot cancel while the request is in flight', () => {
    const submitting = supportReducer(supportReducer(readyPreview(add(start, 'a')), { type: 'OPEN_REVIEW' }), { type: 'CONFIRM_START' });
    assert.equal(supportReducer(submitting, { type: 'CANCEL_REVIEW' }), submitting);
  });

  it('a successful confirmation clears the plan and keeps the result for the success message', () => {
    const submitting = supportReducer(supportReducer(readyPreview(add(start, 'a')), { type: 'OPEN_REVIEW' }), { type: 'CONFIRM_START' });
    const done = supportReducer(submitting, { type: 'CONFIRM_SUCCEEDED', created: [{ id: '1', staffName: 'Ana' }] });
    assert.deepEqual(done.plan, []);
    assert.equal(done.preview.status, 'idle');
    assert.equal(done.confirm.status, 'idle');
    assert.equal(done.result.created.length, 1);
    assert.equal(supportReducer(done, { type: 'DISMISS_RESULT' }).result, null);
  });

  it('a failed confirmation keeps the plan, shows why, and recalculates the preview', () => {
    const submitting = supportReducer(supportReducer(readyPreview(add(start, 'a')), { type: 'OPEN_REVIEW' }), { type: 'CONFIRM_START' });
    const failed = supportReducer(submitting, {
      type: 'CONFIRM_FAILED', message: 'Ana already has an active task.', problems: [{ code: 'STAFF_BUSY', message: 'Ana already has an active task.' }, { code: 'X', message: 'Other' }],
    });
    assert.deepEqual(failed.plan, submitting.plan);
    assert.equal(failed.confirm.status, 'error');
    assert.equal(failed.confirm.stale, false);
    assert.equal(failed.preview.status, 'loading', 'recalculated against current data');
    assert.deepEqual(describeProblems(failed.confirm.problems), ['Ana already has an active task.', 'Other']);
    assert.equal(supportReducer(failed, { type: 'DISMISS_RESULT' }).confirm.status, 'idle');
  });

  it('flags an out-of-date plan so the message can say so', () => {
    const submitting = supportReducer(supportReducer(readyPreview(add(start, 'a')), { type: 'OPEN_REVIEW' }), { type: 'CONFIRM_START' });
    const stale = supportReducer(submitting, { type: 'CONFIRM_FAILED', message: 'out of date', problems: [{ code: 'STALE_PLAN', message: 'changed' }], stale: true });
    assert.equal(stale.confirm.stale, true);
  });
});

describe('labels and workload attribution', () => {
  it('maps support statuses to stages and end reasons to text', () => {
    assert.equal(stageOf('ACTIVE'), 'active');
    assert.equal(stageOf('COMPLETED'), 'completed');
    assert.equal(stageOf('CANCELLED'), 'cancelled');
    assert.equal(describeEndReason('TASK_COMPLETED'), 'Task completed');
    assert.equal(describeEndReason('TASK_REASSIGNED'), 'Task reassigned');
    assert.equal(describeEndReason('unknown'), '');
    assert.equal(beforeAfter(5, 3), '5 → 3');
    assert.equal(beforeAfter(2, 2), '2');
  });

  it('counts a supported person\'s minutes under the receiving department only, never twice', () => {
    const workload = [
      { staffId: 's1', department: 'MAINTENANCE', totalEstimatedDuration: 60, activeTaskCount: 1, supportDepartment: 'HOUSEKEEPING' },
      { staffId: 's2', department: 'MAINTENANCE', totalEstimatedDuration: 30, activeTaskCount: 1, supportDepartment: null },
      { staffId: 's3', department: 'HOUSEKEEPING', totalEstimatedDuration: 45, activeTaskCount: 1 },
    ];
    const departments = [{ code: 'MAINTENANCE', name: 'Maintenance', staffCount: 4 }, { code: 'HOUSEKEEPING', name: 'Housekeeping', staffCount: 2 }];

    const result = calculateDepartmentWorkloads(workload, departments);

    const get = (code) => result.find((d) => d.code === code);
    assert.equal(get('HOUSEKEEPING').totalMinutes, 105, '45 own + 60 supported');
    assert.equal(get('HOUSEKEEPING').activeTaskCount, 2);
    assert.equal(get('MAINTENANCE').totalMinutes, 30);
    assert.equal(get('MAINTENANCE').staffCount, 4, 'headcount still follows the home department');
    assert.equal(result.reduce((sum, d) => sum + d.totalMinutes, 0), 135, 'total equals the sum of every staff member once');
  });
});

describe('stale suggestions', () => {
  it('never adds someone who is no longer offered, even if an older suggestion names them', () => {
    const suggestions = [
      { staffId: 'gone', homeDepartmentCode: 'MAINTENANCE', staffName: 'Gone', homeDepartmentName: 'Maintenance' },
      { staffId: 'a', homeDepartmentCode: 'MAINTENANCE' },
    ];
    const used = supportReducer(start, { type: 'USE_SUGGESTIONS', options, suggestions });
    assert.deepEqual(used.plan.map((p) => p.staffId), ['a'], 'the person who is no longer offered is skipped');
    const direct = add(start, 'gone');
    assert.deepEqual(direct.plan, []);
    assert.match(direct.notice, /no longer available/);
    assert.match(whyCannotAdd(options, [], 'MAINTENANCE', 'gone'), /no longer available/);
  });
});
