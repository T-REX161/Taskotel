import React from 'react';
import { breakdownRows } from '../../utils/departmentBreakdown';

/**
 * DepartmentBreakdown — a Manager who manages several departments sees each one's figures next to the combined totals above.
 * Rendered only for two or more departments; nothing for a single department, Admin or Staff.
 *
 * Props:
 *   data – the dashboard summary (departmentBreakdown comes from the server, for a Manager only)
 */
export default function DepartmentBreakdown({ data }) {
  const rows = breakdownRows(data);
  if (rows.length === 0) return null;

  return (
    <section aria-label="Department breakdown" data-testid="department-breakdown" className="bg-white rounded-2xl shadow-sm border border-gray-200 p-5 sm:p-6">
      <h2 className="text-lg font-bold text-gray-900">By department</h2>
      <p className="text-xs text-gray-500 mt-0.5">The totals above are the sum of these departments.</p>
      <div className="mt-4 overflow-x-auto">
        <table className="w-full text-sm">
          <thead>
            <tr className="text-left text-[11px] font-semibold uppercase tracking-wide text-gray-500">
              <th scope="col" className="py-2 pr-3">Department</th>
              <th scope="col" className="py-2 px-3 text-right">Staff</th>
              <th scope="col" className="py-2 px-3 text-right">Available</th>
              <th scope="col" className="py-2 px-3 text-right">Open tasks</th>
              <th scope="col" className="py-2 px-3 text-right">Active assignments</th>
              <th scope="col" className="py-2 pl-3 text-right">High workload</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            {rows.map((row) => (
              <tr key={row.code} data-testid={`department-breakdown-${row.code}`}>
                <th scope="row" className="py-2.5 pr-3 text-left font-medium text-gray-900">
                  {row.name}
                  {row.primary && <span className="ml-2 rounded-md bg-gray-100 px-1.5 py-0.5 text-[10px] font-semibold uppercase tracking-wide text-gray-600">Primary</span>}
                </th>
                <td className="py-2.5 px-3 text-right tabular-nums text-gray-700">{row.totalStaff}</td>
                <td className="py-2.5 px-3 text-right tabular-nums text-gray-700">{row.availableStaff}</td>
                <td className="py-2.5 px-3 text-right tabular-nums text-gray-700">{row.openTasks}</td>
                <td className="py-2.5 px-3 text-right tabular-nums text-gray-700">{row.activeAssignments}</td>
                <td className="py-2.5 pl-3 text-right tabular-nums text-gray-700">{row.highWorkloadStaff}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}
