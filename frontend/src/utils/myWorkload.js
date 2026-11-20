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
    homeDepartment: record.department || null,
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

/**
 * How many of the person's own tasks are Pending, Assigned, In Progress and Completed, counted from the task list the server returned for them.
 * Each task is counted once (by id); Cancelled and unknown statuses are in none of the four, so `total` is just the sum of the four.
 */
export function taskBreakdown(tasks) {
  const result = { pending: 0, assigned: 0, inProgress: 0, completed: 0, total: 0 };
  const seen = new Set();
  for (const task of Array.isArray(tasks) ? tasks : []) {
    if (!task || (task.id !== undefined && task.id !== null && seen.has(task.id))) continue;
    if (task.id !== undefined && task.id !== null) seen.add(task.id);
    const key = { PENDING: 'pending', ASSIGNED: 'assigned', IN_PROGRESS: 'inProgress', COMPLETED: 'completed' }[task.status];
    if (key) {
      result[key] += 1;
      result.total += 1;
    }
  }
  return result;
}

/** True when a task belongs to another department than the person's own: it is there because of cross-department support. */
export function isSupportTask(task, personal) {
  return Boolean(task && personal && personal.homeDepartment && task.department && task.department !== personal.homeDepartment);
}

/** The person's newest active tasks (most recently created first), at most `limit`, for the dashboard preview. Nothing is added or altered. */
export function latestActiveTasks(tasks, limit = 3) {
  const stamp = (t) => (typeof t?.createdAt === 'string' ? t.createdAt : '');
  return (Array.isArray(tasks) ? tasks.filter((t) => t && ACTIVE_TASK_STATUSES.includes(t.status)) : [])
    .slice()
    .sort((a, b) => (stamp(a) < stamp(b) ? 1 : stamp(a) > stamp(b) ? -1 : String(a.title || '').localeCompare(String(b.title || ''))))
    .slice(0, Math.max(0, limit));
}
