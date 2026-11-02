/**
 * The signed-in user's personal preferences: appearance, date format and time format.
 *
 * The server stores them on the user's own account and sends them with the profile, so they follow the user to every browser
 * and are independent per user. There is no second copy of the state: this module only reads the shared profile cache
 * (utils/currentUserProfile) and, to save, sends the choice to the server and puts the answer back into that same cache.
 * Until a profile has loaded (or for an account that never chose), the defaults apply: light, DD/MM/YYYY, 12-hour.
 */
import { updatePreferences as sendPreferences, getUsername } from '../services/api.js';
import { getCurrentProfileSnapshot, profileFor, setCurrentProfile } from './currentUserProfile.js';
import { DATE_FORMATS, DEFAULT_DATE_FORMAT, DEFAULT_TIME_FORMAT, TIME_FORMATS } from './dateTimeFormat.js';

export const THEMES = ['LIGHT', 'DARK', 'SYSTEM'];
export const DEFAULT_THEME = 'LIGHT';

export const DEFAULT_PREFERENCES = Object.freeze({
  themePreference: DEFAULT_THEME,
  dateFormat: DEFAULT_DATE_FORMAT,
  timeFormat: DEFAULT_TIME_FORMAT,
});

export const PREFERENCE_MESSAGES = {
  loading: 'Loading your preferences…',
  saving: 'Saving…',
  saved: 'Preferences saved.',
  saveFailed: 'Could not save your preference. Please try again.',
};

const pick = (value, allowed, fallback) => (allowed.includes(value) ? value : fallback);

/** Any server value (or nothing) as a complete, valid set of preferences. */
export function normalizePreferences(raw) {
  return {
    themePreference: pick(raw?.themePreference, THEMES, DEFAULT_THEME),
    dateFormat: pick(raw?.dateFormat, DATE_FORMATS, DEFAULT_DATE_FORMAT),
    timeFormat: pick(raw?.timeFormat, TIME_FORMATS, DEFAULT_TIME_FORMAT),
  };
}

/** The preferences of `username` from the shared profile cache; the defaults if that user's profile is not loaded. */
export function preferencesFor(state, username) {
  return normalizePreferences(profileFor(state, username)?.preferences);
}

// Browser storage can be missing or blocked (private mode, tests): then nobody is signed in as far as formatting is concerned
function signedInUsername() {
  try {
    return getUsername();
  } catch {
    return null;
  }
}

/** The signed-in user's preferences right now (synchronous, for the app's formatters). */
export function getPreferences() {
  return preferencesFor(getCurrentProfileSnapshot(), signedInUsername());
}

/** Whether the signed-in user's profile (and so their preferences) has been loaded. */
export function preferencesLoaded() {
  return Boolean(profileFor(getCurrentProfileSnapshot(), signedInUsername()));
}

/** 'light' or 'dark': what the app should look like for a preference, given whether the operating system prefers dark. */
export function resolveTheme(themePreference, systemPrefersDark) {
  if (themePreference === 'DARK') return 'dark';
  if (themePreference === 'SYSTEM') return systemPrefersDark ? 'dark' : 'light';
  return 'light';
}

/**
 * Saves a change to one or more preferences. The server always gets the complete set (current values plus the change), and its
 * answer replaces the shared profile, so every reader (theme, formatters, Settings) updates from one place.
 * Rejects with the server's error (its message is safe to show).
 */
export async function savePreferences(change, { send = sendPreferences, current = getPreferences() } = {}) {
  const next = normalizePreferences({ ...current, ...change });
  const updated = await send(next);
  setCurrentProfile(updated);
  return normalizePreferences(updated?.preferences);
}
