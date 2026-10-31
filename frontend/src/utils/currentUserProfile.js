/**
 * The signed-in user's profile (including the profile picture URL), loaded once and shared by the header and the profile page.
 * Navigating between pages does not fetch it again; an upload or removal updates it in place so every avatar changes at once.
 * The cache belongs to one username: after signing in as someone else it is loaded afresh.
 */
import { getProfile, getUsername } from '../services/api.js';

const EMPTY = Object.freeze({ username: null, profile: null, loading: false, error: null });

let snapshot = EMPTY;
let inFlight = null;
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

  emit({ username, profile: profileFor(snapshot, username), loading: true, error: null });
  const promise = Promise.resolve()
    .then(() => fetchProfile())
    .then((profile) => {
      if (snapshot.username === username) emit({ username, profile, loading: false, error: null });
      return profile;
    })
    .catch((error) => {
      if (snapshot.username === username) emit({ username, profile: snapshot.profile, loading: false, error });
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
  emit({ username: profile.username || snapshot.username, profile, loading: false, error: null });
}

export function resetCurrentProfile() {
  inFlight = null;
  emit(EMPTY);
}
