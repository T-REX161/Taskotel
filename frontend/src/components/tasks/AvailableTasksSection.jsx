import React from 'react';
import Spinner from '../common/Spinner';
import LoadError from '../common/LoadError';
import { availableViewState } from '../../utils/taskClaim';
import { formatDepartment, formatDueDate, formatDuration, formatPriority, getPriorityColor } from './taskFormatters';

/**
 * Available Tasks (Staff): the unassigned, pending tasks of the Staff member's own department, each with an "Accept Task" button.
 * Presentation only: the page loads the list and sends the claim; the server decides who is first.
 *
 * Props:
 *   loading, error, canClaim, reason, tasks  – the list and its state (see utils/taskClaim)
 *   claimingId     – the task being accepted right now (its button shows progress; every other button waits)
 *   notice         – { kind: 'conflict' | 'gone' | 'error', message } from the last failed claim, or null
 *   onClaim(task), onRetry(), onDismissNotice()
 */
/** The wide-screen columns: Task, Priority, Duration, Due, Created, Action. Header and every row share them so the values line up. */
const COLUMNS = 'lg:grid-cols-[minmax(0,1fr)_5.5rem_5.5rem_10.5rem_6.5rem_7rem]';

export default function AvailableTasksSection({
  loading = false,
  error = '',
  canClaim = true,
  reason = '',
  tasks = [],
  claimingId = null,
  notice = null,
  onClaim,
  onRetry,
  onDismissNotice,
  embedded = false,
  hidden = false,
}) {
  const view = availableViewState({ loading, error, canClaim, count: tasks.length });
  const busy = Boolean(claimingId);

  return (
    <section aria-labelledby="available-tasks-heading" data-testid="available-tasks" hidden={hidden} className={embedded ? 'w-full' : 'bg-white rounded-xl shadow-sm border border-gray-200 overflow-hidden w-full'}>
      <div className={embedded ? 'px-4 py-2 border-b border-gray-100 bg-gray-50/40' : 'flex items-center justify-between gap-3 px-4 py-3 border-b border-gray-100'}>
        <div className="min-w-0">
          <h2 id="available-tasks-heading" className={embedded ? 'sr-only' : 'text-base font-semibold text-gray-900'}>Available Tasks</h2>
          <p className="text-xs text-gray-500">Unassigned tasks in your department. The first person to accept a task gets it, and only then does it count in your totals.</p>
        </div>
        {view === 'ready' && !embedded && (
          <span className="shrink-0 inline-flex items-center justify-center h-6 min-w-6 px-2 rounded-md bg-gray-100 text-gray-700 text-xs font-semibold" data-testid="available-count">
            {tasks.length}
          </span>
        )}
      </div>

      {notice && (
        <div
          role="alert"
          data-testid="claim-notice"
          className={`mx-4 mt-3 p-2.5 rounded-lg border text-sm flex items-center justify-between gap-3 ${notice.kind === 'error' ? 'bg-red-50 border-red-200 text-red-800' : 'bg-amber-50 border-amber-200 text-amber-900'}`}
        >
          <span className="font-medium">{notice.message}</span>
          {onDismissNotice && (
            <button type="button" onClick={onDismissNotice} className="text-xs font-bold hover:underline cursor-pointer shrink-0">Dismiss</button>
          )}
        </div>
      )}

      {view === 'loading' && (
        <div role="status" className="flex items-center gap-2.5 px-4 py-5 text-sm text-gray-500">
          <Spinner size="sm" className="text-gray-400" />
          <span>Loading available tasks...</span>
        </div>
      )}

      {view === 'error' && (
        <div className="p-4">
          <LoadError variant="banner" message={error} onRetry={onRetry} retryLabel="Retry" testId="available-error" />
        </div>
      )}

      {view === 'blocked' && (
        <p className="px-4 py-5 text-sm text-gray-600" data-testid="available-blocked">
          {reason || 'You cannot accept tasks right now.'}
        </p>
      )}

      {view === 'empty' && (
        <div className="px-4 py-6 text-center" data-testid="available-empty">
          <p className="text-sm font-medium text-gray-800">No tasks are available right now</p>
          <p className="text-xs text-gray-500 mt-1">When a new task is created for your department, it will appear here.</p>
        </div>
      )}

      {view === 'ready' && !canClaim && (
        <p role="status" data-testid="available-busy" className="px-4 py-2 text-xs font-medium text-amber-800 bg-amber-50 border-b border-amber-200">{reason || 'You cannot accept tasks right now.'}</p>
      )}

      {view === 'ready' && (
        <div>
          {/* Column headings (wide screens only; below that each value carries its own label) */}
          <div aria-hidden="true" className={`hidden lg:grid ${COLUMNS} gap-x-4 px-4 py-2 border-b border-gray-100 text-[11px] font-semibold uppercase tracking-wide text-gray-400`}>
            <span>Task</span>
            <span>Priority</span>
            <span>Duration</span>
            <span>Due</span>
            <span>Created</span>
            <span />
          </div>
          <ul className="divide-y divide-gray-100">
            {tasks.map((task) => {
              const claiming = claimingId === task.id;
              const due = formatDueDate(task.dueDate, task.deadlineTime);
              const created = task.createdAt ? formatDueDate(String(task.createdAt).slice(0, 10)) : '-';
              const label = 'lg:hidden mr-1 text-gray-400';
              return (
                <li key={task.id} data-testid="available-task" className={`px-4 py-3 flex flex-wrap items-center gap-x-4 gap-y-1.5 lg:grid ${COLUMNS} transition-colors hover:bg-gray-50/60`}>
                  <div className="min-w-0 w-full lg:w-auto">
                    <h3 className="text-sm font-semibold text-gray-900 break-words">{task.title}</h3>
                    <p className="text-xs text-gray-500 truncate">
                      {task.departmentName || formatDepartment(task.department)}
                      {task.description ? <span> · {task.description}</span> : null}
                    </p>
                  </div>
                  <span className={`inline-flex items-center justify-self-start h-5 px-2 rounded-md text-[11px] font-semibold leading-none ${getPriorityColor(task.priority)}`}>
                    {formatPriority(task.priority)}
                  </span>
                  <span className="text-xs text-gray-700"><span className={label}>Duration</span>{formatDuration(task.estimatedDuration)}</span>
                  <span className={`text-xs ${due === '-' ? 'hidden lg:inline text-gray-400' : 'text-gray-700'}`}><span className={label}>Due</span>{due === '-' ? '—' : due}</span>
                  <span className={`text-xs ${created === '-' ? 'hidden lg:inline text-gray-400' : 'text-gray-700'}`}><span className={label}>Created</span>{created === '-' ? '—' : created}</span>
                  <button
                    type="button"
                    onClick={() => onClaim && onClaim(task)}
                    disabled={busy || !canClaim}
                    title={!canClaim ? (reason || undefined) : undefined}
                    aria-label={`Accept task ${task.title}`}
                    className="w-full sm:w-auto sm:ml-auto lg:ml-0 lg:w-full inline-flex items-center justify-center gap-2 h-8 px-3 rounded-lg bg-emerald-600 hover:bg-emerald-700 text-white text-xs font-semibold transition-colors cursor-pointer disabled:cursor-not-allowed disabled:opacity-60 focus:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 focus-visible:ring-offset-2"
                  >
                    {claiming && <Spinner size="sm" className="text-white" />}
                    {claiming ? 'Accepting...' : 'Accept Task'}
                  </button>
                </li>
              );
            })}
          </ul>
        </div>
      )}
    </section>
  );
}
