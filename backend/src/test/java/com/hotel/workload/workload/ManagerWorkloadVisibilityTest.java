package com.hotel.workload.workload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.workload.assignment.Assignment;
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
import com.hotel.workload.task.Priority;
import com.hotel.workload.task.Task;
import com.hotel.workload.task.TaskRepository;
import com.hotel.workload.task.TaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * Strict department visibility on the Manager's Workload page, over HTTP with real JWTs. The page is fed by two endpoints,
 * {@code GET /api/workload} (the staff rows) and {@code GET /api/workload/departments} (the department cards). A Manager gets only the
 * departments they manage in both, decided by the backend: the unauthorized departments' codes, names, staff and figures must not appear
 * anywhere in the response text, not even for a department with no workload at all.
 *
 * <p>Fixture. HOME (primary): staff h1 holding a 30-minute task. GRANT: staff g1 holding a 45-minute task. GEMPTY: granted, nobody works
 * there. OTHER: staff o1 holding a 60-minute task (never granted). EMPTY: no staff, no tasks (never granted). SHUT: staff s1 holding a
 * 20-minute task, granted and then made inactive.
 */
@SpringBootTest
@Transactional
public class ManagerWorkloadVisibilityTest {

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private AssignmentRepository assignmentRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtUtil jwt;

    private final ObjectMapper json = new ObjectMapper();
    private MockMvc mvc;
    private Department home, grant, gempty, other, empty, shut;
    private Staff h1, g1, o1, s1;
    private User managerUser, adminUser;
    private String admin, manager, otherManager, staffToken;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        ScopeFixtures sf = new ScopeFixtures(userRepository, staffRepository, departmentRepository, passwordEncoder);
        home = sf.department("WV_HOME");
        grant = sf.department("WV_GRANT");
        gempty = sf.department("WV_GEMPTY");
        other = sf.department("WV_OTHER");
        empty = sf.department("WV_EMPTY");
        shut = sf.department("WV_SHUT");
        adminUser = sf.account("wv_admin", Role.ADMIN, true);
        managerUser = sf.manager("wv_mgr", home).getUser();
        sf.manager("wv_mgr_other", other);
        h1 = sf.staff("wv_h1", home);
        g1 = sf.staff("wv_g1", grant);
        o1 = sf.staff("wv_o1", other);
        s1 = sf.staff("wv_s1", shut);
        hold(h1, home, 30);
        hold(g1, grant, 45);
        hold(o1, other, 60);
        hold(s1, shut, 20);
        admin = bearer("wv_admin", Role.ADMIN);
        manager = bearer("wv_mgr", Role.MANAGER);
        otherManager = bearer("wv_mgr_other", Role.MANAGER);
        staffToken = bearer("wv_h1", Role.STAFF);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String bearer(String username, Role role) {
        return "Bearer " + jwt.generateToken(username, role);
    }

    private void hold(Staff staff, Department department, int minutes) {
        Task task = new Task();
        task.setTitle("WV task of " + staff.getUser().getUsername());
        task.setPriority(Priority.MEDIUM);
        task.setStatus(TaskStatus.ASSIGNED);
        task.setEstimatedDuration(minutes);
        task.setDueDate(LocalDate.of(2027, 1, 1));
        task.setDepartment(department);
        task.setCreatedBy(adminUser);
        task = taskRepository.saveAndFlush(task);
        Assignment assignment = new Assignment();
        assignment.setTask(task);
        assignment.setStaff(staff);
        assignmentRepository.saveAndFlush(assignment);
    }

    private MvcResult call(HttpMethod method, String url, String token, Object body) throws Exception {
        MockHttpServletRequestBuilder builder = request(method, url);
        if (token != null) builder.header("Authorization", token);
        if (body != null) builder.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        return mvc.perform(builder).andReturn();
    }

    private String text(String url, String token) throws Exception {
        MvcResult result = call(HttpMethod.GET, url, token, null);
        assertEquals(200, result.getResponse().getStatus(), url + ": " + result.getResponse().getContentAsString());
        return result.getResponse().getContentAsString();
    }

    private JsonNode get(String url, String token) throws Exception {
        return json.readTree(text(url, token));
    }

    private void grant(Department... departments) throws Exception {
        List<String> codes = new ArrayList<>();
        for (Department d : departments) codes.add(d.getCode());
        MvcResult result = call(HttpMethod.PUT, "/api/users/" + managerUser.getId() + "/view-departments", admin, Map.of("departments", codes));
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
    }

    private static Set<String> field(JsonNode array, String name) {
        Set<String> values = new HashSet<>();
        for (JsonNode node : array) values.add(node.get(name).asText());
        return values;
    }

    private static long minutes(JsonNode rows) {
        long total = 0;
        for (JsonNode row : rows) total += row.get("totalEstimatedDuration").asLong();
        return total;
    }

    /** Nothing about these departments or their people appears anywhere in the text: not a code, not a name, not a staff member. */
    private void assertAbsent(String responseText, String what, Department... departments) {
        for (Department d : departments) {
            assertFalse(responseText.contains(d.getCode()), what + " leaks the code " + d.getCode());
            assertFalse(responseText.contains(d.getName()), what + " leaks the name " + d.getName());
        }
    }

