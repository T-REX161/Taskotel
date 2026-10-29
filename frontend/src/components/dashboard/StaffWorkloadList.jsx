import React from 'react';
import { Link } from 'react-router-dom';
import { durationPercent, loadPercent, sortByLoad, workloadTone } from '../../utils/dashboardView';

const initialsOf = (name) => {
  const parts = String(name || '').trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return '??';
  return (parts.length === 1 ? parts[0].slice(0, 2) : parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
};

/**
 * One column template for every row, so each column starts at the same x in all of them: avatar | name and department |
 * bar | percentage | badge | tasks and duration. Only the name and bar columns flex, and they share the free width in a
 * fixed ratio; everything else has a fixed width, so no value can move a column. The six columns need about 460px, so
 * they are used where the card is that wide (sm up to lg, where the panel is full width, and from 1200px, where the two
 * panels sit side by side). In between, and on a phone, the row is a three-column line (avatar, name, tasks) with the
 * bar, percentage and badge on a line of their own underneath.
 */
const ROW_GRID = 'grid-cols-[2.75rem_minmax(0,1fr)_4.5rem] sm:grid-cols-[2.75rem_minmax(0,1fr)_minmax(6rem,1.3fr)_2.75rem_4.5rem_4.5rem] lg:grid-cols-[2.75rem_minmax(0,1fr)_4.5rem] min-[1200px]:grid-cols-[2.75rem_minmax(0,1fr)_minmax(6rem,1.3fr)_2.75rem_4.5rem_4.5rem]';

/** Rows shown on the dashboard; the Workload page has everyone. Keeps the panel level with the chart without an inner scroll area. */
export const DASHBOARD_ROW_LIMIT = 7;

/**
 * StaffWorkloadList — one row per staff member (Admin / Manager), from GET /api/workload.
 * Name and department, a load bar with its percentage (of the 360-minute At-risk threshold) and the backend's workload
 * state, and the active task count with the estimated duration the backend calculated. People on HIGH or AT_RISK get a
 * thin accent on the left and a faint tint; nothing else changes for them.
 *
 * Shows the most loaded people first, up to DASHBOARD_ROW_LIMIT, then a link to the full list.
 *
 * Props:
 *   staff – array of workload records
 */
export default function StaffWorkloadList({ staff }) {
  const all = sortByLoad(staff);
  const rows = all.slice(0, DASHBOARD_ROW_LIMIT);
  if (rows.length === 0) {
    return <p className="px-6 py-8 text-center text-sm text-gray-500">No staff workload to show yet.</p>;
  }

  return (
    <>
    <ul className="divide-y divide-gray-100" aria-label="Staff workload">
      {rows.map((row) => {
        const tone = workloadTone(row.workloadStatus);
        const department = `${row.departmentName || row.department || 'No department'}${row.supportDepartmentName ? ` → ${row.supportDepartmentName}` : ''}`;
        return (
          <li
            key={row.staffId}
            data-workload={row.workloadStatus}
            className={`border-l-[3px] pl-4 sm:pl-5 pr-5 sm:pr-6 py-3 grid items-center gap-x-4 gap-y-2.5 ${ROW_GRID} transition-colors hover:bg-gray-50/70 ${tone.row}`}
          >
            <span aria-hidden="true" className="w-11 h-11 rounded-full bg-emerald-50 text-emerald-700 ring-1 ring-emerald-100 text-sm font-semibold flex items-center justify-center select-none">
              {initialsOf(row.staffName)}
            </span>
            <div className="min-w-0 text-left">
              <p className="text-[15px] font-semibold text-gray-900 truncate" title={row.staffName}>{row.staffName}</p>
              <p className="text-sm text-gray-500 truncate" title={department}>{department}</p>
            </div>
            {/* Task count and duration: the last column, right-aligned. On a phone it stays beside the name and the bar row goes underneath */}
            <div className="w-[4.5rem] text-right tabular-nums sm:order-last lg:order-none min-[1200px]:order-last">
              <p className="text-sm font-semibold text-gray-900 whitespace-nowrap">{row.activeTaskCount} {row.activeTaskCount === 1 ? 'task' : 'tasks'}</p>
              <p className="text-xs text-gray-500 whitespace-nowrap">{row.totalEstimatedDuration || 0} min</p>
            </div>
            {/* Bar, percentage and badge: three fixed columns from sm up; one tidy row of their own on a phone */}
            <div className="col-span-3 col-start-1 flex items-center gap-3 sm:contents lg:flex min-[1200px]:contents">
              <div
                className="flex-1 sm:flex-none sm:w-full lg:flex-1 lg:w-auto min-[1200px]:flex-none min-[1200px]:w-full h-2 rounded-full bg-gray-100 overflow-hidden"
                role="progressbar"
                aria-label={`${row.staffName} workload`}
                aria-valuemin={0}
                aria-valuemax={360}
                aria-valuenow={Math.min(360, row.totalEstimatedDuration || 0)}
              >
                <div className={`h-full rounded-full transition-[width] duration-500 ease-out ${tone.bar}`} style={{ width: `${durationPercent(row.totalEstimatedDuration)}%` }} />
              </div>
              <span className="w-11 shrink-0 text-right text-sm tabular-nums text-gray-600">{loadPercent(row.totalEstimatedDuration)}%</span>
              <span className={`w-[4.5rem] shrink-0 inline-flex justify-center px-2 py-0.5 rounded-md text-[10px] font-semibold tracking-wide ring-1 ring-inset ${tone.badge}`}>
                {tone.label}
              </span>
            </div>
          </li>
        );
      })}
    </ul>
    {all.length > rows.length && (
      <div className="mt-auto border-t border-gray-100 px-5 sm:px-6 py-3 text-center">
        <Link to="/workload" className="text-sm font-semibold text-emerald-700 hover:text-emerald-800 hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 rounded">
          View all {all.length} in Workload
        </Link>
      </div>
    )}
    </>
  );
}
