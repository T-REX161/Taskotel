import { createElement as h } from 'react';
import Skeleton from './Skeleton.js';

/*
 * Page-level loading layouts (Stage 5D): what a page shows below its real header while its first load is under way.
 *
 * Each layout reuses its page's own container, grid and table classes (copied from the loaded markup), so spacing, breakpoints, column
 * widths and card borders match the page that replaces it, and row and card heights are set to the measured loaded heights. Placeholders
 * are the shared Skeleton (hidden from assistive technology); each layout sits in one aria-busy region with a single screen-reader status
 * message. They are only for a page's first, blocking load: refreshes keep the loaded content on screen. Written without JSX so the
 * layouts can be rendered in Node tests.
 */

const cx = (...classes) => classes.filter(Boolean).join(' ');

/** A placeholder bar centred in a box as tall as the line of text it stands for. */
const line = (bar, box = 'h-5', key) => h('div', { key, className: cx('flex items-center', box) }, h(Skeleton, { className: cx('rounded', bar) }));

/**
 * The region that holds a loading layout: aria-busy, with one status message for screen readers. The message comes last, so it never
 * takes part in the region's vertical spacing (it is visually hidden).
 */
export function LoadingRegion({ label, className = '', children }) {
  return h('div', { 'aria-busy': 'true', 'data-loading-region': '', className }, children, h('p', { role: 'status', className: 'sr-only' }, label));
}

/** A row of summary cards: an icon square and two text lines (label, value), in the page's own grid and card classes. */
export function StatCardsSkeleton({ count, gridClassName, cardClassName, iconClassName, label = ['h-3 w-24', 'h-5'], value = ['h-5 w-12', 'h-8'], note = null }) {
  return h('div', { className: gridClassName }, Array.from({ length: count }, (_, i) => h('div', { key: i, className: cardClassName },
    h(Skeleton, { className: iconClassName }),
    h('div', { className: 'min-w-0 flex-1' }, line(label[0], label[1], 'l'), line(value[0], value[1], 'v'), note ? line(note[0], note[1], 'n') : null),
  )));
}

/** A filter row: one placeholder per field, each with the field's own width classes and height. */
export function FilterBarSkeleton({ className, fields, fieldClassName = 'h-10 rounded-lg' }) {
  return h('div', { className }, fields.map((width, i) => h(Skeleton, { key: i, className: cx('w-full', fieldClassName, width) })));
}

/**
 * A table in its card: the real column headings (in the page's header style and widths) over placeholder rows of the loaded row height.
 * The table is decorative (aria-hidden); the region around it says what is loading. `columns`: { label, th, td, bar } where `bar` is a
 * list of placeholder classes, used in turn row by row (or `cell(row)` for a custom cell).
 */
export function TableSkeleton({ cardClassName, scrollClassName = 'overflow-x-auto', tableClassName, theadClassName, headRowClassName, colWidths, columns, rows = 6, rowClassName, footerClassName }) {
  const body = Array.from({ length: rows }, (_, r) => h('tr', { key: r, className: rowClassName }, columns.map((col, c) => h('td', { key: c, className: col.td },
    col.cell ? col.cell(r) : h(Skeleton, { className: col.bar[r % col.bar.length] }),
  ))));
  return h('div', { className: cardClassName },
    h('div', { className: scrollClassName },
      h('table', { className: tableClassName, 'aria-hidden': 'true' },
        colWidths ? h('colgroup', null, colWidths.map((width, i) => h('col', { key: i, className: width }))) : null,
        h('thead', { className: theadClassName }, h('tr', { className: headRowClassName }, columns.map((col, i) => h('th', { key: i, className: col.th }, col.label)))),
        h('tbody', { className: 'divide-y divide-gray-100' }, body),
      ),
    ),
    footerClassName ? h('div', { className: footerClassName }, line('h-3 w-44', 'h-4'), h(Skeleton, { className: 'h-8 w-56 rounded-lg' })) : null,
  );
}

// ── shared pieces ─────────────────────────────────────────────────────────────────────────────────────────────

