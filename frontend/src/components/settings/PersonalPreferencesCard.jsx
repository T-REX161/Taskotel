import React, { useState } from 'react';
import useCurrentUserProfile from '../../hooks/useCurrentUserProfile';
import { useTheme } from '../../theme/ThemeContext';
import { formatDateTimeParts } from '../../utils/dateTimeFormat';
import { PREFERENCE_MESSAGES, normalizePreferences, savePreferences } from '../../utils/preferences';

export const APPEARANCE_OPTIONS = [
  { value: 'LIGHT', label: 'Light' },
  { value: 'DARK', label: 'Dark' },
  { value: 'SYSTEM', label: 'System' },
];
export const DATE_FORMAT_OPTIONS = [
  { value: 'DMY', label: 'DD/MM/YY' },
  { value: 'MDY', label: 'MM/DD/YY' },
  { value: 'YMD', label: 'YY-MM-DD' },
];
export const TIME_FORMAT_OPTIONS = [
  { value: 'H12', label: '12-hour' },
  { value: 'H24', label: '24-hour' },
];

function Spinner() {
  return (
    <svg className="animate-spin h-4 w-4" fill="none" viewBox="0 0 24 24" aria-hidden="true">
      <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
      <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z" />
    </svg>
  );
}

function SlidersIcon({ className }) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <line x1="4" y1="21" x2="4" y2="14" />
      <line x1="4" y1="10" x2="4" y2="3" />
      <line x1="12" y1="21" x2="12" y2="12" />
      <line x1="12" y1="8" x2="12" y2="3" />
      <line x1="20" y1="21" x2="20" y2="16" />
      <line x1="20" y1="12" x2="20" y2="3" />
      <line x1="1" y1="14" x2="7" y2="14" />
      <line x1="9" y1="8" x2="15" y2="8" />
      <line x1="17" y1="16" x2="23" y2="16" />
    </svg>
  );
}

/** A segmented control built from native radio buttons, so arrow keys, focus and screen readers work as they do for any radio group. */
function Segmented({ name, label, options, value, disabled, onChange, compact = false }) {
  // compact: a fixed 290px control (segments sized to their text, tighter padding) for the three date labels, which stay on one line
  return (
    <div role="radiogroup" aria-label={label} className={`inline-flex w-full rounded-xl border border-gray-200 bg-gray-50 p-1 gap-1 ${compact ? 'sm:w-[290px]' : 'sm:w-auto'}`}>
      {options.map((option) => (
        <label key={option.value} className={`relative ${compact ? 'flex-auto' : 'flex-1 sm:flex-none'}${disabled ? 'cursor-not-allowed opacity-60' : 'cursor-pointer'}`}>
          <input
            type="radio"
            name={name}
            value={option.value}
            checked={value === option.value}
            disabled={disabled}
            onChange={(event) => onChange(option.value, event)}
            className="peer sr-only"
          />
          <span className={`block text-center whitespace-nowrap ${compact ? 'px-0.5 min-[380px]:px-1 text-xs min-[380px]:text-[13px] sm:text-sm' : 'px-4 text-sm'} py-2 rounded-lg font-semibold text-gray-600 transition-colors peer-checked:bg-emerald-600 peer-checked:text-white peer-checked:shadow-sm peer-focus-visible:ring-2 peer-focus-visible:ring-emerald-500 peer-focus-visible:ring-offset-2 hover:text-gray-900 peer-checked:hover:text-white`}>
            {option.label}
          </span>
        </label>
      ))}
    </div>
  );
}

function PreferenceRow({ title, hint, children }) {
  return (
    <div className="flex flex-col md:flex-row md:items-center md:justify-between gap-3 md:gap-8 min-w-0">
      <div className="min-w-0">
        <p className="text-sm font-semibold text-gray-700">{title}</p>
        <p className="text-sm text-gray-500 mt-0.5">{hint}</p>
      </div>
      {children}
    </div>
  );
}

/**
 * Settings > Personal Preferences: appearance, date format and time format of the signed-in user.
 * Each choice is applied at once and saved to the user's own account (no Save button). While the profile loads the controls are
 * disabled; while one change is saved the controls are locked so choices cannot cross; a failed save puts the previous choice back.
 */
