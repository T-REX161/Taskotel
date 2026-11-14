package com.hotel.workload.workload;

import com.hotel.workload.assignment.Assignment;
import com.hotel.workload.assignment.AssignmentRepository;
import com.hotel.workload.dashboard.AdminDashboardSummaryDto;
import com.hotel.workload.dashboard.DashboardService;
import com.hotel.workload.security.DepartmentScope;
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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deactivated staff are left out of the Admin and Manager workload lists (and so out of the Workload page's available/unavailable counts and
 * the dashboard's workload buckets), matching the dashboard's active-only staff counts. A deactivated member who still holds active work is
 * kept, so held work is never hidden; a Staff member's own row is unchanged. Runs on the in-memory test database and rolls back.
 */
@SpringBootTest
@Transactional
public class WorkloadDeactivatedStaffTest {

    private static final String TAG = "wdeact_";

    @Autowired private ScopedWorkloadCalculator calculator;
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
        department = departmentRepository.findByCode("SECURITY").orElseThrow();
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    private Staff member(boolean active, Availability availability) {
        String username = TAG + "s" + (counter++);
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@hotel.test");
        user.setPassword("not-used");
        user.setRole(Role.STAFF);
        user.setActive(active);
        user.setEmailVerified(true);
        user = userRepository.saveAndFlush(user);
        Staff staff = new Staff();
        staff.setUser(user);
        staff.setName("Deact " + username);
        staff.setEmail(user.getEmail());
        staff.setDepartment(department);
        staff.setAvailability(availability);
        staff.setActive(active);
        return staffRepository.saveAndFlush(staff);
    }

    /** Gives the member an active (ASSIGNED) task of the department, written directly so the member's state can be anything. */
    private void holdTask(Staff holder, int minutes) {
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
        task.setStatus(TaskStatus.ASSIGNED);
        task.setEstimatedDuration(minutes);
        task.setDueDate(LocalDate.now().plusDays(1));
        task.setDepartment(department);
        task.setCreatedBy(creator);
        task = taskRepository.saveAndFlush(task);
        Assignment assignment = new Assignment();
        assignment.setTask(task);
        assignment.setStaff(holder);
        assignment.setAssignedAt(LocalDateTime.now());
        assignmentRepository.saveAndFlush(assignment);
    }

    private static Optional<WorkloadDto> row(List<WorkloadDto> rows, Staff member) {
        return rows.stream().filter(r -> r.getStaffId().equals(member.getId())).findFirst();
    }

    private static List<UUID> ids(List<WorkloadDto> rows) {
        return rows.stream().map(WorkloadDto::getStaffId).toList();
    }

    @Test
    @DisplayName("Admin list: a deactivated member without work has no row; active members (available or not) still do")
    void adminListLeavesOutIdleDeactivatedStaff() {
        Staff active = member(true, Availability.AVAILABLE);
        Staff away = member(true, Availability.UNAVAILABLE);
        Staff deactivated = member(false, Availability.AVAILABLE);

        List<WorkloadDto> rows = calculator.calculateWorkload(DepartmentScope.all());
        assertTrue(ids(rows).contains(active.getId()));
        assertTrue(ids(rows).contains(away.getId()), "an unavailable but active member is still listed");
        assertTrue(row(rows, deactivated).isEmpty(), "a deactivated member is not listed (and so not counted as available)");
    }

    @Test
    @DisplayName("Manager list (own department): a deactivated member without work has no row; active members still do")
    void managerListLeavesOutIdleDeactivatedStaff() {
        Staff active = member(true, Availability.AVAILABLE);
        Staff deactivated = member(false, Availability.AVAILABLE);

        List<WorkloadDto> rows = calculator.calculateWorkload(DepartmentScope.department(department));
        assertTrue(ids(rows).contains(active.getId()));
        assertTrue(row(rows, deactivated).isEmpty());
    }

    @Test
    @DisplayName("A deactivated member who still holds active work keeps their row and minutes, in the Admin and the Manager list")
    void heldWorkIsNeverHidden() {
        Staff holder = member(true, Availability.AVAILABLE);
        holdTask(holder, 90);
        holder.setActive(false);
        staffRepository.saveAndFlush(holder);

        for (DepartmentScope scope : List.of(DepartmentScope.all(), DepartmentScope.department(department))) {
            WorkloadDto kept = row(calculator.calculateWorkload(scope), holder).orElseThrow();
            assertEquals(1, kept.getActiveTaskCount());
            assertEquals(90, kept.getTotalEstimatedDuration());
        }
    }

    @Test
    @DisplayName("Dashboard workload buckets no longer count an idle deactivated member as a LOW-workload person")
    void dashboardBucketsLeaveOutIdleDeactivatedStaff() {
        var admin = new UsernamePasswordAuthenticationToken("admin", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(admin); // the scope resolver reads the signed-in user
        long lowBefore = ((AdminDashboardSummaryDto) dashboardService.getDashboardSummary(admin)).getLowWorkloadStaff();
        member(false, Availability.AVAILABLE);
        assertEquals(lowBefore, ((AdminDashboardSummaryDto) dashboardService.getDashboardSummary(admin)).getLowWorkloadStaff());
        member(true, Availability.AVAILABLE);
        assertEquals(lowBefore + 1, ((AdminDashboardSummaryDto) dashboardService.getDashboardSummary(admin)).getLowWorkloadStaff(),
                "an active member is still counted");
    }

    @Test
    @DisplayName("A Staff member's own row is unchanged (returned whatever their state)")
    void ownRowUnchanged() {
        Staff deactivated = member(false, Availability.AVAILABLE);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                deactivated.getUser().getUsername(), null, List.of(new SimpleGrantedAuthority("ROLE_STAFF"))));
        List<WorkloadDto> rows = calculator.calculateWorkload(DepartmentScope.own());
        assertEquals(List.of(deactivated.getId()), ids(rows));
    }
}
