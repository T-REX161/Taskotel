package com.hotel.workload.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.workload.staff.Availability;
import com.hotel.workload.staff.Staff;
import com.hotel.workload.staff.StaffRepository;
import com.hotel.workload.staff.StaffService;
import com.hotel.workload.staff.TestDepartments;
import com.hotel.workload.verification.EmailService;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Step 2L: a password change or reset ends earlier sessions (K-01); an Admin account is never permanently deleted (K-05); a deactivated
 * account can neither finish credential setup nor reset its password (K-07). Real security chain and real (H2) database; every test
 * rolls back. (Re-authentication for an email change, K-02, is in {@code EmailChangeControllerTest}.)
 */
@SpringBootTest
@Transactional
public class SessionAndCredentialHardeningTest {

    private static final String OLD_PASSWORD = "Old@Pass123";
    private static final String NEW_PASSWORD = "Brand@New123";
    private static final String P = "s2l_";
    private static final AtomicInteger CLIENTS = new AtomicInteger();

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private UserAccountService accountService;
    @Autowired private StaffService staffService;
    @MockitoBean private EmailService emailService;

    private final ObjectMapper mapper = new ObjectMapper();
    private MockMvc mockMvc;

    @BeforeEach
    public void setUp() {
        SecurityContextHolder.clearContext();
        org.springframework.security.test.context.TestSecurityContextHolder.clearContext();
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        when(emailService.isConfigured()).thenReturn(true);
    }

    @AfterEach
    public void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private User account(String name, Role role, boolean active) {
        User user = new User();
        user.setUsername(P + name);
        user.setEmail(P + name + "@hotel.test");
        user.setPassword(passwordEncoder.encode(OLD_PASSWORD));
        user.setRole(role);
        user.setActive(active);
        user.setEmailVerified(true);
        user.setCredentialsSetupRequired(false);
        return userRepository.saveAndFlush(user);
    }

    private Staff staffRecordFor(User user) {
        Staff staff = new Staff();
        staff.setUser(user);
        staff.setName("Name " + user.getUsername());
        staff.setEmail(user.getEmail());
        staff.setDepartment(TestDepartments.HOUSEKEEPING);
        staff.setAvailability(Availability.AVAILABLE);
        staff.setActive(user.isActive());
        return staffRepository.saveAndFlush(staff);
    }

    private static String freshClient() {
        int n = CLIENTS.incrementAndGet();
        return "10.88." + (n / 250) + "." + (n % 250 + 1);
    }

    private MvcResult send(MockHttpServletRequestBuilder builder, String json) throws Exception {
        String client = freshClient();
        return mockMvc.perform(builder.contentType(MediaType.APPLICATION_JSON).with(r -> { r.setRemoteAddr(client); return r; }).content(json)).andReturn();
    }

    private String loginToken(User user, String password) throws Exception {
        MvcResult login = send(post("/api/auth/login"), "{\"username\":\"" + user.getUsername() + "\",\"password\":\"" + password + "\"}");
        assertEquals(200, login.getResponse().getStatus(), login.getResponse().getContentAsString());
        return mapper.readTree(login.getResponse().getContentAsString()).get("token").asText();
    }

    /** The status of an authenticated read with this token: 200 while the session is valid, 401 once it has ended. */
    private int probe(String token) throws Exception {
        return mockMvc.perform(get("/api/profile").header("Authorization", "Bearer " + token)).andReturn().getResponse().getStatus();
    }

    private MvcResult changePassword(String token, String current, String next) throws Exception {
        return send(put("/api/profile/password").header("Authorization", "Bearer " + token),
                "{\"currentPassword\":\"" + current + "\",\"newPassword\":\"" + next + "\",\"confirmPassword\":\"" + next + "\"}");
    }

    private User withResetCode(User user, String code) {
        user.setPasswordResetCodeHash(passwordEncoder.encode(code));
        user.setPasswordResetExpiresAt(LocalDateTime.now().plusMinutes(10));
        user.setPasswordResetAttemptCount(0);
        return userRepository.saveAndFlush(user);
    }

    private MvcResult confirmReset(User user, String code, String password) throws Exception {
        return send(post("/api/auth/password-reset/confirm"), "{\"email\":\"" + user.getEmail() + "\",\"code\":\"" + code
                + "\",\"newPassword\":\"" + password + "\",\"confirmPassword\":\"" + password + "\"}");
    }

