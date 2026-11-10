// Step 2G-4: the support panel against the department-isolated backend. A Manager receives redacted options (no donor
// figures), redacted recommendations (no staff id or department code on a suggestion), Admin-only confirmation, and two
// history projections (receiving and donor). These tests pin the frontend behaviour for each; the backend stays the authority.
import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  CONFIRM_ADMIN_ONLY,
  RESTRICTED_LABEL,
  canArchiveSupport,
  canEndSupport,
  describeArchiveFailure,
  describeArchiveOutcome,
  describeEndEffect,
  describeEndFailure,
  describeEndOutcome,
  donorCapacityLeft,
  donorSummaryText,
  hasDonorFigures,
  initialSupportState,
  isStaleEnd,
  needsReserveOverride,
  personWorkloadText,
  planSignature,
  recordView,
  reserveRiskRows,
  resolveSuggestion,
  suggestedStaffIds,
  supportPermissions,
  supportRecordDisplay,
  supportReducer,
  whyCannotAdd,
} from './supportPlan.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const read = (...parts) => fs.readFileSync(path.join(__dirname, '..', ...parts), 'utf8').replace(/\r\n/g, '\n');

/** No placeholder or broken value ever reaches the screen. */
const assertClean = (text, label = '') => {
  assert.equal(typeof text, 'string', label);
  assert.ok(!/\bnull\b|\bundefined\b|NaN|\[object Object\]/.test(text), `${label}: ${text}`);
};

const NAMES = { HOUSEKEEPING: 'Housekeeping', MAINTENANCE: 'Maintenance', FRONT_DESK: 'Front Desk' };
const departmentName = (code) => NAMES[code] || (code ? code : 'General');

// What the backend sends an Admin (full figures)
const adminOptions = {
  receiving: { code: 'HOUSEKEEPING', name: 'Housekeeping' },
  tasks: [{ id: 't1', title: 'Clean 101' }, { id: 't2', title: 'Clean 102' }, { id: 't3', title: 'Clean 103' }],
  maxHelpers: 3,
  donors: [
    { code: 'MAINTENANCE', name: 'Maintenance', freeStaff: 3, availableStaff: 4, waitingTasks: 0, maxLendable: 2, overrideLendable: 3, canLend: true, note: null,
      staff: [{ staffId: 'm1', name: 'Ana Ruiz', availability: 'AVAILABLE', activeTaskCount: 0, totalEstimatedDuration: 0, workloadStatus: 'LOW' }] },
  ],
};

// What the backend sends a Manager (Step 2G-2): the donors' figures are null, each helper has only id, name and availability
const managerDonor = (code, name, staff) => ({
  code, name, freeStaff: null, availableStaff: null, waitingTasks: null, maxLendable: null, overrideLendable: null, canLend: null, note: null,
  staff: staff.map(([staffId, personName]) => ({ staffId, name: personName, availability: 'AVAILABLE', activeTaskCount: null, totalEstimatedDuration: null, workloadStatus: null })),
});
const managerOptions = {
  receiving: { code: 'HOUSEKEEPING', name: 'Housekeeping' },
  tasks: [{ id: 't1', title: 'Clean 101' }, { id: 't2', title: 'Clean 102' }, { id: 't3', title: 'Clean 103' }],
  maxHelpers: 3,
  donors: [
    managerDonor('MAINTENANCE', 'Maintenance', [['m1', 'Ana Ruiz'], ['m2', 'Sam Lee'], ['m3', 'Sam Lee']]),
    managerDonor('FRONT_DESK', 'Front Desk', [['f1', 'Omar Haddad'], ['f2', 'Ana Ruiz']]),
  ],
};

// A Manager's recommendation suggestion (Step 2G-1): name and home department name only
const managerSuggestion = (staffName, homeDepartmentName) => ({
  staffId: null, staffName, homeDepartmentCode: null, homeDepartmentName, coversTaskTitle: 'Clean 101', donorImpact: null,
  receiverImpact: { uncoveredTasksBefore: 3, uncoveredTasksAfter: 2 }, reasons: ['Currently free and available to help.'],
});

const start = { ...initialSupportState, department: 'HOUSEKEEPING' };
const addTo = (state, options, donorCode, staffId) => supportReducer(state, { type: 'ADD_STAFF', options, donorCode, staffId });

