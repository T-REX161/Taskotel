import React, { useEffect, useRef } from 'react';
import { describeEndEffect } from '../../../utils/supportPlan';
import SupportStageBadge from './SupportStageBadge';

const FOCUSABLE = 'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

const initialsOf = (name) => {
  const parts = String(name || '').trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return '??';
  return (parts.length === 1 ? parts[0].slice(0, 2) : parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
};

const Detail = ({ label, children }) => (
  <div className="min-w-0">
    <dt className="text-[11px] font-semibold uppercase tracking-wide text-gray-500">{label}</dt>
    <dd className="mt-0.5 text-sm text-gray-900 break-words">{children}</dd>
  </div>
);

/**
 * Confirms ending one active support. Nothing changes until "End support" is pressed; Escape and Cancel close it without
 * changes (but not while the request is running). On a failure it stays open and says why.
 */
export default function SupportEndDialog({ row, departmentName, status, error, stale, onConfirm, onCancel }) {
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
      // Keep Tab inside the dialog while it is open
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

  const effect = describeEndEffect(row, departmentName);
  const cannotEnd = effect.blocked || stale;
  const focusRing = 'focus:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 focus-visible:ring-offset-1';

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-3 sm:p-4" onClick={() => !submitting && onCancel()}>
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby="support-end-title"
        aria-describedby="support-end-subtitle"
        className="w-full max-w-md max-h-[90vh] overflow-y-auto rounded-2xl bg-white border border-gray-100 shadow-xl p-4 sm:p-5 space-y-4"
        onClick={(e) => e.stopPropagation()}
      >
        <div>
          <h3 id="support-end-title" className="text-base font-bold text-gray-900">End temporary support</h3>
          <p id="support-end-subtitle" className="text-xs text-gray-500 mt-0.5">Review the details before you confirm.</p>
        </div>

        {/* Who */}
        <div className="flex items-center gap-3 rounded-xl bg-emerald-50 border border-emerald-100 p-3">
          <div aria-hidden="true" className="w-10 h-10 rounded-full bg-white text-emerald-700 border border-emerald-200 font-bold text-sm flex items-center justify-center shrink-0 select-none">
            {initialsOf(row.staffName)}
          </div>
          <div className="min-w-0 flex-1">
            <p className="text-sm font-semibold text-gray-900 truncate">{row.staffName}</p>
            <p className="text-xs text-gray-500">On temporary support</p>
          </div>
          <SupportStageBadge stage="active" />
        </div>

        {/* Where and what */}
        <dl className="grid grid-cols-1 sm:grid-cols-2 gap-x-4 gap-y-3 px-1">
          <Detail label="Permanent department">
            {departmentName(row.homeDepartmentCode)} <span className="text-xs text-gray-500">(unchanged)</span>
          </Detail>
          <Detail label="Helping">{departmentName(row.receivingDepartmentCode)}</Detail>
          <div className="sm:col-span-2 min-w-0">
            <dt className="text-[11px] font-semibold uppercase tracking-wide text-gray-500">Assigned task</dt>
            <dd className="mt-0.5 text-sm text-gray-900 break-words">{row.taskTitle}</dd>
          </div>
        </dl>

        {/* What happens */}
        <section
          aria-label="What ending support does"
          className={`rounded-xl border p-3 ${effect.blocked ? 'bg-amber-50 border-amber-200' : 'bg-emerald-50/60 border-emerald-100'}`}
        >
          <p className={`flex items-center gap-1.5 text-xs font-semibold ${effect.blocked ? 'text-amber-800' : 'text-emerald-800'}`}>
            <svg aria-hidden="true" viewBox="0 0 20 20" fill="currentColor" className="w-4 h-4 shrink-0">
              <path fillRule="evenodd" d="M18 10a8 8 0 11-16 0 8 8 0 0116 0zm-7-4a1 1 0 11-2 0 1 1 0 012 0zM9 9a1 1 0 000 2v3a1 1 0 001 1h1a1 1 0 100-2v-3a1 1 0 00-1-1H9z" clipRule="evenodd" />
            </svg>
            {effect.blocked ? 'This support cannot be ended yet' : 'What ending support does'}
          </p>
          {effect.blocked ? (
            <p className="mt-1.5 text-xs text-gray-700">{effect.text}</p>
          ) : (
            <ul className="mt-1.5 space-y-1 text-xs text-gray-700 list-disc pl-5 marker:text-emerald-600">
              {effect.points.map((point) => (<li key={point}>{point}</li>))}
            </ul>
          )}
        </section>

        {status === 'error' && (
          <div role="alert" className="rounded-xl border border-red-200 bg-red-50 p-3 text-sm text-red-800">
            <p className="font-semibold">Support was not ended.</p>
            <p className="text-xs mt-0.5">{error}</p>
            {stale && <p className="text-xs mt-0.5">The list behind this window has been refreshed with the latest state.</p>}
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
            {cannotEnd && status === 'error' ? 'Close' : 'Cancel'}
          </button>
          {!cannotEnd && (
            <button
              type="button"
              disabled={submitting}
              onClick={onConfirm}
              className={`px-4 py-2 text-sm font-medium text-white bg-emerald-600 hover:bg-emerald-700 rounded-lg cursor-pointer disabled:opacity-60 disabled:cursor-wait ${focusRing}`}
            >
              {submitting ? 'Ending…' : status === 'error' ? 'Try again' : 'End support'}
            </button>
          )}
        </div>
      </div>
    </div>
  );
}
