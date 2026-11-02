/**
 * Account Activity timestamps. The server sends its own local date-time without a zone
 * ("2026-11-02T10:27:51.123", or [y, m, d, h, mi, s] arrays), and it is shown exactly as sent: never shifted to another time zone,
 * so every timestamp reads the same way. It is written in the user's chosen date and time formats. A missing value is never
 * replaced by a made-up date.
 */
import { formatDateTimeParts } from './dateTimeFormat.js';
import { getPreferences } from './preferences.js';

function parts(value) {
  if (Array.isArray(value) && value.length >= 3) {
    const [year, month, day, hour = 0, minute = 0] = value.map(Number);
    return { year, month, day, hour, minute };
  }
  const match = String(value ?? '').match(/^(\d{4})-(\d{2})-(\d{2})(?:[T ](\d{2}):(\d{2}))?/);
  if (!match) return null;
  const [, year, month, day, hour = '0', minute = '0'] = match;
  return { year: +year, month: +month, day: +day, hour: +hour, minute: +minute };
}

/** "02/11/2026, 10:27 AM" (or the user's other formats), or null when there is no usable value. */
export function formatActivityTime(value, prefs = getPreferences()) {
  if (value === null || value === undefined || value === '') return null;
  const p = parts(value);
  if (!p || p.month < 1 || p.month > 12 || p.day < 1 || p.day > 31 || p.hour > 23 || p.minute > 59) return null;
  return formatDateTimeParts(p.year, p.month, p.day, p.hour, p.minute, prefs);
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