// History projections, exactly as the backend serialises them (null fields included)
const base = { id: 's1', staffId: 'p1', staffName: 'Dora Diaz', homeDepartmentCode: 'MAINTENANCE', status: 'ACTIVE', createdAt: '2026-11-01T09:00:00', completedAt: null };
const adminRow = { ...base, planId: 'plan', receivingDepartmentCode: 'HOUSEKEEPING', taskId: 'task', taskTitle: 'Deep clean lobby', assignmentId: 'a', approvedBy: 'admin.jo', endReason: null, taskStatus: 'ASSIGNED', reserveOverride: null, archivedAt: null };
const receivingRow = { ...adminRow, approvedBy: null };
const donorRow = { ...base, planId: null, receivingDepartmentCode: null, taskId: null, taskTitle: null, assignmentId: null, approvedBy: null, endReason: null, taskStatus: null, reserveOverride: null, archivedAt: null };

describe('who may do what', () => {
  it('Admin may view, plan, confirm, end and archive', () => {
    assert.deepEqual(supportPermissions('ADMIN'), { canView: true, canPlan: true, canConfirm: true, canEnd: true, canArchive: true });
  });

  it('Manager may view, plan, end and archive, but never confirm', () => {
    assert.deepEqual(supportPermissions('MANAGER'), { canView: true, canPlan: true, canConfirm: false, canEnd: true, canArchive: true });
  });

  it('Staff, an unknown role and no role are shown nothing', () => {
    for (const role of ['STAFF', 'GUEST', '', null, undefined]) {
      assert.deepEqual(supportPermissions(role), { canView: false, canPlan: false, canConfirm: false, canEnd: false, canArchive: false }, String(role));
    }
  });
});

describe('confirmation controls', () => {
  const ready = (options) => {
    let state = addTo(start, options, 'MAINTENANCE', 'm1');
    return supportReducer(state, { type: 'PREVIEW_READY', signature: planSignature(state.department, state.plan),
      preview: { valid: true, problems: [], warnings: [], canOverrideReserve: false, items: [{ staffId: 'm1', staffName: 'Ana Ruiz' }], donors: [], fingerprint: 'fp' } });
  };

  it('an Admin can open the review and confirm', () => {
    const reviewing = supportReducer(ready(adminOptions), { type: 'OPEN_REVIEW', canConfirm: true });
    assert.equal(reviewing.confirm.status, 'reviewing');
    assert.equal(supportReducer(reviewing, { type: 'CONFIRM_START' }).confirm.status, 'submitting');
  });

  it('a Manager never reaches the confirm dialog, and is told why', () => {
    const state = supportReducer(ready(managerOptions), { type: 'OPEN_REVIEW', canConfirm: false });
    assert.equal(state.confirm.status, 'idle');
    assert.equal(state.notice, CONFIRM_ADMIN_ONLY);
    assert.match(CONFIRM_ADMIN_ONLY, /Only an Admin can confirm/);
    assert.equal(supportReducer(state, { type: 'CONFIRM_START' }).confirm.status, 'idle', 'nothing to submit');
  });

  it('the panel hides the action for a Manager, never sends a Manager confirmation, and renders the dialog for an Admin only', () => {
    const panel = read('components', 'workload', 'CrossDepartmentSupportPanel.jsx');
    const preview = read('components', 'workload', 'support', 'SupportImpactPreview.jsx');
    assert.ok(panel.includes('canConfirm={permissions.canConfirm}') && panel.includes("type: 'OPEN_REVIEW', canConfirm: permissions.canConfirm"));
    assert.ok(panel.includes('if (!permissions.canConfirm) return;'), 'handleConfirm refuses for a Manager');
    assert.ok(panel.includes('{confirming && state.preview.data && permissions.canConfirm && ('), 'the confirm dialog is Admin-only');
    assert.ok(preview.includes('const button = canConfirm ? (') && preview.includes('{CONFIRM_ADMIN_ONLY}'), 'the button is replaced by the explanation');
    assert.ok(preview.includes('data.canOverrideReserve === true && canConfirm'), 'no staffing-risk acknowledgement for a viewer who cannot confirm');
  });
});

describe('Staff never see support management', () => {
  it('the panel renders nothing for Staff, and the page never mounts it for them', () => {
    const panel = read('components', 'workload', 'CrossDepartmentSupportPanel.jsx');
    assert.ok(panel.includes('if (!supportPermissions(getUserRole()).canView) return null;'));
    assert.ok(read('pages', 'WorkloadPage.jsx').includes("['ADMIN', 'MANAGER'].includes(getUserRole()) && <CrossDepartmentSupportPanel"));
  });
});

