import React from 'react';

/**
 * DeleteTaskModal — confirmation modal for deleting a task.
 * Accessible to ADMIN and MANAGER only.
 *
 * Props:
 *   task         – the task object to delete
 *   isAssigned   – boolean indicating whether the task has an active assignment
 *   onConfirm    – callback to execute deletion
 *   onClose      – callback to dismiss the modal
 *   isSubmitting – boolean indicating in-flight delete operation
 *   error        – error string to display in red banner
 */
export default function DeleteTaskModal({
  task,
  isAssigned,
  onConfirm,
  onClose,
  isSubmitting,
  error,
}) {
  if (!task) return null;

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-gray-900/50 backdrop-blur-xs animate-fadeIn"
      onClick={() => !isSubmitting && onClose()}
    >
      <div
        className="bg-white rounded-2xl max-w-md w-full p-6 shadow-xl border border-gray-100 max-h-[85vh] overflow-y-auto space-y-5"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center gap-3 text-red-600">
          <div className="w-10 h-10 rounded-xl bg-red-100 flex items-center justify-center flex-shrink-0">
            <svg className="w-5 h-5 text-red-600" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" />
            </svg>
          </div>
          <div>
            <h3 className="text-lg font-bold text-gray-900">Delete Task</h3>
            <p className="text-xs text-gray-500">This action cannot be undone.</p>
          </div>
        </div>

        <p className="text-sm text-gray-600 leading-relaxed">
          Are you sure you want to delete <span className="font-semibold text-gray-900">"{task.title}"</span>?
          {isAssigned && (
            <span className="block mt-2 text-xs text-amber-700 bg-amber-50 p-2.5 rounded-lg border border-amber-200">
              Note: This task is currently assigned. Its assignment record will also be safely removed.
            </span>
          )}
        </p>

        {error && (
          <div className="p-3.5 rounded-xl bg-red-50 border border-red-200 text-red-800 text-xs flex items-start gap-2">
            <svg className="w-4 h-4 text-red-600 flex-shrink-0 mt-0.5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 8v4m0 4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
            </svg>
            <span>{error}</span>
          </div>
        )}

        <div className="flex items-center justify-end gap-3 pt-2">
          <button
            type="button"
            onClick={onClose}
            disabled={isSubmitting}
            className="px-4 py-2 text-gray-700 font-medium hover:bg-gray-50 border border-gray-200 rounded-xl text-sm transition-colors cursor-pointer disabled:opacity-50"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={onConfirm}
            disabled={isSubmitting}
            className="px-4 py-2 bg-red-600 hover:bg-red-700 active:bg-red-800 text-white font-medium rounded-xl text-sm transition-colors cursor-pointer flex items-center gap-2 disabled:opacity-75 disabled:cursor-not-allowed"
          >
            {isSubmitting ? (
              <>
                <svg className="animate-spin h-4 w-4 text-white" fill="none" viewBox="0 0 24 24">
                  <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
                  <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z" />
                </svg>
                <span>Deleting...</span>
              </>
            ) : (
              'Delete Task'
            )}
          </button>
        </div>
      </div>
    </div>
  );
}
