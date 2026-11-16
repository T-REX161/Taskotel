package com.hotel.workload.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.workload.assignment.AssignmentRepository;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * N1: assignment and reassignment emails, over HTTP with the real security chain and real transactions (an email goes out only after
 * the commit, so these tests are not wrapped in a test transaction). SendGrid is a mock; nothing is ever sent.
 */
@SpringBootTest
public class TaskAssignmentEmailTest {

    private static final String HOME = "N1_HOME";
    private static final String OTHER = "N1_OTHER";

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private AssignmentRepository assignmentRepository;
    @Autowired private NotificationPreferenceRepository preferenceRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @MockitoBean private EmailService emailService;

    private final ObjectMapper mapper = new ObjectMapper();
    private MockMvc mockMvc;
    private ScopeFixtures fixtures;
    private Department home;
    private Department other;
    private Staff s1;
    private Staff s2;
    private Staff s3;
    private Staff outsider;
    private User admin;

    private static final String[] USERS = {"n1_admin", "n1_mgr", "n1_mgr_other", "n1_s1", "n1_s2", "n1_s3", "n1_out"};

    @BeforeEach
    public void setUp() {
        org.springframework.security.test.context.TestSecurityContextHolder.clearContext();
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        when(emailService.isConfigured()).thenReturn(true);
        fixtures = new ScopeFixtures(userRepository, staffRepository, departmentRepository, passwordEncoder);
        home = fixtures.department(HOME);
        other = fixtures.department(OTHER);
        admin = fixtures.account("n1_admin", Role.ADMIN, true);
        fixtures.manager("n1_mgr", home);
        fixtures.manager("n1_mgr_other", other);
        s1 = fixtures.staff("n1_s1", home);
        s2 = fixtures.staff("n1_s2", home);
        s3 = fixtures.staff("n1_s3", home);
        outsider = fixtures.staff("n1_out", other);
        Mockito.clearInvocations(emailService);
    }

    @AfterEach
    public void cleanUp() {
        for (Department department : List.of(home, other)) {
            taskRepository.findAll().stream().filter(t -> t.getDepartment() != null && department.getCode().equals(t.getDepartment().getCode())).forEach(t -> {
                assignmentRepository.findAll().stream().filter(a -> a.getTask() != null && a.getTask().getId().equals(t.getId())).forEach(assignmentRepository::delete);
                assignmentRepository.flush();
                taskRepository.delete(t);
            });
            taskRepository.flush();
        }
        for (String name : USERS) {
            userRepository.findByUsername(name).ifPresent(u -> preferenceRepository.deleteAll(preferenceRepository.findByUserId(u.getId())));
        }
        fixtures.remove(USERS);
        departmentRepository.findByCode(HOME).ifPresent(departmentRepository::delete);
        departmentRepository.findByCode(OTHER).ifPresent(departmentRepository::delete);
        departmentRepository.flush();
    }

    private Task task(String title, Department department) {
        Task task = new Task();
        task.setTitle(title);
        task.setDescription("Check the corridor");
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

    private String assignBody(Task task, Staff staff) {
        return "{\"taskId\":\"" + task.getId() + "\",\"staffId\":\"" + staff.getId() + "\"}";
    }

    private MvcResult assignAs(String username, String role, Task task, Staff staff) throws Exception {
        return call(post("/api/assignments"), username, role, assignBody(task, staff));
    }

    private UUID assignmentIdOf(MvcResult result) throws Exception {
        JsonNode node = mapper.readTree(result.getResponse().getContentAsString());
        return UUID.fromString(node.get("id").asText());
    }

    /** Every notification email sent so far: [to, subject, text, html]. */
    private List<String[]> sent() {
        return Mockito.mockingDetails(emailService).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("sendNotificationEmail"))
                .map(i -> new String[] {i.getArgument(0), i.getArgument(1), i.getArgument(2), i.getArgument(3)})
                .collect(Collectors.toList());
    }

    private List<String> recipients() {
        List<String> to = new ArrayList<>();
        sent().forEach(m -> to.add(m[0]));
        return to;
    }

    private void setPreference(Staff staff, NotificationCategory category, boolean email) {
        // A saved choice replaces an earlier one for the same category (one row per account and category)
        NotificationPreference existing = preferenceRepository.findByUserId(staff.getUser().getId()).stream()
                .filter(row -> category.name().equals(row.getCategory())).findFirst().orElse(null);
        if (existing == null) {
            preferenceRepository.saveAndFlush(new NotificationPreference(staff.getUser().getId(), category, true, email));
        } else {
            existing.setEmail(email);
            preferenceRepository.saveAndFlush(existing);
        }
    }

    private static String email(Staff staff) {
        return staff.getUser().getEmail();
    }

    // ── a new assignment ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Admin assigns: the assignee gets one branded email with the task's facts; nobody else is emailed")
    public void adminAssignmentEmailsTheAssignee() throws Exception {
        Task task = task("Deep clean room 402", home);
        MvcResult result = assignAs("n1_admin", "ADMIN", task, s1);
        assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());

