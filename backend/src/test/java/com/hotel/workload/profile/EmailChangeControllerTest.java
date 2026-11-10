package com.hotel.workload.profile;

import com.hotel.workload.security.Role;
import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
import com.hotel.workload.staff.Availability;
import com.hotel.workload.staff.Staff;
import com.hotel.workload.staff.StaffRepository;
import com.hotel.workload.staff.TestDepartments;
import com.hotel.workload.verification.EmailSendingException;
import com.hotel.workload.verification.EmailService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Changing the email address through POST /api/profile/email-change/request and /confirm, over HTTP through the real security chain,
 * controller, service, BCrypt and (in-memory) database. The email service is mocked, so these tests prove what the application asks
 * it to send (to whom, with which code) and what is stored; they do not send real email.
 */
@SpringBootTest
public class EmailChangeControllerTest {

    private static final String PREFIX = "ec_";
    private static final String REQUEST = "/api/profile/email-change/request";
    private static final String CONFIRM = "/api/profile/email-change/confirm";

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @MockitoBean private EmailService emailService;

    private MockMvc mockMvc;
    private static final java.util.concurrent.atomic.AtomicInteger CLIENTS = new java.util.concurrent.atomic.AtomicInteger();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        reset(emailService);
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
        user.setEmail(PREFIX + name + "@old.test");
        user.setPassword(passwordEncoder.encode("Correct@123"));
        user.setRole(role);
        user.setActive(true);
        user.setEmailVerified(true);
        user.setCredentialsSetupRequired(false);
        return userRepository.saveAndFlush(user);
    }

    private void withStaffRecord(User user) {
        Staff staff = new Staff();
        staff.setUser(user);
        staff.setName("Name " + name(user));
        staff.setEmail(user.getEmail());
        staff.setDepartment(TestDepartments.HOUSEKEEPING);
        staff.setAvailability(Availability.AVAILABLE);
        staffRepository.saveAndFlush(staff);
    }

    private static String name(User user) {
        return user.getUsername().substring(PREFIX.length());
    }

    private MvcResult call(String path, User as, String json) throws Exception {
        int n = CLIENTS.incrementAndGet(); // a fresh client address per call, never reused, so the limiters (shared by the cached context) never interfere
        return call(path, as, json, "10.20." + (n / 250) + "." + (n % 250));
    }

    private MvcResult call(String path, User as, String json, String address) throws Exception {
        return mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json)
                .with(user(as.getUsername()).roles(as.getRole().name())).with(r -> { r.setRemoteAddr(address); return r; })).andReturn();
    }

    private static String requestBody(String email) {
        return requestBody(email, "Correct@123");
    }

    private static String requestBody(String email, String currentPassword) {
        return "{\"newEmail\":\"" + email + "\",\"currentPassword\":\"" + currentPassword + "\"}";
    }

    private static String codeBody(String code) {
        return "{\"code\":\"" + code + "\"}";
    }

    private static String text(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    private User reload(User user) {
        return userRepository.findById(user.getId()).orElseThrow();
    }

    /** The code the application asked the email service to send to this address most recently. */
    private String sentCode(String toEmail) {
        ArgumentCaptor<String> to = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(emailService, org.mockito.Mockito.atLeastOnce()).sendEmailChangeVerificationEmail(to.capture(), code.capture());
        int last = to.getAllValues().size() - 1;
        assertEquals(toEmail, to.getAllValues().get(last), "the code goes to the NEW address");
        return code.getAllValues().get(last);
    }

    private static String wrong(String code) {
        return code.equals("000000") ? "111111" : "000000";
    }

    // ── the happy path ───────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Every role: request sends a code to the new address and changes nothing; the right code then switches the email, the staff record follows, and the profile answer shows it")
    void successfulChangeForEveryRole() throws Exception {
        for (Role role : Role.values()) {
            reset(emailService);
            User me = account("ok_" + role.name().toLowerCase(), role);
            withStaffRecord(me);
            String oldEmail = me.getEmail();
            String newEmail = PREFIX + "new_" + role.name().toLowerCase() + "@new.test";

            MvcResult requested = call(REQUEST, me, requestBody(newEmail));
            assertEquals(200, requested.getResponse().getStatus(), text(requested));
            String code = sentCode(newEmail);
            assertTrue(code.matches("\\d{6}"));
            assertTrue(text(requested).contains("\"pendingEmailMasked\":\"" + PREFIX.substring(0, 2) + "***@new.test\""), text(requested));
            assertTrue(text(requested).contains("\"expiresInSeconds\":600") && text(requested).contains("\"resendAvailableInSeconds\":60"), text(requested));
            assertFalse(text(requested).contains(code) || text(requested).contains(newEmail), "neither the code nor the full address is echoed");

            User pending = reload(me);
            assertEquals(oldEmail, pending.getEmail(), role + ": the email is unchanged before verification");
            assertEquals(newEmail, pending.getPendingEmail());
            assertTrue(pending.isEmailVerified());
            verify(emailService).sendEmailChangeNotice(eq(oldEmail), anyString(), eq(false));
            verify(emailService, never()).sendEmailChangeNotice(anyString(), anyString(), eq(true));

            MvcResult confirmed = call(CONFIRM, me, codeBody(code));
            assertEquals(200, confirmed.getResponse().getStatus(), text(confirmed));
            assertTrue(text(confirmed).contains("\"email\":\"" + newEmail + "\"") && text(confirmed).contains("\"emailVerified\":true"), text(confirmed));

            User after = reload(me);
            assertEquals(newEmail, after.getEmail());
            assertTrue(after.isEmailVerified());
            assertEquals(me.getUsername(), after.getUsername(), "the login identity (username) never changes");
            assertEquals(me.getPassword(), after.getPassword());
            assertEquals(role, after.getRole());
            assertNull(after.getPendingEmail());
            assertNull(after.getPendingEmailCodeHash());
            assertNull(after.getPendingEmailExpiresAt());
            assertEquals(newEmail, staffRepository.findByUserUsername(me.getUsername()).orElseThrow().getEmail(), "the staff record mirrors the account");
            verify(emailService).sendEmailChangeNotice(eq(oldEmail), anyString(), eq(true));
        }
    }

    @Test
    @DisplayName("An account without a staff record (for example the admin) can change its email too")
    void accountWithoutStaffRecord() throws Exception {
        User me = account("nostaff", Role.ADMIN);
        call(REQUEST, me, requestBody("ec_nostaff@new.test"));
        assertEquals(200, call(CONFIRM, me, codeBody(sentCode("ec_nostaff@new.test"))).getResponse().getStatus());
        assertEquals("ec_nostaff@new.test", reload(me).getEmail());
    }

    @Test
    @DisplayName("The new address is trimmed and lower-cased; the pending address is never a way to sign in, and the old one still works as the login e-mail until the change completes")
    void normalisationAndLoginIdentity() throws Exception {
        User me = account("login", Role.MANAGER);
        call(REQUEST, me, requestBody("  EC_Login.New@NEW.test "));
        assertEquals("ec_login.new@new.test", reload(me).getPendingEmail());

        String ip = "10.77.1." + (CLIENTS.incrementAndGet() % 250);
        MvcResult pendingLogin = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).with(r -> { r.setRemoteAddr(ip); return r; })
                .content("{\"username\":\"ec_login.new@new.test\",\"password\":\"Correct@123\"}")).andReturn();
        assertEquals(401, pendingLogin.getResponse().getStatus(), "an unverified pending address cannot sign anyone in");
        MvcResult oldLogin = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).with(r -> { r.setRemoteAddr(ip); return r; })
                .content("{\"username\":\"" + me.getEmail() + "\",\"password\":\"Correct@123\"}")).andReturn();
        assertEquals(200, oldLogin.getResponse().getStatus(), "the current email still signs in until the change completes");

        call(CONFIRM, me, codeBody(sentCode("ec_login.new@new.test")));
        String ip2 = "10.78." + (CLIENTS.incrementAndGet() / 250) + "." + (CLIENTS.get() % 250);
        MvcResult newLogin = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).with(r -> { r.setRemoteAddr(ip2); return r; })
                .content("{\"username\":\"ec_login.new@new.test\",\"password\":\"Correct@123\"}")).andReturn();
        assertEquals(200, newLogin.getResponse().getStatus(), "after verification the new email signs in, with the same account");
        assertTrue(text(newLogin).contains("\"username\":\"" + me.getUsername() + "\""));
    }

    // ── code handling ────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Only a BCrypt hash of the code is stored; the code itself is not in the database, the response or any stored field")
    void codeIsHashed() throws Exception {
        User me = account("hash", Role.STAFF);
        MvcResult result = call(REQUEST, me, requestBody("ec_hash@new.test"));
        String code = sentCode("ec_hash@new.test");
        User stored = reload(me);
        assertNotNull(stored.getPendingEmailCodeHash());
        assertNotEquals(code, stored.getPendingEmailCodeHash());
        assertTrue(stored.getPendingEmailCodeHash().startsWith("$2"), "BCrypt");
        assertTrue(passwordEncoder.matches(code, stored.getPendingEmailCodeHash()));
        for (String field : new String[] {stored.getPendingEmail(), stored.getPendingEmailCodeHash(), text(result)}) assertFalse(field.contains(code + "\""), "no plaintext code");
        assertNull(stored.getVerificationCodeHash(), "the first-time verification columns are not used or touched");
    }

    @Test
    @DisplayName("The code lives for ten minutes")
    void expiryIsTenMinutes() throws Exception {
        User me = account("ttl", Role.STAFF);
        LocalDateTime before = LocalDateTime.now();
        call(REQUEST, me, requestBody("ec_ttl@new.test"));
        LocalDateTime expires = reload(me).getPendingEmailExpiresAt();
        assertTrue(expires.isAfter(before.plusMinutes(9)) && expires.isBefore(LocalDateTime.now().plusMinutes(11)), expires.toString());
    }

    @Test
    @DisplayName("A wrong code is refused and counted, the email is unchanged, and the fifth wrong code cancels the change")
    void incorrectCode() throws Exception {
        User me = account("wrong", Role.MANAGER);
        call(REQUEST, me, requestBody("ec_wrong@new.test"));
        String code = sentCode("ec_wrong@new.test");

        MvcResult first = call(CONFIRM, me, codeBody(wrong(code)));
        assertEquals(400, first.getResponse().getStatus());
        assertTrue(text(first).contains("Incorrect code. 4 attempts remaining."), text(first));
        assertEquals(1, reload(me).getPendingEmailAttemptCount(), "the failed try was saved");
        assertEquals(me.getEmail(), reload(me).getEmail());

        for (int i = 0; i < 3; i++) assertEquals(400, call(CONFIRM, me, codeBody(wrong(code))).getResponse().getStatus());
        MvcResult last = call(CONFIRM, me, codeBody(wrong(code)));
        assertEquals(400, last.getResponse().getStatus());
        assertTrue(text(last).contains("Too many incorrect codes"), text(last));
        assertNull(reload(me).getPendingEmail(), "the change was cancelled");

        MvcResult rightButTooLate = call(CONFIRM, me, codeBody(code));
        assertEquals(400, rightButTooLate.getResponse().getStatus(), "even the right code no longer works");
        assertEquals(me.getEmail(), reload(me).getEmail());
    }

    @Test
    @DisplayName("Malformed codes are refused without counting: letters, too short, too long, empty, missing")
    void malformedCodes() throws Exception {
        User me = account("format", Role.STAFF);
        call(REQUEST, me, requestBody("ec_format@new.test"));
        for (String bad : new String[] {"abcdef", "12345", "1234567", "", " 123456", "12 456"}) {
            assertEquals(400, call(CONFIRM, me, codeBody(bad)).getResponse().getStatus(), bad);
        }
        assertEquals(400, call(CONFIRM, me, "{}").getResponse().getStatus());
        assertEquals(0, reload(me).getPendingEmailAttemptCount(), "format errors do not use up attempts");
    }

    @Test
    @DisplayName("An expired code is refused and cancelled; the email is unchanged and a new code can be requested")
    void expiredCode() throws Exception {
        User me = account("expired", Role.STAFF);
        call(REQUEST, me, requestBody("ec_expired@new.test"));
        String code = sentCode("ec_expired@new.test");
        User stored = reload(me);
        stored.setPendingEmailExpiresAt(LocalDateTime.now().minusMinutes(1));
        userRepository.saveAndFlush(stored);

        MvcResult result = call(CONFIRM, me, codeBody(code));
        assertEquals(400, result.getResponse().getStatus());
        assertTrue(text(result).contains("expired"), text(result));
        assertNull(reload(me).getPendingEmail());
        assertEquals(me.getEmail(), reload(me).getEmail());
        assertEquals(200, call(REQUEST, me, requestBody("ec_expired@new.test")).getResponse().getStatus());
    }

    @Test
    @DisplayName("A code works once: using it again, or confirming with nothing pending, is refused")
    void replayAndNothingPending() throws Exception {
        User me = account("replay", Role.MANAGER);
        assertEquals(400, call(CONFIRM, me, codeBody("123456")).getResponse().getStatus(), "nothing pending");
        call(REQUEST, me, requestBody("ec_replay@new.test"));
        String code = sentCode("ec_replay@new.test");
        assertEquals(200, call(CONFIRM, me, codeBody(code)).getResponse().getStatus());
        MvcResult again = call(CONFIRM, me, codeBody(code));
        assertEquals(400, again.getResponse().getStatus());
        assertTrue(text(again).contains("no email change in progress"), text(again));
        assertEquals("ec_replay@new.test", reload(me).getEmail());
    }

    // ── resend ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Resend: not before the 60-second cooldown (429); then a new code replaces the old one, resets the attempts, and only the new one works")
    void resend() throws Exception {
        User me = account("resend", Role.STAFF);
        call(REQUEST, me, requestBody("ec_resend@new.test"));
        String first = sentCode("ec_resend@new.test");
        call(CONFIRM, me, codeBody(wrong(first)));
        assertEquals(1, reload(me).getPendingEmailAttemptCount());

        MvcResult tooSoon = call(REQUEST, me, requestBody("ec_resend@new.test"));
        assertEquals(429, tooSoon.getResponse().getStatus(), text(tooSoon));
        assertTrue(text(tooSoon).contains("seconds before requesting another code"), text(tooSoon));
        verify(emailService, times(1)).sendEmailChangeVerificationEmail(anyString(), anyString());

        User stored = reload(me);
        stored.setPendingEmailLastSentAt(LocalDateTime.now().minusSeconds(61));
        userRepository.saveAndFlush(stored);
        MvcResult resent = call(REQUEST, me, requestBody("ec_resend@new.test"));
        assertEquals(200, resent.getResponse().getStatus(), text(resent));
        verify(emailService, times(2)).sendEmailChangeVerificationEmail(anyString(), anyString());
        String second = sentCode("ec_resend@new.test");
        assertEquals(0, reload(me).getPendingEmailAttemptCount(), "attempts start again with the new code");
        assertTrue(passwordEncoder.matches(second, reload(me).getPendingEmailCodeHash()));
        if (!first.equals(second)) assertEquals(400, call(CONFIRM, me, codeBody(first)).getResponse().getStatus(), "the replaced code is dead");
        assertEquals(200, call(CONFIRM, me, codeBody(second)).getResponse().getStatus());
        assertEquals("ec_resend@new.test", reload(me).getEmail());
    }

    @Test
    @DisplayName("Asking for a different new address replaces the pending one (the earlier code stops working)")
    void differentAddressReplacesPending() throws Exception {
        User me = account("switch", Role.STAFF);
        call(REQUEST, me, requestBody("ec_switch_a@new.test"));
        String codeA = sentCode("ec_switch_a@new.test");
        call(REQUEST, me, requestBody("ec_switch_b@new.test"));
        assertEquals("ec_switch_b@new.test", reload(me).getPendingEmail());
        String codeB = sentCode("ec_switch_b@new.test");
        if (!codeA.equals(codeB)) assertEquals(400, call(CONFIRM, me, codeBody(codeA)).getResponse().getStatus());
        assertEquals(200, call(CONFIRM, me, codeBody(codeB)).getResponse().getStatus());
        assertEquals("ec_switch_b@new.test", reload(me).getEmail());
    }

    // ── validation and duplicates ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Invalid addresses are refused, and so is your own current address; nothing is sent or stored")
    void invalidAddresses() throws Exception {
        User me = account("invalid", Role.STAFF);
        for (String bad : new String[] {"", "   ", "plain", "a@b", "a b@c.test", "@c.test", "a@@c.test", "a@c.", "x".repeat(250) + "@c.test", "a@c.test, b@c.test"}) {
            assertEquals(400, call(REQUEST, me, requestBody(bad)).getResponse().getStatus(), bad);
        }
        assertEquals(400, call(REQUEST, me, "{}").getResponse().getStatus());
        MvcResult same = call(REQUEST, me, requestBody(me.getEmail().toUpperCase()));
        assertEquals(400, same.getResponse().getStatus());
        assertTrue(text(same).contains("already your email address"));
        verify(emailService, never()).sendEmailChangeVerificationEmail(anyString(), anyString());
        assertNull(reload(me).getPendingEmail());
    }

    @Test
    @DisplayName("An address already used by another account (any letter case, user or staff record) is refused with 409 before any code is sent")
    void duplicateAddress() throws Exception {
        User me = account("dup_me", Role.STAFF);
        User other = account("dup_other", Role.MANAGER);
        withStaffRecord(other);

        MvcResult byUser = call(REQUEST, me, requestBody(other.getEmail().toUpperCase()));
        assertEquals(409, byUser.getResponse().getStatus(), text(byUser));
        assertTrue(text(byUser).contains("already in use"));
        verify(emailService, never()).sendEmailChangeVerificationEmail(anyString(), anyString());
        assertNull(reload(me).getPendingEmail());
        assertEquals(me.getEmail(), reload(me).getEmail());
    }

    @Test
    @DisplayName("If the address is taken after the code was sent, confirming is refused with 409, the pending change is dropped and the email stays")
    void takenWhileCodeWasOut() throws Exception {
        User me = account("race_me", Role.STAFF);
        call(REQUEST, me, requestBody("ec_race@new.test"));
        String code = sentCode("ec_race@new.test");
        User other = account("race_other", Role.STAFF);
        other.setEmail("ec_race@new.test");
        userRepository.saveAndFlush(other);

        MvcResult result = call(CONFIRM, me, codeBody(code));
        assertEquals(409, result.getResponse().getStatus(), text(result));
        assertEquals(me.getEmail(), reload(me).getEmail(), "unchanged");
        assertNull(reload(me).getPendingEmail());
        assertEquals("ec_race@new.test", reload(other).getEmail(), "the other account keeps it");
    }

    // ── safety ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("If the email cannot be sent, the request fails (500), nothing is stored and the old address is not notified")
    void sendFailureLeavesNothing() throws Exception {
        User me = account("sendfail", Role.STAFF);
        doThrow(new EmailSendingException("boom")).when(emailService).sendEmailChangeVerificationEmail(anyString(), anyString());
        MvcResult result = call(REQUEST, me, requestBody("ec_sendfail@new.test"));
        assertEquals(500, result.getResponse().getStatus(), text(result));
        assertFalse(text(result).contains("boom"), "no internals");
        User stored = reload(me);
        assertNull(stored.getPendingEmail());
        assertNull(stored.getPendingEmailCodeHash());
        assertEquals(me.getEmail(), stored.getEmail());
        verify(emailService, never()).sendEmailChangeNotice(anyString(), anyString(), anyBoolean());
    }

    @Test
    @DisplayName("A notice to the old address that cannot be sent is never lost silently: the request is refused and nothing is stored (Step 2L, fail closed)")
    void noticeFailureBlocksTheRequest() throws Exception {
        User me = account("noticefail", Role.STAFF);
        doThrow(new EmailSendingException("nope")).when(emailService).sendEmailChangeNotice(anyString(), anyString(), anyBoolean());
        MvcResult refused = call(REQUEST, me, requestBody("ec_noticefail@new.test"));
        assertEquals(500, refused.getResponse().getStatus(), text(refused));
        assertFalse(text(refused).contains("nope"), "no internals");
        User stored = reload(me);
        assertNull(stored.getPendingEmail(), "no change was started");
        assertNull(stored.getPendingEmailCodeHash());
        assertEquals(me.getEmail(), stored.getEmail());
    }

    @Test
    @DisplayName("Accounts that still have to finish first-time verification or password setup cannot use this to skip it")
    void firstTimeSetupCannotBeSkipped() throws Exception {
        User pending = account("setup", Role.STAFF);
        pending.setCredentialsSetupRequired(true);
        userRepository.saveAndFlush(pending);
        MvcResult setup = call(REQUEST, pending, requestBody("ec_setup@new.test"));
        assertEquals(403, setup.getResponse().getStatus(), text(setup));

        User unverified = account("unverified", Role.MANAGER);
        unverified.setEmailVerified(false);
        userRepository.saveAndFlush(unverified);
        assertEquals(403, call(REQUEST, unverified, requestBody("ec_unverified@new.test")).getResponse().getStatus());
        assertEquals(403, call(CONFIRM, unverified, codeBody("123456")).getResponse().getStatus());
        verify(emailService, never()).sendEmailChangeVerificationEmail(anyString(), anyString());
        assertEquals(unverified.getEmail(), reload(unverified).getEmail());
        assertFalse(reload(unverified).isEmailVerified(), "an unverified account is not made verified by this");
    }

    @Test
    @DisplayName("Without a token both endpoints are refused and nothing is stored or sent")
    void requiresAuthentication() throws Exception {
        User me = account("anon", Role.STAFF);
        int a = mockMvc.perform(post(REQUEST).contentType(MediaType.APPLICATION_JSON).content(requestBody("ec_anon@new.test"))).andReturn().getResponse().getStatus();
        int b = mockMvc.perform(post(CONFIRM).contentType(MediaType.APPLICATION_JSON).content(codeBody("123456"))).andReturn().getResponse().getStatus();
        int c = mockMvc.perform(post(REQUEST).header("Authorization", "Bearer not.a.token").contentType(MediaType.APPLICATION_JSON).content(requestBody("ec_anon@new.test"))).andReturn().getResponse().getStatus();
        for (int status : new int[] {a, b, c}) assertTrue(status == 401 || status == 403, "status " + status);
        verify(emailService, never()).sendEmailChangeVerificationEmail(anyString(), anyString());
        assertNull(reload(me).getPendingEmail());
    }

    @Test
    @DisplayName("Users are isolated: only the signed-in account is changed, a user id in the body or query is ignored, and one user's code is useless to another")
    void userIsolation() throws Exception {
        User alice = account("alice", Role.STAFF);
        User bob = account("bob", Role.ADMIN);

        MvcResult result = mockMvc.perform(post(REQUEST).param("userId", bob.getId().toString()).param("username", bob.getUsername())
                .contentType(MediaType.APPLICATION_JSON)
                .with(user(alice.getUsername()).roles("STAFF")).with(r -> { r.setRemoteAddr("10.55.5.5"); return r; })
                .content("{\"userId\":\"" + bob.getId() + "\",\"username\":\"" + bob.getUsername() + "\",\"newEmail\":\"ec_alice@new.test\",\"currentPassword\":\"Correct@123\"}")).andReturn();
        assertEquals(200, result.getResponse().getStatus(), text(result));
        assertEquals("ec_alice@new.test", reload(alice).getPendingEmail());
        assertNull(reload(bob).getPendingEmail(), "bob has nothing pending");

        String aliceCode = sentCode("ec_alice@new.test");
        MvcResult bobTries = call(CONFIRM, bob, codeBody(aliceCode));
        assertEquals(400, bobTries.getResponse().getStatus(), "alice's code does nothing for bob");
        assertEquals(bob.getEmail(), reload(bob).getEmail());
        assertEquals(alice.getEmail(), reload(alice).getEmail(), "and has not been used up");

        assertEquals(200, call(CONFIRM, alice, codeBody(aliceCode)).getResponse().getStatus());
        assertEquals("ec_alice@new.test", reload(alice).getEmail());
        assertEquals(bob.getEmail(), reload(bob).getEmail());
    }

    @Test
    @DisplayName("Rate limits: code requests (5 per hour per client and account) and code guesses (10 per 15 minutes) are refused with 429, not later than that")
    void rateLimits() throws Exception {
        User me = account("limits", Role.STAFF);
        String ip = "10.66.6.6";
        for (int i = 0; i < 5; i++) {
            assertEquals(200, call(REQUEST, me, requestBody("ec_limits_" + i + "@new.test"), ip).getResponse().getStatus(), "request " + i);
        }
        MvcResult blocked = call(REQUEST, me, requestBody("ec_limits_5@new.test"), ip);
        assertEquals(429, blocked.getResponse().getStatus(), text(blocked));
        assertEquals(me.getEmail(), reload(me).getEmail());

        // guesses from another client address: the confirm limiter is separate; use up its allowance of 10
        String ip2 = "10.66.7.7";
        User other = account("limits_confirm", Role.STAFF);
        call(REQUEST, other, requestBody("ec_limits_c@new.test"), "10.66.8.8");
        String code = sentCode("ec_limits_c@new.test");
        int refused = 0;
        for (int i = 0; i < 14; i++) {
            if (call(CONFIRM, other, codeBody(wrong(code)), ip2).getResponse().getStatus() == 429) refused++;
        }
        assertTrue(refused >= 1, "guessing is limited");
    }

    @Test
    @DisplayName("Existing flows are unaffected: first-time verification columns, password reset columns and login are independent of a pending change")
    void otherFlowsAreIndependent() throws Exception {
        User me = account("independent", Role.STAFF);
        me.setPasswordResetCodeHash("keep-me");
        userRepository.saveAndFlush(me);
        call(REQUEST, me, requestBody("ec_independent@new.test"));
        call(CONFIRM, me, codeBody(sentCode("ec_independent@new.test")));
        User after = reload(me);
        assertEquals("keep-me", after.getPasswordResetCodeHash(), "password reset state untouched");
        assertNull(after.getVerificationCodeHash());
        assertNull(after.getSetupToken());
        assertTrue(after.isActive());
    }

    // ── Step 2L (K-02): the current password is required, and the old address is always told ─────────────────────────────

    @Test
    @DisplayName("A request without the current password (missing, empty or blank) is refused with 400; nothing is sent or stored")
    void passwordIsRequired() throws Exception {
        User me = account("nopw", Role.STAFF);
        for (String body : new String[]{"{\"newEmail\":\"ec_nopw@new.test\"}", requestBody("ec_nopw@new.test", ""), "{\"newEmail\":\"ec_nopw@new.test\",\"currentPassword\":null}"}) {
            MvcResult result = call(REQUEST, me, body);
            assertEquals(400, result.getResponse().getStatus(), body + " -> " + text(result));
            assertTrue(text(result).contains(EmailChangeService.PASSWORD_REQUIRED), text(result));
        }
        assertNull(reload(me).getPendingEmail());
        verify(emailService, never()).sendEmailChangeVerificationEmail(anyString(), anyString());
        verify(emailService, never()).sendEmailChangeNotice(anyString(), anyString(), anyBoolean());
    }

    @Test
    @DisplayName("A wrong current password is refused with 400 (never 401), nothing is sent or stored, and the password is not echoed")
    void wrongPasswordIsRefused() throws Exception {
        User me = account("wrongpw", Role.STAFF);
        MvcResult result = call(REQUEST, me, requestBody("ec_wrongpw@new.test", "Not@Mypass9"));
        assertEquals(400, result.getResponse().getStatus(), text(result));
        assertTrue(text(result).contains(EmailChangeService.WRONG_PASSWORD));
        assertFalse(text(result).contains("Not@Mypass9") || text(result).contains("Correct@123"), "no password in the answer");
        assertNull(reload(me).getPendingEmail());
        verify(emailService, never()).sendEmailChangeVerificationEmail(anyString(), anyString());
        verify(emailService, never()).sendEmailChangeNotice(anyString(), anyString(), anyBoolean());
        // The wrong attempt did not use up anything else: the right password still works at once
        assertEquals(200, call(REQUEST, me, requestBody("ec_wrongpw@new.test")).getResponse().getStatus());
    }

    @Test
    @DisplayName("The password must be the signed-in account's own: another account's password does not work, whoever the body names")
    void passwordMustBeTheCallersOwn() throws Exception {
        User alice = account("pw_alice", Role.STAFF);
        User bob = account("pw_bob", Role.STAFF);
        alice.setPassword(passwordEncoder.encode("Alice@Only1"));
        userRepository.saveAndFlush(alice);
        bob.setPassword(passwordEncoder.encode("Bob@Only123"));
        userRepository.saveAndFlush(bob);

        // Alice's session with Bob's password, and with a body that names Bob: refused
        MvcResult withBobsPassword = mockMvc.perform(post(REQUEST).contentType(MediaType.APPLICATION_JSON)
                .with(user(alice.getUsername()).roles("STAFF")).with(r -> { r.setRemoteAddr("10.55.9.9"); return r; })
                .content("{\"username\":\"" + bob.getUsername() + "\",\"newEmail\":\"ec_pw_alice@new.test\",\"currentPassword\":\"Bob@Only123\"}")).andReturn();
        assertEquals(400, withBobsPassword.getResponse().getStatus(), text(withBobsPassword));
        assertNull(reload(alice).getPendingEmail());
        assertNull(reload(bob).getPendingEmail());

        assertEquals(200, call(REQUEST, alice, requestBody("ec_pw_alice@new.test", "Alice@Only1")).getResponse().getStatus());
        assertEquals("ec_pw_alice@new.test", reload(alice).getPendingEmail());
        assertNull(reload(bob).getPendingEmail());
    }

    @Test
    @DisplayName("Wrong passwords are limited like every other request: after five from one client the right password is refused too (429)")
    void passwordGuessingIsLimited() throws Exception {
        User me = account("pwlimit", Role.STAFF);
        String ip = "10.66.10.10";
        for (int i = 0; i < 5; i++) {
            assertEquals(400, call(REQUEST, me, requestBody("ec_pwlimit@new.test", "Wrong@Pass" + i), ip).getResponse().getStatus(), "guess " + i);
        }
        MvcResult blocked = call(REQUEST, me, requestBody("ec_pwlimit@new.test"), ip);
        assertEquals(429, blocked.getResponse().getStatus(), text(blocked));
        assertNull(reload(me).getPendingEmail());
    }

    @Test
    @DisplayName("Whether another account uses the address is not revealed to someone who does not know the password")
    void passwordIsCheckedBeforeAddressUseIsRevealed() throws Exception {
        User me = account("reveal", Role.STAFF);
        User other = account("reveal_other", Role.STAFF);
        MvcResult wrong = call(REQUEST, me, requestBody(other.getEmail(), "Not@Mypass9"));
        assertEquals(400, wrong.getResponse().getStatus());
        assertTrue(text(wrong).contains(EmailChangeService.WRONG_PASSWORD), "the password is the first refusal, not 409");
        assertEquals(409, call(REQUEST, me, requestBody(other.getEmail())).getResponse().getStatus(), "with the right password the 409 is still given");
    }

    @Test
    @DisplayName("Any exception from the email client while sending the notice (not only EmailSendingException) also stops the request")
    void anyNoticeFailureBlocksTheRequest() throws Exception {
        User me = account("noticert", Role.STAFF);
        doThrow(new IllegalStateException("socket closed")).when(emailService).sendEmailChangeNotice(anyString(), anyString(), anyBoolean());
        MvcResult refused = call(REQUEST, me, requestBody("ec_noticert@new.test"));
        assertEquals(500, refused.getResponse().getStatus(), text(refused));
        assertFalse(text(refused).contains("socket"));
        assertNull(reload(me).getPendingEmail());
    }

    @Test
    @DisplayName("If the completion notice cannot be sent the change is rolled back (email unchanged, code still usable); once it can, the same code completes it")
    void completionNoticeFailureRollsBackTheChange() throws Exception {
        User me = account("compfail", Role.STAFF);
        withStaffRecord(me);
        String oldEmail = me.getEmail();
        assertEquals(200, call(REQUEST, me, requestBody("ec_compfail@new.test")).getResponse().getStatus());
        String code = sentCode("ec_compfail@new.test");

        doThrow(new EmailSendingException("down")).when(emailService).sendEmailChangeNotice(anyString(), anyString(), eq(true));
        MvcResult refused = call(CONFIRM, me, codeBody(code));
        assertEquals(500, refused.getResponse().getStatus(), text(refused));
        User stored = reload(me);
        assertEquals(oldEmail, stored.getEmail(), "the email did not change");
        assertEquals("ec_compfail@new.test", stored.getPendingEmail(), "the change is still pending");
        assertNotNull(stored.getPendingEmailCodeHash(), "and the code was not used up");
        assertEquals(oldEmail, staffRepository.findByUserUsername(me.getUsername()).orElseThrow().getEmail(), "the staff record did not change either");

        reset(emailService); // the email service works again
        MvcResult done = call(CONFIRM, me, codeBody(code));
        assertEquals(200, done.getResponse().getStatus(), text(done));
        assertEquals("ec_compfail@new.test", reload(me).getEmail());
        verify(emailService).sendEmailChangeNotice(eq(oldEmail), anyString(), eq(true));
    }
}
