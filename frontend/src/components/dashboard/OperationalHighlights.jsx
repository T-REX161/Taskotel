import React from 'react';
import { Link } from 'react-router-dom';
import { overloadedStaff, supportHighlights, workloadTone } from '../../utils/dashboardView';

const SEVERITY_DOT = { CRITICAL: 'bg-red-500', HIGH: 'bg-amber-500', MODERATE: 'bg-gray-400' };
const SEVERITY_TEXT = { CRITICAL: 'Critical', HIGH: 'High', MODERATE: 'Moderate' };
const SHOWN = 3;

const Tile = ({ tone, title, children, action }) => (
  <div className={`rounded-xl border bg-white px-4 py-3 flex flex-col gap-2 shadow-[0_1px_2px_rgba(16,24,40,0.04)] ${tone}`}>
    <div className="flex items-center justify-between gap-3">
      <h2 className="text-sm font-semibold text-gray-900">{title}</h2>
      {action}
    </div>
    {children}
  </div>
);

/**
 * OperationalHighlights — two compact tiles, shown only when something genuinely applies (Admin / Manager only):
 *  - staff whose workload is HIGH or AT_RISK, from the workload list the dashboard already loaded;
 *  - departments the existing recommendation engine says may need cross-department support.
 * Nothing is inferred here and nothing can be acted on from this strip; the Workload page owns that.
 *
 * Props:
 *   staff           – workload records (array) or null while unavailable
 *   recommendations – normalised GET /api/workload/recommendations response or null while unavailable
 */
export default function OperationalHighlights({ staff, recommendations }) {
  const strained = Array.isArray(staff) ? overloadedStaff(staff) : [];
  const departments = recommendations ? supportHighlights(recommendations) : [];
  if (strained.length === 0 && departments.length === 0) return null;

  return (
    <section aria-label="Operational highlights" className={`grid grid-cols-1 gap-3 ${strained.length > 0 && departments.length > 0 ? 'lg:grid-cols-2' : ''}`}>
      {strained.length > 0 && (
        <Tile tone="border-amber-200" title={`${strained.length} ${strained.length === 1 ? 'person' : 'people'} on high workload`}>
          <ul className="flex flex-wrap gap-2">
            {strained.slice(0, SHOWN).map((row) => {
              const tone = workloadTone(row.workloadStatus);
              return (
                <li key={row.staffId} className="inline-flex items-center gap-2 rounded-lg bg-gray-50 px-2.5 py-1 text-sm text-gray-700">
                  <span className="font-medium">{row.staffName}</span>
                  <span className={`px-1.5 py-px rounded text-[10px] font-semibold tracking-wide ring-1 ring-inset ${tone.badge}`}>{tone.label}</span>
                </li>
              );
            })}
            {strained.length > SHOWN && <li className="px-1 py-1 text-sm text-gray-500">+{strained.length - SHOWN} more</li>}
          </ul>
        </Tile>
      )}
      {departments.length > 0 && (
        <Tile
          tone="border-emerald-200"
          title={`${departments.length} ${departments.length === 1 ? 'department' : 'departments'} may need support`}
          action={<Link to="/workload" className="text-xs font-semibold text-emerald-700 hover:text-emerald-800 hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 rounded">Review in Workload</Link>}
        >
          <ul className="flex flex-wrap gap-2">
            {departments.slice(0, SHOWN).map((d) => (
              <li key={d.code} className="inline-flex items-center gap-2 rounded-lg bg-gray-50 px-2.5 py-1 text-sm text-gray-700">
                <span className={`w-2 h-2 rounded-full ${SEVERITY_DOT[d.severity] || SEVERITY_DOT.MODERATE}`} aria-hidden="true" />
                <span className="font-medium">{d.name}</span>
                <span className="text-xs text-gray-500">
                  {SEVERITY_TEXT[d.severity] || 'Moderate'}
                  {d.uncoveredTasks > 0 ? ` · ${d.uncoveredTasks} uncovered` : ''}
                  {d.canBeHelped ? '' : ' · no donor'}
                </span>
              </li>
            ))}
            {departments.length > SHOWN && <li className="px-1 py-1 text-sm text-gray-500">+{departments.length - SHOWN} more</li>}
          </ul>
        </Tile>
      )}
    </section>
  );
}
