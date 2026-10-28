import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const read = (...parts) => fs.readFileSync(path.join(__dirname, ...parts), 'utf8').replace(/\r\n/g, '\n');
const history = read('SupportHistory.jsx');
const dialog = read('SupportEndDialog.jsx');
const panel = read('..', 'CrossDepartmentSupportPanel.jsx');
const api = read('..', '..', '..', 'services', 'api.js');

describe('End Support button in the Confirmed support list', () => {
  it('shows only on active rows and only when the viewer may end support', () => {
    assert.ok(history.includes('canEnd && canEndSupport(row) &&'), 'active rows only, behind the role flag');
    assert.ok(history.includes('canEnd = false'), 'off unless the parent says the viewer is Admin or Manager');
    assert.ok(history.includes("{endingId === row.id ? 'Ending…' : 'End Support'}"));
    assert.ok(history.includes('aria-label={`End support for ${row.staffName}`}'), 'each button names its person');
  });

  it('is disabled while that row is being ended, and sits beside the status badge without breaking narrow layouts', () => {
    assert.ok(history.includes('disabled={endingId === row.id}'));
    assert.ok(history.includes('flex items-center gap-2 lg:justify-end') && history.includes('lg:grid lg:grid-cols-'), 'columns on wide screens, stacked on a phone');
    assert.ok(history.includes('text-red-700 border border-gray-300') && !/bg-red-[5-9]00/.test(history), 'End Support is a restrained outline, red only as text and on hover');
  });

  it('the panel offers the action to Admin and Manager only', () => {
    assert.ok(panel.includes("const canEnd = ['ADMIN', 'MANAGER'].includes(getUserRole());"));
    assert.ok(panel.includes('canEnd={canEnd}') && panel.includes("onEnd={(row) => endDispatch({ type: 'END_OPEN', row })}"));
  });
});

describe('End Support confirmation dialog', () => {
  it('has a title and a short subtitle', () => {
    assert.ok(dialog.includes('End temporary support') && dialog.includes('Review the details before you confirm.'));
    assert.ok(dialog.includes('aria-describedby="support-end-subtitle"'));
  });

  it('shows the staff member in a card with initials, name and the Active badge, in the emerald palette', () => {
    assert.ok(dialog.includes('initialsOf(row.staffName)') && dialog.includes('{row.staffName}') && dialog.includes('<SupportStageBadge stage="active" />'));
    assert.ok(dialog.includes('bg-emerald-50 border border-emerald-100') && dialog.includes('text-emerald-700 border border-emerald-200'), 'same tints as the Workload table avatars');
  });

  it('separates permanent department, receiving department and assigned task', () => {
    for (const text of ['Permanent department', 'Helping', 'Assigned task', 'departmentName(row.homeDepartmentCode)',
      'departmentName(row.receivingDepartmentCode)', 'row.taskTitle', '(unchanged)']) {
      assert.ok(dialog.includes(text), text);
    }
    assert.ok(dialog.includes('grid-cols-1 sm:grid-cols-2') && dialog.includes('sm:col-span-2'), 'two columns on wider screens, stacked on phones');
  });

  it('uses a restrained emerald primary button, a neutral outlined Cancel, and no destructive red buttons', () => {
    assert.ok(dialog.includes('text-white bg-emerald-600 hover:bg-emerald-700'));
    assert.ok(dialog.includes('text-gray-700 border border-gray-300 rounded-lg hover:bg-gray-50'));
    assert.ok(!/bg-red-[5-9]00/.test(dialog), 'no solid red fills; red stays for the error message only');
    assert.ok(dialog.includes('focus-visible:ring-2 focus-visible:ring-emerald-500'), 'visible keyboard focus');
    assert.ok(dialog.includes('flex flex-col-reverse sm:flex-row sm:justify-end'), 'full-width stacked buttons on phones, End support on top');
  });

  it('keeps Tab inside the dialog and starts focus on the safe choice', () => {
    assert.ok(dialog.includes("event.key === 'Tab'") && dialog.includes('first.focus()') && dialog.includes('last.focus()'));
    assert.ok(dialog.includes('cancelRef.current?.focus()'));
  });

  it('explains what happens in short points and blocks the action when the task is in progress', () => {
    assert.ok(dialog.includes('describeEndEffect(row, departmentName)') && dialog.includes('effect.points.map') && dialog.includes('{effect.text}'));
    assert.ok(dialog.includes('What ending support does') && dialog.includes('This support cannot be ended yet'));
    assert.ok(dialog.includes('const cannotEnd = effect.blocked || stale;') && dialog.includes('{!cannotEnd && ('), 'no confirm button when it cannot be ended');
  });

  it('cancels with Cancel, Escape or the backdrop, but never while the request is running', () => {
    assert.ok(dialog.includes("event.key === 'Escape' && !submitting"));
    assert.ok(dialog.includes('onClick={() => !submitting && onCancel()}'));
    assert.match(dialog, /disabled=\{submitting\}\s+onClick=\{onCancel\}/);
  });

  it('shows a loading label, an error with the reason, and a retry', () => {
    assert.ok(dialog.includes("submitting ? 'Ending…' : status === 'error' ? 'Try again' : 'End support'"));
    assert.match(dialog, /disabled=\{submitting\}\s+onClick=\{onConfirm\}/, 'the confirm button is disabled while submitting');
    assert.ok(dialog.includes('role="alert"') && dialog.includes('Support was not ended.') && dialog.includes('{error}'));
  });

  it('is an accessible modal that fits phones and uses the existing design', () => {
    assert.ok(dialog.includes('role="dialog"') && dialog.includes('aria-modal="true"') && dialog.includes('aria-labelledby="support-end-title"'));
    assert.ok(dialog.includes('w-full max-w-md max-h-[90vh] overflow-y-auto rounded-2xl') && dialog.includes('p-3 sm:p-4'), 'scrolls inside the viewport on a 390px phone');
    assert.ok(dialog.includes('min-w-0') && dialog.includes('break-words') && dialog.includes('truncate'), 'long names and task titles cannot overflow');
  });
});

describe('wiring in the panel', () => {
  it('sends one request per confirmation and refreshes everything that depends on it afterwards', () => {
    assert.ok(panel.includes('if (endInFlight.current || !endState.row) return;') && panel.includes('endInFlight.current = true;'), 'double clicks are ignored');
    assert.ok(panel.includes('await endSupportAssignment(row.id)'));
    const finallyBlock = panel.slice(panel.indexOf('} finally {', panel.indexOf('endSupportAssignment(row.id)')));
    for (const call of ['loadNeeds();', 'loadHistory();', 'loadOptions(state.department);']) assert.ok(finallyBlock.includes(call), call);
    assert.ok(panel.includes('if (onChanged) onChanged();'), 'the page refreshes its workload table');
  });

  it('reports success and keeps the error in the dialog', () => {
    assert.ok(panel.includes("endState.status === 'done'") && panel.includes('describeEndOutcome(endState.result, departmentNames)'));
    assert.ok(panel.includes("['reviewing', 'submitting', 'error'].includes(endState.status)"));
  });

  it('calls POST /api/support/assignments/{id}/end', () => {
    assert.ok(api.includes('`/api/support/assignments/${encodeURIComponent(supportId)}/end`, { method: \'POST\' }'));
  });
});
