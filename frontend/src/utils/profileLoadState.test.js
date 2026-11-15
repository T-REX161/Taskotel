import { describe, it, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import {
  PROFILE_LOAD_ERROR_MESSAGE, getCurrentProfileSnapshot, loadCurrentProfile, profileFor, profileLoadState, resetCurrentProfile,
  setCurrentProfile, subscribeCurrentProfile,
} from './currentUserProfile.js';
import { PREFERENCE_MESSAGES } from './preferences.js';

// Stage 5B of the loading-state plan: a failed profile request is an error with a Retry, never an endless "Loading…".
const read = (path) => readFileSync(new URL(`../${path}`, import.meta.url), 'utf8').replace(/\r\n/g, '\n');
const profile = (username, extra = {}) => ({ username, role: 'STAFF', email: `${username}@example.invalid`, active: true, ...extra });
const deferred = () => { let resolve; let reject; const promise = new Promise((res, rej) => { resolve = res; reject = rej; }); return { promise, resolve, reject }; };
const flush = () => new Promise((r) => setTimeout(r, 0));

// What both components read from the hook, for one user
const view = (username = 'maria') => {
  const s = getCurrentProfileSnapshot();
  const own = s.username === username;
  return profileLoadState({ profile: profileFor(s, username), loading: own && s.loading, error: own ? s.error : null });
};

beforeEach(() => resetCurrentProfile());

describe('profileLoadState', () => {
  it('ready, loading and error', () => {
    assert.equal(profileLoadState({ profile: profile('maria'), loading: false, error: null }), 'ready');
    assert.equal(profileLoadState({ profile: null, loading: true, error: null }), 'loading');
    assert.equal(profileLoadState({ profile: null, loading: false, error: new Error('x') }), 'error');
  });

  it('before the first request starts it is loading, not an error', () => {
    assert.equal(profileLoadState({ profile: null, loading: false, error: null }), 'loading');
  });

  it('a loaded profile stays on screen when a later refresh fails or is running', () => {
    assert.equal(profileLoadState({ profile: profile('maria'), loading: false, error: new Error('x') }), 'ready');
    assert.equal(profileLoadState({ profile: profile('maria'), loading: true, error: null }), 'ready');
  });

  it('a retry under way is loading again (the error is cleared)', () => {
    assert.equal(profileLoadState({ profile: null, loading: true, error: new Error('old') }), 'loading');
  });
});

describe('the shared profile flow: initial loading, failure, retry, recovery', () => {
  it('failure ends loading with an error; Retry loads again; success shows the profile', async () => {
    const first = deferred();
    const load = loadCurrentProfile({ username: 'maria', fetchProfile: () => first.promise });
    assert.equal(view(), 'loading', 'initial loading');
    first.reject(Object.assign(new Error('Service unavailable'), { status: 503 }));
    assert.equal(await load, null);
    assert.equal(view(), 'error', 'a failure is never left as "Loading…"');
    assert.equal(getCurrentProfileSnapshot().loading, false);

    const retry = deferred();
    const again = loadCurrentProfile({ username: 'maria', force: true, fetchProfile: () => retry.promise });
    assert.equal(view(), 'loading', 'Retry clears the error and shows loading');
    assert.equal(getCurrentProfileSnapshot().error, null);
    retry.resolve(profile('maria'));
    assert.deepEqual(await again, profile('maria'));
    assert.equal(view(), 'ready', 'successful recovery');
    assert.equal(profileFor(getCurrentProfileSnapshot(), 'maria').email, 'maria@example.invalid');
  });

  it('a retry that fails again is an error again, still never loading', async () => {
    await loadCurrentProfile({ username: 'maria', fetchProfile: async () => { throw new Error('down'); } });
    await loadCurrentProfile({ username: 'maria', force: true, fetchProfile: async () => { throw new Error('still down'); } });
    assert.equal(view(), 'error');
    assert.equal(getCurrentProfileSnapshot().error.message, 'still down');
  });

  it('pressing Retry twice while it runs sends one request', async () => {
    await loadCurrentProfile({ username: 'maria', fetchProfile: async () => { throw new Error('down'); } });
    let requests = 0;
    const pending = deferred();
    const fetchProfile = () => { requests += 1; return pending.promise; };
    const a = loadCurrentProfile({ username: 'maria', force: true, fetchProfile });
    const b = loadCurrentProfile({ username: 'maria', force: true, fetchProfile });
    pending.resolve(profile('maria'));
    await Promise.all([a, b]);
    assert.equal(requests, 1);
    assert.equal(view(), 'ready');
  });

  it('a stale answer never overwrites a newer one: an old request that finishes after a reset and a new load is ignored', async () => {
    const old = deferred();
    loadCurrentProfile({ username: 'maria', fetchProfile: () => old.promise });
    resetCurrentProfile(); // for example: signed out and in again while it was running
    await loadCurrentProfile({ username: 'maria', fetchProfile: async () => profile('maria', { displayName: 'New' }) });
    old.resolve(profile('maria', { displayName: 'Old' }));
    await flush();
    assert.equal(profileFor(getCurrentProfileSnapshot(), 'maria').displayName, 'New');
    // and an old failure does not turn a loaded profile into an error either
    const oldFailure = deferred();
    resetCurrentProfile();
    loadCurrentProfile({ username: 'maria', fetchProfile: () => oldFailure.promise });
    resetCurrentProfile();
    await loadCurrentProfile({ username: 'maria', fetchProfile: async () => profile('maria') });
    oldFailure.reject(new Error('late failure'));
    await flush();
    assert.equal(view(), 'ready');
    assert.equal(getCurrentProfileSnapshot().error, null);
  });

  it('a load that started before an update (picture, name, email) cannot bring back the older profile', async () => {
    const slow = deferred();
    loadCurrentProfile({ username: 'maria', fetchProfile: () => slow.promise });
    setCurrentProfile(profile('maria', { profileImageUrl: 'https://img/new' }));
    slow.resolve(profile('maria', { profileImageUrl: 'https://img/old' }));
    await flush();
    assert.equal(profileFor(getCurrentProfileSnapshot(), 'maria').profileImageUrl, 'https://img/new');
    // a later forced reload is a fresh request, not the dropped one
    let requests = 0;
    await loadCurrentProfile({ username: 'maria', force: true, fetchProfile: async () => { requests += 1; return profile('maria', { profileImageUrl: 'https://img/newest' }); } });
    assert.equal(requests, 1);
    assert.equal(profileFor(getCurrentProfileSnapshot(), 'maria').profileImageUrl, 'https://img/newest');
  });

  it('leaving the page while it loads is safe: the shared store keeps the result, a remount shows it, nothing is set on an unmounted component', async () => {
    const pending = deferred();
    let calls = 0;
    const stop = subscribeCurrentProfile(() => { calls += 1; }); // a mounted component
    loadCurrentProfile({ username: 'maria', fetchProfile: () => pending.promise });
    stop(); // the component unmounts (useSyncExternalStore unsubscribes)
    const before = calls;
    pending.resolve(profile('maria'));
    await flush();
    assert.equal(calls, before, 'no update reaches the unmounted component');
    assert.equal(view(), 'ready', 'the next mount reads the loaded profile');
  });

  it('another account\'s failure is never shown as this user\'s', async () => {
    await loadCurrentProfile({ username: 'rocky', fetchProfile: async () => { throw new Error('down'); } });
    assert.equal(view('rocky'), 'error');
    assert.equal(view('maria'), 'loading', 'maria\'s own request has not answered yet');
  });
});

// ── the components ───────────────────────────────────────────────────────────────────────────────────────────────

describe('hook: loading and error belong to the signed-in user', () => {
  it('scopes both to the current username', () => {
    const hook = read('hooks/useCurrentUserProfile.js');
    assert.ok(hook.includes("const own = Boolean(username) && state.username === username;"));
    assert.ok(hook.includes('loading: own && state.loading, error: own ? state.error : null'));
  });
});

describe('ProfilePage', () => {
  const page = read('pages/ProfilePage.jsx');

  it('derives loading / error / ready from the hook', () => {
    assert.ok(page.includes('const { profile, loading: profileLoading, error: profileError } = useCurrentUserProfile();'));
    assert.ok(page.includes('const profileState = profileLoadState({ profile, loading: profileLoading, error: profileError });'));
    assert.ok(page.includes("const profileUnavailable = profileState === 'error';"));
  });

  it('on error: a clear message and a Retry through the shared flow', () => {
    // Stage 5C: the shared LoadError (a role="alert" banner whose button reads "Retry"; see components/common/loadingPrimitives.test.js)
    assert.match(page, /\{profileUnavailable && \(\s*<LoadError variant="banner" testId="profile-load-error" message=\{PROFILE_LOAD_ERROR_MESSAGE\} onRetry=\{retryCurrentProfile\} \/>/);
    assert.match(PROFILE_LOAD_ERROR_MESSAGE, /could not be loaded/);
    assert.match(read('utils/currentUserProfile.js'), /export const retryCurrentProfile = \(\) => loadCurrentProfile\(\{ force: true \}\);/);
  });

  it('no value is left on "Loading…" after a failure: rows, badges and account activity read "Unavailable"', () => {
    assert.ok(page.includes("const missingValue = (whenLoaded) => (profile ? whenLoaded : profileUnavailable ? 'Unavailable' : 'Loading…');"));
    assert.ok(page.includes("{missingValue('Not available')}") && page.includes("{missingValue('Not added')}"));
    assert.doesNotMatch(page, /profile \? '[^']*' : 'Loading…'/, 'no row decides "Loading…" from the profile alone any more');
    assert.ok(page.includes('const status = profileUnavailable ? unavailableFlag : accountStatus(profile);'));
    assert.ok(page.includes('const verification = profileUnavailable ? unavailableFlag : emailVerification(profile);'));
    for (const field of ["'createdAt'", "'lastLoginAt'", "'passwordChangedAt'"]) {
      assert.match(page, new RegExp(`= profileUnavailable \\? unavailableActivity : describeActivity\\(profile, ${field}`), field);
    }
  });

  it('editing stays disabled until a valid profile is loaded', () => {
    assert.match(page, /onClick=\{\(\) => \{ clearNotice\(\); setEditorOpen\(true\); \}\}\s*disabled=\{!profile\}/, 'picture');
    assert.match(page, /onClick=\{startEditingName\}\s*disabled=\{!profile\}/, 'display name');
    assert.ok(page.includes('disabled={!email}'), 'email (needs the loaded address)');
    assert.match(page, /setPhoneDialogOpen\(true\); \}\}\s*disabled=\{!profile\}/, 'phone');
  });

  it('the existing fields and the refresh on opening the page are unchanged', () => {
    for (const line of ['const email = emailAddress(profile);', 'const phone = phoneNumber(profile);', 'const pendingChange = pendingEmailChange(profile);',
      'loadCurrentProfile({ force: true });', 'const displayName = shownName(profile, username);']) assert.ok(page.includes(line), line);
  });
});

describe('PersonalPreferencesCard', () => {
  const card = read('components/settings/PersonalPreferencesCard.jsx');

  it('derives loading / error / ready, and the controls stay locked until ready', () => {
    assert.ok(card.includes('const profileState = profileLoadState({ profile, loading: profileLoading, error: profileError });'));
    assert.ok(card.includes("const loaded = profileState === 'ready';"));
    assert.ok(card.includes('const locked = !loaded || saving;'));
    assert.equal((card.match(/disabled=\{locked\}/g) || []).length, 3, 'appearance, date format and time format');
  });

  it('"Loading your preferences…" only while loading; on error a message and a Retry instead', () => {
    assert.ok(card.includes("{profileState === 'loading' && <p role=\"status\" className=\"text-sm text-gray-500\">{PREFERENCE_MESSAGES.loading}</p>}"));
    assert.match(card, /\{profileState === 'error' && \(\s*<div role="alert" data-testid="preferences-load-error"[\s\S]*?\{PREFERENCE_MESSAGES\.loadFailed\}[\s\S]*?onClick=\{retryCurrentProfile\}[\s\S]*?Retry/);
    assert.equal(PREFERENCE_MESSAGES.loadFailed, 'Your preferences could not be loaded.');
    assert.doesNotMatch(card, /\{!loaded && <p/, 'the old unconditional loading line is gone');
  });

  it('saving behaviour is unchanged', () => {
    assert.ok(card.includes('await savePreferences({ [field]: value }, { current: prefs });'));
    assert.ok(card.includes("if (isTheme) setPreference(previous, null, { persist: false });"));
  });
});
