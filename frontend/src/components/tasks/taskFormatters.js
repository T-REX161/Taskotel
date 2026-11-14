/**
 * Shared formatting and colour-mapping utilities for task-related components.
 * Pure functions — no React imports, no API calls, no side-effects.
 */

import { displayDepartmentName } from '../../utils/departmentRegistry.js';
import { formatDateParts, formatTimeParts } from '../../utils/dateTimeFormat.js';
import { getPreferences } from '../../utils/preferences.js';


export function getPriorityColor(priority) {
  switch (priority) {
    case 'URGENT':
    case 'Urgent':
      return 'bg-rose-100 text-rose-800';
    case 'HIGH':
    case 'High':
      return 'bg-rose-100 text-rose-800';
    case 'MEDIUM':
    case 'Medium':
      return 'bg-amber-100 text-amber-800';
    case 'LOW':
    case 'Low':
      return 'bg-emerald-100 text-emerald-800';
    default:
      return 'bg-gray-100 text-gray-800';
  }
}

export function formatPriority(priority) {
  switch (priority) {
    case 'LOW': return 'Low';
    case 'MEDIUM': return 'Medium';
    case 'HIGH': return 'High';
    case 'URGENT': return 'Urgent';
    default: return priority || '-';
  }
}

export function getStatusColor(status) {
  switch (status) {
    case 'PENDING':
    case 'Pending':
      return 'bg-amber-100 text-amber-800';
    case 'ASSIGNED':
    case 'Assigned':
      return 'bg-blue-100 text-blue-800';
    case 'IN_PROGRESS':
    case 'In Progress':
      return 'bg-purple-100 text-purple-800';
    case 'COMPLETED':
    case 'Completed':
      return 'bg-emerald-100 text-emerald-800';
    case 'CANCELLED':
    case 'Cancelled':
      return 'bg-gray-100 text-gray-700';
    default:
      return 'bg-gray-100 text-gray-800';
  }
}

export function formatStatus(status) {
  switch (status) {
    case 'PENDING': return 'Pending';
    case 'ASSIGNED': return 'Assigned';
    case 'IN_PROGRESS': return 'In Progress';
    case 'COMPLETED': return 'Completed';
    case 'CANCELLED': return 'Cancelled';
    default: return status || '-';
  }
}

export function formatDepartment(dept) {
  if (!dept) return '-';
  return displayDepartmentName(dept);
}


/**
 * Format a time string (e.g., "17:00", "17:00:00", "14:30") in the signed-in user's chosen time format (12-hour "5:00 PM" or 24-hour "17:00").
 * Returns null if input is empty or invalid.
 *
 * @param {string|null|undefined} timeStr
 * @returns {string|null} Formatted time string (e.g. "5:00 PM" or "17:00") or null
 */
export function formatTime(timeStr) {
  if (!timeStr) return null;
  const str = String(timeStr).trim();
  if (!str) return null;

  // Check if string is already in 12-hour format with AM/PM (e.g. "05:00 PM", "5:00 PM", "05:00:00 PM")
  const match12 = str.match(/^(\d{1,2}):(\d{2})(?::\d{2})?\s*(AM|PM)$/i);
  if (match12) {
    const hours = parseInt(match12[1], 10);
    const minutes = match12[2];
    const period = match12[3].toUpperCase();
    if (hours >= 1 && hours <= 12) {
      return formatTimeParts((hours % 12) + (period === 'PM' ? 12 : 0), parseInt(minutes, 10), getPreferences().timeFormat);
    }
  }

  // 24-hour format (e.g. "17:00", "17:00:00", "09:05")
  const parts = str.split(':');
  if (parts.length < 2) return null;
  const hours = parseInt(parts[0], 10);
  const minutes = parseInt(parts[1], 10);
  if (isNaN(hours) || isNaN(minutes) || hours < 0 || hours > 23 || minutes < 0 || minutes > 59) {
    return null;
  }
  return formatTimeParts(hours, minutes, getPreferences().timeFormat);
}

/**
 * Format a date string (e.g., "2026-10-13") in the signed-in user's chosen date format ("13/10/2026", "10/13/2026" or "2026-10-13").
 * Returns null if input is empty or invalid.
 *
 * @param {string|null|undefined} dueDateStr
 * @returns {string|null} Formatted date (e.g. "13/10/2026") or null
 */
/**
 * Task counts for the Tasks page summary cards, one per status, so the cards add up to the total:
 * total = pending + assigned + inProgress + completed + cancelled (a task with an unknown status counts only in the total).
 */
