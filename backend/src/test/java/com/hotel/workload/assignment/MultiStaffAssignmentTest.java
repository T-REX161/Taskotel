package com.hotel.workload.assignment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.workload.security.Role;
import com.hotel.workload.security.ScopeFixtures;
import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
import com.hotel.workload.staff.Availability;
import com.hotel.workload.staff.Department;
import com.hotel.workload.staff.DepartmentRepository;
import com.hotel.workload.staff.Staff;
import com.hotel.workload.staff.StaffRepository;
import com.hotel.workload.support.SupportAssignment;
import com.hotel.workload.support.SupportAssignmentRepository;
import com.hotel.workload.support.SupportLifecycle;
import com.hotel.workload.support.SupportStatus;
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
import org.springframework.dao.DataIntegrityViolationException;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Assigning one task to several staff members (POST /api/assignments with staffIds), through the real security chain, services and database
 * (H2). Every test rolls back. Fixture: departments A and B; in A an Admin, two Managers, staff a1..a3 (available), a4 (unavailable), a5
 * (inactive), and a Manager account with a Staff record (never assignable); in B one Manager and staff b1. Tasks have different durations so
 * the workload can be told apart.
 */
@SpringBootTest
@Transactional
public class MultiStaffAssignmentTest {

    private static final String A = "MSA_A";
    private static final String B = "MSA_B";

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private AssignmentRepository assignmentRepository;
    @Autowired private SupportAssignmentRepository supportRepository;
    @Autowired private SupportLifecycle supportLifecycle;
    @Autowired private PasswordEncoder passwordEncoder;

    private final ObjectMapper mapper = new ObjectMapper();
    private MockMvc mockMvc;
    private Department deptA;
    private Department deptB;
    private User admin;
    private Staff a1, a2, a3, a4, a5, b1, managerRecord;
    private Task task;     // PENDING, A, 40 minutes

    @BeforeEach
    public void setUp() {
        SecurityContextHolder.clearContext();
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        ScopeFixtures fixtures = new ScopeFixtures(userRepository, staffRepository, departmentRepository, passwordEncoder);
        deptA = fixtures.department(A);
        deptB = fixtures.department(B);
        admin = fixtures.account("msa_admin", Role.ADMIN, true);
        fixtures.manager("msa_mgr_a1", deptA);
        fixtures.manager("msa_mgr_a2", deptA);
        fixtures.manager("msa_mgr_b", deptB);
        managerRecord = fixtures.manager("msa_mgr_target", deptA);
        a1 = fixtures.staff("msa_a1", deptA);
        a2 = fixtures.staff("msa_a2", deptA);
        a3 = fixtures.staff("msa_a3", deptA);
        a4 = fixtures.staff("msa_a4", deptA);
        a4.setAvailability(Availability.UNAVAILABLE);
        staffRepository.saveAndFlush(a4);
        a5 = fixtures.staff("msa_a5", deptA);
        a5.setActive(false);
        staffRepository.saveAndFlush(a5);
        b1 = fixtures.staff("msa_b1", deptB);
        task = task("Shared task", deptA, TaskStatus.PENDING, 40);
    }

    @AfterEach
    public void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Task task(String title, Department department, TaskStatus status, int minutes) {
        Task t = new Task();
        t.setTitle(title);
        t.setPriority(Priority.MEDIUM);
        t.setStatus(status);
        t.setEstimatedDuration(minutes);
        t.setDueDate(LocalDate.of(2027, 1, 1));
        t.setDepartment(department);
        t.setCreatedBy(admin);
        return taskRepository.saveAndFlush(t);
    }

