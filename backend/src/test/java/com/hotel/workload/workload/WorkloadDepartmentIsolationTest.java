package com.hotel.workload.workload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.workload.assignment.Assignment;
import com.hotel.workload.assignment.AssignmentRepository;
import com.hotel.workload.dashboard.DashboardService;
import com.hotel.workload.security.Role;
import com.hotel.workload.security.ScopeFixtures;
import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Manager department isolation for workload (Step 2E), through the real security chain and the real (H2) database, plus direct service calls.
 * Every test rolls back.
 *
 * <p>Fixture: departments WISO_ALPHA and WISO_BETA. ALPHA has two Managers (m1, m2) and staff sa1 (own 40 min task), sa2 (no work), sal (lent to
 * BETA: holds a 50 min BETA task through an ACTIVE support record) and san (holds a 400 min task that has no department). BETA has Manager m3 and
 * staff sb1 (own 60 min task). One Administrator and four invalid Managers (no Staff record, inactive account, inactive Staff record, inactive
 * department).
 */
@SpringBootTest
@Transactional
public class WorkloadDepartmentIsolationTest {

    private static final String ALPHA = "WISO_ALPHA";
    private static final String BETA = "WISO_BETA";
    private static final String CLOSED = "WISO_CLOSED";

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private AssignmentRepository assignmentRepository;
    @Autowired private SupportAssignmentRepository supportRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private WorkloadService workloadService;
    @Autowired private DashboardService dashboardService;

    private final ObjectMapper mapper = new ObjectMapper();
    private MockMvc mockMvc;

    private User admin;
    private Staff sa1, sa2, sal, san, sb1;

    @BeforeEach
    public void setUp() {
        SecurityContextHolder.clearContext();
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        ScopeFixtures fixtures = new ScopeFixtures(userRepository, staffRepository, departmentRepository, passwordEncoder);
        Department alpha = fixtures.department(ALPHA);
        Department beta = fixtures.department(BETA);
        Department closed = fixtures.department(CLOSED);

        admin = fixtures.account("wiso_admin", Role.ADMIN, true);
        fixtures.manager("wiso_m1", alpha);
        fixtures.manager("wiso_m2", alpha);
        fixtures.manager("wiso_m3", beta);
        sa1 = fixtures.staff("wiso_sa1", alpha);
        sa2 = fixtures.staff("wiso_sa2", alpha);
        sal = fixtures.staff("wiso_sal", alpha);
        san = fixtures.staff("wiso_san", alpha);
        sb1 = fixtures.staff("wiso_sb1", beta);

        fixtures.account("wiso_m_nostaff", Role.MANAGER, true);
        fixtures.account("wiso_m_inactive_account", Role.MANAGER, false);
        fixtures.staffRecord("wiso_m_inactive_account", Role.MANAGER, alpha);
        Staff inactiveStaff = fixtures.manager("wiso_m_inactive_staff", alpha);
        inactiveStaff.setActive(false);
        staffRepository.saveAndFlush(inactiveStaff);
        fixtures.manager("wiso_m_closed_department", closed);
        closed.setActive(false);
        departmentRepository.saveAndFlush(closed);

        assign(task("WISO own alpha", alpha, 40), sa1);
        assign(task("WISO own beta", beta, 60), sb1);
        // A BETA task held by an ALPHA staff member on an ACTIVE support record
        Task loanTask = task("WISO loan beta task", beta, 50);
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
        support.setApprovedByUsername("wiso_admin");
        supportRepository.saveAndFlush(support);
        // A task with no department (only an Admin can create such an assignment), long enough to be AT_RISK on its own
        assign(task("WISO no department", null, 400), san);
    }

