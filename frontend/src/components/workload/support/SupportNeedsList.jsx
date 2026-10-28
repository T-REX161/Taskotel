import React from 'react';
import { getSeverityLabel, getSeverityTone, pickMostUrgent } from '../../../utils/supportRecommendations';
import { formatMinutes } from '../../../utils/supportPlan';

const Figure = ({ label, value, warn }) => (
  <div className="min-w-0">
    <dt className="text-[11px] text-gray-500 leading-4">{label}</dt>
    <dd className={`text-sm font-semibold leading-5 tabular-nums ${warn ? 'text-amber-700' : 'text-gray-900'}`}>{value}</dd>
  </div>
);

/**
 * Step 1: the departments with more waiting work than free staff. Choosing one starts a plan for it.
 * `departments` is [{ code, name, severity, pressure, suggestionCount }]. The one that most needs help is marked.
 */
export default function SupportNeedsList({ departments, selectedCode, onSelect }) {
  const urgentCode = pickMostUrgent(departments);
  return (
    <div role="group" aria-label="Departments that need support" className="grid grid-cols-[repeat(auto-fill,minmax(min(100%,16rem),1fr))] gap-3">
      {departments.map((dept) => {
        const selected = dept.code === selectedCode;
        const urgent = dept.code === urgentCode;
        const p = dept.pressure;
        const tone = getSeverityTone(dept.severity);
        return (
          <button
            key={dept.code}
            type="button"
            aria-pressed={selected}
            onClick={() => onSelect(dept.code)}
            className={`group text-left rounded-xl border p-3.5 transition-colors cursor-pointer focus:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 focus-visible:ring-offset-1 ${
              urgent ? `border-l-4 ${tone.accent}` : ''
            } ${selected ? 'border-emerald-500 bg-emerald-50/40' : 'border-gray-200 bg-white hover:border-gray-300 hover:bg-gray-50'}`}
          >
            <span className="flex items-start justify-between gap-2">
              <span className="min-w-0">
                <span className="block text-sm font-semibold text-gray-900 leading-5 truncate">{dept.name}</span>
                <span className="mt-0.5 flex items-center gap-1.5 text-[11px] text-gray-500">
                  <span aria-hidden="true" className={`w-1.5 h-1.5 rounded-full ${tone.dot}`} />
                  {getSeverityLabel(dept.severity)}
                  {urgent && <span className="font-semibold text-amber-700">· Most urgent</span>}
                </span>
              </span>
              <span
                aria-hidden="true"
                className={`mt-0.5 w-4 h-4 shrink-0 rounded-full border flex items-center justify-center ${selected ? 'bg-emerald-600 border-emerald-600 text-white' : 'border-gray-300'}`}
              >
                {selected && (
                  <svg viewBox="0 0 12 12" className="w-2.5 h-2.5" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M2.5 6.5l2.2 2.2L9.5 3.8" /></svg>
                )}
              </span>
            </span>

            <dl className="mt-3 grid grid-cols-3 gap-x-2">
              <Figure label="Waiting" value={p.waitingTasks} />
              <Figure label="Minutes" value={formatMinutes(p.waitingMinutes).replace(' min', '')} />
              <Figure label="Uncovered" value={p.uncoveredTasks} warn={p.uncoveredTasks > 0} />
            </dl>

            <span className="mt-2.5 flex items-center justify-between gap-2 border-t border-gray-100 pt-2 text-[11px]">
              <span className="text-gray-500">{p.freeStaff} free of {p.availableStaff} available{p.urgentOrHighTasks > 0 ? ` · ${p.urgentOrHighTasks} urgent/high` : ''}</span>
              {dept.suggestionCount > 0
                ? <span className="font-semibold text-emerald-700 whitespace-nowrap">{dept.suggestionCount} suggested helper{dept.suggestionCount === 1 ? '' : 's'}</span>
                : <span className="font-medium text-amber-700 text-right">No helpers available</span>}
            </span>
          </button>
        );
      })}
    </div>
  );
}