    private MvcResult call(String username, String role, MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.with(user(username).roles(role)).contentType(MediaType.APPLICATION_JSON)).andReturn();
    }

    private static String ids(Staff... staff) {
        List<String> quoted = new ArrayList<>();
        for (Staff s : staff) quoted.add("\"" + s.getId() + "\"");
        return "[" + String.join(",", quoted) + "]";
    }

    private MvcResult assignMany(String username, String role, Task t, String staffIdsJson) throws Exception {
        return call(username, role, post("/api/assignments").content("{\"taskId\":\"" + t.getId() + "\",\"staffIds\":" + staffIdsJson + "}"));
    }

    private MvcResult assignMany(Task t, Staff... staff) throws Exception {
        return assignMany("msa_admin", "ADMIN", t, ids(staff));
    }

    private MvcResult assignOne(String username, String role, Task t, Staff s) throws Exception {
        return call(username, role, post("/api/assignments").content("{\"taskId\":\"" + t.getId() + "\",\"staffId\":\"" + s.getId() + "\"}"));
    }

    private JsonNode json(MvcResult r) throws Exception {
        return mapper.readTree(r.getResponse().getContentAsString());
    }

    private String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString();
    }

    private long rows(Task t) {
        assignmentRepository.flush();
        return assignmentRepository.findAllByTaskId(t.getId()).size();
    }

    private TaskStatus status(Task t) {
        taskRepository.flush();
        return taskRepository.findById(t.getId()).orElseThrow().getStatus();
    }

    private Assignment give(Task t, Staff s) {
        Assignment assignment = new Assignment();
        assignment.setTask(t);
        assignment.setStaff(s);
        return assignmentRepository.saveAndFlush(assignment);
    }

    // ── success ─────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Several staff are assigned in one request: 201 with a list of the created assignments, one row per person, the task ASSIGNED")
    void assignsSeveralStaff() throws Exception {
        MvcResult result = assignMany(task, a1, a2, a3);
        assertEquals(201, result.getResponse().getStatus(), body(result));
        JsonNode created = json(result);
        assertTrue(created.isArray());
        assertEquals(3, created.size());
        Set<String> staffIds = new HashSet<>();
        for (JsonNode item : created) {
            assertEquals(task.getId().toString(), item.get("taskId").asText());
            assertEquals("ASSIGNED", item.get("taskStatus").asText());
            staffIds.add(item.get("staffId").asText());
        }
        assertEquals(Set.of(a1.getId().toString(), a2.getId().toString(), a3.getId().toString()), staffIds);
        assertEquals(3, rows(task), "one row per staff member");
        assertEquals(TaskStatus.ASSIGNED, status(task));
    }

    @Test
    @DisplayName("The answer lists the assignments in the order the staff were sent")
    void answerKeepsTheRequestOrder() throws Exception {
        JsonNode created = json(assignMany(task, a3, a1, a2));
        assertEquals(a3.getId().toString(), created.get(0).get("staffId").asText());
        assertEquals(a1.getId().toString(), created.get(1).get("staffId").asText());
        assertEquals(a2.getId().toString(), created.get(2).get("staffId").asText());
    }

    @Test
    @DisplayName("One person in a list works, and the original single-staff format still answers with one assignment object")
    void singleFormatsStillWork() throws Exception {
        MvcResult list = assignMany(task, a1);
        assertEquals(201, list.getResponse().getStatus());
        assertTrue(json(list).isArray() && json(list).size() == 1);

        Task other = task("Single", deptA, TaskStatus.PENDING, 20);
        MvcResult single = assignOne("msa_admin", "ADMIN", other, a2);
        assertEquals(201, single.getResponse().getStatus(), body(single));
        assertTrue(json(single).isObject(), "the old format keeps its old answer");
        assertEquals(a2.getId().toString(), json(single).get("staffId").asText());
        assertEquals(1, rows(other));
    }

    // ── request shape ───────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Exactly one of staffId and staffIds: both, neither, an empty list, a null or repeated id, too many, a non-list and a bad id are 400 and create nothing")
    void badShapesAreRefused() throws Exception {
        String t = "\"taskId\":\"" + task.getId() + "\"";
        String one = "\"" + a1.getId() + "\"";
        List<String> bodies = List.of(
                "{" + t + ",\"staffId\":" + one + ",\"staffIds\":[" + one + "]}",
                "{" + t + "}",
                "{" + t + ",\"staffIds\":[]}",
                "{" + t + ",\"staffIds\":[null]}",
                "{" + t + ",\"staffIds\":[" + one + ",null]}",
                "{" + t + ",\"staffIds\":[" + one + "," + one + "]}",
                "{" + t + ",\"staffIds\":\"" + a1.getId() + "\"}",
                "{" + t + ",\"staffIds\":{}}",
                "{" + t + ",\"staffIds\":[\"not-a-uuid\"]}",
                "{" + t + ",\"staffIds\":[1,2]}",
                "{\"staffIds\":[" + one + "]}",
                "{\"taskId\":null,\"staffIds\":[" + one + "]}",
                "{" + t + ",\"staffIds\":[" + one);
        for (String bad : bodies) {
            MvcResult result = call("msa_admin", "ADMIN", post("/api/assignments").content(bad));
            assertEquals(400, result.getResponse().getStatus(), bad + " -> " + body(result));
        }
        List<String> fiftyOne = new ArrayList<>();
        for (int i = 0; i < 51; i++) fiftyOne.add("\"" + UUID.randomUUID() + "\"");
        assertEquals(400, call("msa_admin", "ADMIN", post("/api/assignments").content("{" + t + ",\"staffIds\":[" + String.join(",", fiftyOne) + "]}")).getResponse().getStatus());
        assertEquals(0, rows(task));
        assertEquals(TaskStatus.PENDING, status(task));
    }

    // ── all or nothing ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("One refused person refuses everyone: unavailable, inactive, a Manager account, another department, a missing person (nothing is created, the task stays Pending)")
    void oneInvalidMemberRefusesTheWholeRequest() throws Exception {
        record Case(String name, Staff bad, int expected) {}
        List<Case> cases = List.of(
                new Case("unavailable", a4, 400),
                new Case("inactive", a5, 400),
                new Case("manager account", managerRecord, 400),
                new Case("another department", b1, 400));
        for (Case c : cases) {
            MvcResult result = assignMany(task, a1, c.bad(), a2);
            assertEquals(c.expected(), result.getResponse().getStatus(), c.name() + ": " + body(result));
            assertEquals(0, rows(task), c.name() + ": nothing created");
            assertEquals(TaskStatus.PENDING, status(task), c.name());
        }
        MvcResult missing = assignMany("msa_admin", "ADMIN", task, "[\"" + a1.getId() + "\",\"" + UUID.randomUUID() + "\"]");
        assertEquals(404, missing.getResponse().getStatus(), body(missing));
        assertEquals(0, rows(task));

        // The valid members were not touched: they can still be assigned afterwards
        assertEquals(201, assignMany(task, a1, a2).getResponse().getStatus());
    }

    @Test
    @DisplayName("The refusal names the person when several were sent, and the single-staff message is unchanged")
    void messagesNameThePersonOnlyForLists() throws Exception {
        String many = body(assignMany(task, a1, a4));
        assertTrue(many.contains("Staff member is not available") && many.contains(a4.getName()), many);
        String single = body(assignOne("msa_admin", "ADMIN", task, a4));
        assertTrue(single.contains("Staff member is not available") && !single.contains(a4.getName()), single);
    }

    @Test
    @DisplayName("A staff member who already has an active assignment refuses the whole request (409); an already-assigned, completed or cancelled task is refused too")
    void conflictsAreRefused() throws Exception {
        Task busyWith = task("Busy", deptA, TaskStatus.ASSIGNED, 15);
        give(busyWith, a2);
        MvcResult busy = assignMany(task, a1, a2, a3);
        assertEquals(409, busy.getResponse().getStatus(), body(busy));
        assertEquals(0, rows(task));

        assertEquals(201, assignMany(task, a1, a3).getResponse().getStatus());
        MvcResult again = assignMany(task, a1, a3);
        assertEquals(409, again.getResponse().getStatus(), "an already-assigned task: " + body(again));
        assertEquals(2, rows(task), "unchanged");

        Task done = task("Done", deptA, TaskStatus.COMPLETED, 10);
        Task cancelled = task("Cancelled", deptA, TaskStatus.CANCELLED, 10);
        assertEquals(400, assignMany(done, a2).getResponse().getStatus());
        assertEquals(400, assignMany(cancelled, a2).getResponse().getStatus());
    }

    // ── who may assign, and where ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("A Manager assigns several staff of their own department; one person from another department, or a task of another department, is a 404 that creates nothing")
    void managerScopeAppliesToEveryMember() throws Exception {
        MvcResult own = assignMany("msa_mgr_a1", "MANAGER", task, ids(a1, a2));
        assertEquals(201, own.getResponse().getStatus(), body(own));
        assertEquals(2, rows(task));

        Task second = task("Second", deptA, TaskStatus.PENDING, 20);
        MvcResult crossing = assignMany("msa_mgr_a1", "MANAGER", second, ids(a3, b1));
        assertEquals(404, crossing.getResponse().getStatus(), body(crossing));
        assertEquals(0, rows(second), "the member in the Manager's own department was not assigned either");
        assertEquals(TaskStatus.PENDING, status(second));

        MvcResult otherDepartmentTask = assignMany("msa_mgr_b", "MANAGER", second, ids(b1));
        assertEquals(404, otherDepartmentTask.getResponse().getStatus());
        assertEquals(404, assignMany("msa_mgr_a1", "MANAGER", task("In B", deptB, TaskStatus.PENDING, 20), ids(a3)).getResponse().getStatus());
    }

    @Test
    @DisplayName("Staff cannot assign (403) and nobody signed in is 401, for both formats")
    void onlyAdminAndManagerAssign() throws Exception {
        assertEquals(403, assignMany("msa_a1", "STAFF", task, ids(a1, a2)).getResponse().getStatus());
        assertEquals(403, assignOne("msa_a1", "STAFF", task, a1).getResponse().getStatus());
        MvcResult anonymous = mockMvc.perform(post("/api/assignments").contentType(MediaType.APPLICATION_JSON)
                .content("{\"taskId\":\"" + task.getId() + "\",\"staffIds\":" + ids(a1) + "}")).andReturn();
        assertEquals(401, anonymous.getResponse().getStatus());
        assertEquals(0, rows(task));
    }

    // ── shared status ───────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Any assigned staff member can move the shared task status; one who is not assigned gets 404; the other assignees see the same status")
    void sharedStatusIsMovedByAnyAssignee() throws Exception {
        assignMany(task, a1, a2);
        MvcResult started = call("msa_a1", "STAFF", patch("/api/tasks/" + task.getId() + "/status").content("{\"status\":\"IN_PROGRESS\"}"));
        assertEquals(200, started.getResponse().getStatus(), body(started));
        assertEquals(TaskStatus.IN_PROGRESS, status(task));

        assertEquals(404, call("msa_a3", "STAFF", patch("/api/tasks/" + task.getId() + "/status").content("{\"status\":\"COMPLETED\"}")).getResponse().getStatus(), "not assigned");
        assertEquals(TaskStatus.IN_PROGRESS, status(task));

        JsonNode seenByA2 = json(call("msa_a2", "STAFF", get("/api/tasks")));
        assertEquals(1, seenByA2.size());
        assertEquals("IN_PROGRESS", seenByA2.get(0).get("status").asText(), "the other assignee sees the shared status");

        MvcResult completed = call("msa_a2", "STAFF", patch("/api/tasks/" + task.getId() + "/status").content("{\"status\":\"COMPLETED\"}"));
        assertEquals(200, completed.getResponse().getStatus(), body(completed));
        assertEquals(TaskStatus.COMPLETED, status(task));
        assertEquals(409, call("msa_a1", "STAFF", patch("/api/tasks/" + task.getId() + "/status").content("{\"status\":\"IN_PROGRESS\"}")).getResponse().getStatus(), "finished tasks stay finished");
        assertEquals(2, rows(task), "the assignments are kept");
    }

    // ── workload and dashboard ──────────────────────────────────────────────────────────────

    private JsonNode workloadRow(JsonNode workload, Staff staff) {
        for (JsonNode row : workload) if (row.get("staffId").asText().equals(staff.getId().toString())) return row;
        throw new AssertionError("no workload row for " + staff.getName());
    }

    @Test
    @DisplayName("Every assigned staff member's workload includes the task's FULL duration (40 minutes each); the unassigned member has none; finishing the task clears them all")
    void workloadCountsTheFullDurationForEachAssignee() throws Exception {
        assignMany(task, a1, a2);
        JsonNode workload = json(call("msa_admin", "ADMIN", get("/api/workload")));
        for (Staff assigned : List.of(a1, a2)) {
            assertEquals(1, workloadRow(workload, assigned).get("activeTaskCount").asInt(), assigned.getName());
            assertEquals(40, workloadRow(workload, assigned).get("totalEstimatedDuration").asInt(), assigned.getName() + " carries the whole 40 minutes");
        }
        assertEquals(0, workloadRow(workload, a3).get("activeTaskCount").asInt());
        assertEquals(0, workloadRow(workload, a3).get("totalEstimatedDuration").asInt());

        // Each person's own view
        JsonNode own = json(call("msa_a2", "STAFF", get("/api/workload")));
        assertEquals(1, own.size());
        assertEquals(40, own.get(0).get("totalEstimatedDuration").asInt());

        // A Manager sees their department's staff, including both assignees
        JsonNode managerView = json(call("msa_mgr_a1", "MANAGER", get("/api/workload")));
        assertEquals(40, workloadRow(managerView, a1).get("totalEstimatedDuration").asInt());
        assertEquals(40, workloadRow(managerView, a2).get("totalEstimatedDuration").asInt());

        call("msa_a1", "STAFF", patch("/api/tasks/" + task.getId() + "/status").content("{\"status\":\"IN_PROGRESS\"}"));
        call("msa_a1", "STAFF", patch("/api/tasks/" + task.getId() + "/status").content("{\"status\":\"COMPLETED\"}"));
        JsonNode after = json(call("msa_admin", "ADMIN", get("/api/workload")));
        assertEquals(0, workloadRow(after, a1).get("activeTaskCount").asInt());
        assertEquals(0, workloadRow(after, a2).get("totalEstimatedDuration").asInt(), "completion frees every assignee");
    }

    @Test
    @DisplayName("Dashboard counts: active assignments rise by the number of rows (3), the task is counted once by status, for the Admin and for the Manager's department")
    void dashboardCountsStayConsistent() throws Exception {
        JsonNode adminBefore = json(call("msa_admin", "ADMIN", get("/api/dashboard/summary")));
        JsonNode managerBefore = json(call("msa_mgr_a1", "MANAGER", get("/api/dashboard/summary")));
        assignMany(task, a1, a2, a3);
        JsonNode adminAfter = json(call("msa_admin", "ADMIN", get("/api/dashboard/summary")));
        JsonNode managerAfter = json(call("msa_mgr_a1", "MANAGER", get("/api/dashboard/summary")));

        assertEquals(3, adminAfter.get("totalActiveAssignments").asInt() - adminBefore.get("totalActiveAssignments").asInt(), "one per assignment row");
        assertEquals(1, adminAfter.get("assignedTasks").asInt() - adminBefore.get("assignedTasks").asInt(), "the task itself once");
        assertEquals(1, adminBefore.get("pendingTasks").asInt() - adminAfter.get("pendingTasks").asInt() + 0, "it left Pending once");
        assertEquals(3, managerAfter.get("totalActiveAssignments").asInt() - managerBefore.get("totalActiveAssignments").asInt(), "the Manager's department counts the same rows");
        assertEquals(1, managerAfter.get("assignedTasks").asInt() - managerBefore.get("assignedTasks").asInt());

        // Another department's Manager sees none of it
        JsonNode otherBefore = json(call("msa_mgr_b", "MANAGER", get("/api/dashboard/summary")));
        assertEquals(0, otherBefore.get("totalActiveAssignments").asInt(), "no assignment of another department leaks");
        // A member's own dashboard shows the shared task once
        JsonNode own = json(call("msa_a3", "STAFF", get("/api/dashboard/summary")));
        assertEquals(1, own.get("assignedTasks").asInt());
        assertEquals(1, own.get("recentAssignments").size());
    }

    // ── reassign stays single-staff; delete; unique key ────────────────────────────────────

    @Test
    @DisplayName("Reassignment is still one assignment to one staff member, and leaves the other assignees of the task alone")
    void reassignKeepsWorkingPerAssignment() throws Exception {
        JsonNode created = json(assignMany(task, a1, a2));
        String firstAssignmentId = created.get(0).get("id").asText();
        MvcResult moved = call("msa_admin", "ADMIN", put("/api/assignments/" + firstAssignmentId).content("{\"staffId\":\"" + a3.getId() + "\"}"));
        assertEquals(200, moved.getResponse().getStatus(), body(moved));
        Set<UUID> holders = new HashSet<>();
        assignmentRepository.findAllByTaskId(task.getId()).forEach(a -> holders.add(a.getStaff().getId()));
        assertEquals(Set.of(a3.getId(), a2.getId()), holders, "only the first assignee was replaced");
        assertEquals(2, rows(task));
    }

    @Test
    @DisplayName("Deleting the task removes every one of its assignments")
    void deletingTheTaskRemovesAllAssignments() throws Exception {
        assignMany(task, a1, a2, a3);
        assertEquals(204, call("msa_admin", "ADMIN", delete("/api/tasks/" + task.getId())).getResponse().getStatus());
        assertEquals(0, rows(task));
    }

    @Test
    @DisplayName("The database refuses the same staff member twice on one task (unique task + staff), while two people on one task are fine")
    void theSameStaffMemberCannotBeOnATaskTwice() {
        give(task, a1);
        give(task, a2);
        assertEquals(2, rows(task));
        Assignment duplicate = new Assignment();
        duplicate.setTask(task);
        duplicate.setStaff(a1);
        assertThrows(DataIntegrityViolationException.class, () -> assignmentRepository.saveAndFlush(duplicate));
    }

    // ── support lifecycle ───────────────────────────────────────────────────────────────────

    private SupportAssignment support(Task t, Staff lent) {
        SupportAssignment support = new SupportAssignment();
        support.setPlanId(UUID.randomUUID());
        support.setStaffId(lent.getId());
        support.setStaffName(lent.getName());
        support.setHomeDepartmentCode(A);
        support.setReceivingDepartmentCode(A);
        support.setTaskId(t.getId());
        support.setTaskTitle(t.getTitle());
        support.setApprovedByUsername("msa_admin");
        return supportRepository.saveAndFlush(support);
    }

    private SupportStatus supportStatus(SupportAssignment s) {
        supportRepository.flush();
        return supportRepository.findById(s.getId()).orElseThrow().getStatus();
    }

    @Test
    @DisplayName("Support is not disturbed by a multi-staff assignment of another task, and still ends with its own task when that is completed")
    void multiAssignmentLeavesOtherSupportAlone() throws Exception {
        Task lentTask = task("Lent", deptA, TaskStatus.IN_PROGRESS, 30);
        give(lentTask, a3);
        SupportAssignment support = support(lentTask, a3);

        assertEquals(201, assignMany(task, a1, a2).getResponse().getStatus());
        assertEquals(SupportStatus.ACTIVE, supportStatus(support), "an unrelated multi-staff assignment does not end it");

        lentTask.setStatus(TaskStatus.COMPLETED);
        taskRepository.saveAndFlush(lentTask);
        supportLifecycle.onTaskChanged(lentTask);
        assertEquals(SupportStatus.COMPLETED, supportStatus(support), "the existing lifecycle still applies");
    }

    @Test
    @DisplayName("Completing a multi-assigned task through the Staff endpoint ends any support on it, as before")
    void completionEndsSupportOnAMultiAssignedTask() throws Exception {
        assignMany(task, a1, a2);
        call("msa_a1", "STAFF", patch("/api/tasks/" + task.getId() + "/status").content("{\"status\":\"IN_PROGRESS\"}"));
        SupportAssignment support = support(task, a2);
        assertEquals(200, call("msa_a2", "STAFF", patch("/api/tasks/" + task.getId() + "/status").content("{\"status\":\"COMPLETED\"}")).getResponse().getStatus());
        assertEquals(SupportStatus.COMPLETED, supportStatus(support));
    }

    @Test
    @DisplayName("Reassigning ONE of several holders does not end the support of another holder; reassigning the supported person (the single-holder case) still does")
    void reassignmentHookJudgesTheSupportedPerson() throws Exception {
        // Several holders: the supported person (a1) keeps the task while a2 is replaced by a3
        Assignment held1 = give(task, a1);
        give(task, a2);
        SupportAssignment support = support(task, a1);
        supportLifecycle.onTaskReassigned(task.getId(), a3.getId());
        assertEquals(SupportStatus.ACTIVE, supportStatus(support), "the supported person still holds the task");

        // The supported person is the one replaced: the support ends (the earlier single-holder behaviour)
        held1.setStaff(a3);
        assignmentRepository.saveAndFlush(held1);
        supportLifecycle.onTaskReassigned(task.getId(), a3.getId());
        assertEquals(SupportStatus.CANCELLED, supportStatus(support));
        assertFalse(assignmentRepository.findAllByTaskId(task.getId()).stream().anyMatch(a -> a.getStaff().getId().equals(a1.getId())));
    }

    @Test
    @DisplayName("Single-holder reassignment through the API still ends the support of the replaced person, exactly as before")
    void singleHolderReassignmentStillEndsSupport() throws Exception {
        Task lentTask = task("Lent single", deptA, TaskStatus.ASSIGNED, 30);
        Assignment held = give(lentTask, a1);
        SupportAssignment support = support(lentTask, a1);
        MvcResult moved = call("msa_admin", "ADMIN", put("/api/assignments/" + held.getId()).content("{\"staffId\":\"" + a2.getId() + "\"}"));
        assertEquals(200, moved.getResponse().getStatus(), body(moved));
        assertEquals(SupportStatus.CANCELLED, supportStatus(support));
    }
}
