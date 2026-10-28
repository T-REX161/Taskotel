package com.hotel.workload.support;

import com.hotel.workload.assignment.AssignmentRepository;
import com.hotel.workload.security.UserRepository;
import com.hotel.workload.staff.DepartmentRepository;
import com.hotel.workload.staff.Staff;
import com.hotel.workload.staff.StaffRepository;
import com.hotel.workload.support.SupportDtos.SupportAssignmentDto;
import com.hotel.workload.task.Task;
import com.hotel.workload.task.TaskRepository;
import com.hotel.workload.task.TaskStatus;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Removing an ended record from the history: it is archived (kept, no longer listed), and nothing else is touched. */
@SpringBootTest
@Transactional
public class SupportArchiveTest {

    @Autowired private SupportAssignmentService service;
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

    /** Ends the support the way a manager does (the task goes back to waiting). */
    private void endByHand() {
        service.end(support.id());
        entityManager.flush();
        entityManager.clear();
    }

    /** Lets the supported task finish, so the support closes as completed and its assignment stays as history. */
    private void completeTheTask() {
        Task live = taskRepository.findById(task.getId()).orElseThrow();
        live.setStatus(TaskStatus.COMPLETED);
        taskRepository.saveAndFlush(live);
        entityManager.clear();
        service.list(null);          // the history read closes finished support
        entityManager.flush();
        entityManager.clear();
    }

    private boolean listed(UUID id) {
        return service.list(null).stream().anyMatch(r -> r.id().equals(id));
    }

    private SupportPlanException conflict(UUID id) {
        return assertThrows(SupportPlanException.class, () -> service.archive(id));
    }

