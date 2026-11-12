import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { filterEligibleStaff } from '../../utils/staffFilter.js';

describe('filterEligibleStaff', () => {
  const sampleStaff = [
    {
      id: 'staff-1',
      role: 'STAFF',
      name: 'Maria Santos',
      department: 'HOUSEKEEPING',
      active: true,
      availability: 'AVAILABLE',
    },
    {
      id: 'staff-2',
      role: 'STAFF',
      name: 'Carlos Rodriguez',
      department: 'HOUSEKEEPING',
      active: true,
      availability: 'AVAILABLE',
    },
    {
      id: 'staff-3',
      role: 'STAFF',
      name: 'Elena Rostova',
      department: 'HOUSEKEEPING',
      active: true,
      availability: 'BUSY',
    },
    {
      id: 'staff-4',
      role: 'STAFF',
      name: 'John Doe',
      department: 'HOUSEKEEPING',
      active: false,
      availability: 'AVAILABLE',
    },
    {
      id: 'staff-5',
      role: 'STAFF',
      name: 'Priya Sharma',
      department: 'FRONT_DESK',
      active: true,
      availability: 'AVAILABLE',
    },
  ];

  it('filters staff strictly by the selected department', () => {
    const result = filterEligibleStaff({
      allStaff: sampleStaff,
      tasks: [],
      assignments: [],
      department: 'FRONT_DESK',
    });

    assert.strictEqual(result.length, 1);
    assert.strictEqual(result[0].id, 'staff-5');
    assert.strictEqual(result[0].name, 'Priya Sharma');
  });

  it('excludes inactive staff and staff whose availability is not AVAILABLE', () => {
    const result = filterEligibleStaff({
      allStaff: sampleStaff,
      tasks: [],
      assignments: [],
      department: 'HOUSEKEEPING',
    });

    const ids = result.map((s) => s.id);
    assert.ok(ids.includes('staff-1'));
    assert.ok(ids.includes('staff-2'));
    assert.ok(!ids.includes('staff-3'), 'staff-3 has availability BUSY, should be excluded');
    assert.ok(!ids.includes('staff-4'), 'staff-4 is inactive, should be excluded');
  });

  it('excludes staff member who already has an active task assignment (ASSIGNED or IN_PROGRESS)', () => {
    const tasks = [
      { id: 'task-101', status: 'ASSIGNED' },
      { id: 'task-102', status: 'IN_PROGRESS' },
    ];
    const assignments = [
      { taskId: 'task-101', staffId: 'staff-1' },
    ];

    const result = filterEligibleStaff({
      allStaff: sampleStaff,
      tasks,
      assignments,
      department: 'HOUSEKEEPING',
    });

    const ids = result.map((s) => s.id);
    assert.ok(!ids.includes('staff-1'), 'staff-1 has an active ASSIGNED task, should be excluded');
    assert.ok(ids.includes('staff-2'), 'staff-2 has no assignments, should be eligible');
  });

  it('includes staff whose previous assignments are only COMPLETED or CANCELLED', () => {
    const tasks = [
      { id: 'task-done', status: 'COMPLETED' },
      { id: 'task-canc', status: 'CANCELLED' },
    ];
    const assignments = [
      { taskId: 'task-done', staffId: 'staff-1' },
      { taskId: 'task-canc', staffId: 'staff-2' },
    ];

    const result = filterEligibleStaff({
      allStaff: sampleStaff,
      tasks,
      assignments,
      department: 'HOUSEKEEPING',
    });

    const ids = result.map((s) => s.id);
    assert.ok(ids.includes('staff-1'), 'staff-1 only has a completed task, should be eligible');
    assert.ok(ids.includes('staff-2'), 'staff-2 only has a cancelled task, should be eligible');
  });

  it('excludes staff if they have one COMPLETED task and one active IN_PROGRESS task', () => {
    const tasks = [
      { id: 'task-done', status: 'COMPLETED' },
      { id: 'task-active', status: 'IN_PROGRESS' },
    ];
    const assignments = [
      { taskId: 'task-done', staffId: 'staff-1' },
      { taskId: 'task-active', staffId: 'staff-1' },
    ];

    const result = filterEligibleStaff({
      allStaff: sampleStaff,
      tasks,
      assignments,
      department: 'HOUSEKEEPING',
    });

    const ids = result.map((s) => s.id);
    assert.ok(!ids.includes('staff-1'), 'staff-1 has an active task alongside completed task, should be excluded');
    assert.ok(ids.includes('staff-2'));
  });

  it('supports taskStatus field directly on assignment record if present', () => {
    const assignments = [
      { taskId: 't-1', staffId: 'staff-1', taskStatus: 'ASSIGNED' },
      { taskId: 't-2', staffId: 'staff-2', taskStatus: 'COMPLETED' },
    ];

    const result = filterEligibleStaff({
      allStaff: sampleStaff,
      tasks: [],
      assignments,
      department: 'HOUSEKEEPING',
    });

    const ids = result.map((s) => s.id);
    assert.ok(!ids.includes('staff-1'), 'staff-1 has taskStatus ASSIGNED, should be excluded');
    assert.ok(ids.includes('staff-2'), 'staff-2 has taskStatus COMPLETED, should be eligible');
  });

  it('returns empty array if no department is specified', () => {
    const result = filterEligibleStaff({
      allStaff: sampleStaff,
      tasks: [],
      assignments: [],
      department: '',
    });

    assert.deepStrictEqual(result, []);
  });

  it('returns empty array if all staff in department have active assignments', () => {
    const tasks = [
      { id: 'task-101', status: 'ASSIGNED' },
      { id: 'task-102', status: 'IN_PROGRESS' },
    ];
    const assignments = [
      { taskId: 'task-101', staffId: 'staff-1' },
      { taskId: 'task-102', staffId: 'staff-2' },
    ];

    const result = filterEligibleStaff({
      allStaff: sampleStaff,
      tasks,
      assignments,
      department: 'HOUSEKEEPING',
    });

    assert.strictEqual(result.length, 0);
  });

  describe('Real backend API response field "availability" and property compatibility', () => {
    it('uses real StaffDto field "availability" and does not drop staff due to missing availabilityStatus', () => {
      const realBackendStaff = [
        {
          id: 's-real-1',
          role: 'STAFF',
          name: 'Elena Rostova',
          email: 'elena@example.com',
          phone: '555-0199',
          department: 'HOUSEKEEPING',
          availability: 'AVAILABLE', // real backend field
          active: true,
        },
        {
          id: 's-real-2',
          role: 'STAFF',
          name: 'Igor Vane',
          email: 'igor@example.com',
          phone: '555-0200',
          department: 'HOUSEKEEPING',
          availability: 'UNAVAILABLE',
          active: true,
        },
      ];

      const result = filterEligibleStaff({
        allStaff: realBackendStaff,
        tasks: [],
        assignments: [],
        department: 'HOUSEKEEPING',
      });

      assert.strictEqual(result.length, 1);
      assert.strictEqual(result[0].id, 's-real-1');
      assert.strictEqual(result[0].name, 'Elena Rostova');
    });

    it('supports fallback when staff object uses availabilityStatus', () => {
      const legacyStaff = [
        {
          id: 's-legacy-1',
          role: 'STAFF',
          name: 'Legacy Staff',
          department: 'HOUSEKEEPING',
          availabilityStatus: 'AVAILABLE', // legacy property name
          active: true,
        },
        {
          id: 's-legacy-2',
          role: 'STAFF',
          name: 'Legacy Busy',
          department: 'HOUSEKEEPING',
          availabilityStatus: 'BUSY',
          active: true,
        },
      ];

      const result = filterEligibleStaff({
        allStaff: legacyStaff,
        tasks: [],
        assignments: [],
        department: 'HOUSEKEEPING',
      });

      assert.strictEqual(result.length, 1);
      assert.strictEqual(result[0].id, 's-legacy-1');
    });

    it('excludes staff when neither availability nor availabilityStatus is AVAILABLE', () => {
      const missingAvailStaff = [
        {
          id: 's-none-1',
          role: 'STAFF',
          name: 'No Avail',
          department: 'HOUSEKEEPING',
          active: true,
        },
      ];

      const result = filterEligibleStaff({
        allStaff: missingAvailStaff,
        tasks: [],
        assignments: [],
        department: 'HOUSEKEEPING',
      });

      assert.strictEqual(result.length, 0);
    });
  });

  describe('Reassignment exclusion rules', () => {
    it('allows currently assigned staff member to be included when excludeTaskId is passed', () => {
      const tasks = [
        { id: 'task-reassign', status: 'ASSIGNED' },
      ];
      const assignments = [
        { id: 'assign-1', taskId: 'task-reassign', staffId: 'staff-1', taskStatus: 'ASSIGNED' },
      ];

      // Without exclusion, staff-1 is blocked
      const blocked = filterEligibleStaff({
        allStaff: sampleStaff,
        tasks,
        assignments,
        department: 'HOUSEKEEPING',
      });
      assert.ok(!blocked.some((s) => s.id === 'staff-1'));

      // With excludeTaskId, staff-1's assignment on task-reassign is ignored, so staff-1 can be rendered in dropdown as currently assigned
      const allowed = filterEligibleStaff({
        allStaff: sampleStaff,
        tasks,
        assignments,
        department: 'HOUSEKEEPING',
        excludeTaskId: 'task-reassign',
      });
      assert.ok(allowed.some((s) => s.id === 'staff-1'));
    });

    it('allows currently assigned staff member to be included when excludeAssignmentId is passed', () => {
      const tasks = [
        { id: 'task-reassign-2', status: 'ASSIGNED' },
      ];
      const assignments = [
        { id: 'assign-99', taskId: 'task-reassign-2', staffId: 'staff-1', taskStatus: 'ASSIGNED' },
      ];

      const allowed = filterEligibleStaff({
        allStaff: sampleStaff,
        tasks,
        assignments,
        department: 'HOUSEKEEPING',
        excludeAssignmentId: 'assign-99',
      });
      assert.ok(allowed.some((s) => s.id === 'staff-1'));
    });
  });
});

