import { describe, it, beforeEach, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  NOTIFICATION_CATEGORIES, NOTIFICATION_MESSAGES, changeRequest, defaultPreferenceMap, loadNotificationPreferences,
  notificationErrorMessage, saveNotificationPreference, toPreferenceMap, withChange,
} from '../utils/notificationPreferences.js';
import { getNotificationPreferences, updateNotificationPreferences } from '../services/api.js';

const here = path.dirname(fileURLToPath(import.meta.url));
const read = (...parts) => fs.readFileSync(path.join(here, ...parts), 'utf8');

const realFetch = globalThis.fetch;
const realStorage = Object.getOwnPropertyDescriptor(globalThis, 'localStorage');
const realWindow = Object.getOwnPropertyDescriptor(globalThis, 'window');
let store;
const signInAs = (username) => { store.username = username; store.token = `jwt-${username}`; };

beforeEach(() => {
  store = {};
  Object.defineProperty(globalThis, 'localStorage', {
    configurable: true, writable: true,
    value: { getItem: (k) => (k in store ? store[k] : null), setItem: (k, v) => { store[k] = String(v); }, removeItem: (k) => { delete store[k]; } },
  });
  Object.defineProperty(globalThis, 'window', { configurable: true, writable: true, value: { location: { href: '/settings' } } });
});

afterEach(() => {
  globalThis.fetch = realFetch;
  if (realStorage) Object.defineProperty(globalThis, 'localStorage', realStorage); else delete globalThis.localStorage;
  if (realWindow) Object.defineProperty(globalThis, 'window', realWindow); else delete globalThis.window;
});

/** A fake server holding each user's saved rows, answering like the real endpoints (defaults for what was never saved). */
function fakeServer() {
  const rowsByUser = {};
  const calls = [];
  globalThis.fetch = async (url, options = {}) => {
    const user = options.headers.Authorization.replace('Bearer jwt-', '');
    calls.push({ user, method: options.method || 'GET', url, body: options.body ? JSON.parse(options.body) : null });
    rowsByUser[user] ||= {};
    if (options.method === 'PUT') {
      for (const change of JSON.parse(options.body).preferences) {
        if (change.category === 'SECURITY' && !change.email) {
          return { ok: false, status: 400, json: async () => ({ message: 'Security alerts are required and cannot be turned off.' }) };
        }
        rowsByUser[user][change.category] = { email: change.email };
      }
    }
    const preferences = NOTIFICATION_CATEGORIES.map((c) => ({
      category: c.key, locked: Boolean(c.locked),
      email: rowsByUser[user][c.key]?.email ?? c.defaults.email,
    }));
    return { ok: true, status: 200, json: async () => ({ preferences }) };
  };
  return { rowsByUser, calls };
}

