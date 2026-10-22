import { formatDepartment } from '../components/tasks/taskFormatters.js';

/**
 * Checks if a given date or timestamp represents "today" compared against referenceDate.
 *
 * @param {string|Date|null|undefined} dateInput
 * @param {Date} [referenceDate=new Date()]
 * @returns {boolean}
 */
export function isToday(dateInput, referenceDate = new Date()) {
  if (!dateInput) return false;
  if (typeof dateInput === 'string') {
    const trimmed = dateInput.trim();
    if (trimmed.toLowerCase().startsWith('today')) {
      return true;
    }
  }

  const d = new Date(dateInput);
  if (isNaN(d.getTime())) return false;

  return (
    d.getFullYear() === referenceDate.getFullYear() &&
    d.getMonth() === referenceDate.getMonth() &&
    d.getDate() === referenceDate.getDate()
  );
}

/**
 * Format assignedAt timestamp into a user-friendly string.
 * Examples:
 *   - Same day: "Today, 8:30 AM"
 *   - Different day: "Oct 14, 2026, 8:30 AM"
 *   - Already formatted: "Today, 08:30 AM"
 *   - Null / empty: "-"
 *
 * @param {string|Date|null|undefined} assignedAt
 * @param {Date} [referenceDate=new Date()]
 * @returns {string}
 */
export function formatAssignedAt(assignedAt, referenceDate = new Date()) {
  if (!assignedAt) return '-';
  if (typeof assignedAt === 'string') {
    const trimmed = assignedAt.trim();
    if (!trimmed) return '-';
    // If it's already a formatted string like 'Today, 08:30 AM'
    if (/^today,\s*\d{1,2}:\d{2}\s*(am|pm)$/i.test(trimmed)) {
      return trimmed;
    }
  }

  const d = new Date(assignedAt);
  if (isNaN(d.getTime())) {
    return String(assignedAt);
  }

  const hours = d.getHours();
  const minutes = d.getMinutes();
  const period = hours >= 12 ? 'PM' : 'AM';
  const h12 = hours % 12 === 0 ? 12 : hours % 12;
  const padMin = minutes < 10 ? `0${minutes}` : `${minutes}`;
  const timeStr = `${h12}:${padMin} ${period}`;

  if (isToday(d, referenceDate)) {
    return `Today, ${timeStr}`;
  }

  const months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
  const dateStr = `${months[d.getMonth()]} ${d.getDate()}, ${d.getFullYear()}`;
  return `${dateStr}, ${timeStr}`;
}

/**
 * Splits a formatAssignedAt() result into its date and time parts for two-line display,
 * without reformatting either part.
 * Examples:
 *   - "Oct 16, 2026, 2:29 PM" -> { date: "Oct 16, 2026", time: "2:29 PM" }
 *   - "Today, 8:30 AM"        -> { date: "Today", time: "8:30 AM" }
 *   - "-" (or any text without a trailing time) -> { date: "-", time: "" }
 *
 * @param {string} formatted
 * @returns {{date: string, time: string}}
 */
export function splitAssignedAt(formatted) {
  const text = String(formatted ?? '');
  const match = text.match(/^(.*\S),\s*(\d{1,2}:\d{2}\s*[AP]M)$/i);
  return match ? { date: match[1], time: match[2] } : { date: text, time: '' };
}

/**
 * Maps task/assignment status enum or string to a readable label.
 *
 * @param {string|null|undefined} status
 * @returns {string}
 */
export function formatAssignmentStatus(status) {
  if (!status) return '-';
  const normalized = String(status).toUpperCase().replace(/\s+/g, '_');
  switch (normalized) {
    case 'PENDING': return 'Pending';
    case 'ASSIGNED': return 'Assigned';
    case 'IN_PROGRESS': return 'In Progress';
    case 'COMPLETED': return 'Completed';
    case 'CANCELLED': return 'Cancelled';
    case 'ACTIVE': return 'Active';
    default: return String(status);
  }
}

/**
 * Returns Tailwind badge styling classes for an assignment/task status.
 *
 * @param {string|null|undefined} status
 * @returns {string}
 */
export function getAssignmentStatusBadge(status) {
  if (!status) return 'bg-gray-100 text-gray-800';
  const normalized = String(status).toUpperCase().replace(/\s+/g, '_');
  switch (normalized) {
    case 'ACTIVE':
    case 'ASSIGNED':
      return 'bg-blue-100 text-blue-800';
    case 'IN_PROGRESS':
      return 'bg-blue-100 text-blue-800';
    case 'COMPLETED':
      return 'bg-emerald-100 text-emerald-800';
    case 'PENDING':
      return 'bg-amber-100 text-amber-800';
    case 'CANCELLED':
      return 'bg-gray-100 text-gray-700';
    default:
      return 'bg-gray-100 text-gray-800';
  }
}

