import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

describe('TaskFilters department dropdown options', () => {
  const componentPath = path.join(__dirname, 'TaskFilters.jsx');
  const source = fs.readFileSync(componentPath, 'utf8');

  // Extract the Department select dropdown block
  const deptSectionMatch = source.match(
    /\{\/\* 4\. Department dropdown \*\/\}[\s\S]*?<select[^>]*>([\s\S]*?)<\/select>/
  );

  it('renders the Department dropdown select in TaskFilters.jsx', () => {
    assert.ok(deptSectionMatch, 'Department dropdown select block should exist in TaskFilters.jsx');
  });

  const selectContent = deptSectionMatch ? deptSectionMatch[1] : '';
  const optionRegex = /<option\s+value="([^"]*)"\s*>([\s\S]*?)<\/option>/g;
  const options = [];
  let match;
  while ((match = optionRegex.exec(selectContent)) !== null) {
    options.push({ value: match[1], label: match[2].trim() });
  }

  it('includes "All Departments" as the default option with an empty value', () => {
    const allOption = options.find((opt) => opt.value === '');
    assert.ok(allOption, '"All Departments" option must exist');
    assert.strictEqual(allOption.label, 'All Departments');
  });

  it('includes only the 6 departments supported by the backend in the expected order', () => {
    const departmentValues = options.map((opt) => opt.value).filter(Boolean);
    const expectedDepartments = [
      'HOUSEKEEPING',
      'FRONT_DESK',
      'MAINTENANCE',
      'ROOM_SERVICE',
      'FOOD_AND_BEVERAGE',
      'SECURITY',
    ];
    assert.deepStrictEqual(
      departmentValues,
      expectedDepartments,
      'Department options must strictly match the backend Department enum'
    );
  });

  it('removes unsupported options (KITCHEN, SPA, and CONCIERGE)', () => {
    const unsupported = ['KITCHEN', 'SPA', 'CONCIERGE'];
    for (const dept of unsupported) {
      const exists = options.some((opt) => opt.value === dept);
      assert.strictEqual(
        exists,
        false,
        `TaskFilters must not contain unsupported option: ${dept}`
      );
    }
  });
});
