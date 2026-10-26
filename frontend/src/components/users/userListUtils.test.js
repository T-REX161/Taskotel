import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { getPaginationState } from '../../utils/assignmentUtils.js';
import {
  USERS_PER_PAGE,
  ROLE_OPTIONS,
  STATUS_OPTIONS,
  normalizeRole,
  roleBadgeClass,
  statusBadge,
  formatCreatedDate,
  isCurrentUser,
  filterUsers,
  sortUsers,
  indexStaffByUserId,
  getUserActions,
} from './userListUtils.js';

const users = [
  { id: 'u1', username: 'rahul', email: 'rahul@hotel.com', role: 'STAFF', active: true, createdAt: '2026-08-25T09:00:00' },
  { id: 'u2', username: 'admin', email: 'admin@hotel.com', role: 'ADMIN', active: true, createdAt: '2026-08-20T08:00:00' },
  { id: 'u3', username: 'Priya', email: 'priya.n@hotel.com', role: 'STAFF', active: false, createdAt: '2026-08-25T10:00:00' },
  { id: 'u4', username: 'manager', email: 'ops@hotel.com', role: 'MANAGER', active: true, createdAt: '2026-08-22T08:00:00' },
  { id: 'u5', username: 'amit', email: 'amit@hotel.com', role: 'STAFF', active: true, createdAt: null },
];
const names = (list) => list.map((u) => u.username);

describe('filterUsers', () => {
  it('searches username and email, case-insensitively and ignoring surrounding spaces', () => {
    assert.deepStrictEqual(names(filterUsers(users, { query: '  PRIYA ' })), ['Priya']);
    assert.deepStrictEqual(names(filterUsers(users, { query: 'ops@' })), ['manager'], 'Email match');
    assert.deepStrictEqual(names(filterUsers(users, { query: 'hotel.com' })).length, 5);
    assert.deepStrictEqual(filterUsers(users, { query: 'nobody' }), []);
  });

  it('filters by role and by status', () => {
    assert.deepStrictEqual(names(filterUsers(users, { role: 'ADMIN' })), ['admin']);
    assert.deepStrictEqual(names(filterUsers(users, { role: 'MANAGER' })), ['manager']);
    assert.deepStrictEqual(names(filterUsers(users, { role: 'STAFF' })), ['rahul', 'Priya', 'amit']);
    assert.deepStrictEqual(names(filterUsers(users, { status: 'INACTIVE' })), ['Priya']);
    assert.strictEqual(filterUsers(users, { status: 'ACTIVE' }).length, 4);
  });

  it('combines search, role and status with AND', () => {
    assert.deepStrictEqual(names(filterUsers(users, { query: 'a', role: 'STAFF', status: 'ACTIVE' })), ['rahul', 'amit']);
    assert.deepStrictEqual(filterUsers(users, { query: 'admin', role: 'STAFF' }), [], 'Admin is not staff');
    assert.deepStrictEqual(filterUsers(users, { role: 'ADMIN', status: 'INACTIVE' }), []);
  });

  it('treats empty filters as "all" and tolerates odd input', () => {
    assert.strictEqual(filterUsers(users, {}).length, 5);
    assert.strictEqual(filterUsers(users).length, 5);
    assert.deepStrictEqual(filterUsers(null, { query: 'x' }), []);
    assert.deepStrictEqual(names(filterUsers([{ username: 'x', role: 'ROLE_ADMIN', active: true }, null], { role: 'ADMIN' })), ['x']);
  });
});

describe('sortUsers', () => {
  it('lists admins, then managers, then staff, A–Z by username within a role, without changing the input', () => {
    const before = names(users);
    assert.deepStrictEqual(names(sortUsers(users)), ['admin', 'manager', 'amit', 'Priya', 'rahul']);
    assert.deepStrictEqual(names(users), before);
    assert.deepStrictEqual(sortUsers(undefined), []);
  });
});

