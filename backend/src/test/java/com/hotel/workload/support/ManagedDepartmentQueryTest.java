package com.hotel.workload.support;

import com.hotel.workload.assignment.AssignmentRepository;
import com.hotel.workload.security.UserRepository;
import com.hotel.workload.staff.Department;
import com.hotel.workload.staff.DepartmentRepository;
import com.hotel.workload.staff.Staff;
import com.hotel.workload.staff.StaffRepository;
import com.hotel.workload.task.Priority;
import com.hotel.workload.task.Task;
import com.hotel.workload.task.TaskRepository;
import com.hotel.workload.task.TaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2 of the multi-department Manager change: the department-set queries that replace the single-department ones on the Manager paths.
 * With a set holding one department they must return exactly what the single-department query returns (so P2 changes nothing), and with
 * any set they must never return a record of a department outside it.
 */
@SpringBootTest
@Transactional
public class ManagedDepartmentQueryTest {

    @Autowired private UserRepository users;
    @Autowired private StaffRepository staffRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private AssignmentRepository assignmentRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private SupportAssignmentRepository supportRepository;

    private SupportTestFixture fx;
    private Department a, b, c;
    private Task taskA, taskB, taskNone;
    private Staff staffA, staffB;
    private SupportAssignment ab, ba, cc, archived;

    @BeforeEach
    void setUp() {
        fx = new SupportTestFixture(users, staffRepository, taskRepository, assignmentRepository, departmentRepository, supportRepository);
        a = fx.department("A");
        b = fx.department("B");
        c = fx.department("C");
        taskA = fx.task("A task", a, Priority.MEDIUM, 30, TaskStatus.PENDING);
        taskB = fx.task("B task", b, Priority.MEDIUM, 30, TaskStatus.PENDING);
        taskNone = fx.task("No department task", null, Priority.LOW, 10, TaskStatus.PENDING);
        staffA = fx.eligible("Alma A", a);
        staffB = fx.eligible("Bert B", b);
        ab = record(staffA, a, b, SupportStatus.ACTIVE, 3);   // A lends to B
        ba = record(staffB, b, a, SupportStatus.COMPLETED, 2); // B lends to A
        cc = record(fx.eligible("Cleo C", c), c, c, SupportStatus.COMPLETED, 1); // involves only C
        archived = record(fx.eligible("Ana A", a), a, c, SupportStatus.COMPLETED, 4);
        archived.archive("someone", LocalDateTime.now());
        supportRepository.saveAndFlush(archived);
    }

    private SupportAssignment record(Staff person, Department home, Department receiving, SupportStatus status, int hoursAgo) {
        SupportAssignment s = new SupportAssignment();
        s.setPlanId(UUID.randomUUID());
        s.setStaffId(person.getId());
        s.setStaffName(person.getName());
        s.setHomeDepartmentCode(home.getCode());
        s.setReceivingDepartmentCode(receiving.getCode());
        s.setTaskId(taskB.getId());
        s.setTaskTitle("t");
        s.setApprovedByUsername("approver");
        s.setCreatedAt(LocalDateTime.now().minusHours(hoursAgo));
        if (status != SupportStatus.ACTIVE) s.end(status, SupportEndReason.TASK_COMPLETED, LocalDateTime.now());
        return supportRepository.saveAndFlush(s);
    }

    private static Set<UUID> ids(List<SupportAssignment> rows) {
        return rows.stream().map(SupportAssignment::getId).collect(Collectors.toSet());
    }

    @Test
    @DisplayName("Task and staff locks: one department = the single-department lock; never a record of another department or none")
    void locks() {
        for (Task t : List.of(taskA, taskB, taskNone)) {
            assertEquals(taskRepository.lockByIdAndDepartment(t.getId(), a), taskRepository.lockByIdAndDepartmentIn(t.getId(), List.of(a)), t.getTitle());
        }
        for (Staff s : List.of(staffA, staffB)) {
            assertEquals(staffRepository.lockByIdAndDepartment(s.getId(), a), staffRepository.lockByIdAndDepartmentIn(s.getId(), List.of(a)), s.getName());
        }
        assertTrue(taskRepository.lockByIdAndDepartmentIn(taskA.getId(), List.of(a)).isPresent());
        assertTrue(taskRepository.lockByIdAndDepartmentIn(taskB.getId(), List.of(a)).isEmpty(), "another department's task");
        assertTrue(taskRepository.lockByIdAndDepartmentIn(taskNone.getId(), List.of(a, b, c)).isEmpty(), "a task with no department");
        assertTrue(taskRepository.lockByIdAndDepartmentIn(UUID.randomUUID(), List.of(a)).isEmpty(), "no such task");
        assertTrue(taskRepository.lockByIdAndDepartmentIn(taskB.getId(), List.of(a, b)).isPresent(), "any department of the set");
        assertTrue(staffRepository.lockByIdAndDepartmentIn(staffB.getId(), List.of(a)).isEmpty());
        assertTrue(staffRepository.lockByIdAndDepartmentIn(staffB.getId(), List.of(a, b)).isPresent());
        assertTrue(staffRepository.lockByIdAndDepartmentIn(UUID.randomUUID(), List.of(a, b)).isEmpty());
    }

    @Test
    @DisplayName("Support queries: one department = the single-department query (same rows, same order); a set never reaches outside it")
    void supportQueries() {
        for (Department d : List.of(a, b, c)) {
            String code = d.getCode();
            assertEquals(supportRepository.findHistoryInvolvingDepartment(code), supportRepository.findHistoryInvolvingDepartments(List.of(code)), code);
            assertEquals(supportRepository.findHistoryInvolvingDepartmentAndStatus(code, SupportStatus.COMPLETED),
                    supportRepository.findHistoryInvolvingDepartmentsAndStatus(List.of(code), SupportStatus.COMPLETED), code);
            assertEquals(ids(supportRepository.findByStatusInvolvingDepartment(SupportStatus.ACTIVE, code)),
                    ids(supportRepository.findByStatusInvolvingDepartments(SupportStatus.ACTIVE, List.of(code))), code);
            for (SupportAssignment s : List.of(ab, ba, cc, archived)) {
                assertEquals(supportRepository.findTaskIdByIdInvolvingDepartment(s.getId(), code),
                        supportRepository.findTaskIdByIdInvolvingDepartments(s.getId(), List.of(code)), code);
                assertEquals(supportRepository.lockByIdInvolvingDepartment(s.getId(), code).map(SupportAssignment::getId),
                        supportRepository.lockByIdInvolvingDepartments(s.getId(), List.of(code)).map(SupportAssignment::getId), code);
            }
        }
        // A set: every record involving any of its departments, newest first, archived ones left out; nothing involving only C
        List<SupportAssignment> history = supportRepository.findHistoryInvolvingDepartments(List.of(a.getCode(), b.getCode()));
        assertEquals(List.of(ba.getId(), ab.getId()), history.stream().map(SupportAssignment::getId).toList(),
                "ba (2 hours ago) before ab (3 hours ago); cc and the archived record excluded");
        assertTrue(supportRepository.findTaskIdByIdInvolvingDepartments(cc.getId(), List.of(a.getCode(), b.getCode())).isEmpty());
        assertTrue(supportRepository.lockByIdInvolvingDepartments(cc.getId(), List.of(a.getCode(), b.getCode())).isEmpty());
        assertEquals(Set.of(ab.getId()), ids(supportRepository.findByStatusInvolvingDepartments(SupportStatus.ACTIVE, List.of(a.getCode(), c.getCode()))));
    }
}
