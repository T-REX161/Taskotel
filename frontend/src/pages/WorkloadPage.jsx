import React, { useState, useEffect, useMemo, useCallback, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  WorkloadIcon,
  StaffIcon,
  TasksIcon,
  EyeIcon,
} from '../components/icons/Icons';
import TotalRemainingBreakdown from '../components/workload/TotalRemainingBreakdown';
import { getWorkload, getWorkloadDepartments, getDepartmentCapacity, getStaff, getUserRole } from '../services/api';
import { canListStaff } from '../utils/accessPolicy';
import { decideWorkloadRefresh, selectedStaffAfterRefresh } from '../utils/workloadRefresh';
import { formatTime } from '../utils/dateTimeFormat';
import { getPreferences } from '../utils/preferences';
import {
  calculateWorkloadSummary,
  restrictWorkloadToDepartments,
  getWorkloadStatusBadge,
  getWorkloadProgressBarColor,
  getDepartmentLevelBarColor,
  departmentLevelLabel,
  getDepartmentLegendRanges,
  parseDepartmentCapacityAnswer,
  describeDepartmentRisk,
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
import DepartmentLegendTiles from '../components/workload/DepartmentLegendTiles';
import CrossDepartmentSupportPanel from '../components/workload/CrossDepartmentSupportPanel';
import PageSizeToggle from '../components/common/PageSizeToggle';
import AnimatedNumber from '../components/common/AnimatedNumber';
import DepartmentOptions from '../components/common/DepartmentOptions';
import UserAvatar from '../components/common/UserAvatar';
import LoadError from '../components/common/LoadError';
import { WorkloadPageSkeleton } from '../components/common/PageSkeletons';
import { loadCrossDepartmentStatus, peekCrossDepartmentStatus } from '../utils/crossDepartmentStatus';

// Staff rows per page by default (this list has always shown 5); the toggle offers 5, 10 or 15
const ITEMS_PER_PAGE = 5;

// How often the workload refreshes itself in the background
const WORKLOAD_REFRESH_MS = 30000;

/** One request's answer as {ok, data} or {ok, error}, so that each part of a refresh can be decided on its own. */
const settle = (promise, warning) =>
  promise.then(
    (data) => ({ ok: true, data }),
    (error) => {
      if (warning) console.warn(warning, error);
      return { ok: false, error };
    }
  );

export default function WorkloadPage() {
  const navigate = useNavigate();

  const [workload, setWorkload] = useState([]);
  const [departments, setDepartments] = useState([]);
  const [allStaff, setAllStaff] = useState([]);
  // The department capacity answer (see utils/workloadUtils): { status: 'loading' } until the first answer; { status: 'ready', enabled, threshold,
  // shiftMinutes, departments } when the server answered (utilization and risk are decided there, never recomputed here); { status: 'unavailable' }
  // when it cannot be read: nothing is guessed and no fixed legend is shown as if it were current. A later failed refresh keeps the last answer.
  const [capacityState, setCapacityState] = useState({ status: 'loading' });
  const [isLoading, setIsLoading] = useState(true);
  // Cross-Department Assistance (Admin and Manager only): read as the page opens, alongside the workload, so the loading skeleton
  // reserves room for the support panel only when it will be shown. The panel reuses this same answer when it mounts (no second
  // request). Until it arrives the last value known in this session is used; with none, no room is reserved (nothing to collapse later).
  const canSeeSupport = ['ADMIN', 'MANAGER'].includes(getUserRole());
  const [supportSetting, setSupportSetting] = useState(() => (canSeeSupport ? peekCrossDepartmentStatus() || 'loading' : 'not-needed'));
  useEffect(() => {
    if (!canSeeSupport) return undefined;
    let active = true;
    loadCrossDepartmentStatus({ force: true })
      .then((state) => { if (active) setSupportSetting(state); })
      // a failed read: the panel then shows in full (as it does itself), so the skeleton keeps room for it
      .catch(() => { if (active) setSupportSetting((prev) => (prev === 'loading' ? 'unknown' : prev)); });
    return () => { active = false; };
  }, [canSeeSupport]);
  const [error, setError] = useState('');
  // For a Manager: when the data on screen was last refreshed successfully, while later refreshes keep failing (null: it is current)
  const [staleSince, setStaleSince] = useState(null);
  const [selectedStaff, setSelectedStaff] = useState(null);
  const [currentPage, setCurrentPage] = useState(1);
  const [pageSize, setPageSize] = useState(ITEMS_PER_PAGE); // rows per page, chosen with the toggle
  const [departmentFilter, setDepartmentFilter] = useState(''); // '' = every department; filters the staff table only
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
      // The department cards come from the server's own answer for this account: for a Manager only the departments they manage
      const departmentsPromise = getWorkloadDepartments();
      // The staff list is for Admin and Manager only (the backend answers Staff with 403), so Staff never request it
      const staffPromise = canListStaff(getUserRole()) ? getStaff() : Promise.resolve(null);

      // Each answer is settled on its own, so a partial failure is decided part by part (see utils/workloadRefresh.js)
      // The department capacity never fails the page: if it cannot be read, the last known answer stays; with none yet, the department cards and
      // the risk levels say they are unavailable
      const capacityPromise = (['ADMIN', 'MANAGER'].includes(getUserRole()) ? getDepartmentCapacity() : Promise.reject(new Error('not for this role')))
        .then((answer) => parseDepartmentCapacityAnswer(answer))
        .catch(() => null);
      const [workloadResult, departmentsResult, staffResult, capacityResult] = await Promise.all([
        settle(workloadPromise),
        settle(departmentsPromise, 'Failed to load departments for workload distribution:'),
        settle(staffPromise, 'Failed to load staff list for workload view modal:'),
        capacityPromise,
      ]);
      if (!isMountedRef.current) return;
      // A readable answer replaces the state; an unreadable one keeps what was known, or marks the department figures unavailable (never zeros)
      setCapacityState((prev) => capacityResult || (prev.status === 'ready' ? prev : { status: 'unavailable' }));

      // What stays on screen after this refresh. For a Manager nothing from an older refresh is kept beside newer rows, a refusal (403)
      // clears the page, and data that could not be refreshed is marked stale for at most two minutes; for anyone else nothing changed.
      const role = getUserRole();
      const decision = decideWorkloadRefresh({
        role,
        isBackground,
        now: Date.now(),
        lastSuccessAt: lastFetchRef.current,
        outcome: { workload: workloadResult, departments: departmentsResult, staff: staffResult },
      });
      if (decision.action === 'redirect') {
        navigate('/login');
        return;
      }
      if (decision.action === 'replace' || decision.action === 'clear') {
        setWorkload(decision.workload);
        if (decision.departments !== null) setDepartments(decision.departments);
        if (decision.allStaff !== null) setAllStaff(decision.allStaff);
        // A Manager's open details close when that person is no longer among the refreshed rows
        setSelectedStaff((current) => selectedStaffAfterRefresh(role, current, decision.workload));
        setStaleSince(null);
        if (decision.action === 'replace') {
          lastFetchRef.current = Date.now();
          if (decision.clearError) setError('');
        }
      } else if (decision.action === 'keep-stale') {
        setStaleSince(decision.staleSince);
        console.warn('Background workload refresh failed; showing the last data, marked as stale:', workloadResult.error);
      } else if (decision.action === 'keep') {
        console.warn('Background workload refresh failed; keeping the current data:', workloadResult.error);
      }
      if (decision.error) setError(decision.error);
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

  // A Manager's page is about the departments they manage only. The server already sends only those (rows and departments); this keeps the
  // cards, totals, table and pages consistent with the department list even if the two answers were read at slightly different moments, and
  // never lets a row of any other department count.
  const isManager = getUserRole() === 'MANAGER';
  const visibleWorkload = useMemo(
    () => (isManager ? restrictWorkloadToDepartments(workload, departments) : workload),
    [isManager, workload, departments]
  );

  // Dynamic summary across active staff workload (complete dataset)
  const summary = useMemo(() => {
    return calculateWorkloadSummary(visibleWorkload);
  }, [visibleWorkload]);

  // The department cards come straight from the server's capacity answer
  const departmentCards = useMemo(() => (capacityState.status === 'ready' ? capacityState.departments : []), [capacityState]);
  const remainingTotals = useMemo(() => departmentCards.reduce(
    (sum, d) => ({ assigned: sum.assigned + d.assignedMinutes, waiting: sum.waiting + d.waitingMinutes, remaining: sum.remaining + d.remainingMinutes }),
    { assigned: 0, waiting: 0, remaining: 0 }
  ), [departmentCards]);

  // Sort complete staff workload list alphabetically (A–Z) before pagination
  const sortedWorkload = useMemo(() => {
    return sortWorkloadAlphabetically(visibleWorkload);
  }, [visibleWorkload]);

  // The staff table can be narrowed to one department (the summary and department cards above keep showing everyone)
  const filteredWorkload = useMemo(() => {
    if (!departmentFilter) return sortedWorkload;
    return sortedWorkload.filter((row) => String(row.department || '').toUpperCase() === departmentFilter.toUpperCase());
  }, [sortedWorkload, departmentFilter]);

  const totalPages = Math.max(1, Math.ceil(filteredWorkload.length / pageSize));

  // If the staff dataset changes and the current page becomes invalid, return to a valid page
  useEffect(() => {
    if (currentPage > totalPages) {
      setCurrentPage(totalPages);
    }
  }, [currentPage, totalPages]);

  // Paginated staff slice (pageSize rows per page)
  const paginatedWorkload = useMemo(() => {
    const startIndex = (currentPage - 1) * pageSize;
    return filteredWorkload.slice(startIndex, startIndex + pageSize);
  }, [filteredWorkload, currentPage, pageSize]);

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
        profileImageUrl: item.profileImageUrl || null,
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
  // The page's own header: shown while the first load runs (above a skeleton of the page) and once loaded
  const pageHeader = (
    <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-3">
      <div>
        <h1 className="text-xl sm:text-2xl font-bold text-gray-900">Staff Workload</h1>
        <p className="text-xs sm:text-sm text-gray-500 mt-0.5">
          Monitor staff workload and active task distribution.
        </p>
      </div>
    </div>
  );

  if (isLoading) {
    return (
      <div className="w-full space-y-4">
        {pageHeader}
        <WorkloadPageSkeleton label="Loading staff workload data..." showSupport={supportSetting === 'on' || supportSetting === 'unknown'} />
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

        <LoadError title="Failed to load staff workload" message={error} onRetry={() => fetchData()} retryLabel="Try Again" />
      </div>
    );
  }

  return (
    <div className="w-full space-y-4">
      {/* Page Header */}
      {pageHeader}

      {/* A Manager's data that could not be refreshed: shown, but marked as old (cleared after two minutes, see utils/workloadRefresh.js) */}
      {staleSince && (
        <LoadError
          variant="banner"
          compact
          testId="workload-stale-notice"
          message={`Couldn't refresh. Showing data from ${formatTime(new Date(staleSince), getPreferences().timeFormat)}. Trying again every 30 seconds.`}
          onRetry={() => fetchData(true)}
          retryLabel="Refresh now"
        />
      )}

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
                <p className="text-xs sm:text-sm font-medium text-gray-500 truncate">
                  {card.title}
                </p>
                <h3 className="text-xl sm:text-2xl font-bold text-gray-900 mt-0.5 truncate" title={card.subtitle}>
                  {card.value}
                </h3>
              </div>
            </div>
          ))}
        </div>
      </section>

      {/* 2. Department Risk Levels: the legend of the department cards below, built from the saved Capacity Threshold (getDepartmentLegendRanges), or an unavailable notice when the capacity cannot be loaded; display only. Staff statuses are not part of it */}
      <section className="bg-white rounded-xl border border-gray-100 shadow-2xs px-4 py-3 space-y-2.5" aria-label="Department risk levels">
        <div className="flex flex-wrap items-baseline gap-x-3 gap-y-0.5">
          <h2 className="text-sm font-semibold text-gray-900" title={describeDepartmentRisk(capacityState)}>Department Risk Levels</h2>
          {capacityState.status === 'ready' && !capacityState.enabled && (
            <span className="text-xs text-gray-500" data-testid="department-legend-alert-off">Max Task Capacity Alert is off: AT RISK is not evaluated</span>
          )}
        </div>
        {capacityState.status === 'ready' ? (
          <DepartmentLegendTiles ranges={getDepartmentLegendRanges(capacityState.threshold, capacityState.enabled)} />
        ) : capacityState.status === 'unavailable' ? (
          <p role="status" className="rounded-lg border border-dashed border-gray-300 px-3 py-2 text-sm text-gray-500" data-testid="department-legend-unavailable">
            Risk levels are unavailable: the department capacity could not be loaded. It is retried automatically.
          </p>
        ) : null}
      </section>

      {/* 3. Workload by Department: full width container with one card per department */}
      <section className="min-w-0 bg-white rounded-xl shadow-2xs border border-gray-100 px-4 pt-3 pb-4 sm:px-5 sm:pt-3.5 sm:pb-5 space-y-3" aria-label="Workload by department">
        {/* Heading on the left, total department load on the right; stacks on narrow screens */}
        <div className="flex flex-wrap items-start justify-between gap-x-6 gap-y-2">
          <div className="min-w-0">
            <h2 className="text-sm sm:text-base font-bold text-gray-900">Workload by Department</h2>
          </div>
          <div className="min-w-0 sm:ml-auto sm:text-right">
            {/* Total remaining with an info button that opens how it is made up (assigned + waiting, per department, and the utilization formula) */}
            <TotalRemainingBreakdown totals={remainingTotals} departments={departmentCards} shiftMinutes={capacityState.shiftMinutes} />
          </div>
        </div>

        {/* One card per department, from the server's capacity answer: remaining minutes, utilization (remaining minutes / staff capacity), level
            and the eligible staff. auto-fill keeps every card the same width and wraps to new rows as departments are added. */}
        {capacityState.status === 'unavailable' ? (
          <p role="status" className="rounded-lg border border-dashed border-gray-300 px-3 py-4 text-sm text-gray-500 text-center" data-testid="department-cards-unavailable">
            Department workload is unavailable: the capacity figures could not be loaded. They are retried automatically.
          </p>
        ) : (
          <div className="grid grid-cols-[repeat(auto-fill,minmax(min(100%,17rem),1fr))] gap-4">
            {departmentCards.map((dept) => {
              const label = departmentLevelLabel(dept);
              const barColor = getDepartmentLevelBarColor(dept.level);
              const width = dept.noCapacity ? 100 : Math.min(dept.utilizationPercent ?? 0, 100);
              return (
                <div
                  key={dept.code}
                  className="min-w-0 bg-white rounded-xl p-4 shadow-2xs border border-gray-100 space-y-2.5 hover:border-gray-200 transition-colors"
                  title={`${dept.remainingMinutes} min of work against ${dept.capacityMinutes} min of capacity (${dept.eligibleStaff} eligible staff x ${capacityState.shiftMinutes} min)`}
                >
                  <div className="flex justify-between items-start gap-2 text-xs sm:text-sm">
                    <span className="min-w-0 font-semibold text-gray-900 break-words leading-snug">{dept.name}</span>
                    <span className="font-bold text-gray-900 whitespace-nowrap">{dept.remainingMinutes} mins</span>
                  </div>

                  {/* The bar is the department's utilization (full when it has work and nobody to do it) */}
                  <div className="w-full h-2 bg-gray-100 rounded-full overflow-hidden">
                    <div
                      className={`tk-bar-grow h-full rounded-full transition-[width,background-color] duration-slow ease-standard ${barColor}`}
                      style={{ width: `${width}%` }}
                    />
                  </div>

                  <div className="flex flex-wrap justify-between items-center gap-x-3 gap-y-0.5 text-2xs text-gray-500">
                    <span>
                      {dept.noCapacity ? 'No capacity' : (<><AnimatedNumber value={dept.utilizationPercent} decimals={1} />%</>)}
                      {label && (
                        <span className="ml-2 text-xs font-medium tracking-wide" data-testid="department-status">{label}</span>
                      )}
                    </span>
                    <span title="Active tasks and eligible staff">
                      {dept.activeTasks} {dept.activeTasks === 1 ? 'task' : 'tasks'} • {dept.eligibleStaff} staff
                      {dept.helpersIn > 0 ? ` (+${dept.helpersIn} helping)` : ''}
                      {dept.lentOut > 0 ? ` (${dept.lentOut} lent out)` : ''}
                    </span>
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </section>

      {/* Cross-department support suggestions: administrators and managers only (the endpoint refuses staff) */}
      {['ADMIN', 'MANAGER'].includes(getUserRole()) && <CrossDepartmentSupportPanel onChanged={() => fetchData(true)} />}

      {/* 4. Staff Workload Distribution: full width, below Workload by Department */}
      <div className="min-w-0 bg-white rounded-xl shadow-2xs border border-gray-100 overflow-hidden">
        <div className="p-4 border-b border-gray-100 flex flex-col sm:flex-row sm:items-center justify-between gap-3 bg-white">
          <div>
            <h2 className="text-sm sm:text-base font-bold text-gray-900">Staff Workload Distribution</h2>
          </div>
          <div className="flex flex-wrap items-center gap-2.5 self-start sm:self-auto">
            {departments.length > 1 && (
              <div className="relative">
                <select
                  value={departmentFilter}
                  onChange={(event) => { setDepartmentFilter(event.target.value); setCurrentPage(1); }}
                  aria-label="Filter staff by department"
                  className="h-9 pl-3 pr-9 appearance-none bg-white border border-gray-200 rounded-lg text-sm text-gray-800 cursor-pointer focus:outline-none focus:ring-2 focus:ring-emerald-500/30 focus:border-emerald-500 transition-colors"
                >
                  <option value="">All Departments</option>
                  <DepartmentOptions departments={departments} />
                </select>
                <svg className="w-4 h-4 text-gray-500 absolute right-3 top-1/2 -translate-y-1/2 pointer-events-none" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                  <path d="m6 9 6 6 6-6" />
                </svg>
              </div>
            )}
            <span className="text-xs font-semibold px-2.5 py-1 rounded-full bg-gray-100 text-gray-600">
              {filteredWorkload.length} Staff
            </span>
            {filteredWorkload.length > 0 && (
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
                      <p className="font-medium text-gray-700 text-sm">
                        {departmentFilter ? 'No staff in this department' : 'No staff workload records available'}
                      </p>
                      <p className="text-xs text-gray-400">
                        {departmentFilter ? 'Choose another department, or All Departments.' : 'Workload metrics will appear as tasks are assigned to staff.'}
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
                          <UserAvatar
                            imageUrl={item.profileImageUrl || staffMatch?.profileImageUrl}
                            name={item.staffName}
                            initials={initials}
                            className="w-7 h-7 lg:w-9 lg:h-9 rounded-full shrink-0 border"
                            photoClassName="border-gray-200"
                            fallbackClassName="bg-emerald-50 text-emerald-700 font-bold text-xs border-emerald-200"
                          />
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
                        {item.supportDepartment && (
                          <span className="mt-1 block text-[11px] leading-4 text-amber-700" title="Temporary cross-department support. Their home department has not changed.">
                            Supporting {item.supportDepartmentName || item.supportDepartment}
                          </span>
                        )}
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
              <p className="font-medium text-gray-700 text-sm">
                {departmentFilter ? 'No staff in this department' : 'No staff workload records available'}
              </p>
              <p className="text-xs text-gray-400 mt-1">
                {departmentFilter ? 'Choose another department, or All Departments.' : 'Workload metrics will appear as tasks are assigned to staff.'}
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
                      <UserAvatar
                        imageUrl={item.profileImageUrl || staffMatch?.profileImageUrl}
                        name={item.staffName}
                        initials={initials}
                        className="w-8 h-8 rounded-full shrink-0 border"
                        photoClassName="border-gray-200"
                        fallbackClassName="bg-emerald-50 text-emerald-700 font-bold text-xs border-emerald-200"
                      />
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
                        className={`h-full rounded-full transition-[width,background-color] duration-slow ease-standard ${getWorkloadProgressBarColor(
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

      {/* Staff Details Modal: always rendered (it hides itself without a staff member) so it can play its exit */}
      <StaffDetailsModal
        staff={selectedStaff}
        onClose={() => setSelectedStaff(null)}
      />

    </div>
  );
}