describe('badges and dates', () => {
  it('role badges: Admin purple, Manager blue, Staff gray', () => {
    assert.strictEqual(roleBadgeClass('ADMIN'), 'bg-purple-100 text-purple-700');
    assert.strictEqual(roleBadgeClass('ROLE_MANAGER'), 'bg-blue-100 text-blue-700');
    assert.strictEqual(roleBadgeClass('STAFF'), 'bg-gray-100 text-gray-700');
    assert.strictEqual(roleBadgeClass('SOMETHING_ELSE'), 'bg-gray-100 text-gray-700');
    assert.strictEqual(normalizeRole('admin'), 'ADMIN');
    assert.strictEqual(normalizeRole('ROOT'), '');
  });

  it('status badges: light green for Active, muted red for Inactive', () => {
    assert.deepStrictEqual(statusBadge(true), { label: 'Active', className: 'bg-emerald-100 text-emerald-700' });
    assert.deepStrictEqual(statusBadge(false), { label: 'Inactive', className: 'bg-red-50 text-red-600' });
  });

  it('created dates use the project date format and never shift with the timezone', () => {
    assert.strictEqual(formatCreatedDate('2026-08-20T08:00:00'), 'Aug 20, 2026');
    assert.strictEqual(formatCreatedDate('2026-09-01T23:59:59.123'), 'Sep 1, 2026');
    assert.strictEqual(formatCreatedDate('2026-12-31T00:00:00Z'), 'Dec 31, 2026');
    assert.strictEqual(formatCreatedDate([2026, 8, 25, 9, 0]), 'Aug 25, 2026');
    assert.strictEqual(formatCreatedDate(null), '—');
    assert.strictEqual(formatCreatedDate('not a date'), '—');
  });

  it('offers exactly the role and status choices of the reference', () => {
    assert.deepStrictEqual(ROLE_OPTIONS.map((o) => o.label), ['All Roles', 'Admin', 'Manager', 'Staff']);
    assert.deepStrictEqual(STATUS_OPTIONS.map((o) => o.label), ['All Status', 'Active', 'Inactive']);
  });
});

describe('row actions', () => {
  const activeStaff = { id: 's1', userId: 'u1', active: true };
  const inactiveStaff = { id: 's3', userId: 'u3', active: false };

  it('links accounts to staff records by user id', () => {
    const map = indexStaffByUserId([activeStaff, inactiveStaff, { id: 's9' }, null]);
    assert.strictEqual(map.get('u1'), activeStaff);
    assert.strictEqual(map.size, 2);
  });

  it('an account without a staff record offers no actions, and says why', () => {
    const result = getUserActions(users[1], { staff: null, currentUsername: 'someone', isAdmin: true });
    assert.deepStrictEqual(result.actions, []);
    assert.match(result.note, /no staff record/);
    assert.match(getUserActions(users[1], { staff: null, currentUsername: 'ADMIN', isAdmin: true }).note, /your account/);
  });

  it('an active account: view, edit and deactivate (deactivation is confirmed in a dialog)', () => {
    assert.deepStrictEqual(getUserActions(users[0], { staff: activeStaff, currentUsername: 'admin', isAdmin: true }).actions, ['view', 'edit', 'deactivate']);
  });

  it('an inactive account: activate, and remove only for administrators', () => {
    assert.deepStrictEqual(getUserActions(users[2], { staff: inactiveStaff, currentUsername: 'admin', isAdmin: true }).actions, ['view', 'edit', 'activate', 'remove']);
    assert.deepStrictEqual(getUserActions(users[2], { staff: inactiveStaff, currentUsername: 'admin', isAdmin: false }).actions, ['view', 'edit', 'activate']);
  });

  it('never offers to deactivate or remove your own account', () => {
    const own = getUserActions(users[0], { staff: activeStaff, currentUsername: 'RAHUL', isAdmin: true });
    assert.deepStrictEqual(own.actions, ['view', 'edit']);
    assert.match(own.note, /your account/);
    const ownInactive = getUserActions(users[2], { staff: inactiveStaff, currentUsername: 'priya', isAdmin: true });
    assert.ok(!ownInactive.actions.includes('remove'));
    assert.ok(isCurrentUser(users[2], ' priya ') && !isCurrentUser(users[2], '') && !isCurrentUser(null, 'priya'));
  });
});

