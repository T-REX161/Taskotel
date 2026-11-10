package com.hotel.workload.security;

import com.hotel.workload.verification.EmailService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Step 2M: what the password-reset service really persists. These tests are NOT wrapped in a test transaction: every call to the service
 * runs in its own real transaction (as in production), so a rollback that silently undoes a saved attempt count, a lost update between
 * parallel requests, or a second use of a code all show up here. The rows are committed and removed again afterwards.
 */
@SpringBootTest
public class PasswordResetPersistenceTest {

    private static final String CODE = "123456";
    private static final String WRONG = "654321";
    private static final String NEW_PASSWORD = "Brand@New123";
    private static final String P = "prp_";
    private static final int THREADS = 6;

    @Autowired private PasswordResetService service;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @MockitoBean private EmailService emailService;

    @AfterEach
    public void cleanUp() {
        userRepository.findAll().stream().filter(u -> u.getUsername().startsWith(P)).forEach(userRepository::delete);
        userRepository.flush();
    }

    private User account(String name, boolean withCode) {
        User user = new User();
        user.setUsername(P + name);
        user.setEmail(P + name + "@hotel.test");
        user.setPassword(passwordEncoder.encode("Old@Pass123"));
        user.setRole(Role.STAFF);
        user.setActive(true);
        user.setEmailVerified(true);
        user.setCredentialsSetupRequired(false);
        if (withCode) {
            user.setPasswordResetCodeHash(passwordEncoder.encode(CODE));
            user.setPasswordResetExpiresAt(LocalDateTime.now().plusMinutes(10));
            user.setPasswordResetAttemptCount(0);
        }
        return userRepository.saveAndFlush(user);
    }

    private User reload(User user) {
        return userRepository.findById(user.getId()).orElseThrow();
    }

    private static ResetPasswordRequest confirm(User user, String code) {
        ResetPasswordRequest request = new ResetPasswordRequest();
        request.setEmail(user.getEmail());
        request.setCode(code);
        request.setNewPassword(NEW_PASSWORD);
        request.setConfirmPassword(NEW_PASSWORD);
        return request;
    }

    private static ForgotPasswordRequest forgot(User user) {
        ForgotPasswordRequest request = new ForgotPasswordRequest();
        request.setEmail(user.getEmail());
        return request;
    }

    private List<Throwable> runTogether(Callable<Object> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            CountDownLatch ready = new CountDownLatch(THREADS);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Throwable>> futures = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        task.call();
                        return null;
                    } catch (Throwable t) {
                        return t;
                    }
                }));
            }
            assertTrue(ready.await(20, TimeUnit.SECONDS));
            go.countDown();
            List<Throwable> outcomes = new ArrayList<>();
            for (Future<Throwable> future : futures) outcomes.add(future.get(60, TimeUnit.SECONDS));
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("Wrong codes are really counted in the database, and the fifth cancels the code (the refusal must not roll the count back)")
    public void wrongCodesArePersistedAndTheFifthCancelsTheCode() {
        User user = account("wrong", true);
        for (int attempt = 1; attempt <= 4; attempt++) {
            assertThrows(InvalidVerificationCodeException.class, () -> service.confirmReset(confirm(user, WRONG)));
            User stored = reload(user);
            assertEquals(attempt, stored.getPasswordResetAttemptCount(), "attempt " + attempt + " must be saved");
            assertNotNull(stored.getPasswordResetCodeHash(), "the code survives until the fifth failure");
        }
        assertThrows(InvalidVerificationCodeException.class, () -> service.confirmReset(confirm(user, WRONG)));
        User cancelled = reload(user);
        assertNull(cancelled.getPasswordResetCodeHash(), "the fifth wrong code cancels the reset code");
        assertNull(cancelled.getPasswordResetExpiresAt());

        // The right code is useless now
        assertThrows(InvalidVerificationCodeException.class, () -> service.confirmReset(confirm(user, CODE)));
        assertEquals(user.getPassword(), reload(user).getPassword(), "the password never changed");
    }

    @RepeatedTest(3)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("Parallel wrong guesses are all counted (no lost update): the code is cancelled and the password untouched")
    public void parallelWrongGuessesCannotExceedTheLimit() throws Exception {
        User user = account("pwrong", true);
        List<Throwable> outcomes = runTogether(() -> {
            service.confirmReset(confirm(user, WRONG));
            return null;
        });
        for (Throwable outcome : outcomes) assertTrue(outcome instanceof InvalidVerificationCodeException, "every guess is refused, was " + outcome);
        User after = reload(user);
        assertNull(after.getPasswordResetCodeHash(), "five counted failures cancelled the code, however the six guesses interleaved");
        assertEquals(user.getPassword(), after.getPassword());
    }

    @RepeatedTest(3)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("A reset code is single-use under concurrency: parallel confirms with the right code succeed exactly once")
    public void correctCodeWorksOnce() throws Exception {
        User user = account("single", true);
        List<Throwable> outcomes = runTogether(() -> {
            service.confirmReset(confirm(user, CODE));
            return null;
        });
        assertEquals(1, outcomes.stream().filter(o -> o == null).count(), "exactly one reset: " + outcomes);
        for (Throwable outcome : outcomes) if (outcome != null) assertTrue(outcome instanceof InvalidVerificationCodeException, "" + outcome);
        User after = reload(user);
        assertNull(after.getPasswordResetCodeHash());
        assertTrue(passwordEncoder.matches(NEW_PASSWORD, after.getPassword()));
        assertNotNull(after.getPasswordChangedAt());
    }

    @RepeatedTest(3)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("Parallel reset requests for one account send exactly one email (the cooldown holds under concurrency)")
    public void parallelRequestsSendOneEmail() throws Exception {
        User user = account("psend", false);
        reset(emailService);
        when(emailService.isConfigured()).thenReturn(true);

        List<Throwable> outcomes = runTogether(() -> {
            service.requestReset(forgot(user));
            return null;
        });
        for (Throwable outcome : outcomes) assertNull(outcome, "no request fails: " + outcome);
        verify(emailService, times(1)).sendPasswordResetEmail(anyString(), anyString());
        assertNotNull(reload(user).getPasswordResetCodeHash());
    }
}
