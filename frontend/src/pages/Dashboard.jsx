import React, { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { getDashboardSummary, getUserRole, getUsername, getWorkload } from '../services/api';
import { describeUser } from '../components/layout/Header';
import { greetingFor } from '../utils/dashboardView';
import SummaryCards from '../components/dashboard/SummaryCards';
import WorkloadOverview from '../components/dashboard/WorkloadOverview';
import DepartmentOverview from '../components/dashboard/DepartmentOverview';
import TaskStatusSummary from '../components/dashboard/TaskStatusSummary';
import AssignmentsOverview from '../components/dashboard/AssignmentsOverview';

export default function Dashboard() {
  const navigate = useNavigate();
  const [data, setData] = useState(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState('');
  // Per-person workload: only Admin and Manager load it (the backend returns just their own record to Staff)
  const [staffWorkload, setStaffWorkload] = useState({ status: 'loading', data: [] });

  const fetchDashboardData = async () => {
    setIsLoading(true);
    setError('');
    try {
      const summary = await getDashboardSummary();
      setData(summary);
    } catch (err) {
      if (err.message && err.message.includes('Redirecting to login')) {
        navigate('/login');
        return;
      }
      setError('Unable to load dashboard data. Please try again.');
    } finally {
      setIsLoading(false);
    }
  };

  const loadStaffWorkload = async () => {
    try {
      setStaffWorkload({ status: 'ready', data: await getWorkload() });
    } catch {
      setStaffWorkload({ status: 'error', data: [] });
    }
  };

  useEffect(() => {
    fetchDashboardData();
    if (getUserRole() !== 'STAFF') loadStaffWorkload();
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
            onClick={fetchDashboardData}
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

  const welcomeName = describeUser(getUsername(), userRole).displayName;

  return (
    <div className="w-full space-y-6">
      {/* Header */}
      <div>
        <h1 className="text-2xl font-bold text-gray-900">Dashboard</h1>
        <p className="text-gray-500 mt-1">
          {greetingFor(new Date().getHours())}, <span className="font-semibold text-gray-700">{welcomeName}</span>.{' '}
          {isStaff
            ? 'Here is your personal workload, assignments and task status.'
            : 'Here is how your hotel staff, tasks and workload look today.'}
        </p>
      </div>

      {/* Summary Cards */}
      <SummaryCards data={data} isStaff={isStaff} />

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        {/* Left Column (Workload & Departments) */}
        <div className="lg:col-span-2 space-y-6">
          <WorkloadOverview data={data} isStaff={isStaff} staffWorkload={isStaff ? undefined : staffWorkload} />
          <DepartmentOverview />
        </div>

        {/* Right Column (Task Status & Recent Items) */}
        <div className="space-y-6">
          <TaskStatusSummary data={data} isStaff={isStaff} />
          <AssignmentsOverview data={data} isStaff={isStaff} />
        </div>
      </div>
    </div>
  );
}
