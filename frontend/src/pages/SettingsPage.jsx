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
    <div className="w-full space-y-6">
      {/* Header: back link, title, one-line description */}
      <header className="space-y-2">
        <Link to="/settings" className="inline-flex items-center gap-1.5 text-sm font-medium text-emerald-700 hover:text-emerald-800 hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 rounded">
          <span aria-hidden="true">&larr;</span> Back to Settings
        </Link>
        <h1 className="text-2xl sm:text-3xl font-bold tracking-tight text-gray-900">System Settings</h1>
        <p className="text-sm sm:text-base text-gray-500">Configure hotel operations and workload balancing rules.</p>
      </header>

      <section className="bg-white rounded-2xl border border-gray-200 shadow-sm overflow-hidden" aria-labelledby="system-settings-rules-title" aria-busy={loading || saving}>
        <div className="flex items-center gap-4 px-6 sm:px-8 py-5 border-b border-gray-100">
          <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-emerald-50 text-emerald-700">
            <SlidersIcon className="h-5 w-5" />
          </span>
          <div className="min-w-0">
            <h2 id="system-settings-rules-title" className="text-lg font-bold text-gray-900">Workload Balancing Rules</h2>
            <p className="text-sm text-gray-500 mt-0.5">Hotel-wide rules for balancing work between staff and departments.</p>
          </div>
        </div>

        {(loadError || loading) && (
          <div className="px-6 sm:px-8 py-6">
            {loadError && (
              <div role="alert" className="flex flex-wrap items-center justify-between gap-3 text-sm font-medium text-red-700 bg-red-50 border border-red-200 rounded-lg px-4 py-3">
                <span>{loadError}</span>
                <button type="button" onClick={() => setAttempt((n) => n + 1)} className="px-3 py-1.5 rounded-lg border border-red-300 bg-white text-red-700 font-semibold hover:bg-red-50 focus:outline-none focus-visible:ring-2 focus-visible:ring-red-400 cursor-pointer">
                  Try again
                </button>
              </div>
            )}
            {loading && (
              <div className="grid grid-cols-1 gap-4 lg:grid-cols-2" aria-hidden="true">
                {[0, 1, 2, 3].map((n) => (
                  <div key={n} className="flex items-center justify-between gap-6 rounded-xl border border-gray-200 p-5 animate-pulse">
                    <div className="space-y-2 flex-1"><div className="h-3.5 w-40 rounded bg-gray-200" /><div className="h-3 w-3/4 rounded bg-gray-100" /></div>
                    <div className="h-6 w-11 rounded-full bg-gray-200" />
                  </div>
                ))}
              </div>
            )}
            {loading && <p role="status" className="sr-only">{SYSTEM_SETTINGS_MESSAGES.loading}</p>}
          </div>
        )}

        {form && (
          <>
            {/* The four settings as equal cards: two columns on wide screens, one on small ones */}
            <div className="grid grid-cols-1 gap-4 p-6 sm:p-8 lg:grid-cols-2">
              <SettingCard
                icon={<ScaleIcon className="h-5 w-5" />}
                title="Auto-Balancing Trigger"
                description="Show workload balancing recommendations for departments under pressure. Suggestions only: no task is ever moved automatically"
              >
                <SettingSwitch label="Auto-Balancing Trigger" checked={form.autoBalancingEnabled} disabled={saving} onChange={() => change({ autoBalancingEnabled: !form.autoBalancingEnabled })} />
              </SettingCard>

              <SettingCard
                icon={<BellIcon className="h-5 w-5" />}
                title="Max Task Capacity Alert"
                description="Show a dashboard warning when a department reaches the capacity threshold below"
              >
                <SettingSwitch label="Max Task Capacity Alert" checked={form.capacityAlertEnabled} disabled={saving} onChange={() => change({ capacityAlertEnabled: !form.capacityAlertEnabled })} />
              </SettingCard>

              <SettingCard
                icon={<GaugeIcon className="h-5 w-5" />}
                title="Capacity Threshold"
                description={`Percentage of a department's available staff who have active work (unavailable staff are not counted either way), at which the capacity alert applies (${THRESHOLD_MIN} to ${THRESHOLD_MAX})`}
                error={!thresholdCheck.valid ? thresholdCheck.message : null}
              >
                <div className={`flex items-center rounded-lg border bg-white transition-colors focus-within:ring-2 ${thresholdCheck.valid ? 'border-gray-300 hover:border-gray-400 focus-within:border-emerald-500 focus-within:ring-emerald-200' : 'border-red-400 focus-within:ring-red-200'} ${saving ? 'opacity-60 bg-gray-50' : ''}`}>
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
                    className="w-14 bg-transparent py-2 pl-3 pr-1 text-right text-sm font-semibold tabular-nums text-gray-900 focus:outline-none disabled:cursor-not-allowed"
                  />
                  <span className="pr-3 text-sm font-medium text-gray-500" aria-hidden="true">%</span>
                </div>
              </SettingCard>

              <SettingCard
                icon={<UsersIcon className="h-5 w-5" />}
                title="Cross-Department Assistance"
                description="Allow new cross-department support to be planned and confirmed. Turning it off does not end support that already exists"
              >
                <SettingSwitch label="Cross-Department Assistance" checked={form.crossDepartmentAssistanceEnabled} disabled={saving} onChange={() => change({ crossDepartmentAssistanceEnabled: !form.crossDepartmentAssistanceEnabled })} />
              </SettingCard>
            </div>

            {/* Save area: what happened, and the one primary action */}
            <div className="border-t border-gray-100 bg-gray-50/70 px-6 sm:px-8 py-4 space-y-3">
              <div className="flex flex-col-reverse gap-3 sm:flex-row sm:items-center sm:justify-between">
                <div className="min-h-[2.25rem] flex items-center">
                  {status.phase === 'saving' && (
                    <p role="status" aria-live="polite" className="flex items-center gap-2 text-sm font-medium text-gray-600">
                      <Spinner />
                      {status.message}
                    </p>
                  )}
                  {status.phase === 'saved' && (
                    <p role="status" aria-live="polite" className="inline-flex items-center gap-2 text-sm font-semibold text-emerald-800 bg-emerald-50 border border-emerald-200 rounded-lg px-3 py-1.5">
                      <CheckIcon className="h-4 w-4" />
                      {status.message}
                    </p>
                  )}
                  {status.phase === 'error' && (
                    <p role="alert" className="text-sm font-medium text-red-700 bg-red-50 border border-red-200 rounded-lg px-3 py-1.5">{status.message}</p>
                  )}
                  {status.phase === 'idle' && dirty && thresholdCheck.valid && <p className="text-sm text-gray-500">You have unsaved changes.</p>}
                  {status.phase === 'idle' && !dirty && <p className="text-sm text-gray-500">All changes are saved.</p>}
                </div>
                <button
                  type="button"
                  onClick={save}
                  disabled={saving || !dirty || !thresholdCheck.valid}
                  className="inline-flex items-center justify-center gap-2 px-5 py-2.5 rounded-lg bg-emerald-600 text-white text-sm font-semibold shadow-sm transition-colors hover:bg-emerald-700 active:bg-emerald-800 focus:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 focus-visible:ring-offset-2 disabled:bg-gray-300 disabled:text-gray-500 disabled:shadow-none disabled:cursor-not-allowed cursor-pointer"
                >
                  {saving ? 'Saving…' : 'Save Changes'}
                </button>
              </div>
              <p className="text-xs text-gray-400">
                These settings are stored now{saved?.updatedBy ? ` (last saved by ${saved.updatedBy})` : ''}. They take effect on workload balancing, alerts and cross-department assistance in a later update.
              </p>
            </div>
          </>
        )}
      </section>
    </div>
  );
}

