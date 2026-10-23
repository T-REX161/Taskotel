import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { formatDuration, formatTime, formatDate, formatDueDate, formatDepartment } from './taskFormatters.js';

describe('formatDuration', () => {
  describe('values below one hour', () => {
    it('formats 30 minutes as "30 min"', () => {
      assert.strictEqual(formatDuration(30), '30 min');
    });

    it('formats 45 minutes as "45 min"', () => {
      assert.strictEqual(formatDuration(45), '45 min');
    });

    it('formats 1 minute as "1 min"', () => {
      assert.strictEqual(formatDuration(1), '1 min');
    });

    it('formats 15 minutes as "15 min"', () => {
      assert.strictEqual(formatDuration(15), '15 min');
    });

    it('formats 59 minutes as "59 min"', () => {
      assert.strictEqual(formatDuration(59), '59 min');
    });

    it('handles numeric string inputs below 1 hour', () => {
      assert.strictEqual(formatDuration('45'), '45 min');
    });
  });

  describe('exact hours', () => {
    it('formats 60 minutes as "1 hr" without zero-minute component', () => {
      assert.strictEqual(formatDuration(60), '1 hr');
    });

    it('formats 120 minutes as "2 hr" without zero-minute component', () => {
      assert.strictEqual(formatDuration(120), '2 hr');
    });

    it('formats 180 minutes as "3 hr" without zero-minute component', () => {
      assert.strictEqual(formatDuration(180), '3 hr');
    });

    it('handles numeric string inputs for exact hours', () => {
      assert.strictEqual(formatDuration('60'), '1 hr');
      assert.strictEqual(formatDuration('120'), '2 hr');
    });
  });

  describe('mixed hours and minutes', () => {
    it('formats 70 minutes as "1 hr 10 min"', () => {
      assert.strictEqual(formatDuration(70), '1 hr 10 min');
    });

    it('formats 75 minutes as "1 hr 15 min"', () => {
      assert.strictEqual(formatDuration(75), '1 hr 15 min');
    });

    it('formats 150 minutes as "2 hr 30 min"', () => {
      assert.strictEqual(formatDuration(150), '2 hr 30 min');
    });

    it('formats 61 minutes as "1 hr 1 min"', () => {
      assert.strictEqual(formatDuration(61), '1 hr 1 min');
    });

    it('formats 125 minutes as "2 hr 5 min"', () => {
      assert.strictEqual(formatDuration(125), '2 hr 5 min');
    });

    it('handles numeric string inputs for mixed hours and minutes', () => {
      assert.strictEqual(formatDuration('75'), '1 hr 15 min');
      assert.strictEqual(formatDuration('150'), '2 hr 30 min');
    });
  });

  describe('zero or missing values according to existing conventions', () => {
    it('returns "-" for 0 minutes', () => {
      assert.strictEqual(formatDuration(0), '-');
    });

    it('returns "-" for string "0"', () => {
      assert.strictEqual(formatDuration('0'), '-');
    });

    it('returns "-" for null', () => {
      assert.strictEqual(formatDuration(null), '-');
    });

    it('returns "-" for undefined', () => {
      assert.strictEqual(formatDuration(undefined), '-');
    });

    it('returns "-" for empty string', () => {
      assert.strictEqual(formatDuration(''), '-');
    });

    it('returns "-" for negative numbers', () => {
      assert.strictEqual(formatDuration(-15), '-');
    });

    it('returns "-" for NaN or non-numeric strings', () => {
      assert.strictEqual(formatDuration('abc'), '-');
      assert.strictEqual(formatDuration(NaN), '-');
    });

    it('respects custom fallback when provided', () => {
      assert.strictEqual(formatDuration(null, 'Not specified'), 'Not specified');
      assert.strictEqual(formatDuration(0, 'Not specified'), 'Not specified');
      assert.strictEqual(formatDuration(undefined, 'None'), 'None');
    });
  });
});

describe('formatTime', () => {
  it('formats "17:00" as "5:00 PM"', () => {
    assert.strictEqual(formatTime('17:00'), '5:00 PM');
  });

  it('formats "17:00:00" with seconds as "5:00 PM"', () => {
    assert.strictEqual(formatTime('17:00:00'), '5:00 PM');
  });

  it('formats "14:30" as "2:30 PM"', () => {
    assert.strictEqual(formatTime('14:30'), '2:30 PM');
  });

  it('formats "09:05" as "9:05 AM"', () => {
    assert.strictEqual(formatTime('09:05'), '9:05 AM');
  });

  it('formats midnight "00:00" as "12:00 AM"', () => {
    assert.strictEqual(formatTime('00:00'), '12:00 AM');
  });

  it('formats noon "12:00" as "12:00 PM"', () => {
    assert.strictEqual(formatTime('12:00'), '12:00 PM');
  });

  it('formats already-formatted 12-hour strings like "05:00 PM" or "5:00 PM"', () => {
    assert.strictEqual(formatTime('05:00 PM'), '5:00 PM');
    assert.strictEqual(formatTime('5:00 PM'), '5:00 PM');
    assert.strictEqual(formatTime('05:00:00 PM'), '5:00 PM');
    assert.strictEqual(formatTime('09:30 AM'), '9:30 AM');
    assert.strictEqual(formatTime('12:00 AM'), '12:00 AM');
    assert.strictEqual(formatTime('12:00 PM'), '12:00 PM');
  });

  it('returns null for null, undefined, empty, or invalid time', () => {
    assert.strictEqual(formatTime(null), null);
    assert.strictEqual(formatTime(undefined), null);
    assert.strictEqual(formatTime(''), null);
    assert.strictEqual(formatTime('invalid'), null);
    assert.strictEqual(formatTime('25:00'), null);
  });
});

