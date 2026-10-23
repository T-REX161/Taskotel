import React from 'react';
import DepartmentIcon from '../common/DepartmentIcon';
import { getDepartmentStyle } from '../../utils/departmentStyles';
import { departmentLabel } from '../../utils/departmentRegistry';

/**
 * Compact, colour-coded department badge. The colour and icon come from the department's stored keys
 * (see utils/departmentStyles.js). The icon is hidden on narrow tablet columns so the name stays readable.
 */
export default function DepartmentBadge({ department }) {
  const { classes } = getDepartmentStyle(department);
  const label = departmentLabel(department);

  return (
    <span
      className={`inline-flex items-center gap-1.5 h-7 px-2.5 max-w-full rounded-full text-[13px] font-semibold whitespace-nowrap ${classes}`}
      title={label}
    >
      <DepartmentIcon department={department} className="hidden lg:block w-4 h-4 shrink-0" />
      <span className="truncate">{label}</span>
    </span>
  );
}
