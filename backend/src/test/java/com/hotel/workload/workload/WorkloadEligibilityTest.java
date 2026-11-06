package com.hotel.workload.workload;

import com.hotel.workload.assignment.AssignmentRepository;
import com.hotel.workload.assignment.AssignmentService;
import com.hotel.workload.assignment.CreateAssignmentRequest;
import com.hotel.workload.assignment.UpdateAssignmentRequest;
import com.hotel.workload.dashboard.AdminDashboardSummaryDto;
import com.hotel.workload.dashboard.DashboardService;
import com.hotel.workload.security.Role;
import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
import com.hotel.workload.staff.Availability;
import com.hotel.workload.staff.Department;
import com.hotel.workload.staff.DepartmentRepository;
import com.hotel.workload.staff.Staff;
import com.hotel.workload.staff.StaffRepository;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Only Staff accounts are operational staff. A staff record linked to a manager or an administrator account cannot be assigned
 * tasks and is left out of workload rows and dashboard staff totals. (Department capacity and the donor reserve in recommendations
 * keep their existing, separately tested rule: such people still count as free staff there, but are never suggested.) Runs on the in-memory test database
 * (not PostgreSQL) and removes everything it creates.
 */
@SpringBootTest
public class WorkloadEligibilityTest {

    private static final String TAG = "elig_";

    @Autowired private AssignmentService assignments;
    @Autowired private WorkloadService workloadService;
    @Autowired private DashboardService dashboardService;
    @Autowired private AssignmentRepository assignmentRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private DepartmentRepository departmentRepository;

    private Department department;
    private int counter;

