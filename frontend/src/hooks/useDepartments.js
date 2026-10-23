import { useCallback, useEffect, useMemo, useState } from 'react';
import { getDepartments } from '../services/api';
import { BUILT_IN_DEPARTMENT_NAMES } from '../utils/departmentRegistry';

// What the forms offer until the list loads, or if it cannot be loaded: the six built-in departments
const FALLBACK_DEPARTMENTS = Object.entries(BUILT_IN_DEPARTMENT_NAMES).map(([code, name]) => ({ code, name, active: true }));

/**
 * The departments from the API, for dropdowns and filters.
 *
 * - departments: every department (active and inactive), or the built-in six while loading or on failure
 * - activeDepartments: only those that can take new staff and tasks. A department from an older API that has no
 *   "active" field counts as active.
 * - reload(): fetch again, for example after a department was created
 */
export default function useDepartments() {
  const [departments, setDepartments] = useState(FALLBACK_DEPARTMENTS);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState('');

  const reload = useCallback(async () => {
    try {
      const list = await getDepartments();
      if (Array.isArray(list) && list.length > 0) {
        setDepartments(list);
      }
      setError('');
    } catch (err) {
      // The forms keep working with the built-in departments; a real authentication problem is handled by apiFetch
      setError(err?.message || 'Unable to load departments.');
    } finally {
      setIsLoading(false);
    }
  }, []);

  useEffect(() => {
    reload();
  }, [reload]);

  const activeDepartments = useMemo(() => departments.filter((department) => department.active !== false), [departments]);

  return { departments, activeDepartments, isLoading, error, reload };
}
