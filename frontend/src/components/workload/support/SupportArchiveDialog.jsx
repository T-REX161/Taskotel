import React, { useEffect, useRef } from 'react';
import { formatDate, formatTime } from '../../../utils/dateTimeFormat';
import { getPreferences } from '../../../utils/preferences';
import { supportRecordDisplay } from '../../../utils/supportPlan';

const FOCUSABLE = 'button:not([disabled])';

const formatWhen = (value) => {
  if (!value) return '';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '';
  const { dateFormat, timeFormat } = getPreferences();
  return `${formatDate(date, dateFormat)}, ${formatTime(date, timeFormat)}`;
};

const Detail = ({ label, children }) => (
  <div className="min-w-0">
    <dt className="text-[11px] font-semibold uppercase tracking-wide text-gray-500">{label}</dt>
    <dd className="mt-0.5 text-sm text-gray-900 break-words">{children}</dd>
  </div>
);

/**
 * Confirms removing one ended record from the history. The record is archived, not deleted. Nothing happens until
 * "Remove" is pressed; Escape and Cancel close it without changes (but not while the request is running). A failure keeps
 * it open and says why.
 */
export default function SupportArchiveDialog({ row, departmentName, status, error, stale, onConfirm, onCancel, view = 'full' }) {
  const dialogRef = useRef(null);
  const cancelRef = useRef(null);
  const previousFocus = useRef(null);
  const submitting = status === 'submitting';

  useEffect(() => {
    previousFocus.current = document.activeElement;
    cancelRef.current?.focus();
    return () => previousFocus.current?.focus?.();
  }, []);

  useEffect(() => {
    const onKey = (event) => {
      if (event.key === 'Escape' && !submitting) {
        onCancel();
        return;
      }
      if (event.key === 'Tab' && dialogRef.current) {
        const items = [...dialogRef.current.querySelectorAll(FOCUSABLE)];
        if (items.length === 0) return;
        const first = items[0];
        const last = items[items.length - 1];
        if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
        else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
      }
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [onCancel, submitting]);

  // Only what the server sent for this viewer: a lending department's Manager gets no receiving department, task or end reason
  const shown = supportRecordDisplay(row, view, departmentName);
  const reason = shown.endReason;
  const focusRing = 'focus:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 focus-visible:ring-offset-1';
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-3 sm:p-4" onClick={() => !submitting && onCancel()}>
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby="support-archive-title"
        aria-describedby="support-archive-body"
        className="w-full max-w-md max-h-[90vh] overflow-y-auto rounded-2xl bg-white border border-gray-100 shadow-xl p-4 sm:p-5 space-y-4"
        onClick={(e) => e.stopPropagation()}
      >
        <div>
          <h3 id="support-archive-title" className="text-base font-bold text-gray-900">Remove from history?</h3>
          <p id="support-archive-body" className="text-xs text-gray-500 mt-0.5">This record will no longer appear in the support history.</p>
        </div>

        <dl className="grid grid-cols-1 sm:grid-cols-2 gap-x-4 gap-y-3 rounded-xl bg-gray-50 border border-gray-200 p-3">
          <Detail label="Staff member">{row.staffName}</Detail>
          <Detail label="Department">
            {view === 'donor' ? <>{shown.home} <span className="block text-xs text-gray-500">{shown.loanNote}</span></> : `${shown.home} → ${shown.receiving}`}
          </Detail>
          <div className="sm:col-span-2 min-w-0">
            <dt className="text-[11px] font-semibold uppercase tracking-wide text-gray-500">Task</dt>
            <dd className="mt-0.5 text-sm text-gray-900 break-words">{shown.taskRestricted ? <span className="text-gray-500 italic">{shown.task}</span> : row.taskTitle}</dd>
          </div>
          <Detail label="Started">{formatWhen(row.createdAt) || '–'}</Detail>
          <Detail label="Ended">{formatWhen(row.completedAt) || '–'}{reason ? <span className="block text-xs text-gray-500">{reason}</span> : null}</Detail>
        </dl>

        <p className="text-xs text-gray-600">
          The record is archived, not deleted. The task, its assignment, the staff member and their permanent department are not changed.
        </p>

        {status === 'error' && (
          <div role="alert" className="rounded-xl border border-red-200 bg-red-50 p-3 text-sm text-red-800">
            <p className="font-semibold">The record was not removed.</p>
            <p className="text-xs mt-0.5">{error}</p>
            {stale && <p className="text-xs mt-0.5">The history behind this window has been refreshed with the latest state.</p>}
          </div>
        )}

        <div className="flex flex-col-reverse sm:flex-row sm:justify-end gap-2">
          <button
            ref={cancelRef}
            type="button"
            disabled={submitting}
            onClick={onCancel}
            className={`px-4 py-2 text-sm font-medium text-gray-700 border border-gray-300 rounded-lg hover:bg-gray-50 cursor-pointer disabled:opacity-50 ${focusRing}`}
          >
            {stale && status === 'error' ? 'Close' : 'Cancel'}
          </button>
          {!(stale && status === 'error') && (
            <button
              type="button"
              disabled={submitting}
              onClick={onConfirm}
              className={`px-4 py-2 text-sm font-medium text-white bg-emerald-600 hover:bg-emerald-700 rounded-lg cursor-pointer disabled:opacity-60 disabled:cursor-wait ${focusRing}`}
            >
              {submitting ? 'Removing…' : status === 'error' ? 'Try again' : 'Remove'}
            </button>
          )}
        </div>
      </div>
    </div>
  );
}
