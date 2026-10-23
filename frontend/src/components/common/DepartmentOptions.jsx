import React from 'react';

/**
 * <option> elements for a department <select>.
 *
 * - Pass activeOnly for forms that assign a department (new staff, new tasks): inactive departments are left out.
 * - Pass currentCode so a record that already belongs to a department (even an inactive one) still shows it.
 * - Names come from the departments themselves, so new departments appear without any code change.
 */
export default function DepartmentOptions({ departments, activeOnly = false, currentCode }) {
  const visible = departments.filter((department) => !activeOnly || department.active !== false || department.code === currentCode);
  const options = visible.slice();
  if (currentCode && !options.some((department) => department.code === currentCode)) {
    options.push({ code: currentCode, name: currentCode.replace(/_/g, ' '), active: false });
  }
  return options.map((department) => (
    <option key={department.code} value={department.code}>
      {department.name}
      {department.active === false ? ' (inactive)' : ''}
    </option>
  ));
}
