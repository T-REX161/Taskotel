import { useCallback, useEffect, useMemo, useState } from 'react';
import { getDepartments } from '../services/api';
import { getUserRole } from '../services/api';
import { loadCurrentProfile } from '../utils/currentUserProfile';
import { managedDepartmentList } from '../utils/managedDepartments';

/**
 * The departments from the API, for dropdowns and filters.
 *
 * - departments: every department (active and inactive) exactly as the server lists them; empty while loading or if the list cannot be
 *   loaded. There is no built-in fallback: an Admin can remove any department, and a removed one must never be offered again
 * - activeDepartments: only those that can take new staff and tasks. A department from an older API that has no
 *   "active" field counts as active.
 * - reload(): fetch again, for example after a department was created
 *
 * For a Manager the list is only the departments they manage (primary first), so a form can never offer a department the server would
 * refuse.
 *
 * Options: { enabled } (default true). While false nothing is fetched and the list stays empty; the list loads as soon as it
 * becomes true. Every existing caller passes nothing and so loads at once, as before.
 */
export default function useDepartments({ enabled = true } = {}) {
  const isManager = getUserRole() === 'MANAGER';
  const [departments, setDepartments] = useState([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState('');

  const reload = useCallback(async () => {
    try {
      const list = await getDepartments();
      if (isManager) {
        const profile = await loadCurrentProfile().catch(() => null);
        setDepartments(managedDepartmentList(list, profile?.departmentCode));
      } else {
        setDepartments(Array.isArray(list) ? list : []); // an empty list is a real answer: nothing is substituted for it
      }
      setError('');
    } catch (err) {
      // The list stays as it was (empty before the first load); a real authentication problem is handled by apiFetch
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
