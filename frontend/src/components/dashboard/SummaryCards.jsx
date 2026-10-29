import React from 'react';
import { StaffIcon, TasksIcon, AssignmentsIcon, WorkloadIcon, ClipboardIcon, ClockIcon } from '../icons/Icons';

const TONES = {
  green: { chip: 'bg-emerald-50 text-emerald-600 ring-emerald-100', value: 'text-gray-900' },
  red: { chip: 'bg-red-50 text-red-500 ring-red-100', value: 'text-gray-900' },
  amber: { chip: 'bg-amber-50 text-amber-600 ring-amber-100', value: 'text-gray-900' },
  slate: { chip: 'bg-gray-100 text-gray-500 ring-gray-200', value: 'text-gray-900' },
  pending: { chip: 'bg-amber-50 text-amber-600 ring-amber-100', value: 'text-amber-600' },
  blue: { chip: 'bg-blue-50 text-blue-600 ring-blue-100', value: 'text-blue-600' },
};

/**
 * The two KPI rows: people and workload first, then the task pipeline. Every value is read from the summary payload;
 * Staff see their own figures (their summary carries no staff counts).
 *
 * Props:
 *   data    – summary payload from getDashboardSummary()
 *   isStaff – boolean indicating whether current user has STAFF role view
 */
export default function SummaryCards({ data, isStaff }) {
  const first = isStaff
    ? [
        { title: 'My Active Tasks', value: data?.workload?.activeTaskCount ?? 0, Icon: TasksIcon, tone: 'green' },
        { title: 'Workload Duration', value: `${data?.workload?.totalEstimatedDuration ?? 0}m`, Icon: WorkloadIcon, tone: 'green' },
        { title: 'Workload Status', value: data?.workload?.workloadStatus ?? 'LOW', Icon: StaffIcon, tone: 'green' },
        { title: 'My Availability', value: data?.workload?.availability ?? 'AVAILABLE', Icon: StaffIcon, tone: 'slate' },
      ]
    : [
        { title: 'Total Staff', value: data?.totalStaff ?? 0, Icon: StaffIcon, tone: 'green' },
        { title: 'Available Staff', value: data?.availableStaff ?? 0, Icon: StaffIcon, tone: 'green' },
        { title: 'Unavailable Staff', value: data?.unavailableStaff ?? 0, Icon: StaffIcon, tone: 'red' },
        { title: 'Active Assignments', value: data?.totalActiveAssignments ?? 0, Icon: AssignmentsIcon, tone: 'amber' },
      ];
  const second = [
    { title: 'Pending Tasks', value: data?.pendingTasks ?? 0, Icon: ClipboardIcon, tone: 'pending' },
    { title: 'Assigned Tasks', value: data?.assignedTasks ?? 0, Icon: TasksIcon, tone: 'blue' },
    { title: 'In Progress', value: data?.inProgressTasks ?? 0, Icon: ClockIcon, tone: 'blue' },
    { title: 'Completed', value: data?.completedTasks ?? 0, Icon: TasksIcon, tone: 'green' },
  ];

  return (
    <div className="grid grid-cols-1 min-[480px]:grid-cols-2 lg:grid-cols-4 gap-3">
      {[...first, ...second].map(({ title, value, Icon, tone }) => (
        <div key={title} className="bg-white rounded-xl p-4 min-h-[5.75rem] border border-gray-200 shadow-[0_1px_2px_rgba(16,24,40,0.04)] transition-colors duration-150 hover:border-emerald-200 flex items-start justify-between gap-3">
          <div className="min-w-0">
            <p className="text-sm font-medium text-gray-600">{title}</p>
            <p className={`mt-2 text-3xl font-bold leading-none tabular-nums truncate ${TONES[tone].value}`}>{value}</p>
          </div>
          <div className={`w-10 h-10 shrink-0 rounded-lg ring-1 ring-inset flex items-center justify-center ${TONES[tone].chip}`}>
            <Icon className="w-5 h-5" />
          </div>
        </div>
      ))}
    </div>
  );
}
