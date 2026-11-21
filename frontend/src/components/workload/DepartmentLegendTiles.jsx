import React from 'react';

// The same four tile looks as the fixed legend: a stronger tint on the light theme, the original on dark, and a dot that matches the bars
const TILE_STYLES = {
  LOW: {
    tile: 'border-blue-200 bg-blue-100 dark:border-blue-100 dark:bg-blue-50',
    dot: 'bg-blue-600 dark:bg-blue-500',
    label: 'text-blue-700',
  },
  NORMAL: {
    tile: 'border-emerald-200 bg-emerald-100 dark:border-emerald-100 dark:bg-emerald-50',
    dot: 'bg-emerald-600 dark:bg-emerald-500',
    label: 'text-emerald-700',
  },
  HIGH: {
    tile: 'border-orange-200 bg-orange-100 dark:border-orange-100 dark:bg-orange-50',
    dot: 'bg-orange-600 dark:bg-orange-500',
    label: 'text-orange-700',
  },
  AT_RISK: {
    tile: 'border-red-200 bg-red-100 dark:border-red-100 dark:bg-red-50',
    dot: 'bg-red-600 dark:bg-red-500',
    label: 'text-red-700',
  },
};

/**
 * The Workload Thresholds legend for the department cards: one tile per category that can occur, as worked out by
 * getDepartmentLegendRanges (the same rule that colours the bars and writes the status labels).
 *
 * @param {{ ranges: Array<{ status: string, label: string, text: string }> }} props
 */
export default function DepartmentLegendTiles({ ranges }) {
  return (
    <ul className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-2" data-testid="department-legend">
      {ranges.map((range) => {
        const style = TILE_STYLES[range.status] || TILE_STYLES.LOW;
        return (
          <li key={range.status} data-status={range.status} className={`flex items-center justify-between gap-3 rounded-lg border px-3 py-2 ${style.tile}`}>
            <span className={`inline-flex items-center gap-2 text-xs font-semibold tracking-wide ${style.label}`}>
              <span className={`w-3 h-3 rounded-full ${style.dot}`} aria-hidden="true" />
              {range.label}
            </span>
            <span className="text-sm font-medium text-gray-900">{range.text}</span>
          </li>
        );
      })}
    </ul>
  );
}
