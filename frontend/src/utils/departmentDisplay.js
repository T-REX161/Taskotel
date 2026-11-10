// Display helpers for the department figures. The server returns a department's staff and task counts only to an Admin and, for the
// Manager's own department, to that Manager (Step 2I). For everything else the figures are null, which must read as "not available",
// never as 0: a zero would be a wrong number, not a hidden one.

export const FIGURE_UNAVAILABLE = '—';

/** A count for display: the number, or a dash when the server did not send one. */
export function departmentCount(value) {
  if (value === null || value === undefined || value === '') return FIGURE_UNAVAILABLE;
  const number = Number(value);
  return Number.isFinite(number) ? number : FIGURE_UNAVAILABLE;
}

/**
 * The total of one figure across the departments, or null when any department has no figure (a total that silently leaves some
 * departments out would be wrong, so it is shown as unavailable instead).
 */
export function totalOfKnownCounts(departments, field) {
  const list = Array.isArray(departments) ? departments : [];
  let total = 0;
  for (const department of list) {
    const value = department?.[field];
    if (value === null || value === undefined || !Number.isFinite(Number(value))) return null;
    total += Number(value);
  }
  return total;
}
