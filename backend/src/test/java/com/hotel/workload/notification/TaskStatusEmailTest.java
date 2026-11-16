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
import org.springframework.web.context.WebApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * N2: task status update emails, over HTTP with the real security chain and real transactions (an email goes out only after the commit).
 * SendGrid is a mock; nothing is ever sent.
 *
 * <p>Fixture: HOME (the task's department) with its Manager mgr_home, a Manager mgr_grant whose primary department is OTHER but who holds
 * an Administrator grant for HOME, a Manager mgr_other with no access to HOME, and four Managers who must NOT be emailed: an inactive
 * account, an inactive linked Staff record, one whose primary department is inactive (grant or not), and one whose grant was revoked.
 */
@SpringBootTest
public class TaskStatusEmailTest {

    private static final String HOME = "N2_HOME";
    private static final String OTHER = "N2_OTHER";
    private static final String DEAD = "N2_DEAD";
    private static final String[] USERS = {"n2_admin", "n2_mgr_home", "n2_mgr_grant", "n2_mgr_other", "n2_mgr_off", "n2_mgr_nostaff",
            "n2_mgr_deadprimary", "n2_mgr_revoked", "n2_s1", "n2_s2", "n2_s3"};

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
    private Department dead;
    private User admin;
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
        dead = fixtures.department(DEAD);
        admin = fixtures.account("n2_admin", Role.ADMIN, true);

        Staff mgrHome = fixtures.manager("n2_mgr_home", home);
        Staff mgrGrant = fixtures.manager("n2_mgr_grant", other);
        fixtures.manager("n2_mgr_other", other);
        Staff mgrOff = fixtures.manager("n2_mgr_off", home);
        Staff mgrNoStaff = fixtures.manager("n2_mgr_nostaff", home);
        Staff mgrDeadPrimary = fixtures.manager("n2_mgr_deadprimary", dead);
        Staff mgrRevoked = fixtures.manager("n2_mgr_revoked", other);
        s1 = fixtures.staff("n2_s1", home);
        s2 = fixtures.staff("n2_s2", home);
        s3 = fixtures.staff("n2_s3", home);

        grant(mgrGrant.getUser(), home);
        grant(mgrDeadPrimary.getUser(), home);
        // the revoked one: granted, then revoked
        grant(mgrRevoked.getUser(), home);
        grantRepository.findAll().stream().filter(g -> g.getManager().getId().equals(mgrRevoked.getUser().getId())).forEach(grantRepository::delete);
        grantRepository.flush();

        User off = mgrOff.getUser();
        off.setActive(false);
        userRepository.saveAndFlush(off);
        mgrNoStaff.setActive(false);
        staffRepository.saveAndFlush(mgrNoStaff);
        dead.setActive(false);
        departmentRepository.saveAndFlush(dead);

        // Everyone has TASK_STATUS switched on, so the tests below show who is chosen by the rules, not by a default
        // Every notification goes only to a verified address, so the fixture accounts are verified
        for (String name : USERS) {
            User account = userRepository.findByUsername(name).orElseThrow();
            account.setEmailVerified(true);
            userRepository.saveAndFlush(account);
        }
        for (String name : USERS) enable(name, true);
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
        for (Department department : List.of(home, other, dead)) {
            taskRepository.findAll().stream().filter(t -> t.getDepartment() != null && department.getCode().equals(t.getDepartment().getCode())).forEach(t -> {
                assignmentRepository.findAll().stream().filter(a -> a.getTask() != null && a.getTask().getId().equals(t.getId())).forEach(assignmentRepository::delete);
                assignmentRepository.flush();
                taskRepository.delete(t);
            });
            taskRepository.flush();
        }
        fixtures.remove(USERS);
        for (String code : List.of(HOME, OTHER, DEAD)) departmentRepository.findByCode(code).ifPresent(departmentRepository::delete);
        departmentRepository.flush();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────────────────

    private void grant(User manager, Department department) {
        ManagerViewGrant grant = new ManagerViewGrant();
        grant.setManager(manager);
        grant.setDepartment(department);
        grant.setGrantedByUserId(admin.getId());
        grant.setGrantedByUsername("n2_admin");
        grantRepository.saveAndFlush(grant);
    }

    private void enable(String username, boolean email) {
        User account = userRepository.findByUsername(username).orElseThrow();
        NotificationPreference existing = preferenceRepository.findByUserId(account.getId()).stream()
                .filter(row -> NotificationCategory.TASK_STATUS.name().equals(row.getCategory())).findFirst().orElse(null);
        if (existing == null) {
            preferenceRepository.saveAndFlush(new NotificationPreference(account.getId(), NotificationCategory.TASK_STATUS, true, email));
        } else {
            existing.setEmail(email);
            preferenceRepository.saveAndFlush(existing);
        }
    }

    /** A task of the department in this status, assigned to the given people. */
    private Task task(String title, Department department, TaskStatus status, Staff... assignees) {
        Task task = new Task();
        task.setTitle(title);
        task.setPriority(Priority.HIGH);
        task.setStatus(status);
        task.setEstimatedDuration(45);
        task.setDueDate(java.time.LocalDate.of(2027, 1, 15));
        task.setDepartment(department);
        task.setCreatedBy(admin);
        task = taskRepository.saveAndFlush(task);
        for (Staff assignee : assignees) {
            Assignment assignment = new Assignment();
            assignment.setTask(task);
            assignment.setStaff(assignee);
            assignmentRepository.saveAndFlush(assignment);
        }
        return task;
    }

    private MvcResult call(MockHttpServletRequestBuilder request, String username, String role, String body) throws Exception {
        request = request.with(user(username).roles(role));
        if (body != null) request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        return mockMvc.perform(request).andReturn();
    }

    /** The full edit (Admin or Manager), changing only the status. */
    private MvcResult edit(String username, String role, Task task, TaskStatus status) throws Exception {
        String body = "{\"title\":\"" + task.getTitle() + "\",\"priority\":\"HIGH\",\"estimatedDuration\":45,\"dueDate\":\"2027-01-15\",\"department\":\""
                + task.getDepartment().getCode() + "\",\"status\":\"" + status.name() + "\"}";
        return call(put("/api/tasks/" + task.getId()), username, role, body);
    }

    private MvcResult staffMoves(String username, Task task, TaskStatus status) throws Exception {
        return call(patch("/api/tasks/" + task.getId() + "/status"), username, "STAFF", "{\"status\":\"" + status.name() + "\"}");
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

    private static String mail(String username) {
        return username + "@scope.test";
    }

    private static List<String> mails(String... usernames) {
        return java.util.Arrays.stream(usernames).map(TaskStatusEmailTest::mail).sorted().toList();
    }

    // ── who is told ──────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Admin changes a status: the assignee, the primary-department Manager and the granted Manager are emailed, once each; nobody else")
    public void adminChangeNotifiesAssigneeAndResponsibleManagers() throws Exception {
        Task task = task("Deep clean 402", home, TaskStatus.ASSIGNED, s1);
        MvcResult result = edit("n2_admin", "ADMIN", task, TaskStatus.IN_PROGRESS);
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());

        assertEquals(mails("n2_s1", "n2_mgr_home", "n2_mgr_grant"), recipients(),
                "assignee + primary Manager + granted Manager; not the Admin, not the unrelated Manager, not the invalid Managers, not the other Staff");
        String[] m = sent().get(0);
        assertEquals("Taskotel - Task status updated: Deep clean 402 is now In Progress", m[1]);
        for (String fragment : new String[] {"Task: Deep clean 402", "Previous status: Assigned", "New status: In Progress", "Priority: HIGH",
                "Department: " + home.getName(), "Due: 2027-01-15", "Changed by: An administrator"}) {
            assertTrue(m[2].contains(fragment), fragment + " in:\n" + m[2]);
        }
        assertTrue(m[3].contains("Taskotel") && m[3].contains("In Progress"));
        assertFalse(m[2].contains("n2_admin") && m[3].contains("n2_admin"), "no account name is shown for an Admin");
        assertFalse(m[2].contains("http"), "no link: no application URL is configured");
    }

    @Test
    @DisplayName("Manager of the department changes a status: the assignee and the OTHER responsible Manager are told; the Manager who made the change is not")
    public void managerChangeExcludesTheActor() throws Exception {
        Task task = task("Manager edit", home, TaskStatus.ASSIGNED, s1);
        assertEquals(200, edit("n2_mgr_home", "MANAGER", task, TaskStatus.IN_PROGRESS).getResponse().getStatus());
        assertEquals(mails("n2_s1", "n2_mgr_grant"), recipients());
        assertTrue(sent().get(0)[2].contains("Changed by: Scope n2_mgr_home (Manager)"));

        // the granted Manager has full management of HOME, so the same change from them tells the primary Manager and the assignee
        Mockito.clearInvocations(emailService);
        Task second = task("Granted edit", home, TaskStatus.PENDING);
        assertEquals(200, edit("n2_mgr_grant", "MANAGER", second, TaskStatus.CANCELLED).getResponse().getStatus());
        assertEquals(mails("n2_mgr_home"), recipients(), "no assignee on this task; the other Manager only");
    }

    @Test
    @DisplayName("Staff moves their own task forward: the responsible Managers are told, the Staff member who made the change is not, and a co-assignee is")
    public void staffChangeNotifiesManagers() throws Exception {
        Task task = task("Staff move", home, TaskStatus.ASSIGNED, s1, s2);
        MvcResult result = staffMoves("n2_s1", task, TaskStatus.IN_PROGRESS);
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertEquals(mails("n2_mgr_home", "n2_mgr_grant", "n2_s2"), recipients());
        assertTrue(sent().get(0)[2].contains("Changed by: Scope n2_s1 (Staff)"));
        assertEquals(TaskStatus.IN_PROGRESS, taskRepository.findById(task.getId()).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("A Manager with no access to the task's department receives nothing, and neither does anyone through another department's task")
    public void unauthorizedManagerReceivesNothing() throws Exception {
        Task task = task("Home only", home, TaskStatus.ASSIGNED, s1);
        edit("n2_admin", "ADMIN", task, TaskStatus.IN_PROGRESS);
        assertFalse(recipients().contains(mail("n2_mgr_other")));

        // a task of the OTHER department: only OTHER's Managers (mgr_grant and mgr_other have it as their primary department)
        Mockito.clearInvocations(emailService);
        Task otherTask = task("Other dept", other, TaskStatus.PENDING);
        edit("n2_admin", "ADMIN", otherTask, TaskStatus.CANCELLED);
        assertEquals(mails("n2_mgr_grant", "n2_mgr_other", "n2_mgr_revoked"), recipients(), "OTHER's own Managers; HOME's Manager is unrelated");
        assertFalse(recipients().contains(mail("n2_mgr_home")));
    }

    @Test
    @DisplayName("Inactive account, inactive linked Staff record, inactive primary department and a revoked grant each receive nothing")
    public void invalidManagersAreSkipped() throws Exception {
        Task task = task("Validity", home, TaskStatus.ASSIGNED, s1);
        edit("n2_admin", "ADMIN", task, TaskStatus.IN_PROGRESS);
        List<String> to = recipients();
        for (String invalid : new String[] {"n2_mgr_off", "n2_mgr_nostaff", "n2_mgr_deadprimary", "n2_mgr_revoked"}) {
            assertFalse(to.contains(mail(invalid)), invalid + " must not be emailed");
        }
        assertEquals(mails("n2_s1", "n2_mgr_home", "n2_mgr_grant"), to);
    }

    @Test
    @DisplayName("Revoking a grant, or deactivating the department, takes effect on the very next status change")
    public void grantAndDepartmentChangesApplyNextTime() throws Exception {
        Task task = task("Live state", home, TaskStatus.PENDING);
        java.util.UUID grantedManagerId = userRepository.findByUsername("n2_mgr_grant").orElseThrow().getId();
        grantRepository.findAll().stream().filter(g -> g.getManager().getId().equals(grantedManagerId)).forEach(grantRepository::delete);
        grantRepository.flush();
        edit("n2_admin", "ADMIN", task, TaskStatus.CANCELLED);
        assertEquals(mails("n2_mgr_home"), recipients(), "the revoked Manager is no longer told");

        // an inactive department has no responsible Managers at all
        Mockito.clearInvocations(emailService);
        Task other2 = task("Dead dept", dead, TaskStatus.PENDING);
        edit("n2_admin", "ADMIN", other2, TaskStatus.CANCELLED);
        assertTrue(recipients().isEmpty(), "nobody manages an inactive department: " + recipients());
    }

    @Test
    @DisplayName("A person who is eligible in two ways (primary Manager with a leftover grant) is emailed once")
    public void recipientsAreDeduplicated() throws Exception {
        grant(userRepository.findByUsername("n2_mgr_home").orElseThrow(), home); // redundant: their own primary department
        Task task = task("Dedupe", home, TaskStatus.PENDING);
        edit("n2_admin", "ADMIN", task, TaskStatus.CANCELLED);
        assertEquals(mails("n2_mgr_home", "n2_mgr_grant"), recipients());
        assertEquals(2, recipients().stream().distinct().count());
    }

    // ── preferences ──────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("TASK_STATUS switched off: that person is not emailed, everyone else still is, and other categories do not matter")
    public void preferenceIsPerRecipient() throws Exception {
        enable("n2_s1", false);
        enable("n2_mgr_home", false);
        Task task = task("Prefs", home, TaskStatus.ASSIGNED, s1);
        edit("n2_admin", "ADMIN", task, TaskStatus.IN_PROGRESS);
        assertEquals(mails("n2_mgr_grant"), recipients());
    }

    @Test
    @DisplayName("An account that never chose anything keeps the old default for TASK_STATUS (off), so existing users see no change")
    public void legacyDefaultIsOff() throws Exception {
        preferenceRepository.deleteAll(preferenceRepository.findByUserId(userRepository.findByUsername("n2_s1").orElseThrow().getId()));
        Task task = task("Legacy", home, TaskStatus.ASSIGNED, s1);
        edit("n2_admin", "ADMIN", task, TaskStatus.IN_PROGRESS);
        assertFalse(recipients().contains(mail("n2_s1")));
        assertTrue(recipients().contains(mail("n2_mgr_home")));
    }

    // ── no change, refusals, failures ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("An update that leaves the status as it was sends nothing, even when other fields change")
    public void unchangedStatusSendsNothing() throws Exception {
        Task task = task("Same status", home, TaskStatus.ASSIGNED, s1);
        MvcResult same = edit("n2_admin", "ADMIN", task, TaskStatus.ASSIGNED);
        assertEquals(200, same.getResponse().getStatus(), same.getResponse().getContentAsString());
        String retitled = "{\"title\":\"New title\",\"priority\":\"LOW\",\"estimatedDuration\":50,\"dueDate\":\"2027-01-15\",\"department\":\"" + HOME + "\",\"status\":\"ASSIGNED\"}"; // other fields change, the status does not
        assertEquals(200, call(put("/api/tasks/" + task.getId()), "n2_admin", "ADMIN", retitled).getResponse().getStatus());
        assertTrue(sent().isEmpty());
    }

    @Test
    @DisplayName("A refused change (Staff move not allowed, a status that conflicts with the assignment, another department's Manager) sends nothing and keeps the old status")
    public void refusedChangesSendNothing() throws Exception {
        Task task = task("Refused", home, TaskStatus.ASSIGNED, s1);
        assertEquals(409, staffMoves("n2_s1", task, TaskStatus.COMPLETED).getResponse().getStatus(), "Staff may only take one step");
        assertEquals(404, staffMoves("n2_s3", task, TaskStatus.IN_PROGRESS).getResponse().getStatus(), "not their task");
        assertEquals(409, edit("n2_admin", "ADMIN", task, TaskStatus.PENDING).getResponse().getStatus(), "an assigned task cannot go back to pending");
        assertEquals(404, edit("n2_mgr_other", "MANAGER", task, TaskStatus.IN_PROGRESS).getResponse().getStatus(), "another department's Manager");
        assertEquals(403, call(put("/api/tasks/" + task.getId()), "n2_s1", "STAFF", "{}").getResponse().getStatus(), "Staff cannot use the full edit");
        assertTrue(sent().isEmpty());
        assertEquals(TaskStatus.ASSIGNED, taskRepository.findById(task.getId()).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("A failing email never undoes a successful status update")
    public void emailFailureDoesNotBreakTheUpdate() throws Exception {
        doThrow(new EmailSendingException("SendGrid is down")).when(emailService).sendNotificationEmail(anyString(), anyString(), anyString(), anyString());
        Task task = task("Despite failure", home, TaskStatus.ASSIGNED, s1);
        MvcResult result = edit("n2_admin", "ADMIN", task, TaskStatus.IN_PROGRESS);
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertEquals(TaskStatus.IN_PROGRESS, taskRepository.findById(task.getId()).orElseThrow().getStatus());
        // and the Staff path
        Task second = task("Despite failure 2", home, TaskStatus.ASSIGNED, s2);
        assertEquals(200, staffMoves("n2_s2", second, TaskStatus.IN_PROGRESS).getResponse().getStatus());
        assertEquals(TaskStatus.IN_PROGRESS, taskRepository.findById(second.getId()).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("A recipient with a missing or invalid address is skipped safely; the others are still emailed")
    public void badAddressIsSkipped() throws Exception {
        User assignee = userRepository.findByUsername("n2_s1").orElseThrow();
        assignee.setEmail("not an address");
        userRepository.saveAndFlush(assignee);
        User blank = userRepository.findByUsername("n2_mgr_grant").orElseThrow();
        blank.setEmail("");
        userRepository.saveAndFlush(blank);
        Task task = task("Bad addresses", home, TaskStatus.ASSIGNED, s1);
        assertEquals(200, edit("n2_admin", "ADMIN", task, TaskStatus.IN_PROGRESS).getResponse().getStatus());
        assertEquals(mails("n2_mgr_home"), recipients());
    }

    // ── every path, no duplicates ────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Assigning a task (PENDING to ASSIGNED) is an assignment event: the assignee gets the N1 email only, and no status email goes to anyone")
    public void assignmentIsNotAStatusEmail() throws Exception {
        Task task = task("To assign", home, TaskStatus.PENDING);
        MvcResult result = call(post("/api/assignments"), "n2_admin", "ADMIN", "{\"taskId\":\"" + task.getId() + "\",\"staffId\":\"" + s1.getId() + "\"}");
        assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertEquals(mails("n2_s1"), recipients());
        assertEquals(1, sent().size());
        assertTrue(sent().get(0)[1].startsWith("Taskotel - New task assigned"), sent().get(0)[1]);
    }

    @Test
    @DisplayName("Each path produces exactly one email per recipient: a full walk through Admin, Manager and Staff changes of the same task")
    public void everyPathOnceEach() throws Exception {
        Task task = task("Walk", home, TaskStatus.ASSIGNED, s1);
        // Staff: ASSIGNED -> IN_PROGRESS
        assertEquals(200, staffMoves("n2_s1", task, TaskStatus.IN_PROGRESS).getResponse().getStatus());
        assertEquals(mails("n2_mgr_home", "n2_mgr_grant"), recipients());
        // Manager: IN_PROGRESS -> COMPLETED
        Mockito.clearInvocations(emailService);
        assertEquals(200, edit("n2_mgr_home", "MANAGER", task, TaskStatus.COMPLETED).getResponse().getStatus());
        assertEquals(mails("n2_s1", "n2_mgr_grant"), recipients());
        // Admin: the status is already COMPLETED, so nothing is announced twice
        Mockito.clearInvocations(emailService);
        assertEquals(200, edit("n2_admin", "ADMIN", task, TaskStatus.COMPLETED).getResponse().getStatus());
        assertTrue(sent().isEmpty());
    }

    @Test
    @DisplayName("An unverified assignee is not emailed about a status change; the verified Managers still are")
    public void unverifiedAssigneeIsNotEmailed() throws Exception {
        verified("n2_s1", false);
        Task task = task("Unverified", home, TaskStatus.ASSIGNED, s1);
        assertEquals(200, edit("n2_admin", "ADMIN", task, TaskStatus.IN_PROGRESS).getResponse().getStatus());
        assertEquals(mails("n2_mgr_home", "n2_mgr_grant"), recipients());
    }

    private void verified(String username, boolean value) {
        User account = userRepository.findByUsername(username).orElseThrow();
        account.setEmailVerified(value);
        userRepository.saveAndFlush(account);
    }
}
