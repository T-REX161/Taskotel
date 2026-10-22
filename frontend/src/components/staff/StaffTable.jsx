import React, { useState, useEffect, useRef, useCallback } from 'react';
import { createPortal } from 'react-dom';
import { formatDepartment, isAvailable, isActive } from './staffFormatters';
import { DotsVerticalIcon, EyeIcon } from '../icons/Icons';
import { getUserRole } from '../../services/api';

/**
 * Get two-letter initials from full name or username.
 */
function getInitials(fullName, username) {
  const name = (fullName || username || '').trim();
  if (!name) return 'ST';
  const parts = name.split(/\s+/);
  if (parts.length >= 2) {
    return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
  }
  return name.slice(0, 2).toUpperCase();
}

/**
 * StaffTable — displays staff members in a crisp SaaS table.
 *
 * Columns:
 *   1. Name (avatar circle, bold name, email underneath)
 *   2. Department (readable text label)
 *   3. Availability (subtle pill)
 *   4. Active Tasks (numeric count, aligned)
 *   5. Workload (in 'min' units)
 *   6. Status (subtle pill)
 *   7. Actions (three-dot menu via portal)
 */
export default function StaffTable({
  staffList = [],
  onView,
  onEdit,
  onDeactivate,
  onActivate,
  onRemove,
  currentUserRole,
}) {
  const [activeMenu, setActiveMenu] = useState(null); // { staff, buttonEl }
  const [menuPosition, setMenuPosition] = useState(null);
  const menuRef = useRef(null);

  const role = currentUserRole !== undefined ? currentUserRole : getUserRole();
  const isAdmin = role === 'ADMIN' || role === 'ROLE_ADMIN';

  const updatePosition = useCallback(() => {
    if (!activeMenu?.buttonEl) return;
    if (!document.body.contains(activeMenu.buttonEl)) {
      setActiveMenu(null);
      return;
    }
    const rect = activeMenu.buttonEl.getBoundingClientRect();
    const spaceBelow = window.innerHeight - rect.bottom;
    const spaceAbove = rect.top;
    const estimatedMenuHeight = 180;
    const openUpward = spaceBelow < estimatedMenuHeight && spaceAbove > spaceBelow;

    setMenuPosition({
      openUpward,
      top: rect.bottom + 6,
      bottom: window.innerHeight - rect.top + 6,
      right: Math.max(8, window.innerWidth - rect.right),
    });
  }, [activeMenu]);

  useEffect(() => {
    if (!activeMenu) {
      setMenuPosition(null);
      return;
    }

    updatePosition();

    const handleScroll = () => {
      updatePosition();
    };

    const handleResize = () => {
      updatePosition();
    };

    const handleKeyDown = (e) => {
      if (e.key === 'Escape') {
        setActiveMenu(null);
      }
    };

    const handlePointerDown = (e) => {
      if (
        menuRef.current &&
        !menuRef.current.contains(e.target) &&
        activeMenu.buttonEl &&
        !activeMenu.buttonEl.contains(e.target)
      ) {
        setActiveMenu(null);
      }
    };

    window.addEventListener('scroll', handleScroll, true);
    window.addEventListener('resize', handleResize);
    document.addEventListener('keydown', handleKeyDown);
    document.addEventListener('pointerdown', handlePointerDown);

    return () => {
      window.removeEventListener('scroll', handleScroll, true);
      window.removeEventListener('resize', handleResize);
      document.removeEventListener('keydown', handleKeyDown);
      document.removeEventListener('pointerdown', handlePointerDown);
    };
  }, [activeMenu, updatePosition]);

  const handleButtonClick = (e, staff) => {
    e.stopPropagation();
    if (activeMenu?.staff?.id === staff.id) {
      setActiveMenu(null);
    } else {
      setActiveMenu({ staff, buttonEl: e.currentTarget });
    }
  };

  return (
    <div className="relative">
      <div className="overflow-x-auto min-h-[300px]">
        <table className="w-full text-left border-collapse table-fixed min-w-[700px] lg:min-w-0">
          <thead>
            <tr className="border-b border-gray-200 bg-gray-50/50 text-xs font-semibold uppercase tracking-wider text-gray-500 whitespace-nowrap">
              <th className="w-[28%] py-3.5 px-4 text-left">Name</th>
              <th className="w-[16%] py-3.5 px-3 text-left">Department</th>
              <th className="w-[14%] py-3.5 px-3 text-left">Availability</th>
              <th className="w-[11%] py-3.5 px-2 text-center">Active Tasks</th>
              <th className="w-[11%] py-3.5 px-3 text-left">Workload</th>
              <th className="w-[12%] py-3.5 px-3 text-left">Status</th>
              <th className="w-[8%] py-3.5 px-4 text-right">Actions</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100 text-sm">
            {staffList.length > 0 ? (
              staffList.map((staff) => {
                const available = isAvailable(staff.availability);
                const active = isActive(staff.active);
                const isMenuOpen = activeMenu?.staff?.id === staff.id;
                const displayName = staff.fullName || staff.name || 'Unnamed Staff';
                const initials = getInitials(displayName, staff.username);
                const activeTasksCount = staff.activeTaskCount ?? 0;
                const workloadMins = staff.workloadMinutes ?? 0;

                return (
                  <tr
                    key={staff.id}
                    className="hover:bg-gray-50/50 transition-colors group"
                  >
                    {/* 1. Name: Circular Avatar, Bold Name, Email underneath (truncated gracefully) */}
                    <td className="py-4 px-4 align-middle">
                      <div className="flex items-center gap-3 min-w-0">
                        {staff.avatarUrl || staff.profileImage ? (
                          <img
                            src={staff.avatarUrl || staff.profileImage}
                            alt={displayName}
                            className="w-10 h-10 rounded-full object-cover shrink-0 border border-gray-200"
                          />
                        ) : (
                          <div className="w-10 h-10 rounded-full bg-gray-100 text-gray-600 font-semibold text-xs flex items-center justify-center shrink-0 border border-gray-200 select-none">
                            {initials}
                          </div>
                        )}
                        <div className="flex flex-col min-w-0 flex-1 overflow-hidden">
                          <span
                            className="font-bold text-gray-900 text-sm truncate block"
                            title={displayName}
                          >
                            {displayName}
                          </span>
                          <span
                            className="text-xs text-gray-500 truncate block mt-0.5"
                            title={staff.email || '-'}
                          >
                            {staff.email || '-'}
                          </span>
                        </div>
                      </div>
                    </td>

                    {/* 2. Department: readable text label on one line */}
                    <td className="py-4 px-3 align-middle text-gray-700 whitespace-nowrap truncate">
                      {formatDepartment(staff.department)}
                    </td>

                    {/* 3. Availability: soft green Available, soft amber Unavailable */}
                    <td className="py-4 px-3 align-middle whitespace-nowrap">
                      {available ? (
                        <span className="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-emerald-100 text-emerald-800">
                          Available
                        </span>
                      ) : (
                        <span className="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-amber-100 text-amber-800">
                          Unavailable
                        </span>
                      )}
                    </td>

                    {/* 4. Active Tasks: numeric count, center-aligned */}
                    <td className="py-4 px-2 align-middle text-center whitespace-nowrap font-medium text-gray-700 tabular-nums">
                      {activeTasksCount}
                    </td>

                    {/* 5. Workload: easy to scan in 'min' units */}
                    <td className="py-4 px-3 align-middle whitespace-nowrap font-medium text-gray-700 tabular-nums">
                      {workloadMins} min
                    </td>

                    {/* 6. Status: soft green Active, soft gray Inactive */}
                    <td className="py-4 px-3 align-middle whitespace-nowrap">
                      {active ? (
                        <span className="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-emerald-100 text-emerald-800">
                          Active
                        </span>
                      ) : (
                        <span className="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-gray-100 text-gray-700">
                          Inactive
                        </span>
                      )}
                    </td>

                    {/* 7. Actions: Bordered, rounded square containing a vertical three-dot icon */}
                    <td className="py-4 px-4 align-middle text-right whitespace-nowrap">
                      <button
                        type="button"
                        onClick={(e) => handleButtonClick(e, staff)}
                        className={`w-8 h-8 rounded-lg border transition-colors cursor-pointer inline-flex items-center justify-center ${
                          isMenuOpen
                            ? 'border-gray-400 bg-gray-100 text-gray-800'
                            : 'border-gray-200 hover:border-gray-300 bg-white hover:bg-gray-50 text-gray-500 hover:text-gray-700'
                        } focus:outline-none`}
                        aria-label="Staff actions"
                        title="Actions"
                        aria-expanded={isMenuOpen}
                      >
                        <DotsVerticalIcon className="w-4 h-4" />
                      </button>
                    </td>
                  </tr>
                );
              })
            ) : (
              <tr>
                <td
                  colSpan="7"
                  className="py-12 px-6 text-center text-sm text-gray-500"
                >
                  No staff members found matching the selected filters.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      {/* Floating Action Menu rendered outside overflow containers via Portal */}
      {activeMenu && menuPosition && createPortal(
        <div
          ref={menuRef}
          style={{
            position: 'fixed',
            ...(menuPosition.openUpward
              ? { bottom: `${menuPosition.bottom}px` }
              : { top: `${menuPosition.top}px` }),
            right: `${menuPosition.right}px`,
            zIndex: 9999,
          }}
          className="w-38 rounded-lg bg-white shadow-lg border border-gray-200 py-1 select-none animate-fadeIn"
          onClick={(e) => e.stopPropagation()}
        >
          {/* View Details */}
          <button
            type="button"
            onClick={() => {
              const staff = activeMenu.staff;
              setActiveMenu(null);
              onView && onView(staff);
            }}
            className="w-full text-left px-3 py-2 text-xs text-gray-700 hover:bg-gray-50 hover:text-emerald-700 flex items-center gap-2 transition-colors cursor-pointer"
          >
            <EyeIcon className="w-3.5 h-3.5 text-gray-400" />
            <span>View Details</span>
          </button>

          {/* Edit Staff */}
          <button
            type="button"
            onClick={() => {
              const staff = activeMenu.staff;
              setActiveMenu(null);
              onEdit && onEdit(staff);
            }}
            className="w-full text-left px-3 py-2 text-xs text-gray-700 hover:bg-gray-50 hover:text-emerald-700 flex items-center gap-2 transition-colors cursor-pointer"
          >
            <svg
              className="w-3.5 h-3.5 text-gray-400"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
            >
              <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7" />
              <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z" />
            </svg>
            <span>Edit Staff</span>
          </button>

          <div className="my-1 border-t border-gray-100" />

          {/* Deactivate / Activate */}
          {isActive(activeMenu.staff.active) ? (
            <button
              type="button"
              onClick={() => {
                const staff = activeMenu.staff;
                setActiveMenu(null);
                onDeactivate && onDeactivate(staff);
              }}
              className="w-full text-left px-3 py-2 text-xs text-red-600 hover:bg-red-50 flex items-center gap-2 transition-colors cursor-pointer font-medium"
            >
              <svg
                className="w-3.5 h-3.5 text-red-500"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                strokeWidth="2"
              >
                <circle cx="12" cy="12" r="10" />
                <line x1="4.93" y1="4.93" x2="19.07" y2="19.07" />
              </svg>
              <span>Deactivate</span>
            </button>
          ) : (
            <button
              type="button"
              onClick={() => {
                const staff = activeMenu.staff;
                setActiveMenu(null);
                onActivate && onActivate(staff);
              }}
              className="w-full text-left px-3 py-2 text-xs text-emerald-700 hover:bg-emerald-50 flex items-center gap-2 transition-colors cursor-pointer font-medium"
            >
              <svg
                className="w-3.5 h-3.5 text-emerald-600"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                strokeWidth="2"
              >
                <polyline points="20 6 9 17 4 12" />
              </svg>
              <span>Activate</span>
            </button>
          )}

          {/* Remove Staff — Only for Admin and Inactive staff */}
          {isAdmin && !isActive(activeMenu.staff.active) && (
            <button
              type="button"
              onClick={() => {
                const staff = activeMenu.staff;
                setActiveMenu(null);
                onRemove && onRemove(staff);
              }}
              className="w-full text-left px-3 py-2 text-xs text-red-600 hover:bg-red-50 flex items-center gap-2 transition-colors cursor-pointer font-medium"
            >
              <svg
                className="w-3.5 h-3.5 text-red-500"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                strokeWidth="2"
                strokeLinecap="round"
                strokeLinejoin="round"
              >
                <path d="M3 6h18" />
                <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6" />
                <path d="M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2" />
                <line x1="10" y1="11" x2="10" y2="17" />
                <line x1="14" y1="11" x2="14" y2="17" />
              </svg>
              <span>Remove Staff</span>
            </button>
          )}
        </div>,
        document.body
      )}
    </div>
  );
}


