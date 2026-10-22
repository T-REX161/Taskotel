import React, { useState, useEffect, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { PlusIcon } from '../components/icons/Icons';
import {
  getTasks,
  getAssignments,
  getUserRole,
  updateTask,
  deleteTask,
  createAssignment,
  reassignTask,
  getStaff,
  getWorkload,
} from '../services/api';

import TaskSummaryCards from '../components/tasks/TaskSummaryCards';
import TaskFilters from '../components/tasks/TaskFilters';
import TasksTable from '../components/tasks/TasksTable';
import TaskDetailsModal from '../components/tasks/TaskDetailsModal';
import EditTaskModal from '../components/tasks/EditTaskModal';
import AssignTaskModal from '../components/tasks/AssignTaskModal';
import DeleteTaskModal from '../components/tasks/DeleteTaskModal';
import { filterEligibleStaff } from '../utils/staffFilter';

const ITEMS_PER_PAGE = 10;

export default function TasksPage() {
  const navigate = useNavigate();
  const userRole = getUserRole();
  const canManageTasks = userRole === 'ADMIN' || userRole === 'MANAGER';

  const [tasks, setTasks] = useState([]);
  const [assignments, setAssignments] = useState([]);
  const [workloadList, setWorkloadList] = useState([]);
  const [isLoadingWorkload, setIsLoadingWorkload] = useState(false);
  const [workloadError, setWorkloadError] = useState('');
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState('');
  const [successBanner, setSuccessBanner] = useState('');

  // Filters state
  const [searchTerm, setSearchTerm] = useState('');
  const [selectedDepartment, setSelectedDepartment] = useState('');
  const [selectedPriority, setSelectedPriority] = useState('');
  const [selectedStatus, setSelectedStatus] = useState('');

  // Pagination state
  const [currentPage, setCurrentPage] = useState(1);

  // Staff state for assignment (loaded dynamically from backend)
  const [allStaff, setAllStaff] = useState([]);
  const [isLoadingStaff, setIsLoadingStaff] = useState(false);

  // Modals state
  const [viewModalTask, setViewModalTask] = useState(null);

  const [editModalTask, setEditModalTask] = useState(null);
  const [editFormData, setEditFormData] = useState({
    title: '',
    description: '',
    category: '',
    priority: 'MEDIUM',
    department: 'HOUSEKEEPING',
    status: 'PENDING',
    estimatedDuration: '30',
    dueDate: '',
    deadlineTime: '',
  });
  const [editFormError, setEditFormError] = useState('');
  const [isSubmittingEdit, setIsSubmittingEdit] = useState(false);

  const [deleteModalTask, setDeleteModalTask] = useState(null);
  const [deleteModalError, setDeleteModalError] = useState('');
  const [isSubmittingDelete, setIsSubmittingDelete] = useState(false);

  const [assignModalTask, setAssignModalTask] = useState(null);
  const [selectedStaffId, setSelectedStaffId] = useState('');
  const [assignModalError, setAssignModalError] = useState('');
  const [isSubmittingAssign, setIsSubmittingAssign] = useState(false);

  const fetchTasksData = async (showLoading = true) => {
    if (showLoading) setIsLoading(true);
    setError('');
    try {
      const [tasksData, assignmentsData, workloadData] = await Promise.all([
        getTasks(),
        getAssignments(),
        getWorkload().catch((err) => {
          console.warn('Failed to load workload for tasks calculations:', err);
          return [];
        }),
      ]);
      setTasks(Array.isArray(tasksData) ? tasksData : []);
      setAssignments(Array.isArray(assignmentsData) ? assignmentsData : []);
      setWorkloadList(Array.isArray(workloadData) ? workloadData : []);
    } catch (err) {
      if (err.message && err.message.includes('Redirecting to login')) {
        navigate('/login');
        return;
      }
      setError(err.message || 'Unable to load tasks and assignments. Please try again.');
    } finally {
      if (showLoading) setIsLoading(false);
    }
  };

  const fetchEligibleStaff = async () => {
    setIsLoadingStaff(true);
    try {
      const data = await getStaff();
      if (Array.isArray(data)) {
        setAllStaff(data);
      }
    } catch (err) {
      console.error('Failed to load eligible staff members:', err);
    } finally {
      setIsLoadingStaff(false);
    }
  };

  const fetchWorkloadData = async () => {
    setIsLoadingWorkload(true);
    setWorkloadError('');
    try {
      const data = await getWorkload();
      if (Array.isArray(data)) {
        setWorkloadList(data);
      }
    } catch (err) {
      console.warn('Failed to refresh workload:', err);
      setWorkloadError(err.message || 'Failed to load workload data');
    } finally {
      setIsLoadingWorkload(false);
    }
  };

  useEffect(() => {
    fetchTasksData();
    if (canManageTasks) {
      fetchEligibleStaff();
      fetchWorkloadData();
    }
  }, [canManageTasks]);

  // Auto-dismiss success notification
  useEffect(() => {
    if (!successBanner) return;
    const timer = setTimeout(() => {
      setSuccessBanner('');
    }, 4500);
    return () => clearTimeout(timer);
  }, [successBanner]);

  // Map assignments by task ID
  const assignmentsByTaskId = useMemo(() => {
    const map = new Map();
    assignments.forEach((assignment) => {
      if (assignment.taskId) {
        map.set(assignment.taskId, assignment);
      }
    });
    return map;
  }, [assignments]);

  // Dynamically filter eligible staff for the active assign modal task
  const eligibleStaff = useMemo(() => {
    if (!assignModalTask) return [];
    const currentAssignment = assignmentsByTaskId.get(assignModalTask.id);
    return filterEligibleStaff({
      allStaff,
      tasks,
      assignments,
      department: assignModalTask.department,
      excludeAssignmentId: currentAssignment?.id,
      excludeTaskId: assignModalTask.id,
    });
  }, [assignModalTask, allStaff, tasks, assignments, assignmentsByTaskId]);



  // Filtered task list
  const filteredTasks = useMemo(() => {
    return tasks.filter((task) => {
      // Search filter across title, category, description, and assigned staff name
      if (searchTerm.trim()) {
        const query = searchTerm.toLowerCase();
        const titleMatch = (task.title || '').toLowerCase().includes(query);
        const categoryMatch = (task.category || '').toLowerCase().includes(query);
        const descriptionMatch = (task.description || '').toLowerCase().includes(query);
        const assignment = assignmentsByTaskId.get(task.id);
        const staffMatch = assignment?.staffName?.toLowerCase().includes(query);
        if (!titleMatch && !categoryMatch && !descriptionMatch && !staffMatch) {
          return false;
        }
      }

      // Department filter
      if (selectedDepartment && task.department !== selectedDepartment) {
        return false;
      }

      // Priority filter
      if (selectedPriority && task.priority !== selectedPriority) {
        return false;
      }

      // Status filter
      if (selectedStatus && task.status !== selectedStatus) {
        return false;
      }

      return true;
    });
  }, [tasks, assignmentsByTaskId, searchTerm, selectedDepartment, selectedPriority, selectedStatus]);

  // Reset pagination to page 1 whenever any filter or search term changes
  useEffect(() => {
    setCurrentPage(1);
  }, [searchTerm, selectedDepartment, selectedPriority, selectedStatus]);

  // Clamp currentPage if filtered list shrinks (e.g. after delete or status change)
  const totalPages = Math.max(1, Math.ceil(filteredTasks.length / ITEMS_PER_PAGE));
  useEffect(() => {
    if (currentPage > totalPages) {
      setCurrentPage(totalPages);
    }
  }, [currentPage, totalPages]);

  // Slice the filtered list for the current page
  const paginatedTasks = useMemo(() => {
    const start = (currentPage - 1) * ITEMS_PER_PAGE;
    return filteredTasks.slice(start, start + ITEMS_PER_PAGE);
  }, [filteredTasks, currentPage]);

  // Build page number array (show at most 5 page buttons with ellipsis-like clamping)
  const pageNumbers = useMemo(() => {
    if (totalPages <= 7) {
      return Array.from({ length: totalPages }, (_, i) => i + 1);
    }
    const pages = new Set([1, totalPages]);
    for (let i = Math.max(2, currentPage - 2); i <= Math.min(totalPages - 1, currentPage + 2); i++) {
      pages.add(i);
    }
    return Array.from(pages).sort((a, b) => a - b);
  }, [totalPages, currentPage]);

  // Open Edit Modal
  const handleOpenEditModal = (task) => {
    setEditModalTask(task);
    setEditFormData({
      title: task.title || '',
      description: task.description || '',
      category: task.category || 'Cleaning',
      priority: task.priority || 'MEDIUM',
      department: task.department || 'HOUSEKEEPING',
      status: task.status || 'PENDING',
      estimatedDuration: task.estimatedDuration ? String(task.estimatedDuration) : '30',
      dueDate: task.dueDate ? String(task.dueDate) : '',
      deadlineTime: task.deadlineTime ? String(task.deadlineTime).substring(0, 5) : '',
    });
    setEditFormError('');
  };

  const handleEditChange = (field, value) => {
    setEditFormData((prev) => ({ ...prev, [field]: value }));
  };

  // Submit Edit Modal
  const handleEditSubmit = async (e) => {
    e.preventDefault();
    if (isSubmittingEdit) return;

    setEditFormError('');

    if (!editFormData.title.trim()) {
      setEditFormError('Task title is required.');
      return;
    }
    if (!editFormData.department) {
      setEditFormError('Department is required.');
      return;
    }
    if (!editFormData.priority) {
      setEditFormError('Priority is required.');
      return;
    }
    if (!editFormData.status) {
      setEditFormError('Status is required.');
      return;
    }
    const duration = parseInt(editFormData.estimatedDuration, 10);
    if (isNaN(duration) || duration <= 0) {
      setEditFormError('Estimated duration must be a positive number of minutes.');
      return;
    }

    setIsSubmittingEdit(true);

    try {
      const updatePayload = {
        title: editFormData.title.trim(),
        description: editFormData.description?.trim() ? editFormData.description.trim() : null,
        category: editFormData.category?.trim() ? editFormData.category.trim() : null,
        priority: editFormData.priority,
        estimatedDuration: duration,
        dueDate: editFormData.dueDate ? editFormData.dueDate : null,
        deadlineTime: editFormData.deadlineTime ? editFormData.deadlineTime : null,
        department: editFormData.department,
        status: editFormData.status,
      };

      await updateTask(editModalTask.id, updatePayload);
      setSuccessBanner(`Task "${updatePayload.title}" updated successfully.`);
      setEditModalTask(null);
      await fetchTasksData(false);
    } catch (err) {
      setEditFormError(err.message || 'Failed to update task. Please check your inputs.');
    } finally {
      setIsSubmittingEdit(false);
    }
  };

  // Open Delete Modal
  const handleOpenDeleteModal = (task) => {
    setDeleteModalTask(task);
    setDeleteModalError('');
  };

  // Submit Delete Modal
  const handleDeleteSubmit = async () => {
    if (isSubmittingDelete || !deleteModalTask) return;

    setIsSubmittingDelete(true);
    setDeleteModalError('');

    try {
      await deleteTask(deleteModalTask.id);
      const deletedTitle = deleteModalTask.title;
      setDeleteModalTask(null);
      setSuccessBanner(`Task "${deletedTitle}" was successfully deleted.`);
      await fetchTasksData(false);
    } catch (err) {
      setDeleteModalError(err.message || 'Failed to delete task. Please try again.');
    } finally {
      setIsSubmittingDelete(false);
    }
  };

  // Open Assign / Reassign Modal
  const handleOpenAssignModal = (task) => {
    setAssignModalTask(task);
    setSelectedStaffId('');
    setAssignModalError('');
    // Refresh eligible staff and workload
    fetchEligibleStaff();
    fetchWorkloadData();
  };

  // Submit Assign / Reassign Modal
  const handleAssignSubmit = async (e) => {
    e.preventDefault();
    if (isSubmittingAssign || !assignModalTask) return;

    setAssignModalError('');

    if (!selectedStaffId) {
      setAssignModalError('Please select an eligible staff member.');
      return;
    }

    const currentAssignment = assignmentsByTaskId.get(assignModalTask.id);
    const isReassign = Boolean(currentAssignment);

    if (isReassign && currentAssignment.staffId === selectedStaffId) {
      setAssignModalError('Target staff member already has an active assignment for this task. Please choose a different staff member.');
      return;
    }

    setIsSubmittingAssign(true);

    try {
      if (isReassign) {
        await reassignTask(currentAssignment.id, { staffId: selectedStaffId });
        setSuccessBanner(`Task "${assignModalTask.title}" was successfully reassigned.`);
      } else {
        await createAssignment({
          taskId: assignModalTask.id,
          staffId: selectedStaffId,
        });
        setSuccessBanner(`Task "${assignModalTask.title}" was successfully assigned.`);
      }
      setAssignModalTask(null);
      await fetchTasksData(false);
    } catch (err) {
      setAssignModalError(err.message || 'Failed to process assignment. Please try again.');
    } finally {
      setIsSubmittingAssign(false);
    }
  };

  if (isLoading) {
    return (
      <div className="w-full space-y-6">
        <div className="flex justify-between items-center">
          <div>
            <h1 className="text-2xl font-bold text-gray-900">Tasks</h1>
            <p className="text-gray-500 mt-1">Create, manage and track hotel operational tasks.</p>
          </div>
        </div>
        <div className="flex items-center justify-center p-16 bg-white rounded-2xl border border-gray-100 shadow-sm">
          <div className="flex flex-col items-center gap-3">
            <svg className="animate-spin h-8 w-8 text-emerald-600" fill="none" viewBox="0 0 24 24">
              <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
              <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z" />
            </svg>
            <p className="text-sm font-medium text-gray-500">Loading tasks and assignments...</p>
          </div>
        </div>
      </div>
    );
  }

  if (error) {
    return (
      <div className="w-full space-y-6">
        <div className="flex justify-between items-center">
          <div>
            <h1 className="text-2xl font-bold text-gray-900">Tasks</h1>
            <p className="text-gray-500 mt-1">Create, manage and track hotel operational tasks.</p>
          </div>
        </div>
        <div className="p-6 bg-red-50 border border-red-200 rounded-2xl text-red-800 space-y-3">
          <h3 className="font-bold text-base">Failed to load tasks</h3>
          <p className="text-sm">{error}</p>
          <button
            type="button"
            onClick={() => fetchTasksData(true)}
            className="px-4 py-2 bg-red-600 hover:bg-red-700 text-white rounded-xl text-sm font-medium transition-colors cursor-pointer"
          >
            Retry
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="w-full space-y-6">
      {/* Top Header */}
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-gray-900 tracking-tight">Tasks</h1>
          <p className="text-sm text-gray-500 mt-1">Create, manage and track hotel operational tasks.</p>
        </div>
        {canManageTasks && (
          <button
            type="button"
            onClick={() => navigate('/tasks/create')}
            className="inline-flex items-center justify-center gap-2 px-4 py-2 bg-emerald-600 hover:bg-emerald-700 active:bg-emerald-800 text-white rounded-lg font-medium text-sm transition-colors cursor-pointer shadow-sm whitespace-nowrap self-start sm:self-auto"
          >
            <PlusIcon className="w-4 h-4" />
            <span>Create Task</span>
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

      {/* Task Summary Metrics Cards */}
      <TaskSummaryCards tasks={tasks} />

      {/* Single horizontal filter row beneath summary cards */}
      <TaskFilters
        searchTerm={searchTerm}
        onSearchChange={setSearchTerm}
        selectedStatus={selectedStatus}
        onStatusChange={setSelectedStatus}
        selectedPriority={selectedPriority}
        onPriorityChange={setSelectedPriority}
        selectedDepartment={selectedDepartment}
        onDepartmentChange={setSelectedDepartment}
      />

      {/* Clean White Table Container */}
      <div className="bg-white rounded-xl shadow-sm border border-gray-200 overflow-hidden w-full">
        <TasksTable
          filteredTasks={paginatedTasks}
          totalTaskCount={tasks.length}
          assignmentsByTaskId={assignmentsByTaskId}
          canManageTasks={canManageTasks}
          onView={(task) => setViewModalTask(task)}
          onEdit={handleOpenEditModal}
          onAssign={handleOpenAssignModal}
          onDelete={handleOpenDeleteModal}
        />

        {/* Pagination controls */}
        {filteredTasks.length > 0 && (
          <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3 px-4 py-3 border-t border-gray-100 bg-gray-50/50">
            {/* Record count label */}
            <p className="text-xs text-gray-500 shrink-0">
              Showing{' '}
              <span className="font-medium text-gray-700">
                {(currentPage - 1) * ITEMS_PER_PAGE + 1}
              </span>
              {'–'}
              <span className="font-medium text-gray-700">
                {Math.min(currentPage * ITEMS_PER_PAGE, filteredTasks.length)}
              </span>
              {' '}of{' '}
              <span className="font-medium text-gray-700">{filteredTasks.length}</span>
              {' '}{filteredTasks.length === 1 ? 'task' : 'tasks'}
            </p>

            {/* Page buttons */}
            {totalPages > 1 && (
              <div className="flex items-center gap-1">
                {/* Previous */}
                <button
                  type="button"
                  onClick={() => setCurrentPage((p) => Math.max(1, p - 1))}
                  disabled={currentPage === 1}
                  className="inline-flex items-center justify-center h-8 px-3 rounded-lg border border-gray-200 text-xs font-medium text-gray-600 bg-white hover:bg-gray-50 hover:border-gray-300 disabled:opacity-40 disabled:pointer-events-none transition-colors cursor-pointer"
                  aria-label="Previous page"
                >
                  ‹ Prev
                </button>

                {/* Numbered pages */}
                {pageNumbers.map((page, idx) => {
                  const prev = pageNumbers[idx - 1];
                  const showEllipsis = prev !== undefined && page - prev > 1;
                  return (
                    <React.Fragment key={page}>
                      {showEllipsis && (
                        <span className="px-1 text-xs text-gray-400 select-none">…</span>
                      )}
                      <button
                        type="button"
                        onClick={() => setCurrentPage(page)}
                        className={`inline-flex items-center justify-center h-8 w-8 rounded-lg border text-xs font-medium transition-colors cursor-pointer ${
                          page === currentPage
                            ? 'bg-emerald-600 border-emerald-600 text-white shadow-sm'
                            : 'bg-white border-gray-200 text-gray-600 hover:bg-gray-50 hover:border-gray-300'
                        }`}
                        aria-label={`Page ${page}`}
                        aria-current={page === currentPage ? 'page' : undefined}
                      >
                        {page}
                      </button>
                    </React.Fragment>
                  );
                })}

                {/* Next */}
                <button
                  type="button"
                  onClick={() => setCurrentPage((p) => Math.min(totalPages, p + 1))}
                  disabled={currentPage === totalPages}
                  className="inline-flex items-center justify-center h-8 px-3 rounded-lg border border-gray-200 text-xs font-medium text-gray-600 bg-white hover:bg-gray-50 hover:border-gray-300 disabled:opacity-40 disabled:pointer-events-none transition-colors cursor-pointer"
                  aria-label="Next page"
                >
                  Next ›
                </button>
              </div>
            )}
          </div>
        )}
      </div>

      {/* ── MODALS ── */}
      <TaskDetailsModal
        task={viewModalTask}
        assignmentsByTaskId={assignmentsByTaskId}
        onClose={() => setViewModalTask(null)}
      />

      {canManageTasks && (
        <>
          <EditTaskModal
            task={editModalTask}
            formData={editFormData}
            onFormChange={handleEditChange}
            onSubmit={handleEditSubmit}
            onClose={() => setEditModalTask(null)}
            isSubmitting={isSubmittingEdit}
            formError={editFormError}
          />

          <AssignTaskModal
            task={assignModalTask}
            currentAssignment={assignModalTask ? assignmentsByTaskId.get(assignModalTask.id) : null}
            eligibleStaff={eligibleStaff}
            isLoadingStaff={isLoadingStaff}
            workloadList={workloadList}
            isLoadingWorkload={isLoadingWorkload}
            workloadError={workloadError}
            selectedStaffId={selectedStaffId}
            onStaffChange={setSelectedStaffId}
            onSubmit={handleAssignSubmit}
            onClose={() => setAssignModalTask(null)}
            isSubmitting={isSubmittingAssign}
            error={assignModalError}
            asModal
          />

          <DeleteTaskModal
            task={deleteModalTask}
            isAssigned={Boolean(deleteModalTask && assignmentsByTaskId.has(deleteModalTask.id))}
            onConfirm={handleDeleteSubmit}
            onClose={() => setDeleteModalTask(null)}
            isSubmitting={isSubmittingDelete}
            error={deleteModalError}
          />
        </>
      )}
    </div>
  );
}
