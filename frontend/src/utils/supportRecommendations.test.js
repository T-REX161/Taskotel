import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { formatMinutes, getSeverityBadge, getSeverityLabel, hasNoPressure, normalizeRecommendations } from './supportRecommendations.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const read = (...parts) => fs.readFileSync(path.join(__dirname, '..', ...parts), 'utf8').replace(/\r\n/g, '\n');

describe('supportRecommendations helpers', () => {
  it('labels severities and falls back safely', () => {
    assert.equal(getSeverityLabel('CRITICAL'), 'Critical');
    assert.equal(getSeverityLabel('HIGH'), 'High');
    assert.equal(getSeverityLabel('anything else'), 'Moderate');
    assert.ok(getSeverityBadge('CRITICAL').includes('red'));
    assert.equal(getSeverityBadge(undefined), getSeverityBadge('MODERATE'));
  });

  it('formats minutes and tolerates missing numbers', () => {
    assert.equal(formatMinutes(90), '90 min');
    assert.equal(formatMinutes(undefined), '0 min');
  });

  it('normalizes a partial or empty response so every list exists', () => {
    const empty = normalizeRecommendations(null);
    assert.deepEqual(empty, { generatedAt: null, recommendations: [], unsupported: [], limitations: [] });
    assert.equal(hasNoPressure(empty), true);

    const partial = normalizeRecommendations({ recommendations: [{ departmentCode: 'HOUSEKEEPING', suggestions: [{ staffId: 's1' }] }] });
    assert.deepEqual(partial.recommendations[0].reasons, []);
    assert.deepEqual(partial.recommendations[0].suggestions[0].reasons, []);
    assert.equal(hasNoPressure(partial), false);
  });

  it('reports "no pressure" only when nothing is recommended and nothing is unsupported', () => {
    assert.equal(hasNoPressure(normalizeRecommendations({ unsupported: [{ departmentCode: 'SECURITY' }] })), false);
  });
});

describe('Cross-Department Support panel wiring', () => {
  const panel = read('components', 'workload', 'CrossDepartmentSupportPanel.jsx');
  const builder = read('components', 'workload', 'support', 'SupportPlanBuilder.jsx');
  const dialog = read('components', 'workload', 'support', 'SupportConfirmDialog.jsx');
  const page = read('pages', 'WorkloadPage.jsx');
  const api = read('services', 'api.js');

  it('uses the read-only recommendations endpoint and the dedicated support endpoints, nothing else that writes', () => {
    assert.ok(api.includes("apiFetch('/api/workload/recommendations')"));
    assert.ok(api.includes("'/api/support/preview', { method: 'POST'") && api.includes("'/api/support/assignments', { method: 'POST'"));
    assert.ok(!panel.includes('createAssignment') && !panel.includes('updateStaff'), 'the panel never edits staff or uses the ordinary assignment endpoint');
  });

  it('creates assignments only from the confirmation dialog, after an explicit confirm', () => {
    assert.equal(panel.split('confirmSupportPlan(').length - 1, 1, 'one place confirms');
    assert.ok(panel.includes('onConfirm={handleConfirm}') && panel.includes("type: 'OPEN_REVIEW'"));
    assert.ok(dialog.includes('role="dialog"') && dialog.includes('aria-modal="true"') && dialog.includes("event.key === 'Escape'"));
    assert.ok(dialog.includes('Confirm assignments') && dialog.includes('Cancel'));
    assert.ok(!builder.includes('confirmSupportPlan'), 'selecting staff never confirms');
  });

  it('distinguishes suggested, selected, active and completed support', () => {
    assert.ok(panel.includes('SUPPORT_STAGES') && panel.includes('SupportStageBadge'));
    assert.ok(builder.includes('stage="selected"') && builder.includes('stage="recommended"'));
    assert.ok(read('components', 'workload', 'support', 'SupportHistory.jsx').includes('stageOf(row.status)'));
    assert.ok(builder.includes('Skills and shifts are not recorded'), 'the suitability limitation is stated');
  });

  it('handles loading, empty, error and retry states, success and failure feedback, and the limitations', () => {
    assert.ok(panel.includes("status: 'loading'") && panel.includes('Calculating recommendations'));
    assert.ok(panel.includes("needs.status === 'error'") && panel.includes('role="alert"') && panel.includes('Try again'));
    assert.ok(panel.includes('no support is needed'));
    assert.ok(panel.includes('Support confirmed') && panel.includes('Nothing was created'));
    assert.ok(panel.includes('The plan was out of date'));
    assert.ok(panel.includes('What these suggestions do not take into account'));
  });

  it('refreshes the page data after a confirmed plan', () => {
    assert.ok(panel.includes('if (onChanged) onChanged();') && panel.includes('loadHistory();') && panel.includes('loadOptions(state.department);'));
    assert.ok(page.includes('<CrossDepartmentSupportPanel onChanged={() => fetchData(true)} />'));
  });

  it('appears on the Workload page for administrators and managers only', () => {
    assert.ok(page.includes("import CrossDepartmentSupportPanel from '../components/workload/CrossDepartmentSupportPanel';"));
    assert.ok(page.includes("['ADMIN', 'MANAGER'].includes(getUserRole()) && <CrossDepartmentSupportPanel"));
    assert.ok(page.indexOf('<CrossDepartmentSupportPanel') > page.indexOf('Workload by Department'));
  });
});
