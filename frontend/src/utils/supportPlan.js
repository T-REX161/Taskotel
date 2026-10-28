// Cross-Department Support: the interaction rules of the plan builder, as pure functions and a reducer, so they can be
// tested without a browser. The backend remains the authority: every rule here is also enforced on preview and confirm.
//
// A plan is a list of { staffId, taskId } pairs for one receiving department. Its life:
//   recommended (from the API)  ->  selected, not confirmed (here)  ->  active support (after confirm)  ->  completed.

export const MAX_PLAN_SIZE = 20;

export const initialSupportState = {
  department: null, // code of the receiving department being planned
  plan: [], // [{ staffId, taskId }]
  notice: '', // a short, non-blocking message about the last interaction
  preview: { status: 'idle', data: null, error: '' }, // idle | loading | ready | error
  confirm: { status: 'idle', error: '', problems: [], stale: false }, // idle | reviewing | submitting | error
  result: null, // { created: [...] } after a successful confirmation
  discardPending: false, // true while asking whether to throw away an unconfirmed draft in order to clear the selection
  reservePrompt: null, // { kind: 'add', donorCode, staffId } or { kind: 'plan' } while asking the manager to acknowledge a staffing risk
};

/**
 * Clearing the selection throws away only what lives in this reducer: the chosen department and the unconfirmed draft.
 * The outcome banner of an earlier confirmation is kept. Confirmed support, assignments and tasks are server data and are
 * never touched from here.
 */
const clearedSelection = (state) => ({ ...initialSupportState, result: state.result });

/** The first waiting task (most important first) that no pair in the plan uses yet, or null. */
export function nextFreeTaskId(tasks, plan) {
  const used = new Set(plan.map((pair) => pair.taskId));
  const free = (Array.isArray(tasks) ? tasks : []).find((task) => !used.has(task.id));
  return free ? free.id : null;
}

/** How many more people each donor department may lend, given what is already in the plan. */
export function donorCapacityLeft(options, plan) {
  const left = {};
  for (const donor of options?.donors || []) {
    const inPlan = plan.filter((pair) => donor.staff.some((s) => s.staffId === pair.staffId)).length;
    left[donor.code] = Math.max(0, donor.maxLendable - inPlan);
  }
  return left;
}

/**
 * How many more people each donor department could lend if the manager acknowledges the staffing risk (its one-person
 * reserve may go; its own waiting work stays covered). Never less than the normal capacity. Servers that do not send
 * `overrideLendable` offer no override.
 */
export function donorOverrideLeft(options, plan) {
  const left = {};
  for (const donor of options?.donors || []) {
    const inPlan = plan.filter((pair) => donor.staff.some((s) => s.staffId === pair.staffId)).length;
    left[donor.code] = Math.max(0, Math.max(donor.maxLendable, donor.overrideLendable ?? donor.maxLendable) - inPlan);
  }
  return left;
}

/** Whether this person is still offered by the freshly loaded options (not busy, not unavailable, still eligible). */
export const isOffered = (options, staffId) => (options?.donors || []).some((donor) => donor.staff.some((s) => s.staffId === staffId));

/** Why this person cannot be added to the plan right now, or '' if they can. */
export function whyCannotAdd(options, plan, donorCode, staffId) {
  if (plan.some((pair) => pair.staffId === staffId)) return '';
  // A suggestion on screen can be older than the options: never add someone who is no longer offered
  if (!isOffered(options, staffId)) return 'That person is no longer available to lend.';
  if (plan.length >= MAX_PLAN_SIZE) return `A plan can have at most ${MAX_PLAN_SIZE} pairs.`;
  if (nextFreeTaskId(options?.tasks, plan) === null) return 'Every waiting task already has a helper in this plan.';
  if (plan.length >= (options?.maxHelpers ?? 0)) return 'The department cannot use more helpers than it has uncovered tasks.';
  // The normal reserve is full: only a manager's acknowledgement (see needsReserveOverride) can still allow this person
  if ((donorCapacityLeft(options, plan)[donorCode] ?? 0) <= 0 && (donorOverrideLeft(options, plan)[donorCode] ?? 0) <= 0) {
    return 'That department must keep a free person in reserve.';
  }
  return '';
}

