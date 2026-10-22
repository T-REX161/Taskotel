/**
 * Workload calculation and styling utilities.
 * Based on the backend definitions in WorkloadServiceImpl.java and WorkloadDto.java:
 *
 * Thresholds:
 *   LOW     →   0 – 120 minutes
 *   NORMAL  → 121 – 240 minutes
 *   HIGH    → 241 – 360 minutes
 *   AT_RISK → > 360 minutes
 */

/**
 * Derives the WorkloadStatus enum string matching backend WorkloadDto.deriveStatus.
 *
 * @param {number|string|null|undefined} totalMinutes
 * @returns {'LOW' | 'NORMAL' | 'HIGH' | 'AT_RISK'}
 */
export function deriveWorkloadStatus(totalMinutes) {
  const minutes = Math.max(0, parseInt(totalMinutes, 10) || 0);
  if (minutes <= 120) {
    return 'LOW';
  } else if (minutes <= 240) {
    return 'NORMAL';
  } else if (minutes <= 360) {
    return 'HIGH';
  } else {
    return 'AT_RISK';
  }
}

/**
 * Returns Tailwind badge classes for a given WorkloadStatus.
 *
 * @param {string|null|undefined} status
 * @returns {string}
 */
export function getWorkloadStatusBadge(status) {
  const normalized = (status || '').toUpperCase();
  switch (normalized) {
    case 'LOW':
      return 'bg-blue-100 text-blue-800 border border-blue-200';
    case 'NORMAL':
      return 'bg-emerald-100 text-emerald-800 border border-emerald-200';
    case 'HIGH':
      return 'bg-orange-100 text-orange-800 border border-orange-200';
    case 'AT_RISK':
      return 'bg-red-100 text-red-800 border border-red-200';
    default:
      return 'bg-gray-100 text-gray-800 border border-gray-200';
  }
}

/**
 * Returns Tailwind progress bar fill color class for a given WorkloadStatus.
 *
 * @param {string|null|undefined} status
 * @returns {string}
 */
export function getWorkloadProgressBarColor(status) {
  const normalized = (status || '').toUpperCase();
  switch (normalized) {
    case 'LOW':
      return 'bg-blue-500';
    case 'NORMAL':
      return 'bg-emerald-500';
    case 'HIGH':
      return 'bg-orange-500';
    case 'AT_RISK':
      return 'bg-red-500';
    default:
      return 'bg-gray-400';
  }
}

/**
 * Calculates percentage of a standard shift (default 480 min = 8 hours), clamped to 0–100%.
 *
 * @param {number|string|null|undefined} minutes
 * @param {number} [maxMinutes=480]
 * @returns {number}
 */
export function calculateWorkloadPercentage(minutes, maxMinutes = 480) {
  const safeMinutes = Math.max(0, parseInt(minutes, 10) || 0);
  const safeMax = maxMinutes > 0 ? maxMinutes : 480;
  return Math.min(100, Math.round((safeMinutes / safeMax) * 100));
}

/**
 * Calculates current and projected workload for a target staff member and task.
 *
 * Rules:
 *   1. Projected workload = current workload + selected task's estimated duration.
 *   2. For reassignment, calculate the target staff member's projected workload using the task.
 *      Do not add the task duration more than once.
 *   3. If the task is already assigned to the selected staff member, do not treat it as a new assignment
 *      (projected workload equals current workload, duration added is 0).
 *   4. Zero-duration tasks or tasks without duration are handled safely (duration added is 0).
 *   5. Missing workload data is indicated safely without substituting fake values.
 *
 * @param {Object} params
 * @param {string} params.staffId
 * @param {Object} params.task
 * @param {Object} [params.currentAssignment]
 * @param {Array<Object>} params.workloadList
 * @returns {Object|null}
 */
