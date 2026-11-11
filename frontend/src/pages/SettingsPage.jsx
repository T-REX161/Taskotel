import React from 'react';
import { Link } from 'react-router-dom';
import PersonalPreferencesCard from '../components/settings/PersonalPreferencesCard';
import NotificationPreferencesCard from '../components/settings/NotificationPreferencesCard';
import { getUserRole } from '../services/api';
import { canAccess } from '../utils/accessPolicy';

/** The general Settings page: personal and notification preferences, for every signed-in role. */
export default function SettingsPage() {
  // Presentation only: the link is shown from the same table that guards the route; the route itself refuses everyone but an Admin
  const showSystemLink = canAccess(getUserRole(), '/settings/system');
  return (
    <div className="w-full max-w-5xl space-y-6">
      {/* Header */}
      <div>
        <h1 className="text-2xl font-bold text-gray-900">Settings</h1>
        <p className="text-gray-500 mt-1">Your personal preferences and notification choices.</p>
      </div>

      <PersonalPreferencesCard />

      <NotificationPreferencesCard />

      {showSystemLink && (
        <div className="bg-white rounded-2xl border border-gray-100 shadow-sm p-6 sm:p-8 flex items-center justify-between gap-4">
          <div>
            <h2 className="text-base font-bold text-gray-900">Site-wide settings</h2>
            <p className="text-sm text-gray-500 mt-1">Hotel-wide operating rules. Administrators only.</p>
          </div>
          <Link to="/settings/system" className="px-4 py-2 rounded-lg bg-emerald-600 hover:bg-emerald-700 text-white text-sm font-medium whitespace-nowrap">Open system settings</Link>
        </div>
      )}
    </div>
  );
}

/** The site-wide System Settings. Admin only: /settings/system is an Admin-only route (utils/accessPolicy), and the link above is hidden for everyone else. */
export function SystemSettingsPage() {
  return (
    <div className="w-full max-w-5xl space-y-6">
      <div>
        <Link to="/settings" className="text-sm text-emerald-700 hover:underline">&larr; Back to Settings</Link>
        <h1 className="text-2xl font-bold text-gray-900 mt-2">System Settings</h1>
        <p className="text-gray-500 mt-1">Configure hotel operations and task auto-balancing rules.</p>
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