/**
 * True when adding this person would use up their department's reserve, so the manager must first acknowledge the risk.
 * Every other rule must already allow them (whyCannotAdd is empty); this only says which kind of "yes" it is.
 */
export function needsReserveOverride(options, plan, donorCode, staffId) {
  if (plan.some((pair) => pair.staffId === staffId)) return false;
  if (whyCannotAdd(options, plan, donorCode, staffId)) return false;
  return (donorCapacityLeft(options, plan)[donorCode] ?? 0) <= 0 && (donorOverrideLeft(options, plan)[donorCode] ?? 0) > 0;
}

/**
 * What the acknowledgement dialog must say: for each affected donor department, how many free staff it has now and how many
 * would remain. `prompt` is { kind: 'add', donorCode, staffId } (a person about to be added) or { kind: 'plan' } (the plan
 * in the preview that the server says needs the acknowledgement).
 */
export function reserveRiskRows(prompt, options, plan, previewData) {
  if (!prompt) return [];
  if (prompt.kind === 'add') {
    const donor = (options?.donors || []).find((d) => d.code === prompt.donorCode);
    if (!donor) return [];
    const person = donor.staff.find((s) => s.staffId === prompt.staffId);
    const alreadyLent = plan.filter((pair) => donor.staff.some((s) => s.staffId === pair.staffId)).length;
    return [{ donorName: donor.name, personName: person?.name || 'this person', freeNow: donor.freeStaff - alreadyLent, freeAfter: donor.freeStaff - alreadyLent - 1 }];
  }
  return (previewData?.donors || [])
    .filter((d) => d.after.freeStaff < d.reserveRequired)
    .map((d) => ({ donorName: d.name, personName: '', freeNow: d.before.freeStaff, freeAfter: d.after.freeStaff }));
}

/** The body for POST /api/support/preview and /api/support/assignments. */
export function buildPlanRequest(state, fingerprint) {
  const request = {
    receivingDepartment: state.department,
    items: state.plan.map((pair) => ({ staffId: pair.staffId, taskId: pair.taskId })),
  };
  if (fingerprint) request.fingerprint = fingerprint;
  // Sent only after the manager acknowledged the staffing risk; otherwise the request is exactly as it always was
  if (hasReserveAcknowledgement(state.plan)) request.acknowledgeReserveOverride = true;
  return request;
}

/** True when the manager has acknowledged the staffing risk for at least one person in this draft. */
export const hasReserveAcknowledgement = (plan) => plan.some((pair) => pair.reserveAcknowledged === true);

/** Stable text for a plan, to tell whether a response still belongs to the plan on screen. */
export function planSignature(department, plan) {
  return `${department}|${plan.map((pair) => `${pair.staffId}:${pair.taskId}`).join(',')}${hasReserveAcknowledgement(plan) ? '|ack' : ''}`;
}

/** The plan can be reviewed only when it has pairs and the server's preview of exactly this plan says it is valid. */
export function canReview(state) {
  return state.plan.length > 0 && state.preview.status === 'ready' && state.preview.data?.valid === true && state.confirm.status === 'idle';
}

const changed = (state, plan, extra = {}) => ({
  ...state,
  plan,
  // Any change to the plan makes the old preview, review and error obsolete
  preview: { status: plan.length ? 'loading' : 'idle', data: null, error: '' },
  confirm: { status: 'idle', error: '', problems: [], stale: false },
  result: null,
  notice: '',
  ...extra,
});

