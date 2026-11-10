package com.hotel.workload.verification;

import com.hotel.workload.security.Role;
import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
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
 * Step 2J, stage 2: simultaneous requests cannot get past the attempt limit, the single-use code or the resend cooldown.
 *
 * <p>These tests are NOT wrapped in a test transaction: each call to the service runs in its own real transaction on its own thread, and
 * the rows they use are committed and removed again afterwards. The threads are released together by a latch so they really overlap.
 */
@SpringBootTest
public class VerificationConcurrencyTest {

    private static final String CODE = "123456";
    private static final String WRONG = "000000";
    private static final String P = "vcc_";
    private static final int THREADS = 6;

    @Autowired private VerificationCodeService service;
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
        user.setPassword(passwordEncoder.encode("irrelevant"));
        user.setRole(Role.STAFF);
        user.setActive(true);
        user.setEmailVerified(false);
        user.setCredentialsSetupRequired(true);
        if (withCode) {
            user.setVerificationCodeHash(passwordEncoder.encode(CODE));
            user.setVerificationCodeExpiresAt(LocalDateTime.now().plusMinutes(10));
        }
        return userRepository.saveAndFlush(user);
    }

    /** Runs the same task on THREADS threads released at the same moment; returns each outcome (null = returned normally). */
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
            for (Future<Throwable> future : futures) {
                outcomes.add(future.get(60, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    @RepeatedTest(3)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("Parallel wrong guesses never get past 5 cumulative failures: exactly 5 are counted, the account is locked, the rest are refused")
    public void parallelWrongGuessesCannotExceedTheLimit() throws Exception {
        User user = account("guess", true);

        List<Throwable> outcomes = runTogether(() -> service.verifyCode(user.getUsername(), WRONG));

        for (Throwable outcome : outcomes) {
            assertTrue(outcome instanceof VerificationException, "every guess is refused with the generic failure, was " + outcome);
            assertEquals(VerificationCodeServiceImpl.GENERIC_FAILURE, outcome.getMessage());
        }
        User after = userRepository.findById(user.getId()).orElseThrow();
        assertEquals(VerificationCodeServiceImpl.MAX_ATTEMPTS, after.getVerificationAttemptCount(),
                "exactly the allowed number of attempts were counted: no lost update, no extra attempt");
        assertNotNull(after.getVerificationLockedUntil(), "the account is locked");
        assertNull(after.getVerificationCodeHash(), "the code is cleared");
    }

    @RepeatedTest(3)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("Parallel use of the one correct code succeeds exactly once, so only one setup token can ever be issued")
    public void correctCodeIsSingleUse() throws Exception {
        User user = account("single", true);

        List<Throwable> outcomes = runTogether(() -> service.verifyCode(user.getUsername(), CODE));

        long successes = outcomes.stream().filter(o -> o == null).count();
        assertEquals(1, successes, "the code works once: " + outcomes);
        for (Throwable outcome : outcomes) {
            if (outcome != null) assertTrue(outcome instanceof VerificationException, "the others get the generic failure, was " + outcome);
        }
        User after = userRepository.findById(user.getId()).orElseThrow();
        assertTrue(after.isEmailVerified());
        assertNull(after.getVerificationCodeHash());
        assertNotNull(after.getSetupToken(), "one setup token was issued");
    }

    @RepeatedTest(3)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("Parallel sends to one account send exactly one email: the cooldown holds under concurrency")
    public void parallelSendsRespectTheCooldown() throws Exception {
        User user = account("send", false);
        reset(emailService);

        List<Throwable> outcomes = runTogether(() -> {
            service.sendVerificationCode(user.getUsername());
            return null;
        });

        for (Throwable outcome : outcomes) assertNull(outcome, "no send fails: " + outcome);
        verify(emailService, times(1)).sendVerificationEmail(anyString(), anyString());
        User after = userRepository.findById(user.getId()).orElseThrow();
        assertNotNull(after.getVerificationCodeHash());
        assertNotNull(after.getVerificationLastSentAt());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("Parallel sends and guesses together: the stored code is always the one that was emailed, and failures stay within the limit")
    public void sendsAndGuessesInterleaveSafely() throws Exception {
        User user = account("mixed", true);
        reset(emailService);
        java.util.concurrent.atomic.AtomicInteger turn = new java.util.concurrent.atomic.AtomicInteger();

        runTogether(() -> {
            if (turn.getAndIncrement() % 2 == 0) {
                service.sendVerificationCode(user.getUsername());
            } else {
                try {
                    service.verifyCode(user.getUsername(), WRONG);
                } catch (VerificationException expected) {
                    // refused, as intended
                }
            }
            return null;
        });

        User after = userRepository.findById(user.getId()).orElseThrow();
        assertTrue(after.getVerificationAttemptCount() <= VerificationCodeServiceImpl.MAX_ATTEMPTS, "never more than the limit");
        verify(emailService, atMost(1)).sendVerificationEmail(anyString(), anyString());
    }
}
