import React, { useEffect, useState } from 'react';
import { getDepartments } from '../../services/api';
import { profileDepartments, showsProfileDepartments } from '../../utils/managerViewAccess';

/**
 * The 'Departments' line in the signed-in Manager's own profile header: the primary department labelled Primary, then any department an
 * Administrator granted this Manager, labelled Additional. The primary department comes from the profile; the additional ones from the
 * Manager's own department list (GET /api/departments), which the server scopes to the caller and marks managed. It is read each time the
 * page opens, so a grant or a revocation shows on the next visit. No Admin-only endpoint is used, and nothing is shown for Admins or Staff.
 *
 * Props: role (normalized role of the signed-in user), profile (the shared profile, or null), profileUnavailable.
 */
export default function ProfileDepartments({ role, profile, profileUnavailable = false }) {
  const shown = showsProfileDepartments(role);
  const [state, setState] = useState({ status: 'loading', departments: [] });

  useEffect(() => {
    if (!shown) return undefined;
    let cancelled = false;
    getDepartments()
      .then((departments) => {
        if (!cancelled) setState({ status: 'ready', departments });
      })
      .catch(() => {
        if (!cancelled) setState({ status: 'error', departments: [] });
      });
    return () => {
      cancelled = true;
    };
  }, [shown]);

  if (!shown) return null;

  const { primary, additional } = profileDepartments(profile, state.departments);
  const placeholder = profile ? 'No department assigned' : profileUnavailable ? 'Unavailable' : 'Loading…';

  // One quiet line of text under the badges (no chips or cards): the primary department slightly stronger, the others plain, separated by
  // middle dots. It stays on one line at desktop widths and wraps between departments on a narrow screen.
  return (
    <p data-testid="profile-departments" className="pt-0.5 text-xs leading-4 text-gray-500 break-words">
      <span className="font-medium text-gray-400">Departments:</span>{' '}
      {primary ? (
        <span data-testid="profile-department" data-kind="primary">
          <span className="font-medium text-gray-700">{primary.name}</span>&nbsp;(Primary)
        </span>
      ) : (
        <span className="italic text-gray-400">{placeholder}</span>
      )}
      {additional.map((department) => (
        <React.Fragment key={department.code}>
          <span aria-hidden="true" className="mx-1.5 text-gray-300">·</span>
          <span data-testid="profile-department" data-kind="additional">
            {department.name}&nbsp;({department.active ? 'Additional' : 'Additional, inactive'})
          </span>
        </React.Fragment>
      ))}
      {state.status === 'error' && <span role="alert" className="ml-1.5 italic text-gray-400">· Additional departments could not be loaded</span>}
    </p>
  );
}