export function supportReducer(state, action) {
  switch (action.type) {
    case 'SELECT_DEPARTMENT':
      return state.department === action.code ? state : { ...initialSupportState, department: action.code };
    case 'REQUEST_DESELECT':
      if (!state.department) return state;
      // While a plan is being reviewed or submitted the dialog owns the screen; nothing may be cleared underneath it
      if (state.confirm.status === 'reviewing' || state.confirm.status === 'submitting') return state;
      // An empty plan has nothing to lose; a draft needs an explicit decision first
      return state.plan.length === 0 ? clearedSelection(state) : { ...state, discardPending: true };
    case 'CANCEL_DISCARD':
      return state.discardPending ? { ...state, discardPending: false } : state;
    case 'CONFIRM_DISCARD':
      return state.discardPending ? clearedSelection(state) : state;
    case 'ADD_STAFF': {
      if (state.plan.some((pair) => pair.staffId === action.staffId)) return state;
      const blocked = whyCannotAdd(action.options, state.plan, action.donorCode, action.staffId);
      if (blocked) return { ...state, notice: blocked };
      // Using up a department's reserve is never silent: without the acknowledgement it is not added
      const needsAck = needsReserveOverride(action.options, state.plan, action.donorCode, action.staffId);
      if (needsAck && !action.acknowledged) return { ...state, notice: 'Acknowledge the staffing risk to add this person.' };
      const pair = { staffId: action.staffId, taskId: nextFreeTaskId(action.options.tasks, state.plan) };
      return changed(state, [...state.plan, needsAck ? { ...pair, reserveAcknowledged: true } : pair]);
    }
    case 'OPEN_RESERVE_PROMPT': {
      // Not while a plan is being reviewed or submitted
      if (state.confirm.status === 'reviewing' || state.confirm.status === 'submitting') return state;
      const prompt = action.prompt;
      if (prompt?.kind === 'add' && !needsReserveOverride(action.options, state.plan, prompt.donorCode, prompt.staffId)) return state;
      if (prompt?.kind === 'plan' && state.preview.data?.canOverrideReserve !== true) return state;
      return prompt ? { ...state, reservePrompt: prompt } : state;
    }
    case 'CANCEL_RESERVE_PROMPT':
      return state.reservePrompt ? { ...state, reservePrompt: null } : state;
    case 'CONFIRM_RESERVE_PROMPT': {
      const prompt = state.reservePrompt;
      if (!prompt) return state;
      const closed = { ...state, reservePrompt: null };
      if (prompt.kind === 'add') {
        return supportReducer(closed, { type: 'ADD_STAFF', options: action.options, donorCode: prompt.donorCode, staffId: prompt.staffId, acknowledged: true });
      }
      // Plan-level: the server said only the reserve blocks the plan. Mark the draft as acknowledged and preview it again;
      // that is all. It does not confirm anything.
      return changed(closed, closed.plan.map((pair) => ({ ...pair, reserveAcknowledged: true })));
    }
    case 'REMOVE_STAFF':
      return changed(state, state.plan.filter((pair) => pair.staffId !== action.staffId));
    case 'SET_TASK': {
      const usedElsewhere = state.plan.some((pair) => pair.taskId === action.taskId && pair.staffId !== action.staffId);
      if (usedElsewhere) return { ...state, notice: 'That task already has a helper in this plan.' };
      return changed(state, state.plan.map((pair) => (pair.staffId === action.staffId ? { ...pair, taskId: action.taskId } : pair)));
    }
    case 'USE_SUGGESTIONS': {
      // Adds the recommended people that fit, never exceeding what the backend would accept
      let plan = [...state.plan];
      for (const suggestion of action.suggestions) {
        if (plan.some((pair) => pair.staffId === suggestion.staffId)) continue;
        if (whyCannotAdd(action.options, plan, suggestion.homeDepartmentCode, suggestion.staffId)) continue;
        // A suggestion that would use up a reserve is never added in bulk: it needs its own acknowledgement
        if (needsReserveOverride(action.options, plan, suggestion.homeDepartmentCode, suggestion.staffId)) continue;
        plan = [...plan, { staffId: suggestion.staffId, taskId: nextFreeTaskId(action.options.tasks, plan) }];
      }
      return plan.length === state.plan.length ? { ...state, notice: 'Nothing more can be added from the suggestions.' } : changed(state, plan);
    }
    case 'CLEAR_PLAN':
      return changed(state, []);
    case 'PREVIEW_READY':
      // Ignore a response for a plan that is no longer on screen
      if (action.signature !== planSignature(state.department, state.plan)) return state;
      return { ...state, preview: { status: 'ready', data: action.preview, error: '' } };
    case 'PREVIEW_FAILED':
      if (action.signature !== planSignature(state.department, state.plan)) return state;
      return { ...state, preview: { status: 'error', data: null, error: action.message } };
    case 'OPEN_REVIEW':
      return canReview(state) ? { ...state, confirm: { status: 'reviewing', error: '', problems: [], stale: false } } : state;
    case 'CANCEL_REVIEW':
      return state.confirm.status === 'reviewing' ? { ...state, confirm: { status: 'idle', error: '', problems: [], stale: false } } : state;
    case 'CONFIRM_START':
      return state.confirm.status === 'reviewing' ? { ...state, confirm: { ...state.confirm, status: 'submitting' } } : state;
    case 'CONFIRM_SUCCEEDED':
      return { ...state, plan: [], preview: { status: 'idle', data: null, error: '' }, confirm: { status: 'idle', error: '', problems: [], stale: false }, result: { created: action.created }, notice: '' };
    case 'CONFIRM_FAILED':
      // The dialog closes; the plan stays so the manager can adjust it, and the preview is recalculated
      return {
        ...state,
        preview: { status: state.plan.length ? 'loading' : 'idle', data: null, error: '' },
        confirm: { status: 'error', error: action.message, problems: action.problems || [], stale: Boolean(action.stale) },
      };
    case 'DISMISS_RESULT':
      return { ...state, result: null, confirm: state.confirm.status === 'error' ? { status: 'idle', error: '', problems: [], stale: false } : state.confirm };
    default:
      return state;
  }
}

