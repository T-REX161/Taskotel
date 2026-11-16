import { useCallback, useEffect, useMemo, useState } from 'react';
import { getDepartments } from '../services/api';
import { BUILT_IN_DEPARTMENT_NAMES } from '../utils/departmentRegistry';
import { getUserRole } from '../services/api';
import { loadCurrentProfile } from '../utils/currentUserProfile';
import { managedDepartmentList } from '../utils/managedDepartments';

// What the forms offer until the list loads, or if it cannot be loaded: the six built-in departments
const FALLBACK_DEPARTMENTS = Object.entries(BUILT_IN_DEPARTMENT_NAMES).map(([code, name]) => ({ code, name, active: true }));

/**
 * The departments from the API, for dropdowns and filters.
 *
 * - departments: every department (active and inactive), or the built-in six while loading or on failure
 * - activeDepartments: only those that can take new staff and tasks. A department from an older API that has no
 *   "active" field counts as active.
 * - reload(): fetch again, for example after a department was created
 *
 * For a Manager the list is only the departments they manage (primary first) and is never replaced by the built-in six, so a form
 * can never offer a department the server would refuse; until it loads, or if it cannot be loaded, it is empty.
 *
 * Options: { enabled } (default true). While false nothing is fetched and the built-in list is kept; the list loads as soon as it
 * becomes true. Every existing caller passes nothing and so loads at once, as before.
 */
export default function useDepartments({ enabled = true } = {}) {
  const isManager = getUserRole() === 'MANAGER';
  const [departments, setDepartments] = useState(isManager ? [] : FALLBACK_DEPARTMENTS);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState('');

  const reload = useCallback(async () => {
    try {
      const list = await getDepartments();
      if (isManager) {
        const profile = await loadCurrentProfile().catch(() => null);
        setDepartments(managedDepartmentList(list, profile?.departmentCode));
      } else if (Array.isArray(list) && list.length > 0) {
        setDepartments(list);
      }
      setError('');
    } catch (err) {
      // The forms keep working with the built-in departments; a real authentication problem is handled by apiFetch
      setError(err?.message || 'Unable to load departments.');
    } finally {
      setIsLoading(false);
    }
  }, [isManager]);

  useEffect(() => {
    if (!enabled) return;
    reload();
  }, [enabled, reload]);

  const activeDepartments = useMemo(() => departments.filter((department) => department.active !== false), [departments]);

  return { departments, activeDepartments, isLoading, error, reload };
}
