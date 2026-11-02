package com.hotel.workload.profile;

import com.hotel.workload.security.AuthService;
import com.hotel.workload.security.PasswordResetService;
import com.hotel.workload.security.ResetPasswordRequest;
import com.hotel.workload.security.Role;
import com.hotel.workload.security.SetupCredentialsRequest;
import com.hotel.workload.security.SetupTokens;
import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
import com.hotel.workload.staff.StaffRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Account Created / Last Login / Last Password Change on the profile: real values only, recorded by the server, own account only. */
@SpringBootTest
public class AccountActivityTest {

    private static final String PREFIX = "act_";

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private AuthService authService;
    @Autowired private PasswordResetService passwordResetService;
    @MockitoBean private ProfileImageStorage storage;

    private MockMvc mockMvc;
    private int clientCounter = 0;

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

    private User account(String name, Role role, boolean verified) {
        User user = new User();
        user.setUsername(PREFIX + name);
        user.setEmail(PREFIX + name + "@hotel.test");
        user.setPassword(passwordEncoder.encode("Correct@123"));
        user.setRole(role);
        user.setActive(true);
        user.setEmailVerified(verified);
        user.setCredentialsSetupRequired(false);
        return userRepository.saveAndFlush(user);
    }

    private User reload(User user) {
        return userRepository.findById(user.getId()).orElseThrow();
    }

    private MvcResult profileOf(User as) throws Exception {
        AbstractMockHttpServletRequestBuilder<?> request = get("/api/profile");
        return mockMvc.perform(request.with(user(as.getUsername()).roles(as.getRole().name()))).andReturn();
    }

    private MvcResult login(String username, String password) throws Exception {
        String client = "10.7.3." + (++clientCounter);
        return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .with(request -> { request.setRemoteAddr(client); return request; })
                .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}")).andReturn();
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    @Test
    @DisplayName("An account with no recorded activity: created time is real, the other two are null, never a placeholder")
    void accountWithoutActivity() throws Exception {
        User me = account("fresh", Role.STAFF, true);
        assertNull(me.getLastLoginAt());
        assertNull(me.getPasswordChangedAt());

        MvcResult result = profileOf(me);

        assertEquals(200, result.getResponse().getStatus());
        assertTrue(body(result).contains("\"lastLoginAt\":null"), body(result));
        assertTrue(body(result).contains("\"passwordChangedAt\":null"), body(result));
        assertFalse(body(result).contains("\"createdAt\":null"), body(result));
        assertTrue(body(result).matches("(?s).*\"createdAt\":\"\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}.*"), body(result));
        assertEquals(me.getCreatedAt().getYear(), reload(me).getCreatedAt().getYear());
    }

    @Test
    @DisplayName("A successful sign-in records the time, and the profile then returns it")
    void loginIsRecorded() throws Exception {
        User me = account("signin", Role.MANAGER, true);
        LocalDateTime before = LocalDateTime.now().minusSeconds(2);

        assertEquals(200, login(me.getUsername(), "Correct@123").getResponse().getStatus());

        LocalDateTime recorded = reload(me).getLastLoginAt();
        assertNotNull(recorded);
        assertTrue(recorded.isAfter(before) && recorded.isBefore(LocalDateTime.now().plusSeconds(2)), "now, from the server clock: " + recorded);
        assertFalse(body(profileOf(me)).contains("\"lastLoginAt\":null"));
        assertNull(reload(me).getPasswordChangedAt(), "signing in does not count as a password change");
    }

    @Test
    @DisplayName("Sign-ins that do not succeed record nothing: wrong password, disabled account, unverified email")
    void failedLoginsAreNotRecorded() throws Exception {
        User wrong = account("wrong", Role.STAFF, true);
        assertEquals(401, login(wrong.getUsername(), "Wrong@12345").getResponse().getStatus());
        assertNull(reload(wrong).getLastLoginAt());

        User disabled = account("off", Role.STAFF, true);
        disabled.setActive(false);
        userRepository.saveAndFlush(disabled);
        assertNotEquals200(login(disabled.getUsername(), "Correct@123"));
        assertNull(reload(disabled).getLastLoginAt());

        User unverified = account("unver", Role.STAFF, false);
        login(unverified.getUsername(), "Correct@123");
        assertNull(reload(unverified).getLastLoginAt(), "no session was issued, so no sign-in happened");
    }

