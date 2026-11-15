package com.hotel.workload.staff;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.workload.assignment.Assignment;
import com.hotel.workload.assignment.AssignmentRepository;
import com.hotel.workload.security.JwtUtil;
import com.hotel.workload.security.Role;
import com.hotel.workload.security.ScopeFixtures;
import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * Admin-controlled, view-only department access for Managers, exercised over HTTP with real JWTs (the whole security filter chain, URL
 * rules and services), against real (rolled-back) records.
 *
 * <p>Fixture: departments HOME (the Manager's own), GRANT (granted for viewing), OTHER (never granted) and SHUT (granted, then inactive).
 * HOME: staff sh, task th assigned to sh. GRANT: staff sg, a Manager mg, task tg assigned to sg and an unassigned task tg2. OTHER: staff
 * so and task to.
 */
@SpringBootTest
@Transactional
public class ManagerViewAccessTest {

    private static final String HOME = "VA_HOME";
    private static final String GRANT = "VA_GRANT";
    private static final String OTHER = "VA_OTHER";
    private static final String SHUT = "VA_SHUT";

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private AssignmentRepository assignmentRepository;
    @Autowired private ManagerViewGrantRepository grantRepository;
    @Autowired private ManagerViewGrantChangeRepository changeRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtUtil jwt;

    private final ObjectMapper json = new ObjectMapper();
    private MockMvc mvc;

    private Department home;
    private Department grant;
    private Department shut;
    private User adminUser;
    private User managerUser;
    private Staff sh;
    private Staff sg;
    private Staff so;
    private Task th;
    private Task tg;
    private Task tg2;
    private Task to;
    private Assignment ah;
    private Assignment ag;

    private String admin;
    private String manager;
    private String otherManager;
    private String staffMember;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        ScopeFixtures fixtures = new ScopeFixtures(userRepository, staffRepository, departmentRepository, passwordEncoder);
        home = fixtures.department(HOME);
        grant = fixtures.department(GRANT);
        Department other = fixtures.department(OTHER);
        shut = fixtures.department(SHUT);

        adminUser = fixtures.account("va_admin", Role.ADMIN, true);
        managerUser = fixtures.manager("va_mgr", home).getUser();
        Staff mg = fixtures.manager("va_mgr_grant", grant);
        sh = fixtures.staff("va_sh", home);
        sg = fixtures.staff("va_sg", grant);
        so = fixtures.staff("va_so", other);
        fixtures.staff("va_sshut", shut);

        th = task("VA home task", home, TaskStatus.ASSIGNED);
        tg = task("VA granted task", grant, TaskStatus.ASSIGNED);
        tg2 = task("VA granted open task", grant, TaskStatus.PENDING);
        to = task("VA other task", other, TaskStatus.PENDING);
        task("VA shut task", shut, TaskStatus.PENDING);
        ah = assign(th, sh);
        ag = assign(tg, sg);

        admin = bearer("va_admin", Role.ADMIN);
        manager = bearer("va_mgr", Role.MANAGER);
        otherManager = bearer(mg.getUser().getUsername(), Role.MANAGER);
        staffMember = bearer("va_sh", Role.STAFF);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String bearer(String username, Role role) {
        return "Bearer " + jwt.generateToken(username, role);
    }

    private Task task(String title, Department department, TaskStatus status) {
        Task task = new Task();
        task.setTitle(title);
        task.setPriority(Priority.MEDIUM);
        task.setStatus(status);
        task.setEstimatedDuration(30);
        task.setDueDate(LocalDate.of(2027, 1, 1));
        task.setDepartment(department);
        task.setCreatedBy(adminUser);
        return taskRepository.saveAndFlush(task);
    }

    private Assignment assign(Task task, Staff staff) {
        Assignment assignment = new Assignment();
        assignment.setTask(task);
        assignment.setStaff(staff);
        return assignmentRepository.saveAndFlush(assignment);
    }

    private MvcResult call(HttpMethod method, String url, String token, Object body) throws Exception {
        MockHttpServletRequestBuilder builder = request(method, url);
        if (token != null) builder.header("Authorization", token);
        if (body != null) builder.contentType(MediaType.APPLICATION_JSON).content(body instanceof String s ? s : json.writeValueAsString(body));
        return mvc.perform(builder).andReturn();
    }

    private int status(HttpMethod method, String url, String token, Object body) throws Exception {
        return call(method, url, token, body).getResponse().getStatus();
    }

    private JsonNode read(String url, String token) throws Exception {
        MvcResult result = call(HttpMethod.GET, url, token, null);
        assertEquals(200, result.getResponse().getStatus(), url + ": " + result.getResponse().getContentAsString());
        return json.readTree(result.getResponse().getContentAsString());
    }

    private String viewUrl(User user) {
        return "/api/users/" + user.getId() + "/view-departments";
    }

    /** Admin replaces the Manager's grants with these codes and expects success. */
    private JsonNode grantAs(String... codes) throws Exception {
        MvcResult result = call(HttpMethod.PUT, viewUrl(managerUser), admin, Map.of("departments", List.of(codes)));
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return json.readTree(result.getResponse().getContentAsString());
    }

    /** id → the listed object, for one list endpoint. */
    private Map<String, JsonNode> byId(String url, String token, String idField) throws Exception {
        Map<String, JsonNode> rows = new HashMap<>();
        for (JsonNode node : read(url, token)) rows.put(node.get(idField).asText(), node);
        return rows;
    }

    private List<String> grantedCodes() {
        List<String> codes = new ArrayList<>();
        for (ManagerViewGrant g : grantRepository.findByManagerId(managerUser.getId())) codes.add(g.getDepartment().getCode());
        return codes;
    }

    private static boolean refused(int status) {
        return status == 403 || status == 404;
    }

    // ── granting and revoking: Admin only ───────────────────────────────────

    @Test
    @DisplayName("Only an Admin can read or change view access; Managers (even for themselves) and Staff are refused, anonymous is 401")
    void grantApiIsAdminOnly() throws Exception {
        assertEquals(200, status(HttpMethod.GET, viewUrl(managerUser), admin, null));
        assertEquals(200, status(HttpMethod.GET, viewUrl(managerUser) + "/history", admin, null));

        for (String caller : List.of(manager, otherManager, staffMember)) {
            assertEquals(403, status(HttpMethod.GET, viewUrl(managerUser), caller, null));
            assertEquals(403, status(HttpMethod.GET, viewUrl(managerUser) + "/history", caller, null));
            assertEquals(403, status(HttpMethod.PUT, viewUrl(managerUser), caller, Map.of("departments", List.of(GRANT))));
            assertEquals(403, status(HttpMethod.PUT, viewUrl(managerUser), caller, Map.of("departments", List.of())));
        }
        assertEquals(401, status(HttpMethod.GET, viewUrl(managerUser), null, null));
        assertEquals(401, status(HttpMethod.PUT, viewUrl(managerUser), null, Map.of("departments", List.of(GRANT))));
        assertTrue(grantedCodes().isEmpty(), "no refused request created a grant");

        // A Manager cannot revoke either
        grantAs(GRANT);
        assertEquals(403, status(HttpMethod.PUT, viewUrl(managerUser), manager, Map.of("departments", List.of())));
        assertEquals(List.of(GRANT), grantedCodes());
    }

    @Test
    @DisplayName("Admin grant, read back and revoke, with the audit history; the primary department is reported separately")
    void adminGrantsAndRevokes() throws Exception {
        JsonNode saved = grantAs(GRANT);
        assertEquals(HOME, saved.get("primaryDepartmentCode").asText());
        assertEquals(1, saved.get("departments").size());
        assertEquals(GRANT, saved.get("departments").get(0).get("departmentCode").asText());
        assertEquals("va_admin", saved.get("departments").get(0).get("grantedBy").asText());

        JsonNode read = read(viewUrl(managerUser), admin);
        assertEquals(GRANT, read.get("departments").get(0).get("departmentCode").asText());

        JsonNode cleared = grantAs();
        assertEquals(0, cleared.get("departments").size());
        assertTrue(grantedCodes().isEmpty());

        JsonNode history = read(viewUrl(managerUser) + "/history", admin);
        assertEquals(2, history.size());
        List<String> actions = new ArrayList<>();
        for (JsonNode change : history) {
            actions.add(change.get("action").asText());
            assertEquals(GRANT, change.get("departmentCode").asText());
            assertEquals("va_admin", change.get("changedByUsername").asText());
        }
        assertTrue(actions.contains("GRANTED") && actions.contains("REVOKED"), actions.toString());
    }

    @Test
    @DisplayName("Invalid grants are refused and change nothing: primary, unknown, inactive, non-Manager, unknown user, missing list")
    void invalidGrantsAreRefusedAtomically() throws Exception {
        grantAs(GRANT);
        Map<String, Object> primary = Map.of("departments", List.of(GRANT, HOME));
        assertEquals(400, status(HttpMethod.PUT, viewUrl(managerUser), admin, primary));
        assertEquals(400, status(HttpMethod.PUT, viewUrl(managerUser), admin, Map.of("departments", List.of(OTHER, "VA_NO_SUCH_DEPARTMENT"))));
        shut.setActive(false);
        departmentRepository.saveAndFlush(shut);
        assertEquals(400, status(HttpMethod.PUT, viewUrl(managerUser), admin, Map.of("departments", List.of(SHUT))));
        assertEquals(400, status(HttpMethod.PUT, viewUrl(sh.getUser()), admin, Map.of("departments", List.of(GRANT))), "a Staff account");
        assertEquals(400, status(HttpMethod.PUT, viewUrl(adminUser), admin, Map.of("departments", List.of(GRANT))), "an Admin account");
        assertEquals(404, status(HttpMethod.PUT, "/api/users/" + UUID.randomUUID() + "/view-departments", admin, Map.of("departments", List.of(GRANT))));
        assertEquals(400, status(HttpMethod.PUT, viewUrl(managerUser), admin, "{}"));
        assertEquals(List.of(GRANT), grantedCodes(), "every refused request left the grants exactly as they were");
        assertEquals(1, changeRepository.findByManagerUserIdOrderByChangedAtDesc(managerUser.getId()).size());
    }

    // ── what a granted Manager can read ─────────────────────────────────────

    @Test
    @DisplayName("Without a grant a Manager reads their own department only (unchanged behaviour)")
    void withoutGrantOnlyPrimary() throws Exception {
        Map<String, JsonNode> tasks = byId("/api/tasks", manager, "id");
        assertEquals(java.util.Set.of(th.getId().toString()), tasks.keySet());
        assertFalse(tasks.get(th.getId().toString()).get("viewOnly").asBoolean());
        assertEquals(java.util.Set.of(sh.getId().toString()), byId("/api/staff", manager, "id").keySet());
        assertEquals(java.util.Set.of(ah.getId().toString()), byId("/api/assignments", manager, "id").keySet());
        assertEquals(java.util.Set.of(sh.getId().toString()), byId("/api/workload", manager, "staffId").keySet());
    }

    @Test
    @DisplayName("With a grant: granted tasks, staff, assignments and workload are listed and marked view-only; other departments never")
    void grantedDepartmentIsReadable() throws Exception {
        grantAs(GRANT);

        Map<String, JsonNode> tasks = byId("/api/tasks", manager, "id");
        assertEquals(java.util.Set.of(th.getId().toString(), tg.getId().toString(), tg2.getId().toString()), tasks.keySet());
        assertFalse(tasks.get(th.getId().toString()).get("viewOnly").asBoolean());
        assertTrue(tasks.get(tg.getId().toString()).get("viewOnly").asBoolean());
        assertTrue(tasks.get(tg2.getId().toString()).get("viewOnly").asBoolean());

        Map<String, JsonNode> staff = byId("/api/staff", manager, "id");
        assertEquals(java.util.Set.of(sh.getId().toString(), sg.getId().toString()), staff.keySet(), "STAFF-role members only, no Manager rows");
        JsonNode own = staff.get(sh.getId().toString());
        assertFalse(own.get("viewOnly").asBoolean());
        assertEquals("va_sh@scope.test", own.get("email").asText(), "own department keeps its contact details");
        JsonNode granted = staff.get(sg.getId().toString());
        assertTrue(granted.get("viewOnly").asBoolean());
        for (String hidden : List.of("email", "phone", "username", "userId", "pendingEmailMasked", "pendingEmailExpiresAt")) {
            assertTrue(granted.get(hidden) == null || granted.get(hidden).isNull(), hidden + " must not be sent for a view-only member");
        }
        assertEquals(sg.getName(), granted.get("name").asText());
        assertEquals(GRANT, granted.get("department").asText());
        assertEquals("AVAILABLE", granted.get("availability").asText());
        assertTrue(granted.get("active").asBoolean());

        Map<String, JsonNode> assignments = byId("/api/assignments", manager, "id");
        assertEquals(java.util.Set.of(ah.getId().toString(), ag.getId().toString()), assignments.keySet());
        assertTrue(assignments.get(ag.getId().toString()).get("viewOnly").asBoolean());
        assertFalse(assignments.get(ah.getId().toString()).get("viewOnly").asBoolean());

        Map<String, JsonNode> workload = byId("/api/workload", manager, "staffId");
        assertEquals(java.util.Set.of(sh.getId().toString(), sg.getId().toString()), workload.keySet());
        assertTrue(workload.get(sg.getId().toString()).get("viewOnly").asBoolean());
        assertEquals(1, workload.get(sg.getId().toString()).get("activeTaskCount").asInt(), "the granted member's own workload");
        assertFalse(workload.get(sh.getId().toString()).get("viewOnly").asBoolean());

        Map<String, JsonNode> departments = byId("/api/departments", manager, "code");
        assertTrue(departments.get(GRANT).get("viewOnly").asBoolean());
        assertTrue(departments.get(GRANT).hasNonNull("staffCount"), "a granted department's figures are shown");
        assertFalse(departments.get(HOME).get("viewOnly").asBoolean());
        assertTrue(departments.get(OTHER).get("staffCount") == null || departments.get(OTHER).get("staffCount").isNull(), "other departments stay basic");
    }

    @Test
    @DisplayName("Other roles are unchanged by grants: Admin sees everything unflagged, Staff their own records, another Manager nothing extra")
    void otherRolesUnchanged() throws Exception {
        grantAs(GRANT, OTHER);
        for (JsonNode task : read("/api/tasks", admin)) assertFalse(task.get("viewOnly").asBoolean());
        for (JsonNode row : read("/api/staff", admin)) assertFalse(row.get("viewOnly").asBoolean());
        assertTrue(byId("/api/staff", admin, "id").get(sg.getId().toString()).hasNonNull("email"), "Admin still sees contact details");

        assertEquals(java.util.Set.of(th.getId().toString()), byId("/api/tasks", staffMember, "id").keySet());
        assertEquals(403, status(HttpMethod.GET, "/api/staff", staffMember, null));

        Map<String, JsonNode> otherManagersTasks = byId("/api/tasks", otherManager, "id");
        assertEquals(java.util.Set.of(tg.getId().toString(), tg2.getId().toString()), otherManagersTasks.keySet(), "the grant is per Manager");
    }

    // ── no write authority, ever ────────────────────────────────────────────

    @Test
    @DisplayName("Every task, staff and assignment write is refused for granted-department records, and nothing changes")
    void grantGivesNoWriteAuthority() throws Exception {
        grantAs(GRANT);
        String sgEmail = sg.getEmail();
        int tasksBefore = (int) taskRepository.count();
        int staffBefore = (int) staffRepository.count();
        int assignmentsBefore = (int) assignmentRepository.count();

        List<String> problems = new ArrayList<>();
        java.util.function.BiConsumer<String, Integer> expectRefused = (label, code) -> {
            if (!refused(code)) problems.add(label + " -> " + code);
        };

        // Tasks
        String createBody = "{\"title\":\"VA sneaky\",\"priority\":\"MEDIUM\",\"estimatedDuration\":30,\"dueDate\":\"2027-01-01\",\"department\":\"" + GRANT + "\"}";
        expectRefused.accept("create task in granted department", status(HttpMethod.POST, "/api/tasks", manager, createBody));
        String updateGranted = "{\"title\":\"VA changed\",\"priority\":\"HIGH\",\"estimatedDuration\":30,\"dueDate\":\"2027-01-01\",\"department\":\"" + GRANT + "\",\"status\":\"PENDING\"}";
        expectRefused.accept("edit granted task", status(HttpMethod.PUT, "/api/tasks/" + tg2.getId(), manager, updateGranted));
        String completeGranted = "{\"title\":\"VA granted task\",\"priority\":\"MEDIUM\",\"estimatedDuration\":30,\"dueDate\":\"2027-01-01\",\"department\":\"" + GRANT + "\",\"status\":\"COMPLETED\"}";
        expectRefused.accept("complete granted task", status(HttpMethod.PUT, "/api/tasks/" + tg.getId(), manager, completeGranted));
        String moveOwnIntoGrant = "{\"title\":\"VA home task\",\"priority\":\"MEDIUM\",\"estimatedDuration\":30,\"dueDate\":\"2027-01-01\",\"department\":\"" + GRANT + "\",\"status\":\"ASSIGNED\"}";
        expectRefused.accept("move own task into granted department", status(HttpMethod.PUT, "/api/tasks/" + th.getId(), manager, moveOwnIntoGrant));
        expectRefused.accept("status of granted task", status(HttpMethod.PATCH, "/api/tasks/" + tg.getId() + "/status", manager, Map.of("status", "COMPLETED")));
        expectRefused.accept("delete granted task", status(HttpMethod.DELETE, "/api/tasks/" + tg2.getId(), manager, null));

        // Staff
        String createStaff = "{\"fullName\":\"VA Sneaky\",\"email\":\"va.sneaky@scope.test\",\"phone\":\"555-0199\",\"department\":\"" + GRANT
                + "\",\"availability\":\"AVAILABLE\",\"username\":\"va_sneaky\",\"temporaryPassword\":\"Str0ng!Passw0rd#\",\"role\":\"STAFF\"}";
        expectRefused.accept("create staff in granted department", status(HttpMethod.POST, "/api/staff", manager, createStaff));
        String editStaff = "{\"fullName\":\"VA Renamed\",\"email\":\"" + sgEmail + "\",\"phone\":\"555-0100\",\"department\":\"" + GRANT + "\",\"availability\":\"UNAVAILABLE\",\"active\":true}";
        expectRefused.accept("edit granted staff", status(HttpMethod.PUT, "/api/staff/" + sg.getId(), manager, editStaff));
        String moveIntoHome = "{\"fullName\":\"" + sg.getName() + "\",\"email\":\"" + sgEmail + "\",\"phone\":\"555-0100\",\"department\":\"" + HOME + "\",\"availability\":\"AVAILABLE\",\"active\":true}";
        expectRefused.accept("pull granted staff into own department", status(HttpMethod.PUT, "/api/staff/" + sg.getId(), manager, moveIntoHome));
        String pushOwnOut = "{\"fullName\":\"" + sh.getName() + "\",\"email\":\"" + sh.getEmail() + "\",\"phone\":\"555-0100\",\"department\":\"" + GRANT + "\",\"availability\":\"AVAILABLE\",\"active\":true}";
        expectRefused.accept("move own staff into granted department", status(HttpMethod.PUT, "/api/staff/" + sh.getId(), manager, pushOwnOut));
        expectRefused.accept("email change for granted staff", status(HttpMethod.POST, "/api/staff/" + sg.getId() + "/email-change", manager, Map.of("newEmail", "va.new@scope.test")));
        expectRefused.accept("deactivate granted staff", status(HttpMethod.PATCH, "/api/staff/" + sg.getId() + "/deactivate", manager, null));
        expectRefused.accept("activate granted staff", status(HttpMethod.PATCH, "/api/staff/" + sg.getId() + "/activate", manager, null));
        expectRefused.accept("remove granted staff", status(HttpMethod.DELETE, "/api/staff/" + sg.getId(), manager, null));

        // Assignments
        expectRefused.accept("assign granted task to granted staff", status(HttpMethod.POST, "/api/assignments", manager, Map.of("taskId", tg2.getId(), "staffId", sg.getId())));
        expectRefused.accept("assign granted task to own staff", status(HttpMethod.POST, "/api/assignments", manager, Map.of("taskId", tg2.getId(), "staffId", sh.getId())));
        Task homeOpen = task("VA home open task", home, TaskStatus.PENDING);
        expectRefused.accept("assign own task to granted staff", status(HttpMethod.POST, "/api/assignments", manager, Map.of("taskId", homeOpen.getId(), "staffIds", List.of(sg.getId()))));
        expectRefused.accept("reassign granted assignment", status(HttpMethod.PUT, "/api/assignments/" + ag.getId(), manager, Map.of("staffId", sh.getId())));
        expectRefused.accept("reassign own assignment to granted staff", status(HttpMethod.PUT, "/api/assignments/" + ah.getId(), manager, Map.of("staffId", sg.getId())));

        // Departments (Admin only, as before)
        expectRefused.accept("edit granted department", status(HttpMethod.PUT, "/api/departments/" + GRANT, manager, "{\"name\":\"VA renamed\",\"iconKey\":\"building\",\"colorKey\":\"emerald\",\"active\":true}"));

        assertTrue(problems.isEmpty(), "writes that were not refused: " + problems);

        // Nothing changed
        taskRepository.flush();
        assertEquals(tasksBefore + 1, taskRepository.count(), "only the fixture task added above");
        assertEquals(staffBefore, staffRepository.count());
        assertEquals(assignmentsBefore, assignmentRepository.count());
        Task tgNow = taskRepository.findById(tg.getId()).orElseThrow();
        assertEquals(TaskStatus.ASSIGNED, tgNow.getStatus());
        Task tg2Now = taskRepository.findById(tg2.getId()).orElseThrow();
        assertEquals("VA granted open task", tg2Now.getTitle());
        assertEquals(HOME, taskRepository.findById(th.getId()).orElseThrow().getDepartment().getCode());
        Staff sgNow = staffRepository.findById(sg.getId()).orElseThrow();
        assertEquals(GRANT, sgNow.getDepartment().getCode());
        assertTrue(sgNow.isActive());
        assertEquals(Availability.AVAILABLE, sgNow.getAvailability());
        assertEquals(HOME, staffRepository.findById(sh.getId()).orElseThrow().getDepartment().getCode());
        assertEquals(sg.getId(), assignmentRepository.findById(ag.getId()).orElseThrow().getStaff().getId());
        assertEquals(sh.getId(), assignmentRepository.findById(ah.getId()).orElseThrow().getStaff().getId());
    }

    @Test
    @DisplayName("The Manager still manages their own department normally while holding a grant")
    void ownDepartmentStillManaged() throws Exception {
        grantAs(GRANT);
        Task homeOpen = task("VA home open task", home, TaskStatus.PENDING);
        Staff spare = new ScopeFixtures(userRepository, staffRepository, departmentRepository, passwordEncoder).staff("va_sh2", home);
        MvcResult assigned = call(HttpMethod.POST, "/api/assignments", manager, Map.of("taskId", homeOpen.getId(), "staffId", spare.getId()));
        assertTrue(assigned.getResponse().getStatus() < 300, assigned.getResponse().getContentAsString());
        String update = "{\"title\":\"VA home open task, edited\",\"priority\":\"HIGH\",\"estimatedDuration\":45,\"dueDate\":\"2027-01-01\",\"department\":\"" + HOME + "\",\"status\":\"ASSIGNED\"}";
        MvcResult edited = call(HttpMethod.PUT, "/api/tasks/" + homeOpen.getId(), manager, update);
        assertEquals(200, edited.getResponse().getStatus(), edited.getResponse().getContentAsString());
    }

    // ── revocation and inactive departments ─────────────────────────────────

    @Test
    @DisplayName("Revocation applies to the very next request with the same token, through every list")
    void revocationIsImmediate() throws Exception {
        grantAs(GRANT);
        assertTrue(byId("/api/tasks", manager, "id").containsKey(tg.getId().toString()));
        grantAs();
        assertFalse(byId("/api/tasks", manager, "id").containsKey(tg.getId().toString()));
        assertFalse(byId("/api/staff", manager, "id").containsKey(sg.getId().toString()));
        assertFalse(byId("/api/assignments", manager, "id").containsKey(ag.getId().toString()));
        assertFalse(byId("/api/workload", manager, "staffId").containsKey(sg.getId().toString()));
        JsonNode grantDept = byId("/api/departments", manager, "code").get(GRANT);
        assertTrue(grantDept.get("staffCount") == null || grantDept.get("staffCount").isNull());
    }

    @Test
    @DisplayName("A granted department that becomes inactive shows nothing until it is active again; the grant itself is kept")
    void inactiveGrantedDepartmentIgnored() throws Exception {
        grantAs(GRANT, SHUT);
        Task shutTask = taskRepository.findByDepartment(shut).get(0);
        assertTrue(byId("/api/tasks", manager, "id").containsKey(shutTask.getId().toString()));
        shut.setActive(false);
        departmentRepository.saveAndFlush(shut);
        assertFalse(byId("/api/tasks", manager, "id").containsKey(shutTask.getId().toString()));
        assertTrue(byId("/api/tasks", manager, "id").containsKey(tg.getId().toString()), "the active grant still applies");
        assertTrue(grantedCodes().contains(SHUT));
        // Keeping an existing inactive grant is allowed; removing it too
        assertEquals(200, status(HttpMethod.PUT, viewUrl(managerUser), admin, Map.of("departments", List.of(GRANT, SHUT))));
        grantAs(GRANT);
        assertEquals(List.of(GRANT), grantedCodes());
    }

    @Test
    @DisplayName("Removing a granted department removes its grants with it (database cascade)")
    void departmentRemovalCascades() throws Exception {
        Department empty = new ScopeFixtures(userRepository, staffRepository, departmentRepository, passwordEncoder).department("VA_EMPTY");
        grantAs(GRANT, "VA_EMPTY");
        assertEquals(204, status(HttpMethod.DELETE, "/api/departments/VA_EMPTY", admin, null));
        grantRepository.flush();
        assertEquals(List.of(GRANT), grantedCodes());
        assertFalse(departmentRepository.findById(empty.getId()).isPresent());
    }

    @Test
    @DisplayName("Removing a Manager account removes its grants with it (database cascade); the audit history is kept")
    void managerRemovalCascades() throws Exception {
        Staff temp = new ScopeFixtures(userRepository, staffRepository, departmentRepository, passwordEncoder).manager("va_mgr_temp", home);
        User tempUser = temp.getUser();
        MvcResult granted = call(HttpMethod.PUT, viewUrl(tempUser), admin, Map.of("departments", List.of(GRANT, OTHER)));
        assertEquals(200, granted.getResponse().getStatus(), granted.getResponse().getContentAsString());
        assertEquals(2, grantRepository.findByManagerId(tempUser.getId()).size());

        assertEquals(200, status(HttpMethod.PATCH, "/api/staff/" + temp.getId() + "/deactivate", admin, null));
        MvcResult removed = call(HttpMethod.DELETE, "/api/users/" + tempUser.getId(), admin, null);
        assertTrue(removed.getResponse().getStatus() < 300, removed.getResponse().getStatus() + " " + removed.getResponse().getContentAsString());
        grantRepository.flush();
        assertFalse(userRepository.findById(tempUser.getId()).isPresent());
        assertTrue(grantRepository.findByManagerId(tempUser.getId()).isEmpty(), "no grant outlives the account");
        assertEquals(2, changeRepository.findByManagerUserIdOrderByChangedAtDesc(tempUser.getId()).size(), "the history is kept");
        assertEquals(200, status(HttpMethod.GET, viewUrl(tempUser) + "/history", admin, null));
        assertEquals(404, status(HttpMethod.GET, viewUrl(tempUser), admin, null));
    }

    @Test
    @DisplayName("Manipulated ids and parameters reach nothing: random ids, another department's ids, and query parameters are ignored")
    void idAndParameterManipulation() throws Exception {
        grantAs(GRANT);
        // Ids of a department that is NOT granted: refused like any other department
        String moveOther = "{\"title\":\"VA other task\",\"priority\":\"HIGH\",\"estimatedDuration\":30,\"dueDate\":\"2027-01-01\",\"department\":\"" + OTHER + "\",\"status\":\"PENDING\"}";
        assertTrue(refused(status(HttpMethod.PUT, "/api/tasks/" + to.getId(), manager, moveOther)));
        assertTrue(refused(status(HttpMethod.DELETE, "/api/tasks/" + to.getId(), manager, null)));
        assertTrue(refused(status(HttpMethod.PATCH, "/api/staff/" + so.getId() + "/deactivate", manager, null)));
        assertTrue(refused(status(HttpMethod.PUT, "/api/tasks/" + UUID.randomUUID(), manager, moveOther)));
        // Query parameters cannot widen a list: the scope comes from the database only
        for (String url : List.of("/api/tasks?department=" + OTHER, "/api/staff?department=" + OTHER, "/api/assignments?department=" + OTHER,
                "/api/workload?department=" + OTHER, "/api/tasks?viewOnly=false&departments=" + OTHER)) {
            for (JsonNode row : read(url, manager)) {
                String text = row.toString();
                assertFalse(text.contains(OTHER) || text.contains(so.getId().toString()) || text.contains(to.getId().toString()), url + " leaked " + text);
            }
        }
        // Another Manager's grant endpoint, with this Manager's token
        assertEquals(403, status(HttpMethod.PUT, "/api/users/" + UUID.randomUUID() + "/view-departments", manager, Map.of("departments", List.of(OTHER))));
        assertEquals(List.of(GRANT), grantedCodes());
    }

    @Test
    @DisplayName("When the granted department becomes the Manager's primary one, the redundant grant is removed and audited")
    void primaryChangeDropsRedundantGrant() throws Exception {
        grantAs(GRANT, OTHER);
        MvcResult moved = call(HttpMethod.PUT, "/api/users/" + managerUser.getId() + "/manager-department", admin, Map.of("department", GRANT));
        assertEquals(200, moved.getResponse().getStatus(), moved.getResponse().getContentAsString());
        assertEquals(List.of(OTHER), grantedCodes());
        ManagerViewGrantChange latest = changeRepository.findByManagerUserIdOrderByChangedAtDesc(managerUser.getId()).stream()
                .filter(c -> c.getAction().equals(ManagerViewGrantChange.ACTION_REVOKED)).findFirst().orElseThrow();
        assertEquals(GRANT, latest.getDepartmentCode());
        assertEquals(ManagerViewGrantChange.SOURCE_PRIMARY_CHANGE, latest.getChangeSource());

        // Now GRANT is fully theirs (not view-only) and OTHER is view-only; HOME is gone
        Map<String, JsonNode> tasks = byId("/api/tasks", manager, "id");
        assertFalse(tasks.get(tg.getId().toString()).get("viewOnly").asBoolean());
        assertTrue(tasks.get(to.getId().toString()).get("viewOnly").asBoolean());
        assertFalse(tasks.containsKey(th.getId().toString()));
    }

    // ── nothing else is widened ─────────────────────────────────────────────

    @Test
    @DisplayName("Dashboard, support recommendations and Cross-Department Assistance planning stay primary-department only")
    void unrelatedEndpointsUnchanged() throws Exception {
        String dashboardBefore = call(HttpMethod.GET, "/api/dashboard/summary", manager, null).getResponse().getContentAsString();
        MvcResult recommendationsBefore = call(HttpMethod.GET, "/api/workload/recommendations", manager, null);
        grantAs(GRANT, OTHER);
        String dashboardAfter = call(HttpMethod.GET, "/api/dashboard/summary", manager, null).getResponse().getContentAsString();
        assertEquals(withoutTimes(dashboardBefore), withoutTimes(dashboardAfter), "the Manager dashboard is the same with or without grants");
        MvcResult recommendationsAfter = call(HttpMethod.GET, "/api/workload/recommendations", manager, null);
        assertEquals(recommendationsBefore.getResponse().getStatus(), recommendationsAfter.getResponse().getStatus());
        assertEquals(withoutTimes(recommendationsBefore.getResponse().getContentAsString()), withoutTimes(recommendationsAfter.getResponse().getContentAsString()));

        int options = status(HttpMethod.GET, "/api/support/options?receivingDepartment=" + GRANT, manager, null);
        assertTrue(options >= 400, "planning support for a granted department is refused: " + options);
        assertTrue(refused(status(HttpMethod.GET, "/api/users", manager, null)));
        assertTrue(refused(status(HttpMethod.GET, "/api/system-settings", manager, null)));
    }

    /** The JSON with every date-time value blanked, so two reads moments apart compare equal. */
    private String withoutTimes(String body) throws Exception {
        assertNotNull(body);
        if (body.isBlank()) return body;
        return body.replaceAll("\"\\d{4}-\\d{2}-\\d{2}T[^\"]*\"", "\"T\"");
    }
}
