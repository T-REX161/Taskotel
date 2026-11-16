import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import {
  VIEW_ACCESS_ADMIN_ONLY_MESSAGE,
  ADDITIONAL_DEPARTMENTS_LABEL,
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
  showsAdditionalAccess,
  additionalAccessRows,
  selectionChanged,
  viewAccessChoices,
  viewAccessErrorMessage,
  viewAccessSummary,
  profileDepartments,
  showsProfileDepartments,
} from './managerViewAccess.js';
import { filterEligibleStaff } from './staffFilter.js';
import { getEligibleUnassignedTasks } from './assignmentUtils.js';
import { getUserActions } from '../components/users/userListUtils.js';

// Admin-controlled additional departments for Managers (full management). The server is the control (grants are Admin only; every
// operation checks the Manager's authorized departments); these tests cover what the pages offer and how answers are worded.
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
    assert.equal(viewAccessSummary(access), 'Own department, plus full management of Security.');
    assert.equal(describeViewAccessChange({ action: 'GRANTED', departmentName: 'Front Desk' }), 'Access to Front Desk granted');
    assert.equal(describeViewAccessChange({ action: 'REVOKED', departmentName: 'Front Desk' }), 'Access to Front Desk removed');
    assert.equal(describeViewAccessChange({ action: 'UPGRADED', departmentName: 'Front Desk' }), 'Access to Front Desk upgraded from view only to full management');
    assert.match(describeViewAccessChange({ action: 'REVOKED', departmentName: 'Front Desk', changeSource: 'PRIMARY_DEPARTMENT_CHANGE' }), /became the Manager’s own department/);
    assert.equal(viewAccessErrorMessage({ status: 403, message: 'x' }), VIEW_ACCESS_ADMIN_ONLY_MESSAGE);
    assert.equal(viewAccessErrorMessage({ status: 400, message: 'Unknown department: Z. Nothing was changed.' }), 'Unknown department: Z. Nothing was changed.');
    assert.match(viewAccessErrorMessage({ status: 500 }), /Nothing was changed/);
  });
});