export function calculateProjectedWorkload({
  staffId,
  task,
  currentAssignment = null,
  workloadList = null,
}) {
  if (!staffId || !task) {
    return null;
  }

  if (!Array.isArray(workloadList)) {
    return {
      hasData: false,
      reason: 'MISSING_WORKLOAD_DATA',
    };
  }

  const staffWorkload = workloadList.find(
    (w) => String(w.staffId).toLowerCase() === String(staffId).toLowerCase()
  );

  if (!staffWorkload) {
    return {
      hasData: false,
      reason: 'STAFF_NOT_FOUND',
    };
  }

  const currentMinutes =
    typeof staffWorkload.totalEstimatedDuration === 'number'
      ? staffWorkload.totalEstimatedDuration
      : parseInt(staffWorkload.totalEstimatedDuration, 10) || 0;

  const currentActiveTasks =
    typeof staffWorkload.activeTaskCount === 'number'
      ? staffWorkload.activeTaskCount
      : parseInt(staffWorkload.activeTaskCount, 10) || 0;

  const currentStatus =
    staffWorkload.workloadStatus || deriveWorkloadStatus(currentMinutes);

  // Parse task estimated duration safely (handling 0, strings, null, undefined)
  const taskDuration = Math.max(0, parseInt(task.estimatedDuration, 10) || 0);

  // Check if the task is ALREADY assigned to this specific staff member
  const isAlreadyAssigned = Boolean(
    (currentAssignment &&
      String(currentAssignment.staffId).toLowerCase() === String(staffId).toLowerCase()) ||
    (task.assignedStaffId &&
      String(task.assignedStaffId).toLowerCase() === String(staffId).toLowerCase()) ||
    (task.staffId &&
      String(task.staffId).toLowerCase() === String(staffId).toLowerCase())
  );

  let durationAdded = 0;
  let tasksAdded = 0;
  let isNewAssignment = true;

  if (isAlreadyAssigned) {
    // If already assigned to this staff member, backend workload already includes this task.
    // Do not add task duration or count again.
    durationAdded = 0;
    tasksAdded = 0;
    isNewAssignment = false;
  } else {
    // Initial assignment or reassignment to a different staff member.
    // Target staff member does not have this task yet.
    durationAdded = taskDuration;
    tasksAdded = 1;
    isNewAssignment = true;
  }

  const projectedMinutes = currentMinutes + durationAdded;
  const projectedActiveTasks = currentActiveTasks + tasksAdded;
  const projectedStatus = deriveWorkloadStatus(projectedMinutes);

  return {
    hasData: true,
    staffId: staffWorkload.staffId || staffId,
    staffName: staffWorkload.staffName || '',
    department: staffWorkload.department || staffWorkload.departmentName || '',
    currentMinutes,
    currentActiveTasks,
    currentStatus,
    taskDuration,
    durationAdded,
    tasksAdded,
    projectedMinutes,
    projectedActiveTasks,
    projectedStatus,
    isAlreadyAssigned,
    isNewAssignment,
  };
}

/**
 * Calculates high-level summary metrics across all staff workload records:
 *   - Total Active Tasks: sum of all activeTaskCount
 *   - Total Workload: sum of all totalEstimatedDuration (in minutes)
 *   - Available Staff: count of staff with availability === 'AVAILABLE'
 *   - Unavailable Staff: count of staff with availability !== 'AVAILABLE'
 *
 * @param {Array<Object>} [workloadList=[]]
 * @returns {{ totalActiveTasks: number, totalWorkload: number, availableStaff: number, unavailableStaff: number }}
 */
export function calculateWorkloadSummary(workloadList = []) {
  const safeList = Array.isArray(workloadList) ? workloadList : [];
  let totalActiveTasks = 0;
  let totalWorkload = 0;
  let availableStaff = 0;
  let unavailableStaff = 0;

  safeList.forEach((w) => {
    if (!w) return;
    const tasks =
      typeof w.activeTaskCount === 'number'
        ? w.activeTaskCount
        : parseInt(w.activeTaskCount, 10) || 0;
    const duration =
      typeof w.totalEstimatedDuration === 'number'
        ? w.totalEstimatedDuration
        : parseInt(w.totalEstimatedDuration, 10) || 0;

    totalActiveTasks += Math.max(0, tasks);
    totalWorkload += Math.max(0, duration);

    const avail = String(w.availability || '').toUpperCase();
    if (avail === 'AVAILABLE') {
      availableStaff += 1;
    } else {
      unavailableStaff += 1;
    }
  });

  return {
    totalActiveTasks,
    totalWorkload,
    availableStaff,
    unavailableStaff,
  };
}

/**
 * All departments supported by the backend Department enum.
 */
export const SUPPORTED_DEPARTMENTS = [
  { code: 'HOUSEKEEPING', name: 'Housekeeping' },
  { code: 'FRONT_DESK', name: 'Front Desk' },
  { code: 'MAINTENANCE', name: 'Maintenance' },
  { code: 'ROOM_SERVICE', name: 'Room Service' },
  { code: 'FOOD_AND_BEVERAGE', name: 'Food & Beverage' },
  { code: 'SECURITY', name: 'Security' },
];

