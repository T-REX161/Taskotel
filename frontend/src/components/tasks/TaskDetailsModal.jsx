import { useEffect } from 'react';
import { createPortal } from 'react-dom';
import UserAvatar from '../common/UserAvatar';
import { CalendarIcon, ClipboardIcon, ClockIcon, CloseIcon, DepartmentsIcon, UserIcon } from '../icons/Icons';
import { FlagIcon, PRIORITY_FLAG, TASK_BADGE } from '../staff/StaffPortalParts';
import { backdropMotion, dialogMotion, withExitTransition } from '../../theme/presence';
import {
  getPriorityColor,
  formatPriority,
  getStatusColor,
  formatStatus,
  formatDepartment,
  formatDueDate,
  formatDuration,
  formatOverdueBy,
  isTaskOverdue,
} from './taskFormatters';

// The task's assignees (name and picture, when there is one), in order, without blanks. The data carries no role for them, so none is shown.
const assigneesOf = (assignments) => (Array.isArray(assignments) ? assignments : [])
  .map((a) => ({ name: typeof a?.staffName === 'string' ? a.staffName.trim() : '', imageUrl: a?.staffProfileImageUrl || null }))
  .filter((a) => a.name);

const PANEL = 'rounded-xl border border-gray-100 bg-gray-50/60';
const LABEL = 'flex items-center gap-1.5 text-[11px] font-semibold uppercase tracking-wider text-gray-500';

/** One label / value pair of the facts panel. */
function Fact({ label, children, Icon }) {
  return (
    <div className="px-4 py-3.5 min-w-0">
      <dt className={LABEL}>
        {Icon && <Icon className="w-3.5 h-3.5 text-gray-400" aria-hidden="true" />}
        {label}
      </dt>
      <dd className="mt-1.5 text-sm leading-snug font-semibold text-gray-900 break-words">{children}</dd>
    </div>
  );
}

/**
 * TaskDetailsModal — read-only view of a task's full detail.
 * Accessible to all authenticated roles.
 *
 * Props:
 *   task              – the task object to display
 *   assignmentsByTaskId – Map<taskId, assignment[]> (every assignee of the task) from the page
 *   onClose           – callback to dismiss the modal
 */
