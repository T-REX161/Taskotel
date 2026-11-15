import { createElement as h } from 'react';

/*
 * A placeholder block shown while content loads (Stage 5C). Shape and size come from `className` (for example "h-11 rounded-xl" or
 * "h-3.5 w-40 rounded"); `tone` picks the strong (gray-200) or soft (gray-100) fill, which the dark theme remaps like every other gray.
 *
 * Placeholders are decoration: each is hidden from assistive technology, so the region that holds them should say what is loading
 * (aria-busy, and a status message). The pulse follows the project's convention for loading animations: it keeps running with
 * reduced motion (see src/index.css). Written without JSX so it can be rendered in Node tests.
 */

export const SKELETON_TONES = Object.freeze({ strong: 'bg-gray-200', soft: 'bg-gray-100' });

export default function Skeleton({ className = '', tone = 'strong', as = 'div' }) {
  const classes = ['animate-pulse', SKELETON_TONES[tone] || SKELETON_TONES.strong, className].filter(Boolean).join(' ');
  return h(as, { className: classes, 'aria-hidden': 'true', 'data-skeleton': '' });
}
