package com.hotel.workload.support;

import com.hotel.workload.assignment.Assignment;
import com.hotel.workload.assignment.AssignmentRepository;
import com.hotel.workload.security.UserRepository;
import com.hotel.workload.staff.DepartmentRepository;
import com.hotel.workload.staff.Staff;
import com.hotel.workload.staff.StaffRepository;
import com.hotel.workload.support.SupportDtos.*;
import com.hotel.workload.task.Task;
import com.hotel.workload.task.TaskRepository;
import com.hotel.workload.task.TaskStatus;
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

import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Ending an active support by hand: what it changes, what it keeps, and what it refuses. */
@SpringBootTest
@Transactional
public class SupportEndServiceTest {

    @Autowired private SupportAssignmentService service;
    @Autowired private WorkloadService workloadService;
    @Autowired private UserRepository users;
    @Autowired private StaffRepository staffRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private AssignmentRepository assignmentRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private SupportAssignmentRepository supportRepository;
    @Autowired private EntityManager entityManager;

    private SupportTestFixture fx;
    private SupportTestFixture.Scenario s;
    private Staff helper;
    private Task task;
    private SupportAssignmentDto support;

    @BeforeEach
    void setUp() {
        fx = new SupportTestFixture(users, staffRepository, taskRepository, assignmentRepository, departmentRepository, supportRepository);
        SupportTestFixture.signInAs("manager_test", "MANAGER");
        s = fx.overloaded(2, 3);
        helper = s.donors.get(0);
        task = s.tasks.get(0);
        support = service.confirm(fx.plan(s.receiving, helper, task)).get(0);
        entityManager.flush();
        entityManager.clear();
    }

    @AfterEach
    void tearDown() {
        SupportTestFixture.signOut();
    }

    private WorkloadDto workloadOf(Staff person) {
        return workloadService.calculateWorkload("manager_test", true).stream().filter(w -> w.getStaffId().equals(person.getId())).findFirst().orElseThrow();
    }

    private SupportPlanException conflict(UUID id) {
        return assertThrows(SupportPlanException.class, () -> service.end(id));
    }

    // ── success ──────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Ending an assigned support records the end time and reason, releases the assignment and puts the task back in the waiting list")
    void endsAnAssignedSupport() {
        SupportAssignmentDto ended = service.end(support.id());
        entityManager.flush();
        entityManager.clear();

        assertEquals(SupportStatus.CANCELLED, ended.status());
        assertEquals(SupportEndReason.MANUALLY_ENDED, ended.endReason());
        assertNotNull(ended.completedAt(), "the end is timestamped");
        assertEquals(TaskStatus.PENDING, ended.taskStatus());

        SupportAssignment stored = supportRepository.findById(support.id()).orElseThrow();
        assertEquals(SupportStatus.CANCELLED, stored.getStatus());
        assertEquals(SupportEndReason.MANUALLY_ENDED, stored.getEndReason());
        assertNotNull(stored.getCompletedAt());

        assertFalse(assignmentRepository.existsByTaskId(task.getId()), "no assignment is left behind that could still count as support");
        assertEquals(TaskStatus.PENDING, taskRepository.findById(task.getId()).orElseThrow().getStatus(), "the task is not completed, just unassigned");
    }

    @Test
    @DisplayName("The helper's permanent department never changes")
    void homeDepartmentIsUnchanged() {
        service.end(support.id());
        entityManager.flush();
        entityManager.clear();

        assertEquals(s.donor.getCode(), staffRepository.findById(helper.getId()).orElseThrow().getDepartment().getCode());
        assertEquals(s.donor.getCode(), supportRepository.findById(support.id()).orElseThrow().getHomeDepartmentCode());
    }

