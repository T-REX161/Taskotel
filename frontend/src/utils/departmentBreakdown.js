// The Manager dashboard's per-department figures. The server sends "departmentBreakdown" only to a Manager: one entry per department they
// manage (the primary one first), and the totals of the dashboard are the sum of those entries. For a Manager with a single department
// the breakdown adds nothing, so the dashboard shows it only when there are several.

const num = (value) => (Number.isFinite(Number(value)) ? Number(value) : 0);

/** The breakdown entries to show: a list of at least two departments, or [] (the dashboard stays as it always was). */
export function breakdownRows(data) {
  const list = Array.isArray(data?.departmentBreakdown) ? data.departmentBreakdown : [];
  const rows = list
    .filter((entry) => entry && typeof entry.departmentCode === 'string' && entry.departmentCode)
    .map((entry) => ({
      code: entry.departmentCode,
      name: entry.departmentName || entry.departmentCode,
      primary: entry.primary === true,
      totalStaff: num(entry.totalStaff),
      availableStaff: num(entry.availableStaff),
      unavailableStaff: num(entry.unavailableStaff),
      openTasks: num(entry.pendingTasks) + num(entry.assignedTasks) + num(entry.inProgressTasks),
      completedTasks: num(entry.completedTasks),
      activeAssignments: num(entry.totalActiveAssignments),
      highWorkloadStaff: num(entry.highWorkloadStaff),
      atRiskStaff: num(entry.atRiskStaff),
    }));
  return rows.length > 1 ? rows : [];
}

/** "Covering Front Desk and Housekeeping" for the header: the departments the Manager manages, or '' when there is only one. */
export function coveringLine(data) {
  const names = breakdownRows(data).map((row) => row.name);
  if (names.length === 0) return '';
  const joined = names.length === 1 ? names[0] : `${names.slice(0, -1).join(', ')} and ${names[names.length - 1]}`;
  return `Covering ${joined}`;
}
