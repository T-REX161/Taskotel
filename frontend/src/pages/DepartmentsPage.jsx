import React, { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { DepartmentsIcon, StaffIcon, TasksIcon } from '../components/icons/Icons';
import { getDepartments } from '../services/api';

export default function DepartmentsPage() {
  const navigate = useNavigate();
  const [departments, setDepartments] = useState([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState('');

  const fetchDepartments = async () => {
    setIsLoading(true);
    setError('');
    try {
      const data = await getDepartments();
      setDepartments(Array.isArray(data) ? data : []);
    } catch (err) {
      if (err.message && err.message.includes('Redirecting to login')) {
        navigate('/login');
        return;
      }
      setError(err.message || 'Unable to load departments. Please try again.');
    } finally {
      setIsLoading(false);
    }
  };

  useEffect(() => {
    fetchDepartments();
  }, []);

  const getStatusBadge = (status) => {
    switch (status) {
      case 'High':
      case 'High Load':
        return 'bg-orange-100 text-orange-800';
      case 'Moderate':
        return 'bg-blue-100 text-blue-800';
      case 'Optimal':
      case 'Operational':
        return 'bg-emerald-100 text-emerald-800';
      default:
        return 'bg-gray-100 text-gray-800';
    }
  };

  if (isLoading) {
    return (
      <div className="w-full space-y-6">
        {/* Header */}
        <div className="flex justify-between items-center">
          <div>
            <h1 className="text-2xl font-bold text-gray-900">Departments</h1>
            <p className="text-gray-500 mt-1">Hotel departments, operational units, and staff capacities.</p>
          </div>
        </div>
        <div className="flex items-center justify-center p-16 bg-white rounded-2xl border border-gray-100 shadow-sm">
          <div className="flex flex-col items-center gap-3">
            <svg className="animate-spin h-8 w-8 text-emerald-600" fill="none" viewBox="0 0 24 24">
              <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
              <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z" />
            </svg>
            <p className="text-sm font-medium text-gray-500">Loading departments...</p>
          </div>
        </div>
      </div>
    );
  }

  if (error) {
    return (
      <div className="w-full space-y-6">
        {/* Header */}
        <div className="flex justify-between items-center">
          <div>
            <h1 className="text-2xl font-bold text-gray-900">Departments</h1>
            <p className="text-gray-500 mt-1">Hotel departments, operational units, and staff capacities.</p>
          </div>
        </div>
        <div className="p-6 bg-red-50 border border-red-200 rounded-2xl text-red-800 space-y-3">
          <h3 className="font-bold text-base">Failed to load departments</h3>
          <p className="text-sm">{error}</p>
          <button
            type="button"
            onClick={fetchDepartments}
            className="px-4 py-2 bg-red-600 hover:bg-red-700 text-white rounded-xl text-sm font-medium transition-colors cursor-pointer"
          >
            Retry
          </button>
        </div>
      </div>
    );
  }

  const totalDepartments = departments.length;
  const activeStaff = departments.reduce((sum, dept) => sum + (Number(dept.staffCount) || 0), 0);
  const activeTasks = departments.reduce((sum, dept) => sum + (Number(dept.activeTasks) || 0), 0);

  const summaryCards = [
    { title: 'Total Departments', value: totalDepartments, icon: <DepartmentsIcon className="w-6 h-6 text-emerald-600" />, bg: 'bg-emerald-50' },
    { title: 'Active Staff', value: activeStaff, icon: <StaffIcon className="w-6 h-6 text-blue-600" />, bg: 'bg-blue-50' },
    { title: 'Active Tasks', value: activeTasks, icon: <TasksIcon className="w-6 h-6 text-purple-600" />, bg: 'bg-purple-50' },
  ];

  return (
    <div className="w-full space-y-6">
      {/* Header */}
      <div className="flex justify-between items-center">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">Departments</h1>
          <p className="text-gray-500 mt-1">Hotel departments, operational units, and staff capacities.</p>
        </div>
      </div>

      {/* Summary Cards */}
      <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
        {summaryCards.map((card, idx) => (
          <div key={idx} className="bg-white rounded-xl p-6 shadow-sm border border-gray-100 flex items-center space-x-4">
            <div className={`p-3 rounded-lg ${card.bg}`}>
              {card.icon}
            </div>
            <div>
              <p className="text-sm font-medium text-gray-500">{card.title}</p>
              <h3 className="text-2xl font-bold text-gray-900">{card.value}</h3>
            </div>
          </div>
        ))}
      </div>

      {/* Departments Grid or Empty State */}
      {departments.length === 0 ? (
        <div className="bg-white rounded-2xl border border-gray-100 shadow-sm p-12 text-center">
          <DepartmentsIcon className="w-12 h-12 text-gray-300 mx-auto mb-3" />
          <h3 className="text-base font-semibold text-gray-900">No departments found</h3>
          <p className="text-sm text-gray-500 mt-1">There are currently no departments available.</p>
        </div>
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
          {departments.map((dept) => (
            <div key={dept.code} className="bg-white rounded-xl p-6 border border-gray-100 shadow-sm space-y-4 hover:border-emerald-200 transition-colors">
              <div className="flex items-start justify-between">
                <div className="space-y-1">
                  <h3 className="text-lg font-bold text-gray-900">{dept.name}</h3>
                  <span className="text-xs font-mono text-gray-400">{dept.code}</span>
                </div>
                <span className={`inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium ${getStatusBadge(dept.status)}`}>
                  {dept.status}
                </span>
              </div>

              <div className="grid grid-cols-2 gap-3 pt-2 border-t border-gray-100 text-sm">
                <div>
                  <p className="text-xs text-gray-400">Staff Count</p>
                  <p className="font-semibold text-gray-800 mt-0.5">{dept.staffCount} members</p>
                </div>
                <div>
                  <p className="text-xs text-gray-400">Active Tasks</p>
                  <p className="font-semibold text-gray-800 mt-0.5">{dept.activeTasks} tasks</p>
                </div>
              </div>

              <div className="pt-2 text-xs text-gray-500 flex items-center justify-between">
                <span>Supervisor: <span className="font-medium text-gray-700">{dept.supervisor || 'Not Assigned'}</span></span>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
