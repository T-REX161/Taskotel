import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { crossDepartmentAssistanceState } from './systemSettings.js';
import { CROSS_DEPARTMENT_REFRESH_MS, activeSupportRows, canCreateSupport, supportPanelMode } from './crossDepartmentVisibility.js';

// Cross-Department Assistance on the Workload page, following the System Settings switch:
//   on → the whole panel; off with active support → only those arrangements, to follow and end them; off with none → nothing at all.
// (This file replaced the tests of the earlier "assistance is disabled" notice, which is no longer shown.)
const read = (...parts) => readFileSync(new URL(`../${parts.join('/')}`, import.meta.url), 'utf8').replace(/\r\n/g, '\n');
/** What GET /api/support/cross-department-status answers: that one flag only. */
const status = (crossDepartmentAssistanceEnabled) => ({ crossDepartmentAssistanceEnabled });
const row = (id, rowStatus) => ({ id, status: rowStatus, staffName: `Person ${id}` });
const ready = (...rows) => ({ status: 'ready', data: rows, error: '' });

describe('reading the switch', () => {
  it('on and off only for a real boolean from the server', () => {
    assert.equal(crossDepartmentAssistanceState(status(true)), 'on');
    assert.equal(crossDepartmentAssistanceState(status(false)), 'off');
  });

  it('anything else is unknown, never a guess (no answer, junk, missing or non-boolean flag)', () => {
    for (const response of [undefined, null, 'off', 42, [], {}, { other: false }, status('false'), status(0), status(null), status(undefined)]) {
      assert.equal(crossDepartmentAssistanceState(response), 'unknown', JSON.stringify(response));
    }
  });
});

describe('what the panel shows', () => {
  it('ENABLED: the complete panel, whatever the support records', () => {
    for (const history of [ready(), ready(row(1, 'ACTIVE')), ready(row(1, 'COMPLETED')), { status: 'loading', data: [] }, { status: 'error', data: [] }]) {
      assert.equal(supportPanelMode({ assistance: 'on', history }), 'full');
    }
    assert.equal(canCreateSupport('on'), true);
  });

  it('DISABLED with no active arrangements: nothing at all (no notice, summary cards, departments list or history)', () => {
    assert.equal(supportPanelMode({ assistance: 'off', history: ready() }), 'hidden');
    assert.equal(supportPanelMode({ assistance: 'off', history: ready(row(1, 'COMPLETED'), row(2, 'CANCELLED')) }), 'hidden', 'past records alone are not shown');
    assert.equal(canCreateSupport('off'), false);
  });

  it('DISABLED with active arrangements: the compact management section, with only those arrangements', () => {
    const history = ready(row(1, 'ACTIVE'), row(2, 'COMPLETED'), row(3, 'ACTIVE'));
    assert.equal(supportPanelMode({ assistance: 'off', history }), 'manage');
    assert.deepEqual(activeSupportRows(history).map((r) => r.id), [1, 3]);
    assert.equal(canCreateSupport('off'), false, 'nothing new can be planned or confirmed');
  });

  it('DISABLED: once the last arrangement ends, the section stays until its "support ended" message is dismissed, then goes', () => {
    assert.equal(supportPanelMode({ assistance: 'off', history: ready(row(1, 'CANCELLED')), keepOutcome: true }), 'manage');
    assert.equal(supportPanelMode({ assistance: 'off', history: ready(row(1, 'CANCELLED')), keepOutcome: false }), 'hidden');
  });

  it('nothing flashes before the answers arrive, and nothing is hidden on a failed read', () => {
    assert.equal(supportPanelMode({ assistance: 'loading', history: ready(row(1, 'ACTIVE')) }), 'pending', 'switch not read yet');
    assert.equal(supportPanelMode({ assistance: 'off', history: { status: 'loading', data: [] } }), 'pending', 'records not read yet');
    assert.equal(supportPanelMode({ assistance: 'unknown', history: ready() }), 'full', 'switch unreadable: as before (the server still refuses new support when off)');
    assert.equal(supportPanelMode({ assistance: 'off', history: { status: 'error', data: [], error: 'x' } }), 'manage', 'records unreadable: kept, so active support is never hidden by an error');
    assert.equal(canCreateSupport('unknown'), true);
    assert.deepEqual(activeSupportRows({ status: 'error', data: [row(1, 'ACTIVE')] }), []);
  });
});

