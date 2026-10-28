import React, { useState } from 'react';
import SupportStageBadge from './SupportStageBadge';
import { canArchiveSupport, canEndSupport, describeEndReason, recordStage, stageOf } from '../../../utils/supportPlan';

const formatWhen = (value) => {
  if (!value) return '';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '' : date.toLocaleString([], { dateStyle: 'medium', timeStyle: 'short' });
};

const initialsOf = (name) => {
  const parts = String(name || '').trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return '??';
  return (parts.length === 1 ? parts[0].slice(0, 2) : parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
};

// One grid for the header and every row, so the columns line up on wide screens; on a phone each row stacks.
const COLUMNS = 'lg:grid lg:grid-cols-[minmax(0,1fr)_minmax(0,1.1fr)_minmax(0,1.4fr)_minmax(0,1.5fr)_auto] lg:items-center lg:gap-x-5';

const Cell = ({ label, children, className = '' }) => (
  <div className={`min-w-0 ${className}`}>
    <p className="lg:hidden text-[11px] font-medium uppercase tracking-wide text-gray-500">{label}</p>
    {children}
  </div>
);

/**
 * Confirmed support: active first, and what has ended. Names are snapshots, so they stay correct later.
 * Active records get an End Support action (Admin and Manager only, via `canEnd`); ended ones never do.
 */
export default function SupportHistory({ history, departmentName, canEnd = false, onEnd, endingId = null, historyOnly = false, onArchive, archivingId = null }) {
  // historyOnly: just the completed and ended records, with no heading, tabs or "no one is on support" message. The panel
  // uses it inside a collapsed "Support history" area when nobody is on active support.
  const [tab, setTab] = useState(historyOnly ? 'ended' : 'active');
  const { status, data, error } = history;
  const rows = (data || []).filter((row) => (tab === 'active' ? row.status === 'ACTIVE' : row.status !== 'ACTIVE'));
  const activeCount = (data || []).filter((row) => row.status === 'ACTIVE').length;
  const endedCount = (data || []).length - activeCount;

  return (
    <section aria-label={historyOnly ? 'Support history' : 'Support assignments'} className="space-y-3">
      {!historyOnly && (
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h4 className="text-sm font-semibold text-gray-900">Confirmed support</h4>
        <div role="tablist" aria-label="Support status" className="inline-flex rounded-lg bg-gray-100 p-0.5 text-xs">
          {[['active', `Active (${activeCount})`], ['ended', `Completed & ended${status === 'ready' ? ` (${endedCount})` : ''}`]].map(([key, label]) => (
            <button
              key={key}
              type="button"
              role="tab"
              aria-selected={tab === key}
              onClick={() => setTab(key)}
              className={`px-3 py-1 rounded-md cursor-pointer font-medium transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 ${tab === key ? 'bg-white text-gray-900 shadow-2xs' : 'text-gray-500 hover:text-gray-700'}`}
            >
              {label}
            </button>
          ))}
        </div>
      </div>
      )}
      {status === 'loading' && <p role="status" className="text-sm text-gray-500">Loading support history…</p>}
      {status === 'error' && <p role="alert" className="text-sm text-red-700">Could not load support history. {error}</p>}
      {status === 'ready' && rows.length === 0 && (
        <p className="text-sm text-gray-500 rounded-xl border border-gray-200 px-3 py-5 text-center">
          {tab === 'active' ? 'No one is on temporary support right now.' : 'No completed or ended support yet.'}
        </p>
      )}
      {status === 'ready' && rows.length > 0 && (
        <div className="rounded-xl border border-gray-200 overflow-hidden">
          <div aria-hidden="true" className={`hidden ${COLUMNS} bg-gray-50 border-b border-gray-200 px-4 py-2 text-[11px] font-semibold uppercase tracking-wide text-gray-500`}>
            <span>Staff member</span><span>Department</span><span>Task</span><span>{tab === 'active' ? 'Started' : 'Started / ended'}</span><span className="text-right">Status</span>
          </div>
          <ul className="divide-y divide-gray-200">
            {rows.map((row) => {
              const stage = recordStage(row);
              const reason = describeEndReason(row.endReason);
              return (
                <li key={row.id} data-stage={stage} className={`px-4 py-3 space-y-2.5 lg:space-y-0 ${COLUMNS}`}>
                  <div className="flex items-center gap-2.5 min-w-0">
                    <span
                      aria-hidden="true"
                      className={`w-9 h-9 rounded-full shrink-0 flex items-center justify-center text-xs font-bold select-none ${
                        stage === 'active' ? 'bg-emerald-600 text-white' : 'bg-gray-100 text-gray-500'
                      }`}
                    >
                      {initialsOf(row.staffName)}
                    </span>
                    <p className="min-w-0 text-sm font-semibold text-gray-900 leading-5 break-words">{row.staffName}</p>
                  </div>
                  <Cell label="Department">
                    <p className="text-sm text-gray-700">{departmentName(row.homeDepartmentCode)} <span aria-label="to" className="text-gray-400">→</span> {departmentName(row.receivingDepartmentCode)}</p>
                  </Cell>
                  <Cell label="Task">
                    <p className="text-sm text-gray-900 break-words">{row.taskTitle}</p>
                  </Cell>
                  <Cell label={tab === 'active' ? 'Started' : 'Started / ended'}>
                    <p className="text-xs text-gray-600">{formatWhen(row.createdAt)} · {row.approvedBy}</p>
                    {row.completedAt && <p className="text-xs text-gray-500">Ended {formatWhen(row.completedAt)}{reason ? ` · ${reason.replace(/^Ended /, '')}` : ''}</p>}
                  </Cell>
                  <div className="flex items-center gap-2 lg:justify-end">
                    <SupportStageBadge stage={stage || stageOf(row.status)} />
                    {canEnd && onArchive && canArchiveSupport(row) && (
                      <button
                        type="button"
                        disabled={archivingId === row.id}
                        onClick={() => onArchive(row)}
                        aria-label={`Remove ${row.staffName} from history`}
                        title="Remove from history"
                        className="inline-flex items-center justify-center w-8 h-8 shrink-0 text-gray-500 border border-gray-300 rounded-lg hover:text-red-700 hover:border-red-200 hover:bg-red-50 cursor-pointer disabled:opacity-50 disabled:cursor-wait focus:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 focus-visible:ring-offset-1"
                      >
                        <svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className={`w-4 h-4 ${archivingId === row.id ? 'animate-pulse' : ''}`}>
                          <path d="M3 6h18" /><path d="M8 6V4a1 1 0 0 1 1-1h6a1 1 0 0 1 1 1v2" /><path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6" /><path d="M10 11v6" /><path d="M14 11v6" />
                        </svg>
                      </button>
                    )}
                    {canEnd && canEndSupport(row) && (
                      <button
                        type="button"
                        disabled={endingId === row.id}
                        onClick={() => onEnd(row)}
                        aria-label={`End support for ${row.staffName}`}
                        className="px-2.5 py-1 text-xs font-medium text-red-700 border border-gray-300 rounded-lg hover:border-red-200 hover:bg-red-50 cursor-pointer disabled:opacity-50 disabled:cursor-wait focus:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 focus-visible:ring-offset-1"
                      >
                        {endingId === row.id ? 'Ending…' : 'End Support'}
                      </button>
                    )}
                  </div>
                </li>
              );
            })}
          </ul>
        </div>
      )}
    </section>
  );
}