describe('formatDueDate', () => {
  it('formats a date and 5:00 PM together as "Oct 13, 2026, 5:00 PM"', () => {
    assert.strictEqual(formatDueDate('2026-10-13', '17:00'), 'Oct 13, 2026, 5:00 PM');
    assert.strictEqual(formatDueDate('2026-10-13', '17:00:00'), 'Oct 13, 2026, 5:00 PM');
    assert.strictEqual(formatDueDate('2026-10-13', '05:00 PM'), 'Oct 13, 2026, 5:00 PM');
    assert.strictEqual(formatDueDate('2026-10-13', '5:00 PM'), 'Oct 13, 2026, 5:00 PM');
  });

  it('formats a date and 2:30 PM together as "Oct 14, 2026, 2:30 PM"', () => {
    assert.strictEqual(formatDueDate('2026-10-14', '14:30'), 'Oct 14, 2026, 2:30 PM');
    assert.strictEqual(formatDueDate('2026-10-14', '14:30:00'), 'Oct 14, 2026, 2:30 PM');
  });

  it('formats a date only when deadline time is omitted or null', () => {
    assert.strictEqual(formatDueDate('2026-10-14'), 'Oct 14, 2026');
    assert.strictEqual(formatDueDate('2026-10-14', null), 'Oct 14, 2026');
    assert.strictEqual(formatDueDate('2026-10-14', ''), 'Oct 14, 2026');
  });

  it('returns "-" when neither date nor time is present', () => {
    assert.strictEqual(formatDueDate(null, null), '-');
    assert.strictEqual(formatDueDate('', ''), '-');
    assert.strictEqual(formatDueDate(undefined), '-');
  });

  it('supports passing a task object directly', () => {
    const taskWithBoth = { dueDate: '2026-10-13', deadlineTime: '17:00' };
    assert.strictEqual(formatDueDate(taskWithBoth), 'Oct 13, 2026, 5:00 PM');

    const taskDateOnly = { dueDate: '2026-10-14', deadlineTime: null };
    assert.strictEqual(formatDueDate(taskDateOnly), 'Oct 14, 2026');

    const taskNeither = { dueDate: null, deadlineTime: null };
    assert.strictEqual(formatDueDate(taskNeither), '-');
  });
});

describe('formatDate', () => {
  it('formats YYYY-MM-DD into "MMM D, YYYY"', () => {
    assert.strictEqual(formatDate('2026-10-13'), 'Oct 13, 2026');
    assert.strictEqual(formatDate('2026-01-01'), 'Jan 1, 2026');
    assert.strictEqual(formatDate('2026-12-31'), 'Dec 31, 2026');
  });

  it('returns null for null, undefined, or empty string', () => {
    assert.strictEqual(formatDate(null), null);
    assert.strictEqual(formatDate(undefined), null);
    assert.strictEqual(formatDate(''), null);
  });
});

describe('formatDepartment', () => {
  it('formats supported backend departments correctly', () => {
    assert.strictEqual(formatDepartment('HOUSEKEEPING'), 'Housekeeping');
    assert.strictEqual(formatDepartment('FRONT_DESK'), 'Front Desk');
    assert.strictEqual(formatDepartment('MAINTENANCE'), 'Maintenance');
    assert.strictEqual(formatDepartment('ROOM_SERVICE'), 'Room Service');
    assert.strictEqual(formatDepartment('FOOD_AND_BEVERAGE'), 'Food & Beverage');
    assert.strictEqual(formatDepartment('SECURITY'), 'Security');
  });

  it('handles fallback for unknown or missing department', () => {
    assert.strictEqual(formatDepartment(null), '-');
    assert.strictEqual(formatDepartment(''), '-');
    assert.strictEqual(formatDepartment(undefined), '-');
    // A department the app has not been told about is shown as a readable form of its code
    assert.strictEqual(formatDepartment('OTHER_DEPT'), 'Other Dept');
  });
});

