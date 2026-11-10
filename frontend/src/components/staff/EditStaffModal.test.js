import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
// Line endings are normalised so the checks behave the same on LF and CRLF (Windows) checkouts
const source = fs.readFileSync(path.join(__dirname, 'EditStaffModal.jsx'), 'utf8').replace(/\r\n/g, '\n');
const addSource = fs.readFileSync(path.join(__dirname, 'AddStaffModal.jsx'), 'utf8').replace(/\r\n/g, '\n');

describe('Edit Staff modal: the login email is not editable', () => {
  it('shows the login email as a disabled, read-only field with an explanation', () => {
    const start = source.indexOf('Login Email');
    const field = source.slice(start, source.indexOf('{/* Login email change */}'));
    assert.ok(start > 0);
    assert.ok(field.includes('readOnly') && field.includes('disabled'));
    assert.ok(field.includes('value={staff.email'), 'displays the account email');
    assert.ok(!field.includes('onChange'), 'no handler can edit it');
    assert.ok(/verified login email/.test(field) && /only the\s+staff member can confirm/.test(field), 'says only the staff member confirms a change');
  });

  it('always submits the email the account already has, never form input', () => {
    assert.ok(source.includes('email: staff.email,'));
    assert.ok(!source.includes('formData.email'), 'the form state is not the source of the email');
  });

  it('keeps every other field editable', () => {
    for (const name of ['fullName', 'phone', 'department', 'availability']) {
      assert.ok(source.includes(`name="${name}"`), name);
    }
  });

  it('leaves the Add Staff form with its own editable email field', () => {
    assert.ok(addSource.includes('label="Email *"'));
  });
});
