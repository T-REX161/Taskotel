import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import {
  assignmentSuccessMessage, buildAssignmentRequest, formatSelectedStaffCount, submittableStaffIds, toggleStaffSelection,
} from './staffSelection.js';
import { assigneeMatchesSearch, assigneeNames, formatAssignees, groupAssignmentsByTask } from './assignmentUtils.js';

describe('choosing several staff members (a new assignment)', () => {
  it('clicking a card adds that person: a second member does not remove the first', () => {
    let selected = [];
    selected = toggleStaffSelection(selected, 'a');
    assert.deepEqual(selected, ['a']);
    selected = toggleStaffSelection(selected, 'b');
    assert.deepEqual(selected, ['a', 'b']);
    selected = toggleStaffSelection(selected, 'c');
    assert.deepEqual(selected, ['a', 'b', 'c'], 'every earlier choice is kept, in the order chosen');
  });

  it('clicking a selected card removes only that person', () => {
    assert.deepEqual(toggleStaffSelection(['a', 'b', 'c'], 'b'), ['a', 'c']);
    assert.deepEqual(toggleStaffSelection(['a', 'b', 'c'], 'a'), ['b', 'c']);
    assert.deepEqual(toggleStaffSelection(['a'], 'a'), []);
  });

  it('selecting, deselecting and selecting again works, and a person is never in the list twice', () => {
    let selected = toggleStaffSelection(toggleStaffSelection([], 'a'), 'b');
    selected = toggleStaffSelection(selected, 'a');
    assert.deepEqual(selected, ['b']);
    selected = toggleStaffSelection(selected, 'a');
    assert.deepEqual(selected, ['b', 'a']);
    assert.equal(new Set(selected).size, selected.length);
  });

  it('never changes the array it is given (React state is replaced, not edited)', () => {
    const before = ['a', 'b'];
    const after = toggleStaffSelection(before, 'c');
    assert.deepEqual(before, ['a', 'b']);
    assert.notEqual(after, before);
    const removed = toggleStaffSelection(before, 'a');
    assert.deepEqual(before, ['a', 'b']);
    assert.deepEqual(removed, ['b']);
  });

  it('an empty or missing id, or a missing list, changes nothing and does not throw', () => {
    assert.deepEqual(toggleStaffSelection(['a'], ''), ['a']);
    assert.deepEqual(toggleStaffSelection(['a'], null), ['a']);
    assert.deepEqual(toggleStaffSelection(['a'], undefined), ['a']);
    assert.deepEqual(toggleStaffSelection(undefined, 'a'), ['a']);
    assert.deepEqual(toggleStaffSelection(null, 'a'), ['a']);
  });
});

describe('a reassignment stays single-staff', () => {
  it('selecting another person replaces the choice; clicking the selected person clears it', () => {
    assert.deepEqual(toggleStaffSelection([], 'a', { single: true }), ['a']);
    assert.deepEqual(toggleStaffSelection(['a'], 'b', { single: true }), ['b']);
    assert.deepEqual(toggleStaffSelection(['b'], 'b', { single: true }), []);
  });
});

describe('the selection counter', () => {
  it('says how many are selected, with the right singular and plural', () => {
    assert.equal(formatSelectedStaffCount(0), 'No staff members selected');
    assert.equal(formatSelectedStaffCount(1), '1 staff member selected');
    assert.equal(formatSelectedStaffCount(2), '2 staff members selected');
    assert.equal(formatSelectedStaffCount(3), '3 staff members selected');
    assert.equal(formatSelectedStaffCount(12), '12 staff members selected');
  });

  it('bad input counts as none', () => {
    for (const bad of [undefined, null, NaN, -4, 'x']) assert.equal(formatSelectedStaffCount(bad), 'No staff members selected', String(bad));
  });
});

