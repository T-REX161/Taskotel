/**
 * Account Activity timestamps. The server stores and sends every moment in UTC ("2026-11-20T05:25:00Z"); each one is shown in the
 * viewer's own time zone (the browser's, Asia/Kolkata for a user in India), written in the user's chosen date and time formats. A
 * timestamp without a zone is read as the server's UTC, never as local time. A missing value is never replaced by a made-up date.
 */
import { formatDateTimeParts, parseServerInstant } from './dateTimeFormat.js';
import { getPreferences } from './preferences.js';

/** "20/11/2026, 10:55 AM" (or the user's other formats) in the viewer's time zone, or null when there is no usable value. */
export function formatActivityTime(value, prefs = getPreferences()) {
  const date = parseServerInstant(value);
  if (!date) return null;
  return formatDateTimeParts(date.getFullYear(), date.getMonth() + 1, date.getDate(), date.getHours(), date.getMinutes(), prefs);
}

/**
 * What the card shows for one timestamp: the formatted time, or why there is none.
 * `profile` is null while it loads; `missing` is the wording for a value the server has not recorded.
 */
export function describeActivity(profile, field, missing = 'Not recorded yet') {
  if (!profile) return { text: 'Loading…', known: false };
  const text = formatActivityTime(profile[field]);
  return text ? { text, known: true } : { text: missing, known: false };
}
