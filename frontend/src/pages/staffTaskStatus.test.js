import { afterEach, beforeEach, describe, it } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { updateMyTaskStatus } from '../services/api.js';
import { staffStatusAction, staffStatusSuccessMessage } from '../utils/taskStatus.js';

const here = path.dirname(fileURLToPath(import.meta.url));
const read = (...p) => fs.readFileSync(path.join(here, ...p), 'utf8').replace(/\r\n/g, '\n');

describe('which action Staff are offered', () => {
  it('Assigned tasks can be started, In Progress tasks can be completed', () => {
    assert.deepEqual({ ...staffStatusAction('ASSIGNED') }, { status: 'IN_PROGRESS', label: 'Start task', busyLabel: 'Starting...' });
    assert.deepEqual({ ...staffStatusAction('IN_PROGRESS') }, { status: 'COMPLETED', label: 'Mark as completed', busyLabel: 'Completing...' });
  });

  it('Pending, Completed, Cancelled and anything unknown have no action (no cancel, no going back, no reopening)', () => {
    for (const status of ['PENDING', 'COMPLETED', 'CANCELLED', 'assigned', '', null, undefined, 42, {}, '__proto__', 'toString']) {
      assert.equal(staffStatusAction(status), null, String(status));
    }
  });

  it('the only status ever offered is the next step, never Pending, Assigned or Cancelled', () => {
    const offered = ['ASSIGNED', 'IN_PROGRESS', 'PENDING', 'COMPLETED', 'CANCELLED'].map((s) => staffStatusAction(s)?.status).filter(Boolean);
    assert.deepEqual(offered, ['IN_PROGRESS', 'COMPLETED']);
  });

  it('the success messages name the task and the step', () => {
    assert.equal(staffStatusSuccessMessage('Room Cleaning', 'IN_PROGRESS'), '"Room Cleaning" was started.');
    assert.equal(staffStatusSuccessMessage('Room Cleaning', 'COMPLETED'), '"Room Cleaning" was marked as completed.');
    assert.equal(staffStatusSuccessMessage('', 'COMPLETED'), 'The task was marked as completed.');
  });
});

describe('the status request', () => {
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

  it('is one PATCH to /api/tasks/{id}/status with the token and a body holding only the status', async () => {
    answer(200, { id: 't1', status: 'IN_PROGRESS' });
    const result = await updateMyTaskStatus('t1', 'IN_PROGRESS');
    assert.equal(result.status, 'IN_PROGRESS');
    assert.equal(calls.length, 1);
    assert.equal(calls[0].url, '/api/tasks/t1/status');
    assert.equal(calls[0].options.method, 'PATCH');
    assert.deepEqual(JSON.parse(calls[0].options.body), { status: 'IN_PROGRESS' });
    assert.equal(calls[0].options.headers.Authorization, 'Bearer jwt-token');
  });

  it('the server\'s reason is the error shown: 409 (not allowed now) and 404 (not your task) keep their message and status', async () => {
    answer(409, { message: 'A task that is Assigned can only be moved to In Progress, not Completed.' });
    await assert.rejects(updateMyTaskStatus('t1', 'COMPLETED'), (e) => e.status === 409 && e.message === 'A task that is Assigned can only be moved to In Progress, not Completed.');
    answer(404, { message: 'Task not found' });
    await assert.rejects(updateMyTaskStatus('t2', 'IN_PROGRESS'), (e) => e.status === 404 && e.message === 'Task not found');
  });

  it('a refusal (403) shows the permission message and never signs the user out; a 401 does', async () => {
    answer(403, {});
    await assert.rejects(updateMyTaskStatus('t1', 'IN_PROGRESS'), (e) => e.status === 403 && /permission/i.test(e.message));
    assert.equal(globalThis.localStorage.getItem('token'), 'jwt-token');
    assert.equal(globalThis.window.location.href, '/tasks');
    answer(401, {});
    await assert.rejects(updateMyTaskStatus('t1', 'IN_PROGRESS'));
    assert.equal(globalThis.window.location.href, '/login');
  });
});

describe('wiring in the Tasks screen (source checks: the project has no DOM test environment)', () => {
  const page = read('TasksPage.jsx');
  const table = read('..', 'components', 'tasks', 'TasksTable.jsx');

  it('only Staff are given the status handler; Admin and Manager are not', () => {
    assert.ok(page.includes("const isStaff = userRole === 'STAFF';"));
    assert.ok(page.includes('onStatusChange={isStaff ? handleStatusChange : undefined}'));
  });

  it('the handler calls the status request with the task id and the next status, shows success, and refreshes the list', () => {
    const handler = page.slice(page.indexOf('const handleStatusChange'), page.indexOf('// Open Edit Modal'));
    assert.ok(handler.includes('await updateMyTaskStatus(task.id, nextStatus);'));
    assert.ok(handler.includes('setSuccessBanner(staffStatusSuccessMessage(task.title, nextStatus));'));
    assert.ok(handler.includes('await fetchTasksData(false);'));
    assert.ok(!handler.includes('updateTask('), 'it never uses the full task update');
  });

  it('it has a loading guard (one update at a time), an error banner with Dismiss, and refreshes even after an error', () => {
    const handler = page.slice(page.indexOf('const handleStatusChange'), page.indexOf('// Open Edit Modal'));
    assert.ok(handler.includes('if (statusBusyTaskId) return;') && handler.includes('setStatusBusyTaskId(task.id);') && handler.includes('setStatusBusyTaskId(null);'));
    assert.ok(handler.includes('setStatusError(err.message ||'));
    assert.ok(/catch[\s\S]*await fetchTasksData\(false\);[\s\S]*finally/.test(handler));
    assert.ok(page.includes('{statusError && (') && page.includes("onClick={() => setStatusError('')}"));
  });

  it('the table offers the action only when it is not a management view, shows the busy label, and uses the shared rule', () => {
    assert.ok(table.includes("import { staffStatusAction } from '../../utils/taskStatus';"));
    assert.ok(table.includes('{!canManageTasks && onStatusChange && (() => {'));
    assert.ok(table.includes('const action = staffStatusAction(task.status);') && table.includes('if (!action) return null;'));
    assert.ok(table.includes('disabled={busy}') && table.includes('{busy ? action.busyLabel : action.label}'));
    assert.ok(table.includes('onStatusChange(task, action.status);'));
  });

  it('Admin/Manager editing controls are still gated by canManageTasks, and Staff get none of them', () => {
    for (const control of ['onEdit && onEdit(task)', 'onAssign && onAssign(task)', 'onDelete && onDelete(task)']) {
      const at = table.indexOf(control);
      assert.ok(at > 0, control);
      assert.ok(table.lastIndexOf('{canManageTasks &&', at) > table.lastIndexOf('{!canManageTasks &&', at), `${control} stays inside a canManageTasks block`);
    }
    assert.ok(page.includes('{canManageTasks && (') && page.includes("navigate('/tasks/create')"));
  });
});
