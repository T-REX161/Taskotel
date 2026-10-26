import React, { useState, useEffect, useMemo, useCallback, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  WorkloadIcon,
  StaffIcon,
  TasksIcon,
  EyeIcon,
} from '../components/icons/Icons';
import { getWorkload, getDepartments, getStaff } from '../services/api';
import {
  calculateWorkloadSummary,
  calculateDepartmentWorkloads,
  getWorkloadStatusBadge,
  getWorkloadProgressBarColor,
  calculateWorkloadPercentage,
  deriveWorkloadStatus,
  sortWorkloadAlphabetically,
} from '../utils/workloadUtils';
import {
  formatDepartment,
  isAvailable,
  getAvailabilityColor,
} from '../components/staff/staffFormatters';
import StaffDetailsModal from '../components/staff/StaffDetailsModal';
import DepartmentBadge from '../components/workload/DepartmentBadge';
import UtilizationMeter from '../components/workload/UtilizationMeter';
import PageSizeToggle from '../components/common/PageSizeToggle';

// Staff rows per page by default (this list has always shown 5); the toggle offers 5, 10 or 15
const ITEMS_PER_PAGE = 5;

// How often the workload refreshes itself in the background
const WORKLOAD_REFRESH_MS = 30000;

export default function WorkloadPage() {
  const navigate = useNavigate();

  const [workload, setWorkload] = useState([]);
  const [departments, setDepartments] = useState([]);
  const [allStaff, setAllStaff] = useState([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState('');
  const [selectedStaff, setSelectedStaff] = useState(null);
  const [currentPage, setCurrentPage] = useState(1);
  const [pageSize, setPageSize] = useState(ITEMS_PER_PAGE); // rows per page, chosen with the toggle
  const isFetchingRef = useRef(false);
  const isMountedRef = useRef(true);
  const lastFetchRef = useRef(0);

  // isBackground: a quiet refresh of already-loaded data. It never shows the loading spinner or the error
  // panel, and a failed attempt keeps the data on screen (the next scheduled attempt simply tries again).
  const fetchData = useCallback(async (isBackground = false) => {
    if (isFetchingRef.current) return; // never run two requests at once
    isFetchingRef.current = true;
    if (!isBackground) {
      setIsLoading(true);
      setError('');
    }

    try {
      const workloadPromise = getWorkload();
      const departmentsPromise = getDepartments().catch((err) => {
        console.warn('Failed to load departments for workload distribution:', err);
        return null;
      });
      const staffPromise = getStaff().catch((err) => {
        console.warn('Failed to load staff list for workload view modal:', err);
        return null;
      });

      const [workloadData, departmentsData, staffData] = await Promise.all([
        workloadPromise,
        departmentsPromise,
        staffPromise,
      ]);
      if (!isMountedRef.current) return;

      setWorkload(Array.isArray(workloadData) ? workloadData : []);
      // A failed departments/staff request keeps the previous list on a background refresh
      setDepartments((prev) => (Array.isArray(departmentsData) ? departmentsData : isBackground ? prev : []));
      setAllStaff((prev) => (Array.isArray(staffData) ? staffData : isBackground ? prev : []));
      lastFetchRef.current = Date.now();
    } catch (err) {
      if (!isMountedRef.current) return;
      if (err.message && err.message.includes('Redirecting to login')) {
        navigate('/login');
        return;
      }
      if (isBackground) {
        console.warn('Background workload refresh failed; keeping the current data:', err);
      } else {
        setError(err.message || 'Unable to load staff workload data. Please try again.');
      }
    } finally {
      isFetchingRef.current = false;
      if (isMountedRef.current && !isBackground) setIsLoading(false);
    }
  }, [navigate]);

  useEffect(() => {
    isMountedRef.current = true;
    fetchData();

    // Keep the data current without any button: refresh every 30s while the tab is visible, and catch up
    // when the tab becomes visible again after being hidden for longer than that.
    const intervalId = setInterval(() => {
      if (document.visibilityState === 'visible') fetchData(true);
    }, WORKLOAD_REFRESH_MS);
    const handleVisibility = () => {
      if (document.visibilityState === 'visible' && Date.now() - lastFetchRef.current >= WORKLOAD_REFRESH_MS) {
        fetchData(true);
      }
    };
    document.addEventListener('visibilitychange', handleVisibility);

    return () => {
      isMountedRef.current = false;
      clearInterval(intervalId);
      document.removeEventListener('visibilitychange', handleVisibility);
    };
  }, [fetchData]);

  // Dynamic summary across active staff workload (complete dataset)
  const summary = useMemo(() => {
    return calculateWorkloadSummary(workload);
  }, [workload]);

  // Aggregated department workload distribution (complete dataset)
  const departmentWorkloads = useMemo(() => {
    return calculateDepartmentWorkloads(workload, departments);
  }, [workload, departments]);

  // Sort complete staff workload list alphabetically (A–Z) before pagination
  const sortedWorkload = useMemo(() => {
    return sortWorkloadAlphabetically(workload);
  }, [workload]);

  const totalPages = Math.max(1, Math.ceil(sortedWorkload.length / pageSize));

  // If the staff dataset changes and the current page becomes invalid, return to a valid page
  useEffect(() => {
    if (currentPage > totalPages) {
      setCurrentPage(totalPages);
    }
  }, [currentPage, totalPages]);

  // Paginated staff slice (pageSize rows per page)
  const paginatedWorkload = useMemo(() => {
    const startIndex = (currentPage - 1) * pageSize;
    return sortedWorkload.slice(startIndex, startIndex + pageSize);
  }, [sortedWorkload, currentPage, pageSize]);

  // A new page size starts again at page 1
  const handlePageSizeChange = (size) => {
    setPageSize(size);
    setCurrentPage(1);
  };


  // Helper to open staff details modal
  const handleViewStaff = (item) => {
    const fullRecord = allStaff.find(
      (s) => String(s.id).toLowerCase() === String(item.staffId).toLowerCase()
    );

    if (fullRecord) {
      setSelectedStaff(fullRecord);
    } else {
      setSelectedStaff({
        id: item.staffId,
        fullName: item.staffName,
        name: item.staffName,
        department: item.department,
        availability: item.availability,
        active: true,
        role: 'STAFF',
        email: '',
        phone: '',
      });
    }
  };

  // Helper to extract staff initials
  const getInitials = (name) => {
    if (!name) return '??';
    const parts = name.trim().split(/\s+/);
    if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase();
    return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
  };

  // Department progress bar color palette
  const getDepartmentBarColor = (index) => {
    const palette = [
      'bg-emerald-500',
      'bg-blue-500',
      'bg-indigo-500',
      'bg-amber-500',
      'bg-purple-500',
      'bg-teal-500',
    ];
    return palette[index % palette.length];
  };

  const summaryCards = [
    {
      title: 'Total Active Tasks',
      value: String(summary.totalActiveTasks),
      subtitle: 'Currently assigned',
      icon: <TasksIcon className="w-5 h-5 sm:w-6 sm:h-6 text-blue-600" />,
      bg: 'bg-blue-50',
      border: 'border-blue-100',
    },
    {
      title: 'Total Workload',
      value: `${summary.totalWorkload} mins`,
      subtitle: 'Across all active shifts',
      icon: <WorkloadIcon className="w-5 h-5 sm:w-6 sm:h-6 text-emerald-600" />,
      bg: 'bg-emerald-50',
      border: 'border-emerald-100',
    },
    {
      title: 'Available Staff',
      value: String(summary.availableStaff),
      subtitle: 'Ready for tasks',
      icon: <StaffIcon className="w-5 h-5 sm:w-6 sm:h-6 text-teal-600" />,
      bg: 'bg-teal-50',
      border: 'border-teal-100',
    },
    {
      title: 'Unavailable Staff',
      value: String(summary.unavailableStaff),
      subtitle: 'Busy or on leave',
      icon: <StaffIcon className="w-5 h-5 sm:w-6 sm:h-6 text-amber-600" />,
      bg: 'bg-amber-50',
      border: 'border-amber-100',
    },
  ];

  // Loading state
  if (isLoading) {
    return (
      <div className="w-full space-y-4 animate-fadeIn">
        <div className="flex justify-between items-center">
          <div>
            <h1 className="text-xl sm:text-2xl font-bold text-gray-900">Staff Workload</h1>
            <p className="text-xs sm:text-sm text-gray-500 mt-0.5">
              Monitor staff workload and active task distribution.
            </p>
          </div>
        </div>

        <div className="bg-white rounded-2xl p-12 border border-gray-100 shadow-2xs flex items-center justify-center">
          <div className="flex flex-col items-center gap-3">
            <svg
              className="animate-spin h-8 w-8 text-emerald-600"
              xmlns="http://www.w3.org/2000/svg"
              fill="none"
              viewBox="0 0 24 24"
            >
              <circle
                className="opacity-25"
                cx="12"
                cy="12"
                r="10"
                stroke="currentColor"
                strokeWidth="4"
              />
              <path
                className="opacity-75"
                fill="currentColor"
                d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"
              />
            </svg>
            <p className="text-sm font-medium text-gray-500">Loading staff workload data...</p>
          </div>
        </div>
      </div>
    );
  }

  // Error state
  if (error) {
    return (
      <div className="w-full space-y-4">
        <div className="flex justify-between items-center">
          <div>
            <h1 className="text-xl sm:text-2xl font-bold text-gray-900">Staff Workload</h1>
            <p className="text-xs sm:text-sm text-gray-500 mt-0.5">
              Monitor staff workload and active task distribution.
            </p>
          </div>
        </div>

        <div className="p-6 bg-red-50 border border-red-200 rounded-2xl text-red-800 space-y-3" role="alert">
          <h3 className="font-bold text-base">Failed to load staff workload</h3>
          <p className="text-sm">{error}</p>
          <button
            type="button"
            onClick={() => fetchData()}
            className="px-4 py-2 bg-red-600 hover:bg-red-700 text-white rounded-xl text-sm font-medium transition-colors cursor-pointer"
          >
            Try Again
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="w-full space-y-4">
      {/* Page Header */}
      <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-3">
        <div>
          <h1 className="text-xl sm:text-2xl font-bold text-gray-900">Staff Workload</h1>
          <p className="text-xs sm:text-sm text-gray-500 mt-0.5">
            Monitor staff workload and active task distribution.
          </p>
        </div>
      </div>

      {/* 1. Workload Summary: one full-width container holding the summary cards and the staff total */}
      <section className="bg-white rounded-xl shadow-2xs border border-gray-100 px-4 pt-3 pb-4 sm:px-5 sm:pt-3.5 sm:pb-5 space-y-3" aria-label="Workload summary">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <h2 className="text-sm sm:text-base font-bold text-gray-900">Workload Summary</h2>
          <span className="text-xs text-gray-500">
            Total Staff:{" "}
            <span className="font-bold text-gray-900">{sortedWorkload.length}</span>
          </span>
        </div>
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
          {summaryCards.map((card, idx) => (
            <div
              key={idx}
              className="bg-white rounded-xl p-4 shadow-2xs border border-gray-100 flex items-center space-x-3.5 hover:border-gray-200 transition-colors"
            >
              <div className={`p-2.5 sm:p-3 rounded-xl ${card.bg} border ${card.border} shrink-0`}>
                {card.icon}
              </div>
              <div className="min-w-0 flex-1">
                <p className="text-2xs sm:text-xs font-semibold text-gray-500 uppercase tracking-wider truncate">
                  {card.title}
                </p>
                <h3 className="text-xl sm:text-2xl font-bold text-gray-900 mt-0.5 truncate">
                  {card.value}
                </h3>
                <p className="text-2xs text-gray-400 mt-0.5 truncate">{card.subtitle}</p>
              </div>
            </div>
          ))}
        </div>
      </section>

      {/* 2. Workload Status Thresholds Legend (unchanged) */}
      <div className="bg-white rounded-xl p-3 border border-gray-100 shadow-2xs flex flex-wrap items-center justify-between gap-3 text-xs">
        <div className="flex items-center gap-2">
          <span className="font-semibold text-gray-700">Workload Thresholds:</span>
          <span className="text-gray-400 text-2xs">(480m Shift Baseline)</span>
        </div>
        <div className="flex flex-wrap items-center gap-3 sm:gap-5">
          <span className="inline-flex items-center gap-1.5 text-blue-700 font-medium">
            <span className="w-2 h-2 rounded-full bg-blue-500" />
            LOW: 0 – 120 mins
          </span>
          <span className="inline-flex items-center gap-1.5 text-emerald-700 font-medium">
            <span className="w-2 h-2 rounded-full bg-emerald-500" />
            NORMAL: 121 – 240 mins
          </span>
          <span className="inline-flex items-center gap-1.5 text-orange-700 font-medium">
            <span className="w-2 h-2 rounded-full bg-orange-500" />
            HIGH: 241 – 360 mins
          </span>
          <span className="inline-flex items-center gap-1.5 text-red-700 font-medium">
            <span className="w-2 h-2 rounded-full bg-red-500" />
            AT RISK: &gt; 360 mins
          </span>
        </div>
      </div>

      {/* 3. Workload by Department: full width container with one card per department */}
      <section className="min-w-0 bg-white rounded-xl shadow-2xs border border-gray-100 px-4 pt-3 pb-4 sm:px-5 sm:pt-3.5 sm:pb-5 space-y-3" aria-label="Workload by department">
        {/* Heading on the left, total department load on the right; stacks on narrow screens */}
        <div className="flex flex-wrap items-start justify-between gap-x-6 gap-y-2">
          <div className="min-w-0">
            <h2 className="text-sm sm:text-base font-bold text-gray-900">Workload by Department</h2>
            <p className="text-2xs sm:text-xs text-gray-500 mt-0.5">
              Operational load across hotel functional units
            </p>
          </div>
          <div className="min-w-0 sm:ml-auto sm:text-right">
            <p className="flex flex-wrap items-baseline gap-x-2.5 sm:justify-end text-xs sm:text-sm">
              <span className="font-medium text-gray-500">Total Department Load</span>
              <span className="font-bold text-gray-900 whitespace-nowrap">{summary.totalWorkload} mins</span>
            </p>
            <p className="text-[11px] sm:text-xs text-gray-400 mt-0.5">Calculated from active staff workloads.</p>
          </div>
        </div>

        {/* One card per department, generated from the data. auto-fill keeps every card the same width and
            wraps to new rows as departments are added, so no department count is hardcoded. */}
        <div className="grid grid-cols-[repeat(auto-fill,minmax(min(100%,17rem),1fr))] gap-4">
          {departmentWorkloads.map((dept, index) => {
            const barColor = getDepartmentBarColor(index);
            return (
              <div
                key={dept.code}
                className="min-w-0 bg-white rounded-xl p-4 shadow-2xs border border-gray-100 space-y-2.5 hover:border-gray-200 transition-colors"
              >
                <div className="flex justify-between items-start gap-2 text-xs sm:text-sm">
                  <span className="min-w-0 font-semibold text-gray-900 break-words leading-snug">{dept.name}</span>
                  <span className="font-bold text-gray-900 whitespace-nowrap">{dept.totalMinutes} mins</span>
                </div>

                {/* Proportional Progress Bar */}
                <div className="w-full h-2 bg-gray-100 rounded-full overflow-hidden">
                  <div
                    className={`h-full rounded-full transition-all duration-300 ${barColor}`}
                    style={{ width: `${Math.min(dept.percentage, 100)}%` }}
                  />
                </div>

                <div className="flex flex-wrap justify-between items-center gap-x-3 gap-y-0.5 text-2xs text-gray-500">
                  <span>
                    {dept.percentage}% of total load
                  </span>
                  <span>
                    {dept.activeTaskCount} {dept.activeTaskCount === 1 ? 'task' : 'tasks'}
                    {dept.staffCount > 0 ? ` • ${dept.staffCount} staff` : ''}
                  </span>
                </div>
              </div>
            );
          })}
        </div>
      </section>

      {/* 4. Staff Workload Distribution: full width, below Workload by Department */}
      <div className="min-w-0 bg-white rounded-xl shadow-2xs border border-gray-100 overflow-hidden">
        <div className="p-4 border-b border-gray-100 flex flex-col sm:flex-row sm:items-center justify-between gap-3 bg-white">
          <div>
            <h2 className="text-sm sm:text-base font-bold text-gray-900">Staff Workload Distribution</h2>
            <p className="text-xs text-gray-500 mt-0.5">
              Active load, task count, and shift utilization
            </p>
          </div>
          <div className="flex flex-wrap items-center gap-2.5 self-start sm:self-auto">
            <span className="text-xs font-semibold px-2.5 py-1 rounded-full bg-gray-100 text-gray-600">
              {sortedWorkload.length} Staff
            </span>
            {sortedWorkload.length > 0 && (
              <div className="flex items-center gap-1.5 pl-2.5 border-l border-gray-200">
                <PageSizeToggle value={pageSize} onChange={handlePageSizeChange} placement="bottom" />
                <span className="text-xs text-gray-500 font-medium whitespace-nowrap">
                  Page {currentPage} of {totalPages}
                </span>
                <button
                  type="button"
                  onClick={() => setCurrentPage((p) => Math.max(1, p - 1))}
                  disabled={currentPage <= 1}
                  className="w-7 h-7 flex items-center justify-center rounded border border-gray-200 bg-white hover:bg-gray-50 disabled:opacity-40 disabled:cursor-not-allowed text-gray-700 transition-colors cursor-pointer text-sm font-semibold select-none shadow-2xs"
                  aria-label="Previous page"
                  title="Previous page"
                >
                  ‹
                </button>
                <button
                  type="button"
                  onClick={() => setCurrentPage((p) => Math.min(totalPages, p + 1))}
                  disabled={currentPage >= totalPages}
                  className="w-7 h-7 flex items-center justify-center rounded border border-gray-200 bg-white hover:bg-gray-50 disabled:opacity-40 disabled:cursor-not-allowed text-gray-700 transition-colors cursor-pointer text-sm font-semibold select-none shadow-2xs"
                  aria-label="Next page"
                  title="Next page"
                >
                  ›
                </button>
              </div>
            )}
          </div>
        </div>

        {/* Desktop/Tablet Table Layout (Visible on md and up; fixed column balance fits 1366x768 without horizontal scrolling) */}
        <div className="hidden md:block w-full overflow-hidden">
          <table className="w-full text-left border-collapse table-fixed">
            <colgroup>
              <col className="w-[24%] lg:w-[25%]" />
              <col className="w-[16%] lg:w-[18%]" />
              <col className="w-[11%] lg:w-[10%]" />
              <col className="w-[12%] lg:w-[10%]" />
              <col className="w-[18%] lg:w-[22%]" />
              <col className="w-[19%] lg:w-[15%]" />
            </colgroup>
            <thead className="bg-emerald-50/50 text-xs font-semibold text-gray-500 uppercase tracking-wider border-b border-emerald-100/70 select-none">
              <tr>
                <th className="w-[24%] lg:w-[25%] px-3 lg:px-4 py-3 font-semibold whitespace-nowrap text-left" title="Staff Member">Staff</th>
                <th className="w-[16%] lg:w-[18%] px-3 lg:px-4 py-3 font-semibold whitespace-nowrap text-left">Department</th>
                <th className="w-[11%] lg:w-[10%] px-3 lg:px-4 py-3 font-semibold whitespace-nowrap text-left" title="Active Tasks">Tasks</th>
                <th className="w-[12%] lg:w-[10%] px-3 lg:px-4 py-3 font-semibold whitespace-nowrap text-left" title="Total Workload">Workload</th>
                <th className="w-[18%] lg:w-[22%] px-3 lg:px-4 py-3 font-semibold whitespace-nowrap text-left">Utilization</th>
                <th className="w-[19%] lg:w-[15%] px-3 lg:px-4 py-3 font-semibold whitespace-nowrap text-left">Availability</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100 text-[13px] sm:text-sm">
              {paginatedWorkload.length === 0 ? (
                <tr>
                  <td colSpan={6} className="px-4 py-12 text-center text-gray-500">
                    <div className="flex flex-col items-center justify-center gap-2">
                      <WorkloadIcon className="w-8 h-8 text-gray-300" />
                      <p className="font-medium text-gray-700 text-sm">No staff workload records available</p>
                      <p className="text-xs text-gray-400">
                        Workload metrics will appear as tasks are assigned to staff.
                      </p>
                    </div>
                  </td>
                </tr>
              ) : (
                paginatedWorkload.map((item, idx) => {
                  const totalDuration =
                    typeof item.totalEstimatedDuration === 'number'
                      ? item.totalEstimatedDuration
                      : parseInt(item.totalEstimatedDuration, 10) || 0;
                  const safeDuration = Math.max(0, totalDuration);
                  const activeTasks =
                    typeof item.activeTaskCount === 'number'
                      ? item.activeTaskCount
                      : parseInt(item.activeTaskCount, 10) || 0;
                  const status =
                    item.workloadStatus || deriveWorkloadStatus(safeDuration);
                  const percentage = calculateWorkloadPercentage(safeDuration, 480);
                  const available = isAvailable(item.availability);
                  const initials = getInitials(item.staffName);

                  // Check if profile details available in allStaff
                  const staffMatch = allStaff.find(
                    (s) => String(s.id).toLowerCase() === String(item.staffId).toLowerCase()
                  );

                  return (
                    <tr
                      key={item.staffId || idx}
                      className="hover:bg-emerald-50/30 transition-colors group"
                    >
                      {/* 1. Staff (Avatar + Name with accessible View trigger + Role) */}
                      <td className="px-4 py-2 min-w-0 align-middle">
                        <div className="flex items-center gap-2 lg:gap-3 min-w-0">
                          {staffMatch?.avatarUrl || staffMatch?.profileImage ? (
                            <img
                              src={staffMatch.avatarUrl || staffMatch.profileImage}
                              alt={item.staffName}
                              className="w-7 h-7 lg:w-9 lg:h-9 rounded-full object-cover shrink-0 border border-gray-200"
                            />
                          ) : (
                            <div className="w-7 h-7 lg:w-9 lg:h-9 rounded-full bg-emerald-50 text-emerald-700 font-bold text-xs flex items-center justify-center shrink-0 border border-emerald-200 select-none">
                              {initials}
                            </div>
                          )}
                          <div className="min-w-0 flex-1">
                            <button
                              type="button"
                              onClick={() => handleViewStaff(item)}
                              className="block max-w-full font-semibold text-gray-900 text-sm sm:text-[15px] leading-snug hover:text-emerald-700 transition-colors cursor-pointer text-left group/name"
                              title={`View details for ${item.staffName}`}
                            >
                              <span className="break-words">{item.staffName}</span>
                              <EyeIcon className="inline-block w-3.5 h-3.5 ml-1.5 align-[-2px] text-gray-400 group-hover/name:text-emerald-600 shrink-0 opacity-70 group-hover/name:opacity-100 transition-opacity" />
                            </button>
                            <span className={`text-[11px] text-gray-400 truncate block leading-tight mt-0.5 ${staffMatch?.role ? 'uppercase tracking-wider font-medium' : ''}`}>
                              {staffMatch?.role || `@${(item.staffName || '').toLowerCase().replace(/\s+/g, '.')}`}
                            </span>
                          </div>
                        </div>
                      </td>

                      {/* 2. Department */}
                      <td className="px-4 py-2 min-w-0 align-middle">
                        <DepartmentBadge department={item.department} />
                      </td>

                      {/* 3. Tasks */}
                      <td className="px-4 py-2 min-w-0 align-middle">
                        <span className="text-[13px] sm:text-sm font-medium text-gray-800 block truncate tabular-nums">
                          {activeTasks} {activeTasks === 1 ? 'task' : 'tasks'}
                        </span>
                      </td>

                      {/* 4. Workload */}
                      <td className="px-4 py-2 min-w-0 align-middle">
                        <span className="text-[13px] sm:text-sm font-semibold text-gray-900 block truncate tabular-nums">
                          {safeDuration} mins
                        </span>
                      </td>

                      {/* 5. Utilization (Balanced Progress Bar + Percentage + Status Badge) */}
                      <td className="px-4 py-2 min-w-0 align-middle">
                        <UtilizationMeter percentage={percentage} status={status} minutes={safeDuration} />
                      </td>

                      {/* 6. Availability */}
                      <td className="px-4 py-2 min-w-0 align-middle">
                        <span
                          className={`inline-flex items-center gap-2 h-7 px-3 rounded-full text-[13px] font-semibold whitespace-nowrap ${getAvailabilityColor(
                            item.availability
                          )}`}
                        >
                          <span
                            className={`w-2 h-2 rounded-full shrink-0 ${
                              available ? 'bg-emerald-500' : 'bg-amber-500'
                            }`}
                          />
                          {available ? 'Available' : 'Unavailable'}
                        </span>
                      </td>
                    </tr>
                  );
                })
              )}
            </tbody>
          </table>
        </div>

        {/* Mobile Responsive Staff Card Layout (< md viewport to prevent horizontal scrolling) */}
        <div className="block md:hidden divide-y divide-gray-100">
          {paginatedWorkload.length === 0 ? (
            <div className="p-8 text-center text-gray-500">
              <WorkloadIcon className="w-8 h-8 text-gray-300 mx-auto mb-2" />
              <p className="font-medium text-gray-700 text-sm">No staff workload records available</p>
              <p className="text-xs text-gray-400 mt-1">
                Workload metrics will appear as tasks are assigned to staff.
              </p>
            </div>
          ) : (
            paginatedWorkload.map((item, idx) => {
              const totalDuration =
                typeof item.totalEstimatedDuration === 'number'
                  ? item.totalEstimatedDuration
                  : parseInt(item.totalEstimatedDuration, 10) || 0;
              const safeDuration = Math.max(0, totalDuration);
              const activeTasks =
                typeof item.activeTaskCount === 'number'
                  ? item.activeTaskCount
                  : parseInt(item.activeTaskCount, 10) || 0;
              const status =
                item.workloadStatus || deriveWorkloadStatus(safeDuration);
              const percentage = calculateWorkloadPercentage(safeDuration, 480);
              const available = isAvailable(item.availability);
              const initials = getInitials(item.staffName);
              const staffMatch = allStaff.find(
                (s) => String(s.id).toLowerCase() === String(item.staffId).toLowerCase()
              );

              return (
                <div
                  key={item.staffId || idx}
                  className="p-4 space-y-3 hover:bg-gray-50/50 transition-colors"
                >
                  {/* Header Row: Staff Info + Availability Pill */}
                  <div className="flex items-start justify-between gap-3">
                    <div className="flex items-center gap-2.5 min-w-0">
                      {staffMatch?.avatarUrl || staffMatch?.profileImage ? (
                        <img
                          src={staffMatch.avatarUrl || staffMatch?.profileImage}
                          alt={item.staffName}
                          className="w-8 h-8 rounded-full object-cover shrink-0 border border-gray-200"
                        />
                      ) : (
                        <div className="w-8 h-8 rounded-full bg-emerald-50 text-emerald-700 font-bold text-xs flex items-center justify-center shrink-0 border border-emerald-200 select-none">
                          {initials}
                        </div>
                      )}
                      <div className="min-w-0">
                        <button
                          type="button"
                          onClick={() => handleViewStaff(item)}
                          className="font-semibold text-gray-900 text-sm text-left truncate flex items-center gap-1 hover:text-emerald-700 cursor-pointer"
                          title={`View details for ${item.staffName}`}
                        >
                          <span className="truncate">{item.staffName}</span>
                          <EyeIcon className="w-3.5 h-3.5 text-gray-400 shrink-0" />
                        </button>
                        <span className="text-2xs text-gray-400 block truncate">
                          {staffMatch?.role || `@${(item.staffName || '').toLowerCase().replace(/\s+/g, '.')}`}
                        </span>
                      </div>
                    </div>
                    <span
                      className={`inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-2xs font-semibold shrink-0 ${getAvailabilityColor(
                        item.availability
                      )}`}
                    >
                      <span
                        className={`w-1.5 h-1.5 rounded-full shrink-0 ${
                          available ? 'bg-emerald-500' : 'bg-amber-500'
                        }`}
                      />
                      {available ? 'Available' : 'Unavailable'}
                    </span>
                  </div>

                  {/* Meta Row: Department, Tasks, Workload */}
                  <div className="grid grid-cols-3 gap-2 text-xs pt-2 border-t border-gray-50">
                    <div>
                      <span className="text-2xs text-gray-400 block">Department</span>
                      <span className="font-medium text-gray-700 truncate block mt-0.5">
                        {formatDepartment(item.department)}
                      </span>
                    </div>
                    <div>
                      <span className="text-2xs text-gray-400 block">Active Tasks</span>
                      <span className="font-medium text-gray-800 block mt-0.5">
                        {activeTasks} {activeTasks === 1 ? 'task' : 'tasks'}
                      </span>
                    </div>
                    <div>
                      <span className="text-2xs text-gray-400 block">Workload</span>
                      <span className="font-semibold text-gray-900 block mt-0.5">
                        {safeDuration} mins
                      </span>
                    </div>
                  </div>

                  {/* Utilization Progress Bar */}
                  <div className="space-y-1.5 pt-1">
                    <div className="flex justify-between items-center text-xs">
                      <span className="text-2xs font-medium text-gray-500">
                        Shift Utilization ({safeDuration}m / 480m)
                      </span>
                      <div className="flex items-center gap-1.5">
                        <span className="font-bold text-gray-800">{percentage}%</span>
                        <span
                          className={`px-1.5 py-0.5 rounded text-2xs font-bold uppercase ${getWorkloadStatusBadge(
                            status
                          )}`}
                        >
                          {status.replace('_', ' ')}
                        </span>
                      </div>
                    </div>
                    <div className="w-full h-1.5 bg-gray-100 rounded-full overflow-hidden">
                      <div
                        className={`h-full rounded-full transition-all duration-300 ${getWorkloadProgressBarColor(
                          status
                        )}`}
                        style={{ width: `${Math.min(percentage, 100)}%` }}
                      />
                    </div>
                  </div>
                </div>
              );
            })
          )}
        </div>
      </div>

      {/* Staff Details Modal */}
      {selectedStaff && (
        <StaffDetailsModal
          staff={selectedStaff}
          onClose={() => setSelectedStaff(null)}
        />
      )}
    </div>
  );
}
