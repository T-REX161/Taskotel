// The viewer's zone: a browser in India
process.env.TZ = 'Asia/Kolkata';

import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { formatActivityTime } from './accountActivity.js';
import { formatCreatedDate } from '../components/users/userListUtils.js';
import { formatAssignedAt, splitAssignedAt } from './assignmentUtils.js';
import { minutesLeft } from './loginEmailChange.js';
import { latestActiveTasks } from './myWorkload.js';

/*
 * Which frontend works with which backend. The strings below are the real `lastLoginAt` values the four combinations produced (a scratch
 * backend started in each JVM zone, one sign-in at 14:24 in India = 08:54 UTC, read from /api/profile over HTTP):
 *   old backend, UTC server   "2026-11-20T08:53:41.561272"    (a UTC clock reading, no zone)
 *   old backend, IST machine  "2026-11-20T14:24:06.541507"    (an India clock reading, no zone)
 *   new backend, UTC server   "2026-11-20T08:54:29.805516Z"
 *   new backend, IST machine  "2026-11-20T08:54:53.114940Z"   (the same instant either way)
 */
const OLD_UTC_BACKEND = '2026-11-20T08:53:41.561272';
const OLD_IST_BACKEND = '2026-11-20T14:24:06.541507';
const NEW_BACKEND_FROM_UTC_SERVER = '2026-11-20T08:54:29.805516Z';
const NEW_BACKEND_FROM_IST_MACHINE = '2026-11-20T08:54:53.114940Z';

/** What the previous frontend did: read the digits as they were sent, never shifted (kept here only to document the combinations). */
function previousFrontend(value) {
  const m = String(value).match(/^(\d{4})-(\d{2})-(\d{2})(?:[T ](\d{2}):(\d{2}))?/);
  const [, , , , h, mi] = m;
  const hour = Number(h);
  return `${hour % 12 === 0 ? 12 : hour % 12}:${mi} ${hour < 12 ? 'AM' : 'PM'}`;
}
const timeOf = (text) => text.split(', ')[1];

describe('new frontend with every backend', () => {
  it('new backend (either server zone): correct, 2:24 PM in India', () => {
    assert.equal(timeOf(formatActivityTime(NEW_BACKEND_FROM_UTC_SERVER)), '2:24 PM');
    assert.equal(timeOf(formatActivityTime(NEW_BACKEND_FROM_IST_MACHINE)), '2:24 PM');
  });

  it('old UTC production backend: correct (a zone-less reading is read as the UTC it is), 2:23 PM', () => {
    assert.equal(timeOf(formatActivityTime(OLD_UTC_BACKEND)), '2:23 PM');
  });

  it('old backend on an India machine: TEMPORARILY WRONG by +5h30 (7:54 PM instead of 2:24 PM). A zone-less India reading cannot be told from UTC; it only happens while a new frontend talks to an old local backend', () => {
    assert.equal(timeOf(formatActivityTime(OLD_IST_BACKEND)), '7:54 PM');
  });
});

describe('previous frontend with every backend (documents what users see during a staggered deploy)', () => {
  it('old backend, UTC server: the bug being fixed (8:53 AM shown at 2:23 PM)', () => {
    assert.equal(previousFrontend(OLD_UTC_BACKEND), '8:53 AM');
  });
  it('old backend on an India machine: correct, which is why localhost looked right', () => {
    assert.equal(previousFrontend(OLD_IST_BACKEND), '2:24 PM');
  });
  it('new backend: unchanged from today for Account Activity (it still shows the UTC clock, 8:54 AM) until the new frontend is deployed; nothing gets worse', () => {
    assert.equal(previousFrontend(NEW_BACKEND_FROM_UTC_SERVER), '8:54 AM');
  });
});