    // ── history ──────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("The ended record stays in the support history with its names, plan, approver and start time intact")
    void historyIsPreserved() {
        SupportAssignmentDto ended = service.end(support.id());
        entityManager.flush();
        entityManager.clear();

        assertTrue(service.list(SupportStatus.ACTIVE).stream().noneMatch(r -> r.id().equals(support.id())));
        SupportAssignmentDto inHistory = service.list(null).stream().filter(r -> r.id().equals(support.id())).findFirst().orElseThrow();
        assertEquals(SupportStatus.CANCELLED, inHistory.status());
        assertEquals(support.staffName(), inHistory.staffName());
        assertEquals(support.taskTitle(), inHistory.taskTitle());
        assertEquals(support.planId(), inHistory.planId());
        assertEquals(support.approvedBy(), inHistory.approvedBy());
        assertEquals(support.createdAt().truncatedTo(ChronoUnit.MILLIS), inHistory.createdAt().truncatedTo(ChronoUnit.MILLIS));
        assertEquals(support.assignmentId(), inHistory.assignmentId(), "the original assignment id stays recorded");
        assertEquals(ended.completedAt().truncatedTo(ChronoUnit.MILLIS), inHistory.completedAt().truncatedTo(ChronoUnit.MILLIS));
        assertTrue(service.list(SupportStatus.CANCELLED).stream().anyMatch(r -> r.id().equals(support.id())));
    }

    @Test
    @DisplayName("An active record in the history reports its task's current status, so the screen can explain what ending does")
    void activeRecordsReportTheTaskStatus() {
        assertEquals(TaskStatus.ASSIGNED, service.list(SupportStatus.ACTIVE).stream().filter(r -> r.id().equals(support.id())).findFirst().orElseThrow().taskStatus());
    }

    // ── workload attribution, reserve and eligibility ────────────────────────────────────────────────────────

    @Test
    @DisplayName("Workload is recalculated: the helper is free again and no longer shown on support, and the task waits in its own department")
    void workloadIsRecalculated() {
        WorkloadDto before = workloadOf(helper);
        assertEquals(1, before.getActiveTaskCount());
        assertEquals(s.receiving.getCode(), before.getSupportDepartment());
        int receivingWaitingBefore = service.getOptions(s.receiving.getCode()).receiving().metrics().waitingTasks();

        service.end(support.id());
        entityManager.flush();
        entityManager.clear();

        WorkloadDto after = workloadOf(helper);
        assertEquals(0, after.getActiveTaskCount());
        assertEquals(0, after.getTotalEstimatedDuration());
        assertNull(after.getSupportDepartment(), "no longer attributed to the receiving department");
        assertEquals(s.donor.getCode(), after.getDepartment());

        Options options = service.getOptions(s.receiving.getCode());
        assertEquals(receivingWaitingBefore + 1, options.receiving().metrics().waitingTasks(), "the task is waiting again");
        assertEquals(40, options.receiving().metrics().assignedMinutes(), "only the department's own busy person remains in its assigned minutes");
        DonorDepartment donor = options.donors().stream().filter(d -> d.code().equals(s.donor.getCode())).findFirst().orElseThrow();
        assertEquals(3, donor.freeStaff(), "the donor has all its free staff back");
        assertTrue(donor.staff().stream().anyMatch(x -> x.staffId().equals(helper.getId())), "the helper can be lent again");
    }

    @Test
    @DisplayName("After ending, the same plan is valid again and can be confirmed, because support is unique per active record, not per task forever")
    void theTaskCanBeSupportedAgain() {
        service.end(support.id());
        entityManager.flush();
        entityManager.clear();

        assertTrue(service.preview(fx.plan(s.receiving, helper, task)).valid(), "donor reserve and eligibility are recalculated");
        List<SupportAssignmentDto> again = service.confirm(fx.plan(s.receiving, helper, task));
        assertEquals(1, again.size());
        assertNotEquals(support.id(), again.get(0).id(), "a new record; the ended one stays as history");
        assertEquals(2, supportRepository.findAll().stream().filter(r -> r.getTaskId().equals(task.getId())).count());
    }

    // ── invalid transitions ──────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Ending the same support twice is a 409 and does not change the first end time")
    void secondEndIsRefused() {
        SupportAssignmentDto first = service.end(support.id());
        entityManager.flush();
        entityManager.clear();

        SupportPlanException e = conflict(support.id());
        assertEquals(409, e.getStatus().value());
        assertEquals("ALREADY_ENDED", e.getProblems().get(0).code());
        assertEquals(first.completedAt().truncatedTo(ChronoUnit.MILLIS),
                supportRepository.findById(support.id()).orElseThrow().getCompletedAt().truncatedTo(ChronoUnit.MILLIS));
    }

