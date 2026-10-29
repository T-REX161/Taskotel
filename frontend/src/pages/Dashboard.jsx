import React, { useState, useEffect, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { getDashboardSummary, getUserRole, getUsername, getWorkload, getWorkloadRecommendations, getTasks } from '../services/api';
import { describeUser } from '../components/layout/Header';
import { buildInsights, formatDashboardDate } from '../utils/dashboardView';
import { keepIfSame, singleFlight, startPolling } from '../utils/polling';
import { normalizeRecommendations } from '../utils/supportRecommendations';
import OperationsInsights from '../components/dashboard/OperationsInsights';
import SummaryCards from '../components/dashboard/SummaryCards';
import WorkloadOverview from '../components/dashboard/WorkloadOverview';
import TaskStatusSummary from '../components/dashboard/TaskStatusSummary';
import AssignmentsOverview from '../components/dashboard/AssignmentsOverview';

// How often the dashboard re-reads its data while it is open and the tab is visible
const DASHBOARD_REFRESH_MS = 30000;

export default function Dashboard() {
  const navigate = useNavigate();
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
      if (!silent) setError('Unable to load dashboard data. Please try again.');
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
          <div className="flex flex-col items-center gap-3">
            <svg
              className="animate-spin h-8 w-8 text-emerald-600"
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
        <div className="p-6 bg-red-50 border border-red-200 rounded-2xl text-red-800 space-y-3">
          <h3 className="font-bold text-base">Failed to load dashboard</h3>
          <p className="text-sm">{error}</p>
          <button
            type="button"
            onClick={() => fetchDashboardData()}
            className="px-4 py-2 bg-red-600 hover:bg-red-700 text-white rounded-xl text-sm font-medium transition-colors cursor-pointer"
          >
            Try Again
          </button>
        </div>
      </div>
    );
  }

  // Determine if response is STAFF or ADMIN/MANAGER
  const userRole = getUserRole();
  const isStaff =
    userRole === 'STAFF' ||
    (data && data.workload !== undefined && data.totalStaff === undefined);

  const insights = buildInsights({ isStaff, own: data?.workload, staffWorkload, tasks, recommendations });
  const welcomeName = describeUser(getUsername(), userRole).displayName;

  return (
    <div className="w-full space-y-5">
      {/* Header */}
      <div className="flex flex-wrap items-start justify-between gap-x-6 gap-y-1">
        <div>
          <h1 className="text-2xl sm:text-[1.75rem] font-bold tracking-tight text-gray-900">Dashboard</h1>
          <p className="text-gray-600 mt-0.5">Welcome back, {welcomeName}!</p>
        </div>
        <p className="text-sm font-medium text-gray-700 sm:pt-1.5">{formatDashboardDate(new Date())}</p>
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

      {/* Staff also see their recent assignments (their summary carries them) */}
      {isStaff && <AssignmentsOverview data={data} isStaff={isStaff} />}
    </div>
  );
}
