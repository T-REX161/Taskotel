// The viewer's zone for these tests (what a user in India has). Set before any Date is used; other zones are switched to inside the tests.
process.env.TZ = 'Asia/Kolkata';

import { describe, it, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import { parseServerInstant } from './dateTimeFormat.js';
import { describeActivity, formatActivityTime } from './accountActivity.js';
import { formatCreatedDate } from '../components/users/userListUtils.js';

const IST = 'Asia/Kolkata';
afterEach(() => { process.env.TZ = IST; });

describe('UTC from the server, shown in the viewer\'s own time zone', () => {
  it('the production Account Activity values: 05:25, 06:10 and 05:27 UTC read 10:55, 11:40 and 10:57 in Asia/Kolkata (not 05:25, 06:10, 05:27)', () => {
    const profile = { createdAt: '2026-11-20T05:25:00Z', lastLoginAt: '2026-11-20T06:10:00Z', passwordChangedAt: '2026-11-20T05:27:00Z' };
    assert.deepEqual(describeActivity(profile, 'createdAt', 'Unavailable'), { text: '20/11/2026, 10:55 AM', known: true });
    assert.deepEqual(describeActivity(profile, 'lastLoginAt'), { text: '20/11/2026, 11:40 AM', known: true });
    assert.deepEqual(describeActivity(profile, 'passwordChangedAt'), { text: '20/11/2026, 10:57 AM', known: true });
  });

  it('the same instant is the same moment in every zone: the viewer\'s zone decides the clock reading', () => {
    process.env.TZ = 'UTC';
    assert.equal(formatActivityTime('2026-11-20T05:25:00Z'), '20/11/2026, 5:25 AM');
    process.env.TZ = 'America/New_York';
    assert.equal(formatActivityTime('2026-11-20T05:25:00Z'), '20/11/2026, 12:25 AM');
    process.env.TZ = IST;
    assert.equal(formatActivityTime('2026-11-20T05:25:00Z'), '20/11/2026, 10:55 AM');
  });

  it('a string with no zone is the server\'s UTC reading, never the viewer\'s local time (the old misreading)', () => {
    assert.equal(formatActivityTime('2026-11-20T05:25:00'), '20/11/2026, 10:55 AM');
    assert.equal(formatActivityTime('2026-11-20T05:25:00.123456'), '20/11/2026, 10:55 AM');
    assert.equal(formatActivityTime('2026-11-20 05:25:00'), '20/11/2026, 10:55 AM');
    assert.equal(formatActivityTime([2026, 11, 20, 5, 25, 0]), '20/11/2026, 10:55 AM', 'array form, also UTC');
  });

  it('an explicit offset is honoured exactly', () => {
    assert.equal(formatActivityTime('2026-11-20T10:55:00+05:30'), '20/11/2026, 10:55 AM');
    assert.equal(formatActivityTime('2026-11-20T10:55:00+0530'), '20/11/2026, 10:55 AM');
    assert.equal(formatActivityTime('2026-11-19T20:00:00-09:00'), '20/11/2026, 10:30 AM', '20:00 at -09:00 is 05:00 UTC');
  });

  it('crossing midnight: 20:00 UTC on the 20th is 1:30 AM on the 21st in India, for the time and for the Users table date', () => {
    assert.equal(formatActivityTime('2026-11-20T20:00:00Z'), '21/11/2026, 1:30 AM');
    assert.equal(formatCreatedDate('2026-11-20T20:00:00Z'), '21/11/2026');
    assert.equal(formatCreatedDate('2026-11-20T05:25:00Z'), '20/11/2026');
  });

  it('respects the chosen 24-hour format and never invents a value', () => {
    assert.equal(formatActivityTime('2026-11-20T05:25:00Z', { dateFormat: 'YMD', timeFormat: 'H24' }), '2026-11-20, 10:55');
    for (const bad of [null, undefined, '', 'not a date', '2026-13-40T10:00:00Z', '2026-02-03T25:00:00Z', {}, [], 42]) {
      assert.equal(formatActivityTime(bad), null, String(JSON.stringify(bad)));
      assert.equal(parseServerInstant(bad), null);
    }
    assert.equal(formatCreatedDate(null), '—');
    assert.equal(formatCreatedDate('garbage'), '—');
  });

  it('parseServerInstant returns the exact instant', () => {
    assert.equal(parseServerInstant('2026-11-20T05:25:00Z').toISOString(), '2026-11-20T05:25:00.000Z');
    assert.equal(parseServerInstant('2026-11-20T05:25:00').toISOString(), '2026-11-20T05:25:00.000Z');
    assert.equal(parseServerInstant('2026-11-20T10:55:00+05:30').toISOString(), '2026-11-20T05:25:00.000Z');
    assert.equal(parseServerInstant([2026, 11, 20, 5, 25]).toISOString(), '2026-11-20T05:25:00.000Z');
  });
});
