import { describe, it, before, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  NONE_OPTION_LABEL,
  UNASSIGNED_FILTER,
  UNASSIGNED_LABEL,
  canChooseNoDepartment,
  canFilterUnassigned,
  departmentLabel,
  departmentPayload,
  departmentProblem,
  hasNoDepartment,
  isUnassignedManager,
  UNASSIGNED_MANAGER_NOTICE,
  matchesDepartmentFilter,
} from './staffDepartment.js';
import { formatDepartment } from '../components/staff/staffFormatters.js';
import { loadModule, renderHtml } from '../testSupport/renderJsx.js';

const dir = path.dirname(fileURLToPath(import.meta.url));
const read = (rel) => fs.readFileSync(path.join(dir, '..', rel), 'utf8').replace(/\r\n/g, '\n');
const addSource = read('components/staff/AddStaffModal.jsx');
const editSource = read('components/staff/EditStaffModal.jsx');
const detailsSource = read('components/staff/StaffDetailsModal.jsx');
const pageSource = read('pages/StaffPage.jsx');

const unassigned = { id: 1, fullName: 'Una Signed', email: 'una@hotel.test', username: 'una', role: 'STAFF', department: null, active: true, availability: 'AVAILABLE' };
const housekeeper = { id: 2, fullName: 'Hana Keeper', email: 'hana@hotel.test', username: 'hana', role: 'STAFF', department: 'HOUSEKEEPING', active: true, availability: 'AVAILABLE' };

describe('Add Staff: department choice and payload', () => {
  it('(1) Admin creating Staff with a department sends the code', () => {
    assert.equal(departmentPayload('HOUSEKEEPING'), 'HOUSEKEEPING');
    assert.equal(departmentProblem({ viewerRole: 'ADMIN', accountRole: 'STAFF', department: 'HOUSEKEEPING' }), '');
  });

  it('(2) Admin creating Staff with None (Unassigned) is allowed and sends null', () => {
    assert.equal(canChooseNoDepartment('ADMIN', 'STAFF'), true);
    assert.equal(departmentProblem({ viewerRole: 'ADMIN', accountRole: 'STAFF', department: '' }), '');
    assert.equal(departmentPayload(''), null);
    assert.equal(departmentPayload('   '), null);
    assert.equal(departmentPayload(undefined), null);
  });

  it('(3) an Admin may create an Admin, a Manager or a Staff account with None (Unassigned), and with a department too', () => {
    for (const accountRole of ['ADMIN', 'MANAGER', 'STAFF']) {
      assert.equal(canChooseNoDepartment('ADMIN', accountRole), true, accountRole);
      assert.equal(departmentProblem({ viewerRole: 'ADMIN', accountRole, department: '' }), '', accountRole);
      assert.equal(departmentPayload(''), null, accountRole);
      assert.equal(departmentProblem({ viewerRole: 'ADMIN', accountRole, department: 'MAINTENANCE' }), '', accountRole);
    }
  });

  it('(4) a Manager creating Staff cannot leave the department empty and is never offered None', () => {
    assert.equal(canChooseNoDepartment('MANAGER', 'STAFF'), false);
    assert.equal(departmentProblem({ viewerRole: 'MANAGER', accountRole: 'STAFF', department: '' }), 'Department is required.');
    assert.equal(canChooseNoDepartment('STAFF', 'STAFF'), false);
    assert.equal(canChooseNoDepartment(null, 'STAFF'), false);
  });

  it('the Add form wires the rule in: None only through allowNone, null payload, validation before the other checks, role-aware default', () => {
    assert.ok(addSource.includes('canChooseNoDepartment(getUserRole(), effectiveRole)'));
    assert.ok(addSource.includes('<DepartmentOptions departments={departments} activeOnly allowNone={allowNoDepartment} />'));
    assert.ok(addSource.includes('department: departmentPayload(formData.department),'));
    assert.ok(addSource.includes("role: canChooseRole ? formData.role : 'STAFF',"));
    assert.ok(addSource.indexOf('departmentProblem(') < addSource.indexOf("setFormError('Email is required.')"));
    assert.ok(addSource.includes("if (formData.department === '' && allowNoDepartment) return;"), 'an empty choice is kept while it is allowed');
    assert.ok(addSource.includes('label="Department *"'), 'the label is unchanged');
  });
});