    private void signInAs(String username, String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    // ── K-01: sessions end when the password changes ─────────────────────────

    @Test
    @DisplayName("A password change ends every earlier session; a token issued afterwards works at once")
    public void passwordChangeEndsEarlierSessions() throws Exception {
        for (Role role : Role.values()) {
            User user = account("chg_" + role.name().toLowerCase(), role, true);
            String first = loginToken(user, OLD_PASSWORD);
            String second = loginToken(user, OLD_PASSWORD); // a second device
            assertEquals(200, probe(first), role + ": the session works before the change");
            assertEquals(200, probe(second));

            MvcResult changed = changePassword(first, OLD_PASSWORD, NEW_PASSWORD);
            assertEquals(200, changed.getResponse().getStatus(), role + ": " + changed.getResponse().getContentAsString());

            assertEquals(401, probe(first), role + ": the token used for the change is no longer valid");
            assertEquals(401, probe(second), role + ": nor is the other device's token");

            String fresh = loginToken(user, NEW_PASSWORD);
            assertEquals(200, probe(fresh), role + ": a token issued after the change works immediately (same second included)");
        }
    }

    @Test
    @DisplayName("A failed password change does not end any session")
    public void failedPasswordChangeKeepsSessions() throws Exception {
        User user = account("failchg", Role.STAFF, true);
        String token = loginToken(user, OLD_PASSWORD);
        LocalDateTime before = userRepository.findById(user.getId()).orElseThrow().getPasswordChangedAt();

        assertEquals(400, changePassword(token, "Wrong@Pass999", NEW_PASSWORD).getResponse().getStatus(), "wrong current password");
        assertEquals(400, changePassword(token, OLD_PASSWORD, "weak").getResponse().getStatus(), "a weak new password");
        assertEquals(400, changePassword(token, OLD_PASSWORD, OLD_PASSWORD).getResponse().getStatus(), "the same password");

        assertEquals(200, probe(token), "nothing ended the session");
        assertEquals(before, userRepository.findById(user.getId()).orElseThrow().getPasswordChangedAt(), "and the password version did not move");
    }

    @Test
    @DisplayName("A password reset ends earlier sessions; a failed reset does not")
    public void passwordResetEndsEarlierSessions() throws Exception {
        User user = account("reset", Role.MANAGER, true);
        String session = loginToken(user, OLD_PASSWORD);
        withResetCode(user, "123456");

        // A wrong code and a weak password both fail: the session survives
        assertEquals(400, confirmReset(user, "000000", NEW_PASSWORD).getResponse().getStatus());
        assertEquals(400, confirmReset(user, "123456", "weak").getResponse().getStatus());
        assertEquals(200, probe(session), "a failed reset leaves the session alone");

        MvcResult done = confirmReset(user, "123456", NEW_PASSWORD);
        assertEquals(200, done.getResponse().getStatus(), done.getResponse().getContentAsString());
        assertEquals(401, probe(session), "the reset ended the earlier session");

        String fresh = loginToken(user, NEW_PASSWORD);
        assertEquals(200, probe(fresh));
    }

    @Test
    @DisplayName("The session returned by first-time credential setup is valid (its password version matches the stored one)")
    public void setupSessionWorks() throws Exception {
        User user = account("setup", Role.STAFF, true);
        user.setCredentialsSetupRequired(true);
        user.setSetupToken(SetupTokens.issue());
        userRepository.saveAndFlush(user);

        MvcResult setup = send(put("/api/auth/credentials/setup"), "{\"setupToken\":\"" + user.getSetupToken() + "\",\"newUsername\":\"" + user.getUsername()
                + "\",\"newPassword\":\"" + NEW_PASSWORD + "\",\"confirmPassword\":\"" + NEW_PASSWORD + "\"}");
        assertEquals(200, setup.getResponse().getStatus(), setup.getResponse().getContentAsString());
        String token = mapper.readTree(setup.getResponse().getContentAsString()).get("token").asText();
        assertEquals(200, probe(token), "the session handed out by setup is accepted");
    }

    @Test
    @DisplayName("Signature, expiry, role and active checks are unchanged: a deactivated account's new token and a forged one are still refused")
    public void otherChecksUnchanged() throws Exception {
        User user = account("others", Role.STAFF, true);
        String token = loginToken(user, OLD_PASSWORD);
        assertEquals(200, probe(token));
        String[] parts = token.split("\\.");
        String flipped = (parts[1].charAt(5) == 'A' ? "B" : "A");
        String tampered = parts[0] + "." + parts[1].substring(0, 5) + flipped + parts[1].substring(6) + "." + parts[2]; // a different payload, the old signature
        assertEquals(401, probe(tampered), "a token whose payload was altered");
        assertEquals(401, probe("not.a.token"));

        user.setActive(false);
        userRepository.saveAndFlush(user);
        assertEquals(401, probe(token), "an inactive account is still refused");
    }

    // ── the password-version rule itself ─────────────────────────────────────

    @Test
    @DisplayName("passwordVersion is a pure function of the stored value: no time zone or daylight-saving setting changes it")
    public void passwordVersionIgnoresTimeZones() {
        LocalDateTime stored = LocalDateTime.of(2026, 11, 1, 1, 30, 15, 123_000_000); // inside a US daylight-saving overlap hour
        TimeZone original = TimeZone.getDefault();
        try {
            long expected = JwtUtil.passwordVersion(stored);
            for (String zone : new String[]{"UTC", "America/New_York", "Asia/Kolkata", "Europe/London", "Pacific/Auckland", "Australia/Lord_Howe"}) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                assertEquals(expected, JwtUtil.passwordVersion(stored), zone);
            }
        } finally {
            TimeZone.setDefault(original);
        }
        assertEquals(0L, JwtUtil.passwordVersion(null), "never changed: version 0");
    }