    @BeforeEach
    void setUp() {
        department = departmentRepository.findByCode("FRONT_DESK").orElseThrow();
        cleanUp();
        // The assignment service decides by the caller's scope, so a direct call needs a signed-in user; these tests are about eligibility
        // rules, not authorization, so they sign in as an administrator.
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("elig_admin", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    @AfterEach
    void cleanUp() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        for (Task task : taskRepository.findAll()) {
            if (task.getTitle().startsWith(TAG)) {
                assignmentRepository.deleteAll(assignmentRepository.findAllByTaskId(task.getId()));
                taskRepository.delete(task);
            }
        }
        for (Staff staff : staffRepository.findAllWithUser()) {
            if (staff.getUser().getUsername().startsWith(TAG)) staffRepository.delete(staff);
        }
        for (User user : userRepository.findAll()) {
            if (user.getUsername().startsWith(TAG)) userRepository.delete(user);
        }
    }

    private Staff staff(Role role, boolean active) {
        String username = TAG + "s" + (counter++);
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@hotel.test");
        user.setPassword("not-used");
        user.setRole(role);
        user.setActive(active);
        user.setEmailVerified(true);
        user = userRepository.saveAndFlush(user);
        Staff staff = new Staff();
        staff.setUser(user);
        staff.setName("Elig " + username);
        staff.setEmail(user.getEmail());
        staff.setDepartment(department);
        staff.setAvailability(Availability.AVAILABLE);
        staff.setActive(active);
        return staffRepository.saveAndFlush(staff);
    }

    private Task task(TaskStatus status, int minutes) {
        User creator = userRepository.findByUsername(TAG + "creator").orElseGet(() -> {
            User user = new User();
            user.setUsername(TAG + "creator");
            user.setEmail(TAG + "creator@hotel.test");
            user.setPassword("not-used");
            user.setRole(Role.ADMIN);
            user.setActive(true);
            return userRepository.saveAndFlush(user);
        });
        Task task = new Task();
        task.setTitle(TAG + "task" + (counter++));
        task.setPriority(Priority.MEDIUM);
        task.setStatus(status);
        task.setEstimatedDuration(minutes);
        task.setDueDate(LocalDate.now().plusDays(1));
        task.setDepartment(department);
        task.setCreatedBy(creator);
        return taskRepository.saveAndFlush(task);
    }

    private void assign(Task task, Staff member) {
        assignments.createAssignment(new CreateAssignmentRequest(task.getId(), member.getId()));
    }

    private AdminDashboardSummaryDto dashboard() {
        var admin = new UsernamePasswordAuthenticationToken("admin", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        return (AdminDashboardSummaryDto) dashboardService.getDashboardSummary(admin);
    }

    private List<WorkloadDto> rows() {
        return workloadService.calculateWorkload();
    }

    // ── assignment ─────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Admin-linked and manager-linked staff records cannot be assigned a task (400), and nothing is saved")
    void privilegedAccountsCannotReceiveAssignments() {
        for (Role role : new Role[] {Role.ADMIN, Role.MANAGER}) {
            Staff privileged = staff(role, true);
            Task task = task(TaskStatus.PENDING, 30);
            ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> assign(task, privileged), role.name());
            assertEquals(400, error.getStatusCode().value());
            assertTrue(error.getReason().contains("manager or administrator"));
            assertTrue(assignmentRepository.findAllByTaskId(task.getId()).isEmpty());
            assertEquals(TaskStatus.PENDING, taskRepository.findById(task.getId()).orElseThrow().getStatus(), "the task is untouched");
        }
    }

    @Test
    @DisplayName("A task cannot be reassigned to an admin- or manager-linked record either")
    void privilegedAccountsCannotReceiveReassignments() {
        Staff holder = staff(Role.STAFF, true);
        Task task = task(TaskStatus.PENDING, 30);
        assign(task, holder);
        UUID assignmentId = assignmentRepository.findAllByTaskId(task.getId()).get(0).getId();
        for (Role role : new Role[] {Role.ADMIN, Role.MANAGER}) {
            Staff privileged = staff(role, true);
            ResponseStatusException error = assertThrows(ResponseStatusException.class,
                    () -> assignments.reassignTask(assignmentId, new UpdateAssignmentRequest(privileged.getId())), role.name());
            assertEquals(400, error.getStatusCode().value());
        }
        assertEquals(holder.getId(), assignmentRepository.findAllByTaskId(task.getId()).get(0).getStaff().getId(), "the task stays with its holder");
    }

    @Test
    @DisplayName("Ordinary Staff accounts can still be assigned and reassigned tasks")
    void ordinaryStaffStillReceiveAssignments() {
        Staff first = staff(Role.STAFF, true), second = staff(Role.STAFF, true);
        Task task = task(TaskStatus.PENDING, 30);
        assign(task, first);
        assertEquals(TaskStatus.ASSIGNED, taskRepository.findById(task.getId()).orElseThrow().getStatus());
        UUID assignmentId = assignmentRepository.findAllByTaskId(task.getId()).get(0).getId();
        assignments.reassignTask(assignmentId, new UpdateAssignmentRequest(second.getId()));
        assertEquals(second.getId(), assignmentRepository.findAllByTaskId(task.getId()).get(0).getStaff().getId());
    }

    // ── workload and dashboard ─────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Admin- and manager-linked staff have no workload row and add nothing to the dashboard staff totals")
    void privilegedAccountsAreNotCountedAsStaff() {
        int rowsBefore = rows().size();
        AdminDashboardSummaryDto before = dashboard();

        Staff admin = staff(Role.ADMIN, true), manager = staff(Role.MANAGER, true);
        Staff inactiveManager = staff(Role.MANAGER, false);

        assertEquals(rowsBefore, rows().size());
        assertFalse(rows().stream().anyMatch(r -> r.getStaffId().equals(admin.getId()) || r.getStaffId().equals(manager.getId())
                || r.getStaffId().equals(inactiveManager.getId())));
        AdminDashboardSummaryDto after = dashboard();
        assertEquals(before.getTotalStaff(), after.getTotalStaff());
        assertEquals(before.getAvailableStaff(), after.getAvailableStaff());
        assertEquals(before.getUnavailableStaff(), after.getUnavailableStaff());
        assertEquals(before.getInactiveStaff(), after.getInactiveStaff());
        assertEquals(before.getLowWorkloadStaff(), after.getLowWorkloadStaff());

        Staff ordinary = staff(Role.STAFF, true);
        assertEquals(rowsBefore + 1, rows().size());
        assertTrue(rows().stream().anyMatch(r -> r.getStaffId().equals(ordinary.getId())));
        AdminDashboardSummaryDto withStaff = dashboard();
        assertEquals(before.getTotalStaff() + 1, withStaff.getTotalStaff());
        assertEquals(before.getAvailableStaff() + 1, withStaff.getAvailableStaff());
    }

    @Test
    @DisplayName("Inactive ordinary staff keep their existing handling: counted in the total and as inactive, never as available")
    void inactiveOrdinaryStaffKeepExistingHandling() {
        AdminDashboardSummaryDto before = dashboard();
        staff(Role.STAFF, false);
        AdminDashboardSummaryDto after = dashboard();
        assertEquals(before.getTotalStaff() + 1, after.getTotalStaff());
        assertEquals(before.getInactiveStaff() + 1, after.getInactiveStaff());
        assertEquals(before.getAvailableStaff(), after.getAvailableStaff());
    }

    @Test
    @DisplayName("Existing workload rules: PENDING, ASSIGNED and IN_PROGRESS tasks count; COMPLETED and CANCELLED do not")
    void workloadStatusRulesAreUnchanged() {
        Staff member = staff(Role.STAFF, true);
        Task pending = task(TaskStatus.PENDING, 30);
        assign(pending, member);
        WorkloadDto row = rows().stream().filter(r -> r.getStaffId().equals(member.getId())).findFirst().orElseThrow();
        assertEquals(1, row.getActiveTaskCount());
        assertEquals(30, row.getTotalEstimatedDuration());

        for (TaskStatus finished : new TaskStatus[] {TaskStatus.COMPLETED, TaskStatus.CANCELLED}) {
            Task done = taskRepository.findById(pending.getId()).orElseThrow();
            done.setStatus(finished);
            taskRepository.saveAndFlush(done);
            WorkloadDto after = rows().stream().filter(r -> r.getStaffId().equals(member.getId())).findFirst().orElseThrow();
            assertEquals(0, after.getActiveTaskCount(), finished.name());
            assertEquals(0, after.getTotalEstimatedDuration(), finished.name());
        }
        // an unassigned task belongs to nobody's workload
        task(TaskStatus.PENDING, 500);
        assertEquals(0, rows().stream().filter(r -> r.getStaffId().equals(member.getId())).findFirst().orElseThrow().getTotalEstimatedDuration());
    }
}
