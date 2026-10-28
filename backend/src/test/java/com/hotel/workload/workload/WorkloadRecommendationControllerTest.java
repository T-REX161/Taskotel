package com.hotel.workload.workload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.workload.assignment.Assignment;
import com.hotel.workload.assignment.AssignmentRepository;
import com.hotel.workload.security.Role;
import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
import com.hotel.workload.staff.Availability;
import com.hotel.workload.staff.Department;
import com.hotel.workload.staff.DepartmentRepository;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
public class WorkloadRecommendationControllerTest {

    private static final String URL = "/api/workload/recommendations";

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private AssignmentRepository assignmentRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private jakarta.persistence.EntityManager entityManager;

    private MockMvc mockMvc;

    @BeforeEach
    public void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    // ── authorization ────────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "ADMIN")
    public void adminCanReadRecommendations() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendations").isArray())
                .andExpect(jsonPath("$.unsupported").isArray())
                .andExpect(jsonPath("$.limitations").isNotEmpty());
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    public void managerCanReadRecommendations() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "STAFF")
    public void staffReceives403() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isForbidden());
    }

    @Test
    public void unauthenticatedReceives401() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "STAFF")
    public void staffStillPassesSecurityOnTheExistingWorkloadEndpoint() throws Exception {
        mockMvc.perform(get("/api/workload")).andExpect(status().isForbidden()); // reaches the service, which refuses a mock user with no staff record, as before
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    public void endpointIsReadOnly() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().is4xxClientError());
        mockMvc.perform(put(URL).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().is4xxClientError());
        mockMvc.perform(delete(URL)).andExpect(status().is4xxClientError());
    }

    // ── integration against real rows ────────────────────────────────────────

    private Department newDepartment(String code) {
        Department department = new Department(code, code);
        department.setActive(true);
        department.setCreatedAt(LocalDateTime.now());
        return departmentRepository.saveAndFlush(department);
    }

    private Staff newStaff(String key, Department department, Availability availability, boolean active) {
        return newStaff(key, department, availability, active, Role.STAFF, true);
    }

    private Staff newStaff(String key, Department department, Availability availability, boolean active, Role role, boolean emailVerified) {
        User user = new User();
        user.setUsername("rec_" + key);
        user.setEmail("rec_" + key + "@hotel.com");
        user.setPassword(passwordEncoder.encode("password"));
        user.setRole(role);
        user.setActive(true);
        user.setEmailVerified(emailVerified);
        user.setCredentialsSetupRequired(!emailVerified);
        user = userRepository.saveAndFlush(user);
        Staff staff = new Staff();
        staff.setUser(user);
        staff.setName("Rec " + key);
        staff.setEmail("rec_" + key + "@hotel.com");
        staff.setDepartment(department);
        staff.setAvailability(availability);
        staff.setActive(active);
        return staffRepository.saveAndFlush(staff);
    }

    private Task newTask(String title, Department department, Priority priority, int minutes, TaskStatus status, User creator) {
        Task task = new Task();
        task.setTitle(title);
        task.setPriority(priority);
        task.setStatus(status);
        task.setEstimatedDuration(minutes);
        task.setDepartment(department);
        task.setCreatedBy(creator);
        return taskRepository.saveAndFlush(task);
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    @Transactional
    public void recommendsFromRealRowsAndChangesNothing() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        Department receiver = newDepartment("RECV_" + tag);
        Department donor = newDepartment("DONR_" + tag);

        Staff receiverStaff = newStaff("r1_" + tag, receiver, Availability.AVAILABLE, true);
        User creator = receiverStaff.getUser();
        Task assigned = newTask("Assigned job", receiver, Priority.MEDIUM, 60, TaskStatus.ASSIGNED, creator);
        Assignment assignment = new Assignment();
        assignment.setTask(assigned);
        assignment.setStaff(receiverStaff);
        assignmentRepository.saveAndFlush(assignment);
        newTask("Urgent waiting", receiver, Priority.URGENT, 45, TaskStatus.PENDING, creator);
        newTask("Normal waiting", receiver, Priority.MEDIUM, 30, TaskStatus.PENDING, creator);
        newTask("Finished long ago", receiver, Priority.URGENT, 500, TaskStatus.COMPLETED, creator);

        Set<UUID> ineligible = new HashSet<>();
        ineligible.add(newStaff("inactive_" + tag, donor, Availability.AVAILABLE, false).getId());
        ineligible.add(newStaff("away_" + tag, donor, Availability.UNAVAILABLE, true).getId());
        // Free and available, but not suggestable: a manager, an admin, and staff who cannot sign in yet
        ineligible.add(newStaff("mgr_" + tag, donor, Availability.AVAILABLE, true, Role.MANAGER, true).getId());
        ineligible.add(newStaff("adm_" + tag, donor, Availability.AVAILABLE, true, Role.ADMIN, true).getId());
        ineligible.add(newStaff("pend_" + tag, donor, Availability.AVAILABLE, true, Role.STAFF, false).getId());
        for (int i = 0; i < 3; i++) newStaff("d" + i + "_" + tag, donor, Availability.AVAILABLE, true);

        java.util.Map<UUID, String> departmentsBefore = new java.util.HashMap<>();
        for (Staff member : staffRepository.findAll()) departmentsBefore.put(member.getId(), member.getDepartment().getCode());
        long staffBefore = staffRepository.count();
        long tasksBefore = taskRepository.count();
        long assignmentsBefore = assignmentRepository.count();

        String body = mockMvc.perform(get(URL)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode root = new ObjectMapper().readTree(body);

        JsonNode rec = null;
        for (JsonNode candidate : root.get("recommendations")) {
            if (candidate.get("departmentCode").asText().equals(receiver.getCode())) rec = candidate;
        }
        assertNotNull(rec, "the overloaded department is recommended: " + body);
        assertEquals("CRITICAL", rec.get("severity").asText());
        JsonNode pressure = rec.get("pressure");
        assertEquals(2, pressure.get("waitingTasks").asInt(), "the completed task is not counted");
        assertEquals(75, pressure.get("waitingMinutes").asInt());
        assertEquals(0, pressure.get("freeStaff").asInt());
        assertEquals(2, pressure.get("uncoveredTasks").asInt());
        assertTrue(rec.get("suggestions").size() >= 1 && rec.get("suggestions").size() <= 3);
        for (JsonNode suggestion : rec.get("suggestions")) {
            UUID id = UUID.fromString(suggestion.get("staffId").asText());
            assertFalse(ineligible.contains(id), "inactive, unavailable, manager, admin and not-yet-set-up staff are never suggested");
            Staff suggested = staffRepository.findById(id).orElseThrow();
            assertTrue(suggested.isActive());
            assertEquals(Availability.AVAILABLE, suggested.getAvailability());
            assertFalse(assignmentRepository.existsByStaffIdAndTaskStatusIn(id, java.util.List.of(TaskStatus.PENDING, TaskStatus.ASSIGNED, TaskStatus.IN_PROGRESS)));
            assertNotEquals(receiver.getCode(), suggestion.get("homeDepartmentCode").asText());
            assertEquals(suggested.getDepartment().getCode(), suggestion.get("homeDepartmentCode").asText(), "home department is reported as stored");
        }

        // Read-only: nothing was created, moved or reassigned
        assertEquals(staffBefore, staffRepository.count());
        assertEquals(tasksBefore, taskRepository.count());
        assertEquals(assignmentsBefore, assignmentRepository.count());
        // Every staff member's permanent department is exactly what it was before the recommendation was produced
        entityManager.clear();
        for (Staff member : staffRepository.findAll()) {
            assertEquals(departmentsBefore.get(member.getId()), member.getDepartment().getCode(), "department unchanged for " + member.getName());
        }
        assertEquals(receiver.getCode(), staffRepository.findById(receiverStaff.getId()).orElseThrow().getDepartment().getCode());
        assertEquals(TaskStatus.PENDING, taskRepository.findAll().stream().filter(t -> t.getTitle().equals("Urgent waiting")).findFirst().orElseThrow().getStatus());
    }
}
