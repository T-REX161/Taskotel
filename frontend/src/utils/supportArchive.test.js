import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  archiveReducer,
  canArchiveSupport,
  describeArchiveFailure,
  describeArchiveOutcome,
  initialArchiveState,
  isStaleArchive,
  supportHistoryView,
} from './supportPlan.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const read = (...parts) => fs.readFileSync(path.join(__dirname, '..', ...parts), 'utf8').replace(/\r\n/g, '\n');

// Records as an archive-capable backend sends them: archivedAt is present (null until removed)
const record = (id, status, extra = {}) => ({ id, status, staffName: 'Sofia Marino', taskTitle: 'Turnover Suite 801', archivedAt: null, ...extra });
const active = record('s1', 'ACTIVE');
const ended = record('s2', 'CANCELLED', { endReason: 'MANUALLY_ENDED', completedAt: '2026-10-27T12:00:00' });
const completed = record('s3', 'COMPLETED', { endReason: 'TASK_COMPLETED' });
const run = (...actions) => actions.reduce((state, action) => archiveReducer(state, action), initialArchiveState);

describe('which records offer the remove action', () => {
  it('ended and completed records do; active ones never do', () => {
    assert.equal(canArchiveSupport(ended), true);
    assert.equal(canArchiveSupport(completed), true);
    assert.equal(canArchiveSupport(active), false, 'active support must be ended first');
    assert.equal(canArchiveSupport(null), false);
  });

  it('an already archived record does not', () => {
    assert.equal(canArchiveSupport({ ...ended, archivedAt: '2026-10-27T13:00:00' }), false);
  });

  it('is not offered by a backend that does not report archiving, where it could not work', () => {
    const { archivedAt, ...older } = ended;
    assert.equal(canArchiveSupport(older), false);
  });

  it('cannot even be opened for an active record', () => {
    assert.deepEqual(run({ type: 'ARCHIVE_OPEN', row: active }), initialArchiveState);
  });
});

describe('confirmation and cancellation', () => {
  it('opens the confirmation for the chosen record without sending anything', () => {
    const state = run({ type: 'ARCHIVE_OPEN', row: ended });
    assert.equal(state.status, 'reviewing');
    assert.equal(state.row.id, 's2');
  });

  it('cancelling closes it and clears everything: nothing was requested', () => {
    assert.deepEqual(run({ type: 'ARCHIVE_OPEN', row: ended }, { type: 'ARCHIVE_CANCEL' }), initialArchiveState);
  });

  it('cannot be cancelled while the request is running', () => {
    const submitting = run({ type: 'ARCHIVE_OPEN', row: ended }, { type: 'ARCHIVE_START' });
    assert.equal(archiveReducer(submitting, { type: 'ARCHIVE_CANCEL' }).status, 'submitting');
  });

  it('a second record cannot be opened while one is being reviewed', () => {
    const state = run({ type: 'ARCHIVE_OPEN', row: ended });
    assert.equal(archiveReducer(state, { type: 'ARCHIVE_OPEN', row: completed }).row.id, 's2');
  });
});

describe('loading, success and error', () => {
  it('moves to submitting once, and a second confirm while submitting is ignored (no duplicate request)', () => {
    const submitting = run({ type: 'ARCHIVE_OPEN', row: ended }, { type: 'ARCHIVE_START' });
    assert.equal(submitting.status, 'submitting');
    assert.equal(archiveReducer(submitting, { type: 'ARCHIVE_START' }), submitting);
    assert.equal(run({ type: 'ARCHIVE_START' }).status, 'idle', 'cannot start without being asked');
  });

  it('success closes the dialog and keeps the record for the confirmation message', () => {
    const done = run({ type: 'ARCHIVE_OPEN', row: ended }, { type: 'ARCHIVE_START' }, { type: 'ARCHIVE_SUCCEEDED', row: { ...ended, archivedAt: '2026-10-27T13:00:00' } });
    assert.equal(done.status, 'done');
    assert.match(describeArchiveOutcome(done.row), /Removed from the history: Sofia Marino · Turnover Suite 801\. The record is archived, not deleted\./);
    assert.deepEqual(archiveReducer(done, { type: 'ARCHIVE_DISMISS' }), initialArchiveState);
  });

  it('a late response is ignored when nothing is being submitted', () => {
    assert.equal(run({ type: 'ARCHIVE_SUCCEEDED', row: ended }).status, 'idle');
    assert.equal(run({ type: 'ARCHIVE_FAILED', message: 'x' }).status, 'idle');
  });

  it('an error keeps the dialog open with the reason and allows trying again or closing', () => {
    const failed = run({ type: 'ARCHIVE_OPEN', row: ended }, { type: 'ARCHIVE_START' }, { type: 'ARCHIVE_FAILED', message: 'Server unreachable', httpStatus: 500 });
    assert.equal(failed.status, 'error');
    assert.equal(failed.error, 'Server unreachable');
    assert.equal(failed.stale, false);
    assert.equal(archiveReducer(failed, { type: 'ARCHIVE_START' }).status, 'submitting', 'Try again');
    assert.deepEqual(archiveReducer(failed, { type: 'ARCHIVE_CANCEL' }), initialArchiveState, 'Close');
  });

  it('a record that changed meanwhile (already removed, became active, gone) is reported as out of date, with no retry', () => {
    for (const code of ['ALREADY_ARCHIVED', 'SUPPORT_ACTIVE', 'SUPPORT_NOT_FOUND']) {
      const failed = run({ type: 'ARCHIVE_OPEN', row: ended }, { type: 'ARCHIVE_START' }, { type: 'ARCHIVE_FAILED', message: 'm', code, httpStatus: 409 });
      assert.equal(failed.stale, true, code);
      assert.equal(isStaleArchive(code), true);
    }
    assert.equal(isStaleArchive('SOMETHING_ELSE'), false);
  });

  it('a 404 without a problem code says the server has no such action and points at the restart', () => {
    const message = describeArchiveFailure('Not Found', '', 404);
    assert.match(message, /does not recognise this action/);
    assert.match(message, /restarted/);
    assert.equal(describeArchiveFailure('Server unreachable', '', 500), 'Server unreachable');
    assert.equal(describeArchiveFailure('', '', 500), 'The record could not be removed.');
  });
});