describe('Edit Staff: assign, clear, and who may', () => {
  it('(5) Admin assigns a department to unassigned Staff: the form opens empty and the chosen code is sent', () => {
    assert.ok(editSource.includes("department: staff.department || '',"), 'no invented default department');
    assert.ok(!editSource.includes("|| 'HOUSEKEEPING'"));
    assert.equal(departmentPayload('FRONT_DESK'), 'FRONT_DESK');
    assert.equal(departmentProblem({ viewerRole: 'ADMIN', accountRole: 'STAFF', department: 'FRONT_DESK' }), '');
  });

  it('(6) Admin clears a Staff department with None (Unassigned): null is sent, 409 from the server is still shown', () => {
    assert.equal(canChooseNoDepartment('ADMIN', 'STAFF'), true);
    assert.equal(departmentPayload(''), null);
    assert.ok(editSource.includes('department: departmentPayload(formData.department),'));
    assert.ok(editSource.includes('canChooseNoDepartment(viewerRole, staff.role'));
    assert.ok(editSource.includes('allowNone={allowNoDepartment}'));
    assert.ok(editSource.includes('if (err.status === 409) {') && editSource.includes("err.message || 'A conflict occurred. Please check your data.'"));
  });

  it('Admin can clear or assign a department for ADMIN, MANAGER and STAFF records', () => {
    for (const role of ['ADMIN', 'MANAGER', 'STAFF']) {
      assert.equal(canChooseNoDepartment('ADMIN', role), true, role);
      assert.equal(departmentProblem({ viewerRole: 'ADMIN', accountRole: role, department: '' }), '', role);
      assert.equal(departmentPayload('FRONT_DESK'), 'FRONT_DESK');
    }
  });

  it('an unassigned Manager or Admin opens on None (Unassigned), not an invented department; a Manager viewer cannot edit the select', () => {
    assert.ok(editSource.includes("department: staff.department || '',"));
    assert.ok(editSource.includes("const canEditDepartment = viewerRole === 'ADMIN';"));
    assert.ok(editSource.includes('disabled={isSubmitting || !canEditDepartment}'));
  });

  it('an unassigned Manager gets a clear notice in the edit form and the details view; other records do not', () => {
    assert.equal(isUnassignedManager({ role: 'MANAGER', department: null }), true);
    assert.equal(isUnassignedManager({ role: 'MANAGER', department: '' }), true);
    assert.equal(isUnassignedManager({ role: 'MANAGER', department: 'HOUSEKEEPING' }), false);
    assert.equal(isUnassignedManager({ role: 'ADMIN', department: null }), false, 'an Admin keeps full access');
    assert.equal(isUnassignedManager({ role: 'STAFF', department: null }), false);
    assert.equal(isUnassignedManager(null), false);
    assert.ok(/cannot access any department-scoped data/.test(UNASSIGNED_MANAGER_NOTICE));
    assert.ok(editSource.includes('{isUnassignedManager(staff) && ('));
    assert.ok(detailsSource.includes('{isUnassignedManager(staff) && <p'));
  });

  it('(7) a Manager cannot clear a department or be offered None', () => {
    assert.equal(canChooseNoDepartment('MANAGER', 'STAFF'), false);
    assert.equal(departmentProblem({ viewerRole: 'MANAGER', accountRole: 'STAFF', department: '' }), 'Department is required.');
  });

  it('transfer restrictions, validation and the existing handlers are untouched', () => {
    for (const status of [404, 403, 400]) assert.ok(editSource.includes(`err.status === ${status}`), String(status));
    assert.ok(editSource.includes('email: staff.email,') && editSource.includes("'Full name is required.'"));
  });
});

describe('Unassigned display', () => {
  it('(8) shows Unassigned, never None, null, undefined or an empty cell', () => {
    for (const value of [null, undefined, '', '   ', 42, {}]) {
      assert.equal(formatDepartment(value), UNASSIGNED_LABEL, String(value));
      assert.equal(departmentLabel(value), 'Unassigned');
    }
    assert.equal(formatDepartment('HOUSEKEEPING'), 'Housekeeping');
    assert.equal(UNASSIGNED_LABEL, 'Unassigned');
    assert.equal(NONE_OPTION_LABEL, 'None (Unassigned)');
    assert.equal(hasNoDepartment('HOUSEKEEPING'), false);
  });

  it('the details modal and the table use the formatter', () => {
    assert.ok(detailsSource.includes('{formatDepartment(staff.department)}'));
    assert.ok(read('components/staff/StaffTable.jsx').includes('{formatDepartment(staff.department)}'));
  });

  describe('rendered table', () => {
    const saved = {};
    before(() => {
      saved.localStorage = globalThis.localStorage;
      globalThis.localStorage = { getItem: () => 'ADMIN', setItem() {}, removeItem() {} };
    });
    after(() => {
      globalThis.localStorage = saved.localStorage;
    });

    it('renders Unassigned for Staff without a department and the real name for the others, without crashing', async () => {
      const { default: StaffTable } = await loadModule('components/staff/StaffTable.jsx');
      const html = renderHtml(StaffTable, { staffList: [unassigned, housekeeper, { ...unassigned, id: 3, fullName: 'No Key', department: undefined }], currentUserRole: 'ADMIN' });
      assert.equal((html.match(/>Unassigned</g) || []).length, 2);
      assert.ok(html.includes('>Housekeeping<'));
      assert.ok(!/>(None|null|undefined)</.test(html));
    });
  });

  describe('rendered department options', () => {
    const departments = [{ code: 'HOUSEKEEPING', name: 'Housekeeping', active: true }];
    it('offers None (Unassigned) first only when allowed', async () => {
      const { default: DepartmentOptions } = await loadModule('components/common/DepartmentOptions.jsx');
      const withNone = renderHtml(() => DepartmentOptions({ departments, activeOnly: true, allowNone: true }));
      const without = renderHtml(() => DepartmentOptions({ departments, activeOnly: true }));
      assert.ok(withNone.startsWith('<option value="">None (Unassigned)</option>'), withNone);
      assert.ok(!without.includes('None'));
      assert.ok(without.includes('Housekeeping'));
    });
  });
});

