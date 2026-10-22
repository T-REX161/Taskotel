import React from 'react';
import StaffWorkloadTable from './StaffWorkloadTable';

/**
 * WorkloadOverview — section card displaying staff or personal workload overview.
 *
 * Props:
 *   data    – summary payload from getDashboardSummary()
 *   isStaff – boolean indicating whether current user has STAFF role view
 */
export default function WorkloadOverview({ data, isStaff }) {
  return (
    <div className="bg-white rounded-xl shadow-sm border border-gray-100 overflow-hidden">
      <div className="p-6 border-b border-gray-100 flex justify-between items-center">
        <h2 className="text-lg font-bold text-gray-900">
          {isStaff ? 'My Workload' : 'Staff Workload'}
        </h2>
        {!isStaff && (
          <span className="text-xs sm:text-sm text-gray-500 font-medium">
            Active Staff:{' '}
            <strong className="text-gray-900">
              {data?.totalStaffWithWorkload ?? 0}
            </strong>
          </span>
        )}
      </div>

      <StaffWorkloadTable data={data} isStaff={isStaff} />
    </div>
  );
}