const PAGINATION_FOOTER = 'flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3 px-4 py-3 border-t border-gray-100 bg-gray-50/50';
const LIST_HEAD_ROW = 'border-b border-gray-200 bg-gray-50/50 text-xs font-semibold uppercase tracking-wider text-gray-500 whitespace-nowrap';
const text = (...widths) => widths.map((w) => `h-3.5 rounded ${w}`);
const badge = (...widths) => widths.map((w) => `h-5 rounded-full ${w}`);
const person = (avatar = 'h-9 w-9') => (r) => h('div', { className: 'flex items-center gap-3 min-w-0' },
  h(Skeleton, { className: `${avatar} rounded-full shrink-0` }),
  h('div', { className: 'min-w-0 flex-1 space-y-1.5' }, h(Skeleton, { className: cx('h-3.5 rounded', ['w-3/4', 'w-2/3', 'w-4/5'][r % 3]) }), h(Skeleton, { tone: 'soft', className: 'h-3 w-1/2 rounded' })),
);

// ── Tasks ─────────────────────────────────────────────────────────────────────────────────────────────────────

export function TasksPageSkeleton({ label = 'Loading tasks and assignments...' } = {}) {
  const td = 'py-4 px-3 align-middle';
  return h(LoadingRegion, { label, className: 'space-y-6' },
    h(StatCardsSkeleton, {
      count: 5,
      gridClassName: 'grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-5 gap-4 sm:gap-6 w-full',
      cardClassName: 'bg-white rounded-xl p-5 sm:p-6 shadow-sm border border-gray-100 flex items-center space-x-4',
      iconClassName: 'h-12 w-12 rounded-lg shrink-0',
      label: ['h-3 w-20', 'h-4 sm:h-5'],
      value: ['h-5 w-10', 'h-7 sm:h-8'],
    }),
    h(FilterBarSkeleton, { className: 'flex flex-col md:flex-row items-center gap-3 w-full', fields: ['md:flex-1 md:min-w-0', 'md:w-44', 'md:w-44', 'md:w-48'] }),
    h(TableSkeleton, {
      cardClassName: 'bg-white rounded-xl shadow-sm border border-gray-200 overflow-hidden w-full',
      tableClassName: 'w-full text-left border-collapse table-fixed min-w-[800px]',
      headRowClassName: LIST_HEAD_ROW,
      rowClassName: 'h-[65px]',
      footerClassName: PAGINATION_FOOTER,
      columns: [
        { label: 'Task', th: 'w-[23%] py-3.5 px-4 text-left', td: 'py-4 px-4 align-middle', bar: text('w-3/4', 'w-2/3', 'w-4/5') },
        { label: 'Department', th: 'w-[12%] py-3.5 px-3 text-left', td, bar: text('w-3/4', 'w-2/3') },
        { label: 'Priority', th: 'w-[9%] py-3.5 px-3 text-left', td, bar: badge('w-14') },
        { label: 'Status', th: 'w-[11%] py-3.5 px-3 text-left', td, bar: badge('w-20', 'w-16') },
        { label: 'Assigned To', th: 'w-[13%] py-3.5 px-3 text-left', td, bar: text('w-3/4', 'w-1/2') },
        { label: 'Duration', th: 'w-[8%] py-3.5 px-3 text-left', td, bar: text('w-12') },
        { label: 'Due Date', th: 'w-[16%] min-w-[125px] py-3.5 px-3 text-center', td: `${td} text-center`, bar: text('w-20 mx-auto') },
        { label: 'Actions', th: 'w-[8%] min-w-[68px] py-3.5 px-3 sm:px-4 text-right', td: 'py-4 px-3 sm:px-4 align-middle', bar: ['h-8 w-8 rounded-lg ml-auto'] },
      ],
    }),
  );
}

// ── Staff ─────────────────────────────────────────────────────────────────────────────────────────────────────