export default function PersonalPreferencesCard() {
  const { profile } = useCurrentUserProfile();
  const { setPreference } = useTheme();
  const [status, setStatus] = useState({ phase: 'idle', message: '' }); // idle | saving | saved | error

  const loaded = Boolean(profile);
  const saving = status.phase === 'saving';
  const prefs = normalizePreferences(profile?.preferences);
  const locked = !loaded || saving;

  const change = async (field, value, event) => {
    if (locked || prefs[field] === value) return;
    const isTheme = field === 'themePreference';
    const previous = prefs[field];
    setStatus({ phase: 'saving', message: PREFERENCE_MESSAGES.saving });
    if (isTheme) {
      // The theme changes on screen immediately, growing from the chosen control; it is saved below with this card's own states
      const box = event?.target?.closest('label')?.getBoundingClientRect?.();
      setPreference(value, box ? { x: box.left + box.width / 2, y: box.top + box.height / 2 } : null, { persist: false });
    }
    try {
      await savePreferences({ [field]: value }, { current: prefs });
      setStatus({ phase: 'saved', message: PREFERENCE_MESSAGES.saved });
    } catch (error) {
      if (isTheme) setPreference(previous, null, { persist: false });
      setStatus({ phase: 'error', message: error?.status === 400 && error.message ? error.message : PREFERENCE_MESSAGES.saveFailed });
    }
  };

  return (
    <section className="bg-white rounded-2xl border border-gray-200 shadow-sm overflow-hidden" aria-labelledby="personal-preferences-title">
      <div className="flex items-center gap-4 px-6 sm:px-8 py-6 border-b border-gray-100">
        <SlidersIcon className="w-7 h-7 text-gray-500 shrink-0" />
        <div className="min-w-0">
          <h2 id="personal-preferences-title" className="text-lg font-bold text-gray-900">Personal Preferences</h2>
          <p className="text-sm text-gray-500 mt-0.5">How Taskotel looks and how dates and times are shown, just for you.</p>
        </div>
      </div>

      <div className="px-6 sm:px-8 py-7 space-y-7" aria-busy={saving || !loaded}>
        <PreferenceRow title="Appearance" hint="System follows your device's light or dark setting.">
          <Segmented name="appearance" label="Appearance" options={APPEARANCE_OPTIONS} value={prefs.themePreference} disabled={locked}
            onChange={(value, event) => change('themePreference', value, event)} />
        </PreferenceRow>

        <PreferenceRow title="Date Format" hint="Used wherever Taskotel shows a date.">
          <Segmented name="date-format" label="Date format" options={DATE_FORMAT_OPTIONS} value={prefs.dateFormat} disabled={locked} compact
            onChange={(value, event) => change('dateFormat', value, event)} />
        </PreferenceRow>

        <PreferenceRow title="Time Format" hint="Used wherever Taskotel shows a time.">
          <Segmented name="time-format" label="Time format" options={TIME_FORMAT_OPTIONS} value={prefs.timeFormat} disabled={locked}
            onChange={(value, event) => change('timeFormat', value, event)} />
        </PreferenceRow>

        <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3 pt-5 border-t border-gray-100">
          <p className="text-sm text-gray-500">
            Example: <span className="font-semibold text-gray-800">{formatDateTimeParts(2026, 11, 2, 17, 30, prefs)}</span>
          </p>
          <div className="min-h-[2.25rem] flex items-center">
            {!loaded && <p className="text-sm text-gray-500">{PREFERENCE_MESSAGES.loading}</p>}
            {loaded && status.phase === 'saving' && (
              <p role="status" aria-live="polite" className="flex items-center gap-2 text-sm font-medium text-gray-600">
                <Spinner />
                {status.message}
              </p>
            )}
            {loaded && status.phase === 'saved' && (
              <p role="status" aria-live="polite" className="text-sm font-medium text-emerald-800 bg-emerald-50 border border-emerald-200 rounded-lg px-3 py-1.5">
                {status.message}
              </p>
            )}
            {loaded && status.phase === 'error' && (
              <p role="alert" className="text-sm font-medium text-red-700 bg-red-50 border border-red-200 rounded-lg px-3 py-1.5">
                {status.message}
              </p>
            )}
          </div>
        </div>
      </div>
    </section>
  );
}
