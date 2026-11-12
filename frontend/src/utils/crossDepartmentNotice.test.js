import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { CROSS_DEPARTMENT_DISABLED_NOTICE, crossDepartmentAssistanceOff } from './systemSettings.js';

const read = (...parts) => readFileSync(new URL(`../${parts.join('/')}`, import.meta.url), 'utf8');
/** What GET /api/support/cross-department-status answers: that one flag only. */
const status = (crossDepartmentAssistanceEnabled) => ({ crossDepartmentAssistanceEnabled });

test('the notice text is exactly the approved wording', () => {
  assert.equal(
    CROSS_DEPARTMENT_DISABLED_NOTICE,
    'Cross-department assistance is currently disabled. Existing support arrangements remain active and can still be managed.',
  );
});

test('notice visibility: shown when the status says OFF', () => {
  assert.equal(crossDepartmentAssistanceOff(status(false)), true);
});

test('notice visibility: absent when the status says ON', () => {
  assert.equal(crossDepartmentAssistanceOff(status(true)), false);
});

test('notice visibility: never shown on a failed or unreadable read (no answer, junk, missing or non-boolean flag)', () => {
  for (const response of [undefined, null, 'off', 42, [], {}, { other: false }, status('false'), status(0), status(null), status(undefined)]) {
    assert.equal(crossDepartmentAssistanceOff(response), false, JSON.stringify(response));
  }
});

test('the panel shows the notice only while the flag is off, near the top, before the support content', () => {
  const panel = read('components', 'workload', 'CrossDepartmentSupportPanel.jsx');
  assert.match(panel, /\{crossDepartmentOff && \(\s*<p role="status" data-testid="cross-department-disabled-notice"/);
  assert.match(panel, /\{CROSS_DEPARTMENT_DISABLED_NOTICE\}/);
  const notice = panel.indexOf('cross-department-disabled-notice');
  assert.ok(notice > panel.indexOf('Cross-Department Support</h2>'), 'after the header');
  assert.ok(notice < panel.indexOf('How this works'), 'before the rest of the panel content');
  assert.ok(notice < panel.indexOf('Departments that need support'), 'before the departments list');
});

test('both roles read the flag through the narrow status route; a failed read hides the notice; no other settings route is used', () => {
  const panel = read('components', 'workload', 'CrossDepartmentSupportPanel.jsx');
  // no role gate inside the loader: the panel itself is only mounted for Admin and Manager (supportPermissions.canView)
  assert.match(panel, /crossDepartmentAssistanceOff\(await getCrossDepartmentStatus\(\)\)/);
  assert.ok(!/getUserRole\(\) !== 'ADMIN'\) return;/.test(panel), 'Managers are not excluded from the read');
  assert.ok(!/getSystemSettings/.test(panel), 'the Admin-only settings route is not used by the panel');
  assert.match(panel, /catch \{\s*if \(mounted\.current\) setCrossDepartmentOff\(false\);/);
  assert.match(panel, /if \(!supportPermissions\(getUserRole\(\)\)\.canView\) return null;/);
  const api = read('services', 'api.js');
  assert.match(api, /export const getCrossDepartmentStatus = \(\) => \{\s*return apiFetch\('\/api\/support\/cross-department-status'\);/);
});

test('the status read is repeated on Refresh, and the notice does not touch the support actions or same-department assignment', () => {
  const panel = read('components', 'workload', 'CrossDepartmentSupportPanel.jsx');
  assert.match(panel, /loadCrossDepartmentSetting\(\);\s*loadOptions\(state\.department\);/);
  assert.match(panel, /const canEnd = \['ADMIN', 'MANAGER'\]\.includes\(getUserRole\(\)\);/);
  assert.ok(!/crossDepartmentOff/.test(panel.slice(panel.indexOf('const canEnd'), panel.indexOf('const canEnd') + 200)), 'canEnd does not depend on the flag');
  assert.equal((panel.match(/crossDepartmentOff/g) || []).length, 2, 'the flag is only declared and used by the notice; nothing else reads it');
  // preview and confirm still go to the server and its 409 (no client-side blocking added)
  assert.match(panel, /previewSupportPlan/);
  assert.match(panel, /confirmSupportPlan/);
  assert.ok(!/createAssignment|assignTask/.test(panel), 'the panel has nothing to do with same-department assignment');
});