export function StaffPageSkeleton({ label = 'Loading staff directory and workload...' } = {}) {
  const td = 'py-4 px-3 align-middle';
  return h(LoadingRegion, { label, className: 'space-y-6' },
    h(FilterBarSkeleton, { className: 'flex flex-col md:flex-row items-center gap-3 w-full', fields: ['md:flex-1 md:min-w-0', 'md:w-48', 'md:w-40', 'md:w-36'] }),
    h(TableSkeleton, {
      cardClassName: 'bg-white rounded-xl shadow-sm border border-gray-200 overflow-hidden w-full',
      tableClassName: 'w-full text-left border-collapse table-fixed min-w-[700px] lg:min-w-0',
      headRowClassName: LIST_HEAD_ROW,
      rowClassName: 'h-[73px]',
      footerClassName: PAGINATION_FOOTER,
      columns: [
        { label: 'Name', th: 'w-[28%] py-3.5 px-4 text-left', td: 'py-4 px-4 align-middle', cell: person() },
        { label: 'Department', th: 'w-[16%] py-3.5 px-3 text-left', td, bar: text('w-3/4', 'w-2/3') },
        { label: 'Availability', th: 'w-[14%] py-3.5 px-3 text-left', td, bar: badge('w-20') },
        { label: 'Active Tasks', th: 'w-[11%] py-3.5 px-2 text-center', td: 'py-4 px-2 align-middle', bar: text('w-6 mx-auto') },
        { label: 'Workload', th: 'w-[11%] py-3.5 px-3 text-left', td, bar: text('w-16') },
        { label: 'Status', th: 'w-[12%] py-3.5 px-3 text-left', td, bar: badge('w-16') },
        { label: 'Actions', th: 'w-[8%] py-3.5 px-4 text-right', td: 'py-4 px-4 align-middle', bar: ['h-8 w-8 rounded-lg ml-auto'] },
      ],
    }),
  );
}

// ── Assignments ───────────────────────────────────────────────────────────────────────────────────────────────

export function AssignmentsPageSkeleton({ label = 'Loading assignments...' } = {}) {
  const td = 'px-6 py-4 align-middle';
  return h(LoadingRegion, { label, className: 'space-y-6' },
    h(StatCardsSkeleton, {
      count: 4,
      gridClassName: 'grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4 lg:gap-5',
      cardClassName: 'bg-white rounded-xl p-5 shadow-sm border border-gray-200/70 flex items-center gap-4',
      iconClassName: 'h-12 w-12 rounded-xl shrink-0',
      label: ['h-3 w-28', 'h-4'],
      value: ['h-6 w-10', 'h-7'],
    }),
    h('div', { className: 'bg-white rounded-xl shadow-sm border border-gray-200/70 overflow-hidden' },
      h('div', { className: 'px-6 pt-5 pb-3 space-y-3' },
        h('div', { className: 'flex flex-col sm:flex-row sm:items-start sm:justify-between gap-3' },
          h('div', { className: 'min-w-0 flex-1' }, line('h-4 w-56', 'h-7'), line('h-3 w-72 max-w-full', 'h-5 mt-1')),
          h(Skeleton, { className: 'h-7 w-28 rounded-full self-start shrink-0' }),
        ),
        h(FilterBarSkeleton, { className: 'flex flex-col md:flex-row md:items-center gap-2 w-full', fieldClassName: 'h-9 rounded-lg', fields: ['md:flex-1 md:min-w-0', 'md:w-48', 'md:w-44'] }),
      ),
      h(TableSkeleton, {
        cardClassName: '',
        tableClassName: 'w-full min-w-[760px] text-left',
        theadClassName: 'bg-emerald-50/50 text-[11px] font-semibold uppercase tracking-wider whitespace-nowrap text-gray-600 border-y border-gray-200/70',
        rowClassName: 'h-[65px]',
        columns: [
          { label: 'Task', th: 'px-6 py-3', td, cell: (r) => h('div', { className: 'flex items-center gap-3' }, h(Skeleton, { className: 'h-8 w-8 rounded-lg shrink-0' }), h(Skeleton, { className: cx('h-3.5 rounded', ['w-3/4', 'w-2/3', 'w-4/5'][r % 3]) })) },
          { label: 'Staff Member', th: 'px-6 py-3', td, cell: () => h('div', { className: 'flex items-center gap-2.5' }, h(Skeleton, { className: 'h-7 w-7 rounded-full shrink-0' }), h(Skeleton, { className: 'h-3.5 w-28 rounded' })) },
          { label: 'Department', th: 'px-6 py-3', td, bar: text('w-24', 'w-20') },
          { label: 'Assigned Time', th: 'px-6 py-3 whitespace-nowrap text-center', td: `${td} text-center`, cell: () => h('div', { className: 'space-y-1.5' }, h(Skeleton, { className: 'h-3.5 w-20 rounded mx-auto' }), h(Skeleton, { tone: 'soft', className: 'h-3 w-14 rounded mx-auto' })) },
          { label: 'Status', th: 'px-6 py-3', td, bar: badge('w-20', 'w-24') },
          { label: 'Actions', th: 'px-6 py-3 w-20 text-right', td: `${td} text-right`, bar: ['h-8 w-8 rounded-lg ml-auto'] },
        ],
      }),
    ),
  );
}

