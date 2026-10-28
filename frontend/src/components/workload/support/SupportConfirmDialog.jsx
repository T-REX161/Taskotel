import React, { useEffect, useRef } from 'react';
import { formatMinutes } from '../../../utils/supportPlan';

/**
 * Step 6: nothing is created until the manager explicitly confirms here. Escape and Cancel close it without changes.
 */
export default function SupportConfirmDialog({ preview, receivingName, submitting, onConfirm, onCancel }) {
  const cancelRef = useRef(null);
  const previousFocus = useRef(null);

  useEffect(() => {
    previousFocus.current = document.activeElement;
    cancelRef.current?.focus();
    const onKey = (event) => {
      if (event.key === 'Escape' && !submitting) onCancel();
    };
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('keydown', onKey);
      previousFocus.current?.focus?.();
    };
  }, [onCancel, submitting]);

  const items = preview.items;
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-3 sm:p-4" onClick={() => !submitting && onCancel()}>
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby="support-confirm-title"
        className="w-full max-w-lg max-h-[90vh] overflow-y-auto rounded-2xl bg-white border border-gray-100 shadow-xl p-5 space-y-3"
        onClick={(e) => e.stopPropagation()}
      >
        <h3 id="support-confirm-title" className="text-base font-bold text-gray-900">Confirm temporary support for {receivingName}</h3>
        <p className="text-sm text-gray-600">
          This creates {items.length} real task assignment{items.length === 1 ? '' : 's'}. Each person helps until their task is completed and stays in their home department.
        </p>
        <ul className="divide-y divide-gray-100 rounded-lg border border-gray-100">
          {items.map((item) => (
            <li key={item.staffId} className="p-2.5 text-sm">
              <span className="font-medium text-gray-900">{item.staffName}</span>
              <span className="text-gray-500"> ({item.homeDepartmentName}) → </span>
              <span className="text-gray-900">{item.taskTitle}</span>
              <span className="text-gray-500"> · {formatMinutes(item.taskMinutes)}</span>
            </li>
          ))}
        </ul>
        {preview.warnings?.length > 0 && (
          <div role="group" aria-label="Staffing risk" className="rounded-lg border border-amber-300 bg-amber-50 p-3 text-xs text-amber-900">
            <p className="font-semibold">Staffing risk you acknowledged</p>
            <ul className="list-disc pl-4 mt-1 space-y-0.5 marker:text-amber-600">
              {preview.warnings.map((warning) => (<li key={warning.message}>{warning.message}</li>))}
            </ul>
          </div>
        )}
        <p className="text-xs text-gray-500">
          Everything is checked again on the server when you confirm. If staff or tasks changed since the preview, nothing is created and you will see why.
        </p>
        <div className="flex justify-end gap-2 pt-1">
          <button ref={cancelRef} type="button" disabled={submitting} onClick={onCancel} className="px-4 py-2 text-sm font-medium text-gray-700 border border-gray-200 rounded-lg hover:bg-gray-50 cursor-pointer disabled:opacity-50">
            Cancel
          </button>
          <button type="button" disabled={submitting} onClick={onConfirm} className="px-4 py-2 text-sm font-medium text-white bg-emerald-600 hover:bg-emerald-700 rounded-lg cursor-pointer disabled:opacity-60 disabled:cursor-wait">
            {submitting ? 'Creating…' : 'Confirm assignments'}
          </button>
        </div>
      </div>
    </div>
  );
}