describe('categories and defaults', () => {
  it('has exactly the five categories, in order, with the specified titles and descriptions', () => {
    assert.deepEqual(NOTIFICATION_CATEGORIES.map((c) => [c.key, c.title, c.description]), [
      ['TASK_ASSIGNED', 'Task Assigned / Reassigned', 'Get notified when a task is assigned or reassigned to you.'],
      ['TASK_STATUS', 'Task Status Updates', "Get notified when an assigned task's status changes."],
      ['WORKLOAD_ALERT', 'Workload Alerts', 'Get notified when your workload reaches HIGH or AT_RISK.'],
      ['TASK_REMINDER', 'Task Reminders', 'Receive reminders about pending or overdue tasks.'],
      ['SECURITY', 'Security Alerts', 'Receive important account and login security notifications.'],
    ]);
  });

  it('defaults: email on for assignment, workload and security; off for status and reminders', () => {
    assert.deepEqual(defaultPreferenceMap(), {
      TASK_ASSIGNED: { email: true, locked: false },
      TASK_STATUS: { email: false, locked: false },
      WORKLOAD_ALERT: { email: true, locked: false },
      TASK_REMINDER: { email: false, locked: false },
      SECURITY: { email: true, locked: true },
    });
  });

  it('there is no in-app channel anywhere in the model', () => {
    for (const row of Object.values(defaultPreferenceMap())) assert.deepEqual(Object.keys(row).sort(), ['email', 'locked']);
    assert.ok(!('inApp' in toPreferenceMap({ preferences: [{ category: 'TASK_STATUS', inApp: true, email: true }] }).TASK_STATUS), 'an old field in a response is dropped');
  });

  it('a response becomes a complete map: missing or unknown categories are handled, security is always on and locked', () => {
    assert.deepEqual(toPreferenceMap(undefined), defaultPreferenceMap());
    assert.deepEqual(toPreferenceMap({ preferences: [{ category: 'NOPE', email: false }] }), defaultPreferenceMap());
    const map = toPreferenceMap({ preferences: [
      { category: 'TASK_STATUS', email: true, locked: false },
      { category: 'SECURITY', email: false, locked: false }, // a wrong answer can never switch security off on screen
    ] });
    assert.deepEqual(map.TASK_STATUS, { email: true, locked: false });
    assert.deepEqual(map.SECURITY, { email: true, locked: true });
    assert.deepEqual(map.TASK_ASSIGNED, { email: true, locked: false }, 'untouched categories keep their defaults');
  });
});

describe('changes and the security lock', () => {
  const map = defaultPreferenceMap();

  it('switching the email switch changes only that category', () => {
    const next = withChange(map, 'TASK_STATUS', 'email', true);
    assert.deepEqual(next.TASK_STATUS, { email: true, locked: false });
    assert.deepEqual({ ...next, TASK_STATUS: map.TASK_STATUS }, map, 'nothing else changed');
    assert.deepEqual(map.TASK_STATUS, { email: false, locked: false }, 'the old map is not modified');
  });

  it('security alerts cannot be switched off from the screen, nor can unknown categories or channels be invented', () => {
    assert.equal(withChange(map, 'TASK_STATUS', 'inApp', false), map, 'in-app is not a channel any more');
    assert.equal(withChange(map, 'SECURITY', 'email', false), map);
    assert.equal(withChange(map, 'NOPE', 'email', false), map);
    assert.equal(withChange(map, 'TASK_STATUS', 'sms', true), map);
    assert.equal(changeRequest(map, 'SECURITY', 'email', false), null);
  });

  it('the request carries the category with its email switch only, and never a user id', () => {
    assert.deepEqual(changeRequest(map, 'TASK_REMINDER', 'email', true), { preferences: [{ category: 'TASK_REMINDER', email: true }] });
    assert.deepEqual(changeRequest(map, 'TASK_ASSIGNED', 'email', false), { preferences: [{ category: 'TASK_ASSIGNED', email: false }] });
    assert.equal(changeRequest(map, 'TASK_ASSIGNED', 'inApp', false), null);
  });

  it('a refused change is rejected without sending anything', async () => {
    let sent = false;
    await assert.rejects(saveNotificationPreference(map, 'SECURITY', 'email', false, { send: async () => { sent = true; return {}; } }),
      (error) => error.status === 400 && error.message === NOTIFICATION_MESSAGES.securityRequired);
    assert.equal(sent, false);
  });
});

