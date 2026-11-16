package com.hotel.workload.notification;

import com.hotel.workload.notification.NotificationService.Notice;
import com.hotel.workload.security.Role;
import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
import com.hotel.workload.verification.EmailService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * N3: the delivery rules for SECURITY notices (verified address, the one explicit inactive-recipient exception, after-commit timing)
 * and the content of the four templates. Real transactions; SendGrid is a mock.
 */
@SpringBootTest
public class SecurityNotificationRulesTest {

    private static final String P = "sr_";

    @Autowired private SecurityEmailNotifier security;
    @Autowired private NotificationService notifications;
    @Autowired private NotificationPreferenceRepository preferenceRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoBean private EmailService emailService;

    private static final EmailContent CONTENT = new EmailContent("Subject", "text", "<p>html</p>");

    @BeforeEach
    public void setUp() {
        when(emailService.isConfigured()).thenReturn(true);
    }

    @AfterEach
    public void cleanUp() {
        userRepository.findAll().stream().filter(u -> u.getUsername().startsWith(P)).forEach(u -> {
            preferenceRepository.deleteAll(preferenceRepository.findByUserId(u.getId()));
            userRepository.delete(u);
        });
        userRepository.flush();
    }

    private User account(String name, boolean active, boolean verified, String email) {
        User user = new User();
        user.setUsername(P + name);
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode("Old@Pass123"));
        user.setRole(Role.STAFF);
        user.setActive(active);
        user.setEmailVerified(verified);
        user.setCredentialsSetupRequired(false);
        return userRepository.saveAndFlush(user);
    }

    private User account(String name, boolean active) {
        return account(name, active, true, P + name + "@hotel.test");
    }

    private void verifySent(User user, int times) {
        verify(emailService, times(times)).sendNotificationEmail(eq(user.getEmail()), anyString(), anyString(), anyString());
    }

    private void verifyNothingSent() {
        verify(emailService, never()).sendNotificationEmail(anyString(), anyString(), anyString(), anyString());
    }

    // ── who may receive a security notice ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Each of the four events reaches an active, verified owner with its own subject")
    public void allFourEvents() {
        User owner = account("all", true);
        security.passwordChanged(owner.getId());
        security.passwordResetCompleted(owner.getId());
        security.accountDeactivated(owner.getId(), "Administrator");
        security.accountReactivated(owner.getId());
        ArgumentCaptorSubjects subjects = new ArgumentCaptorSubjects();
        subjects.collect(emailService);
        assertEquals(List.of("Taskotel - Your password was changed", "Taskotel - Your password was reset",
                "Taskotel - Your account was deactivated", "Taskotel - Your account was reactivated"), subjects.subjects);
    }

    @Test
    @DisplayName("An unverified address receives no security notice, not even the deactivation notice; a verified one does")
    public void unverifiedIsNeverEmailed() {
        User unverified = account("unver", true, false, P + "unver@hotel.test");
        security.passwordChanged(unverified.getId());
        security.accountDeactivated(unverified.getId(), "Manager");
        verifyNothingSent();
        User ok = account("ver", true);
        security.passwordChanged(ok.getId());
        verifySent(ok, 1);
    }

    @Test
    @DisplayName("Invalid or blank addresses and unknown accounts are skipped without error")
    public void invalidRecipientsAreSkipped() {
        User blank = account("blank", true, true, "");
        User bad = account("bad", true, true, "not an address");
        assertDoesNotThrow(() -> {
            security.passwordChanged(blank.getId());
            security.passwordChanged(bad.getId());
            security.passwordChanged(java.util.UUID.randomUUID());
            security.passwordChanged(null);
        });
        verifyNothingSent();
    }

    @Test
    @DisplayName("Inactive accounts are skipped for every security notice except the deactivation notice")
    public void inactiveOnlyForDeactivation() {
        User inactive = account("inactive", false);
        security.passwordChanged(inactive.getId());
        security.passwordResetCompleted(inactive.getId());
        security.accountReactivated(inactive.getId());
        verifyNothingSent();
        security.accountDeactivated(inactive.getId(), "Administrator");
        verifySent(inactive, 1);
    }

    @Test
    @DisplayName("The inactive-recipient exception belongs to SECURITY alone: the flag does nothing for any other category, and SECURITY without it still skips inactive accounts")
    public void exceptionIsNarrow() {
        User inactive = account("narrow", false);
        for (NotificationCategory category : NotificationCategory.values()) {
            if (category == NotificationCategory.SECURITY) continue;
            notifications.sendAfterCommit(category, List.of(new Notice(inactive.getId(), CONTENT, true)));
        }
        notifications.sendAfterCommit(NotificationCategory.SECURITY, List.of(new Notice(inactive.getId(), CONTENT)));
        notifications.sendAfterCommit(NotificationCategory.SECURITY, List.of(new Notice(inactive.getId(), CONTENT, false)));
        verifyNothingSent();
        notifications.sendAfterCommit(NotificationCategory.SECURITY, List.of(new Notice(inactive.getId(), CONTENT, true)));
        verifySent(inactive, 1);
    }

    @Test
    @DisplayName("Every category requires a verified address: an unverified account is skipped for each of them, a verified one is emailed")
    public void everyCategoryRequiresAVerifiedAddress() {
        User unverified = account("other", true, false, P + "other@hotel.test");
        User verified = account("other_ok", true);
        for (NotificationCategory category : NotificationCategory.values()) {
            preferenceRepository.save(new NotificationPreference(unverified.getId(), category, true, true));
            preferenceRepository.save(new NotificationPreference(verified.getId(), category, true, true));
            notifications.sendAfterCommit(category, List.of(new Notice(unverified.getId(), CONTENT), new Notice(verified.getId(), CONTENT)));
        }
        verifySent(unverified, 0);
        verifySent(verified, NotificationCategory.values().length);
    }

    @Test
    @DisplayName("The inactive exception lifts only the 'active' requirement: an unverified inactive owner is still skipped, a verified one is emailed")
    public void inactiveExceptionStillNeedsAVerifiedAddress() {
        User unverifiedInactive = account("inact_unver", false, false, P + "inact_unver@hotel.test");
        User verifiedInactive = account("inact_ver", false);
        notifications.sendAfterCommit(NotificationCategory.SECURITY, List.of(new Notice(unverifiedInactive.getId(), CONTENT, true)));
        verifySent(unverifiedInactive, 0);
        notifications.sendAfterCommit(NotificationCategory.SECURITY, List.of(new Notice(verifiedInactive.getId(), CONTENT, true)));
        verifySent(verifiedInactive, 1);
    }

    @Test
    @DisplayName("Task assignment and status notices skip an unverified account too (the resolver is shared by every category)")
    public void taskNoticesSkipUnverified() {
        User unverified = account("task_unver", true, false, P + "task_unver@hotel.test");
        preferenceRepository.save(new NotificationPreference(unverified.getId(), NotificationCategory.TASK_STATUS, true, true));
        notifications.sendAfterCommit(NotificationCategory.TASK_ASSIGNED, List.of(new Notice(unverified.getId(), CONTENT)));
        notifications.sendAfterCommit(NotificationCategory.TASK_STATUS, () -> List.of(new Notice(unverified.getId(), CONTENT)));
        verifySent(unverified, 0);
    }

    @Test
    @DisplayName("A SECURITY notice is sent whatever preference is stored, and a person named twice is emailed once")
    public void securityIgnoresPreferencesAndDeduplicates() {
        User owner = account("pref", true);
        preferenceRepository.saveAndFlush(new NotificationPreference(owner.getId(), NotificationCategory.SECURITY, true, false));
        notifications.sendAfterCommit(NotificationCategory.SECURITY, List.of(new Notice(owner.getId(), CONTENT), new Notice(owner.getId(), CONTENT, true)));
        verifySent(owner, 1);
    }

    // ── timing ───────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Notices go out only after the commit; a rollback sends nothing")
    public void afterCommitOnly() {
        User owner = account("tx", true);
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.executeWithoutResult(status -> {
            security.passwordResetCompleted(owner.getId());
            verifyNothingSent();
        });
        verifySent(owner, 1);

        Mockito.clearInvocations(emailService);
        template.executeWithoutResult(status -> {
            security.accountDeactivated(owner.getId(), "Administrator");
            status.setRollbackOnly();
        });
        verifyNothingSent();
    }

    @Test
    @DisplayName("A reactivation notice is skipped when the account is inactive again by delivery time; a deactivation notice is not blocked by it")
    public void reactivationSkippedIfInactiveAgain() {
        User owner = account("flip", true);
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.executeWithoutResult(status -> {
            security.accountReactivated(owner.getId());
            User u = userRepository.findById(owner.getId()).orElseThrow();
            u.setActive(false); // deactivated again before the commit
            userRepository.save(u);
        });
        verifyNothingSent();

        template.executeWithoutResult(status -> {
            security.accountDeactivated(owner.getId(), "Manager");
        });
        verifySent(owner, 1);
    }

    // ── content ──────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("The four templates: required subjects and wording, the security footer, no link, no timestamp, no secret-like content")
    public void templates() {
        EmailContent changed = NotificationEmailTemplates.passwordChanged();
        EmailContent reset = NotificationEmailTemplates.passwordResetCompleted();
        EmailContent off = NotificationEmailTemplates.accountDeactivated("Administrator");
        EmailContent on = NotificationEmailTemplates.accountReactivated();

        assertEquals("Taskotel - Your password was changed", changed.subject());
        assertEquals("Taskotel - Your password was reset", reset.subject());
        assertEquals("Taskotel - Your account was deactivated", off.subject());
        assertEquals("Taskotel - Your account was reactivated", on.subject());
        assertTrue(changed.text().contains("previously") || changed.text().contains("signed out"));
        assertTrue(reset.text().contains("verification code"));
        assertTrue(off.text().contains("Deactivated by: Administrator") && off.text().contains("no longer sign in"));
        assertTrue(on.text().contains("Your Taskotel account is active again.") && on.text().contains("You can use your account as before."));
        assertTrue(on.text().contains("If you had not finished setting up your account yet, complete the setup steps when you next sign in."));
        assertFalse((on.text() + on.html()).contains("existing password"), "never assumes the first-time password setup is complete");

        for (EmailContent content : List.of(changed, reset, off, on)) {
            String all = content.subject() + content.text() + content.html();
            assertTrue(content.text().contains("Contact an administrator immediately"));
            assertTrue(content.text().contains("required security notice") && content.html().contains("required security notice"));
            assertFalse(content.text().contains("Notification Preferences"), "not a preference-controlled message");
            assertFalse(all.toLowerCase().contains("http") || all.contains("href"), "no link");
            assertFalse(all.matches("(?s).*\\d{4}-\\d{2}-\\d{2}.*") || all.matches("(?s).*\\d{1,2}:\\d{2}.*"), "no timestamp");
            assertFalse(all.contains("\r") && content.subject().contains("\n"));
            assertTrue(content.html().contains("Taskotel"));
            for (String forbidden : new String[] {"password:", "token", "jwt", "secret", "username", "$2a$", "ip address"}) {
                assertFalse(all.toLowerCase().contains(forbidden), "must not mention: " + forbidden);
            }
        }
    }

    @Test
    @DisplayName("The actor role is escaped and kept out of the subject; an unknown or blank role is simply left out")
    public void actorRoleHandling() {
        EmailContent odd = NotificationEmailTemplates.accountDeactivated("<script>alert(1)</script>\r\nBcc: x@evil.test");
        assertFalse(odd.html().contains("<script>"));
        assertTrue(odd.html().contains("&lt;script&gt;"));
        assertFalse(odd.subject().contains("script") || odd.subject().contains("\n") || odd.subject().contains("\r"));
        assertFalse(NotificationEmailTemplates.accountDeactivated(null).text().contains("Deactivated by"));
        assertFalse(NotificationEmailTemplates.accountDeactivated("  ").text().contains("Deactivated by"));
    }

    /** Collects the subjects of every notification email sent to the mock, in order. */
    private static final class ArgumentCaptorSubjects {
        final java.util.List<String> subjects = new java.util.ArrayList<>();

        void collect(EmailService service) {
            Mockito.mockingDetails(service).getInvocations().stream()
                    .filter(i -> i.getMethod().getName().equals("sendNotificationEmail"))
                    .forEach(i -> subjects.add(i.getArgument(1)));
        }
    }
}
