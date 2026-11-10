import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { accessScopeFor, accountStatus, emailAddress, emailVerification, flagState, phoneNumber } from '../utils/profileView.js';

const here = path.dirname(fileURLToPath(import.meta.url));
const read = (...parts) => fs.readFileSync(path.join(here, ...parts), 'utf8');
const page = read('ProfilePage.jsx');

describe('profile view helpers (everything comes from the backend profile)', () => {
  it('email: the registered address, trimmed; nothing while it is missing', () => {
    assert.equal(emailAddress({ email: '  maria@hotel.com ' }), 'maria@hotel.com');
    for (const none of [null, undefined, {}, { email: '' }, { email: '   ' }, { email: 42 }]) assert.equal(emailAddress(none), null);
  });

  it('phone: the stored number; null (never a made-up value) when there is none', () => {
    assert.equal(phoneNumber({ phone: ' +1 555 0100 ' }), '+1 555 0100');
    for (const none of [null, undefined, {}, { phone: null }, { phone: '' }, { phone: '  ' }, { phone: 5550100 }]) assert.equal(phoneNumber(none), null);
  });

  it('email verification is "Verified" only when the backend says true', () => {
    assert.deepEqual(emailVerification({ emailVerified: true }), { state: 'on', label: 'Verified' });
    assert.deepEqual(emailVerification({ emailVerified: false }), { state: 'off', label: 'Not verified' });
    for (const unknown of [null, undefined, {}, { emailVerified: 'true' }, { emailVerified: 1 }]) assert.deepEqual(emailVerification(unknown), { state: 'unknown', label: 'Loading…' });
  });

  it('account status is Active / Inactive from the backend, and Loading… until it is known', () => {
    assert.equal(accountStatus({ active: true }).label, 'Active');
    assert.equal(accountStatus({ active: false }).label, 'Inactive');
    assert.equal(accountStatus(null).label, 'Loading…');
    assert.equal(flagState(undefined), 'unknown');
  });

  it('access scope follows the existing role wording for Admin, Manager and Staff', () => {
    assert.equal(accessScopeFor('ADMIN'), 'Full Administrator Access');
    assert.equal(accessScopeFor('MANAGER'), 'Operations & Management Access');
    assert.equal(accessScopeFor('STAFF'), 'Standard Staff Access');
    assert.equal(accessScopeFor(undefined), 'Standard Staff Access');
  });
});

describe('profile page: header', () => {
  it('shows the (clickable) picture, display name, username, role badge and status badge', () => {
    const header = page.slice(page.indexOf('aria-label="Profile summary"'), page.indexOf('title="Personal Information"'));
    for (const part of ['<UserAvatar', 'Edit profile picture', '{displayName}</h2>', '@{username}', '{roleText}', '<StatusBadge state={status.state} label={status.label} />']) {
      assert.ok(header.includes(part), part);
    }
    assert.ok(header.includes('rounded-full'), 'circular picture');
  });

  it('is not decorated: no gradient banner or blurred blobs', () => {
    const header = page.slice(page.indexOf('aria-label="Profile summary"'), page.indexOf('title="Personal Information"'));
    assert.ok(!header.includes('blur-3xl') && !header.includes('bg-gradient-to-br'), 'no decorative backdrop');
  });
});

