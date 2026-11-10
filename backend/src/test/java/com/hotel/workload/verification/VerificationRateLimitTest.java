package com.hotel.workload.verification;

import com.hotel.workload.security.Role;
import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Step 2J, stage 2: the in-memory per-client limits in front of the public verification endpoints (switched on here with small numbers; they
 * are off in the shared test configuration). Counted before the account is looked up, so a refusal never depends on whether the account
 * exists; keyed on the connection's own address, never on a forwarded header.
 *
 * <p>These limits are per application instance and are cleared by a restart. They are a best-effort layer: the failed-attempt count,
 * lockout and cooldown in the database hold across restarts.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "auth.verification-send-rate-limit.enabled=true",
        "auth.verification-send-rate-limit.window-seconds=600",
        "auth.verification-send-rate-limit.max-attempts-per-client-and-account=2",
        "auth.verification-send-rate-limit.max-attempts-per-client=5",
        "auth.verification-send-rate-limit.max-attempts-per-account=100",
        "auth.verification-verify-rate-limit.enabled=true",
        "auth.verification-verify-rate-limit.window-seconds=600",
        "auth.verification-verify-rate-limit.max-attempts-per-client-and-account=3",
        "auth.verification-verify-rate-limit.max-attempts-per-client=6",
        "auth.verification-verify-rate-limit.max-attempts-per-account=100"
})
public class VerificationRateLimitTest {

    private static final String P = "vrl_";

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @MockitoBean private EmailService emailService;

    private MockMvc mockMvc;
    private static int clients = 0;

