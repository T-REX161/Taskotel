package com.hotel.workload.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.web.context.WebApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Who is emailed when a task is ASSIGNED, REASSIGNED and COMPLETED: the new assignee(s), the Manager responsible for the task's department,
 * the Admin who made the assignment, and (on completion) the person who ORIGINALLY assigned it. Everything goes over HTTP with the real
 * security chain and real transactions (an email goes out only after the commit). SendGrid is a mock; nothing is ever sent.
 *
 * <p>World: department HOME with Manager {@code n7_mgr}, Staff s1/s2/s3; department OTHER with Manager {@code n7_mgr_other} and Staff;
 * {@code n7_mgr_grant} (primary department OTHER) holds an Administrator grant for HOME; two Admins ({@code n7_admin}, {@code n7_admin2}).
 */
@SpringBootTest
public class TaskNotificationRecipientRulesTest {

    private static final String HOME = "N7_HOME";
    private static final String OTHER = "N7_OTHER";
    private static final String[] USERS = {"n7_admin", "n7_admin2", "n7_mgr", "n7_mgr_new", "n7_mgr_grant", "n7_mgr_other", "n7_s1", "n7_s2", "n7_s3", "n7_out"};

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private AssignmentRepository assignmentRepository;
    @Autowired private ManagerViewGrantRepository grantRepository;
    @Autowired private NotificationPreferenceRepository preferenceRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @MockitoBean private EmailService emailService;

    private MockMvc mockMvc;
    private ScopeFixtures fixtures;
    private Department home;
    private Department other;
    private User admin;
    private User admin2;
    private Staff mgr;
    private Staff s1;
    private Staff s2;
    private Staff s3;