describe('pagination of the users list (10 per page)', () => {
  // 27 accounts: an admin, a manager and 25 staff, every fourth one inactive
  const many = [
    { id: 'a', username: 'admin', email: 'admin@hotel.com', role: 'ADMIN', active: true },
    { id: 'm', username: 'manager', email: 'manager@hotel.com', role: 'MANAGER', active: true },
    ...Array.from({ length: 25 }, (_, i) => ({
      id: `s${i + 1}`,
      username: `staff${String(i + 1).padStart(2, '0')}`,
      email: `staff${i + 1}@hotel.com`,
      role: 'STAFF',
      active: (i + 1) % 4 !== 0,
    })),
  ];
  const page = (list, number) => {
    const state = getPaginationState({ totalItems: list.length, currentPage: number, pageSize: USERS_PER_PAGE });
    return { state, rows: list.slice(state.startIndex, state.endIndex) };
  };
  const allPages = (list) => {
    const { totalPages } = page(list, 1).state;
    return Array.from({ length: totalPages }, (_, i) => page(list, i + 1));
  };

  it('shows at most 10 users per page; the last page holds the remainder', () => {
    assert.strictEqual(USERS_PER_PAGE, 10);
    const pages = allPages(sortUsers(many));
    assert.deepStrictEqual(pages.map((p) => p.rows.length), [10, 10, 7]);
    assert.deepStrictEqual(pages.map((p) => `${p.state.rangeStart}-${p.state.rangeEnd}`), ['1-10', '11-20', '21-27']);
    assert.ok(!pages[0].state.hasPrevious && pages[0].state.hasNext, 'First page: no Previous');
    assert.ok(pages[2].state.hasPrevious && !pages[2].state.hasNext, 'Last page: no Next');
  });

  it('every user appears on exactly one page, in list order (nothing skipped or repeated)', () => {
    const sorted = sortUsers(many);
    const seen = allPages(sorted).flatMap((p) => p.rows.map((u) => u.id));
    assert.deepStrictEqual(seen, sorted.map((u) => u.id));
    assert.strictEqual(new Set(seen).size, many.length);
  });

  it('search and filters apply to the whole list before it is cut into pages', () => {
    const activeStaff = sortUsers(filterUsers(many, { role: 'STAFF', status: 'ACTIVE' }));
    assert.strictEqual(activeStaff.length, 19);
    const pages = allPages(activeStaff);
    assert.deepStrictEqual(pages.map((p) => p.rows.length), [10, 9]);
    assert.ok(pages.every((p) => p.rows.every((u) => u.role === 'STAFF' && u.active)));
    assert.strictEqual(allPages(sortUsers(filterUsers(many, { query: 'staff1' }))).length, 2, '"staff1" matches staff01 and staff10–19: 11 users, 2 pages');
    assert.deepStrictEqual(allPages(sortUsers(filterUsers(many, { status: 'INACTIVE' }))).map((p) => p.rows.length), [6]);
  });

  it('a page that no longer exists falls back to the last one, and an empty list has a single empty page', () => {
    const shrunk = sortUsers(many).slice(0, 12);
    assert.strictEqual(page(shrunk, 3).state.page, 2, 'Was on page 3; only 2 pages remain');
    assert.deepStrictEqual(page(shrunk, 3).rows.map((u) => u.id), shrunk.slice(10).map((u) => u.id));
    const empty = page([], 1).state;
    assert.deepStrictEqual([empty.totalPages, empty.rangeStart, empty.rangeEnd, empty.hasNext], [1, 0, 0, false]);
  });
});
