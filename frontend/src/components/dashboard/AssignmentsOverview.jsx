import React from 'react';
import { StaffIcon, AssignmentsIcon } from '../icons/Icons';

/**
 * AssignmentsOverview — card displaying recent assignments for staff or organization-wide assignment metrics for admin.
 *
 * Props:
 *   data    – summary payload from getDashboardSummary()
 *   isStaff – boolean indicating whether current user has STAFF role view
 */
export default function AssignmentsOverview({ data, isStaff }) {
  return (
    <div className="bg-white rounded-2xl shadow-sm border border-gray-200 p-5 sm:p-6">
      <div className="flex justify-between items-center mb-6">
        <h2 className="text-lg font-bold text-gray-900">
          {isStaff ? 'Recent Assignments' : 'Active Assignments'}
        </h2>
      </div>

      {isStaff ? (
        data?.recentAssignments && data.recentAssignments.length > 0 ? (
          <div className="space-y-4">
            {data.recentAssignments.map((assignment, idx) => (
              <div
                key={assignment.id || idx}
                className="p-4 rounded-lg border border-gray-100 hover:border-gray-200 transition-colors bg-white"
              >
                <h4 className="font-semibold text-gray-900 mb-2">
                  {assignment.taskTitle || 'Assigned Task'}
                </h4>
                <div className="flex items-center justify-between text-xs text-gray-500">
                  <div className="flex items-center space-x-1.5">
                    <StaffIcon className="w-3.5 h-3.5 text-gray-400" />
                    <span>{assignment.staffName || 'You'}</span>
                  </div>
                  <span className="text-xs font-medium px-2 py-0.5 rounded-md bg-blue-50 text-blue-700">
                    {assignment.assignedAt
                      ? new Date(assignment.assignedAt).toLocaleDateString()
                      : 'Assigned'}
                  </span>
                </div>
              </div>
            ))}
          </div>
        ) : (
          <p className="text-sm text-gray-500 py-4 text-center">
            No active assignments currently.
          </p>
        )
      ) : (
        <div className="space-y-4">
          <div className="p-4 rounded-xl border border-emerald-100 bg-emerald-50/50">
            <div className="flex items-center justify-between">
              <div>
                <p className="text-xs font-bold uppercase tracking-wider text-emerald-800">
                  Total Active Assignments
                </p>
                <h3 className="text-2xl font-bold text-emerald-900 mt-1">
                  {data?.totalActiveAssignments ?? 0}
                </h3>
              </div>
              <div className="p-3 bg-white rounded-xl shadow-xs text-emerald-600">
                <AssignmentsIcon className="w-5 h-5" />
              </div>
            </div>
            <p className="text-xs text-emerald-700/80 mt-2">
              Active task dispatches assigned to staff members.
            </p>
          </div>

          <div className="p-3.5 rounded-xl border border-gray-100 bg-gray-50/50 flex items-center justify-between">
            <span className="text-sm font-medium text-gray-600">
              Staff with Workload
            </span>
            <span className="text-sm font-bold text-gray-900">
              {data?.totalStaffWithWorkload ?? 0}
            </span>
          </div>
          <div className="p-3.5 rounded-xl border border-gray-100 bg-gray-50/50 flex items-center justify-between">
            <span className="text-sm font-medium text-gray-600">
              Total Registered Tasks
            </span>
            <span className="text-sm font-bold text-gray-900">
              {data?.totalTasks ?? 0}
            </span>
          </div>
        </div>
      )}
    </div>
  );
}
