package com.hotel.workload.support;

import com.hotel.workload.assignment.AssignmentRepository;
import com.hotel.workload.security.UserRepository;
import com.hotel.workload.staff.DepartmentRepository;
import com.hotel.workload.staff.Staff;
import com.hotel.workload.staff.StaffRepository;
import com.hotel.workload.support.SupportDtos.Preview;
import com.hotel.workload.support.SupportDtos.Problem;
import com.hotel.workload.task.TaskRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Staff member with no department has no home department to lend from: the support plan refuses them with a clear problem (never a 500),
 * as a whole, and the planning, confirmation and lifecycle code never reads a department that is not there.
 */
@SpringBootTest
@Transactional
public class UnassignedStaffSupportTest {

    @Autowired private SupportAssignmentService service;
    @Autowired private UserRepository users;
    @Autowired private StaffRepository staffRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private AssignmentRepository assignmentRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private SupportAssignmentRepository supportRepository;

    private SupportTestFixture fx;

    @BeforeEach
    void setUp() {
        fx = new SupportTestFixture(users, staffRepository, taskRepository, assignmentRepository, departmentRepository, supportRepository);
        SupportTestFixture.signInAs("manager_test", "ADMIN");
    }

    @AfterEach
    void tearDown() {
        SupportTestFixture.signOut();
    }

    private Set<String> codes(Preview preview) {
        return preview.problems().stream().map(Problem::code).collect(Collectors.toSet());
    }

    private Staff withoutDepartment(Staff staff) {
        staff.setDepartment(null);
        return staffRepository.saveAndFlush(staff);
    }

    @Test
    @DisplayName("Preview refuses an unassigned Staff member with STAFF_NO_DEPARTMENT (not a 500, and not the misleading 'department is not active')")
    void previewRefusesUnassignedStaff() {
        var s = fx.overloaded(3, 3);
        Staff unassigned = withoutDepartment(s.donors.get(0));
        Preview preview = service.preview(fx.plan(s.receiving, unassigned, s.tasks.get(0)));
        assertFalse(preview.valid());
        assertTrue(codes(preview).contains("STAFF_NO_DEPARTMENT"), codes(preview).toString());
        assertFalse(codes(preview).contains("DONOR_INACTIVE"));
        assertTrue(preview.problems().stream().anyMatch(p -> p.message().contains("has no department")));
    }

    @Test
    @DisplayName("Confirm refuses it too, with the same problem and as a whole: no support record or assignment is created, even for the valid members of the plan")
    void confirmRefusesAtomically() {
        var s = fx.overloaded(3, 3);
        Staff unassigned = withoutDepartment(s.donors.get(0));
        long supportBefore = supportRepository.count();
        long assignmentsBefore = assignmentRepository.count();
        SupportPlanException ex = assertThrows(SupportPlanException.class,
                () -> service.confirm(fx.plan(s.receiving, unassigned, s.tasks.get(0), s.donors.get(1), s.tasks.get(1))));
        assertTrue(ex.getProblems().stream().map(Problem::code).collect(Collectors.toSet()).contains("STAFF_NO_DEPARTMENT"));
        assertEquals(supportBefore, supportRepository.count(), "no support record");
        assertEquals(assignmentsBefore, assignmentRepository.count(), "no assignment, not even for the valid donor");
    }

    @Test
    @DisplayName("A mixed plan is refused as a whole in the preview, naming only the unassigned person; the same plan without them is valid")
    void mixedPlanNamesTheProblem() {
        var s = fx.overloaded(3, 3);
        Staff unassigned = withoutDepartment(s.donors.get(0));
        Preview mixed = service.preview(fx.plan(s.receiving, unassigned, s.tasks.get(0), s.donors.get(1), s.tasks.get(1)));
        assertFalse(mixed.valid());
        assertEquals(1, mixed.problems().stream().filter(p -> "STAFF_NO_DEPARTMENT".equals(p.code())).count());
        assertTrue(service.preview(fx.plan(s.receiving, s.donors.get(1), s.tasks.get(1))).valid(), "a person with a department is still fine");
    }

    @Test
    @DisplayName("Options, history and the support list keep working with unassigned Staff in the database (no null department is read)")
    void readPathsAreNullSafe() {
        var s = fx.overloaded(3, 3);
        for (Staff donor : s.donors) withoutDepartment(donor);
        assertTrue(service.getOptions(s.receiving.getCode()) != null);
        assertTrue(service.list(null) != null);
    }
}
