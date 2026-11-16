// What the Workload page does with the answers of one refresh (the three requests: workload rows, department cards, staff list).
//
// The server decides what a Manager may see and applies a revocation on the very next request. This module decides what the PAGE keeps
// when a refresh does not succeed, so that a Manager's screen cannot go on showing departments they no longer manage:
//
//   * a refresh that succeeds replaces everything (rows, departments, staff list), and ends any stale or error state;
//   * a Manager whose refresh is refused with 403 (their access changed: for example their department was deactivated) loses everything
//     on screen at once and sees the server's message with a Retry;
//   * any other failure (a network error, a 5xx) keeps the last good data, marked as stale, for at most MAX_STALE_MS since the last
//     success, and then clears it;
//   * a partial failure is decided per part: the rows are the anchor (a Manager's department list and staff list are never kept from an
//     older refresh), and a request that fails alone never leaves older data beside fresh rows.
//
// All of this is for a MANAGER only. For anyone else every case behaves exactly as the page always has. Hiding or clearing here is not a
// security control (the server refuses what is not allowed); it keeps the screen honest.

/** The longest a Manager's page keeps showing data that could not be refreshed: two minutes since the last successful refresh. */
export const MAX_STALE_MS = 2 * 60 * 1000;

export const STALE_LIMIT_MESSAGE =
  'The staff workload could not be refreshed for 2 minutes, so it is no longer shown. Check your connection and try again.';

const DEFAULT_FAILURE_MESSAGE = 'Unable to load staff workload data. Please try again.';

const isManagerRole = (role) => String(role || '').trim().toUpperCase() === 'MANAGER';

/** True when the server refused the request because the signed-in user's access changed (HTTP 403). */
export function isAccessDenied(error) {
  return Boolean(error) && error.status === 403;
}

const CLEARED = { workload: [], departments: [], allStaff: [], staleSince: null };

/**
 * @param {object} args
 * @param {string} args.role          the signed-in user's role
 * @param {boolean} args.isBackground a quiet refresh of data already on screen (false: the first load or a Retry)
 * @param {number} args.now           the time, in ms
 * @param {number} args.lastSuccessAt the time of the last successful refresh, in ms (0 when there has been none)
 * @param {{workload: Result, departments: Result, staff: Result}} args.outcome one settled result per request:
 *        {ok: true, data} or {ok: false, error}
 * @returns {object} { action, ... } where action is one of
 *   'replace'     rows, departments (null: keep what is shown), allStaff (null: keep), staleSince null, clearError
 *   'clear'       nothing may stay on screen: empty data, and the error to show
 *   'keep-stale'  keep the data on screen and show that it is old since staleSince
 *   'keep'        change nothing (a quiet failure with nothing to mark)
 *   'error'       show the error panel (the first load or a Retry failed)
 *   'redirect'    the session ended: go to the sign-in page
 */
export function decideWorkloadRefresh({ role, isBackground, now, lastSuccessAt = 0, outcome }) {
  const manager = isManagerRole(role);
  const { workload, departments, staff } = outcome;

  if (workload.ok) {
    const rows = Array.isArray(workload.data) ? workload.data : [];
    // Anyone else keeps the previous list when a quiet refresh of it fails (null: keep); a Manager's list always comes from this refresh
    const part = (result) => (result.ok && Array.isArray(result.data) ? result.data : !manager && isBackground ? null : []);
    return { action: 'replace', workload: rows, departments: part(departments), allStaff: part(staff), staleSince: null, clearError: manager };
  }

  const error = workload.error;
  const message = (error && error.message) || DEFAULT_FAILURE_MESSAGE;
  if (message.includes('Redirecting to login')) return { action: 'redirect' };

  if (!manager) return isBackground ? { action: 'keep' } : { action: 'error', error: message };

  // A Manager
  if (isAccessDenied(error)) return { action: 'clear', ...CLEARED, error: message };
  if (!isBackground) return { action: 'error', error: message };
  if (!lastSuccessAt) return { action: 'keep' }; // nothing was ever shown, so nothing can be stale
  if (now - lastSuccessAt >= MAX_STALE_MS) return { action: 'clear', ...CLEARED, error: STALE_LIMIT_MESSAGE };
  return { action: 'keep-stale', staleSince: lastSuccessAt };
}

/**
 * The staff member whose details are open after a refresh. For a Manager the details close when that person is no longer among the
 * refreshed (authorized) rows; for anyone else nothing changes.
 */
export function selectedStaffAfterRefresh(role, selected, rows) {
  if (!selected || !isManagerRole(role)) return selected ?? null;
  const id = String(selected.id ?? selected.staffId ?? '').toLowerCase();
  const present = (Array.isArray(rows) ? rows : []).some((row) => row && String(row.staffId ?? '').toLowerCase() === id && id !== '');
  return present ? selected : null;
}
