package com.hotel.workload.workload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.workload.assignment.Assignment;
import com.hotel.workload.assignment.AssignmentRepository;
import com.hotel.workload.security.Role;
import com.hotel.workload.security.ScopeFixtures;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * GET /api/workload/department-summary: department workload UTILIZATION (assigned minutes over working capacity), through the real security
 * chain and the real (H2) database. Every test rolls back.
 *
 * <p>Fixture. Departments DSUM_A, DSUM_B and DSUM_C.
 * <ul>
 *   <li>A's staff: a1, a2, a3 (AVAILABLE, a3 holds nothing), a4 (UNAVAILABLE, holds a task), a5 (deactivated). Capacity = 3 eligible x 480 = 1440.</li>
 *   <li>A owns: t1 60 min ASSIGNED held by a1, a2 AND b1 (a helper from B); t2 30 min PENDING nobody holds; t3 no duration IN_PROGRESS (a1);
 *       t6 100 min (a4); t4 500 min COMPLETED (a1); t5 500 min CANCELLED (a2); and a 400 min task with no department (a1).</li>
 *   <li>B: staff b1 (AVAILABLE) holds its own 120 min task. Capacity 480.</li>
 *   <li>C: a Manager and no staff, with one unassigned 45 min task: no capacity.</li>
 * </ul>
 * So A: assigned 60 x 2 (a1, a2) + 0 + 100 = 220 minutes over 3 unique tasks held by 3 people, 15.3% of 1440 = LOW, backlog 1 task / 30 min.
 * B: 120 of 480 = 25.0% (exactly the LOW boundary).
 */
@SpringBootTest
@Transactional
public class DepartmentWorkloadSummaryTest {

    private static final String A = "DSUM_A";
    private static final String B = "DSUM_B";
    private static final String C = "DSUM_C";

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
    private Staff a1, a2, a4, b1;
    private Task t1;

    @BeforeEach
    public void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        ScopeFixtures fixtures = new ScopeFixtures(userRepository, staffRepository, departmentRepository, passwordEncoder);
        Department deptA = fixtures.department(A);
        Department deptB = fixtures.department(B);
        Department deptC = fixtures.department(C);
        admin = fixtures.account("dsum_admin", Role.ADMIN, true);
        fixtures.manager("dsum_mgr_a", deptA);
        fixtures.manager("dsum_mgr_b", deptB);
        fixtures.manager("dsum_mgr_c", deptC);
        fixtures.account("dsum_staff_user", Role.STAFF, true);
        // the signed-in Staff account of the 403 test: kept out of A's capacity so the counts below stay exact
        Staff signedInStaff = fixtures.staffRecord("dsum_staff_user", Role.STAFF, deptA);
        signedInStaff.setAvailability(Availability.UNAVAILABLE);
        staffRepository.saveAndFlush(signedInStaff);
        a1 = fixtures.staff("dsum_a1", deptA);
        a2 = fixtures.staff("dsum_a2", deptA);
        fixtures.staff("dsum_a3", deptA);
        a4 = fixtures.staff("dsum_a4", deptA);
        a4.setAvailability(Availability.UNAVAILABLE);
        staffRepository.saveAndFlush(a4);
        Staff a5 = fixtures.staff("dsum_a5", deptA);
        a5.setActive(false);
        staffRepository.saveAndFlush(a5);
        b1 = fixtures.staff("dsum_b1", deptB);

