import React, { useCallback, useEffect, useState } from 'react';
import {
  NOTIFICATION_CATEGORIES, NOTIFICATION_MESSAGES, loadNotificationPreferences, notificationErrorMessage,
  saveNotificationPreference, withChange,
} from '../../utils/notificationPreferences';

function BellIcon({ className }) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9" />
      <path d="M13.73 21a2 2 0 0 1-3.46 0" />
    </svg>
  );
}

function LockIcon({ className }) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <rect x="3" y="11" width="18" height="11" rx="2" />
      <path d="M7 11V7a5 5 0 0 1 10 0v4" />
    </svg>
  );
}

function Spinner() {
  return (
    <svg className="animate-spin h-4 w-4" fill="none" viewBox="0 0 24 24" aria-hidden="true">
      <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
      <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z" />
    </svg>
  );
}

/** An on/off switch (a button with role="switch", so it works with the keyboard and screen readers). The knob stays white in both themes. */
function Switch({ checked, disabled, label, title, onChange }) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      aria-label={label}
      title={title}
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

/**
 * Settings > Notification Preferences: for each category, whether the signed-in user is notified by email (the only channel).
 * Preferences are loaded when the card opens and each switch is saved as soon as it is flipped (no Save button), like Personal
 * Preferences. While one change is saved the switches are locked so changes cannot cross; a failed save puts the switch back and says
 * so. Security alerts are required: always on, with their switches locked and an explanation.
 */
export default function NotificationPreferencesCard() {
  const [prefs, setPrefs] = useState(null); // category -> { email, locked }; null until loaded
  const [loadError, setLoadError] = useState('');
  const [attempt, setAttempt] = useState(0);
  const [status, setStatus] = useState({ phase: 'idle', message: '' }); // idle | saving | saved | error

  useEffect(() => {
    let active = true;
    setLoadError('');
    loadNotificationPreferences()
      .then((map) => { if (active) setPrefs(map); })
      .catch(() => { if (active) setLoadError(NOTIFICATION_MESSAGES.loadFailed); });
    return () => { active = false; };
  }, [attempt]);

  const retry = useCallback(() => setAttempt((n) => n + 1), []);

  const loading = !prefs && !loadError;
  const saving = status.phase === 'saving';

  const toggle = async (category, channel) => {
    if (!prefs || saving || prefs[category].locked) return;
    const previous = prefs;
    const value = !previous[category][channel];
    setPrefs(withChange(previous, category, channel, value)); // shown at once; the server's answer replaces it below
    setStatus({ phase: 'saving', message: NOTIFICATION_MESSAGES.saving });
    try {
      setPrefs(await saveNotificationPreference(previous, category, channel, value));
      setStatus({ phase: 'saved', message: NOTIFICATION_MESSAGES.saved });
    } catch (error) {
      setPrefs(previous);
      setStatus({ phase: 'error', message: notificationErrorMessage(error, NOTIFICATION_MESSAGES.saveFailed) });
    }
  };

  return (
    <section className="bg-white rounded-2xl border border-gray-200 shadow-sm overflow-hidden" aria-labelledby="notification-preferences-title">
      <div className="flex items-center gap-4 px-6 sm:px-8 py-6 border-b border-gray-100">
        <BellIcon className="w-7 h-7 text-gray-500 shrink-0" />
        <div className="min-w-0">
          <h2 id="notification-preferences-title" className="text-lg font-bold text-gray-900">Notification Preferences</h2>
          <p className="text-sm text-gray-500 mt-0.5">Choose which updates Taskotel emails you.</p>
        </div>
      </div>

      <div className="px-6 sm:px-8 py-6" aria-busy={loading || saving}>
        {loadError && (
          <div role="alert" className="flex flex-wrap items-center justify-between gap-3 text-sm font-medium text-red-700 bg-red-50 border border-red-200 rounded-lg px-3 py-2">
            <span>{loadError}</span>
            <button type="button" onClick={retry} className="px-3 py-1 rounded-lg border border-red-300 bg-white text-red-700 font-semibold hover:bg-red-50 cursor-pointer">
              Try again
            </button>
          </div>
        )}
        {loading && <p className="text-sm text-gray-500">{NOTIFICATION_MESSAGES.loading}</p>}

        {prefs && (
          <div role="table" aria-label="Notification preferences">
            {/* Column headings (wide screens; on small screens each switch carries its own label) */}
            <div role="row" className="hidden md:grid md:grid-cols-[1fr_5rem] md:gap-6 pb-3 border-b border-gray-100 text-xs font-semibold uppercase tracking-wide text-gray-500">
              <span role="columnheader">Notification</span>
              <span role="columnheader" className="text-right">Email</span>
            </div>

            <div className="divide-y divide-gray-100">
              {NOTIFICATION_CATEGORIES.map((category) => {
                const row = prefs[category.key];
                return (
                  <div key={category.key} role="row" className="py-4 grid gap-3 md:grid-cols-[1fr_5rem] md:gap-6 md:items-center">
                    <div role="cell" className="min-w-0">
                      <p className="flex flex-wrap items-center gap-2 text-sm font-semibold text-gray-800">
                        {category.title}
                        {row.locked && (
                          <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-md text-xs font-semibold bg-emerald-50 text-emerald-800 border border-emerald-200">
                            <LockIcon className="w-3 h-3" />
                            Required
                          </span>
                        )}
                      </p>
                      <p className="text-sm text-gray-500 mt-0.5">{category.description}</p>
                      {row.locked && <p className="text-xs text-gray-500 mt-1">{NOTIFICATION_MESSAGES.securityRequired}</p>}
                    </div>
                    <div role="cell" className="flex items-center justify-between md:justify-end gap-3">
                      <span className="text-sm font-medium text-gray-600 md:hidden">Email</span>
                      <Switch
                        checked={row.email}
                        disabled={row.locked || saving}
                        label={`${category.title}: email notifications`}
                        title={row.locked ? 'Required' : undefined}
                        onChange={() => toggle(category.key, 'email')}
                      />
                    </div>
                  </div>
                );
              })}
            </div>

            <div className="pt-4 border-t border-gray-100 min-h-[3.25rem] flex items-center">
              {status.phase === 'saving' && (
                <p role="status" aria-live="polite" className="flex items-center gap-2 text-sm font-medium text-gray-600">
                  <Spinner />
                  {status.message}
                </p>
              )}
              {status.phase === 'saved' && (
                <p role="status" aria-live="polite" className="text-sm font-medium text-emerald-800 bg-emerald-50 border border-emerald-200 rounded-lg px-3 py-1.5">
                  {status.message}
                </p>
              )}
              {status.phase === 'error' && (
                <p role="alert" className="text-sm font-medium text-red-700 bg-red-50 border border-red-200 rounded-lg px-3 py-1.5">
                  {status.message}
                </p>
              )}
            </div>
          </div>
        )}
      </div>
    </section>
  );
}
