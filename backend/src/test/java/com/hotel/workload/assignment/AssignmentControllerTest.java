package com.hotel.workload.assignment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.workload.security.Role;
import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
import com.hotel.workload.staff.Availability;
import com.hotel.workload.staff.Department;
import com.hotel.workload.staff.TestDepartments;
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
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
public class AssignmentControllerTest {

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
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    public void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();

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

    private Staff createTestStaff(String username, String email, boolean active, Availability availability) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode("password"));
        user.setRole(Role.STAFF);
        user.setActive(active);
        user = userRepository.saveAndFlush(user);

        Staff staff = new Staff();
        staff.setUser(user);
        staff.setName("Test Staff " + username);
        staff.setEmail(email);
        staff.setDepartment(TestDepartments.HOUSEKEEPING);
        staff.setAvailability(availability);
        staff.setActive(active);
        return staffRepository.saveAndFlush(staff);
    }

    private Task createTestTask(String title, TaskStatus status) {
        User admin = userRepository.findByUsername("testadmin").orElseThrow();
        Task task = new Task();
        task.setTitle(title);
        task.setPriority(Priority.MEDIUM);
        task.setStatus(status);
        task.setDepartment(TestDepartments.HOUSEKEEPING);
        task.setCreatedBy(admin);
        return taskRepository.saveAndFlush(task);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @Transactional
    public void testAdminCanAssignTaskReturns201() throws Exception {
        Staff staff = createTestStaff("adminstaff", "adminstaff@hotel.com", true, Availability.AVAILABLE);
        Task task = createTestTask("Admin Assign Task", TaskStatus.PENDING);

        CreateAssignmentRequest request = new CreateAssignmentRequest(task.getId(), staff.getId());

        mockMvc.perform(post("/api/assignments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.taskId").value(task.getId().toString()))
                .andExpect(jsonPath("$.staffId").value(staff.getId().toString()))
                .andExpect(jsonPath("$.assignedAt", notNullValue()));
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    @Transactional
    public void testManagerCanAssignTaskReturns201() throws Exception {
        Staff staff = createTestStaff("mgrstaff", "mgrstaff@hotel.com", true, Availability.AVAILABLE);
        Task task = createTestTask("Manager Assign Task", TaskStatus.PENDING);

        CreateAssignmentRequest request = new CreateAssignmentRequest(task.getId(), staff.getId());

        mockMvc.perform(post("/api/assignments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.taskId").value(task.getId().toString()))
                .andExpect(jsonPath("$.staffId").value(staff.getId().toString()));
    }

    @Test
    @WithMockUser(roles = "STAFF")
    @Transactional
    public void testStaffReceives403() throws Exception {
        Staff staff = createTestStaff("staffuser", "staffuser@hotel.com", true, Availability.AVAILABLE);
        Task task = createTestTask("Staff Forbidden Task", TaskStatus.PENDING);

        CreateAssignmentRequest request = new CreateAssignmentRequest(task.getId(), staff.getId());

        mockMvc.perform(post("/api/assignments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @Transactional
    public void testUnauthenticatedReceives401() throws Exception {
        Staff staff = createTestStaff("unauthstaff", "unauthstaff@hotel.com", true, Availability.AVAILABLE);
        Task task = createTestTask("Unauth Task", TaskStatus.PENDING);

        CreateAssignmentRequest request = new CreateAssignmentRequest(task.getId(), staff.getId());

        mockMvc.perform(post("/api/assignments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @Transactional
    public void testNonExistentTaskReturns404() throws Exception {
        Staff staff = createTestStaff("staff404", "staff404@hotel.com", true, Availability.AVAILABLE);
        UUID nonExistentTaskId = UUID.randomUUID();

        CreateAssignmentRequest request = new CreateAssignmentRequest(nonExistentTaskId, staff.getId());

        mockMvc.perform(post("/api/assignments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @Transactional
    public void testNonExistentStaffReturns404() throws Exception {
        Task task = createTestTask("Task 404 Staff", TaskStatus.PENDING);
        UUID nonExistentStaffId = UUID.randomUUID();

        CreateAssignmentRequest request = new CreateAssignmentRequest(task.getId(), nonExistentStaffId);

        mockMvc.perform(post("/api/assignments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @Transactional
    public void testInactiveStaffCannotBeAssignedReturns400() throws Exception {
        Staff staff = createTestStaff("inactivestaff", "inactivestaff@hotel.com", false, Availability.AVAILABLE);
        Task task = createTestTask("Inactive Staff Task", TaskStatus.PENDING);

        CreateAssignmentRequest request = new CreateAssignmentRequest(task.getId(), staff.getId());

        mockMvc.perform(post("/api/assignments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @Transactional
    public void testUnavailableStaffCannotBeAssignedReturns400() throws Exception {
        Staff staff = createTestStaff("unavailstaff", "unavailstaff@hotel.com", true, Availability.UNAVAILABLE);
        Task task = createTestTask("Unavail Staff Task", TaskStatus.PENDING);

        CreateAssignmentRequest request = new CreateAssignmentRequest(task.getId(), staff.getId());

        mockMvc.perform(post("/api/assignments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @Transactional
    public void testCompletedTaskCannotBeAssignedReturns400() throws Exception {
        Staff staff = createTestStaff("completedstaff", "completedstaff@hotel.com", true, Availability.AVAILABLE);
        Task task = createTestTask("Completed Task", TaskStatus.COMPLETED);

        CreateAssignmentRequest request = new CreateAssignmentRequest(task.getId(), staff.getId());

        mockMvc.perform(post("/api/assignments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @Transactional
    public void testDuplicateActiveAssignmentIsRejectedReturns409() throws Exception {
        Staff staff1 = createTestStaff("dupstaff1", "dupstaff1@hotel.com", true, Availability.AVAILABLE);
        Staff staff2 = createTestStaff("dupstaff2", "dupstaff2@hotel.com", true, Availability.AVAILABLE);
        Task task = createTestTask("Duplicate Assignment Task", TaskStatus.PENDING);

        CreateAssignmentRequest request1 = new CreateAssignmentRequest(task.getId(), staff1.getId());
        mockMvc.perform(post("/api/assignments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request1)))
                .andExpect(status().isCreated());

        CreateAssignmentRequest request2 = new CreateAssignmentRequest(task.getId(), staff2.getId());
        mockMvc.perform(post("/api/assignments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request2)))
                .andExpect(status().isConflict());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @Transactional
    public void testAssignmentIsPersistedInPostgres() throws Exception {
        Staff staff = createTestStaff("persiststaff", "persiststaff@hotel.com", true, Availability.AVAILABLE);
        Task task = createTestTask("Persist Task", TaskStatus.PENDING);

        CreateAssignmentRequest request = new CreateAssignmentRequest(task.getId(), staff.getId());

        MvcResult result = mockMvc.perform(post("/api/assignments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        UUID assignmentId = UUID.fromString(objectMapper.readTree(responseBody).get("id").asText());

        Assignment persisted = assignmentRepository.findById(assignmentId).orElse(null);
        assertNotNull(persisted);
        assertEquals(task.getId(), persisted.getTask().getId());
        assertEquals(staff.getId(), persisted.getStaff().getId());
        assertNotNull(persisted.getAssignedAt());

        Task updatedTask = taskRepository.findById(task.getId()).orElseThrow();
        assertEquals(TaskStatus.ASSIGNED, updatedTask.getStatus());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @Transactional
    public void testAssignmentUuidIsAutomaticallyGenerated() throws Exception {
        Staff staff = createTestStaff("uuidstaff", "uuidstaff@hotel.com", true, Availability.AVAILABLE);
        Task task = createTestTask("UUID Test Task", TaskStatus.PENDING);

        String jsonWithoutId = String.format("""
                {
                    "taskId": "%s",
                    "staffId": "%s"
                }
                """, task.getId(), staff.getId());

        MvcResult result = mockMvc.perform(post("/api/assignments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonWithoutId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        UUID generatedId = UUID.fromString(objectMapper.readTree(responseBody).get("id").asText());
        assertNotNull(generatedId);
        assertTrue(assignmentRepository.existsById(generatedId));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    public void testValidationFailsWhenFieldsMissingReturns400() throws Exception {
        String invalidJson = "{}";

        mockMvc.perform(post("/api/assignments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isBadRequest());
    }

    // ── GET /api/assignments ──────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "ADMIN")
    public void testAdminCanRetrieveAssignmentsReturns200() throws Exception {
        mockMvc.perform(get("/api/assignments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    public void testManagerCanRetrieveAssignmentsReturns200() throws Exception {
        mockMvc.perform(get("/api/assignments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @WithMockUser(roles = "STAFF")
    public void testStaffCanRetrieveAssignmentsReturns200() throws Exception {
        mockMvc.perform(get("/api/assignments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @WithMockUser(username = "staffuser_a1", roles = "STAFF")
    @Transactional
    public void testStaffCanOnlyRetrieveAssignedAssignments() throws Exception {
        Staff staff1 = createTestStaff("staffuser_a1", "staffuser_a1@hotel.com", true, Availability.AVAILABLE);
        Staff staff2 = createTestStaff("staffuser_a2", "staffuser_a2@hotel.com", true, Availability.AVAILABLE);

        Task task1 = createTestTask("Staff 1 Task", TaskStatus.PENDING);
        Task task2 = createTestTask("Staff 2 Task", TaskStatus.PENDING);

        Assignment a1 = new Assignment();
        a1.setTask(task1);
        a1.setStaff(staff1);
        a1 = assignmentRepository.saveAndFlush(a1);

        Assignment a2 = new Assignment();
        a2.setTask(task2);
        a2.setStaff(staff2);
        a2 = assignmentRepository.saveAndFlush(a2);

        mockMvc.perform(get("/api/assignments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[?(@.id == '" + a1.getId() + "')]").exists())
                .andExpect(jsonPath("$[?(@.id == '" + a2.getId() + "')]").doesNotExist());
    }

    @Test
    @WithMockUser(username = "staffuser_a2", roles = "STAFF")
    @Transactional
    public void testStaffCannotRetrieveOtherStaffAssignments() throws Exception {
        Staff staff1 = createTestStaff("staffuser_a1_other", "staffuser_a1_other@hotel.com", true, Availability.AVAILABLE);
        Staff staff2 = createTestStaff("staffuser_a2", "staffuser_a2@hotel.com", true, Availability.AVAILABLE);

        Task task1 = createTestTask("Staff 1 Task", TaskStatus.PENDING);
        Task task2 = createTestTask("Staff 2 Task", TaskStatus.PENDING);

        Assignment a1 = new Assignment();
        a1.setTask(task1);
        a1.setStaff(staff1);
        a1 = assignmentRepository.saveAndFlush(a1);

        Assignment a2 = new Assignment();
        a2.setTask(task2);
        a2.setStaff(staff2);
        a2 = assignmentRepository.saveAndFlush(a2);

        mockMvc.perform(get("/api/assignments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[?(@.id == '" + a2.getId() + "')]").exists())
                .andExpect(jsonPath("$[?(@.id == '" + a1.getId() + "')]").doesNotExist());
    }

    @Test
    @WithMockUser(username = "testadmin", roles = "ADMIN")
    @Transactional
    public void testAdminCanRetrieveAllAssignmentsAcrossAllStaff() throws Exception {
        Staff staff1 = createTestStaff("admin_staff_a1", "admin_staff_a1@hotel.com", true, Availability.AVAILABLE);
        Staff staff2 = createTestStaff("admin_staff_a2", "admin_staff_a2@hotel.com", true, Availability.AVAILABLE);

        Task task1 = createTestTask("Admin Cross Task 1", TaskStatus.PENDING);
        Task task2 = createTestTask("Admin Cross Task 2", TaskStatus.PENDING);

        Assignment a1 = new Assignment();
        a1.setTask(task1);
        a1.setStaff(staff1);
        a1 = assignmentRepository.saveAndFlush(a1);

        Assignment a2 = new Assignment();
        a2.setTask(task2);
        a2.setStaff(staff2);
        a2 = assignmentRepository.saveAndFlush(a2);

        mockMvc.perform(get("/api/assignments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[?(@.id == '" + a1.getId() + "')]").exists())
                .andExpect(jsonPath("$[?(@.id == '" + a2.getId() + "')]").exists());
    }

    @Test
    @WithMockUser(username = "testmanager", roles = "MANAGER")
    @Transactional
    public void testManagerCanRetrieveAllAssignmentsAcrossAllStaff() throws Exception {
        Staff staff1 = createTestStaff("mgr_staff_a1", "mgr_staff_a1@hotel.com", true, Availability.AVAILABLE);
        Staff staff2 = createTestStaff("mgr_staff_a2", "mgr_staff_a2@hotel.com", true, Availability.AVAILABLE);

        Task task1 = createTestTask("Manager Cross Task 1", TaskStatus.PENDING);
        Task task2 = createTestTask("Manager Cross Task 2", TaskStatus.PENDING);

        Assignment a1 = new Assignment();
        a1.setTask(task1);
        a1.setStaff(staff1);
        a1 = assignmentRepository.saveAndFlush(a1);

        Assignment a2 = new Assignment();
        a2.setTask(task2);
        a2.setStaff(staff2);
        a2 = assignmentRepository.saveAndFlush(a2);

        mockMvc.perform(get("/api/assignments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[?(@.id == '" + a1.getId() + "')]").exists())
                .andExpect(jsonPath("$[?(@.id == '" + a2.getId() + "')]").exists());
    }

    @Test
    @WithMockUser(username = "unassigned_staff_a", roles = "STAFF")
    @Transactional
    public void testStaffWithNoAssignmentsReceivesEmptyAssignmentList() throws Exception {
        createTestStaff("unassigned_staff_a", "unassigned_staff_a@hotel.com", true, Availability.AVAILABLE);

        mockMvc.perform(get("/api/assignments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    public void testUnauthenticatedCannotRetrieveAssignmentsReturns401() throws Exception {
        mockMvc.perform(get("/api/assignments"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @Transactional
    public void testResponseContainsPersistedAssignmentDataAndDtoInfo() throws Exception {
        Staff staff = createTestStaff("getstaffinfo", "getstaffinfo@hotel.com", true, Availability.AVAILABLE);
        Task task = createTestTask("Get Info Task", TaskStatus.PENDING);

        Assignment assignment = new Assignment();
        assignment.setTask(task);
        assignment.setStaff(staff);
        assignment = assignmentRepository.saveAndFlush(assignment);

        mockMvc.perform(get("/api/assignments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + assignment.getId() + "')].taskId").value(task.getId().toString()))
                .andExpect(jsonPath("$[?(@.id == '" + assignment.getId() + "')].taskTitle").value(task.getTitle()))
                .andExpect(jsonPath("$[?(@.id == '" + assignment.getId() + "')].staffId").value(staff.getId().toString()))
                .andExpect(jsonPath("$[?(@.id == '" + assignment.getId() + "')].staffName").value(staff.getName()))
                .andExpect(jsonPath("$[?(@.id == '" + assignment.getId() + "')].assignedAt").isNotEmpty());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @Transactional
    public void testAssignmentsOrderedByAssignedAtDesc() throws Exception {
        Staff staff1 = createTestStaff("orderstaff1", "orderstaff1@hotel.com", true, Availability.AVAILABLE);
        Staff staff2 = createTestStaff("orderstaff2", "orderstaff2@hotel.com", true, Availability.AVAILABLE);
        Task task1 = createTestTask("Earlier Task", TaskStatus.PENDING);
        Task task2 = createTestTask("Later Task", TaskStatus.PENDING);

        Assignment a1 = new Assignment();
        a1.setTask(task1);
        a1.setStaff(staff1);
        a1 = assignmentRepository.saveAndFlush(a1);

        Assignment a2 = new Assignment();
        a2.setTask(task2);
        a2.setStaff(staff2);
        a2 = assignmentRepository.saveAndFlush(a2);

        // Explicitly set timestamps to verify descending ordering
        a1.setAssignedAt(java.time.LocalDateTime.now().minusHours(2));
        assignmentRepository.saveAndFlush(a1);

        a2.setAssignedAt(java.time.LocalDateTime.now().minusHours(1));
        assignmentRepository.saveAndFlush(a2);

        MvcResult result = mockMvc.perform(get("/api/assignments"))
                .andExpect(status().isOk())
                .andReturn();

        String json = result.getResponse().getContentAsString();
        com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(json);

        int indexA1 = -1;
        int indexA2 = -1;
        for (int i = 0; i < root.size(); i++) {
            String idStr = root.get(i).get("id").asText();
            if (idStr.equals(a1.getId().toString())) {
                indexA1 = i;
            } else if (idStr.equals(a2.getId().toString())) {
                indexA2 = i;
            }
        }

        assertTrue(indexA1 >= 0, "Assignment 1 should be present");
        assertTrue(indexA2 >= 0, "Assignment 2 should be present");
        assertTrue(indexA2 < indexA1, "Newer assignment (a2) must appear before older assignment (a1) in descending order");
    }

    // ── PUT /api/assignments/{assignmentId} (Reassign Task) ───────────────────

    @Test
    @WithMockUser(roles = "ADMIN")
    @Transactional
    public void testAdminCanReassignTaskReturns200() throws Exception {
        Staff staffA = createTestStaff("reassignstaffA", "reassignstaffA@hotel.com", true, Availability.AVAILABLE);
        Staff staffB = createTestStaff("reassignstaffB", "reassignstaffB@hotel.com", true, Availability.AVAILABLE);
        Task task = createTestTask("Reassign Task Admin", TaskStatus.PENDING);

        Assignment assignment = new Assignment();
        assignment.setTask(task);
        assignment.setStaff(staffA);
        assignment = assignmentRepository.saveAndFlush(assignment);

        UpdateAssignmentRequest request = new UpdateAssignmentRequest(staffB.getId());

        mockMvc.perform(put("/api/assignments/" + assignment.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(assignment.getId().toString()))
                .andExpect(jsonPath("$.taskId").value(task.getId().toString()))
                .andExpect(jsonPath("$.staffId").value(staffB.getId().toString()))
                .andExpect(jsonPath("$.staffName").value(staffB.getName()));
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    @Transactional
    public void testManagerCanReassignTaskReturns200() throws Exception {
        Staff staffA = createTestStaff("reassignmgrA", "reassignmgrA@hotel.com", true, Availability.AVAILABLE);
        Staff staffB = createTestStaff("reassignmgrB", "reassignmgrB@hotel.com", true, Availability.AVAILABLE);
        Task task = createTestTask("Reassign Task Mgr", TaskStatus.PENDING);

        Assignment assignment = new Assignment();
        assignment.setTask(task);
        assignment.setStaff(staffA);
        assignment = assignmentRepository.saveAndFlush(assignment);

        UpdateAssignmentRequest request = new UpdateAssignmentRequest(staffB.getId());

        mockMvc.perform(put("/api/assignments/" + assignment.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(assignment.getId().toString()))
                .andExpect(jsonPath("$.staffId").value(staffB.getId().toString()));
    }

    @Test
    @WithMockUser(roles = "STAFF")
    @Transactional
    public void testStaffRoleForbiddenOnReassignReturns403() throws Exception {
        Staff staffA = createTestStaff("reassignstfA", "reassignstfA@hotel.com", true, Availability.AVAILABLE);
        Staff staffB = createTestStaff("reassignstfB", "reassignstfB@hotel.com", true, Availability.AVAILABLE);
        Task task = createTestTask("Reassign Task Staff", TaskStatus.PENDING);

        Assignment assignment = new Assignment();
        assignment.setTask(task);
        assignment.setStaff(staffA);
        assignment = assignmentRepository.saveAndFlush(assignment);

        UpdateAssignmentRequest request = new UpdateAssignmentRequest(staffB.getId());

        mockMvc.perform(put("/api/assignments/" + assignment.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @Transactional
    public void testUnauthenticatedOnReassignReturns401() throws Exception {
        Staff staffA = createTestStaff("reassignunauthA", "reassignunauthA@hotel.com", true, Availability.AVAILABLE);
        Staff staffB = createTestStaff("reassignunauthB", "reassignunauthB@hotel.com", true, Availability.AVAILABLE);
        Task task = createTestTask("Reassign Task Unauth", TaskStatus.PENDING);

        Assignment assignment = new Assignment();
        assignment.setTask(task);
        assignment.setStaff(staffA);
        assignment = assignmentRepository.saveAndFlush(assignment);

        UpdateAssignmentRequest request = new UpdateAssignmentRequest(staffB.getId());

        mockMvc.perform(put("/api/assignments/" + assignment.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @Transactional
    public void testNonExistentAssignmentReturns404() throws Exception {
        Staff staff = createTestStaff("reassignneStaff", "reassignneStaff@hotel.com", true, Availability.AVAILABLE);
        UUID nonExistentAssignmentId = UUID.randomUUID();

        UpdateAssignmentRequest request = new UpdateAssignmentRequest(staff.getId());

        mockMvc.perform(put("/api/assignments/" + nonExistentAssignmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @Transactional
    public void testNonExistentTargetStaffReturns404() throws Exception {
        Staff staffA = createTestStaff("reassignorigStaff", "reassignorigStaff@hotel.com", true, Availability.AVAILABLE);
        Task task = createTestTask("Reassign NonExistent Staff Task", TaskStatus.PENDING);

        Assignment assignment = new Assignment();
        assignment.setTask(task);
        assignment.setStaff(staffA);
        assignment = assignmentRepository.saveAndFlush(assignment);

        UUID nonExistentStaffId = UUID.randomUUID();
        UpdateAssignmentRequest request = new UpdateAssignmentRequest(nonExistentStaffId);

        mockMvc.perform(put("/api/assignments/" + assignment.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @Transactional
    public void testInactiveTargetStaffIsRejectedReturns400() throws Exception {
        Staff staffA = createTestStaff("reassignactA", "reassignactA@hotel.com", true, Availability.AVAILABLE);
        Staff staffB = createTestStaff("reassigninactB", "reassigninactB@hotel.com", false, Availability.AVAILABLE);
        Task task = createTestTask("Inactive Target Task", TaskStatus.PENDING);

        Assignment assignment = new Assignment();
        assignment.setTask(task);
        assignment.setStaff(staffA);
        assignment = assignmentRepository.saveAndFlush(assignment);

        UpdateAssignmentRequest request = new UpdateAssignmentRequest(staffB.getId());

        mockMvc.perform(put("/api/assignments/" + assignment.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @Transactional
    public void testUnavailableTargetStaffIsRejectedReturns400() throws Exception {
        Staff staffA = createTestStaff("reassignavailA", "reassignavailA@hotel.com", true, Availability.AVAILABLE);
        Staff staffB = createTestStaff("reassignunavailB", "reassignunavailB@hotel.com", true, Availability.UNAVAILABLE);
        Task task = createTestTask("Unavailable Target Task", TaskStatus.PENDING);

        Assignment assignment = new Assignment();
        assignment.setTask(task);
        assignment.setStaff(staffA);
        assignment = assignmentRepository.saveAndFlush(assignment);

        UpdateAssignmentRequest request = new UpdateAssignmentRequest(staffB.getId());

        mockMvc.perform(put("/api/assignments/" + assignment.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @Transactional
    public void testCompletedTaskCannotBeReassignedReturns400() throws Exception {
        Staff staffA = createTestStaff("reassigncompA", "reassigncompA@hotel.com", true, Availability.AVAILABLE);
        Staff staffB = createTestStaff("reassigncompB", "reassigncompB@hotel.com", true, Availability.AVAILABLE);
        Task task = createTestTask("Completed Reassign Task", TaskStatus.COMPLETED);

        Assignment assignment = new Assignment();
        assignment.setTask(task);
        assignment.setStaff(staffA);
        assignment = assignmentRepository.saveAndFlush(assignment);

        UpdateAssignmentRequest request = new UpdateAssignmentRequest(staffB.getId());

        mockMvc.perform(put("/api/assignments/" + assignment.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @Transactional
    public void testCancelledTaskCannotBeReassignedReturns400() throws Exception {
        Staff staffA = createTestStaff("reassigncancA", "reassigncancA@hotel.com", true, Availability.AVAILABLE);
        Staff staffB = createTestStaff("reassigncancB", "reassigncancB@hotel.com", true, Availability.AVAILABLE);
        Task task = createTestTask("Cancelled Reassign Task", TaskStatus.CANCELLED);

        Assignment assignment = new Assignment();
        assignment.setTask(task);
        assignment.setStaff(staffA);
        assignment = assignmentRepository.saveAndFlush(assignment);

        UpdateAssignmentRequest request = new UpdateAssignmentRequest(staffB.getId());

        mockMvc.perform(put("/api/assignments/" + assignment.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @Transactional
    public void testReassignToSameStaffRejectedReturns409() throws Exception {
        Staff staffA = createTestStaff("reassignsameA", "reassignsameA@hotel.com", true, Availability.AVAILABLE);
        Task task = createTestTask("Same Staff Task", TaskStatus.PENDING);

        Assignment assignment = new Assignment();
        assignment.setTask(task);
        assignment.setStaff(staffA);
        assignment = assignmentRepository.saveAndFlush(assignment);

        UpdateAssignmentRequest request = new UpdateAssignmentRequest(staffA.getId());

        mockMvc.perform(put("/api/assignments/" + assignment.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @Transactional
    public void testReassignmentPreservesUuidAndTaskIdAndPersistsNewStaff() throws Exception {
        Staff staffA = createTestStaff("reassignorig", "reassignorig@hotel.com", true, Availability.AVAILABLE);
        Staff staffB = createTestStaff("reassigntarget", "reassigntarget@hotel.com", true, Availability.AVAILABLE);
        Task task = createTestTask("Reassign Full Test Task", TaskStatus.PENDING);

        Assignment assignment = new Assignment();
        assignment.setTask(task);
        assignment.setStaff(staffA);
        assignment = assignmentRepository.saveAndFlush(assignment);

        UUID originalAssignmentId = assignment.getId();
        UUID originalTaskId = task.getId();
        long initialAssignmentCount = assignmentRepository.count();

        UpdateAssignmentRequest request = new UpdateAssignmentRequest(staffB.getId());

        mockMvc.perform(put("/api/assignments/" + originalAssignmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(originalAssignmentId.toString()))
                .andExpect(jsonPath("$.taskId").value(originalTaskId.toString()))
                .andExpect(jsonPath("$.staffId").value(staffB.getId().toString()))
                .andExpect(jsonPath("$.staffName").value(staffB.getName()));

        // Verify database persistence
        Assignment persisted = assignmentRepository.findById(originalAssignmentId).orElseThrow();
        assertEquals(originalAssignmentId, persisted.getId());
        assertEquals(originalTaskId, persisted.getTask().getId());
        assertEquals(staffB.getId(), persisted.getStaff().getId());

        // Verify no second assignment was created
        assertEquals(initialAssignmentCount, assignmentRepository.count());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @Transactional
    public void testStaffInDifferentDepartmentCannotBeAssignedReturns400() throws Exception {
        Staff frontDeskStaff = createTestStaff("fdstaff1", "fdstaff1@hotel.com", true, Availability.AVAILABLE);
        frontDeskStaff.setDepartment(TestDepartments.FRONT_DESK);
        frontDeskStaff = staffRepository.saveAndFlush(frontDeskStaff);

        Task housekeepingTask = createTestTask("Housekeeping Task Dept Check", TaskStatus.PENDING);
        housekeepingTask.setDepartment(TestDepartments.HOUSEKEEPING);
        housekeepingTask = taskRepository.saveAndFlush(housekeepingTask);

        CreateAssignmentRequest request = new CreateAssignmentRequest(housekeepingTask.getId(), frontDeskStaff.getId());

        mockMvc.perform(post("/api/assignments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @Transactional
    public void testStaffInDifferentDepartmentCannotBeReassignedReturns400() throws Exception {
        Staff hkStaff = createTestStaff("hkorig", "hkorig@hotel.com", true, Availability.AVAILABLE);
        Staff fdStaff = createTestStaff("fdtarget", "fdtarget@hotel.com", true, Availability.AVAILABLE);
        fdStaff.setDepartment(TestDepartments.FRONT_DESK);
        fdStaff = staffRepository.saveAndFlush(fdStaff);

        Task housekeepingTask = createTestTask("Housekeeping Reassign Dept Check", TaskStatus.PENDING);
        housekeepingTask.setDepartment(TestDepartments.HOUSEKEEPING);
        housekeepingTask = taskRepository.saveAndFlush(housekeepingTask);

        Assignment assignment = new Assignment();
        assignment.setTask(housekeepingTask);
        assignment.setStaff(hkStaff);
        assignment = assignmentRepository.saveAndFlush(assignment);

        UpdateAssignmentRequest request = new UpdateAssignmentRequest(fdStaff.getId());

        mockMvc.perform(put("/api/assignments/" + assignment.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @Transactional
    public void testStaffWithActiveAssignmentCannotBeAssignedReturns409() throws Exception {
        Staff staff = createTestStaff("activeAssignStaff", "activeAssignStaff@hotel.com", true, Availability.AVAILABLE);
        Task task1 = createTestTask("Task With Active Staff 1", TaskStatus.PENDING);
        Task task2 = createTestTask("Task With Active Staff 2", TaskStatus.PENDING);

        // Assign task1 to staff
        CreateAssignmentRequest request1 = new CreateAssignmentRequest(task1.getId(), staff.getId());
        mockMvc.perform(post("/api/assignments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request1)))
                .andExpect(status().isCreated());

        // Attempt to assign task2 to the same staff member who already has an active assignment
        CreateAssignmentRequest request2 = new CreateAssignmentRequest(task2.getId(), staff.getId());
        mockMvc.perform(post("/api/assignments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request2)))
                .andExpect(status().isConflict());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @Transactional
    public void testStaffWithOnlyCompletedOrCancelledAssignmentsCanBeAssignedReturns201() throws Exception {
        Staff staff = createTestStaff("prevDoneStaff", "prevDoneStaff@hotel.com", true, Availability.AVAILABLE);
        Task completedTask = createTestTask("Old Completed Task", TaskStatus.COMPLETED);
        Task cancelledTask = createTestTask("Old Cancelled Task", TaskStatus.CANCELLED);
        Task newTask = createTestTask("New Task To Assign", TaskStatus.PENDING);

        // Existing completed and cancelled assignments
        Assignment assignment1 = new Assignment();
        assignment1.setTask(completedTask);
        assignment1.setStaff(staff);
        assignmentRepository.saveAndFlush(assignment1);

        Assignment assignment2 = new Assignment();
        assignment2.setTask(cancelledTask);
        assignment2.setStaff(staff);
        assignmentRepository.saveAndFlush(assignment2);

        // Now assign the new task - should succeed
        CreateAssignmentRequest request = new CreateAssignmentRequest(newTask.getId(), staff.getId());
        mockMvc.perform(post("/api/assignments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @Transactional
    public void testReassignToStaffWithActiveAssignmentOnAnotherTaskReturns409() throws Exception {
        Staff staffA = createTestStaff("reassignAActive", "reassignAActive@hotel.com", true, Availability.AVAILABLE);
        Staff staffB = createTestStaff("reassignBActive", "reassignBActive@hotel.com", true, Availability.AVAILABLE);
        Task task1 = createTestTask("Reassign Task 1", TaskStatus.PENDING);
        Task task2 = createTestTask("Reassign Task 2", TaskStatus.PENDING);

        // Assign task1 to staffA
        Assignment assignment1 = new Assignment();
        assignment1.setTask(task1);
        assignment1.setStaff(staffA);
        assignment1 = assignmentRepository.saveAndFlush(assignment1);

        // Assign task2 to staffB (active)
        Assignment assignment2 = new Assignment();
        assignment2.setTask(task2);
        assignment2.setStaff(staffB);
        assignmentRepository.saveAndFlush(assignment2);

        // Reassign task1 to staffB who already has an active assignment on task2 -> should be rejected with 409
        UpdateAssignmentRequest request = new UpdateAssignmentRequest(staffB.getId());
        mockMvc.perform(put("/api/assignments/" + assignment1.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @Transactional
    public void testReassignToStaffWithOnlyCompletedOrCancelledAssignmentReturns200() throws Exception {
        Staff staffA = createTestStaff("reassignAOld", "reassignAOld@hotel.com", true, Availability.AVAILABLE);
        Staff staffB = createTestStaff("reassignBOld", "reassignBOld@hotel.com", true, Availability.AVAILABLE);
        Task task1 = createTestTask("Reassign Task 1", TaskStatus.PENDING);
        Task oldTask = createTestTask("Old Completed Task for B", TaskStatus.COMPLETED);

        // Task1 assigned to staffA
        Assignment assignment1 = new Assignment();
        assignment1.setTask(task1);
        assignment1.setStaff(staffA);
        assignment1 = assignmentRepository.saveAndFlush(assignment1);

        // StaffB only has a completed assignment
        Assignment completedAssignment = new Assignment();
        completedAssignment.setTask(oldTask);
        completedAssignment.setStaff(staffB);
        assignmentRepository.saveAndFlush(completedAssignment);

        // Reassign task1 to staffB -> should succeed with 200
        UpdateAssignmentRequest request = new UpdateAssignmentRequest(staffB.getId());
        mockMvc.perform(put("/api/assignments/" + assignment1.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.staffId").value(staffB.getId().toString()));
    }
}