describe('a Manager plans and previews without the donor figures', () => {
  it('knows the figures are withheld, and never turns them into a capacity of zero', () => {
    assert.equal(hasDonorFigures(adminOptions.donors[0]), true);
    assert.equal(hasDonorFigures(managerOptions.donors[0]), false);
    assert.equal(donorCapacityLeft(managerOptions, []).MAINTENANCE, null, 'unknown, not 0');
    assert.equal(donorCapacityLeft(adminOptions, []).MAINTENANCE, 2);
  });

  it('can add a helper, with no reserve block or reserve prompt invented from missing numbers', () => {
    assert.equal(whyCannotAdd(managerOptions, [], 'MAINTENANCE', 'm1'), '');
    assert.equal(needsReserveOverride(managerOptions, [], 'MAINTENANCE', 'm1'), false);
    const state = addTo(start, managerOptions, 'MAINTENANCE', 'm1');
    assert.deepEqual(state.plan, [{ staffId: 'm1', taskId: 't1' }]);
    assert.equal(state.preview.status, 'loading', 'the server preview is requested; it applies the reserve rule');
    assert.deepEqual(reserveRiskRows({ kind: 'add', donorCode: 'MAINTENANCE', staffId: 'm1' }, managerOptions, [], null), [], 'no numbers to show');
  });

  it('the other rules still apply (not offered, plan full, no task left)', () => {
    assert.match(whyCannotAdd(managerOptions, [], 'MAINTENANCE', 'gone'), /no longer available/);
    const full = [{ staffId: 'm1', taskId: 't1' }, { staffId: 'f1', taskId: 't2' }, { staffId: 'm2', taskId: 't3' }];
    assert.notEqual(whyCannotAdd(managerOptions, full, 'FRONT_DESK', 'f2'), '');
  });

  it('the Admin keeps the reserve rules and numbers', () => {
    const lent = [{ staffId: 'x1', taskId: 't1' }, { staffId: 'x2', taskId: 't2' }];
    const admin = { ...adminOptions, donors: [{ ...adminOptions.donors[0], staff: [...adminOptions.donors[0].staff, { staffId: 'x1', name: 'X' }, { staffId: 'x2', name: 'Y' }] }] };
    assert.equal(needsReserveOverride(admin, lent, 'MAINTENANCE', 'm1'), true, 'the third person needs the acknowledgement');
    assert.equal(reserveRiskRows({ kind: 'add', donorCode: 'MAINTENANCE', staffId: 'm1' }, admin, lent, null)[0].freeNow, 1);
  });

  it('a Manager preview with no donor rows is ready and reviewable data, with nothing invented', () => {
    let state = addTo(start, managerOptions, 'MAINTENANCE', 'm1');
    state = supportReducer(state, { type: 'PREVIEW_READY', signature: planSignature(state.department, state.plan),
      preview: { valid: true, problems: [], warnings: [], canOverrideReserve: false, items: [{ staffId: 'm1' }], receiving: { before: { freeStaff: 0 }, after: { freeStaff: 0 } }, donors: [], fingerprint: 'fp' } });
    assert.equal(state.preview.status, 'ready');
    assert.deepEqual(reserveRiskRows({ kind: 'plan' }, managerOptions, state.plan, state.preview.data), []);
    const preview = read('components', 'workload', 'support', 'SupportImpactPreview.jsx');
    assert.ok(preview.includes('donorsWithFigures(data?.donors)') && preview.includes('{donors.map((donor) => ('), 'only donor rows that carry figures are drawn');
    assert.ok(!preview.includes('{data.donors.map('), 'never maps the raw donor list');
    assert.ok(preview.includes("label=\"Lending departments' figures\" value={RESTRICTED_LABEL}"), 'the withheld figures are labelled');
  });
});