export function countTasksByStatus(tasks) {
  const counts = { total: 0, pending: 0, assigned: 0, inProgress: 0, completed: 0, cancelled: 0 };
  const keyFor = { PENDING: 'pending', ASSIGNED: 'assigned', IN_PROGRESS: 'inProgress', COMPLETED: 'completed', CANCELLED: 'cancelled' };
  for (const task of Array.isArray(tasks) ? tasks : []) {
    counts.total += 1;
    const key = keyFor[task?.status];
    if (key) counts[key] += 1;
  }
  return counts;
}

/**
 * Whether a task is past its due moment: still open (not Completed or Cancelled) and due before today, or due today with a deadline time
 * that has passed. A task without a due date is never overdue. Uses the browser's local date and time, the same ones the due date is shown in.
 */
export function isTaskOverdue(task, now = new Date()) {
  if (!task || task.status === 'COMPLETED' || task.status === 'CANCELLED') return false;
  const date = String(task.dueDate || '').split('-').map((part) => parseInt(part, 10));
  if (date.length !== 3 || date.some(Number.isNaN)) return false;
  const byDay = date[0] - now.getFullYear() || date[1] - (now.getMonth() + 1) || date[2] - now.getDate();
  if (byDay !== 0) return byDay < 0;
  const time = String(task.deadlineTime || '').split(':').map((part) => parseInt(part, 10));
  if (time.length < 2 || time.some(Number.isNaN)) return false;
  return time[0] * 60 + time[1] < now.getHours() * 60 + now.getMinutes();
}

export function formatDate(dueDateStr) {
  if (!dueDateStr) return null;
  const parts = String(dueDateStr).split('-');
  if (parts.length === 3) {
    const year = parseInt(parts[0], 10);
    const month = parseInt(parts[1], 10) - 1;
    const day = parseInt(parts[2], 10);
    if (!isNaN(year) && month >= 0 && month < 12 && !isNaN(day)) {
      return formatDateParts(year, month + 1, day, getPreferences().dateFormat);
    }
  }
  return String(dueDateStr);
}

/**
 * Format due date and optional deadline time together.
 *
 * Examples:
 *  - "2026-10-13", "17:00" -> "13/10/2026, 5:00 PM" (default formats; they follow the user's preferences)
 *  - "2026-10-14", "14:30:00" -> "14/10/2026, 2:30 PM"
 *  - "2026-10-14", null -> "14/10/2026"
 *  - null, null -> "-"
 *
 * @param {string|object|null|undefined} dueDateInput - Date string in YYYY-MM-DD format, or a task object
 * @param {string|null|undefined} [deadlineTimeInput=null] - Time string in HH:mm or HH:mm:ss format
 * @returns {string} Formatted string or "-"
 */
export function formatDueDate(dueDateInput, deadlineTimeInput = null) {
  let dueDateStr = dueDateInput;
  let deadlineTimeStr = deadlineTimeInput;

  if (dueDateInput && typeof dueDateInput === 'object') {
    dueDateStr = dueDateInput.dueDate;
    deadlineTimeStr = deadlineTimeInput || dueDateInput.deadlineTime;
  }

  const formattedDate = formatDate(dueDateStr);
  const formattedTime = deadlineTimeStr ? formatTime(deadlineTimeStr) : null;

  if (formattedDate && formattedTime) {
    return `${formattedDate}, ${formattedTime}`;
  }
  if (formattedDate) {
    return formattedDate;
  }
  if (formattedTime) {
    return formattedTime;
  }
  return '-';
}

/**
 * Format a task duration in minutes into a human-readable string.
 *
 * Rules:
 *  - Below 1 hour: "X min" (e.g. 30 -> "30 min", 45 -> "45 min")
 *  - Exact hours: "X hr" (e.g. 60 -> "1 hr", 120 -> "2 hr")
 *  - Mixed hours and minutes: "X hr Y min" (e.g. 70 -> "1 hr 10 min", 75 -> "1 hr 15 min", 150 -> "2 hr 30 min")
 *  - Zero or missing values: returns fallback ("-" by default) according to existing conventions
 *
 * @param {number|string|null|undefined} minutes - Duration in minutes
 * @param {string} [fallback='-'] - Fallback string for missing, zero, or non-positive values
 * @returns {string} Formatted duration string
 */
export function formatDuration(minutes, fallback = '-') {
  if (minutes === null || minutes === undefined || minutes === '') {
    return fallback;
  }
  const num = Number(minutes);
  if (isNaN(num) || num <= 0) {
    return fallback;
  }
  const mins = Math.round(num);
  if (mins <= 0) {
    return fallback;
  }

  const hours = Math.floor(mins / 60);
  const remainingMins = mins % 60;

  if (hours === 0) {
    return `${remainingMins} min`;
  }
  if (remainingMins === 0) {
    return `${hours} hr`;
  }
  return `${hours} hr ${remainingMins} min`;
}