/** The API's problem list as short, readable lines. */
export function describeProblems(problems) {
  return (Array.isArray(problems) ? problems : []).map((problem) => problem.message).filter(Boolean);
}

export const SUPPORT_STAGES = [
  { key: 'recommended', label: 'Suggested', hint: 'Recommended by Taskotel. Nothing is selected or assigned yet.' },
  { key: 'selected', label: 'Selected', hint: 'In your plan but not confirmed. No assignment exists.' },
  { key: 'active', label: 'Active', hint: 'Confirmed. The person is helping until the task is completed.' },
  { key: 'completed', label: 'Completed', hint: 'The supported task is done.' },
  { key: 'ended', label: 'Ended', hint: 'Support was ended by a manager before the task was done.' },
];

// One accent colour family (emerald) for the states that are good or in progress, neutral for what is over
export const STAGE_STYLES = {
  recommended: 'border-transparent bg-emerald-50 text-emerald-700',
  selected: 'border-emerald-600 bg-emerald-600 text-white',
  active: 'border-emerald-200 bg-emerald-50 text-emerald-700',
  completed: 'border-gray-200 bg-gray-100 text-gray-600',
  ended: 'border-gray-300 bg-transparent text-gray-600',
  cancelled: 'border-gray-200 bg-gray-100 text-gray-500',
};

// ── Removing an ended record from the history ────────────────────────────────────────────────────────────────
// The server archives the record (kept, no longer listed). idle -> reviewing -> submitting -> done | error, with one
// request at a time. Nothing is hidden in the browser: the list is reloaded from the server afterwards.

export const initialArchiveState = { status: 'idle', row: null, error: '', code: '', stale: false, result: null };

/**
 * The remove action is offered for ended records only (active support must be ended first), and only when the backend
 * reports archiving: an archive-capable backend sends `archivedAt` (null until removed) on every record, an older one does
 * not, so the button is not shown where it could not work.
 */
export const canArchiveSupport = (row) => Boolean(row) && row.status !== 'ACTIVE' && 'archivedAt' in row && !row.archivedAt;

const STALE_ARCHIVE_CODES = ['ALREADY_ARCHIVED', 'SUPPORT_ACTIVE', 'SUPPORT_NOT_FOUND'];
export const isStaleArchive = (code) => STALE_ARCHIVE_CODES.includes(code);