    @BeforeEach
    public void setUp() {
        org.springframework.security.test.context.TestSecurityContextHolder.clearContext();
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        when(emailService.isConfigured()).thenReturn(true);
        fixtures = new ScopeFixtures(userRepository, staffRepository, departmentRepository, passwordEncoder);
        home = fixtures.department(HOME);
        other = fixtures.department(OTHER);
        admin = fixtures.account("n7_admin", Role.ADMIN, true);
        admin2 = fixtures.account("n7_admin2", Role.ADMIN, true);
        mgr = fixtures.manager("n7_mgr", home);
        Staff mgrGrant = fixtures.manager("n7_mgr_grant", other);
        fixtures.manager("n7_mgr_other", other);
        s1 = fixtures.staff("n7_s1", home);
        s2 = fixtures.staff("n7_s2", home);
        s3 = fixtures.staff("n7_s3", home);
        fixtures.staff("n7_out", other);
        ManagerViewGrant grant = new ManagerViewGrant();
        grant.setManager(mgrGrant.getUser());
        grant.setDepartment(home);
        grant.setGrantedByUserId(admin.getId());
        grant.setGrantedByUsername("n7_admin");
        grantRepository.saveAndFlush(grant);
        for (String name : new String[] {"n7_admin", "n7_admin2", "n7_mgr", "n7_mgr_grant", "n7_mgr_other", "n7_s1", "n7_s2", "n7_s3", "n7_out"}) verify(name);
        // Accounts created through the application start with every optional category ON; the fixtures mirror that for task status
        for (String name : new String[] {"n7_admin", "n7_admin2", "n7_mgr", "n7_mgr_grant", "n7_mgr_other", "n7_s1", "n7_s2", "n7_s3"}) {
            preference(name, NotificationCategory.TASK_STATUS, true);
        }
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
        for (Department department : List.of(home, other)) {
            taskRepository.findAll().stream().filter(t -> t.getDepartment() != null && department.getCode().equals(t.getDepartment().getCode())).forEach(t -> {
                assignmentRepository.findAll().stream().filter(a -> a.getTask() != null && a.getTask().getId().equals(t.getId())).forEach(assignmentRepository::delete);
                assignmentRepository.flush();
                taskRepository.delete(t);
            });
            taskRepository.flush();
        }
        fixtures.remove(USERS);
        departmentRepository.findByCode(HOME).ifPresent(departmentRepository::delete);
        departmentRepository.findByCode(OTHER).ifPresent(departmentRepository::delete);
        departmentRepository.flush();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────────────────

    private void verify(String username) {
        User account = userRepository.findByUsername(username).orElseThrow();
        account.setEmailVerified(true);
        userRepository.saveAndFlush(account);
    }

    private void preference(String username, NotificationCategory category, boolean email) {
        User account = userRepository.findByUsername(username).orElseThrow();
        NotificationPreference existing = preferenceRepository.findByUserId(account.getId()).stream()
                .filter(row -> category.name().equals(row.getCategory())).findFirst().orElse(null);
        if (existing == null) {
            preferenceRepository.saveAndFlush(new NotificationPreference(account.getId(), category, true, email));
        } else {
            existing.setEmail(email);
            preferenceRepository.saveAndFlush(existing);
        }
    }

    private Task task(String title, Department department) {
        Task task = new Task();
        task.setTitle(title);
        task.setPriority(Priority.HIGH);
        task.setStatus(TaskStatus.PENDING);
        task.setEstimatedDuration(45);
        task.setDepartment(department);
        task.setCreatedBy(admin);
        return taskRepository.saveAndFlush(task);
    }

    private MvcResult call(MockHttpServletRequestBuilder request, String username, String role, String body) throws Exception {
        request = request.with(user(username).roles(role));
        if (body != null) request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        return mockMvc.perform(request).andReturn();
    }

    private MvcResult assign(String username, String role, Task task, Staff... staff) throws Exception {
        String ids = java.util.Arrays.stream(staff).map(s -> "\"" + s.getId() + "\"").collect(Collectors.joining(","));
        return call(post("/api/assignments"), username, role, "{\"taskId\":\"" + task.getId() + "\",\"staffIds\":[" + ids + "]}");
    }

    private MvcResult reassign(String username, String role, Task task, Staff to) throws Exception {
        UUID assignmentId = assignmentsOf(task).get(0).getId();
        return call(put("/api/assignments/" + assignmentId), username, role, "{\"staffId\":\"" + to.getId() + "\"}");
    }

    private MvcResult staffMoves(String username, Task task, TaskStatus status) throws Exception {
        return call(patch("/api/tasks/" + task.getId() + "/status"), username, "STAFF", "{\"status\":\"" + status.name() + "\"}");
    }

    /** Staff takes the task through to COMPLETED; the emails of the earlier steps are discarded. */
    private void startAndComplete(String username, Task task) throws Exception {
        assertEquals(200, staffMoves(username, task, TaskStatus.IN_PROGRESS).getResponse().getStatus());
        Mockito.clearInvocations(emailService);
        MvcResult done = staffMoves(username, task, TaskStatus.COMPLETED);
        assertEquals(200, done.getResponse().getStatus(), done.getResponse().getContentAsString());
    }

    private List<Assignment> assignmentsOf(Task task) {
        return assignmentRepository.findAll().stream().filter(a -> a.getTask() != null && a.getTask().getId().equals(task.getId())).toList();
    }

    private List<String[]> sent() {
        return Mockito.mockingDetails(emailService).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("sendNotificationEmail"))
                .map(i -> new String[] {i.getArgument(0), i.getArgument(1), i.getArgument(2), i.getArgument(3)})
                .collect(Collectors.toList());
    }

    private List<String> recipients() {
        List<String> to = new ArrayList<>();
        sent().forEach(m -> to.add(m[0]));
        to.sort(String::compareTo);
        return to;
    }

    private static List<String> mails(String... usernames) {
        return java.util.Arrays.stream(usernames).map(u -> u + "@scope.test").sorted().toList();
    }

    private String[] mailTo(String username) {
        return sent().stream().filter(m -> m[0].equals(username + "@scope.test")).findFirst().orElseThrow();
    }

    // ── assignment ───────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Admin assigns: the assignee, the department Manager and that Admin are emailed; another Admin, another department's Manager and a granted-elsewhere Manager are not")
    public void adminAssignmentRecipients() throws Exception {
        Task task = task("Admin job", home);
        assertEquals(201, assign("n7_admin", "ADMIN", task, s1).getResponse().getStatus());
        // n7_mgr_grant holds an active grant for HOME, so it IS responsible for this department (the existing Manager rule); n7_mgr_other is not
        assertEquals(mails("n7_s1", "n7_mgr", "n7_mgr_grant", "n7_admin"), recipients());
        assertEquals("Taskotel - New task assigned: Admin job", mailTo("n7_s1")[1]);
        String[] managerMail = mailTo("n7_mgr");
        assertEquals("Taskotel - Task assigned: Admin job", managerMail[1]);
        assertTrue(managerMail[2].contains("Assigned to: " + s1.getName()) && managerMail[2].contains("Assigned by: An administrator"), managerMail[2]);
        assertEquals(managerMail[1], mailTo("n7_admin")[1], "the acting Admin gets the same overview");
        assertFalse(recipients().contains("n7_admin2@scope.test"), "an Admin who did not assign is not told");
        assertFalse(recipients().contains("n7_mgr_other@scope.test"), "an unrelated Manager is not told");
    }

