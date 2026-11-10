import React, { useCallback, useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import {
  assignManagerDepartment,
  getDepartments,
  getManagerDepartment,
  getManagerDepartmentHistory,
  getUserRole,
} from '../../services/api';
import { formatDate, formatTime } from '../../utils/dateTimeFormat';
import { getPreferences } from '../../utils/preferences';
import {
  canManageManagerDepartment,
  canSubmit,
  canViewManagerDepartmentHistory,
  currentDepartmentText,
  departmentChoices,
  describeChange,
  describeChangedBy,
  managerDepartmentErrorMessage,
  validateSelection,
  validityNote,
} from '../../utils/managerDepartment';

const formatWhen = (value) => {
  if (!value) return '';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '';
  const { dateFormat, timeFormat } = getPreferences();
  return `${formatDate(date, dateFormat)}, ${formatTime(date, timeFormat)}`;
};

/**
 * Admin-only dialog on the Users page: a Manager's current department, a control to assign or change it, and the history of those
 * changes. Nothing renders for anyone else (the server refuses them as well; this is convenience, not the control).
 *
 * Props: isOpen, user (the Manager account row), onClose, onChanged(updatedDepartment) after a successful change.
 */
export default function ManagerDepartmentModal({ isOpen, user, onClose, onChanged }) {
  const permitted = isOpen && canManageManagerDepartment(getUserRole(), user);
  const showHistory = canViewManagerDepartmentHistory(getUserRole());

  const [info, setInfo] = useState(null);
  const [departments, setDepartments] = useState([]);
  const [history, setHistory] = useState([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');
  const [historyError, setHistoryError] = useState('');
  const [selected, setSelected] = useState('');
  const [touched, setTouched] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState('');
  const [saved, setSaved] = useState('');
  const inFlight = useRef(false); // a second click before the first answer must not send a second request
  const dialogRef = useRef(null);

  const userId = user?.id;

  const loadHistory = useCallback(async () => {
    if (!showHistory || !userId) return;
    try {
      setHistory(await getManagerDepartmentHistory(userId));
      setHistoryError('');
    } catch (err) {
      setHistoryError(managerDepartmentErrorMessage(err, 'The department history could not be loaded.'));
    }
  }, [userId, showHistory]);

  useEffect(() => {
    if (!permitted || !userId) return undefined;
    let cancelled = false;
    setLoading(true);
    setLoadError('');
    setInfo(null);
    setHistory([]);
    setSelected('');
    setTouched(false);
    setSubmitError('');
    setSaved('');
    Promise.all([getManagerDepartment(userId), getDepartments()])
      .then(([current, allDepartments]) => {
        if (cancelled) return;
        setInfo(current);
        setDepartments(allDepartments);
      })
      .catch((err) => {
        if (!cancelled) setLoadError(managerDepartmentErrorMessage(err, 'The Manager’s department could not be loaded.'));
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

  const currentCode = info?.departmentCode || '';
  const choices = departmentChoices(departments, currentCode);
  const validation = touched ? validateSelection(currentCode, selected) : '';
  const hasStaffRecord = Boolean(info);

  const handleSave = async () => {
    if (inFlight.current) return;
    setTouched(true);
    if (validateSelection(currentCode, selected) !== '') return;
    inFlight.current = true;
    setSubmitting(true);
    setSubmitError('');
    setSaved('');
    try {
      const updated = await assignManagerDepartment(userId, selected);
      setInfo(updated);
      setSelected('');
      setTouched(false);
      setSaved(`Department changed to ${currentDepartmentText(updated)}.`);
      await loadHistory();
      if (onChanged) onChanged(updated);
    } catch (err) {
      setSubmitError(managerDepartmentErrorMessage(err));
    } finally {
      inFlight.current = false;
      setSubmitting(false);
    }
  };

  const note = validityNote(info);

  return createPortal(
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-gray-900/50 backdrop-blur-xs animate-fadeIn" onClick={() => !submitting && onClose()}>
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby="manager-department-title"
        className="bg-white rounded-2xl max-w-lg w-full p-6 shadow-xl border border-gray-100 max-h-[85vh] overflow-y-auto space-y-5"
        onClick={(event) => event.stopPropagation()}
      >
        <div>
          <h3 id="manager-department-title" className="text-lg font-bold text-gray-900">Manager department</h3>
          <p className="text-xs text-gray-500">{user.fullName || user.username} ({user.username})</p>
        </div>

        {loading && <p role="status" className="text-sm text-gray-500">Loading…</p>}
        {loadError && <p role="alert" className="p-3.5 rounded-xl bg-red-50 border border-red-200 text-red-800 text-xs">{loadError}</p>}

        {hasStaffRecord && (
          <>
            <div>
              <p className="text-[11px] font-medium uppercase tracking-wide text-gray-500">Current department</p>
              <p data-testid="current-department" className="text-sm font-semibold text-gray-900">{currentDepartmentText(info)}</p>
              {note && <p className="mt-1 text-xs text-amber-800">{note}</p>}
            </div>

            <div className="space-y-2">
              <label htmlFor="manager-department-select" className="block text-sm font-medium text-gray-700">
                {currentCode ? 'Change department to' : 'Assign department'}
              </label>
              <select
                id="manager-department-select"
                value={selected}
                disabled={submitting}
                onChange={(event) => { setSelected(event.target.value); setTouched(true); setSubmitError(''); setSaved(''); }}
                aria-invalid={validation ? 'true' : undefined}
                className="w-full h-11 px-3 bg-white border border-gray-200 rounded-xl text-sm text-gray-800 focus:outline-none focus:ring-2 focus:ring-emerald-500/30 focus:border-emerald-500 disabled:opacity-60"
              >
                <option value="">Select a department…</option>
                {choices.map((choice) => (
                  <option key={choice.code} value={choice.code}>{choice.name}</option>
                ))}
              </select>
              {validation && <p role="alert" className="text-xs text-red-700">{validation}</p>}
              <p className="text-xs text-gray-500">
                Applies on the Manager&rsquo;s next request. Existing tasks, assignments and support records stay with their original department.
                A change is refused while the Manager holds active tasks in another department.
              </p>
            </div>
          </>
        )}

        {submitError && <p role="alert" className="p-3.5 rounded-xl bg-red-50 border border-red-200 text-red-800 text-xs">{submitError}</p>}
        {saved && <p role="status" className="p-3.5 rounded-xl bg-emerald-50 border border-emerald-200 text-emerald-800 text-xs">{saved}</p>}

        {showHistory && hasStaffRecord && (
          <section aria-label="Department change history" className="space-y-2">
            <h4 className="text-sm font-semibold text-gray-900">Department change history</h4>
            {historyError && <p role="alert" className="text-xs text-red-700">{historyError}</p>}
            {!historyError && history.length === 0 && <p className="text-xs text-gray-500">No department changes recorded yet.</p>}
            {history.length > 0 && (
              <ul className="divide-y divide-gray-100 rounded-xl border border-gray-200">
                {history.map((change) => (
                  <li key={change.id} className="px-3 py-2 text-sm">
                    <p className="text-gray-900">{describeChange(change)}</p>
                    <p className="text-xs text-gray-500">{describeChangedBy(change)} · {formatWhen(change.changedAt)}</p>
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
          {hasStaffRecord && (
            <button
              type="button"
              onClick={handleSave}
              disabled={!canSubmit({ currentCode, selectedCode: selected, submitting })}
              className="px-4 py-2 bg-emerald-600 hover:bg-emerald-700 active:bg-emerald-800 text-white font-medium rounded-xl text-sm transition-colors cursor-pointer disabled:opacity-60 disabled:cursor-not-allowed"
            >
              {submitting ? 'Saving…' : 'Save department'}
            </button>
          )}
        </div>
      </div>
    </div>,
    document.body
  );
}