    private static void assertNotEquals200(MvcResult result) {
        assertTrue(result.getResponse().getStatus() != 200, "status " + result.getResponse().getStatus());
    }

    @Test
    @DisplayName("Setting the first password and resetting a forgotten one record the change time; display name and picture edits do not")
    void passwordChangeIsRecorded() throws Exception {
        // first password through the setup link
        User onboarding = account("setup", Role.STAFF, true);
        onboarding.setCredentialsSetupRequired(true);
        onboarding.setSetupToken(SetupTokens.issue());
        userRepository.saveAndFlush(onboarding);
        SetupCredentialsRequest setup = new SetupCredentialsRequest();
        setup.setSetupToken(onboarding.getSetupToken());
        setup.setNewUsername(onboarding.getUsername());
        setup.setNewPassword("Brand@New123");
        authService.setupCredentials(setup);
        assertNotNull(reload(onboarding).getPasswordChangedAt());

        // forgotten password through the emailed code
        User forgot = account("forgot", Role.ADMIN, true);
        forgot.setPasswordResetCodeHash(passwordEncoder.encode("123456"));
        forgot.setPasswordResetExpiresAt(LocalDateTime.now().plusMinutes(10));
        userRepository.saveAndFlush(forgot);
        assertNull(reload(forgot).getPasswordChangedAt());
        passwordResetService.confirmReset(new ResetPasswordRequest(forgot.getEmail(), "123456", "Reset@Pass123", "Reset@Pass123"));
        LocalDateTime changed = reload(forgot).getPasswordChangedAt();
        assertNotNull(changed);
        assertTrue(Duration.between(changed, LocalDateTime.now()).abs().getSeconds() < 5);

        // other edits leave it alone
        User other = account("other", Role.STAFF, true);
        userRepository.updateDisplayName(other.getId(), "Someone Else");
        assertNull(reload(other).getPasswordChangedAt());
    }

    @Test
    @DisplayName("Each user sees only their own activity; there is no way to ask for someone else's, and no secrets are in the response")
    void ownActivityOnlyAndNoSecrets() throws Exception {
        User alice = account("alice", Role.STAFF, true);
        User bob = account("bob", Role.STAFF, true);
        bob.setLastLoginAt(LocalDateTime.of(2026, 3, 4, 5, 6, 7));
        bob.setPasswordChangedAt(LocalDateTime.of(2026, 2, 3, 4, 5, 6));
        bob.setPasswordResetCodeHash("secret-reset-hash");
        bob.setSetupToken("secret-setup-token");
        userRepository.saveAndFlush(bob);

        String alices = body(profileOf(alice));
        assertTrue(alices.contains("\"username\":\"act_alice\""));
        assertFalse(alices.contains("2026-03-04") || alices.contains("2026-02-03"), "bob's times are not in alice's profile: " + alices);

        String bobs = body(profileOf(bob));
        assertTrue(bobs.contains("\"lastLoginAt\":\"2026-03-04T05:06:07\"") && bobs.contains("\"passwordChangedAt\":\"2026-02-03T04:05:06\""), bobs);
        for (String secret : new String[] {"password\"", "Hash", "hash", "setupToken", "secret-", "verificationCode", "passwordResetCodeHash"}) {
            assertFalse(bobs.contains(secret), "must not expose " + secret + ": " + bobs);
        }

        // the endpoint takes no user id: a query parameter naming another user changes nothing
        MvcResult tryOther = mockMvc.perform(get("/api/profile").param("username", bob.getUsername()).param("id", bob.getId().toString())
                .with(user(alice.getUsername()).roles("STAFF"))).andReturn();
        assertTrue(body(tryOther).contains("\"username\":\"act_alice\"") && !body(tryOther).contains("2026-03-04"), body(tryOther));
    }

    @Test
    @DisplayName("Without a token the profile (and its activity) is refused")
    void requiresAuthentication() throws Exception {
        int status = mockMvc.perform(get("/api/profile")).andReturn().getResponse().getStatus();
        assertTrue(status == 401 || status == 403, "status " + status);
    }
}
