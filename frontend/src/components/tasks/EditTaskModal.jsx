import React, { useState } from 'react';
import { CloseIcon } from '../icons/Icons';
import DepartmentOptions from '../common/DepartmentOptions';
import useDepartments from '../../hooks/useDepartments';
import { DEPARTMENT_CHANGE_LOCKED_HINT } from '../../utils/taskDepartment';
import { backdropMotion, dialogMotion, withExitTransition } from '../../theme/presence';
import Spinner from '../common/Spinner';

/**
 * Inline spinner SVG reused across the submit buttons.
 */

/**
 * EditTaskModal — form for updating an existing task.
 * ADMIN and MANAGER only.
 *
 * Props:
 *   task           – the task being edited (used to render the modal title)
 *   formData       – controlled form state object
 *   onFormChange   – (field, value) => void  — called on every field change
 *   onSubmit       – (e) => void             — called on form submit
 *   onClose        – () => void
 *   isSubmitting   – boolean
 *   formError      – string | ''
 *   canChangeDepartment – false for a Manager: the department is shown but cannot be changed (the server refuses a move too).
 *                        Defaults to true (Admin behaviour unchanged).
 *   assignees      – the task's assignments ({ id, staffName }); each can be removed (the task has no assignee left → Pending)
 *   onRemoveAssignee – (assignment) => void  — removes that person from the task right away (it is its own request, not part of Save)
 *   removingAssignmentId – the assignment being removed, or null
 */
