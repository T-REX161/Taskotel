import { describe, it, beforeEach, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  EMAIL_CHANGE_MESSAGES, emailChangeErrorMessage, formatCountdown, isCompleteCode, normalizeEmail, onlyDigits, secondsLeft,
  sendEmailChangeCode, validateNewEmail, verifyEmailChangeCode,
} from '../utils/emailChange.js';
import { confirmEmailChange, requestEmailChange } from '../services/api.js';

const here = path.dirname(fileURLToPath(import.meta.url));
const read = (...parts) => fs.readFileSync(path.join(here, ...parts), 'utf8');

const realFetch = globalThis.fetch;
const realStorage = Object.getOwnPropertyDescriptor(globalThis, 'localStorage');
const realSession = Object.getOwnPropertyDescriptor(globalThis, 'sessionStorage');
const realWindow = Object.getOwnPropertyDescriptor(globalThis, 'window');
let local;
let session;
beforeEach(() => {
  local = { token: 'jwt-maria', username: 'maria' };
  session = {};
  const make = (s) => ({ getItem: (k) => (k in s ? s[k] : null), setItem: (k, v) => { s[k] = String(v); }, removeItem: (k) => { delete s[k]; } });
  Object.defineProperty(globalThis, 'localStorage', { configurable: true, writable: true, value: make(local) });
  Object.defineProperty(globalThis, 'sessionStorage', { configurable: true, writable: true, value: make(session) });
  Object.defineProperty(globalThis, 'window', { configurable: true, writable: true, value: { location: { href: '/profile' } } });
});
afterEach(() => {
  globalThis.fetch = realFetch;
  for (const [name, saved] of [['localStorage', realStorage], ['sessionStorage', realSession], ['window', realWindow]]) {
    if (saved) Object.defineProperty(globalThis, name, saved); else delete globalThis[name];
  }
});

describe('validating the new address', () => {
  it('accepts a normal address, trimmed and in any case', () => {
    assert.equal(validateNewEmail('  Maria.Lopez+work@Example.com ', 'old@hotel.com'), null);
    assert.equal(normalizeEmail('  Maria@Example.COM '), 'maria@example.com');
  });

  it('requires an address, and refuses ones that cannot be an email', () => {
    assert.equal(validateNewEmail('', 'old@hotel.com'), EMAIL_CHANGE_MESSAGES.required);
    assert.equal(validateNewEmail('   ', 'old@hotel.com'), EMAIL_CHANGE_MESSAGES.required);
    for (const bad of ['plain', 'a@b', 'a b@c.test', '@c.test', 'a@@c.test', 'a@c.', 'a@c.test, b@c.test', `${'x'.repeat(250)}@c.test`]) {
      assert.equal(validateNewEmail(bad, 'old@hotel.com'), EMAIL_CHANGE_MESSAGES.invalid, bad);
    }
  });

  it('refuses your own current address, whatever its case', () => {
    assert.equal(validateNewEmail('OLD@hotel.com', 'old@hotel.com'), EMAIL_CHANGE_MESSAGES.same);
  });
});

describe('the code box and the countdowns', () => {
  it('keeps only digits, at most six', () => {
    assert.equal(onlyDigits('12a3-4 5678'), '123456');
    assert.equal(onlyDigits(null), '');
    assert.ok(isCompleteCode('123456') && !isCompleteCode('12345') && !isCompleteCode('1234567') && !isCompleteCode('12345a'));
  });

  it('counts down whole seconds and never goes below zero', () => {
    assert.equal(secondsLeft(10_000, 4_500), 6);
    assert.equal(secondsLeft(10_000, 10_000), 0);
    assert.equal(secondsLeft(10_000, 99_000), 0);
    assert.equal(formatCountdown(585), '9:45');
    assert.equal(formatCountdown(600), '10:00');
    assert.equal(formatCountdown(5), '0:05');
    assert.equal(formatCountdown(-3), '0:00');
  });
});

