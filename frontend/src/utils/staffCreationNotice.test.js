import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { withInvitationOutcome } from './staffCreationNotice.js';
import { getLoginOutcome, LOGIN_MESSAGES } from '../pages/loginResult.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const read = (...parts) => fs.readFileSync(path.join(__dirname, ...parts), 'utf8').replace(/\r\n/g, '\n');
const BASE = 'Staff member "Mia" created successfully!';

describe('what the creator is told after creating an account', () => {
  it('an invitation that was sent is reported with the address it went to', () => {
    const text = withInvitationOutcome(BASE, { invitationEmailSent: true, email: 'mia@hotel.test' });
    assert.ok(text.startsWith(BASE));
    assert.ok(text.includes('invitation email') && text.includes('mia@hotel.test'));
    assert.ok(!/password\s*[:=]/i.test(text), 'the message never shows a password');
  });

  it('a failed invitation is said plainly, and the account is still reported as created', () => {
    const text = withInvitationOutcome(BASE, { invitationEmailSent: false, email: 'mia@hotel.test' });
    assert.ok(text.startsWith(BASE), 'the account exists');
    assert.ok(text.includes('could not be sent') && text.includes('another way'));
    assert.ok(!text.includes('was sent to'), 'a failed invitation is never claimed as sent');
  });

  it('a missing flag (an older server) claims nothing either way', () => {
    for (const created of [null, undefined, {}, { email: 'x@y.test' }, { invitationEmailSent: null }, { invitationEmailSent: 'true' }]) {
      assert.equal(withInvitationOutcome(BASE, created), BASE);
    }
  });

  it('a sent invitation without an address still reads sensibly', () => {
    assert.ok(withInvitationOutcome(BASE, { invitationEmailSent: true }).includes('their email address'));
  });

  it('both pages that create accounts use it', () => {
    assert.ok(read('..', 'pages', 'StaffPage.jsx').includes('withInvitationOutcome(`Staff member "${newStaff?.name || fullName}" created successfully!`, newStaff)'));
    assert.ok(read('..', 'pages', 'UsersPage.jsx').includes('notify(withInvitationOutcome(`User "${newStaff?.username || fullName}" created.`, newStaff))'));
  });
});

describe('the login page and the expired temporary password', () => {
  it('shows the server explanation only for that exact code on a 401', () => {
    const message = 'Your temporary password has expired. Use "Forgot password" on the login page to set a new one.';
    assert.deepEqual(getLoginOutcome({ ok: false, status: 401, data: { code: 'TEMPORARY_PASSWORD_EXPIRED', message } }), { kind: 'invalid', message });
  });

  it('every other 401 keeps the generic message, whatever its body says', () => {
    for (const data of [null, {}, { message: 'Invalid username/email or password' }, { message: 'secret detail', code: 'SOMETHING_ELSE' }, { code: 'TEMPORARY_PASSWORD_EXPIRED' }, { code: 'TEMPORARY_PASSWORD_EXPIRED', message: '   ' }]) {
      assert.deepEqual(getLoginOutcome({ ok: false, status: 401, data }), { kind: 'invalid', message: LOGIN_MESSAGES.invalid }, JSON.stringify(data));
    }
  });
});
