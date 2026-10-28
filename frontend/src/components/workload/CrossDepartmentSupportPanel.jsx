import React, { useCallback, useEffect, useMemo, useReducer, useRef, useState } from 'react';
import {
  archiveSupportAssignment,
  confirmSupportPlan,
  endSupportAssignment,
  getSupportAssignments,
  getUserRole,
  getSupportOptions,
  getWorkloadRecommendations,
  previewSupportPlan,
} from '../../services/api';
import { getSeverityLabel, getSeverityTone, normalizeRecommendations } from '../../utils/supportRecommendations';
import { departmentLabel } from '../../utils/departmentRegistry';
import {
  SUPPORT_STAGES,
  archiveReducer,
  buildPlanRequest,
  canReview,
  describeArchiveOutcome,
  initialArchiveState,
  describeEndOutcome,
  describeProblems,
  endReducer,
  formatMinutes,
  initialEndState,
  initialSupportState,
  planSignature,
  reserveRiskRows,
  supportHistoryView,
  supportReducer,
} from '../../utils/supportPlan';
import SupportStageBadge from './support/SupportStageBadge';
import SupportNeedsList from './support/SupportNeedsList';
import SupportPlanBuilder from './support/SupportPlanBuilder';
import SupportImpactPreview from './support/SupportImpactPreview';
import SupportConfirmDialog from './support/SupportConfirmDialog';
import SupportEndDialog from './support/SupportEndDialog';
import SupportDiscardDialog from './support/SupportDiscardDialog';
import SupportArchiveDialog from './support/SupportArchiveDialog';
import SupportReserveOverrideDialog from './support/SupportReserveOverrideDialog';
import SupportHistory from './support/SupportHistory';
import SupportOverview from './support/SupportOverview';

const PREVIEW_DEBOUNCE_MS = 300;

const Stat = ({ label, value }) => (
  <div className="min-w-0">
    <p className="text-[11px] text-gray-500">{label}</p>
    <p className="text-sm font-semibold text-gray-900">{value}</p>
  </div>
);

/**
 * Cross-Department Support: a manager-controlled workflow for lending available staff to an overloaded department.
 *
 *   1. choose an overloaded department   2. choose staff from other departments   3. choose a task for each person
 *   4. see the workload impact           5. review and explicitly confirm          6. refresh and track until done
 *
 * Nothing changes until the manager confirms; the server re-checks everything at that moment. Skills and shifts are not
 * recorded in Taskotel, so suitability is the manager's call. `onChanged` lets the page refresh its own workload data.
 */
