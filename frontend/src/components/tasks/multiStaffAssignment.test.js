import { before, describe, it } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { loadModule, renderHtml } from '../../testSupport/renderJsx.js';
import { groupAssignmentsByTask } from '../../utils/assignmentUtils.js';

const here = path.dirname(fileURLToPath(import.meta.url));
const src = path.join(here, '..', '..');
const read = (...parts) => fs.readFileSync(path.join(src, ...parts), 'utf8').replace(/\r\n/g, '\n');

const task = { id: 't1', title: 'Room Cleaning', department: 'HOUSEKEEPING', priority: 'HIGH', status: 'PENDING', estimatedDuration: 40 };
const staff = [
  { id: 's1', name: 'Rahul Sharma', department: 'HOUSEKEEPING', availability: 'AVAILABLE' },
  { id: 's2', name: 'Priya Nair', department: 'HOUSEKEEPING', availability: 'AVAILABLE' },
  { id: 's3', name: 'Amit Rao', department: 'HOUSEKEEPING', availability: 'AVAILABLE' },
  { id: 's4', name: 'Meera Iyer', department: 'HOUSEKEEPING', availability: 'AVAILABLE' },
];
const workload = [
  { staffId: 's1', staffName: 'Rahul Sharma', activeTaskCount: 0, totalEstimatedDuration: 0, workloadStatus: 'LOW' },
  { staffId: 's2', staffName: 'Priya Nair', activeTaskCount: 0, totalEstimatedDuration: 100, workloadStatus: 'LOW' },
  { staffId: 's3', staffName: 'Amit Rao', activeTaskCount: 0, totalEstimatedDuration: 200, workloadStatus: 'NORMAL' },
  { staffId: 's4', staffName: 'Meera Iyer', activeTaskCount: 0, totalEstimatedDuration: 0, workloadStatus: 'LOW' },
];

const CHECKMARK = 'd="M5 13l4 4L19 7"';
// A selected card (and only a selected card) carries the green ring; the counter chip is also green, so border-emerald-600 alone is not a card marker
const SELECTED_CARD = 'border-emerald-600 bg-emerald-50/20 ring-2 ring-emerald-600/20';
// The attribute, not the class names (the buttons carry disabled: variants in their classes)
const isDisabled = (tag) => tag.includes(' disabled=""');
const count = (html, text) => html.split(text).length - 1;

