import { describe, it, beforeEach, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { assignManagerDepartment, getManagerDepartment, getManagerDepartmentHistory } from '../services/api.js';
import { getUserActions } from '../components/users/userListUtils.js';
import {
  ADMIN_ONLY_MESSAGE,
  canManageManagerDepartment,
  canSubmit,
  canViewManagerDepartmentHistory,
  currentDepartmentText,
  departmentChoices,
  describeChange,
  describeChangedBy,
  managerDepartmentErrorMessage,
  validateSelection,
  validityNote,
} from './managerDepartment.js';

const here = path.dirname(fileURLToPath(import.meta.url));
const src = (...parts) => fs.readFileSync(path.join(here, '..', ...parts), 'utf8').replace(/\r\n/g, '\n');

const realFetch = globalThis.fetch;
const realStorage = Object.getOwnPropertyDescriptor(globalThis, 'localStorage');
const realWindow = Object.getOwnPropertyDescriptor(globalThis, 'window');

beforeEach(() => {
  const store = { token: 'jwt-token', username: 'root', role: 'ADMIN' };
  Object.defineProperty(globalThis, 'localStorage', {
    configurable: true,
    writable: true,
    value: { getItem: (k) => (k in store ? store[k] : null), setItem: (k, v) => { store[k] = String(v); }, removeItem: (k) => { delete store[k]; } },
  });
  Object.defineProperty(globalThis, 'window', { configurable: true, writable: true, value: { location: { href: '/users' } } });
});

afterEach(() => {
  globalThis.fetch = realFetch;
  if (realStorage) Object.defineProperty(globalThis, 'localStorage', realStorage); else delete globalThis.localStorage;
  if (realWindow) Object.defineProperty(globalThis, 'window', realWindow); else delete globalThis.window;
});

const respond = (status, body) => {
  const calls = [];
  globalThis.fetch = async (url, options) => {
    calls.push({ url, options });
    return { ok: status >= 200 && status < 300, status, json: async () => body };
  };
  return calls;
};

const departments = [
  { code: 'KITCHEN', name: 'Kitchen', active: true },
  { code: 'HOUSEKEEPING', name: 'Housekeeping', active: true },
  { code: 'CLOSED', name: 'Closed Dept', active: false },
  { code: 'FRONT_DESK', name: 'Front Desk', active: true },
];

describe('who is offered the Manager department control', () => {
  it('only an Admin, and only for a Manager account', () => {
    assert.equal(canManageManagerDepartment('ADMIN', { role: 'MANAGER' }), true);
    assert.equal(canManageManagerDepartment('admin', { role: 'manager' }), true);
    assert.equal(canManageManagerDepartment('ADMIN', { role: 'STAFF' }), false);
    assert.equal(canManageManagerDepartment('ADMIN', { role: 'ADMIN' }), false);
    assert.equal(canManageManagerDepartment('ADMIN', null), false);
    for (const role of ['MANAGER', 'STAFF', '', null, undefined, 'ROOT']) {
      assert.equal(canManageManagerDepartment(role, { role: 'MANAGER' }), false, String(role));
    }
  });

  it('the history is for an Admin only', () => {
    assert.equal(canViewManagerDepartmentHistory('ADMIN'), true);
    for (const role of ['MANAGER', 'STAFF', '', null, undefined]) assert.equal(canViewManagerDepartmentHistory(role), false, String(role));
  });

  const manager = { id: 'u4', username: 'manager', role: 'MANAGER', active: true };
  const staffRecord = { id: 's4', active: true };

  it('the row menu offers it to an Admin for a Manager with a staff record, and to nobody else', () => {
    const admin = getUserActions(manager, { staff: staffRecord, currentUsername: 'root', isAdmin: true });
    assert.ok(admin.actions.includes('department'));
    assert.ok(!getUserActions(manager, { staff: staffRecord, currentUsername: 'root', isAdmin: false }).actions.includes('department'));
    for (const role of ['STAFF', 'ADMIN']) {
      assert.ok(!getUserActions({ ...manager, role }, { staff: staffRecord, currentUsername: 'root', isAdmin: true }).actions.includes('department'), role);
    }
  });
});

describe('choosing a department', () => {
  it('offers active departments only, without the current one, sorted by name', () => {
    assert.deepEqual(departmentChoices(departments, 'KITCHEN').map((d) => d.code), ['FRONT_DESK', 'HOUSEKEEPING']);
    assert.deepEqual(departmentChoices(departments, '').map((d) => d.code), ['FRONT_DESK', 'HOUSEKEEPING', 'KITCHEN']);
    assert.deepEqual(departmentChoices(null, 'KITCHEN'), []);
  });

  it('validates the choice', () => {
    assert.match(validateSelection('KITCHEN', ''), /Choose a department/);
    assert.match(validateSelection('KITCHEN', 'KITCHEN'), /already in that department/);
    assert.equal(validateSelection('KITCHEN', 'HOUSEKEEPING'), '');
    assert.equal(validateSelection('', 'HOUSEKEEPING'), '', 'assigning to a Manager that has no department yet');
  });

  it('Save is enabled only for a valid choice and never while a request is running', () => {
    assert.equal(canSubmit({ currentCode: 'KITCHEN', selectedCode: 'HOUSEKEEPING', submitting: false }), true);
    assert.equal(canSubmit({ currentCode: 'KITCHEN', selectedCode: 'HOUSEKEEPING', submitting: true }), false);
    assert.equal(canSubmit({ currentCode: 'KITCHEN', selectedCode: '', submitting: false }), false);
    assert.equal(canSubmit({ currentCode: 'KITCHEN', selectedCode: 'KITCHEN', submitting: false }), false);
  });
});

describe('what the dialog shows', () => {
  it('current department and validity, never inventing a department', () => {
    assert.equal(currentDepartmentText({ departmentName: 'Kitchen', departmentCode: 'KITCHEN' }), 'Kitchen');
    assert.equal(currentDepartmentText({ departmentCode: 'KITCHEN' }), 'KITCHEN');
    assert.equal(currentDepartmentText({}), 'No department');
    assert.equal(currentDepartmentText(null), 'Not available');
    assert.equal(validityNote({ valid: true }), '');
    assert.match(validityNote({ valid: false, accountActive: true, staffActive: false, departmentActive: true }), /staff record is inactive/);
    assert.match(validityNote({ valid: false, accountActive: false, staffActive: true, departmentActive: false }), /account is inactive, the department is inactive/);
  });

  it('history lines use the stored name snapshots and say who changed it', () => {
    const change = { previousDepartmentName: 'Housekeeping', newDepartmentName: 'Kitchen', changedByUsername: 'root' };
    assert.equal(describeChange(change), 'Housekeeping → Kitchen');
    assert.equal(describeChangedBy(change), 'by root');
    assert.equal(describeChangedBy({}), 'by an Admin');
    assert.equal(describeChange({ previousDepartmentCode: 'A', newDepartmentCode: 'B' }), 'A → B');
  });
});

describe('error handling', () => {
  it('403 says Admin only, 404 and 5xx are plain, 400/409 show the server message', () => {
    assert.equal(managerDepartmentErrorMessage({ status: 403, message: 'internal detail' }), ADMIN_ONLY_MESSAGE);
    assert.match(managerDepartmentErrorMessage({ status: 404, message: 'User not found' }), /could not be found/);
    assert.match(managerDepartmentErrorMessage({ status: 500, message: 'stack trace here' }), /server error/);
    assert.ok(!managerDepartmentErrorMessage({ status: 500, message: 'stack trace here' }).includes('stack trace'));
    assert.equal(managerDepartmentErrorMessage({ status: 409, message: 'This Manager is already assigned to Kitchen. Nothing was changed.' }),
      'This Manager is already assigned to Kitchen. Nothing was changed.');
    assert.equal(managerDepartmentErrorMessage({ status: 400, message: "Department 'Closed' is inactive and cannot be used for new staff or tasks" }),
      "Department 'Closed' is inactive and cannot be used for new staff or tasks");
    assert.match(managerDepartmentErrorMessage({ status: 400 }), /could not be changed/);
    assert.match(managerDepartmentErrorMessage(new Error('Network error')), /Network error/);
  });
});

describe('the API calls', () => {
  it('assign sends PUT with the department code, with the sign-in token', async () => {
    const calls = respond(200, { departmentCode: 'KITCHEN', departmentName: 'Kitchen' });
    const result = await assignManagerDepartment('u4', 'KITCHEN');
    assert.equal(calls.length, 1);
    assert.equal(calls[0].url, '/api/users/u4/manager-department');
    assert.equal(calls[0].options.method, 'PUT');
    assert.deepEqual(JSON.parse(calls[0].options.body), { department: 'KITCHEN' });
    assert.equal(calls[0].options.headers.Authorization, 'Bearer jwt-token');
    assert.equal(result.departmentName, 'Kitchen');
  });

  it('reads the current department and the history from their own paths', async () => {
    const calls = respond(200, []);
    await getManagerDepartment('u4');
    await getManagerDepartmentHistory('u4');
    assert.deepEqual(calls.map((c) => c.url), ['/api/users/u4/manager-department', '/api/users/u4/manager-department/history']);
  });

  it('a refusal surfaces as an error carrying the status and the server message', async () => {
    respond(409, { message: 'This Manager has no linked staff record, so a department cannot be assigned. Nothing was changed.' });
    await assert.rejects(() => assignManagerDepartment('u4', 'KITCHEN'), (err) => {
      assert.equal(err.status, 409);
      assert.match(managerDepartmentErrorMessage(err), /no linked staff record/);
      return true;
    });
    respond(403, { message: 'Only administrators can view or change a Manager department.' });
    await assert.rejects(() => getManagerDepartmentHistory('u4'), (err) => err.status === 403);
  });
});

describe('Admin-only wiring (source guards)', () => {
  const modal = src('components', 'users', 'ManagerDepartmentModal.jsx');
  const page = src('pages', 'UsersPage.jsx');

  it('the dialog renders nothing unless the viewer is an Admin and the target a Manager', () => {
    assert.ok(modal.includes('const permitted = isOpen && canManageManagerDepartment(getUserRole(), user);'));
    assert.ok(modal.includes('if (!permitted) return null;'));
    // No request is made before that check passes
    assert.ok(modal.indexOf('if (!permitted || !userId) return undefined;') < modal.indexOf('getManagerDepartment(userId)'));
  });

  it('the history is read and drawn only for an Admin', () => {
    assert.ok(modal.includes('const showHistory = canViewManagerDepartmentHistory(getUserRole());'));
    assert.ok(modal.includes('if (!showHistory || !userId) return;'));
    assert.ok(modal.includes('{showHistory && hasStaffRecord && ('));
  });

  it('a second submit while one is running is ignored, the button is disabled, and the result refreshes the view', () => {
    assert.ok(modal.includes('if (inFlight.current) return;'));
    assert.ok(modal.includes('inFlight.current = true;') && modal.includes('inFlight.current = false;'));
    assert.ok(modal.includes('disabled={!canSubmit({ currentCode, selectedCode: selected, submitting })}'));
    // after success: show the returned department, reload the history, tell the page to refresh
    const success = modal.slice(modal.indexOf('const updated = await assignManagerDepartment'), modal.indexOf('} catch (err) {', modal.indexOf('const updated = await assignManagerDepartment')));
    assert.ok(success.includes('setInfo(updated)') && success.includes('await loadHistory()') && success.includes('onChanged(updated)'));
    assert.ok(modal.includes('setSubmitError(managerDepartmentErrorMessage(err))'));
  });

  it('the Users page opens it for an Admin only and refreshes after a change', () => {
    assert.ok(page.includes("case 'department':\n        if (!isAdmin) return;"));
    assert.ok(page.includes('isOpen={isAdmin && Boolean(assigningDepartment)}'));
    assert.ok(page.includes('await fetchUsers(false);\n    notify('));
  });

  it('nothing else imports the dialog or the history call (not the Staff page, not a Manager screen)', () => {
    const allowed = new Set(['ManagerDepartmentModal.jsx', 'UsersPage.jsx', 'api.js', 'managerDepartment.test.js']);
    const offenders = [];
    const walk = (dir) => {
      for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
        const full = path.join(dir, entry.name);
        if (entry.isDirectory()) walk(full);
        else if (/\.(jsx?|mjs)$/.test(entry.name) && !allowed.has(entry.name)) {
          const text = fs.readFileSync(full, 'utf8');
          if (text.includes('ManagerDepartmentModal') || text.includes('getManagerDepartmentHistory') || text.includes('assignManagerDepartment')) offenders.push(full);
        }
      }
    };
    walk(path.join(here, '..'));
    assert.deepEqual(offenders, []);
  });
});
