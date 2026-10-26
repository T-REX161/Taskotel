import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  sortStaffAlphabetically,
  compareStaffByName,
  formatDepartment,
  isAvailable,
  isActive,
} from './staffFormatters.js';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

describe('Staff Alphabetical Sorting and Filtering Integration', () => {
  const staffPageSource = fs.readFileSync(path.join(__dirname, '../../pages/StaffPage.jsx'), 'utf8');

  describe('StaffPage.jsx integration', () => {
    it('imports sortStaffAlphabetically in StaffPage.jsx', () => {
      assert.ok(
        staffPageSource.includes('sortStaffAlphabetically'),
        'StaffPage.jsx must import sortStaffAlphabetically from staffFormatters'
      );
    });

    it('applies sortStaffAlphabetically to the filtered staff list before pagination', () => {
      assert.ok(
        staffPageSource.includes('sortStaffAlphabetically(filtered)') ||
        staffPageSource.includes('sortStaffAlphabetically(mergedList)'),
        'StaffPage.jsx must apply sortStaffAlphabetically'
      );
      // Verify pagination slices from the sorted list
      assert.ok(
        /paginatedStaffList\s*=\s*useMemo\(\(\)\s*=>\s*\{[\s\S]*?filteredStaffList\.slice\(start,\s*start\s*\+\s*pageSize\)/.test(
          staffPageSource
        ),
        'paginatedStaffList must slice from filteredStaffList which has been sorted before pagination'
      );
      // ...with the chosen rows per page, 10 unless the user picks another size
      assert.ok(staffPageSource.includes('const ITEMS_PER_PAGE = 10;') && staffPageSource.includes('useState(ITEMS_PER_PAGE)'), 'Default page size stays 10');
    });
  });

  describe('Sorting and Filtering Pipeline', () => {
    const rawStaffList = [
      { id: 1, fullName: 'Zachary Taylor', department: 'HOUSEKEEPING', availability: 'AVAILABLE', active: true },
      { id: 2, fullName: 'carlos santana', department: 'FRONT_DESK', availability: 'AVAILABLE', active: true },
      { id: 3, fullName: 'Alice Johnson', department: 'HOUSEKEEPING', availability: 'AVAILABLE', active: true },
      { id: 4, name: 'bob smith', department: 'MAINTENANCE', availability: 'UNAVAILABLE', active: true },
      { id: 5, fullName: 'Carlos Alberto', department: 'FRONT_DESK', availability: 'AVAILABLE', active: false },
      { id: 6, fullName: 'Aaron Burr', department: 'HOUSEKEEPING', availability: 'AVAILABLE', active: true },
      { id: 7, fullName: 'Adam West', department: 'HOUSEKEEPING', availability: 'AVAILABLE', active: true },
      { id: 8, fullName: 'benjamin franklin', department: 'HOUSEKEEPING', availability: 'AVAILABLE', active: true },
      { id: 9, fullName: 'Ben Adams', department: 'HOUSEKEEPING', availability: 'AVAILABLE', active: true },
      { id: 10, fullName: 'Abigail Williams', department: 'HOUSEKEEPING', availability: 'AVAILABLE', active: true },
      { id: 11, fullName: 'Amanda Waller', department: 'HOUSEKEEPING', availability: 'AVAILABLE', active: true },
      { id: 12, fullName: 'Arthur Dent', department: 'HOUSEKEEPING', availability: 'AVAILABLE', active: true },
    ];

    it('sorts all staff members alphabetically (A to Z) case-insensitively', () => {
      const sorted = sortStaffAlphabetically(rawStaffList);
      const names = sorted.map((s) => s.fullName || s.name);
      assert.deepStrictEqual(names, [
        'Aaron Burr',
        'Abigail Williams',
        'Adam West',
        'Alice Johnson',
        'Amanda Waller',
        'Arthur Dent',
        'Ben Adams',
        'benjamin franklin',
        'bob smith',
        'Carlos Alberto',
        'carlos santana',
        'Zachary Taylor',
      ]);
    });

    it('correctly filters by department and maintains alphabetical sorting', () => {
      const housekeepingOnly = rawStaffList.filter((s) => s.department === 'HOUSEKEEPING');
      const sortedHousekeeping = sortStaffAlphabetically(housekeepingOnly);
      const names = sortedHousekeeping.map((s) => s.fullName);
      assert.deepStrictEqual(names, [
        'Aaron Burr',
        'Abigail Williams',
        'Adam West',
        'Alice Johnson',
        'Amanda Waller',
        'Arthur Dent',
        'Ben Adams',
        'benjamin franklin',
        'Zachary Taylor',
      ]);
    });

    it('correctly filters by active status and maintains alphabetical sorting', () => {
      const activeOnly = rawStaffList.filter((s) => s.active === true);
      const sortedActive = sortStaffAlphabetically(activeOnly);
      const names = sortedActive.map((s) => s.fullName || s.name);
      assert.ok(!names.includes('Carlos Alberto'), 'Inactive staff should be excluded when filtering by active');
      assert.deepStrictEqual(names, [
        'Aaron Burr',
        'Abigail Williams',
        'Adam West',
        'Alice Johnson',
        'Amanda Waller',
        'Arthur Dent',
        'Ben Adams',
        'benjamin franklin',
        'bob smith',
        'carlos santana',
        'Zachary Taylor',
      ]);
    });

    it('correctly filters by search term and maintains alphabetical sorting', () => {
      const searchTerm = 'carl';
      const searchMatches = rawStaffList.filter((s) =>
        (s.fullName || s.name).toLowerCase().includes(searchTerm)
      );
      const sortedMatches = sortStaffAlphabetically(searchMatches);
      const names = sortedMatches.map((s) => s.fullName);
      assert.deepStrictEqual(names, ['Carlos Alberto', 'carlos santana']);
    });
  });

  describe('Sorting and Pagination Interaction', () => {
    const ITEMS_PER_PAGE = 5;
    const generateStaffBatch = () => [
      { id: 1, fullName: 'George Washington' },
      { id: 2, fullName: 'Abraham Lincoln' },
      { id: 3, fullName: 'Thomas Jefferson' },
      { id: 4, fullName: 'John Adams' },
      { id: 5, fullName: 'James Madison' },
      { id: 6, fullName: 'James Monroe' },
      { id: 7, fullName: 'Andrew Jackson' },
      { id: 8, fullName: 'John Quincy Adams' },
      { id: 9, fullName: 'Martin Van Buren' },
      { id: 10, fullName: 'william henry harrison' },
      { id: 11, fullName: 'John Tyler' },
      { id: 12, fullName: 'James K. Polk' },
    ];

    it('applies alphabetical sorting before pagination across all pages', () => {
      const staff = generateStaffBatch();
      const sortedAll = sortStaffAlphabetically(staff);

      // Page 1 (items 0..4)
      const page1 = sortedAll.slice(0, ITEMS_PER_PAGE);
      const page1Names = page1.map((s) => s.fullName);
      assert.deepStrictEqual(page1Names, [
        'Abraham Lincoln',
        'Andrew Jackson',
        'George Washington',
        'James K. Polk',
        'James Madison',
      ]);

      // Page 2 (items 5..9)
      const page2 = sortedAll.slice(ITEMS_PER_PAGE, ITEMS_PER_PAGE * 2);
      const page2Names = page2.map((s) => s.fullName);
      assert.deepStrictEqual(page2Names, [
        'James Monroe',
        'John Adams',
        'John Quincy Adams',
        'John Tyler',
        'Martin Van Buren',
      ]);

      // Page 3 (items 10..11)
      const page3 = sortedAll.slice(ITEMS_PER_PAGE * 2, ITEMS_PER_PAGE * 3);
      const page3Names = page3.map((s) => s.fullName);
      assert.deepStrictEqual(page3Names, [
        'Thomas Jefferson',
        'william henry harrison',
      ]);

      // Cross-page ordering invariant: last element of page N <= first element of page N+1
      assert.ok(
        compareStaffByName(page1[page1.length - 1], page2[0]) < 0,
        'Last item of page 1 must alphabetically precede first item of page 2'
      );
      assert.ok(
        compareStaffByName(page2[page2.length - 1], page3[0]) < 0,
        'Last item of page 2 must alphabetically precede first item of page 3'
      );
    });
  });
});