    @BeforeEach
    public void setUp() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        org.springframework.security.test.context.TestSecurityContextHolder.clearContext();
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        when(emailService.isConfigured()).thenReturn(true);
        User user = new User();
        user.setUsername(P + "known");
        user.setEmail(P + "known@hotel.test");
        user.setPassword(passwordEncoder.encode("irrelevant"));
        user.setRole(Role.STAFF);
        user.setActive(true);
        user.setCredentialsSetupRequired(true);
        user.setVerificationCodeHash(passwordEncoder.encode("123456"));
        user.setVerificationCodeExpiresAt(LocalDateTime.now().plusMinutes(10));
        userRepository.saveAndFlush(user);
    }

    @AfterEach
    public void cleanUp() {
        userRepository.findAll().stream().filter(u -> u.getUsername().startsWith(P)).forEach(userRepository::delete);
        userRepository.flush();
    }

    /** A client address no other test in this context has used. */
    private static String freshClient() {
        return "10.77." + ((++clients / 250) % 250) + "." + (clients % 250 + 1);
    }

    private record Answer(int status, String body, String retryAfter) {}

    private Answer call(String path, String client, String identifier, String code, String forwardedFor) throws Exception {
        String json = code == null ? "{\"identifier\":\"" + identifier + "\"}" : "{\"identifier\":\"" + identifier + "\",\"code\":\"" + code + "\"}";
        var request = post(path).contentType(MediaType.APPLICATION_JSON).content(json).with(r -> { r.setRemoteAddr(client); return r; });
        if (forwardedFor != null) request.header("X-Forwarded-For", forwardedFor).header("Forwarded", "for=" + forwardedFor).header("X-Real-IP", forwardedFor);
        MvcResult result = mockMvc.perform(request).andReturn();
        return new Answer(result.getResponse().getStatus(), result.getResponse().getContentAsString(), result.getResponse().getHeader("Retry-After"));
    }

    private Answer send(String client, String identifier) throws Exception {
        return call("/api/auth/email-verification/send", client, identifier, null, null);
    }

    private Answer attempt(String client, String identifier, String code) throws Exception {
        return call("/api/auth/email-verification/verify", client, identifier, code, null);
    }

    // ── send ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("send: the third request from one client for one identifier is refused with 429, for a known and an unknown account alike")
    public void sendIsLimitedPerClientAndAccount() throws Exception {
        String client = freshClient();
        for (String identifier : new String[]{P + "known", P + "nobody"}) {
            assertEquals(200, send(client, identifier).status());
            assertEquals(200, send(client, identifier).status());
        }
        Answer known = send(client, P + "known");
        Answer unknown = send(client, P + "nobody");
        assertEquals(429, known.status());
        assertEquals(known, unknown, "the refusal is identical for a known and an unknown account");
        assertTrue(known.body().contains(EmailVerificationController.TOO_MANY_REQUESTS_MESSAGE));
        assertNotNull(known.retryAfter());
        assertTrue(Long.parseLong(known.retryAfter()) > 0);
        String lower = known.body().toLowerCase();
        assertFalse(lower.contains("account") || lower.contains("exist") || lower.contains(P));
    }

    @Test
    @DisplayName("send: one client is limited across identifiers (the per-client limit), and another client is not affected")
    public void sendIsLimitedPerClient() throws Exception {
        String client = freshClient();
        for (int i = 0; i < 5; i++) {
            assertEquals(200, send(client, P + "other" + i).status(), "request " + (i + 1));
        }
        assertEquals(429, send(client, P + "other-again").status(), "changing the identifier does not buy a fresh allowance");
        assertEquals(200, send(freshClient(), P + "other-again").status(), "a different client is unaffected");
    }

    // ── verify ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("verify: the fourth attempt from one client for one identifier is refused with 429, identically for known and unknown accounts")
    public void verifyIsLimitedPerClientAndAccount() throws Exception {
        String client = freshClient();
        for (String identifier : new String[]{P + "known", P + "nobody"}) {
            for (int i = 0; i < 3; i++) {
                assertEquals(400, attempt(client, identifier, "000000").status());
            }
        }
        Answer known = attempt(client, P + "known", "000000");
        Answer unknown = attempt(client, P + "nobody", "000000");
        assertEquals(429, known.status());
        assertEquals(known, unknown);
        assertTrue(known.body().contains(EmailVerificationController.TOO_MANY_REQUESTS_MESSAGE));
        assertNotNull(known.retryAfter());
        assertEquals(200, attempt(freshClient(), P + "known", "123456").status(), "another client, with the right code, is not limited");
    }

    // ── forwarded headers ────────────────────────────────────────────────────

    @Test
    @DisplayName("Forwarded headers (X-Forwarded-For, Forwarded, X-Real-IP) are ignored: spoofing them neither escapes a limit nor frames another client")
    public void forwardedHeadersAreNotTrusted() throws Exception {
        String attacker = freshClient();
        String victim = freshClient();
        // The attacker uses up the allowance, then claims to be somebody else in every forwarded header
        send(attacker, P + "known");
        send(attacker, P + "known");
        Answer spoofed = call("/api/auth/email-verification/send", attacker, P + "known", null, victim);
        assertEquals(429, spoofed.status(), "a spoofed X-Forwarded-For does not give a fresh allowance");
        Answer spoofedAgain = call("/api/auth/email-verification/send", attacker, P + "known", null, "203.0.113." + 7);
        assertEquals(429, spoofedAgain.status());

        // A request that merely CLAIMS to come from the attacker's address, from the victim's real connection, is counted as the victim
        Answer victimClaimingAttacker = call("/api/auth/email-verification/send", victim, P + "known", null, attacker);
        assertEquals(200, victimClaimingAttacker.status(), "the victim is not counted against the attacker's address");
    }

    // ── malformed requests are charged to the limiter (they are no longer rejected by validation first) ──────────

    /** Codes that are missing, blank, non-numeric, too short, too long, contain a space, or use non-ASCII digits. */
    private static final String[] MALFORMED = {null, "", "   ", "abc", "12345", "1234567", "12 456", "1234.5", "\u0661\u0662\u0663\u0664\u0665\u0666", "-12345"};

    private Answer raw(String path, String client, String json) throws Exception {
        MvcResult result = mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json)
                .with(r -> { r.setRemoteAddr(client); return r; })).andReturn();
        return new Answer(result.getResponse().getStatus(), result.getResponse().getContentAsString(), result.getResponse().getHeader("Retry-After"));
    }

    private Answer verifyRaw(String client, String identifier, String code) throws Exception {
        String json = code == null ? "{\"identifier\":\"" + identifier + "\"}" : "{\"identifier\":\"" + identifier + "\",\"code\":\"" + code + "\"}";
        return raw("/api/auth/email-verification/verify", client, json);
    }

    @Test
    @DisplayName("verify: every kind of malformed code is charged to the limiter exactly once: three get the generic 400, the fourth the generic 429")
    public void malformedCodesConsumeTheVerifyAllowance() throws Exception {
        // The reference answers for a well-formed wrong code (400) and for being limited (429)
        String reference = freshClient();
        Answer generic400 = attempt(reference, P + "known", "000000");
        attempt(reference, P + "known", "000000");
        attempt(reference, P + "known", "000000");
        Answer generic429 = attempt(reference, P + "known", "000000");
        assertEquals(400, generic400.status());
        assertTrue(generic400.body().contains(VerificationCodeServiceImpl.GENERIC_FAILURE));
        assertEquals(429, generic429.status());

        for (String identifier : new String[]{P + "known", P + "nobody"}) {
            for (String malformed : MALFORMED) {
                String client = freshClient();
                for (int i = 1; i <= 3; i++) {
                    Answer answer = verifyRaw(client, identifier, malformed);
                    assertEquals(400, answer.status(), identifier + " code=[" + malformed + "] request " + i);
                    assertEquals(generic400.body(), answer.body(), "a malformed code gets the same generic failure as a wrong one");
                    assertNull(answer.retryAfter());
                }
                Answer limited = verifyRaw(client, identifier, malformed);
                assertEquals(429, limited.status(), identifier + " code=[" + malformed + "]: the fourth request must be limited");
                assertEquals(generic429.body(), limited.body(), "and gets the same generic 429 as any other limited request");
                assertNotNull(limited.retryAfter());
            }
        }
    }

    @Test
    @DisplayName("verify: malformed and well-formed requests share one allowance, and a request is never counted twice")
    public void malformedAndWellFormedShareOneAllowance() throws Exception {
        String client = freshClient();
        assertEquals(400, verifyRaw(client, P + "known", "abc").status());      // 1 (malformed)
        assertEquals(400, verifyRaw(client, P + "known", null).status());       // 2 (missing)
        assertEquals(400, verifyRaw(client, P + "known", "000000").status());   // 3 (well formed, wrong)
        // The allowance is used up: even the RIGHT code is refused now, so malformed requests cannot be used to dodge the limit
        assertEquals(429, verifyRaw(client, P + "known", "123456").status());

        // Exactly one charge each: three requests were allowed, not two (double counting) and not four (not counted)
        String other = freshClient();
        int allowed = 0;
        for (int i = 0; i < 10 && verifyRaw(other, P + "known", "12ab56").status() == 400; i++) allowed++;
        assertEquals(3, allowed);
    }

    @Test
    @DisplayName("verify: a body that is not readable JSON, or has no identifier, is charged too and ends in the same generic 429")
    public void unreadableAndIdentifierlessRequestsAreCharged() throws Exception {
        String reference = freshClient();
        Answer generic400 = attempt(reference, P + "known", "000000");
        for (int i = 0; i < 2; i++) attempt(reference, P + "known", "000000");
        Answer generic429 = attempt(reference, P + "known", "000000");

        String broken = freshClient();
        for (int i = 1; i <= 3; i++) {
            Answer answer = raw("/api/auth/email-verification/verify", broken, "{not json");
            assertEquals(400, answer.status(), "broken body " + i);
            assertEquals(generic400.body(), answer.body());
        }
        Answer brokenLimited = raw("/api/auth/email-verification/verify", broken, "{not json");
        assertEquals(429, brokenLimited.status());
        assertEquals(generic429.body(), brokenLimited.body());

        String noIdentifier = freshClient();
        for (int i = 1; i <= 3; i++) {
            Answer answer = raw("/api/auth/email-verification/verify", noIdentifier, "{\"code\":\"123456\"}");
            assertEquals(400, answer.status(), "no identifier " + i);
            assertTrue(answer.body().contains("User identifier is required"));
        }
        assertEquals(429, raw("/api/auth/email-verification/verify", noIdentifier, "{\"code\":\"123456\"}").status());
    }

    @Test
    @DisplayName("send: a request with no identifier or an unreadable body is charged to the send limiter as well")
    public void sendRequestsWithoutIdentifierAreCharged() throws Exception {
        String client = freshClient();
        for (int i = 1; i <= 2; i++) {
            assertEquals(400, raw("/api/auth/email-verification/send", client, "{}").status(), "no identifier " + i);
        }
        assertEquals(429, raw("/api/auth/email-verification/send", client, "{}").status(), "the third is limited");

        String broken = freshClient();
        for (int i = 1; i <= 2; i++) {
            assertEquals(400, raw("/api/auth/email-verification/send", broken, "{not json").status());
        }
        assertEquals(429, raw("/api/auth/email-verification/send", broken, "{not json").status());
    }

    @Test
    @DisplayName("verify: valid requests still work normally, and a successful verification gives the allowance back")
    public void validRequestsStillWork() throws Exception {
        String client = freshClient();
        assertEquals(400, attempt(client, P + "known", "000000").status());
        assertEquals(400, attempt(client, P + "known", "111111").status());
        Answer ok = attempt(client, P + "known", "123456");
        assertEquals(200, ok.status(), ok.body());
        assertTrue(ok.body().contains("\"success\":true"));
        assertTrue(ok.body().contains("setupToken"));
        // The success was not charged and cleared the client + account key: more attempts are allowed again (they fail, with no code left)
        for (int i = 0; i < 3; i++) {
            assertEquals(400, attempt(client, P + "known", "000000").status(), "attempt " + (i + 1) + " after the success");
        }
    }

}
