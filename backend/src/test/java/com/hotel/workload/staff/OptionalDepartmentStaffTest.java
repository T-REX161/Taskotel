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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Staff accounts without a department: who may create one, who may give or take a department, and what must never change. Real security
 * chain, services and database (H2); every test rolls back. Fixture: departments A and B; an Admin; a Manager of A; Staff a1 in A; a Manager
 * record and an Admin record (both with department A) as accounts that must keep theirs.
 */
@SpringBootTest
@Transactional
public class OptionalDepartmentStaffTest {

    private static final String A = "OPT_A";
    private static final String B = "OPT_B";

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private AssignmentRepository assignmentRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private final ObjectMapper mapper = new ObjectMapper();
    private MockMvc mockMvc;
    private ScopeFixtures fixtures;
    private Department deptA;
    private Department deptB;
    private User admin;
    private Staff a1;
    private Staff managerRecord;
    private Staff adminRecord;

    @BeforeEach
    public void setUp() {
        SecurityContextHolder.clearContext();
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        fixtures = new ScopeFixtures(userRepository, staffRepository, departmentRepository, passwordEncoder);
        deptA = fixtures.department(A);
        deptB = fixtures.department(B);
        admin = fixtures.account("opt_admin", Role.ADMIN, true);
        fixtures.manager("opt_mgr", deptA);
        a1 = fixtures.staff("opt_a1", deptA);
        managerRecord = fixtures.manager("opt_mgr_target", deptA);
        adminRecord = fixtures.staffRecord("opt_admin_staff", Role.ADMIN, deptA);
    }

