import { afterEach, beforeEach, describe, it } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  SENT_MESSAGE,
  canRequestEmailChange,
  emailChangeErrorMessage,
  minutesLeft,
  pendingChange,
  pendingFromResponse,
  validateNewEmail,
} from './loginEmailChange.js';
import { requestStaffEmailChange } from '../services/api.js';

const NOW = Date.UTC(2026, 0, 1, 12, 0, 0);
const iso = (ms) => new Date(ms).toISOString();

describe('who may be given a login email change', () => {
  it('only active Staff-role accounts (the server also enforces this and the department rules)', () => {
    assert.equal(canRequestEmailChange({ role: 'STAFF', active: true }), true);
    assert.equal(canRequestEmailChange({ role: 'STAFF' }), true);
    assert.equal(canRequestEmailChange({ role: 'STAFF', active: false }), false);
    assert.equal(canRequestEmailChange({ role: 'MANAGER', active: true }), false);
    assert.equal(canRequestEmailChange({ role: 'ADMIN', active: true }), false);
    assert.equal(canRequestEmailChange({ active: true }), false, 'an unknown role is not offered the action');
    assert.equal(canRequestEmailChange(null), false);
  });
});

describe('pending status', () => {
  it('a pending change is shown only while its code is still valid, and only as the masked pending address', () => {
    const staff = { email: 'old@x.test', pendingEmailMasked: 'ne***@x.test', pendingEmailExpiresAt: iso(NOW + 5 * 60000) };
    assert.deepEqual(pendingChange(staff, NOW), { masked: 'ne***@x.test', expiresAt: iso(NOW + 5 * 60000) });
    assert.equal(pendingChange({ ...staff, pendingEmailExpiresAt: iso(NOW - 1) }, NOW), null, 'an expired code is no longer pending');
    assert.equal(pendingChange({ email: 'old@x.test' }, NOW), null);
    assert.equal(pendingChange(null, NOW), null);
  });

  it('the pending address never replaces the active login email in what is described', () => {
    const staff = { email: 'old@x.test', pendingEmailMasked: 'ne***@x.test', pendingEmailExpiresAt: iso(NOW + 60000) };
    assert.equal(staff.email, 'old@x.test');
    assert.ok(!JSON.stringify(pendingChange(staff, NOW)).includes('old@x.test'));
  });

  it('minutes left round up and never go negative', () => {
    assert.equal(minutesLeft(iso(NOW + 61000), NOW), 2);
    assert.equal(minutesLeft(iso(NOW + 1000), NOW), 1);
    assert.equal(minutesLeft(iso(NOW), NOW), 0);
    assert.equal(minutesLeft('nonsense', NOW), 0);
    assert.equal(minutesLeft(undefined, NOW), 0);
  });

  it('a successful request becomes a pending change from the answer (masked address and lifetime)', () => {
    const pending = pendingFromResponse({ pendingEmailMasked: 'ne***@x.test', expiresInSeconds: 600, resendAvailableInSeconds: 60 }, NOW);
    assert.equal(pending.masked, 'ne***@x.test');
    assert.equal(minutesLeft(pending.expiresAt, NOW), 10);
    assert.equal(pendingFromResponse({}, NOW), null);
    assert.equal(pendingFromResponse(null, NOW), null);
  });

  it('the success message says the login email did not change and that the staff member confirms it', () => {
    assert.ok(/stays the same/.test(SENT_MESSAGE) && /staff member confirms it themselves/.test(SENT_MESSAGE));
  });
});

describe('checking the typed address before asking', () => {
  it('rejects empty, malformed and unchanged addresses; accepts a different valid one', () => {
    assert.match(validateNewEmail('', 'a@x.test'), /Enter the new/);
    assert.match(validateNewEmail('   ', 'a@x.test'), /Enter the new/);
    assert.match(validateNewEmail('nope', 'a@x.test'), /valid email/);
    assert.match(validateNewEmail('A@X.TEST', 'a@x.test'), /already/);
    assert.equal(validateNewEmail(' b@x.test ', 'a@x.test'), '');
  });
});

