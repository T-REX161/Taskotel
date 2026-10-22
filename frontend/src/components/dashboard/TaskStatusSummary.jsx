import React from 'react';

/**
 * TaskStatusSummary — summary card showing task distribution across statuses.
 *
 * Props:
 *   data    – summary payload containing status counts
 *   isStaff – boolean indicating whether current user has STAFF role view
 */
export default function TaskStatusSummary({ data, isStaff }) {
  const taskStatus = isStaff
    ? [
        {
          status: 'Pending',
          count: data?.pendingTasks ?? 0,
          color: 'bg-yellow-400 text-yellow-800',
        },
        {
          status: 'Assigned',
          count: data?.assignedTasks ?? 0,
          color: 'bg-blue-500 text-blue-800',
        },
        {
          status: 'In Progress',
          count: data?.inProgressTasks ?? 0,
          color: 'bg-purple-500 text-purple-800',
        },
        {
          status: 'Completed',
          count: data?.completedTasks ?? 0,
          color: 'bg-emerald-500 text-emerald-800',
        },
      ]
    : [
        {
          status: 'Pending',
          count: data?.pendingTasks ?? 0,
          color: 'bg-yellow-400 text-yellow-800',
        },
        {
          status: 'Assigned',
          count: data?.assignedTasks ?? 0,
          color: 'bg-blue-500 text-blue-800',
        },
        {
          status: 'In Progress',
          count: data?.inProgressTasks ?? 0,
          color: 'bg-purple-500 text-purple-800',
        },
        {
          status: 'Completed',
          count: data?.completedTasks ?? 0,
          color: 'bg-emerald-500 text-emerald-800',
        },
        {
          status: 'Cancelled',
          count: data?.cancelledTasks ?? 0,
          color: 'bg-gray-400 text-gray-800',
        },
      ];

  return (
    <div className="bg-white rounded-xl shadow-sm border border-gray-100 p-6">
      <h2 className="text-lg font-bold text-gray-900 mb-6">Task Status</h2>
      <div className="space-y-5">
        {taskStatus.map((status, idx) => (
          <div key={idx} className="flex items-center justify-between">
            <div className="flex items-center space-x-3">
              <span
                className={`w-3 h-3 rounded-full ${status.color.split(' ')[0]}`}
              ></span>
              <span className="text-gray-600 font-medium">{status.status}</span>
            </div>
            <span className="font-bold text-gray-900">{status.count}</span>
          </div>
        ))}
      </div>
    </div>
  );
}
