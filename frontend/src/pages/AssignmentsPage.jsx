import React, { useState, useEffect, useMemo, useCallback, useRef } from 'react';
import { createPortal } from 'react-dom';
import { useNavigate, useLocation } from 'react-router-dom';
import { AssignmentsIcon, DotsVerticalIcon, EyeIcon } from '../components/icons/Icons';
import {
  getAssignments,
  getTasks,
  getWorkload,
  getStaff,
  createAssignment,
  reassignTask,
  getUserRole,
} from '../services/api';
import {
  calculateAssignmentSummary,
  resolveAssignmentDepartment,
  formatAssignedAt,
  splitAssignedAt,
  formatAssignmentStatus,
  getEligibleUnassignedTasks,
  getActionsMenuPosition,
  getNextMenuItemIndex,
  getPaginationState,
  getPageNumbers,
  groupAssignmentsByTask,
  isAssignmentTaskFinished,
  EMPTY_ASSIGNMENT_FILTERS,
  filterAssignments,
  hasActiveAssignmentFilters,
} from '../utils/assignmentUtils';
import { assignmentSuccessMessage, buildAssignmentRequest, submittableStaffIds, toggleStaffSelection } from '../utils/staffSelection';
import { filterEligibleStaff } from '../utils/staffFilter';
import AssignTaskModal from '../components/tasks/AssignTaskModal';
import PageSizeToggle from '../components/common/PageSizeToggle';
import TaskDetailsModal from '../components/tasks/TaskDetailsModal';
import AssignmentFilters from '../components/assignments/AssignmentFilters';
import AssignmentStatusBadge from '../components/assignments/AssignmentStatusBadge';
import UserAvatar from '../components/common/UserAvatar';
import { staffInitials } from '../components/assignments/assignmentTableStyles';

const ASSIGN_TASK_BUTTON_CLASS =
  'shrink-0 inline-flex items-center justify-center bg-emerald-600 hover:bg-emerald-700 active:bg-emerald-800 text-white px-4 py-2 rounded-lg text-sm font-medium transition-colors cursor-pointer shadow-sm focus:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 focus-visible:ring-offset-2';
const ASSIGNMENTS_PER_PAGE = 10;
const PAGINATION_BUTTON_FOCUS_CLASS =
  'focus:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 focus-visible:ring-offset-1';
const ACTIONS_MENU_ID = 'assignment-actions-menu';
const ACTIONS_MENU_WIDTH = 176; // w-44
const ACTIONS_MENU_ESTIMATED_HEIGHT = 96; // two items plus padding; replaced by the measured height once rendered
const ACTIONS_MENU_ITEM_CLASS =
  'w-full text-left px-3 py-2 text-xs text-gray-700 hover:bg-emerald-50 hover:text-emerald-700 focus:outline-none focus-visible:bg-emerald-50 focus-visible:text-emerald-700 flex items-center gap-2 transition-colors cursor-pointer';