describe('failure messages', () => {
  it('rate limits, delivery failures, expired or invalid input and permission problems each get a clear message', () => {
    assert.equal(emailChangeErrorMessage({ status: 429, message: 'Please wait 40 seconds before requesting another code.' }), 'Please wait 40 seconds before requesting another code.');
    assert.match(emailChangeErrorMessage({ status: 429 }), /Too many requests/);
    assert.match(emailChangeErrorMessage({ status: 500, message: 'Could not send the email change notice' }), /could not be sent\. Nothing was changed/);
    assert.match(emailChangeErrorMessage({ status: 503 }), /Nothing was changed/);
    assert.equal(emailChangeErrorMessage({ status: 400, message: 'Enter a valid email address.' }), 'Enter a valid email address.');
    assert.equal(emailChangeErrorMessage({ status: 409, message: 'This account is deactivated.' }), 'This account is deactivated.');
    assert.match(emailChangeErrorMessage({ status: 404 }), /not found/);
    assert.match(emailChangeErrorMessage({ status: 403 }), /permission/);
    assert.match(emailChangeErrorMessage(new Error('boom')), /boom|Nothing was changed/);
  });
});

describe('the API call', () => {
  let calls;
  beforeEach(() => {
    calls = [];
    const store = new Map([['token', 'a.b.c'], ['role', 'MANAGER']]);
    Object.defineProperty(globalThis, 'localStorage', { configurable: true, value: { getItem: (k) => store.get(k) ?? null, setItem: () => {}, removeItem: (k) => store.delete(k) } });
    globalThis.window = { location: { href: '/staff' } };
    globalThis.fetch = async (url, options) => { calls.push({ url, options }); return { ok: true, status: 200, json: async () => ({ pendingEmailMasked: 'ne***@x.test', expiresInSeconds: 600, resendAvailableInSeconds: 60 }) }; };
  });
  afterEach(() => { delete globalThis.localStorage; delete globalThis.window; delete globalThis.fetch; });

  it('posts only the new address to the staff member\'s email-change endpoint, with the bearer token', async () => {
    const answer = await requestStaffEmailChange('11111111-2222-3333-4444-555555555555', 'new@x.test');
    assert.equal(answer.pendingEmailMasked, 'ne***@x.test');
    assert.equal(calls.length, 1);
    assert.equal(calls[0].url, '/api/staff/11111111-2222-3333-4444-555555555555/email-change');
    assert.equal(calls[0].options.method, 'POST');
    assert.deepEqual(JSON.parse(calls[0].options.body), { newEmail: 'new@x.test' });
    assert.equal(calls[0].options.headers.Authorization, 'Bearer a.b.c');
  });

  it('a 429 reaches the screen as an error with its status, and does not end the session', async () => {
    globalThis.fetch = async () => ({ ok: false, status: 429, json: async () => ({ message: 'Please wait 30 seconds before requesting another code.' }) });
    await assert.rejects(requestStaffEmailChange('id', 'new@x.test'), (e) => e.status === 429 && /Please wait 30 seconds/.test(e.message));
    assert.equal(globalThis.localStorage.getItem('token'), 'a.b.c');
    assert.equal(globalThis.window.location.href, '/staff');
  });
});

describe('edit screen wiring (source checks: the project has no DOM test environment)', () => {
  const read = (...p) => fs.readFileSync(path.join(path.dirname(fileURLToPath(import.meta.url)), ...p), 'utf8').replace(/\r\n/g, '\n');
  const modal = read('..', 'components', 'staff', 'EditStaffModal.jsx');

  it('the active email is read-only and separate from the pending notice, which is labelled as not active', () => {
    assert.ok(modal.includes('value={staff.email || \'\'}') && modal.includes('Pending verification: {pending.masked}'));
    assert.ok(modal.includes('Not active yet. The login email is still {staff.email}'));
    assert.ok(!modal.includes('setPending(') || !/staff\.email\s*=\s*/.test(modal), 'the screen never rewrites the active email');
  });

  it('the request button is not a form submit, and Enter in the new-address box asks for a code instead of saving the profile', () => {
    assert.ok(/onClick=\{handleRequestEmailChange\}[\s\S]{0,200}disabled=/.test(modal) || modal.includes('type="button"\n                        onClick={handleRequestEmailChange}'));
    assert.ok(modal.includes("if (e.key === 'Enter') { e.preventDefault(); handleRequestEmailChange(); }"));
  });

  it('the action is offered only for eligible accounts, and the screen never confirms a change on the staff member\'s behalf', () => {
    assert.ok(modal.includes('canRequestEmailChange(staff) ?'));
    assert.ok(!/confirmEmailChange|email-change\/confirm/.test(modal));
  });

  it('the profile save still sends the unchanged email, never the proposed one', () => {
    assert.ok(modal.includes('email: staff.email,'));
    assert.ok(!modal.includes('email: newEmail'));
  });
});
