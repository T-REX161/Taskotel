/**
 * Display name rules for the profile page. They mirror the server (which validates again): not empty, at most 50 characters,
 * no control characters or line breaks.
 */
export const DISPLAY_NAME_MAX = 50;

export const DISPLAY_NAME_MESSAGES = {
  empty: 'Display name cannot be empty.',
  tooLong: `Display name must be ${DISPLAY_NAME_MAX} characters or fewer.`,
  invalid: 'Display name cannot contain control characters or line breaks.',
  saved: 'Display name updated.',
  saveFailed: 'Could not update your display name. Please try again.',
};

/** The message to show for `value`, or null when it can be saved. */
export function validateDisplayName(value) {
  const name = String(value ?? '').trim();
  if (!name) return DISPLAY_NAME_MESSAGES.empty;
  if ([...name].length > DISPLAY_NAME_MAX) return DISPLAY_NAME_MESSAGES.tooLong;
  // eslint-disable-next-line no-control-regex
  if (/[\u0000-\u001f\u007f-\u009f]/.test(name)) return DISPLAY_NAME_MESSAGES.invalid;
  return null;
}

/** The saved display name, or `fallback` while the profile has not loaded or has no name (e.g. the username-derived one). */
export function preferredName(profile, fallback) {
  const chosen = profile?.displayName && String(profile.displayName).trim();
  return chosen || fallback;
}

/** The name to show: the saved one, otherwise the username with a capital first letter (the same default the server uses). */
export function shownName(profile, username) {
  const chosen = profile?.displayName && String(profile.displayName).trim();
  if (chosen) return chosen;
  const name = String(username || '').trim();
  return name ? name.charAt(0).toUpperCase() + name.slice(1) : 'Account';
}