describe('error messages', () => {
  it('shows the server\'s own short text for refusals it explained, and the generic text otherwise', () => {
    for (const status of [400, 403, 409, 429]) assert.equal(emailChangeErrorMessage({ status, message: 'This email address is already in use.' }, 'x'), 'This email address is already in use.');
    assert.equal(emailChangeErrorMessage({ status: 500, message: 'java.lang.NullPointerException' }, 'fallback'), 'fallback');
    assert.equal(emailChangeErrorMessage({ status: 400, message: 'x'.repeat(201) }, 'fallback'), 'fallback');
    assert.equal(emailChangeErrorMessage(new Error('Failed to fetch'), 'fallback'), 'fallback');
  });
});

describe('sending the code', () => {
  it('sends only the normalised new address, and resolves with the masked address and the expiry and resend moments', async () => {
    let sent = null;
    const info = await sendEmailChangeCode('  New@Example.com ', 'old@hotel.com', {
      now: () => 1_000_000,
      send: async (email) => { sent = email; return { pendingEmailMasked: 'ne***@example.com', expiresInSeconds: 600, resendAvailableInSeconds: 60 }; },
    });
    assert.equal(sent, 'new@example.com');
    assert.deepEqual(info, { maskedEmail: 'ne***@example.com', expiresAt: 1_600_000, resendAt: 1_060_000 });
  });

  it('refuses an unsendable address without calling the server', async () => {
    let called = false;
    await assert.rejects(sendEmailChangeCode('nope', 'old@hotel.com', { send: async () => { called = true; } }), (e) => e.status === 0 && e.message === EMAIL_CHANGE_MESSAGES.invalid);
    await assert.rejects(sendEmailChangeCode('old@hotel.com', 'old@hotel.com', { send: async () => { called = true; } }), (e) => e.message === EMAIL_CHANGE_MESSAGES.same);
    assert.equal(called, false);
  });

  it('passes a server refusal on (duplicate, cooldown, rate limit), never as a success', async () => {
    await assert.rejects(sendEmailChangeCode('new@example.com', 'old@hotel.com', { send: async () => { throw Object.assign(new Error('This email address is already in use.'), { status: 409 }); } }),
      (e) => e.status === 409);
  });
});

describe('verifying the code', () => {
  it('needs six digits; nothing is sent otherwise', async () => {
    let called = false;
    await assert.rejects(verifyEmailChangeCode('123', { confirm: async () => { called = true; } }), (e) => e.status === 0 && e.message === EMAIL_CHANGE_MESSAGES.codeRequired);
    assert.equal(called, false);
  });

  it('resolves with the updated profile only when the server confirmed', async () => {
    const profile = await verifyEmailChangeCode('123456', { confirm: async (code) => ({ email: 'new@example.com', emailVerified: true, code }) });
    assert.equal(profile.email, 'new@example.com');
    await assert.rejects(verifyEmailChangeCode('123456', { confirm: async () => { throw Object.assign(new Error('Incorrect code. 4 attempts remaining.'), { status: 400 }); } }), (e) => e.status === 400);
  });
});

describe('the API calls', () => {
  it('POST the new address to /request and the code to /confirm, with the signed-in token, naming no user, and storing nothing', async () => {
    const calls = [];
    globalThis.fetch = async (url, options) => {
      calls.push({ url, method: options.method, auth: options.headers.Authorization, body: JSON.parse(options.body) });
      return { ok: true, status: 200, json: async () => ({ ok: true }) };
    };
    await requestEmailChange('new@example.com');
    await confirmEmailChange('654321');
    assert.deepEqual(calls.map((c) => [c.method, c.url, c.auth]), [
      ['POST', '/api/profile/email-change/request', 'Bearer jwt-maria'],
      ['POST', '/api/profile/email-change/confirm', 'Bearer jwt-maria'],
    ]);
    assert.deepEqual(calls[0].body, { newEmail: 'new@example.com' });
    assert.deepEqual(calls[1].body, { code: '654321' });
    const stored = JSON.stringify([local, session]);
    assert.ok(!stored.includes('654321') && !stored.includes('new@example.com'), 'no code or address in browser storage');
  });

  it('a wrong code (400) keeps the user signed in', async () => {
    globalThis.fetch = async () => ({ ok: false, status: 400, json: async () => ({ message: 'Incorrect code. 4 attempts remaining.' }) });
    await assert.rejects(confirmEmailChange('000000'), (e) => e.status === 400 && e.message.startsWith('Incorrect code'));
    assert.equal(local.token, 'jwt-maria');
  });
});

