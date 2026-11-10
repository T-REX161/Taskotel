package com.hotel.workload.staff;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Manager department isolation for staff management, through the real security chain and the real (H2) database. Every test rolls back.
 *
 * <p>Fixture: two departments, ALPHA and BETA. ALPHA has two Managers (m1, m2), BETA has one (m3). Staff: sa1, sa2 in ALPHA, sb1 in BETA.
 * One Administrator without a Staff record, one Administrator record inside ALPHA, and four invalid Managers (no Staff record, inactive
 * account, inactive Staff record, inactive department).
 */
@SpringBootTest
@Transactional
public class StaffDepartmentIsolationTest {

    private static final String ALPHA = "ISO_ALPHA";
    private static final String BETA = "ISO_BETA";
    private static final String GONE = "ISO_CLOSED";

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private AssignmentRepository assignmentRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private StaffService staffService;

    private final ObjectMapper mapper = new ObjectMapper();
    private MockMvc mockMvc;

    private Department alpha;
    private Department beta;
    private Staff sa1, sa2, sb1, m1, m2, m3, adminRecordInAlpha;
    private User admin;

    @BeforeEach
    public void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        ScopeFixtures fixtures = new ScopeFixtures(userRepository, staffRepository, departmentRepository, passwordEncoder);
        alpha = fixtures.department(ALPHA);
        beta = fixtures.department(BETA);
        Department closed = fixtures.department(GONE);

        admin = fixtures.account("iso_admin", Role.ADMIN, true);
        adminRecordInAlpha = fixtures.staffRecord("iso_admin_record", Role.ADMIN, alpha);
        m1 = fixtures.manager("iso_m1", alpha);
        m2 = fixtures.manager("iso_m2", alpha);
        m3 = fixtures.manager("iso_m3", beta);
        sa1 = fixtures.staff("iso_sa1", alpha);
        sa2 = fixtures.staff("iso_sa2", alpha);
        sb1 = fixtures.staff("iso_sb1", beta);

