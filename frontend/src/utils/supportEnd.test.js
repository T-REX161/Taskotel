import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import {
  canEndSupport,
  describeEndEffect,
  describeEndFailure,
  describeEndOutcome,
  describeEndReason,
  endReducer,
  initialEndState,
  isStaleEnd,
  stageOf,
} from './supportPlan.js';

const names = { HOUSEKEEPING: 'Housekeeping', FRONT_DESK: 'Front Desk' };
const departmentName = (code) => names[code] || code;
const active = {
  id: 's1', staffName: 'Sofia Marino', homeDepartmentCode: 'FRONT_DESK', receivingDepartmentCode: 'HOUSEKEEPING',
  taskTitle: 'Turn over suite 402', status: 'ACTIVE', taskStatus: 'ASSIGNED',
};
const ended = { ...active, status: 'CANCELLED', endReason: 'MANUALLY_ENDED', completedAt: '2026-10-27T12:00:00' };

const run = (...actions) => actions.reduce((state, action) => endReducer(state, action), initialEndState);

describe('the End Support action is only offered for active support', () => {
  it('is offered for an active record and not for completed, cancelled or missing ones', () => {
    assert.equal(canEndSupport(active), true);
    assert.equal(canEndSupport({ ...active, status: 'COMPLETED' }), false);
    assert.equal(canEndSupport({ ...active, status: 'CANCELLED' }), false);
    assert.equal(canEndSupport(ended), false);
    assert.equal(canEndSupport(null), false);
  });

  it('refuses to open the confirmation for a record that is not active', () => {
    assert.deepEqual(run({ type: 'END_OPEN', row: ended }), initialEndState);
  });
});

describe('confirmation and cancellation', () => {
  it('opens the confirmation for the chosen record without sending anything', () => {
    const state = run({ type: 'END_OPEN', row: active });
    assert.equal(state.status, 'reviewing');
    assert.equal(state.row.id, 's1');
  });

  it('cancelling closes it and clears everything', () => {
    assert.deepEqual(run({ type: 'END_OPEN', row: active }, { type: 'END_CANCEL' }), initialEndState);
  });

  it('cannot be cancelled while the request is running', () => {
    const submitting = run({ type: 'END_OPEN', row: active }, { type: 'END_START' });
    assert.equal(endReducer(submitting, { type: 'END_CANCEL' }).status, 'submitting');
  });

  it('a second record cannot be opened while one is being reviewed', () => {
    const state = run({ type: 'END_OPEN', row: active });
    assert.equal(endReducer(state, { type: 'END_OPEN', row: { ...active, id: 's2' } }).row.id, 's1');
  });
});

