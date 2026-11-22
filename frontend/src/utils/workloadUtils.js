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

/*
 * Department risk on the Workload page is CAPACITY based, and decided by the server (DepartmentCapacity on the backend):
 *
 *   utilization = remaining task minutes / (eligible available staff x one standard shift) x 100
 *
 * Remaining minutes are the estimated minutes of the department's active tasks, held or waiting; they do not fall because a helper joins.
 * A person on cross-department support counts for the department they help and not for their home one. A department is AT RISK when
 * its utilization is at or above the saved Capacity Threshold (and the Max Task Capacity Alert is on). Below it, LOW is up to 25%, NORMAL
 * up to 50% and HIGH above that. The page never recomputes any of this: it shows what the server says, so the cards, the legend, the
 * dashboard and Cross-Department Support cannot disagree. (Individual staff statuses are a different, fixed measure: see
 * getWorkloadStatusBadge.)
 */

const DEPARTMENT_LEVELS = ['LOW', 'NORMAL', 'HIGH', 'AT_RISK'];

/** Bar fill for a department level (the 600 shades are strong on the light theme, the 500 shades keep contrast on the dark one). */
export function getDepartmentLevelBarColor(level) {
  switch (level) {
    case 'LOW': return 'bg-blue-600 dark:bg-blue-500';
    case 'NORMAL': return 'bg-emerald-600 dark:bg-emerald-500';
    case 'HIGH': return 'bg-orange-600 dark:bg-orange-500';
    case 'AT_RISK': return 'bg-red-600 dark:bg-red-500';
    default: return 'bg-gray-400';
  }
}

/** The label on a department card: its level, or NO STAFF when it has work and nobody eligible to do it. */
export function departmentLevelLabel(entry) {
  if (!entry) return null;
  if (entry.noCapacity) return 'NO STAFF';
  return DEPARTMENT_LEVELS.includes(entry.level) ? entry.level.replace('_', ' ') : null;
}

/** A utilization as shown: "66.7%", "100%", or "n/a" when the department has work but no capacity. */
export function formatUtilization(value) {
  if (!Number.isFinite(value)) return 'n/a';
  return `${Number.isInteger(value) ? value : value.toFixed(1)}%`;
}

const isCount = (value) => Number.isInteger(value) && value >= 0;

/**
 * The department-capacity endpoint's answer as the page's state, or null when it is not a complete, valid answer (nothing is guessed or
 * defaulted: an unreadable answer is "unavailable", never zeros or the old fixed ranges).
 *
 * @returns {{ status: 'ready', enabled: boolean, threshold: number, shiftMinutes: number, departments: Array<object> } | null}
 */
export function parseDepartmentCapacityAnswer(answer) {
  if (!answer || typeof answer !== 'object' || typeof answer.enabled !== 'boolean') return null;
  const threshold = answer.capacityThresholdPercent;
  if (!Number.isInteger(threshold) || threshold < 1 || threshold > 100) return null;
  if (!Number.isInteger(answer.shiftMinutes) || answer.shiftMinutes <= 0) return null;
  if (!Array.isArray(answer.departments)) return null;
  for (const d of answer.departments) {
    const valid = d && typeof d === 'object' && typeof d.code === 'string' && d.code && typeof d.name === 'string'
      && ['remainingMinutes', 'assignedMinutes', 'waitingMinutes', 'activeTasks', 'waitingTasks', 'eligibleStaff', 'helpersIn', 'lentOut'].every((k) => isCount(d[k]))
      && Number.isFinite(d.capacityMinutes) && d.capacityMinutes >= 0
      && (d.utilizationPercent === null || (Number.isFinite(d.utilizationPercent) && d.utilizationPercent >= 0))
      && DEPARTMENT_LEVELS.includes(d.level) && typeof d.atRisk === 'boolean' && typeof d.noCapacity === 'boolean';
    if (!valid) return null;
  }
  return { status: 'ready', enabled: answer.enabled, threshold, shiftMinutes: answer.shiftMinutes, departments: answer.departments };
}

/**
 * The explanation of the department levels (the heading's tooltip), from the page's state. It describes the department cards only:
 * individual staff statuses use their own fixed ranges and are not affected by the Capacity Threshold.
 */
export function describeDepartmentRisk(state) {
  const base = 'Utilization is the department\'s remaining task minutes as a share of its staff capacity (eligible available staff x a 480-minute shift).';
  if (state?.status === 'ready' && state.enabled) return `${base} A department is AT RISK from ${state.threshold}% (the Capacity Threshold in System Settings).`;
  if (state?.status === 'ready') return `${base} The Max Task Capacity Alert is off, so no department is marked AT RISK.`;
  if (state?.status === 'unavailable') return 'The department capacity could not be loaded, so the risk levels are unavailable.';
  return base;
}

/** The level a utilization has under the server's rule (kept here so the legend can be proven to describe exactly the levels that can occur). */
export function levelForUtilization(utilization, threshold, enabled = true) {
  if (!Number.isFinite(utilization)) return null;
  if (enabled && utilization >= threshold) return 'AT_RISK';
  if (utilization <= 25) return 'LOW';
  if (utilization <= 50) return 'NORMAL';
  return 'HIGH';
}

