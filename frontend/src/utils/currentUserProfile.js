/**
 * The signed-in user's profile (including the profile picture URL), loaded once and shared by the header and the profile page.
 * Navigating between pages does not fetch it again; an upload or removal updates it in place so every avatar changes at once.
 * The cache belongs to one username: after signing in as someone else it is loaded afresh.
 */
import { getProfile, getUsername } from '../services/api.js';

const EMPTY = Object.freeze({ username: null, profile: null, loading: false, error: null });

let snapshot = EMPTY;
let inFlight = null;
// The newest request: an older one's answer is not applied, so it can never overwrite a later retry, an upload's result or a sign-out
let latest = 0;
const listeners = new Set();

function emit(next) {
  snapshot = next;
  listeners.forEach((listener) => listener());
}

export function subscribeCurrentProfile(listener) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function getCurrentProfileSnapshot() {
  return snapshot;
}

/** The cached profile for `username`, or null when it is not loaded (or belongs to someone else). */
export function profileFor(state, username) {
  return username && state.username === username ? state.profile : null;
}

/**
 * Loads the profile unless it is already cached for this user (or a load is already running, which is then shared).
 * Resolves with the profile, or null when it could not be loaded (the default avatar is shown meanwhile).
 */
export function loadCurrentProfile({ force = false, username = getUsername(), fetchProfile = getProfile } = {}) {
  if (!username) return Promise.resolve(null);
  if (!force && snapshot.username === username && snapshot.profile) return Promise.resolve(snapshot.profile);
  if (inFlight && inFlight.username === username) return inFlight.promise;

  const request = ++latest;
  const isCurrent = () => request === latest && snapshot.username === username;
  emit({ username, profile: profileFor(snapshot, username), loading: true, error: null });
  const promise = Promise.resolve()
    .then(() => fetchProfile())
    .then((profile) => {
      if (isCurrent()) emit({ username, profile, loading: false, error: null });
      return profile;
    })
    .catch((error) => {
      if (isCurrent()) emit({ username, profile: snapshot.profile, loading: false, error });
      return null;
    })
    .finally(() => {
      if (inFlight && inFlight.promise === promise) inFlight = null;
    });
  inFlight = { username, promise };
  return promise;
}

/** Replaces the cached profile with the one an upload or removal returned. */
export function setCurrentProfile(profile) {
  if (!profile) return;
  // A load that started before this change would bring back older data: it is dropped, and the next load starts afresh
  latest += 1;
  inFlight = null;
  emit({ username: profile.username || snapshot.username, profile, loading: false, error: null });
}

export function resetCurrentProfile() {
  latest += 1;
  inFlight = null;
  emit(EMPTY);
}

// ── what a page shows for the profile (Stage 5B) ────────────────────────────────────────────────────────────────

export const PROFILE_LOAD_ERROR_MESSAGE = 'Your profile could not be loaded. Check your connection and try again.';

/**
 * 'ready' | 'loading' | 'error'. A loaded profile is always shown (a failed background refresh keeps it); without one, a failed
 * request is an error (never an endless "Loading…"), and anything else, including the moment before the first request, is loading.
 */
export function profileLoadState({ profile, loading, error }) {
  if (profile) return 'ready';
  if (error && !loading) return 'error';
  return 'loading';
}

/** Retry: load the profile again through the shared flow (a request already running is reused, not duplicated). */
export const retryCurrentProfile = () => loadCurrentProfile({ force: true });
