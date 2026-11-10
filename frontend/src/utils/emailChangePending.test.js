import { afterEach, beforeEach, describe, it } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { emailChangeErrorMessage, pendingEmailChange, sendEmailChangeCode, startingStep, verifyEmailChangeCode } from './emailChange.js';

const NOW = Date.UTC(2026, 0, 1, 12, 0, 0);

describe('a pending change reported by the profile', () => {
  it('is shown, masked, with its expiry, when the server reports one', () => {
    const profile = { email: 'old@x.test', pendingEmailMasked: 'ne***@new.test', pendingEmailExpiresAt: '2026-01-01T12:08:00' };
    const pending = pendingEmailChange(profile);
    assert.equal(pending.maskedEmail, 'ne***@new.test');
    assert.equal(pending.expiresAt, Date.parse('2026-01-01T12:08:00'));
  });

  it('is absent when there is none (null, missing or blank fields)', () => {
    assert.equal(pendingEmailChange({ email: 'old@x.test', pendingEmailMasked: null, pendingEmailExpiresAt: null }), null);
    assert.equal(pendingEmailChange({ email: 'old@x.test' }), null);
    assert.equal(pendingEmailChange({ pendingEmailMasked: '   ' }), null);
    assert.equal(pendingEmailChange(null), null);
    assert.equal(pendingEmailChange(undefined), null);
  });

  it('carries only the masked address: nothing in the result is the full pending address', () => {
    const pending = pendingEmailChange({ pendingEmailMasked: 'ne***@new.test', pendingEmailExpiresAt: '2026-01-01T12:08:00', pendingEmail: 'newaddress@new.test', pendingEmailCodeHash: 'x' });
    assert.deepEqual(Object.keys(pending).sort(), ['expiresAt', 'maskedEmail']);
    assert.ok(!JSON.stringify(pending).includes('newaddress'));
  });

  it('an unreadable expiry does not hide the notice (the server decides validity); the time is just unknown', () => {
    assert.equal(pendingEmailChange({ pendingEmailMasked: 'ne***@new.test', pendingEmailExpiresAt: 'garbage' }).expiresAt, null);
  });
});

describe('where the email dialog starts', () => {
  it('with a pending change it opens on the code step, showing only the masked address', () => {
    const start = startingStep({ maskedEmail: 'ne***@new.test', expiresAt: NOW + 480000 }, NOW);
    assert.equal(start.step, 'verify');
    assert.equal(start.continuing, true);
    assert.deepEqual(start.info, { maskedEmail: 'ne***@new.test', expiresAt: NOW + 480000, resendAt: NOW });
  });

  it('without one it is the ordinary first step (new address and password), unchanged', () => {
    assert.deepEqual(startingStep(null, NOW), { step: 'enter', continuing: false, info: null });
    assert.deepEqual(startingStep(undefined, NOW), { step: 'enter', continuing: false, info: null });
  });
});

