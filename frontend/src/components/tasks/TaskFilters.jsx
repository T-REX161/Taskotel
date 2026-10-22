import React from 'react';
import { SearchIcon } from '../icons/Icons';

/**
 * TaskFilters — unified, single horizontal filter toolbar beneath the Tasks header.
 * Order: Search, Status, Priority, Department.
 */
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
  return (
    <div className="flex flex-col md:flex-row items-center gap-3 w-full">
      {/* 1. Search tasks input with search icon */}
      <div className="relative flex-1 w-full min-w-0">
        <div className="absolute inset-y-0 left-0 pl-3.5 flex items-center pointer-events-none text-gray-400">
          <SearchIcon className="w-4 h-4" />
        </div>
        <input
          type="text"
          value={searchTerm}
          onChange={(e) => onSearchChange && onSearchChange(e.target.value)}
          placeholder="Search by title, category, description..."
          className="w-full h-10 pl-10 pr-4 bg-white border border-gray-200 rounded-lg text-sm text-gray-800 placeholder-gray-400 focus:outline-none focus:ring-1 focus:ring-emerald-500 focus:border-emerald-500 transition-colors shadow-xs"
        />
      </div>

      {/* 2. Status dropdown */}
      <div className="w-full md:w-44">
        <select
          value={selectedStatus}
          onChange={(e) => onStatusChange && onStatusChange(e.target.value)}
          className="w-full h-10 px-3 bg-white border border-gray-200 rounded-lg text-sm text-gray-700 focus:outline-none focus:ring-1 focus:ring-emerald-500 focus:border-emerald-500 cursor-pointer transition-colors shadow-xs"
        >
          <option value="">All Statuses</option>
          <option value="PENDING">Pending</option>
          <option value="ASSIGNED">Assigned</option>
          <option value="IN_PROGRESS">In Progress</option>
          <option value="COMPLETED">Completed</option>
          <option value="CANCELLED">Cancelled</option>
        </select>
      </div>

      {/* 3. Priority dropdown */}
      <div className="w-full md:w-44">
        <select
          value={selectedPriority}
          onChange={(e) => onPriorityChange && onPriorityChange(e.target.value)}
          className="w-full h-10 px-3 bg-white border border-gray-200 rounded-lg text-sm text-gray-700 focus:outline-none focus:ring-1 focus:ring-emerald-500 focus:border-emerald-500 cursor-pointer transition-colors shadow-xs"
        >
          <option value="">All Priorities</option>
          <option value="LOW">Low</option>
          <option value="MEDIUM">Medium</option>
          <option value="HIGH">High</option>
          <option value="URGENT">Urgent</option>
        </select>
      </div>

      {/* 4. Department dropdown */}
      <div className="w-full md:w-48">
        <select
          value={selectedDepartment}
          onChange={(e) => onDepartmentChange && onDepartmentChange(e.target.value)}
          className="w-full h-10 px-3 bg-white border border-gray-200 rounded-lg text-sm text-gray-700 focus:outline-none focus:ring-1 focus:ring-emerald-500 focus:border-emerald-500 cursor-pointer transition-colors shadow-xs"
        >
          <option value="">All Departments</option>
          <option value="HOUSEKEEPING">Housekeeping</option>
          <option value="FRONT_DESK">Front Desk</option>
          <option value="MAINTENANCE">Maintenance</option>
          <option value="ROOM_SERVICE">Room Service</option>
          <option value="FOOD_AND_BEVERAGE">Food &amp; Beverage</option>
          <option value="SECURITY">Security</option>
        </select>
      </div>
    </div>
  );
}
