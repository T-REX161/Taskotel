import { describe, it, beforeEach, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { formatDate, formatDateParts, formatDateTimeParts, formatTime, formatTimeParts } from '../utils/dateTimeFormat.js';
import {
  DEFAULT_PREFERENCES, getPreferences, normalizePreferences, preferencesFor, preferencesLoaded, resolveTheme, savePreferences,
} from '../utils/preferences.js';
import { getCurrentProfileSnapshot, loadCurrentProfile, resetCurrentProfile, setCurrentProfile } from '../utils/currentUserProfile.js';
import { updatePreferences } from '../services/api.js';
import { formatDate as formatTaskDate, formatDueDate, formatTime as formatTaskTime } from '../components/tasks/taskFormatters.js';
import { formatAssignedAt, splitAssignedAt } from '../utils/assignmentUtils.js';
import { formatDashboardDate } from '../utils/dashboardView.js';
import { formatActivityTime } from '../utils/accountActivity.js';
import { formatCreatedDate } from '../components/users/userListUtils.js';

const here = path.dirname(fileURLToPath(import.meta.url));
const read = (...parts) => fs.readFileSync(path.join(here, ...parts), 'utf8');

const realFetch = globalThis.fetch;
const realStorage = Object.getOwnPropertyDescriptor(globalThis, 'localStorage');
const realWindow = Object.getOwnPropertyDescriptor(globalThis, 'window');
let store;

const signInAs = (username) => { store.username = username; store.token = `jwt-${username}`; };
const profileOf = (username, preferences) => ({ username, role: 'STAFF', displayName: username, preferences });

beforeEach(() => {
  store = {};
  Object.defineProperty(globalThis, 'localStorage', {
    configurable: true, writable: true,
    value: { getItem: (k) => (k in store ? store[k] : null), setItem: (k, v) => { store[k] = String(v); }, removeItem: (k) => { delete store[k]; } },
  });
  Object.defineProperty(globalThis, 'window', { configurable: true, writable: true, value: { location: { href: '/settings' } } });
  resetCurrentProfile();
});

afterEach(() => {
  globalThis.fetch = realFetch;
  if (realStorage) Object.defineProperty(globalThis, 'localStorage', realStorage); else delete globalThis.localStorage;
  if (realWindow) Object.defineProperty(globalThis, 'window', realWindow); else delete globalThis.window;
  resetCurrentProfile();
});

describe('date and time formatting', () => {
  it('writes dates in all three formats', () => {
    assert.equal(formatDateParts(2026, 11, 2, 'DMY'), '02/11/2026');
    assert.equal(formatDateParts(2026, 11, 2, 'MDY'), '11/02/2026');
    assert.equal(formatDateParts(2026, 11, 2, 'YMD'), '2026-11-02');
    assert.equal(formatDateParts(2026, 1, 5), '05/01/2026', 'DD/MM/YYYY is the default');
  });

  it('writes times in 12-hour and 24-hour format, including midnight and noon', () => {
    assert.equal(formatTimeParts(17, 5, 'H12'), '5:05 PM');
    assert.equal(formatTimeParts(17, 5, 'H24'), '17:05');
    assert.equal(formatTimeParts(0, 0, 'H12'), '12:00 AM');
    assert.equal(formatTimeParts(0, 0, 'H24'), '00:00');
    assert.equal(formatTimeParts(12, 30, 'H12'), '12:30 PM');
    assert.equal(formatTimeParts(12, 30, 'H24'), '12:30');
    assert.equal(formatTimeParts(9, 7), '9:07 AM', '12-hour is the default');
  });

  it('writes a date and time together, and formats JavaScript dates', () => {
    assert.equal(formatDateTimeParts(2026, 11, 2, 17, 30, { dateFormat: 'MDY', timeFormat: 'H24' }), '11/02/2026, 17:30');
    assert.equal(formatDateTimeParts(2026, 11, 2, 17, 30), '02/11/2026, 5:30 PM');
    const d = new Date(2026, 10, 2, 8, 9);
    assert.equal(formatDate(d, 'YMD'), '2026-11-02');
    assert.equal(formatTime(d, 'H24'), '08:09');
  });
});

describe('preferences: values, defaults and theme options', () => {
  it('fills in the defaults (light, DD/MM/YYYY, 12-hour) for anything missing or unrecognised', () => {
    assert.deepEqual(normalizePreferences(undefined), DEFAULT_PREFERENCES);
    assert.deepEqual(normalizePreferences({ themePreference: 'PURPLE', dateFormat: 'x', timeFormat: 'y' }), DEFAULT_PREFERENCES);
    assert.deepEqual(normalizePreferences({ themePreference: 'DARK', dateFormat: 'YMD', timeFormat: 'H24' }), { themePreference: 'DARK', dateFormat: 'YMD', timeFormat: 'H24' });
    assert.deepEqual(DEFAULT_PREFERENCES, { themePreference: 'LIGHT', dateFormat: 'DMY', timeFormat: 'H12' });
  });

  it('resolves every appearance option; System follows the operating system', () => {
    assert.equal(resolveTheme('LIGHT', true), 'light');
    assert.equal(resolveTheme('LIGHT', false), 'light');
    assert.equal(resolveTheme('DARK', false), 'dark');
    assert.equal(resolveTheme('DARK', true), 'dark');
    assert.equal(resolveTheme('SYSTEM', true), 'dark');
    assert.equal(resolveTheme('SYSTEM', false), 'light');
    assert.equal(resolveTheme('nonsense', true), 'light');
  });
});

describe('preferences are per user', () => {
  it('each signed-in user reads their own preferences; a user who never chose gets the defaults', () => {
    setCurrentProfile(profileOf('maria', { themePreference: 'DARK', dateFormat: 'MDY', timeFormat: 'H24' }));
    signInAs('maria');
    assert.deepEqual(getPreferences(), { themePreference: 'DARK', dateFormat: 'MDY', timeFormat: 'H24' });
    assert.equal(preferencesLoaded(), true);

    signInAs('rocky'); // someone else on the same browser: Maria's cached profile is never used for them
    assert.deepEqual(getPreferences(), DEFAULT_PREFERENCES);
    assert.equal(preferencesLoaded(), false);

    setCurrentProfile(profileOf('rocky', { themePreference: 'SYSTEM', dateFormat: 'YMD', timeFormat: 'H12' }));
    assert.deepEqual(getPreferences(), { themePreference: 'SYSTEM', dateFormat: 'YMD', timeFormat: 'H12' });
    assert.deepEqual(preferencesFor(getCurrentProfileSnapshot(), 'maria'), DEFAULT_PREFERENCES, 'the cache holds one user at a time');
  });

  it('Admin, Manager and Staff keep independent preferences (each loads their own profile)', async () => {
    const server = {
      admin: { themePreference: 'DARK', dateFormat: 'MDY', timeFormat: 'H24' },
      manager: { themePreference: 'SYSTEM', dateFormat: 'YMD', timeFormat: 'H12' },
      staff: { themePreference: 'LIGHT', dateFormat: 'DMY', timeFormat: 'H12' },
    };
    for (const [username, preferences] of Object.entries(server)) {
      signInAs(username);
      globalThis.fetch = async (url, options) => {
        assert.equal(options.headers.Authorization, `Bearer jwt-${username}`, 'each request carries only its own token');
        return { ok: true, status: 200, json: async () => profileOf(username, preferences) };
      };
      await loadCurrentProfile({ force: true, username });
      assert.deepEqual(getPreferences(), preferences, username);
    }
  });
});

describe('every date and time in Taskotel follows the chosen formats', () => {
  const choose = (preferences) => { signInAs('maria'); setCurrentProfile(profileOf('maria', preferences)); };

  it('defaults: DD/MM/YYYY and 12-hour everywhere', () => {
    signInAs('maria');
    assert.equal(formatTaskDate('2026-10-13'), '13/10/2026');
    assert.equal(formatTaskTime('17:00'), '5:00 PM');
    assert.equal(formatDueDate('2026-10-13', '17:00'), '13/10/2026, 5:00 PM');
  });

  it('MM/DD/YYYY with 24-hour: tasks, assignments, dashboard, account activity and user list all change together', () => {
    choose({ themePreference: 'LIGHT', dateFormat: 'MDY', timeFormat: 'H24' });
    assert.equal(formatTaskDate('2026-10-13'), '10/13/2026');
    assert.equal(formatTaskTime('5:00 PM'), '17:00');
    assert.equal(formatTaskTime('09:05'), '09:05');
    assert.equal(formatDueDate({ dueDate: '2026-10-13', deadlineTime: '17:00:00' }), '10/13/2026, 17:00');
    const past = formatAssignedAt(new Date(2026, 9, 16, 14, 29), new Date(2026, 9, 20));
    assert.equal(past, '10/16/2026, 14:29');
    assert.deepEqual(splitAssignedAt(past), { date: '10/16/2026', time: '14:29' }, '24-hour times split onto their own line too');
    assert.equal(formatAssignedAt(new Date(2026, 9, 20, 8, 30), new Date(2026, 9, 20, 12, 0)), 'Today, 08:30');
    assert.equal(formatDashboardDate(new Date(2026, 8, 29)), 'Tue, 09/29/2026');
    assert.equal(formatActivityTime('2026-11-02T17:27:51.123'), '11/02/2026, 17:27');
    assert.equal(formatCreatedDate('2026-08-20T08:00:00'), '08/20/2026');
  });

  it('YYYY-MM-DD with 12-hour', () => {
    choose({ themePreference: 'DARK', dateFormat: 'YMD', timeFormat: 'H12' });
    assert.equal(formatDueDate('2026-10-13', '17:00'), '2026-10-13, 5:00 PM');
    assert.equal(formatActivityTime('2026-11-02T00:05:00'), '2026-11-02, 12:05 AM');
    assert.equal(formatCreatedDate('2026-08-20T08:00:00'), '2026-08-20');
  });

  it('changing the choice changes the very next result, and unrecognised input is left as it was', () => {
    choose({ themePreference: 'LIGHT', dateFormat: 'DMY', timeFormat: 'H12' });
    assert.equal(formatTaskDate('2026-10-13'), '13/10/2026');
    choose({ themePreference: 'LIGHT', dateFormat: 'YMD', timeFormat: 'H24' });
    assert.equal(formatTaskDate('2026-10-13'), '2026-10-13');
    assert.equal(formatTaskDate('not a date'), 'not a date');
    assert.equal(formatTaskTime('invalid'), null);
    assert.equal(formatActivityTime(null), null);
  });
});

describe('saving and persistence', () => {
  it('sends the complete set to the server as the signed-in user, and the answer replaces the shared profile', async () => {
    signInAs('maria');
    setCurrentProfile(profileOf('maria', { themePreference: 'LIGHT', dateFormat: 'MDY', timeFormat: 'H24' }));
    const calls = [];
    globalThis.fetch = async (url, options) => {
      calls.push({ url, options });
      const sent = JSON.parse(options.body);
      return { ok: true, status: 200, json: async () => profileOf('maria', sent) };
    };

    const saved = await savePreferences({ themePreference: 'DARK' });

    assert.equal(calls.length, 1, 'one request, no extra profile request');
    assert.equal(calls[0].url, '/api/profile/preferences');
    assert.equal(calls[0].options.method, 'PUT');
    assert.equal(calls[0].options.headers.Authorization, 'Bearer jwt-maria');
    assert.deepEqual(JSON.parse(calls[0].options.body), { themePreference: 'DARK', dateFormat: 'MDY', timeFormat: 'H24' }, 'the change plus the other two as they were; no user id');
    assert.deepEqual(saved, { themePreference: 'DARK', dateFormat: 'MDY', timeFormat: 'H24' });
    assert.deepEqual(getPreferences(), saved, 'formatters and theme read the new value from the same shared profile');
  });

  it('a refused save (400) leaves the shared preferences untouched and reports the server message', async () => {
    signInAs('maria');
    setCurrentProfile(profileOf('maria', { themePreference: 'DARK', dateFormat: 'YMD', timeFormat: 'H24' }));
    globalThis.fetch = async () => ({ ok: false, status: 400, json: async () => ({ message: 'Time format must be 12-hour or 24-hour.' }) });
    await assert.rejects(savePreferences({ timeFormat: 'H12' }), (error) => error.status === 400 && error.message === 'Time format must be 12-hour or 24-hour.');
    assert.deepEqual(getPreferences(), { themePreference: 'DARK', dateFormat: 'YMD', timeFormat: 'H24' });
  });

  it('survives a refresh and signing out and in: a fresh load reads the saved choice from the server', async () => {
    signInAs('maria');
    const serverRow = { themePreference: 'LIGHT', dateFormat: 'DMY', timeFormat: 'H12' };
    globalThis.fetch = async (url, options) => {
      if (options?.method === 'PUT') Object.assign(serverRow, JSON.parse(options.body));
      return { ok: true, status: 200, json: async () => profileOf('maria', { ...serverRow }) };
    };
    await loadCurrentProfile({ force: true, username: 'maria' });
    await savePreferences({ themePreference: 'SYSTEM', dateFormat: 'YMD', timeFormat: 'H24' });

    resetCurrentProfile(); // a page refresh (or a new session) starts with nothing in memory
    assert.deepEqual(getPreferences(), DEFAULT_PREFERENCES, 'nothing is remembered in the browser itself');
    await loadCurrentProfile({ force: true, username: 'maria' });
    assert.deepEqual(getPreferences(), { themePreference: 'SYSTEM', dateFormat: 'YMD', timeFormat: 'H24' });
  });

  it('PUT /api/profile/preferences is the one call, using the shared authenticated fetch', async () => {
    signInAs('maria');
    let seen;
    globalThis.fetch = async (url, options) => { seen = { url, options }; return { ok: true, status: 200, json: async () => ({}) }; };
    await updatePreferences({ themePreference: 'DARK', dateFormat: 'DMY', timeFormat: 'H12' });
    assert.equal(seen.url, '/api/profile/preferences');
    assert.equal(seen.options.method, 'PUT');
    assert.equal(seen.options.headers['Content-Type'], 'application/json');
  });
});

describe('Settings page: Personal Preferences card', () => {
  const card = read('..', 'components', 'settings', 'PersonalPreferencesCard.jsx');
  const settings = read('SettingsPage.jsx');

  it('offers exactly the requested options, as radio segments (not checkboxes)', () => {
    assert.ok(card.includes("{ value: 'LIGHT', label: 'Light' }") && card.includes("{ value: 'DARK', label: 'Dark' }") && card.includes("{ value: 'SYSTEM', label: 'System' }"));
    assert.ok(card.includes("label: 'DD/MM/YY'") && card.includes("label: 'MM/DD/YY'") && card.includes("label: 'YY-MM-DD'"));
    assert.ok(card.includes("label: '12-hour'") && card.includes("label: '24-hour'"));
    assert.equal((card.match(/\{ value: '/g) || []).length, 8, 'three + three + two options, nothing else');
    assert.ok(card.includes('type="radio"') && card.includes('role="radiogroup"') && !card.includes('type="checkbox"'));
    for (const title of ['>Appearance<', '>Date Format<', '>Time Format<']) assert.ok(card.includes(`title="${title.slice(1, -1)}"`), title);
  });

  it('the date labels are short (YY) but the codes are unchanged and real dates keep four-digit years', () => {
    assert.ok(card.includes("{ value: 'DMY', label: 'DD/MM/YY' }") && card.includes("{ value: 'MDY', label: 'MM/DD/YY' }") && card.includes("{ value: 'YMD', label: 'YY-MM-DD' }"));
    assert.ok(!card.includes('YYYY'), 'no four-letter year left in the visible labels');
    assert.equal(formatDateParts(2026, 11, 2, 'DMY'), '02/11/2026');
    assert.equal(formatDateParts(2026, 11, 2, 'MDY'), '11/02/2026');
    assert.equal(formatDateParts(2026, 11, 2, 'YMD'), '2026-11-02');
    assert.equal(formatDateTimeParts(2026, 11, 2, 17, 30, { dateFormat: 'DMY', timeFormat: 'H12' }), '02/11/2026, 5:30 PM', 'the example line');
    for (const code of ['DMY', 'MDY', 'YMD']) assert.match(formatDateParts(2026, 1, 5, code), /2026/);
  });

  it('the Date Format control is a compact 290px, single-line control; Appearance and Time Format keep the standard one', () => {
    assert.ok(card.includes("compact ? 'sm:w-[290px]' : 'sm:w-auto'"), '290px wide from the sm breakpoint, full width on phones');
    assert.ok(card.includes("compact ? 'px-0.5 min-[380px]:px-1 text-xs min-[380px]:text-[13px] sm:text-sm' : 'px-4 text-sm'"), 'tighter padding only when compact');
    assert.ok(card.includes('whitespace-nowrap'), 'labels never wrap');
    const usage = (name) => card.slice(card.indexOf(`<Segmented name="${name}"`), card.indexOf('/>', card.indexOf(`<Segmented name="${name}"`)));
    assert.ok(/\bcompact\b/.test(usage('date-format')), 'the date control is compact');
    assert.ok(!/\bcompact\b/.test(usage('appearance')) && !/\bcompact\b/.test(usage('time-format')), 'the other two are not');
    assert.equal((card.match(/\bcompact\b[^-]/g) || []).filter((m) => m.startsWith('compact')).length > 0, true);
  });

  it('appears on the Settings page without touching Workload Balancing Rules or Account Activity', () => {
    assert.ok(settings.includes('<PersonalPreferencesCard />') && !settings.includes('AccountActivityCard'));
    for (const kept of ['Workload Balancing Rules', 'Auto-Balancing Trigger', 'Max Task Capacity Alert', 'Cross-Department Assistance']) assert.ok(settings.includes(kept), kept);
  });

  it('shows loading, saving, success and error states, and locks the controls while loading or saving', () => {
    for (const text of ['PREFERENCE_MESSAGES.loading', "status.phase === 'saving'", "status.phase === 'saved'", "status.phase === 'error'", 'role="alert"', 'aria-busy']) {
      assert.ok(card.includes(text), text);
    }
    assert.ok(card.includes('const locked = !loaded || saving;') && card.includes('if (locked || prefs[field] === value) return;'));
    const messages = read('..', 'utils', 'preferences.js');
    for (const text of ['Loading your preferences…', 'Saving…', 'Preferences saved.', 'Could not save your preference. Please try again.']) assert.ok(messages.includes(text), text);
  });

  it('applies the theme at once, saves through the shared helper, and puts the old theme back if saving fails', () => {
    const change = card.slice(card.indexOf('const change = async'), card.indexOf('return (', card.indexOf('const change = async')));
    assert.ok(change.indexOf('setPreference(value') < change.indexOf('await savePreferences'), 'theme first, then the save');
    assert.ok(change.includes('{ persist: false }'), 'this card saves with its own states, so the theme does not save twice');
    assert.ok(change.includes('setPreference(previous, null, { persist: false })'), 'revert on failure');
    assert.ok(change.includes('savePreferences({ [field]: value }, { current: prefs })'));
  });

  it('reads the shared profile and keeps no preference state or storage of its own', () => {
    assert.equal((card.match(/useCurrentUserProfile\(\)/g) || []).length, 1);
    for (const forbidden of ['localStorage', 'sessionStorage', 'getProfile', 'loadCurrentProfile', 'apiFetch', 'fetch(']) assert.ok(!card.includes(forbidden), forbidden);
  });
});

describe('theme wiring: one source of truth', () => {
  const context = read('..', 'theme', 'ThemeContext.jsx');
  const shell = read('..', 'components', 'layout', 'AppShell.jsx');
  const header = read('..', 'components', 'layout', 'Header.jsx');

  it('Light, Dark and System are all handled; System follows the operating system live', () => {
    assert.ok(context.includes("const PREFERENCES = ['LIGHT', 'DARK', 'SYSTEM'];"));
    assert.ok(context.includes("window.matchMedia?.('(prefers-color-scheme: dark)')") && context.includes("addEventListener?.('change', onChange)"));
    assert.ok(context.includes('const theme = resolveTheme(preference, systemDark);'));
  });

  it('the signed-in user\'s saved preference replaces the browser cache, and only a changed saved value does', () => {
    assert.ok(context.includes('profile?.preferences?.themePreference'));
    assert.ok(context.includes('[savedPreference]'), 'reacts to the saved value, never to local changes that are still being saved');
    assert.ok(context.includes("storePreference(savedPreference)"));
  });

  it('the header toggle also saves to the account, but never before the profile has loaded (it could overwrite other preferences)', () => {
    assert.ok(context.includes('if (persist && preferencesLoaded()) savePreferences({ themePreference: value }).catch(() => {});'));
    assert.ok(header.includes('toggleTheme('), 'the toggle is unchanged');
  });

  it('the browser key is only a first-paint cache of the preference, in one place', () => {
    const users = ['components', 'pages', 'utils', 'hooks'].flatMap((dir) => {
      const walk = (d) => fs.readdirSync(path.join(here, '..', d), { withFileTypes: true });
      return walk(dir).filter((e) => e.isFile() && /\.jsx?$/.test(e.name) && !/\.test\./.test(e.name))
        .map((e) => [`${dir}/${e.name}`, read('..', dir, e.name)]);
    });
    for (const [file, source] of users) assert.ok(!source.includes('taskotel-theme') && !source.includes('THEME_STORAGE_KEY'), `${file} must not keep its own theme storage`);
  });

  it('date and time changes reach every page: the content is remounted on a format change, but not on Settings', () => {
    assert.ok(shell.includes("const contentKey = currentNav === 'Settings' ? 'settings' : `${dateFormat}|${timeFormat}`;"));
    assert.ok(shell.includes('<div key={contentKey} className="max-w-[1440px] mx-auto w-full">'));
    assert.equal((shell.match(/useCurrentUserProfile\(\)/g) || []).length, 1);
  });
});
