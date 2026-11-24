import { afterEach, beforeEach, describe, it } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { loadModule, renderHtml } from '../../testSupport/renderJsx.js';
import { claimTask, getAvailableTasks } from '../../services/api.js';
import {
  AVAILABLE_LOAD_ERROR,
  CLAIM_CONFLICT_MESSAGE,
  CLAIM_FALLBACK_ERROR,
  availableViewState,
  claimFailure,
  claimSuccessMessage,
  normalizeAvailable,
  performClaim,
} from '../../utils/taskClaim.js';
import { navigationFor } from '../../utils/accessPolicy.js';

const here = path.dirname(fileURLToPath(import.meta.url));
const read = (...p) => fs.readFileSync(path.join(here, ...p), 'utf8').replace(/\r\n/g, '\n');

const TASKS = [
  { id: 't1', title: 'Deep clean 402', description: 'Strip the bed and vacuum', priority: 'HIGH', department: 'HOUSEKEEPING', departmentName: 'Housekeeping',
    estimatedDuration: 45, dueDate: '2027-01-15', deadlineTime: '17:30:00', createdAt: '2026-11-20T09:15:00', status: 'PENDING' },
  { id: 't2', title: 'Restock minibar', description: null, priority: 'LOW', department: 'HOUSEKEEPING', departmentName: 'Housekeeping',
    estimatedDuration: 20, dueDate: null, deadlineTime: null, createdAt: '2026-11-21T10:00:00', status: 'PENDING' },
];

async function render(props) {
  const { default: Section } = await loadModule('components/tasks/AvailableTasksSection.jsx');
  return renderHtml(Section, props);
}