function EditTaskModal({
  task,
  formData,
  onFormChange,
  onSubmit,
  onClose,
  isSubmitting,
  formError,
  canChangeDepartment = true,
  assignees = [],
  onRemoveAssignee,
  removingAssignmentId = null,
  closing = false,
}) {
  const { departments } = useDepartments();
  // Two-step removal: the first click asks, the second one removes (nothing happens on a stray click)
  const [confirmingId, setConfirmingId] = useState(null);

  if (!task) return null;

  const field = (name) => ({
    value: formData[name] ?? '',
    onChange: (e) => onFormChange(name, e.target.value),
    disabled: isSubmitting,
  });

  return (
    <div
      className={`fixed inset-0 z-50 flex items-center justify-center p-4 bg-gray-900/50 backdrop-blur-xs ${backdropMotion(closing)}`}
      inert={closing}
      onClick={() => !isSubmitting && onClose()}
    >
      <div
        className={`bg-white rounded-2xl max-w-2xl w-full p-6 sm:p-8 shadow-xl border border-gray-100 max-h-[85vh] overflow-y-auto space-y-6 ${dialogMotion(closing)}`}
        onClick={(e) => e.stopPropagation()}
      >
        {/* Header */}
        <div className="flex items-start justify-between border-b border-gray-100 pb-4">
          <div>
            <h2 className="text-xl font-bold text-gray-900">Edit Task</h2>
            <p className="text-sm text-gray-500 mt-0.5">Update task details and operational requirements.</p>
          </div>
          <button
            type="button"
            onClick={onClose}
            disabled={isSubmitting}
            className="text-gray-400 hover:text-gray-600 rounded-lg p-1 transition-colors cursor-pointer disabled:opacity-50"
            title="Close"
          >
            <CloseIcon className="w-5 h-5" />
          </button>
        </div>

        {/* Error banner */}
        {formError && (
          <div className="p-3.5 rounded-xl bg-red-50 border border-red-200 text-red-800 text-sm flex items-center gap-2">
            <svg className="w-5 h-5 flex-shrink-0 text-red-600" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 8v4m0 4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
            </svg>
            <span>{formError}</span>
          </div>
        )}

        <form onSubmit={onSubmit} className="space-y-4">
          {/* Title */}
          <div className="space-y-1.5">
            <label className="text-sm font-medium text-gray-700">Task Title *</label>
            <input
              type="text"
              required
              {...field('title')}
              className="w-full px-4 py-2 border border-gray-200 rounded-xl focus:outline-none focus:ring-2 focus:ring-emerald-500 text-sm"
              placeholder="Task title"
            />
          </div>

          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
            {/* Department */}
            <div className="space-y-1.5">
              <label className="text-sm font-medium text-gray-700">Department *</label>
              <select
                {...field('department')}
                data-testid="edit-task-department"
                disabled={isSubmitting || !canChangeDepartment}
                title={canChangeDepartment ? undefined : DEPARTMENT_CHANGE_LOCKED_HINT}
                className="w-full px-4 py-2 border border-gray-200 rounded-xl focus:outline-none focus:ring-2 focus:ring-emerald-500 text-sm bg-white disabled:bg-gray-50 disabled:text-gray-600"
              >
                <DepartmentOptions departments={departments} activeOnly currentCode={task.department} />
              </select>
              {!canChangeDepartment && <p className="text-xs text-gray-500">{DEPARTMENT_CHANGE_LOCKED_HINT}</p>}
            </div>

            {/* Category */}
            <div className="space-y-1.5">
              <label className="text-sm font-medium text-gray-700">Category</label>
              <select
                {...field('category')}
                className="w-full px-4 py-2 border border-gray-200 rounded-xl focus:outline-none focus:ring-2 focus:ring-emerald-500 text-sm bg-white"
              >
                <option value="Cleaning">Cleaning</option>
                <option value="Maintenance">Maintenance</option>
                <option value="Guest Request">Guest Request</option>
                <option value="Inspection">Inspection</option>
                <option value="Front Desk">Front Desk Assistance</option>
                <option value="Room Service">Room Service</option>
                <option value="Security">Security Patrol</option>
              </select>
            </div>

            {/* Priority */}
            <div className="space-y-1.5">
              <label className="text-sm font-medium text-gray-700">Priority *</label>
              <select
                {...field('priority')}
                className="w-full px-4 py-2 border border-gray-200 rounded-xl focus:outline-none focus:ring-2 focus:ring-emerald-500 text-sm bg-white"
              >
                <option value="LOW">Low</option>
                <option value="MEDIUM">Medium</option>
                <option value="HIGH">High</option>
                <option value="URGENT">Urgent</option>
              </select>
            </div>

            {/* Status */}
            <div className="space-y-1.5">
              <label className="text-sm font-medium text-gray-700">Status *</label>
              <select
                {...field('status')}
                className="w-full px-4 py-2 border border-gray-200 rounded-xl focus:outline-none focus:ring-2 focus:ring-emerald-500 text-sm bg-white"
              >
                <option value="PENDING">Pending</option>
                <option value="ASSIGNED">Assigned</option>
                <option value="IN_PROGRESS">In Progress</option>
                <option value="COMPLETED">Completed</option>
                <option value="CANCELLED">Cancelled</option>
              </select>
            </div>

            {/* Deadline Time */}
            <div className="space-y-1.5">
              <label className="text-sm font-medium text-gray-700">Deadline Time</label>
              <input
                type="time"
                {...field('deadlineTime')}
                className="w-full px-4 py-2 border border-gray-200 rounded-xl focus:outline-none focus:ring-2 focus:ring-emerald-500 text-sm bg-white"
              />
            </div>

            {/* Due Date */}
            <div className="space-y-1.5">
              <label className="text-sm font-medium text-gray-700">Due Date</label>
              <input
                type="date"
                {...field('dueDate')}
                className="w-full px-4 py-2 border border-gray-200 rounded-xl focus:outline-none focus:ring-2 focus:ring-emerald-500 text-sm bg-white"
              />
            </div>

            {/* Estimated Duration */}
            <div className="sm:col-span-2 space-y-1.5">
              <label className="text-sm font-medium text-gray-700">Estimated Duration (minutes) *</label>
              <input
                type="number"
                min="1"
                required
                {...field('estimatedDuration')}
                className="w-full px-4 py-2 border border-gray-200 rounded-xl focus:outline-none focus:ring-2 focus:ring-emerald-500 text-sm"
                placeholder="30"
              />
            </div>
          </div>

          {/* Assigned To */}
          <div className="space-y-1.5" data-testid="edit-task-assignees">
            <label className="text-sm font-medium text-gray-700">Assigned To</label>
            {assignees.length === 0 ? (
              <p className="text-sm text-gray-500">Unassigned. Assign staff from the Actions menu or the Assignments page.</p>
            ) : (
              <ul className="space-y-2">
                {assignees.map((a) => {
                  const removing = removingAssignmentId === a.id;
                  const confirming = confirmingId === a.id;
                  const finished = task.status === 'COMPLETED' || task.status === 'CANCELLED';
                  return (
                    <li key={a.id} className="flex items-center justify-between gap-3 px-4 py-2 border border-gray-200 rounded-xl text-sm">
                      <span className="font-medium text-gray-800 truncate">{a.staffName}</span>
                      {!finished && onRemoveAssignee && (
                        confirming ? (
                          <span className="flex items-center gap-2 flex-shrink-0">
                            <button
                              type="button"
                              disabled={isSubmitting || removing}
                              onClick={() => { setConfirmingId(null); onRemoveAssignee(a); }}
                              className="px-3 py-1 text-xs font-medium text-white bg-red-600 hover:bg-red-700 rounded-lg cursor-pointer disabled:opacity-60"
                            >
                              {removing ? 'Removing...' : 'Confirm remove'}
                            </button>
                            <button
                              type="button"
                              disabled={removing}
                              onClick={() => setConfirmingId(null)}
                              className="px-3 py-1 text-xs font-medium text-gray-600 border border-gray-200 rounded-lg hover:bg-gray-50 cursor-pointer"
                            >
                              Keep
                            </button>
                          </span>
                        ) : (
                          <button
                            type="button"
                            disabled={isSubmitting || removing}
                            onClick={() => setConfirmingId(a.id)}
                            className="px-3 py-1 text-xs font-medium text-red-600 border border-red-200 rounded-lg hover:bg-red-50 cursor-pointer flex-shrink-0 disabled:opacity-60"
                            title={`Remove ${a.staffName} from this task`}
                          >
                            Remove
                          </button>
                        )
                      )}
                    </li>
                  );
                })}
              </ul>
            )}
            {formError && assignees.length > 0 && (
              <p className="text-xs text-red-600" role="alert">{formError}</p>
            )}
            {assignees.length > 0 && onRemoveAssignee && (
              <p className="text-xs text-gray-500">Removing takes effect immediately. If nobody is left, the task goes back to Pending.</p>
            )}
          </div>

          {/* Description */}
          <div className="space-y-1.5">
            <label className="text-sm font-medium text-gray-700">Description</label>
            <textarea
              rows="3"
              {...field('description')}
              className="w-full px-4 py-2 border border-gray-200 rounded-xl focus:outline-none focus:ring-2 focus:ring-emerald-500 text-sm resize-none"
              placeholder="Task instructions and details..."
            />
          </div>

          {/* Footer buttons */}
          <div className="flex items-center justify-end gap-3 pt-4 border-t border-gray-100">
            <button
              type="button"
              onClick={onClose}
              disabled={isSubmitting}
              className="px-5 py-2.5 text-gray-700 font-medium hover:bg-gray-50 border border-gray-200 rounded-xl text-sm transition-colors cursor-pointer"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={isSubmitting}
              className="px-5 py-2.5 bg-emerald-600 hover:bg-emerald-700 active:bg-emerald-800 text-white font-medium rounded-xl text-sm transition-colors cursor-pointer flex items-center gap-2 disabled:opacity-75 disabled:cursor-not-allowed"
            >
              {isSubmitting ? (
                <>
                  <Spinner className="text-white" />
                  <span>Saving Changes...</span>
                </>
              ) : (
                'Save Changes'
              )}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}

// Stays mounted for its 120ms exit after closing (none with reduced motion); see theme/presence.js
export default withExitTransition(EditTaskModal, (props) => Boolean(props.task));
