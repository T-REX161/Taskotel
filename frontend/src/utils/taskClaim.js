/**
 * Staff self-service task claiming (Available Tasks): the rules the screen follows. Pure functions, so they can be tested without a
 * browser. The backend decides every claim (department, state, eligibility, who is first); this only decides what to show.
 */

export const CLAIM_FALLBACK_ERROR = 'The task could not be accepted. Please try again.';
export const AVAILABLE_LOAD_ERROR = 'Available tasks could not be loaded. Check your connection and try again.';
export const CLAIM_CONFLICT_MESSAGE = 'This task was just accepted by another staff member. The list has been refreshed.';

export const claimSuccessMessage = (title) => `You accepted ${title ? `"${title}"` : 'the task'}. It is now in My Tasks.`;

/**
 * What a failed claim means for the screen:
 *   conflict – 409: somebody else was first (or the task is no longer waiting, or the caller may not take another task). The server's own
 *              message is kept, because it says which of these it is; the list must be refreshed either way.
 *   gone     – 404: the task is no longer there for this person (deleted, or moved out of their department). Refresh too.
 *   error    – anything else: the server's message, or a fixed fallback.
 */
export function claimFailure(error) {
  const status = error?.status;
  const message = typeof error?.message === 'string' && error.message.trim() ? error.message.trim() : '';
  if (status === 409) return { kind: 'conflict', message: message || CLAIM_CONFLICT_MESSAGE };
  if (status === 404) return { kind: 'gone', message: 'This task is no longer available. The list has been refreshed.' };
  return { kind: 'error', message: message || CLAIM_FALLBACK_ERROR };
}

/** The answer of GET /api/tasks/available as the screen uses it; anything malformed is treated as "nothing to claim", never as tasks. */
export function normalizeAvailable(data) {
  const tasks = Array.isArray(data?.tasks) ? data.tasks.filter((task) => task && typeof task === 'object' && task.id) : [];
  const canClaim = data?.canClaim !== false;
  // A person who cannot accept (for example they already hold an active task) may still be sent the open tasks to look at; the server
  // decides that, and claim() still refuses them. Tasks are kept whatever canClaim says.
  return { canClaim, reason: canClaim ? '' : (typeof data?.reason === 'string' ? data.reason : ''), tasks };
}

/** 'loading' | 'error' | 'blocked' (cannot accept right now and nothing to show, with a reason) | 'empty' | 'ready'. Loading wins, then an error. */
export function availableViewState({ loading = false, error = '', canClaim = true, count = 0 }) {
  if (loading) return 'loading';
  if (error) return 'error';
  if (!canClaim && count === 0) return 'blocked';
  return count > 0 ? 'ready' : 'empty';
}

/**
 * One claim, start to finish: send it, then ALWAYS refresh (after a success so the task moves from Available Tasks to My Tasks, after a
 * failure so the screen matches the server). Resolves to what to tell the person:
 *   { ok: true, message }                   – this person got the task
 *   { ok: false, kind, message }            – conflict | gone | error (see claimFailure)
 *   { redirect: true }                      – the session ended; the browser is already on its way to sign-in, nothing is refreshed
 * A refresh that fails does not change the outcome of the claim (the page shows its own error for the list).
 */
export async function performClaim(task, { claim, refresh }) {
  let outcome;
  try {
    await claim(task.id);
    outcome = { ok: true, message: claimSuccessMessage(task.title) };
  } catch (error) {
    if (error?.message && error.message.includes('Redirecting to login')) return { redirect: true };
    outcome = { ok: false, ...claimFailure(error) };
  }
  try {
    await refresh();
  } catch {
    // the list shows its own error and Retry
  }
  return outcome;
}