/** A 404 without a problem code means the server does not know this route at all: it is running an older version. */
export function describeArchiveFailure(message, code, httpStatus) {
  if (httpStatus === 404 && !code) {
    return 'The server does not recognise this action (404 on POST /api/support/assignments/{id}/archive). The backend is probably running an older version and needs to be restarted with the latest one.';
  }
  return message || 'The record could not be removed.';
}

export const describeArchiveOutcome = (row) => `Removed from the history: ${row.staffName} · ${row.taskTitle}. The record is archived, not deleted.`;

export function archiveReducer(state, action) {
  switch (action.type) {
    case 'ARCHIVE_OPEN':
      return state.status === 'idle' && canArchiveSupport(action.row) ? { ...initialArchiveState, status: 'reviewing', row: action.row } : state;
    case 'ARCHIVE_CANCEL':
      // Never while the request is running
      return state.status === 'reviewing' || state.status === 'error' ? initialArchiveState : state;
    case 'ARCHIVE_START':
      return state.status === 'reviewing' || state.status === 'error' ? { ...state, status: 'submitting', error: '', code: '', stale: false } : state;
    case 'ARCHIVE_SUCCEEDED':
      return state.status === 'submitting' ? { ...initialArchiveState, status: 'done', row: state.row, result: action.row } : state;
    case 'ARCHIVE_FAILED':
      return state.status === 'submitting'
        ? { ...state, status: 'error', error: describeArchiveFailure(action.message, action.code, action.httpStatus), code: action.code || '', stale: isStaleArchive(action.code) }
        : state;
    case 'ARCHIVE_DISMISS':
      return state.status === 'done' ? initialArchiveState : state;
    default:
      return state;
  }
}

/**
 * What to show for confirmed support. It depends only on the support records the server returned (`history`), never on the
 * draft plan: choosing a department, adding staff or previewing a plan creates no record.
 *   'section'  the main Confirmed support section (Active and Completed & ended tabs): at least one ACTIVE record exists,
 *              or the load failed (so a failure is never mistaken for "no support"; the section carries the error)
 *   'history'  nobody is on active support but completed or ended records exist: only a small collapsed "Support history"
 *              area, so the past stays reachable without keeping the whole section on screen
 *   'none'     still loading, or the server has no support records at all
 */
export function supportHistoryView(history) {
  if (history?.status === 'error') return 'section';
  if (history?.status !== 'ready' || !Array.isArray(history.data)) return 'none';
  if (history.data.some((row) => row.status === 'ACTIVE')) return 'section';
  return history.data.length > 0 ? 'history' : 'none';
}

/** A support record as a stage key: active, completed, ended (by a manager) or cancelled (the task was cancelled, moved...). */
export function recordStage(row) {
  if (row?.status === 'ACTIVE') return 'active';
  if (row?.status === 'COMPLETED') return 'completed';
  return row?.endReason === 'MANUALLY_ENDED' ? 'ended' : 'cancelled';
}

/** A support record's status as a stage key. */
export function stageOf(status) {
  if (status === 'ACTIVE') return 'active';
  if (status === 'COMPLETED') return 'completed';
  return 'cancelled';
}

const END_REASONS = {
  TASK_COMPLETED: 'Task completed',
  TASK_CANCELLED: 'Task cancelled',
  TASK_DELETED: 'Task deleted',
  TASK_REASSIGNED: 'Task reassigned',
  TASK_MOVED: 'Task moved to another department',
  MANUALLY_ENDED: 'Ended by a manager',
};

export const describeEndReason = (reason) => END_REASONS[reason] || '';

// ── Ending an active support by hand ─────────────────────────────────────────────────────────────────────────
// idle -> reviewing (dialog open) -> submitting -> done | error. Only one request can be in flight: a second click while
// submitting is ignored. The backend decides; these rules only keep the screen honest and prevent double submits.

export const initialEndState = { status: 'idle', row: null, error: '', code: '', stale: false, result: null };

