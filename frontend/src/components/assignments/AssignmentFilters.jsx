import React from 'react';
import { CloseIcon, SearchIcon } from '../icons/Icons';
import DepartmentOptions from '../common/DepartmentOptions';
import useDepartments from '../../hooks/useDepartments';
import { ASSIGNED_TIME_OPTIONS } from '../../utils/assignmentUtils';
import { filterControlClass } from './assignmentTableStyles';

/**
 * AssignmentFilters — the search and filter row of the Current Staff Assignments table: the individually styled fields side by side,
 * with no surrounding container of their own (no border, background or padding).
 * Order: Search (the widest), Department, Assigned Time, then Clear Filters while anything is set. A field that holds a value is
 * tinted green, so the active filters are easy to spot.
 * The department names come from the departments API (the same list the Tasks filter uses); filtering itself happens on the page,
 * over the assignments it already loaded.
 *
 * Props:
 *   filters   – { search, department, assignedTime }
 *   onChange  – (key, value) => void
 *   onClear   – () => void
 *   isActive  – whether any search or filter is set (shows Clear Filters)
 */
export default function AssignmentFilters({ filters, onChange, onClear, isActive }) {
  const { departments } = useDepartments();
  const searchActive = Boolean(String(filters.search || '').trim());
  return (
    <div className="flex flex-col md:flex-row md:items-center gap-2 w-full">
      {/* 1. Search: task title or staff member */}
      <div className="relative flex-1 w-full min-w-0">
        <div className={`absolute inset-y-0 left-0 pl-3.5 flex items-center pointer-events-none ${searchActive ? 'text-emerald-600' : 'text-gray-400'}`}>
          <SearchIcon className="w-4 h-4" />
        </div>
        <input
          type="text"
          value={filters.search}
          onChange={(e) => onChange('search', e.target.value)}
          placeholder="Search by task or staff member..."
          aria-label="Search assignments by task title or staff member"
          className={`${filterControlClass(searchActive)} pl-10 pr-4 placeholder-gray-400`}
        />
      </div>

      {/* 2. Department */}
      <div className="w-full md:w-48">
        <select
          value={filters.department}
          onChange={(e) => onChange('department', e.target.value)}
          aria-label="Filter by department"
          className={`${filterControlClass(Boolean(filters.department))} px-3 cursor-pointer`}
        >
          <option value="">All Departments</option>
          <DepartmentOptions departments={departments} />
        </select>
      </div>

      {/* 3. Assigned Time (when the assignment was made) */}
      <div className="w-full md:w-44">
        <select
          value={filters.assignedTime}
          onChange={(e) => onChange('assignedTime', e.target.value)}
          aria-label="Filter by assigned time"
          className={`${filterControlClass(Boolean(filters.assignedTime))} px-3 cursor-pointer`}
        >
          {ASSIGNED_TIME_OPTIONS.map((option) => (
            <option key={option.value || 'any'} value={option.value}>{option.label}</option>
          ))}
        </select>
      </div>

      {/* 4. Clear Filters, only while something is set */}
      {isActive && (
        <button
          type="button"
          onClick={onClear}
          className="shrink-0 inline-flex items-center justify-center gap-1.5 h-9 px-3 rounded-lg text-sm font-medium text-emerald-700 hover:bg-emerald-100/70 hover:text-emerald-800 transition-colors cursor-pointer focus:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 focus-visible:ring-offset-1"
        >
          <CloseIcon className="w-3.5 h-3.5" aria-hidden="true" />
          Clear Filters
        </button>
      )}
    </div>
  );
}
