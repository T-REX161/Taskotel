import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { describeActivity, formatActivityTime } from '../utils/accountActivity.js';


describe('account activity formatting', () => {
  it('shows the server time exactly as sent, in the chosen formats (DD/MM/YYYY, 12-hour by default) (no time-zone shift)', () => {
    assert.equal(formatActivityTime('2026-11-02T10:27:51.123'), '02/11/2026, 10:27 AM');
    assert.equal(formatActivityTime('2026-03-04T15:06:07'), '04/03/2026, 3:06 PM');
    assert.equal(formatActivityTime('2026-03-04T00:05:00'), '04/03/2026, 12:05 AM');
    assert.equal(formatActivityTime('2026-03-04T12:00:00'), '04/03/2026, 12:00 PM');
    assert.equal(formatActivityTime([2026, 11, 2, 9, 5, 1]), '02/11/2026, 9:05 AM', 'array form');
  });

  it('never invents a date: missing or unusable values give null', () => {
    for (const bad of [null, undefined, '', 'not a date', '2026-13-40T10:00:00', '2026-02-03T25:00:00', {}, []]) {
      assert.equal(formatActivityTime(bad), null, String(JSON.stringify(bad)));
    }
  });
});

describe('account activity states', () => {
  const profile = { createdAt: '2026-01-05T08:30:00', lastLoginAt: '2026-11-02T10:27:51', passwordChangedAt: null };

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