// ── Workload ──────────────────────────────────────────────────────────────────────────────────────────────────

const WORKLOAD_SECTION = 'bg-white rounded-xl shadow-2xs border border-gray-100 px-4 pt-3 pb-4 sm:px-5 sm:pt-3.5 sm:pb-5 space-y-3';

export function WorkloadPageSkeleton({ label = 'Loading staff workload data...', showSupport = false } = {}) {
  const td = 'px-3 lg:px-4 py-3 align-middle';
  const th = 'px-3 lg:px-4 py-3 font-semibold whitespace-nowrap text-left';
  return h(LoadingRegion, { label, className: 'space-y-4' },
    // 1. summary: heading row, then four cards
    h('section', { className: WORKLOAD_SECTION },
      h('div', { className: 'flex flex-wrap items-center justify-between gap-2' }, line('h-3.5 w-36', 'h-5 sm:h-6'), line('h-3 w-24', 'h-4')),
      h(StatCardsSkeleton, {
        count: 4,
        gridClassName: 'grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4',
        cardClassName: 'bg-white rounded-xl p-4 shadow-2xs border border-gray-100 flex items-center space-x-3.5',
        iconClassName: 'h-11 w-11 sm:h-12 sm:w-12 rounded-xl shrink-0',
        label: ['h-2.5 w-24', 'h-4'],
        value: ['h-5 w-12', 'h-11 sm:h-10'],
        note: ['h-2.5 w-28', 'h-5'],
      }),
    ),
    // department thresholds legend (administrators and managers only): its own card, directly after the summary
    showSupport ? h('div', { className: 'bg-white rounded-xl p-3 border border-gray-100 shadow-2xs flex flex-wrap items-center justify-between gap-3' },
      line('h-3 w-56 max-w-full', 'h-4'),
      h('div', { className: 'flex flex-wrap items-center gap-3 sm:gap-5' }, [0, 1, 2, 3].map((i) => line('h-3 w-24', 'h-4', i))),
    ) : null,
    // 3. workload by department: heading, then department cards
    h('section', { className: cx('min-w-0', WORKLOAD_SECTION) },
      h('div', { className: 'flex flex-wrap items-start justify-between gap-x-6 gap-y-2' },
        h('div', { className: 'min-w-0' }, line('h-3.5 w-44', 'h-5 sm:h-6'), line('h-2.5 w-56 max-w-full', 'h-4 mt-0.5')),
        h('div', { className: 'min-w-0' }, line('h-3 w-48', 'h-5'), line('h-2.5 w-40', 'h-4 mt-0.5')),
      ),
      h('div', { className: 'grid grid-cols-[repeat(auto-fill,minmax(min(100%,17rem),1fr))] gap-4' }, Array.from({ length: 6 }, (_, i) => h('div', { key: i, className: 'min-w-0 bg-white rounded-xl p-4 shadow-2xs border border-gray-100 space-y-2.5' },
        h('div', { className: 'flex items-center justify-between gap-3' }, line('h-3.5 w-28', 'h-6'), h(Skeleton, { className: 'h-5 w-16 rounded-full' })),
        h(Skeleton, { className: 'h-2.5 w-full rounded-full' }),
        h('div', { className: 'flex items-center justify-between gap-3' }, line('h-3 w-20', 'h-5'), line('h-3 w-16', 'h-5')),
      ))),
    ),
    // cross-department support (administrators and managers only): the panel's frame, which loads its own content afterwards
    showSupport ? h('section', { className: 'min-w-0 bg-white rounded-2xl shadow-2xs border border-gray-100 px-4 pt-4 pb-5 sm:px-6 sm:pt-5 sm:pb-6 space-y-6' },
      h('div', { className: 'space-y-1' }, line('h-4 w-52', 'h-6'), line('h-3 w-80 max-w-full', 'h-5')),
      h('div', { className: 'grid grid-cols-1 sm:grid-cols-3 gap-4' }, [0, 1, 2].map((i) => h(Skeleton, { key: i, tone: 'soft', className: 'h-[84px] rounded-xl' }))),
      h(Skeleton, { tone: 'soft', className: 'h-[110px] sm:h-[180px] rounded-xl' }),
    ) : null,
    // 4. staff workload distribution: heading row, then the table (md and up) or one card per person (phones)
    h('div', { className: 'min-w-0 bg-white rounded-xl shadow-2xs border border-gray-100 overflow-hidden' },
      h('div', { className: 'p-4 border-b border-gray-100 flex flex-col sm:flex-row sm:items-center justify-between gap-3 bg-white' },
        h('div', null, line('h-3.5 w-48', 'h-5 sm:h-6'), line('h-2.5 w-64 max-w-full', 'h-4 mt-0.5')),
        h(Skeleton, { className: 'h-7 w-40 rounded-full self-start sm:self-auto' }),
      ),
      h(TableSkeleton, {
        cardClassName: 'hidden md:block w-full overflow-hidden',
        scrollClassName: '',
        tableClassName: 'w-full text-left border-collapse table-fixed',
        colWidths: ['w-[24%] lg:w-[25%]', 'w-[16%] lg:w-[18%]', 'w-[11%] lg:w-[10%]', 'w-[12%] lg:w-[10%]', 'w-[18%] lg:w-[22%]', 'w-[19%] lg:w-[15%]'],
        theadClassName: 'bg-emerald-50/50 text-xs font-semibold text-gray-500 uppercase tracking-wider border-b border-emerald-100/70 select-none',
        rowClassName: 'h-[66px]',
        rows: 5,
        columns: [
          { label: 'Staff', th, td, cell: person('h-8 w-8') },
          { label: 'Department', th, td, bar: badge('w-24', 'w-20') },
          { label: 'Tasks', th, td, bar: text('w-6') },
          { label: 'Workload', th, td, bar: text('w-14') },
          { label: 'Utilization', th, td, cell: () => h('div', { className: 'flex items-center gap-2' }, h(Skeleton, { className: 'h-2 flex-1 rounded-full' }), h(Skeleton, { className: 'h-3.5 w-8 rounded' })) },
          { label: 'Availability', th, td, bar: badge('w-20') },
        ],
      }),
      h('div', { className: 'block md:hidden divide-y divide-gray-100' }, Array.from({ length: 5 }, (_, i) => h('div', { key: i, className: 'p-4 space-y-3' },
        person('h-9 w-9')(i),
        h('div', { className: 'grid grid-cols-2 gap-3' }, [0, 1, 2, 3].map((k) => h('div', { key: k, className: 'space-y-1.5' }, h(Skeleton, { tone: 'soft', className: 'h-2.5 w-14 rounded' }), h(Skeleton, { className: 'h-3.5 w-16 rounded' })))),
        h(Skeleton, { className: 'h-2 w-full rounded-full' }),
        h('div', { className: 'flex items-center justify-between gap-3' }, h(Skeleton, { className: 'h-5 w-20 rounded-full' }), h(Skeleton, { className: 'h-3.5 w-16 rounded' })),
      ))),
    ),
  );
}

