/**
 * The Dashboard's live clock: what it shows and how it ticks. Pure and injectable, so it can be tested without a browser.
 * It uses the browser's own local time zone (nothing is hardcoded) and the shared date/time formatters, so it follows the
 * signed-in user's date and time preferences like every other date in the app. Minutes only: seconds are never shown.
 */
import { formatDate, formatTime } from './dateTimeFormat.js';

export const CLOCK_INTERVAL_MS = 60_000;

const WEEKDAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];

const isValidDate = (date) => date instanceof Date && !Number.isNaN(date.getTime());

/** { date: "Mon, 02/11/2026", time: "3:01 PM" } for a Date and the user's preferences; empty strings for a missing or invalid Date. */
export function clockParts(date, prefs = {}) {
  if (!isValidDate(date)) return { date: '', time: '' };
  return {
    date: `${WEEKDAYS[date.getDay()]}, ${formatDate(date, prefs.dateFormat)}`,
    time: formatTime(date, prefs.timeFormat),
  };
}

/**
 * Calls onTick(new Date()) once a minute (not immediately: the caller already has the current time) and returns a function that
 * stops it. The first tick waits for the next minute boundary, so the shown minute is never more than a moment behind; after that
 * it repeats every 60 seconds. The timer functions are injectable for tests.
 */
export function startClock(onTick, {
  intervalMs = CLOCK_INTERVAL_MS,
  now = () => new Date(),
  setTimeoutFn = setTimeout,
  clearTimeoutFn = clearTimeout,
  setIntervalFn = setInterval,
  clearIntervalFn = clearInterval,
} = {}) {
  let intervalId = null;
  const current = now();
  const untilNextMinute = intervalMs - ((current.getSeconds() * 1000 + current.getMilliseconds()) % intervalMs);
  const timeoutId = setTimeoutFn(() => {
    onTick(now());
    intervalId = setIntervalFn(() => onTick(now()), intervalMs);
  }, untilNextMinute);
  return () => {
    clearTimeoutFn(timeoutId);
    if (intervalId !== null) clearIntervalFn(intervalId);
  };
}