describe('loading, saving and persistence (against a fake server)', () => {
  it('GET returns the defaults for a user who never saved anything, using the signed-in user\'s token', async () => {
    signInAs('maria');
    const { calls } = fakeServer();
    const map = await loadNotificationPreferences();
    assert.deepEqual(map, defaultPreferenceMap());
    assert.deepEqual(calls.map((c) => [c.user, c.method, c.url]), [['maria', 'GET', '/api/notification-preferences']]);
  });

  it('overlapping loads (a double mount) share a single request', async () => {
    signInAs('maria');
    const { calls } = fakeServer();
    const [a, b] = await Promise.all([loadNotificationPreferences(), loadNotificationPreferences()]);
    assert.deepEqual(a, b);
    assert.equal(calls.length, 1);
  });

  it('a save sends only the changed category and resolves with the server\'s complete answer', async () => {
    signInAs('maria');
    const { calls } = fakeServer();
    const saved = await saveNotificationPreference(defaultPreferenceMap(), 'TASK_STATUS', 'email', true);
    assert.deepEqual(saved.TASK_STATUS, { email: true, locked: false });
    assert.deepEqual(saved.SECURITY, { email: true, locked: true });
    assert.equal(calls.length, 1);
    assert.equal(calls[0].method, 'PUT');
    assert.deepEqual(calls[0].body, { preferences: [{ category: 'TASK_STATUS', email: true }] });
  });

  it('survives a refresh and signing out and in: a fresh load reads what was saved', async () => {
    signInAs('maria');
    fakeServer();
    await saveNotificationPreference(await loadNotificationPreferences(), 'WORKLOAD_ALERT', 'email', false);
    const afterRefresh = await loadNotificationPreferences(); // a new page load has nothing in memory
    assert.deepEqual(afterRefresh.WORKLOAD_ALERT, { email: false, locked: false });
  });

  it('Admin, Manager and Staff keep independent preferences', async () => {
    const { rowsByUser } = fakeServer();
    signInAs('admin');
    await saveNotificationPreference(defaultPreferenceMap(), 'TASK_ASSIGNED', 'email', false);
    signInAs('manager');
    await saveNotificationPreference(defaultPreferenceMap(), 'TASK_REMINDER', 'email', true);
    signInAs('staff');
    assert.deepEqual(await loadNotificationPreferences(), defaultPreferenceMap(), 'staff never changed anything');
    signInAs('admin');
    const admin = await loadNotificationPreferences();
    assert.equal(admin.TASK_ASSIGNED.email, false);
    assert.equal(admin.TASK_REMINDER.email, false, 'the manager\'s change did not reach the admin');
    assert.deepEqual(Object.keys(rowsByUser).sort(), ['admin', 'manager', 'staff']);
    assert.deepEqual(Object.keys(rowsByUser.admin), ['TASK_ASSIGNED']);
  });

  it('a failed request is never reported as saved: the server\'s 400 message is shown, anything else becomes the generic text', async () => {
    signInAs('maria');
    globalThis.fetch = async () => ({ ok: false, status: 500, json: async () => ({ message: 'java.lang.NullPointerException at X' }) });
    await assert.rejects(saveNotificationPreference(defaultPreferenceMap(), 'TASK_STATUS', 'email', true), (error) => {
      assert.equal(notificationErrorMessage(error, NOTIFICATION_MESSAGES.saveFailed), NOTIFICATION_MESSAGES.saveFailed);
      return true;
    });
    assert.equal(notificationErrorMessage({ status: 400, message: 'Send the categories to change.' }, 'x'), 'Send the categories to change.');
    assert.equal(notificationErrorMessage({ status: 400, message: 'x'.repeat(201) }, 'fallback'), 'fallback');
    assert.equal(notificationErrorMessage(new Error('Failed to fetch'), 'fallback'), 'fallback');
  });

  it('the two API calls use the shared authenticated fetch and the right verbs', async () => {
    signInAs('maria');
    const { calls } = fakeServer();
    await getNotificationPreferences();
    await updateNotificationPreferences({ preferences: [{ category: 'TASK_STATUS', email: true }] });
    assert.deepEqual(calls.map((c) => [c.method, c.url]), [['GET', '/api/notification-preferences'], ['PUT', '/api/notification-preferences']]);
  });
});

