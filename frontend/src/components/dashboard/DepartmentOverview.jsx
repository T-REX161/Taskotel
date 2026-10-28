import React from 'react';
import { DepartmentsIcon } from '../icons/Icons';

const DEFAULT_DEPARTMENTS = [
  'Front Desk',
  'Housekeeping',
  'Maintenance',
  'F&B',
  'Concierge',
  'Security',
];

/**
 * DepartmentOverview — displays hotel operational departments in a card grid.
 *
 * Props:
 *   departments – array of department names (optional, defaults to standard 6 departments)
 */
export default function DepartmentOverview({ departments = DEFAULT_DEPARTMENTS }) {
  return (
    <div className="bg-white rounded-2xl shadow-sm border border-gray-200 p-5 sm:p-6">
      <div className="flex justify-between items-center mb-4">
        <h2 className="text-lg font-bold text-gray-900">Department Overview</h2>
      </div>
      <div className="grid grid-cols-2 md:grid-cols-3 gap-4">
        {departments.map((dept, idx) => (
          <div
            key={idx}
            className="p-4 rounded-lg border border-gray-100 hover:border-emerald-500/30 hover:bg-emerald-50/30 transition-colors cursor-pointer group"
          >
            <div className="flex items-center space-x-3">
              <div className="p-2 bg-gray-50 rounded-lg group-hover:bg-white transition-colors">
                <DepartmentsIcon className="w-5 h-5 text-gray-400 group-hover:text-emerald-600" />
              </div>
              <span className="font-medium text-gray-700 group-hover:text-emerald-700">
                {dept}
              </span>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
