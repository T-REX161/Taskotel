import React from 'react';
import { getUsername, getUserRole } from '../services/api';
import { ProfileIcon } from '../components/icons/Icons';

export default function ProfilePage() {
  const username = getUsername() || 'admin';
  const role = getUserRole() || 'ADMIN';

  return (
    <div className="w-full max-w-5xl space-y-6">
      {/* Header */}
      <div>
        <h1 className="text-2xl font-bold text-gray-900">User Profile</h1>
        <p className="text-gray-500 mt-1">Manage your account credentials, personal details, and preferences.</p>
      </div>

      {/* Main Profile Card */}
      <div className="bg-white rounded-2xl border border-gray-100 shadow-sm p-6 sm:p-8 space-y-6">
        <div className="flex flex-col sm:flex-row items-start sm:items-center gap-5 pb-6 border-b border-gray-100">
          <div className="w-20 h-20 rounded-2xl bg-gradient-to-tr from-emerald-600 via-emerald-500 to-teal-400 flex items-center justify-center text-white text-2xl font-bold shadow-md shadow-emerald-700/20">
            {username.slice(0, 2).toUpperCase()}
          </div>
          <div className="space-y-1">
            <div className="flex items-center gap-3">
              <h2 className="text-xl font-bold text-gray-900">{username}</h2>
              <span className="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-semibold bg-emerald-100 text-emerald-800">
                {role}
              </span>
            </div>
            <p className="text-sm text-gray-500">EquiFlow — Hospitality Workforce Operations</p>
            <p className="text-xs text-gray-400 font-mono">ID: @{username}</p>
          </div>
        </div>

        {/* Profile Details Form / Grid */}
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-6">
          <div className="space-y-1">
            <label className="text-xs font-semibold text-gray-500 uppercase tracking-wider">Username</label>
            <input 
              type="text" 
              readOnly 
              value={username} 
              className="w-full px-4 py-2.5 bg-gray-50 border border-gray-200 rounded-xl text-sm font-medium text-gray-800 cursor-not-allowed"
            />
          </div>

          <div className="space-y-1">
            <label className="text-xs font-semibold text-gray-500 uppercase tracking-wider">System Role</label>
            <input 
              type="text" 
              readOnly 
              value={role} 
              className="w-full px-4 py-2.5 bg-gray-50 border border-gray-200 rounded-xl text-sm font-medium text-gray-800 cursor-not-allowed"
            />
          </div>

          <div className="space-y-1">
            <label className="text-xs font-semibold text-gray-500 uppercase tracking-wider">Status</label>
            <div className="flex items-center gap-2 px-4 py-2.5 bg-emerald-50/50 border border-emerald-100 rounded-xl text-sm font-medium text-emerald-800">
              <span className="w-2 h-2 rounded-full bg-emerald-500 animate-pulse" />
              <span>Active & Authenticated</span>
            </div>
          </div>

          <div className="space-y-1">
            <label className="text-xs font-semibold text-gray-500 uppercase tracking-wider">Access Scope</label>
            <input 
              type="text" 
              readOnly 
              value={role === 'ADMIN' ? 'Full Administrator Access' : role === 'MANAGER' ? 'Operations & Management Access' : 'Standard Staff Access'} 
              className="w-full px-4 py-2.5 bg-gray-50 border border-gray-200 rounded-xl text-sm font-medium text-gray-800 cursor-not-allowed"
            />
          </div>
        </div>
      </div>
    </div>
  );
}
