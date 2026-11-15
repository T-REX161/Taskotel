import { createElement as h } from 'react';
import { VIEW_ONLY_HINT, VIEW_ONLY_LABEL } from '../../utils/managerViewAccess.js';

/**
 * The small "View only" label on a record a Manager sees only through an Administrator's view-only grant for another department.
 * Purely informative: the server refuses every change to such a record anyway. Written without JSX so it can be rendered in Node tests.
 */
export default function ViewOnlyBadge({ className = '' }) {
  return h(
    'span',
    {
      'data-view-only': '',
      title: VIEW_ONLY_HINT,
      className: `inline-flex shrink-0 items-center gap-1 rounded-md border border-sky-200 bg-sky-50 px-1.5 py-0.5 text-[10px] font-semibold uppercase tracking-wide text-sky-700 ${className}`.trim(),
    },
    h(
      'svg',
      { className: 'w-3 h-3', viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', strokeWidth: 2, 'aria-hidden': 'true' },
      h('path', { d: 'M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z' }),
      h('circle', { cx: 12, cy: 12, r: 3 }),
    ),
    VIEW_ONLY_LABEL,
  );
}
