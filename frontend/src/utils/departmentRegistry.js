/*
 * What the app currently knows about departments: names, icon keys, colour keys and status.
 *
 * Departments are database records, so a department can be created at any time. Staff, tasks and workload
 * rows carry only the department code plus (from the API) its display name. This registry learns those from
 * every API response, so every place that shows a department can show a new one correctly without extra
 * requests. It is plain module state with no React and no side effects other than remembering names.
 */

// The six departments that exist in every installation, and their full names
export const BUILT_IN_DEPARTMENT_NAMES = {
  HOUSEKEEPING: 'Housekeeping',
  FRONT_DESK: 'Front Desk',
  MAINTENANCE: 'Maintenance',
  ROOM_SERVICE: 'Room Service',
  FOOD_AND_BEVERAGE: 'Food & Beverage',
  SECURITY: 'Security',
};

const registered = new Map();

const upper = (code) => String(code || '').trim().toUpperCase();

/** SPA_AND_WELLNESS becomes "Spa And Wellness": a readable name when nothing better is known. */
export function humanizeDepartmentCode(code) {
  return String(code || '')
    .trim()
    .toLowerCase()
    .split('_')
    .filter(Boolean)
    .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
    .join(' ');
}

/** Remember departments from GET /api/departments (or a created or updated department). */
export function registerDepartments(list) {
  for (const department of Array.isArray(list) ? list : []) {
    if (!department || typeof department.code !== 'string') continue;
    const key = upper(department.code);
    registered.set(key, { ...registered.get(key), ...department, code: department.code });
  }
}

/** Learn display names from any API payload whose items carry department and departmentName. */
export function registerDepartmentNames(data) {
  const items = Array.isArray(data) ? data : data && typeof data === 'object' ? [data] : [];
  for (const item of items) {
    if (item && typeof item.department === 'string' && typeof item.departmentName === 'string' && item.departmentName) {
      const key = upper(item.department);
      registered.set(key, { ...registered.get(key), code: item.department, name: item.departmentName });
    }
  }
}

export function getRegisteredDepartment(code) {
  return registered.get(upper(code)) || null;
}

/** The registered display name, or undefined. */
export function getDepartmentName(code) {
  return getRegisteredDepartment(code)?.name || undefined;
}

/** Full display name: registered name, else the built-in name, else a readable form of the code. */
export function departmentLabel(code) {
  if (!code) return 'General';
  return getDepartmentName(code) || BUILT_IN_DEPARTMENT_NAMES[upper(code)] || humanizeDepartmentCode(code);
}

/**
 * The name to show where a formatter has its own compact label for a built-in department (for example "F&B").
 * A department that was renamed, or a new one, always shows its real name.
 */
export function displayDepartmentName(code, compactLabel) {
  const name = getDepartmentName(code);
  if (!name) return compactLabel || BUILT_IN_DEPARTMENT_NAMES[upper(code)] || humanizeDepartmentCode(code);
  if (compactLabel && BUILT_IN_DEPARTMENT_NAMES[upper(code)] === name) return compactLabel;
  return name;
}

/** For tests. */
export function clearDepartmentRegistry() {
  registered.clear();
}