/** The End Support action is offered for active records only. */
export const canEndSupport = (row) => Boolean(row) && row.status === 'ACTIVE';

/** What ending this support does to its task, in words. A task already in progress cannot be ended from here. */
export function describeEndEffect(row, departmentName) {
  const receiving = departmentName(row.receivingDepartmentCode);
  const home = departmentName(row.homeDepartmentCode);
  if (row.taskStatus === 'IN_PROGRESS') {
    return {
      blocked: true,
      points: [],
      text: `"${row.taskTitle}" is already in progress, so this support cannot be ended yet. Complete the task or reassign it to someone in ${receiving} first.`,
    };
  }
  // Only what the server guarantees: the temporary assignment is released and the task is set back to waiting
  const points = [
    `"${row.taskTitle}" is not completed or deleted. It returns to ${receiving}'s waiting list, unassigned.`,
    `${row.staffName} no longer holds this task and stays in ${home}.`,
    'The support stays in the history as ended by a manager.',
  ];
  return { blocked: false, points, text: points.join(' ') };
}

/** After a successful end: one line for the banner. The server may have recorded a different reason if the task changed meanwhile. */
export function describeEndOutcome(row, departmentName) {
  const home = departmentName(row.homeDepartmentCode);
  if (row.endReason === 'MANUALLY_ENDED') {
    return `Support ended. ${row.staffName} is back in ${home}, and "${row.taskTitle}" is waiting again in ${departmentName(row.receivingDepartmentCode)}.`;
  }
  return `This support had already finished (${describeEndReason(row.endReason).toLowerCase() || 'ended'}), so it was closed as that. Nothing else changed.`;
}

/** Problem codes that mean the screen is out of date rather than the request being wrong. */
// (SCHEMA_OUT_OF_DATE is included because retrying cannot succeed until the database is upgraded)
const STALE_CODES = ['ALREADY_ENDED', 'CONCURRENT_CHANGE', 'TASK_IN_PROGRESS', 'SCHEMA_OUT_OF_DATE'];
export const isStaleEnd = (code, status) => STALE_CODES.includes(code) || (status === 404 && code === 'SUPPORT_NOT_FOUND');

/**
 * The message for a failed end. A 404 without a problem code is not a missing record (the server would have said
 * SUPPORT_NOT_FOUND): the server did not recognise the request at all, which means it is running an older version.
 */
export function describeEndFailure(message, code, httpStatus) {
  if (httpStatus === 404 && !code) {
    return 'The server does not recognise this action (404 on POST /api/support/assignments/{id}/end). The backend is probably running an older version and needs to be restarted with the latest one.';
  }
  return message || 'Support could not be ended.';
}

export function endReducer(state, action) {
  switch (action.type) {
    case 'END_OPEN':
      return state.status === 'idle' && canEndSupport(action.row) ? { ...initialEndState, status: 'reviewing', row: action.row } : state;
    case 'END_CANCEL':
      // Never while the request is running: it cannot be taken back once sent
      return state.status === 'reviewing' || state.status === 'error' ? initialEndState : state;
    case 'END_START':
      return state.status === 'reviewing' || state.status === 'error' ? { ...state, status: 'submitting', error: '', code: '' } : state;
    case 'END_SUCCEEDED':
      return state.status === 'submitting' ? { ...initialEndState, status: 'done', row: state.row, result: action.row } : state;
    case 'END_FAILED':
      return state.status === 'submitting'
        ? { ...state, status: 'error', error: describeEndFailure(action.message, action.code, action.httpStatus), code: action.code || '', stale: isStaleEnd(action.code, action.httpStatus) }
        : state;
    case 'END_DISMISS':
      return state.status === 'done' ? initialEndState : state;
    default:
      return state;
  }
}

/** "5 → 3" for a before/after pair, or just the value when it did not change. */
export const beforeAfter = (before, after) => (before === after ? String(before) : `${before} → ${after}`);

export const formatMinutes = (minutes) => `${Number.isFinite(minutes) ? minutes : 0} min`;
