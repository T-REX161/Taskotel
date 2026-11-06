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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Manager department isolation for the support READ paths (Step 2G-2): options, preview and history, through the real security chain and the
 * real (H2) database, plus direct service calls and the repository queries. Every test rolls back. The write paths (confirm, end, archive) are
 * not covered here: they are Step 2G-3.
 *
 * <p>Fixture: R (receiving; busy Hana plus two waiting tasks), D (donor; two free eligible helpers), and an unrelated pair X (home) and Y.
 * Records: rec1 D lent a person to R (active); rec3 R lent a person to D (active); rec4 D lent to R, completed; rec5 an archived R record;
 * rec2 X lent a person to Y (unrelated to R and D). Managers mr (R), md (D), mx (X); four invalid Managers; one Staff; one Administrator.
 */
@SpringBootTest
@Transactional
public class SupportReadDepartmentIsolationTest {

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
    private SupportTestFixture.Scenario s;
    private Department x, y;
    private SupportAssignment rec1, rec2, rec3, rec4, rec5;
    private Staff donorHelper;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        fx = new SupportTestFixture(users, staffRepository, taskRepository, assignmentRepository, departmentRepository, supportRepository);
        ScopeFixtures sf = new ScopeFixtures(users, staffRepository, departmentRepository, passwordEncoder);
        s = fx.overloaded(2, 2);              // receiving R (busy Hana, two waiting tasks), donor D (two free helpers)
        donorHelper = s.donors.get(0);
        x = fx.department("X");
        y = fx.department("Y");
        Department closed = fx.department("C");

        sf.account("sr_admin", Role.ADMIN, true);
        sf.manager("sr_mr", s.receiving);
        sf.manager("sr_md", s.donor);
        sf.manager("sr_mx", x);
        sf.account("sr_staff", Role.STAFF, true);
        sf.staffRecord("sr_staff", Role.STAFF, s.receiving);
        sf.account("sr_m_nostaff", Role.MANAGER, true);
        sf.account("sr_m_inactive_account", Role.MANAGER, false);
        sf.staffRecord("sr_m_inactive_account", Role.MANAGER, s.receiving);
        Staff inactive = sf.manager("sr_m_inactive_staff", s.receiving);
        inactive.setActive(false);
        staffRepository.saveAndFlush(inactive);
        sf.manager("sr_m_closed", closed);
        closed.setActive(false);
        departmentRepository.saveAndFlush(closed);

        // Everyone in R who is not part of the scenario is unavailable, so R's two waiting tasks stay uncovered
        for (String name : List.of("sr_mr", "sr_staff", "sr_m_inactive_account")) {
            Staff member = staffRepository.findByUserUsername(name).orElseThrow();
            member.setAvailability(com.hotel.workload.staff.Availability.UNAVAILABLE);
            staffRepository.saveAndFlush(member);
        }
        // rec1: D's person "Dora Donor" is helping R with an R task (active)
        Staff dora = fx.eligible("Dora Donor", s.donor);
        Task rTask = fx.task("R loan task", s.receiving, Priority.MEDIUM, 30, TaskStatus.ASSIGNED);
        Assignment a1 = assign(rTask, dora);
        rec1 = record(dora, s.donor, s.receiving, rTask, a1, "approver_one");
        // rec3: R's person "Rhea Resident" is helping D with a D task (active)
        Staff rhea = fx.eligible("Rhea Resident", s.receiving);
        Task dTask = fx.task("D secret loan task", s.donor, Priority.MEDIUM, 25, TaskStatus.ASSIGNED);
        Assignment a3 = assign(dTask, rhea);
        rec3 = record(rhea, s.receiving, s.donor, dTask, a3, "approver_three");
        // rec2: unrelated X person helping Y
        Staff xavier = fx.eligible("Secret Xavier", x);
        Task yTask = fx.task("SECRET XY task", y, Priority.URGENT, 40, TaskStatus.ASSIGNED);
        Assignment a2 = assign(yTask, xavier);
        rec2 = record(xavier, x, y, yTask, a2, "xy_approver");
        // rec4: a COMPLETED record, D lent to R
        Staff dina = fx.eligible("Dina Done", s.donor);
        Task done = fx.task("R finished loan task", s.receiving, Priority.LOW, 10, TaskStatus.COMPLETED);
        rec4 = record(dina, s.donor, s.receiving, done, null, "approver_four");
        rec4.end(SupportStatus.COMPLETED, SupportEndReason.TASK_COMPLETED, LocalDateTime.now());
        supportRepository.saveAndFlush(rec4);
        // rec5: an archived record involving R (never listed)
        Staff arch = fx.eligible("Archie Archived", s.donor);
        Task archTask = fx.task("R archived task", s.receiving, Priority.LOW, 10, TaskStatus.COMPLETED);
        rec5 = record(arch, s.donor, s.receiving, archTask, null, "approver_five");
        rec5.end(SupportStatus.COMPLETED, SupportEndReason.TASK_COMPLETED, LocalDateTime.now());
        rec5.archive("approver_five", LocalDateTime.now());
        supportRepository.saveAndFlush(rec5);
        clearInvocations(scopeResolver);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Assignment assign(Task task, Staff staff) {
        Assignment assignment = new Assignment();
        assignment.setTask(task);
        assignment.setStaff(staff);
        return assignmentRepository.saveAndFlush(assignment);
    }

