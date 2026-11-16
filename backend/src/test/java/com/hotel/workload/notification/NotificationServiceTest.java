package com.hotel.workload.notification;

import com.hotel.workload.notification.NotificationService.Notice;
import com.hotel.workload.security.Role;
import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
import com.hotel.workload.verification.EmailSendingException;
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
 * N0: the notification foundation. Not wrapped in a test transaction: the point is what happens at commit and rollback, so each
 * operation runs in its own real transaction, exactly as in production. SendGrid is a mock; nothing is ever sent.
 */
@SpringBootTest
public class NotificationServiceTest {

    private static final String P = "ns_";

    @Autowired private NotificationService notifications;
    @Autowired private NotificationPreferenceService preferences;
    @Autowired private NotificationPreferenceRepository preferenceRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoBean private EmailService emailService;

    private static final EmailContent CONTENT = new EmailContent("Subject", "text body", "<p>html body</p>");

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

    private User account(String name, boolean active, String email) {
        User user = new User();
        user.setUsername(P + name);
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode("Old@Pass123"));
        user.setRole(Role.STAFF);
        user.setActive(active);
        user.setEmailVerified(true);
        user.setCredentialsSetupRequired(false);
        return userRepository.saveAndFlush(user);
    }

    private User account(String name) {
        return account(name, true, P + name + "@hotel.test");
    }

    private void setEmailPreference(User user, NotificationCategory category, boolean email) {
        preferenceRepository.saveAndFlush(new NotificationPreference(user.getId(), category, true, email));
    }

    private void sendNow(NotificationCategory category, Notice... notices) {
        notifications.sendAfterCommit(category, List.of(notices));
    }

    private void verifySentTo(String email, int times) {
        verify(emailService, times(times)).sendNotificationEmail(eq(email), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("An eligible recipient gets the message, with the subject and both bodies")
    public void sendsToAnEligibleRecipient() {
        User user = account("ok");
        sendNow(NotificationCategory.TASK_ASSIGNED, new Notice(user.getId(), CONTENT));
        verify(emailService).sendNotificationEmail(user.getEmail(), "Subject", "text body", "<p>html body</p>");
    }

    @Test
    @DisplayName("A disabled optional category sends nothing; the other categories are unaffected; each category is independent")
    public void optionalCategoryCanBeSwitchedOff() {
        User user = account("off");
        setEmailPreference(user, NotificationCategory.TASK_ASSIGNED, false);
        sendNow(NotificationCategory.TASK_ASSIGNED, new Notice(user.getId(), CONTENT));
        verify(emailService, never()).sendNotificationEmail(anyString(), anyString(), anyString(), anyString());
        sendNow(NotificationCategory.TASK_STATUS, new Notice(user.getId(), CONTENT)); // no saved row: that category's default (off for old accounts)
        verify(emailService, never()).sendNotificationEmail(anyString(), anyString(), anyString(), anyString());
        setEmailPreference(user, NotificationCategory.TASK_STATUS, true);
        sendNow(NotificationCategory.TASK_STATUS, new Notice(user.getId(), CONTENT));
        verifySentTo(user.getEmail(), 1);
    }

    @Test
    @DisplayName("SECURITY is always sent, whatever is stored")
    public void securityIsAlwaysOn() {
        User user = account("sec");
        // A stored switch-off (which the API refuses, but a row could exist) must not stop a security email
        setEmailPreference(user, NotificationCategory.SECURITY, false);
        sendNow(NotificationCategory.SECURITY, new Notice(user.getId(), CONTENT));
        verifySentTo(user.getEmail(), 1);
        assertTrue(preferences.isEmailEnabled(user.getId(), NotificationCategory.SECURITY));
    }

    @Test
    @DisplayName("Existing accounts keep the old defaults (no saved row); a new account gets every optional category on, and a saved choice is never overwritten")
    public void defaultsForExistingAndNewAccounts() {
        User existing = account("old");
        assertTrue(preferences.isEmailEnabled(existing.getId(), NotificationCategory.TASK_ASSIGNED));
        assertFalse(preferences.isEmailEnabled(existing.getId(), NotificationCategory.TASK_STATUS), "unchanged for accounts that never chose");
        assertTrue(preferences.isEmailEnabled(existing.getId(), NotificationCategory.WORKLOAD_ALERT));
        assertFalse(preferences.isEmailEnabled(existing.getId(), NotificationCategory.TASK_REMINDER));

        User fresh = account("fresh");
        preferences.enableAllOptionalForNewAccount(fresh.getId());
        for (NotificationCategory category : NotificationCategory.values()) assertTrue(preferences.isEmailEnabled(fresh.getId(), category), category.name());
        assertEquals(4, preferenceRepository.findByUserId(fresh.getId()).size(), "one row per optional category; SECURITY needs none");

        User chosen = account("chosen");
        setEmailPreference(chosen, NotificationCategory.TASK_ASSIGNED, false);
        preferences.enableAllOptionalForNewAccount(chosen.getId());
        assertFalse(preferences.isEmailEnabled(chosen.getId(), NotificationCategory.TASK_ASSIGNED), "a saved choice is kept");
        assertTrue(preferences.isEmailEnabled(chosen.getId(), NotificationCategory.TASK_STATUS), "only the categories without a row are filled in");
        preferences.enableAllOptionalForNewAccount(chosen.getId());
        assertEquals(4, preferenceRepository.findByUserId(chosen.getId()).size(), "running it again adds nothing");
    }

    @Test
    @DisplayName("Inactive accounts and missing or invalid addresses are skipped without error")
    public void skipsIneligibleRecipients() {
        User inactive = account("inactive", false, P + "inactive@hotel.test");
        User noAddress = account("blank", true, "");
        User badAddress = account("bad", true, "not an address");
        User twoAddresses = account("two", true, "a@hotel.test,b@hotel.test");
        User good = account("good");
        assertDoesNotThrow(() -> sendNow(NotificationCategory.TASK_ASSIGNED,
                new Notice(inactive.getId(), CONTENT), new Notice(noAddress.getId(), CONTENT), new Notice(badAddress.getId(), CONTENT),
                new Notice(twoAddresses.getId(), CONTENT), new Notice(java.util.UUID.randomUUID(), CONTENT), new Notice(good.getId(), CONTENT)));
        verify(emailService, times(1)).sendNotificationEmail(anyString(), anyString(), anyString(), anyString());
        verifySentTo(good.getEmail(), 1);
    }

    @Test
    @DisplayName("One notice per account per event: a person named twice is emailed once")
    public void noDuplicatesForOneEvent() {
        User user = account("dup");
        sendNow(NotificationCategory.TASK_ASSIGNED, new Notice(user.getId(), CONTENT),
                new Notice(user.getId(), new EmailContent("Other", "t", "h")));
        verifySentTo(user.getEmail(), 1);
        verify(emailService).sendNotificationEmail(user.getEmail(), "Subject", "text body", "<p>html body</p>");
    }

    @Test
    @DisplayName("A failing send is swallowed and never stops the other recipients")
    public void failuresNeverPropagate() {
        User first = account("f1");
        User second = account("f2");
        doThrow(new EmailSendingException("boom")).when(emailService).sendNotificationEmail(eq(first.getEmail()), anyString(), anyString(), anyString());
        assertDoesNotThrow(() -> sendNow(NotificationCategory.TASK_ASSIGNED, new Notice(first.getId(), CONTENT), new Notice(second.getId(), CONTENT)));
        verifySentTo(second.getEmail(), 1);
        Mockito.reset(emailService);
        when(emailService.isConfigured()).thenReturn(true);
        doThrow(new IllegalStateException("anything")).when(emailService).sendNotificationEmail(anyString(), anyString(), anyString(), anyString());
        assertDoesNotThrow(() -> sendNow(NotificationCategory.TASK_ASSIGNED, new Notice(first.getId(), CONTENT)));
    }

    @Test
    @DisplayName("When email is not configured nothing is attempted and nothing fails")
    public void notConfiguredSendsNothing() {
        User user = account("nocfg");
        when(emailService.isConfigured()).thenReturn(false);
        assertDoesNotThrow(() -> sendNow(NotificationCategory.TASK_ASSIGNED, new Notice(user.getId(), CONTENT)));
        verify(emailService, never()).sendNotificationEmail(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Nothing is sent before the transaction commits, and nothing at all when it rolls back")
    public void sendsOnlyAfterCommit() {
        User user = account("tx");
        TransactionTemplate template = new TransactionTemplate(transactionManager);

        template.executeWithoutResult(status -> {
            notifications.sendAfterCommit(NotificationCategory.TASK_ASSIGNED, List.of(new Notice(user.getId(), CONTENT)));
            verify(emailService, never()).sendNotificationEmail(anyString(), anyString(), anyString(), anyString()); // still inside it
        });
        verifySentTo(user.getEmail(), 1);

        Mockito.clearInvocations(emailService);
        template.executeWithoutResult(status -> {
            notifications.sendAfterCommit(NotificationCategory.TASK_ASSIGNED, List.of(new Notice(user.getId(), CONTENT)));
            status.setRollbackOnly();
        });
        verify(emailService, never()).sendNotificationEmail(anyString(), anyString(), anyString(), anyString());

        Mockito.clearInvocations(emailService);
        assertThrows(IllegalStateException.class, () -> template.executeWithoutResult(status -> {
            notifications.sendAfterCommit(NotificationCategory.TASK_ASSIGNED, List.of(new Notice(user.getId(), CONTENT)));
            throw new IllegalStateException("the operation failed");
        }));
        verify(emailService, never()).sendNotificationEmail(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Recipients are decided at commit time from the database: an account deactivated or switched off in the same transaction is not emailed")
    public void recipientsAreResolvedAtCommitTime() {
        User deactivated = account("late1");
        User switchedOff = account("late2");
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.executeWithoutResult(status -> {
            notifications.sendAfterCommit(NotificationCategory.TASK_ASSIGNED,
                    List.of(new Notice(deactivated.getId(), CONTENT), new Notice(switchedOff.getId(), CONTENT)));
            User u = userRepository.findById(deactivated.getId()).orElseThrow();
            u.setActive(false);
            userRepository.save(u);
            preferenceRepository.save(new NotificationPreference(switchedOff.getId(), NotificationCategory.TASK_ASSIGNED, true, false));
        });
        verify(emailService, never()).sendNotificationEmail(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Recipients given as a supplier are decided only after the commit, from the database as it is then")
    public void lazyRecipientsAreDecidedAfterCommit() {
        User user = account("lazy");
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        java.util.concurrent.atomic.AtomicInteger asked = new java.util.concurrent.atomic.AtomicInteger();
        template.executeWithoutResult(status -> {
            notifications.sendAfterCommit(NotificationCategory.TASK_STATUS, () -> {
                asked.incrementAndGet();
                return List.of(new Notice(user.getId(), CONTENT));
            });
            assertEquals(0, asked.get(), "not asked while the transaction is open");
            setEmailPreference(user, NotificationCategory.TASK_STATUS, true);
        });
        assertEquals(1, asked.get());
        verifySentTo(user.getEmail(), 1);

        Mockito.clearInvocations(emailService);
        template.executeWithoutResult(status -> {
            notifications.sendAfterCommit(NotificationCategory.TASK_STATUS, () -> List.of(new Notice(user.getId(), CONTENT)));
            status.setRollbackOnly();
        });
        verify(emailService, never()).sendNotificationEmail(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("A supplier that fails sends nothing and breaks nothing")
    public void failingSupplierIsSwallowed() {
        assertDoesNotThrow(() -> notifications.sendAfterCommit(NotificationCategory.TASK_STATUS, () -> {
            throw new IllegalStateException("lookup failed");
        }));
        verify(emailService, never()).sendNotificationEmail(anyString(), anyString(), anyString(), anyString());
    }
}