describe('Assign Task modal with several selected staff (rendered)', () => {
  let Modal;
  before(async () => { Modal = (await loadModule('components/tasks/AssignTaskModal.jsx')).default; });

  const render = (over = {}) => renderHtml(Modal, {
    task, eligibleStaff: staff, workloadList: workload, selectedStaffIds: [], onStaffToggle() {}, onSubmit() {}, onClose() {}, ...over,
  });
  const counter = (html) => (/data-testid="selected-staff-count"[^>]*>([^<]*)</.exec(html) || [])[1];
  const submit = (html) => /<button type="submit"[^>]*>/.exec(html)[0];

  it('with nothing selected: the counter says so, an empty-selection prompt is shown, no card is highlighted, and submitting is disabled', () => {
    const html = render();
    assert.equal(counter(html), 'No staff members selected');
    assert.ok(html.includes('Select an eligible staff member below to view current workload and projected assignment impact.'));
    assert.equal(count(html, SELECTED_CARD), 0);
    assert.equal(count(html, CHECKMARK), 0);
    assert.ok(isDisabled(submit(html)), 'submit is disabled');
    assert.ok(html.includes('Select one or more active, eligible staff members from'));
  });

  it('with several selected: the counter shows the right number, every selected card keeps its green border and checkmark, the others have none', () => {
    const html = render({ selectedStaffIds: ['s1', 's3', 's4'] });
    assert.equal(counter(html), '3 staff members selected');
    assert.equal(count(html, SELECTED_CARD), 3, 'three cards have the green border');
    assert.equal(count(html, CHECKMARK), 3, 'and three checkmarks');
    assert.equal(count(html, 'checked=""'), 3, 'the hidden checkboxes agree');
    assert.ok(!isDisabled(submit(html)), 'submit is enabled');
    assert.ok(html.includes('Assign Task to 3 Staff'));
  });

  it('adding a second member leaves the first selected: both cards are marked (two, then one when one is removed)', () => {
    const two = render({ selectedStaffIds: ['s1', 's2'] });
    assert.equal(counter(two), '2 staff members selected');
    assert.equal(count(two, SELECTED_CARD), 2);
    const one = render({ selectedStaffIds: ['s2'] });
    assert.equal(counter(one), '1 staff member selected');
    assert.equal(count(one, SELECTED_CARD), 1);
    assert.ok(!isDisabled(submit(one)));
    assert.ok(one.includes('>Assign Task<'), 'a single person keeps the plain button text');
  });

  it('the staff inputs are accessible checkboxes with the staff id as value (no radio buttons)', () => {
    const html = render({ selectedStaffIds: ['s2'] });
    assert.equal(count(html, 'type="checkbox"'), 4);
    assert.equal(count(html, 'type="radio"'), 0);
    for (const id of ['s1', 's2', 's3', 's4']) assert.ok(html.includes(`value="${id}"`), id);
    assert.ok(html.includes('aria-label="Select Priya Nair"'));
  });

  it('the workload preview shows the impact for EACH selected member, each with the full task duration added', () => {
    const html = render({ selectedStaffIds: ['s2', 's3'] });
    assert.equal(count(html, 'Projected Workload'), 2, 'one comparison per selected person');
    assert.ok(html.includes('Priya Nair') && html.includes('Amit Rao'));
    assert.ok(html.includes('140 min'), 'Priya: 100 + the full 40 minutes');
    assert.ok(html.includes('240 min'), 'Amit: 200 + the full 40 minutes');
    assert.equal(count(html, '(+40 min)'), 2, 'each is shown +40');
  });

  it('with ONE selected member the preview shows one comparison and no name heading (unchanged look)', () => {
    const html = render({ selectedStaffIds: ['s1'] });
    assert.equal(count(html, 'Projected Workload'), 1);
    assert.ok(!html.includes('text-xs font-bold text-gray-800'), 'no per-person heading for one person');
    assert.ok(html.includes('(+40 min)'));
  });

  it('a selected member with no workload data says so, without breaking the others', () => {
    const html = render({ selectedStaffIds: ['s1', 'ghost'], eligibleStaff: staff, workloadList: workload });
    assert.equal(count(html, 'Projected Workload'), 1, 'the person with data still gets a comparison');
    assert.equal(count(html, 'Workload data unavailable for selected staff member.'), 1, 'the one without says so');
    const missing = render({ selectedStaffIds: ['s1'], workloadList: workload.filter((w) => w.staffId !== 's1') });
    assert.ok(missing.includes('Workload data unavailable for selected staff member.'));
  });

  it('loading and error states of the workload data still show instead of the comparisons', () => {
    assert.ok(render({ selectedStaffIds: ['s1', 's2'], isLoadingWorkload: true }).includes('Loading workload data from server...'));
    const failed = render({ selectedStaffIds: ['s1', 's2'], workloadError: 'boom' });
    assert.ok(failed.includes('Unable to load live workload data.'));
    assert.equal(count(failed, 'Projected Workload'), 0);
  });

  it('people already on the task are shown but cannot be chosen, and are not counted as selectable', () => {
    const html = render({ assignedStaffIds: ['s1', 's2'], currentAssignment: { id: 'a1', staffId: 's1', taskId: 't1' } });
    assert.equal(count(html, 'opacity-60 cursor-not-allowed'), 2, 'both people are shown greyed out');
    assert.equal(count(html, ' disabled=""'), 2 + 1, 'two disabled checkboxes plus the disabled submit button');
    assert.ok(html.includes('>2 Eligible Staff Members<') || html.includes('2 Eligible Staff Members'));
  });

  it('reassignment keeps its behaviour: the button says Confirm Reassignment, and one chosen person enables it', () => {
    const html = render({ currentAssignment: { id: 'a1', staffId: 's1', taskId: 't1' }, assignedStaffIds: ['s1'], selectedStaffIds: ['s2'] });
    assert.ok(html.includes('Reassignment Mode'));
    assert.ok(html.includes('Confirm Reassignment'));
    assert.ok(!isDisabled(submit(html)));
    assert.equal(counter(html), '1 staff member selected');
    assert.ok(html.includes('Select an active, eligible staff member from'), 'single-person wording in reassignment');
    const none = render({ currentAssignment: { id: 'a1', staffId: 's1', taskId: 't1' }, assignedStaffIds: ['s1'] });
    assert.ok(isDisabled(submit(none)));
  });

  it('the submit button shows Processing while submitting', () => {
    assert.ok(render({ selectedStaffIds: ['s1', 's2'], isSubmitting: true }).includes('Processing...'));
  });
});

