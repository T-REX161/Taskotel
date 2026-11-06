package com.hotel.workload.dashboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.workload.assignment.Assignment;
import com.hotel.workload.assignment.AssignmentRepository;
import com.hotel.workload.security.Role;
import com.hotel.workload.security.ScopeFixtures;
import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
import com.hotel.workload.staff.Availability;
import com.hotel.workload.staff.Department;
import com.hotel.workload.staff.DepartmentRepository;
import com.hotel.workload.staff.Staff;
import com.hotel.workload.staff.StaffRepository;
import com.hotel.workload.support.SupportAssignment;
import com.hotel.workload.support.SupportAssignmentRepository;
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
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Manager department isolation for the dashboard (Step 2F), through the real security chain and the real (H2) database, plus direct
 * service calls and repository-level checks. Every test rolls back.
 *
 * <p>Fixture: departments DSH_ALPHA and DSH_BETA. ALPHA has Managers m1 and m2 and five operational Staff: sa1 (own 40 minute task),
 * sa2 (unavailable), sai (inactive), sal (lent to BETA: holds a 50 minute BETA task through an ACTIVE support record) and san (holds a 400 minute
 * task that has no department). ALPHA also owns a PENDING, an IN_PROGRESS, a COMPLETED and a CANCELLED task with no assignment. BETA has Manager m3
 * and Staff sb1 (own 60 minute task). One Administrator and four invalid Managers.
 */
@SpringBootTest
@Transactional
public class DashboardDepartmentIsolationTest {

    private static final String ALPHA = "DSH_ALPHA";
    private static final String BETA = "DSH_BETA";
    private static final String CLOSED = "DSH_CLOSED";
    private static final List<TaskStatus> ACTIVE = List.of(TaskStatus.PENDING, TaskStatus.ASSIGNED, TaskStatus.IN_PROGRESS);

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private AssignmentRepository assignmentRepository;
    @Autowired private SupportAssignmentRepository supportRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private DashboardService dashboardService;

    private final ObjectMapper mapper = new ObjectMapper();
    private MockMvc mockMvc;

    private User admin;
    private Department alpha, beta;
    private Staff sa1, sa2, sai, sal, san, sb1;

    @BeforeEach
    public void setUp() {
        SecurityContextHolder.clearContext();
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        ScopeFixtures fixtures = new ScopeFixtures(userRepository, staffRepository, departmentRepository, passwordEncoder);
        alpha = fixtures.department(ALPHA);
        beta = fixtures.department(BETA);
        Department closed = fixtures.department(CLOSED);

        admin = fixtures.account("dsh_admin", Role.ADMIN, true);
        fixtures.manager("dsh_m1", alpha);
        fixtures.manager("dsh_m2", alpha);
        fixtures.manager("dsh_m3", beta);
        sa1 = fixtures.staff("dsh_sa1", alpha);
        sa2 = fixtures.staff("dsh_sa2", alpha);
        sa2.setAvailability(Availability.UNAVAILABLE);
        staffRepository.saveAndFlush(sa2);
        sai = fixtures.staff("dsh_sai", alpha);
        sai.setActive(false);
        staffRepository.saveAndFlush(sai);
        sal = fixtures.staff("dsh_sal", alpha);
        san = fixtures.staff("dsh_san", alpha);
        sb1 = fixtures.staff("dsh_sb1", beta);

        fixtures.account("dsh_m_nostaff", Role.MANAGER, true);
        fixtures.account("dsh_m_inactive_account", Role.MANAGER, false);
        fixtures.staffRecord("dsh_m_inactive_account", Role.MANAGER, alpha);
        Staff inactiveStaff = fixtures.manager("dsh_m_inactive_staff", alpha);
        inactiveStaff.setActive(false);
        staffRepository.saveAndFlush(inactiveStaff);
        fixtures.manager("dsh_m_closed_department", closed);
        closed.setActive(false);
        departmentRepository.saveAndFlush(closed);

        assign(task("DSH own alpha", alpha, 40, TaskStatus.ASSIGNED), sa1);
        task("DSH alpha pending", alpha, 10, TaskStatus.PENDING);
        task("DSH alpha in progress", alpha, 10, TaskStatus.IN_PROGRESS);
        task("DSH alpha completed", alpha, 10, TaskStatus.COMPLETED);
        task("DSH alpha cancelled", alpha, 10, TaskStatus.CANCELLED);
        assign(task("DSH own beta", beta, 60, TaskStatus.ASSIGNED), sb1);

        Task loanTask = task("DSH loan beta task", beta, 50, TaskStatus.ASSIGNED);
        Assignment loan = assign(loanTask, sal);
        SupportAssignment support = new SupportAssignment();
        support.setPlanId(UUID.randomUUID());
        support.setStaffId(sal.getId());
        support.setStaffName(sal.getName());
        support.setHomeDepartmentCode(ALPHA);
        support.setReceivingDepartmentCode(BETA);
        support.setTaskId(loanTask.getId());
        support.setTaskTitle(loanTask.getTitle());
        support.setAssignmentId(loan.getId());
        support.setApprovedByUsername("dsh_admin");
        supportRepository.saveAndFlush(support);
        assign(task("DSH no department", null, 400, TaskStatus.ASSIGNED), san);
    }

