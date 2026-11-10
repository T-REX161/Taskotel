/**
 * What the browser knows about the current sign-in, and whether it is worth showing the application for.
 *
 * The session is the JWT the backend issued at login, kept with the user name and role in localStorage. None of it is proof of
 * anything: anyone can edit localStorage. It only decides what the screen shows and where to send the user; the backend checks the token on
 * every request and answers 401 (expired or invalid: the stored sign-in is cleared and the user is sent to /login) or 403 (not permitted:
 * the user stays signed in). A 403 never ends a session.
 */
import { normalizeRole } from './accessPolicy.js';

/** The expiry (seconds since 1970) in the token's payload, or null when it has none. Throws nothing; returns undefined for a malformed token. */
export function tokenExpiry(token) {
  if (typeof token !== 'string') return undefined;
  const parts = token.split('.');
  if (parts.length !== 3 || parts.some((part) => !part)) return undefined;
  try {
    const base64 = parts[1].replace(/-/g, '+').replace(/_/g, '/');
    const payload = JSON.parse(atob(base64.padEnd(Math.ceil(base64.length / 4) * 4, '=')));
    if (payload === null || typeof payload !== 'object') return undefined;
    return typeof payload.exp === 'number' ? payload.exp : null;
  } catch {
    return undefined;
  }
}

/**
 * @returns {{status: 'anonymous'|'invalid'|'expired'|'active', role: string|null}}
 *   anonymous: nothing stored. invalid: a token or role that cannot be right (malformed token, missing or unknown role).
 *   expired: the token's own expiry has passed. active: present and plausible, not verified (the backend does that).
 */
export function readSession({ token, role, now = Date.now() } = {}) {
  if (!token) return { status: 'anonymous', role: null };
  const expiry = tokenExpiry(token);
  if (expiry === undefined) return { status: 'invalid', role: null };
  if (expiry !== null && expiry * 1000 <= now) return { status: 'expired', role: null };
  const known = normalizeRole(role);
  if (known === null) return { status: 'invalid', role: null };
  return { status: 'active', role: known };
}
