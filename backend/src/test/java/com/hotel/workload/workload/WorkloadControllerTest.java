package com.hotel.workload.workload;

import com.hotel.workload.assignment.Assignment;
import com.hotel.workload.assignment.AssignmentRepository;
import com.hotel.workload.security.Role;
import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
import com.hotel.workload.staff.Availability;
import com.hotel.workload.staff.Department;
import com.hotel.workload.staff.Staff;
import com.hotel.workload.staff.StaffRepository;
import com.hotel.workload.task.Priority;
import com.hotel.workload.task.Task;
import com.hotel.workload.task.TaskRepository;
import com.hotel.workload.task.TaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
public class WorkloadControllerTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private StaffRepository staffRepository;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private AssignmentRepository assignmentRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private MockMvc mockMvc;

    @BeforeEach
    public void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();

        // Ensure seed admin exists (shared across test classes in the same test DB)
        if (userRepository.findByUsername("testadmin").isEmpty()) {
            User admin = new User();
            admin.setUsername("testadmin");
            admin.setEmail("testadmin@hotel.com");
            admin.setPassword(passwordEncoder.encode("password"));
            admin.setRole(Role.ADMIN);
            admin.setActive(true);
            userRepository.save(admin);
        }

        if (userRepository.findByUsername("testmanager").isEmpty()) {
            User manager = new User();
            manager.setUsername("testmanager");
            manager.setEmail("testmanager@hotel.com");
            manager.setPassword(passwordEncoder.encode("password"));
            manager.setRole(Role.MANAGER);
            manager.setActive(true);
            userRepository.save(manager);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Staff createStaff(String username, String email, Department dept) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode("password"));
        user.setRole(Role.STAFF);
        user.setActive(true);
        user = userRepository.saveAndFlush(user);

        Staff staff = new Staff();
        staff.setUser(user);
        staff.setName("Test " + username);
        staff.setEmail(email);
        staff.setDepartment(dept);
        staff.setAvailability(Availability.AVAILABLE);
        staff.setActive(true);
        return staffRepository.saveAndFlush(staff);
    }

    private Task createTask(String title, TaskStatus status, int estimatedDuration) {
        User admin = userRepository.findByUsername("testadmin").orElseThrow();
        Task task = new Task();
        task.setTitle(title);
        task.setPriority(Priority.MEDIUM);
        task.setStatus(status);
        task.setEstimatedDuration(estimatedDuration);
        task.setDepartment(Department.HOUSEKEEPING);
        task.setCreatedBy(admin);
        return taskRepository.saveAndFlush(task);
    }

    private Assignment assign(Task task, Staff staff) {
        Assignment a = new Assignment();
        a.setTask(task);
        a.setStaff(staff);
        return assignmentRepository.saveAndFlush(a);
    }

    // ── Access-control tests ──────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "ADMIN")
    public void testAdminCanRetrieveWorkloadReturns200() throws Exception {
        mockMvc.perform(get("/api/workload"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    public void testManagerCanRetrieveWorkloadReturns200() throws Exception {
        mockMvc.perform(get("/api/workload"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @WithMockUser(username = "teststaff1", roles = "STAFF")
    @Transactional
    public void testStaffCanRetrieveWorkloadReturns200AndOnlyOwnData() throws Exception {
        Staff ownStaff = createStaff("teststaff1", "teststaff1@hotel.com", Department.HOUSEKEEPING);
        Staff otherStaff = createStaff("teststaff2", "teststaff2@hotel.com", Department.FRONT_DESK);

        MvcResult result = mockMvc.perform(get("/api/workload"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andReturn();

        String json = result.getResponse().getContentAsString();
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode root = om.readTree(json);

        org.junit.jupiter.api.Assertions.assertEquals(1, root.size());
        
        com.fasterxml.jackson.databind.JsonNode staffEntry = root.get(0);
        org.junit.jupiter.api.Assertions.assertEquals(ownStaff.getId().toString(), staffEntry.get("staffId").asText());
        org.junit.jupiter.api.Assertions.assertFalse(json.contains(otherStaff.getName()));
    }

    @Test
    public void testUnauthenticatedRequestReturns401() throws Exception {
        mockMvc.perform(get("/api/workload"))
                .andExpect(status().isUnauthorized());
    }

    // ── Business logic tests ──────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "ADMIN")
    @Transactional
    public void testStaffWithNoAssignmentsHasZeroValues() throws Exception {
        Staff staff = createStaff("wlnoassign", "wlnoassign@hotel.com", Department.HOUSEKEEPING);

        MvcResult result = mockMvc.perform(get("/api/workload"))
                .andExpect(status().isOk())
                .andReturn();

        String json = result.getResponse().getContentAsString();
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode root = om.readTree(json);

        com.fasterxml.jackson.databind.JsonNode staffEntry = null;
        for (com.fasterxml.jackson.databind.JsonNode node : root) {
            if (node.get("staffId").asText().equals(staff.getId().toString())) {
                staffEntry = node;
                break;
            }
        }

        org.junit.jupiter.api.Assertions.assertNotNull(staffEntry, "Staff should appear in workload response");
        org.junit.jupiter.api.Assertions.assertEquals(0, staffEntry.get("activeTaskCount").asInt());
        org.junit.jupiter.api.Assertions.assertEquals(0, staffEntry.get("totalEstimatedDuration").asInt());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @Transactional
    public void testStaffWithMultipleActiveTasksGetCorrectSum() throws Exception {
        Staff staff = createStaff("wlmulti", "wlmulti@hotel.com", Department.HOUSEKEEPING);

        Task t1 = createTask("WL Task Pending", TaskStatus.PENDING, 60);
        Task t2 = createTask("WL Task Assigned", TaskStatus.ASSIGNED, 90);
        Task t3 = createTask("WL Task InProgress", TaskStatus.IN_PROGRESS, 50);
        assign(t1, staff);
        assign(t2, staff);
        assign(t3, staff);

        MvcResult result = mockMvc.perform(get("/api/workload"))
                .andExpect(status().isOk())
                .andReturn();

        String json = result.getResponse().getContentAsString();
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode root = om.readTree(json);

        com.fasterxml.jackson.databind.JsonNode staffEntry = null;
        for (com.fasterxml.jackson.databind.JsonNode node : root) {
            if (node.get("staffId").asText().equals(staff.getId().toString())) {
                staffEntry = node;
                break;
            }
        }

        org.junit.jupiter.api.Assertions.assertNotNull(staffEntry);
        org.junit.jupiter.api.Assertions.assertEquals(3, staffEntry.get("activeTaskCount").asInt());
        org.junit.jupiter.api.Assertions.assertEquals(200, staffEntry.get("totalEstimatedDuration").asInt());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @Transactional
    public void testCompletedTasksAreExcludedFromWorkload() throws Exception {
        Staff staff = createStaff("wlcompleted", "wlcompleted@hotel.com", Department.HOUSEKEEPING);

        Task activeTask = createTask("WL Active Task", TaskStatus.PENDING, 60);
        Task completedTask = createTask("WL Completed Task", TaskStatus.COMPLETED, 120);
        assign(activeTask, staff);
        assign(completedTask, staff);

        MvcResult result = mockMvc.perform(get("/api/workload"))
                .andExpect(status().isOk())
                .andReturn();

        String json = result.getResponse().getContentAsString();
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode root = om.readTree(json);

        com.fasterxml.jackson.databind.JsonNode staffEntry = null;
        for (com.fasterxml.jackson.databind.JsonNode node : root) {
            if (node.get("staffId").asText().equals(staff.getId().toString())) {
                staffEntry = node;
                break;
            }
        }

        org.junit.jupiter.api.Assertions.assertNotNull(staffEntry);
        // Only the PENDING task (60 min) should count; COMPLETED is excluded
        org.junit.jupiter.api.Assertions.assertEquals(1, staffEntry.get("activeTaskCount").asInt());
        org.junit.jupiter.api.Assertions.assertEquals(60, staffEntry.get("totalEstimatedDuration").asInt());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @Transactional
    public void testCancelledTasksAreExcludedFromWorkload() throws Exception {
        Staff staff = createStaff("wlcancelled", "wlcancelled@hotel.com", Department.HOUSEKEEPING);

        Task activeTask = createTask("WL Active For Cancel Test", TaskStatus.IN_PROGRESS, 45);
        Task cancelledTask = createTask("WL Cancelled Task", TaskStatus.CANCELLED, 90);
        assign(activeTask, staff);
        assign(cancelledTask, staff);

        MvcResult result = mockMvc.perform(get("/api/workload"))
                .andExpect(status().isOk())
                .andReturn();

        String json = result.getResponse().getContentAsString();
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode root = om.readTree(json);

        com.fasterxml.jackson.databind.JsonNode staffEntry = null;
        for (com.fasterxml.jackson.databind.JsonNode node : root) {
            if (node.get("staffId").asText().equals(staff.getId().toString())) {
                staffEntry = node;
                break;
            }
        }

        org.junit.jupiter.api.Assertions.assertNotNull(staffEntry);
        // Only IN_PROGRESS (45 min) counts; CANCELLED is excluded
        org.junit.jupiter.api.Assertions.assertEquals(1, staffEntry.get("activeTaskCount").asInt());
        org.junit.jupiter.api.Assertions.assertEquals(45, staffEntry.get("totalEstimatedDuration").asInt());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @Transactional
    public void testActiveTaskCountIsCorrect() throws Exception {
        Staff staff = createStaff("wlcount", "wlcount@hotel.com", Department.FRONT_DESK);

        Task t1 = createTask("WL Count Task 1", TaskStatus.PENDING, 30);
        Task t2 = createTask("WL Count Task 2", TaskStatus.ASSIGNED, 30);
        assign(t1, staff);
        assign(t2, staff);

        MvcResult result = mockMvc.perform(get("/api/workload"))
                .andExpect(status().isOk())
                .andReturn();

        String json = result.getResponse().getContentAsString();
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode root = om.readTree(json);

        com.fasterxml.jackson.databind.JsonNode staffEntry = null;
        for (com.fasterxml.jackson.databind.JsonNode node : root) {
            if (node.get("staffId").asText().equals(staff.getId().toString())) {
                staffEntry = node;
                break;
            }
        }

        org.junit.jupiter.api.Assertions.assertNotNull(staffEntry);
        org.junit.jupiter.api.Assertions.assertEquals(2, staffEntry.get("activeTaskCount").asInt());
    }

    // ── Workload threshold tests ───────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "ADMIN")
    @Transactional
    public void testLowWorkloadStatusIsCalculatedCorrectly() throws Exception {
        // 120 min → LOW
        Staff staff = createStaff("wllow", "wllow@hotel.com", Department.SECURITY);
        Task task = createTask("WL Low Status Task", TaskStatus.PENDING, 120);
        assign(task, staff);

        MvcResult result = mockMvc.perform(get("/api/workload"))
                .andExpect(status().isOk())
                .andReturn();

        String json = result.getResponse().getContentAsString();
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode root = om.readTree(json);

        for (com.fasterxml.jackson.databind.JsonNode node : root) {
            if (node.get("staffId").asText().equals(staff.getId().toString())) {
                org.junit.jupiter.api.Assertions.assertEquals("LOW", node.get("workloadStatus").asText());
                return;
            }
        }
        org.junit.jupiter.api.Assertions.fail("Staff not found in workload response");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @Transactional
    public void testNormalWorkloadStatusIsCalculatedCorrectly() throws Exception {
        // 121 min → NORMAL
        Staff staff = createStaff("wlnormal", "wlnormal@hotel.com", Department.SECURITY);
        Task task = createTask("WL Normal Status Task", TaskStatus.PENDING, 121);
        assign(task, staff);

        MvcResult result = mockMvc.perform(get("/api/workload"))
                .andExpect(status().isOk())
                .andReturn();

        String json = result.getResponse().getContentAsString();
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode root = om.readTree(json);

        for (com.fasterxml.jackson.databind.JsonNode node : root) {
            if (node.get("staffId").asText().equals(staff.getId().toString())) {
                org.junit.jupiter.api.Assertions.assertEquals("NORMAL", node.get("workloadStatus").asText());
                return;
            }
        }
        org.junit.jupiter.api.Assertions.fail("Staff not found in workload response");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @Transactional
    public void testHighWorkloadStatusIsCalculatedCorrectly() throws Exception {
        // 241 min → HIGH
        Staff staff = createStaff("wlhigh", "wlhigh@hotel.com", Department.MAINTENANCE);
        Task task = createTask("WL High Status Task", TaskStatus.PENDING, 241);
        assign(task, staff);

        MvcResult result = mockMvc.perform(get("/api/workload"))
                .andExpect(status().isOk())
                .andReturn();

        String json = result.getResponse().getContentAsString();
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode root = om.readTree(json);

        for (com.fasterxml.jackson.databind.JsonNode node : root) {
            if (node.get("staffId").asText().equals(staff.getId().toString())) {
                org.junit.jupiter.api.Assertions.assertEquals("HIGH", node.get("workloadStatus").asText());
                return;
            }
        }
        org.junit.jupiter.api.Assertions.fail("Staff not found in workload response");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @Transactional
    public void testAtRiskWorkloadStatusIsCalculatedCorrectly() throws Exception {
        // 361 min → AT_RISK
        Staff staff = createStaff("wlatrisk", "wlatrisk@hotel.com", Department.MAINTENANCE);
        Task task = createTask("WL AtRisk Status Task", TaskStatus.PENDING, 361);
        assign(task, staff);

        MvcResult result = mockMvc.perform(get("/api/workload"))
                .andExpect(status().isOk())
                .andReturn();

        String json = result.getResponse().getContentAsString();
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode root = om.readTree(json);

        for (com.fasterxml.jackson.databind.JsonNode node : root) {
            if (node.get("staffId").asText().equals(staff.getId().toString())) {
                org.junit.jupiter.api.Assertions.assertEquals("AT_RISK", node.get("workloadStatus").asText());
                return;
            }
        }
        org.junit.jupiter.api.Assertions.fail("Staff not found in workload response");
    }

    // ── Data-integrity and persistence tests ──────────────────────────────────

    @Test
    @WithMockUser(roles = "ADMIN")
    @Transactional
    public void testWorkloadValuesAreCalculatedFromCurrentPostgresData() throws Exception {
        // Create a staff member, assign tasks, verify the dto reflects the DB state.
        Staff staff = createStaff("wlpgcheck", "wlpgcheck@hotel.com", Department.ROOM_SERVICE);
        Task t1 = createTask("WL PG Task 1", TaskStatus.ASSIGNED, 70);
        Task t2 = createTask("WL PG Task 2", TaskStatus.IN_PROGRESS, 80);
        assign(t1, staff);
        assign(t2, staff);

        MvcResult result = mockMvc.perform(get("/api/workload"))
                .andExpect(status().isOk())
                .andReturn();

        String json = result.getResponse().getContentAsString();
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode root = om.readTree(json);

        com.fasterxml.jackson.databind.JsonNode staffEntry = null;
        for (com.fasterxml.jackson.databind.JsonNode node : root) {
            if (node.get("staffId").asText().equals(staff.getId().toString())) {
                staffEntry = node;
                break;
            }
        }

        org.junit.jupiter.api.Assertions.assertNotNull(staffEntry);
        org.junit.jupiter.api.Assertions.assertEquals(2, staffEntry.get("activeTaskCount").asInt());
        org.junit.jupiter.api.Assertions.assertEquals(150, staffEntry.get("totalEstimatedDuration").asInt());
        // 150 min falls in NORMAL range (121-240)
        org.junit.jupiter.api.Assertions.assertEquals("NORMAL", staffEntry.get("workloadStatus").asText());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @Transactional
    public void testNoWorkloadDataIsPersistedToStaff() throws Exception {
        Staff staff = createStaff("wlnopersist", "wlnopersist@hotel.com", Department.HOUSEKEEPING);
        Task task = createTask("WL NoPersist Task", TaskStatus.ASSIGNED, 200);
        assign(task, staff);

        mockMvc.perform(get("/api/workload"))
                .andExpect(status().isOk());

        // Re-load the staff from DB and confirm workload is NOT written to any staff field.
        Staff reloaded = staffRepository.findById(staff.getId()).orElseThrow();
        // Staff entity has no workload fields — we verify the entity is unchanged
        // by checking the department and availability are still the original values.
        org.junit.jupiter.api.Assertions.assertEquals(Department.HOUSEKEEPING, reloaded.getDepartment());
        org.junit.jupiter.api.Assertions.assertEquals(Availability.AVAILABLE, reloaded.getAvailability());
        // The Staff class has no workload fields, so no workload can have been persisted.
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @Transactional
    public void testResponseContainsRequiredWorkloadFields() throws Exception {
        Staff staff = createStaff("wlfields", "wlfields@hotel.com", Department.FOOD_AND_BEVERAGE);

        MvcResult result = mockMvc.perform(get("/api/workload"))
                .andExpect(status().isOk())
                .andReturn();

        String json = result.getResponse().getContentAsString();
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode root = om.readTree(json);

        com.fasterxml.jackson.databind.JsonNode staffEntry = null;
        for (com.fasterxml.jackson.databind.JsonNode node : root) {
            if (node.get("staffId").asText().equals(staff.getId().toString())) {
                staffEntry = node;
                break;
            }
        }

        org.junit.jupiter.api.Assertions.assertNotNull(staffEntry);
        org.junit.jupiter.api.Assertions.assertTrue(staffEntry.has("staffId"));
        org.junit.jupiter.api.Assertions.assertTrue(staffEntry.has("staffName"));
        org.junit.jupiter.api.Assertions.assertTrue(staffEntry.has("department"));
        org.junit.jupiter.api.Assertions.assertTrue(staffEntry.has("availability"));
        org.junit.jupiter.api.Assertions.assertTrue(staffEntry.has("activeTaskCount"));
        org.junit.jupiter.api.Assertions.assertTrue(staffEntry.has("totalEstimatedDuration"));
        org.junit.jupiter.api.Assertions.assertTrue(staffEntry.has("workloadStatus"));
    }
}
