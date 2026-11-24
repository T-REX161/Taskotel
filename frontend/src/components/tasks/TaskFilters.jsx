import React from 'react';
import { SearchIcon } from '../icons/Icons';
import DepartmentOptions from '../common/DepartmentOptions';
import useDepartments from '../../hooks/useDepartments';

/**
 * TaskFilters — unified, single horizontal filter toolbar beneath the Tasks header.
 * Order: Search, Status, Priority, Department.
 */
// Same dropdown arrow as the Users page (the native one is hidden so every select looks the same)
function ChevronDownIcon({ className }) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="m6 9 6 6 6-6" />
    </svg>
  );
}

export default function TaskFilters({
  searchTerm = '',
  onSearchChange,
  selectedStatus = '',
  onStatusChange,
  selectedPriority = '',
  onPriorityChange,
  selectedDepartment = '',
  onDepartmentChange,
}) {
  const { departments } = useDepartments();
  return (
    <div className="grid grid-cols-1 sm:grid-cols-3 gap-2 lg:flex lg:items-center w-full">
      {/* 1. Search tasks input with search icon */}
      <div className="relative w-full min-w-0 sm:col-span-3 lg:flex-1">
        <div className="absolute inset-y-0 left-0 pl-3.5 flex items-center pointer-events-none text-gray-400">
          <SearchIcon className="w-[18px] h-[18px]" />
        </div>
        <input
          type="text"
          value={searchTerm}
          onChange={(e) => onSearchChange && onSearchChange(e.target.value)}
          placeholder="Search by title, category, description..."
          className="w-full h-10 pl-10 pr-3 bg-white border border-gray-200 rounded-lg text-sm text-gray-800 placeholder-gray-400 focus:outline-none focus:ring-2 focus:ring-emerald-500/30 focus:border-emerald-500 transition-colors"
        />
      </div>

      {/* 2. Status dropdown */}
      <div className="relative w-full lg:w-40">
        <select
          value={selectedStatus}
          onChange={(e) => onStatusChange && onStatusChange(e.target.value)}
          className="w-full h-10 pl-4 pr-10 appearance-none bg-white border border-gray-200 rounded-lg text-sm text-gray-800 cursor-pointer focus:outline-none focus:ring-2 focus:ring-emerald-500/30 focus:border-emerald-500 transition-colors"
        >
          <option value="">All Statuses</option>
          <option value="PENDING">Pending</option>
          <option value="ASSIGNED">Assigned</option>
          <option value="IN_PROGRESS">In Progress</option>
          <option value="COMPLETED">Completed</option>
          <option value="CANCELLED">Cancelled</option>
        </select>
        <ChevronDownIcon className="w-4 h-4 text-gray-500 absolute right-3.5 top-1/2 -translate-y-1/2 pointer-events-none" />
      </div>

      {/* 3. Priority dropdown */}
      <div className="relative w-full lg:w-40">
        <select
          value={selectedPriority}
          onChange={(e) => onPriorityChange && onPriorityChange(e.target.value)}
          className="w-full h-10 pl-4 pr-10 appearance-none bg-white border border-gray-200 rounded-lg text-sm text-gray-800 cursor-pointer focus:outline-none focus:ring-2 focus:ring-emerald-500/30 focus:border-emerald-500 transition-colors"
        >
          <option value="">All Priorities</option>
          <option value="LOW">Low</option>
          <option value="MEDIUM">Medium</option>
          <option value="HIGH">High</option>
          <option value="URGENT">Urgent</option>
        </select>
        <ChevronDownIcon className="w-4 h-4 text-gray-500 absolute right-3.5 top-1/2 -translate-y-1/2 pointer-events-none" />
      </div>

      {/* 4. Department dropdown */}
      <div className="relative w-full lg:w-44">
        <select
          value={selectedDepartment}
          onChange={(e) => onDepartmentChange && onDepartmentChange(e.target.value)}
          className="w-full h-10 pl-4 pr-10 appearance-none bg-white border border-gray-200 rounded-lg text-sm text-gray-800 cursor-pointer focus:outline-none focus:ring-2 focus:ring-emerald-500/30 focus:border-emerald-500 transition-colors"
        >
          <option value="">All Departments</option>
          <DepartmentOptions departments={departments} />
        </select>
        <ChevronDownIcon className="w-4 h-4 text-gray-500 absolute right-3.5 top-1/2 -translate-y-1/2 pointer-events-none" />
      </div>
    </div>
  );
}
