package com.hotel.workload.support;

import com.hotel.workload.assignment.Assignment;
import com.hotel.workload.assignment.AssignmentRepository;
import com.hotel.workload.assignment.AssignmentService;
import com.hotel.workload.assignment.CreateAssignmentRequest;
import com.hotel.workload.assignment.UpdateAssignmentRequest;
import com.hotel.workload.security.Role;
import com.hotel.workload.security.UserRepository;
import com.hotel.workload.staff.Availability;
import com.hotel.workload.staff.DepartmentRepository;
import com.hotel.workload.staff.Staff;
import com.hotel.workload.staff.StaffRepository;
import com.hotel.workload.support.SupportDtos.*;
import com.hotel.workload.task.Priority;
import com.hotel.workload.task.Task;
import com.hotel.workload.task.TaskRepository;
import com.hotel.workload.task.TaskService;
import com.hotel.workload.task.TaskStatus;
import com.hotel.workload.task.UpdateTaskRequest;
import com.hotel.workload.workload.WorkloadDto;
import com.hotel.workload.workload.WorkloadService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
public class SupportAssignmentServiceTest {

    @Autowired private SupportAssignmentService service;
    @Autowired private TaskService taskService;
    @Autowired private AssignmentService assignmentService;
    @Autowired private WorkloadService workloadService;
    @Autowired private UserRepository users;
    @Autowired private StaffRepository staffRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private AssignmentRepository assignmentRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private SupportAssignmentRepository supportRepository;
    @Autowired private EntityManager entityManager;

    private SupportTestFixture fx;

    @BeforeEach
    void setUp() {
        fx = new SupportTestFixture(users, staffRepository, taskRepository, assignmentRepository, departmentRepository, supportRepository);
        SupportTestFixture.signInAs("manager_test", "MANAGER");
    }

    @AfterEach
    void tearDown() {
        SupportTestFixture.signOut();
    }

    private Set<String> codes(Preview preview) {
        return preview.problems().stream().map(Problem::code).collect(Collectors.toSet());
    }

    private Set<String> codesOf(SupportPlanException e) {
        return e.getProblems().stream().map(Problem::code).collect(Collectors.toSet());
    }

    // ── a valid plan ─────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("A valid plan creates real assignments plus support records, and the home department never changes")
    void validPlanIsPersistedAndKeepsHomeDepartment() {
        var s = fx.overloaded(3, 3);
        Staff a = s.donors.get(0), b = s.donors.get(1);

        List<SupportAssignmentDto> created = service.confirm(fx.plan(s.receiving, a, s.tasks.get(0), b, s.tasks.get(1)));

        assertEquals(2, created.size());
        assertEquals(1, created.stream().map(SupportAssignmentDto::planId).distinct().count(), "one plan id for the whole plan");
        entityManager.flush();
        entityManager.clear();

        for (int i = 0; i < 2; i++) {
            Staff helper = i == 0 ? a : b;
            Task task = s.tasks.get(i);
            Assignment assignment = assignmentRepository.findByTaskId(task.getId()).orElseThrow();
            assertEquals(helper.getId(), assignment.getStaff().getId(), "a normal assignment was created");
            assertEquals(TaskStatus.ASSIGNED, taskRepository.findById(task.getId()).orElseThrow().getStatus());
            SupportAssignment record = supportRepository.findByTaskIdAndStatus(task.getId(), SupportStatus.ACTIVE).orElseThrow();
            assertEquals(helper.getId(), record.getStaffId());
            assertEquals(s.donor.getCode(), record.getHomeDepartmentCode());
            assertEquals(s.receiving.getCode(), record.getReceivingDepartmentCode());
            assertEquals(task.getTitle(), record.getTaskTitle());
            assertEquals(assignment.getId(), record.getAssignmentId());
            assertEquals("manager_test", record.getApprovedByUsername());
            assertNotNull(record.getCreatedAt());
            assertNull(record.getCompletedAt());
            assertEquals(0L, record.getVersion());
            // The permanent department is untouched
            assertEquals(s.donor.getCode(), staffRepository.findById(helper.getId()).orElseThrow().getDepartment().getCode());
        }
        assertEquals(2, supportRepository.findByStatus(SupportStatus.ACTIVE).stream().filter(r -> r.getReceivingDepartmentCode().equals(s.receiving.getCode())).count());
    }

