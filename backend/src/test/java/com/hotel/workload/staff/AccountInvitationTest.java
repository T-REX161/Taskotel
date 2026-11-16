package com.hotel.workload.staff;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.hotel.workload.security.Role;
import com.hotel.workload.security.ScopeFixtures;
import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
import com.hotel.workload.verification.EmailSendingException;
import com.hotel.workload.verification.EmailService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * The invitation email after an Admin or a Manager creates an account, over HTTP with the real security chain and real transactions, and
 * the first sign-in with the emailed credentials. SendGrid is a mock; nothing is ever sent.
 */
@SpringBootTest
public class AccountInvitationTest {

    private static final String DEPT = "INV_DEPT";
    private static final String LOGIN_URL = "http://localhost:3000/login";
    private static final String NEW_PASSWORD = "Brand@New123";
    private static final String[] FIXTURE_USERS = {"inv_admin", "inv_mgr"};

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private EmailService emailService;

    private MockMvc mockMvc;
    private ScopeFixtures fixtures;
    private ListAppender<ILoggingEvent> logs;
    private Logger rootLogger;
    private int sequence;

    @BeforeEach
    public void setUp() {
        org.springframework.security.test.context.TestSecurityContextHolder.clearContext();
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        when(emailService.isConfigured()).thenReturn(true);
        fixtures = new ScopeFixtures(userRepository, staffRepository, departmentRepository, passwordEncoder);
        Department department = fixtures.department(DEPT);
        fixtures.account("inv_admin", Role.ADMIN, true);
        fixtures.manager("inv_mgr", department);
        rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        logs = new ListAppender<>();
        logs.start();
        rootLogger.addAppender(logs);
        Mockito.clearInvocations(emailService);
    }

    @AfterEach
    public void cleanUp() {
        rootLogger.detachAppender(logs);
        staffRepository.findAllWithUser().stream().filter(s -> s.getUser().getUsername().startsWith("inv_")).forEach(staffRepository::delete);
        staffRepository.flush();
        userRepository.findAll().stream().filter(u -> u.getUsername().startsWith("inv_")).forEach(userRepository::delete);
        userRepository.flush();
        departmentRepository.findByCode(DEPT).ifPresent(departmentRepository::delete);
        departmentRepository.flush();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────────────────

    private String name() {
        return "inv_new" + (++sequence) + "_" + Math.abs(System.nanoTime() % 100000);
    }

    private static String createBody(String username, String email, String password, String role, String department) {
        return "{\"fullName\":\"Invited Person\",\"email\":\"" + email + "\",\"phone\":\"555-0100\",\"department\":\"" + department
                + "\",\"availability\":\"AVAILABLE\",\"username\":\"" + username + "\",\"temporaryPassword\":\"" + password + "\",\"role\":\"" + role + "\"}";
    }

    private MvcResult create(String actor, String actorRole, String body) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/staff").with(user(actor).roles(actorRole)).contentType(MediaType.APPLICATION_JSON).content(body);
        return mockMvc.perform(request).andReturn();
    }

    private static String temp(int n) {
        return "Tmp@Pass" + n + "xYz";
    }

