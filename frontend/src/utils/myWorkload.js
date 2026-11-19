/**
 * What the Staff "My Workload" page shows about the signed-in person: their own workload record and their own active tasks.
 * Nothing is calculated here: the minutes (support assignments included) and the LOW / NORMAL / HIGH / AT_RISK status come from the server's
 * dashboard summary; only the progress-bar percentage uses the shared 480-minute shift helper. Pure functions, so they run in plain Node tests.
 */
import { SHIFT_MINUTES, calculateWorkloadPercentage } from './workloadUtils.js';

/** The task statuses that count as active workload (Completed and Cancelled do not). */
export const ACTIVE_TASK_STATUSES = Object.freeze(['PENDING', 'ASSIGNED', 'IN_PROGRESS']);

const count = (value) => {
  const n = Number(value);
  return Number.isFinite(n) && n > 0 ? Math.round(n) : 0;
};

/** The signed-in person's own workload record from the dashboard summary, or null when the summary has none. */
export function personalWorkload(summary) {
  const record = summary && typeof summary === 'object' ? summary.workload : null;
  if (!record || typeof record !== 'object') return null;
  const minutes = count(record.totalEstimatedDuration);
  return {
    activeTasks: count(record.activeTaskCount),
    minutes,
    capacity: SHIFT_MINUTES,
    percent: calculateWorkloadPercentage(minutes, SHIFT_MINUTES),
    status: typeof record.workloadStatus === 'string' && record.workloadStatus ? record.workloadStatus.toUpperCase() : null,
    availability: typeof record.availability === 'string' ? record.availability : null,
    // Present only while the person is on temporary cross-department support; those minutes are already inside `minutes`
    supportDepartmentName: record.supportDepartmentName || null,
  };
}

const PRIORITY_RANK = { URGENT: 0, HIGH: 1, MEDIUM: 2, LOW: 3 };
const dueKey = (t) => (typeof t?.dueDate === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(t.dueDate) ? `${t.dueDate}T${String(t.deadlineTime || '23:59:59').slice(0, 8)}` : null);

/** The person's own active tasks, soonest due first (no due date last), then most urgent first. Nothing is added or altered. */
export function activeTasksOf(tasks) {
  const list = Array.isArray(tasks) ? tasks.filter((t) => t && ACTIVE_TASK_STATUSES.includes(t.status)) : [];
  return list.slice().sort((a, b) => {
    const da = dueKey(a);
    const db = dueKey(b);
    if (da !== db) return da === null ? 1 : db === null ? -1 : da < db ? -1 : 1;
    const pa = PRIORITY_RANK[a.priority] ?? 9;
    const pb = PRIORITY_RANK[b.priority] ?? 9;
    return pa !== pb ? pa - pb : String(a.title || '').localeCompare(String(b.title || ''));
  });
}