    private static final List<String> MANAGER_VIEWS = List.of("/api/workload", "/api/workload/departments", "/api/staff", "/api/tasks",
            "/api/assignments", "/api/dashboard/summary");

    // ── 1. a Manager without grants ──────────────────────────────────────────

    @Test
    @DisplayName("A Manager without grants sees only their primary department: its card, its staff, its workload, nothing else")
    void primaryOnly() throws Exception {
        JsonNode departments = get("/api/workload/departments", manager);
        assertEquals(Set.of(home.getCode()), field(departments, "code"));
        assertTrue(departments.get(0).get("managed").asBoolean());

        JsonNode rows = get("/api/workload", manager);
        assertEquals(Set.of(h1.getId().toString()), field(rows, "staffId"));
        assertEquals(30, minutes(rows));

        for (String url : MANAGER_VIEWS) {
            assertAbsent(text(url, manager), url, grant, gempty, other, empty, shut);
        }
    }

    // ── 2./3./4. primary and active grants; the rest completely absent ───────

    @Test
    @DisplayName("With grants a Manager sees the primary and every active granted department, including a granted one nobody works in")
    void primaryAndGrants() throws Exception {
        grant(grant, gempty);

        JsonNode departments = get("/api/workload/departments", manager);
        assertEquals(Set.of(home.getCode(), grant.getCode(), gempty.getCode()), field(departments, "code"));
        for (JsonNode d : departments) assertTrue(d.get("managed").asBoolean(), d.get("code").asText());

        JsonNode rows = get("/api/workload", manager);
        assertEquals(Set.of(h1.getId().toString(), g1.getId().toString()), field(rows, "staffId"), "the granted department with nobody in it adds no row");
        assertEquals(75, minutes(rows), "30 + 45 minutes: authorized departments only");

        // Each card's own figures are the department's real ones (what an Admin sees for it)
        JsonNode adminDepartments = get("/api/workload/departments", admin);
        for (JsonNode card : departments) {
            JsonNode same = null;
            for (JsonNode a : adminDepartments) if (a.get("code").asText().equals(card.get("code").asText())) same = a;
            assertEquals(same.get("staffCount"), card.get("staffCount"), card.get("code").asText());
            assertEquals(same.get("activeTasks"), card.get("activeTasks"), card.get("code").asText());
        }
    }

    @Test
    @DisplayName("The Manager's departments come in the department list's usual order: creation time first, then name")
    void departmentsFollowTheUsualOrder() throws Exception {
        grant(grant, gempty);
        // Explicit creation times, so nothing depends on the clock (fixture departments created back to back can share one): GRANT was
        // created before GEMPTY although GEMPTY comes first by name, so creation time must win
        java.time.LocalDateTime base = java.time.LocalDateTime.of(2026, 1, 1, 8, 0);
        home.setCreatedAt(base);
        grant.setCreatedAt(base.plusMinutes(1));
        gempty.setCreatedAt(base.plusMinutes(2));
        departmentRepository.saveAllAndFlush(List.of(home, grant, gempty));
        assertEquals(List.of(home.getCode(), grant.getCode(), gempty.getCode()), codesInOrder(get("/api/workload/departments", manager)));

        // The same creation time: then by name
        gempty.setCreatedAt(base.plusMinutes(1));
        departmentRepository.saveAndFlush(gempty);
        assertEquals(List.of(home.getCode(), gempty.getCode(), grant.getCode()), codesInOrder(get("/api/workload/departments", manager)));
    }

    @Test
    @DisplayName("Unauthorized departments are completely absent, including one with no workload at all, and their staff and figures with them")
    void unauthorizedDepartmentsAreAbsent() throws Exception {
        grant(grant);
        for (String url : MANAGER_VIEWS) {
            String body = text(url, manager);
            assertAbsent(body, url, other, empty, gempty, shut);
            assertFalse(body.contains("wv_o1") || body.contains(o1.getName()), url + " names an unauthorized staff member");
            assertFalse(body.contains(o1.getId().toString()), url + " carries an unauthorized staff id");
        }
        assertFalse(text("/api/workload", manager).contains("\"totalEstimatedDuration\":60"), "no unauthorized workload figure");

        // The strictest case: a department with no staff and no tasks is just as absent
        JsonNode departments = get("/api/workload/departments", manager);
        assertFalse(field(departments, "code").contains(empty.getCode()));
        assertEquals(2, departments.size());
    }

    // ── 5./6. totals and counts ──────────────────────────────────────────────

