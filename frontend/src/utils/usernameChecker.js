import { candidateUsernames, validateUsername } from './username.js';

/**
 * Debounced username availability checks with stale-result protection. Framework independent so it can be tested.
 *
 * check(username, signal) -> Promise<boolean>  (true only when the server said the username is free)
 * onUpdate(state) with { username, status, reason, suggestions, suggestionsLoading }, where status is
 *   'idle' | 'invalid' | 'checking' | 'available' | 'taken' | 'error'.
 *
 * Every update() invalidates earlier work: a late response for an older username is dropped, never shown.
 * Only an answer of exactly true produces 'available'; a failed request produces 'error', never 'available'.
 */
export function createUsernameChecker({ check, onUpdate, debounceMs = 400, maxSuggestions = 3 }) {
  let sequence = 0;
  let timer = null;
  let controller = null;
  let disposed = false;

  const emit = (id, state) => {
    if (!disposed && id === sequence) {
      onUpdate({ reason: null, suggestions: [], suggestionsLoading: false, ...state });
    }
  };

  const cancelPending = () => {
    clearTimeout(timer);
    timer = null;
    if (controller) controller.abort();
    controller = null;
  };

  const run = async (id, username, fullName) => {
    controller = new AbortController();
    const { signal } = controller;
    let available;
    try {
      available = await check(username, signal);
    } catch {
      emit(id, { username, status: 'error' });
      return;
    }
    if (available === true) {
      emit(id, { username, status: 'available' });
      return;
    }
    emit(id, { username, status: 'taken', suggestionsLoading: true });
    const candidates = candidateUsernames(username, fullName);
    const settled = await Promise.allSettled(candidates.map((candidate) => check(candidate, signal)));
    // Failed lookups are simply left out: an alternative is only offered once the server confirmed it is free
    const suggestions = candidates.filter((_, i) => settled[i].status === 'fulfilled' && settled[i].value === true).slice(0, maxSuggestions);
    emit(id, { username, status: 'taken', suggestions });
  };

  return {
    update(username, fullName = '') {
      cancelPending();
      const id = ++sequence;
      const value = String(username || '').trim();
      const validity = validateUsername(value);
      if (validity !== 'ok') {
        emit(id, { username: value, status: validity === 'empty' ? 'idle' : 'invalid', reason: validity });
        return;
      }
      emit(id, { username: value, status: 'checking' });
      timer = setTimeout(() => run(id, value, fullName), debounceMs);
    },
    dispose() {
      disposed = true;
      cancelPending();
    },
  };
}
