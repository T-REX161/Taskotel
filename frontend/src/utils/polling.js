// Small helpers for refreshing data in the background. No dependencies; everything the helpers touch is passed in, so they are easy to test.

/** Wraps an async function so a call made while the previous one is still running is skipped (no overlapping requests). */
export function singleFlight(fn) {
  let running = false;
  return async (...args) => {
    if (running) return undefined;
    running = true;
    try {
      return await fn(...args);
    } finally {
      running = false;
    }
  };
}

/**
 * Calls `refresh` every `intervalMs` while the page is visible. When the tab becomes hidden the timer is cleared (no requests at
 * all while hidden); when it becomes visible again `refresh` runs immediately and the timer restarts. Returns `stop()`, which
 * clears the timer and removes the listener.
 */
export function startPolling({ refresh, intervalMs, doc = document, setTimer = setInterval, clearTimer = clearInterval }) {
  let timer = null;
  const isVisible = () => doc.visibilityState !== 'hidden';
  const arm = () => {
    if (timer === null) timer = setTimer(() => { if (isVisible()) refresh(); }, intervalMs);
  };
  const disarm = () => {
    if (timer !== null) {
      clearTimer(timer);
      timer = null;
    }
  };
  const onVisibility = () => {
    if (isVisible()) {
      refresh();
      arm();
    } else {
      disarm();
    }
  };
  doc.addEventListener('visibilitychange', onVisibility);
  if (isVisible()) arm();
  return () => {
    disarm();
    doc.removeEventListener('visibilitychange', onVisibility);
  };
}

/** Keeps the previous state object when the new data is identical, so a refresh that changed nothing causes no re-render. */
export const keepIfSame = (prev, next) => (prev && JSON.stringify(prev.data) === JSON.stringify(next.data) && prev.status === next.status ? prev : next);
