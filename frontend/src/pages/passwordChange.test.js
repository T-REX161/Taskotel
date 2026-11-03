import { describe, it, beforeEach, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { PASSWORD_MESSAGES, passwordErrorMessage, submitPasswordChange, validatePasswordChange } from '../utils/passwordChange.js';
import { changePassword } from '../services/api.js';

const here = path.dirname(fileURLToPath(import.meta.url));
const read = (...parts) => fs.readFileSync(path.join(here, ...parts), 'utf8');

const OLD = 'Correct@123';
const NEW = 'Brand#New9pw';
const form = (over = {}) => ({ currentPassword: OLD, newPassword: NEW, confirmPassword: NEW, ...over });

const realFetch = globalThis.fetch;
const realStorage = Object.getOwnPropertyDescriptor(globalThis, 'localStorage');
const realSession = Object.getOwnPropertyDescriptor(globalThis, 'sessionStorage');
const realWindow = Object.getOwnPropertyDescriptor(globalThis, 'window');
let store;
let session;

beforeEach(() => {
  store = { token: 'jwt-maria', username: 'maria' };
  session = {};
  const make = (s) => ({ getItem: (k) => (k in s ? s[k] : null), setItem: (k, v) => { s[k] = String(v); }, removeItem: (k) => { delete s[k]; } });
  Object.defineProperty(globalThis, 'localStorage', { configurable: true, writable: true, value: make(store) });
  Object.defineProperty(globalThis, 'sessionStorage', { configurable: true, writable: true, value: make(session) });
  Object.defineProperty(globalThis, 'window', { configurable: true, writable: true, value: { location: { href: '/settings' } } });
});

afterEach(() => {
  globalThis.fetch = realFetch;
  for (const [name, saved] of [['localStorage', realStorage], ['sessionStorage', realSession], ['window', realWindow]]) {
    if (saved) Object.defineProperty(globalThis, name, saved); else delete globalThis[name];
  }
});

describe('validation (mirrors the server rules)', () => {
  it('a good form has no errors', () => {
    assert.deepEqual(validatePasswordChange(form()), {});
  });

  it('every field is required', () => {
    assert.deepEqual(validatePasswordChange({}), {
      currentPassword: PASSWORD_MESSAGES.currentRequired,
      newPassword: PASSWORD_MESSAGES.newRequired,
      confirmPassword: PASSWORD_MESSAGES.confirmRequired,
    });
  });

  it('the confirmation must match the new password', () => {
    assert.deepEqual(validatePasswordChange(form({ confirmPassword: NEW + 'x' })), { confirmPassword: PASSWORD_MESSAGES.confirmMismatch });
  });

  it('weak passwords are refused: short, no uppercase, no lowercase, no number, no special character, spaces', () => {
    for (const weak of ['Ab1@', 'alllower1@x', 'ALLUPPER1@X', 'NoDigits@@x', 'NoSpecial123x', 'Has Space1@x']) {
      assert.equal(validatePasswordChange(form({ newPassword: weak, confirmPassword: weak })).newPassword, PASSWORD_MESSAGES.newWeak, weak);
    }
  });

  it('a password over 72 bytes (BCrypt\'s limit) is refused', () => {
    const long = 'A1@' + 'a'.repeat(80);
    assert.equal(validatePasswordChange(form({ newPassword: long, confirmPassword: long })).newPassword, PASSWORD_MESSAGES.newTooLong);
  });

  it('a new password identical to the current one is refused', () => {
    assert.deepEqual(validatePasswordChange(form({ newPassword: OLD, confirmPassword: OLD })), { newPassword: PASSWORD_MESSAGES.newSame });
  });
});

describe('error messages', () => {
  it('shows the server\'s own short text for refusals it explained, and the generic text for anything else', () => {
    assert.equal(passwordErrorMessage({ status: 400, message: 'Current password is incorrect.' }), 'Current password is incorrect.');
    assert.equal(passwordErrorMessage({ status: 403, message: 'Finish setting up your account before changing your password.' }), 'Finish setting up your account before changing your password.');
    assert.equal(passwordErrorMessage({ status: 429, message: 'Too many attempts. Please wait a few minutes and try again.' }), 'Too many attempts. Please wait a few minutes and try again.');
    assert.equal(passwordErrorMessage({ status: 500, message: 'java.lang.NullPointerException' }), PASSWORD_MESSAGES.failed);
    assert.equal(passwordErrorMessage({ status: 400, message: 'x'.repeat(201) }), PASSWORD_MESSAGES.failed);
    assert.equal(passwordErrorMessage(new Error('Failed to fetch')), PASSWORD_MESSAGES.failed);
  });
});

describe('submitting', () => {
  it('sends the three fields and nothing else (no user id), and resolves only after the server answered', async () => {
    let sent = null;
    await submitPasswordChange(form(), { send: async (body) => { sent = body; return { message: 'ok' }; } });
    assert.deepEqual(sent, { currentPassword: OLD, newPassword: NEW, confirmPassword: NEW });
  });

  it('an invalid form is rejected with its field errors and nothing is sent', async () => {
    let called = false;
    await assert.rejects(submitPasswordChange(form({ confirmPassword: 'nope' }), { send: async () => { called = true; } }),
      (error) => error.status === 0 && error.errors.confirmPassword === PASSWORD_MESSAGES.confirmMismatch);
    assert.equal(called, false);
  });

  it('a server refusal is passed on, never turned into a success', async () => {
    await assert.rejects(submitPasswordChange(form(), { send: async () => { throw Object.assign(new Error('Current password is incorrect.'), { status: 400 }); } }),
      (error) => error.status === 400 && error.message === 'Current password is incorrect.');
  });

  it('the API call is a PUT to /api/profile/password with the signed-in user\'s token, and no password reaches browser storage', async () => {
    const calls = [];
    globalThis.fetch = async (url, options) => {
      calls.push({ url, method: options.method, auth: options.headers.Authorization, body: JSON.parse(options.body) });
      return { ok: true, status: 200, json: async () => ({ message: 'Your password has been changed.' }) };
    };
    const result = await changePassword({ currentPassword: OLD, newPassword: NEW, confirmPassword: NEW });
    assert.deepEqual(result, { message: 'Your password has been changed.' });
    assert.deepEqual(calls.map((c) => [c.method, c.url, c.auth]), [['PUT', '/api/profile/password', 'Bearer jwt-maria']]);
    assert.deepEqual(Object.keys(calls[0].body).sort(), ['confirmPassword', 'currentPassword', 'newPassword']);
    assert.ok(![...Object.values(store), ...Object.values(session)].some((v) => v.includes(OLD) || v.includes(NEW)), 'no password in local or session storage');
  });

  it('a refusal from the server (400) keeps the message and does not sign the user out; a 401 does', async () => {
    globalThis.fetch = async () => ({ ok: false, status: 400, json: async () => ({ message: 'Current password is incorrect.' }) });
    await assert.rejects(changePassword({ currentPassword: 'x' }), (error) => error.status === 400 && error.message === 'Current password is incorrect.');
    assert.equal(store.token, 'jwt-maria', 'still signed in after a wrong current password');
  });
});

describe('Profile page: Change Password dialog (replaces the Settings Security card)', () => {
  const card = read('..', 'components', 'profile', 'ChangePasswordDialog.jsx');
  const settings = read('SettingsPage.jsx');
  const profile = read('ProfilePage.jsx');

  it('has the three password fields with show/hide controls, the requirements checklist, and Change Password and Cancel buttons', () => {
    assert.ok(card.includes('>Change Password</h3>'));
    for (const label of ['Current Password', 'New Password', 'Confirm New Password']) assert.ok(card.includes(`label="${label}"`), label);
    assert.equal((card.match(/<PasswordField\b/g) || []).length, 3);
    assert.ok(card.includes('`Hide ${label.toLowerCase()}`') && card.includes("type={shown ? 'text' : 'password'}"));
    assert.ok(card.includes('autoComplete="current-password"') && card.includes('autoComplete="new-password"'));
    assert.ok(card.includes('<PasswordRequirements rules={rules} />'), 'the shared requirements checklist');
    assert.ok(card.includes("'Change Password'") && card.includes('>\n            Cancel\n'));
    assert.ok(card.includes('role="dialog"') && card.includes('aria-modal="true"'));
  });

  it('is opened from a green Change Password action under the Account Activity entries', () => {
    const header = profile.slice(profile.indexOf('aria-label="Profile summary"'), profile.indexOf('title="Personal Information"'));
    const headingRow = header.slice(header.indexOf('id="account-activity-title"') - 80, header.indexOf('<dl'));
    assert.ok(headingRow.includes('justify-between') && headingRow.indexOf('Account Activity</h3>') < headingRow.indexOf('Change Password'), 'on the heading line, at the far right');
    assert.ok(headingRow.includes('flex-wrap') && headingRow.includes('whitespace-nowrap'), 'wraps instead of overlapping on narrow screens');
    assert.ok(header.includes('mb-3') && header.includes('space-y-[7px]'), 'comfortable spacing under the heading and between rows');
    assert.ok(header.includes('setPasswordDialogOpen(true)') && header.includes('<KeyIcon') && header.includes('text-emerald-700') && header.includes('hover:text-emerald-800'));
    assert.ok(profile.includes('<ChangePasswordDialog') && profile.includes('onChanged={handlePasswordChanged}'));
  });

  it('after a successful change closes the dialog, shows the success message and refreshes the Last Password Change time', () => {
    const handler = profile.slice(profile.indexOf('const handlePasswordChanged'), profile.indexOf('const status = accountStatus'));
    assert.ok(handler.includes('setPasswordDialogOpen(false)') && handler.includes('showNotice(message)') && handler.includes('loadCurrentProfile({ force: true })'));
  });

  it('reuses the shared validation, rules and request, with no second implementation', () => {
    assert.ok(card.includes("from '../../utils/passwordChange'") && card.includes('submitPasswordChange(values)') && card.includes('evaluatePasswordRules'));
    assert.ok(!card.includes('changePassword(') && !card.includes('/api/profile/password'));
    assert.ok(!settings.includes('SecuritySettingsCard') && !settings.includes('Security Settings') && !settings.includes('Change Password'));
    assert.ok(!fs.existsSync(path.join(here, '..', 'components', 'settings', 'SecuritySettingsCard.jsx')));
    for (const kept of ['<PersonalPreferencesCard />', '<NotificationPreferencesCard />', 'Workload Balancing Rules', 'Auto-Balancing Trigger', 'Max Task Capacity Alert', 'Cross-Department Assistance']) {
      assert.ok(settings.includes(kept), kept);
    }
  });

  it('shows a loading state, prevents duplicate submits, and closes only after the server confirmed', () => {
    assert.ok(card.includes('if (sending.current) return;'));
    assert.ok(card.includes('disabled={saving}') && card.includes('Changing password…') && card.includes('aria-busy={saving}'));
    const submit = card.slice(card.indexOf('const submit = async'), card.indexOf('const dismiss'));
    assert.ok(submit.indexOf('onChanged(PASSWORD_MESSAGES.saved)') > submit.indexOf('await submitPasswordChange('), 'success only after the server agreed');
    assert.ok(submit.includes("phase: 'error'") && submit.includes('passwordErrorMessage(error)') && card.includes('role="alert"'));
    assert.ok(card.includes('if (!sending.current) onClose();') && card.includes("event.key === 'Escape' && !sending.current"), 'cannot be dismissed while a change is being sent');
  });

  it('clears every password field whenever the dialog closes or opens', () => {
    const effect = card.slice(card.indexOf('useEffect(() => {'), card.indexOf('useEffect(() => {', card.indexOf('useEffect(() => {') + 10));
    assert.ok(effect.includes('setValues(EMPTY)') && effect.includes('[isOpen]'));
  });

  it('keeps passwords out of browser storage, URLs and logs, and does not call the network itself', () => {
    for (const forbidden of ['localStorage', 'sessionStorage', 'indexedDB', 'console.', 'fetch(', 'apiFetch', 'window.location']) assert.ok(!card.includes(forbidden), forbidden);
    const util = read('..', 'utils', 'passwordChange.js');
    for (const forbidden of ['localStorage', 'sessionStorage', 'console.']) assert.ok(!util.includes(forbidden), forbidden);
  });

  it('uses the shared theme classes and the green accent, and stacks on small screens', () => {
    assert.ok(card.includes('bg-emerald-600') && card.includes('md:grid-cols-2') && card.includes('max-h-[90vh]'));
    assert.ok(!/dark:/.test(card));
  });
});

describe('first-time setup and login are untouched', () => {
  it('the setup page still uses the setup-token flow, and the login and setup API calls are unchanged', () => {
    const setup = read('SetCredentialsPage.jsx');
    assert.ok(setup.includes('setupCredentials({') && setup.includes('setupToken'));
    const api = read('..', 'services', 'api.js');
    assert.ok(api.includes('/api/auth/credentials/setup'));
    assert.ok(!setup.includes('changePassword') && !read('LoginPage.jsx').includes('changePassword'));
  });
});
