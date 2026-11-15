import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { assignmentDepartmentTile } from './assignmentTableStyles.js';
import { registerDepartments } from '../../utils/departmentRegistry.js';
import { ICON_KEYS } from '../../utils/departmentStyles.js';

const page = readFileSync(new URL('../../pages/AssignmentsPage.jsx', import.meta.url), 'utf8');

test('department tile: each built-in department has its own icon and subtle colour (the shared department identity)', () => {
  const expected = {
    HOUSEKEEPING: ['sprout', 'bg-green-100 text-green-700'],
    FRONT_DESK: ['bell', 'bg-blue-100 text-blue-700'],
    MAINTENANCE: ['wrench', 'bg-purple-100 text-purple-700'],
    ROOM_SERVICE: ['coffee', 'bg-amber-100 text-amber-700'],
    FOOD_AND_BEVERAGE: ['utensils', 'bg-orange-100 text-orange-700'],
    SECURITY: ['shield', 'bg-cyan-100 text-cyan-700'],
  };
  for (const [code, [iconKey, classes]] of Object.entries(expected)) {
    assert.deepEqual(assignmentDepartmentTile(code), { iconKey, classes }, code);
  }
  const icons = Object.keys(expected).map((code) => assignmentDepartmentTile(code).iconKey);
  assert.equal(new Set(icons).size, 6, 'six different icons');
  assert.ok(icons.every((key) => ICON_KEYS.includes(key)));
});

test('department tile: missing or unknown departments get the neutral building icon in grey', () => {
  for (const value of [null, undefined, '', '   ', 'SPA', 'not a department']) {
    assert.deepEqual(assignmentDepartmentTile(value), { iconKey: 'building', classes: 'bg-gray-100 text-gray-700' }, String(value));
  }
});

test('department tile: a department\'s stored icon and colour (from the departments API) are used, as on other pages', () => {
  registerDepartments([{ code: 'SPA_WELLNESS', name: 'Spa & Wellness', iconKey: 'sparkles', colorKey: 'teal' }]);
  assert.deepEqual(assignmentDepartmentTile('SPA_WELLNESS'), { iconKey: 'sparkles', classes: 'bg-teal-100 text-teal-700' });
});

test('page: the tile sits before the title, comes from the assignment\'s actual department (never the title), and keeps long titles in check', () => {
  assert.ok(page.includes('assignmentDepartmentTile(resolveAssignmentDepartmentCode(assignment, tasks, workload))'), 'department code from the assignment/task/staff data');
  assert.doesNotMatch(page, /assignmentDepartmentTile\([^)]*taskTitle/, 'never inferred from the title');
  const cell = page.slice(page.indexOf('data-testid="assignment-department-icon"') - 400, page.indexOf('{taskTitle}</p>'));
  assert.ok(cell.includes('flex items-center gap-3 min-w-0'), 'icon and title vertically centred side by side');
  assert.ok(cell.includes('flex h-8 w-8 shrink-0 items-center justify-center rounded-lg ${departmentTile.classes}'), '32px tile that never shrinks');
  assert.ok(cell.includes('<DepartmentIcon iconKey={departmentTile.iconKey} className="w-4 h-4" />'), 'the shared department icon component');
  assert.ok(cell.includes('aria-hidden="true"'), 'decorative: the Department column still names the department');
  assert.ok(page.includes('min-w-0 max-w-[18rem] font-semibold leading-snug text-gray-900 break-words line-clamp-2" title={taskTitle}'), 'long titles still wrap to two lines');
  // the Department column and the table structure are unchanged
  assert.ok(page.includes('<td className="px-6 py-4 align-middle whitespace-nowrap text-gray-600">{department}</td>'));
  assert.ok(page.includes('<th className="px-6 py-3">Department</th>'));
});
