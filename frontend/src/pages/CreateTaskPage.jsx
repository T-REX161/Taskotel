import React, { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { TasksIcon } from '../components/icons/Icons';
import { createTask, createAssignment, getStaff, getTasks, getAssignments, getUserRole } from '../services/api';
import CreateTaskForm from '../components/tasks/CreateTaskForm';
import { filterEligibleStaff } from '../utils/staffFilter';
import useDepartments from '../hooks/useDepartments';
import { loadCurrentProfile } from '../utils/currentUserProfile';
import {
  MANAGER_DEPARTMENT_LOAD_FAILED_MESSAGE,
  MANAGER_NO_DEPARTMENT_MESSAGE,
  createTaskDepartmentAccess,
  managerDepartmentFromProfile,
} from '../utils/taskDepartment';

export default function CreateTaskPage() {
  const navigate = useNavigate();
  const { departments, activeDepartments } = useDepartments();
  // A Manager creates tasks in their own department only (the server refuses any other): it is read from their own profile before the form
  // is shown, and never defaulted. An Admin keeps the existing selector and default.
  const isManager = getUserRole() === 'MANAGER';
  const [managerDepartment, setManagerDepartment] = useState({ status: isManager ? 'loading' : 'not-needed' });
  const departmentAccess = createTaskDepartmentAccess(getUserRole(), managerDepartment);

  const initialFormState = {
    title: '',
    assignTo: '',
    description: '',
    department: isManager ? '' : 'HOUSEKEEPING',
    category: 'Cleaning',
    priority: 'MEDIUM',
    estimatedDuration: '45',
    dueDate: '',
    deadlineTime: '',
  };

  const [formData, setFormData] = useState(initialFormState);
  const [formError, setFormError] = useState('');
  const [successMessage, setSuccessMessage] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [allStaff, setAllStaff] = useState([]);
  const [tasks, setTasks] = useState([]);
  const [assignments, setAssignments] = useState([]);
  const [isLoadingStaff, setIsLoadingStaff] = useState(true);
  const [createdTaskRecord, setCreatedTaskRecord] = useState(null);

  // If the default department cannot take new tasks (for example it is inactive), start on the first one that can. Not for a Manager:
  // their department is their own, from their profile, and is never replaced by another one.
  useEffect(() => {
    if (isManager) return;
    if (activeDepartments.length > 0 && !activeDepartments.some((department) => department.code === formData.department)) {
      setFormData((prev) => ({ ...prev, department: activeDepartments[0].code }));
    }
  }, [isManager, activeDepartments, formData.department]);

  // A Manager's own department, read fresh from their profile (an Admin may have changed it since the profile was cached)
  const loadManagerDepartment = React.useCallback(async () => {
    if (!isManager) return;
    setManagerDepartment({ status: 'loading' });
    const resolved = managerDepartmentFromProfile(await loadCurrentProfile({ force: true }));
    setManagerDepartment(resolved);
    if (resolved.status === 'ready') {
      setFormData((prev) => ({ ...prev, department: resolved.code }));
    }
  }, [isManager]);

  useEffect(() => {
    let active = true;
    if (isManager) {
      loadManagerDepartment().catch(() => {
        if (active) setManagerDepartment({ status: 'error' });
      });
    }
    return () => {
      active = false;
    };
  }, [isManager, loadManagerDepartment]);

  useEffect(() => {
    let isMounted = true;
    const loadFormData = async () => {
      setIsLoadingStaff(true);
      try {
        const [staffData, tasksData, assignmentsData] = await Promise.all([
          getStaff(),
          getTasks(),
          getAssignments(),
        ]);
        if (isMounted) {
          setAllStaff(Array.isArray(staffData) ? staffData : []);
          setTasks(Array.isArray(tasksData) ? tasksData : []);
          setAssignments(Array.isArray(assignmentsData) ? assignmentsData : []);
        }
      } catch (err) {
        if (isMounted) {
          console.error('Failed to load staff and assignment data:', err);
        }
      } finally {
        if (isMounted) {
          setIsLoadingStaff(false);
        }
      }
    };

    loadFormData();
    return () => {
      isMounted = false;
    };
  }, []);

  // Filter staff by selected department, active status, availability, and active assignment status
  const eligibleStaff = React.useMemo(() => {
    return filterEligibleStaff({
      allStaff,
      tasks,
      assignments,
      department: formData.department,
    });
  }, [allStaff, tasks, assignments, formData.department]);

  // If currently selected staff is no longer in eligible list, clear assignment
  useEffect(() => {
    if (formData.assignTo) {
      const isStillEligible = eligibleStaff.some(
        (s) => String(s.id) === String(formData.assignTo)
      );
      if (!isStillEligible) {
        setFormData((prev) => ({ ...prev, assignTo: '' }));
      }
    }
  }, [eligibleStaff, formData.assignTo]);

  const handleInputChange = (e) => {
    const { name, value } = e.target;
    // A Manager's department is locked to their own; any change event for it is ignored
    if (name === 'department' && isManager) return;
    if (name === 'department') {
      setFormData((prev) => {
        const nextEligible = filterEligibleStaff({
          allStaff,
          tasks,
          assignments,
          department: value,
        });
        const stillEligible = nextEligible.some(
          (s) => String(s.id) === String(prev.assignTo)
        );

        return {
          ...prev,
          department: value,
          assignTo: stillEligible ? prev.assignTo : '',
        };
      });
    } else {
      setFormData((prev) => ({ ...prev, [name]: value }));
    }
    if (formError) setFormError('');
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (isSubmitting) return;

    setFormError('');
    setSuccessMessage('');

    // A Manager can only submit once their own department is known (the server checks it again)
    if (isManager && departmentAccess !== 'locked') {
      setFormError(departmentAccess === 'none' ? MANAGER_NO_DEPARTMENT_MESSAGE : MANAGER_DEPARTMENT_LOAD_FAILED_MESSAGE);
      return;
    }

    // Client-side validations
    if (!formData.title.trim()) {
      setFormError('Task title is required.');
      return;
    }

    if (!formData.department) {
      setFormError('Department is required.');
      return;
    }

    if (!formData.priority) {
      setFormError('Priority is required.');
      return;
    }

    const duration = parseInt(formData.estimatedDuration, 10);
    if (isNaN(duration) || duration <= 0) {
      setFormError('Estimated duration must be a positive number of minutes.');
      return;
    }

    setIsSubmitting(true);

    try {
      let task = createdTaskRecord;

      // Only create the task if it hasn't been created already in this session
      if (!task) {
        const taskPayload = {
          title: formData.title.trim(),
          description: formData.description?.trim() ? formData.description.trim() : null,
          category: formData.category || null,
          priority: formData.priority,
          estimatedDuration: duration,
          dueDate: formData.dueDate ? formData.dueDate : null,
          deadlineTime: formData.deadlineTime ? formData.deadlineTime : null,
          department: formData.department,
        };

        try {
          task = await createTask(taskPayload);
          setCreatedTaskRecord(task);
        } catch (taskErr) {
          setFormError(taskErr.message || 'Failed to create task. Please check your inputs and try again.');
          setIsSubmitting(false);
          return;
        }
      }

      // If user selected a staff member, create assignment
      if (formData.assignTo) {
        try {
          await createAssignment({
            taskId: task.id,
            staffId: formData.assignTo,
          });

          setSuccessMessage(`Task "${task.title}" created and assigned successfully!`);
          setTimeout(() => {
            navigate('/tasks');
          }, 1500);
        } catch (assignErr) {
          // Task creation succeeded, but assignment failed
          setFormError(
            `Task "${task.title}" was created successfully, but assigning staff failed: ${assignErr.message || 'Staff assignment failed'}. You can choose another staff member to retry assignment, or view the task in the tasks list.`
          );
          setIsSubmitting(false);
          return;
        }
      } else {
        setSuccessMessage(`Task "${task.title}" created successfully!`);
        setTimeout(() => {
          navigate('/tasks');
        }, 1500);
      }
    } catch (err) {
      setFormError(err.message || 'An unexpected error occurred. Please try again.');
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <div className="w-full max-w-4xl mx-auto space-y-6">
      {/* Top Breadcrumb & Header */}
      <div className="space-y-2">
        <button
          type="button"
          onClick={() => navigate('/tasks')}
          className="inline-flex items-center gap-1.5 text-xs font-semibold text-emerald-700 hover:text-emerald-800 transition-colors cursor-pointer"
        >
          <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 19l-7-7 7-7" />
          </svg>
          Back to Tasks
        </button>

        <div className="flex items-center justify-between">
          <div>
            <h1 className="text-2xl font-bold text-gray-900">Create Task</h1>
            <p className="text-gray-500 mt-1">Assign hotel operational duties, set deadlines, and manage workload balance.</p>
          </div>
          <div className="hidden sm:flex w-12 h-12 rounded-2xl bg-emerald-50 border border-emerald-100 items-center justify-center text-emerald-600 shadow-xs">
            <TasksIcon className="w-6 h-6" />
          </div>
        </div>
      </div>

      {/* Success Notification Banner */}
      {successMessage && (
        <div className="p-4 rounded-xl bg-emerald-50 border border-emerald-200 text-emerald-800 text-sm flex items-center justify-between shadow-xs animate-fadeIn">
          <div className="flex items-center gap-2.5">
            <span className="w-2.5 h-2.5 rounded-full bg-emerald-500 animate-pulse" />
            <span className="font-semibold">{successMessage}</span>
            <span className="text-xs text-emerald-600 ml-2">Redirecting to tasks list...</span>
          </div>
          <button
            type="button"
            onClick={() => navigate('/tasks')}
            className="text-xs font-bold text-emerald-800 hover:underline cursor-pointer"
          >
            Go Now
          </button>
        </div>
      )}

      {/* A Manager sees no form (and so no editable department) until their own department is known */}
      {departmentAccess === 'loading' && (
        <div role="status" data-testid="manager-department-loading" className="bg-white rounded-2xl border border-gray-100 shadow-sm p-6 sm:p-8 text-sm text-gray-500">
          Loading your department…
        </div>
      )}
      {departmentAccess === 'none' && (
        <div role="alert" data-testid="manager-no-department" className="p-4 rounded-xl bg-amber-50 border border-amber-200 text-amber-900 text-sm flex flex-wrap items-center justify-between gap-3">
          <span>{MANAGER_NO_DEPARTMENT_MESSAGE}</span>
          <button type="button" onClick={() => navigate('/tasks')} className="text-xs font-semibold underline cursor-pointer">Back to Tasks</button>
        </div>
      )}
      {departmentAccess === 'error' && (
        <div role="alert" data-testid="manager-department-error" className="p-4 rounded-xl bg-red-50 border border-red-200 text-red-800 text-sm flex flex-wrap items-center justify-between gap-3">
          <span>{MANAGER_DEPARTMENT_LOAD_FAILED_MESSAGE}</span>
          <button
            type="button"
            onClick={() => loadManagerDepartment().catch(() => setManagerDepartment({ status: 'error' }))}
            className="text-xs font-semibold underline cursor-pointer"
          >
            Try again
          </button>
        </div>
      )}

      {/* Form Card: Admin (the existing selector) or a Manager whose own department is known (locked to it) */}
      {(departmentAccess === 'select' || departmentAccess === 'locked') && (
        <CreateTaskForm
          formData={formData}
          onInputChange={handleInputChange}
          staffList={eligibleStaff}
          departments={departments}
          lockedDepartment={departmentAccess === 'locked' ? { code: managerDepartment.code, name: managerDepartment.name } : null}
          isLoadingStaff={isLoadingStaff}
          isSubmitting={isSubmitting}
          createdTaskRecord={createdTaskRecord}
          formError={formError}
          onSubmit={handleSubmit}
          onCancel={() => navigate('/tasks')}
          onViewTasks={() => navigate('/tasks')}
        />
      )}
    </div>
  );
}
