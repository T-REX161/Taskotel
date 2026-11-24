import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { onlyStaffRole, isAvailable, sortStaffAlphabetically } from './staffFormatters.js';
import { matchesDepartmentFilter } from '../../utils/staffDepartment.js';

const here = path.dirname(fileURLToPath(import.meta.url));
const read = (...p) => fs.readFileSync(path.join(here, ...p), 'utf8');

const roster = [
  { id: '1', name: 'Zapcom', role: 'ADMIN', department: 'FRONT_DESK', availability: 'AVAILABLE', active: true },
  { id: '2', name: 'Maria', role: 'MANAGER', department: 'FRONT_DESK', availability: 'AVAILABLE', active: true },
  { id: '3', name: 'Rahul', role: 'STAFF', department: 'FRONT_DESK', availability: 'AVAILABLE', active: true },
  { id: '4', name: 'Priya', role: 'STAFF', department: 'HOUSEKEEPING', availability: 'UNAVAILABLE', active: false },
  { id: '5', name: 'Legacy', department: 'FRONT_DESK', active: true }, // no role: not trusted as staff
];

describe('Staff Management shows STAFF-role accounts only', () => {
  it('drops ADMIN, MANAGER and role-less records and keeps STAFF', () => {
    assert.deepEqual(onlyStaffRole(roster).map((s) => s.name), ['Rahul', 'Priya']);
  });

  it('is case-insensitive on the role and tolerates bad input', () => {
    assert.deepEqual(onlyStaffRole([{ id: 'a', role: 'staff' }]).map((s) => s.id), ['a']);
    assert.deepEqual(onlyStaffRole(null), []);
    assert.deepEqual(onlyStaffRole([null, undefined]), []);
  });

  it('search, department, availability and pagination act on the role-filtered list', () => {
    const staff = sortStaffAlphabetically(onlyStaffRole(roster));
    assert.equal(staff.length, 2); // the total used for "Showing x of N" and the page count
    const frontDesk = staff.filter((s) => matchesDepartmentFilter(s, 'FRONT_DESK'));
    assert.deepEqual(frontDesk.map((s) => s.name), ['Rahul']);
    assert.deepEqual(staff.filter((s) => isAvailable(s.availability)).map((s) => s.name), ['Rahul']);
    assert.equal(Math.ceil(staff.length / 10), 1);
    assert.ok(!staff.some((s) => /zapcom/i.test(s.name)), 'searching for the Admin finds nothing');
  });

  it('StaffPage applies the safeguard; only the Users page asks for Manager and Admin records', () => {
    const staffPage = read('..', '..', 'pages', 'StaffPage.jsx');
    assert.ok(staffPage.includes('onlyStaffRole(staffData)'));
    assert.ok(!staffPage.includes('includePrivileged'));
    assert.ok(read('..', '..', 'pages', 'UsersPage.jsx').includes('getStaff({ includePrivileged: true })'));
    assert.ok(read('..', '..', 'services', 'api.js').includes('/api/staff?includePrivileged=true'));
  });
});

describe('Department head picker and the other getStaff() consumers', () => {
  const page = (...p) => read('..', '..', ...p);

  it('only the Admin-only head picker and the Users page opt in to Manager and Admin records', () => {
    const optIns = ['pages/StaffPage.jsx', 'pages/CreateTaskPage.jsx', 'pages/TasksPage.jsx', 'pages/WorkloadPage.jsx',
      'pages/UsersPage.jsx', 'components/departments/DepartmentFormModal.jsx']
      .filter((file) => page(...file.split('/')).includes('includePrivileged'));
    assert.deepEqual(optIns, ['pages/UsersPage.jsx', 'components/departments/DepartmentFormModal.jsx']);
  });

  it('the head picker asks for them only as an Administrator and keeps its active-staff and current-head rules', () => {
    const modal = page('components', 'departments', 'DepartmentFormModal.jsx');
    assert.ok(modal.includes("getStaff({ includePrivileged: normalizeRole(getUserRole()) === 'ADMIN' })"));
    assert.ok(modal.includes('member.active !== false || member.id === department?.headStaffId'));
  });

  it('assignment pickers still accept STAFF only, whatever the list holds', async () => {
    const { filterEligibleStaff } = await import('../../utils/staffFilter.js');
    const open = (role) => ({ id: role, role, department: 'FRONT_DESK', active: true, availability: 'AVAILABLE' });
    const eligible = filterEligibleStaff({ allStaff: ['ADMIN', 'MANAGER', 'STAFF'].map(open), department: 'FRONT_DESK' });
    assert.deepEqual(eligible.map((s) => s.role), ['STAFF']);
  });
});
