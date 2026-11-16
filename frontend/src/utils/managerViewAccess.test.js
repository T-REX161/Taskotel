import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createElement as h } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import {
  VIEW_ACCESS_ADMIN_ONLY_MESSAGE,
  HISTORY_FILTERS,
  HISTORY_PAGE_SIZE,
  HISTORY_PREVIEW_SIZE,
  appendHistoryPage,
  canManageViewAccess,
  choiceState,
  hasMoreHistory,
  describeViewAccessChange,
  revokeSelection,
  viewAccessHistoryRows,
  grantedCodes,
  isViewOnly,
  showsAdditionalAccess,
  additionalAccessRows,
  selectionChanged,
  viewAccessChoices,
  viewAccessErrorMessage,
  viewAccessSummary,
  withoutViewOnly,
  profileDepartments,
  showsProfileDepartments,
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

  it('the compact badge keeps the label and the sky colours, only smaller; an unknown size falls back to the default', () => {
    const compact = renderToStaticMarkup(h(ViewOnlyBadge, { size: 'compact' }));
    assert.match(compact, />View only</);
    assert.match(compact, /bg-sky-50/);
    assert.ok(compact.includes('text-[9px]'));
    assert.ok(compact.includes('class="w-2.5 h-2.5"'));
    assert.equal(renderToStaticMarkup(h(ViewOnlyBadge, { size: 'huge' })), renderToStaticMarkup(h(ViewOnlyBadge)));
    assert.ok(renderToStaticMarkup(h(ViewOnlyBadge)).includes('text-[10px]'), 'tables keep the default size');
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

describe('a Manager’s details on the Users page: Additional Department Access', () => {
  const manager = { userId: 'u1', role: 'MANAGER', department: 'HK' };

  it('shown only to an Admin looking at a Manager with an account; Managers and Staff never fetch or see it', () => {
    assert.equal(showsAdditionalAccess('ADMIN', manager), true);
    assert.equal(showsAdditionalAccess('MANAGER', manager), false);
    assert.equal(showsAdditionalAccess('STAFF', manager), false);
    assert.equal(showsAdditionalAccess('ADMIN', { ...manager, role: 'STAFF' }), false);
    assert.equal(showsAdditionalAccess('ADMIN', { ...manager, role: 'ADMIN' }), false);
    assert.equal(showsAdditionalAccess('ADMIN', { ...manager, userId: null }), false);
  });

  it('every granted department by name, never the primary one, never twice; inactive ones are marked', () => {
    const access = {
      primaryDepartmentCode: 'HK',
      departments: [
        { departmentCode: 'SEC', departmentName: 'Security', active: false },
        { departmentCode: 'FD', departmentName: 'Front Desk', active: true },
        { departmentCode: 'HK', departmentName: 'Housekeeping', active: true },
        { departmentCode: 'FD', departmentName: 'Front Desk', active: true },
      ],
    };
    assert.deepEqual(additionalAccessRows(access, 'HK'), [
      { code: 'FD', name: 'Front Desk', active: true },
      { code: 'SEC', name: 'Security', active: false },
    ]);
    assert.deepEqual(additionalAccessRows({ primaryDepartmentCode: 'HK', departments: [{ departmentCode: 'MT', departmentName: 'Maintenance' }] }, 'MT'), [], 'the record’s own department is dropped too');
    assert.deepEqual(additionalAccessRows({ departments: [] }, 'HK'), []);
    assert.deepEqual(additionalAccessRows(null), []);
  });

  it('the row reads the existing Admin-only endpoint each time the details open, labels each department View only, and shows no history', () => {
    const row = read('components', 'users', 'ManagerAdditionalAccess.jsx');
    assert.ok(row.includes('const allowed = showsAdditionalAccess(getUserRole(), staff);'));
    assert.ok(row.includes('if (!allowed) return null;'));
    assert.ok(row.includes('getManagerViewAccess(userId)'), 'the existing view-access read, nothing new');
    assert.ok(!/getManagerViewAccessHistory|updateManagerViewAccess|history/i.test(row.replace(/\/\*[\s\S]*?\*\//g, '')), 'read-only, no audit history');
    assert.ok(row.includes('<ViewOnlyBadge size="compact" />'), 'each department keeps its View only label, in the compact size');
    assert.ok(row.includes('divide-y divide-gray-100') && !row.includes('bg-gray-50'), 'a plain list with hairline separators, no card per department');
    assert.ok(row.includes('No additional department access'));
    assert.ok(row.includes('<DetailRow icon={EyeIcon} label="Additional Department Access" note={list}>'), 'the modal’s own row design; the list sits in its note area');
    assert.ok(row.includes('let cancelled = false;'), 'a late answer for someone else is ignored');
  });

  it('the shared details modal still fetches nothing; it only places the optional row below Department', () => {
    const details = read('components', 'staff', 'StaffDetailsModal.jsx');
    assert.doesNotMatch(details, /services\/api|fetch\(/);
    assert.match(details, /\{formatDepartment\(staff\.department\)\}\s*<\/DetailRow>\s*\{departmentAccess\}/);
    assert.ok(details.includes("function StaffDetailsModal({ staff, onClose, closing = false, departmentAccess = null })"));
  });

  it('only the Users page passes it, for an Admin; the Staff and Workload pages are unchanged', () => {
    const users = read('pages', 'UsersPage.jsx');
    assert.match(users, /departmentAccess=\{\s*\/\/[^\n]*\n\s*isAdmin && viewingStaff && showsAdditionalAccess\(getUserRole\(\), viewingStaff\)\s*\? <ManagerAdditionalAccess key=\{viewingStaff\.userId\} staff=\{viewingStaff\} \/>\s*: null/);
    for (const page of ['StaffPage.jsx', 'WorkloadPage.jsx']) assert.ok(!read('pages', page).includes('departmentAccess'), page);
  });
});

describe('the Admin dialog: revoking one department', () => {
  const access = {
    primaryDepartmentCode: 'HK',
    departments: [
      { departmentCode: 'FD', departmentName: 'Front Desk' },
      { departmentCode: 'SEC', departmentName: 'Security' },
    ],
  };

  it('sends the saved grants minus that one department, keeping the others', () => {
    assert.deepEqual(revokeSelection(access, 'FD'), ['SEC']);
    assert.deepEqual(revokeSelection(access, 'SEC'), ['FD']);
    assert.deepEqual(revokeSelection({ ...access, departments: [access.departments[0]] }, 'FD'), []);
  });

  it('never revokes the primary department, a department that is not granted, or nothing', () => {
    assert.equal(revokeSelection(access, 'HK'), null);
    assert.equal(revokeSelection({ primaryDepartmentCode: 'HK', departments: [{ departmentCode: 'HK' }] }, 'HK'), null, 'even if a stale answer listed it');
    assert.equal(revokeSelection(access, 'MT'), null);
    assert.equal(revokeSelection(access, ''), null);
    assert.equal(revokeSelection(null, 'FD'), null);
  });

  it('row states: primary locked, saved grant, pending grant, pending removal', () => {
    const saved = ['FD', 'SEC'];
    assert.equal(choiceState({ code: 'HK', primary: true }, saved, saved), 'primary');
    assert.equal(choiceState({ code: 'FD' }, saved, ['FD', 'SEC']), 'granted');
    assert.equal(choiceState({ code: 'SEC' }, saved, ['FD']), 'removing');
    assert.equal(choiceState({ code: 'MT' }, saved, ['FD', 'SEC', 'MT']), 'adding');
    assert.equal(choiceState({ code: 'MT' }, saved, saved), 'none');
  });

  it('asks for confirmation, uses the existing endpoint, and offers Revoke only on saved grants (never the primary row)', () => {
    const modal = read('components', 'users', 'ManagerViewAccessModal.jsx');
    const grantedBranch = modal.indexOf("{state === 'granted' && (");
    const revokeButton = modal.indexOf('aria-label={`Revoke view access to ${choice.name}`}');
    assert.ok(grantedBranch > modal.indexOf("{state === 'primary' && ("));
    assert.ok(revokeButton > grantedBranch && revokeButton < modal.indexOf("{state === 'adding' &&"), 'the button sits inside the saved-grant branch only');
    assert.ok(modal.includes('setRevoking({ code: choice.code, name: choice.name });'), 'the button only opens the confirmation');
    assert.ok(modal.includes('role="alertdialog"') && modal.includes("'Revoke access'"), 'a confirmation step before anything is sent');
    assert.ok(modal.includes('const remaining = revokeSelection(access, target?.code);'));
    assert.ok(modal.includes('if (inFlight.current || !remaining) {'));
    assert.ok(modal.includes('const updated = await updateManagerViewAccess(userId, remaining);'), 'the same Admin-only PUT; no new endpoint');
    const api = read('services', 'api.js');
    assert.ok(!/view-departments[^\n]*`, \{\s*method: 'DELETE'/.test(api), 'nothing deletes a grant record or the history');
  });
});

describe('the Admin dialog: layout and history', () => {
  const modal = read('components', 'users', 'ManagerViewAccessModal.jsx');

  it('fixed header and footer around a scrolling body; the history scrolls in its own, shorter area', () => {
    assert.ok(modal.includes('relative flex flex-col bg-white rounded-2xl max-w-xl w-full max-h-[88vh]'));
    assert.ok(modal.includes('<header className="shrink-0'));
    assert.ok(modal.includes('<footer className="shrink-0'));
    assert.ok(modal.includes('flex-1 min-h-0 overflow-y-auto'));
    assert.ok(modal.includes('mt-2 max-h-48 sm:max-h-56 overflow-y-auto'), 'the history has its own scroll area');
    assert.ok(modal.includes('mt-3 max-h-56 sm:max-h-72 overflow-y-auto'), 'so does a long department list, keeping the history in view');
    assert.ok(modal.indexOf('<footer') > modal.indexOf('data-testid="view-access-history"'), 'the buttons come after the history, outside its scroll area');
    assert.ok(modal.includes('aria-label="Close"'), 'a close button in the header');
    assert.ok(modal.includes('Cancel') && modal.includes("'Save changes'"));
  });

  it('the history is read-only: no button or delete action inside it', () => {
    const start = modal.indexOf('data-testid="view-access-history"');
    const end = modal.indexOf('</ol>', start);
    assert.ok(start > 0 && end > start);
    assert.ok(!/<button|onClick|delete|remove/i.test(modal.slice(start, end)));
  });

  it('history rows newest first, with action, department, actor and time; automatic removals are explained', () => {
    const rows = viewAccessHistoryRows([
      { id: 1, action: 'GRANTED', departmentName: 'Front Desk', changedByUsername: 'admin', changedAt: '2026-11-10T09:00:00Z', changeSource: 'ADMIN_VIEW_ACCESS' },
      { id: 3, action: 'REVOKED', departmentCode: 'SEC', changedByUsername: 'admin2', changedAt: '2026-11-12T09:00:00Z', changeSource: 'PRIMARY_DEPARTMENT_CHANGE' },
      { id: 2, action: 'REVOKED', departmentName: 'Front Desk', changedByUsername: 'admin', changedAt: '2026-11-11T09:00:00Z', changeSource: 'ADMIN_VIEW_ACCESS' },
      null,
    ]);
    assert.deepEqual(rows.map((r) => r.id), [3, 2, 1]);
    assert.deepEqual(rows[0], { id: 3, action: 'Access revoked', tone: 'revoked', department: 'SEC', actor: 'admin2', automatic: true, changedAt: '2026-11-12T09:00:00Z' });
    assert.equal(rows[2].action, 'Access granted');
    assert.equal(rows[1].automatic, false);
    assert.deepEqual(viewAccessHistoryRows(null), []);
  });

  it('copes with hundreds of entries', () => {
    const many = Array.from({ length: 500 }, (_, i) => ({ id: i, action: i % 2 ? 'REVOKED' : 'GRANTED', departmentName: 'D', changedByUsername: 'a', changedAt: new Date(Date.UTC(2026, 0, 1) + i * 60000).toISOString() }));
    const rows = viewAccessHistoryRows(many);
    assert.equal(rows.length, 500);
    assert.equal(rows[0].id, 499);
    assert.equal(rows[499].id, 0);
  });
});

describe('access history: preview, full history, filters and Load more', () => {
  const modal = read('components', 'users', 'ManagerViewAccessModal.jsx');
  const full = read('components', 'users', 'ManagerViewAccessHistoryModal.jsx');
  const row = read('components', 'users', 'ViewAccessHistoryRow.jsx');
  const api = read('services', 'api.js');

  it('the dialog shows the 10 newest; "View all history" only when there are more than 10', () => {
    assert.equal(HISTORY_PREVIEW_SIZE, 10);
    for (const [total, more] of [[0, false], [5, false], [10, false], [11, true], [100, true], [1000, true]]) {
      assert.equal(hasMoreHistory({ totalElements: total }), more, `${total} records`);
    }
    assert.equal(hasMoreHistory(null), false);
    assert.ok(modal.includes('setHistory(await getManagerViewAccessHistoryPage(userId, { page: 0, size: HISTORY_PREVIEW_SIZE }));'), 'only the newest page is requested');
    assert.ok(!modal.includes('getManagerViewAccessHistory(userId)'), 'the dialog no longer loads the whole history');
    assert.ok(modal.includes('{hasMoreHistory(history) && (') && modal.includes('View all history ({historyTotal})'));
    assert.ok(modal.includes('<ManagerViewAccessHistoryModal isOpen={showAllHistory} user={user} onClose={() => setShowAllHistory(false)} />'));
    assert.ok(modal.includes("if (event.key !== 'Escape' || inFlight.current || showAllHistory) return;"), 'Escape closes the full history first');
  });

  it('the paged endpoint: Admin-only URL, page, size, action and optional department', () => {
    assert.ok(api.includes('return apiFetch(`/api/users/${userId}/view-departments/history/page?${query}`);'));
    assert.ok(api.includes("const query = new URLSearchParams({ page: String(page), size: String(size), action });"));
    assert.ok(api.includes("if (department) query.set('department', department);"));
  });

  it('full history: Admin only, All / Granted / Revoked and department filters, Load more by pages of 20', () => {
    assert.ok(full.includes('const permitted = isOpen && canManageViewAccess(getUserRole(), user);') && full.includes('if (!permitted) return null;'));
    assert.deepEqual(HISTORY_FILTERS.map((f) => f.key), ['ALL', 'GRANTED', 'REVOKED']);
    assert.equal(HISTORY_PAGE_SIZE, 20);
    assert.ok(full.includes('getManagerViewAccessHistoryPage(userId, { page: 0, size: HISTORY_PAGE_SIZE, action: filter, department })'));
    assert.ok(full.includes('getManagerViewAccessHistoryPage(userId, { page: page + 1, size: HISTORY_PAGE_SIZE, action: filter, department })'));
    assert.ok(full.includes('if (mine !== request.current) return;'), 'a late answer for an old filter is ignored');
    assert.ok(full.includes('h-[min(26rem,52vh)] overflow-y-auto'), 'a fixed-height list that scrolls on its own');
    assert.ok(full.includes('aria-label="Close"') && full.includes('<header className="shrink-0') && full.includes('<footer className="shrink-0'));
  });

  it('read-only everywhere: no delete or clear action, and the API module has no call that changes the history', () => {
    for (const [name, text] of [['full', full], ['row', row]]) {
      assert.ok(!/delete|clear history|remove entry/i.test(text.replace(/\/\*\*[\s\S]*?\*\//g, '').replace(/\/\/.*$/gm, '')), name);
    }
    assert.ok(!row.includes('<button') && !row.includes('onClick'), 'a history row has no actions');
    assert.ok(!/view-departments\/history[^\n]*method:/.test(api), 'no request with a method other than GET goes to the history');
  });

  it('rows read "Access granted" / "Access revoked" with department, Admin and time', () => {
    const [granted, revoked] = viewAccessHistoryRows([
      { id: 'b', action: 'GRANTED', departmentName: 'Front Desk', changedByUsername: 'admin', changedAt: '2026-11-12T09:00:00Z' },
      { id: 'a', action: 'REVOKED', departmentName: 'Security', changedByUsername: 'admin', changedAt: '2026-11-11T09:00:00Z' },
    ]);
    assert.equal(granted.action, 'Access granted');
    assert.equal(revoked.action, 'Access revoked');
    assert.ok(row.includes('{row.action}') && row.includes('{row.department}') && row.includes('by {row.actor}') && row.includes('{formatWhen(row.changedAt)}'));
    assert.ok(row.includes("granted: { dot: 'bg-emerald-500', text: 'text-emerald-700' }") && row.includes("revoked: { dot: 'bg-rose-500', text: 'text-rose-700' }"));
  });

  it('Load more appends the next page without listing a record twice', () => {
    const first = [{ id: 1 }, { id: 2 }];
    assert.deepEqual(appendHistoryPage(first, { items: [{ id: 2 }, { id: 3 }] }).map((c) => c.id), [1, 2, 3]);
    assert.deepEqual(appendHistoryPage([], { items: [{ id: 9 }] }).map((c) => c.id), [9]);
    assert.deepEqual(appendHistoryPage(first, null), first);
    assert.deepEqual(appendHistoryPage(null, { items: [null, { id: 4 }] }).map((c) => c.id), [4]);
  });
});

describe("the Manager's own profile header: Departments", () => {
  const profile = { username: 'mgr', role: 'MANAGER', departmentCode: 'FRONT_DESK', departmentName: 'Front Desk' };
  const own = { code: 'FRONT_DESK', name: 'Front Desk', active: true };
  const basic = (code, name) => ({ code, name, active: true }); // a department the Manager cannot see (identity only, not view-only)
  const granted = (code, name, active = true) => ({ code, name, active, viewOnly: true });

  it('is shown for a Manager only, never for an Admin or Staff', () => {
    assert.equal(showsProfileDepartments('MANAGER'), true);
    assert.equal(showsProfileDepartments('manager'), true);
    for (const role of ['ADMIN', 'STAFF', '', null, undefined, 'OTHER']) assert.equal(showsProfileDepartments(role), false, String(role));
  });

  it('a Manager with only a primary department: Primary, no additional departments', () => {
    const shown = profileDepartments(profile, [own, basic('SECURITY', 'Security'), basic('MAINTENANCE', 'Maintenance')]);
    assert.deepEqual(shown.primary, { code: 'FRONT_DESK', name: 'Front Desk' });
    assert.deepEqual(shown.additional, []);
  });

  it('a Manager with several grants: the view-only departments by name, never the primary one, never twice', () => {
    const list = [own, granted('SECURITY', 'Security'), basic('SPA', 'Spa'), granted('MAINTENANCE', 'Maintenance', false),
      granted('SECURITY', 'Security'), { ...own, viewOnly: true }];
    const shown = profileDepartments(profile, list);
    assert.equal(shown.primary.name, 'Front Desk');
    assert.deepEqual(shown.additional, [
      { code: 'MAINTENANCE', name: 'Maintenance', active: false },
      { code: 'SECURITY', name: 'Security', active: true },
    ]);
  });

  it('after a revocation the server no longer marks the department view-only, so it is no longer listed', () => {
    const before = profileDepartments(profile, [own, granted('SECURITY', 'Security'), granted('SPA', 'Spa')]);
    const after = profileDepartments(profile, [own, basic('SECURITY', 'Security'), granted('SPA', 'Spa')]);
    assert.deepEqual(before.additional.map((d) => d.code), ['SECURITY', 'SPA']);
    assert.deepEqual(after.additional.map((d) => d.code), ['SPA']);
  });

  it('only what the server said: no primary without one on the profile, nothing from other data, odd answers are safe', () => {
    assert.equal(profileDepartments({ departmentCode: null }, [own]).primary, null);
    assert.equal(profileDepartments(null, null).primary, null);
    assert.deepEqual(profileDepartments(profile, 'nope').additional, []);
    assert.deepEqual(profileDepartments(profile, [{ code: 'X', viewOnly: 'true' }, { viewOnly: true }, null]).additional, []);
    assert.equal(profileDepartments({ departmentCode: 'FRONT_DESK' }, []).primary.name, 'FRONT_DESK');
  });

  const component = read('components', 'profile', 'ProfileDepartments.jsx');
  const page = read('pages', 'ProfilePage.jsx');

  it("reads the Manager's own scoped department list, never the Admin-only grant endpoints", () => {
    assert.ok(component.includes('getDepartments()'));
    for (const adminOnly of ['getManagerViewAccess', 'view-departments', 'updateManagerViewAccess', 'getManagerViewAccessHistory']) {
      assert.ok(!component.includes(adminOnly), adminOnly);
    }
    assert.ok(component.includes('const shown = showsProfileDepartments(role);') && component.includes('if (!shown) return undefined;'));
    assert.ok(component.includes('if (!shown) return null;'), 'renders nothing (and fetches nothing) for an Admin or Staff');
  });

  it("shows one compact text line under the badges: Primary and View only labels, no chips, badges or cards", () => {
    assert.ok(component.includes("<p data-testid=\"profile-departments\"") && component.includes("text-xs") && component.includes(">Departments:</span>"));
    assert.ok(component.includes("data-kind=\"primary\"") && component.includes("&nbsp;(Primary)") && component.includes("font-medium text-gray-700"));
    assert.ok(component.includes("data-kind=\"view-only\"") && component.includes("'View only'") && component.includes(">·</span>"));
    for (const chip of ["ViewOnlyBadge", "rounded-md", "border", "bg-gray-50", "<ul", "<li"]) assert.ok(!component.includes(chip), chip);
    assert.ok(!component.includes("whitespace-nowrap"), "long names may wrap on a narrow screen");
    const header = page.slice(page.indexOf('aria-label="Profile summary"'), page.indexOf('title="Personal Information"'));
    const at = (s) => header.indexOf(s);
    assert.ok(at('{accessScope}') > 0 && at('<ProfileDepartments role={role} profile={profile}') > at('{accessScope}'));
  });
});
