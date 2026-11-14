package com.hotel.workload.assignment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Each assignment row carries its holder's existing profile picture URL (staffProfileImageUrl, the same value the Staff and Users lists
 * show), so the Assignments table can show the photo. Who sees which rows is unchanged: Admin all, a Manager their department, Staff only
 * their own. Through the real security chain and the in-memory test database; every test rolls back.
 */
@SpringBootTest
@Transactional
public class AssignmentStaffPictureTest {

    private static final String PICTURE = "https://res.cloudinary.com/example/image/upload/v1/taskotel/profile-pictures/apic_with.jpg";

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private AssignmentRepository assignmentRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private final ObjectMapper mapper = new ObjectMapper();
    private MockMvc mockMvc;
    private User admin;
    private Staff withPicture, withoutPicture;
    private Task taskWith, taskWithout, freeTask;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        ScopeFixtures fixtures = new ScopeFixtures(userRepository, staffRepository, departmentRepository, passwordEncoder);
        Department alpha = fixtures.department("APIC_ALPHA");
        admin = fixtures.account("apic_admin", Role.ADMIN, true);
        fixtures.manager("apic_manager", alpha);
        withPicture = fixtures.staff("apic_with", alpha);
        withoutPicture = fixtures.staff("apic_without", alpha);
        User pictured = withPicture.getUser();
        pictured.setProfileImageUrl(PICTURE);
        pictured.setProfileImagePublicId("taskotel/profile-pictures/apic_with");
        userRepository.saveAndFlush(pictured);

        taskWith = task("APIC task with picture", alpha, TaskStatus.ASSIGNED);
        taskWithout = task("APIC task without picture", alpha, TaskStatus.ASSIGNED);
        freeTask = task("APIC free task", alpha, TaskStatus.PENDING);
        assign(taskWith, withPicture);
        assign(taskWithout, withoutPicture);
    }

    private Task task(String title, Department department, TaskStatus status) {
        Task task = new Task();
        task.setTitle(title);
        task.setPriority(Priority.MEDIUM);
        task.setStatus(status);
        task.setEstimatedDuration(30);
        task.setDueDate(LocalDate.of(2027, 1, 1));
        task.setDepartment(department);
        task.setCreatedBy(admin);
        return taskRepository.saveAndFlush(task);
    }

    private void assign(Task task, Staff staff) {
        Assignment assignment = new Assignment();
        assignment.setTask(task);
        assignment.setStaff(staff);
        assignmentRepository.saveAndFlush(assignment);
    }

    private List<JsonNode> list(String username, String role) throws Exception {
        String body = mockMvc.perform(get("/api/assignments").with(user(username).roles(role))).andReturn().getResponse().getContentAsString();
        List<JsonNode> rows = new ArrayList<>();
        mapper.readTree(body).forEach(rows::add);
        return rows;
    }

    private static JsonNode rowFor(List<JsonNode> rows, Task task) {
        return rows.stream().filter(r -> task.getId().toString().equals(r.path("taskId").asText())).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("Admin list: a holder with a picture carries its URL; a holder without one carries null")
    void adminSeesPictures() throws Exception {
        List<JsonNode> rows = list("apic_admin", "ADMIN");
        assertEquals(PICTURE, rowFor(rows, taskWith).path("staffProfileImageUrl").asText());
        assertTrue(rowFor(rows, taskWithout).has("staffProfileImageUrl"), "the field is always present");
        assertTrue(rowFor(rows, taskWithout).path("staffProfileImageUrl").isNull());
    }

    @Test
    @DisplayName("Manager list (own department): the same pictures, from the same rows as before")
    void managerSeesPictures() throws Exception {
        List<JsonNode> rows = list("apic_manager", "MANAGER");
        assertEquals(PICTURE, rowFor(rows, taskWith).path("staffProfileImageUrl").asText());
        assertTrue(rowFor(rows, taskWithout).path("staffProfileImageUrl").isNull());
    }

    @Test
    @DisplayName("Staff see only their own rows, with their own picture (or null); no one else's row or picture")
    void staffSeeOnlyTheirOwn() throws Exception {
        List<JsonNode> own = list("apic_with", "STAFF");
        assertEquals(1, own.size());
        assertEquals(PICTURE, own.get(0).path("staffProfileImageUrl").asText());
        List<JsonNode> other = list("apic_without", "STAFF");
        assertEquals(1, other.size());
        assertTrue(other.get(0).path("staffProfileImageUrl").isNull());
        assertTrue(other.stream().noneMatch(r -> PICTURE.equals(r.path("staffProfileImageUrl").asText())), "a colleague's picture is not exposed");
    }

    @Test
    @DisplayName("Creating an assignment returns the holder's picture too (no lazy-loading failure)")
    void createReturnsPicture() throws Exception {
        // a free member (one active assignment per person), also with a picture
        Staff free = new ScopeFixtures(userRepository, staffRepository, departmentRepository, passwordEncoder)
                .staff("apic_free", withPicture.getDepartment());
        User account = free.getUser();
        account.setProfileImageUrl(PICTURE);
        userRepository.saveAndFlush(account);
        String body = "{\"taskId\":\"" + freeTask.getId() + "\",\"staffId\":\"" + free.getId() + "\"}";
        var response = mockMvc.perform(post("/api/assignments").with(user("apic_admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
        assertTrue(response.getStatus() < 300, "status " + response.getStatus() + ": " + response.getContentAsString());
        JsonNode created = mapper.readTree(response.getContentAsString());
        JsonNode row = created.isArray() ? created.get(0) : created;
        assertEquals(PICTURE, row.path("staffProfileImageUrl").asText());
    }

    @Test
    @DisplayName("The row's other fields are unchanged; a staff record without a linked account gives null, not an error")
    void dtoShape() throws Exception {
        Set<String> fields = new TreeSet<>();
        rowFor(list("apic_admin", "ADMIN"), taskWith).fieldNames().forEachRemaining(fields::add);
        assertEquals(new TreeSet<>(List.of("id", "taskId", "taskTitle", "taskStatus", "staffId", "staffName", "staffProfileImageUrl", "assignedAt")), fields);

        Staff orphan = new Staff();
        orphan.setName("No account");
        Assignment assignment = new Assignment();
        assignment.setStaff(orphan);
        assertNull(AssignmentDto.fromEntity(assignment).getStaffProfileImageUrl());
        assertEquals("No account", AssignmentDto.fromEntity(assignment).getStaffName());
    }
}
