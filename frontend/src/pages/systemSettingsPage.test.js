import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { canAccess } from '../utils/accessPolicy.js';

const here = path.dirname(fileURLToPath(import.meta.url));
const source = fs.readFileSync(path.join(here, 'SettingsPage.jsx'), 'utf8').replace(/\r\n/g, '\n');
const marker = source.indexOf('export function SystemSettingsPage');
const general = source.slice(0, marker);
const page = source.slice(marker);

// The project has no DOM test environment, so what the page does with this logic is checked on its source; the logic itself
// (validation, request, load and save results, errors) is tested by running it in utils/systemSettings.test.js.
describe('System Settings page wiring (source checks)', () => {
  it('loads the saved settings when the page opens, and offers Try again when that fails', () => {
    assert.ok(page.includes('loadSystemSettings()') && page.includes('}, [attempt]);'));
    assert.ok(page.includes('setLoadError(systemSettingsErrorMessage(error, SYSTEM_SETTINGS_MESSAGES.loadFailed))'));
    assert.ok(page.includes('Try again') && page.includes('setAttempt((n) => n + 1)'));
    assert.ok(page.includes('{loading && <p role="status"'), 'a loading message is shown');
  });

  it('shows the controls only once real values have loaded: a failed load shows an error and no values, never the defaults', () => {
    assert.ok(page.includes('{form && ('));
    assert.ok(page.includes('const [saved, setSaved] = useState(null);') && page.includes('const [form, setForm] = useState(null);'));
    assert.ok(!/useState\(\{[^)]*autoBalancingEnabled/.test(page), 'the form is never initialised with default values');
    assert.ok(!page.includes('defaultChecked'), 'the old unconnected checkboxes are gone');
    assert.ok(!/DEFAULT/.test(page), 'no defaults are used by the page');
  });

  it('shows the four settings with their existing labels, connected to the form values', () => {
    for (const label of ['Auto-Balancing Trigger', 'Max Task Capacity Alert', 'Cross-Department Assistance', 'Capacity Threshold']) assert.ok(page.includes(label), label);
    assert.ok(page.includes('checked={form.autoBalancingEnabled}') && page.includes('checked={form.capacityAlertEnabled}') && page.includes('checked={form.crossDepartmentAssistanceEnabled}'));
    assert.ok(page.includes('value={form.capacityThresholdPercent}'));
    assert.ok(page.includes('onChange={() => change({ autoBalancingEnabled: !form.autoBalancingEnabled })}'));
    assert.ok(page.includes('onChange={() => change({ capacityAlertEnabled: !form.capacityAlertEnabled })}'));
    assert.ok(page.includes('onChange={() => change({ crossDepartmentAssistanceEnabled: !form.crossDepartmentAssistanceEnabled })}'));
    assert.ok(page.includes('onChange={(event) => change({ capacityThresholdPercent: event.target.value })}'));
  });

  it('the threshold input is a labelled text box that shows an inline error for an invalid value and marks itself invalid', () => {
    assert.ok(page.includes('aria-label="Capacity Threshold (percent)"') && page.includes('inputMode="numeric"') && page.includes('maxLength={3}'));
    assert.ok(page.includes('aria-invalid={!thresholdCheck.valid}') && page.includes('error={!thresholdCheck.valid ? thresholdCheck.message : null}'));
    assert.ok(page.includes('const thresholdCheck = form ? parseThreshold(form.capacityThresholdPercent)'));
  });

  it('Save Changes is disabled while saving, when nothing changed, and when the threshold is invalid', () => {
    assert.ok(page.includes('disabled={saving || !dirty || !thresholdCheck.valid}'));
    assert.ok(page.includes("{saving ? 'Saving…' : 'Save Changes'}"));
    assert.ok(page.includes('if (buildRequest(form).error) return;'), 'an invalid threshold sends nothing');
  });

  it('saves through the shared request, shows success only after the server confirms, and replaces the form with the confirmed values', () => {
    const save = page.slice(page.indexOf('const save = async'), page.indexOf('\n  return (\n    <div', page.indexOf('const save = async')));
    const ok = save.indexOf('const confirmed = await saveSystemSettings(form);');
    assert.ok(ok > 0);
    assert.ok(save.indexOf("setStatus({ phase: 'saved'") > ok, 'success is set only after the awaited save');
    assert.ok(save.includes('setSaved(confirmed);') && save.includes('setForm(toForm(confirmed));'));
    assert.ok(save.includes("setStatus({ phase: 'saving'") && save.indexOf("setStatus({ phase: 'saving'") < ok, 'a saving state is shown before the request');
  });

  it('a failed save shows the error and keeps what was typed and what is saved (the catch changes neither)', () => {
    const save = page.slice(page.indexOf('const save = async'), page.indexOf('\n  return (\n    <div', page.indexOf('const save = async')));
    const failure = save.slice(save.indexOf('} catch (error) {'));
    assert.ok(failure.includes("setStatus({ phase: 'error'") && failure.includes('systemSettingsErrorMessage(error, SYSTEM_SETTINGS_MESSAGES.saveFailed)'));
    assert.ok(!failure.includes('setForm(') && !failure.includes('setSaved('));
  });

  it('every control is locked while a save is in progress, and an old message is cleared when something is edited', () => {
    assert.equal((page.match(/disabled=\{saving\}/g) || []).length, 4, 'three switches and the threshold input');
    assert.ok(page.includes("if (status.phase !== 'saving') setStatus({ phase: 'idle', message: '' });"));
  });

  it('is honest that the settings are not applied yet (no behaviour is wired in this stage)', () => {
    assert.ok(page.includes('They take effect on workload balancing, alerts and cross-department assistance in a later update.'));
  });

  it('the success, error and status messages are announced (role status and alert)', () => {
    assert.ok(page.includes("{status.phase === 'saved' && (") && page.includes('role="status" aria-live="polite"'));
    assert.ok(page.includes("{status.phase === 'error' && (") && page.includes('role="alert"'));
  });
});

describe('Admin-only and the rest of Settings are unchanged', () => {
  it('the system settings route is still Admin only, and the general Settings page is still open to everyone', () => {
    assert.equal(canAccess('ADMIN', '/settings/system'), true);
    for (const role of ['MANAGER', 'STAFF', null, 'admin']) assert.equal(canAccess(role, '/settings/system'), false, String(role));
    for (const role of ['ADMIN', 'MANAGER', 'STAFF']) assert.equal(canAccess(role, '/settings'), true, role);
  });

  it('the general Settings page has none of the system settings and still shows the link only through the access table', () => {
    assert.ok(!general.includes('Workload Balancing Rules') && !general.includes('Auto-Balancing Trigger') && !general.includes('Capacity Threshold'));
    assert.ok(general.includes("canAccess(getUserRole(), '/settings/system')") && general.includes('{showSystemLink && ('));
    assert.ok(general.includes('<PersonalPreferencesCard />') && general.includes('<NotificationPreferencesCard />'));
  });
});
