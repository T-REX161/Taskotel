package com.hotel.workload.staff;

import com.hotel.workload.assignment.Assignment;
import com.hotel.workload.assignment.AssignmentRepository;
import com.hotel.workload.security.Role;
import com.hotel.workload.security.ScopeFixtures;
import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
import com.hotel.workload.task.Priority;
import com.hotel.workload.task.Task;
import com.hotel.workload.task.TaskRepository;
import com.hotel.workload.task.TaskStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Permanent staff removal, called on {@link StaffService} directly (no controller, no URL rules in front of it), with real accounts and
 * Staff records in the H2 database. Only an administrator may remove staff, and the existing removal rules still apply to them.
 * Every test rolls back.
 */
@SpringBootTest
@Transactional
public class StaffRemovalAuthorizationTest {

    private static final String DEPARTMENT = "RM_ALPHA";

    @Autowired private StaffService staffService;
    @Autowired private UserRepository userRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private AssignmentRepository assignmentRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private ScopeFixtures fixtures;
    private Department alpha;
    private User admin;

    @BeforeEach
    public void setUp() {
        SecurityContextHolder.clearContext();
        fixtures = new ScopeFixtures(userRepository, staffRepository, departmentRepository, passwordEncoder);
        alpha = fixtures.department(DEPARTMENT);
        admin = fixtures.account("rm_admin", Role.ADMIN, true);
        fixtures.manager("rm_manager", alpha);
        fixtures.staff("rm_caller", alpha);
    }

