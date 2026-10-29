import React from 'react';
import StaffWorkloadTable from './StaffWorkloadTable';
import StaffWorkloadList from './StaffWorkloadList';

/**
 * WorkloadOverview — section card displaying staff or personal workload overview.
 * Admin / Manager see one row per staff member when the workload list loaded, otherwise the band summary from the
 * dashboard payload; Staff see only their own record.
 *
 * Props:
 *   data          – summary payload from getDashboardSummary()
 *   isStaff       – boolean indicating whether current user has STAFF role view
 *   staffWorkload – { status: 'loading'|'ready'|'error', data: [] } from GET /api/workload (Admin / Manager only)
 */
export default function WorkloadOverview({ data, isStaff, staffWorkload }) {
  const listReady = !isStaff && staffWorkload?.status === 'ready';
  return (
    <div className="bg-white rounded-xl border border-gray-200 shadow-[0_1px_2px_rgba(16,24,40,0.04)] overflow-hidden h-full flex flex-col">
      <div className="px-5 sm:px-6 pt-4 pb-3 border-b border-gray-100 flex justify-between items-center gap-3">
        <h2 className="text-lg font-semibold text-gray-900">{isStaff ? 'My Workload' : 'Staff Workload'}</h2>
      </div>

      {!isStaff && staffWorkload?.status === 'loading' && <p role="status" className="px-5 py-8 text-center text-sm text-gray-500">Loading staff workload…</p>}
      {!isStaff && staffWorkload?.status === 'error' && (
        <p role="alert" className="px-5 py-3 text-xs text-amber-700 bg-amber-50 border-b border-amber-100">
          The per-person workload could not be loaded, so the summary by workload level is shown instead.
        </p>
      )}
      {listReady ? <StaffWorkloadList staff={staffWorkload.data} /> : staffWorkload?.status !== 'loading' && <StaffWorkloadTable data={data} isStaff={isStaff} />}
    </div>
  );
}