describe('no null, undefined or NaN on screen', () => {
  it('a donor department and its helpers read as "not available to your role" for a Manager', () => {
    for (const donor of managerOptions.donors) {
      assertClean(donorSummaryText(donor), donor.name);
      assert.match(donorSummaryText(donor), /not available to your role/);
      for (const person of donor.staff) {
        assertClean(personWorkloadText(person), person.name);
        assert.match(personWorkloadText(person), /not available to your role/);
      }
    }
  });

  it('the Admin keeps the exact figures', () => {
    assert.equal(donorSummaryText(adminOptions.donors[0]), '3 free of 4 available · 0 waiting');
    assert.equal(personWorkloadText(adminOptions.donors[0].staff[0]), 'No active task · 0 min');
    assert.equal(personWorkloadText({ activeTaskCount: 2, totalEstimatedDuration: 75 }), '2 active · 75 min');
  });

  it('the builder uses those texts and never prints the raw donor fields', () => {
    const builder = read('components', 'workload', 'support', 'SupportPlanBuilder.jsx');
    assert.ok(builder.includes('{donorSummaryText(donor)}') && builder.includes('{personWorkloadText(person)}'));
    assert.ok(!builder.includes('{donor.freeStaff} free of') && !builder.includes('{person.activeTaskCount'), 'no raw figures');
    assert.ok(builder.includes('{donor.canLend === false && ('), 'a withheld canLend (null) is not shown as "cannot lend"');
    assert.ok(builder.includes('suggestedStaffIds(options, suggestions)'));
  });

  it('every text built from a donor-projection record is clean', () => {
    for (const status of ['ACTIVE', 'COMPLETED', 'CANCELLED']) {
      const row = { ...donorRow, status, completedAt: status === 'ACTIVE' ? null : '2026-11-02T10:00:00' };
      const shown = supportRecordDisplay(row, 'donor', departmentName);
      for (const value of Object.values(shown)) if (typeof value === 'string') assertClean(value, status);
      const effect = describeEndEffect(row, departmentName, 'donor');
      effect.points.forEach((p) => assertClean(p, 'end effect'));
      assertClean(effect.text, 'end effect');
      assertClean(describeEndOutcome(row, departmentName, 'donor'), 'end outcome');
      assertClean(describeArchiveOutcome(row, 'donor'), 'archive outcome');
    }
  });
});

describe('recommendation helpers are matched by name and home department, never guessed', () => {
  it('a unique match resolves to that option person, with the option id and department code', () => {
    assert.deepEqual(resolveSuggestion(managerOptions, managerSuggestion('Omar Haddad', 'Front Desk')), { status: 'matched', staffId: 'f1', donorCode: 'FRONT_DESK' });
    assert.deepEqual(resolveSuggestion(managerOptions, managerSuggestion('Ana Ruiz', 'Maintenance')), { status: 'matched', staffId: 'm1', donorCode: 'MAINTENANCE' },
      'the same name in another department is not a match');
  });

  it('zero matches and several matches are reported, not resolved', () => {
    assert.deepEqual(resolveSuggestion(managerOptions, managerSuggestion('Nobody Here', 'Maintenance')), { status: 'none' });
    assert.deepEqual(resolveSuggestion(managerOptions, managerSuggestion('Omar Haddad', 'Maintenance')), { status: 'none' }, 'wrong department');
    assert.deepEqual(resolveSuggestion(managerOptions, managerSuggestion('Sam Lee', 'Maintenance')), { status: 'ambiguous' }, 'two people share the name');
    assert.deepEqual(resolveSuggestion(managerOptions, managerSuggestion('omar haddad', 'Front Desk')), { status: 'none' }, 'no loose matching');
    assert.deepEqual(resolveSuggestion(managerOptions, managerSuggestion('', '')), { status: 'none' });
  });

  it('"use suggestions" adds only the unique match, and reports the others for manual selection', () => {
    const suggestions = [managerSuggestion('Omar Haddad', 'Front Desk'), managerSuggestion('Sam Lee', 'Maintenance'), managerSuggestion('Ghost', 'Maintenance')];
    const used = supportReducer(start, { type: 'USE_SUGGESTIONS', options: managerOptions, suggestions });
    assert.deepEqual(used.plan, [{ staffId: 'f1', taskId: 't1' }], 'only Omar; nobody chosen in place of the others');
    assert.match(used.notice, /2 suggestions could not be matched to exactly one person/);
    assert.match(used.notice, /Sam Lee \(Maintenance\): more than one person matches/);
    assert.match(used.notice, /Ghost \(Maintenance\): not in the current list/);
    assert.match(used.notice, /Choose helpers from the list yourself/);
    assertClean(used.notice, 'notice');
  });

  it('when nothing matches the plan is unchanged and the reason is given', () => {
    const used = supportReducer(start, { type: 'USE_SUGGESTIONS', options: managerOptions, suggestions: [managerSuggestion('Sam Lee', 'Maintenance')] });
    assert.deepEqual(used.plan, []);
    assert.match(used.notice, /One suggestion could not be matched/);
  });

  it('the Suggested badge marks only resolved people', () => {
    const ids = suggestedStaffIds(managerOptions, [managerSuggestion('Omar Haddad', 'Front Desk'), managerSuggestion('Sam Lee', 'Maintenance')]);
    assert.deepEqual([...ids], ['f1']);
    assert.ok(!ids.has(null), 'a missing id never enters the set');
  });

  it('the Admin flow is unchanged: suggestions carry ids and are used directly', () => {
    const adminSuggestion = { staffId: 'm1', homeDepartmentCode: 'MAINTENANCE', staffName: 'Ana Ruiz', homeDepartmentName: 'Maintenance' };
    assert.deepEqual(resolveSuggestion(adminOptions, adminSuggestion), { status: 'matched', staffId: 'm1', donorCode: 'MAINTENANCE' });
    const used = supportReducer(start, { type: 'USE_SUGGESTIONS', options: adminOptions, suggestions: [adminSuggestion] });
    assert.deepEqual(used.plan, [{ staffId: 'm1', taskId: 't1' }]);
    assert.equal(used.notice, '');
  });
});

