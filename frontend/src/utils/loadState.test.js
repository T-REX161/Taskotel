import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import {
  STAFF_LOAD_ERROR_MESSAGE, SUPPORTING_SOURCES, WORKLOAD_LOAD_ERROR_MESSAGE, createLatestRequest, isRedirectError, listState,
  settleSupportingData, sourcesIn, supportingDataNotice,
} from './loadState.js';

// Stage 5A of the loading-state plan: a failed staff request is never shown as a successful, empty staff list.
const read = (path) => readFileSync(new URL(`../${path}`, import.meta.url), 'utf8').replace(/\r\n/g, '\n');

// ── the four states ──────────────────────────────────────────────────────────────────────────────────────────────

describe('listState: loading, error, successful empty, successful data', () => {
  it('each state on its own', () => {
    assert.equal(listState({ loading: true, error: '', count: 0 }), 'loading');
    assert.equal(listState({ loading: false, error: STAFF_LOAD_ERROR_MESSAGE, count: 0 }), 'error');
    assert.equal(listState({ loading: false, error: '', count: 0 }), 'empty');
    assert.equal(listState({ loading: false, error: '', count: 3 }), 'ready');
  });

  it('a failure is never "empty", even with no rows', () => {
    assert.notEqual(listState({ loading: false, error: 'boom', count: 0 }), 'empty');
  });

  it('a retry under way shows loading, not the previous error; an error wins over rows kept from an earlier load', () => {
    assert.equal(listState({ loading: true, error: STAFF_LOAD_ERROR_MESSAGE, count: 0 }), 'loading');
    assert.equal(listState({ loading: false, error: STAFF_LOAD_ERROR_MESSAGE, count: 5 }), 'error');
  });

  it('defaults are a successful empty answer', () => {
    assert.equal(listState({}), 'empty');
  });
});

// ── stale answers ────────────────────────────────────────────────────────────────────────────────────────────────

describe('createLatestRequest: only the newest request may update the page', () => {
  it('an earlier answer that arrives after a retry started is dropped', () => {
    const requests = createLatestRequest();
    const first = requests.next();
    const retry = requests.next();
    assert.equal(requests.isCurrent(first), false);
    assert.equal(requests.isCurrent(retry), true);
  });

  it('invalidate drops everything in flight (the page or form went away)', () => {
    const requests = createLatestRequest();
    const pending = requests.next();
    requests.invalidate();
    assert.equal(requests.isCurrent(pending), false);
    assert.equal(requests.isCurrent(requests.next()), true, 'new requests still work afterwards');
  });

  it('a scripted load → fail → retry → succeed sequence ends in the right state', () => {
    const requests = createLatestRequest();
    let state = { loading: false, error: '', staff: [] };
    const start = () => { state = { ...state, loading: true, error: '' }; return requests.next(); };
    const fail = (token) => { if (requests.isCurrent(token)) state = { ...state, loading: false, error: STAFF_LOAD_ERROR_MESSAGE }; };
    const succeed = (token, staff) => { if (requests.isCurrent(token)) state = { loading: false, error: '', staff }; };
    const view = () => listState({ loading: state.loading, error: state.error, count: state.staff.length });

    const first = start();
    assert.equal(view(), 'loading');
    fail(first);
    assert.equal(view(), 'error', 'the failure is reported, not shown as "no staff"');
    const retry = start();
    assert.equal(view(), 'loading', 'retry clears the error and shows loading again');
    succeed(retry, [{ id: 's1', name: 'Rahul' }]);
    assert.equal(view(), 'ready');
    assert.deepEqual(state.staff, [{ id: 's1', name: 'Rahul' }]);

    // two quick requests: the slow first one answers last and is ignored
    const slow = start();
    const fast = start();
    succeed(fast, []);
    fail(slow);
    assert.equal(view(), 'empty', 'the newest answer (successful, nobody available) stands');
  });
});

// ── Assignments supporting data ──────────────────────────────────────────────────────────────────────────────────