    @Test
    @DisplayName("A support whose task is already in progress is refused and nothing changes")
    void inProgressIsRefused() {
        Task live = taskRepository.findById(task.getId()).orElseThrow();
        live.setStatus(TaskStatus.IN_PROGRESS);
        taskRepository.saveAndFlush(live);
        entityManager.clear();

        SupportPlanException e = conflict(support.id());
        assertEquals("TASK_IN_PROGRESS", e.getProblems().get(0).code());
        assertTrue(e.getMessage().contains("in progress"));
        entityManager.clear();
        assertEquals(SupportStatus.ACTIVE, supportRepository.findById(support.id()).orElseThrow().getStatus());
        assertTrue(assignmentRepository.existsByTaskId(task.getId()), "the assignment is kept");
        assertEquals(TaskStatus.IN_PROGRESS, taskRepository.findById(task.getId()).orElseThrow().getStatus(), "never silently reset or completed");
    }

    @Test
    @DisplayName("An unknown support id is a coded 404 (SUPPORT_NOT_FOUND), distinguishable from a missing route")
    void unknownIdIsNotFound() {
        SupportPlanException e = assertThrows(SupportPlanException.class, () -> service.end(UUID.randomUUID()));
        assertEquals(404, e.getStatus().value());
        assertEquals("SUPPORT_NOT_FOUND", e.getProblems().get(0).code());
    }

    @Test
    @DisplayName("Stale record: if the task was completed meanwhile, the support records that, not a manual end, and the task stays completed")
    void taskCompletedMeanwhile() {
        Task live = taskRepository.findById(task.getId()).orElseThrow();
        live.setStatus(TaskStatus.COMPLETED);
        taskRepository.saveAndFlush(live);
        entityManager.clear();

        SupportAssignmentDto result = service.end(support.id());

        assertEquals(SupportStatus.COMPLETED, result.status());
        assertEquals(SupportEndReason.TASK_COMPLETED, result.endReason());
        entityManager.flush();
        entityManager.clear();
        assertEquals(TaskStatus.COMPLETED, taskRepository.findById(task.getId()).orElseThrow().getStatus());
        assertTrue(assignmentRepository.existsByTaskId(task.getId()), "completed task history is not deleted");
    }

    @Test
    @DisplayName("Stale record: if the task is held by someone else now, the support ends as reassigned and their assignment is untouched")
    void taskHeldBySomeoneElse() {
        Staff local = fx.eligible("Local Lena", s.receiving);
        Assignment held = assignmentRepository.findByTaskId(task.getId()).orElseThrow();
        held.setStaff(local);
        assignmentRepository.saveAndFlush(held);
        entityManager.clear();

        SupportAssignmentDto result = service.end(support.id());

        assertEquals(SupportEndReason.TASK_REASSIGNED, result.endReason());
        entityManager.flush();
        entityManager.clear();
        Assignment still = assignmentRepository.findByTaskId(task.getId()).orElseThrow();
        assertEquals(local.getId(), still.getStaff().getId());
        assertEquals(TaskStatus.ASSIGNED, taskRepository.findById(task.getId()).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("Stale record: if the task was deleted meanwhile, the support ends as task deleted")
    void taskDeletedMeanwhile() {
        assignmentRepository.deleteAll(assignmentRepository.findAllByTaskId(task.getId()));
        taskRepository.deleteById(task.getId());
        taskRepository.flush();
        entityManager.clear();

        SupportAssignmentDto result = service.end(support.id());

        assertEquals(SupportEndReason.TASK_DELETED, result.endReason());
        assertNull(result.taskStatus());
    }

    @Test
    @DisplayName("Other support records are not touched when one is ended")
    void otherSupportIsUntouched() {
        Staff second = s.donors.get(1);
        Task secondTask = s.tasks.get(1);
        SupportAssignmentDto other = service.confirm(fx.plan(s.receiving, second, secondTask)).get(0);
        entityManager.flush();
        entityManager.clear();

        service.end(support.id());
        entityManager.flush();
        entityManager.clear();

        assertEquals(SupportStatus.ACTIVE, supportRepository.findById(other.id()).orElseThrow().getStatus());
        assertTrue(assignmentRepository.existsByTaskId(secondTask.getId()));
        assertEquals(TaskStatus.ASSIGNED, taskRepository.findById(secondTask.getId()).orElseThrow().getStatus());
    }
}