describe('Filters and search', () => {
  it('(9) the department filter handles Staff without a department', () => {
    assert.equal(matchesDepartmentFilter(unassigned, ''), true);
    assert.equal(matchesDepartmentFilter(unassigned, 'HOUSEKEEPING'), false, 'does not crash and is not a match');
    assert.equal(matchesDepartmentFilter({ ...unassigned, department: undefined }, 'housekeeping'), false);
    assert.equal(matchesDepartmentFilter(unassigned, UNASSIGNED_FILTER), true);
    assert.equal(matchesDepartmentFilter({ ...unassigned, department: '' }, UNASSIGNED_FILTER), true);
    assert.equal(matchesDepartmentFilter(housekeeper, UNASSIGNED_FILTER), false);
    assert.equal(matchesDepartmentFilter(housekeeper, 'housekeeping'), true, 'case-insensitive as before');
    assert.equal(matchesDepartmentFilter(null, 'HOUSEKEEPING'), false);
  });

  it('only an Admin is offered the Unassigned filter entry', () => {
    assert.equal(canFilterUnassigned('ADMIN'), true);
    assert.equal(canFilterUnassigned('MANAGER'), false);
    assert.equal(canFilterUnassigned(null), false);
    const filters = read('components/staff/StaffFilters.jsx');
    assert.ok(filters.includes('{canFilterUnassigned(getUserRole()) && <option value={UNASSIGNED_FILTER}>{UNASSIGNED_LABEL}</option>}'));
    assert.ok(filters.includes('<option value="">All Departments</option>'));
  });

  it('(10) search by name, email, username and phone still works for unassigned Staff (the page code is unchanged and null-safe)', () => {
    assert.ok(pageSource.includes("const name = (staff.fullName || staff.name || '').toLowerCase();"));
    assert.ok(pageSource.includes("const email = (staff.email || '').toLowerCase();"));
    assert.ok(pageSource.includes("const username = (staff.username || '').toLowerCase();"));
    assert.ok(pageSource.includes('const dept = formatDepartment(staff.department).toLowerCase();'));
    assert.ok(pageSource.includes('if (!matchesDepartmentFilter(staff, selectedDepartment)) return false;'));
    // the same expression the page evaluates, on an unassigned record
    const query = 'una';
    const dept = formatDepartment(unassigned.department).toLowerCase();
    assert.ok([unassigned.fullName, unassigned.email, unassigned.username, dept].some((v) => v.toLowerCase().includes(query)));
    assert.ok(dept.includes('unassigned'), 'searching "unassigned" finds them as well');
  });
});

describe('Existing flows are unchanged', () => {
  it('(11) other fields, labels and defaults still behave as before', () => {
    assert.ok(addSource.includes("department: 'HOUSEKEEPING',"), 'Add form still starts on a real department');
    assert.ok(addSource.includes('temporaryPassword: formData.temporaryPassword,'));
    assert.ok(addSource.includes("const canChooseRole = getUserRole() === 'ADMIN';"));
    assert.ok(editSource.includes('active: formData.active,'));
    assert.ok(editSource.includes('<DepartmentOptions departments={departments} activeOnly currentCode={staff?.department}'));
    assert.equal(departmentProblem({ viewerRole: 'ADMIN', accountRole: 'STAFF', department: 'SECURITY' }), '');
  });

  it('(11) the None selection survives role changes in the Add form: no code path replaces an allowed empty department', () => {
    // The only effect that writes the department returns early for an empty choice while None is allowed (an Admin, any role) ...
    const effect = addSource.slice(addSource.indexOf("if (formData.department === '' && allowNoDepartment) return;"));
    assert.ok(effect.indexOf("return;") < effect.indexOf('setFormData((prev) => ({ ...prev, department: activeDepartments[0].code }))'));
    // ... and changing the role only changes the role field
    assert.ok(addSource.includes('[name]: value,'));
    assert.ok(!/name === 'role'.*department/.test(addSource), 'the role change does not touch the department');
    for (const role of ['STAFF', 'MANAGER', 'ADMIN', 'MANAGER']) {
      assert.equal(canChooseNoDepartment('ADMIN', role), true, 'None stays available after switching to ' + role);
    }
    // a Manager viewer is never offered None, and Manager-created Staff are held to the Manager's own department
    assert.equal(canChooseNoDepartment('MANAGER', 'STAFF'), false);
  });
});
