import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createElement as h } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import {
  VIEW_ACCESS_ADMIN_ONLY_MESSAGE,
  canManageViewAccess,
  describeViewAccessChange,
  grantedCodes,
  isViewOnly,
  selectionChanged,
  viewAccessChoices,
  viewAccessErrorMessage,
  viewAccessSummary,
  withoutViewOnly,
} from './managerViewAccess.js';
import { filterEligibleStaff } from './staffFilter.js';
import { getEligibleUnassignedTasks } from './assignmentUtils.js';
import { getUserActions } from '../components/users/userListUtils.js';
import ViewOnlyBadge from '../components/common/ViewOnlyBadge.js';

// Admin-controlled, view-only department access for Managers. The server is the control (grants are Admin only; every write checks
// the Manager's own department only); these tests cover what the pages offer, and that they never offer a change for a view-only record.
const read = (...parts) => readFileSync(new URL(`../${parts.join('/')}`, import.meta.url), 'utf8').replace(/\r\n/g, '\n');

describe('who can grant', () => {
  it('an Admin, for a Manager account only', () => {
    assert.equal(canManageViewAccess('ADMIN', { role: 'MANAGER' }), true);
    for (const [viewer, target] of [['MANAGER', 'MANAGER'], ['STAFF', 'MANAGER'], ['ADMIN', 'STAFF'], ['ADMIN', 'ADMIN'], ['', 'MANAGER'], ['ADMIN', '']]) {
      assert.equal(canManageViewAccess(viewer, { role: target }), false, `${viewer} -> ${target}`);
    }
  });

  it('the Users menu offers it to an Admin on a Manager row (with a staff record) and to nobody else', () => {
    const managerRow = { id: 'u1', username: 'mia', role: 'MANAGER', active: true };
    const staffRecord = { id: 's1', active: true };
    assert.ok(getUserActions(managerRow, { staff: staffRecord, currentUsername: 'admin', isAdmin: true }).actions.includes('viewAccess'));
    assert.ok(!getUserActions(managerRow, { staff: staffRecord, currentUsername: 'admin', isAdmin: false }).actions.includes('viewAccess'), 'not for a non-Admin');
    assert.ok(!getUserActions({ ...managerRow, role: 'STAFF' }, { staff: staffRecord, currentUsername: 'admin', isAdmin: true }).actions.includes('viewAccess'), 'not for Staff rows');
    assert.ok(!getUserActions(managerRow, { staff: null, currentUsername: 'admin', isAdmin: true }).actions.includes('viewAccess'), 'not without a staff record');
  });
});

describe('the dialog choices', () => {
  const departments = [
    { code: 'HK', name: 'Housekeeping', active: true },
    { code: 'FD', name: 'Front Desk', active: true },
    { code: 'MT', name: 'Maintenance', active: false },
    { code: 'SEC', name: 'Security', active: false },
  ];
  const access = { primaryDepartmentCode: 'HK', departments: [{ departmentCode: 'SEC', departmentName: 'Security', active: false }] };

  it('own department first, always included and not selectable; inactive departments only when already granted', () => {
    const rows = viewAccessChoices(departments, access);
    assert.deepEqual(rows.map((r) => r.code), ['HK', 'FD', 'SEC']);
    assert.deepEqual(rows[0], { code: 'HK', name: 'Housekeeping', active: true, primary: true, granted: false, selectable: false });
    assert.equal(rows[1].selectable, true);
    assert.deepEqual(rows[2], { code: 'SEC', name: 'Security', active: false, primary: false, granted: true, selectable: true }, 'can still be removed');
  });

  it('a granted department missing from the list is still shown from the grant', () => {
    const rows = viewAccessChoices([], { primaryDepartmentCode: 'HK', departments: [{ departmentCode: 'X', departmentName: 'Gone', active: true }] });
    assert.deepEqual(rows.map((r) => [r.code, r.name, r.granted]), [['X', 'Gone', true]]);
  });

  it('save is offered only for a real change, in any order', () => {
    assert.equal(selectionChanged(['A', 'B'], ['B', 'A']), false);
    assert.equal(selectionChanged(['A'], ['A', 'B']), true);
    assert.equal(selectionChanged(['A', 'B'], ['A']), true);
    assert.equal(selectionChanged([], []), false);
    assert.deepEqual(grantedCodes(access), ['SEC']);
    assert.deepEqual(grantedCodes(null), []);
  });

  it('wording of the summary, the history and the errors', () => {
    assert.equal(viewAccessSummary({ departments: [] }), 'Own department only.');
    assert.equal(viewAccessSummary(access), 'Own department, plus view-only access to Security.');
    assert.equal(describeViewAccessChange({ action: 'GRANTED', departmentName: 'Front Desk' }), 'View access to Front Desk granted');
    assert.equal(describeViewAccessChange({ action: 'REVOKED', departmentName: 'Front Desk' }), 'View access to Front Desk removed');
    assert.match(describeViewAccessChange({ action: 'REVOKED', departmentName: 'Front Desk', changeSource: 'PRIMARY_DEPARTMENT_CHANGE' }), /became the Manager’s own department/);
    assert.equal(viewAccessErrorMessage({ status: 403, message: 'x' }), VIEW_ACCESS_ADMIN_ONLY_MESSAGE);
    assert.equal(viewAccessErrorMessage({ status: 400, message: 'Unknown department: Z. Nothing was changed.' }), 'Unknown department: Z. Nothing was changed.');
    assert.match(viewAccessErrorMessage({ status: 500 }), /Nothing was changed/);
  });
});

