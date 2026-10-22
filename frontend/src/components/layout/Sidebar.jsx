import React from 'react';
import EquiFlowLogo from '../brand/EquiFlowLogo';
import {
  DashboardIcon,
  StaffIcon,
  TasksIcon,
  AssignmentsIcon,
  WorkloadIcon,
  DepartmentsIcon,
  UsersIcon,
  ProfileIcon,
  SettingsIcon,
  LogoutIcon,
  CloseIcon,
} from '../icons/Icons';
import { getUserRole } from '../../services/api';

export default function Sidebar({ isOpen, onClose, activeNav = 'Dashboard', onNavClick }) {
  const userRole = getUserRole();

  const generalLinks = [
    { name: 'Dashboard', icon: DashboardIcon, path: '/dashboard' },
    { name: 'Staff', icon: StaffIcon, path: '/staff' },
    { name: 'Tasks', icon: TasksIcon, path: '/tasks' },
    { name: 'Assignments', icon: AssignmentsIcon, path: '/assignments' },
    { name: 'Workload', icon: WorkloadIcon, path: '/workload' },
  ];

  const managementLinks = [
    { name: 'Departments', icon: DepartmentsIcon, path: '/departments' },
    ...(userRole === 'ADMIN' ? [{ name: 'Users', icon: UsersIcon, path: '/users' }] : []),
  ];

  const systemLinks = [
    { name: 'Profile', icon: ProfileIcon, path: '/profile' },
    { name: 'Settings', icon: SettingsIcon, path: '/settings' },
  ];

  const renderNavGroup = (title, items) => (
    <div>
      <div className="px-3 mb-2 text-xs font-semibold uppercase tracking-wider text-gray-400">
        {title}
      </div>
      <nav className="space-y-1">
        {items.map((item) => {
          const Icon = item.icon;
          const isActive = activeNav.toLowerCase() === item.name.toLowerCase();

          return (
            <button
              key={item.name}
              type="button"
              onClick={() => {
                if (onNavClick) onNavClick(item);
                if (onClose) onClose();
              }}
              className={`w-full flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm font-medium transition-colors text-left cursor-pointer group ${
                isActive
                  ? 'bg-emerald-50 text-emerald-700 font-semibold'
                  : 'text-gray-600 hover:text-gray-900 hover:bg-gray-50'
              }`}
            >
              <Icon
                className={`w-5 h-5 shrink-0 transition-colors ${
                  isActive ? 'text-emerald-600' : 'text-gray-400 group-hover:text-gray-600'
                }`}
              />
              <span className="truncate">{item.name}</span>
            </button>
          );
        })}
      </nav>
    </div>
  );

  return (
    <>
      {/* Mobile backdrop */}
      {isOpen && (
        <div
          className="fixed inset-0 z-40 bg-gray-900/40 backdrop-blur-xs lg:hidden transition-opacity"
          onClick={onClose}
          aria-hidden="true"
        />
      )}

      {/* Sidebar container */}
      <aside
        className={`fixed lg:sticky top-0 lg:top-16 z-40 lg:z-10 h-full lg:h-[calc(100vh-4rem)] w-60 bg-white border-r border-gray-200 flex flex-col justify-between transition-transform duration-200 ease-in-out shrink-0 select-none ${
          isOpen ? 'translate-x-0' : '-translate-x-full lg:translate-x-0'
        }`}
      >
        {/* Navigation scroll area */}
        <div className="flex-1 flex flex-col min-h-0 overflow-y-auto px-3 py-4">
          {/* Mobile drawer header */}
          <div className="h-12 px-2 flex items-center justify-between border-b border-gray-100 mb-4 lg:hidden shrink-0">
            <div className="flex items-center gap-3">
              <EquiFlowLogo className="w-9 h-9 shrink-0 rounded-[20%] shadow-sm" />
              <span className="text-[17px] font-bold tracking-tight text-slate-900 leading-5">
                Equi<span className="text-emerald-700">Flow</span>
              </span>
            </div>
            <button
              type="button"
              onClick={onClose}
              className="p-1 rounded-lg text-gray-400 hover:text-gray-700 hover:bg-gray-100"
              aria-label="Close menu"
            >
              <CloseIcon className="w-4 h-4" />
            </button>
          </div>

          {/* Nav groups — balanced spacing between sections */}
          <div className="space-y-6">
            {renderNavGroup('GENERAL', generalLinks)}
            {renderNavGroup('MANAGEMENT', managementLinks)}
            {renderNavGroup('SYSTEM', systemLinks)}
          </div>
        </div>

        {/* Bottom Section: Logout */}
        <div className="p-3 border-t border-gray-100 bg-white shrink-0">
          <button
            type="button"
            onClick={() => {
              if (onNavClick) onNavClick('Logout');
            }}
            className="w-full flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm font-medium text-gray-600 hover:text-red-600 hover:bg-red-50 transition-colors cursor-pointer group"
          >
            <LogoutIcon className="w-5 h-5 text-gray-400 group-hover:text-red-500 transition-colors" />
            <span>Logout</span>
          </button>
        </div>
      </aside>
    </>
  );
}

