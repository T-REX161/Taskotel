import React, { useState } from 'react';
import { Link } from 'react-router-dom';
import { WorkloadIcon, DepartmentsIcon, TasksIcon } from '../icons/Icons';
import { workloadTone } from '../../utils/dashboardView';
import { getSeverityLabel, getSeverityBadge } from '../../utils/supportRecommendations';

const ICONS = { workload: WorkloadIcon, support: DepartmentsIcon, tasks: TasksIcon };
const TONES = {
  red: 'bg-red-50 text-red-600 ring-red-100',
  amber: 'bg-amber-50 text-amber-600 ring-amber-100',
};

const PRIORITY_BADGES = {
  URGENT: 'bg-red-50 text-red-700 ring-red-200',
  HIGH: 'bg-amber-50 text-amber-700 ring-amber-200',
};
const STATUS_LABELS = { PENDING: 'Pending' };
const pill = 'shrink-0 rounded-full px-2 py-0.5 text-[10px] font-semibold ring-1 ring-inset';

const Chevron = ({ open }) => (
  <svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className={`w-4 h-4 shrink-0 text-gray-300 transition-transform duration-200 motion-reduce:transition-none group-hover:text-emerald-600 ${open ? 'rotate-90' : ''}`}>
    <path d="M9 6l6 6-6 6" />
  </svg>
);

const Row = ({ children }) => <li className="flex items-center gap-2 py-1.5 text-xs">{children}</li>;

function Details({ details }) {
  const { type, rows, total, viewAll } = details;
  const more = total - rows.length;
  return (
    <div className="bg-gray-50/60 border-t border-gray-100 pl-[79px] sm:pl-[83px] pr-5 sm:pr-6 pb-3 pt-1">
      <ul className="divide-y divide-gray-100">
        {rows.map((r) => {
          if (type === 'workload') {
            const tone = workloadTone(r.status);
            return (
              <Row key={r.id}>
                <span className="min-w-0 flex-1">
                  <span className="block font-medium text-gray-900 truncate" title={r.name}>{r.name}</span>
                  <span className="block text-gray-500 truncate">{[r.department, r.activeTasks != null ? `${r.activeTasks} active` : ''].filter(Boolean).join(' · ')}</span>
                </span>
                <span className="tabular-nums text-gray-700">{r.percent}%</span>
                <span className={`${pill} ${tone.badge}`}>{tone.label}</span>
              </Row>
            );
          }
          if (type === 'tasks') {
            return (
              <Row key={r.id}>
                <span className="min-w-0 flex-1">
                  <span className="block font-medium text-gray-900 truncate" title={r.title}>{r.title}</span>
                  <span className="block text-gray-500 truncate">{[STATUS_LABELS[r.status] || r.status, r.department].filter(Boolean).join(' · ')}</span>
                </span>
                <span className={`${pill} ${PRIORITY_BADGES[r.priority]}`}>{r.priority}</span>
              </Row>
            );
          }
          return (
            <Row key={r.code}>
              <span className="min-w-0 flex-1">
                <span className="block font-medium text-gray-900 truncate" title={r.name}>{r.name}</span>
                <span className="block text-gray-500 truncate" title={r.reason}>{r.reason || (r.canBeHelped ? 'Support suggested' : 'No other department can lend staff')}</span>
              </span>
              <span className="shrink-0 text-right">
                <span className={`inline-block rounded-full px-2 py-0.5 text-[10px] font-semibold border ${getSeverityBadge(r.severity)}`}>{getSeverityLabel(r.severity)}</span>
                {r.uncoveredTasks > 0 && <span className="block mt-0.5 text-gray-500">{r.uncoveredTasks} uncovered</span>}
              </span>
            </Row>
          );
        })}
      </ul>
      {(more > 0 || viewAll) && (
        <div className="flex items-center justify-between pt-1.5 text-xs">
          <span className="text-gray-500">{more > 0 ? `+${more} more` : ''}</span>
          {viewAll && <Link to={viewAll} className="font-medium text-emerald-700 hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 rounded">View all</Link>}
        </div>
      )}
    </div>
  );
}

/**
 * OperationsInsights — a compact card with up to three things that need attention, each expanding inline to show its details (one open at a time).
 * `insights` comes from buildInsights(): { state, items, unavailable }. The card says "all clear" only when every source
 * loaded, and says so plainly when a source could not be loaded instead of pretending there is nothing to report.
 */
export default function OperationsInsights({ insights }) {
  const { state, items, unavailable } = insights;
  const [openKey, setOpenKey] = useState(null);
  if (state === 'hidden') return null;

  return (
    <section aria-label="Operations insights" className="bg-white rounded-xl border border-gray-200 shadow-[0_1px_2px_rgba(16,24,40,0.04)] overflow-hidden">
      <div className="px-5 sm:px-6 pt-4 pb-3 border-b border-gray-100">
        <h2 className="text-lg font-semibold text-gray-900">Operations Insights</h2>
        <p className="text-xs text-gray-500 mt-0.5">What needs attention now</p>
      </div>

      {state === 'loading' && <p role="status" className="px-5 sm:px-6 py-5 text-sm text-gray-500">Loading insights…</p>}
      {state === 'clear' && <p className="px-5 sm:px-6 py-5 text-sm text-gray-500">Nothing needs attention right now.</p>}
      {state === 'unavailable' && <p role="alert" className="px-5 sm:px-6 py-5 text-sm text-gray-500">Insights could not be loaded, so this card cannot say whether anything needs attention.</p>}

      {state === 'items' && (
        <ul className="divide-y divide-gray-100">
          {items.map((item) => {
            const Icon = ICONS[item.kind];
            const open = openKey === item.key;
            const panelId = `insight-panel-${item.key}`;
            return (
              <li key={item.key} data-insight={item.key}>
                <button type="button" aria-expanded={open} aria-controls={panelId} onClick={() => setOpenKey(open ? null : item.key)} className="group w-full text-left flex items-center gap-4 border-l-[3px] border-l-transparent pl-4 sm:pl-5 pr-5 sm:pr-6 py-3 transition-colors hover:bg-gray-50/70 focus:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-emerald-500">
                  <span className={`w-11 h-11 shrink-0 rounded-lg ring-1 ring-inset flex items-center justify-center ${TONES[item.tone]}`} aria-hidden="true">
                    <Icon className="w-5 h-5" />
                  </span>
                  <span className="min-w-0 flex-1">
                    <span className="block text-sm font-semibold text-gray-900 truncate" title={item.title}>{item.title}</span>
                    <span className="block text-xs text-gray-500 truncate" title={item.detail}>{item.detail}</span>
                  </span>
                  <Chevron open={open} />
                </button>
                {open && (
                  <div id={panelId} role="region" aria-label={`${item.title} details`} className="motion-safe:animate-[insightIn_160ms_ease-out]">
                    <Details details={item.details} />
                  </div>
                )}
              </li>
            );
          })}
        </ul>
      )}

      {state === 'items' && unavailable.length > 0 && (
        <p className="border-t border-gray-100 px-5 sm:px-6 py-2 text-[11px] text-gray-400">Some insight data could not be loaded, so this list may be incomplete.</p>
      )}
    </section>
  );
}
