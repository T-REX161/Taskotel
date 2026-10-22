import React, { useState, useEffect, useRef, useCallback } from 'react';
import { createPortal } from 'react-dom';
import { TasksIcon, DotsVerticalIcon, EyeIcon } from '../icons/Icons';
import {
  getPriorityColor,
  formatPriority,
  getStatusColor,
  formatStatus,
  formatDepartment,
  formatDate,
  formatTime,
  formatDueDate,
  formatDuration,
} from './taskFormatters';

/**
 * TasksTable — displays hotel operational tasks in a crisp SaaS table.
 *
 * Columns:
 *   1. Task (bold title, muted description underneath)
 *   2. Department (readable text label)
 *   3. Priority (colored pill badge: urgent/high=red, medium=amber, low=green)
 *   4. Status (colored pill badge)
 *   5. Assigned To (staff name or 'Unassigned')
 *   6. Duration (e.g. '30 min')
 *   7. Due Date (readable date or neutral dash)
 *   8. Actions (bordered rounded-square 3-dot menu via portal)
 */
export default function TasksTable({
  filteredTasks = [],
  totalTaskCount = 0,
  assignmentsByTaskId = new Map(),
  canManageTasks = false,
  onView,
  onEdit,
  onAssign,
  onDelete,
}) {
  const [activeMenu, setActiveMenu] = useState(null); // { task, buttonEl }
  const [menuPosition, setMenuPosition] = useState(null);
  const menuRef = useRef(null);

  const updatePosition = useCallback(() => {
    if (!activeMenu?.buttonEl) return;
    if (!document.body.contains(activeMenu.buttonEl)) {
      setActiveMenu(null);
      return;
    }
    const rect = activeMenu.buttonEl.getBoundingClientRect();
    const spaceBelow = window.innerHeight - rect.bottom;
    const spaceAbove = rect.top;
    const estimatedMenuHeight = 160;
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

    const handleScroll = () => updatePosition();
    const handleResize = () => updatePosition();
    const handleKeyDown = (e) => {
      if (e.key === 'Escape') setActiveMenu(null);
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

  const handleButtonClick = (e, task) => {
    e.stopPropagation();
    if (activeMenu?.task?.id === task.id) {
      setActiveMenu(null);
    } else {
      setActiveMenu({ task, buttonEl: e.currentTarget });
    }
  };

  return (
    <div className="relative">
      <div className="overflow-x-auto min-h-[300px]">
        <table className="w-full text-left border-collapse table-fixed min-w-[800px]">
          <thead>
            <tr className="border-b border-gray-200 bg-gray-50/50 text-xs font-semibold uppercase tracking-wider text-gray-500 whitespace-nowrap">
              <th className="w-[23%] py-3.5 px-4 text-left">Task</th>
              <th className="w-[12%] py-3.5 px-3 text-left">Department</th>
              <th className="w-[9%] py-3.5 px-3 text-left">Priority</th>
              <th className="w-[11%] py-3.5 px-3 text-left">Status</th>
              <th className="w-[13%] py-3.5 px-3 text-left">Assigned To</th>
              <th className="w-[8%] py-3.5 px-3 text-left">Duration</th>
              <th className="w-[16%] min-w-[125px] py-3.5 px-3 text-center">Due Date</th>
              <th className="w-[8%] min-w-[68px] py-3.5 px-3 sm:px-4 text-right">Actions</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100 text-sm">
            {filteredTasks.length > 0 ? (
              filteredTasks.map((task) => {
                const assignment = assignmentsByTaskId.get(task.id);
                const assignedStaffName = assignment?.staffName;
                const isTaskAssigned = Boolean(assignment);
                const isTerminalStatus = task.status === 'COMPLETED' || task.status === 'CANCELLED';
                const isMenuOpen = activeMenu?.task?.id === task.id;

                return (
                  <tr
                    key={task.id}
                    className="hover:bg-gray-50/50 transition-colors group"
                  >
                    {/* 1. Task: Bold title with description beneath */}
                    <td className="py-4 px-4 align-middle">
                      <div className="flex flex-col min-w-0 overflow-hidden">
                        <button
                          type="button"
                          onClick={() => onView && onView(task)}
                          className="font-bold text-gray-900 text-sm hover:text-emerald-700 transition-colors text-left truncate block w-full cursor-pointer"
                          title={task.title}
                        >
                          {task.title}
                        </button>
                        {task.description ? (
                          <span
                            className="text-xs text-gray-500 truncate block mt-0.5"
                            title={task.description}
                          >
                            {task.description}
                          </span>
                        ) : null}
                      </div>
                    </td>

                    {/* 2. Department */}
                    <td className="py-4 px-3 align-middle text-gray-700 whitespace-nowrap truncate text-sm">
                      {formatDepartment(task.department)}
                    </td>

                    {/* 3. Priority badge */}
                    <td className="py-4 px-3 align-middle whitespace-nowrap">
                      <span
                        className={`inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium whitespace-nowrap ${getPriorityColor(
                          task.priority
                        )}`}
                      >
                        {formatPriority(task.priority)}
                      </span>
                    </td>

                    {/* 4. Status badge */}
                    <td className="py-4 px-3 align-middle whitespace-nowrap">
                      <span
                        className={`inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium whitespace-nowrap ${getStatusColor(
                          task.status
                        )}`}
                      >
                        {formatStatus(task.status)}
                      </span>
                    </td>

                    {/* 5. Assigned To */}
                    <td className="py-4 px-3 align-middle whitespace-nowrap text-sm">
                      {assignedStaffName ? (
                        <span
                          className="font-medium text-gray-900 truncate block"
                          title={assignedStaffName}
                        >
                          {assignedStaffName}
                        </span>
                      ) : (
                        <span className="text-gray-400 italic">Unassigned</span>
                      )}
                    </td>

                    {/* 6. Duration */}
                    <td className="py-4 px-3 align-middle whitespace-nowrap text-sm font-medium text-gray-700 tabular-nums">
                      {formatDuration(task.estimatedDuration)}
                    </td>

                    {/* 7. Due Date */}
                    <td className="py-4 px-3 align-middle text-center whitespace-nowrap">
                      {(() => {
                        const dateText = formatDate(task.dueDate);
                        const timeText = formatTime(task.deadlineTime);

                        if (dateText && timeText) {
                          return (
                            <div className="flex flex-col items-center justify-center min-w-0 leading-tight text-center">
                              <span className="text-sm font-medium text-gray-900 whitespace-nowrap">{dateText}</span>
                              <span className="text-xs text-gray-500 whitespace-nowrap mt-0.5">{timeText}</span>
                            </div>
                          );
                        }
                        if (dateText) {
                          return (
                            <div className="flex flex-col items-center justify-center min-w-0 leading-tight text-center">
                              <span className="text-sm font-medium text-gray-900 whitespace-nowrap">{dateText}</span>
                            </div>
                          );
                        }
                        if (timeText) {
                          return (
                            <div className="flex flex-col items-center justify-center min-w-0 leading-tight text-center">
                              <span className="text-sm font-medium text-gray-900 whitespace-nowrap">{timeText}</span>
                            </div>
                          );
                        }
                        return (
                          <div className="flex flex-col items-center justify-center min-w-0 leading-tight text-center">
                            <span className="text-sm text-gray-400 select-none leading-tight">—</span>
                          </div>
                        );
                      })()}
                    </td>

                    {/* 8. Actions: Bordered, rounded-square 3-dot menu */}
                    <td className="py-4 px-3 sm:px-4 align-middle text-right whitespace-nowrap">
                      <button
                        type="button"
                        onClick={(e) => handleButtonClick(e, task)}
                        className={`w-8 h-8 rounded-lg border transition-colors cursor-pointer inline-flex items-center justify-center ${
                          isMenuOpen
                            ? 'border-gray-400 bg-gray-100 text-gray-800'
                            : 'border-gray-200 hover:border-gray-300 bg-white hover:bg-gray-50 text-gray-500 hover:text-gray-700'
                        } focus:outline-none`}
                        aria-label="Task actions"
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
                  colSpan={8}
                  className="py-12 px-6 text-center text-sm text-gray-500"
                >
                  <div className="flex flex-col items-center gap-2">
                    <TasksIcon className="w-8 h-8 text-gray-300" />
                    <p className="font-medium text-gray-700">No tasks found</p>
                    <p className="text-xs text-gray-400">
                      {totalTaskCount === 0
                        ? 'There are currently no tasks in the system.'
                        : 'No tasks match the selected filters.'}
                    </p>
                  </div>
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
          className="w-40 rounded-lg bg-white shadow-lg border border-gray-200 py-1 select-none animate-fadeIn"
          onClick={(e) => e.stopPropagation()}
        >
          {/* View Details */}
          <button
            type="button"
            onClick={() => {
              const task = activeMenu.task;
              setActiveMenu(null);
              onView && onView(task);
            }}
            className="w-full text-left px-3 py-2 text-xs text-gray-700 hover:bg-gray-50 hover:text-emerald-700 flex items-center gap-2 transition-colors cursor-pointer"
          >
            <EyeIcon className="w-3.5 h-3.5 text-gray-400" />
            <span>View Details</span>
          </button>

          {/* Edit Task (if canManageTasks) */}
          {canManageTasks && (
            <button
              type="button"
              onClick={() => {
                const task = activeMenu.task;
                setActiveMenu(null);
                onEdit && onEdit(task);
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
              <span>Edit Task</span>
            </button>
          )}

          {/* Assign / Reassign Task (if canManageTasks) */}
          {canManageTasks && (
            (() => {
              const task = activeMenu.task;
              const assignment = assignmentsByTaskId.get(task.id);
              const isTaskAssigned = Boolean(assignment);
              const isTerminalStatus = task.status === 'COMPLETED' || task.status === 'CANCELLED';

              if (isTerminalStatus) {
                return (
                  <button
                    type="button"
                    disabled
                    title={`Cannot ${isTaskAssigned ? 'reassign' : 'assign'} completed or cancelled task`}
                    className="w-full text-left px-3 py-2 text-xs text-gray-400 flex items-center gap-2 cursor-not-allowed opacity-60"
                  >
                    <svg
                      className="w-3.5 h-3.5 text-gray-300"
                      viewBox="0 0 24 24"
                      fill="none"
                      stroke="currentColor"
                      strokeWidth="2"
                    >
                      <path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2" />
                      <circle cx="9" cy="7" r="4" />
                      <polyline points="16 11 18 13 22 9" />
                    </svg>
                    <span>{isTaskAssigned ? 'Reassign' : 'Assign'}</span>
                  </button>
                );
              }

              return (
                <button
                  type="button"
                  onClick={() => {
                    setActiveMenu(null);
                    onAssign && onAssign(task);
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
                    <path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2" />
                    <circle cx="9" cy="7" r="4" />
                    <polyline points="16 11 18 13 22 9" />
                  </svg>
                  <span>{isTaskAssigned ? 'Reassign' : 'Assign'}</span>
                </button>
              );
            })()
          )}

          {/* Delete Task (if canManageTasks) */}
          {canManageTasks && (
            <>
              <div className="my-1 border-t border-gray-100" />
              <button
                type="button"
                onClick={() => {
                  const task = activeMenu.task;
                  setActiveMenu(null);
                  onDelete && onDelete(task);
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
                  <polyline points="3 6 5 6 21 6" />
                  <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2" />
                </svg>
                <span>Delete</span>
              </button>
            </>
          )}
        </div>,
        document.body
      )}
    </div>
  );
}
