import React from 'react';
import SupportStageBadge from './SupportStageBadge';
import { donorCapacityLeft, formatMinutes, needsReserveOverride, whyCannotAdd } from '../../../utils/supportPlan';

const PRIORITY_LABELS = { URGENT: 'Urgent', HIGH: 'High', MEDIUM: 'Medium', LOW: 'Low' };

const taskLabel = (task) =>
  `${task.title} · ${PRIORITY_LABELS[task.priority] || task.priority} · ${task.estimatedDuration ? `${task.estimatedDuration} min` : 'no duration'}`;

const initialsOf = (name) => {
  const parts = String(name || '').trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return '??';
  return (parts.length === 1 ? parts[0].slice(0, 2) : parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
};

const Avatar = ({ name, active }) => (
  <span
    aria-hidden="true"
    className={`w-9 h-9 rounded-full shrink-0 flex items-center justify-center text-xs font-bold select-none ${
      active ? 'bg-emerald-600 text-white' : 'bg-gray-100 text-gray-600'
    }`}
  >
    {initialsOf(name)}
  </span>
);

const focusRing = 'focus:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 focus-visible:ring-offset-1';

/**
 * Step 2 and 3: pick staff from other departments, then choose which waiting task each will help with.
 * Left: the lending departments' eligible staff, in a grid of equal cards. Right: the plan, a neutral panel that carries
 * the impact preview and the confirm action (`planFooter`). It is not sticky: the page's <main> has overflow-y-auto, which
 * makes it the sticky container although the window does the scrolling, so sticky would never engage. The plan is a draft
 * until it is confirmed in the dialog.
 */
export default function SupportPlanBuilder({ options, plan, suggestions, notice, disabled, knownStaff, planFooter, onAdd, onRequestOverride, onRemove, onSetTask, onUseSuggestions, onClear }) {
  const capacityLeft = donorCapacityLeft(options, plan);
  const suggestedIds = new Set(suggestions.map((s) => s.staffId));
  const staffById = new Map();
  options.donors.forEach((donor) => donor.staff.forEach((s) => staffById.set(s.staffId, { ...s, homeCode: donor.code, homeName: donor.name })));

  return (
    <div className="grid grid-cols-1 lg:grid-cols-[minmax(0,1fr)_22rem] xl:grid-cols-[minmax(0,1fr)_24rem] gap-6 items-start">
      {/* Staff who can help */}
      <section aria-label="Staff who can help" className="min-w-0 space-y-6">
        <h4 className="sr-only">Staff who can help</h4>
        {options.donors.length === 0 && (
          <p className="text-sm text-gray-600 rounded-xl border border-gray-200 p-4">
            No other department has a free staff member who can sign in and be lent.
          </p>
        )}
        {options.donors.map((donor) => (
          <div key={donor.code} className="space-y-2.5">
            <div className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-0.5">
              <h5 className="text-sm font-semibold text-gray-900">{donor.name}</h5>
              <p className="text-xs text-gray-500">
                {donor.freeStaff} free of {donor.availableStaff} available · {donor.waitingTasks} waiting
                {donor.canLend && <span className="font-medium text-gray-700"> · can lend {capacityLeft[donor.code] ?? donor.maxLendable} more</span>}
              </p>
            </div>
            {!donor.canLend && (
              <p className="flex items-start gap-1.5 text-xs text-amber-800 rounded-lg bg-amber-50 border border-amber-200 px-2.5 py-1.5">
                <svg aria-hidden="true" viewBox="0 0 20 20" fill="currentColor" className="w-3.5 h-3.5 mt-px shrink-0"><path fillRule="evenodd" d="M8.26 3.1c.75-1.34 2.73-1.34 3.48 0l6.1 10.9c.74 1.33-.22 2.99-1.74 2.99H3.9c-1.52 0-2.48-1.66-1.74-2.99l6.1-10.9zM10 7a1 1 0 00-1 1v3a1 1 0 102 0V8a1 1 0 00-1-1zm0 7.5a1.1 1.1 0 100-2.2 1.1 1.1 0 000 2.2z" clipRule="evenodd" /></svg>
                <span>{donor.note}{donor.overrideLendable > 0 ? ' You can still add them after acknowledging the staffing risk.' : ''}</span>
              </p>
            )}
            <ul className="grid grid-cols-[repeat(auto-fill,minmax(min(100%,13.5rem),1fr))] gap-2.5">
              {donor.staff.map((person) => {
                const inPlan = plan.some((pair) => pair.staffId === person.staffId);
                const blocked = !inPlan && whyCannotAdd(options, plan, donor.code, person.staffId);
                const suggested = !inPlan && suggestedIds.has(person.staffId);
                // Allowed, but only after the manager acknowledges that the department is left without a free person
                const risky = needsReserveOverride(options, plan, donor.code, person.staffId);
                return (
                  <li
                    key={person.staffId}
                    className={`flex flex-col gap-2.5 rounded-xl border p-3 transition-colors ${
                      inPlan ? 'border-emerald-500 bg-emerald-50/50'
                        : blocked ? 'border-gray-200 bg-gray-50/60 opacity-70'
                        : 'border-gray-200 bg-white hover:border-gray-300 hover:bg-gray-50/50'
                    }`}
                  >
                    <div className="flex items-start gap-2.5">
                      <Avatar name={person.name} active={inPlan} />
                      <div className="min-w-0 flex-1">
                        <p className="text-sm font-semibold text-gray-900 leading-5 break-words">{person.name}</p>
                        <div className="mt-0.5 flex items-center justify-between gap-2 min-h-[1.25rem]">
                          <p className="flex items-center gap-1.5 text-xs font-medium text-emerald-700 leading-4">
                            <span aria-hidden="true" className="w-1.5 h-1.5 rounded-full bg-emerald-500 shrink-0" />
                            Available
                          </p>
                          {inPlan ? <SupportStageBadge stage="selected" title="In your plan, not confirmed" /> : suggested ? <SupportStageBadge stage="recommended" title="Recommended by Taskotel" /> : null}
                        </div>
                        <p className="text-xs text-gray-500 leading-4">{person.activeTaskCount === 0 ? 'No active task' : `${person.activeTaskCount} active`} · {formatMinutes(person.totalEstimatedDuration)}</p>
                      </div>
                    </div>
                    {blocked && <p className="text-[11px] leading-4 text-amber-800">{blocked}</p>}
                    {risky && <p className="text-[11px] leading-4 text-amber-800">Last free person in {donor.name}. Needs your acknowledgement.</p>}
                    {inPlan && plan.find((pair) => pair.staffId === person.staffId)?.reserveAcknowledged && (
                      <p className="text-[11px] leading-4 text-amber-800">Reserve override acknowledged.</p>
                    )}
                    <button
                      type="button"
                      aria-pressed={inPlan}
                      disabled={disabled || Boolean(blocked)}
                      title={blocked || undefined}
                      onClick={() => {
                        if (inPlan) onRemove(person.staffId);
                        else if (risky) onRequestOverride(donor.code, person.staffId);
                        else onAdd(donor.code, person.staffId);
                      }}
                      className={`mt-auto w-full px-2 py-1.5 text-xs font-medium rounded-lg border transition-colors cursor-pointer disabled:cursor-not-allowed disabled:opacity-60 ${focusRing} ${
                        inPlan ? 'border-gray-300 text-gray-700 hover:bg-gray-100' : 'border-gray-300 text-gray-800 hover:border-emerald-500 hover:text-emerald-700 disabled:hover:border-gray-300 disabled:hover:text-gray-800'
                      }`}
                    >
                      {inPlan ? 'Remove from plan' : 'Add to plan'}
                    </button>
                  </li>
                );
              })}
            </ul>
          </div>
        ))}
        <p className="text-xs text-gray-500">
          Skills and shifts are not recorded, so suitability for the task is not verified. You decide who can do the work.
        </p>
      </section>

      {/* The plan */}
      <section
        aria-label="Support plan"
        className="min-w-0 rounded-2xl border border-gray-200 bg-gray-50 flex flex-col"
      >
        <div className="p-4 pb-3 space-y-3 shrink-0">
          <div className="flex items-start justify-between gap-3">
            <div className="min-w-0">
              <h4 className="text-sm font-semibold text-gray-900">Your plan</h4>
              <p className="mt-0.5 text-xs text-gray-500">{plan.length === 0 ? 'No helpers selected' : `${plan.length} of ${options.maxHelpers} helper${options.maxHelpers === 1 ? '' : 's'} selected`}</p>
            </div>
            <span className="inline-flex items-center gap-1.5 shrink-0 rounded-md border border-gray-300 px-1.5 py-px text-[11px] font-semibold text-gray-700 leading-4">
              <span aria-hidden="true" className="w-1.5 h-1.5 rounded-full bg-amber-500" />
              Draft: {plan.length} selected, not confirmed
            </span>
          </div>
          {(suggestions.length > 0 || plan.length > 0) && (
            <div className="flex gap-2">
              {suggestions.length > 0 && (
                <button type="button" disabled={disabled} onClick={onUseSuggestions} className={`px-2.5 py-1 text-xs font-medium whitespace-nowrap text-emerald-700 border border-emerald-600/60 rounded-lg hover:bg-emerald-50 cursor-pointer disabled:opacity-50 ${focusRing}`}>
                  Use suggestions
                </button>
              )}
              {plan.length > 0 && (
                <button type="button" disabled={disabled} onClick={onClear} className={`px-2.5 py-1 text-xs font-medium whitespace-nowrap text-gray-600 border border-gray-300 rounded-lg hover:bg-gray-100 cursor-pointer disabled:opacity-50 ${focusRing}`}>
                  Clear
                </button>
              )}
            </div>
          )}
          {notice && <p role="status" className="text-xs text-amber-800 rounded-lg bg-amber-50 border border-amber-200 px-2.5 py-1.5">{notice}</p>}
        </div>

        <div className="px-4">
          {plan.length === 0 && (
            <div className="rounded-xl border border-dashed border-gray-300 px-4 py-5 text-center">
              <p className="text-sm font-medium text-gray-800">Choose who will help</p>
              <p className="mt-0.5 text-xs text-gray-500">Add staff from the list. Each person helps with one waiting task. Nothing is assigned until you confirm.</p>
            </div>
          )}
          <ul className="space-y-2">
            {plan.map((pair) => {
              const offered = staffById.get(pair.staffId);
              // A person who stopped being offered (for example, now busy) keeps their name from when they were added
              const person = offered || knownStaff?.get(pair.staffId);
              const usedElsewhere = new Set(plan.filter((p) => p.staffId !== pair.staffId).map((p) => p.taskId));
              const selectId = `support-task-${pair.staffId}`;
              return (
                <li key={pair.staffId} className="rounded-xl border border-gray-200 bg-white p-3 space-y-2">
                  <div className="flex items-center gap-2.5">
                    <Avatar name={person?.name} active />
                    <p className="min-w-0 flex-1 text-sm font-semibold text-gray-900 leading-5">
                      {person?.name || 'Staff member'}
                      <span className="block text-xs font-normal text-gray-500">from {person?.homeName || 'another department'}</span>
                    </p>
                    <button
                      type="button"
                      disabled={disabled}
                      onClick={() => onRemove(pair.staffId)}
                      aria-label={`Remove ${person?.name || 'staff member'} from the plan`}
                      className={`w-7 h-7 shrink-0 rounded-lg text-gray-400 hover:text-gray-700 hover:bg-gray-100 cursor-pointer text-lg leading-none disabled:opacity-50 ${focusRing}`}
                    >
                      ×
                    </button>
                  </div>
                  {!offered && <p className="text-xs text-amber-800">No longer available to lend. Remove them from the plan.</p>}
                  {pair.reserveAcknowledged && offered && (
                    <p className="text-xs text-amber-800">Staffing risk acknowledged: {person?.homeName || 'their department'} may have no free staff left.</p>
                  )}
                  <div>
                    <label htmlFor={selectId} className="block text-[11px] font-medium text-gray-500 mb-0.5">Helps with</label>
                    <select
                      id={selectId}
                      value={pair.taskId || ''}
                      disabled={disabled}
                      onChange={(e) => onSetTask(pair.staffId, e.target.value)}
                      className="w-full h-9 px-2 bg-white border border-gray-200 rounded-lg text-sm text-gray-800 focus:outline-none focus:ring-2 focus:ring-emerald-500"
                    >
                      {options.tasks.map((task) => (
                        <option key={task.id} value={task.id} disabled={usedElsewhere.has(task.id)}>
                          {taskLabel(task)}
                        </option>
                      ))}
                    </select>
                  </div>
                </li>
              );
            })}
          </ul>
        </div>

        {planFooter && <div className="p-4 pt-3 mt-3 border-t border-gray-200 shrink-0">{planFooter}</div>}
        {!planFooter && <div className="pb-4" />}
      </section>
    </div>
  );
}
