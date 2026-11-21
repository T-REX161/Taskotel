package com.hotel.workload.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.workload.assignment.AssignmentRepository;
import com.hotel.workload.security.JwtUtil;
import com.hotel.workload.security.Role;
import com.hotel.workload.security.ScopeFixtures;
import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
import com.hotel.workload.staff.Department;
import com.hotel.workload.staff.DepartmentRepository;
import com.hotel.workload.staff.Staff;
import com.hotel.workload.staff.StaffRepository;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * The whole Cross-Department Support life cycle through the real HTTP endpoints, on isolated data: every row is created by the test inside a
 * transaction that is rolled back, in the in-memory test database. Nothing is written to a development or production database.
 *
 * <p>Scenario: the receiving department has one busy person and more waiting tasks than free staff; a donor department has eligible, available,
 * free STAFF. Receiving department "needs support" -> the engine suggests helpers -> the Administrator confirms -> the helper holds the receiving
 * department's task, active support and workload attribution show it -> support is ended -> the helper is released. The donor's reserve rule and
 * Admin / Manager permissions are checked along the way.
 */
@SpringBootTest
@Transactional
public class CrossDepartmentSupportScenarioTest {

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository users;
    @Autowired private StaffRepository staffRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private AssignmentRepository assignmentRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private SupportAssignmentRepository supportRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private JwtUtil jwt;
    @Autowired private PasswordEncoder passwordEncoder;

    private final ObjectMapper json = new ObjectMapper();
    private MockMvc mvc;
    private SupportTestFixture fx;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        fx = new SupportTestFixture(users, staffRepository, taskRepository, assignmentRepository, departmentRepository, supportRepository);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────────────

    private String token(String name, Role role) {
        User user = new User();
        user.setUsername(name + fx.tag.toLowerCase());
        user.setEmail(name + fx.tag + "@t.test");
        user.setPassword("x");
        user.setRole(role);
        user.setActive(true);
        user.setEmailVerified(true);
        user.setCredentialsSetupRequired(false);
        users.saveAndFlush(user);
        return "Bearer " + jwt.generateToken(user.getUsername(), role);
    }

    /** A valid Manager of the department: an active account with an active Staff record in it. */
    private String managerToken(String name, Department department) {
        String bearer = token(name, Role.MANAGER);
        new ScopeFixtures(users, staffRepository, departmentRepository, passwordEncoder).staffRecord(name + fx.tag.toLowerCase(), Role.MANAGER, department);
        return bearer;
    }

    private MockHttpServletRequestBuilder req(HttpMethod method, String url, String bearer, Object body) throws Exception {
        MockHttpServletRequestBuilder builder = request(method, url);
        if (bearer != null) builder.header("Authorization", bearer);
        if (body != null) builder.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        return builder;
    }