    @Test
    @DisplayName("Sub-millisecond differences (nanosecond in memory, microsecond in the database) do not change the version")
    public void passwordVersionIsMillisecondPrecise() {
        LocalDateTime inMemory = LocalDateTime.of(2026, 5, 5, 10, 0, 0, 123_456_789);
        LocalDateTime inDatabase = LocalDateTime.of(2026, 5, 5, 10, 0, 0, 123_456_000); // as a microsecond column returns it
        assertEquals(JwtUtil.passwordVersion(inMemory), JwtUtil.passwordVersion(inDatabase));
        assertNotEquals(JwtUtil.passwordVersion(inMemory), JwtUtil.passwordVersion(inMemory.plusNanos(1_000_000)), "a real millisecond later is a new version");
    }

    @Test
    @DisplayName("matchesCurrentPassword: the claim must equal the current version; legacy tokens compare issue time at second precision")
    public void matchesCurrentPasswordRules() {
        LocalDateTime changedAt = LocalDateTime.of(2026, 5, 5, 10, 0, 7, 600_000_000); // 10:00:07.600
        long version = JwtUtil.passwordVersion(changedAt);
        long second = (version / 1000) * 1000;

        // With a claim: exact equality
        assertTrue(JwtUtil.matchesCurrentPassword(version, new Date(second - 60_000), changedAt));
        assertFalse(JwtUtil.matchesCurrentPassword(version - 1, new Date(), changedAt));
        assertFalse(JwtUtil.matchesCurrentPassword(0L, new Date(), changedAt), "a token from before any change");
        assertFalse(JwtUtil.matchesCurrentPassword(version, new Date(), changedAt.plusSeconds(1)), "the password changed again");
        assertTrue(JwtUtil.matchesCurrentPassword(0L, new Date(), null), "never changed, never changed");
        assertFalse(JwtUtil.matchesCurrentPassword(version, new Date(), null), "a claim from a later version than the account has");

        // Without a claim (legacy): issue time against the second of the change
        assertTrue(JwtUtil.matchesCurrentPassword(null, new Date(second), changedAt), "issued in the same second as the change: accepted");
        assertTrue(JwtUtil.matchesCurrentPassword(null, new Date(second + 5_000), changedAt));
        assertFalse(JwtUtil.matchesCurrentPassword(null, new Date(second - 1), changedAt), "issued before that second: refused");
        assertFalse(JwtUtil.matchesCurrentPassword(null, null, changedAt), "no issue time and a changed password: refused");
        assertTrue(JwtUtil.matchesCurrentPassword(null, null, null), "an account that never changed its password accepts a legacy token");
    }

    // ── K-05: Admin accounts are never permanently deleted ───────────────────

