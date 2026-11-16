import React, { useCallback, useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import {
  getDepartments,
  getManagerViewAccess,
  getManagerViewAccessHistory,
  getUserRole,
  updateManagerViewAccess,
} from '../../services/api';
import { CloseIcon, LockIcon } from '../icons/Icons';
import ViewOnlyBadge from '../common/ViewOnlyBadge';
import { formatDate, formatTime } from '../../utils/dateTimeFormat';
import { getPreferences } from '../../utils/preferences';
import { backdropMotion, dialogMotion, withExitTransition } from '../../theme/presence';
import {
  canManageViewAccess,
  choiceState,
  grantedCodes,
  revokeSelection,
  selectionChanged,
  viewAccessChoices,
  viewAccessErrorMessage,
  viewAccessHistoryRows,
  viewAccessSummary,
} from '../../utils/managerViewAccess';

const formatWhen = (value) => {
  if (!value) return '';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '';
  const { dateFormat, timeFormat } = getPreferences();
  return `${formatDate(date, dateFormat)}, ${formatTime(date, timeFormat)}`;
};

const ACTION_TONE = {
  granted: 'bg-emerald-50 text-emerald-700',
  revoked: 'bg-rose-50 text-rose-700',
  other: 'bg-gray-100 text-gray-600',
};

// Saved grants first (so they are easy to find), then the rest; the own department stays on top. Only the SAVED state decides the
// order, so rows do not jump while the Admin ticks boxes.
const STATE_ORDER = { primary: 0, granted: 1, removing: 1, adding: 2, none: 2 };

/**
 * Admin-only dialog on the Users page: which other departments a Manager may VIEW (staff, workload, tasks and assignments), never
 * manage. The Manager's own department is always included and locked. Each saved grant can be revoked on its own (after a confirmation),
 * or the whole selection saved at once; both use the same Admin-only endpoint, and the server records every change in the append-only
 * history shown below (read-only here: nothing removes a record). Nothing renders for anyone else; the server refuses everyone but an
 * Admin as well (this is convenience, not the control).
 *
 * Layout: fixed header and footer, a scrolling body, and a history list with its own scroll area, so a long history never pushes the
 * buttons out of reach.
 *
 * Props: isOpen, user (the Manager account row), onClose.
 */
function ManagerViewAccessModal({ isOpen, user, onClose, closing = false }) {
  const permitted = isOpen && canManageViewAccess(getUserRole(), user);

  const [access, setAccess] = useState(null);
  const [departments, setDepartments] = useState([]);
  const [selected, setSelected] = useState([]);
  const [history, setHistory] = useState([]);
  const [historyError, setHistoryError] = useState('');
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState('');
  const [saved, setSaved] = useState('');
  const [revoking, setRevoking] = useState(null); // the department awaiting the Admin's confirmation
  const inFlight = useRef(false); // a second click before the first answer must not send a second request
  const confirmCancelRef = useRef(null);

  const userId = user?.id;

  const loadHistory = useCallback(async () => {
    if (!userId) return;
    try {
      setHistory(await getManagerViewAccessHistory(userId));
      setHistoryError('');
    } catch (err) {
      setHistoryError(viewAccessErrorMessage(err, 'The access history could not be loaded.'));
    }
  }, [userId]);

  useEffect(() => {
    if (!permitted || !userId) return undefined;
    let cancelled = false;
    setLoading(true);
    setLoadError('');
    setAccess(null);
    setSelected([]);
    setHistory([]);
    setSubmitError('');
    setSaved('');
    setRevoking(null);
    Promise.all([getManagerViewAccess(userId), getDepartments()])
      .then(([current, allDepartments]) => {
        if (cancelled) return;
        setAccess(current);
        setSelected(grantedCodes(current));
        setDepartments(allDepartments);
      })
      .catch((err) => {
        if (!cancelled) setLoadError(viewAccessErrorMessage(err, 'The Manager’s department access could not be loaded.'));
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    loadHistory();
    return () => {
      cancelled = true;
    };
  }, [permitted, userId, loadHistory]);

  useEffect(() => {
    if (!permitted) return undefined;
    const onKeyDown = (event) => {
      if (event.key !== 'Escape' || inFlight.current) return;
      // Escape first dismisses the confirmation, then the dialog
      if (revoking) setRevoking(null);
      else onClose();
    };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [permitted, onClose, revoking]);

  useEffect(() => {
    if (revoking) confirmCancelRef.current?.focus();
  }, [revoking]);

  if (!permitted) return null;

  const savedCodes = grantedCodes(access);
  const choices = viewAccessChoices(departments, access)
    .map((choice) => ({ choice, state: choiceState(choice, savedCodes, selected) }))
    .sort((a, b) => STATE_ORDER[choiceState(a.choice, savedCodes, savedCodes)] - STATE_ORDER[choiceState(b.choice, savedCodes, savedCodes)]);
  const changed = selectionChanged(savedCodes, selected);
  const historyRows = viewAccessHistoryRows(history);
  const primaryName = choices.find(({ state }) => state === 'primary')?.choice.name || access?.primaryDepartmentName || '';

  const toggle = (code) => {
    setSubmitError('');
    setSaved('');
    setSelected((current) => (current.includes(code) ? current.filter((c) => c !== code) : [...current, code]));
  };

  const handleSave = async () => {
    if (inFlight.current || !changed) return;
    inFlight.current = true;
    setSubmitting(true);
    setSubmitError('');
    setSaved('');
    try {
      const updated = await updateManagerViewAccess(userId, selected);
      setAccess(updated);
      setSelected(grantedCodes(updated));
      setSaved(`Saved. ${viewAccessSummary(updated)} Applies on the Manager’s next request.`);
      await loadHistory();
    } catch (err) {
      setSubmitError(viewAccessErrorMessage(err));
    } finally {
      inFlight.current = false;
      setSubmitting(false);
    }
  };

  // Revokes ONE saved grant: the same endpoint with the saved set minus that department. Other unsaved ticks stay pending, unsent.
  const handleRevoke = async () => {
    const target = revoking;
    const remaining = revokeSelection(access, target?.code);
    if (inFlight.current || !remaining) {
      setRevoking(null);
      return;
    }
    inFlight.current = true;
    setSubmitting(true);
    setSubmitError('');
    setSaved('');
    try {
      const updated = await updateManagerViewAccess(userId, remaining);
      setAccess(updated);
      setSelected((current) => current.filter((code) => code !== target.code));
      setSaved(`View access to ${target.name} revoked. Applies on the Manager’s next request.`);
      await loadHistory();
    } catch (err) {
      setSubmitError(viewAccessErrorMessage(err, 'The access could not be revoked. Please try again.'));
    } finally {
      inFlight.current = false;
      setSubmitting(false);
      setRevoking(null);
    }
  };

  const grantedCount = savedCodes.length;

  return createPortal(
    <div
      className={`fixed inset-0 z-50 flex items-center justify-center p-3 sm:p-4 bg-gray-900/50 backdrop-blur-xs ${backdropMotion(closing)}`}
      inert={closing}
      onClick={() => !submitting && !revoking && onClose()}
    >
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby="manager-view-access-title"
        data-testid="manager-view-access"
        className={`relative flex flex-col bg-white rounded-2xl max-w-xl w-full max-h-[88vh] shadow-xl border border-gray-100 overflow-hidden ${dialogMotion(closing)}`}
        onClick={(event) => event.stopPropagation()}
      >
        {/* A. Header: always visible */}
        <header className="shrink-0 flex items-start justify-between gap-3 px-5 sm:px-6 pt-5 pb-4 border-b border-gray-100">
          <div className="min-w-0">
            <h3 id="manager-view-access-title" className="text-lg font-bold text-gray-900">Department view access</h3>
            <p className="text-sm text-gray-700 break-words">
              {user.fullName || user.username} <span className="text-gray-500">({user.username})</span>
            </p>
            {primaryName && <p className="text-xs text-gray-500">Manager · {primaryName}</p>}
          </div>
          <button
            type="button"
            onClick={onClose}
            disabled={submitting}
            aria-label="Close"
            className="shrink-0 -mr-1.5 -mt-1 p-1.5 rounded-lg text-gray-500 hover:text-gray-900 hover:bg-gray-100 transition-colors cursor-pointer disabled:opacity-50"
          >
            <CloseIcon className="w-5 h-5" aria-hidden="true" />
          </button>
        </header>

        {/* Body: scrolls on its own when needed; the history below has its own, shorter scroll area */}
        <div className="flex-1 min-h-0 overflow-y-auto overscroll-contain px-5 sm:px-6 py-4 space-y-6" data-testid="view-access-body">
          {loading && <p role="status" className="text-sm text-gray-500">Loading…</p>}
          {loadError && <p role="alert" className="p-3.5 rounded-xl bg-red-50 border border-red-200 text-red-800 text-xs">{loadError}</p>}

          {/* B. Departments */}
          {access && (
            <fieldset disabled={submitting} className="min-w-0">
              <legend className="w-full flex items-baseline justify-between gap-3">
                <span className="text-sm font-semibold text-gray-900">Departments this Manager can view</span>
                <span className="shrink-0 text-xs font-normal text-gray-500" data-testid="granted-count">
                  {grantedCount} additional
                </span>
              </legend>
              <p className="mt-1 text-xs text-gray-500">
                View only: staff, workload, tasks and assignments are visible but cannot be changed. Contact details stay hidden.
              </p>
              {!access.primaryDepartmentCode && (
                <p className="mt-2 text-xs text-amber-800">This Manager has no department yet, so no access applies until one is assigned.</p>
              )}
              <ul
                data-testid="view-access-departments"
                className="mt-3 max-h-56 sm:max-h-72 overflow-y-auto overscroll-contain pr-1 divide-y divide-gray-100 border-y border-gray-100"
              >
                {choices.map(({ choice, state }) => {
                  const id = `view-access-${choice.code}`;
                  const checked = choice.primary || selected.includes(choice.code);
                  return (
                    <li key={choice.code} data-testid="view-access-row" data-state={state} className="flex items-center gap-3 py-2 min-h-11">
                      <input
                        id={id}
                        type="checkbox"
                        checked={checked}
                        disabled={!choice.selectable}
                        onChange={() => toggle(choice.code)}
                        className="w-4 h-4 shrink-0 rounded border-gray-300 text-emerald-600 focus:ring-emerald-500 disabled:opacity-60"
                      />
                      <label
                        htmlFor={id}
                        className={`flex-1 min-w-0 break-words text-sm ${
                          state === 'primary' || state === 'granted' ? 'font-medium text-gray-900' : choice.selectable ? 'text-gray-700' : 'text-gray-500'
                        } ${choice.selectable ? 'cursor-pointer' : ''}`}
                      >
                        {choice.name}
                      </label>
                      <span className="shrink-0 flex items-center gap-2">
                        {state !== 'primary' && !choice.active && <span className="text-[11px] font-medium text-amber-700">Inactive</span>}
                        {state === 'primary' && (
                          <span className="inline-flex items-center gap-1 text-[11px] font-semibold text-emerald-700" title="The Manager’s own department is always included and cannot be removed">
                            <LockIcon className="w-3 h-3" aria-hidden="true" />
                            Primary
                          </span>
                        )}
                        {state === 'granted' && (
                          <>
                            <ViewOnlyBadge size="compact" />
                            <button
                              type="button"
                              onClick={() => {
                                setSubmitError('');
                                setSaved('');
                                setRevoking({ code: choice.code, name: choice.name });
                              }}
                              aria-label={`Revoke view access to ${choice.name}`}
                              className="px-2 py-1 rounded-md text-xs font-medium text-rose-700 hover:bg-rose-50 transition-colors cursor-pointer disabled:opacity-50"
                            >
                              Revoke
                            </button>
                          </>
                        )}
                        {state === 'adding' && <span className="text-[11px] font-medium text-sky-700">Will be granted</span>}
                        {state === 'removing' && <span className="text-[11px] font-medium text-amber-700">Will be removed</span>}
                      </span>
                    </li>
                  );
                })}
              </ul>
            </fieldset>
          )}

          {/* C. History: append-only and read-only, newest first, in its own scroll area */}
          {access && (
            <section aria-labelledby="view-access-history-title" className="min-w-0">
              <div className="flex items-baseline justify-between gap-3">
                <h4 id="view-access-history-title" className="text-sm font-semibold text-gray-900">Access change history</h4>
                {historyRows.length > 0 && (
                  <span className="shrink-0 text-xs text-gray-500" data-testid="history-count">
                    {historyRows.length} {historyRows.length === 1 ? 'change' : 'changes'}
                  </span>
                )}
              </div>
              {historyError && <p role="alert" className="mt-2 text-xs text-red-700">{historyError}</p>}
              {!historyError && historyRows.length === 0 && <p className="mt-2 text-xs text-gray-500">No access changes recorded yet.</p>}
              {historyRows.length > 0 && (
                <ol
                  data-testid="view-access-history"
                  tabIndex={0}
                  aria-label="Access changes, most recent first"
                  className="mt-2 max-h-48 sm:max-h-56 overflow-y-auto overscroll-contain divide-y divide-gray-100 border-y border-gray-100 focus:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500/40"
                >
                  {historyRows.map((row) => (
                    <li key={row.id} className="py-2 pr-1">
                      <div className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-0.5">
                        <p className="min-w-0 text-sm text-gray-900 break-words">
                          <span className={`inline-block mr-1.5 rounded px-1.5 py-px text-[10px] font-semibold uppercase tracking-wide align-[1px] ${ACTION_TONE[row.tone]}`}>
                            {row.action}
                          </span>
                          {row.department}
                        </p>
                        <time dateTime={row.changedAt || undefined} className="shrink-0 text-xs text-gray-500 tabular-nums">
                          {formatWhen(row.changedAt)}
                        </time>
                      </div>
                      <p className="text-xs text-gray-500 break-words">
                        by {row.actor}
                        {row.automatic && ' · automatic: it became the Manager’s own department'}
                      </p>
                    </li>
                  ))}
                </ol>
              )}
            </section>
          )}
        </div>

        {/* D. Footer: always visible */}
        <footer className="shrink-0 px-5 sm:px-6 py-3.5 border-t border-gray-100 space-y-3">
          {submitError && <p role="alert" className="p-3 rounded-xl bg-red-50 border border-red-200 text-red-800 text-xs">{submitError}</p>}
          {saved && <p role="status" className="p-3 rounded-xl bg-emerald-50 border border-emerald-200 text-emerald-800 text-xs">{saved}</p>}
          <div className="flex items-center justify-end gap-3">
            <button
              type="button"
              onClick={onClose}
              disabled={submitting}
              className="px-4 py-2 text-gray-700 font-medium hover:bg-gray-50 border border-gray-200 rounded-xl text-sm transition-colors cursor-pointer disabled:opacity-50"
            >
              Cancel
            </button>
            {access && (
              <button
                type="button"
                onClick={handleSave}
                disabled={submitting || !changed}
                className="px-4 py-2 bg-emerald-600 hover:bg-emerald-700 active:bg-emerald-800 text-white font-medium rounded-xl text-sm transition-colors cursor-pointer disabled:opacity-60 disabled:cursor-not-allowed"
              >
                {submitting && !revoking ? 'Saving…' : 'Save changes'}
              </button>
            )}
          </div>
        </footer>

        {/* Revocation confirmation, over the dialog */}
        {revoking && (
          <div className="absolute inset-0 z-10 flex items-end sm:items-center justify-center p-3 sm:p-6 bg-black/50" onClick={() => !submitting && setRevoking(null)}>
            <div
              role="alertdialog"
              aria-modal="true"
              aria-labelledby="revoke-view-access-title"
              aria-describedby="revoke-view-access-text"
              data-testid="revoke-confirm"
              className="w-full max-w-sm rounded-2xl bg-white border border-gray-100 shadow-xl p-5 space-y-3"
              onClick={(event) => event.stopPropagation()}
            >
              <h4 id="revoke-view-access-title" className="text-base font-bold text-gray-900 break-words">Revoke access to {revoking.name}?</h4>
              <p id="revoke-view-access-text" className="text-sm text-gray-600">
                {user.fullName || user.username} will no longer see {revoking.name} from their next request. Other access is kept. The change is
                recorded in the access history.
              </p>
              <div className="flex justify-end gap-2 pt-1">
                <button
                  ref={confirmCancelRef}
                  type="button"
                  disabled={submitting}
                  onClick={() => setRevoking(null)}
                  className="px-4 py-2 text-sm font-medium text-gray-700 border border-gray-200 rounded-xl hover:bg-gray-50 cursor-pointer disabled:opacity-50"
                >
                  Cancel
                </button>
                <button
                  type="button"
                  disabled={submitting}
                  onClick={handleRevoke}
                  className="px-4 py-2 text-sm font-medium text-white bg-rose-600 hover:bg-rose-700 rounded-xl cursor-pointer disabled:opacity-60 disabled:cursor-wait"
                >
                  {submitting ? 'Revoking…' : 'Revoke access'}
                </button>
              </div>
            </div>
          </div>
        )}
      </div>
    </div>,
    document.body
  );
}

// Stays mounted for its 120ms exit after closing (none with reduced motion); see theme/presence.js
export default withExitTransition(ManagerViewAccessModal, (props) => Boolean(props.isOpen));
