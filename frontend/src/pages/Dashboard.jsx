import React, { useState, useEffect, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { getDashboardSummary, getUserRole, getUsername, getWorkload, getWorkloadRecommendations, getTasks } from '../services/api';
import { describeUser } from '../components/layout/Header';
import useCurrentUserProfile from '../hooks/useCurrentUserProfile';
import { preferredName } from '../utils/displayName';
import { buildInsights, dashboardLoadErrorMessage } from '../utils/dashboardView';
import { keepIfSame, singleFlight, startPolling } from '../utils/polling';
import { normalizeRecommendations } from '../utils/supportRecommendations';
import OperationsInsights from '../components/dashboard/OperationsInsights';
import LiveClock from '../components/dashboard/LiveClock';
import SummaryCards from '../components/dashboard/SummaryCards';
import WorkloadOverview from '../components/dashboard/WorkloadOverview';
import TaskStatusSummary from '../components/dashboard/TaskStatusSummary';
import DepartmentBreakdown from '../components/dashboard/DepartmentBreakdown';
import { coveringLine } from '../utils/departmentBreakdown';
import AssignmentsOverview from '../components/dashboard/AssignmentsOverview';
import Spinner from '../components/common/Spinner';
import LoadError from '../components/common/LoadError';

// How often the dashboard re-reads its data while it is open and the tab is visible
const DASHBOARD_REFRESH_MS = 30000;

export default function Dashboard() {
  const navigate = useNavigate();
  // The shared, once-loaded profile (also read by the header and the profile page): the welcome name follows a saved display name
  const { profile } = useCurrentUserProfile();
  const [data, setData] = useState(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState('');
  // Per-person workload: only Admin and Manager load it (the backend returns just their own record to Staff)
  const [staffWorkload, setStaffWorkload] = useState({ status: 'loading', data: [] });
  // Sources for Operations Insights, all Admin / Manager only (Staff never request them): departments that may need support,
  // from the existing recommendations API, and the task list (the backend returns only a Staff member's own tasks to Staff)
  const [recommendations, setRecommendations] = useState({ status: 'loading', data: null });
  const [tasks, setTasks] = useState({ status: 'loading', data: [] });

  // `silent` refreshes (polling, tab return) never show the spinner or the error screen: a failed refresh keeps what is on screen
  const fetchDashboardData = async (silent = false) => {
    if (!silent) {
      setIsLoading(true);
      setError('');
    }
    try {
      const summary = await getDashboardSummary();
      setData((prev) => (JSON.stringify(prev) === JSON.stringify(summary) ? prev : summary));
      setError('');
    } catch (err) {
      if (err.message && err.message.includes('Redirecting to login')) {
        navigate('/login');
        return;
      }
      if (!silent) setError(dashboardLoadErrorMessage(err));
    } finally {
      if (!silent) setIsLoading(false);
    }
  };

  // The three loaders below serve the first load and every refresh. On a failure they keep data that already loaded
  // (status 'ready') and only report an error when there is nothing to show.
  const failed = (prev) => (prev.status === 'ready' ? prev : { status: 'error', data: prev.data });

  const loadStaffWorkload = async () => {
    try {
      const next = { status: 'ready', data: await getWorkload() };
      setStaffWorkload((prev) => keepIfSame(prev, next));
    } catch {
      setStaffWorkload(failed);
    }
  };

  const loadRecommendations = async () => {
    // A Manager's support recommendations are not department-scoped yet (Support isolation step): a Manager's dashboard does not request or show them
    if (getUserRole() === 'MANAGER') return;
    try {
      const next = { status: 'ready', data: normalizeRecommendations(await getWorkloadRecommendations()) };
      setRecommendations((prev) => keepIfSame(prev, next));
    } catch {
      setRecommendations(failed);
    }
  };

  const loadTasks = async () => {
    try {
      const next = { status: 'ready', data: await getTasks() };
      setTasks((prev) => keepIfSame(prev, next));
    } catch {
      setTasks(failed);
    }
  };

  // One refresh of everything the page shows, each source fetched once. Staff only ever load their own summary.
  const refreshAll = (silent) =>
    Promise.all([
      fetchDashboardData(silent),
      ...(getUserRole() !== 'STAFF' ? [loadStaffWorkload(), loadRecommendations(), loadTasks()] : []),
    ]);

  // The guard is created once, so only one refresh runs at a time across renders; it always calls the latest closure
  const latestRefresh = useRef(refreshAll);
  latestRefresh.current = refreshAll;
  const refreshRef = useRef(null);
  if (refreshRef.current === null) refreshRef.current = singleFlight((silent) => latestRefresh.current(silent));

  useEffect(() => {
    // Visiting (or revisiting) the Dashboard mounts it, which loads fresh data right away; after that the page keeps itself
    // current every 30 seconds, and on returning to the tab, and stops all of it when it unmounts.
    refreshRef.current(false);
    return startPolling({ refresh: () => refreshRef.current(true), intervalMs: DASHBOARD_REFRESH_MS });
  }, []);

  if (isLoading) {
    return (
      <div className="w-full space-y-6">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">Dashboard</h1>
          <p className="text-gray-500 mt-1">Loading dashboard data...</p>
        </div>
        <div className="flex items-center justify-center p-16 bg-white rounded-2xl border border-gray-100 shadow-sm">
          <div role="status" className="flex flex-col items-center gap-3">
            <Spinner size="lg" className="text-emerald-600" />
            <p className="text-sm font-medium text-gray-500">
              Loading metrics...
            </p>
          </div>
        </div>
      </div>
    );
  }

  if (error) {
    return (
      <div className="w-full space-y-6">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">Dashboard</h1>
          <p className="text-gray-500 mt-1">
            Monitor hotel staff availability, tasks and workload.
          </p>
        </div>
        <LoadError title="Failed to load dashboard" message={error} onRetry={() => fetchDashboardData()} retryLabel="Try Again" />
      </div>
    );
  }

  // Determine if response is STAFF or ADMIN/MANAGER
  const userRole = getUserRole();
  const isStaff =
    userRole === 'STAFF' ||
    (data && data.workload !== undefined && data.totalStaff === undefined);

  const insights = buildInsights({ isStaff, own: data?.workload, staffWorkload, tasks, recommendations, showSupport: userRole !== 'MANAGER', capacityAlerts: data?.capacityAlerts });
  const welcomeName = preferredName(profile, describeUser(getUsername(), userRole).displayName);

  return (
    <div className="w-full space-y-5">
      {/* Header */}
      <div className="flex flex-wrap items-start justify-between gap-x-6 gap-y-1">
        <div>
          <h1 className="text-2xl sm:text-[1.75rem] font-bold tracking-tight text-gray-900">Dashboard</h1>
          <p className="text-gray-600 mt-0.5">Welcome back, {welcomeName}!</p>
          {coveringLine(data) && <p data-testid="dashboard-covering" className="text-sm text-gray-500 mt-0.5">{coveringLine(data)}</p>}
        </div>
        <LiveClock />
      </div>

      {/* KPI cards */}
      <SummaryCards data={data} isStaff={isStaff} />

      {/* items-start: each panel keeps the height its content needs, so the chart card is not stretched to the workload list */}
      <div className="grid grid-cols-1 lg:grid-cols-12 gap-5 items-start">
        <div className="lg:col-span-7">
          <WorkloadOverview data={data} isStaff={isStaff} staffWorkload={isStaff ? undefined : staffWorkload} />
        </div>
        <div className="lg:col-span-5 space-y-5">
          <TaskStatusSummary data={data} isStaff={isStaff} />
          <OperationsInsights insights={insights} />
        </div>
      </div>

      {/* A Manager of several departments: each department's own figures (nothing for one department, Admin or Staff) */}
      <DepartmentBreakdown data={data} />

      {/* Staff also see their recent assignments (their summary carries them) */}
      {isStaff && <AssignmentsOverview data={data} isStaff={isStaff} />}
    </div>
  );
}
