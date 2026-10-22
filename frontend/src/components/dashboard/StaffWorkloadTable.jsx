import React from 'react';

/**
 * StaffWorkloadTable — table rendering workload distributions or individual workload record.
 *
 * Props:
 *   data    – summary payload containing workload / distribution counts
 *   isStaff – boolean indicating whether current user has STAFF view
 */
export default function StaffWorkloadTable({ data, isStaff }) {
  const totalWorkloadStaff = data?.totalStaffWithWorkload || 1;

  return (
    <div className="overflow-x-auto">
      {isStaff ? (
        /* STAFF view: Single personal workload record */
        <table className="w-full text-left">
          <thead className="bg-gray-50/50 text-sm text-gray-500 border-b border-gray-100">
            <tr>
              <th className="px-6 py-4 font-medium">Staff Member</th>
              <th className="px-6 py-4 font-medium">Department</th>
              <th className="px-6 py-4 font-medium">Active Tasks</th>
              <th className="px-6 py-4 font-medium">Workload Duration</th>
              <th className="px-6 py-4 font-medium">Status</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100 text-sm">
            {data?.workload ? (
              <tr className="hover:bg-gray-50/50 transition-colors">
                <td className="px-6 py-4 font-medium text-gray-900">
                  {data.workload.staffName || 'You'}
                </td>
                <td className="px-6 py-4 text-gray-600">
                  {data.workload.department || 'N/A'}
                </td>
                <td className="px-6 py-4 text-gray-600">
                  {data.workload.activeTaskCount ?? 0}
                </td>
                <td className="px-6 py-4">
                  <div className="flex items-center space-x-3">
                    <span className="text-gray-600 w-14">
                      {data.workload.totalEstimatedDuration ?? 0} min
                    </span>
                    <div className="flex-1 h-2 bg-gray-100 rounded-full overflow-hidden min-w-[60px]">
                      <div
                        className={`h-full rounded-full ${
                          data.workload.workloadStatus === 'AT_RISK'
                            ? 'bg-red-500'
                            : data.workload.workloadStatus === 'HIGH'
                            ? 'bg-orange-500'
                            : data.workload.workloadStatus === 'NORMAL'
                            ? 'bg-blue-500'
                            : 'bg-emerald-500'
                        }`}
                        style={{
                          width: `${Math.min(
                            100,
                            ((data.workload.totalEstimatedDuration || 0) / 360) * 100
                          )}%`,
                        }}
                      />
                    </div>
                  </div>
                </td>
                <td className="px-6 py-4">
                  <span
                    className={`inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium ${
                      data.workload.workloadStatus === 'AT_RISK'
                        ? 'bg-red-100 text-red-800'
                        : data.workload.workloadStatus === 'HIGH'
                        ? 'bg-orange-100 text-orange-800'
                        : data.workload.workloadStatus === 'NORMAL'
                        ? 'bg-blue-100 text-blue-800'
                        : 'bg-emerald-100 text-emerald-800'
                    }`}
                  >
                    {data.workload.workloadStatus || 'NORMAL'}
                  </span>
                </td>
              </tr>
            ) : (
              <tr>
                <td colSpan="5" className="px-6 py-6 text-center text-gray-400">
                  No active workload record found.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      ) : (
        /* ADMIN / MANAGER view: Organization-level workload summary data */
        <table className="w-full text-left">
          <thead className="bg-gray-50/50 text-sm text-gray-500 border-b border-gray-100">
            <tr>
              <th className="px-6 py-4 font-medium">Workload Level</th>
              <th className="px-6 py-4 font-medium">Duration Range</th>
              <th className="px-6 py-4 font-medium">Staff Count</th>
              <th className="px-6 py-4 font-medium">Distribution</th>
              <th className="px-6 py-4 font-medium">Status</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100 text-sm">
            {/* Low Workload */}
            <tr className="hover:bg-gray-50/50 transition-colors">
              <td className="px-6 py-4 font-medium text-gray-900">Low Workload</td>
              <td className="px-6 py-4 text-gray-600">0 – 120 min</td>
              <td className="px-6 py-4 font-semibold text-gray-900">
                {data?.lowWorkloadStaff ?? 0}
              </td>
              <td className="px-6 py-4">
                <div className="flex items-center space-x-3">
                  <div className="flex-1 h-2 bg-gray-100 rounded-full overflow-hidden min-w-[80px]">
                    <div
                      className="h-full rounded-full bg-emerald-500 transition-all duration-300"
                      style={{
                        width: `${Math.min(
                          100,
                          ((data?.lowWorkloadStaff || 0) / totalWorkloadStaff) * 100
                        )}%`,
                      }}
                    />
                  </div>
                </div>
              </td>
              <td className="px-6 py-4">
                <span className="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-emerald-100 text-emerald-800">
                  LOW
                </span>
              </td>
            </tr>

            {/* Normal Workload */}
            <tr className="hover:bg-gray-50/50 transition-colors">
              <td className="px-6 py-4 font-medium text-gray-900">Normal Workload</td>
              <td className="px-6 py-4 text-gray-600">121 – 240 min</td>
              <td className="px-6 py-4 font-semibold text-gray-900">
                {data?.normalWorkloadStaff ?? 0}
              </td>
              <td className="px-6 py-4">
                <div className="flex items-center space-x-3">
                  <div className="flex-1 h-2 bg-gray-100 rounded-full overflow-hidden min-w-[80px]">
                    <div
                      className="h-full rounded-full bg-blue-500 transition-all duration-300"
                      style={{
                        width: `${Math.min(
                          100,
                          ((data?.normalWorkloadStaff || 0) / totalWorkloadStaff) * 100
                        )}%`,
                      }}
                    />
                  </div>
                </div>
              </td>
              <td className="px-6 py-4">
                <span className="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-blue-100 text-blue-800">
                  NORMAL
                </span>
              </td>
            </tr>

            {/* High Workload */}
            <tr className="hover:bg-gray-50/50 transition-colors">
              <td className="px-6 py-4 font-medium text-gray-900">High Workload</td>
              <td className="px-6 py-4 text-gray-600">241 – 360 min</td>
              <td className="px-6 py-4 font-semibold text-gray-900">
                {data?.highWorkloadStaff ?? 0}
              </td>
              <td className="px-6 py-4">
                <div className="flex items-center space-x-3">
                  <div className="flex-1 h-2 bg-gray-100 rounded-full overflow-hidden min-w-[80px]">
                    <div
                      className="h-full rounded-full bg-orange-500 transition-all duration-300"
                      style={{
                        width: `${Math.min(
                          100,
                          ((data?.highWorkloadStaff || 0) / totalWorkloadStaff) * 100
                        )}%`,
                      }}
                    />
                  </div>
                </div>
              </td>
              <td className="px-6 py-4">
                <span className="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-orange-100 text-orange-800">
                  HIGH
                </span>
              </td>
            </tr>

            {/* At Risk Workload */}
            <tr className="hover:bg-gray-50/50 transition-colors">
              <td className="px-6 py-4 font-medium text-gray-900">At Risk</td>
              <td className="px-6 py-4 text-gray-600">&gt; 360 min</td>
              <td className="px-6 py-4 font-semibold text-gray-900">
                {data?.atRiskStaff ?? 0}
              </td>
              <td className="px-6 py-4">
                <div className="flex items-center space-x-3">
                  <div className="flex-1 h-2 bg-gray-100 rounded-full overflow-hidden min-w-[80px]">
                    <div
                      className="h-full rounded-full bg-red-500 transition-all duration-300"
                      style={{
                        width: `${Math.min(
                          100,
                          ((data?.atRiskStaff || 0) / totalWorkloadStaff) * 100
                        )}%`,
                      }}
                    />
                  </div>
                </div>
              </td>
              <td className="px-6 py-4">
                <span className="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-red-100 text-red-800">
                  AT RISK
                </span>
              </td>
            </tr>
          </tbody>
        </table>
      )}
    </div>
  );
}
