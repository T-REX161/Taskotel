// The departments a signed-in Manager may use in forms and filters: only those they manage (their primary department and any an
// Administrator granted), the primary one first. The server decides what is allowed (and answers a Manager's /api/departments with a
// "managed" flag per department); this only stops the screens from offering what the server would refuse.

/**
 * @param {Array} departments the /api/departments answer
 * @param {string} [primaryCode] the Manager's own (primary) department code, from their profile
 * @returns {Array} the managed departments, the primary one first, the rest keeping the order they came in
 */
export function managedDepartmentList(departments, primaryCode) {
  const managed = (Array.isArray(departments) ? departments : []).filter((department) => department && department.managed === true);
  const primary = String(primaryCode || '').trim().toUpperCase();
  if (!primary) return managed;
  const first = managed.filter((department) => String(department.code || '').toUpperCase() === primary);
  const rest = managed.filter((department) => String(department.code || '').toUpperCase() !== primary);
  return [...first, ...rest];
}

/**
 * The departments a screen may show for this role. The server already answers a Manager with their managed departments only; this keeps
 * the Departments page honest even against an older or odd answer: for a Manager anything not flagged managed is dropped entirely (no
 * card, no figures, no part of any total). Admin and Staff lists are returned untouched.
 */
export function authorizedDepartments(role, departments) {
  const list = Array.isArray(departments) ? departments : [];
  return role === 'MANAGER' ? list.filter((department) => department && department.managed === true) : list;
}
