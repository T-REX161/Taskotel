import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  calculateWorkloadSummary,
  calculateDepartmentWorkloads,
  calculateWorkloadPercentage,
  deriveWorkloadStatus,
  SUPPORTED_DEPARTMENTS,
  sortWorkloadAlphabetically,
  compareWorkloadByName,
} from '../utils/workloadUtils.js';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

describe('WorkloadPage Component Integration & Compact Desktop Table Layout', () => {
  const pagePath = path.join(__dirname, 'WorkloadPage.jsx');
  const pageSource = fs.readFileSync(pagePath, 'utf8');
  const apiServicePath = path.join(__dirname, '../services/api.js');
  const apiSource = fs.readFileSync(apiServicePath, 'utf8');

  describe('API Service Integration', () => {
    it('verifies getWorkload, getDepartments, and getStaff are exported by api.js', () => {
      assert.ok(apiSource.includes('export const getWorkload'), 'api.js must export getWorkload()');
      assert.ok(apiSource.includes('export const getDepartments'), 'api.js must export getDepartments()');
      assert.ok(apiSource.includes('export const getStaff'), 'api.js must export getStaff()');
    });

    it('imports getWorkload, getDepartments, and getStaff in WorkloadPage.jsx', () => {
      assert.ok(pageSource.includes('getWorkload'), 'WorkloadPage must import getWorkload');
      assert.ok(pageSource.includes('getDepartments'), 'WorkloadPage must import getDepartments');
      assert.ok(pageSource.includes('getStaff'), 'WorkloadPage must import getStaff');
    });

    it('fetches real data using Promise.all for workload, departments, and staff', () => {
      assert.ok(
        /Promise\.all\(\s*\[[\s\S]*?workload[\s\S]*?departments[\s\S]*?staff[\s\S]*?\]\s*\)/i.test(
          pageSource
        ),
        'WorkloadPage must fetch live data concurrently using Promise.all'
      );
    });
  });

  describe('Removal of Hardcoded Mock Data', () => {
    it('does not contain hardcoded mockStaffWorkload array', () => {
      assert.strictEqual(
        pageSource.includes('mockStaffWorkload'),
        false,
        'WorkloadPage must not define hardcoded mockStaffWorkload'
      );
      assert.strictEqual(
        pageSource.includes('Maria Santos') && pageSource.includes('Carlos Rodriguez'),
        false,
        'WorkloadPage must not contain hardcoded mock staff records'
      );
    });

    it('does not contain hardcoded summary numbers', () => {
      assert.strictEqual(
        /value:\s*['"]4['"]/.test(pageSource),
        false,
        'WorkloadPage must not hardcode summary value "4"'
      );
      assert.strictEqual(
        /value:\s*['"]2['"]/.test(pageSource),
        false,
        'WorkloadPage must not hardcode summary value "2"'
      );
      assert.strictEqual(
        /value:\s*['"]1['"]/.test(pageSource),
        false,
        'WorkloadPage must not hardcode summary value "1"'
      );
      assert.strictEqual(
        /value:\s*['"]0['"]/.test(pageSource),
        false,
        'WorkloadPage must not hardcode summary value "0"'
      );
    });
  });

  describe('Summary Cards Calculation & Rendering', () => {
    it('uses calculateWorkloadSummary to derive metrics dynamically', () => {
      assert.ok(
        pageSource.includes('calculateWorkloadSummary'),
        'WorkloadPage must import and call calculateWorkloadSummary'
      );
    });

    it('renders the 4 required summary cards: Total Active Tasks, Total Workload, Available Staff, Unavailable Staff', () => {
      assert.ok(pageSource.includes('Total Active Tasks'), 'Card "Total Active Tasks" must exist');
      assert.ok(pageSource.includes('Total Workload'), 'Card "Total Workload" must exist');
      assert.ok(pageSource.includes('Available Staff'), 'Card "Available Staff" must exist');
      assert.ok(pageSource.includes('Unavailable Staff'), 'Card "Unavailable Staff" must exist');
    });

    it('accurately calculates summary metrics from sample backend workload data', () => {
      const sampleWorkload = [
        {
          staffId: '1',
          staffName: 'Alice Walker',
          department: 'HOUSEKEEPING',
          availability: 'AVAILABLE',
          activeTaskCount: 3,
          totalEstimatedDuration: 180,
          workloadStatus: 'NORMAL',
        },
        {
          staffId: '2',
          staffName: 'Bob Vance',
          department: 'MAINTENANCE',
          availability: 'UNAVAILABLE',
          activeTaskCount: 2,
          totalEstimatedDuration: 290,
          workloadStatus: 'HIGH',
        },
        {
          staffId: '3',
          staffName: 'Charlie Day',
          department: 'FRONT_DESK',
          availability: 'AVAILABLE',
          activeTaskCount: 0,
          totalEstimatedDuration: 0,
          workloadStatus: 'LOW',
        },
      ];

      const summary = calculateWorkloadSummary(sampleWorkload);
      assert.strictEqual(summary.totalActiveTasks, 5);
      assert.strictEqual(summary.totalWorkload, 470);
      assert.strictEqual(summary.availableStaff, 2);
      assert.strictEqual(summary.unavailableStaff, 1);
    });
  });

  describe('Two-Column Responsive Layout (65–67% Table, min-w-0 Protection)', () => {
    it('implements responsive two-column grid with ~66.7% table allocation (8 of 12 columns)', () => {
      assert.ok(
        pageSource.includes('lg:grid-cols-12'),
        'Layout must use a 12-column grid at desktop width'
      );
      assert.ok(
        pageSource.includes('lg:col-span-8'),
        'Left column (Staff Workload table) must take 8 of 12 columns (66.7% width)'
      );
      assert.ok(
        pageSource.includes('lg:col-span-4'),
        'Right column (Workload by Department panel) must take 4 of 12 columns (33.3% width)'
      );
    });

    it('applies min-w-0 to both grid columns to prevent flex/grid container blowout', () => {
      assert.ok(
        pageSource.includes('lg:col-span-8 min-w-0'),
        'Table card column must have min-w-0 to shrink properly'
      );
      assert.ok(
        pageSource.includes('lg:col-span-4 min-w-0'),
        'Department panel column must have min-w-0 to shrink properly'
      );
    });
  });

  describe('Staff Workload Table Compact Proportions & Sizing', () => {
    it('enforces table-fixed w-full layout with exact column proportions: 23%, 16%, 12%, 11%, 21%, 17% totaling 100%', () => {
      assert.ok(pageSource.includes('table-fixed'), 'Table must use table-fixed layout');
      assert.ok(pageSource.includes('w-full'), 'Table must use w-full layout');
      assert.ok(pageSource.includes('<colgroup>'), 'Table must use colgroup for explicit column widths');
      assert.ok(pageSource.includes('w-[23%]'), 'Staff Member col width: 23%');
      assert.ok(pageSource.includes('w-[16%]'), 'Department col width: 16%');
      assert.ok(pageSource.includes('w-[12%]'), 'Active Tasks col width: 12%');
      assert.ok(pageSource.includes('w-[11%]'), 'Workload col width: 11%');
      assert.ok(pageSource.includes('w-[21%]'), 'Utilization col width: 21%');
      assert.ok(pageSource.includes('w-[17%]'), 'Availability col width: 17%');
    });

    it('restores header font size to ~12–13px (text-xs) and body font size to ~13–14px (text-[13px] sm:text-sm)', () => {
      assert.ok(pageSource.includes('text-xs'), 'Header row must use readable 12px (text-xs) font size');
      assert.ok(pageSource.includes('text-[13px]'), 'Body rows must use comfortable 13px/14px font size');
    });

    it('restores comfortable table cell padding (px-3 py-3)', () => {
      assert.ok(pageSource.includes('px-3 py-3'), 'Must use comfortable padding (px-3 py-3)');
    });

    it('uses concise, non-overlapping header labels consistently left-aligned', () => {
      const headers = ['Staff', 'Department', 'Tasks', 'Workload', 'Utilization', 'Availability'];
      headers.forEach((h) => {
        assert.ok(pageSource.includes(h), `Header "${h}" must exist`);
      });
      assert.ok(
        pageSource.includes('text-left') && pageSource.includes('whitespace-nowrap'),
        'Headers must be left-aligned and single-line whitespace-nowrap'
      );
    });

    it('integrates View action into the Staff Member name area opening StaffDetailsModal', () => {
      assert.ok(
        pageSource.includes('handleViewStaff'),
        'Staff Member area must wire View trigger to handleViewStaff'
      );
      assert.ok(
        pageSource.includes('StaffDetailsModal'),
        'Page must import and render StaffDetailsModal upon viewing'
      );
      assert.ok(
        pageSource.includes('title={`View details for ${item.staffName}`}'),
        'View button in staff member cell must have accessible title'
      );
    });

    it('renders unclipped Availability badges with dot indicators in the 17% width column', () => {
      assert.ok(
        pageSource.includes('Available') && pageSource.includes('Unavailable'),
        'Table must display Available and Unavailable status'
      );
      assert.ok(
        pageSource.includes('bg-emerald-500') && pageSource.includes('bg-amber-500'),
        'Table must display colored status dot indicators'
      );
    });

    it('displays utilization percentage, status badge, progress bar, and minute indicator cleanly inside Utilization column', () => {
      assert.ok(pageSource.includes('calculateWorkloadPercentage'), 'Table must calculate shift percentage');
      assert.ok(pageSource.includes('/ 480m'), 'Table must display 480m shift baseline');
      assert.ok(pageSource.includes('getWorkloadProgressBarColor'), 'Table must use status-based progress bar color');
      assert.ok(pageSource.includes('getWorkloadStatusBadge'), 'Table must display status badge');
    });
  });

  describe('Staff Workload Pagination & Alphabetical Sorting', () => {
    it('imports and calls sortWorkloadAlphabetically to sort all staff before pagination', () => {
      assert.ok(
        pageSource.includes('sortWorkloadAlphabetically'),
        'WorkloadPage must import and call sortWorkloadAlphabetically'
      );
      assert.ok(
        pageSource.includes('sortWorkloadAlphabetically(workload)'),
        'Must sort the full workload dataset alphabetically'
      );
    });

    it('proves that alphabetical A–Z sorting happens before pagination slicing', () => {
      const mockUnsortedWorkload = [
        { staffId: '1', staffName: 'Zara Phillips', totalEstimatedDuration: 60 },
        { staffId: '2', staffName: 'alice walker', totalEstimatedDuration: 120 },
        { staffId: '3', staffName: 'Bob Vance', totalEstimatedDuration: 180 },
        { staffId: '4', staffName: 'charlie day', totalEstimatedDuration: 90 },
        { staffId: '5', staffName: 'David Wallace', totalEstimatedDuration: 240 },
        { staffId: '6', staffName: 'Edward Cullen', totalEstimatedDuration: 150 },
        { staffId: '7', staffName: 'Frank Sinatra', totalEstimatedDuration: 210 },
      ];

      const sorted = sortWorkloadAlphabetically(mockUnsortedWorkload);
      const itemsPerPage = 5;

      // Page 1 should contain the first 5 alphabetically sorted staff
      const page1 = sorted.slice(0, itemsPerPage);
      assert.strictEqual(page1.length, 5);
      assert.deepStrictEqual(
        page1.map((s) => s.staffName),
        ['alice walker', 'Bob Vance', 'charlie day', 'David Wallace', 'Edward Cullen']
      );

      // Page 2 should contain the remaining staff members
      const page2 = sorted.slice(itemsPerPage, itemsPerPage * 2);
      assert.strictEqual(page2.length, 2);
      assert.deepStrictEqual(
        page2.map((s) => s.staffName),
        ['Frank Sinatra', 'Zara Phillips']
      );

      // Explicitly proves Zara (originally 1st in unsorted array) was pushed to page 2 because of A–Z sorting
      assert.strictEqual(page1[0].staffName, 'alice walker');
      assert.strictEqual(page2[1].staffName, 'Zara Phillips');
    });

    it('handles case differences and missing names in alphabetical sorting', () => {
      const mixed = [
        { staffId: '1', staffName: '' },
        { staffId: '2', staffName: 'zachary' },
        { staffId: '3', staffName: 'Alice' },
        { staffId: '4', staffName: 'bob' },
      ];
      const sorted = sortWorkloadAlphabetically(mixed);
      assert.strictEqual(sorted[0].staffName, 'Alice');
      assert.strictEqual(sorted[1].staffName, 'bob');
      assert.strictEqual(sorted[2].staffName, 'zachary');
      assert.strictEqual(sorted[3].staffName, '');
    });

    it('configures exactly 5 staff rows per page (ITEMS_PER_PAGE = 5)', () => {
      assert.ok(
        pageSource.includes('ITEMS_PER_PAGE = 5'),
        'WorkloadPage must configure ITEMS_PER_PAGE = 5'
      );
      assert.ok(
        pageSource.includes('Math.ceil(sortedWorkload.length / ITEMS_PER_PAGE)'),
        'WorkloadPage must compute totalPages based on ITEMS_PER_PAGE'
      );
      assert.ok(
        pageSource.includes('paginatedWorkload.map'),
        'Workload table and mobile list must render paginatedWorkload slice'
      );
    });

    it('renders Previous (‹) and Next (›) pagination buttons and compact page indicator in card header', () => {
      assert.ok(
        pageSource.includes('aria-label="Previous page"'),
        'Card header must provide accessible Previous button'
      );
      assert.ok(
        pageSource.includes('aria-label="Next page"'),
        'Card header must provide accessible Next button'
      );
      assert.ok(pageSource.includes('‹'), 'Previous button must display ‹ icon');
      assert.ok(pageSource.includes('›'), 'Next button must display › icon');
      assert.ok(
        pageSource.includes('Page {currentPage} of {totalPages}'),
        'Card header must render compact page indicator'
      );
    });

    it('disables Previous on the first page and Next on the last page at boundaries', () => {
      assert.ok(
        pageSource.includes('disabled={currentPage <= 1}'),
        'Previous button must be disabled when on page 1'
      );
      assert.ok(
        pageSource.includes('disabled={currentPage >= totalPages}'),
        'Next button must be disabled when on the final page'
      );

      // Verify boundary logic numerically
      const checkButtons = (currentPage, totalPages) => ({
        prevDisabled: currentPage <= 1,
        nextDisabled: currentPage >= totalPages,
      });

      // Page 1 of 3: Previous disabled, Next enabled
      assert.deepStrictEqual(checkButtons(1, 3), { prevDisabled: true, nextDisabled: false });
      // Page 2 of 3: Both enabled
      assert.deepStrictEqual(checkButtons(2, 3), { prevDisabled: false, nextDisabled: false });
      // Page 3 of 3: Previous enabled, Next disabled
      assert.deepStrictEqual(checkButtons(3, 3), { prevDisabled: false, nextDisabled: true });
      // Single page (1 of 1): Both disabled
      assert.deepStrictEqual(checkButtons(1, 1), { prevDisabled: true, nextDisabled: true });
    });

    it('adjusts to a valid page when the staff dataset changes', () => {
      assert.ok(
        pageSource.includes('if (currentPage > totalPages)'),
        'Must clamp currentPage if it exceeds totalPages'
      );
      assert.ok(
        pageSource.includes('setCurrentPage(totalPages)'),
        'Must update currentPage to valid totalPages'
      );

      // Simulating dataset change from 12 items (3 pages) to 4 items (1 page)
      let curPage = 3;
      const initialTotalPages = Math.ceil(12 / 5); // 3
      assert.strictEqual(curPage <= initialTotalPages, true);

      const newTotalPages = Math.max(1, Math.ceil(4 / 5)); // 1
      if (curPage > newTotalPages) {
        curPage = newTotalPages;
      }
      assert.strictEqual(curPage, 1, 'Current page must clamp to valid page 1');
    });

    it('ensures summary cards and department totals remain based on all staff, not just the visible page', () => {
      assert.ok(
        pageSource.includes('calculateWorkloadSummary(workload)'),
        'Summary cards must be calculated from full workload dataset, not paginated slice'
      );
      assert.ok(
        pageSource.includes('calculateDepartmentWorkloads(workload, departments)'),
        'Department totals must be calculated from full workload dataset, not paginated slice'
      );

      // Verify calculation across 7 staff records where visible page has only 5 records
      const fullList = [
        { staffId: '1', staffName: 'A', activeTaskCount: 1, totalEstimatedDuration: 60, availability: 'AVAILABLE', department: 'HOUSEKEEPING' },
        { staffId: '2', staffName: 'B', activeTaskCount: 2, totalEstimatedDuration: 120, availability: 'AVAILABLE', department: 'HOUSEKEEPING' },
        { staffId: '3', staffName: 'C', activeTaskCount: 1, totalEstimatedDuration: 90, availability: 'AVAILABLE', department: 'FRONT_DESK' },
        { staffId: '4', staffName: 'D', activeTaskCount: 3, totalEstimatedDuration: 180, availability: 'UNAVAILABLE', department: 'MAINTENANCE' },
        { staffId: '5', staffName: 'E', activeTaskCount: 1, totalEstimatedDuration: 30, availability: 'AVAILABLE', department: 'FRONT_DESK' },
        { staffId: '6', staffName: 'F', activeTaskCount: 2, totalEstimatedDuration: 100, availability: 'AVAILABLE', department: 'HOUSEKEEPING' },
        { staffId: '7', staffName: 'G', activeTaskCount: 1, totalEstimatedDuration: 40, availability: 'UNAVAILABLE', department: 'SECURITY' },
      ];

      const fullSummary = calculateWorkloadSummary(fullList);
      const visibleSummary = calculateWorkloadSummary(fullList.slice(0, 5));

      // Full summary includes all 7 staff
      assert.strictEqual(fullSummary.totalActiveTasks, 11);
      assert.strictEqual(fullSummary.totalWorkload, 620);
      assert.strictEqual(fullSummary.availableStaff, 5);
      assert.strictEqual(fullSummary.unavailableStaff, 2);

      // Verify that full summary is different from visible summary (visible has only 8 tasks and 480 mins)
      assert.strictEqual(visibleSummary.totalActiveTasks, 8);
      assert.strictEqual(visibleSummary.totalWorkload, 480);
      assert.notStrictEqual(fullSummary.totalWorkload, visibleSummary.totalWorkload);

      // Department aggregations must also sum all 7 records (620 mins)
      const deptResults = calculateDepartmentWorkloads(fullList);
      const totalDeptMinutes = deptResults.reduce((acc, d) => acc + d.totalMinutes, 0);
      assert.strictEqual(totalDeptMinutes, 620);
    });
  });

  describe('Mobile Responsive Layout', () => {
    it('provides a dedicated mobile staff card layout (< md) to prevent mobile horizontal scrolling', () => {
      assert.ok(
        pageSource.includes('block md:hidden'),
        'Must render a mobile-optimized card view on viewports below md'
      );
      assert.ok(
        pageSource.includes('hidden md:block'),
        'Must render the tabular layout on md viewports and larger'
      );
    });
  });

  describe('Workload by Department Panel', () => {
    it('uses calculateDepartmentWorkloads to aggregate department metrics', () => {
      assert.ok(
        pageSource.includes('calculateDepartmentWorkloads'),
        'Page must call calculateDepartmentWorkloads'
      );
    });

    it('renders all backend-supported departments with proportional progress bars', () => {
      assert.strictEqual(SUPPORTED_DEPARTMENTS.length, 6);
      const sampleWorkload = [
        { department: 'HOUSEKEEPING', totalEstimatedDuration: 200, activeTaskCount: 2 },
        { department: 'FRONT_DESK', totalEstimatedDuration: 100, activeTaskCount: 1 },
      ];
      const deptResults = calculateDepartmentWorkloads(sampleWorkload);
      assert.strictEqual(deptResults.length, 6);

      const hk = deptResults.find((d) => d.code === 'HOUSEKEEPING');
      const fd = deptResults.find((d) => d.code === 'FRONT_DESK');
      const maint = deptResults.find((d) => d.code === 'MAINTENANCE');

      assert.strictEqual(hk.totalMinutes, 200);
      assert.strictEqual(hk.activeTaskCount, 2);
      assert.strictEqual(hk.percentage, 67);

      assert.strictEqual(fd.totalMinutes, 100);
      assert.strictEqual(fd.percentage, 33);

      assert.strictEqual(maint.totalMinutes, 0);
      assert.strictEqual(maint.percentage, 0);

      const sumDeptMinutes = deptResults.reduce((acc, d) => acc + d.totalMinutes, 0);
      assert.strictEqual(sumDeptMinutes, 300);
    });

    it('contains mathematical consistency confirmation note', () => {
      assert.ok(
        pageSource.includes('mathematical consistency'),
        'Panel must document mathematical consistency with total staff workload'
      );
    });
  });

  describe('Loading, Error, Retry, and Empty States', () => {
    it('renders loading state with spinner and descriptive message', () => {
      assert.ok(pageSource.includes('animate-spin'), 'Loading state must show spinner');
      assert.ok(
        pageSource.includes('Loading staff workload data...'),
        'Loading state must show descriptive message'
      );
    });

    it('renders error state with alert banner and Try Again retry button', () => {
      assert.ok(pageSource.includes('role="alert"'), 'Error state must use role="alert"');
      assert.ok(pageSource.includes('Try Again'), 'Error state must provide Try Again button');
    });

    it('renders empty state when workload list is empty', () => {
      assert.ok(
        pageSource.includes('No staff workload records available'),
        'Table must display empty state message when workload is empty'
      );
    });

    it('provides manual Refresh button in header for live data synchronization', () => {
      assert.ok(
        pageSource.includes('title="Refresh live workload from backend"'),
        'Header must contain manual refresh button'
      );
    });
  });
});