describe('no view-only gating remains: an additional department is managed like the primary one', () => {
  const sources = {
    tasksTable: read('components', 'tasks', 'TasksTable.jsx'),
    tasksPage: read('pages', 'TasksPage.jsx'),
    staffTable: read('components', 'staff', 'StaffTable.jsx'),
    staffPage: read('pages', 'StaffPage.jsx'),
    staffDetails: read('components', 'staff', 'StaffDetailsModal.jsx'),
    assignments: read('pages', 'AssignmentsPage.jsx'),
    workload: read('pages', 'WorkloadPage.jsx'),
    dashboard: read('pages', 'Dashboard.jsx'),
    assignmentUtils: read('utils', 'assignmentUtils.js'),
    staffFilter: read('utils', 'staffFilter.js'),
  };

  it('no page, table, modal or helper reads a view-only flag, offers a badge or imports the removed helpers', () => {
    for (const [name, source] of Object.entries(sources)) {
      assert.ok(!/isViewOnly|withoutViewOnly|ViewOnlyBadge|viewOnly|view-only-contact-note/.test(source), name);
    }
    assert.throws(() => read('components', 'common', 'ViewOnlyBadge.js'), 'the badge component is gone');
  });

  it('Tasks: edit, assign and delete follow canManageTasks alone, for every task the Manager can see', () => {
    assert.equal(sources.tasksTable.split('{canManageTasks && (').length - 1, 3, 'Edit, Assign/Reassign and Delete');
    for (const [handler, first] of [['handleOpenEditModal', 'setEditModalTask(task);'], ['handleOpenDeleteModal', 'setDeleteModalTask(task);'], ['handleOpenAssignModal', 'setAssignModalTask(task);']]) {
      const at = sources.tasksPage.indexOf('const ' + handler + ' = (task) => {');
      assert.ok(at > 0 && sources.tasksPage.slice(at, at + 120).includes(first), handler);
    }
  });

  it('Staff: every change is offered after View Details, with no gate; the email line is always shown', () => {
    const table = sources.staffTable;
    for (const control of ['onEdit && onEdit(staff)', 'onDeactivate && onDeactivate(staff)', 'onActivate && onActivate(staff)', 'onRemove && onRemove(staff)']) {
      assert.ok(table.indexOf(control) > table.indexOf('onView && onView(staff)'), control);
    }
    assert.ok(table.includes("{staff.email || '-'}"));
    assert.ok(sources.staffDetails.includes("@{staff.username || 'staff'}") && sources.staffDetails.includes('staff-details-contact'));
  });

  it('Assignments: Reassign and navigation from a task work for every task the Manager can see', () => {
    assert.ok(sources.assignments.includes('onClick={handleMenuReassign}'));
    assert.ok(sources.assignments.includes('if (found) {'));
  });

  it('assignment pickers do not look at a flag: a record is eligible on its own merits (the server decides access)', () => {
    const staff = [
      { id: 's1', role: 'STAFF', department: 'FD', active: true, availability: 'AVAILABLE' },
      { id: 's2', role: 'STAFF', department: 'FD', active: true, availability: 'AVAILABLE', viewOnly: false },
    ];
    assert.deepEqual(filterEligibleStaff({ allStaff: staff, department: 'FD' }).map((s) => s.id), ['s1', 's2']);
    const tasks = [{ id: 't1', status: 'PENDING' }, { id: 't2', status: 'PENDING', viewOnly: false }];
    assert.deepEqual(getEligibleUnassignedTasks({ tasks, assignments: [] }).map((t) => t.id), ['t1', 't2']);
  });

  it('the Dashboard uses the rows exactly as the server sends them (they are already the authorized departments)', () => {
    assert.ok(sources.dashboard.includes('data: await getWorkload()') && sources.dashboard.includes('data: await getTasks()'));
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

  it('the row reads the existing Admin-only endpoint each time the details open, labels each department Full management, and shows no history', () => {
    const row = read('components', 'users', 'ManagerAdditionalAccess.jsx');
    assert.ok(row.includes('const allowed = showsAdditionalAccess(getUserRole(), staff);'));
    assert.ok(row.includes('if (!allowed) return null;'));
    assert.ok(row.includes('getManagerViewAccess(userId)'), 'the existing view-access read, nothing new');
    assert.ok(!/getManagerViewAccessHistory|updateManagerViewAccess|history/i.test(row.replace(/\/\*[\s\S]*?\*\//g, '')), 'read-only, no audit history');
    assert.ok(row.includes('>Full management</span>') && !row.includes('ViewOnlyBadge') && !/view only/i.test(row.replace(/\/\*[\s\S]*?\*\//g, '')), 'each department says Full management, never View only');
    assert.ok(row.includes('aria-label="Additional departments this Manager manages"'));
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
    const revokeButton = modal.indexOf('aria-label={`Revoke access to ${choice.name}`}');
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
  const own = { code: 'FRONT_DESK', name: 'Front Desk', active: true, managed: true };
  const basic = (code, name) => ({ code, name, active: true, managed: false }); // a department the Manager does not manage (identity only)
  const granted = (code, name, active = true) => ({ code, name, active, managed: true });

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

  it('a Manager with several grants: the managed additional departments by name, never the primary one, never twice', () => {
    const list = [own, granted('SECURITY', 'Security'), basic('SPA', 'Spa'), granted('MAINTENANCE', 'Maintenance', false),
      granted('SECURITY', 'Security'), { ...own, managed: true }];
    const shown = profileDepartments(profile, list);
    assert.equal(shown.primary.name, 'Front Desk');
    assert.deepEqual(shown.additional, [
      { code: 'MAINTENANCE', name: 'Maintenance', active: false },
      { code: 'SECURITY', name: 'Security', active: true },
    ]);
  });

  it('after a revocation the server no longer marks the department managed, so it is no longer listed', () => {
    const before = profileDepartments(profile, [own, granted('SECURITY', 'Security'), granted('SPA', 'Spa')]);
    const after = profileDepartments(profile, [own, basic('SECURITY', 'Security'), granted('SPA', 'Spa')]);
    assert.deepEqual(before.additional.map((d) => d.code), ['SECURITY', 'SPA']);
    assert.deepEqual(after.additional.map((d) => d.code), ['SPA']);
  });

  it('only what the server said: no primary without one on the profile, nothing from other data, odd answers are safe', () => {
    assert.equal(profileDepartments({ departmentCode: null }, [own]).primary, null);
    assert.equal(profileDepartments(null, null).primary, null);
    assert.deepEqual(profileDepartments(profile, 'nope').additional, []);
    assert.deepEqual(profileDepartments(profile, [{ code: 'X', managed: 'true' }, { managed: true }, { code: 'Y', viewOnly: true }, null]).additional, [],
      'only a real true for managed counts, and a code is needed; the old viewOnly flag means nothing');
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

  it("shows one compact text line under the badges: Primary and Additional labels, no chips, badges or cards", () => {
    assert.ok(component.includes("<p data-testid=\"profile-departments\"") && component.includes("text-xs") && component.includes(">Departments:</span>"));
    assert.ok(component.includes("data-kind=\"primary\"") && component.includes("&nbsp;(Primary)") && component.includes("font-medium text-gray-700"));
    assert.ok(component.includes("data-kind=\"additional\"") && component.includes("'Additional'") && component.includes(">·</span>"));
    assert.ok(!/view[- ]only/i.test(component.replace(/\/\*[\s\S]*?\*\//g, '')), 'no View only wording remains');
    for (const chip of ["ViewOnlyBadge", "rounded-md", "border", "bg-gray-50", "<ul", "<li"]) assert.ok(!component.includes(chip), chip);
    assert.ok(!component.includes("whitespace-nowrap"), "long names may wrap on a narrow screen");
    const header = page.slice(page.indexOf('aria-label="Profile summary"'), page.indexOf('title="Personal Information"'));
    const at = (s) => header.indexOf(s);
    assert.ok(at('{accessScope}') > 0 && at('<ProfileDepartments role={role} profile={profile}') > at('{accessScope}'));
  });
});

describe('the Admin dialog after the access-model change: additional departments are fully managed', () => {
  const modal = read('components', 'users', 'ManagerViewAccessModal.jsx');
  const rowComponent = read('components', 'users', 'ViewAccessHistoryRow.jsx');
  const table = read('components', 'users', 'UsersTable.jsx');

  it('says Full management where it used to say View only, and explains what a grant gives and what stays Admin-only', () => {
    assert.equal(ADDITIONAL_DEPARTMENTS_LABEL, 'Additional departments (full management)');
    assert.ok(modal.includes('{ADDITIONAL_DEPARTMENTS_LABEL}') && modal.includes('{FULL_MANAGEMENT_LABEL}'));
    assert.ok(modal.includes('Full management: in each additional department the Manager can see and manage staff (including contact details), tasks,'));
    assert.ok(modal.includes('settings stay Admin-only.'));
    const visible = modal.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/.*$/gm, '');
    assert.ok(!/View only|view-only|ViewOnlyBadge|can view|view access/i.test(visible), 'no view-only wording or badge remains in the dialog');
    assert.ok(modal.includes('>Department access</h3>') && table.includes("viewAccess: 'Department Access',"));
    assert.ok(modal.includes('will no longer manage {revoking.name} from their next request.'), 'the revoke confirmation says what is lost');
  });

  it('history rows: an UPGRADED record reads "Upgraded to full management", sorted among the others, under All only', () => {
    const rows = viewAccessHistoryRows([
      { id: 'a', action: 'GRANTED', departmentName: 'Maintenance', changedByUsername: 'admin', changedAt: '2026-11-15T10:00:00Z' },
      { id: 'b', action: 'UPGRADED', departmentName: 'Maintenance', changeSource: 'ACCESS_MODEL_V23', changedByUsername: 'SYSTEM (V23 migration)', changedAt: '2026-11-17T10:00:00Z' },
    ]);
    assert.deepEqual(rows.map((r) => r.id), ['b', 'a'], 'newest first');
    assert.equal(rows[0].action, 'Upgraded to full management');
    assert.equal(rows[0].tone, 'upgraded');
    assert.equal(rows[0].actor, 'SYSTEM (V23 migration)');
    assert.ok(rowComponent.includes("upgraded: { dot: 'bg-sky-500', text: 'text-sky-700' }"));
    assert.deepEqual(HISTORY_FILTERS.map((f) => f.key), ['ALL', 'GRANTED', 'REVOKED'], 'no separate filter for UPGRADED: it appears under All only');
  });
});
