import { describe, it, beforeEach, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { updateDisplayName } from '../services/api.js';
import { DISPLAY_NAME_MAX, DISPLAY_NAME_MESSAGES, preferredName, shownName, validateDisplayName } from '../utils/displayName.js';
import { getCurrentProfileSnapshot, profileFor, resetCurrentProfile, setCurrentProfile } from '../utils/currentUserProfile.js';

// Same function the Header and Dashboard use (copied out of the JSX file, which node cannot import)
const headerSource = fs.readFileSync(path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'components', 'layout', 'Header.jsx'), 'utf8');
const describeUserSource = headerSource.slice(headerSource.indexOf('export function describeUser'), headerSource.indexOf('export default function Header'));
const describeUser = new Function(`${describeUserSource.replace('export function', 'function')}; return describeUser;`)();

const here = path.dirname(fileURLToPath(import.meta.url));
const read = (...parts) => fs.readFileSync(path.join(here, ...parts), 'utf8');
const page = read('ProfilePage.jsx');
const header = read('..', 'components', 'layout', 'Header.jsx');

describe('display name validation', () => {
  it('accepts normal names, trims them and counts characters (not UTF-16 units)', () => {
    assert.equal(validateDisplayName('Ada Lovelace'), null);
    assert.equal(validateDisplayName('  Zoë O\'Neil-Smith  '), null);
    assert.equal(validateDisplayName('😀'.repeat(DISPLAY_NAME_MAX)), null);
    assert.equal(validateDisplayName('x'.repeat(DISPLAY_NAME_MAX)), null);
  });

  it('refuses empty, blank, missing, too long and control-character names with a clear message', () => {
    for (const empty of ['', '   ', null, undefined]) assert.equal(validateDisplayName(empty), DISPLAY_NAME_MESSAGES.empty);
    assert.equal(validateDisplayName('x'.repeat(DISPLAY_NAME_MAX + 1)), DISPLAY_NAME_MESSAGES.tooLong);
    assert.equal(validateDisplayName('line\nbreak'), DISPLAY_NAME_MESSAGES.invalid);
    assert.equal(validateDisplayName('tab\there'), DISPLAY_NAME_MESSAGES.invalid);
  });

  it('shows the saved name, else the username with a capital first letter', () => {
    assert.equal(shownName({ displayName: 'Ada L' }, 'ada'), 'Ada L');
    assert.equal(shownName({ displayName: '  ' }, 'ada'), 'Ada');
    assert.equal(shownName(null, 'maria'), 'Maria');
    assert.equal(shownName(null, ''), 'Account');
  });
});

describe('display name API', () => {
  const realFetch = globalThis.fetch;
  const realStorage = Object.getOwnPropertyDescriptor(globalThis, 'localStorage');
  const realWindow = Object.getOwnPropertyDescriptor(globalThis, 'window');
  beforeEach(() => {
    const store = { token: 'jwt-token', username: 'maria', role: 'MANAGER' };
    Object.defineProperty(globalThis, 'localStorage', {
      configurable: true, writable: true,
      value: { getItem: (k) => (k in store ? store[k] : null), setItem: () => {}, removeItem: () => {} },
    });
    Object.defineProperty(globalThis, 'window', { configurable: true, writable: true, value: { location: { href: '/profile' } } });
    resetCurrentProfile();
  });
  afterEach(() => {
    globalThis.fetch = realFetch;
    if (realStorage) Object.defineProperty(globalThis, 'localStorage', realStorage); else delete globalThis.localStorage;
    if (realWindow) Object.defineProperty(globalThis, 'window', realWindow); else delete globalThis.window;
  });

  it('PUTs only the new name to the own-profile endpoint (never a user id) with the token', async () => {
    const calls = [];
    globalThis.fetch = async (url, options) => {
      calls.push({ url, options });
      return { ok: true, status: 200, json: async () => ({ username: 'maria', displayName: 'Maria G' }) };
    };
    const updated = await updateDisplayName('Maria G');
    assert.equal(calls[0].url, '/api/profile/display-name');
    assert.equal(calls[0].options.method, 'PUT');
    assert.deepEqual(JSON.parse(calls[0].options.body), { displayName: 'Maria G' });
    assert.equal(calls[0].options.headers.Authorization, 'Bearer jwt-token');
    assert.equal(updated.displayName, 'Maria G');
  });

  it('surfaces the server message on a 400', async () => {
    globalThis.fetch = async () => ({ ok: false, status: 400, json: async () => ({ message: DISPLAY_NAME_MESSAGES.empty }) });
    await assert.rejects(updateDisplayName(''), (error) => error.status === 400 && error.message === DISPLAY_NAME_MESSAGES.empty);
  });

  it('the shared profile takes the updated name, so every reader changes at once', () => {
    setCurrentProfile({ username: 'maria', displayName: 'Old', profileImageUrl: null });
    setCurrentProfile({ username: 'maria', displayName: 'New', profileImageUrl: null });
    assert.equal(getCurrentProfileSnapshot().profile.displayName, 'New');
  });
});

