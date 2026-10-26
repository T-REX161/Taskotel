import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
// Line endings are normalised so the checks behave the same on LF and CRLF (Windows) checkouts
const read = (...parts) => fs.readFileSync(path.join(__dirname, ...parts), 'utf8').replace(/\r\n/g, '\n');
const page = read('UsersPage.jsx');
const table = read('..', 'components', 'users', 'UsersTable.jsx');
const addModal = read('..', 'components', 'staff', 'AddStaffModal.jsx');
const api = read('..', 'services', 'api.js');

describe('UsersPage layout', () => {
  it('has the reference header: Users, the subtitle and a green Add User button', () => {
    assert.ok(page.includes('>Users</h1>') && page.includes('Manage system users and roles.'));
    assert.ok(/<PlusIcon className="w-5 h-5" \/>\s*Add User/.test(page));
    assert.ok(page.includes('bg-emerald-600 hover:bg-emerald-700'));
  });

  it('replaces the summary cards with a single search-and-filters row', () => {
    for (const removed of ['Total Users', 'Administrators', 'Staff Users', 'Registered System Users']) {
      assert.ok(!page.includes(removed), `${removed} is gone`);
    }
    assert.ok(page.includes('placeholder="Search users..."'));
    assert.ok(page.includes('options={ROLE_OPTIONS}') && page.includes('options={STATUS_OPTIONS}'));
    assert.ok(page.includes('filterUsers(users, { query, role: roleFilter, status: statusFilter })'), 'Filters combine');
    assert.ok(page.includes('grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-[minmax(0,1.35fr)_minmax(0,1fr)_minmax(0,1fr)]'), 'Stacked on mobile, one row on desktop');
  });

  it('shows a clear empty state with a way to reset the filters', () => {
    assert.ok(page.includes('No users match your filters') && page.includes('Clear filters'));
    assert.ok(/const clearFilters = \(\) => \{\s*setQuery\(''\);\s*setRoleFilter\(''\);\s*setStatusFilter\(''\);/.test(page));
  });

  it('keeps the admin-only access rule', () => {
    assert.ok(page.includes("const isAdmin = userRole === 'ADMIN';"));
    assert.ok(page.includes('You do not have permission to view the Users management page.'));
    assert.ok(page.indexOf('if (!isAdmin) {') > page.lastIndexOf('useMemo('), 'Every hook runs before the early return');
  });
});

describe('UsersTable', () => {
  it('has exactly the reference columns, in order', () => {
    const headers = [...table.matchAll(/<th scope="col"[^>]*>([^<]+)<\/th>/g)].map((m) => m[1]);
    assert.deepStrictEqual(headers, ['Username', 'Email', 'Role', 'Status', 'Created', 'Actions']);
  });

  it('uses the role and status badges and the project date format', () => {
    assert.ok(table.includes('roleBadgeClass(user.role)') && table.includes('statusBadge(user.active)'));
    assert.ok(table.includes('formatCreatedDate(user.createdAt)'));
  });

  it('the menu anchors to the clicked button even when React applies the update after the event', () => {
    assert.ok(/const buttonEl = event\.currentTarget;\s*setActiveMenu\(\(current\) => \(current\?\.user\?\.id === user\.id \? null : \{ user, buttonEl \}\)\);/.test(table));
    assert.ok(!/setActiveMenu\(\([^)]*\) =>[^;]*event\.currentTarget/.test(table), 'currentTarget is never read inside a state updater');
  });

  it('every row has a three-dot menu, rendered outside the scroll container', () => {
    assert.ok(table.includes('<DotsVerticalIcon') && table.includes('aria-label={`Actions for ${user.username}`}'));
    assert.ok(table.includes('createPortal(') && table.includes("position: 'fixed'"));
    assert.ok(table.includes('overflow-x-auto') && table.includes('min-w-[800px]'), 'Scrolls sideways on small screens instead of breaking the page');
  });
});

describe('data and actions', () => {
  it('loads real users and their staff records from the API; nothing is hardcoded', () => {
    assert.ok(page.includes('getUsers(),') && page.includes('getStaff()'));
    assert.ok(!/@hotel\.com|'rahul'|'priya'/.test(page + table), 'No sample users');
  });

  it('Add User reuses the existing staff creation flow (no second user-creation endpoint)', () => {
    assert.ok(page.includes('<AddStaffModal') && page.includes('title="Add User"'));
    assert.ok(!/createUser|\/api\/users['"`],\s*\{\s*method: 'POST'/.test(page + api));
    assert.ok(page.includes('submitLabel="Add User"'), 'The form is labelled Add User here');
    assert.ok(addModal.includes("title = 'Add New Staff'") && addModal.includes("submitLabel = 'Add Staff'"), 'The Staff page keeps its title and button');
    assert.ok(/const handleAddSuccess = async[\s\S]*?await fetchUsers\(false\);/.test(page), 'The list refreshes after creation');
  });

  it('only existing endpoints are used, and destructive ones are confirmed in a dialog', () => {
    for (const fn of ['activateStaff', 'deactivateStaff', 'removeStaff']) assert.ok(page.includes(fn), fn);
    assert.ok(/case 'deactivate':[\s\S]*?setDeactivating\(\{ staff, user \}\);/.test(page), 'Deactivate opens the confirmation dialog');
    assert.ok(/case 'remove':[\s\S]*?setRemoving\(\{ staff, user \}\);/.test(page), 'Remove opens the confirmation dialog');
    assert.ok(page.includes('<DeactivateStaffModal') && page.includes('<RemoveStaffModal'));
    assert.ok(page.includes('await deactivateStaff(deactivating.staff.id);') && page.includes('await removeStaff(removing.staff.id);'));
  });

  it('never deactivates or removes your own account, and removal stays admin-only', () => {
    assert.ok(/case 'deactivate':\s*if \(own\) return;/.test(page));
    assert.ok(/case 'remove':\s*if \(own \|\| !isAdmin\) return;/.test(page));
  });
});

describe('Users pagination', () => {
  const footer = read('..', 'components', 'common', 'TablePagination.jsx');

  it('cuts 10 users per page from the searched, filtered and sorted list', () => {
    assert.ok(page.includes('getPaginationState({ totalItems: visibleUsers.length, currentPage, pageSize })'));
    assert.ok(page.includes('useState(USERS_PER_PAGE)'), 'The chosen page size starts at 10');
    assert.ok(page.includes('visibleUsers.slice(pagination.startIndex, pagination.endIndex)'));
    assert.ok(page.includes('users={pageUsers}'), 'The table shows only the current page');
    assert.ok(page.includes("from '../utils/assignmentUtils'"), 'Reuses the shared, tested pagination helper');
  });

  it('returns to page 1 when the search or a filter changes, and keeps the page valid when the list shrinks', () => {
    assert.ok(/useEffect\(\(\) => \{\s*setCurrentPage\(1\);\s*\}, \[query, roleFilter, statusFilter, pageSize\]\);/.test(page), 'Search, filters and page size all restart at page 1');
    // The clamp builds on the latest page, so it never undoes a reset to page 1 made in the same render
    assert.ok(/if \(currentPage !== pagination\.page\) \{\s*setCurrentPage\(\(page\) => Math\.min\(page, pagination\.totalPages\)\);/.test(page));
    assert.ok(page.indexOf('setCurrentPage(1)') < page.indexOf('if (!isAdmin) {'), 'Hooks run before the early return');
  });

  it('the footer sits inside the table card, under the last row and outside the sideways-scrolling area', () => {
    const scrollEnd = table.indexOf('</table>');
    const footerAt = table.indexOf('{footer}');
    assert.ok(footerAt > scrollEnd && footerAt < table.indexOf('createPortal('));
    assert.ok(page.includes('<TablePagination') && page.includes("noun={['user', 'users']}") && page.includes('totalItems={visibleUsers.length}'));
  });

  it('matches the existing pagination footer: summary, Prev, numbered pages, Next, green current page', () => {
    assert.ok(footer.includes('if (!totalItems) return null;'), 'No pagination for an empty result');
    assert.ok(footer.includes('{pagination.totalPages > 1 && ('), 'No page buttons when everything fits on one page');
    assert.ok(footer.includes('Showing') && footer.includes('{pagination.rangeStart}') && footer.includes('{pagination.rangeEnd}'));
    assert.ok(footer.includes('disabled={!pagination.hasPrevious}') && footer.includes('disabled={!pagination.hasNext}'));
    assert.ok(footer.includes('‹ Prev') && footer.includes('Next ›') && footer.includes('aria-label="Previous page"') && footer.includes('aria-label="Next page"'));
    assert.ok(footer.includes("'bg-emerald-600 border-emerald-600 text-white shadow-sm'") && footer.includes("aria-current={current ? 'page' : undefined}"));
    assert.ok(footer.includes('<nav className="flex flex-wrap items-center gap-1"'), 'Wraps instead of overflowing on small screens');
    assert.ok(footer.includes('aria-hidden="true">…</span>') && footer.includes('getPageNumbers(pagination.totalPages, pagination.page)'));
  });
});