describe('the dialog', () => {
  const dialog = read('..', 'components', 'profile', 'EmailChangeDialog.jsx');

  it('step one: read-only current email, new email field, Send Verification Code', () => {
    const enter = dialog.slice(dialog.indexOf('<form onSubmit={sendCode}'), dialog.indexOf('<form onSubmit={verify}'));
    assert.ok(enter.includes('Current email') && enter.includes('value={currentEmail || \'\'}') && enter.includes('readOnly') && enter.includes('disabled'));
    assert.ok(enter.includes('New email') && enter.includes('Send Verification Code') && enter.includes('Sending…'));
  });

  it('step two: masked address, six-digit code box, Verify Email, resend with a cooldown, expiry countdown', () => {
    const verify = dialog.slice(dialog.indexOf('<form onSubmit={verify}'));
    assert.ok(verify.includes('{info?.maskedEmail}') && !verify.includes('{newEmail}'), 'only the masked address is shown');
    assert.ok(verify.includes('inputMode="numeric"') && verify.includes('maxLength={6}') && verify.includes('onlyDigits('));
    assert.ok(verify.includes('Verify Email') && verify.includes('Verifying…'));
    assert.ok(verify.includes('Resend Code') && verify.includes('Resend code in ${resendIn}s') && verify.includes('disabled={busy || resendIn > 0}'));
    assert.ok(verify.includes('Code expires in') && verify.includes('formatCountdown(expiresIn)') && verify.includes('This code has expired'));
  });

  it('prevents duplicate submissions and cannot be dismissed while a request runs', () => {
    assert.ok(dialog.includes('if (sending.current) return;') && dialog.includes('sending.current = true') && dialog.includes('sending.current = false'));
    assert.ok(dialog.includes("event.key === 'Escape' && !sending.current") && dialog.includes('const dismiss = () => { if (!sending.current) onClose(); };'));
    assert.ok(dialog.includes('disabled={busy}'));
  });

  it('hands over the updated profile only after the server confirmed, and clears the wrong code', () => {
    const verify = dialog.slice(dialog.indexOf('const verify = async'), dialog.indexOf('const dismiss'));
    assert.ok(verify.indexOf('await verifyEmailChangeCode(code)') < verify.indexOf('onChanged(updated)'));
    assert.ok(verify.includes("setCode('')") && verify.includes('emailChangeErrorMessage(err, EMAIL_CHANGE_MESSAGES.verifyFailed)'));
  });

  it('the countdown timer is stopped when the dialog closes, and nothing is kept in browser storage', () => {
    assert.ok(dialog.includes('return () => clearInterval(id);') && dialog.includes("step !== 'verify'"));
    for (const forbidden of ['localStorage', 'sessionStorage', 'indexedDB', 'console.']) assert.ok(!dialog.includes(forbidden), forbidden);
  });

  it('is an accessible modal in the shared style', () => {
    assert.ok(dialog.includes('role="dialog"') && dialog.includes('aria-modal="true"') && dialog.includes('aria-labelledby="email-change-title"'));
    assert.ok(dialog.includes('createPortal(') && dialog.includes('role="alert"'));
    assert.ok(dialog.includes('bg-white rounded-2xl') && dialog.includes('bg-emerald-600') && !/dark:|#[0-9a-fA-F]{6}\b/.test(dialog));
  });
});