describe('profile page: display name', () => {
  it('shows the display name in the banner with the role badge, and the username separately as @username', () => {
    assert.ok(page.includes('{displayName}</h2>') && page.includes('{role}') && page.includes('@{username}'));
  });

  it('has exactly three rows of two fields: Display Name | Username, Status | Email Verified, System Role | Access Scope', () => {
    const labels = ['>Display Name</label>', 'label="Username"', 'label="Status"', 'label="Email Verified"', 'label="System Role"', 'label="Access Scope"'];
    const positions = labels.map((label) => page.indexOf(label));
    assert.ok(positions.every((p) => p > 0), 'all six fields present');
    assert.deepEqual([...positions].sort((a, b) => a - b), positions, 'field order');
    const grid = page.slice(page.indexOf('grid grid-cols-1 md:grid-cols-2 gap-x-8'), page.indexOf('</section>', page.indexOf('grid grid-cols-1 md:grid-cols-2 gap-x-8')));
    assert.equal((grid.match(/<DetailField /g) || []).length + (grid.match(/<label htmlFor="display-name"/g) || []).length, 6, 'six fields, none extra');
    assert.ok(grid.startsWith('grid grid-cols-1 md:grid-cols-2'), 'one column on mobile, two on desktop');
  });

  it('reads Status and Email Verified from the backend profile, never hardcoded', () => {
    assert.ok(page.includes('profile?.active') && page.includes('profile?.emailVerified'));
    assert.ok(!page.includes('Active & Authenticated'), 'no constant "Active & Authenticated"');
    assert.ok(page.includes("flagText(profile?.active, 'Active', 'Inactive')") && page.includes("flagText(profile?.emailVerified, 'Yes', 'No')"));
    assert.ok(page.includes('not whether this browser is signed in'), 'labelled as email verification, not a login session');
  });

  it('keeps the username read-only: only the display name has an input and a save', () => {
    assert.equal((page.match(/<input\b/g) || []).length, 2, 'the file picker and the display name input only');
    assert.ok(page.includes('updateDisplayName(nameDraft.trim())') && !page.includes('updateUsername'));
  });

  it('validates before saving, blocks duplicate submits, and shows errors and success', () => {
    const submit = page.slice(page.indexOf('const handleNameSubmit'), page.indexOf('const chooseFile'));
    assert.ok(submit.indexOf('if (savingName) return;') < submit.indexOf('validateDisplayName(nameDraft)'));
    assert.ok(submit.indexOf('validateDisplayName(nameDraft)') < submit.indexOf('await updateDisplayName'));
    assert.ok(submit.includes('setCurrentProfile(updated)') && submit.includes('setNameSaved(true)') && submit.includes('finally'));
    assert.ok(page.includes('disabled={savingName}') && page.includes('Saving…') && page.includes('role="alert"'));
    assert.ok(page.includes('maxLength={DISPLAY_NAME_MAX}') && page.includes('DISPLAY_NAME_MESSAGES.saved'));
  });

  it('offers Edit, Save and Cancel (Escape cancels)', () => {
    assert.ok(page.includes('Edit\n') && page.includes("'Save'") && page.includes('Cancel') && page.includes("event.key === 'Escape'"));
  });
});

describe('dashboard welcome name', () => {
  const dashboard = read('Dashboard.jsx');
  // The welcome name exactly as the Dashboard computes it, from the shared profile state
  const welcome = (username, role) => preferredName(profileFor(getCurrentProfileSnapshot(), username), describeUser(username, role).displayName);
  beforeEach(() => resetCurrentProfile());

  it('uses the shared profile hook (no second profile request or state) before any early return', () => {
    assert.ok(dashboard.includes("import useCurrentUserProfile from '../hooks/useCurrentUserProfile'"));
    assert.equal((dashboard.match(/useCurrentUserProfile\(\)/g) || []).length, 1);
    assert.ok(dashboard.indexOf('useCurrentUserProfile()') < dashboard.indexOf('if (isLoading)'), 'hook runs on every render');
    assert.ok(!dashboard.includes('getProfile') && !dashboard.includes('loadCurrentProfile'), 'no duplicate fetch');
    assert.ok(dashboard.includes('preferredName(profile, describeUser(getUsername(), userRole).displayName)'));
    assert.ok(dashboard.includes('Welcome back, {welcomeName}!'));
  });

  it('shows the saved display name', () => {
    setCurrentProfile({ username: 'ayush', role: 'ADMIN', displayName: 'Ayush Mallick', profileImageUrl: null });
    assert.equal(welcome('ayush', 'ADMIN'), 'Ayush Mallick');
  });

  it('falls back to the username-derived name while the profile has not loaded, has no name, or is another user\'s', () => {
    assert.equal(welcome('ayush', 'ADMIN'), 'Ayush', 'not loaded');
    setCurrentProfile({ username: 'ayush', displayName: null });
    assert.equal(welcome('ayush', 'ADMIN'), 'Ayush', 'no display name');
    setCurrentProfile({ username: 'ayush', displayName: '   ' });
    assert.equal(welcome('ayush', 'ADMIN'), 'Ayush', 'blank display name');
    setCurrentProfile({ username: 'maria', displayName: 'Maria G' });
    assert.equal(welcome('ayush', 'ADMIN'), 'Ayush', 'cache belongs to someone else');
    assert.equal(welcome(null, null), 'Account');
  });

  it('changes straight away when a new display name is saved on the profile page', () => {
    setCurrentProfile({ username: 'ayush', displayName: 'Old Name' });
    assert.equal(welcome('ayush', 'ADMIN'), 'Old Name');
    setCurrentProfile({ username: 'ayush', displayName: 'New Name' }); // what ProfilePage does with the PUT response
    assert.equal(welcome('ayush', 'ADMIN'), 'New Name');
  });
});

describe('top navigation: display name', () => {
  it('shows the saved display name from the shared profile, falling back to the username-derived one', () => {
    assert.ok(header.includes('profile?.displayName || defaultName'));
    assert.ok(header.includes('{displayName}'));
  });
});
