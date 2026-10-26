import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

// Every paginated list offers the rows-per-page toggle beside its pagination controls
const __dirname = path.dirname(fileURLToPath(import.meta.url));
const read = (...parts) => fs.readFileSync(path.join(__dirname, ...parts), 'utf8').replace(/\r\n/g, '\n');

describe('rows-per-page toggle on every paginated page', () => {
  it('Staff and Tasks: chosen size (default 10) drives the page count, the slice and the summary; size changes restart at page 1', () => {
    for (const [file, list] of [['StaffPage.jsx', 'filteredStaffList'], ['TasksPage.jsx', 'filteredTasks']]) {
      const s = read(file);
      assert.ok(s.includes('const ITEMS_PER_PAGE = 10;') && s.includes('const [pageSize, setPageSize] = useState(ITEMS_PER_PAGE);'), `${file}: default 10`);
      assert.ok(s.includes(`Math.ceil(${list}.length / pageSize)`) && s.includes(`${list}.slice(start, start + pageSize)`), `${file}: pages cut at the chosen size`);
      assert.ok(s.includes('{(currentPage - 1) * pageSize + 1}') && s.includes(`{Math.min(currentPage * pageSize, ${list}.length)}`), `${file}: summary`);
      assert.ok(/, pageSize\]\);\s*\n\s*\n\s*\/\/ Clamp currentPage/.test(s), `${file}: page size is part of the reset-to-page-1 effect`);
      assert.ok(s.includes('<PageSizeToggle value={pageSize} onChange={setPageSize} />'), `${file}: toggle`);
      const footer = s.slice(s.indexOf('{/* Rows-per-page toggle and page buttons */}'));
      assert.ok(footer.indexOf('<PageSizeToggle') < footer.indexOf('aria-label="Previous page"'), `${file}: beside the page buttons`);
      assert.ok(footer.includes('<div className="flex flex-wrap items-center gap-1">'), `${file}: buttons wrap on small screens`);
    }
  });

  it('Assignments: chosen size (default 10) feeds the shared pagination state; a new size restarts at page 1', () => {
    const s = read('AssignmentsPage.jsx');
    assert.ok(s.includes('const [pageSize, setPageSize] = useState(ASSIGNMENTS_PER_PAGE);'));
    assert.ok(/const handlePageSizeChange = \(size\) => \{\s*setPageSize\(size\);\s*setCurrentPage\(1\);/.test(s));
    assert.ok(s.indexOf('<PageSizeToggle value={pageSize} onChange={handlePageSizeChange} />') < s.indexOf('aria-label="Assignments pagination"'));
  });

  it('Users: the shared footer shows the toggle beside its controls', () => {
    const page = read('UsersPage.jsx');
    const footer = read('..', 'components', 'common', 'TablePagination.jsx');
    assert.ok(page.includes('pageSize={pageSize}') && page.includes('onPageSizeChange={setPageSize}'));
    assert.ok(footer.includes('{onPageSizeChange && <PageSizeToggle value={pageSize} onChange={onPageSizeChange} />}'));
    assert.ok(footer.indexOf('<PageSizeToggle') < footer.indexOf('{pagination.totalPages > 1 && ('), 'Shown even when everything fits on one page');
  });

  it('Workload: keeps its 5-row default; the toggle sits beside "Page X of Y" in the card header and opens downwards', () => {
    const s = read('WorkloadPage.jsx');
    assert.ok(s.includes('const ITEMS_PER_PAGE = 5;') && s.includes('const [pageSize, setPageSize] = useState(ITEMS_PER_PAGE);'));
    assert.ok(s.includes('sortedWorkload.slice(startIndex, startIndex + pageSize)'));
    assert.ok(/const handlePageSizeChange = \(size\) => \{\s*setPageSize\(size\);\s*setCurrentPage\(1\);/.test(s));
    assert.ok(s.indexOf('<PageSizeToggle value={pageSize} onChange={handlePageSizeChange} placement="bottom" />') < s.indexOf('Page {currentPage} of {totalPages}'));
  });

  it('a new size or filter lands on page 1 even when the old page number no longer exists', () => {
    // Users, Staff and Tasks reset to page 1 in an effect; the clamp effect runs in the same render (e.g. page 5 of 5
    // at 5 rows, then 10 rows: only 3 pages). The clamp builds on the latest page instead of replacing the reset.
    for (const [file, clamp] of [
      ['StaffPage.jsx', 'setCurrentPage((page) => Math.min(page, totalPages));'],
      ['TasksPage.jsx', 'setCurrentPage((page) => Math.min(page, totalPages));'],
      ['UsersPage.jsx', 'setCurrentPage((page) => Math.min(page, pagination.totalPages));'],
    ]) {
      const s = read(file);
      assert.ok(s.includes(clamp), `${file}: clamp keeps a reset made in the same render`);
      assert.ok(!s.includes('setCurrentPage(totalPages)') && !s.includes('setCurrentPage(pagination.page)'), `${file}: no clamp that overwrites the reset`);
    }
  });

  it('no list is left with a fixed page size', () => {
    for (const file of ['StaffPage.jsx', 'TasksPage.jsx', 'AssignmentsPage.jsx', 'UsersPage.jsx', 'WorkloadPage.jsx']) {
      const s = read(file);
      assert.ok(!/\.slice\([^)]*(ITEMS_PER_PAGE|ASSIGNMENTS_PER_PAGE|USERS_PER_PAGE)/.test(s), `${file}: slices with the chosen size`);
      assert.ok(!/pageSize: (ITEMS_PER_PAGE|ASSIGNMENTS_PER_PAGE|USERS_PER_PAGE)/.test(s), `${file}: no fixed pageSize`);
    }
  });
});