describe('finishing a pending change talks only to the existing confirmation endpoint', () => {
  let calls;
  beforeEach(() => {
    calls = [];
    const store = new Map([['token', 'a.b.c'], ['role', 'STAFF']]);
    Object.defineProperty(globalThis, 'localStorage', { configurable: true, value: { getItem: (k) => store.get(k) ?? null, setItem: () => {}, removeItem: (k) => store.delete(k) } });
    globalThis.window = { location: { href: '/profile' } };
  });
  afterEach(() => { delete globalThis.localStorage; delete globalThis.window; delete globalThis.fetch; });

  const answer = (status, body) => {
    globalThis.fetch = async (url, options) => { calls.push({ url, method: options.method, body: options.body }); return { ok: status < 400, status, json: async () => body }; };
  };

  it('continuing then confirming makes exactly one call, to the confirmation endpoint, with only the code', async () => {
    startingStep({ maskedEmail: 'ne***@new.test', expiresAt: NOW + 1000 }, NOW); // opening the dialog calls nothing
    assert.equal(calls.length, 0);
    answer(200, { email: 'new@new.test', pendingEmailMasked: null, pendingEmailExpiresAt: null });
    const updated = await verifyEmailChangeCode('123456');
    assert.equal(calls.length, 1);
    assert.equal(calls[0].url, '/api/profile/email-change/confirm');
    assert.equal(calls[0].method, 'POST');
    assert.deepEqual(JSON.parse(calls[0].body), { code: '123456' });
    assert.ok(!calls.some((c) => c.url.endsWith('/email-change/request')), 'the request endpoint is never called');
    assert.equal(updated.email, 'new@new.test', 'the answer is the updated profile, which the page shows');
    assert.equal(pendingEmailChange(updated), null, 'and the pending notice is gone');
  });

  it('a wrong code is an error with the server\'s message (the flow is not restarted and nothing else is called)', async () => {
    answer(400, { message: 'Incorrect code. 4 attempts remaining.' });
    await assert.rejects(verifyEmailChangeCode('000000'), (e) => {
      assert.equal(emailChangeErrorMessage(e, 'fallback'), 'Incorrect code. 4 attempts remaining.');
      return e.status === 400;
    });
    assert.equal(calls.length, 1);
  });

  it('an expired code shows the server\'s expiry message', async () => {
    answer(400, { message: 'That code has expired. Request a new code.' });
    await assert.rejects(verifyEmailChangeCode('123456'), (e) => emailChangeErrorMessage(e, 'fallback') === 'That code has expired. Request a new code.');
  });

  it('a malformed code is refused before any call', async () => {
    answer(200, {});
    await assert.rejects(verifyEmailChangeCode('12'), (e) => e.status === 0);
    assert.equal(calls.length, 0);
  });

  it('the ordinary self-service request still works with no pending change: one request, new address and password', async () => {
    const sent = [];
    const result = await sendEmailChangeCode(' New@X.test ', 'old@x.test', 'Correct@123', {
      send: async (email, password) => { sent.push([email, password]); return { pendingEmailMasked: 'ne***@x.test', expiresInSeconds: 600, resendAvailableInSeconds: 60 }; },
      now: () => NOW,
    });
    assert.deepEqual(sent, [['new@x.test', 'Correct@123']]);
    assert.equal(result.maskedEmail, 'ne***@x.test');
    assert.equal(startingStep(null, NOW).step, 'enter');
  });
});

describe('page and dialog wiring (source checks: the project has no DOM test environment)', () => {
  const read = (...p) => fs.readFileSync(path.join(path.dirname(fileURLToPath(import.meta.url)), ...p), 'utf8').replace(/\r\n/g, '\n');
  const dialog = read('..', 'components', 'profile', 'EmailChangeDialog.jsx');
  const page = read('..', 'pages', 'ProfilePage.jsx');

  it('the page shows a notice built only from the masked address and offers "Enter verification code"', () => {
    assert.ok(page.includes('const pendingChange = pendingEmailChange(profile);'));
    assert.ok(page.includes('{pendingChange.maskedEmail}') && page.includes('Enter verification code'));
    assert.ok(!/pendingEmail\b/.test(page.replace(/pendingEmailChange|pendingEmailMasked/g, '')), 'the page never reads a full pending address');
  });

  it('the action opens the dialog on the pending change; the ordinary Edit action opens it without one', () => {
    assert.ok(page.includes('setContinuePending(pendingChange); setEmailDialogOpen(true);'));
    assert.ok(page.includes('setContinuePending(null); setEmailDialogOpen(true);'));
    assert.ok(page.includes('pending={continuePending}'));
  });

  it('a successful confirmation puts the updated profile in the shared profile, so the new active email shows at once', () => {
    assert.ok(/onChanged=\{\(updated\) => \{\s*setCurrentProfile\(updated\);/.test(page));
  });

  it('the profile is read afresh when the page opens, so a change started since the last load is shown', () => {
    assert.ok(page.includes('loadCurrentProfile({ force: true });'));
  });

  it('the dialog starts from startingStep, and the opening effect never requests a code', () => {
    const opening = dialog.slice(dialog.indexOf('// A fresh dialog every time it opens'), dialog.indexOf('// The countdowns tick'));
    assert.ok(opening.includes('startingStep(pendingRef.current'));
    assert.ok(!opening.includes('sendEmailChangeCode') && !opening.includes('requestEmailChange'));
  });

  it('while continuing there is no resend and no client-side expiry block (the server is the judge)', () => {
    assert.ok(dialog.includes('{continuing ? (\n                <span aria-hidden="true" />'));
    assert.ok(dialog.includes('disabled={busy || (!continuing && expiresIn === 0)}'));
  });

  it('confirmation still goes through verifyEmailChangeCode, and no second mechanism was added', () => {
    assert.ok(dialog.includes('await verifyEmailChangeCode(code)'));
    assert.ok(!/fetch\(|apiFetch/.test(dialog));
  });
});