describe('view-only records', () => {
  it('only a real true from the server marks a record view-only', () => {
    assert.equal(isViewOnly({ viewOnly: true }), true);
    for (const record of [null, undefined, {}, { viewOnly: false }, { viewOnly: 'true' }, { viewOnly: 1 }]) assert.equal(isViewOnly(record), false);
  });

  it('the Dashboard leaves them out, so a Manager’s figures stay about their own department', () => {
    assert.deepEqual(withoutViewOnly([{ id: 1 }, { id: 2, viewOnly: true }, { id: 3, viewOnly: false }]).map((r) => r.id), [1, 3]);
    assert.equal(withoutViewOnly(null), null);
    const dashboard = read('pages', 'Dashboard.jsx');
    assert.ok(dashboard.includes("data: withoutViewOnly(await getWorkload())"));
    assert.ok(dashboard.includes("data: withoutViewOnly(await getTasks())"));
  });

  it('they are never offered for assignment: not as staff, not as tasks', () => {
    const staff = [
      { id: 's1', role: 'STAFF', department: 'FD', active: true, availability: 'AVAILABLE' },
      { id: 's2', role: 'STAFF', department: 'FD', active: true, availability: 'AVAILABLE', viewOnly: true },
    ];
    assert.deepEqual(filterEligibleStaff({ allStaff: staff, department: 'FD' }).map((s) => s.id), ['s1']);
    const tasks = [{ id: 't1', status: 'PENDING' }, { id: 't2', status: 'PENDING', viewOnly: true }];
    assert.deepEqual(getEligibleUnassignedTasks({ tasks, assignments: [] }).map((t) => t.id), ['t1']);
  });

  it('the badge says "View only", with an explanation', () => {
    const html = renderToStaticMarkup(h(ViewOnlyBadge));
    assert.match(html, /data-view-only/);
    assert.match(html, />View only</);
    assert.match(html, /title="You can view this department’s records but not change them/);
  });
});

describe('pages: a view-only record is shown with the badge and offers View Details only', () => {
  it('Tasks: edit, assign and delete are gated by canManageTasks AND not view-only; the handlers refuse as well', () => {
    const table = read('components', 'tasks', 'TasksTable.jsx');
    assert.equal(table.split('{canManageTasks && !isViewOnly(shownMenu.task) && (').length - 1, 3, 'Edit, Assign/Reassign and Delete');
    assert.ok(!/\{canManageTasks && \(/.test(table), 'no management entry without the view-only check');
    assert.ok(table.includes('{isViewOnly(task) && <ViewOnlyBadge'));
    const page = read('pages', 'TasksPage.jsx');
    for (const handler of ['handleOpenEditModal = (task) => {\n    if (isViewOnly(task)) return;', 'handleOpenDeleteModal = (task) => {\n    if (isViewOnly(task)) return;', 'handleOpenAssignModal = (task) => {\n    if (isViewOnly(task)) return;']) {
      assert.ok(page.includes(handler), handler);
    }
  });

  it('Staff: every change (edit, deactivate, activate, remove) is inside the view-only check; no email line; handlers refuse', () => {
    const table = read('components', 'staff', 'StaffTable.jsx');
    const gate = table.indexOf('{!isViewOnly(shownMenu.staff) && (<>');
    assert.ok(gate > 0);
    for (const control of ['onEdit && onEdit(staff)', 'onDeactivate && onDeactivate(staff)', 'onActivate && onActivate(staff)', 'onRemove && onRemove(staff)']) {
      assert.ok(table.indexOf(control) > gate, `${control} is inside the check`);
    }
    assert.ok(table.indexOf('onView && onView(staff)') < gate, 'View Details stays for everyone');
    assert.match(table, /\{isViewOnly\(staff\) \? \(\s*<ViewOnlyBadge/);
    const page = read('pages', 'StaffPage.jsx');
    for (const handler of ['handleOpenEditModal', 'handleOpenDeactivateModal', 'handleOpenRemoveModal', 'handleActivate']) {
      const body = page.slice(page.indexOf(`const ${handler} = `), page.indexOf(`const ${handler} = `) + 260);
      assert.ok(body.includes('if (isViewOnly(staff)) return;'), handler);
    }
  });

  it('Staff details: no username and no contact section for a view-only member, with a note instead', () => {
    const details = read('components', 'staff', 'StaffDetailsModal.jsx');
    assert.ok(details.includes('const viewOnly = isViewOnly(staff);'));
    assert.match(details, /\{viewOnly \? \(\s*<div className="mt-1\.5 flex justify-center"><ViewOnlyBadge \/><\/div>\s*\) : \(\s*<p className="mt-0\.5 text-sm text-gray-500 break-all">@\{staff\.username/);
    assert.match(details, /\{viewOnly \? \(\s*<p data-testid="view-only-contact-note"[\s\S]*?Contact details are not shown for departments you can only view\.[\s\S]*?\) : \(\s*<section aria-labelledby="staff-details-contact">/);
  });

  it('Assignments: Reassign (both variants) only outside the view-only check; navigation never opens a view-only task', () => {
    const page = read('pages', 'AssignmentsPage.jsx');
    const gate = page.indexOf('{!isViewOnly(shownMenu.assignment) && (<>');
    assert.ok(gate > 0 && page.indexOf('onClick={handleMenuReassign}') > gate && page.indexOf('data-testid="reassign-finished-disabled"') > gate);
    assert.ok(page.includes('const handleOpenReassignModal = (assignment) => {\n    // An assignment of a department this Manager may only view (Administrator grant) is never reassigned; the server refuses it too\n    if (isViewOnly(assignment)) return;'));
    assert.ok(page.includes('if (!isViewOnly(location.state.task)) {') && page.includes('if (found && !isViewOnly(found)) {'));
    assert.ok(page.includes('{isViewOnly(assignment) && <ViewOnlyBadge />}'));
  });

  it('Workload: the badge on both layouts; the fallback details record carries the flag', () => {
    const page = read('pages', 'WorkloadPage.jsx');
    assert.equal(page.split('{isViewOnly(item) && <ViewOnlyBadge className="mt-1" />}').length - 1, 2);
    assert.ok(page.includes('viewOnly: isViewOnly(item),'));
  });
});

describe('the Admin dialog', () => {
  const modal = read('components', 'users', 'ManagerViewAccessModal.jsx');

  it('renders for an Admin on a Manager only, and uses the Admin-only endpoints', () => {
    assert.ok(modal.includes('const permitted = isOpen && canManageViewAccess(getUserRole(), user);'));
    assert.ok(modal.includes('if (!permitted) return null;'));
    const api = read('services', 'api.js');
    assert.ok(api.includes('export const getManagerViewAccess = (userId) => apiFetch(`/api/users/${userId}/view-departments`);'));
    assert.match(api, /export const updateManagerViewAccess = \(userId, departmentCodes\) => apiFetch\(`\/api\/users\/\$\{userId\}\/view-departments`, \{\s*method: 'PUT',\s*body: JSON\.stringify\(\{ departments: departmentCodes \}\),/);
  });

  it('the own department is checked and disabled; saving sends the whole set once, only when changed', () => {
    assert.ok(modal.includes('const checked = choice.primary || selected.includes(choice.code);'));
    assert.ok(modal.includes('disabled={!choice.selectable}'));
    assert.ok(modal.includes('if (inFlight.current || !changed) return;'));
    assert.ok(modal.includes('const updated = await updateManagerViewAccess(userId, selected);'));
    assert.ok(modal.includes('disabled={submitting || !changed}'));
  });

  it('the Users page opens it for Admins only', () => {
    const page = read('pages', 'UsersPage.jsx');
    assert.ok(page.includes("if (action === 'viewAccess') {\n      // Admin only (the server refuses anyone else too); kept on the Manager's account\n      if (isAdmin) setGrantingViewAccess(user);\n      return;\n    }"));
    assert.ok(page.includes('isOpen={isAdmin && Boolean(grantingViewAccess)}'));
  });
});