describe('Tasks table and task details with several assignees (rendered)', () => {
  let Table;
  let Details;
  before(async () => {
    Table = (await loadModule('components/tasks/TasksTable.jsx')).default;
    Details = (await loadModule('components/tasks/TaskDetailsModal.jsx')).default;
  });
  const three = groupAssignmentsByTask([
    { id: '1', taskId: 't1', staffId: 'a', staffName: 'Rahul' },
    { id: '2', taskId: 't1', staffId: 'b', staffName: 'Priya' },
    { id: '3', taskId: 't1', staffId: 'c', staffName: 'Amit' },
  ]);
  const second = { id: 't2', title: 'Lobby', department: 'FRONT_DESK', priority: 'LOW', status: 'PENDING', estimatedDuration: 10 };

  it('the table shows the first assignee and +2, with all three names in the tooltip; an unassigned task still says Unassigned', () => {
    const html = renderHtml(Table, { filteredTasks: [task, second], totalTaskCount: 2, assignmentsByTaskId: three, canManageTasks: true });
    assert.ok(html.includes('Rahul +2'));
    assert.ok(html.includes('title="Rahul, Priya, Amit"'));
    assert.equal(count(html, 'Unassigned'), 1);
  });

  it('one assignee is shown by name alone, exactly as before', () => {
    const one = groupAssignmentsByTask([{ id: '1', taskId: 't1', staffId: 'a', staffName: 'Rahul' }]);
    const html = renderHtml(Table, { filteredTasks: [task], totalTaskCount: 1, assignmentsByTaskId: one, canManageTasks: true });
    assert.ok(html.includes('>Rahul<') && !html.includes('Rahul +'));
  });

  it('the details view lists every assigned staff member, not just one', () => {
    const html = renderHtml(Details, { task, assignmentsByTaskId: three, onClose() {} });
    assert.ok(html.includes('Assigned Staff Members (3)'));
    for (const name of ['Rahul', 'Priya', 'Amit']) assert.ok(html.includes(`>${name}</li>`), name);
  });

  it('the details view of a one-assignee or unassigned task is as before', () => {
    const one = groupAssignmentsByTask([{ id: '1', taskId: 't1', staffId: 'a', staffName: 'Rahul' }]);
    const oneHtml = renderHtml(Details, { task, assignmentsByTaskId: one, onClose() {} });
    assert.ok(oneHtml.includes('Assigned Staff Member<') && oneHtml.includes('>Rahul</li>'));
    const noneHtml = renderHtml(Details, { task: second, assignmentsByTaskId: three, onClose() {} });
    assert.ok(noneHtml.includes('Unassigned'));
  });
});

