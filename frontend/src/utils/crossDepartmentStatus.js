/*
 * The Cross-Department Assistance switch, read once and shared by the Workload page and its support panel.
 *
 * The page starts a fresh read as it opens (alongside its workload request), so its loading skeleton only reserves room for the
 * support panel when the panel will be shown; the panel mounts later and reuses that same answer instead of asking again. Later
 * reads (the panel's 30-second check, Refresh, returning to the tab) are forced, so a changed setting is still picked up.
 *
 * Only successful answers are kept. A failed read rejects (so a caller can keep what it already knew) and leaves the last known
 * value in place. Like the settings, the answer comes from GET /api/support/cross-department-status (Admin and Manager).
 */
import { getCrossDepartmentStatus } from '../services/api.js';
import { crossDepartmentAssistanceState } from './systemSettings.js';

let known = null; // 'on' | 'off' | 'unknown' (an answer the server sent but that was not a clear yes or no)
let inFlight = null;

/** The last known switch, without a request: null until one read has succeeded in this session. */
export const peekCrossDepartmentStatus = () => known;

/**
 * Resolves 'on' | 'off' | 'unknown'. A read already running is always shared (it is as fresh as a new one), so callers never send
 * the same request twice. Otherwise `force` starts a new read and, without it, a known value is reused. Rejects when the request fails.
 */
export function loadCrossDepartmentStatus({ force = false, fetcher = getCrossDepartmentStatus } = {}) {
  if (inFlight) return inFlight;
  if (known && !force) return Promise.resolve(known);
  const promise = Promise.resolve()
    .then(() => fetcher())
    .then((response) => {
      const state = crossDepartmentAssistanceState(response);
      if (inFlight === promise) known = state;
      return state;
    })
    .finally(() => {
      if (inFlight === promise) inFlight = null;
    });
  inFlight = promise;
  return promise;
}

/** Forget what is known (tests, or a different signed-in user). */
export function resetCrossDepartmentStatus() {
  known = null;
  inFlight = null;
}
