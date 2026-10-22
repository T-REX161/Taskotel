import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  getActionsMenuPosition,
  getNextMenuItemIndex,
  getPaginationState,
  getPageNumbers,
} from '../utils/assignmentUtils.js';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

describe('AssignmentsPage Component Integration', () => {
  const pagePath = path.join(__dirname, 'AssignmentsPage.jsx');
  const pageSource = fs.readFileSync(pagePath, 'utf8');
  const apiServicePath = path.join(__dirname, '../services/api.js');
  const apiSource = fs.readFileSync(apiServicePath, 'utf8');

  describe('API service methods availability', () => {
    it('verifies getAssignments, getTasks, and getWorkload are exported by api.js', () => {
      assert.ok(apiSource.includes('export const getAssignments'), 'api.js must export getAssignments()');
      assert.ok(apiSource.includes('export const getTasks'), 'api.js must export getTasks()');
      assert.ok(apiSource.includes('export const getWorkload'), 'api.js must export getWorkload()');
    });

    it('imports getAssignments, getTasks, and getWorkload in AssignmentsPage.jsx', () => {
      assert.ok(
        pageSource.includes('getAssignments') &&
        pageSource.includes('getTasks') &&
        pageSource.includes('getWorkload'),
        'AssignmentsPage must import getAssignments, getTasks, and getWorkload from api.js'
      );
    });

    it('fetches real data using Promise.all for assignments, tasks, and workload', () => {
      assert.ok(
        /Promise\.all\(\s*\[[\s\S]*?assignments[\s\S]*?tasks[\s\S]*?workload[\s\S]*?\]\s*\)/i.test(pageSource),
        'AssignmentsPage must fetch assignments, tasks, and workload using Promise.all'
      );
    });
  });

  describe('Removal of hardcoded mock data', () => {
    it('does not contain hardcoded mockAssignments array with fake records', () => {
      assert.strictEqual(
        pageSource.includes('const mockAssignments ='),
        false,
        'AssignmentsPage must not define hardcoded mockAssignments'
      );
      assert.strictEqual(
        pageSource.includes('Maria Santos') && pageSource.includes('Priya Sharma') && pageSource.includes('Room 302 Deep Clean'),
        false,
        'AssignmentsPage must not contain hardcoded mock assignment rows'
      );
    });

    it('does not contain hardcoded summary card values', () => {
      // Original mock had hardcoded values: '6', '4', '2', '1'
      assert.strictEqual(
        /value:\s*['"]6['"]/.test(pageSource),
        false,
        'AssignmentsPage must not contain hardcoded total assignments value "6"'
      );
      assert.strictEqual(
        /value:\s*['"]4['"]/.test(pageSource),
        false,
        'AssignmentsPage must not contain hardcoded active assignments value "4"'
      );
      assert.strictEqual(
        /value:\s*['"]2['"]/.test(pageSource),
        false,
        'AssignmentsPage must not contain hardcoded completed today value "2"'
      );
      assert.strictEqual(
        /value:\s*['"]1['"]/.test(pageSource),
        false,
        'AssignmentsPage must not contain hardcoded unassigned tasks value "1"'
      );
    });
  });

  describe('Dynamic summary cards calculation', () => {
    it('calculates dynamic summary metrics using calculateAssignmentSummary', () => {
      assert.ok(
        pageSource.includes('calculateAssignmentSummary'),
        'AssignmentsPage must import and call calculateAssignmentSummary'
      );
    });

    it('defines the 4 required summary cards: Total Assignments, Active Assignments, Completed Today, Unassigned Tasks', () => {
      assert.ok(pageSource.includes('Total Assignments'), 'Card "Total Assignments" must exist');
      assert.ok(pageSource.includes('Active Assignments'), 'Card "Active Assignments" must exist');
      assert.ok(pageSource.includes('Completed Today'), 'Card "Completed Today" must exist');
      assert.ok(pageSource.includes('Unassigned Tasks'), 'Card "Unassigned Tasks" must exist');
    });

    it('preserves existing card colors and styling', () => {
      assert.ok(pageSource.includes('bg-emerald-50'), 'Must preserve emerald background for Total Assignments');
      assert.ok(pageSource.includes('bg-blue-50'), 'Must preserve blue background for Active Assignments');
      assert.ok(pageSource.includes('bg-purple-50'), 'Must preserve purple background for Completed Today');
      assert.ok(pageSource.includes('bg-yellow-50'), 'Must preserve yellow background for Unassigned Tasks');
    });
  });

  describe('Loading, Error, and Empty States', () => {
    it('renders a loading state with spinner and "Loading assignments..." message', () => {
      assert.ok(pageSource.includes('isLoading'), 'Component must track isLoading state');
      assert.ok(pageSource.includes('animate-spin'), 'Loading state must include spinner animation');
      assert.ok(pageSource.includes('Loading assignments...'), 'Loading state must display "Loading assignments..."');
    });

    it('renders an error state with alert banner and "Try Again" retry action', () => {
      assert.ok(pageSource.includes('error'), 'Component must track error state');
      assert.ok(pageSource.includes('role="alert"'), 'Error container must have role="alert"');
      assert.ok(pageSource.includes('Failed to load assignments'), 'Error container must display title "Failed to load assignments"');
      assert.ok(
        pageSource.includes('Try Again') && pageSource.includes('onClick={fetchData}'),
        'Error container must include a "Try Again" button that triggers refetching'
      );
    });

    it('renders an empty state row when assignments array is empty', () => {
      assert.ok(pageSource.includes('No assignments found'), 'Table must render "No assignments found" when empty');
      assert.ok(
        pageSource.includes('colSpan={6}') || pageSource.includes('colSpan="6"'),
        'Empty state table cell must span 6 columns matching header'
      );
    });
  });

  describe('Table structure, live data rendering, and actions', () => {
    it('preserves table headers in exact order', () => {
      const headers = ['Task', 'Staff Member', 'Department', 'Assigned Time', 'Status', 'Actions'];
      for (const h of headers) {
        assert.ok(pageSource.includes(h), `Table must include header "${h}"`);
      }
    });

    it('displays live assignment fields (taskTitle, staffName, department, assignedAt, status)', () => {
      assert.ok(pageSource.includes('assignment.taskTitle') || pageSource.includes('taskTitle'), 'Row must display task title from assignment');
      assert.ok(pageSource.includes('assignment.staffName') || pageSource.includes('staffName'), 'Row must display staff name from assignment');
      assert.ok(pageSource.includes('resolveAssignmentDepartment'), 'Row must resolve department');
      assert.ok(pageSource.includes('formatAssignedAt'), 'Row must format assigned time');
      assert.ok(
        pageSource.includes('splitAssignedAt(formatAssignedAt(assignment.assignedAt))'),
        'Assigned Time must reuse formatAssignedAt and only split it for display'
      );
      assert.ok(
        /<div className="flex flex-col items-center justify-center gap-0\.5">\s*<span className="text-\[13px\] leading-4 font-medium text-slate-600">\{assignedTime\.date\}<\/span>\s*\{assignedTime\.time && \(\s*<span className="text-xs leading-3 text-slate-500">\{assignedTime\.time\}<\/span>/.test(pageSource),
        'Assigned Time must show a medium-weight date line above a smaller, muted time line, both centered'
      );
      assert.ok(
        pageSource.includes('<td className="px-6 py-3.5 align-middle whitespace-nowrap text-center tabular-nums">'),
        'Assigned Time cell must center the two-line group horizontally and vertically'
      );
      assert.ok(
        pageSource.includes('<th className="px-6 py-3 whitespace-nowrap text-center">Assigned Time</th>'),
        'Assigned Time header must be centered over its content'
      );
      assert.ok(pageSource.includes('getAssignmentStatusBadge'), 'Row must apply badge styling');
      assert.ok(pageSource.includes('formatAssignmentStatus'), 'Row must display formatted status');
    });

    it('preserves row actions (View Details, Reassign in the ⋮ menu) and Assign Task button', () => {
      assert.ok(pageSource.includes('View Details'), 'Row actions menu must offer "View Details"');
      assert.ok(pageSource.includes('Reassign'), 'Row actions menu must offer "Reassign"');
      assert.ok(pageSource.includes('Assign Task'), 'Header must preserve "Assign Task" button');
    });
  });

  describe('Direct Assignment & Reassignment Workflow (Step 4)', () => {
    it('opens assignment workflow directly from AssignmentsPage when Assign Task is clicked', () => {
      assert.ok(
        pageSource.includes('handleOpenAssignModal'),
        'Clicking Assign Task must trigger handleOpenAssignModal'
      );
      assert.ok(
        pageSource.includes('setIsAssignModalOpen(true)'),
        'handleOpenAssignModal must open AssignTaskModal directly on AssignmentsPage'
      );
    });

    it('allows selecting eligible unassigned tasks using getEligibleUnassignedTasks', () => {
      assert.ok(
        pageSource.includes('getEligibleUnassignedTasks'),
        'AssignmentsPage must compute unassignedTasks using getEligibleUnassignedTasks'
      );
      assert.ok(
        pageSource.includes('unassignedTasks={unassignedTasks}'),
        'AssignmentsPage must pass unassignedTasks to AssignTaskModal'
      );
    });

    it('opens reassignment for the specific assignment row when Reassign is clicked', () => {
      assert.ok(
        pageSource.includes('handleOpenReassignModal(assignment)'),
        'Reassign button must pass specific assignment row to handleOpenReassignModal'
      );
      assert.ok(
        pageSource.includes('setCurrentAssignment(assignment)'),
        'handleOpenReassignModal must set currentAssignment to the target row assignment'
      );
      assert.ok(
        pageSource.includes('currentAssignment={currentAssignment}'),
        'AssignmentsPage must pass currentAssignment to AssignTaskModal'
      );
    });

    it('opens TaskDetailsModal when View Details is selected for an assignment row', () => {
      assert.ok(
        pageSource.includes('handleOpenViewModal(assignment)'),
        'View Details must trigger handleOpenViewModal'
      );
      assert.ok(
        pageSource.includes('<TaskDetailsModal'),
        'AssignmentsPage must render TaskDetailsModal for viewing assignment task details'
      );
    });

    it('calls existing createAssignment and reassignTask API endpoints', () => {
      assert.ok(
        pageSource.includes('createAssignment'),
        'AssignmentsPage must call createAssignment for new assignments'
      );
      assert.ok(
        pageSource.includes('reassignTask'),
        'AssignmentsPage must call reassignTask for reassignments'
      );
    });

    it('refreshes assignments, tasks, and workload data after successful submission', () => {
      assert.ok(
        /await\s+fetchData\(\)/.test(pageSource),
        'AssignmentsPage must refresh data by calling await fetchData() upon success'
      );
      assert.ok(
        pageSource.includes('setSuccessBanner'),
        'AssignmentsPage must display a success banner upon completion'
      );
    });

    it('prevents duplicate submissions with isSubmittingAssign state', () => {
      assert.ok(
        pageSource.includes('isSubmittingAssign'),
        'AssignmentsPage must track isSubmittingAssign to prevent double submission'
      );
      assert.ok(
        pageSource.includes('isSubmitting={isSubmittingAssign}'),
        'AssignTaskModal must receive isSubmitting state'
      );
    });

    it('restricts assignment management actions to ADMIN and MANAGER roles', () => {
      assert.ok(
        pageSource.includes('canManageAssignments'),
        'AssignmentsPage must compute canManageAssignments permission flag'
      );
      assert.ok(
        pageSource.includes("userRole === 'ADMIN' || userRole === 'MANAGER'"),
        'canManageAssignments must be true only for ADMIN and MANAGER'
      );
      assert.ok(
        pageSource.includes('{canManageAssignments && ('),
        'Assign Task and Reassign actions must be guarded by canManageAssignments'
      );
    });
  });

  describe('Row actions menu (⋮)', () => {
    // Source of one top-level handler, up to its closing line: `  };` or `  }, [deps]);` (useCallback)
    const handlerSource = (name) => {
      const match = pageSource.match(new RegExp(`const ${name} = [\\s\\S]*?\\n  \\}[^\\n]*;`));
      assert.ok(match, `${name} must be defined`);
      return match[0];
    };

    it('replaces the View / Reassign text buttons with one ⋮ icon button per row', () => {
      assert.ok(pageSource.includes('<DotsVerticalIcon'), 'Row must render the vertical three-dot icon');
      assert.ok(
        pageSource.includes('onClick={(e) => handleActionsButtonClick(e, assignment)}'),
        'The ⋮ button must open the menu for its own assignment row'
      );
      assert.strictEqual(
        pageSource.includes('text-blue-600 hover:text-blue-700'),
        false,
        'The old blue "View" text button must be removed'
      );
      assert.strictEqual(
        /onClick=\{\(\) => handleOpen(View|Reassign)Modal\(assignment\)\}/.test(pageSource),
        false,
        'Rows must no longer call View / Reassign directly'
      );
    });

    it('labels the ⋮ button for assistive technology and shows a keyboard focus ring', () => {
      assert.ok(pageSource.includes('aria-label={`Actions for ${taskTitle}`}'), 'Button needs a per-row accessible label');
      assert.ok(pageSource.includes('aria-haspopup="menu"'), 'Button must announce that it opens a menu');
      assert.ok(pageSource.includes('aria-expanded={isMenuOpen}'), 'Button must expose its expanded state');
      assert.ok(pageSource.includes('aria-controls={isMenuOpen ? ACTIONS_MENU_ID : undefined}'), 'Button must reference the open menu');
      assert.ok(pageSource.includes('focus-visible:ring-emerald-500'), 'Button needs an emerald keyboard focus ring');
      assert.ok(pageSource.includes('<DotsVerticalIcon className="w-4 h-4" aria-hidden="true" />'), 'Icon must be hidden from screen readers');
    });

    it('opening: clicking toggles the menu for that row only', () => {
      const source = handlerSource('handleActionsButtonClick');
      assert.ok(source.includes('e.stopPropagation()'), 'Click must not bubble to the outside-click handler');
      assert.ok(source.includes('activeMenu?.assignment?.id === assignment.id'), 'Clicking the open row button must close it');
      assert.ok(source.includes('setActiveMenu({ assignment, buttonEl: e.currentTarget })'), 'Clicking another row must open that row');
      assert.ok(pageSource.includes('const isMenuOpen = activeMenu?.assignment?.id === assignment.id;'), 'Open state is per row');
    });

    it('opening: renders a menu with View Details and (for managers) Reassign items', () => {
      assert.ok(pageSource.includes('role="menu"'), 'Dropdown must have role="menu"');
      assert.ok(pageSource.includes('id={ACTIONS_MENU_ID}'), 'Dropdown id must match aria-controls');
      assert.strictEqual((pageSource.match(/^\s+role="menuitem"$/gm) || []).length, 2, 'Menu must have exactly two items');
      assert.ok(pageSource.includes('<span>View Details</span>'), 'Menu must contain "View Details"');
      assert.ok(pageSource.includes('<span>Reassign</span>'), 'Menu must contain "Reassign"');
      assert.ok(
        /\{canManageAssignments && \(\s*<button\s+type="button"\s+role="menuitem"\s+onClick=\{handleMenuReassign\}/.test(pageSource),
        'Reassign must stay restricted to ADMIN and MANAGER'
      );
    });

    it('selecting View Details closes the menu and reuses the existing view handler', () => {
      const source = handlerSource('handleMenuViewDetails');
      assert.ok(/setActiveMenu\(null\);[\s\S]*handleOpenViewModal\(assignment\)/.test(source), 'Must close, then open the existing View dialog');
      assert.ok(pageSource.includes('onClick={handleMenuViewDetails}'), 'View Details item must use the handler');
      assert.strictEqual((pageSource.match(/const handleOpenViewModal = /g) || []).length, 1, 'View logic must not be duplicated');
    });

    it('selecting Reassign closes the menu and reuses the existing reassign handler', () => {
      const source = handlerSource('handleMenuReassign');
      assert.ok(/setActiveMenu\(null\);[\s\S]*handleOpenReassignModal\(assignment\)/.test(source), 'Must close, then open the existing Reassign workflow');
      assert.ok(pageSource.includes('onClick={handleMenuReassign}'), 'Reassign item must use the handler');
      assert.strictEqual((pageSource.match(/const handleOpenReassignModal = /g) || []).length, 1, 'Reassign logic must not be duplicated');
    });

    it('closing: Escape closes and returns focus, Tab and outside clicks close, listeners are removed', () => {
      assert.ok(/e\.key === 'Escape'[\s\S]*?closeActionsMenu\(true\)/.test(pageSource), 'Escape must close and restore focus to the ⋮ button');
      assert.ok(handlerSource('closeActionsMenu').includes('buttonEl.focus()'), 'Closing with restoreFocus must focus the trigger');
      assert.ok(/e\.key === 'Tab'[\s\S]*?closeActionsMenu\(false\)/.test(pageSource), 'Tab must close the menu');
      assert.ok(
        pageSource.includes("if (menuRef.current?.contains(e.target) || activeMenu.buttonEl?.contains(e.target)) return;"),
        'Clicks inside the menu or on its button must not count as outside clicks'
      );
      assert.ok(pageSource.includes("document.addEventListener('pointerdown', handlePointerDown)"), 'Outside clicks must be detected');
      assert.ok(pageSource.includes("document.removeEventListener('pointerdown', handlePointerDown)"), 'Outside-click listener must be removed');
      assert.ok(pageSource.includes("document.removeEventListener('keydown', handleKeyDown)"), 'Key listener must be removed');
      assert.ok(pageSource.includes('if (isLoading || isAssignModalOpen) setActiveMenu(null);'), 'Menu must close when the table is replaced');
    });

    it('keyboard: arrow keys move between items and the first item receives focus on open', () => {
      assert.ok(pageSource.includes('getNextMenuItemIndex(items.indexOf(document.activeElement), items.length, e.key)'), 'Arrow keys must use getNextMenuItemIndex');
      assert.ok(pageSource.includes(`menuRef.current.querySelector('[role="menuitem"]')?.focus()`), 'First item must receive focus when the menu opens');
      assert.ok(pageSource.includes('focus-visible:bg-emerald-50'), 'Menu items need a visible keyboard focus state');
    });

    it('placement: portal to document.body with fixed position computed from the button', () => {
      assert.ok(/createPortal\([\s\S]*?role="menu"[\s\S]*?document\.body\s*\)/.test(pageSource), 'Menu must render through a portal so the table cannot clip it');
      assert.ok(pageSource.includes("position: 'fixed'"), 'Menu must be fixed-positioned relative to the viewport');
      assert.ok(pageSource.includes('getActionsMenuPosition({'), 'Placement must use getActionsMenuPosition');
      assert.ok(pageSource.includes("window.addEventListener('scroll', handleReposition, true)"), 'Menu must follow the button while scrolling');
      assert.ok(pageSource.includes("window.addEventListener('resize', handleReposition)"), 'Menu must follow the button on resize');
    });

    it('design: white rounded dropdown with subtle border and shadow, emerald hover accents', () => {
      assert.ok(pageSource.includes('w-44 rounded-lg bg-white shadow-lg border border-gray-200 py-1'), 'Dropdown styling must match the EquiFlow menus');
      assert.ok(pageSource.includes('hover:bg-emerald-50 hover:text-emerald-700'), 'Items must use emerald hover accents');
    });
  });

  describe('getActionsMenuPosition (row actions menu placement)', () => {
    const viewport = { viewportWidth: 1280, viewportHeight: 800, menuWidth: 176, menuHeight: 96 };

    it('opens downward, right-aligned to the button, when there is room below', () => {
      const pos = getActionsMenuPosition({ ...viewport, rect: { top: 200, bottom: 232, right: 1200 } });
      assert.deepStrictEqual(pos, { openUpward: false, top: 238, bottom: 606, right: 80 });
    });

    it('opens upward when the button is near the bottom of the viewport', () => {
      const pos = getActionsMenuPosition({ ...viewport, rect: { top: 740, bottom: 772, right: 1200 } });
      assert.strictEqual(pos.openUpward, true);
      assert.strictEqual(pos.bottom, 800 - 740 + 6, 'Menu bottom must sit just above the button');
    });

    it('stays downward when space is tight below but even tighter above', () => {
      const pos = getActionsMenuPosition({ ...viewport, viewportHeight: 150, rect: { top: 40, bottom: 72, right: 1200 } });
      assert.strictEqual(pos.openUpward, false);
    });

    it('keeps the menu inside the right edge when the button extends past the viewport', () => {
      const pos = getActionsMenuPosition({ ...viewport, rect: { top: 200, bottom: 232, right: 1300 } });
      assert.strictEqual(pos.right, 8);
    });

    it('keeps the menu inside the left edge when the button is near the left side', () => {
      const pos = getActionsMenuPosition({ ...viewport, rect: { top: 200, bottom: 232, right: 100 } });
      assert.strictEqual(pos.right, 1280 - 176 - 8, 'Left edge of the menu must stay 8px inside the viewport');
    });
  });

  describe('getNextMenuItemIndex (row actions menu keyboard navigation)', () => {
    it('ArrowDown moves to the next item and wraps; from no focus it picks the first', () => {
      assert.strictEqual(getNextMenuItemIndex(-1, 2, 'ArrowDown'), 0);
      assert.strictEqual(getNextMenuItemIndex(0, 2, 'ArrowDown'), 1);
      assert.strictEqual(getNextMenuItemIndex(1, 2, 'ArrowDown'), 0);
    });

    it('ArrowUp moves to the previous item and wraps; from no focus it picks the last', () => {
      assert.strictEqual(getNextMenuItemIndex(-1, 2, 'ArrowUp'), 1);
      assert.strictEqual(getNextMenuItemIndex(1, 2, 'ArrowUp'), 0);
      assert.strictEqual(getNextMenuItemIndex(0, 2, 'ArrowUp'), 1);
    });

    it('works with a single item (STAFF sees only View Details)', () => {
      assert.strictEqual(getNextMenuItemIndex(0, 1, 'ArrowDown'), 0);
      assert.strictEqual(getNextMenuItemIndex(0, 1, 'ArrowUp'), 0);
    });

    it('ignores other keys and empty menus', () => {
      assert.strictEqual(getNextMenuItemIndex(0, 2, 'Enter'), -1);
      assert.strictEqual(getNextMenuItemIndex(-1, 0, 'ArrowDown'), -1);
    });
  });

  describe('Pagination (10 assignments per page)', () => {
    it('paginates the fetched list client-side with 10 rows per page', () => {
      assert.ok(pageSource.includes('const ASSIGNMENTS_PER_PAGE = 10;'), 'Page size must be 10');
      assert.ok(
        pageSource.includes('getPaginationState({ totalItems: assignments.length, currentPage, pageSize: ASSIGNMENTS_PER_PAGE })'),
        'Pagination state must come from getPaginationState over the full assignments list'
      );
      assert.ok(
        pageSource.includes('assignments.slice(pagination.startIndex, pagination.endIndex)'),
        'Rows must be a slice of the fetched list (no new API call)'
      );
      assert.ok(pageSource.includes('paginatedAssignments.map((assignment) => {'), 'Table body must render the current page only');
      assert.strictEqual(pageSource.includes('assignments.map((assignment) => {'), false, 'Table body must not render the full list');
    });

    it('keeps summary cards, empty state, columns and the ⋮ menu working on the full / paged data', () => {
      assert.ok(pageSource.includes('calculateAssignmentSummary({ assignments, tasks })'), 'Summary cards must still use the full list');
      assert.ok(pageSource.includes('assignments.length === 0 ? ('), 'Empty state must still depend on the full list');
      assert.ok(
        /paginatedAssignments\.map\(\(assignment\) => \{[\s\S]*?handleActionsButtonClick\(e, assignment\)/.test(pageSource),
        'Each paged row must keep its ⋮ actions button'
      );
    });

    it('shows "Showing X–Y of N assignments" and hides all controls when there are no assignments', () => {
      assert.ok(pageSource.includes('{assignments.length > 0 && ('), 'Pagination footer must be hidden for zero assignments');
      assert.ok(/Showing\{' '\}[\s\S]*?pagination\.rangeStart[\s\S]*?pagination\.rangeEnd[\s\S]*?assignments\.length/.test(pageSource), 'Summary must show the visible range and total');
      assert.ok(pageSource.includes("assignments.length === 1 ? 'assignment' : 'assignments'"), 'Summary noun must be singular for one assignment');
      assert.ok(pageSource.includes('{pagination.totalPages > 1 && ('), 'Page buttons appear only when there is more than one page');
    });

    it('renders Previous / page numbers / Next with correct disabled and current-page states', () => {
      assert.ok(pageSource.includes('disabled={!pagination.hasPrevious}'), 'Previous must be disabled on the first page');
      assert.ok(pageSource.includes('disabled={!pagination.hasNext}'), 'Next must be disabled on the last page');
      assert.ok(pageSource.includes('onClick={() => setCurrentPage(pagination.page - 1)}'), 'Previous must go back one page');
      assert.ok(pageSource.includes('onClick={() => setCurrentPage(pagination.page + 1)}'), 'Next must go forward one page');
      assert.ok(pageSource.includes('onClick={() => setCurrentPage(page)}'), 'Page numbers must jump to that page');
      assert.ok(pageSource.includes("aria-current={page === pagination.page ? 'page' : undefined}"), 'Current page must be marked for assistive technology');
      assert.ok(pageSource.includes('aria-label="Previous page"') && pageSource.includes('aria-label="Next page"'), 'Prev/Next need accessible labels');
      assert.ok(pageSource.includes("bg-emerald-600 border-emerald-600 text-white"), 'Current page uses the emerald theme');
    });

    it('keeps the stored page valid when the list shrinks', () => {
      assert.ok(
        /if \(currentPage !== pagination\.page\) \{\s*setCurrentPage\(pagination\.page\);/.test(pageSource),
        'Out-of-range page must be clamped to a valid page'
      );
    });
  });

  describe('getPaginationState (assignments pagination)', () => {
    const items = Array.from({ length: 24 }, (_, i) => `a${i + 1}`);
    const pageOf = (list, currentPage) => {
      const state = getPaginationState({ totalItems: list.length, currentPage, pageSize: 10 });
      return { ...state, rows: list.slice(state.startIndex, state.endIndex) };
    };

    it('shows the first 10 of 24 on page 1 with Previous disabled and Next enabled', () => {
      const p = pageOf(items, 1);
      assert.strictEqual(p.rows.length, 10);
      assert.deepStrictEqual([p.rows[0], p.rows[9]], ['a1', 'a10']);
      assert.deepStrictEqual([p.rangeStart, p.rangeEnd, p.totalPages], [1, 10, 3]);
      assert.deepStrictEqual([p.hasPrevious, p.hasNext], [false, true]);
    });

    it('navigates: page 2 shows 11–20 with both buttons enabled', () => {
      const p = pageOf(items, 2);
      assert.deepStrictEqual([p.rows[0], p.rows[9], p.rows.length], ['a11', 'a20', 10]);
      assert.deepStrictEqual([p.rangeStart, p.rangeEnd], [11, 20]);
      assert.deepStrictEqual([p.hasPrevious, p.hasNext], [true, true]);
    });

    it('navigates: the last page shows the remaining 4 with Next disabled', () => {
      const p = pageOf(items, 3);
      assert.deepStrictEqual(p.rows, ['a21', 'a22', 'a23', 'a24']);
      assert.deepStrictEqual([p.rangeStart, p.rangeEnd], [21, 24]);
      assert.deepStrictEqual([p.hasPrevious, p.hasNext], [true, false]);
    });

    it('handles exactly 10 and fewer than 10 assignments as a single page', () => {
      const ten = pageOf(items.slice(0, 10), 1);
      assert.deepStrictEqual([ten.totalPages, ten.rangeStart, ten.rangeEnd, ten.hasPrevious, ten.hasNext], [1, 1, 10, false, false]);
      const seven = pageOf(items.slice(0, 7), 1);
      assert.deepStrictEqual([seven.totalPages, seven.rows.length, seven.rangeStart, seven.rangeEnd], [1, 7, 1, 7]);
      assert.deepStrictEqual([seven.hasPrevious, seven.hasNext], [false, false]);
    });

    it('handles zero assignments without invalid page numbers', () => {
      const p = pageOf([], 1);
      assert.deepStrictEqual([p.page, p.totalPages, p.rows.length], [1, 1, 0]);
      assert.deepStrictEqual([p.rangeStart, p.rangeEnd, p.hasPrevious, p.hasNext], [0, 0, false, false]);
    });

    it('clamps the page when the list shrinks, so it never shows an empty page', () => {
      const shrunk = pageOf(items.slice(0, 15), 3);
      assert.deepStrictEqual([shrunk.page, shrunk.rangeStart, shrunk.rangeEnd, shrunk.rows.length], [2, 11, 15, 5]);
      assert.strictEqual(pageOf([], 3).page, 1);
      assert.strictEqual(pageOf(items, 0).page, 1);
      assert.strictEqual(pageOf(items, -2).page, 1);
      assert.strictEqual(pageOf(items, Number.NaN).page, 1);
    });
  });

  describe('getPageNumbers (assignments pagination buttons)', () => {
    it('lists every page when there are at most 7', () => {
      assert.deepStrictEqual(getPageNumbers(1, 1), [1]);
      assert.deepStrictEqual(getPageNumbers(3, 2), [1, 2, 3]);
      assert.deepStrictEqual(getPageNumbers(7, 4), [1, 2, 3, 4, 5, 6, 7]);
    });

    it('shows first, last and two pages either side of the current page beyond 7', () => {
      assert.deepStrictEqual(getPageNumbers(12, 1), [1, 2, 3, 12]);
      assert.deepStrictEqual(getPageNumbers(12, 6), [1, 4, 5, 6, 7, 8, 12]);
      assert.deepStrictEqual(getPageNumbers(12, 12), [1, 10, 11, 12]);
    });
  });

  describe('API schema compatibility & limitation handling', () => {
    it('safely handles backend AssignmentDto omitting department field by resolving from tasks or workload', () => {
      assert.ok(
        pageSource.includes('resolveAssignmentDepartment(assignment, tasks, workload)'),
        'AssignmentsPage must pass assignment, tasks, and workload to resolveAssignmentDepartment'
      );
    });
  });

  describe('Assignments Page Navigation & Overview Default Mode', () => {
    it('initializes the page in overview mode by defaulting isAssignModalOpen to false', () => {
      assert.ok(
        /const\s+\[isAssignModalOpen,\s*setIsAssignModalOpen\]\s*=\s*useState\(\s*false\s*\)/.test(pageSource),
        'AssignmentsPage must initialize isAssignModalOpen to false'
      );
      assert.strictEqual(
        /const\s+\[isAssignModalOpen,\s*setIsAssignModalOpen\]\s*=\s*useState\(\s*true\s*\)/.test(pageSource),
        false,
        'AssignmentsPage must not initialize isAssignModalOpen to true'
      );
      assert.strictEqual(
        pageSource.includes("location.state?.viewMode ? location.state.viewMode !== 'overview' : true"),
        false,
        'AssignmentsPage must not default isAssignModalOpen using stale location.state.viewMode'
      );
    });

    it('verifies direct navigation to /assignments renders the overview elements', () => {
      // Heading & subtitle
      assert.ok(pageSource.includes('Assignments'), 'Overview must render "Assignments" heading');
      assert.ok(
        pageSource.includes('Manage task assignments and staff distributions.'),
        'Overview must render "Manage task assignments and staff distributions." subtitle'
      );
      // Assign Task button
      assert.ok(pageSource.includes('Assign Task'), 'Overview must include "Assign Task" button');
      // 4 Summary cards
      assert.ok(pageSource.includes('Total Assignments'), 'Overview must render "Total Assignments" summary card');
      assert.ok(pageSource.includes('Active Assignments'), 'Overview must render "Active Assignments" summary card');
      assert.ok(pageSource.includes('Completed Today'), 'Overview must render "Completed Today" summary card');
      assert.ok(pageSource.includes('Unassigned Tasks'), 'Overview must render "Unassigned Tasks" summary card');
      // Table & actions
      assert.ok(pageSource.includes('Current Staff Assignments'), 'Overview must render "Current Staff Assignments" table');
      assert.ok(pageSource.includes('View Details'), 'Overview table must include "View Details" action');
      assert.ok(pageSource.includes('Reassign'), 'Overview table must include "Reassign" action');
    });

    it('prevents automatically reopening the assignment form on refresh or revisit by clearing stale navigation state', () => {
      // assignModalTask initialized to null, not eager from location.state
      assert.ok(
        /const\s+\[assignModalTask,\s*setAssignModalTask\]\s*=\s*useState\(\s*null\s*\)/.test(pageSource),
        'assignModalTask must initialize to null'
      );
      // Clears location.state via replace: true so browser refresh or revisit does not retain stale state
      assert.ok(
        pageSource.includes('navigate(location.pathname, { replace: true, state: null })'),
        'AssignmentsPage must consume/clear location.state to prevent stale reopening on refresh or revisit'
      );
    });

    it('opens the dedicated Task Assignment page when Assign Task is clicked from overview', () => {
      assert.ok(
        pageSource.includes('handleOpenAssignModal'),
        'Overview Assign Task button must trigger handleOpenAssignModal'
      );
      assert.ok(
        pageSource.includes('setIsAssignModalOpen(true)'),
        'handleOpenAssignModal must set isAssignModalOpen to true'
      );
      assert.ok(
        pageSource.includes('if (canManageAssignments && isAssignModalOpen)'),
        'AssignmentsPage must render dedicated Task Assignment page when isAssignModalOpen is true'
      );
    });

    it('opens the dedicated page in reassignment mode with assignment and task preloaded when Reassign is clicked', () => {
      assert.ok(
        pageSource.includes('handleOpenReassignModal(assignment)'),
        'Reassign button on row must pass the assignment record to handleOpenReassignModal'
      );
      assert.ok(
        pageSource.includes('setCurrentAssignment(assignment)'),
        'handleOpenReassignModal must set currentAssignment to the target row assignment'
      );
      assert.ok(
        pageSource.includes('setAssignModalTask(associatedTask)'),
        'handleOpenReassignModal must preload the associated task'
      );
      assert.ok(
        pageSource.includes('currentAssignment={currentAssignment}'),
        'AssignmentsPage must pass currentAssignment to AssignTaskModal'
      );
    });

    it('returns to overview table when Back button is clicked on dedicated Task Assignment page', () => {
      assert.ok(
        pageSource.includes('handleCloseAssignModal'),
        'Dedicated view must pass handleCloseAssignModal to onClose callback'
      );
      assert.ok(
        pageSource.includes('setIsAssignModalOpen(false)'),
        'handleCloseAssignModal must set isAssignModalOpen to false to return to overview'
      );
    });

    it('preserves existing assignment and reassignment behavior, live APIs, and role permissions', () => {
      assert.ok(
        pageSource.includes('createAssignment'),
        'AssignmentsPage must preserve createAssignment API call'
      );
      assert.ok(
        pageSource.includes('reassignTask'),
        'AssignmentsPage must preserve reassignTask API call'
      );
      assert.ok(
        /await\s+fetchData\(\)/.test(pageSource),
        'AssignmentsPage must refresh data with await fetchData() after assignment/reassignment'
      );
      assert.ok(
        pageSource.includes('workloadList={workload}'),
        'AssignmentsPage must pass real live workload data to AssignTaskModal'
      );
      assert.ok(
        pageSource.includes('eligibleStaff={eligibleStaff}'),
        'AssignmentsPage must pass dynamically filtered eligible staff to AssignTaskModal'
      );
      assert.ok(
        pageSource.includes('canManageAssignments'),
        'AssignmentsPage must guard assignment management actions with canManageAssignments'
      );
    });
  });
});