describe('profile page: Contact Information', () => {
  const contact = page.slice(page.indexOf('title="Contact Information"'), page.indexOf('<EmailChangeDialog'));

  it('has Email Address (with its verification status beside it) and Phone Number, in that order, so both cards have two rows', () => {
    const order = ['label="Email Address"', '{email}', 'Email verification:', '<StatusBadge', 'label="Phone Number"'].map((l) => contact.indexOf(l));
    assert.ok(order.every((p) => p > 0) && [...order].sort((a, b) => a - b).join() === order.join(), order.join());
    assert.equal((contact.match(/<FieldRow /g) || []).length, 2);
  });

  it('shows the registered email and phone from the profile only, and "Not added" when there is no phone', () => {
    assert.ok(contact.includes('emailAddress') === false && page.includes('const email = emailAddress(profile)') && page.includes('const phone = phoneNumber(profile)'));
    assert.ok(contact.includes('{email}') && contact.includes('{phone}'));
    assert.ok(contact.includes("'Not added'"), 'missing phone is "Not added"');
    assert.ok(contact.includes("profile ? 'Not added' : 'Loading…'"), 'not claimed before the profile has loaded');
    assert.ok(!/\+?\d[\d\s-]{6,}\d/.test(contact), 'no hardcoded phone number');
    assert.ok(!/@[a-z0-9-]+\.[a-z]{2,}/i.test(contact), 'no hardcoded email address');
  });

  it('marks the email verified only when the backend confirms it', () => {
    assert.ok(contact.includes('<StatusBadge state={verification.state} label={verification.label} offTone="warning" />'));
    assert.ok(page.includes('const verification = emailVerification(profile)'));
  });

  it('Email Edit and the phone Edit/Add both open their own dialog; nothing is a "coming soon" placeholder any more', () => {
    assert.ok(!page.includes('ComingSoon') && !contact.includes('Soon') && !contact.toLowerCase().includes('coming soon'));
    assert.ok(contact.includes('setEmailDialogOpen(true)') && contact.includes('disabled={!email}'), 'Edit opens the dialog, and needs a loaded email');
    assert.ok(contact.includes('setPhoneDialogOpen(true)') && contact.includes("{phone ? 'Edit' : 'Add'}") && contact.includes('disabled={!profile}'));
  });

  it('the email dialog is wired to the shared profile: the new address and verification status show at once, with a success message', () => {
    assert.ok(page.includes('<EmailChangeDialog') && page.includes('currentEmail={email}'));
    const wiring = page.slice(page.indexOf('<EmailChangeDialog'));
    assert.ok(wiring.includes('setCurrentProfile(updated)') && wiring.includes('emailNotice.show(EMAIL_CHANGE_MESSAGES.changed)') && wiring.includes('setEmailDialogOpen(false)'));
    assert.ok(contact.includes('{emailNotice.notice}') && contact.includes('role="status"'));
  });

  it('the phone dialog is wired to the shared profile, shows a success message, and never calls the number verified', () => {
    assert.ok(page.includes('<PhoneChangeDialog') && page.includes('currentPhone={phone}'));
    const wiring = page.slice(page.indexOf('<PhoneChangeDialog'));
    assert.ok(wiring.includes('setCurrentProfile(updated)') && wiring.includes('phoneNotice.show(PHONE_MESSAGES.changed)') && wiring.includes('setPhoneDialogOpen(false)'));
    assert.ok(contact.includes('{phoneNotice.notice}') && contact.includes('role="status"'));
    assert.ok(!contact.includes('<input') && !contact.includes('<form'), 'the form lives in the dialog');
    for (const forbidden of ['sendOtp', 'sms', 'phoneVerified', 'verifyPhone']) assert.ok(!page.toLowerCase().includes(forbidden.toLowerCase()), forbidden);
  });
});

describe('profile page: existing behavior is kept', () => {
  it('still has display name editing that updates the shared profile, and picture upload and removal', () => {
    assert.ok(page.includes('updateDisplayName(nameDraft.trim())') && page.includes('setCurrentProfile(updated)'));
    assert.ok(page.includes('<ProfilePictureEditor') && page.includes('setCurrentProfile(updated)'));
  });

  it('still reads the shared profile once and does not request the profile itself', () => {
    assert.equal((page.match(/useCurrentUserProfile\(\)/g) || []).length, 1);
    assert.ok(!page.includes('getProfile') && !page.includes('apiFetch'));
  });

  it('works for every role: the role badge and access scope come from the signed-in role, not a hardcoded one', () => {
    assert.ok(page.includes('getUserRole()') && page.includes('accessScopeFor(role)'));
    assert.ok(!page.includes('Full Administrator Access'), 'wording lives in one place');
  });

  it('is responsive: cards stack until large screens, the two information cards are equal height only side by side', () => {
    assert.ok(page.includes('grid-cols-1 lg:grid-cols-2 gap-5 items-stretch'), 'two columns on large screens, cards stretch to the same height there');
    assert.ok(page.includes('flex flex-col h-full'), 'each card fills its grid cell');
    assert.equal((page.match(/flex flex-1 flex-col divide-y divide-gray-100/g) || []).length, 2, 'the rows of both information cards share the extra height equally');
    assert.ok(page.includes('flex flex-col justify-center flex-1'), 'each row grows evenly and keeps its content centred');
    assert.ok(!page.includes('lg:grid-cols-2 gap-5 items-start'), 'the cards grid does not pin the cards to their own height');
    assert.ok(!/lg:h-|min-h-/.test(page.slice(page.indexOf('title="Personal Information"'), page.indexOf('title="Contact Information"'))), 'no fixed heights: mobile stacks naturally');
    assert.ok(page.includes('min-w-0') && page.includes('truncate') && page.includes('flex-wrap'), 'long values shrink instead of overflowing');
    assert.ok(page.includes('flex-col md:flex-row'), 'header stacks on small screens');
  });

  it('uses the shared card style and theme classes (no hex colours or dark: overrides)', () => {
    assert.ok(page.includes('bg-white rounded-2xl border border-gray-200 shadow-sm'));
    assert.ok(!/dark:/.test(page.replace(/dark:border-emerald-500\/20/g, '')) , 'colours come from the shared theme');
    assert.ok(!/#[0-9a-fA-F]{6}\b/.test(page), 'no hex colours');
  });
});
