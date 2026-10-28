import React, { useEffect, useRef } from 'react';

const FOCUSABLE = 'button:not([disabled])';

/**
 * Asks before clearing the department selection while an unconfirmed draft plan exists. Discarding changes nothing on the
 * server: a draft has no assignments yet. Escape, the backdrop and "Keep plan" all keep the draft.
 */
export default function SupportDiscardDialog({ departmentName, names, count, onKeep, onDiscard }) {
  const dialogRef = useRef(null);
  const keepRef = useRef(null);
  const previousFocus = useRef(null);

  useEffect(() => {
    previousFocus.current = document.activeElement;
    keepRef.current?.focus();
    return () => previousFocus.current?.focus?.();
  }, []);

  useEffect(() => {
    const onKey = (event) => {
      if (event.key === 'Escape') {
        onKeep();
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
  }, [onKeep]);

  const focusRing = 'focus:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 focus-visible:ring-offset-1';
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-3 sm:p-4" onClick={onKeep}>
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby="support-discard-title"
        aria-describedby="support-discard-body"
        className="w-full max-w-md max-h-[90vh] overflow-y-auto rounded-2xl bg-white border border-gray-100 shadow-xl p-4 sm:p-5 space-y-4"
        onClick={(e) => e.stopPropagation()}
      >
        <div>
          <h3 id="support-discard-title" className="text-base font-bold text-gray-900">Discard this draft plan?</h3>
          <p id="support-discard-body" className="text-xs text-gray-500 mt-0.5">
            Clearing the selection removes your unconfirmed plan for {departmentName || 'this department'}.
          </p>
        </div>
        <div className="rounded-xl bg-gray-50 border border-gray-200 p-3 text-sm text-gray-700 space-y-1.5">
          <p className="font-medium text-gray-900">{count} helper{count === 1 ? '' : 's'} in the draft</p>
          {names.length > 0 && <p className="text-xs text-gray-600 break-words">{names.join(', ')}</p>}
          <p className="text-xs text-gray-500">Nothing has been assigned for this draft, so no assignment, task or confirmed support changes.</p>
        </div>
        <div className="flex flex-col-reverse sm:flex-row sm:justify-end gap-2">
          <button
            type="button"
            onClick={onDiscard}
            className={`px-4 py-2 text-sm font-medium text-red-700 border border-gray-300 rounded-lg hover:border-red-200 hover:bg-red-50 cursor-pointer ${focusRing}`}
          >
            Discard draft
          </button>
          <button
            ref={keepRef}
            type="button"
            onClick={onKeep}
            className={`px-4 py-2 text-sm font-medium text-white bg-emerald-600 hover:bg-emerald-700 rounded-lg cursor-pointer ${focusRing}`}
          >
            Keep plan
          </button>
        </div>
      </div>
    </div>
  );
}
