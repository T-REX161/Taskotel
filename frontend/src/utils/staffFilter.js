/**
 * Filter staff members eligible to receive a task:
 * 1. Must match the specified department.
 * 2. Must be active (active !== false and status !== 'INACTIVE').
 * 3. Must have AVAILABLE availability (checks actual API response field `availability`, with fallback to `availabilityStatus`).
 * 4. Must NOT have any active task assignment (an assignment on a task with status not COMPLETED and not CANCELLED),
 *    excluding the current task/assignment when reassigning.
 *
 * @param {object} params
 * @param {Array} [params.allStaff=[]] - Array of staff objects from GET /api/staff
 * @param {Array} [params.tasks=[]] - Array of task objects from GET /api/tasks
 * @param {Array} [params.assignments=[]] - Array of assignment objects from GET /api/assignments
 * @param {string} [params.department=''] - Target department enum
 * @param {string|null} [params.excludeAssignmentId=null] - Assignment ID to exclude from blocking (for reassignments)
 * @param {string|null} [params.excludeTaskId=null] - Task ID to exclude from blocking (for reassignments)
 * @returns {Array} Filtered list of eligible staff objects
 */
export function filterEligibleStaff({
  allStaff = [],
  tasks = [],
  assignments = [],
  department = '',
  excludeAssignmentId = null,
  excludeTaskId = null,
} = {}) {
  if (!department) return [];

  const activeTaskIds = new Set(
    (Array.isArray(tasks) ? tasks : [])
      .filter((t) => t && t.status !== 'COMPLETED' && t.status !== 'CANCELLED')
      .map((t) => String(t.id))
  );

  const staffWithActiveAssignments = new Set();
  (Array.isArray(assignments) ? assignments : []).forEach((a) => {
    if (!a || !a.staffId) return;

    // When reassigning a task, do not treat the assignment being reassigned as an active blocker
    if (excludeAssignmentId && (String(a.id) === String(excludeAssignmentId) || String(a.assignmentId) === String(excludeAssignmentId))) {
      return;
    }
    if (excludeTaskId && String(a.taskId) === String(excludeTaskId)) {
      return;
    }

    if (a.taskStatus) {
      if (a.taskStatus !== 'COMPLETED' && a.taskStatus !== 'CANCELLED') {
        staffWithActiveAssignments.add(String(a.staffId));
      }
    } else if (a.taskId && activeTaskIds.has(String(a.taskId))) {
      staffWithActiveAssignments.add(String(a.staffId));
    }
  });

  return (Array.isArray(allStaff) ? allStaff : []).filter((s) => {
    if (!s) return false;

    // 1. Department match
    const matchesDept = s.department === department;

    // 2. Active status: must be active
    const isActive = s.active !== false && s.status !== 'INACTIVE';

    // 3. Availability: checks actual API response field `availability` (supports `availabilityStatus` fallback)
    const rawAvailability = s.availability !== undefined ? s.availability : s.availabilityStatus;
    const isAvailable = rawAvailability === 'AVAILABLE' || rawAvailability === 'Available';

    // 4. Must not have an active task assignment on another task
    const hasActiveAssignment = staffWithActiveAssignments.has(String(s.id));

    return matchesDept && isActive && isAvailable && !hasActiveAssignment;
  });
}
