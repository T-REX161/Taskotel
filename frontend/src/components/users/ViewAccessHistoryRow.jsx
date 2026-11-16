import React from 'react';
import { formatDate, formatTime } from '../../utils/dateTimeFormat';
import { getPreferences } from '../../utils/preferences';

export const formatWhen = (value) => {
  if (!value) return '';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '';
  const { dateFormat, timeFormat } = getPreferences();
  return `${formatDate(date, dateFormat)}, ${formatTime(date, timeFormat)}`;
};

const TONE = {
  granted: { dot: 'bg-emerald-500', text: 'text-emerald-700' },
  revoked: { dot: 'bg-rose-500', text: 'text-rose-700' },
  upgraded: { dot: 'bg-sky-500', text: 'text-sky-700' },
  other: { dot: 'bg-gray-400', text: 'text-gray-600' },
};

/**
 * One access-history record (a row from viewAccessHistoryRows): a small coloured dot and "Access granted" / "Access revoked", the
 * department, the time, and the Admin who made the change. Read-only: no actions, ever (the history is append-only).
 */
export default function ViewAccessHistoryRow({ row }) {
  const tone = TONE[row.tone] || TONE.other;
  return (
    <li data-testid="view-access-history-row" data-action={row.tone} className="flex items-start gap-2.5 py-2.5 pr-1">
      <span aria-hidden="true" className={`mt-[7px] h-1.5 w-1.5 shrink-0 rounded-full ${tone.dot}`} />
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-0.5">
          <p className="min-w-0 text-sm break-words">
            <span className={`font-medium ${tone.text}`}>{row.action}</span>
            <span className="text-gray-400" aria-hidden="true"> · </span>
            <span className="font-medium text-gray-900">{row.department}</span>
          </p>
          <time dateTime={row.changedAt || undefined} className="shrink-0 text-xs text-gray-500 tabular-nums">
            {formatWhen(row.changedAt)}
          </time>
        </div>
        <p className="text-xs text-gray-500 break-words">
          by {row.actor}
          {row.automatic && ' · automatic: it became the Manager’s own department'}
        </p>
      </div>
    </li>
  );
}