    @AfterEach
    public void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void signInAs(String username, String... authorities) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(username, null,
                java.util.Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()));
    }

    /** An inactive staff member with an inactive account and no assignments: eligible for permanent removal by an administrator. */
    private Staff eligibleStaff(String username) {
        Staff staff = fixtures.staff(username, alpha);
        staff.setActive(false);
        staff.getUser().setActive(false);
        userRepository.saveAndFlush(staff.getUser());
        return staffRepository.saveAndFlush(staff);
    }

    private void assignTask(Staff staff, TaskStatus status) {
        Task task = new Task();
        task.setTitle("Removal task " + status);
        task.setPriority(Priority.MEDIUM);
        task.setStatus(status);
        task.setDepartment(alpha);
        task.setCreatedBy(admin);
        task = taskRepository.saveAndFlush(task);
        Assignment assignment = new Assignment();
        assignment.setTask(task);
        assignment.setStaff(staff);
        assignmentRepository.saveAndFlush(assignment);
    }

    private ResponseStatusException removalRefusedWith(UUID id, HttpStatus expected) {
        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> staffService.removeStaff(id));
        assertEquals(expected, error.getStatusCode());
        return error;
    }

    private void assertStillThere(Staff staff) {
        staffRepository.flush();
        assertTrue(staffRepository.findById(staff.getId()).isPresent(), "The staff record must still exist");
        assertTrue(userRepository.findById(staff.getUser().getId()).isPresent(), "The login account must still exist");
    }

    // ── authorization ────────────────────────────────────────────────────────

    @Test
    @DisplayName("An Admin removes an eligible inactive staff member: the record and the login account are deleted")
    public void adminRemovesEligibleInactiveStaff() {
        Staff target = eligibleStaff("rm_target");
        UUID staffId = target.getId();
        UUID userId = target.getUser().getId();
        signInAs("rm_admin", "ROLE_ADMIN");

        staffService.removeStaff(staffId);

        staffRepository.flush();
        assertTrue(staffRepository.findById(staffId).isEmpty());
        assertTrue(userRepository.findById(userId).isEmpty());
    }

    @Test
    @DisplayName("A Manager cannot remove staff when calling the service directly, not even an eligible inactive member of their own department")
    public void managerCannotRemoveStaff() {
        Staff target = eligibleStaff("rm_target");
        signInAs("rm_manager", "ROLE_MANAGER");

        ResponseStatusException error = removalRefusedWith(target.getId(), HttpStatus.FORBIDDEN);

        assertEquals("Only administrators are authorized to remove staff members", error.getReason());
        assertStillThere(target);
    }

    @Test
    @DisplayName("A Manager of another department cannot remove it either")
    public void managerOfAnotherDepartmentCannotRemove() {
        Staff target = eligibleStaff("rm_target");
        Department other = fixtures.department("RM_BETA");
        fixtures.manager("rm_other_manager", other);
        signInAs("rm_other_manager", "ROLE_MANAGER");

        removalRefusedWith(target.getId(), HttpStatus.FORBIDDEN);
        assertStillThere(target);
    }

    @Test
    @DisplayName("An invalid Manager (no Staff record) is refused with 403 as well")
    public void invalidManagerCannotRemoveStaff() {
        Staff target = eligibleStaff("rm_target");
        fixtures.account("rm_manager_nostaff", Role.MANAGER, true);
        signInAs("rm_manager_nostaff", "ROLE_MANAGER");

        removalRefusedWith(target.getId(), HttpStatus.FORBIDDEN);
        assertStillThere(target);
    }

    @Test
    @DisplayName("A Staff member cannot remove staff, neither a colleague nor themselves")
    public void staffCannotRemoveStaff() {
        Staff target = eligibleStaff("rm_target");
        signInAs("rm_caller", "ROLE_STAFF");

        removalRefusedWith(target.getId(), HttpStatus.FORBIDDEN);
        Staff caller = staffRepository.findByUserUsername("rm_caller").orElseThrow();
        removalRefusedWith(caller.getId(), HttpStatus.FORBIDDEN);
        assertStillThere(target);
        assertStillThere(caller);
    }

    @Test
    @DisplayName("Without a sign-in the call is refused with 401")
    public void anonymousCannotRemoveStaff() {
        Staff target = eligibleStaff("rm_target");

        removalRefusedWith(target.getId(), HttpStatus.UNAUTHORIZED);
        assertStillThere(target);
    }

    @Test
    @DisplayName("A bare 'ADMIN' authority (which the security filter never issues) is not an administrator")
    public void authorityTextAloneIsNotEnough() {
        Staff target = eligibleStaff("rm_target");
        signInAs("rm_admin", "ADMIN");

        removalRefusedWith(target.getId(), HttpStatus.FORBIDDEN);
        assertStillThere(target);
    }

    // ── the existing removal rules still apply to an Admin ───────────────────

    @Test
    @DisplayName("An Admin still cannot remove an active staff member (400)")
    public void adminCannotRemoveActiveStaff() {
        Staff active = fixtures.staff("rm_active", alpha);
        signInAs("rm_admin", "ROLE_ADMIN");

        removalRefusedWith(active.getId(), HttpStatus.BAD_REQUEST);
        assertStillThere(active);
    }

    @Test
    @DisplayName("An Admin still cannot remove an inactive member who holds an active assignment (409)")
    public void adminCannotRemoveStaffWithActiveAssignment() {
        Staff target = eligibleStaff("rm_target");
        assignTask(target, TaskStatus.ASSIGNED);
        signInAs("rm_admin", "ROLE_ADMIN");

        ResponseStatusException error = removalRefusedWith(target.getId(), HttpStatus.CONFLICT);
        assertTrue(error.getReason().contains("active task assignments"));
        assertStillThere(target);
    }

    @Test
    @DisplayName("An Admin still cannot remove an inactive member with assignment history (409): deactivation is the way to keep history")
    public void adminCannotRemoveStaffWithAssignmentHistory() {
        Staff target = eligibleStaff("rm_target");
        assignTask(target, TaskStatus.COMPLETED);
        signInAs("rm_admin", "ROLE_ADMIN");

        ResponseStatusException error = removalRefusedWith(target.getId(), HttpStatus.CONFLICT);
        assertTrue(error.getReason().contains("assignment history"));
        assertStillThere(target);
    }

    @Test
    @DisplayName("An Admin gets 404 for a staff member that does not exist")
    public void adminGets404ForUnknownId() {
        signInAs("rm_admin", "ROLE_ADMIN");

        removalRefusedWith(UUID.randomUUID(), HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Refused callers leave every candidate untouched, whatever the candidate's state")
    public void refusedCallersChangeNothing() {
        List<Staff> candidates = List.of(eligibleStaff("rm_c1"), eligibleStaff("rm_c2"));
        for (String[] caller : new String[][] {{"rm_manager", "ROLE_MANAGER"}, {"rm_caller", "ROLE_STAFF"}}) {
            signInAs(caller[0], caller[1]);
            for (Staff candidate : candidates) {
                removalRefusedWith(candidate.getId(), HttpStatus.FORBIDDEN);
            }
        }
        candidates.forEach(this::assertStillThere);
    }
}
