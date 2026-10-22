import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

describe('AssignTaskModal component and task details rendering', () => {
  const modalPath = path.join(__dirname, 'AssignTaskModal.jsx');
  const source = fs.readFileSync(modalPath, 'utf8');

  it('imports formatDate and formatTime alongside formatPriority, formatDepartment, and formatDuration', () => {
    assert.ok(source.includes('formatDate'), 'Must import formatDate from taskFormatters');
    assert.ok(source.includes('formatTime'), 'Must import formatTime from taskFormatters');
    assert.ok(source.includes('formatPriority'), 'Must import formatPriority from taskFormatters');
    assert.ok(source.includes('formatDepartment'), 'Must import formatDepartment from taskFormatters');
    assert.ok(source.includes('formatDuration'), 'Must import formatDuration from taskFormatters');
  });

  describe('Complete task details display in summary section', () => {
    it('displays task.title', () => {
      assert.ok(source.includes('task.title'), 'Task summary must display task.title');
    });

    it('displays task.description when present', () => {
      assert.ok(source.includes('task.description'), 'Task summary must display task.description');
    });

    it('displays task.department formatted with formatDepartment', () => {
      assert.ok(
        source.includes('formatDepartment(task.department)'),
        'Task summary must display formatDepartment(task.department)'
      );
    });

    it('displays task.priority formatted with badge color', () => {
      assert.ok(
        /getPriorityColor\s*\(\s*task\.priority\s*\)/.test(source) &&
        /formatPriority\s*\(\s*task\.priority\s*\)/.test(source),
        'Task summary must display priority badge with formatPriority and getPriorityColor'
      );
    });

    it('displays task.estimatedDuration formatted with formatDuration', () => {
      assert.ok(
        source.includes('formatDuration(task.estimatedDuration'),
        'Task summary must format estimatedDuration using formatDuration'
      );
    });

    it('displays task.dueDate formatted with formatDate', () => {
      assert.ok(
        source.includes('formatDate(task.dueDate)') || source.includes('formattedDueDate'),
        'Task summary must display formatted due date'
      );
    });

    it('displays task.deadlineTime formatted with formatTime', () => {
      assert.ok(
        source.includes('formatTime(task.deadlineTime)') || source.includes('formattedDeadlineTime'),
        'Task summary must display formatted deadline time'
      );
    });
  });

  describe('Staff dropdown with real backend StaffDto shape', () => {
    it('renders staff name using staff.name with fallback to staff.fullName', () => {
      assert.ok(
        source.includes('staff.name') || source.includes('displayName'),
        'Staff dropdown options must use staff.name from backend StaffDto'
      );
    });

    it('uses staff.id as option value', () => {
      assert.ok(
        source.includes('value={staff.id}'),
        'Staff dropdown options must use staff.id as option value'
      );
    });

    it('displays staff department in dropdown option label', () => {
      assert.ok(
        source.includes('formatDepartment(staff.department)'),
        'Staff dropdown option label must include formatted staff.department'
      );
    });

    it('disables the currently assigned staff member when reassigning', () => {
      assert.ok(
        source.includes('disabled={isCurrent}'),
        'Currently assigned staff member must be disabled in dropdown during reassignment'
      );
      assert.ok(
        source.includes('Currently Assigned'),
        'Option label must indicate currently assigned status'
      );
    });
  });

  describe('Workload Impact Comparison UI and calculations', () => {
    it('imports calculateProjectedWorkload and workload helper functions from workloadUtils', () => {
      assert.ok(
        source.includes('calculateProjectedWorkload'),
        'Must import calculateProjectedWorkload from workloadUtils'
      );
      assert.ok(
        source.includes('getWorkloadStatusBadge'),
        'Must import getWorkloadStatusBadge from workloadUtils'
      );
      assert.ok(
        source.includes('getWorkloadProgressBarColor'),
        'Must import getWorkloadProgressBarColor from workloadUtils'
      );
      assert.ok(
        source.includes('calculateWorkloadPercentage'),
        'Must import calculateWorkloadPercentage from workloadUtils'
      );
    });

    it('imports getWorkload API service', () => {
      assert.ok(
        source.includes('getWorkload'),
        'Must import getWorkload from api service'
      );
    });

    it('calculates projectedWorkload dynamically using useMemo', () => {
      assert.ok(
        source.includes('calculateProjectedWorkload({'),
        'Must call calculateProjectedWorkload with staffId, task, currentAssignment, and workloadList'
      );
      assert.ok(
        source.includes('useMemo('),
        'Must compute projectedWorkload dynamically using useMemo'
      );
    });

    it('renders Current Workload card with numeric minutes and progress bar', () => {
      assert.ok(
        source.includes('Current Workload'),
        'Must render Current Workload card label'
      );
      assert.ok(
        source.includes('projectedWorkload.currentMinutes'),
        'Must display numeric minutes for current workload'
      );
      assert.ok(
        source.includes('getWorkloadProgressBarColor(projectedWorkload.currentStatus)'),
        'Must apply visual progress bar color for current workload status'
      );
    });

    it('renders Projected Workload card with numeric minutes, impact indicator, and progress bar', () => {
      assert.ok(
        source.includes('Projected Workload'),
        'Must render Projected Workload card label'
      );
      assert.ok(
        source.includes('projectedWorkload.projectedMinutes'),
        'Must display numeric minutes for projected workload'
      );
      assert.ok(
        source.includes('durationAdded'),
        'Must display added duration impact (+X min)'
      );
      assert.ok(
        source.includes('getWorkloadProgressBarColor(projectedWorkload.projectedStatus)'),
        'Must apply visual progress bar color for projected workload status'
      );
    });

    it('displays already-assigned notice when task is already assigned to selected staff member', () => {
      assert.ok(
        source.includes('projectedWorkload.isAlreadyAssigned'),
        'Must check projectedWorkload.isAlreadyAssigned'
      );
      assert.ok(
        source.includes('already assigned'),
        'Must render notice indicating task is already assigned'
      );
    });

    it('handles workload loading state safely', () => {
      assert.ok(
        source.includes('isWorkloadLoading'),
        'Must track and render workload loading state'
      );
    });

    it('handles workload error state safely without fake data', () => {
      assert.ok(
        source.includes('activeWorkloadError'),
        'Must handle workload error state'
      );
    });

    it('handles missing workload data safely without substituting fake values', () => {
      assert.ok(
        source.includes('Workload data unavailable') || source.includes('STAFF_NOT_FOUND'),
        'Must render missing workload data message without fake values'
      );
    });
  });

  describe('Error and empty states', () => {
    it('renders error banner with role="alert" when error is present', () => {
      assert.ok(source.includes('role="alert"'), 'Error banner must have role="alert"');
      assert.ok(source.includes('{error}'), 'Error banner must display error message');
    });

    it('renders informative message when no staff member can be selected', () => {
      assert.ok(
        source.includes('!isLoadingStaff && task && selectableStaffCount === 0 &&'),
        'Empty message must show whenever no staff card is selectable, including when only the current assignee is listed'
      );
      assert.ok(
        source.includes('!isLoadingStaff && eligibleStaff.length > 0 &&'),
        'Staff cards (including the disabled current assignee) must still render when eligibleStaff is non-empty'
      );
      assert.ok(
        source.includes('AVAILABLE') && source.includes('department'),
        'Empty message must mention department and AVAILABLE requirements'
      );
    });
  });

  describe('Real backend response shapes formatting simulation', () => {
    it('formats real TaskDto and StaffDto payloads properly without crashing', async () => {
      const {
        formatPriority,
        formatDepartment,
        formatDuration,
        formatDate,
        formatTime,
      } = await import('./taskFormatters.js');

      const realTaskDto = {
        id: '9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d',
        title: 'Room 302 Deep Clean',
        description: 'Complete room sanitization, carpet cleaning, and linen replacement.',
        category: 'Housekeeping',
        priority: 'HIGH',
        status: 'PENDING',
        estimatedDuration: 45,
        dueDate: '2026-10-18',
        deadlineTime: '14:30:00',
        department: 'HOUSEKEEPING',
      };

      const realStaffDto = {
        id: '1a2b3c4d-5e6f-7a8b-9c0d-1e2f3a4b5c6d',
        name: 'Maria Santos',
        email: 'maria.santos@hotel.com',
        phone: '555-0101',
        department: 'HOUSEKEEPING',
        availability: 'AVAILABLE',
        active: true,
      };

      assert.strictEqual(formatPriority(realTaskDto.priority), 'High');
      assert.strictEqual(formatDepartment(realTaskDto.department), 'Housekeeping');
      assert.strictEqual(formatDuration(realTaskDto.estimatedDuration), '45 min');
      assert.strictEqual(formatDate(realTaskDto.dueDate), 'Oct 18, 2026');
      assert.strictEqual(formatTime(realTaskDto.deadlineTime), '2:30 PM');

      assert.strictEqual(realStaffDto.name, 'Maria Santos');
      assert.strictEqual(formatDepartment(realStaffDto.department), 'Housekeeping');
    });

    it('simulates workload calculations with real WorkloadDto payloads', async () => {
      const { calculateProjectedWorkload } = await import('../../utils/workloadUtils.js');

      const realWorkloads = [
        {
          staffId: '1a2b3c4d-5e6f-7a8b-9c0d-1e2f3a4b5c6d',
          staffName: 'Maria Santos',
          department: 'HOUSEKEEPING',
          availability: 'AVAILABLE',
          activeTaskCount: 1,
          totalEstimatedDuration: 60,
          workloadStatus: 'LOW',
        },
        {
          staffId: '2b3c4d5e-6f7a-8b9c-0d1e-2f3a4b5c6d7e',
          staffName: 'Rahul Sharma',
          department: 'HOUSEKEEPING',
          availability: 'AVAILABLE',
          activeTaskCount: 2,
          totalEstimatedDuration: 210,
          workloadStatus: 'NORMAL',
        },
      ];

      const realTask = {
        id: 'task-clean-1',
        title: 'Lobby Glass Polishing',
        estimatedDuration: 45,
        department: 'HOUSEKEEPING',
      };

      // 1. Initial assignment for Maria
      const mariaProjection = calculateProjectedWorkload({
        staffId: '1a2b3c4d-5e6f-7a8b-9c0d-1e2f3a4b5c6d',
        task: realTask,
        currentAssignment: null,
        workloadList: realWorkloads,
      });

      assert.strictEqual(mariaProjection.currentMinutes, 60);
      assert.strictEqual(mariaProjection.durationAdded, 45);
      assert.strictEqual(mariaProjection.projectedMinutes, 105);
      assert.strictEqual(mariaProjection.projectedStatus, 'LOW');

      // 2. Changing selection to Rahul (transition from NORMAL to HIGH)
      const rahulProjection = calculateProjectedWorkload({
        staffId: '2b3c4d5e-6f7a-8b9c-0d1e-2f3a4b5c6d7e',
        task: realTask,
        currentAssignment: null,
        workloadList: realWorkloads,
      });

      assert.strictEqual(rahulProjection.currentMinutes, 210);
      assert.strictEqual(rahulProjection.durationAdded, 45);
      assert.strictEqual(rahulProjection.projectedMinutes, 255);
      assert.strictEqual(rahulProjection.currentStatus, 'NORMAL');
      assert.strictEqual(rahulProjection.projectedStatus, 'HIGH');

      // 3. Reassignment from Rahul to Maria
      const reassignProjection = calculateProjectedWorkload({
        staffId: '1a2b3c4d-5e6f-7a8b-9c0d-1e2f3a4b5c6d',
        task: realTask,
        currentAssignment: {
          id: 'asg-rahul',
          taskId: 'task-clean-1',
          staffId: '2b3c4d5e-6f7a-8b9c-0d1e-2f3a4b5c6d7e',
        },
        workloadList: realWorkloads,
      });

      assert.strictEqual(reassignProjection.currentMinutes, 60);
      assert.strictEqual(reassignProjection.durationAdded, 45);
      assert.strictEqual(reassignProjection.projectedMinutes, 105);
      assert.strictEqual(reassignProjection.isAlreadyAssigned, false);

      // 4. Reassignment selecting the same assignee (Rahul)
      const alreadyAssignedProjection = calculateProjectedWorkload({
        staffId: '2b3c4d5e-6f7a-8b9c-0d1e-2f3a4b5c6d7e',
        task: realTask,
        currentAssignment: {
          id: 'asg-rahul',
          taskId: 'task-clean-1',
          staffId: '2b3c4d5e-6f7a-8b9c-0d1e-2f3a4b5c6d7e',
        },
        workloadList: realWorkloads,
      });

      assert.strictEqual(alreadyAssignedProjection.currentMinutes, 210);
      assert.strictEqual(alreadyAssignedProjection.durationAdded, 0); // No double addition
      assert.strictEqual(alreadyAssignedProjection.projectedMinutes, 210);
      assert.strictEqual(alreadyAssignedProjection.isAlreadyAssigned, true);
    });
  });

  describe('Unassigned task selection workflow (Step 4)', () => {
    it('supports unassignedTasks prop and renders task selector when not reassigning', () => {
      assert.ok(
        source.includes('unassignedTasks && !isReassign'),
        'Must render task selection dropdown when unassignedTasks is provided and not reassigning'
      );
      assert.ok(
        source.includes('Select Task to Assign'),
        'Task selector dropdown must have label "Select Task to Assign *"'
      );
      assert.ok(
        source.includes('-- Select an Unassigned Task --'),
        'Task selector dropdown must include default placeholder option'
      );
    });

    it('renders informative message when unassignedTasks array is empty', () => {
      assert.ok(
        source.includes('unassignedTasks.length === 0'),
        'Must handle empty unassignedTasks array'
      );
      assert.ok(
        source.includes('no unassigned tasks available'),
        'Must display message when no unassigned tasks are available'
      );
    });

    it('disables submit button until both task and selectedStaffId are present', () => {
      assert.ok(
        source.includes('disabled={isSubmitting || !selectedStaffId || !task}'),
        'Submit button must be disabled when either task or selectedStaffId is missing'
      );
    });
  });

  describe('Task Assignment Redesign Layout and Selectable Staff Cards', () => {
    it('renders heading "Task Assignment" and subtitle "Assign or reassign staff to this task."', () => {
      assert.ok(source.includes('Task Assignment'), 'Must display "Task Assignment" page heading');
      assert.ok(
        source.includes('Assign or reassign staff to this task.'),
        'Must display "Assign or reassign staff to this task." subtitle'
      );
    });

    it('renders a Back button that triggers onClose', () => {
      assert.ok(
        source.includes('onClick={onClose}'),
        'Back button must trigger onClose callback'
      );
      assert.ok(
        source.includes('Back'),
        'Back button must contain "Back" text'
      );
    });

    it('renders a responsive two-column top section for Task Information and Current Assignment', () => {
      assert.ok(
        source.includes('grid-cols-1 lg:grid-cols-2') || source.includes('lg:grid-cols-2'),
        'Must use responsive two-column layout on large screens'
      );
      assert.ok(source.includes('Task Information'), 'Must render Task Information card');
      assert.ok(source.includes('Current Assignment'), 'Must render Current Assignment card');
    });

    it('renders an "Assign to Staff" section with selectable staff cards', () => {
      assert.ok(source.includes('Assign to Staff'), 'Must render "Assign to Staff" section heading');
      assert.ok(
        source.includes('onStaffChange(staff.id)'),
        'Clicking or selecting a staff card must call onStaffChange with staff.id'
      );
    });

    it('displays staff member name, department, availability, and current workload on each staff card', () => {
      assert.ok(source.includes('displayName'), 'Must display staff member name');
      assert.ok(source.includes('formatDepartment(staff.department)'), 'Must display staff department');
      assert.ok(source.includes('staff.availability'), 'Must display staff availability');
      assert.ok(source.includes('currentMins'), 'Must display staff current workload minutes');
    });

    it('clearly highlights the selected staff member', () => {
      assert.ok(
        source.includes('isSelected'),
        'Must track isSelected for the selected staff member'
      );
      assert.ok(
        source.includes('border-emerald-600') && source.includes('ring-emerald-600'),
        'Must apply emerald border and ring highlight to selected staff card'
      );
    });

    it('supports both assigning ("Assign Task") and reassigning ("Confirm Reassignment")', () => {
      assert.ok(
        source.includes('Confirm Reassignment'),
        'Must render "Confirm Reassignment" button label when reassigning'
      );
      assert.ok(
        source.includes('Assign Task'),
        'Must render "Assign Task" button label for initial assignment'
      );
    });

    it('positions the Back button in the top header with onClose callback', () => {
      assert.ok(
        source.includes('onClick={onClose}'),
        'Back button must trigger onClose callback'
      );
      assert.ok(
        source.includes('Back'),
        'Back button must include "Back" text'
      );
    });

    it('displays the current assignee workload in minutes and status badge during reassignment', () => {
      assert.ok(
        source.includes('currentAssigneeWorkload'),
        'Must compute currentAssigneeWorkload for the currently assigned staff member'
      );
      assert.ok(
        source.includes('currentAssigneeWorkload.totalEstimatedDuration'),
        'Must display current assignee workload in minutes'
      );
      assert.ok(
        source.includes('currentAssigneeWorkload.workloadStatus'),
        'Must display current assignee workload status badge'
      );
    });

    it('uses 2-letter uppercase initials or profile image for staff avatars in responsive 4-column grid', () => {
      assert.ok(
        source.includes('getStaffInitials'),
        'Must define and use getStaffInitials for avatar letters'
      );
      assert.ok(
        source.includes('xl:grid-cols-4'),
        'Must configure 4 cards per row on desktop screens with xl:grid-cols-4'
      );
    });

    it('disables the current assignee from being selected again during reassignment', () => {
      assert.ok(
        source.includes('disabled={isCurrent}'),
        'Staff card radio input must be disabled for current assignee'
      );
      assert.ok(
        source.includes('isCurrent ?'),
        'Staff card must apply disabled styling when isCurrent is true'
      );
    });

    it('explains the empty state without implying the listed current assignee is unavailable', () => {
      assert.ok(
        source.includes("No {isReassign ? 'other ' : ''}eligible staff members in {formatDepartment(task.department)} are available for {isReassign ? 'reassignment' : 'assignment'}."),
        'Empty message must say no other eligible staff are available for assignment or reassignment in the task department'
      );
      assert.ok(
        source.includes('be active, have AVAILABLE status, and have no other active assignment'),
        'Empty message must list the same eligibility rules applied by filterEligibleStaff'
      );
      assert.ok(
        source.includes('{eligibleStaff.length > selectableStaffCount && (') &&
          source.includes('The current assignee is shown below for reference and cannot be selected again.'),
        'Empty message must explain the disabled current-assignee card only when that card is shown'
      );
      assert.ok(
        !source.includes('No active and available staff members were found in'),
        'Old message implying the current assignee is unavailable must be removed'
      );
    });

    it('excludes the disabled current assignee from the eligible staff count', () => {
      assert.ok(
        source.includes('eligibleStaff.filter((staff) => currentAssignment?.staffId !== staff.id).length'),
        'Selectable count must exclude the current assignee using the same comparison as isCurrent'
      );
      assert.ok(
        source.includes("{selectableStaffCount} {selectableStaffCount === 1 ? 'Eligible Staff Member' : 'Eligible Staff Members'}"),
        'Eligible staff badge must display the selectable count'
      );
      assert.ok(
        !source.includes("{eligibleStaff.length} {eligibleStaff.length === 1 ? 'Eligible Staff Member'"),
        'Eligible staff badge must not count disabled cards'
      );
    });
  });
});


