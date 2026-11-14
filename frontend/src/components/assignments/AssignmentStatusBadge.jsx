import React from 'react';
import { assignmentStatusTone } from './assignmentTableStyles';

/**
 * AssignmentStatusBadge — a task status as a soft pill with a small coloured dot. Presentation only: the label comes from the page
 * (formatAssignmentStatus), the colours from assignmentStatusTone.
 */
export default function AssignmentStatusBadge({ status, label }) {
  const tone = assignmentStatusTone(status);
  return (
    <span className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-medium leading-none whitespace-nowrap ring-1 ring-inset ${tone.badge}`}>
      <span className={`h-1.5 w-1.5 shrink-0 rounded-full ${tone.dot}`} aria-hidden="true" />
      {label}
    </span>
  );
}
