/**
 * The Dashboard's live clock: what it shows and how it ticks. Pure and injectable, so it can be tested without a browser.
 * It uses the browser's own local time zone (nothing is hardcoded) and the shared date/time formatters, so it follows the
 * signed-in user's date and time preferences like every other date in the app.
 */
import { formatDate, formatTime } from './dateTimeFormat.js';

/**
 * How often a clock that has to run its own timer (startClock without the shared heartbeat) looks at the system time. It only reports a
 * change when the second or minute really changed. The page itself uses the shared heartbeat, which is aimed at the second boundaries.
 */
export const CLOCK_TICK_MS = 250;

const WEEKDAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];

const isValidDate = (date) => date instanceof Date && !Number.isNaN(date.getTime());

/** The calendar minute or second a Date falls in; two Dates with the same key show the same text. */
const unitKey = (date, unitMs) => Math.floor(date.getTime() / unitMs);

/** { date: "Mon, 02/11/2026", time: "3:01 PM" } for a Date and the user's preferences (hours and minutes); empty strings for an invalid Date. */
export function clockParts(date, prefs = {}) {
  if (!isValidDate(date)) return { date: '', time: '' };
  return {
    date: `${WEEKDAYS[date.getDay()]}, ${formatDate(date, prefs.dateFormat)}`,
    time: formatTime(date, prefs.timeFormat),
  };
}

/** The seconds of a Date as two digits ("07"), for the small seconds beside the time; empty for an invalid Date. */
export function secondsPart(date) {
  return isValidDate(date) ? String(date.getSeconds()).padStart(2, '0') : '';
}

/** The beat fires this long after a second boundary, so the clock reads the new second rather than the end of the old one. */
export const BOUNDARY_SLACK_MS = 10;

/**
 * One shared heartbeat for everything on the page that follows the clock. It runs a single timer, started when the first listener
 * subscribes and stopped when the last one leaves, so however many parts of the clock there are, exactly one timer runs and none is left
 * behind.
 *
 * The timer is aimed at the next real second boundary of the system clock (each wait is worked out from the time it is set, not counted
 * off), so a listener is called with 'tick' just after every actual second starts, however late or early earlier timers ran: it cannot
 * drift. And a listener is called with 'resync' straight away, with the timer re-aimed, whenever the page comes back (the tab becomes
 * visible, the window regains focus, or the page is restored from the back/forward cache). That is what keeps a clock honest: browsers
 * throttle timers in background tabs (down to one wake-up a minute after a few minutes hidden), so a clock that only trusts its timer is
 * stale, or looks frozen, until the next throttled wake-up; the resync shows the right time the moment the page returns.
 * The clock, timer functions and event targets are injectable for tests.
 */
export function createTicker({
  now = () => Date.now(),
  setTimeoutFn = (...args) => setTimeout(...args),
  clearTimeoutFn = (...args) => clearTimeout(...args),
  doc = typeof document !== 'undefined' ? document : null,
  win = typeof window !== 'undefined' ? window : null,
} = {}) {
  const listeners = new Set();
  let timeoutId = null;
  let running = false;
  let generation = 0; // identifies the one current timer; a callback from any earlier one is ignored

  const fire = (reason) => {
    [...listeners].forEach((listener) => listener(reason));
  };
  const clear = () => {
    generation += 1; // whatever is still pending (or already queued to run) no longer counts
    if (timeoutId !== null) clearTimeoutFn(timeoutId);
    timeoutId = null;
  };
  const schedule = () => {
    clear(); // never two timers
    const wait = 1000 - (now() % 1000) + BOUNDARY_SLACK_MS;
    const mine = generation;
    timeoutId = setTimeoutFn(() => {
      if (mine !== generation) return; // a stale callback (one that was cleared but still ran) must not start a second chain
      timeoutId = null;
      fire('tick');
      if (running) schedule();
    }, wait);
  };
  const resync = () => {
    if (!running) return;
    fire('resync');
    if (running) schedule(); // re-aim at the next second boundary from the time it is now
  };
  const onVisibility = () => {
    if (!doc || !doc.hidden) resync();
  };

  const start = () => {
    running = true;
    schedule();
    doc?.addEventListener?.('visibilitychange', onVisibility);
    win?.addEventListener?.('focus', resync);
    win?.addEventListener?.('pageshow', resync);
  };
  const stop = () => {
    running = false;
    clear();
    doc?.removeEventListener?.('visibilitychange', onVisibility);
    win?.removeEventListener?.('focus', resync);
    win?.removeEventListener?.('pageshow', resync);
  };

  return {
    subscribe(listener) {
      listeners.add(listener);
      if (listeners.size === 1) start();
      return () => {
        if (!listeners.delete(listener)) return;
        if (listeners.size === 0) stop();
      };
    },
    /** How many listeners there are, and whether the one timer is pending, right now (for tests). */
    stats: () => ({ listeners: listeners.size, running: timeoutId !== null }),
  };
}

/** The clock the whole page shares. */
export const sharedTicker = createTicker();

/**
 * Calls onTick(new Date()) when what is shown has changed (the caller already has the current time, so there is no call at the start),
 * and returns a function that stops it. By default that is the minute; with granularity 'second' it is every new second. It listens to
 * the shared heartbeat when one is given (so no extra timer is created and the page-return resync applies), otherwise it runs a timer
 * of its own. The time always comes from the system clock at the moment of the check, never from counting ticks. The timer functions
 * are injectable for tests.
 */
export function startClock(onTick, {
  subscribe = null,
  granularity = 'minute',
  tickMs = CLOCK_TICK_MS,
  now = () => new Date(),
  setIntervalFn = setInterval,
  clearIntervalFn = clearInterval,
} = {}) {
  const unitMs = granularity === 'second' ? 1000 : 60_000;
  let shown = unitKey(now(), unitMs);
  const check = () => {
    const current = now();
    const key = unitKey(current, unitMs);
    if (key === shown) return;
    shown = key;
    onTick(current);
  };
  if (subscribe) return subscribe(check);
  const id = setIntervalFn(check, tickMs);
  return () => clearIntervalFn(id);
}

/** "10:10 AM" -> { main: "10:10", meridiem: "AM" }; "15:01" -> { main: "15:01", meridiem: "" }. The time itself comes from clockParts. */
export function splitTime(time) {
  const match = /^(.*?)\s*([AP]M)?$/.exec(String(time ?? '').trim());
  return { main: match ? match[1] : '', meridiem: match && match[2] ? match[2] : '' };
}