        assertEquals(List.of(email(s1)), recipients(), "exactly one email, to the assignee");
        String[] mail = sent().get(0);
        assertEquals("Taskotel - New task assigned: Deep clean room 402", mail[1]);
        assertTrue(mail[2].contains("Task: Deep clean room 402") && mail[2].contains("Priority: HIGH") && mail[2].contains("Department: " + home.getName()), mail[2]);
        assertTrue(mail[2].contains("Estimated duration: 45 minutes") && mail[2].contains("Regards,\nTaskotel Team"));
        assertTrue(mail[3].contains("Taskotel") && mail[3].contains("Deep clean room 402") && mail[3].contains("<table"), "branded HTML");
        // no other account is named or emailed: not the Admin, not the department's Manager, not the other department
        assertFalse(recipients().contains(admin.getEmail()));
        assertFalse(recipients().contains(email(outsider)));
    }

    @Test
    @DisplayName("Manager assigns in a department they manage: the assignee is emailed once; Staff cannot assign and nothing is sent")
    public void managerAssignmentAndStaffRefusal() throws Exception {
        Task task = task("Restock minibar", home);
        assertEquals(201, assignAs("n1_mgr", "MANAGER", task, s1).getResponse().getStatus());
        assertEquals(List.of(email(s1)), recipients());

        Mockito.clearInvocations(emailService);
        Task second = task("Polish lobby", home);
        assertEquals(403, assignAs("n1_s2", "STAFF", second, s2).getResponse().getStatus());
        assertTrue(sent().isEmpty(), "a refused request sends nothing");
    }

    @Test
    @DisplayName("A Manager of another department is refused (404) and nobody is emailed")
    public void otherDepartmentManagerSendsNothing() throws Exception {
        Task task = task("Home task", home);
        MvcResult result = assignAs("n1_mgr_other", "MANAGER", task, s1);
        assertEquals(404, result.getResponse().getStatus());
        assertTrue(sent().isEmpty());
        assertEquals(0, assignmentRepository.findAll().stream().filter(a -> a.getTask().getId().equals(task.getId())).count(), "nothing was assigned");
    }

    @Test
    @DisplayName("Several assignees on one request: each is emailed exactly once")
    public void multiStaffAssignment() throws Exception {
        Task task = task("Two person job", home);
        String body = "{\"taskId\":\"" + task.getId() + "\",\"staffIds\":[\"" + s1.getId() + "\",\"" + s2.getId() + "\"]}";
        MvcResult result = call(post("/api/assignments"), "n1_admin", "ADMIN", body);
        assertTrue(result.getResponse().getStatus() / 100 == 2, result.getResponse().getContentAsString());
        List<String> to = recipients();
        assertEquals(2, to.size());
        assertTrue(to.containsAll(List.of(email(s1), email(s2))));
        assertEquals(2, to.stream().distinct().count(), "no duplicates");
    }

    // ── preferences ──────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("TASK_ASSIGNED switched off: the assignment succeeds and no email is sent; other categories do not matter")
    public void preferenceOffSuppresses() throws Exception {
        setPreference(s1, NotificationCategory.TASK_ASSIGNED, false);
        setPreference(s1, NotificationCategory.TASK_STATUS, true);
        Task task = task("Quiet task", home);
        assertEquals(201, assignAs("n1_admin", "ADMIN", task, s1).getResponse().getStatus());
        assertTrue(sent().isEmpty());
    }

    @Test
    @DisplayName("An account that never chose anything is emailed (assignment defaults to on); only the assignee's own preference counts")
    public void defaultAndPerUserPreference() throws Exception {
        setPreference(s2, NotificationCategory.TASK_ASSIGNED, false); // someone else's choice
        Task task = task("Default task", home);
        assertEquals(201, assignAs("n1_admin", "ADMIN", task, s1).getResponse().getStatus());
        assertEquals(List.of(email(s1)), recipients());
    }

    // ── reassignment ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Reassignment: the new assignee is told it is theirs, the previous assignee that it no longer is; no one else")
    public void reassignmentEmailsBoth() throws Exception {
        Task task = task("Move me", home);
        UUID assignmentId = assignmentIdOf(assignAs("n1_admin", "ADMIN", task, s1));
        Mockito.clearInvocations(emailService);

        MvcResult result = call(put("/api/assignments/" + assignmentId), "n1_mgr", "MANAGER", "{\"staffId\":\"" + s2.getId() + "\"}");
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());

        List<String[]> mails = sent();
        assertEquals(2, mails.size());
        String[] toNew = mails.stream().filter(m -> m[0].equals(email(s2))).findFirst().orElseThrow();
        String[] toPrevious = mails.stream().filter(m -> m[0].equals(email(s1))).findFirst().orElseThrow();
        assertEquals("Taskotel - New task assigned: Move me", toNew[1]);
        assertEquals("Taskotel - Task reassigned: Move me", toPrevious[1]);
        assertTrue(toPrevious[2].contains("no longer assigned to you"));
        assertFalse(toPrevious[2].contains(s2.getName()) || toPrevious[3].contains(s2.getName()), "the new assignee is not named to the previous one");
        assertFalse(recipients().contains(email(s3)) || recipients().contains(email(outsider)));
    }

    @Test
    @DisplayName("Reassignment honours each recipient's own TASK_ASSIGNED preference, independently")
    public void reassignmentPreferences() throws Exception {
        Task task = task("Pref move", home);
        UUID assignmentId = assignmentIdOf(assignAs("n1_admin", "ADMIN", task, s1));
        setPreference(s1, NotificationCategory.TASK_ASSIGNED, false); // the previous assignee opted out
        Mockito.clearInvocations(emailService);
        assertEquals(200, call(put("/api/assignments/" + assignmentId), "n1_admin", "ADMIN", "{\"staffId\":\"" + s2.getId() + "\"}").getResponse().getStatus());
        assertEquals(List.of(email(s2)), recipients(), "only the new assignee");

        // and the other way round: the new assignee opted out, the previous one is still told
        UUID second = assignmentIdOf(assignAs("n1_admin", "ADMIN", task("Pref move 2", home), s3));
        setPreference(s1, NotificationCategory.TASK_ASSIGNED, true);
        setPreference(s2, NotificationCategory.TASK_ASSIGNED, false);
        Mockito.clearInvocations(emailService);
        assertEquals(200, call(put("/api/assignments/" + second), "n1_admin", "ADMIN", "{\"staffId\":\"" + s1.getId() + "\"}").getResponse().getStatus());
        assertEquals(List.of(email(s1), email(s3)).stream().sorted().toList(), recipients().stream().sorted().toList());
    }

    @Test
    @DisplayName("A refused reassignment (the same person, an unavailable person, another department's person) sends nothing")
    public void refusedReassignmentSendsNothing() throws Exception {
        Task task = task("Stay put", home);
        UUID assignmentId = assignmentIdOf(assignAs("n1_admin", "ADMIN", task, s1));
        Mockito.clearInvocations(emailService);
        assertEquals(409, call(put("/api/assignments/" + assignmentId), "n1_admin", "ADMIN", "{\"staffId\":\"" + s1.getId() + "\"}").getResponse().getStatus());
        assertEquals(400, call(put("/api/assignments/" + assignmentId), "n1_admin", "ADMIN", "{\"staffId\":\"" + outsider.getId() + "\"}").getResponse().getStatus());
        assertEquals(404, call(put("/api/assignments/" + assignmentId), "n1_mgr", "MANAGER", "{\"staffId\":\"" + outsider.getId() + "\"}").getResponse().getStatus());
        assertTrue(sent().isEmpty());
    }

    // ── refusals and failures ────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("A refused assignment (the person already has work) sends no email and keeps nothing")
    public void refusedAssignmentSendsNothing() throws Exception {
        assertEquals(201, assignAs("n1_admin", "ADMIN", task("First", home), s1).getResponse().getStatus());
        Mockito.clearInvocations(emailService);
        Task second = task("Second", home);
        assertEquals(409, assignAs("n1_admin", "ADMIN", second, s1).getResponse().getStatus());
        assertTrue(sent().isEmpty());
    }

    @Test
    @DisplayName("A failing email never breaks the assignment: the answer is the same and the assignment is saved")
    public void emailFailureDoesNotBreakTheAssignment() throws Exception {
        doThrow(new EmailSendingException("SendGrid is down")).when(emailService).sendNotificationEmail(anyString(), anyString(), anyString(), anyString());
        Task task = task("Despite failure", home);
        MvcResult result = assignAs("n1_admin", "ADMIN", task, s1);
        assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertTrue(assignmentRepository.findAll().stream().anyMatch(a -> a.getTask().getId().equals(task.getId()) && a.getStaff().getId().equals(s1.getId())));
        assertEquals(TaskStatus.ASSIGNED, taskRepository.findById(task.getId()).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("When email is not configured the assignment still works and nothing is attempted")
    public void unconfiguredEmailDoesNotBreakTheAssignment() throws Exception {
        when(emailService.isConfigured()).thenReturn(false);
        assertEquals(201, assignAs("n1_admin", "ADMIN", task("No mail", home), s1).getResponse().getStatus());
        assertTrue(sent().isEmpty());
    }

    @Test
    @DisplayName("An assignee without a usable email address is skipped, and the assignment is unaffected")
    public void ineligibleAssigneeIsSkipped() throws Exception {
        User account = s1.getUser();
        account.setEmail("");
        userRepository.saveAndFlush(account);
        assertEquals(201, assignAs("n1_admin", "ADMIN", task("No address", home), s1).getResponse().getStatus());
        assertTrue(sent().isEmpty());
    }
}