    @Test
    @DisplayName("The totals the page builds from the response (load, staff count, pages) cover the authorized departments only")
    void totalsCoverAuthorizedDepartmentsOnly() throws Exception {
        grant(grant);
        JsonNode rows = get("/api/workload", manager);
        assertEquals(2, rows.size(), "the staff count, and so every page count derived from it, covers authorized staff only");
        assertEquals(75, minutes(rows), "Total Department Load: 30 + 45, without OTHER's 60 or SHUT's 20");
        long homeShare = 0, grantShare = 0;
        for (JsonNode row : rows) {
            if (row.get("department").asText().equals(home.getCode())) homeShare += row.get("totalEstimatedDuration").asLong();
            if (row.get("department").asText().equals(grant.getCode())) grantShare += row.get("totalEstimatedDuration").asLong();
        }
        assertEquals(30, homeShare);
        assertEquals(45, grantShare);
        assertEquals(2, get("/api/staff", manager).size(), "the staff list the page opens details from is scoped the same way");
        assertEquals(1, get("/api/workload", otherManager).size(), "another Manager: only their own department, whatever was granted to this one");
        assertEquals(60, minutes(get("/api/workload", otherManager)));
    }

    // ── 7./8. revocation and inactive grants ─────────────────────────────────

    @Test
    @DisplayName("Revoking a grant removes the department's card, staff and workload on the very next request")
    void revocationAppliesOnTheNextRequest() throws Exception {
        grant(grant, gempty);
        assertEquals(3, get("/api/workload/departments", manager).size());
        assertEquals(2, get("/api/workload", manager).size());

        grant(gempty); // revokes GRANT, keeps GEMPTY
        assertEquals(Set.of(home.getCode(), gempty.getCode()), field(get("/api/workload/departments", manager), "code"));
        assertEquals(Set.of(h1.getId().toString()), field(get("/api/workload", manager), "staffId"));
        assertAbsent(text("/api/workload", manager) + text("/api/workload/departments", manager), "after revocation", grant);

        grant();
        assertEquals(Set.of(home.getCode()), field(get("/api/workload/departments", manager), "code"));
    }

    @Test
    @DisplayName("An inactive granted department shows nothing (no card, no staff, no workload); an inactive primary department refuses everything")
    void inactiveDepartments() throws Exception {
        grant(grant, shut);
        assertEquals(Set.of(home.getCode(), grant.getCode(), shut.getCode()), field(get("/api/workload/departments", manager), "code"));

        shut.setActive(false);
        departmentRepository.saveAndFlush(shut);
        assertEquals(Set.of(home.getCode(), grant.getCode()), field(get("/api/workload/departments", manager), "code"));
        assertEquals(Set.of(h1.getId().toString(), g1.getId().toString()), field(get("/api/workload", manager), "staffId"));
        assertAbsent(text("/api/workload", manager) + text("/api/workload/departments", manager), "inactive grant", shut);

        home.setActive(false);
        departmentRepository.saveAndFlush(home);
        assertEquals(403, call(HttpMethod.GET, "/api/workload", manager, null).getResponse().getStatus());
        assertEquals(403, call(HttpMethod.GET, "/api/workload/departments", manager, null).getResponse().getStatus());
    }

    // ── 9./10. Admin and Staff unchanged ─────────────────────────────────────

    @Test
    @DisplayName("An Admin still sees every department and every staff member")
    void adminSeesEverything() throws Exception {
        grant(grant);
        JsonNode departments = get("/api/workload/departments", admin);
        Set<String> codes = field(departments, "code");
        assertTrue(codes.containsAll(Set.of(home.getCode(), grant.getCode(), gempty.getCode(), other.getCode(), empty.getCode(), shut.getCode())));
        for (JsonNode d : departments) assertFalse(d.has("managed"), "an Admin's answer carries no managed field");
        assertEquals(get("/api/departments", admin), departments, "exactly the list an Admin always had");
        assertTrue(field(get("/api/workload", admin), "staffId").containsAll(
                Set.of(h1.getId().toString(), g1.getId().toString(), o1.getId().toString(), s1.getId().toString())));
    }

    @Test
    @DisplayName("A Staff member's Workload page is unchanged: their own row, and the same department list as before")
    void staffUnchanged() throws Exception {
        grant(grant);
        assertEquals(get("/api/departments", staffToken), get("/api/workload/departments", staffToken), "the list a Staff member always had");
        for (JsonNode d : get("/api/workload/departments", staffToken)) assertFalse(d.has("managed"));
        JsonNode rows = get("/api/workload", staffToken);
        assertEquals(Set.of(h1.getId().toString()), field(rows, "staffId"), "their own row only");
    }

    @Test
    @DisplayName("The new endpoint needs a sign-in (anonymous is 401) and is read-only")
    void anonymousIsRefused() throws Exception {
        assertEquals(401, call(HttpMethod.GET, "/api/workload/departments", null, null).getResponse().getStatus());
        // Read-only: any other method is denied by the deny-by-default rules
        assertEquals(403, call(HttpMethod.POST, "/api/workload/departments", manager, Map.of()).getResponse().getStatus());
        assertEquals(403, call(HttpMethod.PUT, "/api/workload/departments", admin, Map.of()).getResponse().getStatus());
        assertEquals(403, call(HttpMethod.DELETE, "/api/workload/departments", manager, null).getResponse().getStatus());
    }

    private static List<String> codesInOrder(JsonNode array) {
        List<String> codes = new ArrayList<>();
        for (JsonNode node : array) codes.add(node.get("code").asText());
        return codes;
    }
}
