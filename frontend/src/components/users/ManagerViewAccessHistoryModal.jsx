import React, { useCallback, useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { getManagerViewAccessHistoryPage, getUserRole } from '../../services/api';
import { CloseIcon } from '../icons/Icons';
import ViewAccessHistoryRow from './ViewAccessHistoryRow';
import { backdropMotion, dialogMotion, withExitTransition } from '../../theme/presence';
import {
  HISTORY_FILTERS,
  HISTORY_PAGE_SIZE,
  appendHistoryPage,
  canManageViewAccess,
  viewAccessErrorMessage,
  viewAccessHistoryRows,
} from '../../utils/managerViewAccess';

const EMPTY_COUNTS = { all: 0, granted: 0, revoked: 0 };

/**
 * The whole access history of one Manager (Admin only), opened from the Department Access dialog: All / Granted / Revoked and a
 * department filter, newest first, in a fixed-height list that loads older records 20 at a time from the paged endpoint. Strictly
 * read-only: the history is append-only, so there is no delete or clear action anywhere. The server refuses everyone but an Admin.
 *
 * Props: isOpen, user (the Manager account row), onClose.
 */
function ManagerViewAccessHistoryModal({ isOpen, user, onClose, closing = false }) {
  const permitted = isOpen && canManageViewAccess(getUserRole(), user);
  const userId = user?.id;

  const [filter, setFilter] = useState('ALL');
  const [department, setDepartment] = useState('');
  const [items, setItems] = useState([]);
  const [page, setPage] = useState(0);
  const [hasMore, setHasMore] = useState(false);
  const [total, setTotal] = useState(0);
  const [counts, setCounts] = useState(EMPTY_COUNTS);
  const [departments, setDepartments] = useState([]);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState('');
  const request = useRef(0); // only the latest request's answer is used (filters can change while one is on its way)
  const closeRef = useRef(null);
  const listRef = useRef(null);

  const apply = useCallback((answer, append) => {
    setItems((current) => (append ? appendHistoryPage(current, answer) : appendHistoryPage([], answer)));
    setPage(answer?.page ?? 0);
    setHasMore(Boolean(answer?.hasMore));
    setTotal(Number(answer?.totalElements) || 0);
    setCounts(answer?.counts || EMPTY_COUNTS);
    setDepartments(Array.isArray(answer?.departments) ? answer.departments : []);
  }, []);

  useEffect(() => {
    if (!permitted || !userId) return undefined;
    const mine = ++request.current;
    setLoading(true);
    setError('');
    getManagerViewAccessHistoryPage(userId, { page: 0, size: HISTORY_PAGE_SIZE, action: filter, department })
      .then((answer) => {
        if (mine !== request.current) return;
        apply(answer, false);
        if (listRef.current) listRef.current.scrollTop = 0;
      })
      .catch((err) => {
        if (mine === request.current) setError(viewAccessErrorMessage(err, 'The access history could not be loaded.'));
      })
      .finally(() => {
        if (mine === request.current) setLoading(false);
      });
    return undefined;
  }, [permitted, userId, filter, department, apply]);

  const loadMore = async () => {
    if (loadingMore || !hasMore) return;
    const mine = ++request.current;
    setLoadingMore(true);
    setError('');
    try {
      const answer = await getManagerViewAccessHistoryPage(userId, { page: page + 1, size: HISTORY_PAGE_SIZE, action: filter, department });
      if (mine === request.current) apply(answer, true);
    } catch (err) {
      if (mine === request.current) setError(viewAccessErrorMessage(err, 'Older records could not be loaded.'));
    } finally {
      if (mine === request.current) setLoadingMore(false);
    }
  };

  useEffect(() => {
    if (!permitted) return undefined;
    const opener = document.activeElement;
    closeRef.current?.focus();
    const onKeyDown = (event) => {
      if (event.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('keydown', onKeyDown);
      opener?.focus?.();
    };
  }, [permitted, onClose]);

  if (!permitted) return null;

  const rows = viewAccessHistoryRows(items);

  return createPortal(
    <div className={`fixed inset-0 z-50 flex items-center justify-center p-3 sm:p-4 bg-black/40 ${backdropMotion(closing)}`} inert={closing} onClick={onClose}>
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby="view-access-full-history-title"
        data-testid="view-access-full-history"
        className={`flex flex-col bg-white rounded-2xl max-w-xl w-full max-h-[88vh] shadow-xl border border-gray-100 overflow-hidden ${dialogMotion(closing)}`}
        onClick={(event) => event.stopPropagation()}
      >
        <header className="shrink-0 flex items-start justify-between gap-3 px-5 sm:px-6 pt-5 pb-4 border-b border-gray-100">
          <div className="min-w-0">
            <h3 id="view-access-full-history-title" className="text-lg font-bold text-gray-900">Access change history</h3>
            <p className="text-sm text-gray-700 break-words">
              {user.fullName || user.username} <span className="text-gray-500">({user.username})</span>
            </p>
          </div>
          <button
            ref={closeRef}
            type="button"
            onClick={onClose}
            aria-label="Close"
            className="shrink-0 -mr-1.5 -mt-1 p-1.5 rounded-lg text-gray-500 hover:text-gray-900 hover:bg-gray-100 transition-colors cursor-pointer"
          >
            <CloseIcon className="w-5 h-5" aria-hidden="true" />
          </button>
        </header>

        {/* Filters */}
        <div className="shrink-0 flex flex-col sm:flex-row sm:items-center sm:justify-between gap-2.5 px-5 sm:px-6 py-3 border-b border-gray-100">
          <div role="group" aria-label="Show" className="inline-flex self-start rounded-lg bg-gray-100 p-0.5">
            {HISTORY_FILTERS.map((option) => {
              const active = filter === option.key;
              return (
                <button
                  key={option.key}
                  type="button"
                  aria-pressed={active}
                  data-testid={`history-filter-${option.key}`}
                  onClick={() => setFilter(option.key)}
                  className={`px-3 py-1 rounded-md text-xs font-medium transition-colors cursor-pointer ${
                    active ? 'bg-white text-gray-900 shadow-sm' : 'text-gray-600 hover:text-gray-900'
                  }`}
                >
                  {option.label} <span className="tabular-nums text-gray-500">{counts[option.count] ?? 0}</span>
                </button>
              );
            })}
          </div>
          <label className="flex items-center gap-2 text-xs text-gray-500 min-w-0">
            <span className="shrink-0">Department</span>
            <select
              value={department}
              onChange={(event) => setDepartment(event.target.value)}
              data-testid="history-filter-department"
              className="min-w-0 w-full sm:w-48 rounded-lg border border-gray-200 bg-white px-2 py-1.5 text-xs text-gray-900 focus:border-emerald-500 focus:ring-1 focus:ring-emerald-500"
            >
              <option value="">All departments</option>
              {departments.map((d) => (
                <option key={d.code} value={d.code}>{d.name || d.code}</option>
              ))}
            </select>
          </label>
        </div>

        {/* The list: fixed height, scrolls on its own */}
        <div ref={listRef} data-testid="view-access-full-history-list" tabIndex={0} aria-label="Access changes, most recent first" className="h-[min(26rem,52vh)] overflow-y-auto overscroll-contain px-5 sm:px-6 focus:outline-none">
          {loading && <p role="status" className="py-4 text-sm text-gray-500">Loading…</p>}
          {!loading && error && rows.length === 0 && <p role="alert" className="py-4 text-sm text-red-700">{error}</p>}
          {!loading && !error && rows.length === 0 && (
            <p className="py-4 text-sm text-gray-500">{filter === 'ALL' && !department ? 'No access changes recorded yet.' : 'No changes match these filters.'}</p>
          )}
          {!loading && rows.length > 0 && (
            <>
              <ol className="divide-y divide-gray-100">
                {rows.map((row) => (
                  <ViewAccessHistoryRow key={row.id} row={row} />
                ))}
              </ol>
              <div className="py-3 border-t border-gray-100 text-center">
                {error && <p role="alert" className="mb-2 text-xs text-red-700">{error}</p>}
                {hasMore ? (
                  <button
                    type="button"
                    onClick={loadMore}
                    disabled={loadingMore}
                    data-testid="history-load-more"
                    className="px-3 py-1.5 rounded-lg text-xs font-medium text-emerald-700 hover:bg-emerald-50 transition-colors cursor-pointer disabled:opacity-60 disabled:cursor-wait"
                  >
                    {loadingMore ? 'Loading…' : 'Load older changes'}
                  </button>
                ) : (
                  <p className="text-xs text-gray-400">Beginning of the history</p>
                )}
              </div>
            </>
          )}
        </div>

        <footer className="shrink-0 flex items-center justify-between gap-3 px-5 sm:px-6 py-3.5 border-t border-gray-100">
          <p className="text-xs text-gray-500" data-testid="history-showing" aria-live="polite">
            {loading ? '' : `Showing ${rows.length} of ${total}`}
          </p>
          <button
            type="button"
            onClick={onClose}
            className="px-4 py-2 text-gray-700 font-medium hover:bg-gray-50 border border-gray-200 rounded-xl text-sm transition-colors cursor-pointer"
          >
            Close
          </button>
        </footer>
      </div>
    </div>,
    document.body
  );
}

// Stays mounted for its 120ms exit after closing (none with reduced motion); see theme/presence.js
export default withExitTransition(ManagerViewAccessHistoryModal, (props) => Boolean(props.isOpen));
