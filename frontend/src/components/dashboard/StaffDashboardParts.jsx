import React from 'react';
import { Link } from 'react-router-dom';
import { CalendarIcon, ClipboardIcon, ClockIcon, TasksIcon } from '../icons/Icons';
import { formatDueDate, formatPriority, formatStatus, formatOverdueBy, getStatusColor, isTaskOverdue } from '../tasks/taskFormatters';
import { latestActiveTasks, personalWorkload } from '../../utils/myWorkload';
import { CARD, FlagIcon, GREEN_CHIP, PRIORITY_BAR, PRIORITY_FLAG, StatCard, TASK_BADGE as BADGE, TASK_ROW } from '../staff/StaffPortalParts';

/**
 * The Staff dashboard (Staff only; Admin and Manager keep their own dashboard): a personal task home page. Four figures and a short preview of the
 * person's newest active tasks. Everything comes from the person's own dashboard summary and own task list; detailed workload
 * (duration, status, availability, utilization) lives on My Workload and full task management on My Tasks.
 */
export default function StaffDashboardView({ data, tasks }) {
  const me = personalWorkload(data);
  // The summary endpoint's figures don't count ASSIGNED tasks, so the cards could read 0 / 0 / 0 next to an active task. Prefer the person's own task list.
  const own = Array.isArray(tasks?.data) ? tasks.data : null;
  const count = (key, statuses) => (own ? own.filter((t) => statuses.includes(t.status)).length : Number(data?.[key]) || 0);

  return (
    <div className="space-y-5">
      <section aria-label="My task summary" className="grid grid-cols-1 min-[480px]:grid-cols-2 lg:grid-cols-4 gap-3">
        <StatCard title="My Active Tasks" Icon={TasksIcon} chip={GREEN_CHIP}>{me ? me.activeTasks : 0}</StatCard>
        <StatCard title="Pending" Icon={ClipboardIcon} chip="bg-amber-50 text-amber-600 ring-amber-100">{count('pendingTasks', ['PENDING', 'ASSIGNED'])}</StatCard>
        <StatCard title="In Progress" Icon={ClockIcon} chip="bg-indigo-50 text-indigo-600 ring-indigo-100">{count('inProgressTasks', ['IN_PROGRESS'])}</StatCard>
        <StatCard title="Completed" Icon={TasksIcon} chip={GREEN_CHIP}>{count('completedTasks', ['COMPLETED'])}</StatCard>
      </section>

      <MyTasksSection tasks={tasks} />
    </div>
  );
}

function MyTasksSection({ tasks }) {
  const loading = tasks?.status === 'loading';
  const failed = tasks?.status === 'error';
  const preview = latestActiveTasks(tasks?.data, 3);

  return (
    <section aria-label="My tasks" className={`${CARD} overflow-hidden`}>
      <div className="flex items-center justify-between gap-3 px-5 py-4 border-b border-gray-100">
        <div className="min-w-0">
          <h2 className="text-base font-semibold tracking-tight text-gray-900">My Tasks</h2>
          <p className="mt-0.5 text-xs text-gray-500">Your assigned work at a glance</p>
        </div>
        {preview.length > 0 && <Link to="/tasks" className="group shrink-0 inline-flex items-center gap-1 rounded-md text-sm font-medium text-emerald-700 hover:text-emerald-800 transition-colors duration-base focus:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 focus-visible:ring-offset-2">View All Tasks<span aria-hidden="true" className="text-xs transition-transform duration-base group-hover:translate-x-0.5 group-hover:-translate-y-0.5">&#8599;</span></Link>}
      </div>

      {loading ? (
        <p role="status" className="px-5 py-10 text-center text-sm text-gray-500">Loading your tasks…</p>
      ) : failed ? (
        <p role="alert" className="px-5 py-10 text-center text-sm text-amber-700">Your tasks could not be loaded right now. They will refresh automatically.</p>
      ) : preview.length === 0 ? (
        <div data-testid="staff-no-active-tasks" className="flex flex-col items-center text-center px-5 py-10">
          <div className="w-12 h-12 rounded-full bg-emerald-50 text-emerald-600 ring-1 ring-inset ring-emerald-100 flex items-center justify-center">
            <ClipboardIcon className="w-6 h-6" aria-hidden="true" />
          </div>
          <h3 className="mt-3 text-sm font-semibold text-gray-900">No active tasks</h3>
          <p className="mt-1 max-w-sm text-sm text-gray-500">Your assigned tasks will appear here.</p>
          <Link to="/tasks" className="mt-4 inline-flex items-center px-3.5 py-2 rounded-lg border border-gray-200 text-sm font-medium text-emerald-700 hover:bg-emerald-50 transition-colors duration-base">
            View All Tasks
          </Link>
        </div>
      ) : (
        <ul className="divide-y divide-gray-100">
          {preview.map((task) => {
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
                    <span className={`inline-flex items-center gap-1.5 ${overdue ? 'text-red-600' : ''}`}>
                      <CalendarIcon className="w-3.5 h-3.5" aria-hidden="true" />
                      {task.dueDate ? formatDueDate(task.dueDate, task.deadlineTime) : 'No due date'}
                    </span>
                  </div>
                </div>
                <div className="flex shrink-0 flex-wrap items-center gap-2">
                  {overdue && <span className={`${BADGE} bg-red-50 text-red-700 ring-1 ring-inset ring-red-100`}>Overdue{overdueBy ? ` by ${overdueBy}` : ''}</span>}
                  <span className={`${BADGE} ${getStatusColor(task.status)}`}>{formatStatus(task.status)}</span>
                </div>
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}
