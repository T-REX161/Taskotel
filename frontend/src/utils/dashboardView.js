// Pure helpers for the dashboard. Everything is derived from what the backend returned; nothing is invented.

/** The task statuses the summary reports, in display order. Staff do not receive a cancelled count. */
export const TASK_STATUSES = [
  { key: 'pendingTasks', label: 'Pending', stroke: 'stroke-yellow-400', dot: 'bg-yellow-400' },
  { key: 'assignedTasks', label: 'Assigned', stroke: 'stroke-blue-500', dot: 'bg-blue-500' },
  { key: 'inProgressTasks', label: 'In Progress', stroke: 'stroke-purple-500', dot: 'bg-purple-500' },
  { key: 'completedTasks', label: 'Completed', stroke: 'stroke-emerald-500', dot: 'bg-emerald-500' },
  { key: 'cancelledTasks', label: 'Cancelled', stroke: 'stroke-gray-400', dot: 'bg-gray-400' },
];

/** Status rows with counts and share of the total; `total` is the sum of the shown statuses. */
export function taskStatusSegments(data, isStaff) {
  const shown = TASK_STATUSES.filter((s) => !(isStaff && s.key === 'cancelledTasks'));
  const rows = shown.map((s) => ({ ...s, count: Number(data?.[s.key]) || 0 }));
  const total = rows.reduce((sum, r) => sum + r.count, 0);
  return { total, rows: rows.map((r) => ({ ...r, percent: total ? Math.round((r.count / total) * 100) : 0 })) };
}

/** Donut arc geometry for stroke-dasharray: each non-empty segment gets a length and an offset on a circle of the given radius. */
export function donutArcs(rows, radius) {
  const circumference = 2 * Math.PI * radius;
  const total = rows.reduce((sum, r) => sum + r.count, 0);
  let used = 0;
  return rows
    .filter((r) => r.count > 0)
    .map((r) => {
      const length = (r.count / total) * circumference;
      const arc = { key: r.key, stroke: r.stroke, length, gap: circumference - length, offset: -used };
      used += length;
      return arc;
    });
}

/** The backend's workload bands (minutes): LOW 0-120, NORMAL 121-240, HIGH 241-360, AT_RISK above. */
export const WORKLOAD_TONES = {
  LOW: { label: 'Low', bar: 'bg-emerald-500', badge: 'bg-emerald-100 text-emerald-800' },
  NORMAL: { label: 'Normal', bar: 'bg-blue-500', badge: 'bg-blue-100 text-blue-800' },
  HIGH: { label: 'High', bar: 'bg-orange-500', badge: 'bg-orange-100 text-orange-800' },
  AT_RISK: { label: 'At risk', bar: 'bg-red-500', badge: 'bg-red-100 text-red-800' },
};

export const workloadTone = (status) => WORKLOAD_TONES[status] || WORKLOAD_TONES.LOW;

/** Bar fill for a duration, full at the At-risk threshold (360 min), the same scale the staff view already used. */
export const durationPercent = (minutes) => Math.max(0, Math.min(100, ((Number(minutes) || 0) / 360) * 100));

/** "2h 30m" style text for a duration in minutes. */
export function formatMinutes(minutes) {
  const total = Math.max(0, Math.round(Number(minutes) || 0));
  const h = Math.floor(total / 60);
  const m = total % 60;
  if (h === 0) return `${m}m`;
  return m === 0 ? `${h}h` : `${h}h ${m}m`;
}

/** Busiest first, then by name, so the people who need attention are at the top. */
export function sortByLoad(list) {
  return [...(Array.isArray(list) ? list : [])].sort(
    (a, b) => (b.totalEstimatedDuration || 0) - (a.totalEstimatedDuration || 0) || String(a.staffName).localeCompare(String(b.staffName))
  );
}

export const greetingFor = (hour) => (hour < 12 ? 'Good morning' : hour < 18 ? 'Good afternoon' : 'Good evening');
