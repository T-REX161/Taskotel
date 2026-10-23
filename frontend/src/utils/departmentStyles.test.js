import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { getDepartmentStyle, normalizeDepartmentKey, DEPARTMENT_STYLE_KEYS, ICON_KEYS } from './departmentStyles.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

describe('departmentStyles', () => {
  it('gives each department its own colour family', () => {
    const expected = {
      HOUSEKEEPING: 'green',
      FRONT_DESK: 'blue',
      MAINTENANCE: 'purple',
      ROOM_SERVICE: 'amber',
      FOOD_AND_BEVERAGE: 'orange',
      SECURITY: 'cyan',
    };
    for (const [code, color] of Object.entries(expected)) {
      const { classes } = getDepartmentStyle(code);
      assert.ok(classes.includes('bg-' + color + '-100') && classes.includes('text-' + color + '-700'), code + ' uses ' + color);
    }
    assert.strictEqual(new Set(DEPARTMENT_STYLE_KEYS.map((k) => getDepartmentStyle(k).classes)).size, 6, 'No two departments share a colour');
  });

  it('keys colours by department identity, never by position, and accepts display names and aliases', () => {
    assert.strictEqual(getDepartmentStyle('front_desk').classes, getDepartmentStyle('FRONT_DESK').classes);
    assert.strictEqual(getDepartmentStyle('Front Desk').classes, getDepartmentStyle('FRONT_DESK').classes);
    assert.strictEqual(getDepartmentStyle('Food & Beverage').key, 'FOOD_AND_BEVERAGE');
    assert.strictEqual(getDepartmentStyle('F_AND_B').key, 'FOOD_AND_BEVERAGE');
    assert.strictEqual(normalizeDepartmentKey('Room Service'), 'ROOM_SERVICE');
    const source = fs.readFileSync(path.join(__dirname, 'departmentStyles.js'), 'utf8');
    assert.ok(!/index|idx/.test(source.replace(/\/\*[\s\S]*?\*\//g, '')), 'No position-based colouring');
  });

  it('uses one neutral fallback for unknown or missing departments', () => {
    const fallback = getDepartmentStyle('CONCIERGE');
    assert.strictEqual(fallback.classes, getDepartmentStyle('SPA').classes);
    assert.strictEqual(fallback.classes, getDepartmentStyle(null).classes);
    assert.ok(fallback.classes.includes('bg-gray-100') && fallback.iconKey === 'building' && fallback.colorKey === 'gray');
    assert.ok(!DEPARTMENT_STYLE_KEYS.some((k) => getDepartmentStyle(k).classes === fallback.classes), 'Fallback is distinct from every mapped department');
  });

  it('renders the badge through the shared component with an icon per department', () => {
    const iconSource = fs.readFileSync(path.join(__dirname, '..', 'components', 'common', 'DepartmentIcon.jsx'), 'utf8');
    for (const icon of ['HousekeepingIcon', 'FrontDeskIcon', 'MaintenanceIcon', 'RoomServiceIcon', 'FoodBeverageIcon', 'SecurityIcon', 'DepartmentsIcon']) {
      assert.ok(iconSource.includes(icon), icon + ' is used');
    }
    assert.ok(iconSource.includes('getDepartmentStyle(department).iconKey'), 'Icon comes from the department identity');
    const badge = fs.readFileSync(path.join(__dirname, '..', 'components', 'workload', 'DepartmentBadge.jsx'), 'utf8');
    assert.ok(badge.includes('getDepartmentStyle(department)') && badge.includes('<DepartmentIcon'), 'Badge colour and icon come from the department identity');
    const page = fs.readFileSync(path.join(__dirname, '..', 'pages', 'WorkloadPage.jsx'), 'utf8');
    assert.ok(page.includes('<DepartmentBadge department={item.department} />'), 'Table uses the badge');
  });
});

describe('department icon keys', () => {
  const read = (...parts) => fs.readFileSync(path.join(__dirname, ...parts), 'utf8');

  it('the dropdown offers exactly the keys the backend accepts, without duplicates', () => {
    const java = read('..', '..', '..', 'backend', 'src', 'main', 'java', 'com', 'hotel', 'workload', 'staff', 'DepartmentCatalog.java');
    const block = java.slice(java.indexOf('ICON_KEYS'), java.indexOf('COLOR_KEYS'));
    const backend = [...block.matchAll(/"([a-z]+)"/g)].map((m) => m[1]).sort();
    assert.deepStrictEqual([...ICON_KEYS].sort(), backend);
    assert.strictEqual(new Set(ICON_KEYS).size, ICON_KEYS.length);
    assert.strictEqual(ICON_KEYS.length, 17);
  });

  it('every key has an icon component and a tooltip label, and the existing mappings are kept', () => {
    const icon = read('..', 'components', 'common', 'DepartmentIcon.jsx');
    const modal = read('..', 'components', 'departments', 'DepartmentFormModal.jsx');
    for (const key of ICON_KEYS) {
      assert.ok(new RegExp('\\b' + key + ': \\w+Icon').test(icon), key + ' has a component');
      assert.ok(new RegExp('\\b' + key + ": '").test(modal), key + ' has a label');
    }
    const builtIn = { HOUSEKEEPING: 'sprout', FRONT_DESK: 'bell', MAINTENANCE: 'wrench', ROOM_SERVICE: 'coffee', FOOD_AND_BEVERAGE: 'utensils', SECURITY: 'shield' };
    for (const [code, key] of Object.entries(builtIn)) assert.strictEqual(getDepartmentStyle(code).iconKey, key, code);
    assert.ok(modal.includes('title={ICON_LABELS[key]}'), 'Hover tooltip');
  });

  it('a custom department keeps a new icon and renders it (unknown keys still fall back to the building)', () => {
    assert.strictEqual(getDepartmentStyle({ code: 'LOGISTICS', iconKey: 'truck', colorKey: 'blue' }).iconKey, 'truck');
    assert.strictEqual(getDepartmentStyle({ code: 'X', iconKey: 'rocket' }).iconKey, 'building');
  });
});
