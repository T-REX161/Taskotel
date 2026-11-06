package com.hotel.workload.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.workload.assignment.Assignment;
import com.hotel.workload.assignment.AssignmentRepository;
import com.hotel.workload.security.CurrentUserScopeResolver;
import com.hotel.workload.security.Role;
import com.hotel.workload.security.ScopeFixtures;
import com.hotel.workload.security.UserRepository;
import com.hotel.workload.staff.Department;
import com.hotel.workload.staff.DepartmentRepository;
import com.hotel.workload.staff.Staff;
import com.hotel.workload.staff.StaffRepository;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Manager department isolation for the support WRITE paths (Step 2G-3): confirm (Admin only), end and archive (Admin globally, a Manager only
 * for records involving their department), through the real security chain and the real (H2) database, plus direct service calls. Every test
 * rolls back. The race tests are in {@link SupportConcurrencyTest}.
 *
 * <p>Fixture: R (receiving) and D (donor) with Managers mr and md; X and Y unrelated with Manager mx. Active records recA and recB (D lent a person
 * to R), recU (X lent a person to Y, unrelated); completed records recC, recC2 (D to R) and recCU (X to Y). Four invalid Managers, one Staff, one
 * Administrator.
 */
@SpringBootTest
@Transactional
public class SupportWriteDepartmentIsolationTest {

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository users;
    @Autowired private StaffRepository staffRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private AssignmentRepository assignmentRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private SupportAssignmentRepository supportRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private SupportAssignmentService supportService;
    @MockitoSpyBean private CurrentUserScopeResolver scopeResolver;

    private final ObjectMapper mapper = new ObjectMapper();
    private MockMvc mvc;
    private SupportTestFixture fx;
    private Department r, d, x, y;
    private SupportAssignment recA, recB, recU, recC, recC2, recCU;
    private Task taskA, taskB, taskU;

    private static final List<String> INVALID_MANAGERS = List.of("sw_m_nostaff", "sw_m_inactive_account", "sw_m_inactive_staff", "sw_m_closed");

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        fx = new SupportTestFixture(users, staffRepository, taskRepository, assignmentRepository, departmentRepository, supportRepository);
        ScopeFixtures sf = new ScopeFixtures(users, staffRepository, departmentRepository, passwordEncoder);
        r = fx.department("R");
        d = fx.department("D");
        x = fx.department("X");
        y = fx.department("Y");
        Department closed = fx.department("C");
        sf.account("sw_admin", Role.ADMIN, true);
        sf.manager("sw_mr", r);
        sf.manager("sw_md", d);
        sf.manager("sw_mx", x);
        sf.account("sw_staff", Role.STAFF, true);
        sf.staffRecord("sw_staff", Role.STAFF, r);
        sf.account("sw_m_nostaff", Role.MANAGER, true);
        sf.account("sw_m_inactive_account", Role.MANAGER, false);
        sf.staffRecord("sw_m_inactive_account", Role.MANAGER, r);
        Staff inactive = sf.manager("sw_m_inactive_staff", r);
        inactive.setActive(false);
        staffRepository.saveAndFlush(inactive);
        sf.manager("sw_m_closed", closed);
        closed.setActive(false);
        departmentRepository.saveAndFlush(closed);

        taskA = fx.task("R loan task A", r, Priority.MEDIUM, 30, TaskStatus.ASSIGNED);
        recA = active(fx.eligible("Dora Donor A", d), d, r, taskA, "approver_a");
        taskB = fx.task("R loan task B", r, Priority.MEDIUM, 20, TaskStatus.ASSIGNED);
        recB = active(fx.eligible("Dora Donor B", d), d, r, taskB, "approver_b");
        taskU = fx.task("SECRET XY task", y, Priority.URGENT, 40, TaskStatus.ASSIGNED);
        recU = active(fx.eligible("Secret Xavier", x), x, y, taskU, "xy_approver");
        recC = ended(fx.eligible("Dina Done C", d), d, r, "R finished C", "approver_c");
        recC2 = ended(fx.eligible("Dina Done C2", d), d, r, "R finished C2", "approver_c2");
        recCU = ended(fx.eligible("Secret Xena", x), x, y, "SECRET XY finished", "xy_approver_done");
        clearInvocations(scopeResolver);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private SupportAssignment active(Staff person, Department home, Department receiving, Task task, String approver) {
        Assignment assignment = new Assignment();
        assignment.setTask(task);
        assignment.setStaff(person);
        assignment = assignmentRepository.saveAndFlush(assignment);
        return record(person, home, receiving, task, assignment, approver);
    }

