package com.hotel.workload.notification;

import com.hotel.workload.assignment.Assignment;
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

import java.time.LocalDateTime;
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
 * N3: the four mandatory security emails, over HTTP with the real security chain and real transactions (an email goes out only after
 * the commit). SendGrid is a mock; nothing is ever sent.
 */
@SpringBootTest
public class SecurityEmailTest {

    private static final String DEPT = "N3_DEPT";
    private static final String OTHER = "N3_OTHER";
    private static final String OLD_PASSWORD = "Old@Pass123";
    private static final String NEW_PASSWORD = "Brand@New456x";
    private static final String CODE = "482913";
    /**
     * The password-reset confirm endpoint is rate-limited per client, per account and per client + account, and the limiter's counters live
     * in the application context, which other test classes share. Every test therefore uses its own client address and its own reset
     * account, so it starts from empty counters whatever ran before it (the limiter itself is covered by ResetAndSetupRateLimitTest).
     */
    private static final java.util.concurrent.atomic.AtomicInteger SEQUENCE = new java.util.concurrent.atomic.AtomicInteger();
    private String resetClient;
    private String resetEmail;

    private static final String[] USERS = {"n3_admin", "n3_admin2", "n3_mgr", "n3_mgr_other", "n3_s1", "n3_s2", "n3_s3", "n3_pw", "n3_reset"};

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private AssignmentRepository assignmentRepository;
    @Autowired private NotificationPreferenceRepository preferenceRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @MockitoBean private EmailService emailService;

    private MockMvc mockMvc;
    private ScopeFixtures fixtures;
    private Department dept;
    private Department other;
    private Staff s1;
    private Staff s2;
    private Staff s3;
    private User admin;

    @BeforeEach
    public void setUp() {
        org.springframework.security.test.context.TestSecurityContextHolder.clearContext();
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        when(emailService.isConfigured()).thenReturn(true);
        fixtures = new ScopeFixtures(userRepository, staffRepository, departmentRepository, passwordEncoder);
        dept = fixtures.department(DEPT);
        other = fixtures.department(OTHER);
        admin = fixtures.account("n3_admin", Role.ADMIN, true);
        fixtures.account("n3_admin2", Role.ADMIN, true); // an Admin account with no Staff record (the direct branch of the account service)
        fixtures.manager("n3_mgr", dept);
        fixtures.manager("n3_mgr_other", other);
        s1 = fixtures.staff("n3_s1", dept);
        s2 = fixtures.staff("n3_s2", dept);
        s3 = fixtures.staff("n3_s3", dept);
        User pw = fixtures.account("n3_pw", Role.STAFF, true);
        pw.setPassword(passwordEncoder.encode(OLD_PASSWORD));
        pw.setCredentialsSetupRequired(false);
        userRepository.saveAndFlush(pw);
        int n = SEQUENCE.incrementAndGet();
        resetClient = "10.73." + (n / 250) + "." + (n % 250 + 1);
        resetEmail = "n3_reset_" + n + "@scope.test";
        User reset = fixtures.account("n3_reset", Role.STAFF, true);
        reset.setEmail(resetEmail);
        reset.setPassword(passwordEncoder.encode(OLD_PASSWORD));
        reset.setCredentialsSetupRequired(false);
        reset.setPasswordResetCodeHash(passwordEncoder.encode(CODE));
        reset.setPasswordResetExpiresAt(LocalDateTime.now().plusMinutes(10));
        reset.setPasswordResetAttemptCount(0);
        userRepository.saveAndFlush(reset);
        // a security notice goes only to a verified address
        for (String name : USERS) verified(name, true);
        Mockito.clearInvocations(emailService);
    }

