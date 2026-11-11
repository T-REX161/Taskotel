/**
 * Choosing staff for a task (Assign Task screen). Pure logic, so it can be tested without a browser.
 *
 * A NEW assignment can take several staff members: clicking a card adds that person, clicking a selected card removes only that
 * person. A REASSIGNMENT stays single-staff (one assignment row is moved to one person), so there the selection is one person at most.
 */

/**
 * The selection after clicking one staff member. Several-staff mode: adds the id when absent (the others stay), removes only that id when
 * present. Single mode (reassignment): selects that one person, or clears the selection when that person was already selected.
 * The input is never changed; an empty or missing id changes nothing.
 */
export function toggleStaffSelection(selected, staffId, { single = false } = {}) {
  const current = Array.isArray(selected) ? selected : [];
  if (!staffId) return current;
  const present = current.includes(staffId);
  if (single) return present ? [] : [staffId];
  return present ? current.filter((id) => id !== staffId) : [...current, staffId];
}

/** "No staff members selected", "1 staff member selected", "3 staff members selected". */
export function formatSelectedStaffCount(count) {
  const n = Number.isFinite(count) && count > 0 ? Math.floor(count) : 0;
  if (n === 0) return 'No staff members selected';
  return `${n} staff ${n === 1 ? 'member' : 'members'} selected`;
}

/**
 * The selected ids that may really be sent: each one at most once (first occurrence kept), and none that is not currently selectable
 * (not in the eligible list, or already holding this task). Keeps the order the user chose them in.
 */
export function submittableStaffIds(selected, eligibleStaff, assignedStaffIds = []) {
  const eligible = new Set((Array.isArray(eligibleStaff) ? eligibleStaff : []).map((s) => s && s.id));
  const assigned = new Set(assignedStaffIds || []);
  const seen = new Set();
  const result = [];
  for (const id of Array.isArray(selected) ? selected : []) {
    if (!id || seen.has(id) || !eligible.has(id) || assigned.has(id)) continue;
    seen.add(id);
    result.push(id);
  }
  return result;
}

/** The body of the new-assignment request: the task and ALL the selected staff, together. */
export function buildAssignmentRequest(taskId, staffIds) {
  return { taskId, staffIds: [...staffIds] };
}

/** The success text after a new assignment: names the number of people when there are several. */
export function assignmentSuccessMessage(taskTitle, count) {
  const title = `Task "${taskTitle}"`;
  return count > 1 ? `${title} was successfully assigned to ${count} staff members.` : `${title} was successfully assigned.`;
}
