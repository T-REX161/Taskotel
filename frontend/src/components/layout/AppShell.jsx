import React, { useEffect, useRef, useState } from 'react';
import { useNavigate, useLocation, Outlet } from 'react-router-dom';
import { clearAuth } from '../../services/api';
import Sidebar from './Sidebar';
import Header from './Header';
import useCurrentUserProfile from '../../hooks/useCurrentUserProfile';
import { normalizePreferences } from '../../utils/preferences';

export default function AppShell({ children, activeNav, onNavChange }) {
  const navigate = useNavigate();
  const location = useLocation();
  const [isSidebarOpen, setIsSidebarOpen] = useState(false);
  const mainRef = useRef(null);

  // Reloading must not bring back an old offset into a page whose content loads afterwards
  useEffect(() => {
    if ('scrollRestoration' in window.history) window.history.scrollRestoration = 'manual';
  }, []);

  // A page always opens at its top: neither the window nor <main> keeps the previous page's scroll offset
  useEffect(() => {
    window.scrollTo(0, 0);
    mainRef.current?.scrollTo?.(0, 0);
  }, [location.pathname]);

  // Compute active navigation based on the actual current route
  const getActiveNavFromPath = (path) => {
    const normalized = path.toLowerCase();
    if (normalized === '/' || normalized === '/dashboard' || normalized.startsWith('/dashboard/')) {
      return 'Dashboard';
    }
    if (normalized.startsWith('/staff')) return 'Staff';
    if (normalized.startsWith('/tasks')) return 'Tasks';
    if (normalized.startsWith('/assignments')) return 'Assignments';
    if (normalized.startsWith('/workload')) return 'Workload';
    if (normalized.startsWith('/departments')) return 'Departments';
    if (normalized.startsWith('/users')) return 'Users';
    if (normalized.startsWith('/profile')) return 'Profile';
    if (normalized.startsWith('/settings')) return 'Settings';
    return activeNav || 'Dashboard';
  };

  const currentNav = getActiveNavFromPath(location.pathname);

  // Dates and times are written by plain functions that read the user's chosen formats when a page renders. The page content is
  // therefore remounted once when the formats change (or when the saved ones arrive after a refresh), so every date and time on
  // screen follows the new choice. The Settings page itself is left alone, so its saving/success message survives its own change.
  const { profile } = useCurrentUserProfile();
  const { dateFormat, timeFormat } = normalizePreferences(profile?.preferences);
  const contentKey = currentNav === 'Settings' ? 'settings' : `${dateFormat}|${timeFormat}`;

  const navPathMap = {
    dashboard: '/dashboard',
    staff: '/staff',
    tasks: '/tasks',
    assignments: '/assignments',
    workload: '/workload',
    departments: '/departments',
    users: '/users',
    profile: '/profile',
    settings: '/settings',
  };

  const handleNavClick = (navItem) => {
    const navName = typeof navItem === 'string' ? navItem : navItem?.name;
    const path = typeof navItem === 'object' && navItem?.path ? navItem.path : null;

    if (!navName) return;

    if (navName.toLowerCase() === 'logout') {
      clearAuth();
      navigate('/login');
      return;
    }

    const targetPath = path || navPathMap[navName.toLowerCase()] || `/${navName.toLowerCase()}`;
    navigate(targetPath);

    if (onNavChange) {
      onNavChange(navName);
    }
  };

  return (
    <div className="min-h-screen flex flex-col bg-surface-page text-slate-900">
      {/* White Top Navigation Bar */}
      <Header
        onMenuClick={() => setIsSidebarOpen(true)}
        onProfileClick={() => handleNavClick('Profile')}
        onLogoClick={() => handleNavClick('Dashboard')}
      />

      <div className="flex-1 flex flex-row min-h-0 relative">
        {/* Left Sidebar */}
        <Sidebar
          isOpen={isSidebarOpen}
          onClose={() => setIsSidebarOpen(false)}
          activeNav={currentNav}
          onNavClick={handleNavClick}
        />

        {/* Main Content Area */}
        <main ref={mainRef} data-page={currentNav} className="flex-1 min-w-0 p-4 sm:p-6 overflow-y-auto bg-surface-page">
          <div key={contentKey} className="max-w-[1440px] mx-auto w-full">
            {children || <Outlet />}
          </div>
        </main>
      </div>
    </div>
  );
}