describe('settleSupportingData: each source is ready or failed, a failure never becomes an empty list', () => {
  it('answers become lists; failures are marked and get no value (the page keeps what it had)', () => {
    const out = settleSupportingData(['tasks', 'workload', 'staff'], [
      { status: 'fulfilled', value: [{ id: 't1' }] },
      { status: 'rejected', reason: new Error('Network error') },
      { status: 'fulfilled', value: null },
    ]);
    assert.deepEqual(out.values, { tasks: [{ id: 't1' }], staff: [] }, 'a non-array answer counts as empty, as before');
    assert.equal('workload' in out.values, false, 'no [] for the failed source');
    assert.deepEqual(out.status, { tasks: 'ready', workload: 'error', staff: 'ready' });
    assert.equal(out.redirect, false);
  });

  it('a retry of some sources only reports those', () => {
    const out = settleSupportingData(['staff'], [{ status: 'fulfilled', value: [{ id: 's1' }] }]);
    assert.deepEqual(out, { values: { staff: [{ id: 's1' }] }, status: { staff: 'ready' }, redirect: false });
  });

  it('the sign-in redirect is recognised', () => {
    const out = settleSupportingData(['tasks'], [{ status: 'rejected', reason: new Error('Session expired or unauthorized. Redirecting to login...') }]);
    assert.equal(out.redirect, true);
    assert.equal(isRedirectError(new Error('Network error')), false);
  });

  it('sourcesIn lists sources in a fixed order', () => {
    assert.deepEqual(SUPPORTING_SOURCES, ['tasks', 'workload', 'staff']);
    assert.deepEqual(sourcesIn({ staff: 'error', tasks: 'error', workload: 'ready' }, 'error'), ['tasks', 'staff']);
    assert.deepEqual(sourcesIn({ tasks: 'ready', workload: 'ready', staff: 'ready' }, 'error'), []);
  });

  it('the notice names what is missing, and is empty when nothing failed', () => {
    assert.equal(supportingDataNotice([]), '');
    assert.equal(supportingDataNotice(['staff']),
      'Staff members could not be loaded. Assignments are listed, but details that depend on it may be missing or incomplete.');
    assert.equal(supportingDataNotice(['tasks', 'workload', 'staff']),
      'Task details, workload and staff members could not be loaded. Assignments are listed, but details that depend on them may be missing or incomplete.');
    assert.match(WORKLOAD_LOAD_ERROR_MESSAGE, /could not be loaded/);
  });
});

// ── where it is used ─────────────────────────────────────────────────────────────────────────────────────────────

