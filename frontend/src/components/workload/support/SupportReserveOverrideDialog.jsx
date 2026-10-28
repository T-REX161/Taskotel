import React, { useEffect, useRef } from 'react';

const FOCUSABLE = 'button:not([disabled])';

/**
 * Shown before a manager may use up a department's reserve (its last free staff member). Continue only records the
 * acknowledgement in the draft and re-previews it: nothing is assigned or confirmed. Cancel, Escape and the backdrop leave
 * the draft exactly as it was.
 * `rows` is [{ donorName, personName, freeNow, freeAfter }].
 */
export default function SupportReserveOverrideDialog({ rows, onCancel, onContinue }) {
  const dialogRef = useRef(null);
  const cancelRef = useRef(null);
  const previousFocus = useRef(null);

  useEffect(() => {
    previousFocus.current = document.activeElement;
    cancelRef.current?.focus();
    return () => previousFocus.current?.focus?.();
  }, []);

  useEffect(() => {
    const onKey = (event) => {
      if (event.key === 'Escape') {
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
  }, [onCancel]);

  const focusRing = 'focus:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 focus-visible:ring-offset-1';
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-3 sm:p-4" onClick={onCancel}>
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby="support-risk-title"
        aria-describedby="support-risk-body"
        className="w-full max-w-md max-h-[90vh] overflow-y-auto rounded-2xl bg-white border border-gray-100 shadow-xl p-4 sm:p-5 space-y-4"
        onClick={(e) => e.stopPropagation()}
      >
        <div>
          <h3 id="support-risk-title" className="text-base font-bold text-gray-900">Use the last free staff member?</h3>
          <p id="support-risk-body" className="text-xs text-gray-500 mt-0.5">
            A department normally keeps one free person in reserve. This plan would use it up.
          </p>
        </div>

        <div role="group" aria-label="Staffing risk" className="rounded-xl border border-amber-300 bg-amber-50 p-3 space-y-2">
          <p className="flex items-center gap-1.5 text-xs font-semibold text-amber-900">
            <svg aria-hidden="true" viewBox="0 0 20 20" fill="currentColor" className="w-4 h-4 shrink-0"><path fillRule="evenodd" d="M8.26 3.1c.75-1.34 2.73-1.34 3.48 0l6.1 10.9c.74 1.33-.22 2.99-1.74 2.99H3.9c-1.52 0-2.48-1.66-1.74-2.99l6.1-10.9zM10 7a1 1 0 00-1 1v3a1 1 0 102 0V8a1 1 0 00-1-1zm0 7.5a1.1 1.1 0 100-2.2 1.1 1.1 0 000 2.2z" clipRule="evenodd" /></svg>
            Staffing risk
          </p>
          <ul className="space-y-1.5 text-sm text-amber-950">
            {rows.map((row) => (
              <li key={row.donorName}>
                <span className="font-semibold">{row.donorName}</span> has {row.freeNow} free staff
                {row.personName ? <> and {row.personName} is one of them</> : null}. After this loan it would have{' '}
                <span className="font-semibold">{Math.max(0, row.freeAfter)} free</span>, so it may be unable to take new work.
              </li>
            ))}
          </ul>
        </div>

        <p className="text-xs text-gray-500">
          This is temporary support: the person stays in their home department. Continuing does not assign anything. You will
          still preview the plan and confirm it before it takes effect, and everything is checked again at that moment.
        </p>

        <div className="flex flex-col-reverse sm:flex-row sm:justify-end gap-2">
          <button
            ref={cancelRef}
            type="button"
            onClick={onCancel}
            className={`px-4 py-2 text-sm font-medium text-gray-700 border border-gray-300 rounded-lg hover:bg-gray-50 cursor-pointer ${focusRing}`}
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={onContinue}
            className={`px-4 py-2 text-sm font-medium text-white bg-emerald-600 hover:bg-emerald-700 rounded-lg cursor-pointer ${focusRing}`}
          >
            Continue
          </button>
        </div>
      </div>
    </div>
  );
}
