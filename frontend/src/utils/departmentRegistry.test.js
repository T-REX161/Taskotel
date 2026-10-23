import { describe, it, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import {
  clearDepartmentRegistry,
  displayDepartmentName,
  departmentLabel,
  getDepartmentName,
  humanizeDepartmentCode,
  registerDepartmentNames,
  registerDepartments,
} from './departmentRegistry.js';
import { COLOR_KEYS, COLOR_STYLES, DEFAULT_COLOR_KEY, DEFAULT_ICON_KEY, ICON_KEYS, getDepartmentStyle } from './departmentStyles.js';
import { formatDepartment as formatStaffDepartment } from '../components/staff/staffFormatters.js';
import { formatDepartment as formatTaskDepartment } from '../components/tasks/taskFormatters.js';
import { calculateDepartmentWorkloads } from './workloadUtils.js';

describe('departmentRegistry', () => {
  beforeEach(() => clearDepartmentRegistry());

  it('names a department it has never heard of from its code', () => {
    assert.strictEqual(humanizeDepartmentCode('SPA_AND_WELLNESS'), 'Spa And Wellness');
    assert.strictEqual(departmentLabel('SPA_AND_WELLNESS'), 'Spa And Wellness');
    assert.strictEqual(departmentLabel('FOOD_AND_BEVERAGE'), 'Food & Beverage');
    assert.strictEqual(departmentLabel(null), 'General');
  });

  it('learns names from the department list and from staff, task and workload payloads', () => {
    registerDepartments([{ code: 'SPA_AND_WELLNESS', name: 'Spa & Wellness', iconKey: 'users', colorKey: 'rose', active: true }]);
    assert.strictEqual(getDepartmentName('spa_and_wellness'), 'Spa & Wellness');
    registerDepartmentNames([{ department: 'VALET', departmentName: 'Valet Parking' }, { department: 'X' }, null]);
    assert.strictEqual(getDepartmentName('VALET'), 'Valet Parking');
    assert.strictEqual(getDepartmentName('X'), undefined);
    registerDepartmentNames({ department: 'POOL', departmentName: 'Pool' });
    assert.strictEqual(getDepartmentName('POOL'), 'Pool');
  });

  it('every formatter shows a new department by its real name', () => {
    registerDepartments([{ code: 'SPA_AND_WELLNESS', name: 'Spa & Wellness' }]);
    assert.strictEqual(formatStaffDepartment('SPA_AND_WELLNESS'), 'Spa & Wellness');
    assert.strictEqual(formatTaskDepartment('SPA_AND_WELLNESS'), 'Spa & Wellness');
  });

  it('keeps the compact built-in label unless the department was renamed', () => {
    registerDepartments([{ code: 'FOOD_AND_BEVERAGE', name: 'Food & Beverage' }]);
    assert.strictEqual(formatStaffDepartment('FOOD_AND_BEVERAGE'), 'F&B');
    assert.strictEqual(displayDepartmentName('FOOD_AND_BEVERAGE', 'F&B'), 'F&B');
    registerDepartments([{ code: 'FOOD_AND_BEVERAGE', name: 'Dining' }]);
    assert.strictEqual(formatStaffDepartment('FOOD_AND_BEVERAGE'), 'Dining');
    assert.strictEqual(formatTaskDepartment('FOOD_AND_BEVERAGE'), 'Dining');
  });
});

describe('department styles from stored keys', () => {
  beforeEach(() => clearDepartmentRegistry());

  it('uses the stored colour and icon of a new department', () => {
    registerDepartments([{ code: 'SPA_AND_WELLNESS', name: 'Spa', iconKey: 'users', colorKey: 'rose' }]);
    const style = getDepartmentStyle('SPA_AND_WELLNESS');
    assert.deepStrictEqual([style.iconKey, style.colorKey], ['users', 'rose']);
    assert.strictEqual(style.classes, COLOR_STYLES.rose.classes);
    // An object (such as an API row) carries its own keys
    assert.strictEqual(getDepartmentStyle({ code: 'ANY', iconKey: 'bell', colorKey: 'indigo' }).colorKey, 'indigo');
  });

  it('the built-in six keep their colours and icons even before the list loads (older backend)', () => {
    const expected = { HOUSEKEEPING: ['sprout', 'green'], FRONT_DESK: ['bell', 'blue'], MAINTENANCE: ['wrench', 'purple'],
      ROOM_SERVICE: ['coffee', 'amber'], FOOD_AND_BEVERAGE: ['utensils', 'orange'], SECURITY: ['shield', 'cyan'] };
    for (const [code, [icon, color]] of Object.entries(expected)) {
      const style = getDepartmentStyle(code);
      assert.deepStrictEqual([style.iconKey, style.colorKey], [icon, color], code);
    }
  });

  it('unknown or invalid keys fall back to the building icon and neutral grey, consistently', () => {
    for (const input of [{ code: 'NEW', iconKey: 'rocket', colorKey: 'hotpink' }, 'NEW', null, undefined]) {
      const style = getDepartmentStyle(input);
      assert.deepStrictEqual([style.iconKey, style.colorKey], [DEFAULT_ICON_KEY, DEFAULT_COLOR_KEY]);
    }
    assert.ok(ICON_KEYS.includes(DEFAULT_ICON_KEY) && COLOR_KEYS.includes(DEFAULT_COLOR_KEY));
  });

  it('every selectable colour has full Tailwind classes and a swatch', () => {
    for (const key of COLOR_KEYS) {
      assert.match(COLOR_STYLES[key].classes, /^bg-[a-z]+-100 text-[a-z]+-700$/, key);
      assert.match(COLOR_STYLES[key].swatch, /^bg-[a-z]+-\d+$/, key);
    }
  });
});

describe('workload by department uses the real department list', () => {
  it('shows departments created later, with their keys and status, and keeps every total', () => {
    const departments = [
      { code: 'HOUSEKEEPING', name: 'Housekeeping', staffCount: 2, iconKey: 'sprout', colorKey: 'green', active: true },
      { code: 'SPA_AND_WELLNESS', name: 'Spa & Wellness', staffCount: 1, iconKey: 'users', colorKey: 'rose', active: true },
    ];
    const workload = [
      { department: 'HOUSEKEEPING', totalEstimatedDuration: 100, activeTaskCount: 2 },
      { department: 'SPA_AND_WELLNESS', departmentName: 'Spa & Wellness', totalEstimatedDuration: 300, activeTaskCount: 1 },
    ];
    const result = calculateDepartmentWorkloads(workload, departments);
    assert.deepStrictEqual(result.map((d) => d.code), ['HOUSEKEEPING', 'SPA_AND_WELLNESS']);
    const spa = result.find((d) => d.code === 'SPA_AND_WELLNESS');
    assert.deepStrictEqual([spa.totalMinutes, spa.activeTaskCount, spa.staffCount, spa.percentage, spa.colorKey], [300, 1, 1, 75, 'rose']);
    assert.strictEqual(result.reduce((sum, d) => sum + d.totalMinutes, 0), 400);
  });

  it('still returns the built-in six when no department list is available', () => {
    assert.strictEqual(calculateDepartmentWorkloads([], []).length, 6);
  });
});
