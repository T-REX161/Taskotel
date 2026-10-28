import React from 'react';
import { summarizeNeeds } from '../../../utils/supportRecommendations';

const Tile = ({ label, value, hint, tone }) => (
  <div className="min-w-0 rounded-xl bg-gray-50 border border-gray-200 px-3 py-2.5">
    <p className="text-[11px] font-medium text-gray-500 leading-4">{label}</p>
    <p className="mt-0.5 flex items-center gap-1.5 text-xl font-bold leading-7 text-gray-900 tabular-nums">
      {tone && <span aria-hidden="true" className={`w-1.5 h-1.5 rounded-full ${tone}`} />}
      {value}
    </p>
    <p className="text-[11px] text-gray-500 leading-4">{hint}</p>
  </div>
);

/**
 * Compact summary of the live data below: how many departments need help, how many tasks nobody can take, and how many
 * people are on temporary support right now. Nothing is hard-coded; while data is loading or failed it shows a dash.
 */
export default function SupportOverview({ needs, history }) {
  const needsKnown = needs.status === 'ready' && needs.data;
  const summary = needsKnown ? summarizeNeeds(needs.data) : null;
  const activeCount = history.status === 'ready' ? history.data.filter((row) => row.status === 'ACTIVE').length : null;
  const dash = '–';

  return (
    <div role="group" aria-label="Workload overview" className="grid grid-cols-3 gap-2 sm:gap-3">
      <Tile
        label="Need help"
        value={summary ? summary.departments : dash}
        hint={summary ? (summary.departments === 1 ? 'department' : 'departments') : 'departments'}
        tone={summary && summary.departments > 0 ? 'bg-amber-500' : summary ? 'bg-emerald-500' : null}
      />
      <Tile
        label="Uncovered tasks"
        value={summary ? summary.uncovered : dash}
        hint={summary ? `of ${summary.waiting} waiting` : 'waiting, no free staff'}
        tone={summary && summary.uncovered > 0 ? 'bg-amber-500' : summary ? 'bg-emerald-500' : null}
      />
      <Tile
        label="Active support"
        value={activeCount ?? dash}
        hint={activeCount === 1 ? 'person helping now' : 'people helping now'}
        tone={activeCount > 0 ? 'bg-emerald-500' : null}
      />
    </div>
  );
}
