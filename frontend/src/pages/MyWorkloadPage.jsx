import React, { useEffect, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { getDashboardSummary, getTasks } from '../services/api';
import { keepIfSame, singleFlight, startPolling } from '../utils/polling';
import { activeTasksOf, personalWorkload } from '../utils/myWorkload';
import { getWorkloadStatusBadge } from '../utils/workloadUtils';
import { dashboardLoadErrorMessage } from '../utils/dashboardView';
import { formatDueDate, formatDuration, formatPriority, formatStatus, getPriorityColor, getStatusColor, isTaskOverdue } from '../components/tasks/taskFormatters';
import { getAvailabilityColor } from '../components/staff/staffFormatters';
import UtilizationMeter from '../components/workload/UtilizationMeter';
import Spinner from '../components/common/Spinner';
import LoadError from '../components/common/LoadError';

const REFRESH_MS = 30000;
const CARD ='bg-white rounded-xl border border-gray-100 shadow-2xs';
const availabilityText = (value) => (value ? String(value).replace(/_/g, ' ').toLowerCase().replace(/^\w/, (c) => c.toUpperCase()) : 'Unavailable');

function Kpi({ title, children }) {
  return (
    <div className={`${CARD} p-4`}>
      <p className="text-xs font-medium text-gray-500">{title}</p>
      <div className="mt-1.5 text-2xl font-bold text-gray-900 tabular-nums">{children}</div>
    </div>
  );
}

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

  return (
    <div className="w-full space-y-5">
      {header}

      {personal.supportDepartmentName && (
        <div role="status" className="px-4 py-3 rounded-xl bg-blue-50 border border-blue-100 text-sm text-blue-900">
          You are currently supporting <span className="font-semibold">{personal.supportDepartmentName}</span>. Those tasks are included in your workload below.
        </div>
      )}

      <section aria-label="My workload summary" className="grid grid-cols-2 lg:grid-cols-4 gap-3 sm:gap-4">
        <Kpi title="My Active Tasks">{personal.activeTasks}</Kpi>
        <Kpi title="My Workload">{formatDuration(personal.minutes, '0 min')}</Kpi>
        <Kpi title="My Workload Status">
          <span className={`inline-flex items-center px-2.5 py-1 rounded-full text-sm font-bold uppercase tracking-wide ${getWorkloadStatusBadge(personal.status)}`}>
            {personal.status ? personal.status.replace('_', ' ') : '-'}
          </span>
        </Kpi>
        <Kpi title="My Availability">
          <span className={`inline-flex items-center px-2.5 py-1 rounded-full text-sm font-semibold ${getAvailabilityColor(personal.availability)}`}>{availabilityText(personal.availability)}</span>
        </Kpi>
      </section>

      <section aria-label="Shift utilization" className={`${CARD} p-4 sm:p-5`}>
        <h2 className="text-base font-bold text-gray-900">Shift Utilization</h2>
        <div className="mt-3">
          <UtilizationMeter percentage={personal.percent} status={personal.status || ''} minutes={personal.minutes} baseline={personal.capacity} />
        </div>
        <p className="mt-3 text-xs text-gray-500">Measured against a {personal.capacity}-minute shift.</p>
      </section>

      <section aria-label="My assigned tasks" className={CARD}>
        <div className="flex items-center justify-between gap-3 px-4 sm:px-5 py-3 border-b border-gray-100">
          <h2 className="text-base font-bold text-gray-900">My Assigned Tasks</h2>
          <Link to="/tasks" className="text-sm font-medium text-emerald-700 hover:text-emerald-800 hover:underline">View all my tasks</Link>
        </div>
        {active.length === 0 ? (
          <p className="px-5 py-10 text-center text-sm text-gray-500">You have no active tasks right now.</p>
        ) : (
          <ul className="divide-y divide-gray-100">
            {active.map((task) => (
              <li key={task.id} className="px-4 sm:px-5 py-3 grid grid-cols-1 sm:grid-cols-[minmax(0,1fr)_auto] gap-x-6 gap-y-2 sm:items-center">
                <div className="min-w-0">
                  <p className="text-sm font-semibold text-gray-900 truncate" title={task.title}>{task.title}</p>
                  <div className="mt-1.5 flex flex-wrap items-center gap-1.5">
                    <span className={`inline-flex items-center px-2 py-0.5 rounded-full text-[11px] font-semibold ${getPriorityColor(task.priority)}`}>{formatPriority(task.priority)}</span>
                    <span className={`inline-flex items-center px-2 py-0.5 rounded-full text-[11px] font-semibold ${getStatusColor(task.status)}`}>{formatStatus(task.status)}</span>
                    {task.departmentName && <span className="text-xs text-gray-500">{task.departmentName}</span>}
                  </div>
                </div>
                <div className="flex sm:flex-col sm:items-end gap-x-5 gap-y-0.5 text-xs text-gray-500">
                  <span className="font-semibold text-gray-800 tabular-nums">{formatDuration(task.estimatedDuration)}</span>
                  <span className={isTaskOverdue(task) ? 'font-semibold text-red-700' : ''}>
                    {formatDueDate(task.dueDate, task.deadlineTime)}{isTaskOverdue(task) ? ' · Overdue' : ''}
                  </span>
                </div>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}
