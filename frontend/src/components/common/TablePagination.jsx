import React from 'react';
import { getPageNumbers } from '../../utils/assignmentUtils';
import PageSizeToggle from './PageSizeToggle';

const BUTTON_FOCUS_CLASS = 'focus:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 focus-visible:ring-offset-1';
const STEP_BUTTON_CLASS = `inline-flex items-center justify-center h-8 px-3 rounded-lg border border-gray-200 text-xs font-medium text-gray-600 bg-white hover:bg-gray-50 hover:border-gray-300 disabled:opacity-40 disabled:pointer-events-none transition-colors cursor-pointer ${BUTTON_FOCUS_CLASS}`;

/**
 * Footer pagination for a table card: "Showing 1–10 of 25 users", ‹ Prev, numbered pages (gaps shown as "…") and
 * Next ›. Same markup and styling as the Assignments and Staff page footers; renders nothing when there are no items
 * and shows only the summary when everything fits on one page.
 *
 * Props:
 *  - pagination: the result of getPaginationState() from utils/assignmentUtils
 *  - totalItems: how many items the pages are cut from (after search and filters)
 *  - noun: [singular, plural], e.g. ['user', 'users']
 *  - onPageChange(page)
 *  - label: accessible name of the page navigation
 *  - pageSize, onPageSizeChange(size): optional rows-per-page toggle shown beside the controls
 */
export default function TablePagination({ pagination, totalItems, noun, onPageChange, label, pageSize, onPageSizeChange }) {
  if (!totalItems) return null;
  const pageNumbers = getPageNumbers(pagination.totalPages, pagination.page);
  const [singular, plural] = noun;

  return (
    <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3 px-5 py-3 border-t border-gray-100 bg-gray-50/50">
      <p className="text-xs text-gray-500 shrink-0" aria-live="polite">
        Showing{' '}
        <span className="font-medium text-gray-700">{pagination.rangeStart}</span>
        {'–'}
        <span className="font-medium text-gray-700">{pagination.rangeEnd}</span>
        {' '}of{' '}
        <span className="font-medium text-gray-700">{totalItems}</span>
        {' '}{totalItems === 1 ? singular : plural}
      </p>

      <div className="flex flex-wrap items-center gap-2">
        {onPageSizeChange && <PageSizeToggle value={pageSize} onChange={onPageSizeChange} />}
        {pagination.totalPages > 1 && (
          <nav className="flex flex-wrap items-center gap-1" aria-label={label}>
            <button
              type="button"
              onClick={() => onPageChange(pagination.page - 1)}
              disabled={!pagination.hasPrevious}
              className={STEP_BUTTON_CLASS}
              aria-label="Previous page"
            >
              ‹ Prev
            </button>

            {pageNumbers.map((page, index) => {
              const previous = pageNumbers[index - 1];
              const gap = previous !== undefined && page - previous > 1;
              const current = page === pagination.page;
              return (
                <React.Fragment key={page}>
                  {gap && (
                    <span className="px-1 text-xs text-gray-400 select-none" aria-hidden="true">…</span>
                  )}
                  <button
                    type="button"
                    onClick={() => onPageChange(page)}
                    className={`inline-flex items-center justify-center h-8 w-8 rounded-lg border text-xs font-medium transition-colors cursor-pointer ${BUTTON_FOCUS_CLASS} ${
                      current
                        ? 'bg-emerald-600 border-emerald-600 text-white shadow-sm'
                        : 'bg-white border-gray-200 text-gray-600 hover:bg-gray-50 hover:border-gray-300'
                    }`}
                    aria-label={`Page ${page}`}
                    aria-current={current ? 'page' : undefined}
                  >
                    {page}
                  </button>
                </React.Fragment>
              );
            })}

            <button
              type="button"
              onClick={() => onPageChange(pagination.page + 1)}
              disabled={!pagination.hasNext}
              className={STEP_BUTTON_CLASS}
              aria-label="Next page"
            >
              Next ›
            </button>
          </nav>
        )}
      </div>
    </div>
  );
}
