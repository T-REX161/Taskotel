import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { FIGURE_UNAVAILABLE, departmentCount, totalOfKnownCounts } from './departmentDisplay.js';

const here = path.dirname(fileURLToPath(import.meta.url));
const src = (...parts) => fs.readFileSync(path.join(here, '..', ...parts), 'utf8').replace(/\r\n/g, '\n');

const admin = [
  { code: 'A', staffCount: 4, activeTasks: 7 },
  { code: 'B', staffCount: 0, activeTasks: 0 },
];
// What a Manager of A receives: A in full, every other department with null figures
const manager = [
  { code: 'A', staffCount: 4, activeTasks: 7 },
  { code: 'B', staffCount: null, activeTasks: null },
];
// What Staff receive: no figures at all
const staff = [{ code: 'A' }, { code: 'B' }];

describe('department figures', () => {
  it('shows a number, including a real zero, and a dash for a figure the server did not send', () => {
    assert.equal(departmentCount(4), 4);
    assert.equal(departmentCount(0), 0, 'zero is a real figure and stays zero');
    assert.equal(departmentCount('3'), 3);
    for (const hidden of [null, undefined, '', 'abc', NaN]) {
      assert.equal(departmentCount(hidden), FIGURE_UNAVAILABLE, String(hidden));
    }
  });

  it('an Admin gets real totals, with zeros counted', () => {
    assert.equal(totalOfKnownCounts(admin, 'staffCount'), 4);
    assert.equal(totalOfKnownCounts(admin, 'activeTasks'), 7);
    assert.equal(totalOfKnownCounts([], 'staffCount'), 0);
  });

  it('a total over departments with hidden figures is unavailable, never a smaller number', () => {
    assert.equal(totalOfKnownCounts(manager, 'staffCount'), null);
    assert.equal(totalOfKnownCounts(staff, 'activeTasks'), null);
    assert.equal(departmentCount(totalOfKnownCounts(manager, 'staffCount')), FIGURE_UNAVAILABLE);
    assert.equal(totalOfKnownCounts(null, 'staffCount'), 0);
  });
});

describe('Departments page (source guard)', () => {
  const page = src('pages', 'DepartmentsPage.jsx');

  it('draws counts through the helper, so a hidden figure is never shown as 0', () => {
    assert.ok(page.includes('departmentCount(dept.staffCount)') && page.includes('departmentCount(dept.activeTasks)'));
    assert.ok(page.includes("totalOfKnownCounts(departments, 'staffCount')") && page.includes("totalOfKnownCounts(departments, 'activeTasks')"));
    assert.ok(!page.includes('Number(dept.staffCount) || 0') && !page.includes('Number(dept.activeTasks) || 0'));
  });

  it('management controls stay Admin-only', () => {
    assert.ok(page.includes("const canManage = role === 'ADMIN';"));
  });
});
