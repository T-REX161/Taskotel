import React, { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import PersonalPreferencesCard from '../components/settings/PersonalPreferencesCard';
import NotificationPreferencesCard from '../components/settings/NotificationPreferencesCard';
import { getUserRole } from '../services/api';
import { canAccess } from '../utils/accessPolicy';
import {
  SYSTEM_SETTINGS_MESSAGES, THRESHOLD_MAX, THRESHOLD_MIN, buildRequest, isDirty, loadSystemSettings, parseThreshold, saveSystemSettings,
  systemSettingsErrorMessage, toForm,
} from '../utils/systemSettings';

/** The general Settings page: personal and notification preferences, for every signed-in role. */
export default function SettingsPage() {
  // Presentation only: the link is shown from the same table that guards the route; the route itself refuses everyone but an Admin
  const showSystemLink = canAccess(getUserRole(), '/settings/system');
  return (
    <div className="w-full max-w-5xl space-y-6">
      {/* Header */}
      <div>
        <h1 className="text-2xl font-bold text-gray-900">Settings</h1>
        <p className="text-gray-500 mt-1">Your personal preferences and notification choices.</p>
      </div>

      <PersonalPreferencesCard />

      <NotificationPreferencesCard />

      {showSystemLink && (
        <div className="bg-white rounded-2xl border border-gray-100 shadow-sm p-6 sm:p-8 flex items-center justify-between gap-4">
          <div>
            <h2 className="text-base font-bold text-gray-900">Site-wide settings</h2>
            <p className="text-sm text-gray-500 mt-1">Hotel-wide operating rules. Administrators only.</p>
          </div>
          <Link to="/settings/system" className="px-4 py-2 rounded-lg bg-emerald-600 hover:bg-emerald-700 text-white text-sm font-medium whitespace-nowrap">Open system settings</Link>
        </div>
      )}
    </div>
  );
}

/**
 * The site-wide System Settings. Admin only: /settings/system is an Admin-only route (utils/accessPolicy), the link above is hidden for
 * everyone else, and the backend refuses everyone but an Admin. The settings are loaded when the page opens and saved with the Save button
 * (all four values at once). A failed load shows an error with Try again and NO values (never the defaults in place of the saved ones); a
 * failed save keeps what was typed and shows the error; success is only shown after the server confirms the save.
 */
export function SystemSettingsPage() {
  const [saved, setSaved] = useState(null); // what the server last confirmed; null until loaded
  const [form, setForm] = useState(null); // what is on screen (the threshold is text while it is typed)
  const [loadError, setLoadError] = useState('');
  const [attempt, setAttempt] = useState(0);
  const [status, setStatus] = useState({ phase: 'idle', message: '' }); // idle | saving | saved | error

  useEffect(() => {
    let active = true;
    setLoadError('');
    loadSystemSettings()
      .then((settings) => {
        if (!active) return;
        setSaved(settings);
        setForm(toForm(settings));
      })
      .catch((error) => { if (active) setLoadError(systemSettingsErrorMessage(error, SYSTEM_SETTINGS_MESSAGES.loadFailed)); });
    return () => { active = false; };
  }, [attempt]);

  const loading = !form && !loadError;
  const saving = status.phase === 'saving';
  const thresholdCheck = form ? parseThreshold(form.capacityThresholdPercent) : { valid: true };
  const dirty = isDirty(saved, form);

  const change = (patch) => {
    setForm((current) => ({ ...current, ...patch }));
    if (status.phase !== 'saving') setStatus({ phase: 'idle', message: '' }); // an old "saved" or error message no longer describes the screen
  };

  const save = async () => {
    if (!form || saving) return;
    if (buildRequest(form).error) return; // the invalid threshold message is already shown under the field; nothing is sent
    setStatus({ phase: 'saving', message: SYSTEM_SETTINGS_MESSAGES.saving });
    try {
      const confirmed = await saveSystemSettings(form);
      setSaved(confirmed);
      setForm(toForm(confirmed));
      setStatus({ phase: 'saved', message: SYSTEM_SETTINGS_MESSAGES.saved });
    } catch (error) {
      // What was typed stays on screen and what is saved is unchanged
      setStatus({ phase: 'error', message: systemSettingsErrorMessage(error, SYSTEM_SETTINGS_MESSAGES.saveFailed) });
    }
  };

  return (
    <div className="w-full max-w-5xl space-y-6">
      <div>
        <Link to="/settings" className="text-sm text-emerald-700 hover:underline">&larr; Back to Settings</Link>
        <h1 className="text-2xl font-bold text-gray-900 mt-2">System Settings</h1>
        <p className="text-gray-500 mt-1">Configure hotel operations and task auto-balancing rules.</p>
      </div>

      <div className="bg-white rounded-2xl border border-gray-100 shadow-sm p-6 sm:p-8 space-y-6" aria-busy={loading || saving}>
        <h2 className="text-base font-bold text-gray-900 border-b border-gray-100 pb-3">Workload Balancing Rules</h2>

        {loadError && (
          <div role="alert" className="flex flex-wrap items-center justify-between gap-3 text-sm font-medium text-red-700 bg-red-50 border border-red-200 rounded-lg px-3 py-2">
            <span>{loadError}</span>
            <button type="button" onClick={() => setAttempt((n) => n + 1)} className="px-3 py-1 rounded-lg border border-red-300 bg-white text-red-700 font-semibold hover:bg-red-50 cursor-pointer">
              Try again
            </button>
          </div>
        )}
        {loading && <p role="status" className="text-sm text-gray-500">{SYSTEM_SETTINGS_MESSAGES.loading}</p>}

        {form && (
          <>
            <div className="space-y-4 max-w-xl text-sm">
              <div className="flex items-center justify-between gap-4 py-2 border-b border-gray-50">
                <div>
                  <p className="font-medium text-gray-800">Auto-Balancing Trigger</p>
                  <p className="text-xs text-gray-400">Automatically suggest reassignments when staff enters HIGH or AT_RISK state</p>
                </div>
                <SettingSwitch label="Auto-Balancing Trigger" checked={form.autoBalancingEnabled} disabled={saving} onChange={() => change({ autoBalancingEnabled: !form.autoBalancingEnabled })} />
              </div>

              <div className="flex items-center justify-between gap-4 py-2 border-b border-gray-50">
                <div>
                  <p className="font-medium text-gray-800">Max Task Capacity Alert</p>
                  <p className="text-xs text-gray-400">Send notifications when a department reaches the capacity threshold below</p>
                </div>
                <SettingSwitch label="Max Task Capacity Alert" checked={form.capacityAlertEnabled} disabled={saving} onChange={() => change({ capacityAlertEnabled: !form.capacityAlertEnabled })} />
              </div>

              <div className="flex items-center justify-between gap-4 py-2 border-b border-gray-50">
                <div>
                  <p className="font-medium text-gray-800">Capacity Threshold</p>
                  <p className="text-xs text-gray-400">Percentage of a department&apos;s staff in work at which the capacity alert applies ({THRESHOLD_MIN} to {THRESHOLD_MAX})</p>
                  {!thresholdCheck.valid && (
                    <p id="capacity-threshold-error" role="alert" className="text-xs font-medium text-red-700 mt-1">{thresholdCheck.message}</p>
                  )}
                </div>
                <div className="flex items-center gap-1.5 shrink-0">
                  <input
                    type="text"
                    inputMode="numeric"
                    autoComplete="off"
                    maxLength={3}
                    aria-label="Capacity Threshold (percent)"
                    aria-invalid={!thresholdCheck.valid}
                    aria-describedby={thresholdCheck.valid ? undefined : 'capacity-threshold-error'}
                    value={form.capacityThresholdPercent}
                    disabled={saving}
                    onChange={(event) => change({ capacityThresholdPercent: event.target.value })}
                    className={`w-16 px-2 py-1.5 rounded-lg border text-right text-sm tabular-nums focus:outline-none focus:ring-2 focus:ring-emerald-500 disabled:opacity-60 ${thresholdCheck.valid ? 'border-gray-300' : 'border-red-400'}`}
                  />
                  <span className="text-gray-500">%</span>
                </div>
              </div>

              <div className="flex items-center justify-between gap-4 py-2">
                <div>
                  <p className="font-medium text-gray-800">Cross-Department Assistance</p>
                  <p className="text-xs text-gray-400">Allow qualified staff to receive tasks from adjacent departments during surge periods</p>
                </div>
                <SettingSwitch label="Cross-Department Assistance" checked={form.crossDepartmentAssistanceEnabled} disabled={saving} onChange={() => change({ crossDepartmentAssistanceEnabled: !form.crossDepartmentAssistanceEnabled })} />
              </div>
            </div>

            <div className="pt-4 border-t border-gray-100 flex flex-wrap items-center gap-3">
              <button
                type="button"
                onClick={save}
                disabled={saving || !dirty || !thresholdCheck.valid}
                className="px-4 py-2 rounded-lg bg-emerald-600 hover:bg-emerald-700 text-white text-sm font-medium disabled:opacity-60 disabled:cursor-not-allowed cursor-pointer"
              >
                {saving ? 'Saving…' : 'Save Changes'}
              </button>
              {status.phase === 'saving' && <p role="status" aria-live="polite" className="text-sm font-medium text-gray-600">{status.message}</p>}
              {status.phase === 'saved' && (
                <p role="status" aria-live="polite" className="text-sm font-medium text-emerald-800 bg-emerald-50 border border-emerald-200 rounded-lg px-3 py-1.5">{status.message}</p>
              )}
              {status.phase === 'error' && (
                <p role="alert" className="text-sm font-medium text-red-700 bg-red-50 border border-red-200 rounded-lg px-3 py-1.5">{status.message}</p>
              )}
            </div>
            <p className="text-xs text-gray-400">
              These settings are stored now{saved?.updatedBy ? ` (last saved by ${saved.updatedBy})` : ''}. They take effect on workload balancing, alerts and cross-department assistance in a later update.
            </p>
          </>
        )}
      </div>
    </div>
  );
}

/** An on/off switch (a button with role="switch", as in Notification Preferences). The knob stays white in both themes. */
function SettingSwitch({ checked, disabled, label, onChange }) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      aria-label={label}
      disabled={disabled}
      onClick={onChange}
      className={`relative inline-flex h-6 w-11 shrink-0 items-center rounded-full transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 focus-visible:ring-offset-2 ${
        checked ? 'bg-emerald-600' : 'bg-gray-300'
      } ${disabled ? 'opacity-60 cursor-not-allowed' : 'cursor-pointer'}`}
    >
      <span
        className={`inline-block h-5 w-5 rounded-full shadow transition-transform ${checked ? 'translate-x-[22px]' : 'translate-x-0.5'}`}
        style={{ backgroundColor: '#ffffff' }}
      />
    </button>
  );
}