describe('Create Task: staff loading', () => {
  const page = read('pages/CreateTaskPage.jsx');
  const form = read('components/tasks/CreateTaskForm.jsx');

  it('a failed load is an error (not an empty list); only the newest request applies; leaving the page drops it', () => {
    assert.match(page, /\} catch \(err\) \{\s*if \(!formDataRequest\.isCurrent\(request\)\) return;\s*console\.error\('Failed to load staff and assignment data:', err\);\s*setStaffLoadError\(STAFF_LOAD_ERROR_MESSAGE\);/);
    assert.ok(page.includes('setIsLoadingStaff(true);\n    setStaffLoadError(\'\');'), 'a retry clears the error and shows loading');
    assert.ok(page.includes('if (!formDataRequest.isCurrent(request)) return;\n      setAllStaff('), 'a stale answer is ignored');
    assert.ok(page.includes('return formDataRequest.invalidate;'));
    assert.ok(page.includes('if (staffLoadError) return [];'), 'nobody is offered while availability is unknown');
  });

  it('the form shows each state, and "No available staff" only after a successful empty answer', () => {
    assert.ok(form.includes('const staffState = listState({ loading: isLoadingStaff, error: staffLoadError, count: staffList.length });'));
    assert.equal((form.match(/No available staff in this department/g) || []).length, 2);
    for (const block of form.split('No available staff in this department').slice(0, 2)) {
      assert.match(block.slice(-160), /staffState === 'empty' &&/, 'both the option and the hint are for the empty state only');
    }
    assert.match(form, /\{staffState === 'error' && \(\s*<div role="alert"[\s\S]*?\{staffLoadError\}[\s\S]*?onClick=\{onRetryStaff\}[\s\S]*?Retry/);
    assert.ok(form.includes("{staffState === 'ready' && staffList.map((staff) => ("), 'staff are listed only after a successful answer');
    assert.ok(form.includes('{staffState === \'loading\' && (\n              <p role="status"'));
  });

  it('the Manager department gate is unchanged', () => {
    assert.ok(page.includes('const formDataNeeded = createTaskFormDataNeeded(departmentAccess);'));
    assert.ok(page.includes("{(departmentAccess === 'select' || departmentAccess === 'locked') && ("));
    assert.ok(page.includes('if (isManager && departmentAccess !== \'locked\') {'));
  });
});

describe('Tasks: eligible staff in the assign dialog', () => {
  const page = read('pages/TasksPage.jsx');
  const modal = read('components/tasks/AssignTaskModal.jsx');

  it('the page keeps the failure, ignores stale answers and passes a retry', () => {
    assert.match(page, /const fetchEligibleStaff = async \(\) => \{\s*const request = staffRequest\.next\(\);\s*setIsLoadingStaff\(true\);\s*setStaffLoadError\(''\);/);
    assert.ok(page.includes("console.error('Failed to load eligible staff members:', err);\n      setStaffLoadError(STAFF_LOAD_ERROR_MESSAGE);"));
    assert.ok(page.includes('staffError={staffLoadError}') && page.includes('onRetryStaff={fetchEligibleStaff}'));
  });

  it('the dialog shows the error with a Retry, and never the empty state, the cards or a "0 eligible" count for a failure', () => {
    // Stage 5C: the shared LoadError (a role="alert" banner whose button reads "Retry"; see components/common/loadingPrimitives.test.js)
    assert.match(modal, /\{!isLoadingStaff && staffError && \(\s*<LoadError variant="banner" compact message=\{staffError\} onRetry=\{onRetryStaff\} retryDisabled=\{isSubmitting\} \/>/);
    assert.ok(modal.includes('{!isLoadingStaff && !staffError && task && selectableStaffCount === 0 && ('));
    assert.ok(modal.includes('{!isLoadingStaff && !staffError && eligibleStaff.length > 0 && ('));
    assert.match(modal, /\{!isLoadingStaff && !staffError && \(\s*<span[^>]*>\s*\{selectableStaffCount\}/);
    assert.ok(modal.includes("staffError = '',") && modal.includes('onRetryStaff,'));
  });

  it('assignment, reassignment and eligibility logic are unchanged', () => {
    assert.ok(page.includes('const staffIds = submittableStaffIds(selectedStaffIds, eligibleStaff, assignedIds);'));
    assert.match(page, /filterEligibleStaff\(\{\s*allStaff,\s*tasks,\s*assignments,\s*department: assignModalTask\.department,/);
    assert.ok(modal.includes('const selectableStaffCount = eligibleStaff.filter((staff) => !isOnThisTask(staff.id)).length;'));
  });
});

describe('Assignments: supporting data', () => {
  const page = read('pages/AssignmentsPage.jsx');

  it('no failure is turned into an empty list any more', () => {
    assert.doesNotMatch(page, /\.catch\(\(err\) => \{\s*console\.warn\([^)]*\);\s*return \[\];/);
    assert.ok(page.includes('await Promise.allSettled(sources.map((source) => loaders[source]()))'));
    assert.ok(page.includes("if (result.status[source] === 'ready') setters[source](result.values[source]);"), 'only a successful answer replaces data');
  });

  it('each source is tracked separately, and only its newest request applies', () => {
    assert.ok(page.includes("staff: canManageAssignments ? 'loading' : 'ready'"));
    assert.ok(page.includes('const tokens = sources.map((source) => supportRequests[source].next());'));
    assert.ok(page.includes('const current = sources.filter((source, index) => supportRequests[source].isCurrent(tokens[index]));'));
    assert.ok(page.includes("loadSupportingData(canManageAssignments ? SUPPORTING_SOURCES : ['tasks', 'workload'])"), 'Staff never request the staff list');
  });

  it('a non-blocking notice with a Retry for just what failed, disabled while a retry runs', () => {
    assert.ok(page.includes('{supportingDataNotice(failedSupport)}') || page.includes('supportingDataNotice(failedSupport)'));
    assert.ok(page.includes('onClick={() => retrySupportingData(failedSupport)}') && page.includes('disabled={loadingSupport.length > 0}'));
    assert.equal((page.match(/\{supportNotice\}/g) || []).length, 2, 'on the list and in the assign view (where failed tasks would look like "no unassigned tasks")');
  });

  it('the assign dialog gets the staff and workload state, with a staff retry', () => {
    assert.ok(page.includes("isLoadingStaff={supportStatus.staff === 'loading'}"));
    assert.ok(page.includes("staffError={supportStatus.staff === 'error' ? STAFF_LOAD_ERROR_MESSAGE : ''}"));
    assert.ok(page.includes("onRetryStaff={() => retrySupportingData(['staff'])}"));
    assert.ok(page.includes("workloadError={supportStatus.workload === 'error' ? WORKLOAD_LOAD_ERROR_MESSAGE : ''}"));
  });

  it('the list, its calculations and the reassignment rules are unchanged', () => {
    for (const line of [
      'filterAssignments(assignments, filters, { tasks, workload })',
      'calculateAssignmentSummary({ assignments, tasks })',
      '{canManageAssignments && isAssignmentTaskFinished(shownMenu.assignment, tasks) && (',
      "setError(err.message || 'Unable to load assignments data. Please try again.');",
    ]) assert.ok(page.includes(line), line);
  });
});