    @Test
    @DisplayName("Deleting an Admin account is refused (409) through DELETE /api/users and DELETE /api/staff, with or without a staff record")
    public void adminAccountsCannotBeDeletedOverHttp() throws Exception {
        User caller = account("caller", Role.ADMIN, true);
        User bare = account("admin_bare", Role.ADMIN, false);                       // no staff record
        User withStaff = account("admin_staff", Role.ADMIN, false);
        Staff staff = staffRecordFor(withStaff);                                     // with a staff record

        MvcResult byUser = mockMvc.perform(delete("/api/users/" + bare.getId()).with(user(caller.getUsername()).roles("ADMIN"))).andReturn();
        assertEquals(409, byUser.getResponse().getStatus(), byUser.getResponse().getContentAsString());
        assertTrue(byUser.getResponse().getContentAsString().contains(UserAccountService.ADMIN_ACCOUNT_MESSAGE));

        MvcResult byUserWithStaff = mockMvc.perform(delete("/api/users/" + withStaff.getId()).with(user(caller.getUsername()).roles("ADMIN"))).andReturn();
        assertEquals(409, byUserWithStaff.getResponse().getStatus(), byUserWithStaff.getResponse().getContentAsString());

        MvcResult byStaff = mockMvc.perform(delete("/api/staff/" + staff.getId()).with(user(caller.getUsername()).roles("ADMIN"))).andReturn();
        assertEquals(409, byStaff.getResponse().getStatus(), byStaff.getResponse().getContentAsString());
        assertTrue(byStaff.getResponse().getContentAsString().contains(UserAccountService.ADMIN_ACCOUNT_MESSAGE));

        assertTrue(userRepository.findById(bare.getId()).isPresent(), "the account was not deleted");
        assertTrue(userRepository.findById(withStaff.getId()).isPresent());
        assertTrue(staffRepository.findById(staff.getId()).isPresent(), "nor was its staff record");
    }

