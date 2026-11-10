import { afterEach, beforeEach, describe, it } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { apiFetch } from './api.js';

const here = path.dirname(fileURLToPath(import.meta.url));
const read = (...p) => fs.readFileSync(path.join(here, ...p), 'utf8').replace(/\r\n/g, '\n');

let store;
let location;

beforeEach(() => {
  store = new Map([['token', 'a.b.c'], ['username', 'tester'], ['role', 'STAFF']]);
  location = { href: '/staff' };
  Object.defineProperty(globalThis, 'localStorage', {
    configurable: true,
    value: { getItem: (k) => (store.has(k) ? store.get(k) : null), setItem: (k, v) => store.set(k, v), removeItem: (k) => store.delete(k) },
  });
  globalThis.window = { location };
});

afterEach(() => {
  delete globalThis.localStorage;
  delete globalThis.window;
  delete globalThis.fetch;
});

const respond = (status, body = {}) => {
  globalThis.fetch = async () => ({ ok: status >= 200 && status < 300, status, json: async () => body });
};

describe('how the API wrapper treats 401 and 403', () => {
  it('a 403 reports the refusal and leaves the user signed in (no sign-out, no redirect)', async () => {
    respond(403, { message: 'Forbidden' });
    await assert.rejects(apiFetch('/api/staff'), (error) => error.status === 403);
    assert.equal(store.get('token'), 'a.b.c');
    assert.equal(store.get('role'), 'STAFF');
    assert.equal(location.href, '/staff', 'no navigation to /login');
  });

  it('a 401 ends the session: the stored sign-in is cleared and the user is sent to /login', async () => {
    respond(401);
    await assert.rejects(apiFetch('/api/tasks'), /Redirecting to login/);
    assert.deepEqual([...store.keys()], []);
    assert.equal(location.href, '/login');
  });

  it('with no stored token no request is made and the user is sent to /login', async () => {
    store.clear();
    let called = false;
    globalThis.fetch = async () => { called = true; return { ok: true, status: 200, json: async () => ({}) }; };
    await assert.rejects(apiFetch('/api/tasks'));
    assert.equal(called, false);
    assert.equal(location.href, '/login');
  });

  it('a successful response is returned and nothing is cleared', async () => {
    respond(200, { ok: 1 });
    assert.deepEqual(await apiFetch('/api/tasks'), { ok: 1 });
    assert.equal(store.get('token'), 'a.b.c');
  });
});

describe('page wiring that cannot be rendered here (no DOM environment): source checks, not behaviour tests', () => {
  it('the Workload page requests the staff list only for roles allowed to, so Staff never trigger the 403', () => {
    const page = read('..', 'pages', 'WorkloadPage.jsx');
    assert.ok(page.includes('canListStaff(getUserRole())\n        ? getStaff()'), 'getStaff is gated');
    assert.ok(!/const staffPromise = getStaff\(\)/.test(page), 'no unconditional getStaff call');
  });

  it('the sidebar builds its links from the shared policy and has no role rules of its own', () => {
    const sidebar = read('..', 'components', 'layout', 'Sidebar.jsx');
    assert.ok(sidebar.includes('navigationFor(getUserRole())'));
    assert.ok(!sidebar.includes("userRole === 'ADMIN'"));
  });

  it('the Profile page never falls back to ADMIN for a missing role', () => {
    const page = read('..', 'pages', 'ProfilePage.jsx');
    assert.ok(!/\|\|\s*'ADMIN'/.test(page) && !/\|\|\s*'admin'/.test(page));
    assert.ok(page.includes('normalizeRole(getUserRole())') && page.includes("role || 'Unavailable'"));
  });

  it('the Staff dashboard requests only its own summary (no organisation-wide sources)', () => {
    const page = read('..', 'pages', 'Dashboard.jsx');
    assert.ok(page.includes("getUserRole() !== 'STAFF' ? [loadStaffWorkload(), loadRecommendations(), loadTasks()] : []"));
  });
});