/**
 * The legend for the department cards: one tile per level that can actually occur under the saved threshold, in plain numbers. AT RISK
 * starts at the threshold (inclusive); below it LOW is up to 25%, NORMAL up to 50% and HIGH above 50%, each cut off where the threshold
 * begins, so a level the threshold makes impossible is not listed (at 40% there is no HIGH; at 10% there is only LOW below the risk
 * level). With the alert off there is no AT RISK tile and HIGH is everything above 50%.
 *
 * @returns {Array<{ status: string, label: string, text: string }>}
 */
export function getDepartmentLegendRanges(threshold, enabled = true) {
  const tiles = [];
  if (!enabled || !Number.isInteger(threshold)) {
    return [
      { status: 'LOW', label: 'LOW', text: '0–25%' },
      { status: 'NORMAL', label: 'NORMAL', text: '25–50%' },
      { status: 'HIGH', label: 'HIGH', text: '50%+' },
    ];
  }
  tiles.push({ status: 'LOW', label: 'LOW', text: `0–${Math.min(25, threshold)}%` });
  if (threshold > 25) tiles.push({ status: 'NORMAL', label: 'NORMAL', text: `25–${Math.min(50, threshold)}%` });
  if (threshold > 50) tiles.push({ status: 'HIGH', label: 'HIGH', text: `50–${threshold}%` });
  tiles.push({ status: 'AT_RISK', label: 'AT RISK', text: `${threshold}%+` });
  return tiles;
}

/**
 * One standard shift in minutes (8 hours). Every workload percentage in the app (Dashboard, Workload page, Assign dialog) is a share of
 * this, so the same person shows the same percentage everywhere.
 */
export const SHIFT_MINUTES = 480;

/**
 * Calculates percentage of a standard shift (default 480 min = 8 hours), clamped to 0–100%.
 *
 * @param {number|string|null|undefined} minutes
 * @param {number} [maxMinutes=480]
 * @returns {number}
 */
export function calculateWorkloadPercentage(minutes, maxMinutes = SHIFT_MINUTES) {
  const safeMinutes = Math.max(0, parseInt(minutes, 10) || 0);
  const safeMax = maxMinutes > 0 ? maxMinutes : SHIFT_MINUTES;
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
 * The workload rows of the departments in `departmentList` only. Used for a Manager, whose page is about the departments they manage:
 * the server already sends only those, and this keeps the cards, totals, table and pages consistent with the department list. Without a
 * department list (not loaded, or it failed) the rows are returned as they are, because the server has already limited them.
 *
 * @param {Array<Object>} [workloadList=[]]
 * @param {Array<Object>} [departmentList=[]]
 * @returns {Array<Object>}
 */
export function restrictWorkloadToDepartments(workloadList = [], departmentList = []) {
  const rows = Array.isArray(workloadList) ? workloadList : [];
  const codes = new Set(
    (Array.isArray(departmentList) ? departmentList : []).filter((d) => d && d.code).map((d) => String(d.code).toUpperCase())
  );
  if (codes.size === 0) return rows;
  return rows.filter((row) => row && codes.has(String(row.department || '').toUpperCase()));
}

/**
 * Aggregates workload by department, ensuring mathematical consistency with staff workload totals.
 *
 * With `options.strict` (a Manager): the built-in departments are never used as a fallback, and a row of a department that is not in the
 * department list neither creates a card nor counts towards the total, so no percentage ever includes it.
 *
 * @param {Array<Object>} [workloadList=[]]
 * @param {Array<Object>} [departmentList=[]]
 * @param {{ strict?: boolean }} [options]
 * @returns {Array<{ code: string, name: string, totalMinutes: number, activeTaskCount: number, staffCount: number, percentage: number, overallMinutes: number }>}
 */
export function calculateDepartmentWorkloads(workloadList = [], departmentList = [], options = {}) {
  const strict = options?.strict === true;
  const safeWorkload = Array.isArray(workloadList) ? workloadList : [];
  const safeDeptList = Array.isArray(departmentList) ? departmentList : [];

  const deptMetaMap = new Map();
  safeDeptList.forEach((d) => {
    if (d && d.code) {
      deptMetaMap.set(String(d.code).toUpperCase(), d);
    }
  });

  // Departments come from the database, so a department created later appears here without any code change, and one an Admin removed
  // never does. Without a list there is nothing to seed: a department then appears only through a workload row that carries its code.
  const seeds = Array.from(deptMetaMap.values());

  const deptMap = new Map();
  seeds.forEach((dept) => {
    const meta = deptMetaMap.get(String(dept.code).toUpperCase());
    deptMap.set(String(dept.code).toUpperCase(), {
      code: dept.code,
      name: meta?.name || dept.name,
      totalMinutes: 0,
      activeTaskCount: 0,
      staffCount: typeof meta?.staffCount === 'number' ? meta.staffCount : 0,
      iconKey: meta?.iconKey,
      colorKey: meta?.colorKey,
      active: meta ? meta.active !== false : true,
    });
  });

  let overallMinutes = 0;

  safeWorkload.forEach((w) => {
    if (!w) return;
    // Someone on temporary cross-department support counts under the department they are helping (their home department is unchanged)
    const rawDept = String(w.supportDepartment || w.department || '').toUpperCase();
    // Strict (a Manager): with a known department list, a row of any other department is not part of this page at all
    if (strict && deptMetaMap.size > 0 && !deptMap.has(rawDept)) return;
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
        name: (typeof w.departmentName === 'string' && w.departmentName) || rawDept,
        totalMinutes: 0,
        activeTaskCount: 0,
        staffCount: 0,
        active: true,
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
