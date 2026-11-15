import React, { useEffect, useRef } from 'react';
import { createPortal } from 'react-dom';
import { backdropMotion, dialogMotion, withExitTransition } from '../../theme/presence';
import Spinner from '../common/Spinner';

/**
 * DeactivateStaffModal — confirmation modal when deactivating a staff member.
 *
 * Props:
 *   isOpen       – boolean indicating if the modal is open
 *   staff        – the staff member object to deactivate
 *   onClose      – callback to dismiss modal without making API request
 *   onConfirm    – callback to execute deactivation API request
 *   isSubmitting – boolean indicating in-flight deactivation request
 *   error        – error string to display in banner if API request fails
 */
function DeactivateStaffModal({
  isOpen,
  staff,
  onClose,
  onConfirm,
  isSubmitting = false,
  error = '',
  closing = false,
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
      className={`fixed inset-0 z-50 flex items-center justify-center p-4 bg-gray-900/50 backdrop-blur-xs ${backdropMotion(closing)}`}
      inert={closing}
      onClick={() => !isSubmitting && onClose()}
    >
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby="deactivate-staff-modal-title"
        aria-describedby="deactivate-staff-modal-desc"
        className={`bg-white rounded-2xl max-w-md w-full p-6 shadow-xl border border-gray-100 max-h-[85vh] overflow-y-auto space-y-5 ${dialogMotion(closing)}`}
        onClick={(e) => e.stopPropagation()}
      >
        {/* Header with red warning/deactivation badge */}
        <div className="flex items-center gap-3 text-red-600">
          <div className="w-10 h-10 rounded-xl bg-red-100 flex items-center justify-center flex-shrink-0">
            <svg
              className="w-5 h-5 text-red-600"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
              aria-hidden="true"
            >
              <circle cx="12" cy="12" r="10" />
              <line x1="4.93" y1="4.93" x2="19.07" y2="19.07" />
            </svg>
          </div>
          <div>
            <h3
              id="deactivate-staff-modal-title"
              className="text-lg font-bold text-gray-900"
            >
              Deactivate Staff Member?
            </h3>
            <p className="text-xs text-gray-500">Deactivation Confirmation</p>
          </div>
        </div>

        {/* Message */}
        <p
          id="deactivate-staff-modal-desc"
          className="text-sm text-gray-600 leading-relaxed"
        >
          Are you sure you want to deactivate{' '}
          <span className="font-semibold text-gray-900">{staffName}</span>?
          {' '}They will no longer be eligible for new task assignments. You can reactivate them later if needed.
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
                <Spinner className="text-white" />
                <span>Deactivating...</span>
              </>
            ) : (
              'Deactivate Staff'
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

// Stays mounted for its 120ms exit after closing (none with reduced motion); see theme/presence.js
export default withExitTransition(DeactivateStaffModal, (props) => Boolean(props.isOpen && props.staff));