function TaskDetailsModal({ task, assignmentsByTaskId, onClose, closing = false }) {
  useEffect(() => {
    if (!task) return undefined;
    const onKeyDown = (e) => { if (e.key === 'Escape') onClose?.(); };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [task, onClose]);

  if (!task) return null;

  const assignees = assigneesOf(assignmentsByTaskId.get(task.id));
  const overdue = isTaskOverdue(task);
  const overdueBy = formatOverdueBy(task);

  const modal = (
    <div
      className={`fixed inset-0 z-50 flex items-center justify-center p-3 sm:p-6 bg-black/70 backdrop-blur-sm ${backdropMotion(closing)}`}
      inert={closing}
      onClick={onClose}
    >
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby="task-details-title"
        className={`bg-white tk-solid-dialog rounded-2xl max-w-2xl w-full shadow-xl border border-gray-200 max-h-[calc(100dvh-1.5rem)] sm:max-h-[min(92dvh,46rem)] flex flex-col overflow-hidden ${dialogMotion(closing)}`}
        onClick={(e) => e.stopPropagation()}
      >
        {/* Header: what the task is and where it stands */}
        <div className="flex items-start justify-between gap-4 px-6 sm:px-7 pt-5 pb-4 border-b border-gray-100">
          <div className="min-w-0">
            <span className="text-[11px] font-semibold text-emerald-700 bg-emerald-50 px-2.5 py-1 rounded-full uppercase tracking-wider">
              Task Details
            </span>
            <h2 id="task-details-title" className="text-xl font-bold leading-snug text-gray-900 mt-2.5 break-words">{task.title}</h2>
            <div className="mt-2.5 flex flex-wrap items-center gap-2">
              <span className={`${TASK_BADGE} ${getStatusColor(task.status)}`}>{formatStatus(task.status)}</span>
              <span className={`${TASK_BADGE} gap-1.5 ${getPriorityColor(task.priority)}`}>
                <FlagIcon className={`w-3 h-3 ${PRIORITY_FLAG[task.priority] || ''}`} />
                {formatPriority(task.priority)}
              </span>
              {overdue && (
                <span className={`${TASK_BADGE} bg-red-50 text-red-700 ring-1 ring-inset ring-red-100`}>
                  Overdue{overdueBy ? ` by ${overdueBy}` : ''}
                </span>
              )}
            </div>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="shrink-0 text-gray-400 hover:text-gray-600 hover:bg-gray-100 rounded-lg p-1.5 transition-colors cursor-pointer focus:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500"
            title="Close"
            aria-label="Close"
          >
            <CloseIcon className="w-5 h-5" />
          </button>
        </div>

        {/* Body */}
        <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain px-6 sm:px-7 py-5 space-y-3">
          <section aria-label="Description" className={`${PANEL} px-4 py-3`}>
            <h3 className={LABEL}>Description</h3>
            <p className="mt-1.5 text-sm text-gray-700 whitespace-pre-wrap break-words leading-relaxed">
              {task.description || <span className="text-gray-400">No description provided.</span>}
            </p>
          </section>

          <dl className={`grid grid-cols-1 sm:grid-cols-2 ${PANEL} divide-y divide-gray-100/60 sm:divide-y-0 sm:[&>*:nth-child(n+3)]:border-t sm:[&>*:nth-child(n+3)]:border-gray-100/60 sm:[&>*:nth-child(even)]:border-l sm:[&>*:nth-child(even)]:border-gray-100/60`}>
            <Fact label="Department" Icon={DepartmentsIcon}>{formatDepartment(task.department)}</Fact>
            <Fact label="Category" Icon={ClipboardIcon}>{task.category || 'None'}</Fact>
            <Fact label="Due Date" Icon={CalendarIcon}>
              <span className={overdue ? 'text-red-600' : ''}>{formatDueDate(task.dueDate, task.deadlineTime)}</span>
            </Fact>
            <Fact label="Estimated Duration" Icon={ClockIcon}>{formatDuration(task.estimatedDuration, 'Not specified')}</Fact>
          </dl>

          <section aria-label="Assigned staff" className={`${PANEL} px-4 py-3`}>
            <h3 className={LABEL}>
              <UserIcon className="w-3.5 h-3.5 text-gray-400" aria-hidden="true" />
              {assignees.length > 1 ? `Assigned Staff Members (${assignees.length})` : 'Assigned Staff Member'}
            </h3>
            {assignees.length === 0 ? (
              <p className="mt-1.5 text-sm">
                <span className="text-gray-400 font-normal italic">Unassigned</span>
              </p>
            ) : (
              <ul className="mt-2.5 space-y-2.5">
                {assignees.map((person, index) => (
                  <li key={`${person.name}-${index}`} className="flex items-center gap-3.5 min-w-0 font-semibold text-gray-900 text-sm leading-snug break-words">
                    <UserAvatar imageUrl={person.imageUrl} name={person.name} size="lg" className="ring-1 ring-inset ring-gray-200/70" />
                    {person.name}
                  </li>
                ))}
              </ul>
            )}
          </section>
        </div>

        {/* Footer */}
        <div className="flex justify-end px-6 sm:px-7 py-3 border-t border-gray-100/70 bg-gray-50/30">
          <button
            type="button"
            onClick={onClose}
            className="inline-flex items-center justify-center min-w-[5.5rem] px-5 py-2 rounded-lg border border-gray-200 bg-transparent text-gray-700 hover:bg-gray-100 hover:text-gray-900 active:bg-gray-200 font-medium text-sm transition-colors cursor-pointer focus:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 focus-visible:ring-offset-2"
          >
            Close
          </button>
        </div>
      </div>
    </div>
  );

  // Rendered on the page body so the overlay covers the whole screen, top bar and sidebar included (inside the page content the sticky top bar paints above it)
  return typeof document === 'undefined' ? modal : createPortal(modal, document.body);
}

// Stays mounted for its 120ms exit after closing (none with reduced motion); see theme/presence.js
export default withExitTransition(TaskDetailsModal, (props) => Boolean(props.task));