        // Invalid Managers, one reason each
        fixtures.account("iso_m_nostaff", Role.MANAGER, true);
        User inactiveAccount = fixtures.account("iso_m_inactive_account", Role.MANAGER, false);
        fixtures.staffRecord("iso_m_inactive_account", Role.MANAGER, alpha); // account stays inactive
        Staff inactiveStaff = fixtures.manager("iso_m_inactive_staff", alpha);
        inactiveStaff.setActive(false);
        staffRepository.saveAndFlush(inactiveStaff);
        fixtures.manager("iso_m_closed_department", closed);
        closed.setActive(false);
        departmentRepository.saveAndFlush(closed);
        assertFalse(inactiveAccount.isActive());
    }

    @AfterEach
    public void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private MvcResult call(String username, String role, MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.with(user(username).roles(role)).contentType(MediaType.APPLICATION_JSON)).andReturn();
    }

    private int status(String username, String role, MockHttpServletRequestBuilder request) throws Exception {
        return call(username, role, request).getResponse().getStatus();
    }

    private Set<String> listedUsernames(String username, String role) throws Exception {
        MvcResult result = call(username, role, get("/api/staff"));
        assertEquals(200, result.getResponse().getStatus());
        Set<String> names = new HashSet<>();
        for (JsonNode node : mapper.readTree(result.getResponse().getContentAsString())) {
            names.add(node.get("username").asText());
        }
        return names;
    }

    private String updateBody(Staff staff, String departmentCode, String email, boolean active) {
        return """
                {"fullName": "%s", "email": "%s", "phone": "555-0100", "department": "%s", "availability": "AVAILABLE", "active": %s}
                """.formatted(staff.getName(), email, departmentCode, active);
    }

    private String createBody(String username, String departmentCode, String role) {
        return """
                {"fullName": "New %s", "email": "%s@scope.test", "phone": "555-0100", "department": %s, "availability": "AVAILABLE",
                 "username": "%s", "temporaryPassword": "TempPass@123"%s}
                """.formatted(username, username, departmentCode == null ? "null" : "\"" + departmentCode + "\"", username,
                role == null ? "" : ", \"role\": \"" + role + "\"");
    }

    private Staff reload(Staff staff) {
        staffRepository.flush();
        return staffRepository.findById(staff.getId()).orElseThrow();
    }

    private void assertUntouched(Staff before) {
        Staff stored = reload(before);
        assertEquals(before.getDepartment().getCode(), stored.getDepartment().getCode(), "department unchanged");
        assertEquals(before.getName(), stored.getName(), "name unchanged");
        assertEquals(before.getEmail(), stored.getEmail(), "email unchanged");
        assertTrue(stored.isActive(), "still active");
        assertTrue(userRepository.findById(before.getUser().getId()).orElseThrow().isActive(), "login still active");
    }

    // ── 1. Admin: global ─────────────────────────────────────────────────────

    @Test
    @DisplayName("Admin lists staff of every department, including manager and admin records")
    public void adminListsEveryDepartment() throws Exception {
        Set<String> names = listedUsernames("iso_admin", "ADMIN");
        assertTrue(names.containsAll(Set.of("iso_sa1", "iso_sa2", "iso_sb1", "iso_m1", "iso_m3", "iso_admin_record")), names.toString());
    }

    @Test
    @DisplayName("Admin updates, deactivates, reactivates and transfers staff in any department; creates staff anywhere")
    public void adminManagesEveryDepartment() throws Exception {
        assertEquals(200, status("iso_admin", "ADMIN", put("/api/staff/" + sb1.getId()).content(updateBody(sb1, BETA, sb1.getEmail(), true))));
        assertEquals(200, status("iso_admin", "ADMIN", patch("/api/staff/" + sa1.getId() + "/deactivate")));
        assertEquals(200, status("iso_admin", "ADMIN", patch("/api/staff/" + sa1.getId() + "/activate")));

        // Only an administrator transfers staff between departments
        assertEquals(200, status("iso_admin", "ADMIN", put("/api/staff/" + sb1.getId()).content(updateBody(sb1, ALPHA, sb1.getEmail(), true))));
        assertEquals(ALPHA, reload(sb1).getDepartment().getCode());

        assertEquals(201, status("iso_admin", "ADMIN", post("/api/staff").content(createBody("iso_new_in_beta", BETA, null))));
        assertEquals(BETA, staffRepository.findByUserUsername("iso_new_in_beta").orElseThrow().getDepartment().getCode());
        assertEquals(201, status("iso_admin", "ADMIN", post("/api/staff").content(createBody("iso_new_mgr_alpha", ALPHA, "MANAGER"))));
    }

    // ── 2. Manager: list only own department ─────────────────────────────────

    @Test
    @DisplayName("A Manager lists only the ordinary staff of their own department: no other department, no manager or admin accounts")
    public void managerListsOnlyOwnDepartment() throws Exception {
        Set<String> alphaView = listedUsernames("iso_m1", "MANAGER");
        assertEquals(Set.of("iso_sa1", "iso_sa2"), alphaView);

        Set<String> betaView = listedUsernames("iso_m3", "MANAGER");
        assertEquals(Set.of("iso_sb1"), betaView);

        // The response never carries other departments' staff or privileged accounts, in any field
        String body = call("iso_m1", "MANAGER", get("/api/staff")).getResponse().getContentAsString();
        for (String hidden : List.of("iso_sb1", "iso_m1", "iso_m2", "iso_m3", "iso_admin_record", sb1.getId().toString(), m3.getId().toString())) {
            assertFalse(body.contains(hidden), hidden + " must not appear in a Manager's list");
        }
        assertFalse(body.contains("\"role\":\"MANAGER\"") || body.contains("\"role\":\"ADMIN\""));
    }

    @Test
    @DisplayName("Two Managers of the same department see the same staff")
    public void managersOfOneDepartmentSeeTheSameList() throws Exception {
        assertEquals(listedUsernames("iso_m1", "MANAGER"), listedUsernames("iso_m2", "MANAGER"));
    }

    // ── 3. Manager: no access to other departments' records (IDOR) ───────────

    @Test
    @DisplayName("A Manager cannot update, deactivate or activate staff of another department: 404, and nothing changes")
    public void managerCannotTouchOtherDepartmentStaff() throws Exception {
        assertEquals(404, status("iso_m1", "MANAGER", put("/api/staff/" + sb1.getId()).content(updateBody(sb1, BETA, "hijack@scope.test", true))));
        assertEquals(404, status("iso_m1", "MANAGER", put("/api/staff/" + sb1.getId()).content(updateBody(sb1, ALPHA, "hijack@scope.test", true))),
                "A crafted request that also names the Manager's own department changes nothing either");
        assertEquals(404, status("iso_m1", "MANAGER", patch("/api/staff/" + sb1.getId() + "/deactivate")));
        assertEquals(404, status("iso_m1", "MANAGER", patch("/api/staff/" + sb1.getId() + "/activate")));
        assertUntouched(sb1);

        // The other way round, and for a Manager of the other department
        assertEquals(404, status("iso_m3", "MANAGER", patch("/api/staff/" + sa1.getId() + "/deactivate")));
        assertUntouched(sa1);
    }

    @Test
    @DisplayName("An id in another department is indistinguishable from an id that does not exist")
    public void outOfScopeLooksLikeNotFound() throws Exception {
        MvcResult other = call("iso_m1", "MANAGER", patch("/api/staff/" + sb1.getId() + "/deactivate"));
        MvcResult missing = call("iso_m1", "MANAGER", patch("/api/staff/" + UUID.randomUUID() + "/deactivate"));
        assertEquals(404, other.getResponse().getStatus());
        assertEquals(404, missing.getResponse().getStatus());
        assertEquals(missing.getResponse().getContentAsString(), other.getResponse().getContentAsString());
    }

    // ── 4/5. Manager: creating staff ─────────────────────────────────────────

    @Test
    @DisplayName("A Manager creates staff in their own department, and the record is linked to it")
    public void managerCreatesInOwnDepartment() throws Exception {
        MvcResult result = call("iso_m1", "MANAGER", post("/api/staff").content(createBody("iso_created_by_m1", ALPHA, null)));
        assertEquals(201, result.getResponse().getStatus());
        assertEquals(ALPHA, mapper.readTree(result.getResponse().getContentAsString()).get("department").asText());

        Staff stored = staffRepository.findByUserUsername("iso_created_by_m1").orElseThrow();
        assertEquals(ALPHA, stored.getDepartment().getCode());
        assertEquals(alpha.getId(), stored.getDepartment().getId());
        assertEquals(Role.STAFF, stored.getUser().getRole());
        assertFalse(stored.getUser().isEmailVerified(), "The existing first-time verification flow still applies");
        assertTrue(stored.getUser().isCredentialsSetupRequired());

        // The other Manager of the department sees the new member; the Manager of another department does not
        assertTrue(listedUsernames("iso_m2", "MANAGER").contains("iso_created_by_m1"));
        assertFalse(listedUsernames("iso_m3", "MANAGER").contains("iso_created_by_m1"));
    }

    @Test
    @DisplayName("A Manager cannot create staff in another department, however the request is crafted: 403 and no account is created")
    public void managerCannotCreateInOtherDepartment() throws Exception {
        assertEquals(403, status("iso_m1", "MANAGER", post("/api/staff").content(createBody("iso_crafted_beta", BETA, null))));
        assertEquals(403, status("iso_m1", "MANAGER", post("/api/staff").content(createBody("iso_crafted_unknown", "NO_SUCH_DEPARTMENT", null))),
                "An unknown department is refused the same way, so the response does not reveal which departments exist");
        assertEquals(403, status("iso_m1", "MANAGER", post("/api/staff").content(createBody("iso_crafted_case", "iso_alpha", null))),
                "Only the exact department code of the Manager is accepted");
        assertEquals(403, status("iso_m1", "MANAGER", post("/api/staff").content(createBody("iso_crafted_role", BETA, "STAFF"))));
        for (String name : List.of("iso_crafted_beta", "iso_crafted_unknown", "iso_crafted_case", "iso_crafted_role")) {
            assertTrue(userRepository.findByUsername(name).isEmpty(), name + " must not exist");
            assertTrue(staffRepository.findByUserUsername(name).isEmpty());
        }
        // A missing department is a validation error, never silently defaulted to some department
        assertEquals(400, status("iso_m1", "MANAGER", post("/api/staff").content(createBody("iso_crafted_null", null, null))));
        assertTrue(userRepository.findByUsername("iso_crafted_null").isEmpty());
    }

    @Test
    @DisplayName("A Manager cannot create manager or admin accounts, even in their own department")
    public void managerCannotCreateElevatedAccounts() throws Exception {
        assertEquals(403, status("iso_m1", "MANAGER", post("/api/staff").content(createBody("iso_elev_mgr", ALPHA, "MANAGER"))));
        assertEquals(403, status("iso_m1", "MANAGER", post("/api/staff").content(createBody("iso_elev_admin", ALPHA, "ADMIN"))));
        assertTrue(userRepository.findByUsername("iso_elev_mgr").isEmpty() && userRepository.findByUsername("iso_elev_admin").isEmpty());
    }

    // ── transfers ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("A Manager cannot transfer staff to another department, even from their own department: 403 and nothing changes")
    public void managerCannotTransferStaff() throws Exception {
        assertEquals(403, status("iso_m1", "MANAGER", put("/api/staff/" + sa1.getId()).content(updateBody(sa1, BETA, "iso_sa1@scope.test", true))));
        assertEquals(403, status("iso_m1", "MANAGER", put("/api/staff/" + sa1.getId()).content(updateBody(sa1, "NO_SUCH_DEPARTMENT", "iso_sa1@scope.test", true))));
        assertEquals(ALPHA, reload(sa1).getDepartment().getCode());

        // Updating the same member without a transfer still works, for both Managers of the department
        assertEquals(200, status("iso_m1", "MANAGER", put("/api/staff/" + sa1.getId()).content(updateBody(sa1, ALPHA, sa1.getEmail(), true))));
        assertEquals(200, status("iso_m2", "MANAGER", put("/api/staff/" + sa1.getId()).content(updateBody(sa1, ALPHA, sa1.getEmail(), true))));
        assertEquals("iso_sa1@scope.test", reload(sa1).getEmail(), "the login email is not changed by a staff update");
    }

    // ── 6. Manager and Admin accounts are not manageable by a Manager ────────

    @Test
    @DisplayName("A Manager cannot manage Manager or Admin accounts: 403 in their own department, 404 in another")
    public void managerCannotManagePrivilegedAccounts() throws Exception {
        // Same department: other Manager, and an Admin record
        for (Staff target : List.of(m2, adminRecordInAlpha)) {
            assertEquals(403, status("iso_m1", "MANAGER", put("/api/staff/" + target.getId()).content(updateBody(target, ALPHA, "evil@scope.test", true))));
            assertEquals(403, status("iso_m1", "MANAGER", patch("/api/staff/" + target.getId() + "/deactivate")));
            assertEquals(403, status("iso_m1", "MANAGER", patch("/api/staff/" + target.getId() + "/activate")));
            assertTrue(reload(target).isActive());
            assertTrue(userRepository.findById(target.getUser().getId()).orElseThrow().isActive());
        }
        // A Manager cannot manage their own record either
        assertEquals(403, status("iso_m1", "MANAGER", patch("/api/staff/" + m1.getId() + "/deactivate")));
        // Another department's Manager is out of scope
        assertEquals(404, status("iso_m1", "MANAGER", patch("/api/staff/" + m3.getId() + "/deactivate")));
        assertTrue(reload(m3).isActive());
    }

    // ── 7. Invalid Managers fail closed ──────────────────────────────────────

    @Test
    @DisplayName("An invalid Manager gets 403 on every staff operation: no Staff record, inactive account, inactive Staff record, inactive department")
    public void invalidManagersFailClosed() throws Exception {
        for (String name : List.of("iso_m_nostaff", "iso_m_inactive_account", "iso_m_inactive_staff", "iso_m_closed_department")) {
            assertEquals(403, status(name, "MANAGER", get("/api/staff")), name + " list");
            assertEquals(403, status(name, "MANAGER", get("/api/staff/username-availability").param("username", "iso_probe")), name + " availability");
            assertEquals(403, status(name, "MANAGER", post("/api/staff").content(createBody("iso_by_" + name, ALPHA, null))), name + " create");
            assertEquals(403, status(name, "MANAGER", put("/api/staff/" + sa1.getId()).content(updateBody(sa1, ALPHA, "x@scope.test", true))), name + " update");
            assertEquals(403, status(name, "MANAGER", patch("/api/staff/" + sa1.getId() + "/deactivate")), name + " deactivate");
            assertEquals(403, status(name, "MANAGER", patch("/api/staff/" + sa1.getId() + "/activate")), name + " activate");
            assertTrue(userRepository.findByUsername("iso_by_" + name).isEmpty(), name + " must not create anything");
        }
        assertUntouched(sa1);
    }

    @Test
    @DisplayName("A Manager's access follows the database immediately: moved, then deactivated")
    public void managerAccessFollowsTheDatabase() throws Exception {
        assertEquals(Set.of("iso_sa1", "iso_sa2"), listedUsernames("iso_m1", "MANAGER"));

        m1.setDepartment(beta);
        staffRepository.saveAndFlush(m1);
        assertEquals(Set.of("iso_sb1"), listedUsernames("iso_m1", "MANAGER"), "The very next request is scoped to the new department");
        assertEquals(404, status("iso_m1", "MANAGER", patch("/api/staff/" + sa1.getId() + "/deactivate")));

        m1.setActive(false);
        staffRepository.saveAndFlush(m1);
        assertEquals(403, status("iso_m1", "MANAGER", get("/api/staff")));
    }

    // ── 8. Two Managers manage the same staff ────────────────────────────────

    @Test
    @DisplayName("Two Managers of the same department manage the same department's staff")
    public void twoManagersShareTheDepartment() throws Exception {
        assertEquals(200, status("iso_m1", "MANAGER", patch("/api/staff/" + sa1.getId() + "/deactivate")));
        assertFalse(reload(sa1).isActive());
        assertEquals(200, status("iso_m2", "MANAGER", patch("/api/staff/" + sa1.getId() + "/activate")));
        assertTrue(reload(sa1).isActive());
        assertEquals(200, status("iso_m2", "MANAGER", patch("/api/staff/" + sa2.getId() + "/deactivate")));
        assertEquals(200, status("iso_m1", "MANAGER", patch("/api/staff/" + sa2.getId() + "/activate")));
        assertEquals(201, status("iso_m2", "MANAGER", post("/api/staff").content(createBody("iso_by_m2", ALPHA, null))));
    }

    // ── 9. Staff ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Staff cannot reach the staff management endpoints: 403 for every operation, on their own and on other records")
    public void staffCannotUseStaffManagement() throws Exception {
        assertEquals(403, status("iso_sa1", "STAFF", get("/api/staff")));
        assertEquals(403, status("iso_sa1", "STAFF", get("/api/staff/username-availability").param("username", "x")));
        assertEquals(403, status("iso_sa1", "STAFF", post("/api/staff").content(createBody("iso_by_staff", ALPHA, null))));
        assertEquals(403, status("iso_sa1", "STAFF", put("/api/staff/" + sa1.getId()).content(updateBody(sa1, ALPHA, "self@scope.test", true))));
        assertEquals(403, status("iso_sa1", "STAFF", put("/api/staff/" + sa2.getId()).content(updateBody(sa2, ALPHA, "x@scope.test", true))));
        assertEquals(403, status("iso_sa1", "STAFF", patch("/api/staff/" + sa1.getId() + "/deactivate")));
        assertEquals(403, status("iso_sa1", "STAFF", patch("/api/staff/" + sa2.getId() + "/activate")));
        assertTrue(userRepository.findByUsername("iso_by_staff").isEmpty());
        assertUntouched(sa1);
    }

    @Test
    @DisplayName("The service itself refuses a Staff caller and an unauthenticated caller, not only the URL rules")
    public void serviceRefusesStaffAndAnonymousCallers() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("iso_sa1", null,
                List.of(new SimpleGrantedAuthority("ROLE_STAFF"))));
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> staffService.getAllStaff()).getStatusCode().value());
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> staffService.deactivateStaff(sa2.getId())).getStatusCode().value());

        SecurityContextHolder.clearContext();
        assertEquals(401, assertThrows(ResponseStatusException.class, () -> staffService.getAllStaff()).getStatusCode().value());
        assertUntouched(sa2);
    }

    @Test
    @DisplayName("Staff keep their own-profile behaviour")
    public void staffStillReadTheirOwnProfile() throws Exception {
        MvcResult result = call("iso_sa1", "STAFF", get("/api/profile"));
        assertEquals(200, result.getResponse().getStatus());
        assertEquals("iso_sa1", mapper.readTree(result.getResponse().getContentAsString()).get("username").asText());
    }

    // ── 10. Existing business rules stay intact inside the scope ─────────────

    @Test
    @DisplayName("Existing rules still apply to a Manager: a member with an active task cannot be deactivated (409) and nothing changes")
    public void existingActiveAssignmentRuleStillApplies() throws Exception {
        Task task = new Task();
        task.setTitle("Isolation task");
        task.setPriority(Priority.MEDIUM);
        task.setStatus(TaskStatus.ASSIGNED);
        task.setDepartment(alpha);
        task.setCreatedBy(admin);
        task = taskRepository.saveAndFlush(task);
        Assignment assignment = new Assignment();
        assignment.setTask(task);
        assignment.setStaff(sa1);
        assignmentRepository.saveAndFlush(assignment);

        assertEquals(409, status("iso_m1", "MANAGER", patch("/api/staff/" + sa1.getId() + "/deactivate")));
        assertEquals(409, status("iso_m2", "MANAGER", put("/api/staff/" + sa1.getId()).content(updateBody(sa1, ALPHA, "iso_sa1@scope.test", false))));
        assertUntouched(sa1);
    }

    @Test
    @DisplayName("Existing rules still apply: duplicate username 409, weak password 400, Admin permanent removal only")
    public void otherExistingRulesStillApply() throws Exception {
        assertEquals(409, status("iso_m1", "MANAGER", post("/api/staff").content(createBody("iso_sa2", ALPHA, null))));
        MockHttpServletRequestBuilder weak = post("/api/staff").content(createBody("iso_weak", ALPHA, null).replace("TempPass@123", "weak"));
        assertEquals(400, status("iso_m1", "MANAGER", weak));
        // Permanent removal stays Admin-only: a Manager gets 403 even for their own department's inactive staff
        assertEquals(200, status("iso_m1", "MANAGER", patch("/api/staff/" + sa2.getId() + "/deactivate")));
        assertEquals(403, status("iso_m1", "MANAGER", org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/staff/" + sa2.getId())));
        assertEquals(204, status("iso_admin", "ADMIN", org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/staff/" + sa2.getId())));
    }
}
