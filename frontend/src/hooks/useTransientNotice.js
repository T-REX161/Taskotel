import { useCallback, useEffect, useRef, useState } from 'react';

/** How long a success notice stays on screen. */
export const NOTICE_DURATION_MS = 3000;

/**
 * A message that dismisses itself.
 *
 * `show(message)` displays it and (re)starts the timer, even when the text is the same as the one already showing, so a second
 * update before the first notice has gone gets a full new duration. `clear()` removes it at once. The timer is cancelled whenever
 * the notice changes, is cleared, or the component unmounts (leaving the page), so no stale timer can fire later.
 *
 * Returns { notice, show, clear }; `notice` is the message text, or '' when nothing is showing.
 */
export default function useTransientNotice(duration = NOTICE_DURATION_MS) {
  const [state, setState] = useState(null); // { message, id }; a new id every time, so the effect below always restarts
  const counter = useRef(0);

  const show = useCallback((message) => {
    counter.current += 1;
    setState(message ? { message, id: counter.current } : null);
  }, []);
  const clear = useCallback(() => setState(null), []);

  useEffect(() => {
    if (!state) return undefined;
    const timer = setTimeout(() => setState(null), duration);
    return () => clearTimeout(timer);
  }, [state, duration]);

  return { notice: state ? state.message : '', show, clear };
}
