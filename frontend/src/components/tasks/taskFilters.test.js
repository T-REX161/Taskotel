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

  it('lists the departments from the data instead of a hardcoded list', () => {
    assert.ok(selectContent.includes('<DepartmentOptions departments={departments} />'),
      'Options must be generated from the department list');
    assert.ok(source.includes("import useDepartments from '../../hooks/useDepartments';") && source.includes('useDepartments()'),
      'The list must come from useDepartments');
    // The only literal option is "All Departments"
    assert.deepStrictEqual(options.map((opt) => opt.value), ['']);
  });

  it('shows every department, active or inactive, because existing tasks may belong to an inactive one', () => {
    assert.ok(!selectContent.includes('activeOnly'), 'The filter must not hide inactive departments');
  });

  it('no longer hardcodes any department code', () => {
    for (const code of ['HOUSEKEEPING', 'FRONT_DESK', 'MAINTENANCE', 'ROOM_SERVICE', 'FOOD_AND_BEVERAGE', 'SECURITY', 'KITCHEN', 'SPA', 'CONCIERGE']) {
      assert.ok(!source.includes(code), 'TaskFilters must not hardcode ' + code);
    }
  });
});
