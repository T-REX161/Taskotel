package com.hotel.workload.notification;

import com.hotel.workload.assignment.Assignment;
import com.hotel.workload.assignment.AssignmentRepository;
import com.hotel.workload.security.Role;
import com.hotel.workload.security.ScopeFixtures;
import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
import com.hotel.workload.staff.Department;
import com.hotel.workload.staff.DepartmentRepository;
import com.hotel.workload.staff.ManagerViewGrant;
import com.hotel.workload.staff.ManagerViewGrantRepository;
import com.hotel.workload.staff.Staff;
import com.hotel.workload.staff.StaffRepository;
import com.hotel.workload.task.Priority;
import com.hotel.workload.task.Task;
import com.hotel.workload.task.TaskRepository;
import com.hotel.workload.task.TaskStatus;
import com.hotel.workload.verification.EmailSendingException;
import com.hotel.workload.verification.EmailService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * N4: workload alert emails, over HTTP with the real security chain and real transactions (an alert is evaluated only after the commit).
 * The delivery runs inline in these tests (taskotel.notifications.async=false); the asynchronous worker is covered in
 * WorkloadAlertAsyncTest. SendGrid is a mock; nothing is ever sent.
 *
 * <p>Fixture: HOME (the staff members' home department) with its Manager wa_mgr_home and a Manager wa_mgr_grant whose primary department
 * is OTHER but who holds an Administrator grant for HOME; RECV (a department that receives loan work) with its Manager wa_mgr_recv; and
 * wa_mgr_other, who has nothing to do with HOME. A task counts as "home work" for a person of HOME when its department is HOME.
 */
@SpringBootTest
public class WorkloadAlertIntegrationTest {

    private static final String HOME = "WA_HOME";
    private static final String RECV = "WA_RECV";
    private static final String OTHER = "WA_OTHER";
    private static final String[] USERS = {"wa_admin", "wa_mgr_home", "wa_mgr_grant", "wa_mgr_recv", "wa_mgr_other", "wa_s1", "wa_s2", "wa_s3"};

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private AssignmentRepository assignmentRepository;
    @Autowired private ManagerViewGrantRepository grantRepository;
    @Autowired private NotificationPreferenceRepository preferenceRepository;
    @Autowired private WorkloadAlertStateRepository stateRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private WorkloadAlertService alerts;
    @Autowired private PasswordEncoder passwordEncoder;
    @MockitoBean private EmailService emailService;

    private MockMvc mockMvc;
    private ScopeFixtures fixtures;
    private Department home;
    private Department recv;
    private User admin;
    private Staff s1;
    private Staff s2;
    private Staff s3;
    private Staff mgrHome;
    private final List<UUID> staffIds = new ArrayList<>();

    @BeforeEach
    public void setUp() {
        org.springframework.security.test.context.TestSecurityContextHolder.clearContext();
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        when(emailService.isConfigured()).thenReturn(true);
        fixtures = new ScopeFixtures(userRepository, staffRepository, departmentRepository, passwordEncoder);
        home = fixtures.department(HOME);
        recv = fixtures.department(RECV);
        Department other = fixtures.department(OTHER);
        admin = fixtures.account("wa_admin", Role.ADMIN, true);
        mgrHome = fixtures.manager("wa_mgr_home", home);
        Staff mgrGrant = fixtures.manager("wa_mgr_grant", other);
        fixtures.manager("wa_mgr_recv", recv);
        fixtures.manager("wa_mgr_other", other);
        s1 = fixtures.staff("wa_s1", home);
        s2 = fixtures.staff("wa_s2", home);
        s3 = fixtures.staff("wa_s3", home);
        ManagerViewGrant grant = new ManagerViewGrant();
        grant.setManager(mgrGrant.getUser());
        grant.setDepartment(home);
        grant.setGrantedByUserId(admin.getId());
        grant.setGrantedByUsername("wa_admin");
        grantRepository.saveAndFlush(grant);
        for (String name : USERS) verified(name, true); // every notification goes only to a verified address
        staffIds.addAll(List.of(s1.getId(), s2.getId(), s3.getId()));
        Mockito.clearInvocations(emailService);
    }

    @AfterEach
    public void cleanUp() {
        for (String name : USERS) {
            userRepository.findByUsername(name).ifPresent(u -> {
                grantRepository.findAll().stream().filter(g -> g.getManager().getId().equals(u.getId())).forEach(grantRepository::delete);
                preferenceRepository.deleteAll(preferenceRepository.findByUserId(u.getId()));
            });
        }
        grantRepository.flush();
        for (String code : List.of(HOME, RECV, OTHER)) {
            taskRepository.findAll().stream().filter(t -> t.getDepartment() != null && code.equals(t.getDepartment().getCode())).forEach(t -> {
                assignmentRepository.findAll().stream().filter(a -> a.getTask() != null && a.getTask().getId().equals(t.getId())).forEach(assignmentRepository::delete);
                assignmentRepository.flush();
                taskRepository.delete(t);
            });
            taskRepository.flush();
        }
        stateRepository.deleteAll(stateRepository.findAll().stream().filter(r -> staffIds.contains(r.getStaffId())).toList());
        fixtures.remove(USERS);
        for (String code : List.of(HOME, RECV, OTHER)) departmentRepository.findByCode(code).ifPresent(departmentRepository::delete);
        departmentRepository.flush();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────────────────

    private void verified(String username, boolean value) {
        User account = userRepository.findByUsername(username).orElseThrow();
        account.setEmailVerified(value);
        userRepository.saveAndFlush(account);
    }

    private Task task(String title, Department department, int minutes, TaskStatus status) {
        Task task = new Task();
        task.setTitle(title);
        task.setPriority(Priority.HIGH);
        task.setStatus(status);
        task.setEstimatedDuration(minutes);
        task.setDueDate(java.time.LocalDate.of(2027, 1, 15));
        task.setDepartment(department);
        task.setCreatedBy(admin);
        return taskRepository.saveAndFlush(task);
    }

    /** An assignment made directly in the database: it bypasses the application, so no alert state exists for it (work that predates N4). */
    private Task heldDirectly(String title, Department department, int minutes, Staff holder) {
        Task task = task(title, department, minutes, TaskStatus.ASSIGNED);
        Assignment assignment = new Assignment();
        assignment.setTask(task);
        assignment.setStaff(holder);
        assignmentRepository.saveAndFlush(assignment);
        return task;
    }

    private MvcResult call(MockHttpServletRequestBuilder request, String username, String role, String body) throws Exception {
        request = request.with(user(username).roles(role));
        if (body != null) request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        return mockMvc.perform(request).andReturn();
    }

    private MvcResult assign(String actor, String role, Task task, Staff... staff) throws Exception {
        String body = staff.length == 1
                ? "{\"taskId\":\"" + task.getId() + "\",\"staffId\":\"" + staff[0].getId() + "\"}"
                : "{\"taskId\":\"" + task.getId() + "\",\"staffIds\":[" + java.util.Arrays.stream(staff).map(s -> "\"" + s.getId() + "\"").collect(Collectors.joining(",")) + "]}";
        return call(post("/api/assignments"), actor, role, body);
    }

    private MvcResult edit(Task task, int minutes, TaskStatus status, String departmentCode) throws Exception {
        String body = "{\"title\":\"" + task.getTitle() + "\",\"priority\":\"HIGH\",\"estimatedDuration\":" + minutes + ",\"dueDate\":\"2027-01-15\",\"department\":\""
                + departmentCode + "\",\"status\":\"" + status.name() + "\"}";
        return call(put("/api/tasks/" + task.getId()), "wa_admin", "ADMIN", body);
    }

    private MvcResult edit(Task task, int minutes, TaskStatus status) throws Exception {
        return edit(task, minutes, status, task.getDepartment().getCode());
    }

    /** The workload alert emails only (the assignment and status emails of N1 and N2 are separate and are not what these tests count). */
    private List<String[]> sent() {
        return Mockito.mockingDetails(emailService).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("sendNotificationEmail"))
                .map(i -> new String[] {i.getArgument(0), i.getArgument(1), i.getArgument(2), i.getArgument(3)})
                .filter(m -> m[1].startsWith("Taskotel - Your workload is now") || m[1].startsWith("Taskotel - Workload alert:"))
                .collect(Collectors.toList());
    }

    private List<String> recipients() {
        List<String> to = new ArrayList<>();
        sent().forEach(m -> to.add(m[0]));
        to.sort(String::compareTo);
        return to;
    }

    private static String mail(String username) {
        return username + "@scope.test";
    }

    private static List<String> mails(String... usernames) {
        return java.util.Arrays.stream(usernames).map(WorkloadAlertIntegrationTest::mail).sorted().toList();
    }

    private String level(UUID staffId, String audience) {
        return stateRepository.findByStaffIdAndAudience(staffId, audience).map(WorkloadAlertState::getLevel).orElse("(none)");
    }

    private String[] emailTo(String username) {
        return sent().stream().filter(m -> m[0].equals(mail(username))).findFirst().orElseThrow(() -> new AssertionError("no email to " + username));
    }

    // ── thresholds, recovery and re-entry ───────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Threshold boundaries over real assignments: 120, 121 and 240 minutes do not alert; 241 and 360 alert HIGH; 361 alerts AT_RISK. Finishing the task recovers without an email, and the next rise alerts again")
    public void boundariesRecoveryAndReentry() throws Exception {
        int[] minutes = {120, 121, 240, 241, 360, 361};
        String[] expectedLevel = {null, null, null, "HIGH", "HIGH", "AT RISK"};
        for (int i = 0; i < minutes.length; i++) {
            Mockito.clearInvocations(emailService);
            Task task = task("Boundary " + minutes[i], home, minutes[i], TaskStatus.PENDING);
            MvcResult result = assign("wa_admin", "ADMIN", task, s1);
            assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
            if (expectedLevel[i] == null) {
                assertTrue(sent().isEmpty(), minutes[i] + " minutes must not alert, got " + recipients());
            } else {
                // the Staff member, and the two Managers responsible for HOME (primary department and active grant); nobody else
                assertEquals(mails("wa_s1", "wa_mgr_home", "wa_mgr_grant"), recipients(), minutes[i] + " minutes");
                assertEquals("Taskotel - Your workload is now " + expectedLevel[i], emailTo("wa_s1")[1]);
                assertTrue(emailTo("wa_mgr_home")[1].endsWith("is now " + expectedLevel[i]));
            }
            // finishing the task is a recovery: no email, and the stored levels follow
            Mockito.clearInvocations(emailService);
            assertEquals(200, edit(task, minutes[i], TaskStatus.COMPLETED).getResponse().getStatus());
            assertTrue(sent().isEmpty(), "recovery sends nothing");
            assertEquals("LOW", level(s1.getId(), "STAFF"));
            assertEquals("LOW", level(s1.getId(), "MANAGER"));
        }
    }

    @Test
    @DisplayName("LOW straight to AT_RISK sends one alert; HIGH to AT_RISK sends one; AT_RISK back to HIGH sends none, and rising again alerts again")
    public void escalationAndDeEscalation() throws Exception {
        Task task = task("Long job", home, 400, TaskStatus.PENDING);
        assertEquals(201, assign("wa_admin", "ADMIN", task, s1).getResponse().getStatus());
        assertEquals(3, sent().size(), "LOW -> AT_RISK: one alert per recipient, not one for HIGH and another for AT_RISK");
        assertTrue(emailTo("wa_s1")[1].endsWith("AT RISK"));

        Mockito.clearInvocations(emailService);
        assertEquals(200, edit(task, 380, TaskStatus.ASSIGNED).getResponse().getStatus());
        assertTrue(sent().isEmpty(), "still AT_RISK: nothing");
        assertEquals(200, edit(task, 300, TaskStatus.ASSIGNED).getResponse().getStatus());
        assertTrue(sent().isEmpty(), "AT_RISK -> HIGH: no email");
        assertEquals("HIGH", level(s1.getId(), "STAFF"));
        assertEquals(200, edit(task, 320, TaskStatus.IN_PROGRESS).getResponse().getStatus());
        assertTrue(sent().isEmpty(), "still HIGH (and a status change that does not move the minutes): nothing");

        assertEquals(200, edit(task, 400, TaskStatus.IN_PROGRESS).getResponse().getStatus());
        assertEquals(mails("wa_s1", "wa_mgr_home", "wa_mgr_grant"), recipients(), "HIGH -> AT_RISK alerts again");
        assertTrue(emailTo("wa_s1")[1].endsWith("AT RISK"));
    }

    @Test
    @DisplayName("Work that was already HIGH before this feature existed does not alert on its first change within the same level")
    public void existingHighWorkloadIsNotAFalseAlert() throws Exception {
        Task task = heldDirectly("Already heavy", home, 300, s1);
        assertTrue(stateRepository.findByStaffIdAndAudience(s1.getId(), "STAFF").isEmpty(), "no state yet");
        assertEquals(200, edit(task, 320, TaskStatus.ASSIGNED).getResponse().getStatus());
        assertTrue(sent().isEmpty(), "HIGH before and HIGH after is not a rise");
        assertEquals("HIGH", level(s1.getId(), "STAFF"), "the baseline was taken from the workload before the change");
        // and a real rise from there still alerts
        assertEquals(200, edit(task, 400, TaskStatus.ASSIGNED).getResponse().getStatus());
        assertEquals(3, sent().size());
    }

    // ── Staff global versus Manager department-scoped, and confidentiality ──────────────────────────────────────

    @Test
    @DisplayName("Loan work raises only the Staff member's GLOBAL level: the Staff member is told, no Manager is, and the email shows no department or task")
    public void loanWorkAlertsOnlyTheStaffMember() throws Exception {
        Task loan = heldDirectly("Secret RECV loan task", recv, 200, s1); // a task of another department, 200 minutes: global NORMAL
        assertEquals(200, edit(loan, 300, TaskStatus.ASSIGNED).getResponse().getStatus()); // now 300: global HIGH

        assertEquals(mails("wa_s1"), recipients(), "only the Staff member; Managers of HOME, of RECV and of anywhere else hear nothing");
        String text = emailTo("wa_s1")[2] + emailTo("wa_s1")[3];
        assertTrue(text.contains("300 minutes") && text.contains("Active tasks: 1"));
        assertFalse(text.contains("Secret RECV loan task") || text.contains(recv.getName()), "no task title or department");
        assertEquals("HIGH", level(s1.getId(), "STAFF"));
        assertEquals("LOW", level(s1.getId(), "MANAGER"), "the Manager view of HOME does not include loan work");
    }

    @Test
    @DisplayName("Home-department work raises the MANAGER level: the Managers are told, with the department's figures only, never the global total or the loan work")
    public void managersSeeOnlyTheDepartmentFigures() throws Exception {
        Task homeTask = heldDirectly("Home job", home, 200, s2);
        heldDirectly("Hidden loan job", recv, 200, s2); // legacy second active assignment: global = 400
        // 200 -> 250 on the home task: home level NORMAL -> HIGH (250); global stays AT_RISK (400 -> 450)
        assertEquals(200, edit(homeTask, 250, TaskStatus.ASSIGNED).getResponse().getStatus());

        assertEquals(mails("wa_mgr_home", "wa_mgr_grant"), recipients(), "the Managers of HOME only; the Staff member's global level did not rise");
        for (String manager : new String[] {"wa_mgr_home", "wa_mgr_grant"}) {
            String[] email = emailTo(manager);
            String all = email[1] + email[2] + email[3];
            assertTrue(email[2].contains("Active workload (department tasks): 250 minutes") && email[2].contains("Active tasks (department): 1"), email[2]);
            assertTrue(email[2].contains("Staff member: Scope wa_s2") && email[2].contains("Department: " + home.getName()));
            for (String forbidden : new String[] {"450", "400", "Hidden loan job", recv.getName(), "Home job"}) {
                assertFalse(all.contains(forbidden), "a Manager email must not contain: " + forbidden);
            }
        }
        assertEquals("HIGH", level(s2.getId(), "MANAGER"));
        assertEquals("AT_RISK", level(s2.getId(), "STAFF"));
    }

    @Test
    @DisplayName("A Manager of the department that receives the loan work is never told about the donor Staff member")
    public void receivingManagerIsNeverTold() throws Exception {
        Task loan = heldDirectly("Loan", recv, 100, s1);
        assertEquals(200, edit(loan, 400, TaskStatus.ASSIGNED).getResponse().getStatus());
        assertEquals(mails("wa_s1"), recipients());
        assertFalse(recipients().contains(mail("wa_mgr_recv")));
        assertFalse(recipients().contains(mail("wa_admin")), "Admins are never told");
    }

    // ── every workload-changing operation ───────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Multi-staff assignment: each person is evaluated, the Staff member is told once, and each Manager once per person")
    public void multiStaffAssignment() throws Exception {
        Task shared = task("Team job", home, 300, TaskStatus.PENDING);
        assertEquals(201, assign("wa_admin", "ADMIN", shared, s1, s2).getResponse().getStatus());
        List<String> to = recipients();
        assertEquals(6, to.size(), to.toString());
        assertEquals(1, to.stream().filter(a -> a.equals(mail("wa_s1"))).count());
        assertEquals(1, to.stream().filter(a -> a.equals(mail("wa_s2"))).count());
        assertEquals(2, to.stream().filter(a -> a.equals(mail("wa_mgr_home"))).count(), "one per affected person");
        assertEquals(2, to.stream().filter(a -> a.equals(mail("wa_mgr_grant"))).count());
    }

    @Test
    @DisplayName("Reassignment evaluates both people: the previous holder recovers silently, the new holder alerts")
    public void reassignment() throws Exception {
        Task task = task("Move me", home, 300, TaskStatus.PENDING);
        MvcResult first = assign("wa_admin", "ADMIN", task, s1);
        UUID assignmentId = UUID.fromString(new com.fasterxml.jackson.databind.ObjectMapper().readTree(first.getResponse().getContentAsString()).get("id").asText());
        Mockito.clearInvocations(emailService);

        MvcResult moved = call(put("/api/assignments/" + assignmentId), "wa_admin", "ADMIN", "{\"staffId\":\"" + s2.getId() + "\"}");
        assertEquals(200, moved.getResponse().getStatus(), moved.getResponse().getContentAsString());
        assertEquals(mails("wa_s2", "wa_mgr_home", "wa_mgr_grant"), recipients(), "the new holder and the Managers about the new holder; nothing about the previous holder");
        assertTrue(emailTo("wa_mgr_home")[2].contains("Scope wa_s2"));
        assertEquals("LOW", level(s1.getId(), "STAFF"), "the previous holder's level followed the change");
        assertEquals("HIGH", level(s2.getId(), "STAFF"));
    }

    @Test
    @DisplayName("Completion by the Staff member, cancellation and deletion are recoveries: no email, the level resets, and a later rise alerts again")
    public void completionCancellationAndDeletion() throws Exception {
        Task a = task("Finish me", home, 300, TaskStatus.PENDING);
        assertEquals(201, assign("wa_admin", "ADMIN", a, s1).getResponse().getStatus());
        Mockito.clearInvocations(emailService);
        assertEquals(200, call(patch("/api/tasks/" + a.getId() + "/status"), "wa_s1", "STAFF", "{\"status\":\"IN_PROGRESS\"}").getResponse().getStatus());
        assertEquals("HIGH", level(s1.getId(), "STAFF"), "moving to IN_PROGRESS does not change the minutes");
        assertEquals(200, call(patch("/api/tasks/" + a.getId() + "/status"), "wa_s1", "STAFF", "{\"status\":\"COMPLETED\"}").getResponse().getStatus());
        assertTrue(sent().isEmpty());
        assertEquals("LOW", level(s1.getId(), "STAFF"));

        Task b = task("Cancel me", home, 300, TaskStatus.PENDING);
        assertEquals(201, assign("wa_admin", "ADMIN", b, s1).getResponse().getStatus());
        assertEquals(3, sent().size(), "rising again after a recovery alerts again");
        Mockito.clearInvocations(emailService);
        assertEquals(200, edit(b, 300, TaskStatus.CANCELLED).getResponse().getStatus());
        assertTrue(sent().isEmpty());
        assertEquals("LOW", level(s1.getId(), "STAFF"));

        Task c = task("Delete me", home, 300, TaskStatus.PENDING);
        assertEquals(201, assign("wa_admin", "ADMIN", c, s1).getResponse().getStatus());
        Mockito.clearInvocations(emailService);
        assertEquals(204, call(delete("/api/tasks/" + c.getId()), "wa_admin", "ADMIN", null).getResponse().getStatus());
        assertTrue(sent().isEmpty());
        assertEquals("LOW", level(s1.getId(), "STAFF"), "the holder was evaluated before the assignments were deleted");
    }

    @Test
    @DisplayName("A task department change can raise the MANAGER level: moving a loan task into the holder's department makes it home work")
    public void taskDepartmentChange() throws Exception {
        Task loan = heldDirectly("Becomes home work", recv, 300, s3);
        // first a real change of the minutes that stays inside the same levels creates the baseline: global HIGH, home LOW
        assertEquals(200, edit(loan, 310, TaskStatus.ASSIGNED, RECV).getResponse().getStatus());
        assertTrue(sent().isEmpty());
        assertEquals("LOW", level(s3.getId(), "MANAGER"));

        assertEquals(200, edit(loan, 310, TaskStatus.ASSIGNED, HOME).getResponse().getStatus());
        assertEquals(mails("wa_mgr_home", "wa_mgr_grant"), recipients(), "only the Managers: the Staff member's global level did not change");
        assertEquals("HIGH", level(s3.getId(), "MANAGER"));
    }

    @Test
    @DisplayName("A department transfer of someone holding work in the target department evaluates them: the NEW department's Managers are told, the old one's are not")
    public void staffTransfer() throws Exception {
        Task loan = heldDirectly("Work in RECV", recv, 300, s3);
        assertEquals(200, edit(loan, 310, TaskStatus.ASSIGNED, RECV).getResponse().getStatus()); // baseline: global HIGH, home (HOME) LOW
        Mockito.clearInvocations(emailService);

        String body = "{\"fullName\":\"" + s3.getName() + "\",\"email\":\"" + s3.getEmail() + "\",\"phone\":\"555-0100\",\"department\":\"" + RECV
                + "\",\"availability\":\"AVAILABLE\",\"active\":true}";
        MvcResult result = call(put("/api/staff/" + s3.getId()), "wa_admin", "ADMIN", body);
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertEquals(mails("wa_mgr_recv"), recipients(), "the work is now home work of RECV, so RECV's Manager hears; HOME's Managers do not");
        assertTrue(emailTo("wa_mgr_recv")[2].contains("Department: " + recv.getName()));
    }

    // ── recipients, preferences, actor ───────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Eligibility: an unverified Staff member, a Manager with the preference off, an inactive Manager account and a revoked grant are each skipped, independently")
    public void eligibilityAndPreferences() throws Exception {
        verified("wa_s1", false);
        User prefOff = userRepository.findByUsername("wa_mgr_home").orElseThrow();
        preferenceRepository.saveAndFlush(new NotificationPreference(prefOff.getId(), NotificationCategory.WORKLOAD_ALERT, true, false));
        assertEquals(201, assign("wa_admin", "ADMIN", task("Eligibility", home, 300, TaskStatus.PENDING), s1).getResponse().getStatus());
        assertEquals(mails("wa_mgr_grant"), recipients(), "unverified Staff and the Manager with the preference off are skipped");

        Mockito.clearInvocations(emailService);
        verified("wa_s1", true);
        preferenceRepository.deleteAll(preferenceRepository.findByUserId(prefOff.getId()));
        User grantee = userRepository.findByUsername("wa_mgr_grant").orElseThrow();
        grantRepository.findAll().stream().filter(g -> g.getManager().getId().equals(grantee.getId())).forEach(grantRepository::delete);
        grantRepository.flush(); // revoked
        User inactive = userRepository.findByUsername("wa_mgr_home").orElseThrow();
        inactive.setActive(false);
        userRepository.saveAndFlush(inactive);
        Task second = task("Eligibility 2", home, 300, TaskStatus.PENDING);
        // the earlier assignment is still active for s1, so use s2 for the second alert
        assertEquals(201, assign("wa_admin", "ADMIN", second, s2).getResponse().getStatus());
        assertEquals(mails("wa_s2"), recipients(), "only the Staff member: the inactive Manager and the revoked grant receive nothing");
    }

    @Test
    @DisplayName("The person who made the change is not emailed: a Manager who assigns the work is excluded, the others are told")
    public void actorIsExcluded() throws Exception {
        assertEquals(201, assign("wa_mgr_home", "MANAGER", task("By a Manager", home, 300, TaskStatus.PENDING), s1).getResponse().getStatus());
        assertEquals(mails("wa_s1", "wa_mgr_grant"), recipients());
        assertFalse(recipients().contains(mail("wa_mgr_home")));
    }

    // ── transactions and failures ────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("A refused operation and a rolled-back transaction produce no alert and no state")
    public void rollbackProducesNothing() throws Exception {
        Task busy = task("Already holds", home, 100, TaskStatus.PENDING);
        assertEquals(201, assign("wa_admin", "ADMIN", busy, s1).getResponse().getStatus());
        Mockito.clearInvocations(emailService);
        assertEquals(409, assign("wa_admin", "ADMIN", task("Refused", home, 400, TaskStatus.PENDING), s1).getResponse().getStatus(), "s1 already holds work");
        assertTrue(sent().isEmpty());

        // a transaction that tracks a change and then rolls back: the evaluation never runs
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.executeWithoutResult(status -> {
            alerts.track(List.of(s3.getId()));
            status.setRollbackOnly();
        });
        assertThrows(IllegalStateException.class, () -> template.executeWithoutResult(status -> {
            alerts.track(List.of(s3.getId()));
            throw new IllegalStateException("the operation failed");
        }));
        assertTrue(sent().isEmpty());
        assertTrue(stateRepository.findByStaffIdAndAudience(s3.getId(), "STAFF").isEmpty(), "nothing was recorded");
    }

    @Test
    @DisplayName("An email failure never undoes the business operation, and the alert is not repeated afterwards: the level was already recorded")
    public void emailFailure() throws Exception {
        doThrow(new EmailSendingException("SendGrid is down")).when(emailService).sendNotificationEmail(anyString(), anyString(), anyString(), anyString());
        Task task = task("Despite failure", home, 300, TaskStatus.PENDING);
        MvcResult result = assign("wa_admin", "ADMIN", task, s1);
        assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertEquals(TaskStatus.ASSIGNED, taskRepository.findById(task.getId()).orElseThrow().getStatus());
        assertEquals("HIGH", level(s1.getId(), "STAFF"), "the state advanced before the email was attempted");

        Mockito.reset(emailService);
        when(emailService.isConfigured()).thenReturn(true);
        assertEquals(200, edit(task, 320, TaskStatus.ASSIGNED).getResponse().getStatus());
        assertTrue(sent().isEmpty(), "best effort: the lost alert is not repeated while the level is unchanged");
        assertEquals(200, edit(task, 400, TaskStatus.ASSIGNED).getResponse().getStatus());
        assertEquals(3, sent().size(), "the next real rise alerts normally");
    }

    @Test
    @DisplayName("Operations that cannot change the workload track nothing: a task that never had a holder, and edits of a finished task")
    public void noHookNoState() throws Exception {
        Task unassigned = task("Nobody holds it", home, 100, TaskStatus.PENDING);
        assertEquals(200, edit(unassigned, 400, TaskStatus.PENDING).getResponse().getStatus());
        Task finished = task("Done", home, 100, TaskStatus.COMPLETED);
        assertEquals(200, call(put("/api/tasks/" + finished.getId()), "wa_admin", "ADMIN",
                "{\"title\":\"Done\",\"priority\":\"HIGH\",\"estimatedDuration\":500,\"dueDate\":\"2027-01-15\",\"department\":\"" + HOME + "\",\"status\":\"COMPLETED\"}").getResponse().getStatus());
        assertTrue(sent().isEmpty());
        assertTrue(stateRepository.findAll().stream().noneMatch(r -> staffIds.contains(r.getStaffId())), "no state was created");
    }
}
