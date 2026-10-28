import React from 'react';
import TaskotelLogo from '../brand/TaskotelLogo';
import { SearchIcon, MenuIcon, SunIcon, MoonIcon } from '../icons/Icons';
import { useTheme } from '../../theme/ThemeContext';
import { getUsername, getUserRole } from '../../services/api';

/** Display name, initials and role label for the signed-in user (from the stored login; nothing is assumed when it is missing). */
export function describeUser(username, role) {
  const name = String(username || '').trim();
  const displayName = name ? name.charAt(0).toUpperCase() + name.slice(1) : 'Account';
  const initials = (name || 'Account').slice(0, 2).toUpperCase();
  const roleLabel = { ADMIN: 'Admin', MANAGER: 'Manager', STAFF: 'Staff' }[role] || '';
  return { displayName, initials, roleLabel };
}

export default function Header({ onMenuClick, onProfileClick, onLogoClick }) {
  const { isDark, toggleTheme } = useTheme();
  const { displayName, initials, roleLabel } = describeUser(getUsername(), getUserRole());

  return (
    <header className="h-16 px-4 sm:px-6 lg:px-8 border-b border-gray-200 bg-surface-chrome flex items-center justify-between gap-4 sticky top-0 z-30 shrink-0">
      {/* Left: Mobile menu toggle + Taskotel Logo Branding */}
      <div className="flex items-center gap-3 sm:gap-5 shrink-0">
        {/* Mobile menu trigger */}
        <button
          type="button"
          onClick={onMenuClick}
          className="lg:hidden p-1.5 -ml-1 rounded-lg text-slate-600 hover:text-emerald-700 hover:bg-slate-100 transition-colors cursor-pointer"
          aria-label="Open sidebar"
        >
          <MenuIcon className="w-5 h-5" />
        </button>

        {/* Taskotel Logo & Branding */}
        <div
          onClick={onLogoClick}
          className="flex items-center gap-3 cursor-pointer select-none group"
          title="Taskotel — Hotel Operations, Simplified"
        >
          <TaskotelLogo className="w-10 h-10 shrink-0 transition group-hover:brightness-95" />
          <div className="flex flex-col justify-center">
            <span className="text-[20px] font-bold tracking-tight text-[#04302a] leading-[22px] dark:text-neutral-100">
              Task<span className="text-[#0f9874]">otel</span>
            </span>
            <span className="hidden sm:block whitespace-nowrap text-[7.2px] font-semibold text-[#6b7f99] tracking-[0.07em] uppercase leading-none mt-1">
              Hotel Operations, Simplified
            </span>
          </div>
        </div>
      </div>

      {/* Center: Search Bar */}
      <div className="flex-1 max-w-sm mx-2 sm:mx-4">
        <div className="relative w-full">
          <div className="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none text-gray-400">
            <SearchIcon className="w-4 h-4" />
          </div>
          <input
            type="text"
            placeholder="Search..."
            className="w-full pl-9 pr-4 py-2 bg-gray-50 hover:bg-gray-100 focus:bg-white text-sm text-gray-800 placeholder-gray-400 rounded-lg border border-gray-200 focus:border-emerald-500 focus:ring-1 focus:ring-emerald-500 outline-none transition-all"
          />
        </div>
      </div>

      {/* Right: theme toggle & current user */}
      <div className="flex items-center gap-1 sm:gap-1.5 shrink-0">
        {/* Theme toggle: Sun in light mode, Moon in dark mode */}
        <button
          type="button"
          onClick={(event) => {
            // The circular theme reveal grows from the centre of this button (also for keyboard activation)
            const rect = event.currentTarget.getBoundingClientRect();
            toggleTheme({ x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 });
          }}
          className="w-9 h-9 rounded-lg text-slate-500 hover:text-slate-800 hover:bg-slate-100 flex items-center justify-center transition-colors cursor-pointer"
          aria-label={isDark ? 'Switch to light mode' : 'Switch to dark mode'}
          title={isDark ? 'Switch to light mode' : 'Switch to dark mode'}
        >
          {isDark ? <MoonIcon className="w-[18px] h-[18px]" /> : <SunIcon className="w-[18px] h-[18px]" />}
        </button>

        {/* Divider */}
        <div className="h-5 w-[1px] bg-slate-200 mx-1.5 hidden sm:block" />

        {/* Current User Avatar */}
        <div
          onClick={onProfileClick}
          className="flex items-center gap-2.5 pl-1 cursor-pointer hover:opacity-85 transition-opacity select-none"
          title="View Profile"
        >
          <div className="relative">
            <div className="w-9 h-9 rounded-full bg-emerald-100 text-emerald-800 font-semibold text-xs flex items-center justify-center ring-1 ring-emerald-200">
              {initials}
            </div>
            {/* Online Status Dot */}
            <span className="absolute bottom-0 right-0 w-2.5 h-2.5 rounded-full bg-emerald-500 ring-2 ring-white" />
          </div>
          <div className="hidden md:flex flex-col text-left">
            <span className="text-sm font-semibold text-slate-800 leading-tight">{displayName}</span>
            {roleLabel && <span className="text-xs font-medium text-slate-500 leading-tight">{roleLabel}</span>}
          </div>
        </div>
      </div>
    </header>
  );
}