    private void assertNoPasswordInLogs(String... secrets) {
        for (ILoggingEvent event : logs.list) {
            String all = event.getFormattedMessage() + (event.getThrowableProxy() == null ? "" : event.getThrowableProxy().getMessage());
            for (String secret : secrets) assertFalse(all.contains(secret), "a log line contains the temporary password: " + event.getLoggerName());
        }
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    private static String field(MvcResult result, String name) throws Exception {
        Matcher matcher = Pattern.compile("\"" + name + "\":\"([^\"]+)\"").matcher(body(result));
        return matcher.find() ? matcher.group(1) : null;
    }

    private MvcResult send(String path, String client, String json, boolean put) throws Exception {
        MockHttpServletRequestBuilder builder = put ? put(path) : post(path);
        return mockMvc.perform(builder.contentType(MediaType.APPLICATION_JSON).with(request -> {
            request.setRemoteAddr(client);
            return request;
        }).content(json)).andReturn();
    }

    private MvcResult login(String client, String username, String password) throws Exception {
        return send("/api/auth/login", client, "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}", false);
    }

    // ── the invitation is sent for accounts created by an Admin or a Manager ────────────────────────────────────

    @Test
    @DisplayName("An Admin creates a STAFF, a MANAGER and an ADMIN account: one invitation each, to that account's address, with its username, its temporary password and the login link")
    public void adminCreatesAccounts() throws Exception {
        int n = 0;
        for (String role : new String[] {"STAFF", "MANAGER", "ADMIN"}) {
            String username = name();
            String email = username + "@hotel.test";
            String password = temp(++n);
            MvcResult result = create("inv_admin", "ADMIN", createBody(username, email, password, role, DEPT));
            assertEquals(201, result.getResponse().getStatus(), body(result));

            verify(emailService, times(1)).sendAccountInvitationEmail(email, username, password, LOGIN_URL);
            assertTrue(body(result).contains("\"invitationEmailSent\":true"), body(result));
            assertFalse(body(result).contains(password), "the temporary password is never in the API response");
            User stored = userRepository.findByUsername(username).orElseThrow();
            assertEquals(Role.valueOf(role), stored.getRole());
            assertFalse(stored.isEmailVerified());
            assertTrue(stored.isCredentialsSetupRequired(), "the first-time setup is still required");
            assertNotEquals(password, stored.getPassword(), "stored hashed");
            assertTrue(passwordEncoder.matches(password, stored.getPassword()));
        }
        verify(emailService, times(3)).sendAccountInvitationEmail(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("A Manager creates a STAFF account in their own department: the invitation is sent")
    public void managerCreatesAnAccount() throws Exception {
        String username = name();
        String email = username + "@hotel.test";
        MvcResult result = create("inv_mgr", "MANAGER", createBody(username, email, temp(10), "STAFF", DEPT));
        assertEquals(201, result.getResponse().getStatus(), body(result));
        verify(emailService, times(1)).sendAccountInvitationEmail(email, username, temp(10), LOGIN_URL);
        assertTrue(body(result).contains("\"invitationEmailSent\":true"));
    }

    @Test
    @DisplayName("A refused creation sends nothing: duplicate username, duplicate email, weak password, missing email, a Manager asking for a Manager account, no permission")
    public void refusedCreationsSendNothing() throws Exception {
        String taken = name();
        assertEquals(201, create("inv_admin", "ADMIN", createBody(taken, taken + "@hotel.test", temp(20), "STAFF", DEPT)).getResponse().getStatus());
        Mockito.clearInvocations(emailService);

        assertEquals(409, create("inv_admin", "ADMIN", createBody(taken, name() + "@hotel.test", temp(21), "STAFF", DEPT)).getResponse().getStatus(), "username taken");
        assertEquals(409, create("inv_admin", "ADMIN", createBody(name(), taken + "@hotel.test", temp(22), "STAFF", DEPT)).getResponse().getStatus(), "email taken");
        assertEquals(400, create("inv_admin", "ADMIN", createBody(name(), name() + "@hotel.test", "weak", "STAFF", DEPT)).getResponse().getStatus(), "weak password");
        assertEquals(400, create("inv_admin", "ADMIN", createBody(name(), "", temp(23), "STAFF", DEPT)).getResponse().getStatus(), "no email");
        assertEquals(403, create("inv_mgr", "MANAGER", createBody(name(), name() + "@hotel.test", temp(24), "MANAGER", DEPT)).getResponse().getStatus(), "a Manager cannot create a Manager");
        assertEquals(403, create("inv_mgr", "STAFF", createBody(name(), name() + "@hotel.test", temp(25), "STAFF", DEPT)).getResponse().getStatus(), "Staff cannot create accounts");
        verify(emailService, never()).sendAccountInvitationEmail(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("A creation that is rolled back (an unknown department, found after the login account was saved) leaves no account and sends no invitation")
    public void rolledBackCreationSendsNothing() throws Exception {
        String username = name();
        MvcResult result = create("inv_admin", "ADMIN", createBody(username, username + "@hotel.test", temp(30), "STAFF", "NO_SUCH_DEPARTMENT"));
        assertTrue(result.getResponse().getStatus() >= 400, body(result));
        assertTrue(userRepository.findByUsername(username).isEmpty(), "the whole creation was rolled back");
        verify(emailService, never()).sendAccountInvitationEmail(anyString(), anyString(), anyString(), anyString());
    }

    // ── delivery failure ─────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("When the email cannot be sent the account is still created, the creator is told it was not sent, and no log line contains the temporary password")
    public void deliveryFailureKeepsTheAccount() throws Exception {
        doThrow(new EmailSendingException("SendGrid is down")).when(emailService).sendAccountInvitationEmail(anyString(), anyString(), anyString(), anyString());
        String username = name();
        String password = temp(40);
        MvcResult result = create("inv_admin", "ADMIN", createBody(username, username + "@hotel.test", password, "STAFF", DEPT));

        assertEquals(201, result.getResponse().getStatus(), body(result));
        assertTrue(body(result).contains("\"invitationEmailSent\":false"), "the invitation is not claimed as sent: " + body(result));
        assertFalse(body(result).contains(password));
        assertTrue(userRepository.findByUsername(username).isPresent(), "the account was not rolled back or deleted");
        assertNoPasswordInLogs(password);
        assertTrue(logs.list.stream().anyMatch(e -> e.getFormattedMessage().contains("account invitation email could not be sent")), "the failure is logged");
    }

    @Test
    @DisplayName("When email is not configured at all the account is still created and the response says the invitation was not sent")
    public void emailNotConfigured() throws Exception {
        when(emailService.isConfigured()).thenReturn(false);
        String username = name();
        MvcResult result = create("inv_admin", "ADMIN", createBody(username, username + "@hotel.test", temp(41), "STAFF", DEPT));
        assertEquals(201, result.getResponse().getStatus());
        assertTrue(body(result).contains("\"invitationEmailSent\":false"));
        assertTrue(userRepository.findByUsername(username).isPresent());
        verify(emailService, never()).sendAccountInvitationEmail(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("A successful creation logs no temporary password either, and the flag appears only on the create answer, not in the staff list")
    public void successLogsNothingSensitive() throws Exception {
        String username = name();
        String password = temp(42);
        assertEquals(201, create("inv_admin", "ADMIN", createBody(username, username + "@hotel.test", password, "STAFF", DEPT)).getResponse().getStatus());
        assertNoPasswordInLogs(password);
        MvcResult list = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/staff").with(user("inv_admin").roles("ADMIN"))).andReturn();
        assertFalse(body(list).contains("invitationEmailSent"), "left out everywhere except the create answer");
        assertFalse(body(list).contains(password));
    }

    // ── the first sign-in with the emailed credentials, for every role ──────────────────────────────────────────

    @Test
    @DisplayName("An invited STAFF, MANAGER and ADMIN signs in with the emailed credentials: code emailed, verification, password setup, then only the new password works")
    public void firstSignInFollowsTheExistingFlowForEveryRole() throws Exception {
        int n = 100;
        for (String role : new String[] {"STAFF", "MANAGER", "ADMIN"}) {
            String username = name();
            String email = username + "@hotel.test";
            String password = temp(++n);
            assertEquals(201, create("inv_admin", "ADMIN", createBody(username, email, password, role, DEPT)).getResponse().getStatus());
            ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> pass = ArgumentCaptor.forClass(String.class);
            verify(emailService).sendAccountInvitationEmail(eq(email), user.capture(), pass.capture(), eq(LOGIN_URL));
            Mockito.clearInvocations(emailService);
            String client = "10.9.0." + n;

            // the credentials from the email: valid, but no session yet, and a verification code is emailed
            MvcResult first = login(client, user.getValue(), pass.getValue());
            assertTrue(body(first).contains("\"emailVerificationRequired\":true"), role + ": " + body(first));
            assertFalse(body(first).contains("\"token\":\""), "no session before verification");
            ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
            verify(emailService, atLeastOnce()).sendVerificationEmail(eq(email), code.capture());

            MvcResult verified = send("/api/auth/email-verification/verify", client,
                    "{\"identifier\":\"" + username + "\",\"code\":\"" + code.getValue() + "\"}", false);
            assertEquals(200, verified.getResponse().getStatus(), body(verified));
            String setupToken = field(verified, "setupToken");
            assertNotNull(setupToken, role + ": verification leads to the password setup");
            assertFalse(body(verified).contains("\"token\":\""));
            assertFalse(body(login(client, username, pass.getValue())).contains("\"token\":\""), "still no session before the password is set");

            MvcResult done = send("/api/auth/credentials/setup", client, "{\"setupToken\":\"" + setupToken + "\",\"newUsername\":\"" + username
                    + "\",\"newPassword\":\"" + NEW_PASSWORD + "\",\"confirmPassword\":\"" + NEW_PASSWORD + "\"}", true);
            assertEquals(200, done.getResponse().getStatus(), body(done));
            assertEquals(role, field(done, "role"));

            assertEquals(401, login(client, username, pass.getValue()).getResponse().getStatus(), "the temporary password is dead once the new one is set");
            MvcResult in = login(client, username, NEW_PASSWORD);
            assertEquals(200, in.getResponse().getStatus());
            assertNotNull(field(in, "token"));
            assertNoPasswordInLogs(pass.getValue());
        }
    }

    // ── the temporary password expires ───────────────────────────────────────────────────────────────────────────

    private void backdate(String username, long hours) {
        jdbc.update("update users set created_at = ? where username = ?", LocalDateTime.now().minusHours(hours), username);
    }

    @Test
    @DisplayName("The temporary password works for 24 hours; after that sign-in says it expired, a wrong password still gets the ordinary answer, and Forgot password restores the way in")
    public void temporaryPasswordExpires() throws Exception {
        String username = name();
        String email = username + "@hotel.test";
        String password = temp(200);
        assertEquals(201, create("inv_admin", "ADMIN", createBody(username, email, password, "STAFF", DEPT)).getResponse().getStatus());

        backdate(username, 23);
        assertTrue(body(login("10.9.1.1", username, password)).contains("\"emailVerificationRequired\":true"), "still valid after 23 hours");

        backdate(username, 25);
        MvcResult expired = login("10.9.1.2", username, password);
        assertEquals(401, expired.getResponse().getStatus());
        assertTrue(body(expired).contains("temporary password has expired"), body(expired));
        MvcResult wrong = login("10.9.1.3", username, "Wrong@Pass999");
        assertEquals(401, wrong.getResponse().getStatus());
        assertFalse(body(wrong).contains("expired"), "only someone who knows the password learns it expired");

        // Forgot password sets a new password (recorded as a password change), so the account is no longer on its temporary one:
        // sign-in works again and goes through the same verification and setup
        User stored = userRepository.findByUsername(username).orElseThrow();
        userRepository.updatePassword(stored.getId(), passwordEncoder.encode("Reset@Pass123"), LocalDateTime.now());
        MvcResult afterReset = login("10.9.1.4", username, "Reset@Pass123");
        assertTrue(body(afterReset).contains("\"emailVerificationRequired\":true"), body(afterReset));
    }

    @Test
    @DisplayName("Accounts that finished their setup are never affected by the expiry, however old they are")
    public void finishedAccountsAreNotAffected() throws Exception {
        String username = name();
        User finished = new User();
        finished.setUsername(username);
        finished.setEmail(username + "@hotel.test");
        finished.setPassword(passwordEncoder.encode("Done@Pass123"));
        finished.setRole(Role.STAFF);
        finished.setActive(true);
        finished.setEmailVerified(true);
        finished.setCredentialsSetupRequired(false);
        userRepository.saveAndFlush(finished);
        backdate(username, 24 * 400);
        MvcResult in = login("10.9.2.1", username, "Done@Pass123");
        assertEquals(200, in.getResponse().getStatus(), body(in));
        assertNotNull(field(in, "token"));
    }
}