    private SupportAssignment ended(Staff person, Department home, Department receiving, String title, String approver) {
        Task task = fx.task(title, receiving, Priority.LOW, 10, TaskStatus.COMPLETED);
        SupportAssignment support = record(person, home, receiving, task, null, approver);
        support.end(SupportStatus.COMPLETED, SupportEndReason.TASK_COMPLETED, LocalDateTime.now());
        return supportRepository.saveAndFlush(support);
    }

    private SupportAssignment record(Staff person, Department home, Department receiving, Task task, Assignment assignment, String approver) {
        SupportAssignment support = new SupportAssignment();
        support.setPlanId(UUID.randomUUID());
        support.setStaffId(person.getId());
        support.setStaffName(person.getName());
        support.setHomeDepartmentCode(home.getCode());
        support.setReceivingDepartmentCode(receiving.getCode());
        support.setTaskId(task.getId());
        support.setTaskTitle(task.getTitle());
        if (assignment != null) support.setAssignmentId(assignment.getId());
        support.setApprovedByUsername(approver);
        return supportRepository.saveAndFlush(support);
    }

    private MvcResult send(MockHttpServletRequestBuilder builder, String username, String role) throws Exception {
        clearInvocations(scopeResolver);
        if (username != null) builder = builder.with(user(username).roles(role));
        return mvc.perform(builder.contentType(MediaType.APPLICATION_JSON)).andReturn();
    }

    private MvcResult end(SupportAssignment record, String username, String role) throws Exception {
        return send(post("/api/support/assignments/" + record.getId() + "/end"), username, role);
    }

    private MvcResult end(UUID id, String username, String role) throws Exception {
        return send(post("/api/support/assignments/" + id + "/end"), username, role);
    }

    private MvcResult archive(SupportAssignment record, String username, String role) throws Exception {
        return send(post("/api/support/assignments/" + record.getId() + "/archive"), username, role);
    }

    private MvcResult archive(UUID id, String username, String role) throws Exception {
        return send(post("/api/support/assignments/" + id + "/archive"), username, role);
    }

    private MvcResult confirm(Object plan, String username, String role) throws Exception {
        return send(post("/api/support/assignments").content(mapper.writeValueAsString(plan)), username, role);
    }

