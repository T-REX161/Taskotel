import { useEffect, useRef, useState } from 'react';
import { checkUsernameAvailability } from '../services/api';
import { createUsernameChecker } from '../utils/usernameChecker';

const IDLE = { username: '', status: 'idle', reason: null, suggestions: [], suggestionsLoading: false };

/**
 * Live availability of `username`, with verified alternatives when it is taken.
 * `recheckKey` forces a fresh check of the same username (for example after the server rejected it).
 */
export default function useUsernameAvailability(username, fullName, recheckKey = 0) {
  const [state, setState] = useState(IDLE);
  const checkerRef = useRef(null);
  const fullNameRef = useRef(fullName);
  fullNameRef.current = fullName;

  useEffect(() => {
    const checker = createUsernameChecker({ check: checkUsernameAvailability, onUpdate: setState });
    checkerRef.current = checker;
    return () => checker.dispose();
  }, []);

  useEffect(() => {
    checkerRef.current?.update(username, fullNameRef.current);
  }, [username, recheckKey]);

  return state;
}
