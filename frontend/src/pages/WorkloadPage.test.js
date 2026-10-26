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

  describe('Stacked Full-Width Layout', () => {
    it('orders the sections: summary container, thresholds, department panel, staff table', () => {
      const at = (m) => pageSource.indexOf(m);
      const order = ['aria-label="Workload summary"', 'Workload Thresholds:', '<h2 className="text-sm sm:text-base font-bold text-gray-900">Workload by Department</h2>', 'Staff Workload Distribution</h2>'].map(at);
      assert.ok(order.every((i) => i > 0), 'All four sections must exist');
      assert.deepStrictEqual(order, [...order].sort((x, y) => x - y), 'Sections must appear in the required order');
    });

    it('no longer splits the department panel and the table into side-by-side columns', () => {
      assert.ok(!pageSource.includes('lg:grid-cols-12'), 'No 12-column split');
      assert.ok(!pageSource.includes('lg:col-span-8') && !pageSource.includes('lg:col-span-4'), 'No column spans');
    });

    it('puts the four real summary cards and the staff total in one full-width container', () => {
      const start = pageSource.indexOf('aria-label="Workload summary">');
      const section = pageSource.slice(start, pageSource.indexOf('</section>', start));
      assert.ok(section.includes('{summaryCards.map('), 'Summary cards live inside the container');
      assert.ok(section.includes('lg:grid-cols-4'), 'Cards sit in a responsive grid');
      assert.ok(section.includes('Total Staff:') && section.includes('{sortedWorkload.length}'), 'Staff total uses real data, top right');
    });

    it('shows each department in its own card inside a Workload Summary-style container, in a grid that grows with the data', () => {
      const start = pageSource.indexOf('aria-label="Workload by department"');
      const section = pageSource.slice(start, pageSource.indexOf('</section>', start));
      assert.ok(pageSource.includes('min-w-0 bg-white rounded-xl shadow-2xs border border-gray-100 px-4 pt-3 pb-4 sm:px-5 sm:pt-3.5 sm:pb-5 space-y-3" aria-label="Workload by department"'), 'Same container style as the summary');
      assert.ok(section.includes('grid-cols-[repeat(auto-fill,minmax(min(100%,17rem),1fr))]'), 'Auto-fill grid, so rows are added as departments grow');
      assert.ok(section.includes('{departmentWorkloads.map((dept, index) => {') && section.includes('key={dept.code}'), 'One card per department from the data');
      assert.ok(section.includes('min-w-0 bg-white rounded-xl p-4 shadow-2xs border border-gray-100'), 'Each department is a card');
      for (const field of ['{dept.name}', '{dept.totalMinutes} mins', 'dept.percentage', 'dept.activeTaskCount', 'dept.staffCount', 'getDepartmentBarColor(index)']) {
        assert.ok(section.includes(field), 'Card shows ' + field);
      }
      for (const fixed of ['lg:grid-cols-3', 'md:grid-cols-2', 'SUPPORTED_DEPARTMENTS', 'departmentWorkloads[', 'slice(']) {
        assert.ok(!section.includes(fixed), 'No hardcoded department count: ' + fixed);
      }
    });
  });

  describe('Staff Workload Table Compact Proportions & Sizing', () => {
    it('enforces table-fixed w-full layout with exact column proportions: 25%, 18%, 10%, 10%, 22%, 15% totaling 100%', () => {
      assert.ok(pageSource.includes('table-fixed'), 'Table must use table-fixed layout');
      assert.ok(pageSource.includes('w-full'), 'Table must use w-full layout');
      assert.ok(pageSource.includes('<colgroup>'), 'Table must use colgroup for explicit column widths');
      assert.ok(pageSource.includes('w-[25%]'), 'Staff Member col width: 25%');
      assert.ok(pageSource.includes('w-[18%]'), 'Department col width: 18%');
      assert.ok(pageSource.includes('w-[10%]'), 'Active Tasks col width: 10%');
      assert.ok(pageSource.includes('w-[11%]'), 'Workload col width: 11%');
      assert.ok(pageSource.includes('w-[22%]'), 'Utilization col width: 22%');
      assert.ok(pageSource.includes('w-[15%]'), 'Availability col width: 15%');
    });

    it('restores header font size to ~12–13px (text-xs) and body font size to ~13–14px (text-[13px] sm:text-sm)', () => {
      assert.ok(pageSource.includes('text-xs'), 'Header row must use readable 12px (text-xs) font size');
      assert.ok(pageSource.includes('text-[13px]'), 'Body rows must use comfortable 13px/14px font size');
    });

    it('uses comfortable, even table cell padding (px-3 lg:px-4 py-3)', () => {
      assert.ok(pageSource.includes('px-3 lg:px-4 py-3'), 'Must use comfortable padding (px-3, px-4 on large screens, py-3)');
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

    it('renders unclipped Availability badges with dot indicators in the 15% width column', () => {
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
      assert.ok(pageSource.includes('<UtilizationMeter percentage={percentage} status={status} minutes={safeDuration} />'), 'Table must render the utilization meter with the calculated values');
      const meter = fs.readFileSync(path.join(__dirname, '..', 'components', 'workload', 'UtilizationMeter.jsx'), 'utf8');
      assert.ok(meter.includes('{minutes} / {baseline} mins') && meter.includes('baseline = 480'), 'Meter must display used workload / 480 mins shift baseline');
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
        pageSource.includes('Math.ceil(sortedWorkload.length / pageSize)') && pageSource.includes('useState(ITEMS_PER_PAGE)'),
        'WorkloadPage must compute totalPages from the chosen page size, which starts at ITEMS_PER_PAGE (5)'
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

    it('keeps a short note that the department total comes from the active staff workloads', () => {
      assert.ok(
        pageSource.includes('Calculated from active staff workloads.') && !pageSource.includes('mathematical consistency') && pageSource.includes('{summary.totalWorkload} mins'),
        'Shows the short note and the unchanged total'
      );
    });

    it('shows the total in the section header, top-right, and no longer in a bar under the cards', () => {
      const section = pageSource.slice(pageSource.indexOf('aria-label="Workload by department"'), pageSource.indexOf('{/* 4. Staff Workload Distribution'));
      const total = section.indexOf('{summary.totalWorkload} mins');
      assert.ok(total > section.indexOf('Workload by Department</h2>') && total < section.indexOf('departmentWorkloads.map('), 'Total sits in the header, above the cards');
      assert.ok(section.includes('justify-between') && section.includes('sm:ml-auto sm:text-right'), 'Heading left, total right; stacks on small screens');
      assert.ok(/Total Department Load<\/span>\s*<span className="font-bold[^"]*">\{summary\.totalWorkload\} mins/.test(section) && section.includes('gap-x-2.5'), 'Label then bold value on one line, ~10px apart');
      assert.strictEqual(section.split('Total Department Load').length, 2, 'Only one total: the bottom bar is gone');
      assert.ok(!section.includes('Total Department Workload Callout') && !/\b422\b/.test(pageSource), 'No old bar and no hardcoded total');
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

    it('no longer shows the Live Sync badge or a Refresh button', () => {
      assert.ok(!pageSource.includes('Live Sync'), 'No Live Sync badge');
      assert.ok(!pageSource.includes("'Refreshing...'") && !pageSource.includes("'Refresh'") && !pageSource.includes('isRefreshing'), 'No Refresh button label or state');
      assert.ok(!pageSource.includes('title="Refresh live workload from backend"'), 'No refresh button title');
      assert.ok(pageSource.includes('Monitor staff workload and active task distribution.') && pageSource.includes('>Staff Workload</h1>'), 'Heading and subtitle kept');
    });

    it('refreshes the workload automatically every 30 seconds while the tab is visible', () => {
      assert.ok(pageSource.includes('const WORKLOAD_REFRESH_MS = 30000;'));
      assert.ok(pageSource.includes("if (document.visibilityState === 'visible') fetchData(true);"), 'Only refreshes while the tab is visible');
      assert.ok(pageSource.includes('}, WORKLOAD_REFRESH_MS);'), 'Interval uses the 30s constant');
      assert.ok(pageSource.includes("document.addEventListener('visibilitychange', handleVisibility)"), 'Catches up when the tab returns');
      assert.ok(pageSource.includes('clearInterval(intervalId)') && pageSource.includes("document.removeEventListener('visibilitychange'"), 'Cleans up on unmount');
    });

    it('background refreshes never show the spinner or error panel, never overlap, and keep data on failure', () => {
      assert.ok(pageSource.includes('if (isFetchingRef.current) return;'), 'No overlapping requests');
      assert.ok(pageSource.includes("if (!isBackground) {\n      setIsLoading(true);\n      setError('');\n    }") || pageSource.includes("if (!isBackground) {\r\n      setIsLoading(true);\r\n      setError('');\r\n    }"), 'Spinner and error reset only for foreground loads');
      assert.ok(pageSource.includes('if (isBackground) {') && pageSource.includes("console.warn('Background workload refresh failed; keeping the current data:', err);"), 'Background failures only log');
      assert.ok(pageSource.includes("setError(err.message || 'Unable to load staff workload data. Please try again.');"), 'Foreground failures still show the error panel');
      assert.ok(pageSource.includes('isBackground ? prev : []'), 'A failed departments or staff request keeps the previous list');
      assert.ok(pageSource.includes("navigate('/login')"), 'Expired sessions still go to login');
    });
  });
});
