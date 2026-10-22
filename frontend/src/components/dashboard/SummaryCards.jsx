import React from 'react';
import {
  StaffIcon,
  TasksIcon,
  AssignmentsIcon,
  WorkloadIcon,
} from '../icons/Icons';

/**
 * SummaryCards — top metric summary cards for the dashboard.
 * Dynamically adjusts metrics, labels, and grid layout based on user role.
 *
 * Props:
 *   data    – summary payload from getDashboardSummary()
 *   isStaff – boolean indicating whether current user has STAFF role view
 */
export default function SummaryCards({ data, isStaff }) {
  const cards = isStaff
    ? [
        {
          title: 'My Active Tasks',
          value: data?.workload?.activeTaskCount ?? 0,
          icon: <TasksIcon className="w-6 h-6 text-emerald-600" />,
          bg: 'bg-emerald-50',
        },
        {
          title: 'Workload Duration',
          value: `${data?.workload?.totalEstimatedDuration ?? 0}m`,
          icon: <WorkloadIcon className="w-6 h-6 text-emerald-600" />,
          bg: 'bg-emerald-50',
        },
        {
          title: 'Workload Status',
          value: data?.workload?.workloadStatus ?? 'LOW',
          icon: <StaffIcon className="w-6 h-6 text-emerald-600" />,
          bg: 'bg-emerald-50',
        },
        {
          title: 'My Availability',
          value: data?.workload?.availability ?? 'AVAILABLE',
          icon: <StaffIcon className="w-6 h-6 text-gray-500" />,
          bg: 'bg-gray-50',
        },
      ]
    : [
        {
          title: 'Total Staff',
          value: data?.totalStaff ?? 0,
          icon: <StaffIcon className="w-6 h-6 text-emerald-600" />,
          bg: 'bg-emerald-50',
        },
        {
          title: 'Available Staff',
          value: data?.availableStaff ?? 0,
          icon: <StaffIcon className="w-6 h-6 text-emerald-600" />,
          bg: 'bg-emerald-50',
        },
        {
          title: 'Unavailable Staff',
          value: data?.unavailableStaff ?? 0,
          icon: <StaffIcon className="w-6 h-6 text-gray-400" />,
          bg: 'bg-gray-50',
        },
        {
          title: 'Inactive Staff',
          value: data?.inactiveStaff ?? 0,
          icon: <StaffIcon className="w-6 h-6 text-red-400" />,
          bg: 'bg-red-50',
        },
        {
          title: 'Total Tasks',
          value: data?.totalTasks ?? 0,
          icon: <TasksIcon className="w-6 h-6 text-emerald-600" />,
          bg: 'bg-emerald-50',
        },
        {
          title: 'Active Assignments',
          value: data?.totalActiveAssignments ?? 0,
          icon: <AssignmentsIcon className="w-6 h-6 text-blue-600" />,
          bg: 'bg-blue-50',
        },
      ];

  return (
    <div
      className={`grid grid-cols-1 sm:grid-cols-2 ${
        isStaff ? 'lg:grid-cols-4' : 'lg:grid-cols-3 xl:grid-cols-6'
      } gap-4 sm:gap-6`}
    >
      {cards.map((card, idx) => (
        <div
          key={idx}
          className="bg-white rounded-xl p-5 sm:p-6 shadow-sm border border-gray-100 flex items-center space-x-4"
        >
          <div className={`p-3 rounded-lg ${card.bg}`}>{card.icon}</div>
          <div>
            <p className="text-xs sm:text-sm font-medium text-gray-500">
              {card.title}
            </p>
            <h3 className="text-xl sm:text-2xl font-bold text-gray-900">
              {card.value}
            </h3>
          </div>
        </div>
      ))}
    </div>
  );
}
