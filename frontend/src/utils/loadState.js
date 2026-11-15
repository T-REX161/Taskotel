/*
 * Loading outcomes for lists the pages fetch for their forms (Stage 5A of the loading-state plan).
 *
 * A failed request must never look like a successful, empty answer: "No available staff" is only true after the server answered
 * with no one. Every list therefore has four states: loading, error, empty (a successful answer with nothing in it) and ready.
 */

export const STAFF_LOAD_ERROR_MESSAGE = 'Staff members could not be loaded. Check your connection and try again.';
export const WORKLOAD_LOAD_ERROR_MESSAGE = 'Workload data could not be loaded, so projected workload is not available.';

/** 'loading' | 'error' | 'empty' | 'ready'. Loading wins (a retry is under way), then an error, then the answer itself. */
export function listState({ loading = false, error = '', count = 0 }) {
  if (loading) return 'loading';
  if (error) return 'error';
  return count > 0 ? 'ready' : 'empty';
}

/**
 * Only the newest request may update the page: a slower, older answer (an earlier open of the dialog, a retry overtaken by a full
 * reload) is dropped. `next()` starts a request and returns its token; `isCurrent(token)` says whether it is still the newest;
 * `invalidate()` drops every request in flight (for example when the page goes away).
 */
export function createLatestRequest() {
  let current = 0;
  return {
    next: () => ++current,
    isCurrent: (token) => token === current,
    invalidate: () => { current += 1; },
  };
}

/** The 401 path in services/api.js has already sent the browser to the sign-in page: nothing to show for it. */
export const isRedirectError = (error) => Boolean(error?.message && error.message.includes('Redirecting to login'));

// ── Assignments page: data that supports the list (tasks, workload, staff) ───────────────────────────────────────────

export const SUPPORTING_SOURCES = ['tasks', 'workload', 'staff'];

const SOURCE_LABELS = { tasks: 'task details', workload: 'workload', staff: 'staff members' };

/**
 * Turns Promise.allSettled results for some sources into what the page applies:
 *   values – the new list for each source that answered (a non-array answer counts as an empty list, as before)
 *   status – 'ready' for each source that answered, 'error' for each that failed (its previous data is kept, not replaced by [])
 *   redirect – a failure was the sign-in redirect
 */
export function settleSupportingData(sources, settled) {
  const values = {};
  const status = {};
  let redirect = false;
  sources.forEach((source, index) => {
    const result = settled[index];
    if (result?.status === 'fulfilled') {
      values[source] = Array.isArray(result.value) ? result.value : [];
      status[source] = 'ready';
    } else {
      status[source] = 'error';
      if (isRedirectError(result?.reason)) redirect = true;
    }
  });
  return { values, status, redirect };
}

/** Sources currently in a given state, in a fixed order. */
export const sourcesIn = (statusBySource, state) => SUPPORTING_SOURCES.filter((source) => statusBySource[source] === state);

/** The notice for supporting data that failed, or '' when everything loaded. */
export function supportingDataNotice(failedSources) {
  if (!failedSources.length) return '';
  const labels = failedSources.map((source) => SOURCE_LABELS[source]);
  const list = labels.length === 1 ? labels[0] : `${labels.slice(0, -1).join(', ')} and ${labels[labels.length - 1]}`;
  return `${list[0].toUpperCase()}${list.slice(1)} could not be loaded. Assignments are listed, but details that depend on ${failedSources.length === 1 ? 'it' : 'them'} may be missing or incomplete.`;
}
