import { describe, it, beforeEach, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { BOUNDARY_SLACK_MS, CLOCK_TICK_MS, clockParts, createTicker, secondsPart, sharedTicker, splitTime, startClock } from '../utils/liveClock.js';
import { formatTime } from '../utils/dateTimeFormat.js';
import { getPreferences } from '../utils/preferences.js';
import { resetCurrentProfile, setCurrentProfile } from '../utils/currentUserProfile.js';
import { formatAssignedAt } from '../utils/assignmentUtils.js';

const here = path.dirname(fileURLToPath(import.meta.url));
const read = (...parts) => fs.readFileSync(path.join(here, ...parts), 'utf8');

const realStorage = Object.getOwnPropertyDescriptor(globalThis, 'localStorage');
beforeEach(() => {
  const store = { username: 'maria', token: 'x' };
  Object.defineProperty(globalThis, 'localStorage', {
    configurable: true, writable: true,
    value: { getItem: (k) => (k in store ? store[k] : null), setItem: (k, v) => { store[k] = String(v); }, removeItem: (k) => { delete store[k]; } },
  });
  resetCurrentProfile();
});
afterEach(() => {
  resetCurrentProfile();
  if (realStorage) Object.defineProperty(globalThis, 'localStorage', realStorage); else delete globalThis.localStorage;
});

const afternoon = new Date(2026, 10, 2, 15, 1, 7); // 2 Nov 2026, 15:01:07 local time (the seconds must never show)
const SECONDS_PATTERN = /\d{1,2}:\d{2}:\d{2}/;

describe('clock text', () => {
  it('12-hour: 3:01 PM, with AM/PM and no seconds', () => {
    assert.deepEqual(clockParts(afternoon, { dateFormat: 'DMY', timeFormat: 'H12' }), { date: 'Mon, 02/11/2026', time: '3:01 PM' });
  });

  it('24-hour: 15:01, with no AM/PM and no seconds', () => {
    const { time } = clockParts(afternoon, { dateFormat: 'DMY', timeFormat: 'H24' });
    assert.equal(time, '15:01');
    assert.ok(!/AM|PM/.test(time));
  });

  it('all three date formats are preserved', () => {
    assert.equal(clockParts(afternoon, { dateFormat: 'DMY', timeFormat: 'H24' }).date, 'Mon, 02/11/2026');
    assert.equal(clockParts(afternoon, { dateFormat: 'MDY', timeFormat: 'H24' }).date, 'Mon, 11/02/2026');
    assert.equal(clockParts(afternoon, { dateFormat: 'YMD', timeFormat: 'H24' }).date, 'Mon, 2026-11-02');
  });

  it('edge hours: midnight is 12:00 AM / 00:00, noon is 12:00 PM / 12:00', () => {
    const midnight = new Date(2026, 0, 1, 0, 0, 0);
    const noon = new Date(2026, 0, 1, 12, 0, 0);
    assert.equal(clockParts(midnight, { timeFormat: 'H12' }).time, '12:00 AM');
    assert.equal(clockParts(midnight, { timeFormat: 'H24' }).time, '00:00');
    assert.equal(clockParts(noon, { timeFormat: 'H12' }).time, '12:00 PM');
    assert.equal(clockParts(noon, { timeFormat: 'H24' }).time, '12:00');
  });

  it('defaults to 12-hour and DD/MM/YYYY when no preference is given', () => {
    assert.deepEqual(clockParts(afternoon), { date: 'Mon, 02/11/2026', time: '3:01 PM' });
  });

  it('seconds are never displayed, whatever the second is, in either format and any date format', () => {
    for (let second = 0; second < 60; second += 7) {
      const d = new Date(2026, 10, 2, 15, 1, second);
      for (const timeFormat of ['H12', 'H24']) {
        for (const dateFormat of ['DMY', 'MDY', 'YMD']) {
          const parts = clockParts(d, { timeFormat, dateFormat });
          assert.ok(!SECONDS_PATTERN.test(`${parts.date} ${parts.time}`), `${timeFormat}/${dateFormat}: ${parts.time}`);
        }
      }
      assert.equal(clockParts(d, { timeFormat: 'H12' }).time, '3:01 PM');
      assert.equal(clockParts(d, { timeFormat: 'H24' }).time, '15:01');
    }
  });

  it('it is exactly the shared time formatter, so the whole app agrees', () => {
    assert.equal(formatTime(afternoon, 'H12'), '3:01 PM');
    assert.equal(formatTime(afternoon, 'H24'), '15:01');
    for (const timeFormat of ['H12', 'H24']) assert.equal(clockParts(afternoon, { timeFormat }).time, formatTime(afternoon, timeFormat));
  });

  it('a missing or invalid date gives empty text, never NaN or an error', () => {
    for (const bad of [null, undefined, new Date('nope'), '2026-11-02', 12345]) {
      assert.deepEqual(clockParts(bad, { dateFormat: 'DMY', timeFormat: 'H12' }), { date: '', time: '' });
    }
  });

  it('uses the local time of the Date it is given: no time zone is applied or assumed', () => {
    const local = new Date(2026, 5, 15, 23, 59, 59);
    assert.equal(clockParts(local, { timeFormat: 'H24' }).time, '23:59');
    assert.ok(clockParts(local, { timeFormat: 'H24' }).date.endsWith('15/06/2026'));
  });
});

describe('preference changes', () => {
  it('the signed-in user\'s saved preferences decide the clock, and a change shows at once', () => {
    setCurrentProfile({ username: 'maria', preferences: { themePreference: 'LIGHT', dateFormat: 'DMY', timeFormat: 'H12' } });
    assert.equal(clockParts(afternoon, getPreferences()).time, '3:01 PM');
    setCurrentProfile({ username: 'maria', preferences: { themePreference: 'LIGHT', dateFormat: 'YMD', timeFormat: 'H24' } });
    assert.deepEqual(clockParts(afternoon, getPreferences()), { date: 'Mon, 2026-11-02', time: '15:01' });
    setCurrentProfile({ username: 'maria', preferences: { themePreference: 'LIGHT', dateFormat: 'MDY', timeFormat: 'H12' } });
    assert.deepEqual(clockParts(afternoon, getPreferences()), { date: 'Mon, 11/02/2026', time: '3:01 PM' });
  });

  it('before any profile is loaded the defaults apply', () => {
    assert.deepEqual(clockParts(afternoon, getPreferences()), { date: 'Mon, 02/11/2026', time: '3:01 PM' });
  });
});

describe('the ticking: one timer, shown once a minute by default', () => {
  /** A fake clock and interval timer: advance(ms) moves time forward and fires whatever is due. */
  function fakeTimers(start) {
    let time = start.getTime();
    let nextId = 1;
    const timers = new Map(); // id -> { fn, at, every }
    return {
      now: () => new Date(time),
      setIntervalFn: (fn, ms) => { timers.set(nextId, { fn, at: time + ms, every: ms }); return nextId++; },
      clearIntervalFn: (id) => timers.delete(id),
      size: () => timers.size,
      advance(ms) {
        const end = time + ms;
        for (;;) {
          const due = [...timers.entries()].filter(([, t]) => t.at <= end).sort((x, y) => x[1].at - y[1].at)[0];
          if (!due) break;
          const [, t] = due;
          time = t.at;
          t.at += t.every;
          t.fn();
        }
        time = end;
      },
    };
  }

  it('checks the time several times a second with exactly one timer', () => {
    assert.equal(CLOCK_TICK_MS, 250);
    const t = fakeTimers(new Date(2026, 10, 2, 15, 1, 7));
    const stop = startClock(() => {}, t);
    assert.equal(t.size(), 1, 'a single running interval');
    stop();
  });

  it('shows the new minute within a second of the boundary, and never in between (seconds are never shown)', () => {
    const t = fakeTimers(new Date(2026, 10, 2, 15, 1, 7));
    const seen = [];
    const stop = startClock((d) => seen.push(clockParts(d, { timeFormat: 'H12' }).time), t);
    t.advance(30_000);
    assert.deepEqual(seen, [], 'nothing before the minute changes (15:01:37)');
    t.advance(23_000); // 15:02:00
    assert.deepEqual(seen, ['3:02 PM']);
    t.advance(59_000);
    assert.deepEqual(seen, ['3:02 PM'], 'sixty checks later, still no new render');
    t.advance(1_000); // 15:03:00
    t.advance(60_000); // 15:04:00
    assert.deepEqual(seen, ['3:02 PM', '3:03 PM', '3:04 PM']);
    stop();
  });

  it('over ten minutes it renders about ten times, not hundreds, and always exactly on a new minute', () => {
    const t = fakeTimers(new Date(2026, 10, 2, 23, 58, 41));
    const stamps = [];
    startClock((d) => stamps.push(d), t);
    t.advance(10 * 60_000);
    assert.ok(stamps.length >= 9 && stamps.length <= 11, `rendered ${stamps.length} times`);
    for (const d of stamps) assert.equal(d.getSeconds(), 0, 'each render lands on the minute');
    for (let i = 1; i < stamps.length; i += 1) assert.equal(stamps[i] - stamps[i - 1], 60_000);
    assert.equal(clockParts(stamps[0], { timeFormat: 'H24' }).time, '23:59');
    assert.equal(clockParts(stamps[1], { timeFormat: 'H24' }).time, '00:00');
  });

  it('catches up after the computer sleeps: the first check after waking shows the right minute', () => {
    const timers = fakeTimers(new Date(2026, 10, 2, 15, 1, 7));
    let wall = new Date(2026, 10, 2, 15, 1, 7); // what the "wall clock" says; it can jump while the timers do not run
    const got = [];
    const stop = startClock((d) => got.push(clockParts(d, { timeFormat: 'H24' }).time), { ...timers, now: () => wall });
    wall = new Date(2026, 10, 2, 17, 30, 5); // the computer slept for two and a half hours
    timers.advance(1000); // the next one-second check after waking
    assert.deepEqual(got, ['17:30']);
    stop();
  });

  it('stopping (the cleanup on unmount) clears the interval, and nothing ticks afterwards', () => {
    let ticks = 0;
    const t = fakeTimers(new Date(2026, 10, 2, 15, 1, 7));
    const stop = startClock(() => { ticks += 1; }, t);
    t.advance(2 * 60_000);
    const before = ticks;
    assert.ok(before >= 2);
    stop();
    assert.equal(t.size(), 0, 'the interval is cleared');
    t.advance(10 * 60_000);
    assert.equal(ticks, before, 'no ticks after stop');
  });

  it('works with the real timers too: it starts and stops cleanly', () => {
    const stop = startClock(() => {});
    stop();
  });
});

describe('Dashboard wiring', () => {
  const clock = read('..', 'components', 'dashboard', 'LiveClock.jsx');
  const dashboard = read('Dashboard.jsx');

  it('the clock sits in the Dashboard header, where the date was, with no container of its own', () => {
    const header = dashboard.slice(dashboard.indexOf('{/* Header */}'), dashboard.indexOf('{/* KPI cards */}'));
    assert.ok(header.includes('<LiveClock />') && header.includes('Welcome back, {welcomeName}!'));
    const body = clock.slice(clock.lastIndexOf('return ('));
    assert.equal((body.match(/<time\b/g) || []).length, 1, 'one element, no extra containers');
    assert.ok(!/\bborder|\bbg-|shadow|rounded|\bpx-|\bpy-|gradient|glow/.test(body), 'no background, border, rounded box, padding, shadow or glow');
    assert.ok(body.includes('inline-flex max-w-full flex-col items-start gap-1 tabular-nums'), 'two rows that start at the same left edge; steady digits, never wider than the header');
  });

  it('it re-renders when preferences change, follows the shared heartbeat every second, and cleans up on unmount', () => {
    assert.ok(clock.includes('useCurrentUserProfile()') && clock.includes('getPreferences()'));
    assert.ok(clock.includes("return startClock(setNow, { subscribe: sharedTicker.subscribe, granularity: 'second' });"), 'the effect returns the stop function');
    assert.ok(!clock.includes('setInterval') && !clock.includes('setTimeout'), 'no timer of its own');
  });

  it('has two rows: the time (hours and minutes prominent, seconds smaller and muted, small AM/PM), then a small calendar icon and the date', () => {
    const body = clock.slice(clock.lastIndexOf('return ('));
    const order = ['{main}', '{secondsPart(now)}', '{meridiem}', '<CalendarIcon', '{date}'].map((t) => body.indexOf(t));
    assert.ok(order.every((p) => p > 0) && [...order].sort((a, b) => a - b).join() === order.join(), order.join());
    assert.ok(body.includes('text-2xl font-semibold') && body.includes('text-sm font-medium text-gray-500" aria-hidden="true">:{secondsPart(now)}') && body.includes('text-xs font-semibold text-gray-500'), 'hours and minutes prominent, seconds smaller and muted, AM/PM smaller');
    assert.ok(body.includes('text-xs font-medium leading-none text-gray-500') && body.includes('<CalendarIcon className="w-3.5 h-3.5 text-gray-400" />'), 'a small calendar icon and the date');
    assert.ok(!/h-4 w-px|clock-live-dot|AnalogClockIcon/.test(clock), 'no divider, status dot or separate analogue clock component');
  });

  it('has no clock icon at all: the calendar icon is the only icon, and the two rows share one left edge by plain flex-column alignment', () => {
    const body = clock.slice(clock.lastIndexOf('return ('));
    assert.ok(!/ClockIcon|data-hand|data-pivot|<circle|<line|rotate\(|handAngles|angles=/.test(clock), 'no clock icon, hands or hand angles anywhere in the component');
    assert.equal((clock.match(/<svg/g) || []).length, 1, 'one svg in the whole file: the shared icon wrapper');
    assert.equal((clock.match(/<CalendarIcon/g) || []).length, 1, 'used once: the calendar icon');
    assert.ok(body.includes('flex-col items-start') && !/grid|col-start|col-span|row-start|row-span|absolute|\bmt-\[|-m[trbl]-/.test(body.replace('sm:mt-1', '')), 'aligned by flex alone: no grid columns left over, no offsets or absolute positioning');
    assert.ok(!read('..', 'utils', 'liveClock.js').includes('handAngles'), 'the analogue-only helper is gone with the icon');
    assert.ok(!fs.existsSync(path.join(here, '..', 'components', 'dashboard', 'AnalogClockIcon.jsx')));
  });

  it('the seconds come right after the minutes and are hidden from screen readers; the screen reader hears the time once', () => {
    const body = clock.slice(clock.lastIndexOf('return ('));
    assert.ok(body.includes('aria-hidden="true">:{secondsPart(now)}</span>'));
    assert.ok(body.includes('<span className="sr-only">{time}</span>') && !/sr-only">[^<]*secondsPart/.test(body));
    assert.ok(!/:\d\d:\d\d/.test(clockParts(afternoon, { timeFormat: 'H24' }).time) && !SECONDS_PATTERN.test(clockParts(afternoon, { timeFormat: 'H12' }).time), 'clockParts itself stays hours and minutes');
  });

  it('has no animation of its own: no status dot, glow or keyframes are left behind', () => {
    const css = read('..', 'index.css');
    assert.ok(!css.includes('clock-live-dot') && !css.includes('clockPulse') && !css.includes('clockDigit') && !css.includes('clock-glow'));
    assert.ok(!/animate-|requestAnimationFrame|animation:|@keyframes|transition/.test(clock), 'no CSS animation or frame loop in the component');
  });



  it('no time zone is anywhere in the clock code', () => {
    for (const file of [clock, read('..', 'utils', 'liveClock.js'), read('..', 'utils', 'dateTimeFormat.js')]) {
      for (const forbidden of ['Hyderabad', 'Asia/', 'Kolkata', 'timeZone', 'getTimezoneOffset', 'toLocale']) {
        assert.ok(!file.includes(forbidden), forbidden);
      }
    }
  });

  it('the Dashboard keeps its other sections', () => {
    for (const part of ['<SummaryCards', '<WorkloadOverview', '<TaskStatusSummary', '<OperationsInsights', '<AssignmentsOverview']) assert.ok(dashboard.includes(part), part);
  });
});

describe('existing timestamps still follow the preferences, and missing ones are handled', () => {
  it('assignment timestamps use the shared formatters and a missing one is a dash', () => {
    setCurrentProfile({ username: 'maria', preferences: { themePreference: 'LIGHT', dateFormat: 'DMY', timeFormat: 'H24' } });
    assert.equal(formatAssignedAt(null), '-');
    assert.equal(formatAssignedAt(undefined), '-');
    const text = formatAssignedAt('2026-11-02T14:45:00');
    assert.ok(/\d{2}:\d{2}/.test(text) && !/[AP]M/.test(text), text);
  });
});

describe('splitting the digital time for display', () => {
  it('separates AM/PM from the digits, and leaves 24-hour times whole', () => {
    assert.deepEqual(splitTime('10:10 AM'), { main: '10:10', meridiem: 'AM' });
    assert.deepEqual(splitTime('3:01 PM'), { main: '3:01', meridiem: 'PM' });
    assert.deepEqual(splitTime('15:01'), { main: '15:01', meridiem: '' });
    assert.deepEqual(splitTime('00:00'), { main: '00:00', meridiem: '' });
    assert.deepEqual(splitTime(''), { main: '', meridiem: '' });
    assert.deepEqual(splitTime(undefined), { main: '', meridiem: '' });
  });

  it('together with clockParts it follows the saved time format', () => {
    assert.deepEqual(splitTime(clockParts(afternoon, { timeFormat: 'H12' }).time), { main: '3:01', meridiem: 'PM' });
    assert.deepEqual(splitTime(clockParts(afternoon, { timeFormat: 'H24' }).time), { main: '15:01', meridiem: '' });
  });
});

/** Fake clock, timer and page events, so second boundaries, late timers, throttling and page returns can be driven by hand. */
function fakeEnv(start) {
  const st = { wall: start.getTime(), pending: null, waits: [], nextId: 0, doc: {}, win: {} };
  const target = (name) => ({
    addEventListener: (type, fn) => { st[name][type] = fn; },
    removeEventListener: (type, fn) => { if (st[name][type] === fn) delete st[name][type]; },
  });
  st.documentLike = { hidden: false, ...target('doc') };
  st.windowLike = target('win');
  st.now = () => st.wall;
  st.setTimeoutFn = (fn, ms) => { st.nextId += 1; st.pending = { fn, id: st.nextId }; st.waits.push(ms); return st.nextId; };
  st.clearTimeoutFn = (id) => { if (st.pending && st.pending.id === id) st.pending = null; };
  /** Moves the wall clock by lateMs past the wait that was asked for, then runs the pending timeout. */
  st.fire = (lateMs = 0) => {
    const timer = st.pending;
    st.pending = null;
    st.wall += st.waits[st.waits.length - 1] + lateMs;
    timer.fn();
  };
  st.timers = () => (st.pending ? 1 : 0);
  st.options = () => ({ now: st.now, setTimeoutFn: st.setTimeoutFn, clearTimeoutFn: st.clearTimeoutFn, doc: st.documentLike, win: st.windowLike });
  return st;
}
const at = (h, m, sec, ms = 0) => new Date(2026, 10, 3, h, m, sec, ms);

describe('the shared heartbeat', () => {
  it('runs exactly one timer however many listeners there are, and none when there are none', () => {
    const env = fakeEnv(at(16, 8, 16));
    const ticker = createTicker(env.options());
    assert.equal(env.timers(), 0);
    const stops = [ticker.subscribe(() => {}), ticker.subscribe(() => {}), ticker.subscribe(() => {})];
    assert.equal(env.timers(), 1, 'one timer for three listeners');
    assert.deepEqual(ticker.stats(), { listeners: 3, running: true });
    stops[0](); stops[1]();
    assert.equal(env.timers(), 1, 'still running for the last listener');
    stops[2]();
    assert.equal(env.timers(), 0, 'the timer is cleared with the last listener');
    assert.deepEqual(ticker.stats(), { listeners: 0, running: false });
    assert.equal(Object.keys(env.doc).length + Object.keys(env.win).length, 0, 'page listeners are removed too');
  });

  it('survives mounting and unmounting over and over (StrictMode does it on purpose) without leaking timers or listeners', () => {
    const env = fakeEnv(at(16, 8, 16));
    const ticker = createTicker(env.options());
    for (let i = 0; i < 50; i += 1) {
      const stop = ticker.subscribe(() => {});
      assert.equal(env.timers(), 1);
      assert.equal(Object.keys(env.doc).length + Object.keys(env.win).length, 3, 'three page listeners while mounted');
      stop();
      stop(); // stopping twice is harmless
      assert.equal(env.timers(), 0);
    }
    assert.deepEqual(ticker.stats(), { listeners: 0, running: false });
    assert.equal(Object.keys(env.doc).length + Object.keys(env.win).length, 0);
  });

  it('aims at the next real second boundary of the system clock (just after it), not at a fixed delay', () => {
    const cases = [[at(16, 8, 16, 0), 1000], [at(16, 8, 16, 250), 750], [at(16, 8, 16, 999), 1], [at(16, 8, 59, 500), 500]];
    for (const [start, untilBoundary] of cases) {
      const env = fakeEnv(start);
      const stop = createTicker(env.options()).subscribe(() => {});
      assert.equal(env.waits[0], untilBoundary + BOUNDARY_SLACK_MS, start.getMilliseconds() + ' ms into the second');
      stop();
    }
  });

  it('cannot drift: after a late timer, the next wait is worked out again from the clock, so beats stay on the second boundaries', () => {
    const env = fakeEnv(at(16, 8, 16, 250));
    const beats = [];
    createTicker(env.options()).subscribe(() => beats.push(env.wall % 1000));
    env.fire(0);     // on time: lands 10 ms after the boundary
    env.fire(0);
    env.fire(400);   // a busy page: this one fires 400 ms late
    env.fire(0);     // and the next wait was recalculated, so it is back on the boundary
    env.fire(0);
    assert.deepEqual(beats, [10, 10, 410, 10, 10]);
  });

  it("calls listeners with 'tick' on every beat, each once per beat", () => {
    const env = fakeEnv(at(16, 8, 16));
    const seen = [];
    createTicker(env.options()).subscribe((reason) => seen.push(reason));
    env.fire(); env.fire(); env.fire();
    assert.deepEqual(seen, ['tick', 'tick', 'tick']);
    assert.equal(env.timers(), 1, 'always exactly one timer pending');
  });

  it("calls listeners with 'resync' at once when the tab becomes visible, the window is focused or the page is restored, and re-aims the one timer", () => {
    const env = fakeEnv(at(16, 8, 16));
    const ticker = createTicker(env.options());
    const seen = [];
    ticker.subscribe((reason) => seen.push(reason));
    env.documentLike.hidden = true;
    env.doc.visibilitychange(); // going to the background: nothing to do
    assert.deepEqual(seen, []);
    env.documentLike.hidden = false;
    env.wall = at(16, 8, 40, 700).getTime();
    env.doc.visibilitychange(); // coming back
    assert.deepEqual(seen, ['resync']);
    assert.equal(env.timers(), 1, 'still exactly one timer');
    assert.equal(env.waits[env.waits.length - 1], 300 + BOUNDARY_SLACK_MS, 're-aimed at the next boundary from the time it is now');
    env.win.focus();
    env.win.pageshow();
    assert.deepEqual(seen, ['resync', 'resync', 'resync']);
    assert.equal(env.timers(), 1);
  });

  it('a stopped listener hears nothing more, and no resync is delivered after the last one left', () => {
    const env = fakeEnv(at(16, 8, 16));
    const ticker = createTicker(env.options());
    let hits = 0;
    const stop = ticker.subscribe(() => { hits += 1; });
    const other = ticker.subscribe(() => {});
    env.fire();
    stop();
    env.fire();
    assert.equal(hits, 1);
    other();
    assert.equal(env.timers(), 0);
    assert.equal(env.doc.visibilitychange, undefined);
  });

  it('a listener that leaves during a beat does not leave a timer behind', () => {
    const env = fakeEnv(at(16, 8, 16));
    const ticker = createTicker(env.options());
    let stop;
    stop = ticker.subscribe(() => stop());
    env.fire();
    assert.equal(env.timers(), 0, 'the last listener left while the beat ran: nothing is re-scheduled');
    assert.deepEqual(ticker.stats(), { listeners: 0, running: false });
  });

  it('a stale callback (a cleared timer that still ran, as when a background tab releases held-back timers) cannot start a second timer chain', () => {
    const env = fakeEnv(at(16, 8, 16, 250));
    const ticker = createTicker(env.options());
    const seen = [];
    ticker.subscribe((reason) => seen.push(reason));
    const stale = env.pending; // the timer that is about to be superseded
    env.wall = at(16, 8, 20, 100).getTime();
    env.win.focus(); // resync re-aims the timer, so 'stale' is no longer the current one
    assert.deepEqual(seen, ['resync']);
    stale.fn(); // the old callback runs anyway
    assert.deepEqual(seen, ['resync'], 'it did nothing');
    assert.equal(env.timers(), 1, 'still exactly one timer pending, and it is the new one');
    env.fire();
    assert.deepEqual(seen, ['resync', 'tick']);
    assert.equal(env.timers(), 1);
  });

  it('the page shares one ticker, and the minute clock rides on it without creating a timer of its own', () => {
    const env = fakeEnv(at(10, 9, 58));
    const ticker = createTicker(env.options());
    const seen = [];
    const stop = startClock((d) => seen.push(clockParts(d, { timeFormat: 'H24' }).time), { subscribe: ticker.subscribe, now: () => new Date(env.wall) });
    assert.equal(env.timers(), 1, 'the shared timer, started by the first subscriber');
    env.fire();           // 10:09:59.0 + slack
    assert.deepEqual(seen, [], 'same minute: nothing');
    env.fire();           // 10:10:00 + slack
    assert.deepEqual(seen, ['10:10']);
    stop();
    assert.equal(env.timers(), 0);
    assert.equal(typeof sharedTicker.subscribe, 'function');
  });
});

describe('the seconds beside the time', () => {
  it('are two digits, taken from the local system time', () => {
    assert.equal(secondsPart(new Date(2026, 10, 3, 16, 8, 16)), '16');
    assert.equal(secondsPart(new Date(2026, 10, 3, 16, 8, 5)), '05');
    assert.equal(secondsPart(new Date(2026, 10, 3, 16, 8, 0)), '00');
    assert.equal(secondsPart(new Date(2026, 10, 3, 16, 8, 59, 999)), '59');
    assert.equal(secondsPart(new Date('nope')), '');
    assert.equal(secondsPart(null), '');
  });

  it('the whole display reads like "4:08 :16 PM": hours and minutes, seconds, then AM/PM', () => {
    const d = new Date(2026, 10, 3, 16, 8, 16);
    const { main, meridiem } = splitTime(clockParts(d, { timeFormat: 'H12' }).time);
    assert.equal(`${main} :${secondsPart(d)} ${meridiem}`, '4:08 :16 PM');
    const h24 = splitTime(clockParts(d, { timeFormat: 'H24' }).time);
    assert.equal(`${h24.main} :${secondsPart(d)}${h24.meridiem ? ' ' + h24.meridiem : ''}`, '16:08 :16', 'no AM/PM in 24-hour');
  });

  describe('keeping step with the system clock', () => {
    const watch = (env, granularity = 'second') => {
      const ticker = createTicker(env.options());
      const seen = [];
      const stop = startClock((d) => seen.push(d), { subscribe: ticker.subscribe, granularity, now: () => new Date(env.wall) });
      return { seen, stop, ticker };
    };
    const shown = (seen) => seen.map((d) => secondsPart(d));

    it('shows every new second exactly once, for a full minute and beyond (no repeats, no skips), including :59 to :00', () => {
      const env = fakeEnv(at(16, 8, 0, 0));
      const { seen } = watch(env);
      for (let n = 0; n < 125; n += 1) env.fire();
      const values = shown(seen);
      assert.equal(values.length, 125, 'one change per real second');
      values.forEach((v, k) => assert.equal(+v, (k + 1) % 60, 'second #' + (k + 1)));
      assert.equal(values[58], '59');
      assert.equal(values[59], '00', ':59 rolls over to :00');
      assert.deepEqual(seen.slice(58, 61).map((d) => clockParts(d, { timeFormat: 'H24' }).time), ['16:08', '16:09', '16:09'], 'and the minute moves on with it');
    });

    it('always shows the system clock\'s second, even when a beat is late or a whole second was missed', () => {
      const env = fakeEnv(at(16, 8, 0, 900));
      const { seen } = watch(env);
      env.fire(0);        // 16:08:01.0
      env.fire(1200);     // very late: 16:08:02.2 + 1 s of waiting -> jumps straight to the real second
      env.fire(0);
      const values = shown(seen).map(Number);
      assert.ok(values.every((v, k) => k === 0 || v > values[k - 1]), values.join());
      assert.equal(+shown(seen).at(-1), new Date(env.wall).getSeconds());
    });

    it('goes over midnight: the seconds, the minute and the date all roll over together', () => {
      const env = fakeEnv(new Date(2026, 10, 3, 23, 59, 57, 0));
      const { seen } = watch(env);
      for (let n = 0; n < 5; n += 1) env.fire();
      const lines = seen.map((d) => `${clockParts(d, { dateFormat: 'DMY', timeFormat: 'H24' }).date} ${splitTime(clockParts(d, { timeFormat: 'H24' }).time).main}:${secondsPart(d)}`);
      assert.deepEqual(lines, ['Tue, 03/11/2026 23:59:58', 'Tue, 03/11/2026 23:59:59', 'Wed, 04/11/2026 00:00:00', 'Wed, 04/11/2026 00:00:01', 'Wed, 04/11/2026 00:00:02']);
    });

    it('returning to the tab shows the real time at once, even though the throttled timer has not fired', () => {
      const env = fakeEnv(at(16, 8, 10, 0));
      const { seen } = watch(env);
      env.documentLike.hidden = true;
      env.wall = at(16, 12, 42, 300).getTime();   // minutes in the background; the throttled timer never fired
      env.documentLike.hidden = false;
      env.doc.visibilitychange();                 // the tab is active again: no waiting for the timer
      assert.deepEqual(shown(seen), ['42']);
      assert.equal(clockParts(seen[0], { timeFormat: 'H24' }).time, '16:12');
    });

    it('focus and page restore resync the same way', () => {
      const env = fakeEnv(at(16, 8, 10, 0));
      const { seen } = watch(env);
      env.wall = at(16, 8, 31, 0).getTime(); env.win.focus();
      env.wall = at(16, 9, 7, 0).getTime(); env.win.pageshow();
      assert.deepEqual(shown(seen), ['31', '07']);
    });

    it('runs on exactly one timer, and stopping removes the timer and every page listener', () => {
      const env = fakeEnv(at(16, 8, 10, 0));
      const { stop, ticker } = watch(env);
      assert.equal(env.timers(), 1);
      assert.deepEqual(ticker.stats(), { listeners: 1, running: true });
      stop();
      assert.deepEqual(ticker.stats(), { listeners: 0, running: false });
      assert.equal(env.timers(), 0);
      assert.equal(Object.keys(env.doc).length + Object.keys(env.win).length, 0, 'no listeners left on the page');
    });
  });
});

describe('clock digit animation', () => {
  const jsx = () => read('..', 'components', 'dashboard', 'LiveClock.jsx');
  const css = () => read('..', 'index.css');

  it('rolls only hours/minutes characters; seconds stay a plain value', () => {
    const src = jsx();
    assert.match(src, /<RollingTime text=\{main\} \/>/);
    assert.match(src, /:\{secondsPart\(now\)\}<\/span>/);
    assert.doesNotMatch(src, /RollChar[^\n]*secondsPart/);
  });

  it('animates only on a real value change, never on first paint', () => {
    const src = jsx();
    assert.match(src, /state\.cur !== value/);
    assert.match(src, /n: animateOnMount \? 1 : 0/);
  });

  it('adds no timers of its own and leaves the ticker untouched', () => {
    const src = jsx();
    assert.doesNotMatch(src, /setInterval|setTimeout|requestAnimationFrame/);
    assert.match(src, /startClock\(setNow, \{ subscribe: sharedTicker\.subscribe, granularity: 'second' \}\)/);
  });

  it('fades AM/PM only when it changes', () => {
    assert.match(jsx(), /state\.n > 0 \? 'clock-fade-in'/);
  });

  it('uses 250-350 ms CSS animations and disables them for reduced motion', () => {
    const c = css();
    const durations = [...c.matchAll(/animation: clock\w+ (\d+)ms/g)].map((m) => Number(m[1]));
    assert.equal(durations.length, 3);
    durations.forEach((d) => assert.ok(d >= 250 && d <= 350));
    assert.match(c, /prefers-reduced-motion: reduce\)\s*\{\s*\.clock-roll-in, \.clock-fade-in \{ animation: none; \}\s*\.clock-roll-out \{ display: none; \}/);
    assert.match(jsx(), /prefers-reduced-motion: reduce/);
  });

  it('uses no hard-coded colours, so it follows the light and dark themes', () => {
    const block = css().slice(css().indexOf('Dashboard clock: subtle digit roll'));
    assert.doesNotMatch(block, /#[0-9a-f]{3,6}\b|rgb/i);
  });
});

describe('clock follows the saved time-format preference', () => {
  const at = (h, m, s) => new Date(2026, 10, 4, h, m, s);
  const shown = (date, prefs) => {
    const { main, meridiem } = splitTime(clockParts(date, prefs).time);
    return `${main}:${secondsPart(date)}${meridiem ? ' ' + meridiem : ''}`;
  };

  it('12-hour: 9:46:06 AM, with AM/PM', () => {
    assert.equal(shown(at(9, 46, 6), { timeFormat: 'H12' }), '9:46:06 AM');
  });

  it('24-hour: 09:46:06, no AM/PM', () => {
    assert.equal(shown(at(9, 46, 6), { timeFormat: 'H24' }), '09:46:06');
  });

  it('handles midnight, noon and leading zeros in both formats', () => {
    assert.equal(shown(at(0, 5, 9), { timeFormat: 'H12' }), '12:05:09 AM');
    assert.equal(shown(at(0, 5, 9), { timeFormat: 'H24' }), '00:05:09');
    assert.equal(shown(at(12, 0, 0), { timeFormat: 'H12' }), '12:00:00 PM');
    assert.equal(shown(at(12, 0, 0), { timeFormat: 'H24' }), '12:00:00');
    assert.equal(shown(at(23, 59, 59), { timeFormat: 'H12' }), '11:59:59 PM');
    assert.equal(shown(at(23, 59, 59), { timeFormat: 'H24' }), '23:59:59');
  });

  it('the date does not depend on the time format', () => {
    const d = at(15, 3, 0);
    assert.equal(clockParts(d, { dateFormat: 'MDY', timeFormat: 'H12' }).date, clockParts(d, { dateFormat: 'MDY', timeFormat: 'H24' }).date);
  });

  it('switching the saved preference changes what the clock reads, with no reload', async () => {
    const store = new Map();
    globalThis.localStorage = { getItem: (k) => store.get(k) ?? null, setItem: (k, v) => store.set(k, v), removeItem: (k) => store.delete(k) };
    try {
      localStorage.setItem('username', 'bob');
      setCurrentProfile({ username: 'bob', preferences: { themePreference: 'LIGHT', dateFormat: 'DMY', timeFormat: 'H12' } });
      const { savePreferences } = await import('../utils/preferences.js');
      const d = at(21, 4, 5);
      assert.equal(shown(d, getPreferences()), '9:04:05 PM');
      await savePreferences({ timeFormat: 'H24' }, { send: async (p) => ({ username: 'bob', preferences: p }) });
      assert.equal(shown(d, getPreferences()), '21:04:05');
      await savePreferences({ timeFormat: 'H12' }, { send: async (p) => ({ username: 'bob', preferences: p }) });
      assert.equal(shown(d, getPreferences()), '9:04:05 PM');
    } finally {
      resetCurrentProfile();
      delete globalThis.localStorage;
    }
  });

  it('LiveClock reads the shared preferences and hardcodes no 12-hour override', () => {
    const src = read('..', 'components', 'dashboard', 'LiveClock.jsx') + read('..', 'utils', 'liveClock.js');
    assert.match(src, /getPreferences\(\)/);
    assert.doesNotMatch(src, /hour12\s*:\s*true|toLocaleTimeString/);
  });
});