    @Test
    @DisplayName("Existing assignment rules are untouched: the ordinary endpoint still refuses a cross-department assignment")
    void ordinaryAssignmentStillRequiresTheSameDepartment() {
        var s = fx.overloaded(2, 3);
        CreateAssignmentRequest request = new CreateAssignmentRequest();
        request.setTaskId(s.tasks.get(0).getId());
        request.setStaffId(s.donors.get(0).getId());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> assignmentService.createAssignment(request));

        assertTrue(ex.getReason().contains("does not belong to the task's department"));
    }

    // ── preview and workload numbers ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Preview shows the workload before and after for both departments, counts minutes once, and changes nothing")
    void previewShowsImpactAndChangesNothing() {
        var s = fx.overloaded(3, 3);     // waiting minutes: 40, 50, 60 -> 150
        long assignmentsBefore = assignmentRepository.count();

        Preview preview = service.preview(fx.plan(s.receiving, s.donors.get(0), s.tasks.get(0), s.donors.get(1), s.tasks.get(1)));

        assertTrue(preview.valid(), preview.problems().toString());
        DepartmentImpact r = preview.receiving();
        assertEquals(3, r.before().waitingTasks());
        assertEquals(150, r.before().waitingMinutes());
        assertEquals(40, r.before().assignedMinutes(), "the receiving department's own busy employee");
        assertEquals(0, r.before().freeStaff());
        assertEquals(3, r.before().uncoveredTasks());
        assertEquals(1, r.after().waitingTasks());
        assertEquals(60, r.after().waitingMinutes());
        assertEquals(40 + 40 + 50, r.after().assignedMinutes(), "the two supported tasks now count under the receiving department");
        assertEquals(1, r.after().uncoveredTasks());
        assertEquals(60, r.after().uncoveredMinutes());

        assertEquals(1, preview.donors().size());
        DepartmentImpact d = preview.donors().get(0);
        assertEquals("DONOR", d.role());
        assertEquals(3, d.before().freeStaff());
        assertEquals(1, d.after().freeStaff());
        assertEquals(0, d.before().assignedMinutes());
        assertEquals(0, d.after().assignedMinutes(), "lent minutes are not double counted in the donor department");
        assertEquals(1, d.reserveRequired());
        assertEquals(2, d.lending());
        assertNotNull(preview.fingerprint());
        assertEquals(assignmentsBefore, assignmentRepository.count(), "a preview creates nothing");
        assertTrue(supportRepository.findAll().stream().noneMatch(x -> x.getReceivingDepartmentCode().equals(s.receiving.getCode())));
    }

    @Test
    @DisplayName("After confirmation the workload shows the helper under the receiving department with the task minutes")
    void workloadReflectsConfirmedSupport() {
        var s = fx.overloaded(2, 3);
        Staff helper = s.donors.get(0);
        service.confirm(fx.plan(s.receiving, helper, s.tasks.get(0)));
        entityManager.flush();

        WorkloadDto dto = workloadService.calculateWorkload("manager_test", true).stream()
                .filter(w -> w.getStaffId().equals(helper.getId())).findFirst().orElseThrow();

        assertEquals(1, dto.getActiveTaskCount());
        assertEquals(40, dto.getTotalEstimatedDuration());
        assertEquals(s.donor.getCode(), dto.getDepartment(), "the home department is still reported as their department");
        assertEquals(s.receiving.getCode(), dto.getSupportDepartment());
        assertEquals(s.receiving.getName(), dto.getSupportDepartmentName());
        Options after = service.getOptions(s.receiving.getCode());
        assertEquals(1, after.receiving().metrics().waitingTasks());
        assertEquals(40 + 40, after.receiving().metrics().assignedMinutes());
        DonorDepartment donorAfter = after.donors().stream().filter(d -> d.code().equals(s.donor.getCode())).findFirst().orElseThrow();
        assertTrue(donorAfter.staff().stream().noneMatch(x -> x.staffId().equals(helper.getId())), "a busy helper is no longer offered");
    }

    @Test
    @DisplayName("Options list waiting tasks and the donors' eligible staff with how many each may lend")
    void optionsDescribeTheDepartmentsAndWhoCanHelp() {
        var s = fx.overloaded(3, 3);
        fx.person("Unverified Una", s.donor, Role.STAFF, false, true, true, Availability.AVAILABLE, true);   // free but not lendable

        Options options = service.getOptions(s.receiving.getCode());

        assertEquals(3, options.maxHelpers());
        assertEquals(3, options.tasks().size());
        assertEquals("Waiting 1", options.tasks().get(0).title(), "most important first (the urgent one)");
        DonorDepartment donor = options.donors().stream().filter(d -> d.code().equals(s.donor.getCode())).findFirst().orElseThrow();
        assertEquals(4, donor.freeStaff(), "the unverified person still counts as free capacity");
        assertEquals(3, donor.staff().size(), "but is not offered");
        assertEquals(3, donor.maxLendable(), "4 free, 0 waiting: may lend 3 and keep 1");
        assertTrue(donor.canLend());
        assertFalse(options.limitations().isEmpty());
        assertThrows(ResponseStatusException.class, () -> service.getOptions("NO_SUCH_DEPARTMENT"));
    }

    @Test
    @DisplayName("The options agree with the read-only recommendations for the same data")
    void optionsAgreeWithTheRecommendationNumbers() {
        var s = fx.overloaded(3, 3);
        Options options = service.getOptions(s.receiving.getCode());
        var summary = options.receiving();

        assertEquals(3, summary.metrics().waitingTasks());
        assertEquals(150, summary.metrics().waitingMinutes());
        assertEquals(1, summary.urgentOrHighTasks());
        assertEquals(3, summary.metrics().uncoveredTasks());
        assertEquals(com.hotel.workload.workload.WorkloadRecommendationsDto.Severity.CRITICAL, summary.severity());
    }

    // ── the donor reserve rule ───────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("A donor with only one free person cannot lend them")
    void donorWithOneFreePersonCannotLend() {
        var s = fx.overloaded(2, 1);

        Preview preview = service.preview(fx.plan(s.receiving, s.donors.get(0), s.tasks.get(0)));

        assertFalse(preview.valid());
        assertTrue(codes(preview).contains("DONOR_RESERVE"));
        SupportPlanException ex = assertThrows(SupportPlanException.class, () -> service.confirm(fx.plan(s.receiving, s.donors.get(0), s.tasks.get(0))));
        assertTrue(codesOf(ex).contains("DONOR_RESERVE"));
        assertEquals(0, supportRepository.findAll().stream().filter(x -> x.getReceivingDepartmentCode().equals(s.receiving.getCode())).count());
    }

    @Test
    @DisplayName("A donor with two free people can lend one and keep one, but not both")
    void donorWithTwoFreePeopleLendsOneNotBoth() {
        var s = fx.overloaded(2, 2);

        assertTrue(service.preview(fx.plan(s.receiving, s.donors.get(0), s.tasks.get(0))).valid());
        Preview both = service.preview(fx.plan(s.receiving, s.donors.get(0), s.tasks.get(0), s.donors.get(1), s.tasks.get(1)));

        assertFalse(both.valid());
        assertTrue(codes(both).contains("DONOR_RESERVE"));
        assertEquals(1, service.confirm(fx.plan(s.receiving, s.donors.get(0), s.tasks.get(0))).size());
    }

    @Test
    @DisplayName("The donor must still be able to cover its own waiting work after lending")
    void donorKeepsEnoughFreeStaffForItsOwnWaitingWork() {
        var s = fx.overloaded(3, 3);
        fx.waiting("Donor's own job 1", s.donor, Priority.HIGH, 30);
        fx.waiting("Donor's own job 2", s.donor, Priority.HIGH, 30);

        Preview twoLent = service.preview(fx.plan(s.receiving, s.donors.get(0), s.tasks.get(0), s.donors.get(1), s.tasks.get(1)));
        Preview oneLent = service.preview(fx.plan(s.receiving, s.donors.get(0), s.tasks.get(0)));

        assertFalse(twoLent.valid(), "3 free - 2 lent = 1, but 2 tasks are waiting in the donor");
        assertTrue(codes(twoLent).contains("DONOR_RESERVE"));
        assertTrue(oneLent.valid(), "3 free - 1 lent = 2, which covers its 2 waiting tasks");
        assertEquals(2, oneLent.donors().get(0).reserveRequired());
    }

    @Test
    @DisplayName("A department whose own free staff can take its waiting work does not need help")
    void receivingDepartmentMustActuallyNeedHelp() {
        var s = fx.overloaded(1, 3);
        fx.eligible("Receiving free staff", s.receiving);   // now the only waiting task is covered

        Preview preview = service.preview(fx.plan(s.receiving, s.donors.get(0), s.tasks.get(0)));

        assertFalse(preview.valid());
        assertTrue(codes(preview).contains("NOT_NEEDED"));
    }

    // ── who and what may be selected ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Inactive, unavailable, unverified, setup-pending, disabled, manager and busy staff are rejected, and nothing is created")
    void ineligibleStaffAreRejected() {
        var s = fx.overloaded(7, 3);
        Staff inactive = fx.person("Inactive", s.donor, Role.STAFF, true, false, true, Availability.AVAILABLE, false);
        Staff away = fx.person("Away", s.donor, Role.STAFF, true, false, true, Availability.UNAVAILABLE, true);
        Staff unverified = fx.person("Unverified", s.donor, Role.STAFF, false, true, true, Availability.AVAILABLE, true);
        Staff setupPending = fx.person("Setup pending", s.donor, Role.STAFF, true, true, true, Availability.AVAILABLE, true);
        Staff disabled = fx.person("Disabled account", s.donor, Role.STAFF, true, false, false, Availability.AVAILABLE, true);
        Staff manager = fx.person("Manager", s.donor, Role.MANAGER, true, false, true, Availability.AVAILABLE, true);
        Staff busy = fx.eligible("Busy", s.donor);
        fx.assign(fx.task("Busy job", s.donor, Priority.LOW, 20, TaskStatus.PENDING), busy);

        Object[][] cases = {
                {inactive, "STAFF_INACTIVE"}, {away, "STAFF_UNAVAILABLE"}, {unverified, "CANNOT_SIGN_IN"},
                {setupPending, "CANNOT_SIGN_IN"}, {disabled, "CANNOT_SIGN_IN"}, {manager, "NOT_STAFF_ROLE"}, {busy, "STAFF_BUSY"}};
        for (int i = 0; i < cases.length; i++) {
            Staff person = (Staff) cases[i][0];
            String expected = (String) cases[i][1];
            SupportPlanRequest plan = fx.plan(s.receiving, person, s.tasks.get(i));
            Preview preview = service.preview(plan);
            assertFalse(preview.valid(), person.getName());
            assertTrue(codes(preview).contains(expected), person.getName() + " -> " + codes(preview));
            SupportPlanException ex = assertThrows(SupportPlanException.class, () -> service.confirm(plan), person.getName());
            assertEquals(409, ex.getStatus().value());
        }
        assertEquals(0, supportRepository.findAll().stream().filter(x -> x.getReceivingDepartmentCode().equals(s.receiving.getCode())).count());
        assertTrue(s.tasks.stream().allMatch(t -> taskRepository.findById(t.getId()).orElseThrow().getStatus() == TaskStatus.PENDING));
    }

    @Test
    @DisplayName("Already-assigned, completed, cancelled and other-department tasks, and duplicates, are rejected")
    void ineligibleTasksAndDuplicatesAreRejected() {
        var s = fx.overloaded(2, 5);
        Task alreadyAssigned = fx.waiting("Taken", s.receiving, Priority.MEDIUM, 20);
        fx.assign(alreadyAssigned, fx.eligible("Receiving worker", s.receiving));
        Task completed = fx.task("Done", s.receiving, Priority.MEDIUM, 20, TaskStatus.COMPLETED);
        Task cancelled = fx.task("Dropped", s.receiving, Priority.MEDIUM, 20, TaskStatus.CANCELLED);
        Task elsewhere = fx.waiting("Donor's task", s.donor, Priority.MEDIUM, 20);
        Staff a = s.donors.get(0), b = s.donors.get(1);

        assertTrue(codes(service.preview(fx.plan(s.receiving, a, alreadyAssigned))).contains("TASK_ALREADY_ASSIGNED"));
        assertTrue(codes(service.preview(fx.plan(s.receiving, a, completed))).contains("TASK_FINISHED"));
        assertTrue(codes(service.preview(fx.plan(s.receiving, a, cancelled))).contains("TASK_FINISHED"));
        assertTrue(codes(service.preview(fx.plan(s.receiving, a, elsewhere))).contains("TASK_WRONG_DEPARTMENT"));
        assertTrue(codes(service.preview(fx.plan(s.receiving, a, s.tasks.get(0), a, s.tasks.get(1)))).contains("DUPLICATE_STAFF"));
        assertTrue(codes(service.preview(fx.plan(s.receiving, a, s.tasks.get(0), b, s.tasks.get(0)))).contains("DUPLICATE_TASK"));
        Staff sameDepartment = fx.eligible("Colleague", s.receiving);
        assertTrue(codes(service.preview(fx.plan(s.receiving, sameDepartment, s.tasks.get(0)))).contains("SAME_DEPARTMENT"));
    }

    // ── duplicates, stale previews ───────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Confirming the same plan twice cannot create duplicate or conflicting assignments")
    void duplicateConfirmationIsRejected() {
        var s = fx.overloaded(3, 4);
        SupportPlanRequest plan = fx.plan(s.receiving, s.donors.get(0), s.tasks.get(0));
        service.confirm(plan);
        entityManager.flush();

        SupportPlanException ex = assertThrows(SupportPlanException.class, () -> service.confirm(plan));

        assertTrue(codesOf(ex).containsAll(Set.of("STAFF_BUSY", "TASK_ALREADY_ASSIGNED")), codesOf(ex).toString());
        assertEquals(1, assignmentRepository.findAllByTaskId(s.tasks.get(0).getId()).size());
        assertEquals(1, supportRepository.findAll().stream().filter(x -> x.getTaskId().equals(s.tasks.get(0).getId())).count());
    }

    @Test
    @DisplayName("A preview that went stale is refused at confirmation, with a clear message")
    void stalePreviewIsRefused() {
        var s = fx.overloaded(3, 4);
        SupportPlanRequest plan = fx.plan(s.receiving, s.donors.get(0), s.tasks.get(0));
        Preview preview = service.preview(plan);
        assertTrue(preview.valid());

        // Meanwhile someone changes a staff member the plan depends on
        Staff helper = staffRepository.findById(s.donors.get(0).getId()).orElseThrow();
        helper.setAvailability(Availability.UNAVAILABLE);
        staffRepository.saveAndFlush(helper);
        plan.setFingerprint(preview.fingerprint());

        SupportPlanException ex = assertThrows(SupportPlanException.class, () -> service.confirm(plan));

        assertEquals(409, ex.getStatus().value());
        assertTrue(ex.getMessage().contains("out of date") || ex.getMessage().contains("cannot be confirmed"), ex.getMessage());
        assertEquals(0, supportRepository.findAll().stream().filter(x -> x.getTaskId().equals(s.tasks.get(0).getId())).count());
    }

    @Test
    @DisplayName("A matching fingerprint confirms; a changed task invalidates it")
    void fingerprintMatchesWhileNothingChanges() {
        var s = fx.overloaded(3, 4);
        SupportPlanRequest plan = fx.plan(s.receiving, s.donors.get(0), s.tasks.get(0));
        plan.setFingerprint(service.preview(plan).fingerprint());
        assertEquals(plan.getFingerprint(), service.preview(plan).fingerprint(), "stable while nothing changes");

        assertEquals(1, service.confirm(plan).size());
    }

    // ── completion ───────────────────────────────────────────────────────────────────────────────────────────

    private UpdateTaskRequest update(Task task, TaskStatus status) {
        UpdateTaskRequest request = new UpdateTaskRequest();
        request.setTitle(task.getTitle());
        request.setPriority(task.getPriority());
        request.setEstimatedDuration(task.getEstimatedDuration());
        request.setDueDate(task.getDueDate());
        request.setDepartment(task.getDepartment().getCode());
        request.setStatus(status);
        return request;
    }

    @Test
    @DisplayName("Completing the supported task completes the support record and frees the person, home department unchanged")
    void completingTheTaskCompletesTheSupport() {
        var s = fx.overloaded(2, 3);
        Staff helper = s.donors.get(0);
        Task task = s.tasks.get(0);
        service.confirm(fx.plan(s.receiving, helper, task));
        entityManager.flush();

        taskService.updateTask(task.getId(), update(task, TaskStatus.COMPLETED));
        entityManager.flush();
        entityManager.clear();

        SupportAssignment record = supportRepository.findAll().stream().filter(x -> x.getTaskId().equals(task.getId())).findFirst().orElseThrow();
        assertEquals(SupportStatus.COMPLETED, record.getStatus());
        assertEquals(SupportEndReason.TASK_COMPLETED, record.getEndReason());
        assertNotNull(record.getCompletedAt());
        assertEquals(s.donor.getCode(), staffRepository.findById(helper.getId()).orElseThrow().getDepartment().getCode());
        assertEquals(1, service.list(SupportStatus.COMPLETED).stream().filter(x -> x.taskId().equals(task.getId())).count());
        assertEquals(0, service.list(SupportStatus.ACTIVE).stream().filter(x -> x.taskId().equals(task.getId())).count());
        WorkloadDto dto = workloadService.calculateWorkload("manager_test", true).stream().filter(w -> w.getStaffId().equals(helper.getId())).findFirst().orElseThrow();
        assertNull(dto.getSupportDepartment());
        assertEquals(0, dto.getActiveTaskCount());
    }

    @Test
    @DisplayName("Cancelling, deleting or reassigning the supported task ends the support with the right reason")
    void otherWaysSupportEnds() {
        var s = fx.overloaded(4, 4);
        Task cancelledTask = s.tasks.get(0), deletedTask = s.tasks.get(1), reassignedTask = s.tasks.get(2);
        service.confirm(fx.plan(s.receiving, s.donors.get(0), cancelledTask, s.donors.get(1), deletedTask));
        entityManager.flush();
        Staff ownStaff = fx.eligible("Receiving helper", s.receiving);
        Task third = reassignedTask;
        // The second plan is allowed because the donor still has a free person left
        service.confirm(fx.plan(s.receiving, s.donors.get(2), third));
        entityManager.flush();

        taskService.updateTask(cancelledTask.getId(), update(cancelledTask, TaskStatus.CANCELLED));
        taskService.deleteTask(deletedTask.getId());
        Assignment assignment = assignmentRepository.findByTaskId(third.getId()).orElseThrow();
        UpdateAssignmentRequest reassign = new UpdateAssignmentRequest();
        reassign.setStaffId(ownStaff.getId());
        assignmentService.reassignTask(assignment.getId(), reassign);
        entityManager.flush();
        entityManager.clear();

        java.util.Map<java.util.UUID, SupportAssignment> byTask = supportRepository.findAll().stream()
                .filter(x -> x.getReceivingDepartmentCode().equals(s.receiving.getCode()))
                .collect(Collectors.toMap(SupportAssignment::getTaskId, x -> x));
        assertEquals(SupportEndReason.TASK_CANCELLED, byTask.get(cancelledTask.getId()).getEndReason());
        assertEquals(SupportStatus.CANCELLED, byTask.get(cancelledTask.getId()).getStatus());
        assertEquals(SupportEndReason.TASK_DELETED, byTask.get(deletedTask.getId()).getEndReason());
        assertEquals(SupportEndReason.TASK_REASSIGNED, byTask.get(reassignedTask.getId()).getEndReason());
        for (Staff donor : s.donors) {
            assertEquals(s.donor.getCode(), staffRepository.findById(donor.getId()).orElseThrow().getDepartment().getCode());
        }
    }

    @Test
    @DisplayName("Editing other fields of a supported task does not end the support")
    void editingASupportedTaskKeepsTheSupport() {
        var s = fx.overloaded(2, 3);
        Task task = s.tasks.get(0);
        service.confirm(fx.plan(s.receiving, s.donors.get(0), task));
        entityManager.flush();
        UpdateTaskRequest edit = update(task, TaskStatus.IN_PROGRESS);
        edit.setTitle("Renamed while supported");

        taskService.updateTask(task.getId(), edit);
        entityManager.flush();

        assertEquals(SupportStatus.ACTIVE, supportRepository.findByTaskIdAndStatus(task.getId(), SupportStatus.ACTIVE).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("Request problems are all reported together")
    void allProblemsAreReported() {
        var s = fx.overloaded(2, 3);
        Staff away = fx.person("Away", s.donor, Role.STAFF, true, false, true, Availability.UNAVAILABLE, true);
        Task done = fx.task("Done", s.receiving, Priority.LOW, 10, TaskStatus.COMPLETED);

        Preview preview = service.preview(fx.plan(s.receiving, away, done));

        assertTrue(codes(preview).containsAll(Set.of("STAFF_UNAVAILABLE", "TASK_FINISHED")), codes(preview).toString());
        assertTrue(preview.items().isEmpty());
    }
}
