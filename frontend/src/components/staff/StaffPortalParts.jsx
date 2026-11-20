import React from 'react';

/** Small pieces shared by the Staff portal screens (the Dashboard and My Workload), so both look and read the same. */
export const CARD = 'bg-white rounded-xl border border-gray-200 shadow-[0_1px_2px_rgba(16,24,40,0.04)]';

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