describe('the two pages that host the modal (source checks: their state needs a browser)', () => {
  const pages = { TasksPage: read('pages', 'TasksPage.jsx'), AssignmentsPage: read('pages', 'AssignmentsPage.jsx') };

  for (const [name, source] of Object.entries(pages)) {
    describe(name, () => {
      it('keeps an array of selected staff ids and no single selected id', () => {
        assert.ok(source.includes('const [selectedStaffIds, setSelectedStaffIds] = useState([]);'));
        assert.ok(!source.includes('selectedStaffId,') && !source.includes('setSelectedStaffId('), 'the single-id state is gone');
      });

      it('toggles through the shared helper, single-staff only while reassigning', () => {
        assert.ok(source.includes('onStaffToggle={(staffId) => setSelectedStaffIds((current) => toggleStaffSelection(current, staffId, { single:'));
        assert.ok(source.includes('selectedStaffIds={selectedStaffIds}'));
        assert.ok(source.includes('assignedStaffIds={'));
      });

      it('a new assignment submits all selected ids together through createAssignment, with staffIds', () => {
        assert.ok(source.includes('await createAssignment(buildAssignmentRequest(assignModalTask.id, staffIds));'));
        assert.ok(source.includes('assignmentSuccessMessage(assignModalTask.title, staffIds.length)'));
        assert.ok(!/createAssignment\(\{\s*taskId/.test(source), 'the old one-person payload is gone');
      });

      it('a reassignment still uses the single-staff API with one person', () => {
        assert.ok(source.includes('await reassignTask(currentAssignment.id, { staffId: staffIds[0] });'));
        assert.ok(source.includes('A reassignment moves the task to one staff member. Please choose one.'));
      });

      it('an empty selection is refused before any request, with a message', () => {
        const at = source.indexOf('const staffIds = submittableStaffIds(selectedStaffIds, eligibleStaff, assignedIds);');
        assert.ok(at > 0);
        const guard = source.slice(at, at + 260);
        assert.ok(guard.includes('staffIds.length === 0') && guard.includes('Please select at least one eligible staff member.') && guard.includes('return;'));
        assert.ok(source.indexOf('Please select at least one eligible staff member.') < source.indexOf('await createAssignment('));
      });

      it('the selection is cleared when the modal opens, the task changes, the modal closes and after a successful assignment', () => {
        assert.ok((source.match(/setSelectedStaffIds\(\[\]\)/g) || []).length >= 3, 'cleared in several places');
      });
    });
  }

  it('Tasks page: the selection is cleared when the modal is closed, and a task is opened with an empty selection', () => {
    const source = pages.TasksPage;
    assert.ok(source.includes('onClose={() => { setAssignModalTask(null); setSelectedStaffIds([]); }}'));
    const open = source.slice(source.indexOf('const handleOpenAssignModal'), source.indexOf('const handleOpenAssignModal') + 220);
    assert.ok(open.includes('setSelectedStaffIds([]);'));
    const success = source.slice(source.indexOf('await createAssignment('), source.indexOf('await createAssignment(') + 400);
    assert.ok(success.includes('setSelectedStaffIds([]);'));
  });

  it('Assignments page: the selection is cleared on close, on choosing another task, on opening the reassign view and after success', () => {
    const source = pages.AssignmentsPage;
    const close = source.slice(source.indexOf('const handleCloseAssignModal'), source.indexOf('const handleCloseAssignModal') + 300);
    assert.ok(close.includes('setSelectedStaffIds([]);'));
    const pick = source.slice(source.indexOf('const handleTaskSelect'), source.indexOf('const handleTaskSelect') + 260);
    assert.ok(pick.includes('setSelectedStaffIds([]);'));
    const reassign = source.slice(source.indexOf('const handleOpenReassignModal'), source.indexOf('const handleOpenReassignModal') + 700);
    assert.ok(reassign.includes('setSelectedStaffIds([]);'));
    assert.ok(/setCurrentAssignment\(null\);\s*setSelectedStaffIds\(\[\]\);\s*if \(location\.state\)/.test(source), 'cleared after a successful assignment');
  });

  it('both pages group assignments by task, so a task with several assignees is never reduced to one', () => {
    for (const source of Object.values(pages)) {
      assert.ok(source.includes('groupAssignmentsByTask(assignments)'));
      assert.ok(!source.includes('map.set(assignment.taskId, assignment)'));
    }
  });

  it('Tasks page: a task with several assignees cannot be reassigned from the Tasks screen (each assignee is reassigned from Assignments), and search covers every assignee', () => {
    const source = pages.TasksPage;
    assert.ok(source.includes('function soleAssignment(taskAssignments)'));
    assert.ok(source.includes('This task has several assignees. Reassign each one from the Assignments page.'));
    assert.ok(source.includes('assigneeMatchesSearch(assignmentsByTaskId.get(task.id), query)'));
    assert.ok(read('components', 'tasks', 'TasksTable.jsx').includes('hasSeveralAssignees && !isTerminalStatus'));
  });

  it('the Assignments page still lists one row per staff member and reassigns that row only', () => {
    const source = pages.AssignmentsPage;
    assert.ok(source.includes('handleOpenReassignModal(assignment)') && source.includes('setCurrentAssignment(assignment);'));
  });
});

describe('the workload panel scrolls on its own (rendered layout)', () => {
  let Modal;
  before(async () => { Modal = (await loadModule('components/tasks/AssignTaskModal.jsx')).default; });

  const many = Array.from({ length: 8 }, (_, i) => ({ id: `m${i}`, name: `Member ${i}`, department: 'HOUSEKEEPING', availability: 'AVAILABLE' }));
  const manyLoad = many.map((m, i) => ({ staffId: m.id, staffName: m.name, activeTaskCount: 0, totalEstimatedDuration: i * 10, workloadStatus: 'LOW' }));
  const render = (ids) => renderHtml(Modal, {
    task, eligibleStaff: many, workloadList: manyLoad, selectedStaffIds: ids, onStaffToggle() {}, onSubmit() {}, onClose() {},
  });
  const AREA = 'data-testid="workload-scroll-area"';
  /** The markup of the scroll area only: from its opening tag to the start of the next major section. */
  const area = (html) => html.slice(html.indexOf(AREA), html.indexOf('Assign to Staff'));

  it('the two top columns are aligned at the top, so the Task Information card keeps its own height; they stack on small screens', () => {
    const html = render(['m0']);
    const grid = /<div class="grid grid-cols-1 lg:grid-cols-2 items-start gap-6">/.exec(html);
    assert.ok(grid, 'one column on small screens, two on large, items aligned to the top (not stretched)');
  });

  it('the comparisons sit in a bounded, vertically scrolling, keyboard-reachable region with no horizontal scrolling', () => {
    const tag = /<div data-testid="workload-scroll-area"[^>]*>/.exec(render(['m0']))[0];
    for (const part of ['max-h-[26rem]', 'lg:max-h-[14rem]', 'overflow-y-auto', 'overflow-x-hidden', 'overscroll-contain', 'min-w-0']) assert.ok(tag.includes(part), part);
    assert.ok(tag.includes('role="region"') && tag.includes('aria-label="Workload impact for the selected staff"') && tag.includes('tabindex="0"'));
    assert.ok(tag.includes('[scrollbar-width:thin]') && tag.includes('rgba(16,185,129,0.45)'), 'a thin scrollbar in the Taskotel green');
  });

  it('the heading is outside the scrolling region, so it stays visible while the comparisons scroll', () => {
    const html = render(['m0', 'm1', 'm2']);
    const heading = html.indexOf('Workload Impact Comparison');
    assert.ok(heading > 0 && heading < html.indexOf(AREA), 'the heading comes before the scroll area');
    assert.ok(!area(html).includes('Workload Impact Comparison'), 'and is not inside it');
  });

  it('with one, three or eight selected, every comparison is inside the same scrolling region (the card does not need to grow)', () => {
    for (const ids of [['m0'], ['m0', 'm1', 'm2'], many.map((m) => m.id)]) {
      const html = render(ids);
      assert.equal(count(area(html), 'Projected Workload'), ids.length, `${ids.length} comparison(s) inside the scroll area`);
      assert.equal(count(html, AREA), 1, 'one scroll area, no nested scrolling regions');
    }
  });

  it('the prompt, loading and error messages are inside the scrolling region too, so the panel keeps one shape', () => {
    assert.ok(area(render([])).includes('Select an eligible staff member below'));
    assert.ok(area(renderHtml(Modal, { task, eligibleStaff: many, workloadList: manyLoad, selectedStaffIds: ['m0'], isLoadingWorkload: true, onStaffToggle() {}, onSubmit() {}, onClose() {} })).includes('Loading workload data from server...'));
  });

  it('nothing else about the modal changed: the staff cards, the counter and the submit button are outside the scrolling region', () => {
    const html = render(['m0', 'm1']);
    assert.ok(!area(html).includes('type="checkbox"') && !area(html).includes('type="submit"'));
    assert.ok(html.includes('2 staff members selected') && html.includes('Assign Task to 2 Staff'));
  });
});
