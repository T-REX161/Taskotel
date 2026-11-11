/**
 * What a Staff member may do to a task assigned to them: move it one step forward. This mirrors the backend rule (PATCH /api/tasks/{id}/status);
 * the backend decides, this only decides which action to offer. Cancelling, going back and reopening are not Staff actions.
 */
const STAFF_NEXT_ACTION = Object.freeze({
  ASSIGNED: Object.freeze({ status: 'IN_PROGRESS', label: 'Start task', busyLabel: 'Starting...' }),
  IN_PROGRESS: Object.freeze({ status: 'COMPLETED', label: 'Mark as completed', busyLabel: 'Completing...' }),
});

/** The one action a Staff member has for a task in this status, or null (Pending, Completed, Cancelled and anything unknown have none). */
export function staffStatusAction(status) {
  return typeof status === 'string' && Object.prototype.hasOwnProperty.call(STAFF_NEXT_ACTION, status) ? STAFF_NEXT_ACTION[status] : null;
}

export function staffStatusSuccessMessage(title, status) {
  const name = title ? `"${title}"` : 'The task';
  return status === 'COMPLETED' ? `${name} was marked as completed.` : `${name} was started.`;
}
