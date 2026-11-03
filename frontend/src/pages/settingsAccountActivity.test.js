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

const header = profilePage.slice(profilePage.indexOf('aria-label="Profile summary"'), profilePage.indexOf('title="Personal Information"'));

describe('profile page: one unified header with Account Activity on the right', () => {
  it('shows Account Created, Last Login and Last Password Change, in that order, in the header', () => {
    const labels = ["'Account Created'", "'Last Login'", "'Last Password Change'"];
    const positions = labels.map((label) => header.indexOf(label));
    assert.ok(positions.every((p) => p > 0), 'all three present');
    assert.deepEqual([...positions].sort((a, b) => a - b), positions, 'order');
    assert.ok(header.includes('>Account Activity</h3>'));
  });

  it('has profile information on the left, a divider, and the activity on the right', () => {
    const order = ['<UserAvatar', '{displayName}</h2>', '{accessScope}', 'Divider', 'id="account-activity-title"', '<dl'].map((t) => header.indexOf(t));
    assert.ok(order.every((p) => p > 0), order.join(','));
    assert.deepEqual([...order].sort((a, b) => a - b), order);
    assert.ok(header.includes('hidden lg:block self-stretch w-px bg-gray-200'), 'vertical divider on desktop');
    assert.ok(header.includes('lg:hidden h-px bg-gray-200'), 'horizontal divider when stacked');
    assert.ok(header.includes('flex flex-col lg:flex-row lg:items-center'), 'two columns from lg, stacked below, vertically centred');
    assert.ok(header.includes('lg:w-[22rem]'), 'enough width for the timestamps');
  });

  it('shows the dates as plain label and value rows, not input-style boxes', () => {
    const activity = header.slice(header.indexOf('id="account-activity-title"'), header.indexOf('</dl>'));
    assert.ok(activity.includes('<dl') && activity.includes('<dt') && activity.includes('<dd'));
    assert.ok(!/\bborder\b|rounded|bg-gray-50|<input/.test(activity), 'no bordered or filled containers around the dates');
    assert.ok(activity.includes('text-xs text-gray-500') && activity.includes('text-sm font-medium'));
  });

  it('is no longer a separate card, and is gone from the Settings page', () => {
    assert.ok(!fs.existsSync(path.join(here, '..', 'components', 'account', 'AccountActivityCard.jsx')));
    assert.ok(!settingsPage.includes('AccountActivityCard') && !settingsPage.includes('Account Activity'));
    assert.equal((profilePage.match(/<section\b/g) || []).length, 2, 'the header and the shared card component only');
    for (const kept of ['System Settings', 'Auto-Balancing Trigger', 'Max Task Capacity Alert', 'Cross-Department Assistance']) {
      assert.ok(settingsPage.includes(kept), kept);
    }
  });

  it('reads the single shared profile and the shared formatter: no request, no hardcoded or constructed dates', () => {
    assert.ok(profilePage.includes("import { describeActivity } from '../utils/accountActivity'"));
    assert.ok(profilePage.includes("describeActivity(profile, 'createdAt', 'Unavailable')"));
    assert.ok(profilePage.includes("describeActivity(profile, 'lastLoginAt')") && profilePage.includes("describeActivity(profile, 'passwordChangedAt')"));
    assert.equal((profilePage.match(/useCurrentUserProfile\(\)/g) || []).length, 1, 'one shared read');
    assert.ok(!profilePage.includes('new Date(') && !settingsPage.includes('useCurrentUserProfile'));
  });

  it('keeps the rest of the page: picture editing, display name editing, and the two information cards in order', () => {
    assert.ok(profilePage.includes('<ProfilePictureEditor') && profilePage.includes('updateDisplayName(nameDraft.trim())'));
    const order = ['aria-label="Profile summary"', 'title="Personal Information"', 'title="Contact Information"'].map((t) => profilePage.indexOf(t));
    assert.ok(order.every((p) => p > 0) && [...order].sort((x, y) => x - y).join() === order.join());
    assert.equal((profilePage.match(/<ProfileCard\b/g) || []).length, 2);
  });
});

describe('account activity: states from the shared profile', () => {
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
