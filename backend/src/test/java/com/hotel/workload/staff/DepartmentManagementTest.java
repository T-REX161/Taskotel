package com.hotel.workload.staff;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.workload.assignment.AssignmentRepository;
import com.hotel.workload.security.Role;
import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Database-backed departments: listing, creating, editing, validation, duplicate names, authorization,
 * and how new and inactive departments behave across staff, tasks, assignments and workload.
 * Runs on the H2 test database; every test rolls back.
 */
@SpringBootTest
@Transactional
public class DepartmentManagementTest {

    @Autowired private WebApplicationContext context;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private AssignmentRepository assignmentRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private MockMvc mockMvc;

    @BeforeEach
    public void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        if (userRepository.findByUsername("testadmin").isEmpty()) {
            User admin = new User();
            admin.setUsername("testadmin");
            admin.setEmail("testadmin@hotel.com");
            admin.setPassword(passwordEncoder.encode("password"));
            admin.setRole(Role.ADMIN);
            admin.setActive(true);
            userRepository.save(admin);
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private MvcResult createDepartment(String json) throws Exception {
        return mockMvc.perform(post("/api/departments").contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
    }

    private String departmentJson(String name) {
        return "{\"name\": \"" + name + "\"}";
    }

    /** Creates a department as the current user and returns its code. */
    private String newDepartment(String name) throws Exception {
        MvcResult result = createDepartment(departmentJson(name));
        assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return body(result).get("code").asText();
    }

    private Staff savedStaff(String username, String departmentCode, boolean active) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@hotel.com");
        user.setPassword(passwordEncoder.encode("password"));
        user.setRole(Role.STAFF);
        user.setActive(active);
        user = userRepository.saveAndFlush(user);

        Staff staff = new Staff();
        staff.setUser(user);
        staff.setName("Staff " + username);
        staff.setEmail(username + "@hotel.com");
        staff.setDepartment(departmentRepository.findByCode(departmentCode).orElseThrow());
        staff.setAvailability(Availability.AVAILABLE);
        staff.setActive(active);
        return staffRepository.saveAndFlush(staff);
    }

    private String staffJson(String username, String departmentCode) {
        return """
                {"fullName": "Staff %s", "email": "%s@hotel.com", "phone": "555-0100", "department": "%s",
                 "availability": "AVAILABLE", "username": "%s", "temporaryPassword": "TempPass@123"}
                """.formatted(username, username, departmentCode, username);
    }

    private String staffUpdateJson(Staff staff, String departmentCode) {
        return """
                {"fullName": "%s", "email": "%s", "phone": "555-0100", "department": "%s",
                 "availability": "AVAILABLE", "active": true}
                """.formatted(staff.getName(), staff.getEmail(), departmentCode);
    }

    private String taskJson(String title, String departmentCode, int minutes) {
        return """
                {"title": "%s", "category": "General", "priority": "MEDIUM", "estimatedDuration": %d,
                 "dueDate": "2026-12-31", "department": %s}
                """.formatted(title, minutes, departmentCode == null ? "null" : "\"" + departmentCode + "\"");
    }

    private JsonNode departmentFromList(String code) throws Exception {
        MvcResult list = mockMvc.perform(get("/api/departments")).andExpect(status().isOk()).andReturn();
        for (JsonNode node : body(list)) {
            if (code.equals(node.get("code").asText())) return node;
        }
        return fail("Department " + code + " is not in the list");
    }

    // ── listing and preservation of the original six ─────────────────────────

    @Test
    @WithMockUser(roles = "STAFF")
    @DisplayName("The original six departments are listed first, in order, with their codes, names, icons, colours and active status")
    public void testOriginalSixAreListedInOrder() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/departments")).andExpect(status().isOk()).andReturn();
        JsonNode list = body(result);
        assertEquals(DepartmentCatalog.BUILT_IN.size(), list.size());
        for (int i = 0; i < DepartmentCatalog.BUILT_IN.size(); i++) {
            DepartmentCatalog.BuiltIn expected = DepartmentCatalog.BUILT_IN.get(i);
            JsonNode actual = list.get(i);
            assertEquals(expected.code(), actual.get("code").asText());
            assertEquals(expected.name(), actual.get("name").asText());
            assertEquals(expected.iconKey(), actual.get("iconKey").asText());
            assertEquals(expected.colorKey(), actual.get("colorKey").asText());
            assertTrue(actual.get("active").asBoolean());
            assertTrue(actual.get("id").asText().length() == 36, "Departments have UUID ids");
            assertEquals("Not Assigned", actual.get("supervisor").asText());
        }
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Bootstrapping is idempotent: running it again changes nothing and never duplicates a department")
    public void testBootstrapIsIdempotent() throws Exception {
        long before = departmentRepository.count();
        Department housekeeping = departmentRepository.findByCode("HOUSEKEEPING").orElseThrow();
        housekeeping.setName("Housekeeping & Laundry");
        departmentRepository.saveAndFlush(housekeeping);

        new DepartmentBootstrap(departmentRepository).run();

        assertEquals(before, departmentRepository.count());
        assertEquals("Housekeeping & Laundry", departmentRepository.findByCode("HOUSEKEEPING").orElseThrow().getName(),
                "An edited built-in department is left exactly as it is");
    }

    // ── creating ─────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Every general-purpose icon can be saved for a new department and is returned unchanged by the list")
    public void testEveryIconKeyIsSavedAndReturned() throws Exception {
        List<String> newIcons = List.of("clipboard", "calendar", "clock", "briefcase", "package", "truck", "sparkles", "monitor", "bed");
        for (String icon : newIcons) {
            assertTrue(DepartmentCatalog.ICON_KEYS.contains(icon), icon);
            MvcResult result = createDepartment("{\"name\": \"Dept " + icon + "\", \"iconKey\": \"" + icon + "\", \"colorKey\": \"teal\"}");
            assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
            assertEquals(icon, body(result).get("iconKey").asText());
        }
        // "After reload": a fresh read from the database
        MvcResult list = mockMvc.perform(get("/api/departments")).andReturn();
        for (String icon : newIcons) {
            String code = "DEPT_" + icon.toUpperCase();
            boolean found = false;
            for (JsonNode department : body(list)) {
                if (code.equals(department.get("code").asText())) {
                    assertEquals(icon, department.get("iconKey").asText());
                    found = true;
                }
            }
            assertTrue(found, code);
        }
        // The icons the built-in departments already use are unchanged
        for (DepartmentCatalog.BuiltIn builtIn : DepartmentCatalog.BUILT_IN) {
            assertEquals(builtIn.iconKey(), departmentRepository.findByCode(builtIn.code()).orElseThrow().getIconKey());
        }
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("An admin can create a department; it gets a UUID, a generated code, defaults and appears in the list")
    public void testCreateDepartmentSuccessfully() throws Exception {
        MvcResult result = createDepartment("""
                {"name": "  Spa   & Wellness ", "description": "Treatments and fitness", "iconKey": "users", "colorKey": "rose"}
                """);
        assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        JsonNode created = body(result);
        assertEquals("Spa & Wellness", created.get("name").asText(), "Whitespace is tidied");
        assertEquals("SPA_AND_WELLNESS", created.get("code").asText());
        assertEquals("Treatments and fitness", created.get("description").asText());
        assertEquals("users", created.get("iconKey").asText());
        assertEquals("rose", created.get("colorKey").asText());
        assertTrue(created.get("active").asBoolean(), "Active by default");
        assertEquals(0, created.get("staffCount").asInt());
        assertEquals(0, created.get("activeTasks").asInt());
        assertDoesNotThrow(() -> UUID.fromString(created.get("id").asText()));

        mockMvc.perform(get("/api/departments")).andExpect(jsonPath("$.length()").value(7))
                .andExpect(jsonPath("$[6].code").value("SPA_AND_WELLNESS"));
        Department stored = departmentRepository.findByCode("SPA_AND_WELLNESS").orElseThrow();
        assertNotNull(stored.getCreatedAt());
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    @DisplayName("A manager can create a department; omitted icon and colour use the fallback")
    public void testManagerCanCreateWithFallbacks() throws Exception {
        MvcResult result = createDepartment(departmentJson("Concierge"));
        assertEquals(201, result.getResponse().getStatus());
        assertEquals(DepartmentCatalog.DEFAULT_ICON_KEY, body(result).get("iconKey").asText());
        assertEquals(DepartmentCatalog.DEFAULT_COLOR_KEY, body(result).get("colorKey").asText());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Name is required and must be 2 to 100 characters; the response says what is wrong")
    public void testRequiredFieldValidation() throws Exception {
        for (String json : List.of("{}", "{\"name\": \"\"}", "{\"name\": \"   \"}", "{\"name\": \"A\"}", "{\"name\": \"" + "x".repeat(101) + "\"}")) {
            MvcResult result = createDepartment(json);
            assertEquals(400, result.getResponse().getStatus(), json);
            assertTrue(body(result).get("message").asText().toLowerCase().contains("name"), result.getResponse().getContentAsString());
        }
        MvcResult longDescription = createDepartment("{\"name\": \"Valid Name\", \"description\": \"" + "d".repeat(501) + "\"}");
        assertEquals(400, longDescription.getResponse().getStatus());
        assertEquals(6, departmentRepository.count(), "Nothing was saved");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Unsupported icon or colour keys are rejected")
    public void testUnsupportedIconAndColor() throws Exception {
        assertEquals(400, createDepartment("{\"name\": \"Valet\", \"iconKey\": \"<script>\"}").getResponse().getStatus());
        assertEquals(400, createDepartment("{\"name\": \"Valet\", \"colorKey\": \"hotpink\"}").getResponse().getStatus());
        assertEquals(6, departmentRepository.count());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Duplicate names are refused with 409, ignoring letter case and extra spaces, including against built-in and inactive departments")
    public void testDuplicateNames() throws Exception {
        newDepartment("Concierge");
        for (String duplicate : List.of("Concierge", "concierge", "  CONCIERGE  ", "Housekeeping", "food & beverage")) {
            MvcResult result = createDepartment(departmentJson(duplicate));
            assertEquals(409, result.getResponse().getStatus(), duplicate);
            assertTrue(body(result).get("message").asText().contains("already exists"));
        }
        // An inactive department keeps its name reserved
        MvcResult inactive = createDepartment("{\"name\": \"Night Audit\", \"active\": false}");
        assertEquals(201, inactive.getResponse().getStatus());
        assertEquals(409, createDepartment(departmentJson("night audit")).getResponse().getStatus());
        assertEquals(8, departmentRepository.count());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Two different names that reduce to the same code still get unique codes")
    public void testCodeCollisionGetsASuffix() throws Exception {
        assertEquals("QUALITY_CONTROL", newDepartment("Quality Control"));
        assertEquals("QUALITY_CONTROL_2", newDepartment("Quality-Control"));
        assertTrue(newDepartment("部門").startsWith("DEPT_"), "A name with no Latin letters or digits still gets a code");
    }

    // ── department head ──────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("The department head must be an existing active staff member")
    public void testDepartmentHead() throws Exception {
        Staff active = savedStaff("head_active", "SECURITY", true);
        Staff inactive = savedStaff("head_inactive", "SECURITY", false);

        MvcResult ok = createDepartment("{\"name\": \"Guest Safety\", \"headStaffId\": \"" + active.getId() + "\"}");
        assertEquals(201, ok.getResponse().getStatus(), ok.getResponse().getContentAsString());
        assertEquals(active.getId().toString(), body(ok).get("headStaffId").asText());
        assertEquals(active.getName(), body(ok).get("headStaffName").asText());
        assertEquals(active.getName(), body(ok).get("supervisor").asText());

        assertEquals(400, createDepartment("{\"name\": \"Gate\", \"headStaffId\": \"" + inactive.getId() + "\"}").getResponse().getStatus());
        assertEquals(400, createDepartment("{\"name\": \"Gate\", \"headStaffId\": \"" + UUID.randomUUID() + "\"}").getResponse().getStatus());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Removing a staff member who heads a department clears the head instead of failing")
    public void testRemovingAHeadClearsTheReference() throws Exception {
        Staff head = savedStaff("head_removed", "SECURITY", false);
        Department department = departmentRepository.findByCode("SECURITY").orElseThrow();
        department.setHeadStaff(head);
        departmentRepository.saveAndFlush(department);

        mockMvc.perform(delete("/api/staff/" + head.getId())).andExpect(status().isNoContent());

        assertNull(departmentRepository.findByCode("SECURITY").orElseThrow().getHeadStaff());
        assertFalse(staffRepository.existsById(head.getId()));
    }

    // ── updating ─────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "MANAGER")
    @DisplayName("Renaming keeps the code, so staff and tasks follow the new name")
    public void testRenameKeepsCode() throws Exception {
        int before = departmentFromList("MAINTENANCE").get("staffCount").asInt();
        Staff staff = savedStaff("rename_staff", "MAINTENANCE", true);

        MvcResult updated = mockMvc.perform(put("/api/departments/MAINTENANCE").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"Engineering\", \"description\": \"Repairs\", \"iconKey\": \"wrench\", \"colorKey\": \"indigo\", \"active\": true}"))
                .andExpect(status().isOk()).andReturn();
        assertEquals("MAINTENANCE", body(updated).get("code").asText());
        assertEquals("Engineering", body(updated).get("name").asText());
        assertEquals("indigo", body(updated).get("colorKey").asText());

        mockMvc.perform(get("/api/staff")).andExpect(status().isOk());
        assertEquals("MAINTENANCE", staffRepository.findById(staff.getId()).orElseThrow().getDepartment().getCode());
        assertEquals(before + 1, departmentFromList("MAINTENANCE").get("staffCount").asInt(), "The staff member stays in the renamed department");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Updating refuses a name used by another department, accepts the department's own name, and 404s an unknown code")
    public void testUpdateValidation() throws Exception {
        String own = "{\"name\": \"Front Desk\", \"active\": true}";
        mockMvc.perform(put("/api/departments/FRONT_DESK").contentType(MediaType.APPLICATION_JSON).content(own)).andExpect(status().isOk());
        mockMvc.perform(put("/api/departments/FRONT_DESK").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"security\", \"active\": true}")).andExpect(status().isConflict());
        mockMvc.perform(put("/api/departments/FRONT_DESK").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"\", \"active\": true}")).andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/departments/FRONT_DESK").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"Reception\"}")).andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/departments/NO_SUCH").contentType(MediaType.APPLICATION_JSON).content(own)).andExpect(status().isNotFound());
    }

    // ── authorization ────────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "STAFF")
    @DisplayName("Staff can read departments but cannot create or edit them")
    public void testStaffCannotWrite() throws Exception {
        mockMvc.perform(get("/api/departments")).andExpect(status().isOk());
        mockMvc.perform(post("/api/departments").contentType(MediaType.APPLICATION_JSON).content(departmentJson("Valet")))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/departments/SECURITY").contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"Safety\", \"active\": true}"))
                .andExpect(status().isForbidden());
        assertEquals(6, departmentRepository.count());
        assertEquals("Security", departmentRepository.findByCode("SECURITY").orElseThrow().getName());
    }

    @Test
    @DisplayName("Anonymous callers are rejected")
    public void testAnonymousIsRejected() throws Exception {
        mockMvc.perform(get("/api/departments")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/departments").contentType(MediaType.APPLICATION_JSON).content(departmentJson("Valet")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/departments/SECURITY").contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"Safety\", \"active\": true}"))
                .andExpect(status().isUnauthorized());
    }

