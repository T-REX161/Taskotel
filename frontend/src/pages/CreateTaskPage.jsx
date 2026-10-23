import React, { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { TasksIcon } from '../components/icons/Icons';
import { createTask, createAssignment, getStaff, getTasks, getAssignments } from '../services/api';
import CreateTaskForm from '../components/tasks/CreateTaskForm';
import { filterEligibleStaff } from '../utils/staffFilter';
import useDepartments from '../hooks/useDepartments';

export default function CreateTaskPage() {
  const navigate = useNavigate();
  const { departments, activeDepartments } = useDepartments();

  const initialFormState = {
    title: '',
    assignTo: '',
    description: '',
    department: 'HOUSEKEEPING',
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

  // If the default department cannot take new tasks (for example it is inactive), start on the first one that can
  useEffect(() => {
    if (activeDepartments.length > 0 && !activeDepartments.some((department) => department.code === formData.department)) {
      setFormData((prev) => ({ ...prev, department: activeDepartments[0].code }));
    }
  }, [activeDepartments, formData.department]);

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

      {/* Form Card */}
      <CreateTaskForm
        formData={formData}
        onInputChange={handleInputChange}
        staffList={eligibleStaff}
        departments={departments}
        isLoadingStaff={isLoadingStaff}
        isSubmitting={isSubmitting}
        createdTaskRecord={createdTaskRecord}
        formError={formError}
        onSubmit={handleSubmit}
        onCancel={() => navigate('/tasks')}
        onViewTasks={() => navigate('/tasks')}
      />
    </div>
  );
}
