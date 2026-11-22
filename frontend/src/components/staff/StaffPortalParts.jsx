import React from 'react';

/** Small pieces shared by the Staff portal screens (the Dashboard and My Workload), so both look and read the same. */
export const CARD = 'bg-white rounded-xl border border-gray-200 shadow-[0_1px_2px_rgba(16,24,40,0.04)]';

// Shared by the Staff task rows (Dashboard "My Tasks" and My Workload "My Assigned Tasks")
export const PRIORITY_FLAG = { URGENT: 'text-rose-500', HIGH: 'text-orange-500', MEDIUM: 'text-amber-500', LOW: 'text-emerald-500' };
export const PRIORITY_BAR = { URGENT: 'bg-rose-500', HIGH: 'bg-orange-500', MEDIUM: 'bg-amber-500', LOW: 'bg-emerald-500' };
export const TASK_BADGE = 'inline-flex items-center h-6 px-2.5 rounded-md text-[11px] font-semibold leading-none whitespace-nowrap';
export const TASK_ROW = 'relative pl-6 pr-5 py-4 flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between sm:gap-6 transition-colors duration-base ease-out hover:bg-gray-50/60';

export function FlagIcon({ className }) {
  return (
    <svg className={className} viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
      <path d="M4 2.75a.75.75 0 0 1 1.5 0V3h8.4a.6.6 0 0 1 .47.97L12.4 6.5l1.97 2.53a.6.6 0 0 1-.47.97H5.5v7.25a.75.75 0 0 1-1.5 0V2.75Z" />
    </svg>
  );
}

export const availabilityText = (value) => (value ? String(value).replace(/_/g, ' ').toLowerCase().replace(/^\w/, (c) => c.toUpperCase()) : 'Unavailable');

// Presentation only: what each server-provided workload status looks like. The status itself (and its thresholds) always comes from the server.
export const STATUS_TONE = {
  LOW: { chip: 'bg-blue-50 text-blue-600 ring-blue-100', text: 'Light load' },
  NORMAL: { chip: 'bg-emerald-50 text-emerald-600 ring-emerald-100', text: 'Balanced load' },
  HIGH: { chip: 'bg-orange-50 text-orange-600 ring-orange-100', text: 'Heavy load' },
  AT_RISK: { chip: 'bg-red-50 text-red-500 ring-red-100', text: 'At risk of overload' },
};
export const NEUTRAL_CHIP = 'bg-gray-100 text-gray-500 ring-gray-200';
export const GREEN_CHIP = 'bg-emerald-50 text-emerald-600 ring-emerald-100';

/** One headline figure: a title, the value (a number or a badge) and an icon chip. */
export function StatCard({ title, Icon, chip, children }) {
  return (
    <div className={`${CARD} p-4 min-h-[6rem] flex items-start justify-between gap-3`}>
      <div className="min-w-0">
        <p className="text-sm font-medium text-gray-600">{title}</p>
        <div className="mt-2 min-h-[2rem] flex items-center text-3xl font-bold leading-none tabular-nums text-gray-900">{children}</div>
      </div>
      <div className={`w-10 h-10 shrink-0 rounded-lg ring-1 ring-inset flex items-center justify-center ${chip}`}>
        <Icon className="w-5 h-5" aria-hidden="true" />
      </div>
    </div>
  );
}
