// The viewer's zone: the server sends UTC and every time is shown in the viewer's own zone (a user in India here)
process.env.TZ = 'Asia/Kolkata';

import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { describeActivity, formatActivityTime } from '../utils/accountActivity.js';


describe('account activity formatting', () => {
  it('shows the UTC moment from the server in the zone of the viewer (Asia/Kolkata here), in the chosen formats (DD/MM/YYYY, 12-hour by default)', () => {
    assert.equal(formatActivityTime('2026-11-02T04:57:51.123Z'), '02/11/2026, 10:27 AM');
    assert.equal(formatActivityTime('2026-03-04T09:36:07Z'), '04/03/2026, 3:06 PM');
    assert.equal(formatActivityTime('2026-03-03T18:35:00Z'), '04/03/2026, 12:05 AM');
    assert.equal(formatActivityTime('2026-03-04T06:30:00Z'), '04/03/2026, 12:00 PM');
    assert.equal(formatActivityTime([2026, 11, 2, 3, 35, 1]), '02/11/2026, 9:05 AM', 'array form');
  });

  it('never invents a date: missing or unusable values give null', () => {
    for (const bad of [null, undefined, '', 'not a date', '2026-13-40T10:00:00', '2026-02-03T25:00:00', {}, []]) {
      assert.equal(formatActivityTime(bad), null, String(JSON.stringify(bad)));
    }
  });
});

describe('account activity states', () => {
  const profile = { createdAt: '2026-01-05T03:00:00Z', lastLoginAt: '2026-11-02T04:57:51Z', passwordChangedAt: null };

  it('an account with recorded activity shows real times', () => {
    assert.deepEqual(describeActivity(profile, 'createdAt'), { text: '05/01/2026, 8:30 AM', known: true });
    assert.deepEqual(describeActivity(profile, 'lastLoginAt'), { text: '02/11/2026, 10:27 AM', known: true });
  });

  it('an account without a timestamp says it is not recorded, instead of showing a date', () => {
    assert.deepEqual(describeActivity(profile, 'passwordChangedAt'), { text: 'Not recorded yet', known: false });
    assert.deepEqual(describeActivity({}, 'lastLoginAt'), { text: 'Not recorded yet', known: false });
    assert.deepEqual(describeActivity({ createdAt: null }, 'createdAt', 'Unavailable'), { text: 'Unavailable', known: false });
  });

  it('while the profile has not loaded, nothing is claimed either way', () => {
    for (const field of ['createdAt', 'lastLoginAt', 'passwordChangedAt']) {
      assert.deepEqual(describeActivity(null, field), { text: 'Loading…', known: false });
    }
  });
});