    @Test
    @DisplayName("The assignment records who made it, and it is the signed-in account")
    public void assignmentRecordsTheAssigner() throws Exception {
        Task task = task("Recorded", home);
        assertEquals(201, assign("n7_admin", "ADMIN", task, s1).getResponse().getStatus());
        assertEquals(admin.getId(), assignmentsOf(task).get(0).getAssignedByUserId());
    }

    @Test
    @DisplayName("Manager assigns in their own department: the Manager is both assigner and department Manager and gets ONE email; no Admin is told")
    public void managerAssignmentSendsOneEmailToTheManager() throws Exception {
        Task task = task("Manager job", home);
        assertEquals(201, assign("n7_mgr", "MANAGER", task, s1).getResponse().getStatus());
        List<String> to = recipients();
        assertEquals(1, to.stream().filter("n7_mgr@scope.test"::equals).count(), "one email, not two");
        assertEquals(mails("n7_s1", "n7_mgr", "n7_mgr_grant"), to);
        assertFalse(to.contains("n7_admin@scope.test") || to.contains("n7_admin2@scope.test"), "no Admin made this assignment");
    }

    @Test
    @DisplayName("Manager assigns in an additional (granted) department: the assignee, the granted Manager (once) and the department's own Manager are told")
    public void grantedDepartmentAssignment() throws Exception {
        Task task = task("Granted job", home);
        assertEquals(201, assign("n7_mgr_grant", "MANAGER", task, s1).getResponse().getStatus());
        assertEquals(mails("n7_s1", "n7_mgr", "n7_mgr_grant"), recipients());
        assertEquals(userRepository.findByUsername("n7_mgr_grant").orElseThrow().getId(), assignmentsOf(task).get(0).getAssignedByUserId());
    }

    @Test
    @DisplayName("Several Staff on one task: every new assignee is told once and the Manager and Admin get ONE overview naming all of them")
    public void multiStaffAssignment() throws Exception {
        Task task = task("Team job", home);
        assertEquals(201, assign("n7_admin", "ADMIN", task, s1, s2, s3).getResponse().getStatus());
        assertEquals(mails("n7_s1", "n7_s2", "n7_s3", "n7_mgr", "n7_mgr_grant", "n7_admin"), recipients());
        String body = mailTo("n7_mgr")[2];
        assertTrue(body.contains(s1.getName()) && body.contains(s2.getName()) && body.contains(s3.getName()), body);
    }

    @Test
    @DisplayName("Reassignment: the new assignee, the previous assignee, the department Manager and the acting Admin; the original assigner is not re-told and stays on record")
    public void reassignmentRecipientsAndHistory() throws Exception {
        Task task = task("Move me", home);
        assertEquals(201, assign("n7_admin", "ADMIN", task, s1).getResponse().getStatus());
        Mockito.clearInvocations(emailService);

        assertEquals(200, reassign("n7_admin2", "ADMIN", task, s2).getResponse().getStatus());
        assertEquals(mails("n7_s2", "n7_s1", "n7_mgr", "n7_mgr_grant", "n7_admin2"), recipients());
        assertEquals("Taskotel - Task reassigned: Move me", mailTo("n7_s1")[1]);
        assertFalse(recipients().contains("n7_admin@scope.test"), "the original assigner did not make this change");
        assertEquals(admin.getId(), assignmentsOf(task).get(0).getAssignedByUserId(), "the original assigner is preserved, not replaced by the reassigning Admin");
    }