    private JsonNode json(MvcResult result, int expectedStatus) throws Exception {
        assertEquals(expectedStatus, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    private SupportAssignment stored(SupportAssignment record) {
        return supportRepository.findById(record.getId()).orElseThrow();
    }

    private void signInAs(String username, String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(username, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private Map<String, Object> planFor(SupportTestFixture.Scenario scenario) {
        return Map.of("receivingDepartment", scenario.receiving.getCode(),
                "items", List.of(Map.of("staffId", scenario.donors.get(0).getId().toString(), "taskId", scenario.tasks.get(0).getId().toString())));
    }

    // ── confirm: Admin only ──────────────────────────────────────────────────

    @Test
    @DisplayName("Confirm: the Admin confirms a plan; a Manager (receiver, donor or any other) gets 403 and nothing is created")
    void confirmIsAdminOnly() throws Exception {
        var scenario = new SupportTestFixture(users, staffRepository, taskRepository, assignmentRepository, departmentRepository, supportRepository).overloaded(2, 3);
        ScopeFixtures sf = new ScopeFixtures(users, staffRepository, departmentRepository, passwordEncoder);
        sf.manager("sw_cm_recv", scenario.receiving);
        sf.manager("sw_cm_donor", scenario.donor);
        Map<String, Object> plan = planFor(scenario);
        long before = supportRepository.count();

        for (String manager : List.of("sw_cm_recv", "sw_cm_donor", "sw_mx")) {
            assertEquals(403, confirm(plan, manager, "MANAGER").getResponse().getStatus(), manager);
        }
        assertEquals(before, supportRepository.count(), "a refused confirmation creates nothing");

        JsonNode created = json(confirm(plan, "sw_admin", "ADMIN"), 201);
        assertEquals("ACTIVE", created.get(0).get("status").asText());
        assertEquals(before + 1, supportRepository.count());
    }

    @Test
    @DisplayName("Confirm: a direct service call by a Manager, invalid Manager, Staff or an anonymous caller is refused before anything is locked or created")
    void confirmDirectServiceCalls() {
        var scenario = new SupportTestFixture(users, staffRepository, taskRepository, assignmentRepository, departmentRepository, supportRepository).overloaded(2, 3);
        new ScopeFixtures(users, staffRepository, departmentRepository, passwordEncoder).manager("sw_cm_direct", scenario.receiving);
        SupportPlanRequest request = new SupportTestFixture(users, staffRepository, taskRepository, assignmentRepository, departmentRepository, supportRepository)
                .plan(scenario.receiving, scenario.donors.get(0), scenario.tasks.get(0));
        long before = supportRepository.count();

        for (String manager : List.of("sw_cm_direct", "sw_mr", "sw_m_nostaff", "sw_m_inactive_account", "sw_m_inactive_staff", "sw_m_closed")) {
            signInAs(manager, "MANAGER");
            assertEquals(403, assertThrows(ResponseStatusException.class, () -> supportService.confirm(request)).getStatusCode().value(), manager);
        }
        signInAs("sw_staff", "STAFF");
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> supportService.confirm(request)).getStatusCode().value());
        SecurityContextHolder.clearContext();
        assertEquals(401, assertThrows(ResponseStatusException.class, () -> supportService.confirm(request)).getStatusCode().value());
        assertEquals(before, supportRepository.count());

        signInAs("sw_admin", "ADMIN");
        assertEquals(1, supportService.confirm(request).size());
    }

    // ── end ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("End: the receiving Manager ends a record of their department; the response has the receiving view (no approver) and the task returns to waiting")
    void receivingManagerEnds() throws Exception {
        JsonNode result = json(end(recA, "sw_mr", "MANAGER"), 200);
        assertEquals("CANCELLED", result.get("status").asText());
        assertEquals("MANUALLY_ENDED", result.get("endReason").asText());
        assertEquals("R loan task A", result.get("taskTitle").asText());
        assertTrue(result.get("approvedBy").isNull());
        assertEquals(SupportStatus.CANCELLED, stored(recA).getStatus());
        assertEquals(TaskStatus.PENDING, taskRepository.findById(taskA.getId()).orElseThrow().getStatus(), "the task is back on its department waiting list");
        assertEquals(0, assignmentRepository.findAllByTaskId(taskA.getId()).size());
    }

    @Test
    @DisplayName("End: the donor Manager ends a record of their department; the response shows only the person and the loan, nothing of the receiving department")
    void donorManagerEnds() throws Exception {
        JsonNode result = json(end(recB, "sw_md", "MANAGER"), 200);
        assertEquals("Dora Donor B", result.get("staffName").asText());
        assertEquals("CANCELLED", result.get("status").asText());
        for (String field : List.of("receivingDepartmentCode", "taskId", "taskTitle", "assignmentId", "approvedBy", "endReason", "taskStatus", "planId", "reserveOverride")) {
            assertTrue(result.get(field).isNull(), field);
        }
        assertFalse(result.toString().contains(r.getCode()));
        assertFalse(result.toString().contains("R loan task B"));
        assertEquals(SupportStatus.CANCELLED, stored(recB).getStatus());
        assertEquals(TaskStatus.PENDING, taskRepository.findById(taskB.getId()).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("End: an unrelated Manager gets 404 for an existing record, identical to a record that does not exist, and nothing changes")
    void unrelatedManagerCannotEnd() throws Exception {
        MvcResult unrelated = end(recA, "sw_mx", "MANAGER");
        MvcResult missing = end(UUID.randomUUID(), "sw_mx", "MANAGER");
        assertEquals(404, unrelated.getResponse().getStatus());
        assertEquals(404, missing.getResponse().getStatus());
        assertEquals(missing.getResponse().getContentAsString(), unrelated.getResponse().getContentAsString(), "same body: existence is not revealed");
        assertFalse(unrelated.getResponse().getContentAsString().contains("R loan task A"));
        assertEquals(SupportStatus.ACTIVE, stored(recA).getStatus());
        assertEquals(TaskStatus.ASSIGNED, taskRepository.findById(taskA.getId()).orElseThrow().getStatus());

        // the receiving and donor Managers of OTHER pairs, too
        assertEquals(404, end(recU, "sw_mr", "MANAGER").getResponse().getStatus());
        assertEquals(404, end(recU, "sw_md", "MANAGER").getResponse().getStatus());
        assertEquals(SupportStatus.ACTIVE, stored(recU).getStatus());
        assertEquals(TaskStatus.ASSIGNED, taskRepository.findById(taskU.getId()).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("End: the Admin ends any record, including an unrelated pair, with the full response")
    void adminEndsAnything() throws Exception {
        JsonNode result = json(end(recU, "sw_admin", "ADMIN"), 200);
        assertEquals("xy_approver", result.get("approvedBy").asText());
        assertEquals("SECRET XY task", result.get("taskTitle").asText());
        assertEquals(SupportStatus.CANCELLED, stored(recU).getStatus());
    }

    @Test
    @DisplayName("End: a donor Manager told that the task is already in progress learns no task title or id; the receiving Manager does")
    void inProgressErrorLeaksNothingToTheDonor() throws Exception {
        Task task = taskRepository.findById(taskA.getId()).orElseThrow();
        task.setStatus(TaskStatus.IN_PROGRESS);
        taskRepository.saveAndFlush(task);

        MvcResult donor = end(recA, "sw_md", "MANAGER");
        assertEquals(409, donor.getResponse().getStatus());
        String text = donor.getResponse().getContentAsString();
        assertFalse(text.contains("R loan task A"), "no task title");
        assertFalse(text.contains(taskA.getId().toString()), "no task id");
        assertEquals("TASK_IN_PROGRESS", mapper.readTree(text).get("problems").get(0).get("code").asText());

        MvcResult receiver = end(recA, "sw_mr", "MANAGER");
        assertEquals(409, receiver.getResponse().getStatus());
        assertTrue(receiver.getResponse().getContentAsString().contains("R loan task A"), "their own department task is named");
        assertEquals(SupportStatus.ACTIVE, stored(recA).getStatus());
    }

    @Test
    @DisplayName("End: ending an already ended record is a clean 409 for the involved Manager, with no task id for a donor-only Manager")
    void alreadyEndedIsAConflict() throws Exception {
        assertEquals(200, end(recA, "sw_mr", "MANAGER").getResponse().getStatus());
        MvcResult again = end(recA, "sw_md", "MANAGER");
        assertEquals(409, again.getResponse().getStatus());
        JsonNode problem = mapper.readTree(again.getResponse().getContentAsString()).get("problems").get(0);
        assertEquals("ALREADY_ENDED", problem.get("code").asText());
        assertTrue(problem.get("taskId").isNull());
    }

    @Test
    @DisplayName("End: when the task finished meanwhile the record is closed with the real reason, and a donor Manager still gets only the donor view")
    void donorViewWhenTheTaskAlreadyFinished() throws Exception {
        Task task = taskRepository.findById(taskA.getId()).orElseThrow();
        task.setStatus(TaskStatus.COMPLETED);
        taskRepository.saveAndFlush(task);

        JsonNode donor = json(end(recA, "sw_md", "MANAGER"), 200);
        assertEquals("COMPLETED", donor.get("status").asText());
        for (String field : List.of("receivingDepartmentCode", "taskId", "taskTitle", "approvedBy", "endReason", "taskStatus")) assertTrue(donor.get(field).isNull(), field);
        assertEquals(SupportEndReason.TASK_COMPLETED, stored(recA).getEndReason(), "the real reason is still recorded");

        Task other = taskRepository.findById(taskB.getId()).orElseThrow();
        other.setStatus(TaskStatus.CANCELLED);
        taskRepository.saveAndFlush(other);
        JsonNode receiver = json(end(recB, "sw_mr", "MANAGER"), 200);
        assertEquals("R loan task B", receiver.get("taskTitle").asText());
        assertEquals("TASK_CANCELLED", receiver.get("endReason").asText());
        assertTrue(receiver.get("approvedBy").isNull());
    }

    // ── archive ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Archive: a receiving Manager and a donor Manager can each archive an ended record of their department")
    void involvedManagersArchive() throws Exception {
        JsonNode viaReceiver = json(archive(recC, "sw_mr", "MANAGER"), 200);
        assertFalse(viaReceiver.get("archivedAt").isNull());
        assertTrue(viaReceiver.get("approvedBy").isNull());
        assertEquals("R finished C", viaReceiver.get("taskTitle").asText());
        assertNotNull(stored(recC).getArchivedAt());

        JsonNode viaDonor = json(archive(recC2, "sw_md", "MANAGER"), 200);
        assertFalse(viaDonor.get("archivedAt").isNull());
        for (String field : List.of("receivingDepartmentCode", "taskId", "taskTitle", "approvedBy")) assertTrue(viaDonor.get(field).isNull(), field);
        assertNotNull(stored(recC2).getArchivedAt());
    }

    @Test
    @DisplayName("Archive: an unrelated record answers 404 exactly like one that does not exist, active or ended, and nothing changes")
    void unrelatedManagerCannotArchive() throws Exception {
        MvcResult unrelated = archive(recCU, "sw_mr", "MANAGER");
        MvcResult missing = archive(UUID.randomUUID(), "sw_mr", "MANAGER");
        assertEquals(404, unrelated.getResponse().getStatus());
        assertEquals(missing.getResponse().getContentAsString(), unrelated.getResponse().getContentAsString());
        assertFalse(unrelated.getResponse().getContentAsString().contains("SECRET XY"));
        assertNull(stored(recCU).getArchivedAt());

        // an ACTIVE record of another department is a 404 too, not a 409 that would reveal it exists and is active
        MvcResult activeUnrelated = archive(recU, "sw_mr", "MANAGER");
        assertEquals(404, activeUnrelated.getResponse().getStatus());
        assertEquals(missing.getResponse().getContentAsString(), activeUnrelated.getResponse().getContentAsString());
        assertEquals(404, archive(recC, "sw_mx", "MANAGER").getResponse().getStatus());
        assertNull(stored(recC).getArchivedAt());
    }

    @Test
    @DisplayName("Archive: an active involved record is a clean 409 (no task id for a donor-only Manager); the Admin archives any ended record")
    void archiveRulesAreUnchanged() throws Exception {
        MvcResult donor = archive(recA, "sw_md", "MANAGER");
        assertEquals(409, donor.getResponse().getStatus());
        JsonNode problem = mapper.readTree(donor.getResponse().getContentAsString()).get("problems").get(0);
        assertEquals("SUPPORT_ACTIVE", problem.get("code").asText());
        assertTrue(problem.get("taskId").isNull());
        assertEquals(409, archive(recA, "sw_mr", "MANAGER").getResponse().getStatus());

        assertEquals(200, archive(recCU, "sw_admin", "ADMIN").getResponse().getStatus());
        assertNotNull(stored(recCU).getArchivedAt());
        assertEquals(409, archive(recCU, "sw_admin", "ADMIN").getResponse().getStatus(), "already archived");
    }

    // ── refusals ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Invalid Managers get 403 on end and archive; Staff 403; no sign-in 401; nothing changes")
    void refusals() throws Exception {
        for (String manager : INVALID_MANAGERS) {
            assertEquals(403, end(recA, manager, "MANAGER").getResponse().getStatus(), manager);
            MvcResult result = archive(recC, manager, "MANAGER");
            assertEquals(403, result.getResponse().getStatus(), manager);
            assertFalse(result.getResponse().getContentAsString().contains("approver_"));
        }
        assertEquals(403, end(recA, "sw_staff", "STAFF").getResponse().getStatus());
        assertEquals(403, archive(recC, "sw_staff", "STAFF").getResponse().getStatus());
        assertEquals(401, end(recA, null, null).getResponse().getStatus());
        assertEquals(401, archive(recC, null, null).getResponse().getStatus());
        assertEquals(SupportStatus.ACTIVE, stored(recA).getStatus());
        assertNull(stored(recC).getArchivedAt());
    }

    // ── direct service calls and scope resolution ────────────────────────────

    @Test
    @DisplayName("Direct service calls to end and archive enforce the same rules without HTTP")
    void directServiceCalls() {
        signInAs("sw_mx", "MANAGER");
        assertEquals(404, assertThrows(SupportPlanException.class, () -> supportService.end(recA.getId())).getStatus().value());
        assertEquals(404, assertThrows(SupportPlanException.class, () -> supportService.archive(recC.getId())).getStatus().value());
        assertEquals(SupportStatus.ACTIVE, stored(recA).getStatus());
        assertNull(stored(recC).getArchivedAt());

        signInAs("sw_md", "MANAGER");
        assertEquals("CANCELLED", supportService.end(recA.getId()).status().name());
        assertNotNull(supportService.archive(recC.getId()).archivedAt());

        for (String manager : INVALID_MANAGERS) {
            signInAs(manager, "MANAGER");
            assertEquals(403, assertThrows(ResponseStatusException.class, () -> supportService.end(recB.getId())).getStatusCode().value(), manager);
            assertEquals(403, assertThrows(ResponseStatusException.class, () -> supportService.archive(recC2.getId())).getStatusCode().value(), manager);
        }
        signInAs("sw_staff", "STAFF");
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> supportService.end(recB.getId())).getStatusCode().value());
        SecurityContextHolder.clearContext();
        assertEquals(401, assertThrows(ResponseStatusException.class, () -> supportService.end(recB.getId())).getStatusCode().value());
        assertEquals(401, assertThrows(ResponseStatusException.class, () -> supportService.archive(recC2.getId())).getStatusCode().value());
        assertEquals(SupportStatus.ACTIVE, stored(recB).getStatus());

        signInAs("sw_admin", "ADMIN");
        assertEquals("CANCELLED", supportService.end(recU.getId()).status().name());
        assertNotNull(supportService.archive(recCU.getId()).archivedAt());
    }

    @Test
    @DisplayName("Each write request resolves the caller's scope exactly once")
    void scopeIsResolvedOnce() throws Exception {
        end(recA, "sw_mr", "MANAGER");
        verify(scopeResolver, times(1)).resolve();
        archive(recC, "sw_md", "MANAGER");
        verify(scopeResolver, times(1)).resolve();
        end(recU, "sw_admin", "ADMIN");
        verify(scopeResolver, times(1)).resolve();
        archive(recCU, "sw_admin", "ADMIN");
        verify(scopeResolver, times(1)).resolve();
    }

    // ── repository level ─────────────────────────────────────────────────────

    @Test
    @DisplayName("The scoped lookups find a record only through its own departments, and the id lookup returns the task only for them")
    void repositoryLookups() {
        assertTrue(supportRepository.lockByIdInvolvingDepartment(recA.getId(), r.getCode()).isPresent(), "receiver");
        assertTrue(supportRepository.lockByIdInvolvingDepartment(recA.getId(), d.getCode()).isPresent(), "donor");
        assertTrue(supportRepository.lockByIdInvolvingDepartment(recA.getId(), x.getCode()).isEmpty(), "unrelated");
        assertTrue(supportRepository.lockByIdInvolvingDepartment(UUID.randomUUID(), r.getCode()).isEmpty());
        assertEquals(taskA.getId(), supportRepository.findTaskIdByIdInvolvingDepartment(recA.getId(), d.getCode()).orElseThrow());
        assertTrue(supportRepository.findTaskIdByIdInvolvingDepartment(recA.getId(), y.getCode()).isEmpty());
        assertTrue(supportRepository.findTaskIdByIdInvolvingDepartment(recU.getId(), r.getCode()).isEmpty());
    }
}
