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
import { getSeverityLabel, getSeverityTone, normalizeRecommendations, recommendationsTurnedOff } from '../../utils/supportRecommendations';
import { loadCrossDepartmentStatus } from '../../utils/crossDepartmentStatus';
import { CROSS_DEPARTMENT_REFRESH_MS, canCreateSupport, supportPanelMode } from '../../utils/crossDepartmentVisibility';
import { formatUtilization } from '../../utils/workloadUtils';
import { departmentLabel } from '../../utils/departmentRegistry';
import {
  SUPPORT_STAGES,
  archiveReducer,
  buildPlanRequest,
  canArchiveSupport,
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
  recordView,
  reserveRiskRows,
  supportHistoryView,
  supportPermissions,
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
import { Presence } from '../../theme/presence';

const PREVIEW_DEBOUNCE_MS = 300;

const Stat = ({ label, value }) => (
  <div className="min-w-0">
    <p className="text-[11px] text-gray-500">{label}</p>
    <p className="text-sm font-semibold text-gray-900 whitespace-nowrap">{value}</p>
  </div>
);

/**
 * Cross-Department Support: a manager-controlled workflow for lending available staff to an overloaded department.
 *
 *   1. choose an overloaded department   2. choose staff from other departments   3. choose a task for each person
 *   4. see the workload impact           5. review and explicitly confirm          6. refresh and track until done
 *
 * Nothing changes until an Admin confirms; the server re-checks everything at that moment. Skills and shifts are not
 * recorded in Taskotel, so suitability is the manager's call. `onChanged` lets the page refresh its own workload data.
 *
 * Who sees what (Step 2G-4; the backend is the authority, this only decides what is offered):
 *   Admin    every department, full figures, confirm, end and archive;
 *   Manager  each department they manage as receiver, plan and preview (lending figures only for departments they manage), no confirm,
 *            end and archive only on the records the server returns to them (those involving their department);
 *   Staff    nothing: the panel renders nothing (the Workload page also never mounts it for Staff).
 */
export default function CrossDepartmentSupportPanel(props) {
  if (!supportPermissions(getUserRole()).canView) return null;
  return <SupportPanelContent {...props} />;
}

function SupportPanelContent({ onChanged }) {
  const role = getUserRole();
  const permissions = supportPermissions(role);
  const [state, dispatch] = useReducer(supportReducer, initialSupportState);
  const [endState, endDispatch] = useReducer(endReducer, initialEndState);
  const endInFlight = useRef(false);
  const [archiveState, archiveDispatch] = useReducer(archiveReducer, initialArchiveState);
  const archiveInFlight = useRef(false);
  const [needs, setNeeds] = useState({ status: 'loading', data: null, error: '' });
  const [options, setOptions] = useState({ status: 'idle', data: null, error: '' });
  const [history, setHistory] = useState({ status: 'loading', data: [], error: '' });
  const requestCounters = useRef({ needs: 0, options: 0, history: 0, preview: 0, setting: 0 });
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
      data.donors.forEach((donor) => donor.staff.forEach((s) => knownStaff.current.set(s.staffId, { name: s.name, homeName: donor.name, profileImageUrl: s.profileImageUrl || null })));
      if (mounted.current && id === requestCounters.current.options) setOptions({ status: 'ready', data, error: '' });
    } catch (err) {
      if (mounted.current && id === requestCounters.current.options) setOptions({ status: 'error', data: null, error: err?.message || 'Unable to load this department.' });
    }
  }, []);

  // The Cross-Department Assistance switch in System Settings ('loading' | 'on' | 'off' | 'unknown'), from the narrow status route that Admin
  // and Manager may both read (the settings themselves stay Admin-only). A failed first read is 'unknown' (the full panel, as before: the server
  // still refuses a new plan on its own); a failed re-read keeps what was known, so a passing network error never flips the panel.
  const [assistance, setAssistance] = useState('loading');
  // The first read reuses the answer the Workload page already asked for (utils/crossDepartmentStatus); every later one is fresh.
  const loadCrossDepartmentSetting = useCallback(async ({ force = true } = {}) => {
    const id = ++requestCounters.current.setting;
    try {
      const next = await loadCrossDepartmentStatus({ force });
      if (mounted.current && id === requestCounters.current.setting) setAssistance((prev) => (next === 'unknown' && prev !== 'loading' ? prev : next));
    } catch {
      if (mounted.current && id === requestCounters.current.setting) setAssistance((prev) => (prev === 'loading' ? 'unknown' : prev));
    }
  }, []);

  useEffect(() => { loadNeeds(); loadHistory(); loadCrossDepartmentSetting({ force: false }); }, [loadNeeds, loadHistory, loadCrossDepartmentSetting]);

  // The switch can change while the page is open (an Admin in another tab or on another computer): read it again every 30 seconds while
  // the page is visible, and as soon as it becomes visible again.
  useEffect(() => {
    const readIfVisible = () => { if (document.visibilityState === 'visible') loadCrossDepartmentSetting(); };
    const timer = setInterval(readIfVisible, CROSS_DEPARTMENT_REFRESH_MS);
    document.addEventListener('visibilitychange', readIfVisible);
    return () => { clearInterval(timer); document.removeEventListener('visibilitychange', readIfVisible); };
  }, [loadCrossDepartmentSetting]);

  // While it is off, the arrangements still in progress are followed too, so the section goes away once the last one is completed
  useEffect(() => {
    if (assistance !== 'off') return undefined;
    const timer = setInterval(() => { if (document.visibilityState === 'visible') loadHistory(); }, CROSS_DEPARTMENT_REFRESH_MS);
    return () => clearInterval(timer);
  }, [assistance, loadHistory]);

  // When the switch changes, what the panel shows is read again (the first answer needs nothing extra: everything is already loading)
  const previousAssistance = useRef('loading');
  useEffect(() => {
    const previous = previousAssistance.current;
    previousAssistance.current = assistance;
    if (previous === 'loading' || previous === assistance) return;
    loadHistory();
    if (canCreateSupport(assistance)) loadNeeds();
  }, [assistance, loadHistory, loadNeeds]);
  useEffect(() => { loadOptions(state.department); }, [state.department, loadOptions]);

  // Live preview: recalculated shortly after the plan stops changing; an answer for an older plan is ignored
  const signature = planSignature(state.department, state.plan);
  useEffect(() => {
    // No new arrangement is previewed while cross-department assistance is off (the server would refuse it too)
    if (state.preview.status !== 'loading' || state.plan.length === 0 || !canCreateSupport(assistance)) return undefined;
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
  }, [signature, state.preview.status, assistance]);

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
    loadCrossDepartmentSetting();
    loadOptions(state.department);
  }, [loadNeeds, loadHistory, loadCrossDepartmentSetting, loadOptions, state.department]);

  const handleConfirm = async () => {
    // Confirmation is Admin-only: a Manager never sends it (the server would refuse it with 403 anyway). Nothing new is confirmed while
    // cross-department assistance is off (the server would refuse it with 409 anyway).
    if (!permissions.canConfirm || !canCreateSupport(assistance)) return;
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

  // Removes every listed ended record from the history, one by one through the same archive call as each row's trash button (the server
  // decides, so a record that is still active or already removed is refused and counted). The list is reloaded from the server afterwards.
  const [clearing, setClearing] = useState(false);
  const [clearNote, setClearNote] = useState('');
  const handleClearAll = async (rows) => {
    if (archiveInFlight.current || rows.length === 0) return;
    archiveInFlight.current = true;
    setClearing(true);
    let failed = 0;
    for (const row of rows) {
      try {
        await archiveSupportAssignment(row.id);
      } catch {
        failed += 1;
      }
    }
    archiveInFlight.current = false;
    setClearing(false);
    setClearNote(failed === 0
      ? `${rows.length} ${rows.length === 1 ? 'record' : 'records'} removed from the history.`
      : `${rows.length - failed} removed; ${failed} could not be removed (already removed, or not ended). Refreshing the list.`);
    loadHistory();
  };

  const [confirmClear, setConfirmClear] = useState(false);
  const clearableRows = (history.data || []).filter((row) => canArchiveSupport(row));

  const canEnd = ['ADMIN', 'MANAGER'].includes(getUserRole());
  const historyView = supportHistoryView(history);
  const confirming = state.confirm.status === 'reviewing' || state.confirm.status === 'submitting';
  const receiving = options.data?.receiving;

  // What to show for the System Settings switch (utils/crossDepartmentVisibility): the whole panel, only the arrangements still in progress,
  // or nothing. An end-of-support message keeps the section on screen until it is dismissed, even after the last arrangement ended.
  const mode = supportPanelMode({ assistance, history, keepOutcome: endState.status !== 'idle' });

  const refreshButton = (
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
  );
  const endOutcome = endState.status === 'done' && (
    <div role="status" className="rounded-lg border border-emerald-200 bg-emerald-50 p-3 text-sm text-emerald-900 flex flex-wrap items-start justify-between gap-2">
      <p>{describeEndOutcome(endState.result, departmentNames, recordView(endState.result, role))}</p>
      <button type="button" onClick={() => endDispatch({ type: 'END_DISMISS' })} className="text-xs underline cursor-pointer">Dismiss</button>
    </div>
  );
  const endDialog = (
    <Presence>
      {['reviewing', 'submitting', 'error'].includes(endState.status) && endState.row && (
        <SupportEndDialog
          row={endState.row}
          departmentName={departmentNames}
          view={recordView(endState.row, role)}
          status={endState.status}
          error={endState.error}
          stale={endState.stale}
          onConfirm={handleEnd}
          onCancel={() => endDispatch({ type: 'END_CANCEL' })}
        />
      )}
    </Presence>
  );

  if (mode === 'pending' || mode === 'hidden') return null;

  // Switched off with support still in progress: only those arrangements, to follow them and end them. Nothing new can be planned here.
  if (mode === 'manage') {
    return (
      <section
        data-testid="cross-department-manage"
        className="min-w-0 bg-white rounded-2xl shadow-2xs border border-gray-100 px-4 pt-4 pb-5 sm:px-6 sm:pt-5 sm:pb-6 space-y-4"
        aria-label="Active cross-department support"
      >
        <div className="flex flex-wrap items-start justify-between gap-x-6 gap-y-2">
          <div className="min-w-0">
            <h2 className="text-base sm:text-lg font-bold text-gray-900">Active Cross-Department Support</h2>
            <p className="text-xs sm:text-sm text-gray-500 mt-0.5">Staff still helping another department. Follow each arrangement here until it is completed or ended.</p>
          </div>
          {refreshButton}
        </div>
        {endOutcome}
        <SupportHistory
          history={history}
          departmentName={departmentNames}
          role={role}
          activeOnly
          canEnd={canEnd}
          onEnd={(row) => endDispatch({ type: 'END_OPEN', row })}
          endingId={endState.status === 'submitting' ? endState.row?.id : null}
        />
        {endDialog}
      </section>
    );
  }

  return (
    <section
      className="min-w-0 bg-white rounded-2xl shadow-2xs border border-gray-100 px-4 pt-4 pb-5 sm:px-6 sm:pt-5 sm:pb-6 space-y-6"
      aria-label="Cross-department support"
    >
      {/* Header */}
      <div className="flex flex-wrap items-start justify-between gap-x-6 gap-y-2">
        <div className="min-w-0">
          <h2 className="text-base sm:text-lg font-bold text-gray-900">Cross-Department Support</h2>
          <p className="text-xs sm:text-sm text-gray-500 mt-0.5">Lend available staff to a department that is over its capacity or short of free staff, until a task is completed.</p>
          {!permissions.canConfirm && (
            <p className="text-xs text-gray-500 mt-1">
              You can plan and preview support for the departments you manage and manage the support records that involve them. Only an Admin can confirm new support, and the staffing figures of departments you do not manage are not shown to your role.
            </p>
          )}
        </div>
        {refreshButton}
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
      {endOutcome}
      {archiveState.status === 'done' && (
        <div role="status" className="rounded-lg border border-emerald-200 bg-emerald-50 p-3 text-sm text-emerald-900 flex flex-wrap items-start justify-between gap-2">
          <p>{describeArchiveOutcome(archiveState.row, recordView(archiveState.row, role))}</p>
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
            {recommendationsTurnedOff(needs.data)
              ? 'Workload recommendations are turned off in System Settings, so no suggestions are shown.'
              : 'No department currently needs support: none has more waiting work than free staff, and none is at or above its capacity limit.'}
          </p>
        )}
        {departments.length > 0 && (
          <SupportNeedsList
            departments={departments.map((d) => ({ code: d.departmentCode, name: d.departmentName, severity: d.severity, pressure: d.pressure, suggestionCount: d.suggestionCount, reason: d.reason }))}
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
              <div className="space-y-3">
                <div className="min-w-0">
                  <h3 className="text-lg font-bold text-gray-900 flex flex-wrap items-center gap-2 leading-7">
                    {receiving.name}
                    <span className={`px-1.5 py-px rounded-md border text-[11px] leading-4 font-semibold ${getSeverityTone(receiving.severity).chip}`}>{getSeverityLabel(receiving.severity)}</span>
                  </h3>
                </div>
                <div className="grid grid-cols-2 sm:grid-cols-4 lg:grid-cols-7 gap-x-6 gap-y-3">
                  {Number.isFinite(receiving.metrics.utilizationPercent) && (
                    <Stat label="Utilization" value={`${formatUtilization(receiving.metrics.utilizationPercent)}${receiving.metrics.atRisk ? ' · at risk' : ''}`} />
                  )}
                  {Number.isFinite(receiving.metrics.remainingMinutes) && receiving.metrics.capacityMinutes > 0 && (
                    <Stat label="Capacity" value={`${receiving.metrics.remainingMinutes} / ${receiving.metrics.capacityMinutes} min`} />
                  )}
                  <Stat label="Waiting tasks" value={receiving.metrics.waitingTasks} />
                  <Stat label="Waiting minutes" value={formatMinutes(receiving.metrics.waitingMinutes)} />
                  <Stat label="Urgent / high" value={receiving.urgentOrHighTasks} />
                  <Stat label="Free staff" value={`${receiving.metrics.freeStaff} of ${receiving.metrics.availableStaff}`} />
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
                        canConfirm={permissions.canConfirm}
                        donorFiguresRestricted={role === 'MANAGER'}
                        onReview={() => dispatch({ type: 'OPEN_REVIEW', canConfirm: permissions.canConfirm })}
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
            role={role}
            canEnd={canEnd}
            onEnd={(row) => endDispatch({ type: 'END_OPEN', row })}
            endingId={endState.status === 'submitting' ? endState.row?.id : null}
            onArchive={(row) => archiveDispatch({ type: 'ARCHIVE_OPEN', row })}
            archivingId={archiveState.status === 'submitting' ? archiveState.row?.id : null}
            onClearAll={handleClearAll}
            clearing={clearing}
          />
        </div>
      )}
      {historyView === 'history' && (
        <details className="group tk-collapsible rounded-xl border border-gray-200">
          <summary className="flex items-center justify-between gap-2 cursor-pointer select-none px-4 py-2.5 text-sm font-medium text-gray-600 hover:text-gray-900 rounded-xl focus:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500">
            <span>Support history ({history.data.length})</span>
            <span className="flex items-center gap-2">
              {/* Clear all lives in this heading row so it takes no space of its own, and shows only once the list is open (group-open); a click here never opens or closes the list */}
              {canEnd && clearableRows.length > 0 && (confirmClear ? (
                <span className="hidden group-open:flex items-center gap-1.5" onClick={(event) => event.stopPropagation()}>
                  <span className="text-xs font-normal text-gray-600">Remove {clearableRows.length}?</span>
                  <button type="button" disabled={clearing} onClick={(event) => { event.preventDefault(); setConfirmClear(false); }}
                    className="px-2 py-0.5 text-xs font-medium text-gray-700 border border-gray-300 rounded-lg hover:bg-gray-50 cursor-pointer disabled:opacity-50">Cancel</button>
                  <button type="button" data-testid="clear-history-confirm" disabled={clearing}
                    onClick={async (event) => { event.preventDefault(); await handleClearAll(clearableRows); setConfirmClear(false); }}
                    className="px-2 py-0.5 text-xs font-medium text-red-700 border border-red-200 rounded-lg hover:bg-red-50 cursor-pointer disabled:opacity-50 disabled:cursor-wait">{clearing ? 'Removing…' : 'Remove all'}</button>
                </span>
              ) : (
                <button type="button" data-testid="clear-history" title="Remove every ended record from the history (they stay in the audit trail)"
                  onClick={(event) => { event.preventDefault(); event.stopPropagation(); setConfirmClear(true); }}
                  className="hidden group-open:inline-block px-2 py-0.5 text-xs font-medium text-gray-600 border border-gray-300 rounded-lg hover:text-red-700 hover:border-red-200 hover:bg-red-50 cursor-pointer">Clear all</button>
              ))}
              <span aria-hidden="true" className="text-xs text-gray-400 group-open:rotate-180 transition-transform">▾</span>
            </span>
          </summary>
          <div className="px-3 pb-3">
            <SupportHistory
              history={history}
              departmentName={departmentNames}
              role={role}
              historyOnly
              canEnd={canEnd}
              onArchive={(row) => archiveDispatch({ type: 'ARCHIVE_OPEN', row })}
              archivingId={archiveState.status === 'submitting' ? archiveState.row?.id : null}
            />
          </div>
        </details>
      )}

      {clearNote && (
        <div role="status" className="flex items-center justify-between gap-2 rounded-lg border border-gray-200 px-3 py-2 text-xs text-gray-700" data-testid="clear-history-note">
          <p>{clearNote}</p>
          <button type="button" onClick={() => setClearNote('')} className="underline cursor-pointer">Dismiss</button>
        </div>
      )}

      {endDialog}

      <Presence>
        {['reviewing', 'submitting', 'error'].includes(archiveState.status) && archiveState.row && (
          <SupportArchiveDialog
            row={archiveState.row}
            departmentName={departmentNames}
            view={recordView(archiveState.row, role)}
            status={archiveState.status}
            error={archiveState.error}
            stale={archiveState.stale}
            onConfirm={handleArchive}
            onCancel={() => archiveDispatch({ type: 'ARCHIVE_CANCEL' })}
          />
        )}
      </Presence>

      <Presence>
        {state.reservePrompt && (
          <SupportReserveOverrideDialog
            rows={reserveRiskRows(state.reservePrompt, options.data, state.plan, state.preview.data)}
            onCancel={() => dispatch({ type: 'CANCEL_RESERVE_PROMPT' })}
            onContinue={() => dispatch({ type: 'CONFIRM_RESERVE_PROMPT', options: options.data })}
          />
        )}
      </Presence>

      <Presence>
        {state.discardPending && (
          <SupportDiscardDialog
            departmentName={receiving?.name || departmentNames(state.department)}
            count={state.plan.length}
            names={state.plan.map((pair) => knownStaff.current.get(pair.staffId)?.name).filter(Boolean)}
            onKeep={() => dispatch({ type: 'CANCEL_DISCARD' })}
            onDiscard={() => dispatch({ type: 'CONFIRM_DISCARD' })}
          />
        )}
      </Presence>

      <Presence>
        {confirming && state.preview.data && permissions.canConfirm && (
          <SupportConfirmDialog
            preview={state.preview.data}
            receivingName={receiving?.name || ''}
            submitting={state.confirm.status === 'submitting'}
            onConfirm={handleConfirm}
            onCancel={() => dispatch({ type: 'CANCEL_REVIEW' })}
          />
        )}
      </Presence>
    </section>
  );
}