describe('loading, success and error', () => {
  it('moves to submitting when confirmed, and a second confirm while submitting is ignored (no duplicate request)', () => {
    const submitting = run({ type: 'END_OPEN', row: active }, { type: 'END_START' });
    assert.equal(submitting.status, 'submitting');
    assert.equal(endReducer(submitting, { type: 'END_START' }), submitting);
  });

  it('cannot start without first being reviewed', () => {
    assert.equal(run({ type: 'END_START' }).status, 'idle');
  });

  it('success closes the dialog and keeps the ended record for the confirmation message', () => {
    const done = run({ type: 'END_OPEN', row: active }, { type: 'END_START' }, { type: 'END_SUCCEEDED', row: ended });
    assert.equal(done.status, 'done');
    assert.equal(done.result.endReason, 'MANUALLY_ENDED');
    assert.deepEqual(endReducer(done, { type: 'END_DISMISS' }), initialEndState);
  });

  it('a late response is ignored when nothing is being submitted', () => {
    assert.equal(run({ type: 'END_SUCCEEDED', row: ended }).status, 'idle');
    assert.equal(run({ type: 'END_FAILED', message: 'x' }).status, 'idle');
  });

  it('an error keeps the dialog open with the reason, and allows trying again', () => {
    const failed = run({ type: 'END_OPEN', row: active }, { type: 'END_START' }, { type: 'END_FAILED', message: 'Server unreachable', httpStatus: 500 });
    assert.equal(failed.status, 'error');
    assert.equal(failed.error, 'Server unreachable');
    assert.equal(failed.stale, false, 'a network or server error is retryable');
    assert.equal(endReducer(failed, { type: 'END_START' }).status, 'submitting', 'Try again');
    assert.deepEqual(endReducer(failed, { type: 'END_CANCEL' }), initialEndState, 'Close');
  });

  it('a 404 without a problem code means the server has no such route: it says so and points at the restart', () => {
    const failed = run({ type: 'END_OPEN', row: active }, { type: 'END_START' }, { type: 'END_FAILED', message: 'Not Found', httpStatus: 404 });
    assert.match(failed.error, /does not recognise this action/);
    assert.match(failed.error, /POST \/api\/support\/assignments\/\{id\}\/end/);
    assert.match(failed.error, /restarted/);
    assert.equal(failed.stale, false, 'not a stale record, so the list is not treated as out of date');
    assert.equal(describeEndFailure('Server unreachable', '', 500), 'Server unreachable');
  });

  it('a 404 with SUPPORT_NOT_FOUND is a record that no longer exists: stale, with the server message', () => {
    const failed = run({ type: 'END_OPEN', row: active }, { type: 'END_START' }, { type: 'END_FAILED', message: 'This support record no longer exists.', code: 'SUPPORT_NOT_FOUND', httpStatus: 404 });
    assert.equal(failed.error, 'This support record no longer exists.');
    assert.equal(failed.stale, true);
  });

  it('a database that is not upgraded (SCHEMA_OUT_OF_DATE) shows the server message and offers no retry', () => {
    const message = 'The database has not been upgraded for ending support (migration V6). Nothing was changed.';
    const failed = run({ type: 'END_OPEN', row: active }, { type: 'END_START' }, { type: 'END_FAILED', message, code: 'SCHEMA_OUT_OF_DATE', httpStatus: 500 });
    assert.equal(failed.error, message);
    assert.equal(failed.stale, true, 'no Try again: it cannot succeed until V6 is applied');
  });

  it('a stale or concurrent change is reported as out of date, not retryable', () => {
    for (const [code, httpStatus] of [['ALREADY_ENDED', 409], ['CONCURRENT_CHANGE', 409], ['TASK_IN_PROGRESS', 409], ['SUPPORT_NOT_FOUND', 404]]) {
      const failed = run({ type: 'END_OPEN', row: active }, { type: 'END_START' }, { type: 'END_FAILED', message: 'm', code, httpStatus });
      assert.equal(failed.stale, true, `${code || httpStatus}`);
      assert.equal(isStaleEnd(code, httpStatus), true);
    }
    assert.equal(isStaleEnd('', 500), false);
  });

  it('falls back to a plain message when the error has none', () => {
    const failed = run({ type: 'END_OPEN', row: active }, { type: 'END_START' }, { type: 'END_FAILED' });
    assert.equal(failed.error, 'Support could not be ended.');
  });
});

describe('what the dialog says', () => {
  it('explains, in short points, only what the server does: the task returns to the waiting list and the person stops holding it', () => {
    const effect = describeEndEffect(active, departmentName);
    assert.equal(effect.blocked, false);
    assert.equal(effect.points.length, 3);
    assert.match(effect.text, /not completed or deleted/);
    assert.match(effect.text, /returns to Housekeeping's waiting list, unassigned/);
    assert.match(effect.text, /Sofia Marino no longer holds this task and stays in Front Desk/);
    assert.match(effect.text, /stays in the history/);
    assert.doesNotMatch(effect.text, /free again|available/i, 'availability is not changed by ending support, so it is not promised');
  });

  it('refuses a task that is already in progress and says what to do instead', () => {
    const effect = describeEndEffect({ ...active, taskStatus: 'IN_PROGRESS' }, departmentName);
    assert.equal(effect.blocked, true);
    assert.deepEqual(effect.points, []);
    assert.match(effect.text, /already in progress/);
    assert.match(effect.text, /Complete the task or reassign it/);
  });

  it('words the outcome for a manual end and for a support that had already finished', () => {
    assert.match(describeEndOutcome(ended, departmentName), /Sofia Marino is back in Front Desk, and "Turn over suite 402" is waiting again in Housekeeping/);
    const finished = { ...ended, status: 'COMPLETED', endReason: 'TASK_COMPLETED' };
    assert.match(describeEndOutcome(finished, departmentName), /had already finished \(task completed\)/);
  });

  it('names the new end reason and shows an ended record as ended', () => {
    assert.equal(describeEndReason('MANUALLY_ENDED'), 'Ended by a manager');
    assert.equal(stageOf('CANCELLED'), 'cancelled');
  });
});
