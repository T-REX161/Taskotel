package com.hotel.workload.workload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.workload.assignment.Assignment;
import com.hotel.workload.assignment.AssignmentRepository;
import com.hotel.workload.security.CurrentUserScopeResolver;
import com.hotel.workload.security.Role;
import com.hotel.workload.security.ScopeFixtures;
import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
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
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Manager department isolation for workload recommendations (Step 2G-1), through the real security chain and the real (H2) database, plus
 * direct service calls and the repository queries. Every test rolls back.
 *
 * <p>Fixture: REC_RECV (Manager mr; staff r1 busy; r2 busy, lent to REC_OTH; two waiting tasks, one URGENT) is the receiving department.
 * REC_DON (Manager md; free staff d1, d2; d3 busy, lent to REC_RECV; one waiting task) can lend. REC_OTH (Manager mo; busy staff o1; three waiting
 * tasks) is itself under pressure with nobody to lend it. One active task has no department. One Administrator, four invalid Managers, one Staff.
 */
@SpringBootTest
@Transactional
public class WorkloadRecommendationDepartmentIsolationTest {

    private static final String URL = "/api/workload/recommendations";
    private static final String RECV = "REC_RECV";
    private static final String DON = "REC_DON";
    private static final String OTH = "REC_OTH";
    private static final String CLOSED = "REC_CLOSED";
    private static final List<TaskStatus> ACTIVE = List.of(TaskStatus.PENDING, TaskStatus.ASSIGNED, TaskStatus.IN_PROGRESS);

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private AssignmentRepository assignmentRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private WorkloadRecommendationService recommendationService;
    @MockitoSpyBean private CurrentUserScopeResolver scopeResolver;

    private final ObjectMapper mapper = new ObjectMapper();
    private MockMvc mockMvc;

    private User admin;
    private Department recv, don, oth;
    private Staff r1, r2, d1, d2, d3, d4, o1;

    @BeforeEach
    public void setUp() {
        SecurityContextHolder.clearContext();
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        ScopeFixtures fx = new ScopeFixtures(userRepository, staffRepository, departmentRepository, passwordEncoder);
        recv = fx.department(RECV);
        don = fx.department(DON);
        oth = fx.department(OTH);
        Department closed = fx.department(CLOSED);

        admin = fx.account("rec_admin", Role.ADMIN, true);
        fx.manager("rec_mr", recv);
        fx.manager("rec_md", don);
        fx.manager("rec_mo", oth);
        r1 = fx.staff("rec_r1", recv);
        r2 = fx.staff("rec_r2", recv);
        d1 = lendable(fx.staff("rec_d1", don));
        d2 = lendable(fx.staff("rec_d2", don));
        d3 = lendable(fx.staff("rec_d3", don));
        d4 = lendable(fx.staff("rec_d4", don));
        o1 = lendable(fx.staff("rec_o1", oth));

        fx.account("rec_m_nostaff", Role.MANAGER, true);
        fx.account("rec_m_inactive_account", Role.MANAGER, false);
        fx.staffRecord("rec_m_inactive_account", Role.MANAGER, recv);
        Staff inactiveStaff = fx.manager("rec_m_inactive_staff", recv);
        inactiveStaff.setActive(false);
        staffRepository.saveAndFlush(inactiveStaff);
        fx.manager("rec_m_closed", closed);
        closed.setActive(false);
        departmentRepository.saveAndFlush(closed);

        // busy people
        assign(task("REC own task", recv, Priority.MEDIUM, TaskStatus.ASSIGNED, 30), r1);
        assign(task("REC other loan task", oth, Priority.MEDIUM, TaskStatus.ASSIGNED, 45), r2);   // r2 is RECV's, lent to OTH
        assign(task("REC loan to recv task", recv, Priority.MEDIUM, TaskStatus.ASSIGNED, 25), d3);  // d3 is DON's, lent to RECV
        assign(task("REC other own task", oth, Priority.MEDIUM, TaskStatus.ASSIGNED, 20), o1);
        // waiting work (no assignment)
        task("REC waiting urgent", recv, Priority.URGENT, TaskStatus.PENDING, 60);
        task("REC waiting normal", recv, Priority.MEDIUM, TaskStatus.PENDING, 40);
        task("REC donor secret task", don, Priority.HIGH, TaskStatus.PENDING, 15);
        task("REC donor secret task 2", don, Priority.LOW, TaskStatus.PENDING, 15);
        task("REC other secret 1", oth, Priority.HIGH, TaskStatus.PENDING, 50);
        task("REC other secret 2", oth, Priority.HIGH, TaskStatus.PENDING, 50);
        task("REC other secret 3", oth, Priority.LOW, TaskStatus.PENDING, 50);
        task("REC nodept task", null, Priority.MEDIUM, TaskStatus.PENDING, 10);

        fx.account("rec_staff_user", Role.STAFF, true);
        fx.staffRecord("rec_staff_user", Role.STAFF, recv);
        // Everyone who is not part of the scenario is unavailable, so the free-staff figures below are exactly the scenario's
        for (String name : List.of("rec_mr", "rec_md", "rec_mo", "rec_m_inactive_account", "rec_staff_user")) {
            Staff member = staffRepository.findByUserUsername(name).orElseThrow();
            member.setAvailability(com.hotel.workload.staff.Availability.UNAVAILABLE);
            staffRepository.saveAndFlush(member);
        }
        clearInvocations(scopeResolver);
    }

