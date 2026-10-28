import React from 'react';
import { donutArcs, taskStatusSegments } from '../../utils/dashboardView';

const RADIUS = 52;

/**
 * TaskStatusSummary — donut chart plus labelled legend of tasks per status.
 * The counts come straight from the dashboard summary; staff see their own tasks (and no cancelled count).
 *
 * Props:
 *   data    – summary payload containing status counts
 *   isStaff – boolean indicating whether current user has STAFF role view
 */
export default function TaskStatusSummary({ data, isStaff }) {
  const { total, rows } = taskStatusSegments(data, isStaff);
  const arcs = donutArcs(rows, RADIUS);

  return (
    <div className="bg-white rounded-2xl border border-gray-200 shadow-sm p-5 sm:p-6">
      <h2 className="text-base font-semibold text-gray-900">Task Status</h2>
      <p className="text-xs text-gray-500 mt-0.5">{isStaff ? 'Your tasks by status' : 'All tasks by status'}</p>

      <div className="mt-5 flex flex-col items-center gap-5 sm:flex-row sm:items-center xl:flex-col">
        <figure className="relative w-36 h-36 shrink-0 m-0" role="img" aria-label={`${total} tasks: ${rows.map((r) => `${r.count} ${r.label}`).join(', ')}`}>
          <svg viewBox="0 0 140 140" className="w-full h-full -rotate-90">
            <circle cx="70" cy="70" r={RADIUS} fill="none" strokeWidth="16" className="stroke-gray-100" />
            {arcs.map((arc) => (
              <circle
                key={arc.key}
                cx="70"
                cy="70"
                r={RADIUS}
                fill="none"
                strokeWidth="16"
                className={arc.stroke}
                strokeDasharray={`${arc.length} ${arc.gap}`}
                strokeDashoffset={arc.offset}
              />
            ))}
          </svg>
          <figcaption className="absolute inset-0 flex flex-col items-center justify-center">
            <span className="text-2xl font-bold text-gray-900 leading-none">{total}</span>
            <span className="text-[11px] font-medium text-gray-500 mt-1">{total === 1 ? 'task' : 'tasks'}</span>
          </figcaption>
        </figure>

        <ul className="w-full space-y-2.5">
          {rows.map((row) => (
            <li key={row.key} className="flex items-center justify-between gap-3 text-sm">
              <span className="flex items-center gap-2.5 min-w-0">
                <span className={`w-2.5 h-2.5 rounded-full shrink-0 ${row.dot}`} aria-hidden="true" />
                <span className="text-gray-600 font-medium truncate">{row.label}</span>
              </span>
              <span className="shrink-0 text-gray-900 font-semibold">
                {row.count}
                <span className="ml-1.5 text-xs font-medium text-gray-400">{row.percent}%</span>
              </span>
            </li>
          ))}
        </ul>
      </div>
    </div>
  );
}