describe('Available Tasks: rendering', () => {
  it('shows each task with title, description, priority, department, duration and due and created information', async () => {
    const html = await render({ tasks: TASKS });
    assert.match(html, /Available Tasks/);
    assert.equal((html.match(/data-testid="available-task"/g) || []).length, 2);
    for (const text of ['Deep clean 402', 'Strip the bed and vacuum', 'High', 'Housekeeping', '45 min', 'Restock minibar', 'Low', '20 min', 'Due', 'Created']) {
      assert.ok(html.includes(text), `${text} should be shown`);
    }
    assert.match(html, /data-testid="available-count"[^>]*>\s*2\s*</);
  });

  it('offers an Accept Task button for every task, named after the task', async () => {
    const html = await render({ tasks: TASKS });
    assert.equal((html.match(/>Accept Task</g) || []).length, 2);
    assert.match(html, /aria-label="Accept task Deep clean 402"/);
    assert.doesNotMatch(html, /disabled=""/);
  });

  it('while one task is being accepted its button shows progress and every button is disabled (no double claim)', async () => {
    const html = await render({ tasks: TASKS, claimingId: 't1' });
    assert.match(html, />Accepting\.\.\.</);
    assert.equal((html.match(/>Accept Task</g) || []).length, 1);
    assert.equal((html.match(/disabled=""/g) || []).length, 2);
  });

  it('shows a loading state and no tasks while loading', async () => {
    const html = await render({ loading: true, tasks: [] });
    assert.match(html, /role="status"/);
    assert.match(html, /Loading available tasks/);
    assert.doesNotMatch(html, /Accept Task/);
  });

  it('shows a load error with Retry instead of an empty state (a failure never looks like "no tasks")', async () => {
    const html = await render({ error: AVAILABLE_LOAD_ERROR, tasks: [], onRetry: () => {} });
    assert.match(html, /role="alert"/);
    assert.ok(html.includes(AVAILABLE_LOAD_ERROR.replace(/'/g, '&#x27;')));
    assert.match(html, />Retry</);
    assert.doesNotMatch(html, /No tasks are available/);
  });

  it('shows the empty state when the server answered with no tasks', async () => {
    const html = await render({ tasks: [] });
    assert.match(html, /data-testid="available-empty"/);
    assert.match(html, /No tasks are available right now/);
    assert.doesNotMatch(html, /Accept Task/);
  });

  it('explains why nothing can be accepted when the server says the person cannot claim (and offers no button)', async () => {
    const html = await render({ canClaim: false, reason: 'You already have an active task. Finish it before accepting another.', tasks: [] });
    assert.match(html, /data-testid="available-blocked"/);
    assert.match(html, /You already have an active task/);
    assert.doesNotMatch(html, /Accept Task/);
  });

  it('shows the conflict notice when another staff member was first, and a distinct error notice for other failures', async () => {
    const conflict = await render({ tasks: TASKS, notice: { kind: 'conflict', message: CLAIM_CONFLICT_MESSAGE }, onDismissNotice: () => {} });
    assert.match(conflict, /data-testid="claim-notice"/);
    assert.match(conflict, /just accepted by another staff member/);
    assert.match(conflict, /bg-amber-50/);
    const failure = await render({ tasks: TASKS, notice: { kind: 'error', message: CLAIM_FALLBACK_ERROR } });
    assert.match(failure, /bg-red-50/);
  });
});

describe('Available Tasks: the claim flow', () => {
  it('a successful claim confirms, and refreshes the lists afterwards', async () => {
    const calls = [];
    const outcome = await performClaim(TASKS[0], {
      claim: async (id) => { calls.push(['claim', id]); },
      refresh: async () => { calls.push(['refresh']); },
    });
    assert.deepEqual(calls, [['claim', 't1'], ['refresh']]);
    assert.deepEqual(outcome, { ok: true, message: claimSuccessMessage('Deep clean 402') });
    assert.match(outcome.message, /You accepted "Deep clean 402"/);
  });

  it('losing the race (409) is a conflict with the server\'s message, and the list is still refreshed', async () => {
    let refreshed = 0;
    const lost = Object.assign(new Error('This task is no longer available. Another staff member may have accepted it first.'), { status: 409 });
    const outcome = await performClaim(TASKS[0], { claim: async () => { throw lost; }, refresh: async () => { refreshed += 1; } });
    assert.equal(outcome.ok, false);
    assert.equal(outcome.kind, 'conflict');
    assert.match(outcome.message, /Another staff member may have accepted it first/);
    assert.equal(refreshed, 1);
  });

  it('a 404, any other failure and a failed refresh are each handled without losing the claim result', async () => {
    const gone = await performClaim(TASKS[0], { claim: async () => { throw Object.assign(new Error('Task not found'), { status: 404 }); }, refresh: async () => {} });
    assert.equal(gone.kind, 'gone');
    const broken = await performClaim(TASKS[0], { claim: async () => { throw new Error(''); }, refresh: async () => {} });
    assert.deepEqual(broken, { ok: false, kind: 'error', message: CLAIM_FALLBACK_ERROR });
    const refreshFails = await performClaim(TASKS[0], { claim: async () => {}, refresh: async () => { throw new Error('offline'); } });
    assert.equal(refreshFails.ok, true);
  });

  it('a session redirect refreshes nothing and shows nothing', async () => {
    let refreshed = 0;
    const outcome = await performClaim(TASKS[0], {
      claim: async () => { throw new Error('Session expired or unauthorized. Redirecting to login...'); },
      refresh: async () => { refreshed += 1; },
    });
    assert.deepEqual(outcome, { redirect: true });
    assert.equal(refreshed, 0);
  });

  it('claimFailure: 409 keeps the server message (or a fallback), 404 and the rest map as documented', () => {
    assert.deepEqual(claimFailure({ status: 409, message: 'You already have an active task.' }), { kind: 'conflict', message: 'You already have an active task.' });
    assert.equal(claimFailure({ status: 409 }).message, CLAIM_CONFLICT_MESSAGE);
    assert.equal(claimFailure({ status: 404 }).kind, 'gone');
    assert.equal(claimFailure({ status: 500, message: 'Boom' }).kind, 'error');
    assert.equal(claimFailure(null).message, CLAIM_FALLBACK_ERROR);
  });

  it('normalizeAvailable never turns a malformed answer into tasks; availableViewState orders loading, error, blocked, empty, ready', () => {
    assert.deepEqual(normalizeAvailable(null), { canClaim: true, reason: '', tasks: [] });
    assert.deepEqual(normalizeAvailable({ canClaim: false, reason: 'x', tasks: [] }), { canClaim: false, reason: 'x', tasks: [] });
    assert.equal(normalizeAvailable({ canClaim: true, tasks: [null, {}, TASKS[0]] }).tasks.length, 1);
    assert.equal(availableViewState({ loading: true, error: 'e' }), 'loading');
    assert.equal(availableViewState({ error: 'e', count: 3 }), 'error');
    assert.equal(availableViewState({ canClaim: false }), 'blocked');
    assert.equal(availableViewState({ count: 0 }), 'empty');
    assert.equal(availableViewState({ count: 1 }), 'ready');
  });
});

describe('Available Tasks: the requests', () => {
  const realFetch = globalThis.fetch;
  const realStorage = Object.getOwnPropertyDescriptor(globalThis, 'localStorage');
  const realWindow = Object.getOwnPropertyDescriptor(globalThis, 'window');
  let calls;

  beforeEach(() => {
    calls = [];
    const store = { token: 'jwt-token', username: 'rahul', role: 'STAFF' };
    Object.defineProperty(globalThis, 'localStorage', {
      configurable: true,
      writable: true,
      value: { getItem: (k) => (k in store ? store[k] : null), setItem: (k, v) => { store[k] = String(v); }, removeItem: (k) => { delete store[k]; } },
    });
    Object.defineProperty(globalThis, 'window', { configurable: true, writable: true, value: { location: { href: '/tasks' } } });
  });

  afterEach(() => {
    globalThis.fetch = realFetch;
    if (realStorage) Object.defineProperty(globalThis, 'localStorage', realStorage); else delete globalThis.localStorage;
    if (realWindow) Object.defineProperty(globalThis, 'window', realWindow); else delete globalThis.window;
  });

  const answer = (status, body) => {
    globalThis.fetch = async (url, options) => {
      calls.push({ url, options });
      return { ok: status >= 200 && status < 300, status, json: async () => body };
    };
  };

  it('Accept Task is one POST to /api/tasks/{id}/claim with the token and no body', async () => {
    answer(200, { id: 't1', status: 'ASSIGNED' });
    const task = await claimTask('t1');
    assert.equal(task.status, 'ASSIGNED');
    assert.equal(calls.length, 1);
    assert.equal(calls[0].url, '/api/tasks/t1/claim');
    assert.equal(calls[0].options.method, 'POST');
    assert.equal(calls[0].options.body, undefined);
    assert.equal(calls[0].options.headers.Authorization, 'Bearer jwt-token');
  });

  it('a lost race is an error carrying status 409 and the server\'s message', async () => {
    answer(409, { message: 'This task is no longer available. Another staff member may have accepted it first.' });
    await assert.rejects(claimTask('t1'), (e) => e.status === 409 && /no longer available/.test(e.message));
  });

  it('the list is one GET to /api/tasks/available', async () => {
    answer(200, { canClaim: true, reason: null, tasks: TASKS });
    const data = await getAvailableTasks();
    assert.equal(calls[0].url, '/api/tasks/available');
    assert.equal(calls[0].options?.method, undefined);
    assert.equal(data.tasks.length, 2);
  });
});

describe('Available Tasks: the My Tasks page', () => {
  const page = read('..', '..', 'pages', 'TasksPage.jsx');

  it('shows the section to Staff only and loads it with the page', () => {
    assert.match(page, /\{isStaff && \(\s*<AvailableTasksSection/);
    assert.match(page, /if \(isStaff\) fetchAvailableTasks\(\);/);
  });

  it('a claim refreshes both My Tasks and Available Tasks (so My Workload and the dashboard are right on the next visit too)', () => {
    assert.match(page, /performClaim\(task, \{\s*claim: claimTask,\s*refresh: \(\) => Promise\.all\(\[fetchTasksData\(false\), fetchAvailableTasks\(false\)\]\)/);
  });

  it('Staff keep their navigation: Available Tasks lives on My Tasks, no new route or link is needed', () => {
    const items = navigationFor('STAFF').flatMap((group) => group.items.map((item) => item.path));
    assert.deepEqual(items, ['/dashboard', '/tasks', '/workload', '/profile', '/settings']);
  });
});

describe('Available Tasks: tabs inside the tasks card (Staff)', () => {
  const page = read('..', '..', 'pages', 'TasksPage.jsx');

  it('Staff get My Tasks and Available tabs; both panels stay mounted and one is hidden', () => {
    assert.match(page, /role="tablist"/);
    assert.match(page, /\{ id: 'mine', label: 'My Tasks'/);
    assert.match(page, /\{ id: 'available', label: 'Available'/);
    assert.match(page, /embedded\s+hidden=\{activeTab !== 'available'\}/);
    assert.match(page, /hidden=\{isStaff && activeTab !== 'mine'\}/);
  });

  it('the tabs are for Staff only: Admin and Manager get the plain table', () => {
    assert.match(page, /\{isStaff && \(\s*<div role="tablist"/);
  });

  it('an embedded section has no card chrome and no heading of its own, and can be hidden', async () => {
    const html = await render({ tasks: TASKS, embedded: true, hidden: true });
    assert.match(html, /<section[^>]*\shidden=""/);
    assert.doesNotMatch(html, /shadow-sm/);
    assert.doesNotMatch(html, /data-testid="available-count"/);
    assert.match(html, /class="sr-only"[^>]*>Available Tasks</);
    assert.equal((html.match(/>Accept Task</g) || []).length, 2);
  });
});

describe('Available Tasks: a busy member still sees the open tasks', () => {
  it('keeps the tasks the server sent when canClaim is false, and the view is the list, not the blocked message', () => {
    const answer = normalizeAvailable({ canClaim: false, reason: 'You already have an active task.', tasks: TASKS });
    assert.equal(answer.canClaim, false);
    assert.equal(answer.tasks.length, 2);
    assert.equal(availableViewState({ canClaim: false, count: 2 }), 'ready');
    assert.equal(availableViewState({ canClaim: false, count: 0 }), 'blocked');
  });

  it('shows the reason and the tasks, but every Accept Task button is disabled', async () => {
    const html = await render({ tasks: TASKS, canClaim: false, reason: 'You already have an active task. Finish it before accepting another.' });
    assert.match(html, /data-testid="available-busy"[^>]*>You already have an active task/);
    assert.equal((html.match(/data-testid="available-task"/g) || []).length, 2);
    assert.equal((html.match(/disabled=""/g) || []).length, 2);
  });
});
