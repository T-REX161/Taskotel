// Pure helpers for the dashboard. Everything is derived from what the backend returned; nothing is invented.

/** The task statuses the summary reports, in display order. Staff do not receive a cancelled count. */
export const TASK_STATUSES = [
  { key: 'pendingTasks', label: 'Pending', stroke: 'stroke-amber-500', dot: 'bg-amber-500' },
  { key: 'assignedTasks', label: 'Assigned', stroke: 'stroke-sky-400', dot: 'bg-sky-400' },
  { key: 'inProgressTasks', label: 'In Progress', stroke: 'stroke-indigo-500', dot: 'bg-indigo-500' },
  { key: 'completedTasks', label: 'Completed', stroke: 'stroke-emerald-500', dot: 'bg-emerald-500' },
  { key: 'cancelledTasks', label: 'Cancelled', stroke: 'stroke-rose-500', dot: 'bg-rose-500' },
];

/** Status rows with counts and share of the total; `total` is the sum of the shown statuses. */
export function taskStatusSegments(data, isStaff) {
  const shown = TASK_STATUSES.filter((s) => !(isStaff && s.key === 'cancelledTasks'));
  const rows = shown.map((s) => ({ ...s, count: Number(data?.[s.key]) || 0 }));
  const total = rows.reduce((sum, r) => sum + r.count, 0);
  return { total, rows: rows.map((r) => ({ ...r, percent: total ? Math.round((r.count / total) * 100) : 0 })) };
}

/**
 * Donut arc geometry for stroke-dasharray: each non-empty segment gets a length and an offset on a circle of the given radius.
 * `spacing` leaves a small visual gap between neighbouring segments (not applied to a lone full ring). Each segment keeps its
 * own share of the circle as its slot and the gap is cut out of the slot's middle, so the proportions stay true to the counts.
 */
export function donutArcs(rows, radius, spacing = 0) {
  const circumference = 2 * Math.PI * radius;
  const total = rows.reduce((sum, r) => sum + r.count, 0);
  const visible = rows.filter((r) => r.count > 0);
  const cut = visible.length > 1 ? spacing : 0;
  let used = 0;
  return visible.map((r) => {
    const slot = (r.count / total) * circumference;
    const length = Math.max(slot - cut, 0.5);
    const arc = { key: r.key, stroke: r.stroke, length, gap: circumference - length, offset: -(used + cut / 2) };
    used += slot;
    return arc;
  });
}

/** Natural ease-out for the entrance: fast start, soft landing. */
export const easeOutCubic = (x) => 1 - Math.pow(1 - Math.min(1, Math.max(0, x)), 3);

/**
 * The donut arcs revealed up to `progress` (0..1): the ring is drawn clockwise from the top, so every segment keeps its true
 * start, length and gap and only the visible part grows. At progress 1 the result equals the input exactly.
 */
export function revealArcs(arcs, progress, radius) {
  if (progress >= 1) return arcs;
  const circumference = 2 * Math.PI * radius;
  const reach = Math.max(0, progress) * circumference;
  return arcs.map((arc) => {
    const drawn = Math.max(0, Math.min(arc.length, reach + arc.offset));
    return { ...arc, length: drawn, gap: circumference - drawn };
  });
}

/** Legend row fade for the entrance: row `index` starts a little after the chart does, staggered, and is fully in by the end. */
export const legendFade = (progress, index) => Math.min(1, Math.max(0, (progress - 0.2 - index * 0.07) / 0.35));

/**
 * The backend's workload bands (minutes): LOW 0-120, NORMAL 121-240, HIGH 241-360, AT_RISK above.
 * `row` is the restrained treatment for people on HIGH or AT_RISK; the other bands get none.
 */
