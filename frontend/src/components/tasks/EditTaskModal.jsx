import React from 'react';
import { CloseIcon } from '../icons/Icons';

/**
 * Inline spinner SVG reused across the submit buttons.
 */
function Spinner() {
  return (
    <svg className="animate-spin h-4 w-4 text-white" fill="none" viewBox="0 0 24 24">
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
 */
export default function EditTaskModal({
  task,
  formData,
  onFormChange,
  onSubmit,
  onClose,
  isSubmitting,
  formError,
}) {
  if (!task) return null;

  const field = (name) => ({
    value: formData[name] ?? '',
    onChange: (e) => onFormChange(name, e.target.value),
    disabled: isSubmitting,
  });

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-gray-900/50 backdrop-blur-xs animate-fadeIn"
      onClick={() => !isSubmitting && onClose()}
    >
      <div
        className="bg-white rounded-2xl max-w-2xl w-full p-6 sm:p-8 shadow-xl border border-gray-100 max-h-[85vh] overflow-y-auto space-y-6"
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
                className="w-full px-4 py-2 border border-gray-200 rounded-xl focus:outline-none focus:ring-2 focus:ring-emerald-500 text-sm bg-white"
              >
                <option value="HOUSEKEEPING">Housekeeping</option>
                <option value="FRONT_DESK">Front Desk</option>
                <option value="MAINTENANCE">Maintenance</option>
                <option value="ROOM_SERVICE">Room Service</option>
                <option value="FOOD_AND_BEVERAGE">Food &amp; Beverage</option>
                <option value="SECURITY">Security</option>
              </select>
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
                  <Spinner />
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
