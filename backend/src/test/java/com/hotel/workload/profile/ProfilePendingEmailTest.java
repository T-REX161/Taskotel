package com.hotel.workload.profile;

import com.hotel.workload.security.Role;
import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * GET /api/profile reports a pending email change to its owner, masked and only while its code is valid (Stage 3 follow-up), so the
 * signed-in account holder can complete a change an Admin or Manager started. Never the full pending address, the code or its hash.
 */
@SpringBootTest
public class ProfilePendingEmailTest {

    private static final String PREFIX = "ppe_";
    private static final String HASH = "$2a$10$abcdefghijklmnopqrstuvABCDEFGHIJKLMNOPQRSTUVWXYZ0123";

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private MockMvc mockMvc;
    private User me;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        cleanUp();
        me = new User();
        me.setUsername(PREFIX + "me");
        me.setEmail(PREFIX + "me@old.test");
        me.setPassword(passwordEncoder.encode("Correct@123"));
        me.setRole(Role.STAFF);
        me.setActive(true);
        me.setEmailVerified(true);
        me.setCredentialsSetupRequired(false);
        me = userRepository.saveAndFlush(me);
    }

    @AfterEach
    void cleanUp() {
        userRepository.findAll().stream().filter(u -> u.getUsername().startsWith(PREFIX)).forEach(userRepository::delete);
    }

    private void setPending(String email, LocalDateTime expiresAt) {
        User u = userRepository.findById(me.getId()).orElseThrow();
        u.setPendingEmail(email);
        u.setPendingEmailCodeHash(HASH);
        u.setPendingEmailExpiresAt(expiresAt);
        u.setPendingEmailAttemptCount(0);
        u.setPendingEmailLastSentAt(LocalDateTime.now());
        userRepository.saveAndFlush(u);
    }

    private String profile() throws Exception {
        var result = mockMvc.perform(get("/api/profile").with(user(me.getUsername()).roles("STAFF"))).andReturn();
        assertEquals(200, result.getResponse().getStatus());
        return result.getResponse().getContentAsString();
    }

    @Test
    void validPendingRequestIsReportedMaskedWithItsExpiry() throws Exception {
        setPending("newaddress@new.test", LocalDateTime.now().plusMinutes(8));
        String json = profile();
        assertTrue(json.contains("\"pendingEmailMasked\":\"ne***@new.test\""), json);
        assertTrue(json.contains("\"pendingEmailExpiresAt\":\""), json);
        assertTrue(json.contains("\"email\":\"" + me.getEmail() + "\""), "the active email is unchanged");
    }

    @Test
    void noPendingRequestMeansNullFields() throws Exception {
        String json = profile();
        assertTrue(json.contains("\"pendingEmailMasked\":null") && json.contains("\"pendingEmailExpiresAt\":null"), json);
    }

    @Test
    void anExpiredPendingRequestIsNotReported() throws Exception {
        setPending("newaddress@new.test", LocalDateTime.now().minusSeconds(1));
        String json = profile();
        assertTrue(json.contains("\"pendingEmailMasked\":null") && json.contains("\"pendingEmailExpiresAt\":null"), json);
    }

    @Test
    void neverTheFullPendingAddressOrTheCodeHash() throws Exception {
        setPending("newaddress@new.test", LocalDateTime.now().plusMinutes(8));
        String json = profile();
        assertFalse(json.contains("newaddress"), "the local part is never shown in full");
        assertFalse(json.contains(HASH) || json.toLowerCase().contains("codehash") || json.contains("pendingEmailAttempt"), json);
        assertFalse(json.contains("\"pendingEmail\""), "no field carries the unmasked address");
    }
}
