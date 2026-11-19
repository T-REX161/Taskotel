import { describe, it, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import { deleteDepartment } from './api.js';

// What the Admin sees when the server refuses to remove a department: the real deleteDepartment call (and so the shared request code) is run
// against a stand-in for fetch that answers like the backend. The Remove dialog shows err.message for a 409, so this is exactly its text.
const realFetch = globalThis.fetch;
const realStorage = globalThis.localStorage;
afterEach(() => {
  globalThis.fetch = realFetch;
  if (realStorage === undefined) delete globalThis.localStorage; else globalThis.localStorage = realStorage;
});

const store = new Map([['token', 'test-token']]);
const useFakeSession = () => {
  globalThis.localStorage = { getItem: (k) => (store.has(k) ? store.get(k) : null), setItem: (k, v) => store.set(k, String(v)), removeItem: (k) => store.delete(k) };
};
const respond = (status, body) => {
  const calls = [];
  globalThis.fetch = async (url, options) => {
    calls.push({ url, options });
    return { ok: status >= 200 && status < 300, status, json: async () => body, text: async () => JSON.stringify(body), headers: { get: () => 'application/json' } };
  };
  return calls;
};
const rejection = async (fn) => { try { await fn(); } catch (e) { return e; } return assert.fail('expected the call to fail'); };

describe('removing a department: the server explanation reaches the Admin', () => {
  it('a Manager access grant refusal (409) keeps its full message and status', async () => {
    useFakeSession();
    const message = '"Conference Hall" cannot be removed because 1 Manager access grant still references it. Revoke the access grant(s) first (Users page, Department View Access), then remove the department.';
    const calls = respond(409, { status: 409, error: '409 CONFLICT', message });
    const error = await rejection(() => deleteDepartment('CONFERENCE_HALL'));
    assert.equal(error.status, 409);
    assert.equal(error.message, message);
    assert.equal(calls.length, 1);
    assert.equal(calls[0].url, '/api/departments/CONFERENCE_HALL');
    assert.equal(calls[0].options.method, 'DELETE');
  });

  it('a staff, task or assignment refusal (409) keeps its message too', async () => {
    useFakeSession();
    const message = '"Pool Deck" cannot be removed because it still has 1 staff member. Move them to another department first, or mark the department Inactive.';
    respond(409, { status: 409, message });
    const error = await rejection(() => deleteDepartment('POOL_DECK'));
    assert.equal(error.status, 409);
    assert.equal(error.message, message);
  });

  it('a successful removal (204) resolves without an error', async () => {
    useFakeSession();
    respond(204, null);
    await deleteDepartment('LAUNDRY');
  });
});
