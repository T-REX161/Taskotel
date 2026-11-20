/**
 * Date and time formatting for the user's chosen formats. Pure: every function takes the format codes, so the result never
 * depends on hidden state (utils/preferences.js supplies the signed-in user's codes to the app's formatters).
 *
 * Date formats: DMY = DD/MM/YYYY, MDY = MM/DD/YYYY, YMD = YYYY-MM-DD. Time formats: H12 = 5:30 PM, H24 = 17:30.
 */
export const DATE_FORMATS = ['DMY', 'MDY', 'YMD'];
export const TIME_FORMATS = ['H12', 'H24'];
export const DEFAULT_DATE_FORMAT = 'DMY';
export const DEFAULT_TIME_FORMAT = 'H12';

const two = (n) => String(n).padStart(2, '0');

/** "02/11/2026", "11/02/2026" or "2026-11-02" for a calendar date (month 1-12). */
export function formatDateParts(year, month, day, dateFormat = DEFAULT_DATE_FORMAT) {
  const y = String(year).padStart(4, '0');
  if (dateFormat === 'MDY') return `${two(month)}/${two(day)}/${y}`;
  if (dateFormat === 'YMD') return `${y}-${two(month)}-${two(day)}`;
  return `${two(day)}/${two(month)}/${y}`;
}

/** "5:30 PM" (12-hour) or "17:30" (24-hour) for an hour 0-23 and a minute 0-59. */
export function formatTimeParts(hour, minute, timeFormat = DEFAULT_TIME_FORMAT) {
  if (timeFormat === 'H24') return `${two(hour)}:${two(minute)}`;
  const hour12 = hour % 12 === 0 ? 12 : hour % 12;
  return `${hour12}:${two(minute)} ${hour < 12 ? 'AM' : 'PM'}`;
}

/** The date, then the time: "02/11/2026, 5:30 PM". */
export function formatDateTimeParts(year, month, day, hour, minute, prefs = {}) {
  return `${formatDateParts(year, month, day, prefs.dateFormat)}, ${formatTimeParts(hour, minute, prefs.timeFormat)}`;
}

/** A JavaScript Date (local time) as a date, in the chosen format. */
export function formatDate(date, dateFormat) {
  return formatDateParts(date.getFullYear(), date.getMonth() + 1, date.getDate(), dateFormat);
}

/** A JavaScript Date (local time) as a time, in the chosen format. */
export function formatTime(date, timeFormat) {
  return formatTimeParts(date.getHours(), date.getMinutes(), timeFormat);
}

/**
 * A timestamp from the server as a JavaScript Date (an instant), or null when it is not usable.
 *
 * The server stores and sends every moment in UTC. A string with a zone marker ("2026-11-20T05:25:00Z" or "+05:30") is exact. A string with
 * no zone ("2026-11-20T05:25:00", or a [year, month, day, hour, minute, second] array) is the server's own UTC reading, so it is read as UTC
 * too and never as the viewer's local time (that misreading is what showed the server's clock on screen instead of the viewer's). Format the
 * returned Date with the local getters (formatDate, formatTime) to show it in the viewer's time zone.
 */
export function parseServerInstant(value) {
  if (value === null || value === undefined || value === '') return null;
  let date;
  if (Array.isArray(value)) {
    if (value.length < 3) return null;
    const [year, month, day, hour = 0, minute = 0, second = 0, nano = 0] = value.map(Number);
    date = new Date(Date.UTC(year, month - 1, day, hour, minute, second, Math.floor(nano / 1e6)));
    const exact = date.getUTCFullYear() === year && date.getUTCMonth() === month - 1 && date.getUTCDate() === day
      && date.getUTCHours() === hour && date.getUTCMinutes() === minute;
    if (!exact) return null;
  } else if (typeof value === 'string') {
    const text = value.trim().replace(' ', 'T');
    if (/^\d{4}-\d{2}-\d{2}$/.test(text)) {
      date = new Date(text + 'T00:00:00Z');
    } else if (/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/.test(text)) {
      const zoned = /(Z|[+-]\d{2}(?::?\d{2})?)$/i.test(text.slice(text.indexOf('T')));
      date = new Date(zoned ? text : text + 'Z');
    } else {
      return null;
    }
  } else {
    return null;
  }
  return Number.isNaN(date.getTime()) ? null : date;
}