/**
 * Resolves the department for an assignment.
 * Since AssignmentDto in the backend does not expose department directly,
 * this function resolves it by:
 *   1. Checking assignment.department if already populated.
 *   2. Looking up the task in tasks array by taskId (TaskDto has department).
 *   3. Looking up the staff in workload array by staffId (WorkloadDto has department).
 *   4. Falling back to '-'.
 *
 * @param {object} assignment
 * @param {Array} [tasks=[]]
 * @param {Array} [workload=[]]
 * @returns {string}
 */
export function resolveAssignmentDepartment(assignment, tasks = [], workload = []) {
  if (!assignment) return '-';

  // 1. Direct assignment department if present
  if (assignment.department) {
    return formatDepartment(assignment.department);
  }

  // 2. Look up department from associated task
  if (assignment.taskId && Array.isArray(tasks)) {
    const task = tasks.find((t) => t && String(t.id) === String(assignment.taskId));
    if (task && task.department) {
      return formatDepartment(task.department);
    }
  }

  // 3. Look up department from associated staff member's workload
  if (assignment.staffId && Array.isArray(workload)) {
    const staff = workload.find((w) => w && String(w.staffId) === String(assignment.staffId));
    if (staff && staff.department) {
      return formatDepartment(staff.department);
    }
  }

  return '-';
}

/**
 * Calculates dynamic summary metrics for assignments:
 *   - Total Assignments: total count of assignment records
 *   - Active Assignments: assignments whose task status is PENDING, ASSIGNED, IN_PROGRESS, or ACTIVE
 *   - Completed Today: assignments whose task status is COMPLETED and completed on the reference date
 *   - Unassigned Tasks: tasks that have no assignment record and are not in terminal status (COMPLETED or CANCELLED)
 *
 * @param {object} params
 * @param {Array} [params.assignments=[]]
 * @param {Array} [params.tasks=[]]
 * @param {Date} [params.referenceDate=new Date()]
 * @returns {{ totalAssignments: number, activeAssignments: number, completedToday: number, unassignedTasks: number }}
 */
export function calculateAssignmentSummary({
  assignments = [],
  tasks = [],
  referenceDate = new Date(),
} = {}) {
  const safeAssignments = Array.isArray(assignments) ? assignments : [];
  const safeTasks = Array.isArray(tasks) ? tasks : [];

  // Build task map by ID for fast lookup
  const taskMap = new Map();
  safeTasks.forEach((t) => {
    if (t && t.id) {
      taskMap.set(String(t.id), t);
    }
  });

  // Track assigned task IDs
  const assignedTaskIds = new Set();
  let activeAssignments = 0;
  let completedToday = 0;

  safeAssignments.forEach((assignment) => {
    if (!assignment) return;

    if (assignment.taskId) {
      assignedTaskIds.add(String(assignment.taskId));
    }

    // Determine status
    const rawStatus = assignment.taskStatus || assignment.status;
    const associatedTask = assignment.taskId ? taskMap.get(String(assignment.taskId)) : null;
    const status = rawStatus || associatedTask?.status || '';
    const normalizedStatus = String(status).toUpperCase().replace(/\s+/g, '_');

    // Active status rule: PENDING, ASSIGNED, IN_PROGRESS, ACTIVE
    if (
      normalizedStatus === 'ASSIGNED' ||
      normalizedStatus === 'IN_PROGRESS' ||
      normalizedStatus === 'ACTIVE' ||
      normalizedStatus === 'PENDING'
    ) {
      activeAssignments += 1;
    }

    // Completed Today rule: status is COMPLETED and timestamp is today
    if (normalizedStatus === 'COMPLETED') {
      const dateToCheck = associatedTask?.updatedAt || assignment.assignedAt;
      if (isToday(dateToCheck, referenceDate)) {
        completedToday += 1;
      }
    }
  });

  // Unassigned tasks rule: tasks not in assignedTaskIds and not COMPLETED or CANCELLED
  let unassignedTasks = 0;
  safeTasks.forEach((task) => {
    if (!task) return;
    const taskId = String(task.id);
    if (!assignedTaskIds.has(taskId)) {
      const taskStatus = String(task.status || '').toUpperCase();
      if (taskStatus !== 'COMPLETED' && taskStatus !== 'CANCELLED') {
        unassignedTasks += 1;
      }
    }
  });

  return {
    totalAssignments: safeAssignments.length,
    activeAssignments,
    completedToday,
    unassignedTasks,
  };
}

/**
 * Filters the list of tasks to only those that are unassigned and eligible for assignment.
 * A task is unassigned and eligible if:
 *   - It has no active assignment in assignments
 *   - Its status is not COMPLETED, CANCELLED, ASSIGNED, or IN_PROGRESS (e.g. PENDING or unassigned)
 *
 * @param {Object} [params]
 * @param {Array<Object>} [params.tasks=[]]
 * @param {Array<Object>} [params.assignments=[]]
 * @returns {Array<Object>}
 */
