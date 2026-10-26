import React, { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { useNavigate } from 'react-router-dom';
import { CloseIcon, TrashIcon } from '../icons/Icons';
import { deleteDepartment } from '../../services/api';

/**
 * Confirms and removes a department through the department API; nothing is simulated.
 * The server refuses (409, with an explanation shown here) while staff or tasks still belong to the department,
 * and for the built-in departments, so no staff, task or assignment is ever deleted or reassigned from here.
 *
 * Props:
 *  - department: the department to remove, or null when closed
 *  - onClose(): close without changes
 *  - onRemoved(department): called after the server removed it
 */
export default function RemoveDepartmentModal({ department, onClose, onRemoved }) {
  const navigate = useNavigate();
  const isOpen = department !== null;
  const [isRemoving, setIsRemoving] = useState(false);
  const [error, setError] = useState('');
  const inFlight = useRef(false);

  // Fresh state each time it opens
  useEffect(() => {
    if (!isOpen) return;
    inFlight.current = false;
    setIsRemoving(false);
    setError('');
  }, [isOpen, department]);

  // Close with Escape (unless the removal is in flight)
  useEffect(() => {
    if (!isOpen) return undefined;
    const onKeyDown = (event) => {
      if (event.key === 'Escape' && !isRemoving) onClose();
    };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [isOpen, isRemoving, onClose]);

  if (!isOpen) return null;

  const handleClose = () => {
    if (isRemoving) return;
    onClose();
  };

  const handleRemove = async () => {
    // The ref blocks a second click immediately; the state only takes effect on the next render
    if (inFlight.current) return;
    inFlight.current = true;
    setIsRemoving(true);
    setError('');
    try {
      await deleteDepartment(department.code);
      inFlight.current = false;
      onRemoved(department);
    } catch (err) {
      inFlight.current = false;
      if (err.message && err.message.includes('Redirecting to login')) {
        navigate('/login');
        return;
      }
      if (err.status === 409) {
        setError(err.message || 'This department is still in use and was not removed.');
      } else if (err.status === 403) {
        setError('Permission denied. Only ADMIN or MANAGER users can remove departments.');
      } else if (err.status === 404) {
        setError('This department no longer exists. Close this dialog and refresh the page.');
      } else if (err.status === 405) {
        // Only a backend built before DELETE /api/departments/{code} existed answers 405 here
        setError('The running server does not accept this request yet (405 Method Not Allowed): it was started before department removal was added. Restart the backend and try again. Nothing was changed.');
      } else {
        setError(err.message || 'The department could not be removed. Please try again.');
      }
      setIsRemoving(false);
    }
  };

  return createPortal(
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 backdrop-blur-sm p-3 sm:p-4 animate-fadeIn"
      onClick={handleClose}
    >
      <div
        role="alertdialog"
        aria-modal="true"
        aria-labelledby="remove-department-title"
        aria-describedby="remove-department-message"
        className="bg-white rounded-2xl shadow-2xl w-full max-w-md border border-gray-100"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="px-5 sm:px-6 pt-5 pb-4 flex items-start gap-3.5">
          <div className="w-11 h-11 rounded-xl bg-red-50 text-red-600 flex items-center justify-center shrink-0">
            <TrashIcon className="w-[22px] h-[22px]" />
          </div>
          <div className="min-w-0 flex-1 pt-0.5">
            <h2 id="remove-department-title" className="text-lg font-bold text-gray-900 leading-tight">
              Remove Department?
            </h2>
            <p id="remove-department-message" className="text-sm text-gray-600 mt-1.5 leading-relaxed">
              Are you sure you want to remove the &quot;<span className="font-semibold text-gray-900 break-words">{department.name}</span>&quot; department? This
              action may affect staff and task assignments associated with it.
            </p>
          </div>
          <button
            type="button"
            onClick={handleClose}
            disabled={isRemoving}
            className="text-gray-500 hover:text-gray-800 transition-colors cursor-pointer shrink-0 -mr-1 disabled:opacity-50"
            aria-label="Close dialog"
          >
            <CloseIcon className="w-6 h-6" />
          </button>
        </div>

        {error && (
          <div className="px-5 sm:px-6 pb-4">
            <div role="alert" className="p-3 rounded-xl bg-red-50 border border-red-200 text-red-800 text-sm flex items-start gap-2.5">
              <svg className="w-5 h-5 flex-shrink-0 text-red-600 mt-0.5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 8v4m0 4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
              </svg>
              <span>{error}</span>
            </div>
          </div>
        )}

        <div className="px-5 sm:px-6 py-3.5 border-t border-gray-100 flex justify-end gap-3">
          <button
            type="button"
            autoFocus
            onClick={handleClose}
            disabled={isRemoving}
            className="px-5 py-2 text-gray-700 text-sm font-medium bg-white hover:bg-gray-50 rounded-lg transition-colors border border-gray-200 cursor-pointer disabled:opacity-50"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={handleRemove}
            disabled={isRemoving}
            className={`px-5 py-2 bg-red-600 hover:bg-red-700 active:bg-red-800 text-white text-sm font-semibold rounded-lg transition-colors inline-flex items-center gap-2 cursor-pointer shadow-sm ${
              isRemoving ? 'opacity-80 cursor-wait' : ''
            }`}
          >
            {isRemoving ? (
              <>
                <svg className="animate-spin h-4 w-4 text-white" fill="none" viewBox="0 0 24 24">
                  <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
                  <path
                    className="opacity-75"
                    fill="currentColor"
                    d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"
                  />
                </svg>
                <span>Removing...</span>
              </>
            ) : (
              <>
                <TrashIcon className="w-4 h-4" />
                Remove Department
              </>
            )}
          </button>
        </div>
      </div>
    </div>,
    document.body
  );
}