    private SupportAssignment record(Staff staff, Department home, Department receiving, Task task, Assignment assignment, String approver) {
        SupportAssignment support = new SupportAssignment();
        support.setPlanId(UUID.randomUUID());
        support.setStaffId(staff.getId());
        support.setStaffName(staff.getName());
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

    private MvcResult options(String code, String username, String role) throws Exception {
        return send(get("/api/support/options").param("receivingDepartment", code), username, role);
    }

    private MvcResult history(String username, String role, String status) throws Exception {
        MockHttpServletRequestBuilder b = get("/api/support/assignments");
        if (status != null) b = b.param("status", status);
        return send(b, username, role);
    }

    private MvcResult preview(Object plan, String username, String role) throws Exception {
        return send(post("/api/support/preview").content(mapper.writeValueAsString(plan)), username, role);
    }

    private Map<String, Object> plan(Department receiving, Staff staff, Task task) {
        return Map.of("receivingDepartment", receiving.getCode(),
                "items", List.of(Map.of("staffId", staff.getId().toString(), "taskId", task.getId().toString())));
    }

    private JsonNode body(MvcResult result, int expectedStatus) throws Exception {
        assertEquals(expectedStatus, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    private static JsonNode record(JsonNode list, UUID id) {
        for (JsonNode n : list) if (id.toString().equals(n.get("id").asText())) return n;
        return null;
    }

    private void signInAs(String username, String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(username, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private static final List<String> INVALID_MANAGERS = List.of("sr_m_nostaff", "sr_m_inactive_account", "sr_m_inactive_staff", "sr_m_closed");

    // ── options ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Admin: options for any department, with the full donor figures, unchanged")
    void adminOptionsAreGlobal() throws Exception {
        JsonNode forR = body(options(s.receiving.getCode(), "sr_admin", "ADMIN"), 200);
        JsonNode donor = null;
        for (JsonNode d : forR.get("donors")) if (s.donor.getCode().equals(d.get("code").asText())) donor = d;
        assertNotNull(donor);
        assertFalse(donor.get("freeStaff").isNull());
        assertFalse(donor.get("maxLendable").isNull());
        assertFalse(donor.get("canLend").isNull());
        assertFalse(donor.get("staff").get(0).get("activeTaskCount").isNull());
        assertEquals(200, options(s.donor.getCode(), "sr_admin", "ADMIN").getResponse().getStatus(), "any department");
    }

    @Test
    @DisplayName("Manager: options only for their own receiving department; the donor department's figures and workload are omitted")
    void managerOptionsOwnDepartmentOnly() throws Exception {
        JsonNode own = body(options(s.receiving.getCode(), "sr_mr", "MANAGER"), 200);
        assertEquals(s.receiving.getCode(), own.get("receiving").get("code").asText());
        assertEquals(2, own.get("tasks").size(), "the department's own waiting tasks remain");
        JsonNode donor = null;
        for (JsonNode d : own.get("donors")) if (s.donor.getCode().equals(d.get("code").asText())) donor = d;
        assertNotNull(donor, "who could help is still shown");
        for (String field : List.of("freeStaff", "availableStaff", "waitingTasks", "maxLendable", "overrideLendable", "canLend", "note")) {
            assertTrue(donor.get(field).isNull(), field + " is donor-department data");
        }
        assertEquals(4, donor.get("staff").size(), "the four free eligible people of the donor department");
        for (JsonNode person : donor.get("staff")) {
            assertFalse(person.get("staffId").isNull());
            assertFalse(person.get("name").isNull());
            assertTrue(person.get("activeTaskCount").isNull());
            assertTrue(person.get("totalEstimatedDuration").isNull());
            assertTrue(person.get("workloadStatus").isNull());
        }
        // nothing about the unrelated departments or the other departments' tasks and people
        String text = mapper.writeValueAsString(own);
        for (String forbidden : List.of(x.getCode(), y.getCode(), "SECRET XY", "Secret Xavier", "D secret loan task")) assertFalse(text.contains(forbidden), forbidden);
    }

    @Test
    @DisplayName("Manager: another department's options are refused exactly like a department that does not exist (404, same body)")
    void managerOptionsForAnotherDepartmentAre404() throws Exception {
        MvcResult other = options(s.donor.getCode(), "sr_mr", "MANAGER");
        MvcResult missing = options("NO_SUCH_DEPARTMENT", "sr_mr", "MANAGER");
        MvcResult unrelated = options(x.getCode(), "sr_mr", "MANAGER");
        assertEquals(404, other.getResponse().getStatus());
        assertEquals(404, missing.getResponse().getStatus());
        assertEquals(missing.getResponse().getContentAsString(), other.getResponse().getContentAsString());
        assertEquals(missing.getResponse().getContentAsString(), unrelated.getResponse().getContentAsString());
        // a request parameter cannot widen it
        MvcResult widened = send(get("/api/support/options").param("receivingDepartment", s.donor.getCode()).param("all", "true").param("admin", "true"), "sr_mr", "MANAGER");
        assertEquals(404, widened.getResponse().getStatus());
    }

    // ── preview ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Admin: preview for any department with donor impact figures, unchanged")
    void adminPreviewIsUnchanged() throws Exception {
        Task waiting = s.tasks.get(0);
        JsonNode preview = body(preview(plan(s.receiving, donorHelper, waiting), "sr_admin", "ADMIN"), 200);
        assertTrue(preview.get("valid").asBoolean(), preview.toString());
        assertEquals(1, preview.get("donors").size());
        assertFalse(preview.get("donors").get(0).get("before").isNull());
    }

    @Test
    @DisplayName("Manager: preview for their own receiving department works and keeps the valid pairs and own impact, but shows no donor impact")
    void managerPreviewOwnDepartment() throws Exception {
        Task waiting = s.tasks.get(0);
        JsonNode preview = body(preview(plan(s.receiving, donorHelper, waiting), "sr_mr", "MANAGER"), 200);
        assertTrue(preview.get("valid").asBoolean(), preview.toString());
        assertEquals(1, preview.get("items").size());
        assertEquals(donorHelper.getName(), preview.get("items").get(0).get("staffName").asText());
        assertEquals(s.receiving.getCode(), preview.get("receiving").get("code").asText());
        assertEquals(0, preview.get("donors").size(), "no donor department impact figures");
        assertFalse(preview.get("canOverrideReserve").asBoolean());
        assertFalse(preview.get("fingerprint").asText().isBlank(), "the stale-plan fingerprint is kept");
    }

    @Test
    @DisplayName("Manager: a preview that targets another receiving department is answered exactly like a department that does not exist")
    void managerPreviewForAnotherDepartment() throws Exception {
        MvcResult other = preview(plan(s.donor, donorHelper, s.tasks.get(0)), "sr_mr", "MANAGER");
        MvcResult missing = preview(Map.of("receivingDepartment", "NO_SUCH_DEPARTMENT",
                "items", List.of(Map.of("staffId", donorHelper.getId().toString(), "taskId", s.tasks.get(0).getId().toString()))), "sr_mr", "MANAGER");
        assertEquals(200, other.getResponse().getStatus());
        assertEquals(missing.getResponse().getContentAsString(), other.getResponse().getContentAsString());
        JsonNode node = mapper.readTree(other.getResponse().getContentAsString());
        assertFalse(node.get("valid").asBoolean());
        assertEquals("DEPARTMENT_NOT_FOUND", node.get("problems").get(0).get("code").asText());
        // extra payload fields trying to widen the scope are ignored, not trusted
        Map<String, Object> sneaky = new java.util.LinkedHashMap<>(plan(s.donor, donorHelper, s.tasks.get(0)));
        sneaky.put("admin", true);
        sneaky.put("department", s.donor.getCode());
        assertEquals(missing.getResponse().getContentAsString(), preview(sneaky, "sr_mr", "MANAGER").getResponse().getContentAsString());
    }

    @Test
    @DisplayName("Manager: problems never reveal another department's task title, an unrelated person, or the donor's staffing figures")
    void managerPreviewProblemsDoNotLeak() throws Exception {
        Task foreignTask = taskRepository.findAll().stream().filter(t -> "SECRET XY task".equals(t.getTitle())).findFirst().orElseThrow();
        Staff foreignPerson = staffRepository.findAll().stream().filter(p -> "Secret Xavier".equals(p.getName())).findFirst().orElseThrow();
        Map<String, Object> plan = Map.of("receivingDepartment", s.receiving.getCode(), "items", List.of(
                Map.of("staffId", foreignPerson.getId().toString(), "taskId", foreignTask.getId().toString())));
        MvcResult managerResult = preview(plan, "sr_mr", "MANAGER");
        String text = managerResult.getResponse().getContentAsString();
        assertFalse(text.contains("SECRET XY"), "another department's task title");
        assertFalse(text.contains("Secret Xavier"), "an unrelated person's name");
        JsonNode node = mapper.readTree(text);
        assertFalse(node.get("valid").asBoolean());
        Set<String> codes = new java.util.HashSet<>();
        for (JsonNode p : node.get("problems")) codes.add(p.get("code").asText());
        assertTrue(codes.contains("TASK_NOT_FOUND") && codes.contains("STAFF_NOT_FOUND"), codes.toString());
        // the Admin still gets the full explanation
        String adminText = preview(plan, "sr_admin", "ADMIN").getResponse().getContentAsString();
        assertTrue(adminText.contains("SECRET XY") && adminText.contains("Secret Xavier"));
    }

    @Test
    @DisplayName("Manager: the donor-reserve explanation carries no donor figures, while the Admin's still does")
    void managerReserveProblemIsGeneric() throws Exception {
        var tight = new SupportTestFixture(users, staffRepository, taskRepository, assignmentRepository, departmentRepository, supportRepository).overloaded(2, 1);          // the donor has exactly one free helper, which it must keep in reserve
        new ScopeFixtures(users, staffRepository, departmentRepository, passwordEncoder).manager("sr_mr_tight", tight.receiving);
        Map<String, Object> plan = plan(tight.receiving, tight.donors.get(0), tight.tasks.get(0));
        JsonNode managerNode = body(preview(plan, "sr_mr_tight", "MANAGER"), 200);
        assertEquals("DONOR_RESERVE", managerNode.get("problems").get(0).get("code").asText());
        String message = managerNode.get("problems").get(0).get("message").asText();
        assertFalse(message.contains(tight.donor.getName()), message);
        assertFalse(message.matches(".*\\d.*"), "no staffing figure: " + message);
        assertFalse(managerNode.get("canOverrideReserve").asBoolean());
        JsonNode adminNode = body(preview(plan, "sr_admin", "ADMIN"), 200);
        assertTrue(adminNode.get("problems").get(0).get("message").asText().contains(tight.donor.getName()));
        assertTrue(adminNode.get("canOverrideReserve").asBoolean());
    }

    // ── history ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Admin: the whole history, unchanged, including approver usernames")
    void adminHistoryIsGlobal() throws Exception {
        JsonNode list = body(history("sr_admin", "ADMIN", null), 200);
        for (SupportAssignment r : List.of(rec1, rec2, rec3, rec4)) assertNotNull(record(list, r.getId()), r.getTaskTitle());
        assertNull(record(list, rec5.getId()), "archived records stay unlisted");
        assertEquals("xy_approver", record(list, rec2.getId()).get("approvedBy").asText());
        assertEquals("SECRET XY task", record(list, rec2.getId()).get("taskTitle").asText());
    }

    @Test
    @DisplayName("Manager history: records where the department is donor or receiver, and none of the unrelated ones")
    void managerHistoryIsInvolvingOnly() throws Exception {
        JsonNode forR = body(history("sr_mr", "MANAGER", null), 200);
        Set<String> ids = new java.util.HashSet<>();
        for (JsonNode n : forR) ids.add(n.get("id").asText());
        assertEquals(Set.of(rec1.getId().toString(), rec3.getId().toString(), rec4.getId().toString()), ids, "R received rec1 and rec4 and lent for rec3");
        String text = forR.toString();
        for (String forbidden : List.of("SECRET XY", "Secret Xavier", "xy_approver", x.getCode(), y.getCode(), "approver_one", "approver_three", "approver_four", "approver_five", "Archie")) {
            assertFalse(text.contains(forbidden), forbidden);
        }
        JsonNode forX = body(history("sr_mx", "MANAGER", null), 200);
        assertEquals(1, forX.size());
        assertEquals(rec2.getId().toString(), forX.get(0).get("id").asText());
        // the status filter is applied in the same department-filtered query
        JsonNode completed = body(history("sr_mr", "MANAGER", "COMPLETED"), 200);
        assertEquals(1, completed.size());
        assertEquals(rec4.getId().toString(), completed.get(0).get("id").asText());
        assertEquals(2, body(history("sr_mr", "MANAGER", "ACTIVE"), 200).size());
    }

    @Test
    @DisplayName("Receiving Manager: sees the support their department received (person, home department, task, status) but never the approver")
    void receivingManagerView() throws Exception {
        JsonNode r = record(body(history("sr_mr", "MANAGER", null), 200), rec1.getId());
        assertEquals("Dora Donor", r.get("staffName").asText());
        assertEquals(s.donor.getCode(), r.get("homeDepartmentCode").asText());
        assertEquals(s.receiving.getCode(), r.get("receivingDepartmentCode").asText());
        assertEquals("R loan task", r.get("taskTitle").asText());
        assertEquals("ACTIVE", r.get("status").asText());
        assertTrue(r.get("approvedBy").isNull());
        assertTrue(r.get("reserveOverride").isNull());
    }

    @Test
    @DisplayName("Donor Manager: sees only the lent person's name and that they are on a temporary loan; no receiving department, task, plan, approver or reason")
    void donorManagerView() throws Exception {
        JsonNode list = body(history("sr_mr", "MANAGER", null), 200);
        JsonNode r = record(list, rec3.getId());        // R lent Rhea to D: R is the donor
        assertEquals("Rhea Resident", r.get("staffName").asText());
        assertEquals("ACTIVE", r.get("status").asText(), "temporarily on loan");
        assertEquals(s.receiving.getCode(), r.get("homeDepartmentCode").asText(), "their own department");
        for (String field : List.of("receivingDepartmentCode", "taskId", "taskTitle", "assignmentId", "approvedBy", "endReason", "taskStatus", "planId", "reserveOverride", "archivedAt")) {
            assertTrue(r.get(field).isNull(), field + " must not reach a donor Manager");
        }
        assertFalse(r.toString().contains(s.donor.getCode()), "the receiving department's code");
        assertFalse(r.toString().contains("D secret loan task"));
        // and the same for the other direction: D's Manager is the donor of rec1 and sees no R task detail for it
        JsonNode forD = body(history("sr_md", "MANAGER", null), 200);
        JsonNode seenByD = record(forD, rec1.getId());
        assertEquals("Dora Donor", seenByD.get("staffName").asText());
        assertTrue(seenByD.get("taskTitle").isNull());
        assertTrue(seenByD.get("receivingDepartmentCode").isNull());
        assertFalse(forD.toString().contains("R loan task"));
        assertFalse(forD.toString().contains("approver_"));
    }

    // ── refusals ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Invalid Managers get 403 on options, preview and history: inactive account, no Staff record, inactive Staff record, inactive department")
    void invalidManagersAreRefused() throws Exception {
        for (String manager : INVALID_MANAGERS) {
            assertEquals(403, options(s.receiving.getCode(), manager, "MANAGER").getResponse().getStatus(), manager);
            assertEquals(403, preview(plan(s.receiving, donorHelper, s.tasks.get(0)), manager, "MANAGER").getResponse().getStatus(), manager);
            MvcResult result = history(manager, "MANAGER", null);
            assertEquals(403, result.getResponse().getStatus(), manager);
            assertFalse(result.getResponse().getContentAsString().contains("approver_"), manager);
        }
    }

    @Test
    @DisplayName("Staff get 403 and no sign-in gets 401 on options, preview and history")
    void staffAndAnonymousAreRefused() throws Exception {
        assertEquals(403, options(s.receiving.getCode(), "sr_staff", "STAFF").getResponse().getStatus());
        assertEquals(403, preview(plan(s.receiving, donorHelper, s.tasks.get(0)), "sr_staff", "STAFF").getResponse().getStatus());
        assertEquals(403, history("sr_staff", "STAFF", null).getResponse().getStatus());
        assertEquals(401, options(s.receiving.getCode(), null, null).getResponse().getStatus());
        assertEquals(401, preview(plan(s.receiving, donorHelper, s.tasks.get(0)), null, null).getResponse().getStatus());
        assertEquals(401, history(null, null, null).getResponse().getStatus());
    }

    // ── direct service calls and scope resolution ────────────────────────────

    @Test
    @DisplayName("Direct service calls enforce the same rules without HTTP")
    void directServiceCalls() {
        SupportPlanRequest ownPlan = request(s.receiving);
        SupportPlanRequest otherPlan = request(s.donor);

        signInAs("sr_mr", "MANAGER");
        assertNotNull(supportService.getOptions(s.receiving.getCode()));
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> supportService.getOptions(s.donor.getCode())).getStatusCode().value());
        assertTrue(supportService.preview(ownPlan).donors().isEmpty());
        assertEquals("DEPARTMENT_NOT_FOUND", supportService.preview(otherPlan).problems().get(0).code());
        List<SupportDtos.SupportAssignmentDto> mine = supportService.list(null);
        assertEquals(3, mine.size());
        assertTrue(mine.stream().allMatch(d -> d.approvedBy() == null));

        signInAs("sr_admin", "ADMIN");
        assertEquals(4, supportService.list(null).size());
        assertNotNull(supportService.getOptions(s.donor.getCode()));

        for (String manager : INVALID_MANAGERS) {
            signInAs(manager, "MANAGER");
            assertEquals(403, assertThrows(ResponseStatusException.class, () -> supportService.list(null)).getStatusCode().value(), manager);
            assertEquals(403, assertThrows(ResponseStatusException.class, () -> supportService.getOptions(s.receiving.getCode())).getStatusCode().value(), manager);
            assertEquals(403, assertThrows(ResponseStatusException.class, () -> supportService.preview(ownPlan)).getStatusCode().value(), manager);
        }
        signInAs("sr_staff", "STAFF");
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> supportService.list(null)).getStatusCode().value());
        SecurityContextHolder.clearContext();
        assertEquals(401, assertThrows(ResponseStatusException.class, () -> supportService.list(null)).getStatusCode().value());
    }

    private SupportPlanRequest request(Department receiving) {
        SupportPlanRequest request = new SupportPlanRequest();
        request.setReceivingDepartment(receiving.getCode());
        request.setItems(List.of(new SupportPlanRequest.Item(donorHelper.getId(), s.tasks.get(0).getId())));
        return request;
    }

    @Test
    @DisplayName("Each read request resolves the caller's scope exactly once, for Admin and Manager")
    void scopeIsResolvedOnce() throws Exception {
        for (String[] caller : new String[][] {{"sr_admin", "ADMIN"}, {"sr_mr", "MANAGER"}}) {
            options(s.receiving.getCode(), caller[0], caller[1]);
            verify(scopeResolver, times(1)).resolve();
            preview(plan(s.receiving, donorHelper, s.tasks.get(0)), caller[0], caller[1]);
            verify(scopeResolver, times(1)).resolve();
            history(caller[0], caller[1], null);
            verify(scopeResolver, times(1)).resolve();
        }
    }

    // ── reconciliation scope (Step 2G-2 review) ──────────────────────────────

    private void setTaskStatus(UUID taskId, TaskStatus status) {
        Task task = taskRepository.findById(taskId).orElseThrow();
        task.setStatus(status);
        taskRepository.saveAndFlush(task);
    }

    @Test
    @DisplayName("A Manager reading the history cannot change an unrelated department support record; their own department records are still reconciled")
    void managerReadDoesNotReconcileUnrelatedRecords() throws Exception {
        setTaskStatus(rec2.getTaskId(), TaskStatus.COMPLETED);   // unrelated (X lent to Y): its task is finished, so Admin reconciliation would end it
        setTaskStatus(rec1.getTaskId(), TaskStatus.COMPLETED);   // involves R: its task is finished too

        body(history("sr_mr", "MANAGER", null), 200);

        assertEquals(SupportStatus.ACTIVE, supportRepository.findById(rec2.getId()).orElseThrow().getStatus(), "another department record is untouched");
        assertNull(supportRepository.findById(rec2.getId()).orElseThrow().getCompletedAt());
        SupportAssignment own = supportRepository.findById(rec1.getId()).orElseThrow();
        assertEquals(SupportStatus.COMPLETED, own.getStatus(), "the Manager own department record still ends automatically");
        assertEquals(SupportEndReason.TASK_COMPLETED, own.getEndReason());

        // the department that does own the record reconciles it on its own read
        JsonNode forX = body(history("sr_mx", "MANAGER", null), 200);
        assertEquals("COMPLETED", record(forX, rec2.getId()).get("status").asText());
    }

    @Test
    @DisplayName("A Manager reading the history does not end an unrelated record whose task was cancelled; an Admin read still reconciles everything")
    void adminReadStillReconcilesEverything() throws Exception {
        setTaskStatus(rec2.getTaskId(), TaskStatus.CANCELLED);
        body(history("sr_md", "MANAGER", null), 200);       // D is neither X nor Y
        assertEquals(SupportStatus.ACTIVE, supportRepository.findById(rec2.getId()).orElseThrow().getStatus());

        body(history("sr_admin", "ADMIN", null), 200);       // the Admin read is unchanged: it reconciles every active record
        SupportAssignment ended = supportRepository.findById(rec2.getId()).orElseThrow();
        assertEquals(SupportStatus.CANCELLED, ended.getStatus());
        assertEquals(SupportEndReason.TASK_CANCELLED, ended.getEndReason());
    }

    // ── the narrowed load must not change the support calculation ────────────

    @Test
    @DisplayName("A Manager narrowed options carry the same receiving figures, tasks, donors and helpers as the Admin full ones (support loans included)")
    void narrowedOptionsMatchTheFullCalculation() throws Exception {
        JsonNode admin = body(options(s.receiving.getCode(), "sr_admin", "ADMIN"), 200);
        JsonNode manager = body(options(s.receiving.getCode(), "sr_mr", "MANAGER"), 200);
        assertEquals(admin.get("receiving"), manager.get("receiving"), "severity, pressure counts and the receiving department workload figures");
        assertEquals(admin.get("tasks"), manager.get("tasks"));
        assertEquals(admin.get("maxHelpers"), manager.get("maxHelpers"));
        assertEquals(admin.get("limitations"), manager.get("limitations"));
        assertEquals(admin.get("donors").size(), manager.get("donors").size());
        for (int i = 0; i < admin.get("donors").size(); i++) {
            JsonNode a = admin.get("donors").get(i);
            JsonNode m = manager.get("donors").get(i);
            assertEquals(a.get("code"), m.get("code"));
            assertEquals(a.get("name"), m.get("name"));
            assertEquals(a.get("staff").size(), m.get("staff").size());
            for (int j = 0; j < a.get("staff").size(); j++) {
                assertEquals(a.get("staff").get(j).get("staffId"), m.get("staff").get(j).get("staffId"));
                assertEquals(a.get("staff").get(j).get("name"), m.get("staff").get(j).get("name"));
            }
        }
        // the assigned minutes use the support attribution: R own 40 and Dora 30 on an R task count under R; Rhea 25 for D does not
        assertEquals(70, manager.get("receiving").get("metrics").get("assignedMinutes").asInt());
    }

    @Test
    @DisplayName("A Manager narrowed preview gives the same verdicts, items, own impact and stale-plan fingerprint as the Admin, for valid and blocked plans")
    void narrowedPreviewMatchesTheFullCalculation() throws Exception {
        Map<String, Object> valid = plan(s.receiving, donorHelper, s.tasks.get(0));
        JsonNode admin = body(preview(valid, "sr_admin", "ADMIN"), 200);
        JsonNode manager = body(preview(valid, "sr_mr", "MANAGER"), 200);
        assertEquals(admin.get("valid"), manager.get("valid"));
        assertEquals(admin.get("problems"), manager.get("problems"));
        assertEquals(admin.get("items"), manager.get("items"));
        assertEquals(admin.get("receiving"), manager.get("receiving"));
        assertEquals(admin.get("fingerprint"), manager.get("fingerprint"), "the same plan has the same fingerprint, whichever load produced it");

        // blocked plans: a busy person and a task that is already held
        Staff busy = staffRepository.findAll().stream().filter(p -> "Dora Donor".equals(p.getName())).findFirst().orElseThrow();
        Task held = taskRepository.findById(rec1.getTaskId()).orElseThrow();
        Map<String, Object> blocked = Map.of("receivingDepartment", s.receiving.getCode(), "items", List.of(
                Map.of("staffId", busy.getId().toString(), "taskId", s.tasks.get(1).getId().toString()),
                Map.of("staffId", donorHelper.getId().toString(), "taskId", held.getId().toString())));
        JsonNode adminBlocked = body(preview(blocked, "sr_admin", "ADMIN"), 200);
        JsonNode managerBlocked = body(preview(blocked, "sr_mr", "MANAGER"), 200);
        assertFalse(managerBlocked.get("valid").asBoolean());
        assertEquals(adminBlocked.get("valid"), managerBlocked.get("valid"));
        List<String> adminCodes = new ArrayList<>();
        List<String> managerCodes = new ArrayList<>();
        adminBlocked.get("problems").forEach(p -> adminCodes.add(p.get("code").asText()));
        managerBlocked.get("problems").forEach(p -> managerCodes.add(p.get("code").asText()));
        assertEquals(List.of("STAFF_BUSY", "TASK_ALREADY_ASSIGNED"), adminCodes, "the full calculation finds the busy person and the held task");
        // The narrowed load reaches the same verdicts; a busy person is not a possible helper, so the Manager's response hides who they are
        assertEquals(List.of("STAFF_NOT_FOUND", "TASK_ALREADY_ASSIGNED"), managerCodes);
        assertEquals(adminBlocked.get("fingerprint"), managerBlocked.get("fingerprint"));
    }

    @Test
    @DisplayName("The donor reserve rule gives the same verdict from the narrowed load (a donor with one free helper cannot lend)")
    void narrowedReserveRuleMatches() throws Exception {
        var tight = new SupportTestFixture(users, staffRepository, taskRepository, assignmentRepository, departmentRepository, supportRepository).overloaded(2, 1);
        new ScopeFixtures(users, staffRepository, departmentRepository, passwordEncoder).manager("sr_mr_tight2", tight.receiving);
        Map<String, Object> plan = plan(tight.receiving, tight.donors.get(0), tight.tasks.get(0));
        JsonNode admin = body(preview(plan, "sr_admin", "ADMIN"), 200);
        JsonNode manager = body(preview(plan, "sr_mr_tight2", "MANAGER"), 200);
        assertEquals("DONOR_RESERVE", admin.get("problems").get(0).get("code").asText());
        assertEquals("DONOR_RESERVE", manager.get("problems").get(0).get("code").asText());
        assertEquals(admin.get("fingerprint"), manager.get("fingerprint"));
        JsonNode adminOptions = body(options(tight.receiving.getCode(), "sr_admin", "ADMIN"), 200);
        JsonNode managerOptions = body(options(tight.receiving.getCode(), "sr_mr_tight2", "MANAGER"), 200);
        assertEquals(adminOptions.get("donors").size(), managerOptions.get("donors").size());
        assertEquals(adminOptions.get("receiving"), managerOptions.get("receiving"));
    }

    @Test
    @DisplayName("The narrowed load counts a donor department's own waiting tasks in the reserve rule, like the full load")
    void narrowedLoadAppliesTheDonorWaitingWorkRule() throws Exception {
        var shared = new SupportTestFixture(users, staffRepository, taskRepository, assignmentRepository, departmentRepository, supportRepository).overloaded(2, 2);
        new ScopeFixtures(users, staffRepository, departmentRepository, passwordEncoder).manager("sr_mr_waiting", shared.receiving);
        // the donor has two free helpers but also two tasks of its own waiting for someone: lending one would leave it unable to cover them
        fx.task("donor own waiting 1", shared.donor, Priority.MEDIUM, 20, TaskStatus.PENDING);
        fx.task("donor own waiting 2", shared.donor, Priority.MEDIUM, 20, TaskStatus.PENDING);
        Map<String, Object> plan = plan(shared.receiving, shared.donors.get(0), shared.tasks.get(0));
        JsonNode admin = body(preview(plan, "sr_admin", "ADMIN"), 200);
        JsonNode manager = body(preview(plan, "sr_mr_waiting", "MANAGER"), 200);
        assertFalse(admin.get("valid").asBoolean());
        assertEquals("DONOR_RESERVE", admin.get("problems").get(0).get("code").asText());
        assertEquals("DONOR_RESERVE", manager.get("problems").get(0).get("code").asText());
        assertEquals(admin.get("fingerprint"), manager.get("fingerprint"), "the donor's waiting count is part of the fingerprint");
    }

    @Test
    @DisplayName("The narrowed load knows a task is held even when its status is still PENDING, like the full load")
    void narrowedLoadKnowsHeldTasks() throws Exception {
        Task pendingButHeld = fx.task("R pending but held", s.receiving, Priority.MEDIUM, 15, TaskStatus.PENDING);
        Staff holder = staffRepository.findAll().stream().filter(p -> "Rhea Resident".equals(p.getName())).findFirst().orElseThrow();
        assign(pendingButHeld, holder);                       // an assignment exists although the task status is PENDING
        Map<String, Object> plan = plan(s.receiving, donorHelper, pendingButHeld);
        JsonNode admin = body(preview(plan, "sr_admin", "ADMIN"), 200);
        JsonNode manager = body(preview(plan, "sr_mr", "MANAGER"), 200);
        assertEquals("TASK_ALREADY_ASSIGNED", admin.get("problems").get(0).get("code").asText());
        assertEquals("TASK_ALREADY_ASSIGNED", manager.get("problems").get(0).get("code").asText());
        assertEquals(admin.get("fingerprint"), manager.get("fingerprint"));
    }

    // ── repository level ─────────────────────────────────────────────────────

    @Test
    @DisplayName("The history queries filter in the database: donor or receiver, not archived, newest first, with the status filter")
    void repositoryQueries() {
        Set<UUID> all = supportRepository.findHistoryInvolvingDepartment(s.receiving.getCode()).stream().map(SupportAssignment::getId).collect(Collectors.toSet());
        assertEquals(Set.of(rec1.getId(), rec3.getId(), rec4.getId()), all);
        assertEquals(Set.of(rec1.getId(), rec3.getId()), supportRepository.findHistoryInvolvingDepartmentAndStatus(s.receiving.getCode(), SupportStatus.ACTIVE)
                .stream().map(SupportAssignment::getId).collect(Collectors.toSet()));
        assertEquals(List.of(rec2.getId()), supportRepository.findHistoryInvolvingDepartment(x.getCode()).stream().map(SupportAssignment::getId).toList());
        assertEquals(List.of(rec2.getId()), supportRepository.findHistoryInvolvingDepartment(y.getCode()).stream().map(SupportAssignment::getId).toList());
        assertTrue(supportRepository.findHistoryInvolvingDepartment("NO_SUCH").isEmpty());
        List<LocalDateTime> times = new ArrayList<>();
        supportRepository.findHistoryInvolvingDepartment(s.receiving.getCode()).forEach(r -> times.add(r.getCreatedAt()));
        for (int i = 1; i < times.size(); i++) assertFalse(times.get(i).isAfter(times.get(i - 1)), "newest first");
    }

    @Test
    @DisplayName("The narrowing queries: held task ids of one department, the support-attributed minutes aggregate, and the staff lookups")
    void narrowingQueries() {
        List<TaskStatus> active = List.of(TaskStatus.PENDING, TaskStatus.ASSIGNED, TaskStatus.IN_PROGRESS);
        List<UUID> held = assignmentRepository.findHeldTaskIdsByDepartment(s.receiving, active);
        assertTrue(held.contains(rec1.getTaskId()), "R task held by the lent person");
        assertFalse(held.contains(rec3.getTaskId()), "a D task");
        assertFalse(held.contains(rec2.getTaskId()), "a Y task");
        assertEquals(70, assignmentRepository.sumSupportAttributedMinutes(s.receiving, s.receiving.getCode(), active), "R own 40 plus the 30 minute task Dora does for R; Rhea loan to D is D");
        assertEquals(25, assignmentRepository.sumSupportAttributedMinutes(s.donor, s.donor.getCode(), active), "Rhea 25 minute D task; Dora work for R counts under R");
        assertEquals(40, assignmentRepository.sumSupportAttributedMinutes(y, y.getCode(), active), "Xavier loan counts under Y, not X");
        assertEquals(0, assignmentRepository.sumSupportAttributedMinutes(x, x.getCode(), active));
        assertTrue(staffRepository.findActiveWithUserByDepartment(s.receiving).stream().allMatch(p -> p.getDepartment().getId().equals(s.receiving.getId()) && p.isActive()));
        assertTrue(staffRepository.findActiveWithUserByAvailability(com.hotel.workload.staff.Availability.AVAILABLE).stream()
                .allMatch(p -> p.isActive() && p.getAvailability() == com.hotel.workload.staff.Availability.AVAILABLE));
        assertTrue(supportRepository.findByStatusInvolvingDepartment(SupportStatus.ACTIVE, x.getCode()).stream().allMatch(r -> r.getId().equals(rec2.getId())));
        assertEquals(2, supportRepository.findByStatusInvolvingDepartment(SupportStatus.ACTIVE, s.receiving.getCode()).size());
    }
}