// ── Departments ───────────────────────────────────────────────────────────────────────────────────────────────

export function DepartmentsPageSkeleton({ label = 'Loading departments...' } = {}) {
  return h(LoadingRegion, { label, className: 'space-y-6' },
    h(StatCardsSkeleton, {
      count: 3,
      gridClassName: 'grid grid-cols-1 md:grid-cols-3 gap-6',
      cardClassName: 'bg-white rounded-xl px-6 py-4 shadow-sm border border-gray-100 flex items-center space-x-4',
      iconClassName: 'h-12 w-12 rounded-lg shrink-0',
      label: ['h-3 w-28', 'h-5'],
      value: ['h-5 w-10', 'h-7'],
    }),
    h('div', { className: 'grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-5 lg:gap-6' }, Array.from({ length: 6 }, (_, i) => h('div', { key: i, className: 'min-w-0 bg-white rounded-2xl border border-gray-200/80 shadow-sm' },
      h('div', { className: 'flex items-center gap-4 px-5 pt-5 pb-4' }, h(Skeleton, { className: 'w-12 h-12 rounded-xl shrink-0' }), h('div', { className: 'min-w-0 flex-1' }, line(cx('h-4', ['w-32', 'w-40', 'w-28'][i % 3]), 'h-7'))),
      h('div', { className: 'mx-5 border-t border-gray-100' }),
      h('div', { className: 'px-5 pt-4 pb-5 space-y-3.5' }, [0, 1].map((r) => h('div', { key: r, className: 'flex items-center gap-3 h-[23px]' },
        h(Skeleton, { className: 'w-5 h-5 rounded shrink-0' }), h(Skeleton, { className: 'h-3.5 w-16 rounded' }), h(Skeleton, { className: 'h-4 w-8 rounded ml-auto' }),
      ))),
    ))),
  );
}