/**
 * Aggregates workload by department, ensuring mathematical consistency with staff workload totals.
 *
 * @param {Array<Object>} [workloadList=[]]
 * @param {Array<Object>} [departmentList=[]]
 * @returns {Array<{ code: string, name: string, totalMinutes: number, activeTaskCount: number, staffCount: number, percentage: number, overallMinutes: number }>}
 */
export function calculateDepartmentWorkloads(workloadList = [], departmentList = []) {
  const safeWorkload = Array.isArray(workloadList) ? workloadList : [];
  const safeDeptList = Array.isArray(departmentList) ? departmentList : [];

  const deptMetaMap = new Map();
  safeDeptList.forEach((d) => {
    if (d && d.code) {
      deptMetaMap.set(String(d.code).toUpperCase(), d);
    }
  });

  const deptMap = new Map();
  SUPPORTED_DEPARTMENTS.forEach((dept) => {
    const meta = deptMetaMap.get(dept.code);
    deptMap.set(dept.code, {
      code: dept.code,
      name: meta?.name || dept.name,
      totalMinutes: 0,
      activeTaskCount: 0,
      staffCount: typeof meta?.staffCount === 'number' ? meta.staffCount : 0,
    });
  });

  let overallMinutes = 0;

  safeWorkload.forEach((w) => {
    if (!w) return;
    const rawDept = String(w.department || '').toUpperCase();
    const duration =
      typeof w.totalEstimatedDuration === 'number'
        ? w.totalEstimatedDuration
        : parseInt(w.totalEstimatedDuration, 10) || 0;
    const tasks =
      typeof w.activeTaskCount === 'number'
        ? w.activeTaskCount
        : parseInt(w.activeTaskCount, 10) || 0;

    const safeDuration = Math.max(0, duration);
    const safeTasks = Math.max(0, tasks);
    overallMinutes += safeDuration;

    let entry = deptMap.get(rawDept);
    if (!entry) {
      entry = {
        code: rawDept,
        name: rawDept,
        totalMinutes: 0,
        activeTaskCount: 0,
        staffCount: 0,
      };
      deptMap.set(rawDept, entry);
    }

    entry.totalMinutes += safeDuration;
    entry.activeTaskCount += safeTasks;
    if (safeDeptList.length === 0) {
      entry.staffCount += 1;
    }
  });

  return Array.from(deptMap.values()).map((dept) => {
    const percentage =
      overallMinutes > 0 ? Math.round((dept.totalMinutes / overallMinutes) * 100) : 0;
    return {
      ...dept,
      percentage,
      overallMinutes,
    };
  });
}

/**
 * Compares two workload items alphabetically by their display name (staffName, fullName, or name).
 *
 * Rules:
 *   1. Case-insensitive locale-aware comparison (A–Z ascending).
 *   2. Missing or blank names are placed safely at the end.
 *   3. Deterministic and stable for identical names.
 *
 * @param {Object} a
 * @param {Object} b
 * @returns {number}
 */
export function compareWorkloadByName(a, b) {
  const nameA = (a?.staffName || a?.fullName || a?.name || '').trim();
  const nameB = (b?.staffName || b?.fullName || b?.name || '').trim();

  // Missing/blank names placed safely after named staff
  if (!nameA && !nameB) {
    const idA = String(a?.staffId || a?.id || '');
    const idB = String(b?.staffId || b?.id || '');
    return idA.localeCompare(idB);
  }
  if (!nameA) return 1;
  if (!nameB) return -1;

  // Case-insensitive locale-aware comparison
  const cmp = nameA.localeCompare(nameB, undefined, { sensitivity: 'base' });
  if (cmp !== 0) {
    return cmp;
  }

  // Stable secondary comparison for case or exact string differences
  const caseSensitiveCmp = nameA.localeCompare(nameB);
  if (caseSensitiveCmp !== 0) {
    return caseSensitiveCmp;
  }

  // Deterministic tie-breaker by staff ID if present
  const idA = String(a?.staffId || a?.id || '');
  const idB = String(b?.staffId || b?.id || '');
  return idA.localeCompare(idB);
}

/**
 * Sorts a workload list alphabetically by staff display name in ascending A–Z order.
 *
 * @param {Array<Object>} [workloadList=[]]
 * @returns {Array<Object>}
 */
export function sortWorkloadAlphabetically(workloadList = []) {
  if (!Array.isArray(workloadList)) return [];
  return [...workloadList].sort(compareWorkloadByName);
}
