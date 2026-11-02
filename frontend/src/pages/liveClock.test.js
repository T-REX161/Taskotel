import { describe, it, beforeEach, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { CLOCK_INTERVAL_MS, clockParts, startClock } from '../utils/liveClock.js';
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

describe('the ticking: once a minute', () => {
  /** A fake clock and timers: advance(ms) moves time forward and fires whatever is due. */
  function fakeTimers(start) {
    let time = start.getTime();
    let nextId = 1;
    const timers = new Map(); // id -> { fn, at, every }
    return {
      now: () => new Date(time),
      setTimeoutFn: (fn, ms) => { timers.set(nextId, { fn, at: time + ms, every: null }); return nextId++; },
      setIntervalFn: (fn, ms) => { timers.set(nextId, { fn, at: time + ms, every: ms }); return nextId++; },
      clearTimeoutFn: (id) => timers.delete(id),
      clearIntervalFn: (id) => timers.delete(id),
      size: () => timers.size,
      advance(ms) {
        const end = time + ms;
        for (;;) {
          const due = [...timers.entries()].filter(([, t]) => t.at <= end).sort((x, y) => x[1].at - y[1].at)[0];
          if (!due) break;
          const [id, t] = due;
          time = t.at;
          if (t.every) t.at += t.every; else timers.delete(id);
          t.fn();
        }
        time = end;
      },
    };
  }

  it('the interval is one minute, not one second', () => {
    assert.equal(CLOCK_INTERVAL_MS, 60000);
    const clockFile = read('..', 'utils', 'liveClock.js');
    assert.ok(!/1000\)|intervalMs = 1000/.test(clockFile), 'no per-second timer');
  });

  it('shows the new minute right at the minute boundary, then once every 60 seconds, never in between', () => {
    const t = fakeTimers(new Date(2026, 10, 2, 15, 1, 7));
    const seen = [];
    const stop = startClock((d) => seen.push(clockParts(d, { timeFormat: 'H12' }).time), t);
    t.advance(30_000);
    assert.deepEqual(seen, [], 'nothing before the minute changes (15:01:37)');
    t.advance(23_000); // 15:02:00
    assert.deepEqual(seen, ['3:02 PM']);
    t.advance(59_000);
    assert.deepEqual(seen, ['3:02 PM'], 'not every second');
    t.advance(1_000); // 15:03:00
    t.advance(60_000); // 15:04:00
    assert.deepEqual(seen, ['3:02 PM', '3:03 PM', '3:04 PM']);
    stop();
  });

  it('over ten minutes it ticks about ten times, not hundreds, each a minute apart and never with seconds', () => {
    const t = fakeTimers(new Date(2026, 10, 2, 23, 58, 41));
    const stamps = [];
    startClock((d) => stamps.push(d), t);
    t.advance(10 * 60_000);
    assert.ok(stamps.length >= 9 && stamps.length <= 11, `ticked ${stamps.length} times`);
    for (const d of stamps) assert.equal(d.getSeconds(), 0, 'ticks land on the minute');
    for (let i = 1; i < stamps.length; i += 1) assert.equal(stamps[i] - stamps[i - 1], 60_000);
    assert.equal(clockParts(stamps[0], { timeFormat: 'H24' }).time, '23:59');
    assert.equal(clockParts(stamps[1], { timeFormat: 'H24' }).time, '00:00');
  });

  it('stopping (the cleanup on unmount) clears the pending timeout and the interval, before or after the first tick', () => {
    let ticks = 0;
    const early = fakeTimers(new Date(2026, 10, 2, 15, 1, 7));
    const stopEarly = startClock(() => { ticks += 1; }, early);
    assert.equal(early.size(), 1);
    stopEarly();
    assert.equal(early.size(), 0);
    early.advance(10 * 60_000);
    assert.equal(ticks, 0);

    const late = fakeTimers(new Date(2026, 10, 2, 15, 1, 7));
    const stopLate = startClock(() => { ticks += 1; }, late);
    late.advance(2 * 60_000);
    const before = ticks;
    assert.ok(before >= 2);
    stopLate();
    assert.equal(late.size(), 0, 'the interval is cleared');
    late.advance(10 * 60_000);
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

  it('the clock sits in the Dashboard header, where the date was, and is compact (no card)', () => {
    const header = dashboard.slice(dashboard.indexOf('{/* Header */}'), dashboard.indexOf('{/* KPI cards */}'));
    assert.ok(header.includes('<LiveClock />') && header.includes('Welcome back, {welcomeName}!'));
    assert.ok(!/rounded-2xl|shadow|border/.test(clock), 'plain text, no card');
    assert.ok(clock.includes('tabular-nums') && clock.includes('flex-wrap'), 'steady digits, wraps on small screens');
  });

  it('it re-renders when preferences change, ticks via startClock, and cleans up its timer', () => {
    assert.ok(clock.includes('useCurrentUserProfile()') && clock.includes('getPreferences()'));
    assert.ok(clock.includes('useEffect(() => startClock(setNow), []);'), 'the effect returns the stop function');
  });

  it('no seconds and no time zone are anywhere in the clock code', () => {
    for (const file of [clock, read('..', 'utils', 'liveClock.js'), read('..', 'utils', 'dateTimeFormat.js')]) {
      for (const forbidden of ['WithSeconds', 'SecondsParts', 'Hyderabad', 'Asia/', 'Kolkata', 'timeZone', 'getTimezoneOffset', 'toLocale']) {
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
