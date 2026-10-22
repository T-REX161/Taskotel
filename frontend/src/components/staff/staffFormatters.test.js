import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import {
  formatDepartment,
  isAvailable,
  isActive,
  getAvailabilityColor,
  getStatusColor,
  formatDeactivateConfirmationMessage,
  formatRemoveConfirmationMessage,
  getStaffDisplayName,
  compareStaffByName,
  sortStaffAlphabetically,
} from './staffFormatters.js';

describe('staffFormatters', () => {
  describe('formatRemoveConfirmationMessage', () => {
    it('formats remove confirmation message using staff.fullName', () => {
      const staff = {
        id: 1,
        fullName: 'Maria Santos',
        department: 'HOUSEKEEPING',
        active: false,
      };
      const message = formatRemoveConfirmationMessage(staff);
      assert.strictEqual(
        message,
        'Are you sure you want to permanently remove Maria Santos? This action cannot be undone and will permanently remove this staff member and their associated account from the system.'
      );
    });

    it('formats remove confirmation message using staff.name when fullName is omitted', () => {
      const staff = {
        id: 2,
        name: 'Carlos Rodriguez',
        department: 'FRONT_DESK',
        active: false,
      };
      const message = formatRemoveConfirmationMessage(staff);
      assert.strictEqual(
        message,
        'Are you sure you want to permanently remove Carlos Rodriguez? This action cannot be undone and will permanently remove this staff member and their associated account from the system.'
      );
    });

    it('falls back gracefully when staff name is missing or null', () => {
      const staff = { id: 3 };
      const message = formatRemoveConfirmationMessage(staff);
      assert.strictEqual(
        message,
        'Are you sure you want to permanently remove this staff member? This action cannot be undone and will permanently remove this staff member and their associated account from the system.'
      );
    });
  });
  describe('formatDeactivateConfirmationMessage', () => {
    it('formats confirmation message using staff.fullName', () => {
      const staff = {
        id: 1,
        fullName: 'Maria Santos',
        department: 'HOUSEKEEPING',
        active: true,
      };
      const message = formatDeactivateConfirmationMessage(staff);
      assert.strictEqual(
        message,
        'Are you sure you want to deactivate Maria Santos? They will no longer be eligible for new task assignments. You can reactivate them later if needed.'
      );
    });

    it('formats confirmation message using staff.name when fullName is omitted', () => {
      const staff = {
        id: 2,
        name: 'Carlos Rodriguez',
        department: 'FRONT_DESK',
        active: true,
      };
      const message = formatDeactivateConfirmationMessage(staff);
      assert.strictEqual(
        message,
        'Are you sure you want to deactivate Carlos Rodriguez? They will no longer be eligible for new task assignments. You can reactivate them later if needed.'
      );
    });

    it('falls back gracefully when staff name is missing or null', () => {
      const staff = { id: 3 };
      const message = formatDeactivateConfirmationMessage(staff);
      assert.strictEqual(
        message,
        'Are you sure you want to deactivate this staff member? They will no longer be eligible for new task assignments. You can reactivate them later if needed.'
      );
    });
  });

  describe('formatDepartment', () => {
    it('formats known department enums correctly', () => {
      assert.strictEqual(formatDepartment('FRONT_DESK'), 'Front Desk');
      assert.strictEqual(formatDepartment('HOUSEKEEPING'), 'Housekeeping');
      assert.strictEqual(formatDepartment('MAINTENANCE'), 'Maintenance');
      assert.strictEqual(formatDepartment('ROOM_SERVICE'), 'Room Service');
      assert.strictEqual(formatDepartment('SECURITY'), 'Security');
      assert.strictEqual(formatDepartment('CONCIERGE'), 'Concierge');
      assert.strictEqual(formatDepartment('FOOD_AND_BEVERAGE'), 'F&B');
    });

    it('falls back to General for null or undefined', () => {
      assert.strictEqual(formatDepartment(null), 'General');
      assert.strictEqual(formatDepartment(undefined), 'General');
    });
  });

  describe('isActive and isAvailable', () => {
    it('checks active statuses', () => {
      assert.strictEqual(isActive(true), true);
      assert.strictEqual(isActive('Active'), true);
      assert.strictEqual(isActive(false), false);
      assert.strictEqual(isActive('Inactive'), false);
    });

    it('checks availability statuses', () => {
      assert.strictEqual(isAvailable('AVAILABLE'), true);
      assert.strictEqual(isAvailable('Available'), true);
      assert.strictEqual(isAvailable('UNAVAILABLE'), false);
      assert.strictEqual(isAvailable('BUSY'), false);
    });
  });

  describe('getStaffDisplayName', () => {
    it('returns trimmed fullName when present', () => {
      assert.strictEqual(getStaffDisplayName({ fullName: '  Jane Doe  ' }), 'Jane Doe');
    });

    it('falls back to name when fullName is missing', () => {
      assert.strictEqual(getStaffDisplayName({ name: 'John Smith' }), 'John Smith');
    });

    it('handles missing, null, or undefined staff safely', () => {
      assert.strictEqual(getStaffDisplayName(null), '');
      assert.strictEqual(getStaffDisplayName(undefined), '');
      assert.strictEqual(getStaffDisplayName({}), '');
      assert.strictEqual(getStaffDisplayName({ fullName: null, name: null }), '');
    });
  });

  describe('compareStaffByName & sortStaffAlphabetically', () => {
    it('sorts staff members alphabetically in ascending order (A to Z)', () => {
      const staff = [
        { id: 1, fullName: 'Zachary Taylor' },
        { id: 2, fullName: 'Alice Johnson' },
        { id: 3, fullName: 'Michael Brown' },
      ];
      const sorted = sortStaffAlphabetically(staff);
      assert.deepStrictEqual(
        sorted.map((s) => s.fullName),
        ['Alice Johnson', 'Michael Brown', 'Zachary Taylor']
      );
    });

    it('performs case-insensitive comparison', () => {
      const staff = [
        { id: 1, fullName: 'charlie' },
        { id: 2, fullName: 'Alice' },
        { id: 3, fullName: 'BOB' },
        { id: 4, fullName: 'david' },
      ];
      const sorted = sortStaffAlphabetically(staff);
      assert.deepStrictEqual(
        sorted.map((s) => s.fullName),
        ['Alice', 'BOB', 'charlie', 'david']
      );
    });

    it('orders names beginning with the same letter correctly by comparing complete names', () => {
      const staff = [
        { id: 1, fullName: 'Alexandre' },
        { id: 2, fullName: 'Adam' },
        { id: 3, fullName: 'Aaron' },
        { id: 4, fullName: 'alice' },
        { id: 5, fullName: 'Alexander' },
      ];
      const sorted = sortStaffAlphabetically(staff);
      assert.deepStrictEqual(
        sorted.map((s) => s.fullName),
        ['Aaron', 'Adam', 'Alexander', 'Alexandre', 'alice']
      );
    });

    it('correctly orders prefixes and multi-word names', () => {
      const staff = [
        { id: 1, fullName: 'Carlos Santana' },
        { id: 2, fullName: 'Carlos Alberto' },
        { id: 3, fullName: 'Benjamin' },
        { id: 4, fullName: 'Ben' },
      ];
      const sorted = sortStaffAlphabetically(staff);
      assert.deepStrictEqual(
        sorted.map((s) => s.fullName),
        ['Ben', 'Benjamin', 'Carlos Alberto', 'Carlos Santana']
      );
    });

    it('handles missing, null, or empty names safely without crashing', () => {
      const staff = [
        { id: 1, fullName: 'Zack' },
        { id: 2, fullName: '' },
        { id: 3, name: null },
        { id: 4 },
        { id: 5, fullName: 'Alice' },
      ];
      const sorted = sortStaffAlphabetically(staff);
      assert.strictEqual(sorted[0].fullName, 'Alice');
      assert.strictEqual(sorted[1].fullName, 'Zack');
      // Empty/null names pushed to the end safely
      assert.strictEqual(sorted.length, 5);
    });

    it('does not mutate the original array', () => {
      const staff = [
        { id: 1, fullName: 'Bob' },
        { id: 2, fullName: 'Alice' },
      ];
      const originalCopy = [...staff];
      const sorted = sortStaffAlphabetically(staff);
      assert.notStrictEqual(sorted, staff);
      assert.deepStrictEqual(staff, originalCopy);
    });

    it('handles non-array inputs gracefully', () => {
      assert.deepStrictEqual(sortStaffAlphabetically(null), []);
      assert.deepStrictEqual(sortStaffAlphabetically(undefined), []);
      assert.deepStrictEqual(sortStaffAlphabetically('not an array'), []);
    });
  });
});
