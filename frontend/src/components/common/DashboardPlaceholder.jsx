import React from 'react';
import { WorkloadIcon, TasksIcon, StaffIcon } from '../icons/Icons';

export default function DashboardPlaceholder() {
  return (
    <div className="max-w-5xl mx-auto space-y-6">
      {/* Top Welcome Title Banner */}
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4 pb-6 border-b border-gray-100">
        <div>
          <div className="inline-flex items-center gap-2 px-2.5 py-1 rounded-full text-xs font-semibold bg-emerald-50 text-emerald-700 border border-emerald-200/60 mb-2">
            <span className="w-1.5 h-1.5 rounded-full bg-emerald-500 animate-pulse" />
            Hospitality Workforce Operations
          </div>
          <h1 className="text-2xl sm:text-3xl font-extrabold text-gray-900 tracking-tight">
            EquiFlow
          </h1>
          <p className="text-sm text-gray-500 mt-1">
            Balanced workloads. Smarter operations.
          </p>
        </div>

        <div className="flex items-center gap-3">
          <span className="inline-flex items-center px-3 py-1.5 rounded-xl text-xs font-medium bg-emerald-600/10 text-emerald-800 border border-emerald-600/20">
            Phase 1 • Shell Active
          </span>
        </div>
      </div>

      {/* Main Placeholder Card */}
      <div className="bg-white rounded-2xl border border-gray-200/80 p-8 sm:p-12 text-center shadow-xs">
        <div className="mx-auto w-16 h-16 rounded-2xl bg-emerald-50 border border-emerald-100 flex items-center justify-center text-emerald-600 mb-5 shadow-xs">
          <WorkloadIcon className="w-8 h-8" />
        </div>

        <span className="inline-block px-3 py-1 rounded-full text-xs font-bold uppercase tracking-wider text-emerald-800 bg-emerald-100/70 mb-3">
          Coming Soon
        </span>

        <h2 className="text-xl sm:text-2xl font-bold text-gray-900">
          Dashboard coming soon
        </h2>

        <p className="text-gray-500 max-w-md mx-auto mt-2 text-sm leading-relaxed">
          The main workload balance analytics, staff shift monitors, and automated task distribution metrics will appear here.
        </p>

        {/* Feature Preview Cards */}
        <div className="grid grid-cols-1 sm:grid-cols-3 gap-4 mt-8 max-w-2xl mx-auto text-left">
          <div className="p-4 rounded-xl border border-gray-100 bg-gray-50/50 hover:bg-emerald-50/30 transition-colors">
            <div className="w-8 h-8 rounded-lg bg-emerald-100 text-emerald-700 flex items-center justify-center mb-3">
              <StaffIcon className="w-4 h-4" />
            </div>
            <div className="text-xs font-semibold text-gray-900">Staff Capacity</div>
            <div className="text-[11px] text-gray-500 mt-1">Real-time availability and shift balancing</div>
          </div>

          <div className="p-4 rounded-xl border border-gray-100 bg-gray-50/50 hover:bg-emerald-50/30 transition-colors">
            <div className="w-8 h-8 rounded-lg bg-emerald-100 text-emerald-700 flex items-center justify-center mb-3">
              <TasksIcon className="w-4 h-4" />
            </div>
            <div className="text-xs font-semibold text-gray-900">Task Queues</div>
            <div className="text-[11px] text-gray-500 mt-1">Housekeeping, front desk & service tickets</div>
          </div>

          <div className="p-4 rounded-xl border border-gray-100 bg-gray-50/50 hover:bg-emerald-50/30 transition-colors">
            <div className="w-8 h-8 rounded-lg bg-emerald-100 text-emerald-700 flex items-center justify-center mb-3">
              <WorkloadIcon className="w-4 h-4" />
            </div>
            <div className="text-xs font-semibold text-gray-900">Fair Distribution</div>
            <div className="text-[11px] text-gray-500 mt-1">Variance tracking and automatic balancing</div>
          </div>
        </div>
      </div>
    </div>
  );
}
