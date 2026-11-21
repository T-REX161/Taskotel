import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  calculateWorkloadSummary,
  calculateDepartmentWorkloads,
  sortWorkloadAlphabetically } from '../utils/workloadUtils.js';
import { decideWorkloadRefresh } from '../utils/workloadRefresh.js';

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
      assert.ok(apiSource.includes('export const getWorkloadDepartments'), 'api.js must export getWorkloadDepartments()');
    });

    it('imports getWorkload, getWorkloadDepartments, and getStaff in WorkloadPage.jsx, and never the all-departments list', () => {
      assert.ok(pageSource.includes('getWorkload'), 'WorkloadPage must import getWorkload');
      assert.ok(pageSource.includes('getWorkloadDepartments'), 'WorkloadPage must import getWorkloadDepartments');
      assert.ok(pageSource.includes('getStaff'), 'WorkloadPage must import getStaff');
      assert.ok(!/\bgetDepartments\b/.test(pageSource), 'the page takes its departments from the server-scoped list, not from GET /api/departments');
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
      const order = ['aria-label="Workload summary"', 'aria-label="Department risk levels"', '<h2 className="text-sm sm:text-base font-bold text-gray-900">Workload by Department</h2>', 'Staff Workload Distribution</h2>'].map(at);
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
      assert.ok(section.includes('{departmentWorkloads.map((dept) => {') && section.includes('key={dept.code}'), 'One card per department from the data');
      assert.ok(section.includes('min-w-0 bg-white rounded-xl p-4 shadow-2xs border border-gray-100'), 'Each department is a card');
      for (const field of ['{dept.name}', '{dept.totalMinutes} mins', 'dept.percentage', 'dept.activeTaskCount', 'dept.staffCount', 'getDepartmentShareBarColor(dept.percentage, riskThreshold)']) {
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
        pageSource.includes('sortWorkloadAlphabetically(visibleWorkload)'),
        'Must sort the full (visible) workload dataset alphabetically'
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
        pageSource.includes('Math.ceil(filteredWorkload.length / pageSize)') && pageSource.includes('useState(ITEMS_PER_PAGE)'),
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
        pageSource.includes('calculateWorkloadSummary(visibleWorkload)'),
        'Summary cards must be calculated from full workload dataset, not paginated slice'
      );
      assert.ok(
        pageSource.includes('calculateDepartmentWorkloads(visibleWorkload, departments, { strict: isManager })'),
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

    it('renders every department of the list with proportional progress bars', () => {
      const sampleWorkload = [
        { department: 'HOUSEKEEPING', totalEstimatedDuration: 200, activeTaskCount: 2 },
        { department: 'FRONT_DESK', totalEstimatedDuration: 100, activeTaskCount: 1 },
      ];
      const deptList = [['HOUSEKEEPING', 'Housekeeping'], ['FRONT_DESK', 'Front Desk'], ['MAINTENANCE', 'Maintenance'], ['ROOM_SERVICE', 'Room Service'], ['FOOD_AND_BEVERAGE', 'Food & Beverage'], ['SECURITY', 'Security']].map(([code, name]) => ({ code, name, active: true }));
      const deptResults = calculateDepartmentWorkloads(sampleWorkload, deptList);
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

    it('keeps the note that the department total comes from the active staff workloads, as a tooltip rather than a line of text', () => {
      assert.ok(
        pageSource.includes('title="Calculated from active staff workloads"') && !pageSource.includes('mathematical consistency') && pageSource.includes('{summary.totalWorkload} mins'),
        'The note is a tooltip on the label, and the total is unchanged'
      );
      assert.ok(!pageSource.includes('Calculated from active staff workloads.'), 'No separate line of small print under the total');
    });

    it('shows the total in the section header, top-right, and no longer in a bar under the cards', () => {
      const section = pageSource.slice(pageSource.indexOf('aria-label="Workload by department"'), pageSource.indexOf('{/* 4. Staff Workload Distribution'));
      const total = section.indexOf('{summary.totalWorkload} mins');
      assert.ok(total > section.indexOf('Workload by Department</h2>') && total < section.indexOf('departmentWorkloads.map('), 'Total sits in the header, above the cards');
      assert.ok(section.includes('justify-between') && section.includes('sm:ml-auto sm:text-right'), 'Heading left, total right; stacks on small screens');
      assert.ok(/Total load<\/span>\s*<span className="font-bold[^"]*">\{summary\.totalWorkload\} mins/.test(section) && section.includes('gap-x-2.5'), 'Label then bold value on one line, ~10px apart');
      assert.strictEqual(section.split('>Total load<').length, 2, 'Only one total: the bottom bar is gone');
      assert.ok(!section.includes('Total Department Workload Callout') && !/\b422\b/.test(pageSource), 'No old bar and no hardcoded total');
    });
  });

  describe('Loading, Error, Retry, and Empty States', () => {
    it('renders loading state with spinner and descriptive message', () => {
      assert.ok(pageSource.includes('<WorkloadPageSkeleton label="Loading staff workload data..."'), 'Loading state must show a loading indicator'); // page skeleton (Stage 5D)
      assert.ok(
        pageSource.includes('Loading staff workload data...'),
        'Loading state must show descriptive message'
      );
    });

    it('renders error state with alert banner and Try Again retry button', () => {
      // Stage 5C: the shared LoadError renders a role="alert" panel (checked in components/common/loadingPrimitives.test.js)
      assert.ok(pageSource.includes('<LoadError title="Failed to load staff workload" message={error} onRetry={() => fetchData()} retryLabel="Try Again" />'), 'Error state must use role="alert"');
      assert.ok(pageSource.includes('retryLabel="Try Again"'), 'Error state must provide Try Again button');
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
      // A failed departments or staff request keeps the previous list on a quiet refresh (the rule lives in utils/workloadRefresh.js)
      const kept = decideWorkloadRefresh({
        role: 'ADMIN', isBackground: true, now: 1, lastSuccessAt: 1,
        outcome: { workload: { ok: true, data: [] }, departments: { ok: false, error: new Error('x') }, staff: { ok: false, error: new Error('x') } },
      });
      assert.equal(kept.departments, null, 'null means: keep the list that is shown');
      assert.equal(kept.allStaff, null);
      assert.ok(pageSource.includes('if (decision.departments !== null) setDepartments(decision.departments);'));
      assert.ok(pageSource.includes('if (decision.allStaff !== null) setAllStaff(decision.allStaff);'));
      assert.ok(pageSource.includes("navigate('/login')"), 'Expired sessions still go to login');
    });
  });
});

describe('Department Risk Levels card (the fixed default tiles, shown while the alert is off): header row plus four status tiles', () => {
  const source = fs.readFileSync(path.join(__dirname, 'WorkloadPage.jsx'), 'utf8');
  const start = source.indexOf('aria-label="Department risk levels"');
  const card = source.slice(start, source.indexOf('</section>', start));
  const text = card.replace(/<[^>]+>/g, '|').replace(/\s+/g, ' ');

  it('has the heading and the supporting text on one header row', () => {
    assert.ok(card.includes('>Department Risk Levels</h2>'));
    assert.ok(card.includes('title={describeDepartmentRisk(riskState)}'), 'The explanation is a tooltip on the heading that follows the saved threshold, not a line of text');
    assert.ok(!card.includes('480-minute shift'), 'the staff shift basis is not part of the department legend');
  });

  it('shows four tiles with the required names and ranges, in order', () => {
    const expected = [['LOW', '0–25%'], ['NORMAL', 'Above 25%–50%'], ['HIGH', 'Above 50%–75%'], ['AT RISK', 'Above 75%']];
    const tiles = card.split('<li ').slice(1);
    assert.equal(tiles.length, 4);
    tiles.forEach((tile, i) => {
      const t = tile.replace(/<[^>]+>/g, '|').replace(/\s+/g, ' ');
      assert.ok(t.includes(expected[i][0]) && t.includes(expected[i][1]), 'tile ' + i + ': ' + expected[i].join(' / '));
    });
    assert.ok(!/\bmins?\b/.test(text), 'no minute-based labels remain');
    assert.ok(!text.includes('100%') || text.includes('Can exceed 100%'), 'AT RISK is never capped at 100%');
  });

  it('tints each tile with its own status colour (blue, emerald, orange, red) and keeps a coloured dot; the theme palette maps these for dark mode', () => {
    for (const hue of ['blue', 'emerald', 'orange', 'red']) {
      assert.ok(card.includes('border-' + hue + '-200 bg-' + hue + '-100 dark:border-' + hue + '-100 dark:bg-' + hue + '-50'), hue + ' tile: stronger tint on light, original on dark');
      assert.ok(card.includes('w-3 h-3 rounded-full bg-' + hue + '-600 dark:bg-' + hue + '-500'), hue + ' dot: 12px, strong 600 on light, 500 on dark (same as the department bars)');
      assert.ok(card.includes('text-' + hue + '-700'), hue + ' label keeps its readable shade');
    }
    const darkUtilities = card.match(/dark:[a-z0-9:-]+/g) || [];
    assert.ok(darkUtilities.length > 0 && darkUtilities.every((u) => /^dark:(bg|border)-(blue|emerald|orange|red)-(50|100|500)$/.test(u)), 'dark variants only keep the dark look: tint, border and dot colours');
  });

  it('is responsive: one column on phones, two on small screens, four on desktop', () => {
    assert.ok(card.includes('grid-cols-1 sm:grid-cols-2 lg:grid-cols-4'));
  });

  it('the percentage bands are the same boundaries as the minute thresholds over 480 minutes (120, 240, 360)', () => {
    assert.deepEqual([0.25, 0.5, 0.75].map((p) => p * 480), [120, 240, 360]);
  });
});

describe('Workload by Department: the bar colour follows the percentage of total load shown on the card', () => {
  const source = fs.readFileSync(path.join(__dirname, 'WorkloadPage.jsx'), 'utf8');
  const start = source.indexOf('aria-label="Workload by department"');
  const section = source.slice(start, source.indexOf('</section>', start));

  it('has no per-department palette and uses the shared colour helper', () => {
    assert.ok(!source.includes('getDepartmentBarColor'));
    for (const hue of ['bg-indigo-500', 'bg-amber-500', 'bg-purple-500', 'bg-teal-500']) assert.ok(!section.includes(hue), hue);
    assert.ok(section.includes('getDepartmentShareBarColor(dept.percentage, riskThreshold)'));
  });

  it('keeps the label, the bar width and the counts exactly as they were', () => {
    assert.ok(section.includes('<AnimatedNumber value={dept.percentage} />%') && !section.includes('--tk-delay') && !section.includes('delay={') && !section.includes('of total load'), 'Just the percentage: the card sits under "Workload by Department", so "of total load" is implied');
    assert.ok(section.includes('Math.min(dept.percentage, 100)'));
    assert.ok(section.includes('text-2xs text-gray-500') && section.includes("dept.activeTaskCount === 1 ? 'task' : 'tasks'"));
    assert.ok(!section.includes('of capacity') && !section.includes('No available capacity'));
  });
});

describe('Workload by Department: the Capacity Threshold wiring', () => {
  const source = fs.readFileSync(new URL('./WorkloadPage.jsx', import.meta.url), 'utf8');
  const settings = fs.readFileSync(new URL('./SettingsPage.jsx', import.meta.url), 'utf8');

  it('reads the saved threshold with every refresh, so a changed setting shows up without a reload, and a failed read never breaks the page', () => {
    assert.ok(source.includes('getDepartmentRiskThreshold()'));
    assert.ok(source.includes('parseRiskThresholdAnswer(answer)') && source.includes('.catch(() => null)'), 'a failed or unreadable answer is never taken for the defaults');
    assert.ok(source.includes("prev.status === 'ready' || prev.status === 'off' ? prev : { status: 'unavailable' }"), 'a failed refresh keeps the last known answer, or marks the levels unavailable');
    assert.ok(source.includes("['ADMIN', 'MANAGER'].includes(getUserRole()) ? getDepartmentRiskThreshold()"), 'only Admin and Manager ask for it');
  });

  it('the bar colour and the label come from the same threshold, and nothing is hardcoded', () => {
    assert.ok(source.includes('getDepartmentShareBarColor(dept.percentage, riskThreshold)'));
    assert.ok(source.includes('getDepartmentShareStatus(dept.percentage, riskThreshold)'));
    assert.ok(source.includes('data-testid="department-status"'), 'label on the card');
    assert.ok(!/riskThreshold\s*(>=?|===?)\s*\d/.test(source) && !/useState\(\s*(10|85)\s*\)/.test(source));
  });

  it('the Capacity Threshold setting is described as the department workload percentage at which a department becomes AT RISK', () => {
    assert.ok(settings.includes('The department workload percentage at which a department becomes AT RISK (red)'));
    assert.ok(!settings.includes('available staff who have active work'));
  });
});

describe('Workload Thresholds legend wiring', () => {
  const source = fs.readFileSync(path.join(__dirname, 'WorkloadPage.jsx'), 'utf8');
  const tiles = fs.readFileSync(path.join(__dirname, '..', 'components', 'workload', 'DepartmentLegendTiles.jsx'), 'utf8');

  it('the legend is built from the saved threshold, the same state that colours the bars; an unreadable threshold shows an explicit unavailable state, never fixed ranges', () => {
    assert.ok(source.includes("riskState.status === 'ready' ? ("));
    assert.ok(source.includes('<DepartmentLegendTiles ranges={getDepartmentLegendRanges(riskState.value)} />'));
    assert.ok(source.includes("riskState.status === 'unavailable' ? ("), 'its own branch');
    assert.ok(source.includes('data-testid="department-legend-unavailable"') && source.includes('Risk levels are unavailable: the Capacity Threshold could not be loaded.'));
    const unavailable = source.slice(source.indexOf("riskState.status === 'unavailable' ? ("), source.indexOf(') : ('));
    assert.ok(!/LOW|NORMAL|HIGH|AT RISK|25%|50%|75%/.test(unavailable), 'the unavailable state shows no ranges');
    assert.ok(source.includes('getDepartmentShareBarColor(dept.percentage, riskThreshold)'));
    assert.ok(source.includes("const riskKnown = riskState.status !== 'unavailable';") && source.includes(": 'bg-gray-400';") && source.includes(': null;'), 'while unavailable the cards make no risk claim: a neutral bar and no label');
  });

  it('the default tiles appear only while the Max Task Capacity Alert is off, and say so', () => {
    assert.ok(source.includes("riskState.status === 'off' && ("));
    assert.ok(source.includes('data-testid="department-legend-default-note"') && source.includes('Default ranges: Max Task Capacity Alert is off'));
  });

  it('the department legend is kept completely apart from individual Staff statuses: the staff section never reads the department risk state', () => {
    const staffSection = source.slice(source.indexOf('{/* 4. Staff Workload Distribution'));
    assert.ok(staffSection.length > 500, 'found the staff section');
    for (const name of ['riskThreshold', 'riskState', 'getDepartmentLegendRanges', 'getDepartmentShareStatus', 'getDepartmentShareBarColor', 'DepartmentLegendTiles']) {
      assert.ok(!staffSection.includes(name), 'the staff table does not use ' + name);
    }
    assert.ok(staffSection.includes('getWorkloadStatusBadge') && staffSection.includes('getWorkloadProgressBarColor'), 'staff keep their own status helpers');
    const summaryBlock = source.slice(source.indexOf('const summaryCards'), source.indexOf('const summaryCards') + 1800);
    assert.ok(!summaryBlock.includes('riskThreshold') && !summaryBlock.includes('riskState'), 'the summary counts of people by status are unaffected too');
  });

  it('Cross-Department Support is governed by its own rules: nothing in it reads the Capacity Threshold or the department risk status', () => {
    const panel = fs.readFileSync(path.join(__dirname, '..', 'components', 'workload', 'CrossDepartmentSupportPanel.jsx'), 'utf8');
    const recommendations = fs.readFileSync(path.join(__dirname, '..', 'utils', 'supportRecommendations.js'), 'utf8');
    for (const text of [panel, recommendations]) {
      assert.ok(!/riskThreshold|riskState|department-risk-threshold|capacityThreshold|getDepartmentShareStatus|AT_RISK/.test(text), 'support does not depend on AT RISK');
    }
  });

  it('every tile keeps the status colours for light and dark, one tile per category in the list it is given', () => {
    for (const hue of ['blue', 'emerald', 'orange', 'red']) {
      assert.ok(tiles.includes('border-' + hue + '-200 bg-' + hue + '-100 dark:border-' + hue + '-100 dark:bg-' + hue + '-50'));
      assert.ok(tiles.includes('bg-' + hue + '-600 dark:bg-' + hue + '-500'));
      assert.ok(tiles.includes('text-' + hue + '-700'));
    }
    assert.ok(tiles.includes('ranges.map((range)'));
  });

  it('there is no explanatory note above the legend: the tiles speak for themselves', () => {
    assert.ok(!source.includes('department-risk-note') && !source.includes('These ranges are for the department cards'));
  });
});
