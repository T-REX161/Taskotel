import React from 'react';
import TaskotelLogo from '../brand/TaskotelLogo';
import {
  SearchIcon,
  MessageIcon,
  BellIcon,
  SettingsIcon,
  MenuIcon,
} from '../icons/Icons';
import { getUsername, getUserRole } from '../../services/api';

export default function Header({
  onMenuClick,
  onSettingsClick,
  onNotificationClick,
  onMessagesClick,
  onProfileClick,
  onLogoClick,
}) {
  const username = getUsername() || 'Administrator';
  const role = getUserRole() || 'ADMIN';

  // Format display name and initials
  const displayName = username.charAt(0).toUpperCase() + username.slice(1);
  const initials = username.slice(0, 2).toUpperCase();
  const roleLabel =
    role === 'ADMIN'
      ? 'Hotel Administrator'
      : role === 'MANAGER'
      ? 'Hotel Manager'
      : 'Hotel Staff';

  return (
    <header className="h-16 px-4 sm:px-6 lg:px-8 border-b border-gray-200 bg-white flex items-center justify-between gap-4 sticky top-0 z-30 shrink-0">
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
            <span className="text-[20px] font-bold tracking-tight text-[#04302a] leading-[22px]">
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

      {/* Right: Messages, Notifications, Settings & User Avatar */}
      <div className="flex items-center gap-1 sm:gap-1.5 shrink-0">
        {/* Messages Icon */}
        <button
          type="button"
          onClick={onMessagesClick}
          className="w-8.5 h-8.5 rounded-lg text-slate-500 hover:text-slate-800 hover:bg-slate-100 flex items-center justify-center transition-colors cursor-pointer"
          aria-label="Messages"
          title="Messages"
        >
          <MessageIcon className="w-4.5 h-4.5" />
        </button>

        {/* Notifications Icon */}
        <button
          type="button"
          onClick={onNotificationClick}
          className="relative w-8.5 h-8.5 rounded-lg text-slate-500 hover:text-slate-800 hover:bg-slate-100 flex items-center justify-center transition-colors cursor-pointer"
          aria-label="Notifications"
          title="Notifications"
        >
          <BellIcon className="w-4.5 h-4.5" />
          {/* Notification Indicator Dot */}
          <span className="absolute top-1.5 right-1.5 w-2 h-2 rounded-full bg-emerald-500 ring-2 ring-white" />
        </button>

        {/* Settings Icon */}
        <button
          type="button"
          onClick={onSettingsClick}
          className="w-8.5 h-8.5 rounded-lg text-slate-500 hover:text-slate-800 hover:bg-slate-100 flex items-center justify-center transition-colors cursor-pointer"
          aria-label="Settings"
          title="Settings"
        >
          <SettingsIcon className="w-4.5 h-4.5" />
        </button>

        {/* Divider */}
        <div className="h-5 w-[1px] bg-slate-200 mx-1.5 hidden sm:block" />

        {/* Current User Avatar */}
        <div
          onClick={onProfileClick}
          className="flex items-center gap-2 pl-1 cursor-pointer hover:opacity-85 transition-opacity select-none"
          title="View Profile"
        >
          <div className="relative">
            <div className="w-8 h-8 rounded-full bg-slate-800 text-white font-medium text-xs flex items-center justify-center shadow-2xs ring-1 ring-slate-200">
              {initials}
            </div>
            {/* Online Status Dot */}
            <span className="absolute bottom-0 right-0 w-2 h-2 rounded-full bg-emerald-500 ring-1.5 ring-white" />
          </div>
          <div className="hidden md:flex flex-col text-left">
            <span className="text-xs font-semibold text-slate-800 leading-tight">
              {displayName}
            </span>
            <span className="text-[10px] font-medium text-slate-400">
              {roleLabel}
            </span>
          </div>
        </div>
      </div>
    </header>
  );
}