    // ── a new department across staff, tasks, assignments and workload ───────

    @Test
    @WithMockUser(username = "testadmin", roles = "ADMIN")
    @DisplayName("Staff, tasks, assignments, workload and department counts all work with a newly created department")
    public void testNewDepartmentWorksEverywhere() throws Exception {
        String code = newDepartment("Spa & Wellness");

        // Staff can be created in it, and the response names the department
        MvcResult staffCreated = mockMvc.perform(post("/api/staff").contentType(MediaType.APPLICATION_JSON).content(staffJson("spa_one", code)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.department").value(code))
                .andExpect(jsonPath("$.departmentName").value("Spa & Wellness"))
                .andReturn();
        UUID staffId = UUID.fromString(body(staffCreated).get("id").asText());

        // A task can be created in it
        MvcResult taskCreated = mockMvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON).content(taskJson("Massage room reset", code, 90)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.department").value(code))
                .andExpect(jsonPath("$.departmentName").value("Spa & Wellness"))
                .andReturn();
        UUID taskId = UUID.fromString(body(taskCreated).get("id").asText());

        // The staff member can be assigned the task
        mockMvc.perform(post("/api/assignments").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taskId\": \"" + taskId + "\", \"staffId\": \"" + staffId + "\"}"))
                .andExpect(status().isCreated());

        // A task of another department cannot go to them, and theirs cannot go to another department's staff
        MvcResult housekeepingTask = mockMvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON)
                .content(taskJson("Change linen", "HOUSEKEEPING", 30))).andExpect(status().isCreated()).andReturn();
        mockMvc.perform(post("/api/assignments").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taskId\": \"" + body(housekeepingTask).get("id").asText() + "\", \"staffId\": \"" + staffId + "\"}"))
                .andExpect(status().isBadRequest());

        // Workload is calculated for the new department's staff like any other
        MvcResult workload = mockMvc.perform(get("/api/workload")).andExpect(status().isOk()).andReturn();
        JsonNode mine = null;
        for (JsonNode node : body(workload)) {
            if (staffId.toString().equals(node.get("staffId").asText())) mine = node;
        }
        assertNotNull(mine, "The new department's staff member appears in the workload");
        assertEquals(code, mine.get("department").asText());
        assertEquals("Spa & Wellness", mine.get("departmentName").asText());
        assertEquals(1, mine.get("activeTaskCount").asInt());
        assertEquals(90, mine.get("totalEstimatedDuration").asInt());
        assertEquals("LOW", mine.get("workloadStatus").asText());

        // Department counts use the real staff and tasks
        JsonNode listed = departmentFromList(code);
        assertEquals(1, listed.get("staffCount").asInt());
        assertEquals(1, listed.get("activeTasks").asInt());

        // The staff member can be moved there from another department, and back
        Staff other = savedStaff("mover", "SECURITY", true);
        mockMvc.perform(put("/api/staff/" + other.getId()).contentType(MediaType.APPLICATION_JSON).content(staffUpdateJson(other, code)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.department").value(code));
        assertEquals(2, departmentFromList(code).get("staffCount").asInt());
    }

    @Test
    @WithMockUser(username = "testadmin", roles = "ADMIN")
    @DisplayName("Unknown department codes are rejected for staff and tasks")
    public void testUnknownDepartmentRejected() throws Exception {
        mockMvc.perform(post("/api/staff").contentType(MediaType.APPLICATION_JSON).content(staffJson("ghost", "NO_SUCH_DEPT")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON).content(taskJson("Ghost task", "NO_SUCH_DEPT", 10)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/staff").contentType(MediaType.APPLICATION_JSON).content(staffJson("nodept", "")))
                .andExpect(status().isBadRequest());
    }

    // ── inactive departments ─────────────────────────────────────────────────

    @Test
    @WithMockUser(username = "testadmin", roles = "ADMIN")
    @DisplayName("An inactive department cannot take new staff or tasks, but its existing records keep working and are still reported")
    public void testInactiveDepartment() throws Exception {
        String code = newDepartment("Night Audit");
        Staff existing = savedStaff("night_one", code, true);
        MvcResult existingTask = mockMvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON).content(taskJson("Close the books", code, 120)))
                .andExpect(status().isCreated()).andReturn();
        UUID taskId = UUID.fromString(body(existingTask).get("id").asText());
        mockMvc.perform(post("/api/assignments").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taskId\": \"" + taskId + "\", \"staffId\": \"" + existing.getId() + "\"}")).andExpect(status().isCreated());

        // Deactivate it (allowed while it still has staff and tasks)
        mockMvc.perform(put("/api/departments/" + code).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"Night Audit\", \"active\": false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));

        // New staff and new tasks are refused
        mockMvc.perform(post("/api/staff").contentType(MediaType.APPLICATION_JSON).content(staffJson("night_two", code)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("inactive")));
        mockMvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON).content(taskJson("Another", code, 15)))
                .andExpect(status().isBadRequest());
        // Moving other staff into it is refused too
        Staff other = savedStaff("night_mover", "SECURITY", true);
        mockMvc.perform(put("/api/staff/" + other.getId()).contentType(MediaType.APPLICATION_JSON).content(staffUpdateJson(other, code)))
                .andExpect(status().isBadRequest());

        // Existing records are untouched and can still be edited without changing their department
        assertEquals(code, staffRepository.findById(existing.getId()).orElseThrow().getDepartment().getCode());
        mockMvc.perform(put("/api/staff/" + existing.getId()).contentType(MediaType.APPLICATION_JSON).content(staffUpdateJson(existing, code)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.department").value(code));
        mockMvc.perform(put("/api/tasks/" + taskId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"Close the books (edited)\", \"category\": \"General\", \"priority\": \"MEDIUM\", \"estimatedDuration\": 120, \"dueDate\": \"2026-12-31\", \"department\": \"" + code + "\", \"status\": \"ASSIGNED\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.department").value(code));

        // Still reported: counts, workload, and the department stays in the list marked inactive
        JsonNode listed = departmentFromList(code);
        assertFalse(listed.get("active").asBoolean());
        assertEquals(1, listed.get("staffCount").asInt());
        assertEquals(1, listed.get("activeTasks").asInt());
        boolean inWorkload = false;
        for (JsonNode node : body(mockMvc.perform(get("/api/workload")).andReturn())) {
            if (existing.getId().toString().equals(node.get("staffId").asText())) {
                inWorkload = true;
                assertEquals(120, node.get("totalEstimatedDuration").asInt());
            }
        }
        assertTrue(inWorkload);

        // Reactivating makes it selectable again
        mockMvc.perform(put("/api/departments/" + code).contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"Night Audit\", \"active\": true}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/staff").contentType(MediaType.APPLICATION_JSON).content(staffJson("night_three", code)))
                .andExpect(status().isCreated());
    }

    // ── existing relationships ───────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Existing staff and tasks in the original six keep their department and are counted against it")
    public void testExistingRelationshipsArePreserved() throws Exception {
        List<String> codes = new ArrayList<>();
        DepartmentCatalog.BUILT_IN.forEach(builtIn -> codes.add(builtIn.code()));
        List<Integer> before = new ArrayList<>();
        for (String code : codes) {
            before.add(departmentFromList(code).get("staffCount").asInt());
        }
        for (int i = 0; i < codes.size(); i++) {
            savedStaff("preserve_" + i, codes.get(i), true);
        }
        for (int i = 0; i < codes.size(); i++) {
            assertEquals(before.get(i) + 1, departmentFromList(codes.get(i)).get("staffCount").asInt(), codes.get(i));
        }
        MvcResult staffList = mockMvc.perform(get("/api/staff")).andExpect(status().isOk()).andReturn();
        int seen = 0;
        for (JsonNode node : body(staffList)) {
            if (node.get("username").asText().startsWith("preserve_")) {
                int index = Integer.parseInt(node.get("username").asText().substring("preserve_".length()));
                assertEquals(codes.get(index), node.get("department").asText());
                assertEquals(DepartmentCatalog.BUILT_IN.get(index).name(), node.get("departmentName").asText());
                seen++;
            }
        }
        assertEquals(codes.size(), seen);
    }
}
