package com.hotel.workload.security;

import com.hotel.workload.staff.StaffRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** PUT /api/profile/password over HTTP, through the real security chain, controller, service, BCrypt and (in-memory) database. */
@SpringBootTest
public class PasswordChangeControllerTest {

    private static final String PREFIX = "pc_";
    private static final String PATH = "/api/profile/password";
    private static final String OLD = "Correct@123";
    private static final String NEW = "Brand#New9pw";

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private MockMvc mockMvc;
    private int clients = 0;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        cleanUp();
    }

    @AfterEach
    void cleanUp() {
        staffRepository.findAllWithUser().stream().filter(s -> s.getUser().getUsername().startsWith(PREFIX)).forEach(staffRepository::delete);
        userRepository.findAll().stream().filter(u -> u.getUsername().startsWith(PREFIX)).forEach(userRepository::delete);
    }

    private User account(String name, Role role) {
        User user = new User();
        user.setUsername(PREFIX + name);
        user.setEmail(PREFIX + name + "@hotel.test");
        user.setPassword(passwordEncoder.encode(OLD));
        user.setRole(role);
        user.setActive(true);
        user.setEmailVerified(true);
        user.setCredentialsSetupRequired(false);
        return userRepository.saveAndFlush(user);
    }

    /** A fresh client address per call unless one is given, so the rate limiter (shared by the cached context) never gets in the way. */
    private MvcResult change(User as, String json) throws Exception {
        return change(as, json, "10.9.8." + (++clients % 250));
    }

    private MvcResult change(User as, String json, String address) throws Exception {
        AbstractMockHttpServletRequestBuilder<?> request = put(PATH).contentType(MediaType.APPLICATION_JSON).content(json);
        return mockMvc.perform(request.with(user(as.getUsername()).roles(as.getRole().name())).with(r -> { r.setRemoteAddr(address); return r; })).andReturn();
    }

    private static String body(String current, String next, String confirm) {
        return "{\"currentPassword\":" + q(current) + ",\"newPassword\":" + q(next) + ",\"confirmPassword\":" + q(confirm) + "}";
    }

    private static String q(String s) {
        return s == null ? "null" : "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String text(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    private User reload(User u) {
        return userRepository.findById(u.getId()).orElseThrow();
    }

    private void assertUnchanged(User u) {
        User after = reload(u);
        assertEquals(u.getPassword(), after.getPassword(), "the stored hash must be untouched");
        assertTrue(passwordEncoder.matches(OLD, after.getPassword()));
    }

    private int login(String username, String password, String address) throws Exception {
        return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .with(r -> { r.setRemoteAddr(address); return r; })
                .content("{\"username\":" + q(username) + ",\"password\":" + q(password) + "}")).andReturn().getResponse().getStatus();
    }

    // ── success ────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Every role can change its own password: the BCrypt hash is replaced, the date recorded, login works with the new one only")
    void successForEveryRole() throws Exception {
        for (Role role : Role.values()) {
            User me = account(role.name().toLowerCase(), role);
            String before = me.getPassword();
            LocalDateTime start = LocalDateTime.now().minusSeconds(1);

            MvcResult result = change(me, body(OLD, NEW, NEW));

            assertEquals(200, result.getResponse().getStatus(), role + ": " + text(result));
            User after = reload(me);
            assertNotEquals(before, after.getPassword());
            assertTrue(after.getPassword().startsWith("$2"), "stored as a BCrypt hash");
            assertNotEquals(NEW, after.getPassword(), "never stored as plain text");
            assertTrue(passwordEncoder.matches(NEW, after.getPassword()));
            assertFalse(passwordEncoder.matches(OLD, after.getPassword()));
            assertNotNull(after.getPasswordChangedAt());
            assertTrue(after.getPasswordChangedAt().isAfter(start), "Account Activity's last password change is updated");
            assertEquals(me.getEmail(), after.getEmail());
            assertEquals(me.getRole(), after.getRole());
            assertEquals(me.getUsername(), after.getUsername());
            assertTrue(after.isActive() && after.isEmailVerified() && !after.isCredentialsSetupRequired());

            String ip = "10.7.7." + (++clients % 250);
            assertEquals(200, login(me.getUsername(), NEW, ip), role + " signs in with the new password");
            assertEquals(401, login(me.getUsername(), OLD, ip), role + " no longer signs in with the old one");
        }
    }

    @Test
    @DisplayName("The response is a short message only: no password, hash or token")
    void responseHoldsNoSecrets() throws Exception {
        User me = account("clean", Role.ADMIN);
        String json = text(change(me, body(OLD, NEW, NEW)));
        assertEquals("{\"message\":\"Your password has been changed.\"}", json);
        for (String forbidden : new String[] {OLD, NEW, "$2", "token"}) assertFalse(json.contains(forbidden), forbidden);
    }

    @Test
    @DisplayName("The request never prints its passwords (toString is redacted)")
    void requestToStringIsRedacted() {
        String printed = new ChangePasswordRequest(OLD, NEW, NEW).toString();
        assertFalse(printed.contains(OLD) || printed.contains(NEW), printed);
    }

    // ── refusals ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("A wrong current password is refused with 400 (not 401, which would sign the user out) and changes nothing")
    void wrongCurrentPassword() throws Exception {
        User me = account("wrong", Role.MANAGER);
        MvcResult result = change(me, body("Not@Right1", NEW, NEW));
        assertEquals(400, result.getResponse().getStatus());
        assertTrue(text(result).contains(PasswordChangeService.WRONG_CURRENT_MESSAGE), text(result));
        assertFalse(text(result).contains("Not@Right1") || text(result).contains(NEW));
        assertUnchanged(me);
    }

    @Test
    @DisplayName("A confirmation that does not match is refused and changes nothing")
    void confirmationMismatch() throws Exception {
        User me = account("mismatch", Role.STAFF);
        MvcResult result = change(me, body(OLD, NEW, NEW + "x"));
        assertEquals(400, result.getResponse().getStatus());
        assertTrue(text(result).contains(PasswordChangeService.MISMATCH_MESSAGE));
        assertUnchanged(me);
    }

    @Test
    @DisplayName("A new password identical to the current one is refused")
    void sameAsCurrent() throws Exception {
        User me = account("same", Role.STAFF);
        MvcResult result = change(me, body(OLD, OLD, OLD));
        assertEquals(400, result.getResponse().getStatus());
        assertTrue(text(result).contains(PasswordChangeService.SAME_MESSAGE), text(result));
        assertUnchanged(me);
    }

    @Test
    @DisplayName("Weak passwords are rejected by the server: short, no uppercase, no lowercase, no digit, no special character, spaces, too long")
    void weakPasswords() throws Exception {
        User me = account("weak", Role.MANAGER);
        for (String weak : new String[] {"Ab1@", "alllower1@x", "ALLUPPER1@X", "NoDigits@@x", "NoSpecial123x", "Has Space1@x", "A1@" + "a".repeat(80)}) {
            MvcResult result = change(me, body(OLD, weak, weak));
            assertEquals(400, result.getResponse().getStatus(), weak + " -> " + text(result));
            assertFalse(text(result).contains(weak), "the response never echoes the password");
        }
        assertUnchanged(me);
        assertTrue(text(change(me, body(OLD, "short", "short"))).contains("at least 8 characters"), "clear requirement message");
    }

    @Test
    @DisplayName("Missing or empty fields and a body that is not JSON are refused")
    void missingFields() throws Exception {
        User me = account("missing", Role.STAFF);
        for (String bad : new String[] {"{}", body(null, NEW, NEW), body(OLD, null, NEW), body(OLD, NEW, null), body("", NEW, NEW), body(OLD, "", "")}) {
            assertEquals(400, change(me, bad).getResponse().getStatus(), bad);
        }
        assertEquals(400, change(me, "not json").getResponse().getStatus());
        assertUnchanged(me);
    }

    // ── identity and authorization ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Without a token the endpoint is refused and no password changes")
    void requiresAuthentication() throws Exception {
        User me = account("anon", Role.STAFF);
        int status = mockMvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content(body(OLD, NEW, NEW))).andReturn().getResponse().getStatus();
        assertTrue(status == 401 || status == 403, "status " + status);
        assertUnchanged(me);
        // a garbage token is no better
        int bad = mockMvc.perform(put(PATH).header("Authorization", "Bearer not.a.token").contentType(MediaType.APPLICATION_JSON).content(body(OLD, NEW, NEW)))
                .andReturn().getResponse().getStatus();
        assertTrue(bad == 401 || bad == 403, "status " + bad);
        assertUnchanged(me);
    }

    @Test
    @DisplayName("Only the signed-in account changes: a userId, username or id in the body or query is ignored, and another account is never touched")
    void cannotChangeAnotherUser() throws Exception {
        User alice = account("alice", Role.STAFF);
        User bob = account("bob", Role.ADMIN);

        MvcResult result = mockMvc.perform(put(PATH).param("userId", bob.getId().toString()).param("username", bob.getUsername())
                .contentType(MediaType.APPLICATION_JSON)
                .with(user(alice.getUsername()).roles("STAFF")).with(r -> { r.setRemoteAddr("10.6.6.6"); return r; })
                .content("{\"userId\":\"" + bob.getId() + "\",\"username\":\"" + bob.getUsername() + "\",\"id\":\"" + bob.getId()
                        + "\",\"currentPassword\":" + q(OLD) + ",\"newPassword\":" + q(NEW) + ",\"confirmPassword\":" + q(NEW) + "}")).andReturn();

        assertEquals(200, result.getResponse().getStatus(), text(result));
        assertTrue(passwordEncoder.matches(NEW, reload(alice).getPassword()), "alice's password changed");
        assertUnchanged(bob);
        assertNull(reload(bob).getPasswordChangedAt());
    }

    @Test
    @DisplayName("Admin, Manager and Staff are isolated: one user's change never reaches another, and each needs their own current password")
    void rolesAreIndependent() throws Exception {
        User admin = account("iadmin", Role.ADMIN);
        User manager = account("imanager", Role.MANAGER);
        User staff = account("istaff", Role.STAFF);
        assertEquals(200, change(manager, body(OLD, NEW, NEW)).getResponse().getStatus());
        assertUnchanged(admin);
        assertUnchanged(staff);
        // the manager's new password is not a valid "current" password for anyone else
        assertEquals(400, change(staff, body(NEW, "Other#Pass77", "Other#Pass77")).getResponse().getStatus());
        assertUnchanged(staff);
    }

    @Test
    @DisplayName("Accounts that still have to finish first-time setup (or verify their email) cannot use Change Password to skip it")
    void firstTimeSetupCannotBeBypassed() throws Exception {
        User pending = account("pending", Role.STAFF);
        pending.setCredentialsSetupRequired(true);
        userRepository.saveAndFlush(pending);
        MvcResult result = change(pending, body(OLD, NEW, NEW));
        assertEquals(403, result.getResponse().getStatus(), text(result));
        assertTrue(text(result).contains(PasswordChangeService.SETUP_PENDING_MESSAGE));
        assertUnchanged(pending);
        assertTrue(reload(pending).isCredentialsSetupRequired(), "still has to complete setup");

        User unverified = account("unverified", Role.MANAGER);
        unverified.setEmailVerified(false);
        userRepository.saveAndFlush(unverified);
        assertEquals(403, change(unverified, body(OLD, NEW, NEW)).getResponse().getStatus());
        assertUnchanged(unverified);
    }

    @Test
    @DisplayName("First-time setup still works as before, and the account can then change its password normally")
    void firstTimeSetupStillWorks() throws Exception {
        User pending = account("setup", Role.STAFF);
        pending.setCredentialsSetupRequired(true);
        pending.setSetupToken(SetupTokens.issue());
        userRepository.saveAndFlush(pending);

        MvcResult setup = mockMvc.perform(put("/api/auth/credentials/setup").contentType(MediaType.APPLICATION_JSON)
                .with(r -> { r.setRemoteAddr("10.5.5.5"); return r; })
                .content("{\"setupToken\":" + q(pending.getSetupToken()) + ",\"newUsername\":" + q(PREFIX + "setup") + ",\"newPassword\":" + q(NEW)
                        + ",\"confirmPassword\":" + q(NEW) + "}")).andReturn();
        assertEquals(200, setup.getResponse().getStatus(), text(setup));
        User done = reload(pending);
        assertFalse(done.isCredentialsSetupRequired());
        assertTrue(passwordEncoder.matches(NEW, done.getPassword()));

        assertEquals(200, change(done, body(NEW, "Second#Pass42", "Second#Pass42")).getResponse().getStatus());
    }

    // ── limits and login ──────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Guessing the current password is rate limited: after repeated wrong tries even the right password is refused for a while")
    void wrongGuessesAreRateLimited() throws Exception {
        User me = account("limited", Role.ADMIN);
        String ip = "10.4.4.4";
        for (int i = 0; i < 5; i++) {
            assertEquals(400, change(me, body("Wrong@Guess" + i, NEW, NEW), ip).getResponse().getStatus());
        }
        MvcResult blocked = change(me, body(OLD, NEW, NEW), ip);
        assertEquals(429, blocked.getResponse().getStatus(), text(blocked));
        assertUnchanged(me);
        // another client address is not affected by this one's guesses (the account key allows 30)
        assertEquals(200, change(me, body(OLD, NEW, NEW), "10.4.4.5").getResponse().getStatus());
    }

    @Test
    @DisplayName("Existing login is untouched: the old password still signs in until it is changed, and a wrong password is still refused")
    void loginStillWorks() throws Exception {
        User me = account("login", Role.MANAGER);
        String ip = "10.3.3." + (++clients % 250);
        assertEquals(200, login(me.getUsername(), OLD, ip));
        assertEquals(401, login(me.getUsername(), "Wrong@Pass1", ip));
    }
}
