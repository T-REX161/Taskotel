import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import {
  ROLES,
  canChangeRole,
  consequences,
  requiresPassword,
  roleChangeErrorMessage,
  roleChoices,
  roleExplanation,
  submitRoleChange,
  validateRoleChange,
} from './roleChange.js';
import { validateOptionalPhone } from './phoneNumber.js';
import { getUserActions } from '../components/users/userListUtils.js';

const src = (...parts) => readFileSync(fileURLToPath(new URL(parts.join('/'), import.meta.url)), 'utf8').replace(/\r\n/g, '\n');

describe('who can change a role (mirrors the server)', () => {
  it('only an Administrator, never for their own account', () => {
    const user = { username: 'rahul', role: 'STAFF' };
    assert.equal(canChangeRole('ADMIN', user, 'admin'), true);
    assert.equal(canChangeRole('MANAGER', user, 'admin'), false);
    assert.equal(canChangeRole('STAFF', user, 'admin'), false);
    assert.equal(canChangeRole(null, user, 'admin'), false);
    assert.equal(canChangeRole('ADMIN', { username: 'Admin', role: 'ADMIN' }, 'admin'), false, 'own account, case-insensitive');
    assert.equal(canChangeRole('ADMIN', { username: 'x', role: 'BOGUS' }, 'admin'), false);
  });

  it('offers every role except the current one: all six supported transitions', () => {
    const pairs = [];
    for (const from of ROLES) for (const to of roleChoices(from)) pairs.push(`${from}->${to}`);
    assert.deepEqual(pairs.sort(), ['ADMIN->MANAGER', 'ADMIN->STAFF', 'MANAGER->ADMIN', 'MANAGER->STAFF', 'STAFF->ADMIN', 'STAFF->MANAGER']);
  });
});

describe('the dialog content', () => {
  it('asks for the password for anything involving ADMIN, and only for that', () => {
    assert.equal(requiresPassword('STAFF', 'ADMIN'), true);
    assert.equal(requiresPassword('MANAGER', 'ADMIN'), true);
    assert.equal(requiresPassword('ADMIN', 'MANAGER'), true);
    assert.equal(requiresPassword('ADMIN', 'STAFF'), true);
    assert.equal(requiresPassword('STAFF', 'MANAGER'), false);
    assert.equal(requiresPassword('MANAGER', 'STAFF'), false);
  });

  it('explains the resulting permissions and what else changes', () => {
    assert.match(roleExplanation('ADMIN'), /Full system access/);
    assert.match(roleExplanation('MANAGER'), /own department/);
    assert.match(roleExplanation('STAFF'), /only their own/);
    assert.ok(consequences('MANAGER', 'STAFF').some((n) => /additional departments .* removed/i.test(n)));
    assert.ok(consequences('STAFF', 'MANAGER').some((n) => /No additional department is granted/.test(n)));
    assert.ok(consequences('STAFF', 'ADMIN').some((n) => /full control/i.test(n)));
    assert.ok(consequences('ADMIN', 'STAFF').some((n) => /lose all administrator access/i.test(n)));
    for (const [from, to] of [['STAFF', 'MANAGER'], ['ADMIN', 'STAFF']]) {
      const notes = consequences(from, to);
      assert.ok(notes.some((n) => /staff record.*kept/i.test(n)), 'the staff record is never deleted');
      assert.ok(notes.some((n) => /sign in again/i.test(n)), 'sessions end');
    }
  });
});

