/**
 * Route guards (Stage 2). Written without JSX so the decisions and the rendered result can be tested in plain Node.
 *
 *   <RequireSession>  wraps the application shell: no usable sign-in → /login (a stale one is cleared first).
 *   <RequireRouteAccess>  inside the shell: a signed-in user whose role is not allowed on the current path (see utils/accessPolicy) sees an
 *                         Access Denied page in place of the screen, with a link to the dashboard (which every role may open: no loop).
 *
 * These only decide what the browser shows. The backend authorises every request and is the security authority.
 */
import { createElement } from 'react';
import { Link, Navigate, Outlet, useLocation } from 'react-router-dom';
import { clearAuth, getToken, getUserRole } from '../../services/api.js';
import { HOME_PATH, ROLE_LABELS, canAccess } from '../../utils/accessPolicy.js';
import { readSession } from '../../utils/session.js';

export const LOGIN_PATH = '/login';

/** The current stored sign-in, read afresh on every render. */
export const currentSession = () => readSession({ token: getToken(), role: getUserRole() });

/**
 * The pure decision: 'allow', 'login' (nothing usable stored), 'end-session' (stored but invalid or expired: clear it, then /login),
 * or 'denied' (signed in, role not permitted on this path).
 */
export function decideAccess({ session, pathname }) {
  if (session.status === 'anonymous') return { kind: 'login' };
  if (session.status !== 'active') return { kind: 'end-session' };
  if (!canAccess(session.role, pathname)) return { kind: 'denied', role: session.role };
  return { kind: 'allow' };
}

export function AccessDenied({ role }) {
  const label = ROLE_LABELS[role] || 'your role';
  return createElement(
    'section',
    { role: 'alert', 'aria-labelledby': 'access-denied-title', className: 'max-w-xl mx-auto mt-16 p-8 bg-white rounded-2xl border border-gray-100 shadow-sm text-center space-y-3' },
    createElement('h1', { id: 'access-denied-title', className: 'text-2xl font-bold text-gray-900' }, 'Access denied'),
    createElement('p', { className: 'text-sm text-gray-600' }, `Your account (${label}) does not have access to this page.`),
    createElement(Link, { to: HOME_PATH, className: 'inline-block mt-2 px-4 py-2 rounded-lg bg-emerald-600 hover:bg-emerald-700 text-white text-sm font-medium' }, 'Go to Dashboard'),
  );
}

/** The element for a decision. Exported so tests can inspect where a redirect goes. */
export function elementFor(decision, location) {
  if (decision.kind === 'login' || decision.kind === 'end-session') {
    return createElement(Navigate, { to: LOGIN_PATH, replace: true, state: { from: location } });
  }
  if (decision.kind === 'denied') return createElement(AccessDenied, { role: decision.role });
  return createElement(Outlet);
}

/** Needs a usable sign-in for everything below it. Role rules are applied inside the shell by RequireRouteAccess. */
export function RequireSession() {
  const location = useLocation();
  const decision = decideAccess({ session: currentSession(), pathname: location.pathname });
  // A stale or malformed stored sign-in is removed so the login page starts clean (clearing is idempotent)
  if (decision.kind === 'end-session') clearAuth();
  return elementFor(decision.kind === 'denied' ? { kind: 'allow' } : decision, location);
}

/**
 * The role rule for the current path, from the single table in utils/accessPolicy (the sidebar reads the same table). A refused user sees
 * the Access Denied page in place of the screen, which is therefore never rendered (and never requests its data).
 */
export function RequireRouteAccess() {
  const location = useLocation();
  const decision = decideAccess({ session: currentSession(), pathname: location.pathname });
  if (decision.kind === 'end-session') clearAuth();
  return elementFor(decision, location);
}