    @AfterEach
    public void cleanUp() {
        for (String name : USERS) {
            userRepository.findByUsername(name).ifPresent(u -> preferenceRepository.deleteAll(preferenceRepository.findByUserId(u.getId())));
        }
        for (Department department : List.of(dept, other)) {
            taskRepository.findAll().stream().filter(t -> t.getDepartment() != null && department.getCode().equals(t.getDepartment().getCode())).forEach(t -> {
                assignmentRepository.findAll().stream().filter(a -> a.getTask() != null && a.getTask().getId().equals(t.getId())).forEach(assignmentRepository::delete);
                assignmentRepository.flush();
                taskRepository.delete(t);
            });
            taskRepository.flush();
        }
        fixtures.remove(USERS);
        for (String code : List.of(DEPT, OTHER)) departmentRepository.findByCode(code).ifPresent(departmentRepository::delete);
        departmentRepository.flush();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────────────────

    private void verified(String username, boolean value) {
        User account = userRepository.findByUsername(username).orElseThrow();
        account.setEmailVerified(value);
        userRepository.saveAndFlush(account);
    }

    private MvcResult call(MockHttpServletRequestBuilder request, String username, String role, String body) throws Exception {
        if (username != null) request = request.with(user(username).roles(role));
        if (body != null) request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        return mockMvc.perform(request).andReturn();
    }

    private MvcResult changePassword(String current, String next) throws Exception {
        return call(put("/api/profile/password"), "n3_pw", "STAFF",
                "{\"currentPassword\":\"" + current + "\",\"newPassword\":\"" + next + "\",\"confirmPassword\":\"" + next + "\"}");
    }

    private MvcResult confirmReset(String code, String password) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/auth/password-reset/confirm").with(r -> {
            r.setRemoteAddr(resetClient);
            return r;
        });
        return call(request, null, null,
                "{\"email\":\"" + resetEmail + "\",\"code\":\"" + code + "\",\"newPassword\":\"" + password + "\",\"confirmPassword\":\"" + password + "\"}");
    }

    /** Refused by the reset service itself (wrong, expired or cancelled code): never 200, and never the rate limiter's 429. */
    private static void assertRefusedByTheService(MvcResult result) {
        int status = result.getResponse().getStatus();
        assertNotEquals(200, status);
        assertNotEquals(429, status, "the request must reach the service, not be stopped by the shared rate limiter");
    }

    private MvcResult deactivate(String actor, String role, Staff target) throws Exception {
        return call(patch("/api/staff/" + target.getId() + "/deactivate"), actor, role, null);
    }

    private MvcResult activate(String actor, String role, Staff target) throws Exception {
        return call(patch("/api/staff/" + target.getId() + "/activate"), actor, role, null);
    }

    private MvcResult updateStaff(String actor, String role, Staff target, boolean active, String name) throws Exception {
        String body = "{\"fullName\":\"" + name + "\",\"email\":\"" + target.getEmail() + "\",\"phone\":\"555-0100\",\"department\":\"" + DEPT
                + "\",\"availability\":\"AVAILABLE\",\"active\":" + active + "}";
        return call(put("/api/staff/" + target.getId()), actor, role, body);
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
        return to;
    }

    private static String mail(String username) {
        return username + "@scope.test";
    }

    private boolean active(String username) {
        return userRepository.findByUsername(username).orElseThrow().isActive();
    }

    private static void assertNoSecrets(String[] mail, String... secrets) {
        String all = mail[1] + "\n" + mail[2] + "\n" + mail[3];
        for (String secret : secrets) assertFalse(all.contains(secret), "must not contain: " + secret);
        assertFalse(all.toLowerCase().contains("http"), "no link");
        assertFalse(all.contains("$2a$") || all.contains("$2b$") || all.contains("eyJ"), "no hash and no token");
    }

    // ── password changed ─────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PASSWORD_CHANGED: a successful change emails the owner once, with no secret in it; the change itself is unchanged")
    public void passwordChanged() throws Exception {
        MvcResult result = changePassword(OLD_PASSWORD, NEW_PASSWORD);
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertEquals(List.of(mail("n3_pw")), recipients());
        String[] m = sent().get(0);
        assertEquals("Taskotel - Your password was changed", m[1]);
        assertTrue(m[2].contains("signed out") && m[2].contains("Contact an administrator immediately"), m[2]);
        assertTrue(m[2].contains("required security notice"));
        assertFalse(m[2].contains("Notification Preferences"), "security notices are not tied to the preference settings");
        assertNoSecrets(m, OLD_PASSWORD, NEW_PASSWORD, "n3_pw", userRepository.findByUsername("n3_pw").orElseThrow().getPassword());
        assertTrue(passwordEncoder.matches(NEW_PASSWORD, userRepository.findByUsername("n3_pw").orElseThrow().getPassword()));
    }

    @Test
    @DisplayName("PASSWORD_CHANGED: a wrong current password, a mismatch, the same password and a weak password send nothing")
    public void passwordChangeRefusalsSendNothing() throws Exception {
        assertEquals(400, changePassword("Wrong@Pass999", NEW_PASSWORD).getResponse().getStatus());
        assertEquals(400, changePassword(OLD_PASSWORD, OLD_PASSWORD).getResponse().getStatus());
        assertEquals(400, changePassword(OLD_PASSWORD, "weak").getResponse().getStatus());
        MvcResult mismatch = call(put("/api/profile/password"), "n3_pw", "STAFF",
                "{\"currentPassword\":\"" + OLD_PASSWORD + "\",\"newPassword\":\"" + NEW_PASSWORD + "\",\"confirmPassword\":\"Different@456x\"}");
        assertEquals(400, mismatch.getResponse().getStatus());
        assertTrue(sent().isEmpty());
        assertTrue(passwordEncoder.matches(OLD_PASSWORD, userRepository.findByUsername("n3_pw").orElseThrow().getPassword()), "the password is unchanged");
    }

    @Test
    @DisplayName("PASSWORD_CHANGED: a SendGrid failure does not undo the change; a stored SECURITY=false preference does not stop the email")
    public void passwordChangeFailureAndPreference() throws Exception {
        doThrow(new EmailSendingException("down")).when(emailService).sendNotificationEmail(anyString(), anyString(), anyString(), anyString());
        assertEquals(200, changePassword(OLD_PASSWORD, NEW_PASSWORD).getResponse().getStatus());
        assertTrue(passwordEncoder.matches(NEW_PASSWORD, userRepository.findByUsername("n3_pw").orElseThrow().getPassword()));

        Mockito.reset(emailService);
        when(emailService.isConfigured()).thenReturn(true);
        User pw = userRepository.findByUsername("n3_pw").orElseThrow();
        preferenceRepository.saveAndFlush(new NotificationPreference(pw.getId(), NotificationCategory.SECURITY, true, false));
        assertEquals(200, changePassword(NEW_PASSWORD, "Another@Pass789y").getResponse().getStatus());
        assertEquals(List.of(mail("n3_pw")), recipients(), "SECURITY is always on, whatever is stored");
    }

    // ── password reset completed ─────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PASSWORD_RESET_COMPLETED: a successful reset emails the owner once; the code and the password are nowhere in it")
    public void resetCompleted() throws Exception {
        MvcResult result = confirmReset(CODE, NEW_PASSWORD);
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertEquals(List.of(resetEmail), recipients());
        String[] m = sent().get(0);
        assertEquals("Taskotel - Your password was reset", m[1]);
        assertTrue(m[2].contains("verification code that was sent to your email address"));
        assertNoSecrets(m, CODE, NEW_PASSWORD, OLD_PASSWORD, "n3_reset");
        assertTrue(passwordEncoder.matches(NEW_PASSWORD, userRepository.findByUsername("n3_reset").orElseThrow().getPassword()));
    }

    @Test
    @DisplayName("PASSWORD_RESET_COMPLETED: a wrong, expired or exhausted code sends nothing, and the attempt counter still persists as before")
    public void resetRefusalsSendNothing() throws Exception {
        for (int attempt = 1; attempt <= 2; attempt++) { // the endpoint allows 5 attempts per client and account, so few calls are used here
            assertRefusedByTheService(confirmReset("000000", NEW_PASSWORD));
            assertEquals(attempt, userRepository.findByUsername("n3_reset").orElseThrow().getPasswordResetAttemptCount(), "attempt " + attempt + " is saved");
        }
        assertTrue(sent().isEmpty());

        // an expired code
        User account = userRepository.findByUsername("n3_reset").orElseThrow();
        account.setPasswordResetAttemptCount(0);
        account.setPasswordResetExpiresAt(LocalDateTime.now().minusMinutes(1));
        userRepository.saveAndFlush(account);
        assertRefusedByTheService(confirmReset(CODE, NEW_PASSWORD));
        assertTrue(sent().isEmpty());

        // the fifth wrong attempt cancels the code, so even the right code then fails
        account = userRepository.findByUsername("n3_reset").orElseThrow();
        account.setPasswordResetExpiresAt(LocalDateTime.now().plusMinutes(10));
        account.setPasswordResetAttemptCount(4);
        userRepository.saveAndFlush(account);
        assertRefusedByTheService(confirmReset("111111", NEW_PASSWORD));
        assertNull(userRepository.findByUsername("n3_reset").orElseThrow().getPasswordResetCodeHash(), "the code is cancelled");
        assertRefusedByTheService(confirmReset(CODE, NEW_PASSWORD)); // the right code no longer works either
        assertTrue(passwordEncoder.matches(OLD_PASSWORD, userRepository.findByUsername("n3_reset").orElseThrow().getPassword()), "the password is unchanged");
        assertTrue(sent().isEmpty());
    }

    @Test
    @DisplayName("PASSWORD_RESET_COMPLETED: an inactive account cannot reset (as before) and so is not emailed; a SendGrid failure does not undo the reset")
    public void resetInactiveAndFailure() throws Exception {
        User account = userRepository.findByUsername("n3_reset").orElseThrow();
        account.setActive(false);
        userRepository.saveAndFlush(account);
        assertNotEquals(200, confirmReset(CODE, NEW_PASSWORD).getResponse().getStatus());
        assertTrue(sent().isEmpty());

        account = userRepository.findByUsername("n3_reset").orElseThrow();
        account.setActive(true);
        userRepository.saveAndFlush(account);
        doThrow(new EmailSendingException("down")).when(emailService).sendNotificationEmail(anyString(), anyString(), anyString(), anyString());
        assertEquals(200, confirmReset(CODE, NEW_PASSWORD).getResponse().getStatus());
        assertTrue(passwordEncoder.matches(NEW_PASSWORD, userRepository.findByUsername("n3_reset").orElseThrow().getPassword()));
    }

    // ── deactivation and reactivation ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ACCOUNT_DEACTIVATED / ACCOUNT_REACTIVATED: an Admin deactivates and reactivates a Staff account; only that owner is emailed, once each time")
    public void adminDeactivatesAndReactivatesStaff() throws Exception {
        assertEquals(200, deactivate("n3_admin", "ADMIN", s1).getResponse().getStatus());
        assertFalse(active("n3_s1"));
        assertEquals(List.of(mail("n3_s1")), recipients(), "the inactive owner receives the deactivation notice; nobody else does");
        String[] m = sent().get(0);
        assertEquals("Taskotel - Your account was deactivated", m[1]);
        assertTrue(m[2].contains("You can no longer sign in") && m[2].contains("Deactivated by: Administrator"), m[2]);
        assertNoSecrets(m, "n3_s1", "n3_admin", OLD_PASSWORD);

        Mockito.clearInvocations(emailService);
        assertEquals(200, activate("n3_admin", "ADMIN", s1).getResponse().getStatus());
        assertTrue(active("n3_s1"));
        assertEquals(List.of(mail("n3_s1")), recipients());
        String[] r = sent().get(0);
        assertEquals("Taskotel - Your account was reactivated", r[1]);
        assertTrue(r[2].contains("Your Taskotel account is active again.") && r[2].contains("complete the setup steps when you next sign in"), r[2]);
        assertFalse(r[2].contains("existing password"), "never assumes the first-time password setup is complete");
        assertFalse(r[2].contains("Deactivated by"));
        assertNoSecrets(r, "n3_s1", "n3_admin");
    }

    @Test
    @DisplayName("A Manager deactivates and reactivates an authorized Staff account: the owner is told (actor role: Manager); another department's Manager cannot, and nothing is sent")
    public void managerDeactivatesAuthorizedStaff() throws Exception {
        assertEquals(200, deactivate("n3_mgr", "MANAGER", s1).getResponse().getStatus());
        assertEquals(List.of(mail("n3_s1")), recipients());
        assertTrue(sent().get(0)[2].contains("Deactivated by: Manager"));
        Mockito.clearInvocations(emailService);
        assertEquals(200, activate("n3_mgr", "MANAGER", s1).getResponse().getStatus());
        assertEquals(List.of(mail("n3_s1")), recipients());

        Mockito.clearInvocations(emailService);
        assertEquals(404, deactivate("n3_mgr_other", "MANAGER", s2).getResponse().getStatus());
        assertTrue(active("n3_s2"));
        assertTrue(sent().isEmpty());
    }

    @Test
    @DisplayName("The Staff edit (PUT) announces a real change of active state in both directions, and nothing when the state is unchanged")
    public void putUpdateTransitions() throws Exception {
        assertEquals(200, updateStaff("n3_admin", "ADMIN", s2, true, "Renamed Only").getResponse().getStatus());
        assertTrue(sent().isEmpty(), "an ordinary edit that leaves the active state alone sends nothing");

        assertEquals(200, updateStaff("n3_admin", "ADMIN", s2, false, "Renamed Only").getResponse().getStatus());
        assertFalse(active("n3_s2"));
        assertEquals(List.of(mail("n3_s2")), recipients());
        assertEquals("Taskotel - Your account was deactivated", sent().get(0)[1]);

        Mockito.clearInvocations(emailService);
        assertEquals(200, updateStaff("n3_admin", "ADMIN", s2, false, "Renamed Again").getResponse().getStatus());
        assertTrue(sent().isEmpty(), "already inactive: no second notice");

        assertEquals(200, updateStaff("n3_admin", "ADMIN", s2, true, "Back").getResponse().getStatus());
        assertTrue(active("n3_s2"));
        assertEquals(List.of(mail("n3_s2")), recipients());
        assertEquals("Taskotel - Your account was reactivated", sent().get(0)[1]);

        // a Manager's edit is announced with the Manager role
        Mockito.clearInvocations(emailService);
        assertEquals(200, updateStaff("n3_mgr", "MANAGER", s2, false, "Back").getResponse().getStatus());
        assertTrue(sent().get(0)[2].contains("Deactivated by: Manager"));
    }

    @Test
    @DisplayName("Repeating a deactivate or an activate that changes nothing sends nothing")
    public void noOpTransitionsSendNothing() throws Exception {
        assertEquals(200, activate("n3_admin", "ADMIN", s1).getResponse().getStatus()); // already active
        assertTrue(sent().isEmpty());
        assertEquals(200, deactivate("n3_admin", "ADMIN", s1).getResponse().getStatus());
        Mockito.clearInvocations(emailService);
        assertEquals(200, deactivate("n3_admin", "ADMIN", s1).getResponse().getStatus()); // already inactive
        assertTrue(sent().isEmpty());
    }

    @Test
    @DisplayName("The account service (Admin): an account WITHOUT a Staff record is announced by the direct branch; one WITH a Staff record exactly once (no duplicate from the delegation)")
    public void accountServicePaths() throws Exception {
        User admin2 = userRepository.findByUsername("n3_admin2").orElseThrow();
        assertEquals(200, call(patch("/api/users/" + admin2.getId() + "/deactivate"), "n3_admin", "ADMIN", null).getResponse().getStatus());
        assertFalse(active("n3_admin2"));
        assertEquals(List.of(mail("n3_admin2")), recipients());
        assertTrue(sent().get(0)[2].contains("Deactivated by: Administrator"));
        Mockito.clearInvocations(emailService);
        assertEquals(200, call(patch("/api/users/" + admin2.getId() + "/activate"), "n3_admin", "ADMIN", null).getResponse().getStatus());
        assertEquals(List.of(mail("n3_admin2")), recipients());
        assertEquals("Taskotel - Your account was reactivated", sent().get(0)[1]);

        Mockito.clearInvocations(emailService);
        User staffUser = userRepository.findByUsername("n3_s3").orElseThrow();
        assertEquals(200, call(patch("/api/users/" + staffUser.getId() + "/deactivate"), "n3_admin", "ADMIN", null).getResponse().getStatus());
        assertEquals(List.of(mail("n3_s3")), recipients(), "exactly one email, although the account service delegates to the staff service");
        Mockito.clearInvocations(emailService);
        assertEquals(200, call(patch("/api/users/" + staffUser.getId() + "/activate"), "n3_admin", "ADMIN", null).getResponse().getStatus());
        assertEquals(List.of(mail("n3_s3")), recipients());
    }

    @Test
    @DisplayName("A refused deactivation (own account, active assignments, outside scope, no permission) sends nothing and changes nothing")
    public void refusedDeactivationsSendNothing() throws Exception {
        User self = userRepository.findByUsername("n3_admin").orElseThrow();
        assertNotEquals(200, call(patch("/api/users/" + self.getId() + "/deactivate"), "n3_admin", "ADMIN", null).getResponse().getStatus());
        assertTrue(active("n3_admin"));

        Task task = new Task();
        task.setTitle("Busy");
        task.setPriority(Priority.MEDIUM);
        task.setStatus(TaskStatus.ASSIGNED);
        task.setEstimatedDuration(30);
        task.setDepartment(dept);
        task.setCreatedBy(admin);
        task = taskRepository.saveAndFlush(task);
        Assignment assignment = new Assignment();
        assignment.setTask(task);
        assignment.setStaff(s1);
        assignmentRepository.saveAndFlush(assignment);
        assertEquals(409, deactivate("n3_admin", "ADMIN", s1).getResponse().getStatus());
        assertTrue(active("n3_s1"));

        assertEquals(403, deactivate("n3_s2", "STAFF", s3).getResponse().getStatus());
        assertEquals(404, deactivate("n3_mgr_other", "MANAGER", s3).getResponse().getStatus());
        assertTrue(sent().isEmpty());
    }

    @Test
    @DisplayName("A SendGrid failure never undoes a deactivation or a reactivation")
    public void failureDoesNotUndoAccountChanges() throws Exception {
        doThrow(new EmailSendingException("down")).when(emailService).sendNotificationEmail(anyString(), anyString(), anyString(), anyString());
        assertEquals(200, deactivate("n3_admin", "ADMIN", s1).getResponse().getStatus());
        assertFalse(active("n3_s1"));
        assertEquals(200, activate("n3_admin", "ADMIN", s1).getResponse().getStatus());
        assertTrue(active("n3_s1"));
    }

    @Test
    @DisplayName("Recipient safety on the real path: an unverified owner, a blank address and a stored SECURITY=false preference")
    public void recipientSafetyOnRealPaths() throws Exception {
        verified("n3_s1", false);
        assertEquals(200, deactivate("n3_admin", "ADMIN", s1).getResponse().getStatus());
        assertTrue(sent().isEmpty(), "an unverified address is never emailed, even for a deactivation");

        User blank = userRepository.findByUsername("n3_s2").orElseThrow();
        blank.setEmail("");
        userRepository.saveAndFlush(blank);
        assertEquals(200, deactivate("n3_admin", "ADMIN", s2).getResponse().getStatus());
        assertTrue(sent().isEmpty(), "a blank address is skipped safely");
        assertFalse(active("n3_s2"));

        User chosen = userRepository.findByUsername("n3_s3").orElseThrow();
        preferenceRepository.saveAndFlush(new NotificationPreference(chosen.getId(), NotificationCategory.SECURITY, true, false));
        assertEquals(200, deactivate("n3_admin", "ADMIN", s3).getResponse().getStatus());
        assertEquals(List.of(mail("n3_s3")), recipients(), "SECURITY ignores a stored false");
    }
}