describe('the history follows the server after a removal', () => {
  it('when the last ended record is removed the history area disappears, because the server list no longer has it', () => {
    assert.equal(supportHistoryView({ status: 'ready', data: [ended], error: '' }), 'history');
    assert.equal(supportHistoryView({ status: 'ready', data: [], error: '' }), 'none');
  });

  it('with other records left the area stays', () => {
    assert.equal(supportHistoryView({ status: 'ready', data: [completed], error: '' }), 'history');
  });
});

describe('wiring', () => {
  const panel = read('components', 'workload', 'CrossDepartmentSupportPanel.jsx');
  const history = read('components', 'workload', 'support', 'SupportHistory.jsx');
  const dialog = read('components', 'workload', 'support', 'SupportArchiveDialog.jsx');
  const api = read('services', 'api.js');

  it('calls the real archive endpoint, not a local hide', () => {
    assert.ok(api.includes('`/api/support/assignments/${encodeURIComponent(supportId)}/archive`, { method: \'POST\' }'));
    assert.ok(panel.includes('await archiveSupportAssignment(row.id)'));
    assert.ok(!/setHistory\([^)]*filter/.test(panel), 'rows are never filtered out of the list locally');
    const handler = panel.slice(panel.indexOf('const handleArchive'), panel.indexOf('const canEnd'));
    assert.ok(handler.includes('loadHistory();') && !handler.includes('setHistory'), 'the list is reloaded from the server');
    assert.ok(handler.includes('if (archiveInFlight.current || !archiveState.row) return;'), 'double clicks send one request');
  });

  it('shows a trash action on ended rows only, for Admin and Manager, in the status cell next to the badge', () => {
    assert.ok(history.includes('canEnd && onArchive && canArchiveSupport(row)'));
    assert.ok(history.includes('aria-label={`Remove ${row.staffName} from history`}') && history.includes('title="Remove from history"'));
    assert.ok(history.includes('<path d="M3 6h18" />'), 'a trash icon');
    const statusCell = history.slice(history.indexOf('flex items-center gap-2 lg:justify-end'));
    assert.ok(statusCell.indexOf('<SupportStageBadge') < statusCell.indexOf('Remove ${row.staffName} from history'), 'beside the status badge');
    assert.ok(history.includes('w-8 h-8 shrink-0'), 'it keeps its size in the table layout');
  });

  it('both the main section and the history area offer it', () => {
    assert.equal(panel.split("onArchive={(row) => archiveDispatch({ type: 'ARCHIVE_OPEN', row })}").length - 1, 2);
  });

  it('the confirmation names the staff member, task and dates, says nothing is deleted, and has Cancel and Remove', () => {
    for (const text of ['Remove from history?', '{row.staffName}', '{row.taskTitle}', 'Started', 'Ended', 'formatWhen(row.createdAt)', 'formatWhen(row.completedAt)',
      'The record is archived, not deleted.', 'Cancel', 'Remove']) {
      assert.ok(dialog.includes(text), text);
    }
    assert.ok(dialog.includes("event.key === 'Escape' && !submitting") && dialog.includes('cancelRef.current?.focus()') && dialog.includes('role="dialog"'));
  });

  it('shows loading, success and error feedback', () => {
    assert.ok(dialog.includes("submitting ? 'Removing…' : status === 'error' ? 'Try again' : 'Remove'") && dialog.includes('role="alert"') && dialog.includes('The record was not removed.'));
    assert.ok(panel.includes("archiveState.status === 'done'") && panel.includes('describeArchiveOutcome(archiveState.row)'));
    assert.ok(history.includes("archivingId === row.id") && history.includes('disabled={archivingId === row.id}'));
  });

  it('is only reachable for Admin and Manager', () => {
    assert.ok(read('pages', 'WorkloadPage.jsx').includes("['ADMIN', 'MANAGER'].includes(getUserRole()) && <CrossDepartmentSupportPanel"));
    assert.ok(panel.includes("const canEnd = ['ADMIN', 'MANAGER'].includes(getUserRole());"));
  });
});
