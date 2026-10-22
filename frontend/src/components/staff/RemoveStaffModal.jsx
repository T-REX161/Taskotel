import React, { useEffect, useRef } from 'react';
import { createPortal } from 'react-dom';

/**
 * RemoveStaffModal — confirmation modal when permanently removing an inactive staff member.
 *
 * Props:
 *   isOpen       – boolean indicating if the modal is open
 *   staff        – the staff member object to remove
 *   onClose      – callback to dismiss modal without making API request
 *   onConfirm    – callback to execute removal API request
 *   isSubmitting – boolean indicating in-flight removal request
 *   error        – error string to display in banner if API request fails
 */
export default function RemoveStaffModal({
  isOpen,
  staff,
  onClose,
  onConfirm,
  isSubmitting = false,
  error = '',
}) {
  const cancelButtonRef = useRef(null);
  const dialogRef = useRef(null);

  useEffect(() => {
    if (!isOpen || !staff) return;

    const previouslyFocused = document.activeElement;

    // Focus cancel button for safe keyboard navigation on destructive confirmation
    const timer = setTimeout(() => {
      cancelButtonRef.current?.focus();
    }, 50);

    const handleKeyDown = (e) => {
      if (e.key === 'Escape') {
        if (!isSubmitting) {
          e.preventDefault();
          onClose();
        }
      } else if (e.key === 'Tab') {
        if (!dialogRef.current) return;
        const focusable = dialogRef.current.querySelectorAll(
          'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'
        );
        if (focusable.length === 0) return;
        const first = focusable[0];
        const last = focusable[focusable.length - 1];

        if (e.shiftKey) {
          if (document.activeElement === first) {
            e.preventDefault();
            last.focus();
          }
        } else {
          if (document.activeElement === last) {
            e.preventDefault();
            first.focus();
          }
        }
      }
    };

    document.addEventListener('keydown', handleKeyDown);
    return () => {
      clearTimeout(timer);
      document.removeEventListener('keydown', handleKeyDown);
      if (previouslyFocused && typeof previouslyFocused.focus === 'function') {
        previouslyFocused.focus();
      }
    };
  }, [isOpen, staff, isSubmitting, onClose]);

  if (!isOpen || !staff) return null;

  const staffName = staff.fullName || staff.name || 'this staff member';

  const modalContent = (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-gray-900/50 backdrop-blur-xs animate-fadeIn"
      onClick={() => !isSubmitting && onClose()}
    >
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby="remove-staff-modal-title"
        aria-describedby="remove-staff-modal-desc"
        className="bg-white rounded-2xl max-w-md w-full p-6 shadow-xl border border-gray-100 max-h-[85vh] overflow-y-auto space-y-5"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Header with red trash / warning badge */}
        <div className="flex items-center gap-3 text-red-600">
          <div className="w-10 h-10 rounded-xl bg-red-100 flex items-center justify-center flex-shrink-0">
            <svg
              className="w-5 h-5 text-red-600"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
              strokeLinecap="round"
              strokeLinejoin="round"
              aria-hidden="true"
            >
              <path d="M3 6h18" />
              <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6" />
              <path d="M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2" />
              <line x1="10" y1="11" x2="10" y2="17" />
              <line x1="14" y1="11" x2="14" y2="17" />
            </svg>
          </div>
          <div>
            <h3
              id="remove-staff-modal-title"
              className="text-lg font-bold text-gray-900"
            >
              Remove Staff Member?
            </h3>
            <p className="text-xs text-gray-500">Permanent Removal Confirmation</p>
          </div>
        </div>

        {/* Message */}
        <p
          id="remove-staff-modal-desc"
          className="text-sm text-gray-600 leading-relaxed"
        >
          Are you sure you want to permanently remove{' '}
          <span className="font-semibold text-gray-900">{staffName}</span>?
          {' '}This action cannot be undone and will permanently delete this staff member and their account.
        </p>

        {/* Error banner */}
        {error && (
          <div
            role="alert"
            className="p-3.5 rounded-xl bg-red-50 border border-red-200 text-red-800 text-xs flex items-start gap-2"
          >
            <svg
              className="w-4 h-4 text-red-600 flex-shrink-0 mt-0.5"
              fill="none"
              viewBox="0 0 24 24"
              stroke="currentColor"
              aria-hidden="true"
            >
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M12 8v4m0 4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z"
              />
            </svg>
            <span>{error}</span>
          </div>
        )}

        {/* Actions */}
        <div className="flex items-center justify-end gap-3 pt-2">
          <button
            ref={cancelButtonRef}
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
                <svg
                  className="animate-spin h-4 w-4 text-white"
                  fill="none"
                  viewBox="0 0 24 24"
                  aria-hidden="true"
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
                <span>Removing...</span>
              </>
            ) : (
              'Remove Staff'
            )}
          </button>
        </div>
      </div>
    </div>
  );

  return typeof document !== 'undefined'
    ? createPortal(modalContent, document.body)
    : modalContent;
}
