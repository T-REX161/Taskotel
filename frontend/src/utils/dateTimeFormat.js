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
