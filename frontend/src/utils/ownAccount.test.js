import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { OWN_ACCOUNT_DEACTIVATE_HINT, canOfferDeactivation } from './ownAccount.js';

const here = path.dirname(fileURLToPath(import.meta.url));
const read = (...p) => fs.readFileSync(path.join(here, '..', ...p), 'utf8').replace(/\r\n/g, '\n');

describe('canOfferDeactivation: your own account is never offered', () => {
  it('is false for the signed-in account, whatever the case or spacing of the user name', () => {
    assert.equal(canOfferDeactivation({ username: 'ayush' }, 'ayush'), false);
    assert.equal(canOfferDeactivation({ username: 'Ayush' }, ' ayush '), false);
  });

  it('is true for any other account', () => {
    assert.equal(canOfferDeactivation({ username: 'priya' }, 'ayush'), true);
    assert.equal(canOfferDeactivation({ username: 'ayush2' }, 'ayush'), true);
  });

  it('stays true when either side is unknown (the server still decides)', () => {
    assert.equal(canOfferDeactivation({ username: 'ayush' }, null), true);
    assert.equal(canOfferDeactivation({ username: 'ayush' }, ''), true);
    assert.equal(canOfferDeactivation(null, 'ayush'), true);
    assert.equal(canOfferDeactivation({}, 'ayush'), true);
  });

  it('has a plain explanation to show', () => {
    assert.equal(OWN_ACCOUNT_DEACTIVATE_HINT, 'You cannot deactivate your own account.');
  });
});

describe('the Staff screens use it (the server enforces the rule either way)', () => {
  it('the Staff table disables and blocks Deactivate on your own row, with the explanation', () => {
    const table = read('components', 'staff', 'StaffTable.jsx');
    assert.match(table, /disabled=\{!canOfferDeactivation\(activeMenu\.staff, signedInUsername\)\}/);
    assert.match(table, /title=\{canOfferDeactivation\(activeMenu\.staff, signedInUsername\) \? undefined : OWN_ACCOUNT_DEACTIVATE_HINT\}/);
    assert.match(table, /if \(!canOfferDeactivation\(staff, signedInUsername\)\) return;\n\s+setActiveMenu\(null\);\n\s+onDeactivate && onDeactivate\(staff\);/);
    assert.match(table, /currentUsername !== undefined \? currentUsername : getUsername\(\)/);
  });

  it('the Edit Staff form cannot set your own account to Inactive', () => {
    const modal = read('components', 'staff', 'EditStaffModal.jsx');
    assert.match(modal, /disabled=\{isSubmitting \|\| !canOfferDeactivation\(staff, getUsername\(\)\)\}/);
    assert.match(modal, /\{!canOfferDeactivation\(staff, getUsername\(\)\) && \(\n\s+<p className="text-xs text-gray-500">\{OWN_ACCOUNT_DEACTIVATE_HINT\}<\/p>/);
  });

  it('the Staff page ignores a request to deactivate your own account, and still shows the server message for any refusal', () => {
    const page = read('pages', 'StaffPage.jsx');
    assert.match(page, /const handleOpenDeactivateModal = \(staff\) => \{\n\s+if \(!canOfferDeactivation\(staff, getUsername\(\)\)\) return;/);
    // The refusal from the server (own account 400, last administrator 409, active assignments 409) is shown as is
    assert.match(page, /setDeactivateError\(\n\s+err\.message \|\| 'Failed to deactivate staff member\. Please try again\.'/);
  });

  it('the Edit Staff form shows the server message for a refused save (400 own account, 409 last administrator)', () => {
    const modal = read('components', 'staff', 'EditStaffModal.jsx');
    assert.match(modal, /err\.status === 409\) \{\n\s+setFormError\(\n\s+err\.message \|\| 'A conflict occurred/);
    assert.match(modal, /err\.status === 400\) \{\n\s+setFormError\(\n\s+err\.message \|\| 'Invalid input/);
  });

  it('the Users page already hides Deactivate for your own account and shows the server message', () => {
    const page = read('pages', 'UsersPage.jsx');
    assert.match(page, /case 'deactivate':\n\s+if \(own\) return; \/\/ never your own account/);
    assert.match(page, /setDeactivateError\(err\.message \|\| 'Failed to deactivate the user\. Please try again\.'\)/);
  });
});
