package com.hotel.workload.task;

import com.hotel.workload.security.Role;
import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
import com.hotel.workload.staff.Department;
import com.hotel.workload.staff.Availability;
import com.hotel.workload.staff.Staff;
import com.hotel.workload.staff.StaffRepository;
import com.hotel.workload.assignment.Assignment;
import com.hotel.workload.assignment.AssignmentRepository;
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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
public class TaskControllerTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private StaffRepository staffRepository;

    @Autowired
    private AssignmentRepository assignmentRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    private MockMvc mockMvc;

    @BeforeEach
    public void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();

        // Seed admin user if not present (for createTask tests that resolve principal from DB)
        if (userRepository.findByUsername("testadmin").isEmpty()) {
            User admin = new User();
            admin.setUsername("testadmin");
            admin.setEmail("testadmin@hotel.com");
            admin.setPassword(passwordEncoder.encode("password"));
            admin.setRole(Role.ADMIN);
            admin.setActive(true);
            userRepository.save(admin);
        }

        // Seed manager user if not present
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

    private Task createTestTask() {
        User admin = userRepository.findByUsername("testadmin").orElseThrow();
        Task task = new Task();
        task.setTitle("Original Task");
        task.setDescription("Original description");
        task.setCategory("Cleaning");
        task.setPriority(Priority.LOW);
        task.setStatus(TaskStatus.PENDING);
        task.setEstimatedDuration(30);
        task.setDueDate(LocalDate.of(2026, 10, 10));
        task.setDepartment(Department.FRONT_DESK);
        task.setCreatedBy(admin);
        return taskRepository.saveAndFlush(task);
    }

    // ── GET /api/tasks ────────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "ADMIN")
    public void testGetTasksWithAdminRole() throws Exception {
        mockMvc.perform(get("/api/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    public void testGetTasksWithManagerRole() throws Exception {
        mockMvc.perform(get("/api/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @WithMockUser(roles = "STAFF")
    public void testGetTasksWithStaffRole() throws Exception {
        mockMvc.perform(get("/api/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    private Staff createTestStaff(String username, String email) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode("password"));
        user.setRole(Role.STAFF);
        user.setActive(true);
        user = userRepository.saveAndFlush(user);

        Staff staff = new Staff();
        staff.setUser(user);
        staff.setName("Test Staff " + username);
        staff.setEmail(email);
        staff.setDepartment(Department.HOUSEKEEPING);
        staff.setAvailability(Availability.AVAILABLE);
        staff.setActive(true);
        return staffRepository.saveAndFlush(staff);
    }

    @Test
    @WithMockUser(username = "taskstaff1", roles = "STAFF")
    @Transactional
    public void testStaffCanOnlyRetrieveAssignedTasks() throws Exception {
        Staff staff1 = createTestStaff("taskstaff1", "taskstaff1@hotel.com");
        Staff staff2 = createTestStaff("taskstaff2", "taskstaff2@hotel.com");

        Task task1 = createTestTask();
        task1.setTitle("Staff 1 Assigned Task");
        task1 = taskRepository.saveAndFlush(task1);

        Task task2 = createTestTask();
        task2.setTitle("Staff 2 Assigned Task");
        task2 = taskRepository.saveAndFlush(task2);

        Assignment a1 = new Assignment();
        a1.setTask(task1);
        a1.setStaff(staff1);
        assignmentRepository.saveAndFlush(a1);

        Assignment a2 = new Assignment();
        a2.setTask(task2);
        a2.setStaff(staff2);
        assignmentRepository.saveAndFlush(a2);

        mockMvc.perform(get("/api/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[?(@.id == '" + task1.getId() + "')]").exists())
                .andExpect(jsonPath("$[?(@.id == '" + task2.getId() + "')]").doesNotExist());
    }

    @Test
    @WithMockUser(username = "taskstaff2", roles = "STAFF")
    @Transactional
    public void testStaffCannotRetrieveOtherStaffTasks() throws Exception {
        Staff staff1 = createTestStaff("taskstaff1_other", "taskstaff1_other@hotel.com");
        Staff staff2 = createTestStaff("taskstaff2", "taskstaff2@hotel.com");

        Task task1 = createTestTask();
        task1.setTitle("Staff 1 Assigned Task");
        task1 = taskRepository.saveAndFlush(task1);

        Task task2 = createTestTask();
        task2.setTitle("Staff 2 Assigned Task");
        task2 = taskRepository.saveAndFlush(task2);

        Assignment a1 = new Assignment();
        a1.setTask(task1);
        a1.setStaff(staff1);
        assignmentRepository.saveAndFlush(a1);

        Assignment a2 = new Assignment();
        a2.setTask(task2);
        a2.setStaff(staff2);
        assignmentRepository.saveAndFlush(a2);

        mockMvc.perform(get("/api/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[?(@.id == '" + task2.getId() + "')]").exists())
                .andExpect(jsonPath("$[?(@.id == '" + task1.getId() + "')]").doesNotExist());
    }

    @Test
    @WithMockUser(username = "testadmin", roles = "ADMIN")
    @Transactional
    public void testAdminCanRetrieveAllTasksRegardlessOfAssignment() throws Exception {
        Staff staff1 = createTestStaff("taskstaff_adm1", "taskstaff_adm1@hotel.com");
        Staff staff2 = createTestStaff("taskstaff_adm2", "taskstaff_adm2@hotel.com");

        Task task1 = createTestTask();
        task1.setTitle("Admin Visible Task 1");
        task1 = taskRepository.saveAndFlush(task1);

        Task task2 = createTestTask();
        task2.setTitle("Admin Visible Task 2");
        task2 = taskRepository.saveAndFlush(task2);

        Assignment a1 = new Assignment();
        a1.setTask(task1);
        a1.setStaff(staff1);
        assignmentRepository.saveAndFlush(a1);

        Assignment a2 = new Assignment();
        a2.setTask(task2);
        a2.setStaff(staff2);
        assignmentRepository.saveAndFlush(a2);

        mockMvc.perform(get("/api/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[?(@.id == '" + task1.getId() + "')]").exists())
                .andExpect(jsonPath("$[?(@.id == '" + task2.getId() + "')]").exists());
    }

    @Test
    @WithMockUser(username = "testmanager", roles = "MANAGER")
    @Transactional
    public void testManagerCanRetrieveAllTasksRegardlessOfAssignment() throws Exception {
        Staff staff1 = createTestStaff("taskstaff_mg1", "taskstaff_mg1@hotel.com");
        Staff staff2 = createTestStaff("taskstaff_mg2", "taskstaff_mg2@hotel.com");

        Task task1 = createTestTask();
        task1.setTitle("Manager Visible Task 1");
        task1 = taskRepository.saveAndFlush(task1);

        Task task2 = createTestTask();
        task2.setTitle("Manager Visible Task 2");
        task2 = taskRepository.saveAndFlush(task2);

        Assignment a1 = new Assignment();
        a1.setTask(task1);
        a1.setStaff(staff1);
        assignmentRepository.saveAndFlush(a1);

        Assignment a2 = new Assignment();
        a2.setTask(task2);
        a2.setStaff(staff2);
        assignmentRepository.saveAndFlush(a2);

        mockMvc.perform(get("/api/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[?(@.id == '" + task1.getId() + "')]").exists())
                .andExpect(jsonPath("$[?(@.id == '" + task2.getId() + "')]").exists());
    }

    @Test
    @WithMockUser(username = "unassigned_task_staff", roles = "STAFF")
    @Transactional
    public void testStaffWithNoAssignmentsReceivesEmptyList() throws Exception {
        createTestStaff("unassigned_task_staff", "unassigned_task_staff@hotel.com");
        Task task = createTestTask();

        mockMvc.perform(get("/api/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[?(@.id == '" + task.getId() + "')]").doesNotExist());
    }

    @Test
    @WithMockUser(roles = "STAFF")
    public void testGetTasksUnauthenticated() throws Exception {
        // Clear auth to test unauthenticated
    }

    @Test
    public void testGetTasksUnauthenticatedFails() throws Exception {
        mockMvc.perform(get("/api/tasks"))
                .andExpect(status().isUnauthorized());
    }

    // ── POST /api/tasks ───────────────────────────────────────────────────────

    private static final String VALID_TASK_JSON = """
            {
                "title": "Test Task",
                "description": "A test task",
                "category": "Cleaning",
                "priority": "HIGH",
                "estimatedDuration": 60,
                "dueDate": "2026-12-31",
                "department": "HOUSEKEEPING"
            }
            """;

    @Test
    @WithMockUser(username = "testadmin", roles = "ADMIN")
    @Transactional
    public void testCreateTaskWithAdminRole() throws Exception {
        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_TASK_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Test Task"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.priority").value("HIGH"))
                .andExpect(jsonPath("$.department").value("HOUSEKEEPING"));
    }

    @Test
    @WithMockUser(username = "testmanager", roles = "MANAGER")
    @Transactional
    public void testCreateTaskWithManagerRole() throws Exception {
        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_TASK_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Test Task"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    @WithMockUser(roles = "STAFF")
    public void testCreateTaskWithStaffRoleForbidden() throws Exception {
        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_TASK_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    public void testCreateTaskUnauthenticated() throws Exception {
        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_TASK_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "testadmin", roles = "ADMIN")
    public void testCreateTaskWithBlankTitleReturns400() throws Exception {
        String invalidJson = """
                {
                    "title": "",
                    "priority": "HIGH",
                    "estimatedDuration": 60
                }
                """;
        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "testadmin", roles = "ADMIN")
    public void testCreateTaskWithMissingPriorityReturns400() throws Exception {
        String invalidJson = """
                {
                    "title": "Some Task",
                    "estimatedDuration": 60
                }
                """;
        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "testadmin", roles = "ADMIN")
    public void testCreateTaskWithNegativeDurationReturns400() throws Exception {
        String invalidJson = """
                {
                    "title": "Some Task",
                    "priority": "LOW",
                    "estimatedDuration": -5
                }
                """;
        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "testadmin", roles = "ADMIN")
    @Transactional
    public void testCreatedTaskHasAutoGeneratedUUID() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_TASK_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        assert responseBody.contains("\"id\"");
    }

    @Test
    @WithMockUser(username = "testadmin", roles = "ADMIN")
    @Transactional
    public void testCreatedTaskHasPendingStatus() throws Exception {
        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_TASK_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    // ── PUT /api/tasks/{id} ───────────────────────────────────────────────────

    /**
     * VALID_UPDATE_TASK_JSON moves a task to IN_PROGRESS in HOUSEKEEPING. That is only a valid
     * change for a task that has an assignee, so update tests start from an ASSIGNED
     * HOUSEKEEPING task assigned to a HOUSEKEEPING staff member.
     */
    private Task createAssignedTaskForUpdate(String username) {
        return createAssignedTask(username, Department.HOUSEKEEPING, Department.HOUSEKEEPING, TaskStatus.ASSIGNED);
    }

    private static final String VALID_UPDATE_TASK_JSON = """
            {
                "title": "Updated Task Title",
                "description": "Updated Task Description",
                "category": "Maintenance",
                "priority": "HIGH",
                "estimatedDuration": 90,
                "dueDate": "2026-11-15",
                "department": "HOUSEKEEPING",
                "status": "IN_PROGRESS"
            }
            """;

    @Test
    @WithMockUser(roles = "ADMIN")
    @Transactional
    public void testAdminCanUpdateTask() throws Exception {
        Task task = createAssignedTaskForUpdate("upd_admin");

        mockMvc.perform(put("/api/tasks/" + task.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_UPDATE_TASK_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Updated Task Title"))
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    @Transactional
    public void testManagerCanUpdateTask() throws Exception {
        Task task = createAssignedTaskForUpdate("upd_manager");

        mockMvc.perform(put("/api/tasks/" + task.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_UPDATE_TASK_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Updated Task Title"))
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
    }

    @Test
    @WithMockUser(roles = "STAFF")
    @Transactional
    public void testStaffReceives403OnUpdateTask() throws Exception {
        Task task = createTestTask();

        mockMvc.perform(put("/api/tasks/" + task.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_UPDATE_TASK_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    @Transactional
    public void testUnauthenticatedReceives401OnUpdateTask() throws Exception {
        Task task = createTestTask();

        mockMvc.perform(put("/api/tasks/" + task.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_UPDATE_TASK_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @Transactional
    public void testInvalidRequestWithBlankTitleReturns400() throws Exception {
        Task task = createTestTask();
        String invalidJson = """
                {
                    "title": "",
                    "priority": "HIGH",
                    "status": "IN_PROGRESS"
                }
                """;
        mockMvc.perform(put("/api/tasks/" + task.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @Transactional
    public void testInvalidRequestWithMissingPriorityReturns400() throws Exception {
        Task task = createTestTask();
        String invalidJson = """
                {
                    "title": "Some Title",
                    "status": "IN_PROGRESS"
                }
                """;
        mockMvc.perform(put("/api/tasks/" + task.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @Transactional
    public void testInvalidRequestWithNegativeDurationReturns400() throws Exception {
        Task task = createTestTask();
        String invalidJson = """
                {
                    "title": "Some Title",
                    "priority": "HIGH",
                    "estimatedDuration": -10,
                    "status": "IN_PROGRESS"
                }
                """;
        mockMvc.perform(put("/api/tasks/" + task.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @Transactional
    public void testInvalidRequestWithMissingStatusReturns400() throws Exception {
        Task task = createTestTask();
        String invalidJson = """
                {
                    "title": "Some Title",
                    "priority": "HIGH"
                }
                """;
        mockMvc.perform(put("/api/tasks/" + task.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    public void testNonExistentTaskReturns404() throws Exception {
        UUID nonExistentId = UUID.randomUUID();
        mockMvc.perform(put("/api/tasks/" + nonExistentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_UPDATE_TASK_JSON))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @Transactional
    public void testUuidRemainsUnchanged() throws Exception {
        Task task = createAssignedTaskForUpdate("upd_uuid");
        UUID originalId = task.getId();

        mockMvc.perform(put("/api/tasks/" + originalId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_UPDATE_TASK_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(originalId.toString()));

        Task reloaded = taskRepository.findById(originalId).orElseThrow();
        assertEquals(originalId, reloaded.getId());
    }

    @Test
    @WithMockUser(username = "testmanager", roles = "MANAGER")
    @Transactional
    public void testCreatedByRemainsUnchanged() throws Exception {
        Task task = createAssignedTaskForUpdate("upd_created_by");
        UUID originalCreatorId = task.getCreatedBy().getId();
        String originalCreatorUsername = task.getCreatedBy().getUsername();

        mockMvc.perform(put("/api/tasks/" + task.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_UPDATE_TASK_JSON))
                .andExpect(status().isOk());

        Task reloaded = taskRepository.findById(task.getId()).orElseThrow();
        assertNotNull(reloaded.getCreatedBy());
        assertEquals(originalCreatorId, reloaded.getCreatedBy().getId());
        assertEquals(originalCreatorUsername, reloaded.getCreatedBy().getUsername());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @Transactional
    public void testUpdatedValuesArePersisted() throws Exception {
        Task task = createAssignedTaskForUpdate("upd_persisted");
        UUID taskId = task.getId();
        LocalDateTime originalCreatedAt = task.getCreatedAt();

        mockMvc.perform(put("/api/tasks/" + taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_UPDATE_TASK_JSON))
                .andExpect(status().isOk());

        Task persisted = taskRepository.findById(taskId).orElseThrow();
        assertEquals("Updated Task Title", persisted.getTitle());
        assertEquals("Updated Task Description", persisted.getDescription());
        assertEquals("Maintenance", persisted.getCategory());
        assertEquals(Priority.HIGH, persisted.getPriority());
        assertEquals(TaskStatus.IN_PROGRESS, persisted.getStatus());
        assertEquals(90, persisted.getEstimatedDuration());
        assertEquals(LocalDate.of(2026, 11, 15), persisted.getDueDate());
        assertEquals(Department.HOUSEKEEPING, persisted.getDepartment());
        // Verify createdAt is preserved
        assertEquals(originalCreatedAt, persisted.getCreatedAt());
        // Verify updatedAt is set
        assertNotNull(persisted.getUpdatedAt());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @Transactional
    public void testUpdatedTaskDtoIsReturnedCorrectly() throws Exception {
        Task task = createAssignedTaskForUpdate("upd_dto");
        UUID taskId = task.getId();

        mockMvc.perform(put("/api/tasks/" + taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_UPDATE_TASK_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(taskId.toString()))
                .andExpect(jsonPath("$.title").value("Updated Task Title"))
                .andExpect(jsonPath("$.description").value("Updated Task Description"))
                .andExpect(jsonPath("$.category").value("Maintenance"))
                .andExpect(jsonPath("$.priority").value("HIGH"))
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.estimatedDuration").value(90))
                .andExpect(jsonPath("$.dueDate").value("2026-11-15"))
                .andExpect(jsonPath("$.department").value("HOUSEKEEPING"))
                .andExpect(jsonPath("$.createdAt", notNullValue()))
                .andExpect(jsonPath("$.updatedAt", notNullValue()));
    }

    // ── DELETE /api/tasks/{id} ────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "ADMIN")
    @Transactional
    public void testAdminCanDeleteTaskReturns204() throws Exception {
        Task task = createTestTask();

        mockMvc.perform(delete("/api/tasks/" + task.getId()))
                .andExpect(status().isNoContent());
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    @Transactional
    public void testManagerCanDeleteTaskReturns204() throws Exception {
        Task task = createTestTask();

        mockMvc.perform(delete("/api/tasks/" + task.getId()))
                .andExpect(status().isNoContent());
    }

    @Test
    @WithMockUser(roles = "STAFF")
    @Transactional
    public void testStaffReceives403OnDeleteTask() throws Exception {
        Task task = createTestTask();

        mockMvc.perform(delete("/api/tasks/" + task.getId()))
                .andExpect(status().isForbidden());
    }

    @Test
    @Transactional
    public void testUnauthenticatedReceives401OnDeleteTask() throws Exception {
        Task task = createTestTask();

        mockMvc.perform(delete("/api/tasks/" + task.getId()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    public void testNonExistentTaskOnDeleteReturns404() throws Exception {
        UUID nonExistentId = UUID.randomUUID();

        mockMvc.perform(delete("/api/tasks/" + nonExistentId))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @Transactional
    public void testDeletedTaskNoLongerExistsInPostgres() throws Exception {
        Task task = createTestTask();
        UUID taskId = task.getId();

        mockMvc.perform(delete("/api/tasks/" + taskId))
                .andExpect(status().isNoContent());

        assertFalse(taskRepository.existsById(taskId));
        assertTrue(taskRepository.findById(taskId).isEmpty());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @Transactional
    public void testSuccessfulDeleteHasNoResponseBody() throws Exception {
        Task task = createTestTask();

        MvcResult result = mockMvc.perform(delete("/api/tasks/" + task.getId()))
                .andExpect(status().isNoContent())
                .andReturn();

        String content = result.getResponse().getContentAsString();
        assertTrue(content.isEmpty(), "Response body should be empty on 204 No Content");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @Transactional
    public void testAdminCanDeleteAssignedTaskSafelyCascadesAssignment() throws Exception {
        Staff staff = createTestStaff("deletestaff1", "deletestaff1@hotel.com");
        Task task = createTestTask();
        task.setStatus(TaskStatus.ASSIGNED);
        task = taskRepository.saveAndFlush(task);

        Assignment assignment = new Assignment();
        assignment.setTask(task);
        assignment.setStaff(staff);
        assignmentRepository.saveAndFlush(assignment);

        UUID taskId = task.getId();
        assertTrue(assignmentRepository.existsByTaskId(taskId));

        mockMvc.perform(delete("/api/tasks/" + taskId))
                .andExpect(status().isNoContent());

        assertFalse(taskRepository.existsById(taskId));
        assertFalse(assignmentRepository.existsByTaskId(taskId));
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    @Transactional
    public void testManagerCanDeleteAssignedTaskSafelyCascadesAssignment() throws Exception {
        Staff staff = createTestStaff("deletestaff2", "deletestaff2@hotel.com");
        Task task = createTestTask();
        task.setStatus(TaskStatus.ASSIGNED);
        task = taskRepository.saveAndFlush(task);

        Assignment assignment = new Assignment();
        assignment.setTask(task);
        assignment.setStaff(staff);
        assignmentRepository.saveAndFlush(assignment);

        UUID taskId = task.getId();
        assertTrue(assignmentRepository.existsByTaskId(taskId));

        mockMvc.perform(delete("/api/tasks/" + taskId))
                .andExpect(status().isNoContent());

        assertFalse(taskRepository.existsById(taskId));
        assertFalse(assignmentRepository.existsByTaskId(taskId));
    }

    // ── Deadline Time Tests ──────────────────────────────────────────────────

    @Test
    @WithMockUser(username = "testadmin", roles = "ADMIN")
    @Transactional
    public void testCreateTaskWithDateAndDeadlineTime() throws Exception {
        String json = """
                {
                    "title": "Task with Date and Time",
                    "priority": "HIGH",
                    "estimatedDuration": 45,
                    "dueDate": "2026-10-13",
                    "deadlineTime": "17:00:00",
                    "department": "FRONT_DESK"
                }
                """;
        MvcResult result = mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.dueDate").value("2026-10-13"))
                .andExpect(jsonPath("$.deadlineTime").value("17:00:00"))
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        org.json.JSONObject obj = new org.json.JSONObject(responseBody);
        UUID taskId = UUID.fromString(obj.getString("id"));

        Task saved = taskRepository.findById(taskId).orElseThrow();
        assertEquals(LocalDate.of(2026, 10, 13), saved.getDueDate());
        assertEquals(LocalTime.of(17, 0, 0), saved.getDeadlineTime());

        mockMvc.perform(get("/api/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + taskId + "')].deadlineTime").value("17:00:00"));
    }

    @Test
    @WithMockUser(username = "testadmin", roles = "ADMIN")
    @Transactional
    public void testCreateTaskWithDateOnlyOmittedDeadlineTime() throws Exception {
        String json = """
                {
                    "title": "Task Date Only",
                    "priority": "MEDIUM",
                    "estimatedDuration": 30,
                    "dueDate": "2026-10-14",
                    "department": "HOUSEKEEPING"
                }
                """;
        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.dueDate").value("2026-10-14"))
                .andExpect(jsonPath("$.deadlineTime").value(nullValue()));
    }

    @Test
    @WithMockUser(username = "testadmin", roles = "ADMIN")
    @Transactional
    public void testCreateTaskWithNeitherDateNorTime() throws Exception {
        String json = """
                {
                    "title": "Task No Date No Time",
                    "priority": "LOW",
                    "estimatedDuration": 15,
                    "department": "MAINTENANCE"
                }
                """;
        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.dueDate").value(nullValue()))
                .andExpect(jsonPath("$.deadlineTime").value(nullValue()));
    }

    @Test
    @WithMockUser(username = "testadmin", roles = "ADMIN")
    @Transactional
    public void testUpdateTaskDeadlineTime() throws Exception {
        Task task = createTestTask();
        task.setDueDate(LocalDate.of(2026, 10, 14));
        task.setDeadlineTime(LocalTime.of(9, 0));
        task = taskRepository.saveAndFlush(task);

        String updateJson = """
                {
                    "title": "Updated Task Time",
                    "priority": "HIGH",
                    "status": "PENDING",
                    "estimatedDuration": 60,
                    "dueDate": "2026-10-14",
                    "deadlineTime": "14:30:00",
                    "department": "FRONT_DESK"
                }
                """;
        mockMvc.perform(put("/api/tasks/" + task.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dueDate").value("2026-10-14"))
                .andExpect(jsonPath("$.deadlineTime").value("14:30:00"));
    }

    @Test
    @WithMockUser(username = "testadmin", roles = "ADMIN")
    @Transactional
    public void testUpdateTaskToClearDeadLineTime() throws Exception {
        Task task = createTestTask();
        task.setDueDate(LocalDate.of(2026, 10, 14));
        task.setDeadlineTime(LocalTime.of(17, 0));
        task = taskRepository.saveAndFlush(task);

        String updateJson = """
                {
                    "title": "Cleared Deadline Time",
                    "priority": "MEDIUM",
                    "status": "PENDING",
                    "estimatedDuration": 30,
                    "dueDate": "2026-10-14",
                    "deadlineTime": null,
                    "department": "FRONT_DESK"
                }
                """;
        mockMvc.perform(put("/api/tasks/" + task.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dueDate").value("2026-10-14"))
                .andExpect(jsonPath("$.deadlineTime").value(nullValue()));
    }

    @Test
    @WithMockUser(username = "testadmin", roles = "ADMIN")
    @Transactional
    public void testCreateTaskWithInvalidDeadlineTimeReturns400() throws Exception {
        String json = """
                {
                    "title": "Invalid Time Task",
                    "priority": "HIGH",
                    "estimatedDuration": 30,
                    "deadlineTime": "25:99:99",
                    "department": "FRONT_DESK"
                }
                """;
        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest());
    }

    // ── P2: a task's department cannot diverge from its assignee's department ──

    private Task createAssignedTask(String username, Department staffDepartment, Department taskDepartment, TaskStatus status) {
        Staff staff = createTestStaff(username, username + "@hotel.com");
        staff.setDepartment(staffDepartment);
        staff = staffRepository.saveAndFlush(staff);

        Task task = createTestTask();
        task.setDepartment(taskDepartment);
        task.setStatus(status);
        task = taskRepository.saveAndFlush(task);

        Assignment assignment = new Assignment();
        assignment.setTask(task);
        assignment.setStaff(staff);
        assignmentRepository.saveAndFlush(assignment);
        return task;
    }

    private String taskUpdateJson(String title, String department, TaskStatus status) {
        return String.format("""
                {
                    "title": "%s",
                    "description": "Original description",
                    "category": "Cleaning",
                    "priority": "LOW",
                    "estimatedDuration": 45,
                    "dueDate": "2026-10-10",
                    "department": %s,
                    "status": "%s"
                }
                """, title, department == null ? "null" : "\"" + department + "\"", status);
    }

    /** Writes pending changes, then re-reads the task from the database, bypassing the persistence context. */
    private Task reloadTask(UUID taskId) {
        entityManager.flush();
        entityManager.clear();
        return taskRepository.findById(taskId).orElseThrow();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(TaskStatus.class)
    @WithMockUser(roles = "MANAGER")
    @Transactional
    public void testChangingAssignedTaskDepartmentAwayFromAssigneeReturns409AndLeavesTaskUnchanged(TaskStatus status) throws Exception {
        String username = "p2_task_" + status.name().toLowerCase();
        Task task = createAssignedTask(username, Department.HOUSEKEEPING, Department.HOUSEKEEPING, status);

        mockMvc.perform(put("/api/tasks/" + task.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(taskUpdateJson("Changed Title", "FRONT_DESK", status)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "Cannot change this task's department to Front Desk because it is assigned to Test Staff "
                                + username + " (Housekeeping). Reassign the task to a Front Desk staff member first, "
                                + "or keep its current department."));

        Task reloaded = reloadTask(task.getId());
        assertEquals(Department.HOUSEKEEPING, reloaded.getDepartment(), "Rejected update must not change the department");
        assertEquals("Original Task", reloaded.getTitle(), "Rejected update must not change any other field");
        assertEquals(30, reloaded.getEstimatedDuration());
        assertEquals(status, reloaded.getStatus());
        assertEquals(username + "@hotel.com", assignmentRepository.findAllByTaskId(task.getId()).get(0).getStaff().getEmail(),
                "Rejected update must leave the assignment untouched");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @Transactional
    public void testChangingUnassignedTaskDepartmentSucceeds() throws Exception {
        Task task = createTestTask(); // FRONT_DESK, no assignment

        mockMvc.perform(put("/api/tasks/" + task.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(taskUpdateJson("Original Task", "SECURITY", TaskStatus.PENDING)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.department").value("SECURITY"));

        assertEquals(Department.SECURITY, reloadTask(task.getId()).getDepartment());
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    @Transactional
    public void testEditingAssignedTaskWithoutChangingDepartmentSucceeds() throws Exception {
        Task task = createAssignedTask("p2_task_same_dept", Department.HOUSEKEEPING, Department.HOUSEKEEPING, TaskStatus.ASSIGNED);

        mockMvc.perform(put("/api/tasks/" + task.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(taskUpdateJson("Renamed Assigned Task", "HOUSEKEEPING", TaskStatus.ASSIGNED)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Renamed Assigned Task"));

        Task reloaded = reloadTask(task.getId());
        assertEquals("Renamed Assigned Task", reloaded.getTitle());
        assertEquals(45, reloaded.getEstimatedDuration());
        assertEquals(Department.HOUSEKEEPING, reloaded.getDepartment());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @Transactional
    public void testChangingMismatchedTaskDepartmentToAssigneeDepartmentSucceeds() throws Exception {
        // Mirrors an existing record whose department diverged from its assignee before this rule existed
        Task task = createAssignedTask("p2_task_repair", Department.MAINTENANCE, Department.HOUSEKEEPING, TaskStatus.ASSIGNED);

        mockMvc.perform(put("/api/tasks/" + task.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(taskUpdateJson("Original Task", "MAINTENANCE", TaskStatus.ASSIGNED)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.department").value("MAINTENANCE"));

        assertEquals(Department.MAINTENANCE, reloadTask(task.getId()).getDepartment());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @Transactional
    public void testEditingMismatchedTaskWithoutChangingDepartmentSucceeds() throws Exception {
        Task task = createAssignedTask("p2_task_legacy_edit", Department.MAINTENANCE, Department.HOUSEKEEPING, TaskStatus.ASSIGNED);

        mockMvc.perform(put("/api/tasks/" + task.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(taskUpdateJson("Legacy Task Renamed", "HOUSEKEEPING", TaskStatus.ASSIGNED)))
                .andExpect(status().isOk());

        Task reloaded = reloadTask(task.getId());
        assertEquals("Legacy Task Renamed", reloaded.getTitle());
        assertEquals(Department.HOUSEKEEPING, reloaded.getDepartment());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @Transactional
    public void testChangingMismatchedTaskToAThirdDepartmentReturns409() throws Exception {
        Task task = createAssignedTask("p2_task_legacy_third", Department.MAINTENANCE, Department.HOUSEKEEPING, TaskStatus.ASSIGNED);

        mockMvc.perform(put("/api/tasks/" + task.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(taskUpdateJson("Original Task", "SECURITY", TaskStatus.ASSIGNED)))
                .andExpect(status().isConflict());

        assertEquals(Department.HOUSEKEEPING, reloadTask(task.getId()).getDepartment());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @Transactional
    public void testClearingAssignedTaskDepartmentKeepsExistingBehaviour() throws Exception {
        // A null department means "no department restriction", matching the assignment rules
        Task task = createAssignedTask("p2_task_null_dept", Department.HOUSEKEEPING, Department.HOUSEKEEPING, TaskStatus.ASSIGNED);

        mockMvc.perform(put("/api/tasks/" + task.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(taskUpdateJson("Original Task", null, TaskStatus.ASSIGNED)))
                .andExpect(status().isOk());

        assertNull(reloadTask(task.getId()).getDepartment());
    }

    // ── P3: a task's status must stay consistent with its assignment ──

    private static final String ASSIGNED_TO_PENDING_MESSAGE =
            "This task is assigned to a staff member, so it cannot be set back to Pending. "
                    + "Keep it Assigned or In Progress, or mark it Completed or Cancelled.";

    private Task createUnassignedTaskWithStatus(TaskStatus status) {
        Task task = createTestTask(); // FRONT_DESK, "Original Task", 30 min
        task.setStatus(status);
        return taskRepository.saveAndFlush(task);
    }

    private int assignmentCount(UUID taskId) {
        return assignmentRepository.findAllByTaskId(taskId).size();
    }

    private org.springframework.test.web.servlet.ResultActions putTask(UUID taskId, String json) throws Exception {
        return mockMvc.perform(put("/api/tasks/" + taskId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    /** The request sends "Changed Title" and 45 min, so an unchanged title and duration prove nothing was applied. */
    private void assertTaskUnchanged(UUID taskId, TaskStatus expectedStatus, int expectedAssignments) {
        Task reloaded = reloadTask(taskId);
        assertEquals(expectedStatus, reloaded.getStatus(), "Rejected update must not change the status");
        assertEquals("Original Task", reloaded.getTitle(), "Rejected update must not change any other field");
        assertEquals(30, reloaded.getEstimatedDuration(), "Rejected update must not change any other field");
        assertEquals(expectedAssignments, assignmentCount(taskId), "Rejected update must not create or delete an assignment");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = TaskStatus.class, names = { "ASSIGNED", "IN_PROGRESS" })
    @WithMockUser(roles = "MANAGER")
    @Transactional
    public void testAssignedTaskCannotBeSetBackToPending(TaskStatus currentStatus) throws Exception {
        String username = "p3_to_pending_" + currentStatus.name().toLowerCase();
        Task task = createAssignedTask(username, Department.HOUSEKEEPING, Department.HOUSEKEEPING, currentStatus);

        putTask(task.getId(), taskUpdateJson("Changed Title", "HOUSEKEEPING", TaskStatus.PENDING))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(ASSIGNED_TO_PENDING_MESSAGE));

        assertTaskUnchanged(task.getId(), currentStatus, 1);
        assertEquals(username + "@hotel.com", assignmentRepository.findAllByTaskId(task.getId()).get(0).getStaff().getEmail(),
                "Rejected update must not reassign the task");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = TaskStatus.class, names = { "ASSIGNED", "IN_PROGRESS", "COMPLETED" })
    @WithMockUser(roles = "ADMIN")
    @Transactional
    public void testUnassignedTaskCannotMoveToAnAssignedOnlyStatus(TaskStatus requestedStatus) throws Exception {
        Task task = createUnassignedTaskWithStatus(TaskStatus.PENDING);

        putTask(task.getId(), taskUpdateJson("Changed Title", "FRONT_DESK", requestedStatus))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "This task has no assigned staff member, so it cannot be set to " + requestedStatus.getDisplayName()
                                + ". Assign it from the Assignments page first."));

        assertTaskUnchanged(task.getId(), TaskStatus.PENDING, 0);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = TaskStatus.class, names = { "PENDING", "ASSIGNED", "IN_PROGRESS", "CANCELLED" })
    @WithMockUser(roles = "MANAGER")
    @Transactional
    public void testCompletedTaskCannotBeReopenedOrMoved(TaskStatus requestedStatus) throws Exception {
        Task task = createAssignedTask("p3_completed_" + requestedStatus.name().toLowerCase(),
                Department.HOUSEKEEPING, Department.HOUSEKEEPING, TaskStatus.COMPLETED);

        putTask(task.getId(), taskUpdateJson("Changed Title", "HOUSEKEEPING", requestedStatus))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "This task is Completed and cannot be changed to " + requestedStatus.getDisplayName()
                                + ". Finished tasks cannot be reopened; create a new task if more work is needed."));

        assertTaskUnchanged(task.getId(), TaskStatus.COMPLETED, 1);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = TaskStatus.class, names = { "PENDING", "ASSIGNED", "IN_PROGRESS", "COMPLETED" })
    @WithMockUser(roles = "ADMIN")
    @Transactional
    public void testCancelledAssignedTaskCannotBeReopenedOrMoved(TaskStatus requestedStatus) throws Exception {
        Task task = createAssignedTask("p3_cancelled_" + requestedStatus.name().toLowerCase(),
                Department.HOUSEKEEPING, Department.HOUSEKEEPING, TaskStatus.CANCELLED);

        putTask(task.getId(), taskUpdateJson("Changed Title", "HOUSEKEEPING", requestedStatus))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith("This task is Cancelled and cannot be changed to")));

        assertTaskUnchanged(task.getId(), TaskStatus.CANCELLED, 1);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @Transactional
    public void testCancelledUnassignedTaskCannotBeReopenedToPending() throws Exception {
        Task task = createUnassignedTaskWithStatus(TaskStatus.CANCELLED);

        putTask(task.getId(), taskUpdateJson("Changed Title", "FRONT_DESK", TaskStatus.PENDING))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "This task is Cancelled and cannot be changed to Pending. "
                                + "Finished tasks cannot be reopened; create a new task if more work is needed."));

        assertTaskUnchanged(task.getId(), TaskStatus.CANCELLED, 0);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "ASSIGNED, IN_PROGRESS",
            "ASSIGNED, COMPLETED",
            "ASSIGNED, CANCELLED",
            "IN_PROGRESS, ASSIGNED",
            "IN_PROGRESS, COMPLETED",
            "IN_PROGRESS, CANCELLED"
    })
    @WithMockUser(roles = "MANAGER")
    @Transactional
    public void testValidStatusChangesOnAssignedTaskSucceed(TaskStatus from, TaskStatus to) throws Exception {
        Task task = createAssignedTask("p3_valid_" + from.name().toLowerCase() + "_" + to.name().toLowerCase(),
                Department.HOUSEKEEPING, Department.HOUSEKEEPING, from);

        putTask(task.getId(), taskUpdateJson("Changed Title", "HOUSEKEEPING", to))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(to.name()));

        Task reloaded = reloadTask(task.getId());
        assertEquals(to, reloaded.getStatus());
        assertEquals("Changed Title", reloaded.getTitle());
        assertEquals(1, assignmentCount(task.getId()), "A status change must not delete the assignment");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @Transactional
    public void testUnassignedPendingTaskCanBeCancelled() throws Exception {
        Task task = createUnassignedTaskWithStatus(TaskStatus.PENDING);

        putTask(task.getId(), taskUpdateJson("Changed Title", "FRONT_DESK", TaskStatus.CANCELLED))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertEquals(TaskStatus.CANCELLED, reloadTask(task.getId()).getStatus());
        assertEquals(0, assignmentCount(task.getId()), "A status change must not create an assignment");
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    @Transactional
    public void testCompletedTaskFieldsCanStillBeEditedWhenStatusIsUnchanged() throws Exception {
        Task task = createAssignedTask("p3_completed_edit", Department.HOUSEKEEPING, Department.HOUSEKEEPING, TaskStatus.COMPLETED);

        putTask(task.getId(), taskUpdateJson("Completed Task Renamed", "HOUSEKEEPING", TaskStatus.COMPLETED))
                .andExpect(status().isOk());

        Task reloaded = reloadTask(task.getId());
        assertEquals(TaskStatus.COMPLETED, reloaded.getStatus());
        assertEquals("Completed Task Renamed", reloaded.getTitle());
        assertEquals(45, reloaded.getEstimatedDuration());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @Transactional
    public void testCancelledTaskFieldsCanStillBeEditedWhenStatusIsUnchanged() throws Exception {
        Task task = createUnassignedTaskWithStatus(TaskStatus.CANCELLED);

        putTask(task.getId(), taskUpdateJson("Cancelled Task Renamed", "FRONT_DESK", TaskStatus.CANCELLED))
                .andExpect(status().isOk());

        Task reloaded = reloadTask(task.getId());
        assertEquals(TaskStatus.CANCELLED, reloaded.getStatus());
        assertEquals("Cancelled Task Renamed", reloaded.getTitle());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @Transactional
    public void testLegacyPendingTaskWithAssignmentCanBeEditedAndRepairedToAssigned() throws Exception {
        // Mirrors an existing record left PENDING while it still has an assignment
        Task task = createAssignedTask("p3_legacy_pending", Department.HOUSEKEEPING, Department.HOUSEKEEPING, TaskStatus.PENDING);

        putTask(task.getId(), taskUpdateJson("Legacy Renamed", "HOUSEKEEPING", TaskStatus.PENDING))
                .andExpect(status().isOk());
        assertEquals("Legacy Renamed", reloadTask(task.getId()).getTitle());

        putTask(task.getId(), taskUpdateJson("Legacy Renamed", "HOUSEKEEPING", TaskStatus.ASSIGNED))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ASSIGNED"));
        assertEquals(1, assignmentCount(task.getId()));
    }

    @Test
    @WithMockUser(username = "testmanager", roles = "MANAGER")
    @Transactional
    public void testFullLifecycleThroughAssignmentEndpointKeepsStatusConsistent() throws Exception {
        Staff staff = createTestStaff("p3_lifecycle", "p3_lifecycle@hotel.com"); // HOUSEKEEPING, active, AVAILABLE
        Task task = createUnassignedTaskWithStatus(TaskStatus.PENDING);
        task.setDepartment(Department.HOUSEKEEPING);
        task = taskRepository.saveAndFlush(task);

        // Cannot skip the assignment step
        putTask(task.getId(), taskUpdateJson("Original Task", "HOUSEKEEPING", TaskStatus.IN_PROGRESS))
                .andExpect(status().isConflict());

        // The existing assignment flow sets the task to ASSIGNED
        mockMvc.perform(post("/api/assignments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("{\"taskId\":\"%s\",\"staffId\":\"%s\"}", task.getId(), staff.getId())))
                .andExpect(status().isCreated());
        assertEquals(TaskStatus.ASSIGNED, reloadTask(task.getId()).getStatus());

        putTask(task.getId(), taskUpdateJson("Original Task", "HOUSEKEEPING", TaskStatus.IN_PROGRESS))
                .andExpect(status().isOk());
        putTask(task.getId(), taskUpdateJson("Original Task", "HOUSEKEEPING", TaskStatus.COMPLETED))
                .andExpect(status().isOk());

        // Finished tasks stay finished
        putTask(task.getId(), taskUpdateJson("Original Task", "HOUSEKEEPING", TaskStatus.ASSIGNED))
                .andExpect(status().isConflict());
        assertEquals(TaskStatus.COMPLETED, reloadTask(task.getId()).getStatus());
        assertEquals(1, assignmentCount(task.getId()));
    }
}
