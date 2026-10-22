import React from 'react';
import { CloseIcon } from '../icons/Icons';
import {
  getPriorityColor,
  formatPriority,
  getStatusColor,
  formatStatus,
  formatDepartment,
  formatDueDate,
  formatDuration,
} from './taskFormatters';

/**
 * TaskDetailsModal — read-only view of a task's full detail.
 * Accessible to all authenticated roles.
 *
 * Props:
 *   task              – the task object to display
 *   assignmentsByTaskId – Map<taskId, assignment> from TasksPage
 *   onClose           – callback to dismiss the modal
 */
export default function TaskDetailsModal({ task, assignmentsByTaskId, onClose }) {
  if (!task) return null;

  const assignedStaffName = assignmentsByTaskId.get(task.id)?.staffName;

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-gray-900/50 backdrop-blur-xs animate-fadeIn"
      onClick={onClose}
    >
      <div
        className="bg-white rounded-2xl max-w-2xl w-full p-6 sm:p-8 shadow-xl border border-gray-100 max-h-[85vh] overflow-y-auto space-y-6"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Header */}
        <div className="flex items-start justify-between border-b border-gray-100 pb-4">
          <div>
            <span className="text-xs font-semibold text-emerald-700 bg-emerald-50 px-2.5 py-1 rounded-full uppercase tracking-wider">
              Task Details
            </span>
            <h2 className="text-xl font-bold text-gray-900 mt-2">{task.title}</h2>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="text-gray-400 hover:text-gray-600 rounded-lg p-1 transition-colors cursor-pointer"
            title="Close"
          >
            <CloseIcon className="w-5 h-5" />
          </button>
        </div>

        {/* Detail grid */}
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-4 text-sm">
          <div className="bg-gray-50/70 p-4 rounded-xl border border-gray-100">
            <span className="text-xs font-medium text-gray-500 uppercase">Department</span>
            <p className="font-semibold text-gray-900 mt-1">{formatDepartment(task.department)}</p>
          </div>

          <div className="bg-gray-50/70 p-4 rounded-xl border border-gray-100">
            <span className="text-xs font-medium text-gray-500 uppercase">Category</span>
            <p className="font-semibold text-gray-900 mt-1">{task.category || 'None'}</p>
          </div>

          <div className="bg-gray-50/70 p-4 rounded-xl border border-gray-100">
            <span className="text-xs font-medium text-gray-500 uppercase">Priority</span>
            <div className="mt-1">
              <span className={`inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium ${getPriorityColor(task.priority)}`}>
                {formatPriority(task.priority)}
              </span>
            </div>
          </div>

          <div className="bg-gray-50/70 p-4 rounded-xl border border-gray-100">
            <span className="text-xs font-medium text-gray-500 uppercase">Status</span>
            <div className="mt-1">
              <span className={`inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium ${getStatusColor(task.status)}`}>
                {formatStatus(task.status)}
              </span>
            </div>
          </div>

          <div className="bg-gray-50/70 p-4 rounded-xl border border-gray-100">
            <span className="text-xs font-medium text-gray-500 uppercase">Due Date</span>
            <p className="font-semibold text-gray-900 mt-1">{formatDueDate(task.dueDate, task.deadlineTime)}</p>
          </div>

          <div className="bg-gray-50/70 p-4 rounded-xl border border-gray-100">
            <span className="text-xs font-medium text-gray-500 uppercase">Estimated Duration</span>
            <p className="font-semibold text-gray-900 mt-1">
              {formatDuration(task.estimatedDuration, 'Not specified')}
            </p>
          </div>

          <div className="sm:col-span-2 bg-emerald-50/50 p-4 rounded-xl border border-emerald-100">
            <span className="text-xs font-medium text-emerald-800 uppercase">Assigned Staff Member</span>
            <p className="font-bold text-gray-900 mt-1 text-base">
              {assignedStaffName ?? (
                <span className="text-gray-400 font-normal italic">Unassigned</span>
              )}
            </p>
          </div>

          <div className="sm:col-span-2 bg-gray-50/70 p-4 rounded-xl border border-gray-100 space-y-1">
            <span className="text-xs font-medium text-gray-500 uppercase">Description</span>
            <p className="text-gray-700 whitespace-pre-wrap leading-relaxed mt-1">
              {task.description || <span className="italic text-gray-400">No description provided.</span>}
            </p>
          </div>
        </div>

        {/* Footer */}
        <div className="flex justify-end pt-2 border-t border-gray-100">
          <button
            type="button"
            onClick={onClose}
            className="px-5 py-2.5 bg-gray-100 hover:bg-gray-200 text-gray-700 font-medium rounded-xl text-sm transition-colors cursor-pointer"
          >
            Close
          </button>
        </div>
      </div>
    </div>
  );
}
