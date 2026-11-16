package com.hotel.workload.notification;

import com.hotel.workload.notification.NotificationService.Notice;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * The four mandatory security emails (category SECURITY, which no preference can switch off):
 * password changed, password reset completed, account deactivated and account reactivated.
 *
 * <p>Each goes to the affected account's owner only: never an Admin or Manager copy, and never the person who acted (unless that is
 * the owner, as with a password change). The messages carry no secret and no personal detail (see {@link NotificationEmailTemplates}).
 * {@link NotificationService} sends after the surrounding transaction commits, after re-reading the account: it must exist, have a
 * verified, usable email address, and be active, except for the deactivation notice, which is the one message that may reach an inactive
 * account (that is its purpose).
 */
@Component
public class SecurityEmailNotifier {

    private final NotificationService notifications;

    public SecurityEmailNotifier(NotificationService notifications) {
        this.notifications = notifications;
    }

    /** The signed-in owner changed their password. */
    public void passwordChanged(UUID ownerUserId) {
        send(ownerUserId, NotificationEmailTemplates.passwordChanged(), false);
    }

    /** A password reset finished successfully (the owner entered the emailed code). */
    public void passwordResetCompleted(UUID ownerUserId) {
        send(ownerUserId, NotificationEmailTemplates.passwordResetCompleted(), false);
    }

    /**
     * The account was deactivated by an Administrator or a Manager. The notice is delivered although the account is now inactive.
     *
     * @param actorRole "Administrator" or "Manager"
     */
    public void accountDeactivated(UUID ownerUserId, String actorRole) {
        send(ownerUserId, NotificationEmailTemplates.accountDeactivated(actorRole), true);
    }

    /** The account is active again. Skipped if it is inactive again by the time it would be delivered. */
    public void accountReactivated(UUID ownerUserId) {
        send(ownerUserId, NotificationEmailTemplates.accountReactivated(), false);
    }

    private void send(UUID ownerUserId, EmailContent content, boolean includeInactive) {
        if (ownerUserId == null) return;
        notifications.sendAfterCommit(NotificationCategory.SECURITY, List.of(new Notice(ownerUserId, content, includeInactive)));
    }
}
