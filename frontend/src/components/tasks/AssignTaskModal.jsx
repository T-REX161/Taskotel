import React, { useState, useEffect, useMemo } from 'react';
import {
  getPriorityColor,
  formatPriority,
  formatDepartment,
  formatDuration,
  formatDate,
  formatTime,
} from './taskFormatters';
import {
  getWorkloadStatusBadge,
  getWorkloadProgressBarColor,
  calculateWorkloadPercentage,
  calculateProjectedWorkload,
} from '../../utils/workloadUtils';
import { getWorkload } from '../../services/api';

/**
 * Inline spinner SVG.
 */
function Spinner({ className = 'h-4 w-4 text-white' }) {
  return (
    <svg className={`animate-spin ${className}`} fill="none" viewBox="0 0 24 24">
      <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
      <path
        className="opacity-75"
        fill="currentColor"
        d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"
      />
    </svg>
  );
}

/**
 * Helper to extract up to 2-letter uppercase initials for staff avatar.
 */
function getStaffInitials(name) {
  if (!name || typeof name !== 'string') return '?';
  const parts = name.trim().split(/\s+/);
  if (parts.length === 1) {
    return parts[0].slice(0, 2).toUpperCase();
  }
  return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
}

/**
 * AssignTaskModal — dedicated Task Assignment workflow view.
 * Redesigned to match the Taskotel Task Assignment page layout:
 *   - Top header with Back button, "Task Assignment" title & subtitle
 *   - Responsive two-column top section:
 *       • Left Card: Task Information (title, description, department, priority, duration, due date, deadline)
 *       • Right Card: Current Assignment & dynamic Workload Impact Comparison (current vs projected)
 *   - "Assign to Staff" section with responsive selectable staff cards
 *   - Clear highlight for selected staff member, availability badges, and current workloads
 *   - Supports both initial task assignment and reassignment workflows
 *
 * Props:
 *   task                – task object being assigned
 *   unassignedTasks     – optional array of eligible unassigned tasks for selection
 *   selectedTaskId      – controlled task ID when choosing from unassignedTasks
 *   onTaskChange        – (taskId: string) => void callback when user selects an unassigned task
 *   currentAssignment   – assignment record for this task (null / undefined if not assigned)
 *   eligibleStaff       – array of active+available staff objects belonging to task department
 *   isLoadingStaff      – boolean
 *   workloadList        – array of WorkloadDto objects from getWorkload()
 *   workload            – alias for workloadList
 *   isLoadingWorkload   – boolean
 *   workloadError       – string | ''
 *   selectedStaffId     – controlled selected staff UUID string
 *   onStaffChange       – (staffId: string) => void
 *   onSubmit            – (e) => void
 *   onClose             – () => void
 *   isSubmitting        – boolean
 *   error               – string | ''
 *   asModal             – boolean (default false)
 */