    @AfterEach
    public void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private MvcResult call(String username, String role, MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.with(user(username).roles(role)).contentType(MediaType.APPLICATION_JSON)).andReturn();
    }

    /** department: the JSON text to send (a quoted code, null, or an empty string); omitted when the argument is the word OMIT. */
    private static String createBody(String username, String departmentJson, String role) {
        String department = "OMIT".equals(departmentJson) ? "" : "\"department\": " + departmentJson + ",";
        return "{\"fullName\": \"New " + username + "\", \"email\": \"" + username + "@opt.test\", \"phone\": \"555-0100\", " + department
                + " \"availability\": \"AVAILABLE\", \"username\": \"" + username + "\", \"temporaryPassword\": \"TempPass@123\""
                + (role == null ? "" : ", \"role\": \"" + role + "\"") + "}";
    }

    private static String quoted(String code) {
        return "\"" + code + "\"";
    }

    private MvcResult create(String caller, String callerRole, String body) throws Exception {
        return call(caller, callerRole, post("/api/staff").content(body));
    }

    private static String updateBody(Staff staff, String departmentJson) {
        return "{\"fullName\": \"" + staff.getName() + "\", \"email\": \"" + staff.getEmail() + "\", \"phone\": \"555-0100\", \"department\": " + departmentJson
                + ", \"availability\": \"AVAILABLE\", \"active\": true}";
    }

    private MvcResult update(String caller, String callerRole, Staff staff, String departmentJson) throws Exception {
        return call(caller, callerRole, put("/api/staff/" + staff.getId()).content(updateBody(staff, departmentJson)));
    }

    private Staff reload(Staff staff) {
        staffRepository.flush();
        return staffRepository.findById(staff.getId()).orElseThrow();
    }

    private Staff unassignedStaff(String username) {
        Staff staff = fixtures.staff(username, deptA);
        staff.setDepartment(null);
        return staffRepository.saveAndFlush(staff);
    }

    private JsonNode json(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    private String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    private Assignment activeAssignmentFor(Staff staff) {
        Task task = new Task();
        task.setTitle("Active work");
        task.setPriority(Priority.MEDIUM);
        task.setStatus(TaskStatus.ASSIGNED);
        task.setEstimatedDuration(30);
        task.setDueDate(LocalDate.of(2027, 1, 1));
        task.setDepartment(deptA);
        task.setCreatedBy(admin);
        task = taskRepository.saveAndFlush(task);
        Assignment assignment = new Assignment();
        assignment.setTask(task);
        assignment.setStaff(staff);
        return assignmentRepository.saveAndFlush(assignment);
    }

    // ── creating ─────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("An Admin creates a STAFF account WITH a department, as before")
    void adminCreatesWithDepartment() throws Exception {
        MvcResult result = create("opt_admin", "ADMIN", createBody("opt_new_a", quoted(B), null));
        assertEquals(201, result.getResponse().getStatus(), body(result));
        assertEquals(B, json(result).get("department").asText());
        assertEquals(deptB, staffRepository.findByUserUsername("opt_new_a").orElseThrow().getDepartment());
    }

    @Test
    @DisplayName("An Admin creates a STAFF account WITHOUT a department (null, an empty string, or the field left out): 201, department null, the account is a normal STAFF account")
    void adminCreatesWithoutDepartment() throws Exception {
        int n = 0;
        for (String department : List.of("null", "\"\"", "\"   \"", "OMIT")) {
            String username = "opt_none_" + (++n);
            MvcResult result = create("opt_admin", "ADMIN", createBody(username, department, null));
            assertEquals(201, result.getResponse().getStatus(), department + ": " + body(result));
            assertTrue(json(result).get("department").isNull(), department);
            Staff stored = staffRepository.findByUserUsername(username).orElseThrow();
            assertNull(stored.getDepartment(), department);
            assertEquals(Role.STAFF, stored.getUser().getRole());
            assertTrue(stored.isActive() && !stored.getUser().isEmailVerified() && stored.getUser().isCredentialsSetupRequired(), "the usual onboarding still applies");
        }
    }

    @Test
    @DisplayName("An Admin sees the unassigned Staff member in the Staff list, with no department")
    void adminSeesUnassignedStaff() throws Exception {
        Staff un = unassignedStaff("opt_un_list");
        JsonNode list = json(call("opt_admin", "ADMIN", get("/api/staff")));
        JsonNode found = null;
        for (JsonNode item : list) if (item.get("id").asText().equals(un.getId().toString())) found = item;
        assertNotNull(found, "visible to the Admin");
        assertTrue(found.get("department").isNull());
        assertEquals("STAFF", found.get("role").asText());
    }

    @Test
    @DisplayName("A Manager cannot create Staff without a department (400); with their own department it still works, and the department is always their own")
    void managerCannotCreateWithoutDepartment() throws Exception {
        for (String department : List.of("null", "\"\"", "OMIT")) {
            String username = "opt_mgr_none_" + Math.abs(department.hashCode());
            MvcResult result = create("opt_mgr", "MANAGER", createBody(username, department, null));
            assertEquals(400, result.getResponse().getStatus(), department + ": " + body(result));
            assertTrue(userRepository.findByUsername(username).isEmpty(), "nothing was created");
        }
        MvcResult own = create("opt_mgr", "MANAGER", createBody("opt_mgr_own", quoted(A), null));
        assertEquals(201, own.getResponse().getStatus(), body(own));
        assertEquals(deptA, staffRepository.findByUserUsername("opt_mgr_own").orElseThrow().getDepartment());
        assertEquals(403, create("opt_mgr", "MANAGER", createBody("opt_mgr_other", quoted(B), null)).getResponse().getStatus(), "another department is still refused");
    }

    @Test
    @DisplayName("An Admin creates an ADMIN, a MANAGER and a STAFF account WITHOUT a department (null, empty or left out): 201 and a null department for every role")
    void adminCreatesAnyRoleWithoutDepartment() throws Exception {
        for (String role : List.of("ADMIN", "MANAGER", "STAFF")) {
            for (String department : List.of("null", "\"\"", "OMIT")) {
                String username = "opt_any_" + role.toLowerCase() + Math.abs(department.hashCode());
                MvcResult result = create("opt_admin", "ADMIN", createBody(username, department, role));
                assertEquals(201, result.getResponse().getStatus(), role + " " + department + ": " + body(result));
                Staff stored = staffRepository.findByUserUsername(username).orElseThrow();
                assertNull(stored.getDepartment(), role + " " + department);
                assertEquals(Role.valueOf(role), stored.getUser().getRole());
            }
        }
        MvcResult manager = create("opt_admin", "ADMIN", createBody("opt_priv_ok", quoted(A), "MANAGER"));
        assertEquals(201, manager.getResponse().getStatus(), body(manager));
        assertEquals(deptA, staffRepository.findByUserUsername("opt_priv_ok").orElseThrow().getDepartment(), "with a department it is created as before");
    }

    @Test
    @DisplayName("A Manager without a department is denied department-scoped access (403, fail closed); an Admin without one keeps full global access")
    void managerWithoutDepartmentIsDeniedAdminKeepsAccess() throws Exception {
        assertEquals(201, create("opt_admin", "ADMIN", createBody("opt_new_mgr", "null", "MANAGER")).getResponse().getStatus());
        assertEquals(201, create("opt_admin", "ADMIN", createBody("opt_new_admin", "null", "ADMIN")).getResponse().getStatus());
        for (String path : List.of("/api/staff", "/api/tasks", "/api/dashboard/summary")) {
            int status = call("opt_new_mgr", "MANAGER", get(path)).getResponse().getStatus();
            assertEquals(403, status, "unassigned Manager " + path);
        }
        assertEquals(403, create("opt_new_mgr", "MANAGER", createBody("opt_by_nodept_mgr", quoted(A), null)).getResponse().getStatus(), "cannot create Staff either");
        // The department-less Admin sees everything, including the Staff of every department
        MvcResult list = call("opt_new_admin", "ADMIN", get("/api/staff"));
        assertEquals(200, list.getResponse().getStatus(), body(list));
        assertTrue(body(list).contains(a1.getId().toString()));
        assertEquals(200, call("opt_new_admin", "ADMIN", get("/api/tasks")).getResponse().getStatus());
    }

    @Test
    @DisplayName("A Manager receives normal scoped access once an Admin assigns an active department: no new account, same role")
    void managerAccessResumesAfterAssignment() throws Exception {
        assertEquals(201, create("opt_admin", "ADMIN", createBody("opt_resume_mgr", "null", "MANAGER")).getResponse().getStatus());
        Staff record = staffRepository.findByUserUsername("opt_resume_mgr").orElseThrow();
        assertEquals(403, call("opt_resume_mgr", "MANAGER", get("/api/staff")).getResponse().getStatus());

        MvcResult assigned = update("opt_admin", "ADMIN", record, quoted(A));
        assertEquals(200, assigned.getResponse().getStatus(), body(assigned));
        assertEquals(Role.MANAGER, reload(record).getUser().getRole(), "the role is unchanged");
        MvcResult list = call("opt_resume_mgr", "MANAGER", get("/api/staff"));
        assertEquals(200, list.getResponse().getStatus(), body(list));
        assertTrue(body(list).contains(a1.getId().toString()), "sees their department's Staff");

        // Clearing it again closes the access at once
        assertEquals(200, update("opt_admin", "ADMIN", record, "null").getResponse().getStatus());
        assertEquals(403, call("opt_resume_mgr", "MANAGER", get("/api/staff")).getResponse().getStatus());
    }

    // ── assigning and clearing ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("An Admin assigns a department to unassigned Staff: 200, the department is stored, and the department's Manager now sees them")
    void adminAssignsADepartment() throws Exception {
        Staff un = unassignedStaff("opt_un_assign");
        MvcResult managerBefore = call("opt_mgr", "MANAGER", get("/api/staff"));
        assertFalse(body(managerBefore).contains(un.getId().toString()), "unassigned Staff are not visible to a Manager");

        MvcResult result = update("opt_admin", "ADMIN", un, quoted(A));
        assertEquals(200, result.getResponse().getStatus(), body(result));
        assertEquals(A, json(result).get("department").asText());
        assertEquals(deptA, reload(un).getDepartment());
        assertTrue(body(call("opt_mgr", "MANAGER", get("/api/staff"))).contains(un.getId().toString()), "now in the Manager's department");
    }

    @Test
    @DisplayName("An Admin clears a department when there are no active assignments (and can do it again harmlessly)")
    void adminClearsADepartment() throws Exception {
        for (String blank : List.of("null", "\"\"")) {
            Staff staff = fixtures.staff("opt_clear_" + Math.abs(blank.hashCode()), deptA);
            MvcResult result = update("opt_admin", "ADMIN", staff, blank);
            assertEquals(200, result.getResponse().getStatus(), blank + ": " + body(result));
            assertTrue(json(result).get("department").isNull());
            assertNull(reload(staff).getDepartment());
            assertEquals(200, update("opt_admin", "ADMIN", staff, blank).getResponse().getStatus(), "clearing an already-empty department changes nothing");
        }
    }

    @Test
    @DisplayName("Clearing a department with an active assignment is 409 and leaves the record exactly as it was (department, availability, active, the assignment)")
    void clearingWithActiveAssignmentsIsRefused() throws Exception {
        Assignment assignment = activeAssignmentFor(a1);
        MvcResult result = update("opt_admin", "ADMIN", a1, "null");
        assertEquals(409, result.getResponse().getStatus(), body(result));
        assertTrue(body(result).contains("active assignments"));
        Staff stored = reload(a1);
        assertEquals(deptA, stored.getDepartment());
        assertTrue(stored.isActive() && stored.getUser().isActive());
        assertTrue(assignmentRepository.findById(assignment.getId()).isPresent(), "the assignment is untouched");
        // Once the work is finished the same request succeeds
        assignment.getTask().setStatus(TaskStatus.COMPLETED);
        taskRepository.saveAndFlush(assignment.getTask());
        assertEquals(200, update("opt_admin", "ADMIN", a1, "\"\"").getResponse().getStatus());
    }

    @Test
    @DisplayName("An Admin can clear and later assign a department for ADMIN, MANAGER and STAFF records alike (department move audit for a Manager still works)")
    void allRolesCanBeClearedAndAssigned() throws Exception {
        for (Staff record : List.of(managerRecord, adminRecord, a1)) {
            for (String blank : List.of("null", "\"\"")) {
                MvcResult cleared = update("opt_admin", "ADMIN", record, blank);
                assertEquals(200, cleared.getResponse().getStatus(), record.getUser().getRole() + " " + blank + ": " + body(cleared));
                assertNull(reload(record).getDepartment());
                assertTrue(json(cleared).get("department").isNull());
                MvcResult assigned = update("opt_admin", "ADMIN", record, quoted(B));
                assertEquals(200, assigned.getResponse().getStatus(), body(assigned));
                assertEquals(deptB, reload(record).getDepartment());
                assertEquals(200, update("opt_admin", "ADMIN", record, quoted(A)).getResponse().getStatus());
            }
        }
    }

    @Test
    @DisplayName("A Manager cannot change or clear their own department, nor that of another Manager or an Admin (403)")
    void managerCannotChangeOwnDepartment() throws Exception {
        Staff own = staffRepository.findByUserUsername("opt_mgr").orElseThrow();
        for (String department : List.of("null", "\"\"", quoted(B), quoted(A))) {
            int status = update("opt_mgr", "MANAGER", own, department).getResponse().getStatus();
            assertEquals(403, status, "own record " + department);
            assertEquals(403, update("opt_mgr", "MANAGER", managerRecord, department).getResponse().getStatus(), "another Manager " + department);
            assertEquals(403, update("opt_mgr", "MANAGER", adminRecord, department).getResponse().getStatus(), "an Admin " + department);
        }
        assertEquals(deptA, reload(own).getDepartment());
    }

    // ── who may do what ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("A Manager cannot see or manage unassigned Staff: not in their list, and edit, deactivate, activate and login-email change are 404")
    void managerCannotReachUnassignedStaff() throws Exception {
        Staff un = unassignedStaff("opt_un_hidden");
        assertFalse(body(call("opt_mgr", "MANAGER", get("/api/staff"))).contains(un.getId().toString()));
        assertEquals(404, update("opt_mgr", "MANAGER", un, quoted(A)).getResponse().getStatus(), "a Manager cannot even give them their department");
        assertEquals(404, call("opt_mgr", "MANAGER", patch("/api/staff/" + un.getId() + "/deactivate")).getResponse().getStatus());
        assertEquals(404, call("opt_mgr", "MANAGER", patch("/api/staff/" + un.getId() + "/activate")).getResponse().getStatus());
        assertEquals(404, call("opt_mgr", "MANAGER", post("/api/staff/" + un.getId() + "/email-change").content("{\"newEmail\":\"x@opt.test\"}")).getResponse().getStatus());
        assertNull(reload(un).getDepartment(), "unchanged");
    }

    @Test
    @DisplayName("A Manager still cannot clear or change the department of their own Staff (403), and Staff cannot use the endpoints at all")
    void managerAndStaffCannotChangeDepartments() throws Exception {
        assertEquals(403, update("opt_mgr", "MANAGER", a1, "null").getResponse().getStatus());
        assertEquals(403, update("opt_mgr", "MANAGER", a1, quoted(B)).getResponse().getStatus());
        assertEquals(deptA, reload(a1).getDepartment());
        assertEquals(403, update("opt_a1", "STAFF", a1, "null").getResponse().getStatus());
        assertEquals(403, create("opt_a1", "STAFF", createBody("opt_by_staff", "null", null)).getResponse().getStatus());
    }

    // ── existing behaviour ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Existing behaviour is unchanged: an Admin transfers assigned Staff between departments, a Manager edits their own Staff keeping the department, blocked transfers still say 409")
    void existingAssignedStaffBehaviourIsUnchanged() throws Exception {
        assertEquals(200, update("opt_mgr", "MANAGER", a1, quoted(A)).getResponse().getStatus(), "a Manager edits their own Staff with the same department");
        MvcResult moved = update("opt_admin", "ADMIN", a1, quoted(B));
        assertEquals(200, moved.getResponse().getStatus(), body(moved));
        assertEquals(deptB, reload(a1).getDepartment());

        Staff busy = fixtures.staff("opt_busy", deptA);
        activeAssignmentFor(busy);
        MvcResult blocked = update("opt_admin", "ADMIN", busy, quoted(B));
        assertEquals(409, blocked.getResponse().getStatus(), body(blocked));
        assertTrue(body(blocked).contains("Cannot move"), "the existing transfer message");
        assertEquals(deptA, reload(busy).getDepartment());
        assertEquals(400, update("opt_admin", "ADMIN", a1, quoted("NO_SUCH_DEPARTMENT")).getResponse().getStatus() / 100 * 100, "an unknown department is still refused");
    }

    @Test
    @DisplayName("Giving a department to unassigned Staff works for a Staff record whose account is not yet verified (the normal onboarding state)")
    void assignsToNewlyCreatedStaff() throws Exception {
        MvcResult created = create("opt_admin", "ADMIN", createBody("opt_later", "null", null));
        assertEquals(201, created.getResponse().getStatus(), body(created));
        Staff later = staffRepository.findByUserUsername("opt_later").orElseThrow();
        MvcResult assigned = update("opt_admin", "ADMIN", later, quoted(B));
        assertEquals(200, assigned.getResponse().getStatus(), body(assigned));
        assertEquals(deptB, reload(later).getDepartment());
        List<String> names = new ArrayList<>();
        staffRepository.findAll().forEach(s -> names.add(s.getUser().getUsername()));
        assertTrue(names.contains("opt_later"));
    }
}
