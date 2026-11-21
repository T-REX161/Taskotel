import React from 'react';
import { SearchIcon } from '../icons/Icons';
import DepartmentOptions from '../common/DepartmentOptions';
import useDepartments from '../../hooks/useDepartments';
import { getUserRole } from '../../services/api';
import { UNASSIGNED_FILTER, UNASSIGNED_LABEL, canFilterUnassigned } from '../../utils/staffDepartment';

/**
 * StaffFilters — unified, cohesive filter toolbar beneath the Staff Management header.
 *
 * Props:
 *   searchTerm             – current search query
 *   onSearchChange         – (val: string) => void
 *   selectedDepartment     – current department filter
 *   onDepartmentChange     – (val: string) => void
 *   selectedAvailability   – current availability filter
 *   onAvailabilityChange   – (val: string) => void
 *   selectedStatus         – current status filter
 *   onStatusChange         – (val: string) => void
 *   onClearFilters         – optional callback to reset all filters
 */
// Same dropdown arrow as the Users page (the native one is hidden so every select looks the same)
function ChevronDownIcon({ className }) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="m6 9 6 6 6-6" />
    </svg>
  );
}

export default function StaffFilters({
  searchTerm = '',
  onSearchChange,
  selectedDepartment = '',
  onDepartmentChange,
  selectedAvailability = '',
  onAvailabilityChange,
  selectedStatus = '',
  onStatusChange,
}) {
  const { departments } = useDepartments();
  return (
    <div className="flex flex-col md:flex-row items-center gap-3 w-full">
      {/* Search staff input */}
      <div className="relative flex-1 w-full min-w-0">
        <div className="absolute inset-y-0 left-0 pl-3.5 flex items-center pointer-events-none text-gray-400">
          <SearchIcon className="w-[18px] h-[18px]" />
        </div>
        <input
          type="text"
          value={searchTerm}
          onChange={(e) => onSearchChange && onSearchChange(e.target.value)}
          placeholder="Search staff by name or email..."
          className="w-full h-11 pl-10 pr-3 bg-white border border-gray-200 rounded-xl text-sm text-gray-800 placeholder-gray-400 focus:outline-none focus:ring-2 focus:ring-emerald-500/30 focus:border-emerald-500 transition-colors"
        />
      </div>

      {/* Department filter */}
      <div className="relative w-full md:w-48">
        <select
          value={selectedDepartment}
          onChange={(e) =>
            onDepartmentChange && onDepartmentChange(e.target.value)
          }
          className="w-full h-11 pl-4 pr-10 appearance-none bg-white border border-gray-200 rounded-xl text-sm text-gray-800 cursor-pointer focus:outline-none focus:ring-2 focus:ring-emerald-500/30 focus:border-emerald-500 transition-colors"
        >
          <option value="">All Departments</option>
          <DepartmentOptions departments={departments} />
          {canFilterUnassigned(getUserRole()) && <option value={UNASSIGNED_FILTER}>{UNASSIGNED_LABEL}</option>}
        </select>
        <ChevronDownIcon className="w-4 h-4 text-gray-500 absolute right-3.5 top-1/2 -translate-y-1/2 pointer-events-none" />
      </div>

      {/* Availability filter */}
      <div className="relative w-full md:w-44">
        <select
          value={selectedAvailability}
          onChange={(e) =>
            onAvailabilityChange && onAvailabilityChange(e.target.value)
          }
          className="w-full h-11 pl-4 pr-10 appearance-none bg-white border border-gray-200 rounded-xl text-sm text-gray-800 cursor-pointer focus:outline-none focus:ring-2 focus:ring-emerald-500/30 focus:border-emerald-500 transition-colors"
        >
          <option value="">All Availability</option>
          <option value="AVAILABLE">Available</option>
          <option value="UNAVAILABLE">Unavailable</option>
        </select>
        <ChevronDownIcon className="w-4 h-4 text-gray-500 absolute right-3.5 top-1/2 -translate-y-1/2 pointer-events-none" />
      </div>

      {/* Status filter */}
      <div className="relative w-full md:w-40">
        <select
          value={selectedStatus}
          onChange={(e) => onStatusChange && onStatusChange(e.target.value)}
          className="w-full h-11 pl-4 pr-10 appearance-none bg-white border border-gray-200 rounded-xl text-sm text-gray-800 cursor-pointer focus:outline-none focus:ring-2 focus:ring-emerald-500/30 focus:border-emerald-500 transition-colors"
        >
          <option value="">All Status</option>
          <option value="true">Active</option>
          <option value="false">Inactive</option>
        </select>
        <ChevronDownIcon className="w-4 h-4 text-gray-500 absolute right-3.5 top-1/2 -translate-y-1/2 pointer-events-none" />
      </div>
    </div>
  );
}