export default function AssignTaskModal({
  task,
  unassignedTasks,
  selectedTaskId,
  onTaskChange,
  currentAssignment,
  eligibleStaff = [],
  isLoadingStaff = false,
  workloadList,
  workload,
  isLoadingWorkload = false,
  workloadError = '',
  selectedStaffId,
  onStaffChange,
  onSubmit,
  onClose,
  isSubmitting = false,
  error = '',
  asModal = false,
}) {
  const isReassign = Boolean(currentAssignment);
  const formattedDueDate = task ? formatDate(task.dueDate) : null;
  const formattedDeadlineTime = task ? formatTime(task.deadlineTime) : null;

  // Support internal fetch if workloadList / workload is not provided by parent
  const [internalWorkload, setInternalWorkload] = useState(null);
  const [internalLoading, setInternalLoading] = useState(false);
  const [internalError, setInternalError] = useState('');

  const activeWorkloadList = workloadList ?? workload ?? internalWorkload;
  const isWorkloadLoading = isLoadingWorkload || internalLoading;
  const activeWorkloadError = workloadError || internalError;

  useEffect(() => {
    if (workloadList !== undefined || workload !== undefined) return;
    let isMounted = true;
    setInternalLoading(true);
    setInternalError('');
    getWorkload()
      .then((data) => {
        if (isMounted) {
          setInternalWorkload(Array.isArray(data) ? data : []);
        }
      })
      .catch((err) => {
        if (isMounted) {
          setInternalError(err.message || 'Unable to load workload');
        }
      })
      .finally(() => {
        if (isMounted) {
          setInternalLoading(false);
        }
      });

    return () => {
      isMounted = false;
    };
  }, [workloadList, workload]);

  // Calculate projected workload dynamically whenever selected staff or task changes
  const projectedWorkload = useMemo(() => {
    if (!selectedStaffId || !task) return null;
    return calculateProjectedWorkload({
      staffId: selectedStaffId,
      task,
      currentAssignment,
      workloadList: activeWorkloadList,
    });
  }, [selectedStaffId, task, currentAssignment, activeWorkloadList]);

  // Fast map of workloads by staffId for card display
  const workloadByStaffId = useMemo(() => {
    const map = new Map();
    if (Array.isArray(activeWorkloadList)) {
      activeWorkloadList.forEach((w) => {
        if (w && w.staffId) {
          map.set(String(w.staffId), w);
        }
      });
    }
    return map;
  }, [activeWorkloadList]);

  // Workload record of the current assignee during reassignment
  const currentAssigneeWorkload = useMemo(() => {
    if (!isReassign || !currentAssignment?.staffId) return null;
    return workloadByStaffId.get(String(currentAssignment.staffId)) || null;
  }, [isReassign, currentAssignment, workloadByStaffId]);

  // Early return must come after all hooks so they run in the same order on every render
  if (!task && !unassignedTasks) return null;

  // The current assignee's card is shown but disabled, so it is not counted as selectable
  const selectableStaffCount = eligibleStaff.filter((staff) => currentAssignment?.staffId !== staff.id).length;

  const content = (
    <div className="w-full space-y-6">
      {/* ── 1. TOP HEADER ── */}
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4 border-b border-gray-100 pb-4">
        <div>
          <h1 className="text-2xl sm:text-3xl font-bold text-gray-900 tracking-tight">
            Task Assignment
          </h1>
          <p className="text-sm text-gray-500 mt-1">
            Assign or reassign staff to this task.
          </p>
        </div>

        <div className="flex items-center gap-3 self-start sm:self-auto">
          {isReassign && (
            <span className="inline-flex items-center px-3 py-1 rounded-full text-xs font-semibold bg-blue-50 text-blue-700 border border-blue-200">
              Reassignment Mode
            </span>
          )}
          <button
            type="button"
            onClick={onClose}
            disabled={isSubmitting}
            className="inline-flex items-center gap-1.5 px-3.5 py-2 text-sm font-medium text-gray-700 hover:text-gray-900 bg-white hover:bg-gray-50 border border-gray-200 rounded-lg shadow-xs transition-colors cursor-pointer group disabled:opacity-50"
          >
            <svg
              className="w-4 h-4 text-gray-500 transition-transform group-hover:-translate-x-0.5"
              fill="none"
              viewBox="0 0 24 24"
              stroke="currentColor"
            >
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 19l-7-7 7-7" />
            </svg>
            <span>Back</span>
          </button>
        </div>
      </div>

      {/* Global Error Banner */}
      {error && (
        <div
          className="p-4 rounded-xl bg-red-50 border border-red-200 text-red-800 text-sm flex items-start gap-3 shadow-xs animate-fadeIn"
          role="alert"
        >
          <svg className="w-5 h-5 flex-shrink-0 text-red-600 mt-0.5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 8v4m0 4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
          </svg>
          <div className="flex-1">
            <h4 className="font-bold text-red-900">Assignment Error</h4>
            <p className="mt-0.5">{error}</p>
          </div>
        </div>
      )}

      <form onSubmit={onSubmit} className="space-y-6">
        {/* ── 2. TWO-COLUMN TOP SECTION: Task Info & Current Assignment ── */}
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
          {/* LEFT CARD: Task Information */}
          <div className="bg-white rounded-2xl p-6 border border-gray-100 shadow-sm flex flex-col justify-between space-y-4">
            <div className="space-y-4">
              <div className="flex items-center justify-between border-b border-gray-100 pb-3">
                <div className="flex items-center gap-2">
                  <div className="w-8 h-8 rounded-lg bg-emerald-50 text-emerald-600 flex items-center justify-center font-bold">
                    <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2" />
                    </svg>
                  </div>
                  <h2 className="text-base font-bold text-gray-900">Task Information</h2>
                </div>
                {task && (
                  <span
                    className={`inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-semibold ${getPriorityColor(
                      task.priority
                    )}`}
                  >
                    {formatPriority(task.priority)}
                  </span>
                )}
              </div>

              {/* If unassignedTasks is provided and not reassigning, show task selector dropdown */}
              {unassignedTasks && !isReassign && (
                <div className="space-y-1.5">
                  <label className="text-xs font-bold text-gray-700 uppercase tracking-wider">
                    Select Task to Assign *
                  </label>
                  <select
                    required
                    value={task ? task.id : (selectedTaskId || '')}
                    onChange={(e) => onTaskChange && onTaskChange(e.target.value)}
                    disabled={isSubmitting}
                    className="w-full px-3.5 py-2.5 border border-gray-200 rounded-xl focus:outline-none focus:ring-2 focus:ring-emerald-500 text-sm bg-white cursor-pointer"
                  >
                    <option value="">-- Select an Unassigned Task --</option>
                    {unassignedTasks.map((t) => (
                      <option key={t.id} value={t.id}>
                        {t.title} ({formatDepartment(t.department)} • {formatPriority(t.priority)} • {formatDuration(t.estimatedDuration, '30 min')})
                      </option>
                    ))}
                  </select>
                  {unassignedTasks.length === 0 && (
                    <p className="text-xs text-amber-600 font-medium">
                      There are currently no unassigned tasks available to assign.
                    </p>
                  )}
                </div>
              )}

              {/* Task Details Display */}
              {task ? (
                <div className="space-y-3.5">
                  <div>
                    <h3 className="text-lg font-bold text-gray-900">{task.title}</h3>
                    {task.description ? (
                      <p className="text-xs sm:text-sm text-gray-600 mt-1 leading-relaxed bg-gray-50/60 p-3 rounded-xl border border-gray-100">
                        {task.description}
                      </p>
                    ) : (
                      <p className="text-xs text-gray-400 italic mt-1">No description provided.</p>
                    )}
                  </div>

                  {/* Metadata Grid */}
                  <div className="grid grid-cols-2 gap-3 pt-3 border-t border-gray-100 text-xs">
                    <div>
                      <span className="text-gray-400 font-medium block">Department</span>
                      <span className="text-gray-800 font-semibold text-sm">
                        {formatDepartment(task.department)}
                      </span>
                    </div>
                    <div>
                      <span className="text-gray-400 font-medium block">Priority</span>
                      <span className="text-gray-800 font-semibold text-sm">
                        {formatPriority(task.priority)}
                      </span>
                    </div>
                    <div>
                      <span className="text-gray-400 font-medium block">Estimated Duration</span>
                      <span className="text-gray-800 font-semibold text-sm">
                        {formatDuration(task.estimatedDuration, '30 min')}
                      </span>
                    </div>
                    <div>
                      <span className="text-gray-400 font-medium block">Due Date</span>
                      <span className="text-gray-800 font-semibold text-sm">
                        {formattedDueDate || '-'}
                      </span>
                    </div>
                    <div>
                      <span className="text-gray-400 font-medium block">Deadline Time</span>
                      <span className="text-gray-800 font-semibold text-sm">
                        {formattedDeadlineTime || '-'}
                      </span>
                    </div>
                  </div>
                </div>
              ) : (
                <div className="p-8 bg-gray-50/60 rounded-xl border border-dashed border-gray-200 text-center text-xs text-gray-500">
                  Please select an unassigned task above to view task details.
                </div>
              )}
            </div>
          </div>

          {/* RIGHT CARD: Current Assignment & Workload Impact */}
          <div className="bg-white rounded-2xl p-6 border border-gray-100 shadow-sm flex flex-col justify-between space-y-4">
            <div className="space-y-4">
              <div className="flex items-center justify-between border-b border-gray-100 pb-3">
                <div className="flex items-center gap-2">
                  <div className="w-8 h-8 rounded-lg bg-blue-50 text-blue-600 flex items-center justify-center font-bold">
                    <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M16 7a4 4 0 11-8 0 4 4 0 018 0zM12 14a7 7 0 00-7 7h14a7 7 0 00-7-7z" />
                    </svg>
                  </div>
                  <h2 className="text-base font-bold text-gray-900">Current Assignment</h2>
                </div>
                <span className="text-xs text-gray-400 font-medium">Standard 8h Shift (480m)</span>
              </div>

              {/* Current Assignee Information */}
              <div className="p-3.5 bg-gray-50 rounded-xl border border-gray-100 flex items-center justify-between text-xs">
                <div>
                  <span className="text-gray-400 font-medium block">Currently Assigned Staff</span>
                  <div className="flex items-center gap-2 mt-0.5">
                    <span className="text-gray-900 font-bold text-sm">
                      {isReassign ? (currentAssignment?.staffName || 'Assigned Staff') : 'Unassigned (New Task)'}
                    </span>
                    {isReassign && currentAssigneeWorkload && (
                      <span
                        className={`inline-flex items-center px-2 py-0.5 rounded-full text-[10px] font-semibold ${getWorkloadStatusBadge(
                          currentAssigneeWorkload.workloadStatus
                        )}`}
                      >
                        {currentAssigneeWorkload.workloadStatus}
                      </span>
                    )}
                  </div>
                  {isReassign && currentAssigneeWorkload ? (
                    <span className="text-gray-500 text-xs block mt-1">
                      Current Workload: <strong className="text-gray-800">{currentAssigneeWorkload.totalEstimatedDuration} min</strong> ({currentAssigneeWorkload.activeTaskCount || 0} active {currentAssigneeWorkload.activeTaskCount === 1 ? 'task' : 'tasks'})
                    </span>
                  ) : !isReassign ? (
                    <span className="text-gray-400 text-xs block mt-1">
                      Current Workload: <span className="italic">0 min (Unassigned)</span>
                    </span>
                  ) : null}
                </div>
                <div className="text-right">
                  <span className="text-gray-400 font-medium block">Department</span>
                  <span className="text-gray-800 font-semibold text-sm">
                    {task ? formatDepartment(task.department) : '-'}
                  </span>
                </div>
              </div>

              {/* Workload Impact Analysis */}
              <div className="space-y-3 pt-1">
                <div className="flex items-center justify-between">
                  <span className="text-xs font-bold text-gray-700 uppercase tracking-wider flex items-center gap-1.5">
                    <svg className="w-3.5 h-3.5 text-emerald-600" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z" />
                    </svg>
                    <span>Workload Impact Comparison</span>
                  </span>
                </div>

                {/* Loading State */}
                {isWorkloadLoading && (
                  <div className="p-4 bg-gray-50 rounded-xl border border-gray-100 flex items-center justify-center gap-2 text-xs text-gray-500">
                    <Spinner className="h-4 w-4 text-emerald-600" />
                    <span>Loading workload data from server...</span>
                  </div>
                )}

                {/* API Error State */}
                {!isWorkloadLoading && activeWorkloadError && (
                  <div className="p-3 bg-amber-50 border border-amber-200 rounded-xl text-xs text-amber-800 flex items-center gap-2" role="alert">
                    <svg className="w-4 h-4 text-amber-600 flex-shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z" />
                    </svg>
                    <span>Unable to load live workload data. Workload projection is unavailable.</span>
                  </div>
                )}

                {/* Prompt when no staff selected */}
                {!isWorkloadLoading && !activeWorkloadError && !selectedStaffId && (
                  <div className="p-6 bg-gray-50/60 rounded-xl border border-dashed border-gray-200 text-center text-xs text-gray-500">
                    {task
                      ? 'Select an eligible staff member below to view current workload and projected assignment impact.'
                      : 'Select a task and eligible staff member to view workload comparison.'}
                  </div>
                )}

                {/* Missing workload data for selected staff */}
                {!isWorkloadLoading && !activeWorkloadError && selectedStaffId && projectedWorkload?.hasData === false && (
                  <div className="p-3 bg-gray-50 border border-gray-200 rounded-xl text-xs text-gray-500 text-center">
                    {projectedWorkload.reason === 'STAFF_NOT_FOUND'
                      ? 'Workload data unavailable for selected staff member.'
                      : 'Workload data is currently unavailable.'}
                  </div>
                )}

                {/* Active Comparison Display */}
                {!isWorkloadLoading && !activeWorkloadError && projectedWorkload?.hasData && (
                  <div className="space-y-3">
                    {/* Already assigned notice */}
                    {projectedWorkload.isAlreadyAssigned && (
                      <div className="p-2.5 rounded-lg bg-blue-50 border border-blue-200 text-xs text-blue-800 flex items-center gap-1.5 font-medium">
                        <svg className="w-4 h-4 text-blue-600 flex-shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
                        </svg>
                        <span>This task is already assigned to {projectedWorkload.staffName || 'this staff member'}. Projected workload remains unchanged.</span>
                      </div>
                    )}

                    {/* Before & After Cards */}
                    <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                      {/* Left: Current Workload */}
                      <div className="bg-gray-50/80 rounded-xl p-3.5 border border-gray-200/70 space-y-2">
                        <div className="flex items-center justify-between">
                          <span className="text-[11px] font-bold text-gray-500 uppercase tracking-wider">Current Workload</span>
                          <span className={`inline-flex items-center px-2 py-0.5 rounded-full text-[11px] font-semibold ${getWorkloadStatusBadge(projectedWorkload.currentStatus)}`}>
                            {projectedWorkload.currentStatus}
                          </span>
                        </div>

                        <div>
                          <div className="text-xl font-bold text-gray-900">
                            {projectedWorkload.currentMinutes} min
                          </div>
                          <div className="text-xs text-gray-500 mt-0.5">
                            {formatDuration(projectedWorkload.currentMinutes, '0 min')} • {projectedWorkload.currentActiveTasks} active {projectedWorkload.currentActiveTasks === 1 ? 'task' : 'tasks'}
                          </div>
                        </div>

                        {/* Progress Bar */}
                        <div className="space-y-1 pt-1">
                          <div className="w-full h-2 bg-gray-200/70 rounded-full overflow-hidden">
                            <div
                              className={`h-full rounded-full transition-all duration-300 ${getWorkloadProgressBarColor(projectedWorkload.currentStatus)}`}
                              style={{ width: `${calculateWorkloadPercentage(projectedWorkload.currentMinutes)}%` }}
                            />
                          </div>
                          <div className="flex justify-between text-[11px] text-gray-400">
                            <span>{calculateWorkloadPercentage(projectedWorkload.currentMinutes)}% shift</span>
                            <span>480m shift</span>
                          </div>
                        </div>
                      </div>

                      {/* Right: Projected Workload */}
                      <div className="bg-emerald-50/40 rounded-xl p-3.5 border border-emerald-200/80 space-y-2">
                        <div className="flex items-center justify-between">
                          <span className="text-[11px] font-bold text-emerald-800 uppercase tracking-wider">Projected Workload</span>
                          <span className={`inline-flex items-center px-2 py-0.5 rounded-full text-[11px] font-semibold ${getWorkloadStatusBadge(projectedWorkload.projectedStatus)}`}>
                            {projectedWorkload.projectedStatus}
                          </span>
                        </div>

                        <div>
                          <div className="flex items-baseline gap-2">
                            <span className="text-xl font-bold text-gray-900">
                              {projectedWorkload.projectedMinutes} min
                            </span>
                            <span className={`text-xs font-semibold ${projectedWorkload.durationAdded > 0 ? 'text-emerald-700' : 'text-gray-400'}`}>
                              {projectedWorkload.durationAdded > 0 ? `(+${projectedWorkload.durationAdded} min)` : '(+0 min)'}
                            </span>
                          </div>
                          <div className="text-xs text-gray-500 mt-0.5">
                            {formatDuration(projectedWorkload.projectedMinutes, '0 min')} • {projectedWorkload.projectedActiveTasks} active {projectedWorkload.projectedActiveTasks === 1 ? 'task' : 'tasks'}
                          </div>
                        </div>

                        {/* Progress Bar */}
                        <div className="space-y-1 pt-1">
                          <div className="w-full h-2 bg-emerald-100/70 rounded-full overflow-hidden">
                            <div
                              className={`h-full rounded-full transition-all duration-300 ${getWorkloadProgressBarColor(projectedWorkload.projectedStatus)}`}
                              style={{ width: `${calculateWorkloadPercentage(projectedWorkload.projectedMinutes)}%` }}
                            />
                          </div>
                          <div className="flex justify-between text-[11px] text-emerald-700/80">
                            <span>{calculateWorkloadPercentage(projectedWorkload.projectedMinutes)}% shift</span>
                            <span>480m shift</span>
                          </div>
                        </div>
                      </div>
                    </div>

                    {/* Status Change Note */}
                    {projectedWorkload.currentStatus !== projectedWorkload.projectedStatus && (
                      <p className="text-xs text-gray-600 bg-gray-50 rounded-lg p-2 border border-gray-100 flex items-center gap-1.5">
                        <span className="font-semibold text-gray-700">Status Change:</span>
                        <span>
                          Transitions from <span className="font-semibold text-gray-900">{projectedWorkload.currentStatus}</span> to <span className="font-semibold text-emerald-700">{projectedWorkload.projectedStatus}</span> after assignment.
                        </span>
                      </p>
                    )}
                  </div>
                )}
              </div>
            </div>
          </div>
        </div>

        {/* ── 3. ASSIGN TO STAFF SECTION WITH SELECTABLE STAFF CARDS ── */}
        <div className="bg-white rounded-2xl p-6 border border-gray-100 shadow-sm space-y-4">
          <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-1 border-b border-gray-100 pb-3">
            <div>
              <h2 className="text-base font-bold text-gray-900">Assign to Staff</h2>
              <p className="text-xs text-gray-500 mt-0.5">
                Select an active, eligible staff member from{' '}
                <span className="font-semibold text-gray-700">
                  {task ? formatDepartment(task.department) : 'the department'}
                </span>
                .
              </p>
            </div>
            {task && (
              <span className="text-xs font-semibold px-2.5 py-1 rounded-full bg-emerald-50 text-emerald-700 border border-emerald-100 self-start sm:self-auto">
                {selectableStaffCount} {selectableStaffCount === 1 ? 'Eligible Staff Member' : 'Eligible Staff Members'}
              </span>
            )}
          </div>

          {/* Loading state for staff */}
          {isLoadingStaff && (
            <div className="p-6 text-center text-sm text-gray-400 flex items-center justify-center gap-2">
              <Spinner className="h-4 w-4 text-emerald-600" />
              <span>Loading eligible staff members...</span>
            </div>
          )}

          {/* Empty state when no eligible staff found */}
          {!isLoadingStaff && task && selectableStaffCount === 0 && (
            <div className="p-4 rounded-xl bg-amber-50 border border-amber-200 text-xs text-amber-800 space-y-1">
              <p className="font-semibold">No eligible staff members available</p>
              <p>
                No {isReassign ? 'other ' : ''}eligible staff members in {formatDepartment(task.department)} are available for {isReassign ? 'reassignment' : 'assignment'}. Eligible staff must belong to the task department, be active, have AVAILABLE status, and have no other active assignment.
              </p>
              {eligibleStaff.length > selectableStaffCount && (
                <p>The current assignee is shown below for reference and cannot be selected again.</p>
              )}
            </div>
          )}

          {/* Prompt to select a task first */}
          {!isLoadingStaff && !task && (
            <div className="p-6 text-center text-xs text-gray-400 bg-gray-50/50 rounded-xl border border-dashed border-gray-200">
              Please select a task above to load eligible department staff members.
            </div>
          )}

          {/* Responsive Selectable Staff Cards Grid */}
          {!isLoadingStaff && eligibleStaff.length > 0 && (
            <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-4">
              {eligibleStaff.map((staff) => {
                const isCurrent = currentAssignment?.staffId === staff.id;
                const isSelected = selectedStaffId === staff.id;
                const displayName = staff.name || staff.fullName || 'Unnamed Staff';
                const staffWorkload = workloadByStaffId.get(String(staff.id));
                const currentMins = staffWorkload?.totalEstimatedDuration ?? 0;
                const activeTasks = staffWorkload?.activeTaskCount ?? 0;
                const workloadStatus = staffWorkload?.workloadStatus || 'LOW';

                return (
                  <div
                    key={staff.id}
                    onClick={() => {
                      if (!isCurrent && !isSubmitting) {
                        onStaffChange(staff.id);
                      }
                    }}
                    className={`relative rounded-xl p-4 transition-all border flex flex-col justify-between space-y-3 ${
                      isCurrent
                        ? 'opacity-60 cursor-not-allowed bg-gray-50/80 border-gray-200'
                        : isSelected
                        ? 'border-emerald-600 bg-emerald-50/20 ring-2 ring-emerald-600/20 shadow-xs cursor-pointer'
                        : 'border-gray-200 hover:border-emerald-300 hover:bg-gray-50/50 bg-white cursor-pointer shadow-2xs'
                    }`}
                  >
                    {/* Hidden accessible radio for form / test query compatibility */}
                    <input
                      type="radio"
                      name="staffMember"
                      value={staff.id}
                      disabled={isCurrent}
                      checked={isSelected}
                      onChange={() => onStaffChange(staff.id)}
                      className="sr-only"
                    />

                    {/* Top Row: Avatar + Name + Checkbox/Indicator */}
                    <div className="flex items-start justify-between gap-2">
                      <div className="flex items-center gap-2.5 min-w-0">
                        {staff.profileImage || staff.avatarUrl ? (
                          <img
                            src={staff.profileImage || staff.avatarUrl}
                            alt={displayName}
                            className="w-9 h-9 rounded-full object-cover flex-shrink-0"
                          />
                        ) : (
                          <div
                            className={`w-9 h-9 rounded-full flex items-center justify-center font-bold text-xs flex-shrink-0 ${
                              isSelected
                                ? 'bg-emerald-600 text-white shadow-xs'
                                : 'bg-emerald-100 text-emerald-800'
                            }`}
                          >
                            {getStaffInitials(displayName)}
                          </div>
                        )}
                        <div className="min-w-0">
                          <span className="font-bold text-gray-900 text-sm block truncate" title={displayName}>
                            {displayName}
                          </span>
                          <span className="text-xs text-gray-500 block truncate">
                            {formatDepartment(staff.department)}
                            {isCurrent ? ' - Currently Assigned' : ''}
                          </span>
                        </div>
                      </div>

                      {/* Selection Radio Icon */}
                      <div
                        className={`w-5 h-5 rounded-full flex items-center justify-center flex-shrink-0 transition-colors ${
                          isSelected
                            ? 'bg-emerald-600 text-white'
                            : isCurrent
                            ? 'border border-gray-300 bg-gray-200'
                            : 'border border-gray-300 bg-white'
                        }`}
                      >
                        {isSelected && (
                          <svg className="w-3 h-3" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={3} d="M5 13l4 4L19 7" />
                          </svg>
                        )}
                      </div>
                    </div>

                    {/* Middle Row: Status Badges */}
                    <div className="flex items-center gap-1.5 flex-wrap pt-1 border-t border-gray-100 text-[11px]">
                      <span className="inline-flex items-center px-2 py-0.5 rounded-full font-medium bg-emerald-50 text-emerald-700 border border-emerald-100">
                        {staff.availability || 'AVAILABLE'}
                      </span>
                      <span
                        className={`inline-flex items-center px-2 py-0.5 rounded-full font-semibold ${getWorkloadStatusBadge(
                          workloadStatus
                        )}`}
                      >
                        {workloadStatus}
                      </span>
                      {isCurrent && (
                        <span className="inline-flex items-center px-2 py-0.5 rounded-full font-semibold bg-gray-100 text-gray-600">
                          Currently Assigned
                        </span>
                      )}
                    </div>

                    {/* Bottom Row: Workload Metrics + Progress Bar */}
                    <div className="space-y-1.5 pt-1 border-t border-gray-100 text-xs">
                      <div className="flex items-center justify-between text-gray-600">
                        <span className="font-medium">Current Workload:</span>
                        <span className="font-bold text-gray-900">{currentMins} min</span>
                      </div>
                      <div className="text-[11px] text-gray-400">
                        {activeTasks} active {activeTasks === 1 ? 'task' : 'tasks'}
                      </div>
                      <div className="w-full h-1.5 bg-gray-100 rounded-full overflow-hidden">
                        <div
                          className={`h-full rounded-full ${getWorkloadProgressBarColor(workloadStatus)}`}
                          style={{ width: `${calculateWorkloadPercentage(currentMins)}%` }}
                        />
                      </div>
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </div>

        {/* ── 4. ACTION BAR ── */}
        <div className="flex flex-col sm:flex-row sm:items-center sm:justify-end gap-3 pt-4 border-t border-gray-100">
          <button
            type="button"
            onClick={onClose}
            disabled={isSubmitting}
            className="w-full sm:w-auto px-5 py-2.5 text-gray-700 font-medium hover:bg-gray-50 border border-gray-200 rounded-xl text-sm transition-colors cursor-pointer disabled:opacity-50 text-center"
          >
            Cancel
          </button>
          <button
            type="submit"
            disabled={isSubmitting || !selectedStaffId || !task}
            className="w-full sm:w-auto px-6 py-2.5 bg-emerald-600 hover:bg-emerald-700 active:bg-emerald-800 text-white font-medium rounded-xl text-sm transition-colors cursor-pointer flex items-center justify-center gap-2 shadow-xs disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {isSubmitting ? (
              <>
                <Spinner />
                <span>Processing...</span>
              </>
            ) : isReassign ? (
              'Confirm Reassignment'
            ) : (
              'Assign Task'
            )}
          </button>
        </div>
      </form>
    </div>
  );

  if (asModal) {
    return (
      <div
        className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-gray-900/50 backdrop-blur-xs animate-fadeIn overflow-y-auto"
        onClick={() => !isSubmitting && onClose()}
      >
        <div
          className="bg-white rounded-2xl max-w-5xl w-full p-6 sm:p-8 shadow-xl border border-gray-100 max-h-[92vh] overflow-y-auto my-6"
          onClick={(e) => e.stopPropagation()}
        >
          {content}
        </div>
      </div>
    );
  }

  return content;
}