    @AfterEach
    public void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Staff lendable(Staff staff) {
        User account = staff.getUser();
        account.setEmailVerified(true);
        userRepository.saveAndFlush(account);
        return staff;
    }

    private Task task(String title, Department department, Priority priority, TaskStatus status, int minutes) {
        Task task = new Task();
        task.setTitle(title);
        task.setPriority(priority);
        task.setStatus(status);
        task.setEstimatedDuration(minutes);
        task.setDueDate(LocalDate.now().plusDays(30));
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
        clearInvocations(scopeResolver);
        return mockMvc.perform(get(URL).with(user(username).roles(role)).contentType(MediaType.APPLICATION_JSON)).andReturn();
    }

    private String body(String username, String role) throws Exception {
        MvcResult result = call(username, role);
        assertEquals(200, result.getResponse().getStatus(), username);
        return result.getResponse().getContentAsString();
    }

    private JsonNode json(String username, String role) throws Exception {
        return mapper.readTree(body(username, role));
    }

    private static JsonNode entry(JsonNode response, String code) {
        for (JsonNode node : response.get("recommendations")) if (code.equals(node.get("departmentCode").asText())) return node;
        return null;
    }

    private void signInAs(String username, String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(username, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    // ── Admin: global, unchanged ─────────────────────────────────────────────

    @Test
    @DisplayName("Admin receives the global recommendations unchanged: every pressured department, full donor identity and impact figures, and the department-less count")
    public void adminIsGlobal() throws Exception {
        JsonNode admin = json("rec_admin", "ADMIN");
        JsonNode forRecv = entry(admin, RECV);
        assertNotNull(forRecv, "the receiving department");
        assertTrue(forRecv.get("suggestions").size() >= 1);
        JsonNode first = forRecv.get("suggestions").get(0);
        assertFalse(first.get("staffId").isNull(), "Admin still gets the helper's id");
        assertFalse(first.get("homeDepartmentCode").isNull());
        assertFalse(first.get("donorImpact").isNull(), "and the donor impact figures");
        assertTrue(first.get("reasons").get(0).asText().contains("available staff with no active task"), "and the full reason text");
        // REC_OTH is under pressure with nobody to lend: visible to the Admin as unsupported
        boolean othListed = entry(admin, OTH) != null;
        for (JsonNode u : admin.get("unsupported")) if (OTH.equals(u.get("departmentCode").asText())) othListed = true;
        assertTrue(othListed, "Admin sees the other pressured department");
        assertTrue(admin.get("limitations").toString().contains("with no department"), "and the count of tasks with no department");
    }

    @Test
    @DisplayName("The recommendation algorithm is preserved: a Manager's entry has the same severity, pressure, helpers, order and receiver impact as the Admin's for that department")
    public void algorithmIsPreserved() throws Exception {
        JsonNode adminEntry = entry(json("rec_admin", "ADMIN"), RECV);
        JsonNode managerEntry = entry(json("rec_mr", "MANAGER"), RECV);
        assertNotNull(managerEntry);
        assertEquals(adminEntry.get("severity"), managerEntry.get("severity"));
        assertEquals(adminEntry.get("pressure"), managerEntry.get("pressure"));
        assertEquals(adminEntry.get("reasons"), managerEntry.get("reasons"));
        assertEquals(adminEntry.get("suggestions").size(), managerEntry.get("suggestions").size());
        for (int i = 0; i < adminEntry.get("suggestions").size(); i++) {
            JsonNode a = adminEntry.get("suggestions").get(i);
            JsonNode m = managerEntry.get("suggestions").get(i);
            assertEquals(a.get("staffName"), m.get("staffName"));
            assertEquals(a.get("homeDepartmentName"), m.get("homeDepartmentName"));
            assertEquals(a.get("coversTaskTitle"), m.get("coversTaskTitle"));
            assertEquals(a.get("receiverImpact"), m.get("receiverImpact"));
        }
    }

    // ── Manager: own department as receiver only ─────────────────────────────

    @Test
    @DisplayName("A Manager receives only their own department as the receiving department, with its own pressure figures")
    public void managerSeesOnlyOwnDepartment() throws Exception {
        JsonNode response = json("rec_mr", "MANAGER");
        assertEquals(1, response.get("recommendations").size());
        JsonNode own = entry(response, RECV);
        assertNotNull(own);
        assertEquals(2, own.get("pressure").get("waitingTasks").asInt(), "REC waiting urgent and normal; the department-less task is not counted");
        assertEquals(0, own.get("pressure").get("freeStaff").asInt(), "r1 and the lent r2 are busy");
        assertEquals(2, own.get("pressure").get("uncoveredTasks").asInt());
        assertEquals("CRITICAL", own.get("severity").asText());
        assertEquals(0, response.get("unsupported").size());
        for (JsonNode s : own.get("suggestions")) {
            assertEquals(DON + " name", s.get("homeDepartmentName").asText());
            assertTrue(s.get("staffName").asText().startsWith("Scope rec_d"));
        }
        // REC_DON has free d1, d2 and d4 (d3 is lent to REC_RECV) and two waiting tasks: lending one leaves 2, enough for its own two tasks, lending a second would not
        assertEquals(1, own.get("suggestions").size());
    }

    @Test
    @DisplayName("Another department's Manager sees only their own department and nothing about the first one")
    public void otherManagersSeeOnlyTheirOwn() throws Exception {
        // REC_DON is not under pressure: nothing at all, and certainly nothing about REC_RECV
        String donorView = body("rec_md", "MANAGER");
        JsonNode donor = mapper.readTree(donorView);
        assertEquals(0, donor.get("recommendations").size());
        assertEquals(0, donor.get("unsupported").size());
        for (String forbidden : List.of(RECV, "REC own task", "REC waiting", "REC loan to recv task", OTH)) assertFalse(donorView.contains(forbidden), forbidden);

        // REC_OTH is under pressure: only itself, evaluated on its own (other departments' requests do not use up its helpers)
        String othView = body("rec_mo", "MANAGER");
        JsonNode other = mapper.readTree(othView);
        assertEquals(1, other.get("recommendations").size());
        assertEquals(OTH, other.get("recommendations").get(0).get("departmentCode").asText());
        assertEquals(0, other.get("unsupported").size());
        for (String forbidden : List.of(RECV, "\"" + DON + "\"", "REC waiting", "REC donor secret task")) assertFalse(othView.contains(forbidden), forbidden);
    }

    @Test
    @DisplayName("No other department's task titles, pressure, names, codes or staff ids appear anywhere in a Manager's response")
    public void noLeakageInNestedFields() throws Exception {
        String response = body("rec_mr", "MANAGER");
        for (String forbidden : List.of("REC donor secret task", "REC other secret", "REC other loan task", "REC other own task", "REC nodept task",
                "\"" + OTH + "\"", OTH + " name", "\"" + DON + "\"", "rec_o1")) {
            assertFalse(response.contains(forbidden), "leaked: " + forbidden);
        }
        // no donor staff id, whatever the field
        for (Staff donor : List.of(d1, d2, d3, d4, o1)) assertFalse(response.contains(donor.getId().toString()), "leaked donor id");
        // the donor department code never appears either (only its display name)
        assertFalse(response.contains("\"" + DON + "\""));
    }

    @Test
    @DisplayName("Donor identity is limited to the helper's name and home department name; donor impact figures are absent and the reason text carries no donor figure")
    public void donorIdentityIsMinimal() throws Exception {
        JsonNode suggestion = entry(json("rec_mr", "MANAGER"), RECV).get("suggestions").get(0);
        assertTrue(suggestion.get("staffId").isNull());
        assertTrue(suggestion.get("homeDepartmentCode").isNull());
        assertTrue(suggestion.get("donorImpact").isNull(), "no donor free-staff, available-staff or waiting figures");
        assertFalse(suggestion.get("staffName").isNull());
        assertFalse(suggestion.get("homeDepartmentName").isNull());
        assertEquals("Currently free and available to help.", suggestion.get("reasons").get(0).asText());
        assertEquals(2, suggestion.get("reasons").size());
        String reasons = suggestion.get("reasons").toString();
        assertFalse(reasons.contains("waiting task"), "the donor's waiting-task count is not revealed");
        assertFalse(reasons.contains("no active task"), "nor its free-staff count");
        assertFalse(reasons.contains("reserve"));
        assertFalse(reasons.contains(DON));
        // the receiver's own task title and its own impact figures are the Manager's own data and remain
        assertTrue(suggestion.get("coversTaskTitle").asText().startsWith("REC waiting"));
        assertEquals(2, suggestion.get("receiverImpact").get("uncoveredTasksBefore").asInt());
    }

    @Test
    @DisplayName("The global count of tasks with no department is not exposed to a Manager")
    public void departmentLessCountIsNotExposed() throws Exception {
        JsonNode response = json("rec_mr", "MANAGER");
        assertFalse(response.get("limitations").toString().contains("no department"));
        assertFalse(response.toString().contains("REC nodept task"));
        assertTrue(response.get("limitations").size() >= 1, "the general limitations remain");
    }

    @Test
    @DisplayName("Support-loan and department-less behaviour stays consistent with Steps 2E and 2F: loaned people are busy, the department-less task never waits")
    public void loanAndDepartmentLessConsistency() throws Exception {
        JsonNode own = entry(json("rec_mr", "MANAGER"), RECV);
        assertEquals(5, own.get("pressure").get("activeStaff").asInt(), "r1, r2 (on loan elsewhere but still RECV's person), the Manager, the inactive-account Manager and the Staff user");
        assertEquals(0, own.get("pressure").get("freeStaff").asInt(), "a person lent out holds work, so is not free");
        assertEquals(2, own.get("pressure").get("waitingTasks").asInt(), "the loan task held by d3 is not waiting; the department-less task is not counted");
        // the workload endpoint (Step 2E) for the same Manager is unchanged: r2's loan work is excluded
        MvcResult workload = mockMvc.perform(get("/api/workload").with(user("rec_mr").roles("MANAGER"))).andReturn();
        assertEquals(200, workload.getResponse().getStatus());
        for (JsonNode row : mapper.readTree(workload.getResponse().getContentAsString())) {
            if (row.get("staffId").asText().equals(r2.getId().toString())) assertEquals(0, row.get("activeTaskCount").asInt());
        }
    }

    // ── refusals ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Invalid Managers get 403: no Staff record, inactive account, inactive Staff record, inactive department")
    public void invalidManagersAreRefused() throws Exception {
        for (String manager : List.of("rec_m_nostaff", "rec_m_inactive_account", "rec_m_inactive_staff", "rec_m_closed")) {
            MvcResult result = call(manager, "MANAGER");
            assertEquals(403, result.getResponse().getStatus(), manager);
            assertFalse(result.getResponse().getContentAsString().contains("recommendations"), manager);
        }
    }

    @Test
    @DisplayName("Staff get 403 and no sign-in gets 401")
    public void staffAndAnonymousAreRefused() throws Exception {
        assertEquals(403, call("rec_staff_user", "STAFF").getResponse().getStatus());
        assertEquals(401, mockMvc.perform(get(URL)).andReturn().getResponse().getStatus());
    }

    @Test
    @DisplayName("A request parameter cannot widen a Manager's scope")
    public void parametersDoNotWiden() throws Exception {
        String plain = body("rec_mr", "MANAGER");
        MvcResult widened = mockMvc.perform(get(URL + "?department=" + OTH + "&all=true&admin=true").with(user("rec_mr").roles("MANAGER"))).andReturn();
        JsonNode a = mapper.readTree(plain);
        JsonNode b = mapper.readTree(widened.getResponse().getContentAsString());
        assertEquals(a.get("recommendations"), b.get("recommendations"));
        assertEquals(a.get("unsupported"), b.get("unsupported"));
    }

    // ── direct service calls and scope resolution ────────────────────────────

    @Test
    @DisplayName("Direct service calls enforce the same rules without HTTP")
    public void directServiceCalls() {
        signInAs("rec_admin", "ADMIN");
        WorkloadRecommendationsDto global = recommendationService.getRecommendations();
        assertTrue(global.recommendations().stream().anyMatch(r -> RECV.equals(r.departmentCode())));
        assertNotNull(global.recommendations().get(0).suggestions().get(0).staffId());

        signInAs("rec_mr", "MANAGER");
        WorkloadRecommendationsDto own = recommendationService.getRecommendations();
        assertEquals(1, own.recommendations().size());
        assertEquals(RECV, own.recommendations().get(0).departmentCode());
        assertNull(own.recommendations().get(0).suggestions().get(0).staffId());
        assertNull(own.recommendations().get(0).suggestions().get(0).donorImpact());

        signInAs("rec_m_nostaff", "MANAGER");
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> recommendationService.getRecommendations()).getStatusCode().value());
        signInAs("rec_m_closed", "MANAGER");
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> recommendationService.getRecommendations()).getStatusCode().value());
        signInAs("rec_staff_user", "STAFF");
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> recommendationService.getRecommendations()).getStatusCode().value());
        SecurityContextHolder.clearContext();
        assertEquals(401, assertThrows(ResponseStatusException.class, () -> recommendationService.getRecommendations()).getStatusCode().value());
    }

    @Test
    @DisplayName("A request resolves the caller's scope exactly once, for Admin and Manager")
    public void scopeIsResolvedOnce() throws Exception {
        for (String[] caller : new String[][] {{"rec_admin", "ADMIN"}, {"rec_mr", "MANAGER"}}) {
            assertEquals(200, call(caller[0], caller[1]).getResponse().getStatus());
            verify(scopeResolver, times(1)).resolve();
        }
        signInAs("rec_mr", "MANAGER");
        clearInvocations(scopeResolver);
        recommendationService.getRecommendations();
        verify(scopeResolver, times(1)).resolve();
    }

    // ── repository level ─────────────────────────────────────────────────────

    @Test
    @DisplayName("The new queries filter in the database: waiting tasks of one department, waiting counts only, and busy staff ids")
    public void repositoryQueries() {
        List<String> titles = taskRepository.findWaitingByDepartment(recv, ACTIVE).stream().map(Task::getTitle).sorted().collect(Collectors.toList());
        assertEquals(List.of("REC waiting normal", "REC waiting urgent"), titles, "only RECV's unassigned active tasks");
        assertTrue(taskRepository.findWaitingByDepartment(don, ACTIVE).stream().allMatch(t -> DON.equals(t.getDepartment().getCode())));

        Map<String, Integer> counts = taskRepository.countWaitingByDepartmentCode(ACTIVE).stream()
                .collect(Collectors.toMap(row -> (String) row[0], row -> ((Number) row[1]).intValue()));
        assertEquals(2, counts.get(RECV));
        assertEquals(2, counts.get(DON));
        assertEquals(3, counts.get(OTH));
        assertFalse(counts.containsKey(null), "tasks with no department are never counted");

        Set<UUID> busy = Set.copyOf(assignmentRepository.findBusyStaffIds(ACTIVE));
        assertTrue(busy.containsAll(List.of(r1.getId(), r2.getId(), d3.getId(), o1.getId())));
        assertFalse(busy.contains(d1.getId()));
        assertFalse(busy.contains(d2.getId()));
    }
}