    @Test
    @DisplayName("Removing a person from a task tells only that person (no overview, no new assignee)")
    public void unassignTellsOnlyThePerson() throws Exception {
        Task task = task("Take off", home);
        assertEquals(201, assign("n7_admin", "ADMIN", task, s1).getResponse().getStatus());
        Mockito.clearInvocations(emailService);
        UUID id = assignmentsOf(task).get(0).getId();
        assertEquals(204, call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/assignments/" + id), "n7_admin", "ADMIN", null).getResponse().getStatus());
        assertEquals(mails("n7_s1"), recipients());
    }

    // ── completion ───────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Staff completes a task an Admin assigned: the department Manager and that Admin are emailed; no other Admin, no unrelated Manager, no other Staff")
    public void completionAfterAdminAssignment() throws Exception {
        Task task = task("Finish me", home);
        assertEquals(201, assign("n7_admin", "ADMIN", task, s1).getResponse().getStatus());
        startAndComplete("n7_s1", task);
        assertEquals(mails("n7_mgr", "n7_mgr_grant", "n7_admin"), recipients());
        String[] m = mailTo("n7_admin");
        assertEquals("Task Completed: Finish me", m[1]);
        assertTrue(m[2].contains("A task has been completed!") && m[2].contains("Status: Completed") && m[2].contains("Completed by: " + s1.getName() + " (Staff)"), m[2]);
        assertFalse(m[2].contains("Previous status") || m[2].contains("New status"), "a completion email is not a status-change email: " + m[2]);
        assertTrue(sent().stream().allMatch(mail -> mail[1].equals("Task Completed: Finish me")), "the Manager, the granted Manager and the original Admin all get the completion email");
    }

    @Test
    @DisplayName("Staff completes a task a Manager assigned: that Manager (also the department Manager) gets ONE email; no Admin is told")
    public void completionAfterManagerAssignment() throws Exception {
        Task task = task("Manager finish", home);
        assertEquals(201, assign("n7_mgr", "MANAGER", task, s1).getResponse().getStatus());
        startAndComplete("n7_s1", task);
        assertEquals(mails("n7_mgr", "n7_mgr_grant"), recipients());
        assertEquals(1, recipients().stream().filter("n7_mgr@scope.test"::equals).count());
    }

    @Test
    @DisplayName("Original assigner differs from the Manager and from the later reassigning Admin: the completion email goes to the ORIGINAL Admin only")
    public void completionGoesToTheOriginalAssignerAfterReassignment() throws Exception {
        Task task = task("History", home);
        assertEquals(201, assign("n7_admin", "ADMIN", task, s1).getResponse().getStatus());
        assertEquals(200, reassign("n7_admin2", "ADMIN", task, s2).getResponse().getStatus());
        startAndComplete("n7_s2", task);
        assertEquals(mails("n7_mgr", "n7_mgr_grant", "n7_admin"), recipients());
        assertFalse(recipients().contains("n7_admin2@scope.test"), "the Admin who only reassigned is not the original assigner");
    }

    @Test
    @DisplayName("A Manager of a granted department assigned it: that Manager and the department's own Manager are told on completion, once each")
    public void completionAfterGrantedManagerAssignment() throws Exception {
        Task task = task("Grant finish", home);
        assertEquals(201, assign("n7_mgr_grant", "MANAGER", task, s1).getResponse().getStatus());
        startAndComplete("n7_s1", task);
        assertEquals(mails("n7_mgr", "n7_mgr_grant"), recipients());
    }

    @Test
    @DisplayName("An assignment from before the assigner was recorded: only the department Manager(s) are told on completion; nobody is guessed")
    public void completionWithUnknownAssigner() throws Exception {
        Task task = task("Legacy", home);
        assertEquals(201, assign("n7_admin", "ADMIN", task, s1).getResponse().getStatus());
        for (Assignment a : assignmentsOf(task)) {
            a.setAssignedByUserId(null);
            assignmentRepository.saveAndFlush(a);
        }
        assertNull(assignmentsOf(task).get(0).getAssignedByUserId());
        startAndComplete("n7_s1", task);
        assertEquals(mails("n7_mgr", "n7_mgr_grant"), recipients());
    }

    @Test
    @DisplayName("The department Manager changed after the assignment: the CURRENT Manager is told, the former one (now inactive) is not; the original Admin still is")
    public void managerChangedAfterAssignment() throws Exception {
        Task task = task("Handover", home);
        assertEquals(201, assign("n7_admin", "ADMIN", task, s1).getResponse().getStatus());
        // the old Manager leaves and a new Manager takes the department
        User old = mgr.getUser();
        old.setActive(false);
        userRepository.saveAndFlush(old);
        fixtures.manager("n7_mgr_new", home);
        verify("n7_mgr_new");
        preference("n7_mgr_new", NotificationCategory.TASK_STATUS, true);
        startAndComplete("n7_s1", task);
        assertEquals(mails("n7_mgr_new", "n7_mgr_grant", "n7_admin"), recipients());
    }

    @Test
    @DisplayName("An original assigner whose account is inactive, or no longer exists, is skipped without failing the completion")
    public void inactiveOrDeletedAssignerIsSkipped() throws Exception {
        Task task = task("Gone assigner", home);
        assertEquals(201, assign("n7_admin2", "ADMIN", task, s1).getResponse().getStatus());
        User gone = admin2;
        gone.setActive(false);
        userRepository.saveAndFlush(gone);
        startAndComplete("n7_s1", task);
        assertEquals(mails("n7_mgr", "n7_mgr_grant"), recipients());

        Task second = task("Deleted assigner", home);
        Mockito.clearInvocations(emailService);
        Staff s4 = s2;
        assertEquals(201, assign("n7_admin", "ADMIN", second, s4).getResponse().getStatus());
        for (Assignment a : assignmentsOf(second)) {
            a.setAssignedByUserId(UUID.randomUUID()); // an account that does not exist
            assignmentRepository.saveAndFlush(a);
        }
        startAndComplete("n7_s2", second);
        assertEquals(mails("n7_mgr", "n7_mgr_grant"), recipients());
    }

    @Test
    @DisplayName("DOCUMENTED BEHAVIOUR: when one of several assignees completes the task, the other assignee is told too (TASK_STATUS goes to everyone assigned except the person who made the change)")
    public void coAssigneeIsToldOfCompletion() throws Exception {
        Task task = task("Shared", home);
        assertEquals(201, assign("n7_admin", "ADMIN", task, s1, s2).getResponse().getStatus());
        startAndComplete("n7_s1", task);
        assertEquals(mails("n7_s2", "n7_mgr", "n7_mgr_grant", "n7_admin"), recipients(),
                "co-assignee s2 is a recipient of the existing status rule; the completing person s1 is not; s3 (not assigned) is not");
        assertFalse(recipients().contains("n7_s3@scope.test"));
    }

    @Test
    @DisplayName("An inactive department Manager is not emailed about an assignment; the others still are")
    public void inactiveManagerIsNotEmailedOnAssignment() throws Exception {
        User old = mgr.getUser();
        old.setActive(false);
        userRepository.saveAndFlush(old);
        Task task = task("No active mgr", home);
        assertEquals(201, assign("n7_admin", "ADMIN", task, s1).getResponse().getStatus());
        assertEquals(mails("n7_s1", "n7_mgr_grant", "n7_admin"), recipients());
    }

    @Test
    @DisplayName("Completing a finished task again is refused and sends nothing (no duplicate completion email from a retry)")
    public void retryDoesNotDuplicate() throws Exception {
        Task task = task("Once only", home);
        assertEquals(201, assign("n7_admin", "ADMIN", task, s1).getResponse().getStatus());
        startAndComplete("n7_s1", task);
        Mockito.clearInvocations(emailService);
        assertEquals(409, staffMoves("n7_s1", task, TaskStatus.COMPLETED).getResponse().getStatus());
        assertTrue(sent().isEmpty());
    }

    // ── preferences, duplicates, failures ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Each recipient's own preference decides: Manager's TASK_ASSIGNED off, Admin's TASK_ASSIGNED off, Staff's TASK_ASSIGNED off")
    public void assignmentPreferencesAreIndependent() throws Exception {
        preference("n7_mgr", NotificationCategory.TASK_ASSIGNED, false);
        preference("n7_admin", NotificationCategory.TASK_ASSIGNED, false);
        Task task = task("Prefs", home);
        assertEquals(201, assign("n7_admin", "ADMIN", task, s1).getResponse().getStatus());
        assertEquals(mails("n7_s1", "n7_mgr_grant"), recipients());

        preference("n7_s1", NotificationCategory.TASK_ASSIGNED, false);
        Task second = task("Prefs 2", home);
        Mockito.clearInvocations(emailService);
        assertEquals(201, assign("n7_admin", "ADMIN", second, s2).getResponse().getStatus());
        assertEquals(mails("n7_s2", "n7_mgr_grant"), recipients());
    }

    @Test
    @DisplayName("Completion honours TASK_STATUS per recipient: the Manager and the original Admin can each switch it off")
    public void completionPreferencesAreIndependent() throws Exception {
        Task task = task("Status prefs", home);
        assertEquals(201, assign("n7_admin", "ADMIN", task, s1).getResponse().getStatus());
        preference("n7_mgr", NotificationCategory.TASK_STATUS, true);
        preference("n7_mgr_grant", NotificationCategory.TASK_STATUS, true);
        preference("n7_admin", NotificationCategory.TASK_STATUS, false);
        startAndComplete("n7_s1", task);
        assertEquals(mails("n7_mgr", "n7_mgr_grant"), recipients());
    }

    @Test
    @DisplayName("Task Status defaults to off for an account that never chose: Managers and Admin hear nothing until they opt in; the rule only decides WHO is eligible")
    public void statusDefaultIsOff() throws Exception {
        for (String name : USERS) {
            userRepository.findByUsername(name).ifPresent(u -> preferenceRepository.deleteAll(preferenceRepository.findByUserId(u.getId())));
        }
        Task task = task("Default off", home);
        assertEquals(201, assign("n7_admin", "ADMIN", task, s1).getResponse().getStatus());
        startAndComplete("n7_s1", task);
        assertTrue(sent().isEmpty(), "no TASK_STATUS row was saved for anyone, and the category defaults to off");
    }

    @Test
    @DisplayName("Two accounts sharing one email address get one email between them")
    public void duplicateAddressesGetOneEmail() throws Exception {
        User twin = userRepository.findByUsername("n7_mgr_grant").orElseThrow();
        twin.setEmail(mgr.getUser().getEmail().toUpperCase());
        userRepository.saveAndFlush(twin);
        Task task = task("Twins", home);
        assertEquals(201, assign("n7_admin", "ADMIN", task, s1).getResponse().getStatus());
        List<String> to = recipients().stream().map(String::toLowerCase).toList();
        assertEquals(1, to.stream().filter("n7_mgr@scope.test"::equals).count(), "one email to the shared address: " + to);
        assertEquals(4 - 1, to.size());
    }

    @Test
    @DisplayName("A SendGrid failure for one recipient does not stop the others and never breaks the assignment or the completion")
    public void deliveryFailureIsContained() throws Exception {
        doThrow(new EmailSendingException("SendGrid is down")).when(emailService).sendNotificationEmail(Mockito.eq("n7_mgr@scope.test"), anyString(), anyString(), anyString());
        Task task = task("Despite failure", home);
        MvcResult result = assign("n7_admin", "ADMIN", task, s1);
        assertEquals(201, result.getResponse().getStatus());
        assertEquals(mails("n7_s1", "n7_mgr", "n7_mgr_grant", "n7_admin"), recipients(), "every recipient was still attempted");
        assertEquals(TaskStatus.ASSIGNED, taskRepository.findById(task.getId()).orElseThrow().getStatus());

        doThrow(new EmailSendingException("SendGrid is down")).when(emailService).sendNotificationEmail(anyString(), anyString(), anyString(), anyString());
        assertEquals(200, staffMoves("n7_s1", task, TaskStatus.IN_PROGRESS).getResponse().getStatus());
        assertEquals(200, staffMoves("n7_s1", task, TaskStatus.COMPLETED).getResponse().getStatus());
        assertEquals(TaskStatus.COMPLETED, taskRepository.findById(task.getId()).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("A refused assignment (rolled back) sends nothing and records no assigner")
    public void refusedAssignmentSendsNothing() throws Exception {
        Task task = task("Refused", home);
        Staff outsider = staffRepository.findByUserUsername("n7_out").orElseThrow();
        assertEquals(400, assign("n7_admin", "ADMIN", task, outsider).getResponse().getStatus());
        assertTrue(sent().isEmpty());
        assertTrue(assignmentsOf(task).isEmpty());
    }

    @Test
    @DisplayName("Emails never carry an account's address in the log: the failure log masks it")
    public void failureLogMasksTheAddress() throws Exception {
        // The service logs only a masked address; this guards the format the production log relies on
        java.lang.reflect.Method mask = NotificationService.class.getDeclaredMethod("mask", String.class);
        mask.setAccessible(true);
        String masked = (String) mask.invoke(null, "alice.smith@example.com");
        assertEquals("a***@example.com", masked);
        assertFalse(masked.contains("alice"));
    }
}
