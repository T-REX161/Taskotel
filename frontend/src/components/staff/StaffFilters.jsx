import React from 'react';
import { SearchIcon } from '../icons/Icons';

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
  return (
    <div className="flex flex-col md:flex-row items-center gap-3 w-full">
      {/* Search staff input */}
      <div className="relative flex-1 w-full min-w-0">
        <div className="absolute inset-y-0 left-0 pl-3.5 flex items-center pointer-events-none text-gray-400">
          <SearchIcon className="w-4 h-4" />
        </div>
        <input
          type="text"
          value={searchTerm}
          onChange={(e) => onSearchChange && onSearchChange(e.target.value)}
          placeholder="Search staff by name or email..."
          className="w-full h-10 pl-10 pr-4 bg-white border border-gray-200 rounded-lg text-sm text-gray-800 placeholder-gray-400 focus:outline-none focus:ring-1 focus:ring-emerald-500 focus:border-emerald-500 transition-colors shadow-xs"
        />
      </div>

      {/* Department filter */}
      <div className="w-full md:w-48">
        <select
          value={selectedDepartment}
          onChange={(e) =>
            onDepartmentChange && onDepartmentChange(e.target.value)
          }
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

      {/* Availability filter */}
      <div className="w-full md:w-40">
        <select
          value={selectedAvailability}
          onChange={(e) =>
            onAvailabilityChange && onAvailabilityChange(e.target.value)
          }
          className="w-full h-10 px-3 bg-white border border-gray-200 rounded-lg text-sm text-gray-700 focus:outline-none focus:ring-1 focus:ring-emerald-500 focus:border-emerald-500 cursor-pointer transition-colors shadow-xs"
        >
          <option value="">All Availability</option>
          <option value="AVAILABLE">Available</option>
          <option value="UNAVAILABLE">Unavailable</option>
        </select>
      </div>

      {/* Status filter */}
      <div className="w-full md:w-36">
        <select
          value={selectedStatus}
          onChange={(e) => onStatusChange && onStatusChange(e.target.value)}
          className="w-full h-10 px-3 bg-white border border-gray-200 rounded-lg text-sm text-gray-700 focus:outline-none focus:ring-1 focus:ring-emerald-500 focus:border-emerald-500 cursor-pointer transition-colors shadow-xs"
        >
          <option value="">All Status</option>
          <option value="true">Active</option>
          <option value="false">Inactive</option>
        </select>
      </div>
    </div>
  );
}