export function getEligibleUnassignedTasks({ tasks = [], assignments = [] } = {}) {
  const safeTasks = Array.isArray(tasks) ? tasks : [];
  const safeAssignments = Array.isArray(assignments) ? assignments : [];

  const assignedTaskIds = new Set();
  safeAssignments.forEach((assignment) => {
    if (assignment && assignment.taskId) {
      assignedTaskIds.add(String(assignment.taskId));
    }
  });

  return safeTasks.filter((task) => {
    if (!task || !task.id) return false;
    const taskId = String(task.id);
    if (assignedTaskIds.has(taskId)) return false;

    const status = String(task.status || '').toUpperCase();
    if (
      status === 'COMPLETED' ||
      status === 'CANCELLED' ||
      status === 'ASSIGNED' ||
      status === 'IN_PROGRESS'
    ) {
      return false;
    }

    return true;
  });
}

/**
 * Client-side pagination state for a list of `totalItems`, with `currentPage` clamped into
 * [1, totalPages]. An empty list still has one page, so no invalid page numbers are produced.
 * `startIndex`/`endIndex` are slice bounds; `rangeStart`/`rangeEnd` are the 1-based numbers
 * shown in "Showing rangeStart–rangeEnd of totalItems" (both 0 for an empty list).
 *
 * @param {Object} params
 * @param {number} params.totalItems
 * @param {number} params.currentPage
 * @param {number} params.pageSize
 * @returns {{page: number, totalPages: number, startIndex: number, endIndex: number,
 *   rangeStart: number, rangeEnd: number, hasPrevious: boolean, hasNext: boolean}}
 */
export function getPaginationState({ totalItems, currentPage, pageSize }) {
  const total = Math.max(0, Math.floor(Number(totalItems) || 0));
  const totalPages = Math.max(1, Math.ceil(total / pageSize));
  const page = Math.min(Math.max(1, Math.floor(Number(currentPage) || 1)), totalPages);
  const startIndex = (page - 1) * pageSize;
  const endIndex = Math.min(startIndex + pageSize, total);

  return {
    page,
    totalPages,
    startIndex,
    endIndex,
    rangeStart: total === 0 ? 0 : startIndex + 1,
    rangeEnd: endIndex,
    hasPrevious: page > 1,
    hasNext: page < totalPages,
  };
}

/**
 * Page numbers to show as buttons: every page when there are at most 7, otherwise the first,
 * the last and up to two pages either side of the current one (gaps are rendered as "…").
 *
 * @param {number} totalPages
 * @param {number} currentPage
 * @returns {Array<number>}
 */
export function getPageNumbers(totalPages, currentPage) {
  if (totalPages <= 7) {
    return Array.from({ length: totalPages }, (_, i) => i + 1);
  }
  const pages = new Set([1, totalPages]);
  for (let i = Math.max(2, currentPage - 2); i <= Math.min(totalPages - 1, currentPage + 2); i++) {
    pages.add(i);
  }
  return Array.from(pages).sort((a, b) => a - b);
}

/**
 * Computes the fixed-position placement of a row actions dropdown, right-aligned to its
 * trigger button. Opens upward when there is not enough room below and more room above,
 * and keeps the menu at least `margin` pixels inside the left and right viewport edges.
 *
 * @param {Object} params
 * @param {{top: number, bottom: number, right: number}} params.rect - Trigger button bounding rect
 * @param {number} params.viewportWidth
 * @param {number} params.viewportHeight
 * @param {number} params.menuWidth
 * @param {number} params.menuHeight
 * @param {number} [params.gap=6] - Space between the button and the menu
 * @param {number} [params.margin=8] - Minimum distance from the viewport edges
 * @returns {{openUpward: boolean, top: number, bottom: number, right: number}}
 */
export function getActionsMenuPosition({
  rect,
  viewportWidth,
  viewportHeight,
  menuWidth,
  menuHeight,
  gap = 6,
  margin = 8,
}) {
  const spaceBelow = viewportHeight - rect.bottom;
  const spaceAbove = rect.top;
  const openUpward = spaceBelow < menuHeight + gap + margin && spaceAbove > spaceBelow;

  const maxRight = Math.max(margin, viewportWidth - menuWidth - margin);
  const right = Math.min(Math.max(margin, viewportWidth - rect.right), maxRight);

  return {
    openUpward,
    top: rect.bottom + gap,
    bottom: viewportHeight - rect.top + gap,
    right,
  };
}

/**
 * Returns the index of the menu item to focus for an arrow key, wrapping at both ends.
 * With no item focused (currentIndex -1), ArrowDown focuses the first item and ArrowUp the last.
 * Returns -1 for other keys or an empty menu.
 *
 * @param {number} currentIndex
 * @param {number} itemCount
 * @param {string} key
 * @returns {number}
 */
export function getNextMenuItemIndex(currentIndex, itemCount, key) {
  if (itemCount <= 0) return -1;
  if (key === 'ArrowDown') return currentIndex < 0 ? 0 : (currentIndex + 1) % itemCount;
  if (key === 'ArrowUp') return currentIndex <= 0 ? itemCount - 1 : currentIndex - 1;
  return -1;
}

