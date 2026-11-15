import React, { useCallback, useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import {
  getDepartments,
  getManagerViewAccess,
  getManagerViewAccessHistory,
  getUserRole,
  updateManagerViewAccess,
} from '../../services/api';
import { formatDate, formatTime } from '../../utils/dateTimeFormat';
import { getPreferences } from '../../utils/preferences';
import { backdropMotion, dialogMotion, withExitTransition } from '../../theme/presence';
import {
  canManageViewAccess,
  describeViewAccessChange,
  grantedCodes,
  selectionChanged,
  viewAccessChoices,
  viewAccessErrorMessage,
  viewAccessSummary,
} from '../../utils/managerViewAccess';

const formatWhen = (value) => {
  if (!value) return '';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '';
  const { dateFormat, timeFormat } = getPreferences();
  return `${formatDate(date, dateFormat)}, ${formatTime(date, timeFormat)}`;
};

/**
 * Admin-only dialog on the Users page: which other departments a Manager may VIEW (staff, workload, tasks and assignments), never
 * manage. The Manager's own department is always included and cannot be changed here. Nothing renders for anyone else; the server
 * refuses everyone but an Admin as well (this is convenience, not the control).
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
  const inFlight = useRef(false); // a second click before the first answer must not send a second request

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
      if (event.key === 'Escape' && !inFlight.current) onClose();
    };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [permitted, onClose]);

  if (!permitted) return null;

  const choices = viewAccessChoices(departments, access);
  const savedCodes = grantedCodes(access);
  const changed = selectionChanged(savedCodes, selected);

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

  return createPortal(
    <div className={`fixed inset-0 z-50 flex items-center justify-center p-4 bg-gray-900/50 backdrop-blur-xs ${backdropMotion(closing)}`} inert={closing} onClick={() => !submitting && onClose()}>
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby="manager-view-access-title"
        data-testid="manager-view-access"
        className={`bg-white rounded-2xl max-w-lg w-full p-6 shadow-xl border border-gray-100 max-h-[85vh] overflow-y-auto space-y-5 ${dialogMotion(closing)}`}
        onClick={(event) => event.stopPropagation()}
      >
        <div>
          <h3 id="manager-view-access-title" className="text-lg font-bold text-gray-900">Department view access</h3>
          <p className="text-xs text-gray-500">{user.fullName || user.username} ({user.username})</p>
        </div>

        {loading && <p role="status" className="text-sm text-gray-500">Loading…</p>}
        {loadError && <p role="alert" className="p-3.5 rounded-xl bg-red-50 border border-red-200 text-red-800 text-xs">{loadError}</p>}

        {access && (
          <fieldset className="space-y-2" disabled={submitting}>
            <legend className="text-sm font-medium text-gray-700 mb-1">Departments this Manager can view</legend>
            <p className="text-xs text-gray-500">
              View only: the Manager can see staff, workload, tasks and assignments in the selected departments, but cannot create, edit,
              assign, reassign, complete or delete anything there. Staff email addresses and phone numbers stay hidden.
            </p>
            {!access.primaryDepartmentCode && (
              <p className="text-xs text-amber-800">This Manager has no department yet, so no access applies until one is assigned.</p>
            )}
            <ul className="divide-y divide-gray-100 rounded-xl border border-gray-200">
              {choices.map((choice) => {
                const id = `view-access-${choice.code}`;
                const checked = choice.primary || selected.includes(choice.code);
                return (
                  <li key={choice.code} className="px-3 py-2.5 flex items-center gap-3">
                    <input
                      id={id}
                      type="checkbox"
                      checked={checked}
                      disabled={!choice.selectable}
                      onChange={() => toggle(choice.code)}
                      className="w-4 h-4 rounded border-gray-300 text-emerald-600 focus:ring-emerald-500 disabled:opacity-60"
                    />
                    <label htmlFor={id} className={`flex-1 min-w-0 text-sm ${choice.selectable ? 'text-gray-900 cursor-pointer' : 'text-gray-500'}`}>
                      {choice.name}
                    </label>
                    {choice.primary && <span className="shrink-0 text-[11px] font-semibold text-emerald-700">Own department · always included</span>}
                    {!choice.primary && !choice.active && <span className="shrink-0 text-[11px] font-semibold text-amber-700">Inactive</span>}
                  </li>
                );
              })}
            </ul>
          </fieldset>
        )}

        {submitError && <p role="alert" className="p-3.5 rounded-xl bg-red-50 border border-red-200 text-red-800 text-xs">{submitError}</p>}
        {saved && <p role="status" className="p-3.5 rounded-xl bg-emerald-50 border border-emerald-200 text-emerald-800 text-xs">{saved}</p>}

        {access && (
          <section aria-label="Access change history" className="space-y-2">
            <h4 className="text-sm font-semibold text-gray-900">Access change history</h4>
            {historyError && <p role="alert" className="text-xs text-red-700">{historyError}</p>}
            {!historyError && history.length === 0 && <p className="text-xs text-gray-500">No access changes recorded yet.</p>}
            {history.length > 0 && (
              <ul className="divide-y divide-gray-100 rounded-xl border border-gray-200">
                {history.map((change) => (
                  <li key={change.id} className="px-3 py-2 text-sm">
                    <p className="text-gray-900">{describeViewAccessChange(change)}</p>
                    <p className="text-xs text-gray-500">By {change.changedByUsername} · {formatWhen(change.changedAt)}</p>
                  </li>
                ))}
              </ul>
            )}
          </section>
        )}

        <div className="flex items-center justify-end gap-3 pt-1">
          <button
            type="button"
            onClick={onClose}
            disabled={submitting}
            className="px-4 py-2 text-gray-700 font-medium hover:bg-gray-50 border border-gray-200 rounded-xl text-sm transition-colors cursor-pointer disabled:opacity-50"
          >
            Close
          </button>
          {access && (
            <button
              type="button"
              onClick={handleSave}
              disabled={submitting || !changed}
              className="px-4 py-2 bg-emerald-600 hover:bg-emerald-700 active:bg-emerald-800 text-white font-medium rounded-xl text-sm transition-colors cursor-pointer disabled:opacity-60 disabled:cursor-not-allowed"
            >
              {submitting ? 'Saving…' : 'Save access'}
            </button>
          )}
        </div>
      </div>
    </div>,
    document.body
  );
}

// Stays mounted for its 120ms exit after closing (none with reduced motion); see theme/presence.js
export default withExitTransition(ManagerViewAccessModal, (props) => Boolean(props.isOpen));
