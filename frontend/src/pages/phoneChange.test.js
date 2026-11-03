import { describe, it, beforeEach, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { PHONE_MESSAGES, normalizePhone, phoneErrorMessage, savePhone, validatePhone } from '../utils/phoneNumber.js';
import { updatePhone } from '../services/api.js';

const here = path.dirname(fileURLToPath(import.meta.url));
const read = (...parts) => fs.readFileSync(path.join(here, ...parts), 'utf8');

const realFetch = globalThis.fetch;
const realStorage = Object.getOwnPropertyDescriptor(globalThis, 'localStorage');
const realWindow = Object.getOwnPropertyDescriptor(globalThis, 'window');
let local;
beforeEach(() => {
  local = { token: 'jwt-maria', username: 'maria' };
  const make = (s) => ({ getItem: (k) => (k in s ? s[k] : null), setItem: (k, v) => { s[k] = String(v); }, removeItem: (k) => { delete s[k]; } });
  Object.defineProperty(globalThis, 'localStorage', { configurable: true, writable: true, value: make(local) });
  Object.defineProperty(globalThis, 'window', { configurable: true, writable: true, value: { location: { href: '/profile' } } });
});
afterEach(() => {
  globalThis.fetch = realFetch;
  for (const [name, saved] of [['localStorage', realStorage], ['window', realWindow]]) {
    if (saved) Object.defineProperty(globalThis, name, saved); else delete globalThis[name];
  }
});

describe('validating the phone number', () => {
  it('accepts numbers with and without a country code, in common separators', () => {
    for (const ok of ['+91 98765 43210', '+919876543210', '98765-43210', '(020) 7946 0958', '+1 (555) 010-2030', '555.010.2030']) {
      assert.equal(validatePhone(ok, ''), null, ok);
    }
  });

  it('requires a number', () => {
    for (const empty of ['', '   ', null, undefined]) assert.equal(validatePhone(empty, ''), PHONE_MESSAGES.required);
  });

  it('refuses letters, too few or too many digits, stray symbols and unbalanced brackets', () => {
    for (const bad of ['abc', '12345', '+91 98765 abc', '1234567890123456', '++911234567', '98765<43210>', '(123 4567890', '123) 4567890', '+', '1'.repeat(51)]) {
      assert.equal(validatePhone(bad, ''), PHONE_MESSAGES.invalid, bad);
    }
  });

  it('refuses the number that is already saved, however it is spaced', () => {
    assert.equal(validatePhone(' +91   98765 43210 ', '+91 98765 43210'), PHONE_MESSAGES.same);
    assert.equal(validatePhone('+91 98765 43211', '+91 98765 43210'), null);
  });

  it('normalises whitespace like the server does', () => {
    assert.equal(normalizePhone('  +1   555 010 2030 '), '+1 555 010 2030');
  });
});

describe('error messages', () => {
  it("shows the server's own short text for refusals it explained, and the generic text otherwise", () => {
    assert.equal(phoneErrorMessage({ status: 400, message: 'Enter a valid phone number.' }, 'x'), 'Enter a valid phone number.');
    assert.equal(phoneErrorMessage({ status: 500, message: 'java.lang.NullPointerException' }, 'fallback'), 'fallback');
    assert.equal(phoneErrorMessage({ status: 400, message: 'x'.repeat(201) }, 'fallback'), 'fallback');
    assert.equal(phoneErrorMessage(new Error('Failed to fetch'), 'fallback'), 'fallback');
  });
});

describe('saving', () => {
  it('sends only the normalised number and resolves with the updated profile', async () => {
    let sent = null;
    const profile = await savePhone('  +91   98765 43210 ', '', { save: async (phone) => { sent = phone; return { phone }; } });
    assert.equal(sent, '+91 98765 43210');
    assert.equal(profile.phone, '+91 98765 43210');
  });

  it('refuses an unsaveable number without calling the server, and passes a server refusal on', async () => {
    let called = false;
    await assert.rejects(savePhone('nope', '', { save: async () => { called = true; } }), (e) => e.status === 0 && e.message === PHONE_MESSAGES.invalid);
    await assert.rejects(savePhone('', '', { save: async () => { called = true; } }), (e) => e.message === PHONE_MESSAGES.required);
    assert.equal(called, false);
    await assert.rejects(savePhone('+91 98765 43210', '', { save: async () => { throw Object.assign(new Error('Enter a valid phone number.'), { status: 400 }); } }), (e) => e.status === 400);
  });

  it('PUTs the number to /api/profile/phone with the signed-in token, naming no user, and storing nothing', async () => {
    let call;
    globalThis.fetch = async (url, options) => {
      call = { url, method: options.method, auth: options.headers.Authorization, body: JSON.parse(options.body) };
      return { ok: true, status: 200, json: async () => ({ phone: '+91 98765 43210' }) };
    };
    await updatePhone('+91 98765 43210');
    assert.deepEqual([call.method, call.url, call.auth], ['PUT', '/api/profile/phone', 'Bearer jwt-maria']);
    assert.deepEqual(call.body, { phone: '+91 98765 43210' });
    assert.ok(!JSON.stringify(local).includes('98765'), 'no number in browser storage');
  });

  it('a refusal (400) keeps the user signed in', async () => {
    globalThis.fetch = async () => ({ ok: false, status: 400, json: async () => ({ message: 'Enter a valid phone number.' }) });
    await assert.rejects(updatePhone('abc'), (e) => e.status === 400);
    assert.equal(local.token, 'jwt-maria');
  });
});

describe('the dialog', () => {
  const dialog = read('..', 'components', 'profile', 'PhoneChangeDialog.jsx');

  it('shows the current number read-only, a new number field, Cancel and Save Changes', () => {
    assert.ok(dialog.includes('Current phone number') && dialog.includes("value={currentPhone || 'Not added'}") && dialog.includes('readOnly') && dialog.includes('disabled'));
    assert.ok(dialog.includes('New phone number') && dialog.includes('type="tel"') && dialog.includes('+91 98765 43210'));
    assert.ok(dialog.includes('Cancel') && dialog.includes('Save Changes') && dialog.includes('Saving…'));
  });

  it('prevents duplicate submissions and cannot be dismissed while saving', () => {
    assert.ok(dialog.includes('if (saving.current) return;') && dialog.includes('saving.current = true') && dialog.includes('saving.current = false'));
    assert.ok(dialog.includes("event.key === 'Escape' && !saving.current") && dialog.includes('const dismiss = () => { if (!saving.current) onClose(); };'));
  });

  it('hands over the updated profile only after the server confirmed, and shows errors', () => {
    const save = dialog.slice(dialog.indexOf('const save = async'), dialog.indexOf('const dismiss'));
    assert.ok(save.indexOf('await savePhone(value, currentPhone)') < save.indexOf('onChanged(updated)'));
    assert.ok(save.includes('phoneErrorMessage(err, PHONE_MESSAGES.saveFailed)') && dialog.includes('role="alert"'));
  });

  it('never says or implies the number is verified, sends no code, and keeps nothing in browser storage', () => {
    assert.ok(dialog.includes('it is not verified'));
    const jsx = dialog.slice(dialog.indexOf('return createPortal('));
    assert.ok(!/\bverified\b/i.test(jsx.replace('it is not verified', '')), 'no verified wording or badge');
    const code = dialog.replace(/\/\*[\s\S]*?\*\//g, '');
    for (const forbidden of ['sms', 'otp', 'localStorage', 'sessionStorage', 'indexedDB', 'console.']) assert.ok(!code.toLowerCase().includes(forbidden.toLowerCase()), forbidden);
  });

  it('is an accessible modal in the shared style', () => {
    assert.ok(dialog.includes('role="dialog"') && dialog.includes('aria-modal="true"') && dialog.includes('aria-labelledby="phone-change-title"') && dialog.includes('createPortal('));
    assert.ok(dialog.includes('bg-white rounded-2xl') && dialog.includes('bg-emerald-600') && !/dark:|#[0-9a-fA-F]{6}\b/.test(dialog));
  });
});