export const WORKLOAD_TONES = {
  LOW: { label: 'LOW', bar: 'bg-emerald-500', badge: 'bg-emerald-50 text-emerald-700 ring-emerald-200', row: 'border-l-transparent' },
  NORMAL: { label: 'NORMAL', bar: 'bg-blue-500', badge: 'bg-blue-50 text-blue-700 ring-blue-200', row: 'border-l-transparent' },
  HIGH: { label: 'HIGH', bar: 'bg-amber-500', badge: 'bg-amber-50 text-amber-700 ring-amber-200', row: 'border-l-amber-400 bg-amber-50/40' },
  AT_RISK: { label: 'AT RISK', bar: 'bg-red-500', badge: 'bg-red-50 text-red-700 ring-red-200', row: 'border-l-red-500 bg-red-50/40' },
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

/** Workload as a share of the 360-minute At-risk threshold, rounded for display ("75%"). */
export const loadPercent = (minutes) => Math.round(durationPercent(minutes));

/** "Mon, 29 Sep 2026" */
const WEEKDAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
export const formatDashboardDate = (date) => `${WEEKDAYS[date.getDay()]}, ${date.getDate()} ${MONTHS[date.getMonth()]} ${date.getFullYear()}`;

/** People on HIGH or AT_RISK workload, the most loaded first. */
export const overloadedStaff = (list) => sortByLoad(list).filter((row) => row.workloadStatus === 'HIGH' || row.workloadStatus === 'AT_RISK');

const SEVERITY_RANK = { CRITICAL: 3, HIGH: 2, MODERATE: 1 };

/**
 * Departments the existing recommendation engine says may need cross-department support, most severe first.
 * `canBeHelped` is false for departments where no other department can safely lend anyone.
 */
export function supportHighlights(recommendations) {
  const toRow = (d, canBeHelped) => ({
    code: d.departmentCode,
    name: d.departmentName || d.departmentCode,
    severity: d.severity,
    uncoveredTasks: d.pressure?.uncoveredTasks || 0,
    canBeHelped,
    reason: d.reason || (Array.isArray(d.reasons) ? d.reasons[0] : '') || '',
    helpers: Array.isArray(d.suggestions) ? d.suggestions.length : 0,
  });
  return [...(recommendations?.recommendations || []).map((d) => toRow(d, true)), ...(recommendations?.unsupported || []).map((d) => toRow(d, false))].sort(
    (a, b) => (SEVERITY_RANK[b.severity] || 0) - (SEVERITY_RANK[a.severity] || 0) || b.uncoveredTasks - a.uncoveredTasks || String(a.name).localeCompare(String(b.name))
  );
}

/** The number for the middle of the donut: the sum of the shown statuses, or null when it disagrees with the summary's own total. */
export function donutCenterTotal(data, total, isStaff) {
  if (!isStaff && Number.isFinite(data?.totalTasks) && data.totalTasks !== total) return null;
  return total;
}

// ── Operations Insights ─────────────────────────────────────────────────────────────────────────────────────────
// At most one row per kind of insight, ranked by how serious it is. Everything is derived from data the dashboard
// already loads; nothing here decides what is "overdue" (the only due-date rule in the app belongs to the recommendation engine).

const plural = (n, one, many) => `${n} ${n === 1 ? one : many}`;
/** How many rows an expanded insight lists; the rest is summarised as "+N more". */
export const INSIGHT_DETAIL_LIMIT = 4;
const PRIORITY_RANK = { URGENT: 2, HIGH: 1 };
const SEVERITY_WORD = { CRITICAL: 'Critical', HIGH: 'High', MODERATE: 'Moderate' };

/** Pending tasks that are URGENT or HIGH priority: the only task condition treated as needing attention. */
export function pendingPriorityTasks(tasks) {
  const list = Array.isArray(tasks) ? tasks : [];
  const urgent = list.filter((t) => t.status === 'PENDING' && t.priority === 'URGENT').length;
  const high = list.filter((t) => t.status === 'PENDING' && t.priority === 'HIGH').length;
  return { urgent, high, total: urgent + high };
}

/** The PENDING URGENT / HIGH tasks themselves, urgent first, as plain rows for the expanded view. */
export const tasksNeedingAttention = (tasks) =>
  (Array.isArray(tasks) ? tasks : [])
    .filter((t) => t.status === 'PENDING' && PRIORITY_RANK[t.priority])
    .sort((a, b) => PRIORITY_RANK[b.priority] - PRIORITY_RANK[a.priority] || String(a.title).localeCompare(String(b.title)))
    .map((t) => ({ id: t.id, title: t.title, priority: t.priority, status: t.status, department: t.departmentName || t.department || '' }));

/**
 * The insight rows for the dashboard.
 *   isStaff         – Staff only ever get their own workload, from their own dashboard summary
 *   own             – the Staff summary's own workload record ({ workloadStatus, totalEstimatedDuration })
 *   staffWorkload   – { status, data } from GET /api/workload (Admin / Manager)
 *   tasks           – { status, data } from GET /api/tasks (Admin / Manager: all tasks)
 *   recommendations – { status, data } normalised GET /api/workload/recommendations (Admin / Manager)
 * Returns { state, items, unavailable }: state is 'items' | 'clear' | 'loading' | 'unavailable' | 'hidden'. 'clear' (nothing to
 * report) is only ever returned when every source loaded, so a failed source is never mistaken for "no alerts".
 */
export function buildInsights({ isStaff, own, staffWorkload, tasks, recommendations }) {
  if (isStaff) {
    const status = own?.workloadStatus;
    if (status !== 'HIGH' && status !== 'AT_RISK') return { state: 'hidden', items: [], unavailable: [] };
    return {
      state: 'items',
      unavailable: [],
      items: [{ key: 'own-workload', kind: 'workload', rank: status === 'AT_RISK' ? 100 : 70, tone: status === 'AT_RISK' ? 'red' : 'amber', to: '/workload',
        details: { type: 'workload', total: 1, rows: [{ id: 'own', name: 'You', department: own.departmentName || '', percent: loadPercent(own.totalEstimatedDuration), status, activeTasks: own.activeTaskCount ?? null }], viewAll: null },
        title: status === 'AT_RISK' ? 'Your workload is at risk' : 'Your workload is high', detail: `${plural(own.activeTaskCount ?? 0, 'active task', 'active tasks')} · ${own.totalEstimatedDuration ?? 0} min estimated` }],
    };
  }

  const sources = { workload: staffWorkload, tasks, support: recommendations };
  const items = [];

  if (staffWorkload?.status === 'ready') {
    const strained = overloadedStaff(staffWorkload.data);
    if (strained.length > 0) {
      const atRisk = strained.filter((r) => r.workloadStatus === 'AT_RISK').length;
      const names = strained.slice(0, 2).map((r) => `${r.staffName} (${r.workloadStatus === 'AT_RISK' ? 'at risk' : 'high'})`).join(', ');
      items.push({ key: 'workload', kind: 'workload', rank: atRisk > 0 ? 100 : 70, tone: atRisk > 0 ? 'red' : 'amber', to: '/workload',
        details: { type: 'workload', total: strained.length, rows: strained.slice(0, INSIGHT_DETAIL_LIMIT).map((r) => ({ id: r.staffId, name: r.staffName, department: r.departmentName || r.department || '', percent: loadPercent(r.totalEstimatedDuration), status: r.workloadStatus, activeTasks: Number.isFinite(r.activeTaskCount) ? r.activeTaskCount : null })), viewAll: '/workload' },
        title: `${plural(strained.length, 'person', 'people')} on high workload`, detail: names + (strained.length > 2 ? ` +${strained.length - 2} more` : '') });
    }
  }

  if (recommendations?.status === 'ready') {
    const departments = supportHighlights(recommendations.data);
    if (departments.length > 0) {
      const top = departments[0];
      items.push({ key: 'support', kind: 'support', rank: top.severity === 'CRITICAL' ? 90 : top.severity === 'HIGH' ? 60 : 30, tone: top.severity === 'CRITICAL' ? 'red' : 'amber', to: '/workload',
        details: { type: 'support', total: departments.length, rows: departments.slice(0, INSIGHT_DETAIL_LIMIT), viewAll: '/workload' },
        title: `${plural(departments.length, 'department', 'departments')} may need support`,
        detail: `${top.name} · ${SEVERITY_WORD[top.severity] || 'Moderate'}${top.uncoveredTasks > 0 ? `, ${top.uncoveredTasks} uncovered` : ''}${departments.length > 1 ? ` +${departments.length - 1} more` : ''}` });
    }
  }

  if (tasks?.status === 'ready') {
    const { urgent, high, total } = pendingPriorityTasks(tasks.data);
    if (total > 0) {
      items.push({ key: 'tasks', kind: 'tasks', rank: urgent > 0 ? 80 : 50, tone: urgent > 0 ? 'red' : 'amber', to: '/tasks',
        details: { type: 'tasks', total, rows: tasksNeedingAttention(tasks.data).slice(0, INSIGHT_DETAIL_LIMIT), viewAll: '/tasks' },
        title: `${plural(total, 'high-priority task', 'high-priority tasks')} pending`,
        detail: [urgent > 0 ? `${urgent} urgent` : '', high > 0 ? `${high} high` : ''].filter(Boolean).join(' · ') });
    }
  }

  items.sort((a, b) => b.rank - a.rank);
  const unavailable = Object.entries(sources).filter(([, s]) => s?.status === 'error').map(([name]) => name);
  const loading = Object.values(sources).some((s) => !s || s.status === 'loading');
  if (items.length > 0) return { state: 'items', items: items.slice(0, 3), unavailable };
  if (loading) return { state: 'loading', items: [], unavailable };
  return { state: unavailable.length > 0 ? 'unavailable' : 'clear', items: [], unavailable };
}
