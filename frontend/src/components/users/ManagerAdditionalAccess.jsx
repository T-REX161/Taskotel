import React, { useEffect, useState } from 'react';
import { getManagerViewAccess, getUserRole } from '../../services/api';
import { EyeIcon } from '../icons/Icons';
import { DetailRow } from '../staff/StaffDetailsModal';
import { additionalAccessRows, showsAdditionalAccess } from '../../utils/managerViewAccess';

/**
 * "Additional Department Access" in a Manager's details on the Users page (Admin only): the departments an Administrator lets this
 * Manager view, each labelled View only. Read from the existing Admin-only view-access endpoint every time the details open, so a grant
 * or revocation made a moment ago is shown. Read-only: it changes nothing and shows no history. Renders nothing for anyone but an Admin
 * looking at a Manager (the endpoint refuses everyone else anyway).
 *
 * Props: staff (the Manager's staff record, with userId and department).
 */
export default function ManagerAdditionalAccess({ staff }) {
  const allowed = showsAdditionalAccess(getUserRole(), staff);
  const userId = staff?.userId;
  const [state, setState] = useState({ status: 'loading', rows: [] });

  useEffect(() => {
    if (!allowed || !userId) return undefined;
    let cancelled = false; // a late answer for a previously opened person is ignored
    setState({ status: 'loading', rows: [] });
    getManagerViewAccess(userId)
      .then((access) => {
        if (!cancelled) setState({ status: 'ready', rows: additionalAccessRows(access, staff?.department) });
      })
      .catch(() => {
        if (!cancelled) setState({ status: 'error', rows: [] });
      });
    return () => {
      cancelled = true;
    };
  }, [allowed, userId, staff?.department]);

  if (!allowed) return null;

  let value;
  let list = null;
  if (state.status === 'loading') {
    value = <span role="status" className="font-normal text-gray-400">Loading…</span>;
  } else if (state.status === 'error') {
    value = <span role="alert" className="font-normal italic text-gray-400">Could not be loaded</span>;
  } else if (state.rows.length === 0) {
    value = <span data-testid="no-additional-access" className="font-normal italic text-gray-400">No additional department access</span>;
  } else {
    value = `${state.rows.length} ${state.rows.length === 1 ? 'department' : 'departments'}`;
    // A plain list under the title, aligned with the label column (the row's note area): one line per department, name on the left and a
    // quiet "Full management" label on the right, separated by hairlines. Long names wrap; the label never moves over them.
    list = (
      <ul className="mt-2.5 ml-11 divide-y divide-gray-100 border-t border-gray-100" aria-label="Additional departments this Manager manages">
        {state.rows.map((row) => (
          <li key={row.code} data-testid="additional-access-department" className="flex min-h-9 items-center justify-between gap-3 py-2">
            <span className="min-w-0 break-words text-sm font-medium text-gray-900">{row.name}</span>
            <span className="flex shrink-0 items-center gap-2">
              {!row.active && <span className="text-[11px] font-medium text-amber-700">Inactive</span>}
              <span className="text-[11px] font-medium text-emerald-700">Full management</span>
            </span>
          </li>
        ))}
      </ul>
    );
  }

  return (
    <DetailRow icon={EyeIcon} label="Additional Department Access" note={list}>
      {value}
    </DetailRow>
  );
}