        t1 = task("t1", deptA, 60, TaskStatus.ASSIGNED);
        assign(t1, a1);
        assign(t1, a2);
        assign(t1, b1); // a helper from B on A's task
        task("t2 waiting", deptA, 30, TaskStatus.PENDING);
        assign(task("t3 no duration", deptA, null, TaskStatus.IN_PROGRESS), a1);
        assign(task("t6 held by an unavailable person", deptA, 100, TaskStatus.ASSIGNED), a4);
        assign(task("t4 done", deptA, 500, TaskStatus.COMPLETED), a1);
        assign(task("t5 cancelled", deptA, 500, TaskStatus.CANCELLED), a2);
        assign(task("no department", null, 400, TaskStatus.ASSIGNED), a1);
        assign(task("tb", deptB, 120, TaskStatus.ASSIGNED), b1);
        task("c waiting", deptC, 45, TaskStatus.PENDING);
    }

    private Task task(String title, Department department, Integer minutes, TaskStatus status) {
        Task task = new Task();
        task.setTitle(title);
        task.setPriority(Priority.MEDIUM);
        task.setStatus(status);
        task.setEstimatedDuration(minutes);
        task.setDueDate(LocalDate.of(2027, 1, 1));
        task.setDepartment(department);
        task.setCreatedBy(admin);
        return taskRepository.saveAndFlush(task);
    }

    private Assignment assign(Task task, Staff staff) {
        Assignment assignment = new Assignment();
        assignment.setTask(task);
        assignment.setStaff(staff);
        return assignmentRepository.saveAndFlush(assignment);
    }

    private MvcResult summary(String username, String role) throws Exception {
        return mockMvc.perform(get("/api/workload/department-summary").with(user(username).roles(role)).contentType(MediaType.APPLICATION_JSON)).andReturn();
    }

    private Map<String, JsonNode> byCode(String username, String role) throws Exception {
        MvcResult result = summary(username, role);
        assertEquals(200, result.getResponse().getStatus(), username);
        Map<String, JsonNode> rows = new HashMap<>();
        for (JsonNode node : mapper.readTree(result.getResponse().getContentAsString())) rows.put(node.get("code").asText(), node);
        return rows;
    }

    @Test
    @DisplayName("Multiple assignees multiply the duration (the existing per-assignee rule): t1 is 60 x 2 own assignees = 120, plus 0 and 100; completed, cancelled and department-less work is out")
    public void assignedMinutesFollowThePerAssigneeRule() throws Exception {
        JsonNode a = byCode("dsum_admin", "ADMIN").get(A);
        assertEquals(220, a.get("assignedMinutes").asInt());
        JsonNode b = byCode("dsum_admin", "ADMIN").get(B);
        assertEquals(120, b.get("assignedMinutes").asInt());
    }

    @Test
    @DisplayName("The task count is unique tasks and the staff count is distinct people, whatever the number of assignment rows")
    public void uniqueTasksAndDistinctStaff() throws Exception {
        JsonNode a = byCode("dsum_admin", "ADMIN").get(A);
        assertEquals(3, a.get("activeTaskCount").asInt(), "t1 (two own assignees) counts once, with t3 and t6");
        assertEquals(3, a.get("staffCount").asInt(), "a1, a2 and a4; the helper from B is not A's staff");
        JsonNode b = byCode("dsum_admin", "ADMIN").get(B);
        assertEquals(1, b.get("activeTaskCount").asInt());
        assertEquals(1, b.get("staffCount").asInt());
    }

    @Test
    @DisplayName("Capacity is 480 minutes per eligible staff member: AVAILABLE and active, idle people included; UNAVAILABLE and deactivated people excluded")
    public void capacityFromEligibleStaff() throws Exception {
        JsonNode a = byCode("dsum_admin", "ADMIN").get(A);
        assertEquals(3, a.get("eligibleStaffCount").asInt(), "a1, a2 and the idle a3; not a4 (UNAVAILABLE), not a5 (deactivated), not the Manager");
        assertEquals(1440, a.get("capacityMinutes").asInt());
        assertEquals(15.3, a.get("utilizationPercent").asDouble(), 0.0001);
        assertEquals("LOW", a.get("status").asText());
        JsonNode b = byCode("dsum_admin", "ADMIN").get(B);
        assertEquals(1, b.get("eligibleStaffCount").asInt());
        assertEquals(480, b.get("capacityMinutes").asInt());
        assertEquals(25.0, b.get("utilizationPercent").asDouble(), 0.0001);
        assertEquals("LOW", b.get("status").asText(), "exactly 25% is still LOW");
    }

    @Test
    @DisplayName("Unassigned active tasks are a separate backlog: counted apart and never part of the assigned minutes or the utilization")
    public void unassignedIsSeparate() throws Exception {
        JsonNode a = byCode("dsum_admin", "ADMIN").get(A);
        assertEquals(1, a.get("unassignedTaskCount").asInt());
        assertEquals(30, a.get("unassignedMinutes").asInt());
        assertEquals(220, a.get("assignedMinutes").asInt(), "the 30 minute backlog is not in the assigned minutes");
    }

    @Test
    @DisplayName("No capacity: a department with no eligible staff reports NO_CAPACITY and no utilization (never a division by zero), and its backlog is still shown")
    public void zeroCapacity() throws Exception {
        JsonNode c = byCode("dsum_admin", "ADMIN").get(C);
        assertEquals(0, c.get("eligibleStaffCount").asInt());
        assertEquals(0, c.get("capacityMinutes").asInt());
        assertTrue(c.get("utilizationPercent").isNull());
        assertEquals("NO_CAPACITY", c.get("status").asText());
        assertEquals(1, c.get("unassignedTaskCount").asInt());
        assertEquals(0, c.get("assignedMinutes").asInt());
    }

    @Test
    @DisplayName("Support isolation: the helper from B adds nothing to A and B is not charged for the help; removing the helper changes nothing in the minutes")
    public void supportDoesNotInflate() throws Exception {
        Map<String, JsonNode> rows = byCode("dsum_admin", "ADMIN");
        assertEquals(220, rows.get(A).get("assignedMinutes").asInt());
        assertEquals(120, rows.get(B).get("assignedMinutes").asInt(), "B stays at its own 120, not 120 + 60");
        assignmentRepository.findAllByTaskId(t1.getId()).stream().filter(x -> x.getStaff().getId().equals(b1.getId())).forEach(assignmentRepository::delete);
        assignmentRepository.flush();
        Map<String, JsonNode> after = byCode("dsum_admin", "ADMIN");
        assertEquals(220, after.get(A).get("assignedMinutes").asInt());
        assertEquals(120, after.get(B).get("assignedMinutes").asInt());
    }

    @Test
    @DisplayName("A Manager sees only the departments they manage, with that department's own figures; nothing about another department is sent")
    public void managerScope() throws Exception {
        Map<String, JsonNode> rows = byCode("dsum_mgr_a", "MANAGER");
        assertEquals(Set.of(A), rows.keySet());
        assertEquals(220, rows.get(A).get("assignedMinutes").asInt());
        String body = summary("dsum_mgr_a", "MANAGER").getResponse().getContentAsString();
        assertFalse(body.contains(B) || body.contains(C), "nothing about B or C reaches A's Manager");
        Map<String, JsonNode> other = byCode("dsum_mgr_b", "MANAGER");
        assertEquals(Set.of(B), other.keySet());
        assertEquals(120, other.get(B).get("assignedMinutes").asInt());
    }

    @Test
    @DisplayName("Staff are refused (403)")
    public void staffRefused() throws Exception {
        assertEquals(403, summary("dsum_staff_user", "STAFF").getResponse().getStatus());
    }

    @Test
    @DisplayName("Individual staff workload is unchanged: every assignment counts in full for each holder, with the person thresholds")
    public void individualWorkloadUnchanged() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/workload").with(user("dsum_admin").roles("ADMIN"))).andReturn();
        assertEquals(200, result.getResponse().getStatus());
        Map<UUID, JsonNode> rows = new HashMap<>();
        for (JsonNode node : mapper.readTree(result.getResponse().getContentAsString())) rows.put(UUID.fromString(node.get("staffId").asText()), node);
        // a1: t1 60 + t3 0 + the department-less 400 = 460 minutes over 3 active tasks: AT_RISK for a person
        assertEquals(460, rows.get(a1.getId()).get("totalEstimatedDuration").asInt());
        assertEquals(3, rows.get(a1.getId()).get("activeTaskCount").asInt());
        assertEquals("AT_RISK", rows.get(a1.getId()).get("workloadStatus").asText());
        assertEquals(60, rows.get(a2.getId()).get("totalEstimatedDuration").asInt());
        assertEquals("LOW", rows.get(a2.getId()).get("workloadStatus").asText());
        assertEquals(180, rows.get(b1.getId()).get("totalEstimatedDuration").asInt(), "b1: t1 60 + tb 120");
        assertEquals("NORMAL", rows.get(b1.getId()).get("workloadStatus").asText());
    }
}