describe('the other timestamp displays (they parse with new Date, so a zone marker is what makes them right)', () => {
  const now = new Date('2026-11-20T09:00:00Z'); // 14:30 in India
  it('assignments: "Today, 2:24 PM" from a new backend; a zone-less UTC reading from an old UTC backend is misread as local (8:53 AM) until the backend is deployed', () => {
    assert.equal(formatAssignedAt(NEW_BACKEND_FROM_UTC_SERVER, now), 'Today, 2:24 PM');
    assert.deepEqual(splitAssignedAt(formatAssignedAt(NEW_BACKEND_FROM_UTC_SERVER, now)), { date: 'Today', time: '2:24 PM' });
    assert.equal(formatAssignedAt(OLD_UTC_BACKEND, now), 'Today, 8:53 AM');
  });

  it('the Users table shows the calendar day in the viewer\'s zone, and day boundaries follow it', () => {
    assert.equal(formatCreatedDate('2026-11-20T20:00:00Z'), '21/11/2026');
    assert.equal(formatCreatedDate('2026-11-20T08:54:29.805516Z'), '20/11/2026');
  });

  it('pending email expiry: minutes left are right with a zone marker (a zone-less UTC expiry was off by 5h30 in a browser in India)', () => {
    const at = Date.parse('2026-11-20T08:54:00Z');
    assert.equal(minutesLeft('2026-11-20T09:04:00Z', at), 10);
    assert.equal(minutesLeft('2026-11-20T09:04:00', at), 0, 'old backend, zone-less: read as 09:04 local, which was 5h30 ago here, so a live code showed as expired');
  });
});

describe('ordering by creation time', () => {
  const task = (id, createdAt) => ({ id, title: id, status: 'PENDING', createdAt });

  it('newest first across whole seconds and fractions of a second', () => {
    const tasks = [task('a', '2026-11-20T08:54:28.100000Z'), task('b', '2026-11-20T08:54:29.500000Z'), task('c', '2026-11-20T08:54:27.900000Z')];
    assert.deepEqual(latestActiveTasks(tasks).map((t) => t.id), ['b', 'a', 'c']);
  });

  // Regression: the sort used to compare the strings, and a trailing 'Z' sorts after a digit, so tasks created in the same second were misordered
  // when one had no fraction (an exact whole second) or fractions of different lengths. The server drops trailing zero groups
  // ('...:28Z', '...:28.123Z', '...:28.123456Z'). The order now follows the instants.
  it('the same second with different fractional precision orders chronologically: whole second, .1, .123, .123456, .5', () => {
    const tasks = [
      task('half', '2026-11-20T08:54:28.500Z'),
      task('whole', '2026-11-20T08:54:28Z'),
      task('micro', '2026-11-20T08:54:28.123456Z'),
      task('tenth', '2026-11-20T08:54:28.1Z'),
      task('milli', '2026-11-20T08:54:28.123Z'),
    ];
    assert.deepEqual(latestActiveTasks(tasks, 5).map((t) => t.id), ['half', 'micro', 'milli', 'tenth', 'whole']);
  });

  it('a task created at an exact whole second is older than one created half a second later, in either input order', () => {
    const whole = task('whole', '2026-11-20T08:54:28Z');
    const later = task('later', '2026-11-20T08:54:28.500000Z');
    assert.deepEqual(latestActiveTasks([whole, later]).map((t) => t.id), ['later', 'whole']);
    assert.deepEqual(latestActiveTasks([later, whole]).map((t) => t.id), ['later', 'whole']);
  });

  it('microsecond differences inside the same millisecond still order, and equal instants fall back to the title', () => {
    const tasks = [task('b-early', '2026-11-20T08:54:28.123100Z'), task('a-late', '2026-11-20T08:54:28.123900Z'), task('c-tie-2', '2026-11-20T08:54:28.123100Z')];
    assert.deepEqual(latestActiveTasks(tasks, 3).map((t) => t.id), ['a-late', 'b-early', 'c-tie-2']);
  });

  it('tasks with no usable creation time sort last, and a zone-less (older backend) time is read as UTC like every other', () => {
    const tasks = [task('none', null), task('bad', 'garbage'), task('old-backend', '2026-11-20T08:54:29'), task('new-backend', '2026-11-20T08:54:28Z')];
    assert.deepEqual(latestActiveTasks(tasks, 4).map((t) => t.id), ['old-backend', 'new-backend', 'bad', 'none']);
  });
});
