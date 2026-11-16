package com.hotel.workload.security;

import com.hotel.workload.task.CreateTaskRequest;
import com.hotel.workload.task.UpdateTaskRequest;
import com.hotel.workload.verification.EmailService;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for the audit fixes: an email typed in another letter case still finds the account for reset and first-time
 * verification, a password beyond bcrypt's 72 bytes is a 400 (never an encoder failure), and a task's title and duration are bounded.
 */
@SpringBootTest
public class AuditHardeningTest {

    private static final String P = "ahx_";

    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private AuthService authService;
    @MockitoBean private EmailService emailService;

    @AfterEach
    public void cleanUp() {
        userRepository.findAll().stream().filter(u -> u.getUsername().startsWith(P)).forEach(userRepository::delete);
        userRepository.flush();
    }

    private User account(String name, String email) {
        User user = new User();
        user.setUsername(P + name);
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode("Old@Pass123"));
        user.setRole(Role.STAFF);
        user.setActive(true);
        user.setEmailVerified(true);
        user.setCredentialsSetupRequired(false);
        return userRepository.saveAndFlush(user);
    }

    @Test
    @DisplayName("An email stored with capital letters is found whatever case it is typed in; an exact match wins")
    @org.springframework.transaction.annotation.Transactional
    public void emailLookupIgnoresCase() {
        User mixed = account("mixed", "Ahx.Ravi.K@Hotel.test");
        assertEquals(mixed.getId(), userRepository.findByEmailIgnoreCaseForUpdate("ahx.ravi.k@hotel.test").orElseThrow().getId(), "lowercase typed");
        assertEquals(mixed.getId(), userRepository.findByEmailIgnoreCaseForUpdate("AHX.RAVI.K@HOTEL.TEST").orElseThrow().getId(), "uppercase typed");
        assertEquals(mixed.getId(), userRepository.findByEmailIgnoreCaseForUpdate("Ahx.Ravi.K@Hotel.test").orElseThrow().getId(), "exact");
        assertTrue(userRepository.findByEmailIgnoreCaseForUpdate("ahx.nobody@hotel.test").isEmpty(), "unknown stays unknown");
    }

    @Test
    @DisplayName("When two accounts differ only by letter case, the exact match is used and an ambiguous other spelling matches nothing")
    @org.springframework.transaction.annotation.Transactional
    public void ambiguousCaseVariantsAreNeverGuessed() {
        User lower = account("amb_lower", "ahx.amb@hotel.test");
        User upper = account("amb_upper", "AHX.AMB@hotel.test");
        assertEquals(lower.getId(), userRepository.findByEmailIgnoreCaseForUpdate("ahx.amb@hotel.test").orElseThrow().getId());
        assertEquals(upper.getId(), userRepository.findByEmailIgnoreCaseForUpdate("AHX.AMB@hotel.test").orElseThrow().getId());
        assertTrue(userRepository.findByEmailIgnoreCaseForUpdate("Ahx.Amb@hotel.test").isEmpty(), "two candidates: refused, not guessed");
    }

    @Test
    @DisplayName("A password longer than 72 bytes is refused with the validation error, counted in bytes")
    public void passwordsBeyondBcryptLimitAreRefused() {
        PasswordValidatorService validator = new PasswordValidatorService();
        String ok72 = "Aa1!" + "x".repeat(68);
        assertEquals(72, ok72.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        assertTrue(validator.isValid(ok72));
        assertDoesNotThrow(() -> validator.validate(ok72));
        String tooLong = ok72 + "x";
        assertFalse(validator.isValid(tooLong));
        InvalidPasswordException ex = assertThrows(InvalidPasswordException.class, () -> validator.validate(tooLong));
        assertEquals("Password must be at most 72 bytes long.", ex.getMessage());
        // 37 two-byte characters are 74 bytes although only 41 characters
        String multibyte = "Aa1!" + "é".repeat(35);
        assertTrue(multibyte.length() < 72 && multibyte.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72);
        assertThrows(InvalidPasswordException.class, () -> validator.validate(multibyte));
        // the usual rules are unchanged
        assertThrows(InvalidPasswordException.class, () -> validator.validate("short1!"));
        assertThrows(InvalidPasswordException.class, () -> validator.validate(null));
    }

    @Test
    @DisplayName("A task's title is at most 255 characters and its duration at most 100000 minutes, on create and on update")
    public void taskBounds() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        CreateTaskRequest create = new CreateTaskRequest();
        create.setTitle("t".repeat(255));
        create.setPriority(com.hotel.workload.task.Priority.MEDIUM);
        create.setEstimatedDuration(100000);
        assertTrue(validator.validate(create).isEmpty(), "the limits themselves are allowed");
        create.setTitle("t".repeat(256));
        create.setEstimatedDuration(100001);
        assertEquals(2, validator.validate(create).size());
        create.setEstimatedDuration(Integer.MAX_VALUE);
        assertFalse(validator.validate(create).isEmpty());

        UpdateTaskRequest update = new UpdateTaskRequest();
        update.setTitle("t".repeat(256));
        update.setPriority(com.hotel.workload.task.Priority.MEDIUM);
        update.setEstimatedDuration(100001);
        java.util.Set<String> messages = new java.util.TreeSet<>();
        validator.validate(update).forEach(v -> messages.add(v.getMessage()));
        assertTrue(messages.contains("Title must be at most 255 characters") && messages.contains("Estimated duration must be at most 100000 minutes"), messages.toString());
        update.setTitle("ok");
        update.setEstimatedDuration(45);
        messages.clear();
        validator.validate(update).forEach(v -> messages.add(v.getMessage()));
        assertFalse(messages.contains("Title must be at most 255 characters") || messages.contains("Estimated duration must be at most 100000 minutes"), messages.toString());
    }

    private User pendingSetup(String name, String email) {
        User user = account(name, email);
        user.setCredentialsSetupRequired(true);
        user.setSetupToken(SetupTokens.issue());
        return userRepository.saveAndFlush(user);
    }

    private SetupCredentialsRequest setup(User user, String username) {
        SetupCredentialsRequest request = new SetupCredentialsRequest();
        request.setSetupToken(user.getSetupToken());
        request.setNewUsername(username);
        request.setNewPassword("Brand@New123");
        return request;
    }

    @Test
    @DisplayName("A new username cannot be another account's email address (sign-in tries the username first); the account's own email is fine")
    public void usernameCannotCaptureAnotherAccountsEmail() {
        User victim = account("victim", "ahx.victim@hotel.test");
        User attacker = pendingSetup("attacker", "ahx.attacker@hotel.test");
        CredentialsSetupException refused = assertThrows(CredentialsSetupException.class, () -> authService.setupCredentials(setup(attacker, "AHX.Victim@Hotel.test")));
        assertEquals("Username already exists", refused.getMessage());
        assertEquals(P + "attacker", userRepository.findById(attacker.getId()).orElseThrow().getUsername(), "nothing changed");
        assertTrue(userRepository.findById(attacker.getId()).orElseThrow().isCredentialsSetupRequired(), "setup is still pending");
        assertNotNull(victim.getId());

        User own = pendingSetup("own", "ahx.own@hotel.test");
        assertDoesNotThrow(() -> authService.setupCredentials(setup(own, "ahx.own@hotel.test")));
    }
}