    @AfterEach
    public void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Task task(String title, Department department, int minutes, TaskStatus status) {
        Task task = new Task();
        task.setTitle(title);
        task.setPriority(Priority.MEDIUM);
        task.setStatus(status);
        task.setEstimatedDuration(minutes);
        task.setDueDate(LocalDate.of(2027, 1, 1));
        task.setDepartment(department);
        task.setCreatedBy(admin);
        return taskRepository.saveAndFlush(task);
    }

    private Assignment assign(Task task, Staff staff) {
        Assignment assignment = new Assignment();
        assignment.setTask(task);
        assignment.setStaff(staff);
        return assignmentRepository.saveAndFlush(assignment);
    }

    private MvcResult call(String path, String username, String role) throws Exception {
        return mockMvc.perform(get(path).with(user(username).roles(role)).contentType(MediaType.APPLICATION_JSON)).andReturn();
    }

    private JsonNode summary(String username, String role) throws Exception {
        MvcResult result = call("/api/dashboard/summary", username, role);
        assertEquals(200, result.getResponse().getStatus(), username);
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    private void signInAs(String username, String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(username, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private JsonNode serviceSummary(String username, String role) throws Exception {
        signInAs(username, role);
        Object result = dashboardService.getDashboardSummary(SecurityContextHolder.getContext().getAuthentication());
        return mapper.readTree(mapper.writeValueAsString(result));
    }

    private static void assertDepartmentSummary(JsonNode s, long staff, long available, long unavailable, long inactive,
                                                long tasks, long pending, long assigned, long inProgress, long completed, long cancelled,
                                                long activeAssignments) {
        assertEquals(staff, s.get("totalStaff").asLong(), "totalStaff");
        assertEquals(available, s.get("availableStaff").asLong(), "availableStaff");
        assertEquals(unavailable, s.get("unavailableStaff").asLong(), "unavailableStaff");
        assertEquals(inactive, s.get("inactiveStaff").asLong(), "inactiveStaff");
        assertEquals(tasks, s.get("totalTasks").asLong(), "totalTasks");
        assertEquals(pending, s.get("pendingTasks").asLong(), "pendingTasks");
        assertEquals(assigned, s.get("assignedTasks").asLong(), "assignedTasks");
        assertEquals(inProgress, s.get("inProgressTasks").asLong(), "inProgressTasks");
        assertEquals(completed, s.get("completedTasks").asLong(), "completedTasks");
        assertEquals(cancelled, s.get("cancelledTasks").asLong(), "cancelledTasks");
        assertEquals(activeAssignments, s.get("totalActiveAssignments").asLong(), "totalActiveAssignments");
    }

    // ── Admin ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Admin keeps the global dashboard: every department, the lent person's loan work and the department-less task")
    public void adminIsGlobal() throws Exception {
        JsonNode s = summary("dsh_admin", "ADMIN");
        assertEquals(taskRepository.count(), s.get("totalTasks").asLong());
        assertEquals(staffRepository.countOperational(), s.get("totalStaff").asLong());
        assertEquals(assignmentRepository.countActiveAssignments(ACTIVE), s.get("totalActiveAssignments").asLong());
        assertTrue(s.get("totalActiveAssignments").asLong() >= 4, "sa1, sb1, sal (loan) and san (no department) are all counted");
        assertTrue(s.get("atRiskStaff").asLong() >= 1, "the department-less 400 minute task is counted for the Admin");
        assertTrue(s.get("totalStaffWithWorkload").asLong() >= 4);
    }

    // ── Manager: own department only ─────────────────────────────────────────

    @Test
    @DisplayName("Manager sees only their own department's counts: no other department, no loan work, no department-less task")
    public void managerSeesOnlyOwnDepartment() throws Exception {
        JsonNode s = summary("dsh_m1", "MANAGER");
        // Staff: sa1, sa2, sai, sal, san are ALPHA's (5); sai inactive; sa2 unavailable
        // Tasks: own alpha (assigned), pending, in progress, completed, cancelled = 5 (not the BETA tasks, not the department-less one)
        // Active assignments: only sa1's own (sal's work is a BETA task; san's task has no department)
        assertDepartmentSummary(s, 5, 3, 1, 1, 5, 1, 1, 1, 1, 1, 1);
        assertEquals(1, s.get("totalStaffWithWorkload").asLong(), "only sa1 has own-department work");
        assertEquals(0, s.get("atRiskStaff").asLong(), "the department-less 400 minute task does not make anyone AT_RISK for a Manager");
    }

    @Test
    @DisplayName("Another department's Manager sees only their own figures, and never the first department's")
    public void otherDepartmentsManager() throws Exception {
        JsonNode s = summary("dsh_m3", "MANAGER");
        // BETA: Staff sb1 only; tasks: own beta + the lent loan task (both BETA tasks); active assignments: sb1's own task only
        assertDepartmentSummary(s, 1, 1, 0, 0, 2, 0, 2, 0, 0, 0, 1);
        assertEquals(1, s.get("totalStaffWithWorkload").asLong(), "the borrowed person is ALPHA's, so not in BETA's workload");
    }

    @Test
    @DisplayName("Two Managers of the same department see the same dashboard")
    public void sameDepartmentManagersAgree() throws Exception {
        assertEquals(summary("dsh_m1", "MANAGER"), summary("dsh_m2", "MANAGER"));
    }

    @Test
    @DisplayName("No inference by arithmetic: the Admin's totals exceed the sum of the Managers' by at least the department-less task")
    public void managersTotalsDoNotRevealTheRest() throws Exception {
        JsonNode a = summary("dsh_m1", "MANAGER");
        JsonNode b = summary("dsh_m3", "MANAGER");
        JsonNode global = summary("dsh_admin", "ADMIN");
        assertTrue(global.get("totalTasks").asLong() - a.get("totalTasks").asLong() - b.get("totalTasks").asLong() >= 1);
        // and a Manager's figures are not the global ones
        assertNotEquals(global.get("totalTasks").asLong(), a.get("totalTasks").asLong());
        assertNotEquals(global.get("totalActiveAssignments").asLong(), a.get("totalActiveAssignments").asLong());
    }

    @Test
    @DisplayName("A Manager's workload buckets agree exactly with GET /api/workload for the same Manager")
    public void managerWorkloadMatchesWorkloadEndpoint() throws Exception {
        for (String manager : List.of("dsh_m1", "dsh_m3")) {
            MvcResult result = call("/api/workload", manager, "MANAGER");
            assertEquals(200, result.getResponse().getStatus());
            long withWork = 0, low = 0, normal = 0, high = 0, atRisk = 0, rows = 0;
            for (JsonNode row : mapper.readTree(result.getResponse().getContentAsString())) {
                rows++;
                if (row.get("activeTaskCount").asInt() > 0) withWork++;
                switch (row.get("workloadStatus").asText()) {
                    case "LOW": low++; break;
                    case "NORMAL": normal++; break;
                    case "HIGH": high++; break;
                    case "AT_RISK": atRisk++; break;
                    default: fail("unexpected status");
                }
            }
            JsonNode s = summary(manager, "MANAGER");
            assertEquals(withWork, s.get("totalStaffWithWorkload").asLong(), manager);
            assertEquals(low, s.get("lowWorkloadStaff").asLong(), manager);
            assertEquals(normal, s.get("normalWorkloadStaff").asLong(), manager);
            assertEquals(high, s.get("highWorkloadStaff").asLong(), manager);
            assertEquals(atRisk, s.get("atRiskStaff").asLong(), manager);
            assertEquals(rows, low + normal + high + atRisk, manager);
        }
    }

    @Test
    @DisplayName("Support loan: the lent work is in neither Manager's active assignments or workload, in both directions")
    public void supportLoanWorkIsExcludedFromBothManagers() throws Exception {
        // Reverse direction too: a BETA person lent to ALPHA, holding a 20 minute ALPHA task
        Staff sbl = new ScopeFixtures(userRepository, staffRepository, departmentRepository, passwordEncoder).staff("dsh_sbl", beta);
        Task alphaTask = task("DSH alpha task done by beta loan", alpha, 20, TaskStatus.ASSIGNED);
        Assignment held = assign(alphaTask, sbl);
        SupportAssignment support = new SupportAssignment();
        support.setPlanId(UUID.randomUUID());
        support.setStaffId(sbl.getId());
        support.setStaffName(sbl.getName());
        support.setHomeDepartmentCode(BETA);
        support.setReceivingDepartmentCode(ALPHA);
        support.setTaskId(alphaTask.getId());
        support.setTaskTitle(alphaTask.getTitle());
        support.setAssignmentId(held.getId());
        support.setApprovedByUsername("dsh_admin");
        supportRepository.saveAndFlush(support);

        // ALPHA: one more ALPHA task (so 6 tasks), but still only sa1's own active assignment; BETA: one more operational staff member
        JsonNode a = summary("dsh_m1", "MANAGER");
        assertEquals(6, a.get("totalTasks").asLong());
        assertEquals(1, a.get("totalActiveAssignments").asLong());
        assertEquals(1, a.get("totalStaffWithWorkload").asLong());
        JsonNode b = summary("dsh_m3", "MANAGER");
        assertEquals(2, b.get("totalStaff").asLong());
        assertEquals(1, b.get("totalActiveAssignments").asLong());
        assertEquals(1, b.get("totalStaffWithWorkload").asLong());
        // The Admin still counts both loans
        assertTrue(summary("dsh_admin", "ADMIN").get("totalActiveAssignments").asLong() >= 5);
    }

    @Test
    @DisplayName("A Manager's response carries no other department's names, task titles or staff")
    public void noNestedLeakage() throws Exception {
        String body = call("/api/dashboard/summary", "dsh_m1", "MANAGER").getResponse().getContentAsString();
        for (String forbidden : List.of("DSH_BETA", "DSH own beta", "DSH loan beta task", "DSH no department", "dsh_sb1")) {
            assertFalse(body.contains(forbidden), forbidden);
        }
    }

    // ── Invalid Manager / unauthenticated ────────────────────────────────────

    @Test
    @DisplayName("Invalid Managers get 403 for the whole dashboard: no Staff record, inactive account, inactive Staff record, inactive department")
    public void invalidManagersAreRefused() throws Exception {
        for (String manager : List.of("dsh_m_nostaff", "dsh_m_inactive_account", "dsh_m_inactive_staff", "dsh_m_closed_department")) {
            MvcResult result = call("/api/dashboard/summary", manager, "MANAGER");
            assertEquals(403, result.getResponse().getStatus(), manager);
            assertFalse(result.getResponse().getContentAsString().contains("totalStaff"), manager);
        }
    }

    @Test
    @DisplayName("No sign-in gets 401")
    public void unauthenticatedIsRefused() throws Exception {
        assertEquals(401, mockMvc.perform(get("/api/dashboard/summary")).andReturn().getResponse().getStatus());
    }

    // ── Staff ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Staff see only their own data and none of the aggregate fields; a lent person's own dashboard still counts their loan work")
    public void staffSeeOnlyOwnData() throws Exception {
        JsonNode s = summary("dsh_sa1", "STAFF");
        assertEquals(sa1.getName(), s.get("workload").get("staffName").asText());
        assertEquals(40, s.get("workload").get("totalEstimatedDuration").asInt());
        assertEquals(1, s.get("recentAssignments").size());
        assertFalse(s.has("totalStaff"));
        assertFalse(s.has("totalTasks"));

        JsonNode lent = summary("dsh_sal", "STAFF");
        assertEquals(50, lent.get("workload").get("totalEstimatedDuration").asInt());
        assertEquals(1, lent.get("recentAssignments").size());
        assertFalse(lent.toString().contains("dsh_sa1"));
    }

    @Test
    @DisplayName("A Staff account with no Staff record gets 403")
    public void staffWithoutRecordIsRefused() throws Exception {
        new ScopeFixtures(userRepository, staffRepository, departmentRepository, passwordEncoder).account("dsh_s_norecord", Role.STAFF, true);
        assertEquals(403, call("/api/dashboard/summary", "dsh_s_norecord", "STAFF").getResponse().getStatus());
    }

    @Test
    @DisplayName("The dashboard takes no request parameter that widens it")
    public void parametersDoNotWiden() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/dashboard/summary?department=DSH_BETA&all=true&admin=true&username=dsh_sb1")
                .with(user("dsh_m1").roles("MANAGER"))).andReturn();
        assertEquals(mapper.readTree(summary("dsh_m1", "MANAGER").toString()), mapper.readTree(result.getResponse().getContentAsString()));
    }

    // ── direct service calls ─────────────────────────────────────────────────

    @Test
    @DisplayName("Direct service calls follow the same rules without HTTP")
    public void directServiceCalls() throws Exception {
        assertDepartmentSummary(serviceSummary("dsh_m1", "MANAGER"), 5, 3, 1, 1, 5, 1, 1, 1, 1, 1, 1);
        assertDepartmentSummary(serviceSummary("dsh_m3", "MANAGER"), 1, 1, 0, 0, 2, 0, 2, 0, 0, 0, 1);
        assertEquals(taskRepository.count(), serviceSummary("dsh_admin", "ADMIN").get("totalTasks").asLong());
        signInAs("dsh_sal", "STAFF");
        StaffDashboardSummaryDto own = (StaffDashboardSummaryDto) dashboardService.getDashboardSummary(SecurityContextHolder.getContext().getAuthentication());
        assertEquals(50, own.getWorkload().getTotalEstimatedDuration());

        signInAs("dsh_m_nostaff", "MANAGER");
        ResponseStatusException refused = assertThrows(ResponseStatusException.class,
                () -> dashboardService.getDashboardSummary(SecurityContextHolder.getContext().getAuthentication()));
        assertEquals(403, refused.getStatusCode().value());

        SecurityContextHolder.clearContext();
        ResponseStatusException unauthenticated = assertThrows(ResponseStatusException.class,
                () -> dashboardService.getDashboardSummary(new UsernamePasswordAuthenticationToken("dsh_m1", null,
                        List.of(new SimpleGrantedAuthority("ROLE_MANAGER")))));
        assertEquals(401, unauthenticated.getStatusCode().value());
    }

    @Test
    @DisplayName("A caller cannot gain Admin figures by presenting an Admin authority: the scope comes from the database")
    public void authorityStringDoesNotDecide() throws Exception {
        signInAs("dsh_m1", "MANAGER");
        Object forged = dashboardService.getDashboardSummary(new UsernamePasswordAuthenticationToken("dsh_m1", null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        assertDepartmentSummary(mapper.readTree(mapper.writeValueAsString(forged)), 5, 3, 1, 1, 5, 1, 1, 1, 1, 1, 1);
    }

    // ── repository level ─────────────────────────────────────────────────────

    @Test
    @DisplayName("The department count queries filter in the database")
    public void repositoryQueriesFilterByDepartment() {
        assertEquals(5, staffRepository.countOperationalInDepartment(alpha));
        assertEquals(1, staffRepository.countOperationalInDepartment(beta));
        assertEquals(1, staffRepository.countOperationalInactiveInDepartment(alpha));
        assertEquals(3, staffRepository.countOperationalActiveByAvailabilityInDepartment(alpha, Availability.AVAILABLE));
        assertEquals(1, staffRepository.countOperationalActiveByAvailabilityInDepartment(alpha, Availability.UNAVAILABLE));

        assertEquals(5, taskRepository.countByDepartment(alpha));
        assertEquals(1, taskRepository.countByDepartmentAndStatus(alpha, TaskStatus.PENDING));
        assertEquals(1, taskRepository.countByDepartmentAndStatus(alpha, TaskStatus.COMPLETED));
        assertEquals(2, taskRepository.countByDepartmentAndStatus(beta, TaskStatus.ASSIGNED));

        // sa1's own task counts for ALPHA; the lent person's BETA task (holder in ALPHA) and the department-less task count for nobody
        assertEquals(1, assignmentRepository.countActiveByTaskAndStaffDepartment(ACTIVE, alpha));
        assertEquals(1, assignmentRepository.countActiveByTaskAndStaffDepartment(ACTIVE, beta));
    }
}
