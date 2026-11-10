/**
 * Which roles may open which screens, and which links each role sees (Stage 2: frontend role-aware navigation).
 *
 * This is a PRESENTATION policy. The backend is the security authority and answers 401/403 for every request whatever this file says;
 * the stored role is only used here to decide what to show and which screens to open. Nothing in this file grants access to data.
 * The rules mirror the backend route rules (SecurityConfig) and the RBAC specification.
 */

export const ROLES = Object.freeze({ ADMIN: 'ADMIN', MANAGER: 'MANAGER', STAFF: 'STAFF' });

const ALL_ROLES = Object.freeze([ROLES.ADMIN, ROLES.MANAGER, ROLES.STAFF]);
const MANAGEMENT_ROLES = Object.freeze([ROLES.ADMIN, ROLES.MANAGER]);
const ADMIN_ONLY = Object.freeze([ROLES.ADMIN]);

/** The three known roles are the only accepted values (exact, upper-case). Anything else, including a missing role, is null: never a default. */
export function normalizeRole(role) {
  return typeof role === 'string' && Object.prototype.hasOwnProperty.call(ROLES, role) ? ROLES[role] : null;
}

export const isManagement = (role) => MANAGEMENT_ROLES.includes(normalizeRole(role));

/** The screen every signed-in role may open, and where a refused user is sent. */
export const HOME_PATH = '/dashboard';

/**
 * One rule per screen; a path also covers everything below it (`/tasks` covers `/tasks/create` unless a longer rule says otherwise).
 * A path with no rule is the catch-all screen (the dashboard), open to every signed-in role.
 */
export const ROUTE_ACCESS = Object.freeze([
  { path: '/', exact: true, roles: ALL_ROLES },
  { path: '/dashboard', roles: ALL_ROLES },
  { path: '/staff', roles: MANAGEMENT_ROLES },
  { path: '/tasks', roles: ALL_ROLES },
  { path: '/tasks/create', roles: MANAGEMENT_ROLES },
  { path: '/assignments', roles: MANAGEMENT_ROLES },
  { path: '/workload', roles: ALL_ROLES },
  { path: '/departments', roles: MANAGEMENT_ROLES },
  { path: '/users', roles: ADMIN_ONLY },
  { path: '/profile', roles: ALL_ROLES },
]);

export const ACCESS = Object.freeze({ all: ALL_ROLES, management: MANAGEMENT_ROLES, adminOnly: ADMIN_ONLY });

function cleanPath(pathname) {
  const path = String(pathname || '/').toLowerCase().split(/[?#]/)[0];
  return path.length > 1 ? path.replace(/\/+$/, '') : path;
}

/** The roles allowed on a path: the longest matching rule, or every role for the catch-all. */
export function rolesForPath(pathname) {
  const path = cleanPath(pathname);
  let best = null;
  for (const rule of ROUTE_ACCESS) {
    const matches = rule.exact ? path === rule.path : path === rule.path || path.startsWith(`${rule.path}/`);
    if (matches && (!best || rule.path.length > best.path.length)) best = rule;
  }
  return best ? best.roles : ALL_ROLES;
}

/** True only for a known role that the rules allow on the path. An unknown or missing role can open nothing. */
export function canAccess(role, pathname) {
  const known = normalizeRole(role);
  return known !== null && rolesForPath(pathname).includes(known);
}

/**
 * The sidebar for a role: groups of links. `name` identifies the screen (and the active highlight); `label` is what is shown.
 * Staff see the same Tasks and Workload screens as everyone, under their own names; the backend returns only their own records.
 */
export function navigationFor(role) {
  const known = normalizeRole(role);
  if (known === null) return [];
  const link = (name, path, label = name) => ({ name, label, path });
  const system = { title: 'SYSTEM', items: [link('Profile', '/profile')] };

  if (known === ROLES.STAFF) {
    return [
      { title: 'GENERAL', items: [link('Dashboard', '/dashboard'), link('Tasks', '/tasks', 'My Tasks'), link('Workload', '/workload', 'My Workload')] },
      system,
    ];
  }
  return [
    {
      title: 'GENERAL',
      items: [link('Dashboard', '/dashboard'), link('Staff', '/staff'), link('Tasks', '/tasks'), link('Assignments', '/assignments'), link('Workload', '/workload')],
    },
    {
      title: 'MANAGEMENT',
      items: [link('Departments', '/departments'), ...(known === ROLES.ADMIN ? [link('Users', '/users')] : [])],
    },
    system,
  ];
}

/** Whether the role may request the staff list (Admin and Manager only; the backend answers Staff with 403). */
export const canListStaff = (role) => isManagement(role);

export const ROLE_LABELS = Object.freeze({ ADMIN: 'Admin', MANAGER: 'Manager', STAFF: 'Staff' });
