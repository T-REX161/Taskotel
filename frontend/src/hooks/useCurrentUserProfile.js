import { useEffect, useSyncExternalStore } from 'react';
import { getUsername } from '../services/api';
import {
  getCurrentProfileSnapshot,
  loadCurrentProfile,
  profileFor,
  subscribeCurrentProfile,
} from '../utils/currentUserProfile';

/** The signed-in user's profile from the shared cache; it is fetched at most once per user (see utils/currentUserProfile). */
export default function useCurrentUserProfile() {
  const state = useSyncExternalStore(subscribeCurrentProfile, getCurrentProfileSnapshot, getCurrentProfileSnapshot);
  const username = getUsername();

  useEffect(() => {
    loadCurrentProfile({ username });
  }, [username]);

  return { profile: profileFor(state, username), loading: state.loading, error: state.error };
}