    @AfterEach
    public void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Task task(String title, Department department, int minutes) {
        Task task = new Task();
        task.setTitle(title);
        task.setPriority(Priority.MEDIUM);
        task.setStatus(TaskStatus.ASSIGNED);
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

    private MvcResult call(String username, String role) throws Exception {
        return mockMvc.perform(get("/api/workload").with(user(username).roles(role)).contentType(MediaType.APPLICATION_JSON)).andReturn();
    }

    /** The rows of GET /api/workload for this caller, by staff id. */
    private Map<UUID, JsonNode> rows(String username, String role) throws Exception {
        MvcResult result = call(username, role);
        assertEquals(200, result.getResponse().getStatus(), username);
        Map<UUID, JsonNode> byId = new HashMap<>();
        for (JsonNode node : mapper.readTree(result.getResponse().getContentAsString())) {
            byId.put(UUID.fromString(node.get("staffId").asText()), node);
        }
        return byId;
    }

    private void signInAs(String username, String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(username, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private Map<UUID, WorkloadDto> serviceRows(String username, String role) {
        signInAs(username, role);
        Map<UUID, WorkloadDto> byId = new HashMap<>();
        for (WorkloadDto dto : workloadService.calculateWorkload()) byId.put(dto.getStaffId(), dto);
        return byId;
    }

    // ── Admin ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Admin sees every department, including the lent person's loan work and the department-less task")
    public void adminSeesEverything() throws Exception {
        Map<UUID, JsonNode> rows = rows("wiso_admin", "ADMIN");
        assertTrue(rows.keySet().containsAll(List.of(sa1.getId(), sa2.getId(), sal.getId(), san.getId(), sb1.getId())));

        JsonNode lent = rows.get(sal.getId());
        assertEquals(1, lent.get("activeTaskCount").asInt());
        assertEquals(50, lent.get("totalEstimatedDuration").asInt());
        assertEquals(BETA, lent.get("supportDepartment").asText());
        assertEquals(ALPHA, lent.get("department").asText(), "the row stays with the home department");

        JsonNode noDepartment = rows.get(san.getId());
        assertEquals(1, noDepartment.get("activeTaskCount").asInt());
        assertEquals(400, noDepartment.get("totalEstimatedDuration").asInt());
        assertEquals("AT_RISK", noDepartment.get("workloadStatus").asText());
    }

    // ── Manager ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("A Manager sees only the operational staff of their own department, and none of another department")
    public void managerSeesOnlyOwnDepartment() throws Exception {
        Map<UUID, JsonNode> rows = rows("wiso_m1", "MANAGER");
        assertTrue(rows.keySet().containsAll(List.of(sa1.getId(), sa2.getId(), sal.getId(), san.getId())), rows.keySet().toString());
        assertFalse(rows.containsKey(sb1.getId()), "another department's staff must not be listed");
        for (JsonNode row : rows.values()) {
            assertEquals(ALPHA, row.get("department").asText());
        }
        // Manager and Admin accounts are not operational staff: no row for them (their Staff records are in the same department)
        assertFalse(rows.containsKey(staffRepository.findByUserUsername("wiso_m1").orElseThrow().getId()));
        assertFalse(rows.containsKey(staffRepository.findByUserUsername("wiso_m2").orElseThrow().getId()));

        JsonNode own = rows.get(sa1.getId());
        assertEquals(1, own.get("activeTaskCount").asInt());
        assertEquals(40, own.get("totalEstimatedDuration").asInt());
    }

    @Test
    @DisplayName("The other department's Manager sees their own staff only, and not the lent ALPHA person (the row stays with the home department)")
    public void otherDepartmentManagerSeesOwnStaffOnly() throws Exception {
        Map<UUID, JsonNode> rows = rows("wiso_m3", "MANAGER");
        assertTrue(rows.containsKey(sb1.getId()));
        assertFalse(rows.containsKey(sa1.getId()));
        assertFalse(rows.containsKey(sal.getId()));
        assertEquals(60, rows.get(sb1.getId()).get("totalEstimatedDuration").asInt());
    }

    @Test
    @DisplayName("Two Managers of one department see the same rows")
    public void managersShareTheirDepartment() throws Exception {
        assertEquals(rows("wiso_m1", "MANAGER").keySet(), rows("wiso_m2", "MANAGER").keySet());
    }

    @Test
    @DisplayName("Support loan: work done for another department is not counted in the home department Manager's row, and no support detail is exposed")
    public void loanWorkIsNotCountedForTheHomeManager() throws Exception {
        JsonNode lent = rows("wiso_m1", "MANAGER").get(sal.getId());
        assertEquals(0, lent.get("activeTaskCount").asInt());
        assertEquals(0, lent.get("totalEstimatedDuration").asInt());
        assertEquals("LOW", lent.get("workloadStatus").asText());
        assertTrue(lent.get("supportDepartment") == null || lent.get("supportDepartment").isNull(), "the other department is not named");
        assertTrue(lent.get("supportDepartmentName") == null || lent.get("supportDepartmentName").isNull());
        assertFalse(call("wiso_m1", "MANAGER").getResponse().getContentAsString().contains("WISO loan beta task"));
    }

    @Test
    @DisplayName("Department-less task assignments do not count toward a Manager's counts, minutes or status")
    public void departmentLessWorkIsNotCountedForAManager() throws Exception {
        JsonNode row = rows("wiso_m1", "MANAGER").get(san.getId());
        assertEquals(0, row.get("activeTaskCount").asInt());
        assertEquals(0, row.get("totalEstimatedDuration").asInt());
        assertEquals("LOW", row.get("workloadStatus").asText(), "a 400 minute task with no department must not make the row AT_RISK");
    }

    @Test
    @DisplayName("Existing calculations still hold inside the department: counts, minutes and status thresholds")
    public void ownDepartmentCalculationsAreUnchanged() throws Exception {
        Staff busy = sa2;
        assign(task("WISO big one", staffRepository.findById(sa1.getId()).orElseThrow().getDepartment(), 300), busy);
        JsonNode row = rows("wiso_m1", "MANAGER").get(busy.getId());
        assertEquals(1, row.get("activeTaskCount").asInt());
        assertEquals(300, row.get("totalEstimatedDuration").asInt());
        assertEquals("HIGH", row.get("workloadStatus").asText());
        assertEquals("HIGH", rows("wiso_admin", "ADMIN").get(busy.getId()).get("workloadStatus").asText());
    }

    // ── invalid Managers, unauthenticated ────────────────────────────────────

    @Test
    @DisplayName("An invalid Manager (no Staff record, inactive account, inactive Staff record, inactive department) gets 403 and no data")
    public void invalidManagersFailClosed() throws Exception {
        for (String manager : new String[] {"wiso_m_nostaff", "wiso_m_inactive_account", "wiso_m_inactive_staff", "wiso_m_closed_department"}) {
            MvcResult result = call(manager, "MANAGER");
            assertEquals(403, result.getResponse().getStatus(), manager);
            assertFalse(result.getResponse().getContentAsString().contains("staffId"), manager + " must receive no workload data");
        }
    }

    @Test
    @DisplayName("Without signing in the request is 401")
    public void unauthenticatedIsRefused() throws Exception {
        assertEquals(401, mockMvc.perform(get("/api/workload")).andReturn().getResponse().getStatus());
    }

    // ── Staff ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Staff see only their own row, and nobody else's, whoever else exists")
    public void staffSeeOnlyTheirOwnRow() throws Exception {
        Map<UUID, JsonNode> rows = rows("wiso_sa1", "STAFF");
        assertEquals(1, rows.size());
        assertTrue(rows.containsKey(sa1.getId()));
        assertEquals(1, rows("wiso_sb1", "STAFF").size());
        assertTrue(rows("wiso_sb1", "STAFF").containsKey(sb1.getId()));
        assertFalse(call("wiso_sa1", "STAFF").getResponse().getContentAsString().contains(sb1.getName()));
    }

    @Test
    @DisplayName("Staff behaviour is unchanged: a lent person still sees their own loan work and the support label")
    public void staffOwnRowIsUnchanged() throws Exception {
        JsonNode own = rows("wiso_sal", "STAFF").get(sal.getId());
        assertEquals(1, own.get("activeTaskCount").asInt());
        assertEquals(50, own.get("totalEstimatedDuration").asInt());
        assertEquals(BETA, own.get("supportDepartment").asText());
    }

    // ── direct service calls (no HTTP) ───────────────────────────────────────

    @Test
    @DisplayName("The service itself applies the scope: a Manager's direct call is department-limited, an invalid Manager is refused, and no sign-in is 401")
    public void theServiceCannotBeBypassed() {
        Map<UUID, WorkloadDto> manager = serviceRows("wiso_m1", "MANAGER");
        assertTrue(manager.containsKey(sa1.getId()));
        assertFalse(manager.containsKey(sb1.getId()));
        assertEquals(0, manager.get(sal.getId()).getActiveTaskCount());
        assertEquals(0, manager.get(san.getId()).getTotalEstimatedDuration());

        Map<UUID, WorkloadDto> adminRows = serviceRows("wiso_admin", "ADMIN");
        assertTrue(adminRows.containsKey(sb1.getId()));
        assertEquals(50, adminRows.get(sal.getId()).getTotalEstimatedDuration());
        assertEquals(400, adminRows.get(san.getId()).getTotalEstimatedDuration());

        for (String invalid : new String[] {"wiso_m_nostaff", "wiso_m_inactive_account", "wiso_m_inactive_staff", "wiso_m_closed_department"}) {
            signInAs(invalid, "MANAGER");
            assertEquals(403, assertThrows(ResponseStatusException.class, () -> workloadService.calculateWorkload()).getStatusCode().value(), invalid);
        }

        SecurityContextHolder.clearContext();
        assertEquals(401, assertThrows(ResponseStatusException.class, () -> workloadService.calculateWorkload()).getStatusCode().value());

        Map<UUID, WorkloadDto> staff = serviceRows("wiso_sa1", "STAFF");
        assertEquals(1, staff.size());
        assertTrue(staff.containsKey(sa1.getId()));
    }

    @Test
    @DisplayName("A Staff user with no Staff record is still refused (403), as before")
    public void staffWithoutRecordIsRefused() {
        new ScopeFixtures(userRepository, staffRepository, departmentRepository, passwordEncoder).account("wiso_staff_norecord", Role.STAFF, true);
        signInAs("wiso_staff_norecord", "STAFF");
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> workloadService.calculateWorkload()).getStatusCode().value());
    }

    // ── support loan, in both directions (temporary rule until the Support isolation step) ──────────────

    @Test
    @DisplayName("Support loan, both directions: lent work is excluded from BOTH Managers' views, and the borrowed person is listed only under their home department")
    public void loanWorkIsExcludedFromBothManagersInBothDirections() throws Exception {
        Department alpha = departmentRepository.findByCode(ALPHA).orElseThrow();
        Department beta = departmentRepository.findByCode(BETA).orElseThrow();
        // The reverse of the fixture's loan: a BETA person (sbl) lent to ALPHA, holding a 20 minute ALPHA task
        Staff sbl = new ScopeFixtures(userRepository, staffRepository, departmentRepository, passwordEncoder).staff("wiso_sbl", beta);
        Task alphaTask = task("WISO alpha task done by beta loan", alpha, 20);
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
        support.setApprovedByUsername("wiso_admin");
        supportRepository.saveAndFlush(support);

        // ALPHA's Manager: not the borrowed BETA person, and the 20 minutes are in nobody's ALPHA row
        Map<UUID, JsonNode> alphaView = rows("wiso_m1", "MANAGER");
        assertFalse(alphaView.containsKey(sbl.getId()), "the receiving department's Manager does not see the borrowed person");
        assertEquals(40, alphaView.get(sa1.getId()).get("totalEstimatedDuration").asInt());
        int alphaMinutes = 0;
        for (JsonNode row : alphaView.values()) alphaMinutes += row.get("totalEstimatedDuration").asInt();
        assertEquals(40, alphaMinutes, "only ALPHA's own work (sa1's 40) is counted; no loan minutes in either direction");

        // BETA's Manager: lists the borrowed person under their home department, but with none of the lent work and no support detail
        Map<UUID, JsonNode> betaView = rows("wiso_m3", "MANAGER");
        JsonNode borrowed = betaView.get(sbl.getId());
        assertNotNull(borrowed, "the home department's Manager still lists their own person");
        assertEquals(0, borrowed.get("activeTaskCount").asInt());
        assertEquals(0, borrowed.get("totalEstimatedDuration").asInt());
        assertEquals("LOW", borrowed.get("workloadStatus").asText());
        assertTrue(borrowed.get("supportDepartment") == null || borrowed.get("supportDepartment").isNull());
        assertFalse(betaView.containsKey(sal.getId()), "and not ALPHA's lent person");
        assertFalse(call("wiso_m3", "MANAGER").getResponse().getContentAsString().contains("WISO alpha task done by beta loan"));
        assertFalse(call("wiso_m1", "MANAGER").getResponse().getContentAsString().contains("WISO alpha task done by beta loan"));

        // Admin still sees both loans in full
        Map<UUID, JsonNode> adminView = rows("wiso_admin", "ADMIN");
        assertEquals(20, adminView.get(sbl.getId()).get("totalEstimatedDuration").asInt());
        assertEquals(ALPHA, adminView.get(sbl.getId()).get("supportDepartment").asText());
        assertEquals(50, adminView.get(sal.getId()).get("totalEstimatedDuration").asInt());
        assertEquals(BETA, adminView.get(sal.getId()).get("supportDepartment").asText());
    }

    // ── the database query itself ────────────────────────────────────────────

    @Test
    @DisplayName("The Manager query loads only the department's own work: task in the department AND holder from the department (no loan either way, no department-less task)")
    public void theDepartmentQueryLoadsOnlyOwnWork() {
        Department alpha = departmentRepository.findByCode(ALPHA).orElseThrow();
        Department beta = departmentRepository.findByCode(BETA).orElseThrow();
        // A BETA person lent to ALPHA: an ALPHA task held by a BETA staff member
        Staff lentToAlpha = new ScopeFixtures(userRepository, staffRepository, departmentRepository, passwordEncoder).staff("wiso_sb_lent_to_alpha", beta);
        assign(task("WISO alpha task held by beta staff", alpha, 20), lentToAlpha);

        List<String> titles = assignmentRepository
                .findActiveByTaskAndStaffDepartment(List.of(TaskStatus.PENDING, TaskStatus.ASSIGNED, TaskStatus.IN_PROGRESS), alpha).stream()
                .map(a -> a.getTask().getTitle()).toList();

        assertTrue(titles.contains("WISO own alpha"), titles.toString());
        assertFalse(titles.contains("WISO alpha task held by beta staff"), "the holder's home department must also match: " + titles);
        assertFalse(titles.contains("WISO loan beta task"), "an ALPHA person's work for BETA is not ALPHA's own work: " + titles);
        assertFalse(titles.contains("WISO no department"), "a task with no department is never loaded: " + titles);
        assertFalse(titles.contains("WISO own beta"), titles.toString());
    }

    // ── dashboard follows the same scope (Step 2F) ──────────────────────────────────────────────────

    @Test
    @DisplayName("The dashboard now follows the same scope: a Manager's summary is department-only (no loan work, no department-less task) and differs from the Admin's; Staff still count their loan work")
    public void dashboardFollowsTheSameScope() throws Exception {
        signInAs("wiso_m1", "MANAGER");
        JsonNode managerSummary = mapper.readTree(mapper.writeValueAsString(
                dashboardService.getDashboardSummary(SecurityContextHolder.getContext().getAuthentication())));
        signInAs("wiso_admin", "ADMIN");
        JsonNode adminSummary = mapper.readTree(mapper.writeValueAsString(
                dashboardService.getDashboardSummary(SecurityContextHolder.getContext().getAuthentication())));

        assertNotEquals(adminSummary, managerSummary, "a Manager's dashboard is no longer the Admin's");
        assertEquals(0, managerSummary.get("atRiskStaff").asLong(), "the department-less 400 minute task no longer distorts a Manager's dashboard");
        assertTrue(adminSummary.get("atRiskStaff").asLong() >= 1, "the Admin still counts it");
        assertEquals(1, managerSummary.get("totalStaffWithWorkload").asLong(), "only sa1 has own-department work (loan work excluded)");
        assertTrue(adminSummary.get("totalStaffWithWorkload").asLong() >= 4);
        // The same buckets as the Manager's own GET /api/workload view
        long withWork = 0;
        for (JsonNode row : mapper.readTree(call("wiso_m1", "MANAGER").getResponse().getContentAsString())) {
            if (row.get("activeTaskCount").asInt() > 0) withWork++;
        }
        assertEquals(withWork, managerSummary.get("totalStaffWithWorkload").asLong());

        signInAs("wiso_sal", "STAFF");
        var staffSummary = (com.hotel.workload.dashboard.StaffDashboardSummaryDto) dashboardService.getDashboardSummary(
                SecurityContextHolder.getContext().getAuthentication());
        assertEquals(50, staffSummary.getWorkload().getTotalEstimatedDuration(), "a Staff dashboard still counts the loan work");
    }
}