describe('history: the projection the server sent decides what is shown', () => {
  it('recordView: an Admin always gets the full record; a Manager the receiving or the donor projection', () => {
    assert.equal(recordView(adminRow, 'ADMIN'), 'full');
    assert.equal(recordView(donorRow, 'ADMIN'), 'full', 'the role decides first');
    assert.equal(recordView(receivingRow, 'MANAGER'), 'receiving');
    assert.equal(recordView(donorRow, 'MANAGER'), 'donor');
  });

  it('a donor Manager sees only the person, their own department, status and dates', () => {
    const asked = [];
    const shown = supportRecordDisplay(donorRow, 'donor', (code) => { asked.push(code); return departmentName(code); });
    assert.deepEqual(asked, ['MAINTENANCE'], 'the receiving department is never looked up');
    assert.equal(shown.home, 'Maintenance');
    assert.equal(shown.receiving, '');
    assert.equal(shown.task, RESTRICTED_LABEL);
    assert.equal(shown.taskRestricted, true);
    assert.equal(shown.approver, '');
    assert.equal(shown.endReason, '');
    assert.equal(shown.loanNote, 'On loan to another department');
  });

  it('even if a donor row carried receiving details, the donor view would not show them', () => {
    const leaky = { ...donorRow, receivingDepartmentCode: 'HOUSEKEEPING', taskTitle: 'Secret task', approvedBy: 'admin.jo', endReason: 'TASK_MOVED' };
    const text = JSON.stringify(supportRecordDisplay(leaky, 'donor', departmentName));
    for (const hidden of ['Housekeeping', 'Secret task', 'admin.jo', 'moved']) assert.ok(!text.includes(hidden), hidden);
    const effect = describeEndEffect(leaky, departmentName, 'donor').text;
    assert.ok(!effect.includes('Housekeeping') && !effect.includes('Secret task'));
  });

  it('a receiving Manager sees the receiving-side details the server sent, and no approver', () => {
    const shown = supportRecordDisplay(receivingRow, 'receiving', departmentName);
    assert.equal(shown.home, 'Maintenance');
    assert.equal(shown.receiving, 'Housekeeping');
    assert.equal(shown.task, 'Deep clean lobby');
    assert.equal(shown.approver, '');
    assert.equal(supportRecordDisplay({ ...receivingRow, status: 'CANCELLED', endReason: 'MANUALLY_ENDED' }, 'receiving', departmentName).endReason, 'Ended by a manager');
  });

  it('an Admin sees everything, including the approver', () => {
    const shown = supportRecordDisplay(adminRow, 'full', departmentName);
    assert.equal(shown.approver, 'admin.jo');
    assert.equal(shown.receiving, 'Housekeeping');
  });

  it('the history list, end dialog and archive dialog render the donor branch without receiving details', () => {
    const history = read('components', 'workload', 'support', 'SupportHistory.jsx');
    const donorBranch = history.slice(history.indexOf("{view === 'donor' ? ("), history.indexOf(') : (', history.indexOf("{view === 'donor' ? (")));
    assert.ok(donorBranch.includes('{shown.home}') && donorBranch.includes('{shown.loanNote}'));
    assert.ok(!/receivingDepartmentCode|taskTitle|approvedBy/.test(donorBranch), 'nothing from the receiving side');
    assert.ok(history.includes("{row.approvedBy ? ` · ${row.approvedBy}` : ''}"), 'the approver only when sent');
    assert.ok(history.includes('const view = recordView(row, role);'));
    const end = read('components', 'workload', 'support', 'SupportEndDialog.jsx');
    assert.ok(end.includes('donorView ?') && end.includes('donorView || !row.taskTitle'));
    const archive = read('components', 'workload', 'support', 'SupportArchiveDialog.jsx');
    assert.ok(archive.includes("view === 'donor' ?") && archive.includes('shown.taskRestricted'));
    const panel = read('components', 'workload', 'CrossDepartmentSupportPanel.jsx');
    assert.ok(panel.includes('view={recordView(endState.row, role)}') && panel.includes('view={recordView(archiveState.row, role)}'));
    assert.equal(panel.split('role={role}').length - 1, 2, 'both history lists know the viewer role');
  });

  it('end and archive outcomes for a donor Manager name no task or receiving department', () => {
    assert.equal(describeEndOutcome({ ...donorRow, status: 'CANCELLED' }, departmentName, 'donor'), 'Support ended. Dora Diaz is no longer on loan and stays in Maintenance.');
    assert.match(describeEndOutcome({ ...donorRow, status: 'COMPLETED' }, departmentName, 'donor'), /had already finished/);
    assert.equal(describeArchiveOutcome({ ...donorRow, status: 'COMPLETED' }, 'donor'), 'Removed from the history: Dora Diaz. The record is archived, not deleted.');
    assert.equal(describeArchiveOutcome({ ...adminRow, status: 'COMPLETED' }), 'Removed from the history: Dora Diaz · Deep clean lobby. The record is archived, not deleted.', 'Admin unchanged');
  });
});

