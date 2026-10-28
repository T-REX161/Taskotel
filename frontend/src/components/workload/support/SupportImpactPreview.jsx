import React from 'react';
import { beforeAfter, describeProblems, formatMinutes } from '../../../utils/supportPlan';

const Row = ({ label, before, after, format = String, emphasize = false }) => {
  const changed = before !== after;
  return (
    <tr className="border-t border-gray-100 first:border-t-0">
      <th scope="row" className="py-1 pr-3 text-left font-normal text-gray-600">{label}</th>
      <td className={`py-1 text-right tabular-nums ${emphasize && changed ? 'font-semibold text-gray-900' : 'text-gray-700'}`}>
        {changed ? `${format(before)} → ${format(after)}` : format(before)}
      </td>
    </tr>
  );
};

function ImpactTable({ impact, title, note }) {
  const { before, after } = impact;
  return (
    <div className="min-w-0 rounded-lg border border-gray-200 bg-white p-2.5">
      <p className="text-xs font-semibold text-gray-900">{title}</p>
      {note && <p className="text-[11px] text-gray-500 mb-1">{note}</p>}
      <table className="w-full text-xs">
        <tbody>
          <Row label="Waiting tasks" before={before.waitingTasks} after={after.waitingTasks} emphasize />
          <Row label="Waiting minutes" before={before.waitingMinutes} after={after.waitingMinutes} format={formatMinutes} emphasize />
          <Row label="Assigned minutes" before={before.assignedMinutes} after={after.assignedMinutes} format={formatMinutes} emphasize />
          <Row label="Free staff" before={before.freeStaff} after={after.freeStaff} emphasize />
          <Row label="Uncovered tasks" before={before.uncoveredTasks} after={after.uncoveredTasks} emphasize />
        </tbody>
      </table>
    </div>
  );
}

const Headline = ({ label, value, hint }) => (
  <div className="flex items-baseline justify-between gap-3 text-xs">
    <dt className="text-gray-600 min-w-0">{label}{hint && <span className="block text-[11px] text-gray-500">{hint}</span>}</dt>
    <dd className="font-semibold text-gray-900 tabular-nums whitespace-nowrap">{value}</dd>
  </div>
);

/**
 * Step 4 and 5: the live preview of the plan from the server, and the one action that moves on to confirmation. It sits at
 * the foot of the plan panel. A preview is a calculation only: nothing has been assigned until the dialog is confirmed.
 */
export default function SupportImpactPreview({ preview, receivingName, canReview, empty = false, onReview, onAcknowledge }) {
  const { status, data, error } = preview;
  const button = (
    <button
      type="button"
      disabled={!canReview}
      onClick={onReview}
      className="w-full px-4 py-2 text-sm font-medium text-white bg-emerald-600 hover:bg-emerald-700 rounded-lg cursor-pointer disabled:opacity-50 disabled:cursor-not-allowed focus:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 focus-visible:ring-offset-1"
    >
      Review &amp; confirm
    </button>
  );

  if (empty) {
    return (
      <section aria-label="Workload impact preview" className="space-y-2">
        {button}
        <p className="text-[11px] text-gray-500 text-center">Add at least one helper to see the impact.</p>
      </section>
    );
  }

  return (
    <section aria-label="Workload impact preview" className="space-y-3" aria-live="polite">
      <h4 className="text-xs font-semibold uppercase tracking-wide text-gray-500">Impact preview</h4>

      {status === 'loading' && <p role="status" className="text-sm text-gray-500">Calculating the impact…</p>}
      {status === 'error' && <p role="alert" className="text-sm text-red-700">Could not preview this plan. {error}</p>}

      {status === 'ready' && data && (
        <>
          {!data.valid && (
            <div role="alert" className="rounded-lg border border-amber-300 bg-amber-50 p-2.5 text-xs text-amber-900">
              <p className="font-semibold">This plan cannot be confirmed yet:</p>
              <ul className="list-disc pl-4 mt-1 space-y-0.5 marker:text-amber-600">
                {describeProblems(data.problems).map((line) => (<li key={line}>{line}</li>))}
              </ul>
              {data.canOverrideReserve === true && (
                <div className="mt-2 pt-2 border-t border-amber-200 space-y-1.5">
                  <p>Only the department's reserve blocks this plan. As a manager you can acknowledge the staffing risk and continue.</p>
                  <button
                    type="button"
                    onClick={onAcknowledge}
                    className="px-3 py-1 text-xs font-medium text-amber-900 border border-amber-400 rounded-lg hover:bg-amber-100 cursor-pointer focus:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 focus-visible:ring-offset-1"
                  >
                    Review staffing risk
                  </button>
                </div>
              )}
            </div>
          )}
          {data.warnings?.length > 0 && (
            <div role="status" aria-label="Staffing risk" className="rounded-lg border border-amber-300 bg-amber-50 p-2.5 text-xs text-amber-900">
              <p className="font-semibold">Staffing risk acknowledged</p>
              <ul className="list-disc pl-4 mt-1 space-y-0.5 marker:text-amber-600">
                {describeProblems(data.warnings).map((line) => (<li key={line}>{line}</li>))}
              </ul>
            </div>
          )}
          {data.receiving && (
            <>
              <dl className="space-y-1.5">
                <Headline label={`${receivingName} waiting tasks`} value={beforeAfter(data.receiving.before.waitingTasks, data.receiving.after.waitingTasks)} />
                <Headline label={`${receivingName} uncovered`} value={beforeAfter(data.receiving.before.uncoveredTasks, data.receiving.after.uncoveredTasks)} />
                {data.donors.map((donor) => (
                  <Headline
                    key={donor.code}
                    label={`${donor.name} free staff`}
                    hint={`Lending ${donor.lending}; must keep at least ${donor.reserveRequired} free`}
                    value={beforeAfter(donor.before.freeStaff, donor.after.freeStaff)}
                  />
                ))}
              </dl>
              <details className="text-xs">
                <summary className="cursor-pointer font-medium text-gray-600 w-fit rounded focus:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500">Full breakdown</summary>
                <div className="mt-2 space-y-2">
                  <ImpactTable impact={data.receiving} title={`${receivingName} (receiving)`} note={`${data.receiving.lending} helper${data.receiving.lending === 1 ? '' : 's'}; their task minutes count here`} />
                  {data.donors.map((donor) => (
                    <ImpactTable
                      key={donor.code}
                      impact={donor}
                      title={`${donor.name} (lending ${donor.lending})`}
                      note={`Must keep at least ${donor.reserveRequired} free · ${beforeAfter(donor.before.freeStaff, donor.after.freeStaff)} free after`}
                    />
                  ))}
                </div>
              </details>
            </>
          )}
          {data.valid && (
            <div className="space-y-2">
              {button}
              <p className="text-[11px] text-gray-500 text-center">Support lasts until each task is completed. Staff stay in their home department.</p>
            </div>
          )}
        </>
      )}
    </section>
  );
}