// ── Users ─────────────────────────────────────────────────────────────────────────────────────────────────────

export function UsersPageSkeleton({ label = 'Loading users…' } = {}) {
  const td = 'px-5 py-3.5 align-middle';
  return h(LoadingRegion, { label, className: 'space-y-6' },
    // the search and filters (unchanged from the earlier placeholder: the same grid and the 44px field height)
    h('div', { className: 'grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-[minmax(0,1.35fr)_minmax(0,1fr)_minmax(0,1fr)] gap-3 sm:gap-4' },
      h(Skeleton, { className: 'h-11 rounded-xl sm:col-span-2 lg:col-span-1' }),
      h(Skeleton, { className: 'h-11 rounded-xl' }),
      h(Skeleton, { className: 'h-11 rounded-xl' }),
    ),
    // the users table in its card, in place of the earlier single block
    h(TableSkeleton, {
      cardClassName: 'bg-white rounded-xl border border-gray-200 shadow-sm overflow-hidden',
      tableClassName: 'w-full min-w-[800px] text-left table-fixed',
      colWidths: ['w-[36%]', 'w-[15%]', 'w-[14%]', 'w-[20%]', 'w-[15%]'],
      theadClassName: 'bg-gray-50 border-b border-gray-200',
      headRowClassName: 'text-sm font-semibold text-gray-800',
      rowClassName: 'h-[77px]',
      columns: [
        { label: 'Username', th: 'px-5 py-3.5 border-r border-gray-100', td, cell: person('h-10 w-10') },
        { label: 'Role', th: 'px-5 py-3.5 border-r border-gray-100', td, bar: badge('w-16') },
        { label: 'Status', th: 'px-5 py-3.5 border-r border-gray-100', td, bar: badge('w-16') },
        { label: 'Created', th: 'px-5 py-3.5 border-r border-gray-100', td, bar: text('w-24') },
        { label: 'Actions', th: 'px-5 py-3.5 text-center', td: `${td} text-center`, bar: ['h-8 w-8 rounded-lg mx-auto'] },
      ],
    }),
  );
}
