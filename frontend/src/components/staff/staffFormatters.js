/**
 * Shared formatting and colour-mapping utilities for staff-related components.
 * Pure functions — no React imports, no API calls, no side-effects.
 */

import { displayDepartmentName } from '../../utils/departmentRegistry.js';


export function formatDepartment(dept) {
  if (!dept) return 'General';
  return displayDepartmentName(dept, compactDepartmentLabel(dept));
}

/** Short labels for the built-in departments; undefined for any other department. */
function compactDepartmentLabel(dept) {
  switch (dept.toUpperCase()) {
    case 'FRONT_DESK':
      return 'Front Desk';
    case 'HOUSEKEEPING':
      return 'Housekeeping';
    case 'MAINTENANCE':
      return 'Maintenance';
    case 'ROOM_SERVICE':
      return 'Room Service';
    case 'FOOD_AND_BEVERAGE':
    case 'F_AND_B':
      return 'F&B';
    case 'CONCIERGE':
      return 'Concierge';
    case 'SECURITY':
      return 'Security';
    default:
      return undefined;
  }
}

export function isAvailable(availability) {
  return availability === 'AVAILABLE' || availability === 'Available';
}

export function isActive(active) {
  return active === true || active === 'Active';
}

export function getAvailabilityColor(availability) {
  return isAvailable(availability)
    ? 'bg-emerald-100 text-emerald-800'
    : 'bg-amber-100 text-amber-800';
}

export function getStatusColor(active) {
  return isActive(active)
    ? 'bg-emerald-100 text-emerald-800'
    : 'bg-gray-100 text-gray-700';
}

export function formatDeactivateConfirmationMessage(staff) {
  const name = staff?.fullName || staff?.name || 'this staff member';
  return `Are you sure you want to deactivate ${name}? They will no longer be eligible for new task assignments. You can reactivate them later if needed.`;
}

export function formatRemoveConfirmationMessage(staff) {
  const name = staff?.fullName || staff?.name || 'this staff member';
  return `Are you sure you want to permanently remove ${name}? This action cannot be undone and will permanently remove this staff member and their associated account from the system.`;
}

/**
 * Safely extracts the display name of a staff member.
 * Checks fullName, then name, defaulting to an empty string.
 */
export function getStaffDisplayName(staff) {
  if (!staff) return '';
  return (staff.fullName || staff.name || '').trim();
}

/**
 * Comparator for sorting staff alphabetically by their full name in ascending order (A to Z).
 * - Uses case-insensitive comparison over the complete name.
 * - Correctly orders names beginning with the same letter by comparing all characters.
 * - Handles missing or empty names safely without crashing, placing them at the end.
 */
export function compareStaffByName(staffA, staffB) {
  const nameA = getStaffDisplayName(staffA);
  const nameB = getStaffDisplayName(staffB);

  // Missing or empty names sorted safely to the end
  if (!nameA && !nameB) return 0;
  if (!nameA) return 1;
  if (!nameB) return -1;

  // Case-insensitive comparison across complete names
  const comparison = nameA.localeCompare(nameB, undefined, { sensitivity: 'base' });
  if (comparison !== 0) {
    return comparison;
  }

  // Deterministic tie-breaker for identical case-insensitive strings
  return nameA.localeCompare(nameB);
}

/**
 * Sorts an array of staff members alphabetically by their full name in ascending order (A to Z).
 * Returns a new array without mutating the original input.
 */
export function sortStaffAlphabetically(staffList) {
  if (!Array.isArray(staffList)) return [];
  return [...staffList].sort(compareStaffByName);
}

