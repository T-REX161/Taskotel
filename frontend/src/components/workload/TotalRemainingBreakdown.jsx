import React, { useEffect, useRef, useState } from 'react';

/**
 * The "Total remaining" figure of the department cards with an info button that opens how it is made up: assigned minutes plus waiting
 * (unassigned) minutes, per department, and the utilization formula. Display only; every figure is the server's department-capacity answer.
 */
export default function TotalRemainingBreakdown({ totals, departments, shiftMinutes }) {
  const [open, setOpen] = useState(false);
  const root = useRef(null);

  useEffect(() => {
    if (!open) return undefined;
    const onKey = (event) => { if (event.key === 'Escape') setOpen(false); };
    const onPointer = (event) => { if (root.current && !root.current.contains(event.target)) setOpen(false); };
    document.addEventListener('keydown', onKey);
    document.addEventListener('mousedown', onPointer);
    return () => {
      document.removeEventListener('keydown', onKey);
      document.removeEventListener('mousedown', onPointer);
    };
  }, [open]);

  return (
    <div ref={root} className="relative inline-flex items-baseline gap-x-2.5 text-xs sm:text-sm">
      <span className="font-medium text-gray-500">Total remaining</span>
      <span className="font-bold text-gray-900 whitespace-nowrap">{totals.remaining} mins</span>
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        aria-expanded={open}
        aria-label="How the total remaining is calculated"
        data-testid="remaining-breakdown-toggle"
        className="self-center inline-flex h-4 w-4 items-center justify-center rounded-full border border-gray-300 text-[10px] font-bold leading-none text-gray-500 hover:text-gray-900 hover:border-gray-400 cursor-pointer"
      >
        i
      </button>
      {open && (
        <div
          role="dialog"
          aria-label="Total remaining breakdown"
          data-testid="remaining-breakdown"
          className="absolute right-0 top-full z-20 mt-2 w-[min(86vw,19rem)] max-h-[70vh] overflow-y-auto rounded-xl border border-gray-200 bg-white p-3 text-left shadow-lg space-y-2"
        >
          <p className="text-xs font-semibold text-gray-900">
            {totals.assigned} assigned + {totals.waiting} waiting = {totals.remaining} mins
          </p>
          <table className="w-full text-[11px]">
            <thead>
              <tr className="text-gray-500 text-left">
                <th className="font-medium pb-1">Dept</th>
                <th className="font-medium pb-1 text-right">Assigned</th>
                <th className="font-medium pb-1 text-right">Waiting</th>
                <th className="font-medium pb-1 text-right">Total</th>
              </tr>
            </thead>
            <tbody className="text-gray-800">
              {departments.map((d) => (
                <tr key={d.code}>
                  <td className="py-0.5 pr-2">{d.name}</td>
                  <td className="py-0.5 text-right">{d.assignedMinutes}</td>
                  <td className="py-0.5 text-right">{d.waitingMinutes}</td>
                  <td className="py-0.5 text-right font-semibold">{d.remainingMinutes}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <p className="text-[11px] text-gray-500">Utilization % = total ÷ (staff × {shiftMinutes} min shift, assumed)</p>
        </div>
      )}
    </div>
  );
}