    @Test
    @DisplayName("Both service paths refuse an Admin account when called directly, as an Admin caller")
    public void adminAccountsCannotBeDeletedThroughTheServices() {
        User caller = account("caller2", Role.ADMIN, true);
        User bare = account("admin_bare2", Role.ADMIN, false);
        User withStaff = account("admin_staff2", Role.ADMIN, false);
        Staff staff = staffRecordFor(withStaff);
        signInAs(caller.getUsername(), "ADMIN");

        assertEquals(409, assertThrows(ResponseStatusException.class, () -> accountService.remove(bare.getId())).getStatusCode().value());
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> accountService.remove(withStaff.getId())).getStatusCode().value());
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> staffService.removeStaff(staff.getId())).getStatusCode().value());
        assertTrue(userRepository.existsById(bare.getId()) && userRepository.existsById(withStaff.getId()) && staffRepository.existsById(staff.getId()));
    }

    @Test
    @DisplayName("Existing deletion rules for other accounts are unchanged: an inactive Staff or Manager account is still removable")
    public void nonAdminDeletionStillWorks() throws Exception {
        User caller = account("caller3", Role.ADMIN, true);
        User staffUser = account("plain_staff", Role.STAFF, false);
        User managerUser = account("plain_manager", Role.MANAGER, false);
        User activeStaff = account("active_staff", Role.STAFF, true);

        assertEquals(204, mockMvc.perform(delete("/api/users/" + staffUser.getId()).with(user(caller.getUsername()).roles("ADMIN"))).andReturn().getResponse().getStatus());
        assertEquals(204, mockMvc.perform(delete("/api/users/" + managerUser.getId()).with(user(caller.getUsername()).roles("ADMIN"))).andReturn().getResponse().getStatus());
        assertEquals(400, mockMvc.perform(delete("/api/users/" + activeStaff.getId()).with(user(caller.getUsername()).roles("ADMIN"))).andReturn().getResponse().getStatus(),
                "an active account still has to be deactivated first");
        assertFalse(userRepository.existsById(staffUser.getId()));
        assertFalse(userRepository.existsById(managerUser.getId()));
        assertTrue(userRepository.existsById(activeStaff.getId()));
    }

    // ── K-07: a deactivated account cannot set up or reset ───────────────────

    @Test
    @DisplayName("A deactivated account cannot finish credential setup: the answer is the one for a bad token, no session is issued and nothing changes")
    public void inactiveAccountCannotCompleteSetup() throws Exception {
        User user = account("inactive_setup", Role.STAFF, false);
        user.setCredentialsSetupRequired(true);
        user.setSetupToken(SetupTokens.issue());
        userRepository.saveAndFlush(user);
        String hashBefore = user.getPassword();
        String token = user.getSetupToken();

        MvcResult refused = send(put("/api/auth/credentials/setup"), "{\"setupToken\":\"" + token + "\",\"newUsername\":\"" + P + "renamed\",\"newPassword\":\""
                + NEW_PASSWORD + "\",\"confirmPassword\":\"" + NEW_PASSWORD + "\"}");
        MvcResult bogus = send(put("/api/auth/credentials/setup"), "{\"setupToken\":\"" + SetupTokens.issue() + "\",\"newUsername\":\"" + P + "renamed\",\"newPassword\":\""
                + NEW_PASSWORD + "\",\"confirmPassword\":\"" + NEW_PASSWORD + "\"}");

        assertEquals(400, refused.getResponse().getStatus(), refused.getResponse().getContentAsString());
        assertEquals(bogus.getResponse().getStatus(), refused.getResponse().getStatus());
        assertEquals(bogus.getResponse().getContentAsString(), refused.getResponse().getContentAsString(), "indistinguishable from a bad token");
        assertFalse(refused.getResponse().getContentAsString().contains("\"token\""), "no session was issued");

        User after = userRepository.findById(user.getId()).orElseThrow();
        assertEquals(P + "inactive_setup", after.getUsername(), "the username was not changed");
        assertEquals(hashBefore, after.getPassword(), "the password was not changed");
        assertTrue(after.isCredentialsSetupRequired(), "setup is still pending");
        assertNull(after.getPasswordChangedAt());
    }

    @Test
    @DisplayName("A deactivated account cannot reset its password: the same answer as an unknown email, nothing changes, the attempt count is untouched")
    public void inactiveAccountCannotResetPassword() throws Exception {
        User user = withResetCode(account("inactive_reset", Role.STAFF, false), "123456");
        String hashBefore = user.getPassword();

        MvcResult refused = confirmReset(user, "123456", NEW_PASSWORD);
        User unknown = new User();
        unknown.setEmail(P + "nobody@hotel.test");
        MvcResult nobody = confirmReset(unknown, "123456", NEW_PASSWORD);

        assertEquals(400, refused.getResponse().getStatus(), refused.getResponse().getContentAsString());
        assertEquals(nobody.getResponse().getStatus(), refused.getResponse().getStatus());
        assertEquals(nobody.getResponse().getContentAsString(), refused.getResponse().getContentAsString(), "indistinguishable from an unknown email");

        User after = userRepository.findById(user.getId()).orElseThrow();
        assertEquals(hashBefore, after.getPassword(), "the password was not changed");
        assertNull(after.getPasswordChangedAt());
        assertEquals(0, after.getPasswordResetAttemptCount());
        assertNotNull(after.getPasswordResetCodeHash(), "the code was left as it was");
    }

    @Test
    @DisplayName("Reactivating the account makes the same setup token and reset code work again (the refusal changed nothing)")
    public void reactivatedAccountsWorkAgain() throws Exception {
        User resetUser = withResetCode(account("reactivate_reset", Role.STAFF, false), "123456");
        assertEquals(400, confirmReset(resetUser, "123456", NEW_PASSWORD).getResponse().getStatus());
        resetUser.setActive(true);
        userRepository.saveAndFlush(resetUser);
        assertEquals(200, confirmReset(resetUser, "123456", NEW_PASSWORD).getResponse().getStatus());

        User setupUser = account("reactivate_setup", Role.STAFF, false);
        setupUser.setCredentialsSetupRequired(true);
        setupUser.setSetupToken(SetupTokens.issue());
        userRepository.saveAndFlush(setupUser);
        String body = "{\"setupToken\":\"" + setupUser.getSetupToken() + "\",\"newUsername\":\"" + setupUser.getUsername() + "\",\"newPassword\":\""
                + NEW_PASSWORD + "\",\"confirmPassword\":\"" + NEW_PASSWORD + "\"}";
        assertEquals(400, send(put("/api/auth/credentials/setup"), body).getResponse().getStatus());
        setupUser.setActive(true);
        userRepository.saveAndFlush(setupUser);
        assertEquals(200, send(put("/api/auth/credentials/setup"), body).getResponse().getStatus());
    }
}
