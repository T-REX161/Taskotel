import { createElement as h } from 'react';

/*
 * The loading spinner used across Taskotel (Stage 5C): one graphic instead of a copy in every file.
 *
 * The graphic is decoration: it is hidden from assistive technology, so the text beside it (a button label such as "Saving…", or a
 * page's "Loading tasks…" status) is what screen readers announce. It keeps spinning with reduced motion, like every functional loading
 * animation in the app (see the reduced-motion notes in src/index.css). Written without JSX so it can be rendered in Node tests.
 */

export const SPINNER_SIZES = Object.freeze({
  xs: 'h-3.5 w-3.5', // inline hints (username check)
  sm: 'h-4 w-4', // buttons and inline messages
  md: 'h-7 w-7', // compact page loaders
  lg: 'h-8 w-8', // page loaders
});

const ARC = 'M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z';

/** `size`: xs | sm | md | lg (default sm). `className`: colour and spacing, e.g. "text-white" or "text-emerald-600 -ml-1 mr-2". */
export default function Spinner({ size = 'sm', className = '' }) {
  const classes = ['animate-spin', SPINNER_SIZES[size] || SPINNER_SIZES.sm, className].filter(Boolean).join(' ');
  return h(
    'svg',
    { className: classes, fill: 'none', viewBox: '0 0 24 24', 'aria-hidden': 'true', focusable: 'false', 'data-spinner': '' },
    h('circle', { className: 'opacity-25', cx: '12', cy: '12', r: '10', stroke: 'currentColor', strokeWidth: '4' }),
    h('path', { className: 'opacity-75', fill: 'currentColor', d: ARC }),
  );
}