export default function CrossDepartmentSupportPanel({ onChanged }) {
  const [state, dispatch] = useReducer(supportReducer, initialSupportState);
  const [endState, endDispatch] = useReducer(endReducer, initialEndState);
  const endInFlight = useRef(false);
  const [archiveState, archiveDispatch] = useReducer(archiveReducer, initialArchiveState);
  const archiveInFlight = useRef(false);
  const [needs, setNeeds] = useState({ status: 'loading', data: null, error: '' });
  const [options, setOptions] = useState({ status: 'idle', data: null, error: '' });
  const [history, setHistory] = useState({ status: 'loading', data: [], error: '' });
  const requestCounters = useRef({ needs: 0, options: 0, history: 0, preview: 0 });
  // Names of everyone ever offered, so a plan row still reads well after that person stops being offered
  const knownStaff = useRef(new Map());
  const mounted = useRef(true);

  useEffect(() => {
    mounted.current = true;
    return () => { mounted.current = false; };
  }, []);

  const loadNeeds = useCallback(async () => {
    const id = ++requestCounters.current.needs;
    setNeeds((prev) => ({ ...prev, status: 'loading', error: '' }));
    try {
      const data = normalizeRecommendations(await getWorkloadRecommendations());
      if (mounted.current && id === requestCounters.current.needs) setNeeds({ status: 'ready', data, error: '' });
    } catch (err) {
      if (mounted.current && id === requestCounters.current.needs) setNeeds({ status: 'error', data: null, error: err?.message || 'Unable to load recommendations.' });
    }
  }, []);

  const loadHistory = useCallback(async () => {
    const id = ++requestCounters.current.history;
    try {
      const rows = await getSupportAssignments();
      if (mounted.current && id === requestCounters.current.history) setHistory({ status: 'ready', data: Array.isArray(rows) ? rows : [], error: '' });
    } catch (err) {
      if (mounted.current && id === requestCounters.current.history) setHistory({ status: 'error', data: [], error: err?.message || '' });
    }
  }, []);

  const loadOptions = useCallback(async (code) => {
    const id = ++requestCounters.current.options;
    if (!code) { setOptions({ status: 'idle', data: null, error: '' }); return; }
    setOptions((prev) => ({ ...prev, status: 'loading', error: '' }));
    try {
      const data = await getSupportOptions(code);
      data.donors.forEach((donor) => donor.staff.forEach((s) => knownStaff.current.set(s.staffId, { name: s.name, homeName: donor.name })));
      if (mounted.current && id === requestCounters.current.options) setOptions({ status: 'ready', data, error: '' });
    } catch (err) {
      if (mounted.current && id === requestCounters.current.options) setOptions({ status: 'error', data: null, error: err?.message || 'Unable to load this department.' });
    }
  }, []);

  useEffect(() => { loadNeeds(); loadHistory(); }, [loadNeeds, loadHistory]);
  useEffect(() => { loadOptions(state.department); }, [state.department, loadOptions]);

  // Live preview: recalculated shortly after the plan stops changing; an answer for an older plan is ignored
  const signature = planSignature(state.department, state.plan);
  useEffect(() => {
    if (state.preview.status !== 'loading' || state.plan.length === 0) return undefined;
    const id = ++requestCounters.current.preview;
    const timer = setTimeout(async () => {
      try {
        const preview = await previewSupportPlan(buildPlanRequest(state));
        if (mounted.current && id === requestCounters.current.preview) dispatch({ type: 'PREVIEW_READY', signature, preview });
      } catch (err) {
        if (mounted.current && id === requestCounters.current.preview) dispatch({ type: 'PREVIEW_FAILED', signature, message: err?.message || '' });
      }
    }, PREVIEW_DEBOUNCE_MS);
    return () => clearTimeout(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [signature, state.preview.status]);

  const departments = useMemo(() => {
    if (!needs.data) return [];
    const supported = needs.data.recommendations.map((rec) => ({ ...rec, suggestionCount: rec.suggestions.length }));
    const unsupported = needs.data.unsupported.map((item) => ({ ...item, suggestionCount: 0 }));
    return [...supported, ...unsupported];
  }, [needs.data]);

  const departmentNames = useMemo(() => {
    const names = new Map(departments.map((d) => [d.departmentCode, d.departmentName]));
    (options.data?.donors || []).forEach((d) => names.set(d.code, d.name));
    if (options.data?.receiving) names.set(options.data.receiving.code, options.data.receiving.name);
    // Falls back to the app-wide department registry, so a code is never shown raw
    return (code) => names.get(code) || departmentLabel(code);
  }, [departments, options.data]);

  const selectedNeed = needs.data?.recommendations.find((r) => r.departmentCode === state.department);
  const suggestions = selectedNeed?.suggestions || [];

  const refreshAll = useCallback(() => {
    loadNeeds();
    loadHistory();
    loadOptions(state.department);
  }, [loadNeeds, loadHistory, loadOptions, state.department]);

  const handleConfirm = async () => {
    dispatch({ type: 'CONFIRM_START' });
    try {
      const created = await confirmSupportPlan(buildPlanRequest(state, state.preview.data?.fingerprint));
      dispatch({ type: 'CONFIRM_SUCCEEDED', created });
      loadNeeds();
      loadHistory();
      loadOptions(state.department);
      if (onChanged) onChanged();
    } catch (err) {
      const problems = err?.data?.problems || [];
      dispatch({
        type: 'CONFIRM_FAILED',
        message: err?.message || 'The plan could not be confirmed.',
        problems,
        stale: problems.some((p) => p.code === 'STALE_PLAN'),
      });
      // What was on screen was out of date: reload everything it was built from
      loadOptions(state.department);
      loadNeeds();
    }
  };

  const handleEnd = async () => {
    // A second click before the screen re-renders must not send a second request
    if (endInFlight.current || !endState.row) return;
    endInFlight.current = true;
    const row = endState.row;
    endDispatch({ type: 'END_START' });
    try {
      const result = await endSupportAssignment(row.id);
      endDispatch({ type: 'END_SUCCEEDED', row: result });
      if (onChanged) onChanged();
    } catch (err) {
      endDispatch({ type: 'END_FAILED', message: err?.message, code: err?.data?.problems?.[0]?.code, httpStatus: err?.status });
    } finally {
      endInFlight.current = false;
      // Whatever happened, what is on screen is recalculated from the server: history, needs, workload attribution and who can be lent
      loadNeeds();
      loadHistory();
      loadOptions(state.department);
    }
  };

  // Removes an ended record from the history. The server archives it; the list is always reloaded from the server afterwards
  const handleArchive = async () => {
    if (archiveInFlight.current || !archiveState.row) return;
    archiveInFlight.current = true;
    const row = archiveState.row;
    archiveDispatch({ type: 'ARCHIVE_START' });
    try {
      const result = await archiveSupportAssignment(row.id);
      archiveDispatch({ type: 'ARCHIVE_SUCCEEDED', row: { ...row, ...result } });
    } catch (err) {
      archiveDispatch({ type: 'ARCHIVE_FAILED', message: err?.message, code: err?.data?.problems?.[0]?.code, httpStatus: err?.status });
    } finally {
      archiveInFlight.current = false;
      loadHistory();
    }
  };

  const canEnd = ['ADMIN', 'MANAGER'].includes(getUserRole());
  const historyView = supportHistoryView(history);
  const confirming = state.confirm.status === 'reviewing' || state.confirm.status === 'submitting';
  const receiving = options.data?.receiving;

  return (
    <section
      className="min-w-0 bg-white rounded-2xl shadow-2xs border border-gray-100 px-4 pt-4 pb-5 sm:px-6 sm:pt-5 sm:pb-6 space-y-6"
      aria-label="Cross-department support"
    >
      {/* Header */}
      <div className="flex flex-wrap items-start justify-between gap-x-6 gap-y-2">
        <div className="min-w-0">
          <h2 className="text-base sm:text-lg font-bold text-gray-900">Cross-Department Support</h2>
          <p className="text-xs sm:text-sm text-gray-500 mt-0.5">Lend available staff to an overloaded department until a task is completed.</p>
        </div>
        <button
          type="button"
          onClick={refreshAll}
          disabled={needs.status === 'loading'}
          className="inline-flex items-center gap-1.5 px-2.5 py-1 text-xs font-medium text-gray-600 border border-gray-200 rounded-lg hover:bg-gray-50 cursor-pointer disabled:cursor-wait focus:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 focus-visible:ring-offset-1"
        >
          <svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className={`w-3.5 h-3.5 ${needs.status === 'loading' ? 'animate-spin' : ''}`}>
            <path d="M21 12a9 9 0 1 1-2.64-6.36" /><path d="M21 3v6h-6" />
          </svg>
          Refresh
        </button>
      </div>

      {/* What the labels mean, and what is not taken into account: one compact disclosure instead of a legend */}
      <details className="text-xs text-gray-500 -mt-2">
        <summary className="cursor-pointer font-medium text-gray-600 w-fit focus:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 rounded">How this works</summary>
        <ul className="mt-2 grid gap-x-6 gap-y-1.5 sm:grid-cols-2" aria-label="What the labels mean">
          {SUPPORT_STAGES.map((stage) => (
            <li key={stage.key} className="flex items-start gap-2"><span className="shrink-0 w-[4.25rem]"><SupportStageBadge stage={stage.key} /></span><span>{stage.hint}</span></li>
          ))}
        </ul>
        {needs.data && needs.data.limitations.length > 0 && (
          <div className="mt-3">
            <p className="font-medium text-gray-600">What these suggestions do not take into account</p>
            <ul className="list-disc pl-5 mt-1 space-y-0.5">
              {needs.data.limitations.map((limitation) => (<li key={limitation}>{limitation}</li>))}
            </ul>
          </div>
        )}
      </details>

      <SupportOverview needs={needs} history={history} />

      {/* Outcome of the last confirmation */}
      {state.result && (
        <div role="status" className="rounded-lg border border-emerald-200 bg-emerald-50 p-3 text-sm text-emerald-900 flex flex-wrap items-start justify-between gap-2">
          <div>
            <p className="font-semibold">Support confirmed: {state.result.created.length} assignment{state.result.created.length === 1 ? '' : 's'} created.</p>
            <ul className="list-disc pl-5 text-xs mt-0.5">
              {state.result.created.map((row) => (<li key={row.id}>{row.staffName} ({departmentNames(row.homeDepartmentCode)}) → {row.taskTitle}</li>))}
            </ul>
            {state.result.created.some((row) => row.reserveOverride === true) && (
              <p className="text-xs mt-1 text-amber-900">
                Reserve override acknowledged for {[...new Set(state.result.created.filter((row) => row.reserveOverride === true).map((row) => departmentNames(row.homeDepartmentCode)))].join(', ')}:
                {' '}it was left below its usual reserve of free staff.
              </p>
            )}
          </div>
          <button type="button" onClick={() => dispatch({ type: 'DISMISS_RESULT' })} className="text-xs underline cursor-pointer">Dismiss</button>
        </div>
      )}
      {endState.status === 'done' && (
        <div role="status" className="rounded-lg border border-emerald-200 bg-emerald-50 p-3 text-sm text-emerald-900 flex flex-wrap items-start justify-between gap-2">
          <p>{describeEndOutcome(endState.result, departmentNames)}</p>
          <button type="button" onClick={() => endDispatch({ type: 'END_DISMISS' })} className="text-xs underline cursor-pointer">Dismiss</button>
        </div>
      )}
      {archiveState.status === 'done' && (
        <div role="status" className="rounded-lg border border-emerald-200 bg-emerald-50 p-3 text-sm text-emerald-900 flex flex-wrap items-start justify-between gap-2">
          <p>{describeArchiveOutcome(archiveState.row)}</p>
          <button type="button" onClick={() => archiveDispatch({ type: 'ARCHIVE_DISMISS' })} className="text-xs underline cursor-pointer">Dismiss</button>
        </div>
      )}
      {state.confirm.status === 'error' && (
        <div role="alert" className="rounded-lg border border-red-200 bg-red-50 p-3 text-sm text-red-800 flex flex-wrap items-start justify-between gap-2">
          <div>
            <p className="font-semibold">{state.confirm.stale ? 'The plan was out of date, so nothing was created.' : 'Nothing was created.'}</p>
            <p className="text-xs mt-0.5">{state.confirm.error}</p>
            {state.confirm.problems.length > 1 && (
              <ul className="list-disc pl-5 text-xs mt-0.5">{describeProblems(state.confirm.problems).map((line) => (<li key={line}>{line}</li>))}</ul>
            )}
            <p className="text-xs mt-0.5">The preview below has been recalculated. Review it and confirm again.</p>
          </div>
          <button type="button" onClick={() => dispatch({ type: 'DISMISS_RESULT' })} className="text-xs underline cursor-pointer">Dismiss</button>
        </div>
      )}

      {/* Step 1: overloaded departments */}
      <div className="space-y-3">
        <h3 className="text-sm font-semibold text-gray-900">Departments that need support</h3>
        {needs.status === 'loading' && !needs.data && <p role="status" className="text-sm text-gray-500">Calculating recommendations…</p>}
        {needs.status === 'error' && (
          <div role="alert" className="rounded-lg border border-red-200 bg-red-50 p-3 text-sm text-red-800 flex flex-wrap items-center justify-between gap-2">
            <span>Could not load recommendations. {needs.error}</span>
            <button type="button" onClick={loadNeeds} className="text-xs font-semibold underline cursor-pointer">Try again</button>
          </div>
        )}
        {needs.status !== 'error' && needs.data && departments.length === 0 && (
          <p className="text-sm text-gray-600 rounded-lg border border-gray-100 bg-gray-50/60 p-4 text-center">
            No department currently has more waiting work than free staff, so no support is needed.
          </p>
        )}
        {departments.length > 0 && (
          <SupportNeedsList
            departments={departments.map((d) => ({ code: d.departmentCode, name: d.departmentName, severity: d.severity, pressure: d.pressure, suggestionCount: d.suggestionCount }))}
            selectedCode={state.department}
            onSelect={(code) => dispatch(code === state.department ? { type: 'REQUEST_DESELECT' } : { type: 'SELECT_DEPARTMENT', code })}
          />
        )}
      </div>

      {/* Steps 2 to 5 for the chosen department */}
      {state.department && (
        <div className="space-y-5 border-t border-gray-200 pt-6">
          {options.status === 'loading' && !options.data && <p role="status" className="text-sm text-gray-500">Loading staff and tasks…</p>}
          {options.status === 'error' && (
            <div role="alert" className="rounded-lg border border-red-200 bg-red-50 p-3 text-sm text-red-800 flex flex-wrap items-center justify-between gap-2">
              <span>Could not load this department. {options.error}</span>
              <button type="button" onClick={() => loadOptions(state.department)} className="text-xs font-semibold underline cursor-pointer">Try again</button>
            </div>
          )}
          {options.data && receiving && (
            <>
              <div className="flex items-center justify-between gap-3 -mb-2">
                <p className="text-[11px] font-semibold uppercase tracking-wide text-gray-500">Helping</p>
                <button
                  type="button"
                  onClick={() => dispatch({ type: 'REQUEST_DESELECT' })}
                  disabled={confirming}
                  className="inline-flex items-center gap-1 px-2 py-0.5 text-xs font-medium text-gray-600 hover:text-gray-900 hover:bg-gray-100 rounded-md cursor-pointer disabled:opacity-50 focus:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500"
                >
                  <svg aria-hidden="true" viewBox="0 0 12 12" className="w-2.5 h-2.5" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round"><path d="M3 3l6 6M9 3l-6 6" /></svg>
                  Clear selection
                </button>
              </div>
              <div className="flex flex-wrap items-center gap-x-8 gap-y-3">
                <div className="min-w-0">
                  <h3 className="text-lg font-bold text-gray-900 flex flex-wrap items-center gap-2 leading-7">
                    {receiving.name}
                    <span className={`px-1.5 py-px rounded-md border text-[11px] leading-4 font-semibold ${getSeverityTone(receiving.severity).chip}`}>{getSeverityLabel(receiving.severity)}</span>
                  </h3>
                </div>
                <div className="grid grid-cols-2 sm:grid-cols-5 gap-x-6 gap-y-3 flex-1 basis-full md:basis-0 md:min-w-[28rem]">
                  <Stat label="Waiting tasks" value={receiving.metrics.waitingTasks} />
                  <Stat label="Waiting minutes" value={formatMinutes(receiving.metrics.waitingMinutes)} />
                  <Stat label="Urgent / high" value={receiving.urgentOrHighTasks} />
                  <Stat label="Free staff" value={`${receiving.metrics.freeStaff} of ${receiving.metrics.availableStaff} available`} />
                  <Stat label="Uncovered tasks" value={receiving.metrics.uncoveredTasks} />
                </div>
              </div>

              {options.data.tasks.length === 0 ? (
                <p className="text-sm text-gray-600 rounded-lg border border-gray-100 bg-gray-50/60 p-4 text-center">
                  {receiving.name} has no waiting tasks left. Nothing to support.
                </p>
              ) : (
                <div className="space-y-3">
                  <h3 className="text-sm font-semibold text-gray-900">Choose staff and the task each will help with</h3>
                  <SupportPlanBuilder
                    options={options.data}
                    plan={state.plan}
                    suggestions={suggestions}
                    notice={state.notice}
                    knownStaff={knownStaff.current}
                    disabled={confirming}
                    planFooter={(
                      <SupportImpactPreview
                        preview={state.preview}
                        receivingName={receiving.name}
                        canReview={canReview(state)}
                        empty={state.plan.length === 0}
                        onReview={() => dispatch({ type: 'OPEN_REVIEW' })}
                        onAcknowledge={() => dispatch({ type: 'OPEN_RESERVE_PROMPT', options: options.data, prompt: { kind: 'plan' } })}
                      />
                    )}
                    onAdd={(donorCode, staffId) => dispatch({ type: 'ADD_STAFF', options: options.data, donorCode, staffId })}
                    onRequestOverride={(donorCode, staffId) => dispatch({ type: 'OPEN_RESERVE_PROMPT', options: options.data, prompt: { kind: 'add', donorCode, staffId } })}
                    onRemove={(staffId) => dispatch({ type: 'REMOVE_STAFF', staffId })}
                    onSetTask={(staffId, taskId) => dispatch({ type: 'SET_TASK', staffId, taskId })}
                    onUseSuggestions={() => dispatch({ type: 'USE_SUGGESTIONS', options: options.data, suggestions })}
                    onClear={() => dispatch({ type: 'CLEAR_PLAN' })}
                  />
                </div>
              )}
            </>
          )}
        </div>
      )}

      {/* The main section follows ACTIVE support only (see supportHistoryView); a draft plan never counts. With no active
          support, past records stay reachable in a small collapsed area. */}
      {historyView === 'section' && (
        <div>
          <SupportHistory
            history={history}
            departmentName={departmentNames}
            canEnd={canEnd}
            onEnd={(row) => endDispatch({ type: 'END_OPEN', row })}
            endingId={endState.status === 'submitting' ? endState.row?.id : null}
            onArchive={(row) => archiveDispatch({ type: 'ARCHIVE_OPEN', row })}
            archivingId={archiveState.status === 'submitting' ? archiveState.row?.id : null}
          />
        </div>
      )}
      {historyView === 'history' && (
        <details className="group rounded-xl border border-gray-200">
          <summary className="flex items-center justify-between gap-2 cursor-pointer select-none px-4 py-2.5 text-sm font-medium text-gray-600 hover:text-gray-900 rounded-xl focus:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500">
            <span>Support history ({history.data.length})</span>
            <span aria-hidden="true" className="text-xs text-gray-400 group-open:rotate-180 transition-transform">▾</span>
          </summary>
          <div className="px-3 pb-3">
            <SupportHistory
              history={history}
              departmentName={departmentNames}
              historyOnly
              canEnd={canEnd}
              onArchive={(row) => archiveDispatch({ type: 'ARCHIVE_OPEN', row })}
              archivingId={archiveState.status === 'submitting' ? archiveState.row?.id : null}
            />
          </div>
        </details>
      )}

      {['reviewing', 'submitting', 'error'].includes(endState.status) && endState.row && (
        <SupportEndDialog
          row={endState.row}
          departmentName={departmentNames}
          status={endState.status}
          error={endState.error}
          stale={endState.stale}
          onConfirm={handleEnd}
          onCancel={() => endDispatch({ type: 'END_CANCEL' })}
        />
      )}

      {['reviewing', 'submitting', 'error'].includes(archiveState.status) && archiveState.row && (
        <SupportArchiveDialog
          row={archiveState.row}
          departmentName={departmentNames}
          status={archiveState.status}
          error={archiveState.error}
          stale={archiveState.stale}
          onConfirm={handleArchive}
          onCancel={() => archiveDispatch({ type: 'ARCHIVE_CANCEL' })}
        />
      )}

      {state.reservePrompt && (
        <SupportReserveOverrideDialog
          rows={reserveRiskRows(state.reservePrompt, options.data, state.plan, state.preview.data)}
          onCancel={() => dispatch({ type: 'CANCEL_RESERVE_PROMPT' })}
          onContinue={() => dispatch({ type: 'CONFIRM_RESERVE_PROMPT', options: options.data })}
        />
      )}

      {state.discardPending && (
        <SupportDiscardDialog
          departmentName={receiving?.name || departmentNames(state.department)}
          count={state.plan.length}
          names={state.plan.map((pair) => knownStaff.current.get(pair.staffId)?.name).filter(Boolean)}
          onKeep={() => dispatch({ type: 'CANCEL_DISCARD' })}
          onDiscard={() => dispatch({ type: 'CONFIRM_DISCARD' })}
        />
      )}

      {confirming && state.preview.data && (
        <SupportConfirmDialog
          preview={state.preview.data}
          receivingName={receiving?.name || ''}
          submitting={state.confirm.status === 'submitting'}
          onConfirm={handleConfirm}
          onCancel={() => dispatch({ type: 'CANCEL_REVIEW' })}
        />
      )}
    </section>
  );
}