describe('the panel', () => {
  const panel = read('components', 'workload', 'CrossDepartmentSupportPanel.jsx');
  const manageView = panel.slice(panel.indexOf("if (mode === 'manage') {"), panel.indexOf('  return (\n    <section\n      className="min-w-0 bg-white rounded-2xl'));
  const fullView = panel.slice(panel.indexOf('  return (\n    <section\n      className="min-w-0 bg-white rounded-2xl'));

  it('reads the switch from the backend through the narrow status route (both roles), never a hard-coded value', () => {
    // through the shared reader (utils/crossDepartmentStatus), which calls the status route; its first read reuses the page's answer
    assert.ok(panel.includes('const next = await loadCrossDepartmentStatus({ force });'));
    assert.ok(panel.includes('loadCrossDepartmentSetting({ force: false });'), 'mounting reuses the answer the page already asked for');
    assert.match(read('utils', 'crossDepartmentStatus.js'), /fetcher = getCrossDepartmentStatus[\s\S]*crossDepartmentAssistanceState\(response\)/);
    assert.ok(!/getSystemSettings/.test(panel), 'the Admin-only settings route is not used by the panel');
    assert.match(panel, /const \[assistance, setAssistance\] = useState\('loading'\);/);
    assert.match(read('services', 'api.js'), /export const getCrossDepartmentStatus = \(\) => \{\s*return apiFetch\('\/api\/support\/cross-department-status'\);/);
  });

  it('follows changes without a page refresh: re-read every 30s while visible and when the page becomes visible again', () => {
    assert.equal(CROSS_DEPARTMENT_REFRESH_MS, 30000);
    assert.match(panel, /setInterval\(readIfVisible, CROSS_DEPARTMENT_REFRESH_MS\);\s*document\.addEventListener\('visibilitychange', readIfVisible\);/);
    assert.ok(panel.includes("return () => { clearInterval(timer); document.removeEventListener('visibilitychange', readIfVisible); };"), 'stopped when the page goes');
    assert.match(panel, /if \(assistance !== 'off'\) return undefined;\s*const timer = setInterval\(\(\) => \{ if \(document\.visibilityState === 'visible'\) loadHistory\(\); \}, CROSS_DEPARTMENT_REFRESH_MS\);/, 'while off, the active list is followed too');
    assert.ok(panel.includes("if (previous === 'loading' || previous === assistance) return;\n    loadHistory();\n    if (canCreateSupport(assistance)) loadNeeds();"), 'a change reloads what the panel shows');
    assert.ok(panel.includes("setAssistance((prev) => (prev === 'loading' ? 'unknown' : prev));"), 'a failed re-read keeps what was known (no flicker)');
    assert.match(panel, /loadCrossDepartmentSetting\(\);\s*loadOptions\(state\.department\);/, 'Refresh reads it again too');
  });

  it('hidden or not yet known: the panel renders nothing (so no disabled notice, ever)', () => {
    assert.ok(panel.includes("const mode = supportPanelMode({ assistance, history, keepOutcome: endState.status !== 'idle' });"));
    assert.ok(panel.includes("if (mode === 'pending' || mode === 'hidden') return null;"));
    assert.ok(!/cross-department-disabled-notice|CROSS_DEPARTMENT_DISABLED_NOTICE|currently disabled/.test(panel), 'the disabled notice is gone');
  });

  it('DISABLED with active arrangements: a compact section listing only them, with End Support; nothing that creates support', () => {
    assert.ok(manageView.includes('data-testid="cross-department-manage"') && manageView.includes('Active Cross-Department Support'));
    assert.match(manageView, /<SupportHistory\s+history=\{history\}\s+departmentName=\{departmentNames\}\s+role=\{role\}\s+activeOnly\s+canEnd=\{canEnd\}\s+onEnd=/);
    assert.ok(manageView.includes('{endDialog}') && manageView.includes('{endOutcome}'), 'ending an arrangement works as before');
    for (const absent of ['SupportOverview', 'SupportNeedsList', 'Departments that need support', 'SupportPlanBuilder', 'SupportImpactPreview', 'SupportConfirmDialog', 'How this works', 'historyOnly', 'onArchive']) {
      assert.ok(!manageView.includes(absent), `not in the compact section: ${absent}`);
    }
  });

  it('ENABLED: the complete panel as before (summary, departments that need support, planning, confirmation, history)', () => {
    for (const present of ['<SupportOverview needs={needs} history={history} />', 'Departments that need support', '<SupportPlanBuilder', '<SupportConfirmDialog', 'How this works', "{historyView === 'section' && (", "{historyView === 'history' && (", '{endDialog}']) {
      assert.ok(fullView.includes(present), present);
    }
  });

  it('no new arrangement can be previewed or confirmed while disabled (and the server refuses them too)', () => {
    assert.ok(panel.includes('if (!permissions.canConfirm || !canCreateSupport(assistance)) return;'), 'confirm');
    assert.ok(panel.includes("if (state.preview.status !== 'loading' || state.plan.length === 0 || !canCreateSupport(assistance)) return undefined;"), 'preview');
    assert.ok(panel.includes('}, [signature, state.preview.status, assistance]);'));
  });

  it('existing support keeps working while disabled: ending is never gated by the switch, and roles are unchanged', () => {
    const handleEnd = panel.slice(panel.indexOf('const handleEnd = async'), panel.indexOf('const handleArchive'));
    assert.ok(!/assistance|canCreateSupport/.test(handleEnd), 'End Support does not depend on the switch');
    assert.match(panel, /const canEnd = \['ADMIN', 'MANAGER'\]\.includes\(getUserRole\(\)\);/);
    assert.match(panel, /if \(!supportPermissions\(getUserRole\(\)\)\.canView\) return null;/, 'Staff still see nothing');
    assert.ok(read('pages', 'WorkloadPage.jsx').includes("{['ADMIN', 'MANAGER'].includes(getUserRole()) && <CrossDepartmentSupportPanel"));
  });

  it('the backend refuses only NEW support while disabled (for every role); existing arrangements are never ended or changed by the switch', () => {
    const service = readFileSync(new URL('../../../backend/src/main/java/com/hotel/workload/support/SupportAssignmentServiceImpl.java', import.meta.url), 'utf8');
    assert.equal(service.split('requireCrossDepartmentAssistanceEnabled();').length - 1, 3, 'checked by planning options, preview and confirm only');
    assert.match(service, /public Options getOptions\(String receivingDepartmentCode\) \{\s*String managerDepartment = readerDepartmentCode\(\);[\s\S]{0,200}requireCrossDepartmentAssistanceEnabled\(\);/, 'options: after the role check');
    assert.ok(service.includes('Existing support records are never read or changed by this.'));
    const recommendations = readFileSync(new URL('../../../backend/src/main/java/com/hotel/workload/workload/WorkloadRecommendationServiceImpl.java', import.meta.url), 'utf8');
    assert.ok(recommendations.includes('!systemSettings.current().crossDepartmentAssistanceEnabled()'), 'support suggestions are not made while it is off');
  });
});

describe('the active-only list', () => {
  it('shows only arrangements in progress, without the heading and tabs', () => {
    const history = read('components', 'workload', 'support', 'SupportHistory.jsx');
    assert.ok(history.includes('activeOnly = false') && history.includes('{!historyOnly && !activeOnly && ('));
    assert.ok(history.includes("useState(historyOnly ? 'ended' : 'active')"), 'starts (and, without tabs, stays) on the active records');
  });
});
