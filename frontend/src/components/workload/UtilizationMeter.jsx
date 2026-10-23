import React from 'react';
import { getWorkloadProgressBarColor } from '../../utils/workloadUtils';

// Soft pill colours for each existing status; the thresholds themselves are calculated elsewhere
const STATUS_PILL = {
  LOW: 'bg-blue-100 text-blue-700',
  NORMAL: 'bg-emerald-100 text-emerald-700',
  HIGH: 'bg-orange-100 text-orange-700',
  AT_RISK: 'bg-red-100 text-red-700',
};

/**
 * Three left-aligned lines: percentage + status pill, a slim progress bar, and used / baseline minutes.
 * The percentage and status come from the page's existing calculations; this only presents them.
 */
export default function UtilizationMeter({ percentage, status, minutes, baseline = 480 }) {
  const fill = Math.min(Math.max(percentage, 0), 100);
  const pill = STATUS_PILL[String(status).toUpperCase()] || 'bg-gray-100 text-gray-700';

  return (
    <div className="flex flex-col items-start gap-1 w-full max-w-[12.5rem] min-w-0">
      {/* Line 1: percentage + status pill */}
      <div className="flex items-center gap-2.5 h-5">
        <span className="text-base font-bold text-gray-900 leading-none tabular-nums">{percentage}%</span>
        <span
          className={`inline-flex items-center h-[19px] px-2.5 rounded-full text-[10.5px] font-bold uppercase tracking-wide leading-none whitespace-nowrap ${pill}`}
        >
          {String(status).replace('_', ' ')}
        </span>
      </div>

      {/* Line 2: bar. The neutral track stays visible at 0%, the fill uses the status colour */}
      <div
        className="w-full h-[7px] rounded-full bg-slate-100 overflow-hidden"
        role="progressbar"
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={fill}
        aria-label={`Shift utilization ${percentage}%`}
      >
        <div
          className={`h-full rounded-full transition-all duration-300 ${getWorkloadProgressBarColor(status)}`}
          style={{ width: `${fill}%` }}
        />
      </div>

      {/* Line 3: used workload / shift baseline */}
      <div className="text-xs leading-[14px] text-gray-400 tabular-nums whitespace-nowrap">
        {minutes} / {baseline} mins
      </div>
    </div>
  );
}
