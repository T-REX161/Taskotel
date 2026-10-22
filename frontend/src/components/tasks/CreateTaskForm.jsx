import React from 'react';
import { formatDepartment } from './taskFormatters';

/**
 * CreateTaskForm — controlled form card presentation for task creation and optional initial assignment.
 *
 * Props:
 *   formData           – object containing task field values
 *   onInputChange      – change event handler for form inputs
 *   staffList          – array of active staff members for assignment
 *   isLoadingStaff     – boolean indicating whether staff list is loading
 *   isSubmitting       – boolean indicating in-flight creation/assignment operation
 *   createdTaskRecord  – record of created task if task creation succeeded but assignment failed
 *   formError          – validation or API error message
 *   onSubmit           – form submit handler
 *   onCancel           – cancellation handler (navigates back to tasks)
 *   onViewTasks        – fallback navigation to tasks list upon partial failure
 */
export default function CreateTaskForm({
  formData,
  onInputChange,
  staffList = [],
  isLoadingStaff = false,
  isSubmitting = false,
  createdTaskRecord = null,
  formError = '',
  onSubmit,
  onCancel,
  onViewTasks,
}) {
  const isTaskLocked = createdTaskRecord !== null;

  return (
    <div className="bg-white rounded-2xl border border-gray-100 shadow-sm p-6 sm:p-8">
      <form onSubmit={onSubmit} className="space-y-6">
        {/* Validation / API Error Banner */}
        {formError && (
          <div className="p-3.5 rounded-xl bg-red-50 border border-red-200 text-red-800 text-sm flex items-start justify-between gap-2.5">
            <div className="flex items-start gap-2.5">
              <svg
                className="w-5 h-5 flex-shrink-0 text-red-600 mt-0.5"
                fill="none"
                viewBox="0 0 24 24"
                stroke="currentColor"
              >
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M12 8v4m0 4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z"
                />
              </svg>
              <span>{formError}</span>
            </div>
            {isTaskLocked && (
              <button
                type="button"
                onClick={onViewTasks}
                className="text-xs font-bold text-red-800 underline whitespace-nowrap ml-2 cursor-pointer hover:text-red-950"
              >
                View Tasks
              </button>
            )}
          </div>
        )}

        <div className="grid grid-cols-1 md:grid-cols-2 gap-5">
          {/* Title */}
          <div className="md:col-span-2 space-y-1.5">
            <label className="text-sm font-medium text-gray-700">Task Title *</label>
            <input
              type="text"
              name="title"
              required
              value={formData.title}
              onChange={onInputChange}
              disabled={isSubmitting || isTaskLocked}
              className="w-full px-4 py-2.5 border border-gray-200 rounded-xl focus:outline-none focus:ring-2 focus:ring-emerald-500 focus:border-transparent text-sm text-gray-900 placeholder-gray-400 disabled:bg-gray-50 disabled:text-gray-500"
              placeholder="e.g. Room 402 Deep Cleaning & Sanitation"
            />
          </div>

          {/* Row 1 - Left: Department */}
          <div className="space-y-1.5">
            <label className="text-sm font-medium text-gray-700">Department *</label>
            <select
              name="department"
              required
              value={formData.department}
              onChange={onInputChange}
              disabled={isSubmitting || isTaskLocked}
              className="w-full px-4 py-2.5 border border-gray-200 rounded-xl focus:outline-none focus:ring-2 focus:ring-emerald-500 focus:border-transparent text-sm text-gray-900 bg-white disabled:bg-gray-50 disabled:text-gray-500"
            >
              <option value="HOUSEKEEPING">Housekeeping</option>
              <option value="FRONT_DESK">Front Desk</option>
              <option value="MAINTENANCE">Maintenance</option>
              <option value="ROOM_SERVICE">Room Service</option>
              <option value="FOOD_AND_BEVERAGE">Food &amp; Beverage (F&amp;B)</option>
              <option value="SECURITY">Security</option>
            </select>
          </div>

          {/* Row 1 - Right: Assign To */}
          <div className="space-y-1.5">
            <label className="text-sm font-medium text-gray-700">Assign To</label>
            <select
              name="assignTo"
              value={formData.assignTo}
              onChange={onInputChange}
              disabled={isSubmitting || isLoadingStaff}
              className="w-full px-4 py-2.5 border border-gray-200 rounded-xl focus:outline-none focus:ring-2 focus:ring-emerald-500 focus:border-transparent text-sm text-gray-900 bg-white disabled:bg-gray-50"
            >
              <option value="">Unassigned</option>
              {staffList.length === 0 ? (
                <option value="" disabled>
                  No available staff in this department
                </option>
              ) : (
                staffList.map((staff) => (
                  <option key={staff.id} value={staff.id}>
                    {staff.name}
                  </option>
                ))
              )}
            </select>
            {isLoadingStaff && (
              <p className="text-xs text-gray-400">Loading staff members...</p>
            )}
            {!isLoadingStaff && staffList.length === 0 && (
              <p className="text-xs text-amber-600 font-medium">
                No available staff in this department
              </p>
            )}
          </div>

          {/* Row 2 - Left: Category */}
          <div className="space-y-1.5">
            <label className="text-sm font-medium text-gray-700">Category</label>
            <select
              name="category"
              value={formData.category}
              onChange={onInputChange}
              disabled={isSubmitting || isTaskLocked}
              className="w-full px-4 py-2.5 border border-gray-200 rounded-xl focus:outline-none focus:ring-2 focus:ring-emerald-500 focus:border-transparent text-sm text-gray-900 bg-white disabled:bg-gray-50 disabled:text-gray-500"
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

          {/* Row 2 - Right: Priority */}
          <div className="space-y-1.5">
            <label className="text-sm font-medium text-gray-700">Priority *</label>
            <select
              name="priority"
              required
              value={formData.priority}
              onChange={onInputChange}
              disabled={isSubmitting || isTaskLocked}
              className="w-full px-4 py-2.5 border border-gray-200 rounded-xl focus:outline-none focus:ring-2 focus:ring-emerald-500 focus:border-transparent text-sm text-gray-900 bg-white disabled:bg-gray-50 disabled:text-gray-500"
            >
              <option value="LOW">Low</option>
              <option value="MEDIUM">Medium</option>
              <option value="HIGH">High</option>
              <option value="URGENT">Urgent</option>
            </select>
          </div>

          {/* Row 3 - Left: Estimated Duration */}
          <div className="space-y-1.5">
            <label className="text-sm font-medium text-gray-700">
              Estimated Duration (minutes) *
            </label>
            <input
              type="number"
              name="estimatedDuration"
              min="1"
              required
              value={formData.estimatedDuration}
              onChange={onInputChange}
              disabled={isSubmitting || isTaskLocked}
              className="w-full px-4 py-2.5 border border-gray-200 rounded-xl focus:outline-none focus:ring-2 focus:ring-emerald-500 focus:border-transparent text-sm text-gray-900 placeholder-gray-400 disabled:bg-gray-50 disabled:text-gray-500"
              placeholder="45"
            />
          </div>

          {/* Row 3 - Right: Status */}
          <div className="space-y-1.5">
            <label className="text-sm font-medium text-gray-700">Status</label>
            <select
              name="status"
              value={formData.assignTo ? 'ASSIGNED' : 'PENDING'}
              disabled
              className="w-full px-4 py-2.5 border border-gray-200 rounded-xl focus:outline-none focus:ring-2 focus:ring-emerald-500 focus:border-transparent text-sm text-gray-600 bg-gray-50 cursor-not-allowed"
              title="Status is automatically set based on staff assignment"
            >
              <option value="PENDING">Pending (Unassigned)</option>
              <option value="ASSIGNED">Assigned</option>
            </select>
            <p className="text-xs text-gray-400">
              Automatically set based on staff assignment.
            </p>
          </div>

          {/* Row 4 - Left: Deadline Time */}
          <div className="space-y-1.5">
            <label className="text-sm font-medium text-gray-700">Deadline Time</label>
            <input
              type="time"
              name="deadlineTime"
              value={formData.deadlineTime || ''}
              onChange={onInputChange}
              disabled={isSubmitting || isTaskLocked}
              className="w-full px-4 py-2.5 border border-gray-200 rounded-xl focus:outline-none focus:ring-2 focus:ring-emerald-500 focus:border-transparent text-sm text-gray-900 bg-white disabled:bg-gray-50 disabled:text-gray-500"
            />
          </div>

          {/* Row 4 - Right: Due Date */}
          <div className="space-y-1.5">
            <label className="text-sm font-medium text-gray-700">Due Date</label>
            <input
              type="date"
              name="dueDate"
              value={formData.dueDate}
              onChange={onInputChange}
              disabled={isSubmitting || isTaskLocked}
              className="w-full px-4 py-2.5 border border-gray-200 rounded-xl focus:outline-none focus:ring-2 focus:ring-emerald-500 focus:border-transparent text-sm text-gray-900 bg-white disabled:bg-gray-50 disabled:text-gray-500"
            />
          </div>

          {/* Description */}
          <div className="md:col-span-2 space-y-1.5">
            <label className="text-sm font-medium text-gray-700">Description</label>
            <textarea
              name="description"
              rows="4"
              value={formData.description}
              onChange={onInputChange}
              disabled={isSubmitting || isTaskLocked}
              className="w-full px-4 py-2.5 border border-gray-200 rounded-xl focus:outline-none focus:ring-2 focus:ring-emerald-500 focus:border-transparent text-sm text-gray-900 placeholder-gray-400 resize-none disabled:bg-gray-50 disabled:text-gray-500"
              placeholder="Add special instructions, room notes, or tools needed for this task..."
            />
          </div>
        </div>

        {/* Form Actions */}
        <div className="pt-4 border-t border-gray-100 flex items-center justify-end gap-3">
          <button
            type="button"
            onClick={onCancel}
            disabled={isSubmitting}
            className="px-5 py-2.5 text-gray-700 font-medium hover:bg-gray-50 border border-gray-200 rounded-xl transition-colors cursor-pointer text-sm"
          >
            Cancel
          </button>
          <button
            type="submit"
            disabled={isSubmitting}
            className="px-5 py-2.5 bg-emerald-600 hover:bg-emerald-700 active:bg-emerald-800 text-white font-medium rounded-xl transition-colors cursor-pointer shadow-xs text-sm flex items-center gap-2 disabled:opacity-75 disabled:cursor-not-allowed"
          >
            {isSubmitting ? (
              <>
                <svg
                  className="animate-spin h-4 w-4 text-white"
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
                <span>
                  {createdTaskRecord ? 'Assigning Staff...' : 'Creating Task...'}
                </span>
              </>
            ) : (
              createdTaskRecord ? 'Assign Staff' : 'Create Task'
            )}
          </button>
        </div>
      </form>
    </div>
  );
}