describe('what is submitted', () => {
  const eligible = [{ id: 'a' }, { id: 'b' }, { id: 'c' }];

  it('the request carries the task and ALL the selected staff, together, in staffIds', () => {
    assert.deepEqual(buildAssignmentRequest('t1', ['a', 'b', 'c']), { taskId: 't1', staffIds: ['a', 'b', 'c'] });
    assert.deepEqual(buildAssignmentRequest('t1', ['a']), { taskId: 't1', staffIds: ['a'] }, 'even one person goes as a list');
    const ids = ['a', 'b'];
    assert.notEqual(buildAssignmentRequest('t1', ids).staffIds, ids, 'a copy, not the state array itself');
    assert.equal('staffId' in buildAssignmentRequest('t1', ids), false, 'never both formats');
  });

  it('only people who are really selectable are sent, once each, in the order chosen', () => {
    assert.deepEqual(submittableStaffIds(['c', 'a', 'b'], eligible), ['c', 'a', 'b']);
    assert.deepEqual(submittableStaffIds(['a', 'a', 'b'], eligible), ['a', 'b'], 'no duplicates');
    assert.deepEqual(submittableStaffIds(['a', 'zzz', 'b'], eligible), ['a', 'b'], 'someone no longer eligible is dropped');
    assert.deepEqual(submittableStaffIds(['a', 'b'], eligible, ['b']), ['a'], 'someone already on the task is dropped');
    assert.deepEqual(submittableStaffIds(['', null, undefined, 'a'], eligible), ['a']);
    assert.deepEqual(submittableStaffIds([], eligible), []);
    assert.deepEqual(submittableStaffIds(undefined, eligible), []);
    assert.deepEqual(submittableStaffIds(['a'], undefined), []);
  });

  it('the success message names how many people when there are several', () => {
    assert.equal(assignmentSuccessMessage('Room Cleaning', 1), 'Task "Room Cleaning" was successfully assigned.');
    assert.equal(assignmentSuccessMessage('Room Cleaning', 3), 'Task "Room Cleaning" was successfully assigned to 3 staff members.');
  });
});

describe('several assignees on one task (grouping and display)', () => {
  const assignments = [
    { id: '1', taskId: 't1', staffId: 'a', staffName: 'Rahul' },
    { id: '2', taskId: 't2', staffId: 'b', staffName: 'Meera' },
    { id: '3', taskId: 't1', staffId: 'c', staffName: 'Priya' },
    { id: '4', taskId: 't1', staffId: 'd', staffName: 'Amit' },
  ];

  it('groups by task id: every assignment is kept, none overwrites another', () => {
    const map = groupAssignmentsByTask(assignments);
    assert.equal(map.size, 2);
    assert.deepEqual(map.get('t1').map((a) => a.id), ['1', '3', '4']);
    assert.deepEqual(map.get('t2').map((a) => a.id), ['2']);
    assert.equal(map.has('t3'), false);
  });

  it('ignores empty entries and a missing list', () => {
    assert.equal(groupAssignmentsByTask(undefined).size, 0);
    assert.equal(groupAssignmentsByTask([null, {}, { taskId: '' }]).size, 0);
  });

  it('shows the first name and +N for the others, with every name in the tooltip', () => {
    const t1 = groupAssignmentsByTask(assignments).get('t1');
    assert.deepEqual(formatAssignees(t1), { label: 'Rahul +2', title: 'Rahul, Priya, Amit', count: 3 });
    assert.deepEqual(formatAssignees(groupAssignmentsByTask(assignments).get('t2')), { label: 'Meera', title: 'Meera', count: 1 });
    assert.deepEqual(formatAssignees(groupAssignmentsByTask(assignments).get('t1').slice(0, 2)), { label: 'Rahul +1', title: 'Rahul, Priya', count: 2 });
  });

  it('no assignee gives an empty label (the table shows Unassigned)', () => {
    for (const none of [undefined, null, [], [{ staffName: '' }], [{}]]) assert.deepEqual(formatAssignees(none), { label: '', title: '', count: 0 });
  });

  it('lists every name for the details view, without blanks', () => {
    assert.deepEqual(assigneeNames(groupAssignmentsByTask(assignments).get('t1')), ['Rahul', 'Priya', 'Amit']);
    assert.deepEqual(assigneeNames([{ staffName: '  Ana  ' }, { staffName: '' }, null]), ['Ana']);
  });

  it('a search matches ANY assignee of the task', () => {
    const t1 = groupAssignmentsByTask(assignments).get('t1');
    assert.equal(assigneeMatchesSearch(t1, 'amit'), true, 'the third assignee is found');
    assert.equal(assigneeMatchesSearch(t1, 'PRI'), true);
    assert.equal(assigneeMatchesSearch(t1, 'meera'), false);
    assert.equal(assigneeMatchesSearch(t1, ''), false);
    assert.equal(assigneeMatchesSearch(undefined, 'amit'), false);
  });
});