    private MvcResult call(HttpMethod method, String url, String bearer, Object body, int expectedStatus) throws Exception {
        MvcResult result = mvc.perform(req(method, url, bearer, body)).andReturn();
        assertEquals(expectedStatus, result.getResponse().getStatus(), method + " " + url + " -> " + result.getResponse().getContentAsString());
        return result;
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode get(String url, String bearer) throws Exception {
        return body(call(HttpMethod.GET, url, bearer, null, 200));
    }

    private Map<String, Object> plan(Department receiving, Object... staffAndTasks) {
        List<Map<String, String>> items = new ArrayList<>();
        for (int i = 0; i < staffAndTasks.length; i += 2) {
            items.add(Map.of("staffId", ((Staff) staffAndTasks[i]).getId().toString(), "taskId", ((Task) staffAndTasks[i + 1]).getId().toString()));
        }
        return Map.of("receivingDepartment", receiving.getCode(), "items", items);
    }

    private static JsonNode byField(JsonNode array, String field, String value) {
        for (JsonNode node : array) if (value.equals(node.path(field).asText(null))) return node;
        return null;
    }

    private static Set<String> codes(JsonNode problems) {
        Set<String> result = new HashSet<>();
        for (JsonNode problem : problems) result.add(problem.path("code").asText());
        return result;
    }

    private void refresh() {
        entityManager.flush();
        entityManager.clear();
    }

    private JsonNode workloadRow(String adminBearer, Staff member) throws Exception {
        return byField(get("/api/workload", adminBearer), "staffId", member.getId().toString());
    }

    private JsonNode donorEntry(String adminBearer, Department receiving, Department donor) throws Exception {
        return byField(get("/api/support/options?receivingDepartment=" + receiving.getCode(), adminBearer).path("donors"), "code", donor.getCode());
    }

    // ── the full life cycle, as the Administrator ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Overloaded receiver -> needs support -> right helpers suggested -> reserve kept -> confirmed -> helper holds the task, support and workload update -> ended -> helper released")
    void fullLifecycleAsAdmin() throws Exception {
        String admin = token("scn_admin", Role.ADMIN);
        // Receiving: 1 busy person, 3 waiting tasks, 0 free staff -> 3 uncovered. Donor: 3 eligible, available, free STAFF and nothing waiting.
        var s = fx.overloaded(3, 3);
        Set<String> donorIds = new HashSet<>();
        for (Staff donor : s.donors) donorIds.add(donor.getId().toString());
        Task urgent = s.tasks.get(0);

        // 1. The receiving department is listed as needing support, with the donor's free staff as the suggestions
        JsonNode recommendations = get("/api/workload/recommendations", admin);
        JsonNode need = byField(recommendations.path("recommendations"), "departmentCode", s.receiving.getCode());
        assertNotNull(need, "the overloaded department appears under Departments that need support");
        assertEquals(3, need.path("pressure").path("uncoveredTasks").asInt(), "3 waiting tasks, 0 free staff: all uncovered");
        assertEquals(3, need.path("pressure").path("waitingTasks").asInt());
        assertEquals("CRITICAL", need.path("severity").asText(), "the first waiting task is URGENT");

        // 2. The correct helpers: only the donor's free STAFF, the most important waiting task first, and the donor's reserve is respected
        JsonNode suggestions = need.path("suggestions");
        assertEquals(2, suggestions.size(), "3 uncovered tasks, but a donor with 3 free people may lend only 2 and must keep 1 in reserve");
        Set<String> suggested = new HashSet<>();
        for (JsonNode suggestion : suggestions) {
            assertEquals(s.donor.getCode(), suggestion.path("homeDepartmentCode").asText());
            assertTrue(donorIds.contains(suggestion.path("staffId").asText()), "a free eligible person of the donor department");
            suggested.add(suggestion.path("staffId").asText());
        }
        assertEquals(2, suggested.size(), "one person is never suggested twice");
        assertEquals(urgent.getTitle(), suggestions.get(0).path("coversTaskTitle").asText(), "the most important waiting task is covered first");
        assertNull(byField(recommendations.path("unsupported"), "departmentCode", s.receiving.getCode()), "it has donors, so it is not 'unsupported'");

        // 3. Options (the planning screen) agree: 3 uncovered, the donor can lend at most 2
        JsonNode options = get("/api/support/options?receivingDepartment=" + s.receiving.getCode(), admin);
        assertEquals(3, options.path("receiving").path("metrics").path("uncoveredTasks").asInt());
        assertEquals(3, options.path("tasks").size());
        JsonNode donorBefore = byField(options.path("donors"), "code", s.donor.getCode());
        assertEquals(3, donorBefore.path("freeStaff").asInt());
        assertEquals(2, donorBefore.path("maxLendable").asInt(), "the donor keeps its reserve");

        // 4. Reserve rule: asking for all three donors is refused with the reserve problem, and nothing is created
        JsonNode threeLent = body(call(HttpMethod.POST, "/api/support/preview", admin,
                plan(s.receiving, s.donors.get(0), s.tasks.get(0), s.donors.get(1), s.tasks.get(1), s.donors.get(2), s.tasks.get(2)), 200));
        assertFalse(threeLent.path("valid").asBoolean());
        assertTrue(codes(threeLent.path("problems")).contains("DONOR_RESERVE"), "lending the whole free team is refused");
        JsonNode refused = body(call(HttpMethod.POST, "/api/support/assignments", admin,
                plan(s.receiving, s.donors.get(0), s.tasks.get(0), s.donors.get(1), s.tasks.get(1), s.donors.get(2), s.tasks.get(2)), 409));
        assertTrue(codes(refused.path("problems")).contains("DONOR_RESERVE"));
        refresh();
        assertEquals(0, supportRepository.findByStatus(SupportStatus.ACTIVE).stream().filter(r -> r.getReceivingDepartmentCode().equals(s.receiving.getCode())).count(), "a refused plan creates nothing");

        // 5. Preview of the valid two-helper plan changes nothing, then the Administrator confirms it
        Staff helperA = s.donors.get(0);
        Staff helperB = s.donors.get(1);
        Staff reserve = s.donors.get(2);
        Object validPlan = plan(s.receiving, helperA, s.tasks.get(0), helperB, s.tasks.get(1));
        JsonNode preview = body(call(HttpMethod.POST, "/api/support/preview", admin, validPlan, 200));
        assertTrue(preview.path("valid").asBoolean());
        refresh();
        assertEquals(0, supportRepository.findByStatus(SupportStatus.ACTIVE).stream().filter(r -> r.getReceivingDepartmentCode().equals(s.receiving.getCode())).count(), "a preview creates nothing");

        JsonNode created = body(call(HttpMethod.POST, "/api/support/assignments", admin, validPlan, 201));
        assertEquals(2, created.size());
        for (JsonNode record : created) {
            assertEquals("ACTIVE", record.path("status").asText());
            assertEquals(s.receiving.getCode(), record.path("receivingDepartmentCode").asText());
            assertEquals(s.donor.getCode(), record.path("homeDepartmentCode").asText());
        }

        // 6. The helper now holds the receiving department's task; the home department of the helper is unchanged
        refresh();
        for (int i = 0; i < 2; i++) {
            Staff helper = i == 0 ? helperA : helperB;
            Task task = s.tasks.get(i);
            assertEquals(helper.getId(), assignmentRepository.findByTaskId(task.getId()).orElseThrow().getStaff().getId(), "the helper is assigned to the receiving department's task");
            assertEquals(TaskStatus.ASSIGNED, taskRepository.findById(task.getId()).orElseThrow().getStatus());
            assertEquals(s.donor.getCode(), staffRepository.findById(helper.getId()).orElseThrow().getDepartment().getCode(), "permanent department unchanged");
        }
        assertFalse(assignmentRepository.existsByTaskId(s.tasks.get(2).getId()), "the third task stays waiting");

        // 7. Active support is listed, and the workload attributes the helpers' minutes to the receiving department
        JsonNode active = get("/api/support/assignments?status=ACTIVE", admin);
        assertNotNull(byField(active, "staffId", helperA.getId().toString()));
        assertNotNull(byField(active, "staffId", helperB.getId().toString()));
        JsonNode rowA = workloadRow(admin, helperA);
        assertEquals(s.donor.getCode(), rowA.path("department").asText(), "home department shown unchanged");
        assertEquals(s.receiving.getCode(), rowA.path("supportDepartment").asText(), "attributed to the department being helped");
        assertEquals(1, rowA.path("activeTaskCount").asInt());
        assertEquals(s.tasks.get(0).getEstimatedDuration(), rowA.path("totalEstimatedDuration").asInt(), "the task's minutes, counted once");
        assertEquals(s.tasks.get(1).getEstimatedDuration(), workloadRow(admin, helperB).path("totalEstimatedDuration").asInt());
        JsonNode reserveRow = workloadRow(admin, reserve);
        assertEquals(0, reserveRow.path("activeTaskCount").asInt(), "the donor's reserve person is untouched and still free");
        assertTrue(reserveRow.path("supportDepartment").isNull() || reserveRow.path("supportDepartment").asText().isEmpty());

        // 8. Donor reserve after confirming: exactly one free person remains, so no further person can be lent
        JsonNode donorAfter = donorEntry(admin, s.receiving, s.donor);
        assertEquals(1, donorAfter.path("freeStaff").asInt(), "the donor kept its reserve");
        assertEquals(0, donorAfter.path("maxLendable").asInt(), "it cannot lend the last free person");
        JsonNode afterRecs = get("/api/workload/recommendations", admin);
        assertNull(byField(afterRecs.path("recommendations"), "departmentCode", s.receiving.getCode()), "no helper can be suggested any more");
        JsonNode stillNeeds = byField(afterRecs.path("unsupported"), "departmentCode", s.receiving.getCode());
        assertNotNull(stillNeeds, "one task is still uncovered and nobody can lend, so it is reported as needing support without a helper");
        assertEquals(1, stillNeeds.path("pressure").path("uncoveredTasks").asInt());

        // 9. Ending the support releases the helper: the task waits again, the assignment is gone, the workload is back to normal
        for (JsonNode record : created) {
            JsonNode ended = body(call(HttpMethod.POST, "/api/support/assignments/" + record.path("id").asText() + "/end", admin, null, 200));
            assertEquals("CANCELLED", ended.path("status").asText());
            assertEquals("MANUALLY_ENDED", ended.path("endReason").asText());
            assertNotNull(ended.path("completedAt").asText(null));
        }
        refresh();
        for (int i = 0; i < 2; i++) {
            Task task = s.tasks.get(i);
            assertFalse(assignmentRepository.existsByTaskId(task.getId()), "no assignment is left behind");
            assertEquals(TaskStatus.PENDING, taskRepository.findById(task.getId()).orElseThrow().getStatus(), "the task waits in its own department again");
        }
        assertEquals(0, supportRepository.findByStatus(SupportStatus.ACTIVE).stream().filter(r -> r.getReceivingDepartmentCode().equals(s.receiving.getCode())).count());
        JsonNode releasedRow = workloadRow(admin, helperA);
        assertEquals(0, releasedRow.path("activeTaskCount").asInt(), "the helper is free again");
        assertEquals(0, releasedRow.path("totalEstimatedDuration").asInt());
        assertTrue(releasedRow.path("supportDepartment").isNull() || releasedRow.path("supportDepartment").asText().isEmpty(), "no longer attributed to the receiving department");
        assertEquals(s.donor.getCode(), releasedRow.path("department").asText());
        assertEquals(3, donorEntry(admin, s.receiving, s.donor).path("freeStaff").asInt(), "the donor has all its free staff back");
        JsonNode rebuilt = byField(get("/api/workload/recommendations", admin).path("recommendations"), "departmentCode", s.receiving.getCode());
        assertNotNull(rebuilt, "the receiving department needs support again");
        assertEquals(3, rebuilt.path("pressure").path("uncoveredTasks").asInt());
    }

    // ── permissions ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Permissions: only the Admin confirms; the receiving Manager previews and ends; the donor and unrelated Managers see only what they should; Staff and anonymous are refused")
    void adminAndManagerPermissions() throws Exception {
        String admin = token("scn_admin", Role.ADMIN);
        var s = fx.overloaded(2, 3);
        Department unrelated = fx.department("X");
        String receivingManager = managerToken("scn_rm", s.receiving);
        String donorManager = managerToken("scn_dm", s.donor);
        String unrelatedManager = managerToken("scn_um", unrelated);
        String staffBearer = token("scn_staff", Role.STAFF);
        // Each manager is also a free person of their own department, so the receiving department has 1 free person and 2 waiting tasks
        Object onePlan = plan(s.receiving, s.donors.get(0), s.tasks.get(0));

        // The receiving Manager can read options and preview, but cannot confirm: nothing is created
        call(HttpMethod.GET, "/api/support/options?receivingDepartment=" + s.receiving.getCode(), receivingManager, null, 200);
        assertTrue(body(call(HttpMethod.POST, "/api/support/preview", receivingManager, onePlan, 200)).path("valid").asBoolean());
        call(HttpMethod.POST, "/api/support/assignments", receivingManager, onePlan, 403);
        call(HttpMethod.POST, "/api/support/assignments", donorManager, onePlan, 403);
        refresh();
        assertEquals(0, supportRepository.findByStatus(SupportStatus.ACTIVE).stream().filter(r -> r.getReceivingDepartmentCode().equals(s.receiving.getCode())).count());

        // Staff and anonymous callers are refused everything
        call(HttpMethod.GET, "/api/support/options?receivingDepartment=" + s.receiving.getCode(), staffBearer, null, 403);
        call(HttpMethod.POST, "/api/support/assignments", staffBearer, onePlan, 403);
        call(HttpMethod.GET, "/api/workload/recommendations", staffBearer, null, 403);
        call(HttpMethod.GET, "/api/support/assignments", null, null, 401);
        call(HttpMethod.POST, "/api/support/assignments", null, onePlan, 401);

        // The Admin confirms
        JsonNode created = body(call(HttpMethod.POST, "/api/support/assignments", admin, onePlan, 201));
        String supportId = created.get(0).path("id").asText();
        assertEquals("scn_admin" + fx.tag.toLowerCase(), created.get(0).path("approvedBy").asText(), "the Admin sees who approved it");

        // The receiving Manager sees it as a received record: the task, but never the approver
        JsonNode receivingView = byField(get("/api/support/assignments?status=ACTIVE", receivingManager), "id", supportId);
        assertNotNull(receivingView);
        assertEquals(s.tasks.get(0).getTitle(), receivingView.path("taskTitle").asText());
        assertTrue(receivingView.path("approvedBy").isNull(), "a Manager never sees the approver");

        // The donor Manager sees only the loan of their person: no task, no receiving department
        JsonNode donorView = byField(get("/api/support/assignments?status=ACTIVE", donorManager), "id", supportId);
        assertNotNull(donorView);
        assertEquals(s.donors.get(0).getName(), donorView.path("staffName").asText());
        assertTrue(donorView.path("taskTitle").isNull() && donorView.path("receivingDepartmentCode").isNull(), "nothing about the receiving department's work");

        // An unrelated Manager sees nothing of it and cannot end it (an unknown record and an unrelated one look the same)
        assertNull(byField(get("/api/support/assignments?status=ACTIVE", unrelatedManager), "id", supportId));
        call(HttpMethod.POST, "/api/support/assignments/" + supportId + "/end", unrelatedManager, null, 404);
        call(HttpMethod.POST, "/api/support/assignments/" + supportId + "/end", staffBearer, null, 403);
        call(HttpMethod.POST, "/api/support/assignments/" + supportId + "/end", null, null, 401);
        refresh();
        assertEquals(SupportStatus.ACTIVE, supportRepository.findById(UUID.fromString(supportId)).orElseThrow().getStatus(), "refused calls change nothing");

        // The receiving Manager ends it: the helper is released and the task waits again
        JsonNode ended = body(call(HttpMethod.POST, "/api/support/assignments/" + supportId + "/end", receivingManager, null, 200));
        assertEquals("CANCELLED", ended.path("status").asText());
        assertTrue(ended.path("approvedBy").isNull());
        refresh();
        assertFalse(assignmentRepository.existsByTaskId(s.tasks.get(0).getId()));
        assertEquals(TaskStatus.PENDING, taskRepository.findById(s.tasks.get(0).getId()).orElseThrow().getStatus());
        assertEquals(0, workloadRow(admin, s.donors.get(0)).path("activeTaskCount").asInt(), "the helper is free again");
    }
}