describe('end and archive controls follow status and archivedAt', () => {
  it('End is offered on active records of every projection', () => {
    for (const row of [adminRow, receivingRow, donorRow]) assert.equal(canEndSupport(row), true);
    assert.equal(canEndSupport({ ...donorRow, status: 'COMPLETED' }), false);
  });

  it('Remove is offered on ended records whose archivedAt is present and empty, in every projection', () => {
    for (const row of [adminRow, receivingRow, donorRow]) {
      assert.equal(canArchiveSupport({ ...row, status: 'COMPLETED' }), true, 'archivedAt: null');
      assert.equal(canArchiveSupport({ ...row, status: 'CANCELLED', archivedAt: '2026-11-03T10:00:00' }), false, 'already archived');
      assert.equal(canArchiveSupport(row), false, 'still active');
    }
    const { archivedAt: _ignored, ...older } = { ...donorRow, status: 'COMPLETED' };
    assert.equal(canArchiveSupport(older), false, 'a server that does not send archivedAt cannot archive');
  });
});

describe('authorization errors are explained without revealing hidden records', () => {
  it('a 403 says the viewer may not do it', () => {
    assert.equal(describeEndFailure(undefined, undefined, 403), 'You do not have permission to end this support.');
    assert.equal(describeArchiveFailure(undefined, undefined, 403), 'You do not have permission to remove this record.');
    assert.equal(describeEndFailure('Your account is not assigned to an active department.', undefined, 403), 'Your account is not assigned to an active department.');
  });

  it('an out-of-scope record reads like a missing one, and the screen refreshes', () => {
    const message = 'This support record no longer exists. Refresh to see the latest.';
    assert.equal(describeEndFailure(message, 'SUPPORT_NOT_FOUND', 404), message);
    assert.equal(describeArchiveFailure(message, 'SUPPORT_NOT_FOUND', 404), message);
    assert.equal(isStaleEnd('SUPPORT_NOT_FOUND', 404), true);
  });

  it('the failure text is built only from the server message, never from the record on screen', () => {
    const source = read('utils', 'supportPlan.js');
    for (const fn of ['describeEndFailure', 'describeArchiveFailure']) {
      const body = source.slice(source.indexOf(`export function ${fn}`), source.indexOf('\n}\n', source.indexOf(`export function ${fn}`)));
      assert.ok(!/row\.|taskTitle|receivingDepartmentCode|staffName/.test(body), fn);
    }
  });
});
