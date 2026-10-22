import React from 'react';

export default function SettingsPage() {
  return (
    <div className="w-full max-w-5xl space-y-6">
      {/* Header */}
      <div>
        <h1 className="text-2xl font-bold text-gray-900">System Settings</h1>
        <p className="text-gray-500 mt-1">Configure hotel operations, task auto-balancing rules, and notification preferences.</p>
      </div>

      <div className="bg-white rounded-2xl border border-gray-100 shadow-sm p-6 sm:p-8 space-y-6">
        <h2 className="text-base font-bold text-gray-900 border-b border-gray-100 pb-3">Workload Balancing Rules</h2>
        
        <div className="space-y-4 max-w-xl text-sm">
          <div className="flex items-center justify-between py-2 border-b border-gray-50">
            <div>
              <p className="font-medium text-gray-800">Auto-Balancing Trigger</p>
              <p className="text-xs text-gray-400">Automatically suggest reassignments when staff enters HIGH or AT_RISK state</p>
            </div>
            <input type="checkbox" defaultChecked className="w-4 h-4 text-emerald-600 rounded focus:ring-emerald-500" />
          </div>

          <div className="flex items-center justify-between py-2 border-b border-gray-50">
            <div>
              <p className="font-medium text-gray-800">Max Task Capacity Alert</p>
              <p className="text-xs text-gray-400">Send notifications when a department reaches 85% capacity</p>
            </div>
            <input type="checkbox" defaultChecked className="w-4 h-4 text-emerald-600 rounded focus:ring-emerald-500" />
          </div>

          <div className="flex items-center justify-between py-2">
            <div>
              <p className="font-medium text-gray-800">Cross-Department Assistance</p>
              <p className="text-xs text-gray-400">Allow qualified staff to receive tasks from adjacent departments during surge periods</p>
            </div>
            <input type="checkbox" defaultChecked className="w-4 h-4 text-emerald-600 rounded focus:ring-emerald-500" />
          </div>
        </div>
      </div>
    </div>
  );
}