describe('validation and sending', () => {
  it('needs a different role, and a password when ADMIN is involved', () => {
    assert.notEqual(validateRoleChange({ from: 'STAFF', to: '', password: '' }), '');
    assert.notEqual(validateRoleChange({ from: 'STAFF', to: 'STAFF', password: '' }), '');
    assert.equal(validateRoleChange({ from: 'STAFF', to: 'MANAGER', password: '' }), '');
    assert.match(validateRoleChange({ from: 'STAFF', to: 'ADMIN', password: '' }), /password/i);
    assert.equal(validateRoleChange({ from: 'STAFF', to: 'ADMIN', password: 'x' }), '');
  });

  it('sends nothing for an invalid choice, and the password only for ADMIN-involving changes', async () => {
    const calls = [];
    const save = async (...args) => { calls.push(args); return { ok: true }; };
    await assert.rejects(submitRoleChange({ userId: 'u1', from: 'STAFF', to: 'ADMIN', password: '' }, { save }), (e) => e.status === 0);
    assert.equal(calls.length, 0);
    await submitRoleChange({ userId: 'u1', from: 'STAFF', to: 'manager', password: 'secret' }, { save });
    await submitRoleChange({ userId: 'u2', from: 'MANAGER', to: 'ADMIN', password: 'secret' }, { save });
    assert.deepEqual(calls, [['u1', 'MANAGER', undefined], ['u2', 'ADMIN', 'secret']]);
  });

  it('shows the server\'s own short reason for a refusal it explained, a generic message otherwise', () => {
    assert.equal(roleChangeErrorMessage({ status: 409, message: 'Cannot remove the administrator role from the last active administrator.' }), 'Cannot remove the administrator role from the last active administrator.');
    assert.equal(roleChangeErrorMessage({ status: 400, message: 'The password is not correct.' }), 'The password is not correct.');
    assert.match(roleChangeErrorMessage({ status: 500, message: 'NullPointerException at ...' }), /could not be changed/);
    assert.match(roleChangeErrorMessage(null), /could not be changed/);
  });
});

describe('the Users page wiring', () => {
  const active = { staff: { active: true }, currentUsername: 'admin' };
  it('Change Role is an Administrator action on other accounts only', () => {
    const user = { id: 'u1', username: 'rahul', role: 'STAFF', active: true };
    assert.ok(getUserActions(user, { ...active, isAdmin: true }).actions.includes('changeRole'));
    assert.ok(!getUserActions(user, { ...active, isAdmin: false }).actions.includes('changeRole'), 'not for anyone else');
    assert.ok(!getUserActions({ ...user, username: 'admin' }, { ...active, isAdmin: true }).actions.includes('changeRole'), 'not for your own account');
    assert.ok(getUserActions(user, { staff: null, currentUsername: 'admin', isAdmin: true }).actions.includes('changeRole'), 'also without a staff record');
  });

  it('the dialog is separate from Edit Staff, has the required parts, and never uses a browser alert or confirm', () => {
    const modal = src('../components/users/RoleChangeModal.jsx');
    for (const part of ['Change System Role', 'Current role', 'Requested role', 'roleExplanation(newRole)', 'consequences(currentRole, newRole)', 'Cancel', 'Confirm Change', 'role="dialog"', 'aria-modal="true"']) {
      assert.ok(modal.includes(part), part);
    }
    assert.ok(!/window\.(alert|confirm)|\balert\(|\bconfirm\(/.test(modal));
    const edit = src('../components/staff/EditStaffModal.jsx');
    assert.ok(!/role-change|changeUserRole|RoleChangeModal/.test(edit), 'no role control inside Edit Staff');
    assert.ok(!/<select[^>]*name="role"/.test(edit));
  });

  it('the page opens the dialog only for an Administrator and never for the signed-in account', () => {
    const page = src('../pages/UsersPage.jsx');
    assert.ok(page.includes("if (isAdmin && !isCurrentUser(user, currentUsername)) setChangingRole(user);"));
    assert.ok(page.includes('isOpen={isAdmin && Boolean(changingRole)}'));
  });
});

describe('phone numbers in the staff forms', () => {
  it('are optional; a changed number must be valid; an unchanged older value never blocks an edit', () => {
    assert.equal(validateOptionalPhone('', ''), null);
    assert.equal(validateOptionalPhone('   ', '+91 98765 43210'), null, 'clearing is allowed');
    assert.equal(validateOptionalPhone('+91 98765 43210', ''), null);
    assert.match(validateOptionalPhone('abc', ''), /valid phone number/);
    assert.match(validateOptionalPhone('12345', '555-010-0000'), /valid phone number/);
    assert.equal(validateOptionalPhone('ext 12', 'ext 12'), null, 'unchanged legacy value');
    assert.match(validateOptionalPhone('ext 13', 'ext 12'), /valid phone number/);
  });

  it('both staff forms check it before sending and send the normalised number', () => {
    for (const file of ['EditStaffModal', 'AddStaffModal']) {
      const modal = src(`../components/staff/${file}.jsx`);
      assert.ok(modal.includes('validateOptionalPhone('), file);
      assert.ok(modal.includes('phone: normalizePhone(formData.phone) || null'), file);
    }
  });
});
