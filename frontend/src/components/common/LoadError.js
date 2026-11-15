import { createElement as h } from 'react';

/*
 * The message shown when data could not be loaded (Stage 5C), with an optional Retry.
 *
 *   variant "panel"  – a page's main content failed: a title, the message and a filled Retry button (Dashboard, Tasks, ...)
 *   variant "banner" – one part failed: the message on one line with an outlined Retry beside it (a dialog's staff list, the profile)
 *
 * It is a single alert region (role="alert"); nothing inside it is a live region of its own. Without `onRetry` it is still a complete,
 * readable message with no dead button. Props: message (required), title, onRetry, retryLabel ("Retry"), retryDisabled, variant,
 * compact (banner text at text-xs), testId. Written without JSX so it can be rendered in Node tests.
 */

const PANEL = 'p-6 bg-red-50 border border-red-200 rounded-2xl text-red-800 space-y-3';
const PANEL_BUTTON = 'px-4 py-2 bg-red-600 hover:bg-red-700 text-white rounded-xl text-sm font-medium transition-colors cursor-pointer disabled:cursor-not-allowed disabled:opacity-60';
const BANNER = 'p-4 rounded-xl bg-red-50 border border-red-200 text-red-800 flex flex-wrap items-center justify-between gap-3';
const BANNER_BUTTON = 'px-3 py-1.5 rounded-lg border border-red-300 bg-white text-red-700 text-xs font-semibold hover:bg-red-50 cursor-pointer focus:outline-none focus-visible:ring-2 focus-visible:ring-red-400 disabled:cursor-not-allowed disabled:opacity-60';

export default function LoadError({
  message,
  title = '',
  onRetry,
  retryLabel = 'Retry',
  retryDisabled = false,
  variant = 'panel',
  compact = false,
  testId,
}) {
  const retry = typeof onRetry === 'function'
    ? h('button', { type: 'button', onClick: onRetry, disabled: retryDisabled, className: variant === 'banner' ? BANNER_BUTTON : PANEL_BUTTON }, retryLabel)
    : null;

  if (variant === 'banner') {
    return h(
      'div',
      { role: 'alert', 'data-testid': testId, className: `${BANNER} ${compact ? 'text-xs' : 'text-sm'}` },
      h('span', { className: 'font-medium' }, title ? `${title} ${message}` : message),
      retry,
    );
  }
  return h(
    'div',
    { role: 'alert', 'data-testid': testId, className: PANEL },
    title ? h('h3', { className: 'font-bold text-base' }, title) : null,
    h('p', { className: 'text-sm' }, message),
    retry,
  );
}