describe('filterEligibleStaff: only operational Staff accounts are eligible', () => {
  // Every record satisfies the other rules (department, active, AVAILABLE, no assignment); only the linked user's role differs
  const record = (id, role) => ({ id, name: `Person ${id}`, department: 'HOUSEKEEPING', active: true, availability: 'AVAILABLE', role });
  const pick = (allStaff, extra = {}) => filterEligibleStaff({ allStaff, tasks: [], assignments: [], department: 'HOUSEKEEPING', ...extra }).map((s) => s.id);

  it('a STAFF account that satisfies every other rule stays eligible', () => {
    assert.deepEqual(pick([record('a', 'STAFF')]), ['a']);
  });

  it('an ADMIN account is excluded even though it is in the department, active, available and free', () => {
    assert.deepEqual(pick([record('admin-1', 'ADMIN')]), []);
  });

  it('a MANAGER account is excluded even though every other rule passes', () => {
    assert.deepEqual(pick([record('mgr-1', 'MANAGER')]), []);
  });

  it('a missing, null, empty, lower-case or unknown role is excluded (nothing is assumed)', () => {
    const odd = [undefined, null, '', 'staff', 'Staff', ' STAFF', 'SUPERVISOR', 'ROLE_STAFF', 'ADMINISTRATOR', 0, {}];
    odd.forEach((role, i) => assert.deepEqual(pick([record(`odd-${i}`, role)]), [], `role ${JSON.stringify(role)}`));
    const noRoleField = { id: 'bare', name: 'No Role', department: 'HOUSEKEEPING', active: true, availability: 'AVAILABLE' };
    assert.deepEqual(pick([noRoleField]), []);
  });

  it('in a mixed list only the STAFF accounts remain, in their original order', () => {
    const list = [record('s1', 'STAFF'), record('admin', 'ADMIN'), record('s2', 'STAFF'), record('mgr', 'MANAGER'), record('s3', 'STAFF')];
    assert.deepEqual(pick(list), ['s1', 's2', 's3']);
  });

  it('privileged accounts are identified by role only, never by name, username or id', () => {
    const lookAlike = [
      { ...record('admin', 'STAFF'), name: 'Admin', username: 'admin' },
      { ...record('x', 'ADMIN'), name: 'Ordinary Person', username: 'ordinary' },
    ];
    assert.deepEqual(pick(lookAlike), ['admin'], 'a STAFF account called Admin is eligible, an ADMIN account with an ordinary name is not');
  });

  it('the other rules still apply to STAFF accounts: department, inactive, availability and an active assignment', () => {
    const list = [
      record('ok', 'STAFF'),
      { ...record('other-dept', 'STAFF'), department: 'FRONT_DESK' },
      { ...record('inactive', 'STAFF'), active: false },
      { ...record('busy', 'STAFF'), availability: 'BUSY' },
      { ...record('unavailable', 'STAFF'), availability: 'UNAVAILABLE' },
      record('assigned', 'STAFF'),
      record('finished', 'STAFF'),
    ];
    const assignments = [
      { id: 'a1', taskId: 't1', staffId: 'assigned', taskStatus: 'IN_PROGRESS' },
      { id: 'a2', taskId: 't2', staffId: 'finished', taskStatus: 'COMPLETED' },
    ];
    assert.deepEqual(pick(list, { assignments }), ['ok', 'finished']);
  });

  it('reassignment exclusions still work for STAFF accounts, and still never admit a privileged account', () => {
    const list = [record('s1', 'STAFF'), record('admin', 'ADMIN')];
    const tasks = [{ id: 't', status: 'ASSIGNED' }];
    const assignments = [{ id: 'a', taskId: 't', staffId: 's1', taskStatus: 'ASSIGNED' }];
    assert.deepEqual(pick(list, { tasks, assignments }), []);
    assert.deepEqual(pick(list, { tasks, assignments, excludeTaskId: 't' }), ['s1']);
    assert.deepEqual(pick(list, { tasks, assignments, excludeAssignmentId: 'a' }), ['s1']);
  });

  it('a department-less or empty request still returns nothing', () => {
    assert.deepEqual(filterEligibleStaff({ allStaff: [record('s1', 'STAFF')], department: '' }), []);
    assert.deepEqual(filterEligibleStaff({ allStaff: [], department: 'HOUSEKEEPING' }), []);
  });
});
