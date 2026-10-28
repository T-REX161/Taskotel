import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
// Line endings are normalised so the checks behave the same on LF and CRLF (Windows) checkouts
const source = fs.readFileSync(path.join(__dirname, 'AddStaffModal.jsx'), 'utf8').replace(/\r\n/g, '\n');
const section = (title) => {
  const start = source.indexOf(`title="${title}"`);
  return source.slice(start, source.indexOf('</FormSection>', start));
};

describe('Add New Staff modal', () => {
  it('has a header with the title, subtitle and close button', () => {
    assert.ok(source.includes("title = 'Add New Staff'") && source.includes("description = 'Add staff member to your hotel team'"));
    assert.ok(source.includes('<UserPlusIcon') && source.includes('aria-label="Close modal"'));
  });

  it('groups the fields into three sections in the reference order', () => {
    const order = ['Basic Information', 'Work Information', 'Account Settings'].map((t) => source.indexOf(`title="${t}"`));
    assert.ok(order.every((i) => i > 0) && order[0] < order[1] && order[1] < order[2]);
    const fields = (t) => [...section(t).matchAll(/\slabel="([^"]+)"/g)].map((m) => m[1]);
    assert.deepStrictEqual(fields('Basic Information'), ['Full Name *', 'Email *', 'Phone', 'Username *']);
    assert.deepStrictEqual(fields('Work Information'), ['Role *', 'Department *', 'Availability *']);
    assert.deepStrictEqual(fields('Account Settings'), ['Status', 'Temporary Password *']);
  });

  it('lays out two columns, then role/department/availability in one row on wider screens, and stacks on phones', () => {
    assert.ok(section('Basic Information').includes('grid grid-cols-1 sm:grid-cols-2 gap-x-4 gap-y-4'));
    assert.ok(section('Work Information').includes('grid grid-cols-1 sm:grid-cols-2 md:grid-cols-3 gap-x-4 gap-y-4'));
    assert.ok(source.includes('md:grid-cols-[minmax(0,1fr)_minmax(0,15rem)]') && section('Account Settings').includes('aside={<PasswordRequirements rules={tempPasswordRules} />}'), 'Password rules sit beside the section on wide screens');
    assert.ok(source.includes('max-h-[90vh] supports-[height:100dvh]:max-h-[90dvh] flex flex-col overflow-hidden') && source.includes('flex-1 min-h-0 overflow-y-auto') && source.includes('shrink-0 px-5 py-3.5 sm:px-6 border-t border-gray-100 flex flex-wrap'), 'Fits the screen; only the form body scrolls, the footer stays visible');
  });

  it('sends the backend role names; only an administrator can choose Manager or Admin', () => {
    assert.ok(/\{ value: 'STAFF', label: 'Staff' \},\s*\{ value: 'MANAGER', label: 'Manager' \},\s*\{ value: 'ADMIN', label: 'Admin' \}/.test(source));
    assert.ok(source.includes("const canChooseRole = getUserRole() === 'ADMIN';"));
    assert.ok(source.includes("ROLE_OPTIONS.filter((option) => option.value === 'STAFF')"), 'Managers only see Staff');
    assert.ok(source.includes("role: canChooseRole ? formData.role : 'STAFF',"), 'Role is part of the create request');
    assert.ok(source.includes("role: 'STAFF',"), 'Staff is the default');
  });

  it('keeps the existing validation, status default, password rules and visibility toggle', () => {
    for (const text of ["'Full name is required.'", "'Please enter a valid email address.'", "'Temporary password does not satisfy all required security rules.'",
      'value="Active (Default)"', "aria-label={\n                        showTemporaryPassword ? 'Hide password' : 'Show password'", 'evaluatePasswordRules(formData.temporaryPassword)']) {
      assert.ok(source.includes(text), text);
    }
    assert.ok(source.includes('onSuccess(newStaff, payload.fullName);') && source.includes('const newStaff = await createStaff(payload);'));
  });

  it('suggests a username from the full name until the user types their own, and can regenerate it', () => {
    assert.ok(source.includes("...(name === 'fullName' && !usernameEdited ? { username: generateUsername(value) } : {})"), 'follows the name only while not edited');
    assert.ok(source.includes("if (name === 'username') setUsernameEdited(value !== '');"), 'typing marks it as user-entered; clearing hands it back');
    assert.ok(source.includes('const regenerateUsername = () => {') && source.includes('setUsernameEdited(false);'));
    assert.ok(source.includes('aria-label="Generate username from full name"'));
    assert.ok(source.includes("usernameEdited ? 'Custom' : 'Suggested'"), 'suggested and manual usernames are labelled');
  });

  it('checks availability live, offers alternatives, and refuses to submit a taken or unusable username', () => {
    assert.ok(source.includes('useUsernameAvailability(formData.username, formData.fullName, recheckKey)'));
    assert.ok(source.includes('<UsernameFeedback check={feedback} onPickSuggestion={pickUsername}'));
    assert.ok(source.includes("if (usernameStatus === 'taken') {"), 'a known-taken username blocks submit');
    assert.ok(source.includes('setRecheckKey((key) => key + 1)'), 'a 409 from the server re-checks the field');
    assert.ok(/setUsernameEdited\(true\);\s+setFormData\(\(prev\) => \(\{ \.\.\.prev, username \}\)\);/.test(source), 'picking a suggestion fills the field');
    assert.ok(source.includes('username: formData.username.trim(),'), 'the chosen username is submitted as typed, never replaced');
  });
});
