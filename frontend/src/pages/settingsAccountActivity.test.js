import { describe, it, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { describeActivity } from '../utils/accountActivity.js';
import { getCurrentProfileSnapshot, profileFor, resetCurrentProfile, setCurrentProfile } from '../utils/currentUserProfile.js';

const here = path.dirname(fileURLToPath(import.meta.url));
const read = (...parts) => fs.readFileSync(path.join(here, ...parts), 'utf8');
const profilePage = read('ProfilePage.jsx');
const settingsPage = read('SettingsPage.jsx');
const card = read('..', 'components', 'account', 'AccountActivityCard.jsx');

describe('profile page: Account Activity is gone, the rest is untouched', () => {
  it('no longer has the Account Activity card, its fields, icons or formatter', () => {
    for (const gone of ['Account Activity', 'account-activity-title', 'Account Created', 'Last Login', 'Last Password Change',
      'describeActivity', 'accountActivity', 'CalendarIcon', 'ClockIcon', 'KeyIcon', 'ActivityIcon']) {
      assert.ok(!profilePage.includes(gone), `ProfilePage must not contain ${gone}`);
    }
  });

  it('keeps Personal Information with its six fields in the same order', () => {
    const labels = ['>Display Name</label>', 'label="Username"', 'label="Status"', 'label="Email Verified"', 'label="System Role"', 'label="Access Scope"'];
    const positions = labels.map((label) => profilePage.indexOf(label));
    assert.ok(positions.every((p) => p > 0));
    assert.deepEqual([...positions].sort((a, b) => a - b), positions);
    assert.equal((profilePage.match(/<section\b/g) || []).length, 2, 'the profile banner and Personal Information only');
  });

  it('keeps display name editing, picture upload and the single shared profile read', () => {
    assert.ok(profilePage.includes('updateDisplayName(nameDraft.trim())') && profilePage.includes('uploadProfilePicture(picture.file)'));
    assert.ok(profilePage.includes('removeProfilePicture()') && profilePage.includes('Change Profile Picture'));
    assert.equal((profilePage.match(/useCurrentUserProfile\(\)/g) || []).length, 1);
  });
});

describe('settings page: Account Activity', () => {
  it('shows the card below the existing settings, which are all kept', () => {
    assert.ok(settingsPage.includes("import AccountActivityCard from '../components/account/AccountActivityCard'"));
    assert.ok(settingsPage.includes('<AccountActivityCard />'));
    assert.ok(settingsPage.indexOf('<AccountActivityCard />') > settingsPage.indexOf('Workload Balancing Rules'));
    for (const kept of ['System Settings', 'Auto-Balancing Trigger', 'Max Task Capacity Alert', 'Cross-Department Assistance']) {
      assert.ok(settingsPage.includes(kept), kept);
    }
  });

  it('displays Account Created, Last Login and Last Password Change, in that order', () => {
    const labels = ['label="Account Created"', 'label="Last Login"', 'label="Last Password Change"'];
    const positions = labels.map((label) => card.indexOf(label));
    assert.ok(positions.every((p) => p > 0), 'all three present');
    assert.deepEqual([...positions].sort((a, b) => a - b), positions, 'order');
    assert.equal((card.match(/<ActivityField /g) || []).length, 3, 'exactly three fields');
    assert.ok(card.includes('CalendarIcon') && card.includes('ClockIcon') && card.includes('KeyIcon'));
    assert.ok(card.includes('Account Activity</h2>'));
  });

  it('keeps the card styling: same card, and one column on mobile, two on desktop', () => {
    assert.ok(card.includes('bg-white rounded-2xl border border-gray-200 shadow-sm overflow-hidden'));
    assert.ok(card.includes('grid grid-cols-1 md:grid-cols-2 gap-x-8 gap-y-7 px-6 sm:px-8 py-7'));
    assert.ok(!/dark:|#[0-9a-fA-F]{3,6}/.test(card), 'colours come from the shared theme classes (light and dark)');
  });

  it('takes its data from the shared profile hook and formatter, with no request or state of its own', () => {
    assert.ok(card.includes("import useCurrentUserProfile from '../../hooks/useCurrentUserProfile'"));
    assert.equal((card.match(/useCurrentUserProfile\(\)/g) || []).length, 1);
    assert.ok(card.includes("import { describeActivity } from '../../utils/accountActivity'"));
    assert.ok(card.includes("describeActivity(profile, 'createdAt', 'Unavailable')"));
    assert.ok(card.includes("describeActivity(profile, 'lastLoginAt')") && card.includes("describeActivity(profile, 'passwordChangedAt')"));
    for (const source of [card, settingsPage]) {
      for (const forbidden of ['getProfile', 'loadCurrentProfile', 'apiFetch', 'fetch(', 'useState', 'useEffect', 'new Date(']) {
        assert.ok(!source.includes(forbidden), `must not contain ${forbidden}`);
      }
    }
    assert.ok(!settingsPage.includes('useCurrentUserProfile'), 'the page itself adds no second read');
  });
});

describe('settings page: states from the shared profile', () => {
  const view = (username) => profileFor(getCurrentProfileSnapshot(), username);
  const fields = (username) => ['createdAt', 'lastLoginAt', 'passwordChangedAt'].map((field, i) =>
    describeActivity(view(username), field, i === 0 ? 'Unavailable' : undefined));
  beforeEach(() => resetCurrentProfile());

  it('shows Loading… for all three until the profile has loaded', () => {
    assert.deepEqual(fields('ayush'), Array(3).fill({ text: 'Loading…', known: false }));
  });

  it('shows real, formatted times once loaded, and says "Not recorded yet" for a missing one', () => {
    setCurrentProfile({ username: 'ayush', createdAt: '2026-01-05T08:30:00', lastLoginAt: '2026-11-02T10:27:51.123', passwordChangedAt: null });
    assert.deepEqual(fields('ayush'), [
      { text: '05/01/2026, 8:30 AM', known: true },
      { text: '02/11/2026, 10:27 AM', known: true },
      { text: 'Not recorded yet', known: false },
    ]);
  });

  it('shows Unavailable when even the creation time is missing, and never a placeholder date', () => {
    setCurrentProfile({ username: 'ayush', createdAt: null, lastLoginAt: null, passwordChangedAt: null });
    assert.deepEqual(fields('ayush'), [
      { text: 'Unavailable', known: false },
      { text: 'Not recorded yet', known: false },
      { text: 'Not recorded yet', known: false },
    ]);
  });

  it('follows the shared profile, and never shows another user\'s cached times', () => {
    setCurrentProfile({ username: 'ayush', createdAt: '2026-01-05T08:30:00', lastLoginAt: '2026-11-02T10:27:51' });
    assert.equal(fields('ayush')[1].text, '02/11/2026, 10:27 AM');
    setCurrentProfile({ username: 'ayush', createdAt: '2026-01-05T08:30:00', lastLoginAt: '2026-11-03T21:00:00' }); // a later sign-in refreshes the shared profile
    assert.equal(fields('ayush')[1].text, '03/11/2026, 9:00 PM');
    assert.deepEqual(fields('maria'), Array(3).fill({ text: 'Loading…', known: false }));
  });
});