describe('Settings page: Notification Preferences card', () => {
  const card = read('..', 'components', 'settings', 'NotificationPreferencesCard.jsx');
  const settings = read('SettingsPage.jsx');

  it('has the specified title and description, and a row per category with a single email switch', () => {
    assert.ok(card.includes('>Notification Preferences</h2>'));
    assert.ok(card.includes('Choose which updates Taskotel emails you.'));
    assert.ok(card.includes('NOTIFICATION_CATEGORIES.map((category)'));
    assert.equal((card.match(/<Switch\b/g) || []).length, 1, 'one email switch per row');
    assert.ok(card.includes("toggle(category.key, 'email')") && !card.includes("'inApp'"));
    assert.ok(card.includes('>Notification</span>') && card.includes('>Email</span>'), 'column headings');
    assert.ok(!/in-app/i.test(card) && !/inApp/.test(card), 'no In-app column, switch or label');
    assert.ok(card.includes('md:justify-end') && card.includes('text-right'), 'the Email column is aligned to the right');
    assert.ok(card.includes('role="switch"') && card.includes('aria-checked={checked}'));
    assert.ok(card.includes('{category.title}') && card.includes('{category.description}'));
  });

  it('is placed on Settings between Personal Preferences and Workload Balancing Rules, which are unchanged', () => {
    const order = ['<PersonalPreferencesCard />', '<NotificationPreferencesCard />', 'Workload Balancing Rules', '<AccountActivityCard />'].map((s) => settings.indexOf(s));
    assert.ok(order.every((p) => p > 0));
    assert.deepEqual([...order].sort((a, b) => a - b), order);
    for (const kept of ['Auto-Balancing Trigger', 'Max Task Capacity Alert', 'Cross-Department Assistance']) assert.ok(settings.includes(kept), kept);
  });

  it('loads once when it opens, with loading and load-error (with retry) states', () => {
    assert.equal((card.match(/loadNotificationPreferences\(\)/g) || []).length, 1);
    assert.ok(card.includes('NOTIFICATION_MESSAGES.loading') && card.includes('NOTIFICATION_MESSAGES.loadFailed') && card.includes('Try again'));
    assert.ok(card.includes('[attempt]'), 'a retry loads again');
    for (const forbidden of ['fetch(', 'apiFetch', 'localStorage', 'getProfile']) assert.ok(!card.includes(forbidden), forbidden);
  });

  it('saves each switch as it is flipped, locks the switches while saving, and puts the switch back if saving fails', () => {
    const toggle = card.slice(card.indexOf('const toggle = async'), card.indexOf('return (', card.indexOf('const toggle = async')));
    assert.ok(toggle.includes('if (!prefs || saving || prefs[category].locked) return;'));
    assert.ok(toggle.indexOf('setPrefs(withChange(') < toggle.indexOf('await saveNotificationPreference('), 'shown at once, then saved');
    assert.ok(toggle.includes('setPrefs(await saveNotificationPreference(previous, category, channel, value))'), 'the server\'s answer replaces the guess');
    assert.ok(toggle.includes('setPrefs(previous)') && toggle.includes("phase: 'error'"), 'revert and report on failure');
    assert.ok(toggle.indexOf("phase: 'saved'") > toggle.indexOf('await saveNotificationPreference('), 'success is only reported after the server agreed');
    assert.ok(card.includes('disabled={row.locked || saving}'));
    for (const text of ["status.phase === 'saving'", "status.phase === 'saved'", "status.phase === 'error'", 'role="alert"', 'aria-busy']) assert.ok(card.includes(text), text);
  });

  it('security alerts show as required, locked on, with an explanation', () => {
    assert.ok(card.includes('row.locked && (') && card.includes('Required'));
    assert.ok(card.includes('NOTIFICATION_MESSAGES.securityRequired'));
    assert.ok(NOTIFICATION_MESSAGES.securityRequired.includes('always on'));
  });

  it('keeps the card style, is responsive, and the switch knob stays white in both themes', () => {
    assert.ok(card.includes('bg-white rounded-2xl border border-gray-200 shadow-sm overflow-hidden'));
    assert.ok(card.includes('md:grid-cols-[1fr_5rem]') && card.includes('md:hidden'), 'a table on desktop, stacked rows on phones');
    assert.ok(card.includes("backgroundColor: '#ffffff'") && card.includes('bg-emerald-600'));
    assert.ok(!/dark:/.test(card), 'colours come from the shared theme classes');
  });
});