/**
 * One setting as a card: an icon and the name on the left, the control on the right (the same place on every card), the explanation
 * below, and an error under the explanation. All four cards are the same height in a row.
 */
function SettingCard({ icon, title, description, error, children }) {
  return (
    <div className="flex h-full flex-col gap-3 rounded-xl border border-gray-200 bg-white p-5 transition-colors hover:border-emerald-200 hover:bg-emerald-50/30">
      <div className="flex items-center justify-between gap-4">
        <div className="flex min-w-0 items-center gap-3">
          <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-emerald-50 text-emerald-700" aria-hidden="true">{icon}</span>
          <p className="text-sm font-semibold text-gray-900">{title}</p>
        </div>
        <div className="flex shrink-0 items-center">{children}</div>
      </div>
      <div className="min-w-0">
        <p className="text-sm text-gray-500">{description}</p>
        {error && <p id="capacity-threshold-error" role="alert" className="text-xs font-medium text-red-700 mt-2">{error}</p>}
      </div>
    </div>
  );
}

function IconFrame({ className, children }) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      {children}
    </svg>
  );
}
const SlidersIcon = ({ className }) => (<IconFrame className={className}><line x1="4" y1="21" x2="4" y2="14" /><line x1="4" y1="10" x2="4" y2="3" /><line x1="12" y1="21" x2="12" y2="12" /><line x1="12" y1="8" x2="12" y2="3" /><line x1="20" y1="21" x2="20" y2="16" /><line x1="20" y1="12" x2="20" y2="3" /><line x1="1" y1="14" x2="7" y2="14" /><line x1="9" y1="8" x2="15" y2="8" /><line x1="17" y1="16" x2="23" y2="16" /></IconFrame>);
const ScaleIcon = ({ className }) => (<IconFrame className={className}><path d="M12 3v18" /><path d="M5 7h14" /><path d="M5 7l-3 7a3 3 0 0 0 6 0L5 7z" /><path d="M19 7l-3 7a3 3 0 0 0 6 0l-3-7z" /></IconFrame>);
const BellIcon = ({ className }) => (<IconFrame className={className}><path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9" /><path d="M13.73 21a2 2 0 0 1-3.46 0" /></IconFrame>);
const GaugeIcon = ({ className }) => (<IconFrame className={className}><path d="M12 14l4-4" /><path d="M3.34 19a10 10 0 1 1 17.32 0" /></IconFrame>);
const UsersIcon = ({ className }) => (<IconFrame className={className}><path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2" /><circle cx="9" cy="7" r="4" /><path d="M23 21v-2a4 4 0 0 0-3-3.87" /><path d="M16 3.13a4 4 0 0 1 0 7.75" /></IconFrame>);
const CheckIcon = ({ className }) => (<IconFrame className={className}><polyline points="20 6 9 17 4 12" /></IconFrame>);

function Spinner() {
  return (
    <svg className="animate-spin h-4 w-4" fill="none" viewBox="0 0 24 24" aria-hidden="true">
      <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
      <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z" />
    </svg>
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
      } ${disabled ? 'opacity-60 cursor-not-allowed' : `cursor-pointer ${checked ? 'hover:bg-emerald-700' : 'hover:bg-gray-400'}`}`}
    >
      <span
        className={`inline-block h-5 w-5 rounded-full shadow transition-transform ${checked ? 'translate-x-[22px]' : 'translate-x-0.5'}`}
        style={{ backgroundColor: '#ffffff' }}
      />
    </button>
  );
}
