import React, { useEffect, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { getDashboardSummary, getTasks } from '../services/api';
import { keepIfSame, singleFlight, startPolling } from '../utils/polling';
import { activeTasksOf, isSupportTask, personalWorkload, taskBreakdown } from '../utils/myWorkload';
import { getWorkloadProgressBarColor, getWorkloadStatusBadge } from '../utils/workloadUtils';
import { dashboardLoadErrorMessage } from '../utils/dashboardView';
import { formatDueDate, formatDuration, formatOverdueBy, formatPriority, formatStatus, getStatusColor, isTaskOverdue } from '../components/tasks/taskFormatters';
import { getAvailabilityColor } from '../components/staff/staffFormatters';
import { CalendarIcon, ClipboardIcon, ClockIcon, TasksIcon, UserIcon, WorkloadIcon } from '../components/icons/Icons';
import { CARD, FlagIcon, GREEN_CHIP, NEUTRAL_CHIP, PRIORITY_BAR, PRIORITY_FLAG, STATUS_TONE, StatCard, TASK_BADGE, TASK_ROW, availabilityText } from '../components/staff/StaffPortalParts';
import AnimatedNumber from '../components/common/AnimatedNumber';
import Spinner from '../components/common/Spinner';
import LoadError from '../components/common/LoadError';

const REFRESH_MS = 30000;
const BREAKDOWN = [
  { key: 'pending', label: 'Pending', dot: 'bg-amber-500' },
  { key: 'assigned', label: 'Assigned', dot: 'bg-blue-500' },
  { key: 'inProgress', label: 'In Progress', dot: 'bg-indigo-500' },
  { key: 'completed', label: 'Completed', dot: 'bg-emerald-500' },
];

/**
 * My Workload (Staff only): the signed-in person's OWN workload, utilization, status, availability and assigned tasks. Nothing about any other
 * person or any department. The figures come from the server's dashboard summary (the person's own record, cross-department support minutes
 * included) and the person's own task list (the server returns only theirs). Admin and Manager keep the department Workload page.
 */
export default function MyWorkloadPage() {
  const navigate = useNavigate();
  const [summary, setSummary] = useState(null);
  const [tasks, setTasks] = useState([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState('');

  // `silent` refreshes (the 30-second poll, returning to the tab) never show the spinner or the error screen: a failed refresh keeps what is on screen
  const load = async (silent = false) => {
    if (!silent) {
      setIsLoading(true);
      setError('');
    }
    try {
      const [nextSummary, nextTasks] = await Promise.all([getDashboardSummary(), getTasks()]);
      setSummary((prev) => keepIfSame({ status: 'ready', data: prev }, { status: 'ready', data: nextSummary }).data);
      setTasks((prev) => keepIfSame({ status: 'ready', data: prev }, { status: 'ready', data: Array.isArray(nextTasks) ? nextTasks : [] }).data);
      setError('');
    } catch (err) {
      if (err.message && err.message.includes('Redirecting to login')) {
        navigate('/login');
        return;
      }
      if (!silent) setError(dashboardLoadErrorMessage(err));
    } finally {
      if (!silent) setIsLoading(false);
    }
  };

  // Same refresh the department Workload page has: every 30 seconds while the tab is visible, and a catch-up when it becomes visible again
  const latest = useRef(load);
  latest.current = load;
  const refreshRef = useRef(null);
  if (refreshRef.current === null) refreshRef.current = singleFlight((silent) => latest.current(silent));

  useEffect(() => {
    refreshRef.current(false);
    return startPolling({ refresh: () => refreshRef.current(true), intervalMs: REFRESH_MS });
  }, []);

  const header = (
    <div>
      <h1 className="text-2xl font-bold text-gray-900 tracking-tight">My Workload</h1>
      <p className="text-sm text-gray-500 mt-1">Your own tasks, workload and shift utilization.</p>
    </div>
  );

  if (isLoading) {
    return (
      <div className="w-full space-y-5">
        {header}
        <div className={`${CARD} flex items-center justify-center p-16`}>
          <div role="status" className="flex flex-col items-center gap-3">
            <Spinner size="lg" className="text-emerald-600" />
            <p className="text-sm font-medium text-gray-500">Loading your workload...</p>
          </div>
        </div>
      </div>
    );
  }

  const personal = error ? null : personalWorkload(summary);
  if (error || !personal) {
    return (
      <div className="w-full space-y-5">
        {header}
        <LoadError
          title={error ? 'Failed to load your workload' : 'Your workload is not available'}
          message={error || 'Your workload record could not be found. Please try again, or contact your manager if it keeps happening.'}
          onRetry={() => load(false)}
          retryLabel="Try Again"
        />
      </div>
    );
  }

  const active = activeTasksOf(tasks);
  const counts = taskBreakdown(tasks);
  const tone = STATUS_TONE[personal.status] || null;
  const statusText = personal.status ? personal.status.replace('_', ' ') : '-';
  const fill = Math.min(Math.max(personal.percent, 0), 100);
  const available = String(personal.availability || '').toUpperCase() === 'AVAILABLE';

  return (
    <div className="w-full space-y-5">
      {header}

      {personal.supportDepartmentName && (
        <div role="status" className="flex items-start gap-3 px-4 py-3 rounded-xl bg-blue-50 border border-blue-100 text-sm text-blue-900">
          <UserIcon className="w-4 h-4 mt-0.5 shrink-0" aria-hidden="true" />
          <p>
            You are currently supporting <span className="font-semibold">{personal.supportDepartmentName}</span>. Those tasks are included in your workload below.
          </p>
        </div>
      )}

      {/* Summary cards */}
      <section aria-label="My workload summary" className="grid grid-cols-1 min-[480px]:grid-cols-2 lg:grid-cols-4 gap-3">
        <StatCard title="My Active Tasks" Icon={TasksIcon} chip={GREEN_CHIP}>{personal.activeTasks}</StatCard>
        <StatCard title="My Workload" Icon={ClockIcon} chip={GREEN_CHIP}>{formatDuration(personal.minutes, '0 min')}</StatCard>
        <StatCard title="My Workload Status" Icon={WorkloadIcon} chip={tone ? tone.chip : NEUTRAL_CHIP}>
          <span className={`inline-flex items-center px-2.5 py-1 rounded-full text-sm font-bold uppercase tracking-wide ${getWorkloadStatusBadge(personal.status)}`}>{statusText}</span>
        </StatCard>
        <StatCard title="My Availability" Icon={UserIcon} chip={available ? GREEN_CHIP : NEUTRAL_CHIP}>
          <span className={`inline-flex items-center px-2.5 py-1 rounded-full text-sm font-semibold ${getAvailabilityColor(personal.availability)}`}>{availabilityText(personal.availability)}</span>
        </StatCard>
      </section>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-4 items-stretch">
        {/* Shift utilization */}
        <section aria-label="Shift utilization" className={`${CARD} p-5 lg:col-span-2 flex flex-col`}>
          <div className="flex items-start justify-between gap-3">
            <div>
              <h2 className="text-base font-bold text-gray-900">Shift Utilization</h2>
              <p className="mt-0.5 text-xs text-gray-500">Your active tasks against one {personal.capacity}-minute (8-hour) shift.</p>
            </div>
            <span className={`inline-flex items-center px-2.5 py-1 rounded-full text-xs font-bold uppercase tracking-wide whitespace-nowrap ${getWorkloadStatusBadge(personal.status)}`}>{statusText}</span>
          </div>

          <div className="my-auto pt-5">
          <div className="flex items-baseline gap-3">
            <span className="text-4xl font-bold leading-none tabular-nums text-gray-900"><AnimatedNumber value={personal.percent} />%</span>
            {tone && <span className="text-sm font-medium text-gray-500">{tone.text}</span>}
          </div>

          <div
            className="mt-3 w-full h-3 rounded-full bg-gray-100 overflow-hidden"
            role="progressbar"
            aria-valuemin={0}
            aria-valuemax={100}
            aria-valuenow={fill}
            aria-label={`Shift utilization ${personal.percent}%`}
          >
            <div
              className={`tk-bar-grow h-full rounded-full transition-[width,background-color] duration-slow ease-standard ${getWorkloadProgressBarColor(personal.status)}`}
              style={{ width: `${fill}%` }}
            />
          </div>

          <div className="mt-2 flex flex-wrap items-center justify-between gap-x-4 gap-y-1 text-xs text-gray-500">
            <span className="font-semibold text-gray-700 tabular-nums">{personal.minutes} / {personal.capacity} mins</span>
            <span>
              {personal.minutes > personal.capacity ? 'Above one full shift' : `${Math.max(personal.capacity - personal.minutes, 0)} mins of shift capacity left`}
            </span>
          </div>
          </div>
        </section>

        {/* Task breakdown */}
        <section aria-label="My task breakdown" className={`${CARD} p-5`}>
          <div className="flex items-baseline justify-between gap-3">
            <h2 className="text-base font-bold text-gray-900">My Task Breakdown</h2>
            <span className="text-xs text-gray-500 tabular-nums">{counts.total} {counts.total === 1 ? 'task' : 'tasks'}</span>
          </div>
          <ul className="mt-4 space-y-3">
            {BREAKDOWN.map((row) => (
              <li key={row.key}>
                <div className="flex items-center justify-between gap-3 text-sm">
                  <span className="inline-flex items-center gap-2 text-gray-600">
                    <span className={`w-2.5 h-2.5 rounded-full ${row.dot}`} aria-hidden="true" />
                    {row.label}
                  </span>
                  <span className="font-bold text-gray-900 tabular-nums"><AnimatedNumber value={counts[row.key]} /></span>
                </div>
                <div className="mt-1.5 h-1.5 rounded-full bg-gray-100 overflow-hidden" aria-hidden="true">
                  <div className={`tk-bar-grow h-full rounded-full ${row.dot}`} style={{ width: counts.total ? `${(counts[row.key] / counts.total) * 100}%` : '0%' }} />
                </div>
              </li>
            ))}
          </ul>
        </section>
      </div>

      {/* Assigned tasks */}
      <section aria-label="My assigned tasks" className={`${CARD} overflow-hidden`}>
        <div className="flex items-center justify-between gap-3 px-5 py-4 border-b border-gray-100">
          <div className="min-w-0">
            <h2 className="text-base font-semibold tracking-tight text-gray-900">My Assigned Tasks</h2>
            <p className="mt-0.5 text-xs text-gray-500">Open work with its estimate and due date</p>
          </div>
          {active.length > 0 && <Link to="/tasks" className="group shrink-0 inline-flex items-center gap-1 rounded-md text-sm font-medium text-emerald-700 hover:text-emerald-800 transition-colors duration-base focus:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 focus-visible:ring-offset-2">View all my tasks<span aria-hidden="true" className="text-xs transition-transform duration-base group-hover:translate-x-0.5 group-hover:-translate-y-0.5">&#8599;</span></Link>}
        </div>
        {active.length === 0 ? (
          <div data-testid="no-active-tasks" className="flex flex-col items-center text-center px-5 py-10">
            <div className="w-12 h-12 rounded-full bg-emerald-50 text-emerald-600 ring-1 ring-inset ring-emerald-100 flex items-center justify-center">
              <ClipboardIcon className="w-6 h-6" aria-hidden="true" />
            </div>
            <h3 className="mt-3 text-sm font-semibold text-gray-900">No active tasks</h3>
            <p className="mt-1 max-w-sm text-sm text-gray-500">Tasks assigned to you will show up here. You are all caught up for now.</p>
            <Link to="/tasks" className="mt-4 inline-flex items-center px-3.5 py-2 rounded-lg border border-gray-200 text-sm font-medium text-emerald-700 hover:bg-emerald-50 transition-colors duration-base">
              Go to My Tasks
            </Link>
          </div>
        ) : (
          <ul className="divide-y divide-gray-100">
            {active.map((task) => {
              const overdue = isTaskOverdue(task);
              const overdueBy = formatOverdueBy(task);
              return (
                <li key={task.id} className={TASK_ROW}>
                  <span aria-hidden="true" className={`absolute left-0 top-3 bottom-3 w-[3px] rounded-r-full opacity-80 ${PRIORITY_BAR[task.priority] || 'bg-gray-300'}`} />
                  <div className="min-w-0">
                    <p className="text-base font-semibold leading-snug text-gray-900 break-words sm:truncate" title={task.title}>{task.title}</p>
                    <div className="mt-1.5 flex flex-wrap items-center gap-x-4 gap-y-1 text-xs leading-4 text-gray-500">
                      <span className="inline-flex items-center gap-1.5">
                        <FlagIcon className={`w-3.5 h-3.5 ${PRIORITY_FLAG[task.priority] || 'text-gray-400'}`} />
                        {formatPriority(task.priority)} priority
                      </span>
                      {task.departmentName && <span>{task.departmentName}</span>}
                      <span className="inline-flex items-center gap-1.5 tabular-nums">
                        <ClockIcon className="w-3.5 h-3.5" aria-hidden="true" />
                        {formatDuration(task.estimatedDuration, 'No estimate')}
                      </span>
                      <span className={`inline-flex items-center gap-1.5 ${overdue ? 'text-red-600' : ''}`}>
                        <CalendarIcon className="w-3.5 h-3.5" aria-hidden="true" />
                        {task.dueDate ? formatDueDate(task.dueDate, task.deadlineTime) : 'No due date'}
                      </span>
                    </div>
                  </div>
                  <div className="flex shrink-0 flex-wrap items-center gap-2">
                    {overdue && <span className={`${TASK_BADGE} bg-red-50 text-red-700 ring-1 ring-inset ring-red-100`}>Overdue{overdueBy ? ` by ${overdueBy}` : ''}</span>}
                    {isSupportTask(task, personal) && (
                      <span className={`${TASK_BADGE} bg-blue-50 text-blue-700 ring-1 ring-inset ring-blue-100`}>Support</span>
                    )}
                    <span className={`${TASK_BADGE} ${getStatusColor(task.status)}`}>{formatStatus(task.status)}</span>
                  </div>
                </li>
              );
            })}
          </ul>
        )}
      </section>
    </div>
  );
}
