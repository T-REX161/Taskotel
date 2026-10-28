import React from 'react';
import { StaffIcon, TasksIcon, AssignmentsIcon, WorkloadIcon } from '../icons/Icons';

const TONES = {
  emerald: { chip: 'bg-emerald-50 text-emerald-700' },
  mint: { chip: 'bg-teal-50 text-teal-700' },
  slate: { chip: 'bg-gray-100 text-gray-500' },
  red: { chip: 'bg-red-50 text-red-500' },
};

/**
 * SummaryCards — top KPI cards for the dashboard.
 * Every value is read from the summary payload; the cards differ by role (Staff see their own figures).
 *
 * Props:
 *   data    – summary payload from getDashboardSummary()
 *   isStaff – boolean indicating whether current user has STAFF role view
 */
export default function SummaryCards({ data, isStaff }) {
  const cards = isStaff
    ? [
        { title: 'My Active Tasks', value: data?.workload?.activeTaskCount ?? 0, Icon: TasksIcon, tone: 'emerald' },
        { title: 'Workload Duration', value: `${data?.workload?.totalEstimatedDuration ?? 0}m`, Icon: WorkloadIcon, tone: 'mint' },
        { title: 'Workload Status', value: data?.workload?.workloadStatus ?? 'LOW', Icon: StaffIcon, tone: 'emerald' },
        { title: 'My Availability', value: data?.workload?.availability ?? 'AVAILABLE', Icon: StaffIcon, tone: 'slate' },
      ]
    : [
        { title: 'Total Staff', value: data?.totalStaff ?? 0, Icon: StaffIcon, tone: 'emerald' },
        { title: 'Available Staff', value: data?.availableStaff ?? 0, Icon: StaffIcon, tone: 'mint' },
        { title: 'Unavailable Staff', value: data?.unavailableStaff ?? 0, Icon: StaffIcon, tone: 'slate' },
        { title: 'Inactive Staff', value: data?.inactiveStaff ?? 0, Icon: StaffIcon, tone: 'red' },
        { title: 'Total Tasks', value: data?.totalTasks ?? 0, Icon: TasksIcon, tone: 'emerald' },
        { title: 'Active Assignments', value: data?.totalActiveAssignments ?? 0, Icon: AssignmentsIcon, tone: 'mint' },
      ];

  return (
    <div className={`grid grid-cols-1 sm:grid-cols-2 ${isStaff ? 'lg:grid-cols-4' : 'lg:grid-cols-3 xl:grid-cols-6'} gap-4`}>
      {cards.map(({ title, value, Icon, tone }) => (
        <div key={title} className="bg-white rounded-2xl p-4 sm:p-5 shadow-sm border border-gray-200 flex items-center gap-3.5">
          <div className={`w-11 h-11 shrink-0 rounded-xl flex items-center justify-center ${TONES[tone].chip}`}>
            <Icon className="w-5 h-5" />
          </div>
          <div className="min-w-0">
            <p className="text-xs font-medium text-gray-500 leading-snug">{title}</p>
            <p className="text-2xl font-bold text-gray-900 leading-tight truncate">{value}</p>
          </div>
        </div>
      ))}
    </div>
  );
}
