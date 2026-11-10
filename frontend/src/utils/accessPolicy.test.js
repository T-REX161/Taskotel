import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { ROUTE_ACCESS, canAccess, canListStaff, navigationFor, normalizeRole, rolesForPath } from './accessPolicy.js';

const labels = (role) => navigationFor(role).flatMap((group) => group.items.map((item) => item.label));
const paths = (role) => navigationFor(role).flatMap((group) => group.items.map((item) => item.path));

describe('role-aware navigation', () => {
  it('ADMIN sees every screen, including Users', () => {
    assert.deepEqual(labels('ADMIN'), ['Dashboard', 'Staff', 'Tasks', 'Assignments', 'Workload', 'Departments', 'Users', 'Profile']);
  });

  it('MANAGER sees the management screens but not Users', () => {
    assert.deepEqual(labels('MANAGER'), ['Dashboard', 'Staff', 'Tasks', 'Assignments', 'Workload', 'Departments', 'Profile']);
    assert.ok(!paths('MANAGER').includes('/users'));
  });

  it('STAFF sees only Dashboard, My Tasks, My Workload and Profile', () => {
    assert.deepEqual(labels('STAFF'), ['Dashboard', 'My Tasks', 'My Workload', 'Profile']);
    for (const hidden of ['/staff', '/assignments', '/departments', '/users', '/tasks/create']) {
      assert.ok(!paths('STAFF').includes(hidden), `${hidden} is not offered to Staff`);
    }
  });

  it('Staff keep the existing Tasks and Workload screens (same paths, different names): no duplicate pages', () => {
    assert.deepEqual(paths('STAFF').filter((p) => p === '/tasks' || p === '/workload'), ['/tasks', '/workload']);
  });

  it('a missing or unknown role sees no navigation at all', () => {
    for (const role of [null, undefined, '', 'admin', 'SUPERUSER', 'Admin', 42, {}]) assert.deepEqual(navigationFor(role), [], String(role));
  });

  it('every link a role is shown is a screen that role may open (the UI never offers what the route refuses)', () => {
    for (const role of ['ADMIN', 'MANAGER', 'STAFF']) {
      for (const path of paths(role)) assert.ok(canAccess(role, path), `${role} -> ${path}`);
    }
  });
});

describe('Settings is out of the Stage 2 scope', () => {
  it('no role is offered a Settings link', () => {
    for (const role of ['ADMIN', 'MANAGER', 'STAFF']) {
      assert.ok(!labels(role).includes('Settings'), role);
      assert.ok(!paths(role).includes('/settings'), role);
    }
  });

  it('the access table has no rule for /settings, so it is not a protected destination for any role', () => {
    assert.ok(!ROUTE_ACCESS.some((rule) => rule.path.startsWith('/settings')));
  });
});

describe('route access rules', () => {
  const open = (role) => ROUTE_ACCESS.filter((rule) => canAccess(role, rule.path)).map((rule) => rule.path);

  it('ADMIN can open every screen', () => {
    assert.equal(open('ADMIN').length, ROUTE_ACCESS.length);
    assert.ok(canAccess('ADMIN', '/users'));
  });

  it('MANAGER cannot open the Admin-only Users screen but can open the management screens', () => {
    assert.equal(canAccess('MANAGER', '/users'), false);
    for (const path of ['/staff', '/tasks', '/tasks/create', '/assignments', '/workload', '/departments', '/dashboard', '/profile']) {
      assert.ok(canAccess('MANAGER', path), path);
    }
  });

  it('STAFF cannot open any management screen, including task creation and anything below it', () => {
    for (const path of ['/staff', '/staff/12', '/tasks/create', '/assignments', '/departments', '/users', '/users/3']) {
      assert.equal(canAccess('STAFF', path), false, path);
    }
    for (const path of ['/', '/dashboard', '/tasks', '/workload', '/profile']) assert.ok(canAccess('STAFF', path), path);
  });

  it('a longer rule beats a shorter one: Staff may open /tasks but not /tasks/create', () => {
    assert.deepEqual([...rolesForPath('/tasks')], ['ADMIN', 'MANAGER', 'STAFF']);
    assert.deepEqual([...rolesForPath('/tasks/create')], ['ADMIN', 'MANAGER']);
  });

  it('matching ignores case, a trailing slash, a query string and a fragment, so a variant spelling cannot skip a rule', () => {
    for (const path of ['/USERS', '/users/', '/Users?x=1', '/users#top', '/users//']) assert.equal(canAccess('MANAGER', path), false, path);
  });

  it('a prefix that is merely similar does not match (/usersettings is not /users)', () => {
    assert.ok(canAccess('STAFF', '/usersettings'));
  });

  it('an unknown path is the catch-all dashboard, open to signed-in roles; an unknown role opens nothing', () => {
    assert.ok(canAccess('STAFF', '/no-such-page'));
    for (const role of [null, undefined, '', 'admin', 'ROOT']) assert.equal(canAccess(role, '/dashboard'), false, String(role));
  });
});

describe('role value handling', () => {
  it('accepts only the three exact role names and never defaults', () => {
    assert.equal(normalizeRole('ADMIN'), 'ADMIN');
    assert.equal(normalizeRole('MANAGER'), 'MANAGER');
    assert.equal(normalizeRole('STAFF'), 'STAFF');
    for (const bad of [null, undefined, '', ' ADMIN', 'admin', 'ROLE_ADMIN', 'toString', '__proto__', 1, [], {}]) assert.equal(normalizeRole(bad), null, String(bad));
  });

  it('only Admin and Manager may request the staff list (Staff would only get 403)', () => {
    assert.equal(canListStaff('ADMIN'), true);
    assert.equal(canListStaff('MANAGER'), true);
    assert.equal(canListStaff('STAFF'), false);
    assert.equal(canListStaff(null), false);
    assert.equal(canListStaff('bogus'), false);
  });
});