    // ── success ──────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("An ended record is archived: it is no longer listed, but the row, its names and its dates stay in the database")
    void endedRecordIsArchivedAndKept() {
        endByHand();
        assertTrue(listed(support.id()));

        SupportAssignmentDto archived = service.archive(support.id());
        entityManager.flush();
        entityManager.clear();

        assertNotNull(archived.archivedAt());
        assertFalse(listed(support.id()), "gone from the history list");
        assertTrue(service.list(SupportStatus.CANCELLED).stream().noneMatch(r -> r.id().equals(support.id())), "and from the filtered list");
        SupportAssignment stored = supportRepository.findById(support.id()).orElseThrow();
        assertNotNull(stored.getArchivedAt(), "but the record is still there");
        assertEquals("manager_test", stored.getArchivedByUsername(), "with who removed it");
        assertEquals(support.staffName(), stored.getStaffName());
        assertEquals(support.taskTitle(), stored.getTaskTitle());
        assertEquals(SupportStatus.CANCELLED, stored.getStatus(), "its status is untouched");
        assertEquals(SupportEndReason.MANUALLY_ENDED, stored.getEndReason());
        assertNotNull(stored.getCompletedAt());
        assertEquals(support.createdAt().truncatedTo(java.time.temporal.ChronoUnit.MILLIS), stored.getCreatedAt().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
    }

    @Test
    @DisplayName("A completed record can be archived too, and its task and its assignment history are left exactly as they were")
    void completedRecordKeepsTaskAndAssignment() {
        completeTheTask();
        assertEquals(SupportStatus.COMPLETED, supportRepository.findById(support.id()).orElseThrow().getStatus());
        assertTrue(assignmentRepository.existsByTaskId(task.getId()), "the finished task's assignment is history");

        service.archive(support.id());
        entityManager.flush();
        entityManager.clear();

        assertTrue(assignmentRepository.existsByTaskId(task.getId()), "the assignment is not deleted");
        assertEquals(TaskStatus.COMPLETED, taskRepository.findById(task.getId()).orElseThrow().getStatus(), "the task is not touched");
        assertEquals(s.donor.getCode(), staffRepository.findById(helper.getId()).orElseThrow().getDepartment().getCode(), "the permanent department is unchanged");
        assertTrue(staffRepository.existsById(helper.getId()));
        assertNotNull(supportRepository.findById(support.id()).orElseThrow().getArchivedAt());
    }

    @Test
    @DisplayName("Archiving one record leaves every other record, task and assignment alone")
    void otherRecordsAreUntouched() {
        // a second, independent scenario (its own fixture, so its departments have their own codes)
        SupportTestFixture fx2 = new SupportTestFixture(users, staffRepository, taskRepository, assignmentRepository, departmentRepository, supportRepository);
        var other = fx2.overloaded(2, 3);
        SupportAssignmentDto otherSupport = service.confirm(fx2.plan(other.receiving, other.donors.get(0), other.tasks.get(0))).get(0);
        entityManager.flush();
        entityManager.clear();
        endByHand();
        long recordsBefore = supportRepository.count();

        service.archive(support.id());
        entityManager.flush();
        entityManager.clear();

        assertEquals(recordsBefore, supportRepository.count(), "nothing is deleted");
        assertEquals(SupportStatus.ACTIVE, supportRepository.findById(otherSupport.id()).orElseThrow().getStatus());
        assertNull(supportRepository.findById(otherSupport.id()).orElseThrow().getArchivedAt());
        assertTrue(assignmentRepository.existsByTaskId(other.tasks.get(0).getId()));
        assertEquals(TaskStatus.PENDING, taskRepository.findById(task.getId()).orElseThrow().getStatus(), "the manually ended task stays waiting");
        assertTrue(listed(otherSupport.id()));
    }

    // ── protection ───────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Active support cannot be removed: it must be ended through End Support first, and nothing changes")
    void activeRecordIsProtected() {
        SupportPlanException e = conflict(support.id());

        assertEquals(409, e.getStatus().value());
        assertEquals("SUPPORT_ACTIVE", e.getProblems().get(0).code());
        assertTrue(e.getMessage().contains("End it first"));
        entityManager.clear();
        SupportAssignment stored = supportRepository.findById(support.id()).orElseThrow();
        assertEquals(SupportStatus.ACTIVE, stored.getStatus());
        assertNull(stored.getArchivedAt());
        assertTrue(assignmentRepository.existsByTaskId(task.getId()));
    }

    @Test
    @DisplayName("After End Support the same record can then be removed")
    void endThenRemove() {
        conflict(support.id());
        endByHand();
        assertNotNull(service.archive(support.id()).archivedAt());
    }

    @Test
    @DisplayName("Removing the same record twice is a 409 that keeps the first removal time")
    void secondRemovalIsRefused() {
        endByHand();
        SupportAssignmentDto first = service.archive(support.id());
        entityManager.flush();
        entityManager.clear();

        SupportPlanException e = conflict(support.id());

        assertEquals("ALREADY_ARCHIVED", e.getProblems().get(0).code());
        assertEquals(first.archivedAt().truncatedTo(java.time.temporal.ChronoUnit.MILLIS),
                supportRepository.findById(support.id()).orElseThrow().getArchivedAt().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
    }

    @Test
    @DisplayName("An unknown id is a coded 404")
    void unknownIdIsNotFound() {
        SupportPlanException e = conflict(UUID.randomUUID());
        assertEquals(404, e.getStatus().value());
        assertEquals("SUPPORT_NOT_FOUND", e.getProblems().get(0).code());
    }

    @Test
    @DisplayName("Without a signed-in user the removal is refused and changes nothing")
    void requiresASignedInUser() {
        endByHand();
        SupportTestFixture.signOut();
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> service.archive(support.id()));
        entityManager.clear();
        assertNull(supportRepository.findById(support.id()).orElseThrow().getArchivedAt());
    }

    @Test
    @DisplayName("Archiving does not change what the active-support views see, and the history reads still work")
    void activeViewsAreUnaffected() {
        endByHand();
        service.archive(support.id());
        entityManager.flush();
        entityManager.clear();

        assertEquals(0, service.list(SupportStatus.ACTIVE).stream().filter(r -> r.id().equals(support.id())).count());
        assertDoesNotThrow(() -> service.getOptions(s.receiving.getCode()));
    }
}