export default function AssignmentsPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const userRole = getUserRole();
  const canManageAssignments = userRole === 'ADMIN' || userRole === 'MANAGER';

  const [assignments, setAssignments] = useState([]);
  const [tasks, setTasks] = useState([]);
  const [workload, setWorkload] = useState([]);
  const [allStaff, setAllStaff] = useState([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState('');
  const [successBanner, setSuccessBanner] = useState('');

  // Default view is the Assignments overview table
  const [isAssignModalOpen, setIsAssignModalOpen] = useState(false);
  const [assignModalTask, setAssignModalTask] = useState(null);
  const [currentAssignment, setCurrentAssignment] = useState(null);
  const [selectedStaffIds, setSelectedStaffIds] = useState([]); // several for a new assignment, at most one when reassigning
  const [assignModalError, setAssignModalError] = useState('');
  const [isSubmittingAssign, setIsSubmittingAssign] = useState(false);
  const [isLoadingStaff] = useState(false);

  const [viewModalTask, setViewModalTask] = useState(null);

  const fetchData = useCallback(async () => {
    setIsLoading(true);
    setError('');

    try {
      const assignmentsPromise = getAssignments();
      const tasksPromise = getTasks().catch((err) => {
        console.warn('Failed to load tasks for assignments calculations:', err);
        return [];
      });
      const workloadPromise = getWorkload().catch((err) => {
        console.warn('Failed to load workload for assignments calculations:', err);
        return [];
      });
      const staffPromise = canManageAssignments
        ? getStaff().catch((err) => {
            console.warn('Failed to load staff for assignments calculations:', err);
            return [];
          })
        : Promise.resolve([]);

      const [assignmentsData, tasksData, workloadData, staffData] = await Promise.all([
        assignmentsPromise,
        tasksPromise,
        workloadPromise,
        staffPromise,
      ]);

      setAssignments(Array.isArray(assignmentsData) ? assignmentsData : []);
      setTasks(Array.isArray(tasksData) ? tasksData : []);
      setWorkload(Array.isArray(workloadData) ? workloadData : []);
      setAllStaff(Array.isArray(staffData) ? staffData : []);
    } catch (err) {
      if (err.message && err.message.includes('Redirecting to login')) {
        navigate('/login');
        return;
      }
      setError(err.message || 'Unable to load assignments data. Please try again.');
    } finally {
      setIsLoading(false);
    }
  }, [navigate, canManageAssignments]);

  useEffect(() => {
    fetchData();
  }, [fetchData]);

  // Handle optional task navigation state (e.g. task passed from another view).
  // Consumes state on demand and clears it from history to prevent stale reopen on refresh or revisit.
  useEffect(() => {
    if (!canManageAssignments) return;

    if (location.state?.task) {
      setAssignModalTask(location.state.task);
      setIsAssignModalOpen(true);
      navigate(location.pathname, { replace: true, state: null });
    } else if (location.state?.taskId) {
      if (tasks.length > 0) {
        const found = tasks.find((t) => String(t.id) === String(location.state.taskId));
        if (found) {
          setAssignModalTask(found);
          setIsAssignModalOpen(true);
        }
        navigate(location.pathname, { replace: true, state: null });
      } else if (!isLoading) {
        navigate(location.pathname, { replace: true, state: null });
      }
    } else if (location.state) {
      // Clear any other stale navigation state (e.g. viewMode)
      navigate(location.pathname, { replace: true, state: null });
    }
  }, [location.state, tasks, isLoading, canManageAssignments, navigate, location.pathname]);

  // Auto-dismiss success notification
  useEffect(() => {
    if (!successBanner) return;
    const timer = setTimeout(() => {
      setSuccessBanner('');
    }, 4500);
    return () => clearTimeout(timer);
  }, [successBanner]);

  // Dynamic summary metrics
  const summary = useMemo(() => {
    return calculateAssignmentSummary({ assignments, tasks });
  }, [assignments, tasks]);

  // Search and filters over the fetched assignments (no API call per keystroke). The summary cards keep using the full list.
  const [filters, setFilters] = useState(EMPTY_ASSIGNMENT_FILTERS);
  const filtersActive = hasActiveAssignmentFilters(filters);
  const filteredAssignments = useMemo(
    () => filterAssignments(assignments, filters, { tasks, workload }),
    [assignments, filters, tasks, workload]
  );

  // Client-side pagination of the filtered assignments
  const [currentPage, setCurrentPage] = useState(1);
  const [pageSize, setPageSize] = useState(ASSIGNMENTS_PER_PAGE); // rows per page, chosen with the toggle
  const pagination = useMemo(
    () => getPaginationState({ totalItems: filteredAssignments.length, currentPage, pageSize }),
    [filteredAssignments.length, currentPage, pageSize]
  );

  // Any change to the search or a filter starts again at page 1; clearing restores the complete list
  const handleFilterChange = (key, value) => {
    setFilters((prev) => ({ ...prev, [key]: value }));
    setCurrentPage(1);
  };
  const handleClearFilters = () => {
    setFilters(EMPTY_ASSIGNMENT_FILTERS);
    setCurrentPage(1);
  };

  // A new page size starts again at page 1
  const handlePageSizeChange = (size) => {
    setPageSize(size);
    setCurrentPage(1);
  };

  // Keep the stored page valid when the list shrinks (the render already uses the clamped page)
  useEffect(() => {
    if (currentPage !== pagination.page) {
      setCurrentPage(pagination.page);
    }
  }, [currentPage, pagination.page]);

  const paginatedAssignments = useMemo(
    () => filteredAssignments.slice(pagination.startIndex, pagination.endIndex),
    [filteredAssignments, pagination.startIndex, pagination.endIndex]
  );

  const pageNumbers = useMemo(
    () => getPageNumbers(pagination.totalPages, pagination.page),
    [pagination.totalPages, pagination.page]
  );

  // Eligible unassigned tasks available for new assignment
  const unassignedTasks = useMemo(() => {
    return getEligibleUnassignedTasks({ tasks, assignments });
  }, [tasks, assignments]);

  // Assignments grouped by task ID, Map<taskId, assignment[]> (a task can have several assigned staff members), for TaskDetailsModal
  const assignmentsByTaskId = useMemo(() => groupAssignmentsByTask(assignments), [assignments]);

  // Dynamically filter eligible staff for the active assign modal task
  const eligibleStaff = useMemo(() => {
    if (!assignModalTask) return [];
    return filterEligibleStaff({
      allStaff,
      tasks,
      assignments,
      department: assignModalTask.department,
      excludeAssignmentId: currentAssignment?.id,
      excludeTaskId: assignModalTask.id,
    });
  }, [assignModalTask, allStaff, tasks, assignments, currentAssignment]);

  // Open Assign Task workflow from header
  const handleOpenAssignModal = () => {
    setCurrentAssignment(null);
    setAssignModalTask(null);
    setSelectedStaffIds([]);
    setAssignModalError('');
    setIsAssignModalOpen(true);
  };

  // Select an unassigned task in modal
  const handleTaskSelect = (taskId) => {
    const selected = unassignedTasks.find((t) => String(t.id) === String(taskId)) || null;
    setAssignModalTask(selected);
    setSelectedStaffIds([]);
    setAssignModalError('');
  };

  // Open Reassign workflow for a specific assignment row
  const handleOpenReassignModal = (assignment) => {
    setCurrentAssignment(assignment);
    const associatedTask = tasks.find((t) => String(t.id) === String(assignment.taskId)) || {
      id: assignment.taskId,
      title: assignment.taskTitle,
      status: assignment.taskStatus,
      department: resolveAssignmentDepartment(assignment, tasks, workload),
    };
    setAssignModalTask(associatedTask);
    setSelectedStaffIds([]);
    setAssignModalError('');
    setIsAssignModalOpen(true);
  };

  // Open View task details modal
  const handleOpenViewModal = (assignment) => {
    const associatedTask = tasks.find((t) => String(t.id) === String(assignment.taskId)) || {
      id: assignment.taskId,
      title: assignment.taskTitle,
      status: assignment.taskStatus,
      department: resolveAssignmentDepartment(assignment, tasks, workload),
    };
    setViewModalTask(associatedTask);
  };

  // Row actions menu (⋮): rendered through a portal so the table's overflow container cannot clip it
  const [activeMenu, setActiveMenu] = useState(null); // { assignment, buttonEl }
  const [menuPosition, setMenuPosition] = useState(null);
  const menuRef = useRef(null);

  const closeActionsMenu = useCallback((restoreFocus = false) => {
    const buttonEl = activeMenu?.buttonEl;
    setActiveMenu(null);
    if (restoreFocus && buttonEl && document.body.contains(buttonEl)) {
      buttonEl.focus();
    }
  }, [activeMenu]);

  const updateMenuPosition = useCallback(() => {
    if (!activeMenu?.buttonEl) return;
    if (!document.body.contains(activeMenu.buttonEl)) {
      setActiveMenu(null);
      return;
    }
    setMenuPosition(
      getActionsMenuPosition({
        rect: activeMenu.buttonEl.getBoundingClientRect(),
        viewportWidth: window.innerWidth,
        viewportHeight: window.innerHeight,
        menuWidth: ACTIONS_MENU_WIDTH,
        menuHeight: menuRef.current?.offsetHeight || ACTIONS_MENU_ESTIMATED_HEIGHT,
      })
    );
  }, [activeMenu]);

  useEffect(() => {
    if (!activeMenu) {
      setMenuPosition(null);
      return;
    }

    updateMenuPosition();

    const handleReposition = () => updateMenuPosition();
    const handleKeyDown = (e) => {
      if (e.key === 'Escape') {
        e.preventDefault();
        closeActionsMenu(true);
        return;
      }
      if (e.key === 'Tab') {
        closeActionsMenu(false);
        return;
      }
      if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
        const items = menuRef.current ? Array.from(menuRef.current.querySelectorAll('[role="menuitem"]:not([disabled])')) : [];
        const nextIndex = getNextMenuItemIndex(items.indexOf(document.activeElement), items.length, e.key);
        if (nextIndex >= 0) {
          e.preventDefault();
          items[nextIndex].focus();
        }
      }
    };
    const handlePointerDown = (e) => {
      if (menuRef.current?.contains(e.target) || activeMenu.buttonEl?.contains(e.target)) return;
      setActiveMenu(null);
    };

    window.addEventListener('scroll', handleReposition, true);
    window.addEventListener('resize', handleReposition);
    document.addEventListener('keydown', handleKeyDown);
    document.addEventListener('pointerdown', handlePointerDown);

    return () => {
      window.removeEventListener('scroll', handleReposition, true);
      window.removeEventListener('resize', handleReposition);
      document.removeEventListener('keydown', handleKeyDown);
      document.removeEventListener('pointerdown', handlePointerDown);
    };
  }, [activeMenu, updateMenuPosition, closeActionsMenu]);

  // Once the menu is placed, move focus to its first item (keyboard users land inside the menu)
  useEffect(() => {
    if (!activeMenu || !menuPosition || !menuRef.current) return;
    if (!menuRef.current.contains(document.activeElement)) {
      menuRef.current.querySelector('[role="menuitem"]')?.focus();
    }
  }, [activeMenu, menuPosition]);

  // The table is replaced while loading or while the assignment workflow is open
  useEffect(() => {
    if (isLoading || isAssignModalOpen) setActiveMenu(null);
  }, [isLoading, isAssignModalOpen]);

  const handleActionsButtonClick = (e, assignment) => {
    e.stopPropagation();
    if (activeMenu?.assignment?.id === assignment.id) {
      setActiveMenu(null);
    } else {
      setActiveMenu({ assignment, buttonEl: e.currentTarget });
    }
  };

  const handleMenuViewDetails = () => {
    const assignment = activeMenu?.assignment;
    setActiveMenu(null);
    if (assignment) handleOpenViewModal(assignment);
  };

  const handleMenuReassign = () => {
    const assignment = activeMenu?.assignment;
    setActiveMenu(null);
    // A completed or cancelled task cannot be reassigned (the server refuses it), so its dialog is never opened
    if (assignment && !isAssignmentTaskFinished(assignment, tasks)) handleOpenReassignModal(assignment);
  };

  const handleCloseAssignModal = () => {
    if (isSubmittingAssign) return;
    setIsAssignModalOpen(false);
    setAssignModalTask(null);
    setCurrentAssignment(null);
    setSelectedStaffIds([]);
    setAssignModalError('');
    if (location.state) {
      navigate(location.pathname, { replace: true, state: null });
    }
  };

  // Submit Assign / Reassign Modal
  const handleAssignSubmit = async (e) => {
    e.preventDefault();
    if (isSubmittingAssign || !assignModalTask) return;

    setAssignModalError('');

    const isReassign = Boolean(currentAssignment);
    const assignedIds = isReassign ? (assignmentsByTaskId.get(assignModalTask.id) || []).map((a) => a.staffId) : [];

    // A new assignment sends everyone selected; a reassignment moves the one assignment row to one person
    const staffIds = submittableStaffIds(selectedStaffIds, eligibleStaff, assignedIds);
    if (staffIds.length === 0) {
      setAssignModalError('Please select at least one eligible staff member.');
      return;
    }
    if (isReassign && staffIds.length > 1) {
      setAssignModalError('A reassignment moves the task to one staff member. Please choose one.');
      return;
    }

    setIsSubmittingAssign(true);

    try {
      if (isReassign) {
        await reassignTask(currentAssignment.id, { staffId: staffIds[0] });
        setSuccessBanner(`Task "${assignModalTask.title}" was successfully reassigned.`);
      } else {
        await createAssignment(buildAssignmentRequest(assignModalTask.id, staffIds));
        setSuccessBanner(assignmentSuccessMessage(assignModalTask.title, staffIds.length));
      }

      setIsAssignModalOpen(false);
      setAssignModalTask(null);
      setCurrentAssignment(null);
      setSelectedStaffIds([]);
      if (location.state) {
        navigate(location.pathname, { replace: true, state: null });
      }

      // Refresh assignments, tasks, and workload to reflect updated data
      await fetchData();
    } catch (err) {
      setAssignModalError(err.message || 'Failed to process assignment. Please try again.');
    } finally {
      setIsSubmittingAssign(false);
    }
  };

  const summaryCards = [
    {
      title: 'Total Assignments',
      value: String(summary.totalAssignments),
      icon: <AssignmentsIcon className="w-6 h-6 text-emerald-600" />,
      bg: 'bg-emerald-50',
      accent: 'ring-emerald-100',
    },
    {
      title: 'Active Assignments',
      value: String(summary.activeAssignments),
      icon: <AssignmentsIcon className="w-6 h-6 text-blue-600" />,
      bg: 'bg-blue-50',
      accent: 'ring-blue-100',
    },
    {
      title: 'Completed Today',
      value: String(summary.completedToday),
      icon: <AssignmentsIcon className="w-6 h-6 text-purple-600" />,
      bg: 'bg-purple-50',
      accent: 'ring-purple-100',
    },
    {
      title: 'Unassigned Tasks',
      value: String(summary.unassignedTasks),
      icon: <AssignmentsIcon className="w-6 h-6 text-yellow-600" />,
      bg: 'bg-yellow-50',
      accent: 'ring-yellow-100',
    },
  ];

  if (isLoading) {
    return (
      <div className="w-full space-y-6">
        {/* Header */}
        <div className="flex items-end justify-between gap-4">
          <div className="min-w-0">
            <h1 className="text-2xl font-semibold tracking-tight text-gray-900">Assignments</h1>
            <p className="mt-1 text-sm text-gray-500">Manage task assignments and staff distributions.</p>
          </div>
          {canManageAssignments && (
            <button
              onClick={handleOpenAssignModal}
              className={ASSIGN_TASK_BUTTON_CLASS}
            >
              Assign Task
            </button>
          )}
        </div>

        {/* Loading Spinner */}
        <div className="flex items-center justify-center p-16 bg-white rounded-2xl border border-gray-100 shadow-sm">
          <div className="flex flex-col items-center gap-3">
            <svg
              className="animate-spin h-8 w-8 text-emerald-600"
              fill="none"
              viewBox="0 0 24 24"
            >
              <circle
                className="opacity-25"
                cx="12"
                cy="12"
                r="10"
                stroke="currentColor"
                strokeWidth="4"
              />
              <path
                className="opacity-75"
                fill="currentColor"
                d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"
              />
            </svg>
            <p className="text-sm font-medium text-gray-500">
              Loading assignments...
            </p>
          </div>
        </div>
      </div>
    );
  }

  if (error) {
    return (
      <div className="w-full space-y-6">
        {/* Header */}
        <div className="flex items-end justify-between gap-4">
          <div className="min-w-0">
            <h1 className="text-2xl font-semibold tracking-tight text-gray-900">Assignments</h1>
            <p className="mt-1 text-sm text-gray-500">Manage task assignments and staff distributions.</p>
          </div>
          {canManageAssignments && (
            <button
              onClick={handleOpenAssignModal}
              className={ASSIGN_TASK_BUTTON_CLASS}
            >
              Assign Task
            </button>
          )}
        </div>

        {/* Error Alert */}
        <div className="p-6 bg-red-50 border border-red-200 rounded-2xl text-red-800 space-y-3" role="alert">
          <h3 className="font-bold text-base">Failed to load assignments</h3>
          <p className="text-sm">{error}</p>
          <button
            type="button"
            onClick={fetchData}
            className="px-4 py-2 bg-red-600 hover:bg-red-700 text-white rounded-xl text-sm font-medium transition-colors cursor-pointer"
          >
            Try Again
          </button>
        </div>
      </div>
    );
  }

  if (canManageAssignments && isAssignModalOpen) {
    return (
      <div className="w-full space-y-6">
        {successBanner && (
          <div className="p-4 rounded-xl bg-emerald-50 border border-emerald-200 text-emerald-800 text-sm flex items-center justify-between shadow-xs animate-fadeIn">
            <div className="flex items-center gap-2.5">
              <span className="w-2.5 h-2.5 rounded-full bg-emerald-500 animate-pulse" />
              <span className="font-semibold">{successBanner}</span>
            </div>
            <button
              type="button"
              onClick={() => setSuccessBanner('')}
              className="text-xs font-bold text-emerald-800 hover:underline cursor-pointer"
            >
              Dismiss
            </button>
          </div>
        )}

        <AssignTaskModal
          task={assignModalTask}
          unassignedTasks={unassignedTasks}
          onTaskChange={handleTaskSelect}
          currentAssignment={currentAssignment}
          eligibleStaff={eligibleStaff}
          isLoadingStaff={isLoadingStaff}
          workloadList={workload}
          isLoadingWorkload={false}
          workloadError=""
          selectedStaffIds={selectedStaffIds}
          onStaffToggle={(staffId) => setSelectedStaffIds((current) => toggleStaffSelection(current, staffId, { single: Boolean(currentAssignment) }))}
          assignedStaffIds={currentAssignment && assignModalTask ? (assignmentsByTaskId.get(assignModalTask.id) || []).map((a) => a.staffId) : []}
          onSubmit={handleAssignSubmit}
          onClose={handleCloseAssignModal}
          isSubmitting={isSubmittingAssign}
          error={assignModalError}
        />
      </div>
    );
  }

  return (
    <div className="w-full space-y-6">
      {/* Header */}
      <div className="flex items-end justify-between gap-4">
        <div className="min-w-0">
          <h1 className="text-2xl font-semibold tracking-tight text-gray-900">Assignments</h1>
          <p className="mt-1 text-sm text-gray-500">Manage task assignments and staff distributions.</p>
        </div>
        {canManageAssignments && (
          <button
            type="button"
            onClick={handleOpenAssignModal}
            className={ASSIGN_TASK_BUTTON_CLASS}
          >
            Assign Task
          </button>
        )}
      </div>

      {/* Success Notification Banner */}
      {successBanner && (
        <div className="p-4 rounded-xl bg-emerald-50 border border-emerald-200 text-emerald-800 text-sm flex items-center justify-between shadow-xs animate-fadeIn">
          <div className="flex items-center gap-2.5">
            <span className="w-2.5 h-2.5 rounded-full bg-emerald-500 animate-pulse" />
            <span className="font-semibold">{successBanner}</span>
          </div>
          <button
            type="button"
            onClick={() => setSuccessBanner('')}
            className="text-xs font-bold text-emerald-800 hover:underline cursor-pointer"
          >
            Dismiss
          </button>
        </div>
      )}

      {/* Summary Cards */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4 lg:gap-5">
        {summaryCards.map((card, idx) => (
          <div
            key={idx}
            className="bg-white rounded-xl p-5 shadow-sm border border-gray-200/70 flex items-center gap-4 transition-[box-shadow,border-color] duration-200 hover:shadow-md hover:border-gray-300/80"
          >
            <div className={`shrink-0 p-3 rounded-xl ring-1 ring-inset ${card.bg} ${card.accent}`}>
              {card.icon}
            </div>
            <div className="min-w-0">
              <p className="text-xs font-medium uppercase tracking-wide text-gray-500">{card.title}</p>
              <h3 className="mt-1 text-2xl font-semibold leading-none tabular-nums text-gray-900">{card.value}</h3>
            </div>
          </div>
        ))}
      </div>

      {/* Assignments Table */}
      <div className="bg-white rounded-xl shadow-sm border border-gray-200/70 overflow-hidden">
        <div className="px-6 pt-5 pb-3 space-y-3">
          <div className="flex flex-col sm:flex-row sm:items-start sm:justify-between gap-3">
            <div className="min-w-0">
              <h2 className="text-lg font-semibold tracking-tight text-gray-900">Current Staff Assignments</h2>
              <p className="mt-1 text-sm text-gray-500">Monitor and manage your team&apos;s active task assignments.</p>
            </div>
            {/* How many records match (the whole list when nothing is set), as a quiet pill; green while filters are applied */}
            <p
              className={`self-start shrink-0 inline-flex items-center gap-2 rounded-full border px-3 py-1 text-xs font-medium whitespace-nowrap ${
                filtersActive ? 'border-emerald-200 bg-emerald-50 text-emerald-800' : 'border-gray-200 bg-gray-50 text-gray-600'
              }`}
              aria-live="polite"
              data-testid="assignment-match-count"
            >
              <span className={`h-1.5 w-1.5 rounded-full ${filtersActive ? 'bg-emerald-500' : 'bg-gray-400'}`} aria-hidden="true" />
              {filtersActive ? (
                <span>
                  <span className="font-semibold tabular-nums text-gray-900">{filteredAssignments.length}</span> of {assignments.length}{' '}
                  {assignments.length === 1 ? 'assignment matches' : 'assignments match'}
                </span>
              ) : (
                <span>
                  <span className="font-semibold tabular-nums text-gray-900">{assignments.length}</span>{' '}
                  {assignments.length === 1 ? 'assignment' : 'assignments'}
                </span>
              )}
            </p>
          </div>
          <AssignmentFilters
            filters={filters}
            onChange={handleFilterChange}
            onClear={handleClearFilters}
            isActive={filtersActive}
          />
        </div>
        {/* The table keeps its column layout on small screens and scrolls sideways inside the card */}
        <div className="overflow-x-auto">
          <table className="w-full min-w-[760px] text-left">
            <thead className="bg-emerald-50/50 text-[11px] font-semibold uppercase tracking-wider whitespace-nowrap text-gray-600 border-y border-gray-200/70">
              <tr>
                <th className="px-6 py-3">Task</th>
                <th className="px-6 py-3">Staff Member</th>
                <th className="px-6 py-3">Department</th>
                <th className="px-6 py-3 whitespace-nowrap text-center">Assigned Time</th>
                <th className="px-6 py-3">Status</th>
                <th className="px-6 py-3 w-20 text-right">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100 text-sm">
              {assignments.length === 0 ? (
                <tr>
                  <td colSpan={6} className="py-14 px-6 text-center text-sm text-gray-500">
                    <div className="flex flex-col items-center gap-2">
                      <span className="mb-1 flex h-12 w-12 items-center justify-center rounded-full bg-emerald-50 ring-1 ring-inset ring-emerald-100 text-emerald-600" aria-hidden="true">
                        <AssignmentsIcon className="w-6 h-6" />
                      </span>
                      <p className="font-semibold text-gray-900">No assignments found</p>
                      <p className="text-sm text-gray-500">
                        There are currently no staff assignments in the system.
                      </p>
                    </div>
                  </td>
                </tr>
              ) : filteredAssignments.length === 0 ? (
                <tr>
                  <td colSpan={6} className="py-14 px-6 text-center text-sm text-gray-500" data-testid="assignments-no-match">
                    <div className="flex flex-col items-center gap-2">
                      <span className="mb-1 flex h-12 w-12 items-center justify-center rounded-full bg-emerald-50 ring-1 ring-inset ring-emerald-100 text-emerald-600" aria-hidden="true">
                        <AssignmentsIcon className="w-6 h-6" />
                      </span>
                      <p className="font-semibold text-gray-900">No assignments match your filters</p>
                      <p className="text-sm text-gray-500">Try a different search, department or assigned time.</p>
                      <button
                        type="button"
                        onClick={handleClearFilters}
                        className="mt-2 px-4 py-2 text-sm font-medium text-emerald-700 bg-emerald-50 hover:bg-emerald-100 rounded-lg ring-1 ring-inset ring-emerald-100 transition-colors cursor-pointer focus:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500"
                      >
                        Clear Filters
                      </button>
                    </div>
                  </td>
                </tr>
              ) : (
                paginatedAssignments.map((assignment) => {
                  const taskTitle = assignment.taskTitle || assignment.task || 'Untitled Task';
                  const staffName = assignment.staffName || assignment.staff || 'Unassigned';
                  const department = resolveAssignmentDepartment(assignment, tasks, workload);
                  const assignedTime = splitAssignedAt(formatAssignedAt(assignment.assignedAt));
                  const statusRaw = assignment.taskStatus || assignment.status;
                  const statusLabel = formatAssignmentStatus(statusRaw);
                  const isMenuOpen = activeMenu?.assignment?.id === assignment.id;

                  return (
                    <tr key={assignment.id} className={`transition-colors duration-150 hover:bg-emerald-50/40 ${isMenuOpen ? 'bg-emerald-50/40' : ''}`}>
                      {/* Long titles wrap to two lines at most; the full text is in the tooltip */}
                      <td className="px-6 py-4 align-middle">
                        <p className="max-w-[18rem] font-semibold leading-snug text-gray-900 break-words line-clamp-2" title={taskTitle}>{taskTitle}</p>
                      </td>
                      <td className="px-6 py-4 align-middle">
                        <div className="flex items-center gap-2.5 min-w-0">
                          {/* The staff member's profile picture when they have one (initials if not, or if it fails to load) */}
                          <UserAvatar
                            imageUrl={assignment.staffProfileImageUrl}
                            initials={staffInitials(staffName)}
                            name={staffName}
                            className="h-7 w-7 shrink-0 rounded-full text-[11px] font-semibold"
                            fallbackClassName="bg-emerald-50 text-emerald-700 ring-1 ring-inset ring-emerald-100"
                          />
                          <span className="max-w-[12rem] truncate font-medium text-gray-700" title={staffName}>{staffName}</span>
                        </div>
                      </td>
                      <td className="px-6 py-4 align-middle whitespace-nowrap text-gray-600">{department}</td>
                      <td className="px-6 py-4 align-middle whitespace-nowrap text-center tabular-nums">
                        {/* Date above time, centered; 16px + 2px + 12px stays within the 32px actions-button row height */}
                        <div className="flex flex-col items-center justify-center gap-0.5">
                          <span className="text-[13px] leading-4 font-medium text-slate-600">{assignedTime.date}</span>
                          {assignedTime.time && (
                            <span className="text-xs leading-3 text-slate-500">{assignedTime.time}</span>
                          )}
                        </div>
                      </td>
                      <td className="px-6 py-4 align-middle whitespace-nowrap">
                        <AssignmentStatusBadge status={statusRaw} label={statusLabel} />
                      </td>
                      <td className="px-6 py-4 align-middle text-right">
                        <button
                          type="button"
                          onClick={(e) => handleActionsButtonClick(e, assignment)}
                          className={`w-8 h-8 rounded-lg border transition-colors cursor-pointer inline-flex items-center justify-center focus:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 focus-visible:ring-offset-1 ${
                            isMenuOpen
                              ? 'border-emerald-300 bg-emerald-50 text-emerald-700 shadow-xs'
                              : 'border-transparent bg-transparent text-gray-400 hover:border-gray-200 hover:bg-white hover:text-gray-700 hover:shadow-xs dark:hover:bg-gray-100'
                          }`}
                          aria-label={`Actions for ${taskTitle}`}
                          aria-haspopup="menu"
                          aria-expanded={isMenuOpen}
                          aria-controls={isMenuOpen ? ACTIONS_MENU_ID : undefined}
                          title="Actions"
                        >
                          <DotsVerticalIcon className="w-4 h-4" aria-hidden="true" />
                        </button>
                      </td>
                    </tr>
                  );
                })
              )}
            </tbody>
          </table>
        </div>

        {/* Pagination controls over the filtered list (hidden when nothing is shown) */}
        {filteredAssignments.length > 0 && (
          <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3 px-6 py-3 border-t border-gray-100 bg-gray-50/50">
            <p className="text-xs text-gray-500 shrink-0" aria-live="polite">
              Showing{' '}
              <span className="font-medium text-gray-700">{pagination.rangeStart}</span>
              {'–'}
              <span className="font-medium text-gray-700">{pagination.rangeEnd}</span>
              {' '}of{' '}
              <span className="font-medium text-gray-700">{filteredAssignments.length}</span>
              {' '}{filteredAssignments.length === 1 ? 'assignment' : 'assignments'}
            </p>

            <div className="flex flex-wrap items-center gap-2">
              <PageSizeToggle value={pageSize} onChange={handlePageSizeChange} />
              {pagination.totalPages > 1 && (
                <nav className="flex flex-wrap items-center gap-1" aria-label="Assignments pagination">
                  <button
                    type="button"
                    onClick={() => setCurrentPage(pagination.page - 1)}
                    disabled={!pagination.hasPrevious}
                    className={`inline-flex items-center justify-center h-8 px-3 rounded-lg border border-gray-200 text-xs font-medium text-gray-600 bg-white hover:bg-gray-50 hover:border-gray-300 disabled:opacity-40 disabled:pointer-events-none transition-colors cursor-pointer ${PAGINATION_BUTTON_FOCUS_CLASS}`}
                    aria-label="Previous page"
                  >
                    ‹ Prev
                  </button>

                  {pageNumbers.map((page, idx) => {
                    const prev = pageNumbers[idx - 1];
                    const showEllipsis = prev !== undefined && page - prev > 1;
                    return (
                      <React.Fragment key={page}>
                        {showEllipsis && (
                          <span className="px-1 text-xs text-gray-400 select-none" aria-hidden="true">…</span>
                        )}
                        <button
                          type="button"
                          onClick={() => setCurrentPage(page)}
                          className={`inline-flex items-center justify-center h-8 w-8 rounded-lg border text-xs font-medium transition-colors cursor-pointer ${PAGINATION_BUTTON_FOCUS_CLASS} ${
                            page === pagination.page
                              ? 'bg-emerald-600 border-emerald-600 text-white shadow-sm'
                              : 'bg-white border-gray-200 text-gray-600 hover:bg-gray-50 hover:border-gray-300'
                          }`}
                          aria-label={`Page ${page}`}
                          aria-current={page === pagination.page ? 'page' : undefined}
                        >
                          {page}
                        </button>
                      </React.Fragment>
                    );
                  })}

                  <button
                    type="button"
                    onClick={() => setCurrentPage(pagination.page + 1)}
                    disabled={!pagination.hasNext}
                    className={`inline-flex items-center justify-center h-8 px-3 rounded-lg border border-gray-200 text-xs font-medium text-gray-600 bg-white hover:bg-gray-50 hover:border-gray-300 disabled:opacity-40 disabled:pointer-events-none transition-colors cursor-pointer ${PAGINATION_BUTTON_FOCUS_CLASS}`}
                    aria-label="Next page"
                  >
                    Next ›
                  </button>
                </nav>
              )}
            </div>
          </div>
        )}
      </div>

      {/* Row actions menu, rendered outside the table's overflow container */}
      {activeMenu && menuPosition && createPortal(
        <div
          ref={menuRef}
          id={ACTIONS_MENU_ID}
          role="menu"
          aria-label={`Actions for ${activeMenu.assignment.taskTitle || 'assignment'}`}
          style={{
            position: 'fixed',
            ...(menuPosition.openUpward
              ? { bottom: `${menuPosition.bottom}px` }
              : { top: `${menuPosition.top}px` }),
            right: `${menuPosition.right}px`,
            zIndex: 9999,
          }}
          className="w-44 rounded-lg bg-white shadow-lg border border-gray-200 py-1 select-none animate-fadeIn"
          onClick={(e) => e.stopPropagation()}
        >
          <button
            type="button"
            role="menuitem"
            onClick={handleMenuViewDetails}
            className={ACTIONS_MENU_ITEM_CLASS}
          >
            <EyeIcon className="w-3.5 h-3.5 text-gray-400" aria-hidden="true" />
            <span>View Details</span>
          </button>
          {canManageAssignments && isAssignmentTaskFinished(activeMenu.assignment, tasks) && (
            <button
              type="button"
              role="menuitem"
              disabled
              title="Cannot reassign completed or cancelled task"
              data-testid="reassign-finished-disabled"
              className="w-full text-left px-3 py-2 text-xs text-gray-400 flex items-center gap-2 cursor-not-allowed opacity-60"
            >
              <span>Reassign (task finished)</span>
            </button>
          )}
          {canManageAssignments && !isAssignmentTaskFinished(activeMenu.assignment, tasks) && (
            <button
              type="button"
              role="menuitem"
              onClick={handleMenuReassign}
              className={ACTIONS_MENU_ITEM_CLASS}
            >
              <svg
                className="w-3.5 h-3.5 text-gray-400"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                strokeWidth="2"
                aria-hidden="true"
              >
                <path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2" />
                <circle cx="9" cy="7" r="4" />
                <polyline points="16 11 18 13 22 9" />
              </svg>
              <span>Reassign</span>
            </button>
          )}
        </div>,
        document.body
      )}

      {/* ── MODALS ── */}
      <TaskDetailsModal
        task={viewModalTask}
        assignmentsByTaskId={assignmentsByTaskId}
        onClose={() => setViewModalTask(null)}
      />
    </div>
  );
}
