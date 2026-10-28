import React from 'react';
import { durationPercent, formatMinutes, sortByLoad, workloadTone } from '../../utils/dashboardView';

const initialsOf = (name) => {
  const parts = String(name || '').trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return '??';
  return (parts.length === 1 ? parts[0].slice(0, 2) : parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
};

/**
 * StaffWorkloadList — one row per staff member (Admin / Manager), from GET /api/workload.
 * Shows department, active task count and the estimated workload duration the backend calculated.
 *
 * Props:
 *   staff – array of workload records
 */
export default function StaffWorkloadList({ staff }) {
  const rows = sortByLoad(staff);
  if (rows.length === 0) {
    return <p className="px-5 py-8 text-center text-sm text-gray-500">No staff workload to show yet.</p>;
  }

  return (
    <ul className="divide-y divide-gray-100 max-h-[26rem] overflow-y-auto" aria-label="Staff workload">
      {rows.map((row) => {
        const tone = workloadTone(row.workloadStatus);
        return (
          <li key={row.staffId} className="px-5 py-3.5 grid grid-cols-[auto_minmax(0,1fr)] sm:grid-cols-[auto_minmax(0,1.2fr)_minmax(0,1.4fr)_auto] items-center gap-x-3.5 gap-y-2">
            <span aria-hidden="true" className="w-9 h-9 rounded-full bg-emerald-100 text-emerald-800 text-xs font-semibold flex items-center justify-center select-none">
              {initialsOf(row.staffName)}
            </span>
            <div className="min-w-0">
              <p className="text-sm font-semibold text-gray-900 truncate">{row.staffName}</p>
              <p className="text-xs text-gray-500 truncate">
                {row.departmentName || row.department || 'No department'}
                {row.supportDepartmentName ? ` → supporting ${row.supportDepartmentName}` : ''}
                {' · '}
                {row.activeTaskCount} active {row.activeTaskCount === 1 ? 'task' : 'tasks'}
              </p>
            </div>
            <div className="col-span-2 sm:col-span-1 flex items-center gap-3">
              <div
                className="flex-1 h-2 rounded-full bg-gray-100 overflow-hidden"
                role="progressbar"
                aria-label={`${row.staffName} workload`}
                aria-valuemin={0}
                aria-valuemax={360}
                aria-valuenow={Math.min(360, row.totalEstimatedDuration || 0)}
              >
                <div className={`h-full rounded-full ${tone.bar}`} style={{ width: `${durationPercent(row.totalEstimatedDuration)}%` }} />
              </div>
              <span className="w-14 shrink-0 text-right text-xs font-medium text-gray-600">{formatMinutes(row.totalEstimatedDuration)}</span>
            </div>
            <span className={`hidden sm:inline-flex justify-self-end px-2.5 py-0.5 rounded-full text-xs font-medium ${tone.badge}`}>{tone.label}</span>
          </li>
        );
      })}
    </ul>
  );
}
