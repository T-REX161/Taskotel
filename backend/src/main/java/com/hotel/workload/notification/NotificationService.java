package com.hotel.workload.notification;

import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
import com.hotel.workload.verification.EmailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The one place that turns an application event into emails.
 *
 * <p>How it works, and why:
 * <ul>
 *   <li><b>After commit.</b> A notice is sent only once the surrounding database transaction has committed, so a rolled-back change never
 *       emails anyone. Without a transaction the notices are sent at once.</li>
 *   <li><b>Current state.</b> The recipients are looked up at that moment, from the database: the account must still exist and be
 *       active, must have a usable email address, and must have the category switched on (SECURITY is always on). Nothing is taken from
 *       a cache or from the request that caused the event.</li>
 *   <li><b>Never breaks the operation.</b> Every failure (no SendGrid, a refused address, a database error) is logged without the
 *       address in clear text and swallowed. The business operation has already committed and its answer is unaffected.</li>
 *   <li><b>No duplicates.</b> One notice per account per event: a person named twice receives the first notice only.</li>
 * </ul>
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final Pattern PLAIN_ADDRESS = Pattern.compile("^[^@\\s,;<>]+@[^@\\s,;<>]+\\.[^@\\s,;<>]+$");
    private static final int MAX_ADDRESS_LENGTH = 254;

    /** One message for one account. */
    public record Notice(UUID userId, EmailContent content) {}

    private record Target(String email, EmailContent content) {}

    private final UserRepository userRepository;
    private final NotificationPreferenceService preferences;
    private final EmailService emailService;
    private final PlatformTransactionManager transactionManager;

    public NotificationService(UserRepository userRepository, NotificationPreferenceService preferences, EmailService emailService,
                               PlatformTransactionManager transactionManager) {
        this.userRepository = userRepository;
        this.preferences = preferences;
        this.emailService = emailService;
        this.transactionManager = transactionManager;
    }

    /**
     * Sends the notices for one event once the current transaction has committed (or now, when there is none). Never throws.
     *
     * @param category the preference that decides whether each recipient wants it (SECURITY always does)
     * @param notices  the messages, one per account; an account named twice receives the first one only
     */
    public void sendAfterCommit(NotificationCategory category, Collection<Notice> notices) {
        try {
            if (category == null || notices == null || notices.isEmpty()) return;
            Map<UUID, EmailContent> unique = new LinkedHashMap<>();
            for (Notice notice : notices) {
                if (notice != null && notice.userId() != null && notice.content() != null) unique.putIfAbsent(notice.userId(), notice.content());
            }
            if (unique.isEmpty()) return;
            if (TransactionSynchronizationManager.isSynchronizationActive() && TransactionSynchronizationManager.isActualTransactionActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        deliver(category, unique);
                    }
                });
            } else {
                deliver(category, unique);
            }
        } catch (RuntimeException e) {
            log.error("Could not schedule {} notification emails: {}", category, e.getClass().getSimpleName());
        }
    }

    private void deliver(NotificationCategory category, Map<UUID, EmailContent> notices) {
        try {
            if (!emailService.isConfigured()) {
                log.warn("Email is not configured: {} {} notification email(s) were not sent", notices.size(), category);
                return;
            }
            List<Target> targets = resolve(category, notices);
            for (Target target : targets) {
                try {
                    emailService.sendNotificationEmail(target.email(), target.content().subject(), target.content().text(), target.content().html());
                } catch (RuntimeException e) {
                    // The address is masked and the provider's answer is not repeated; the operation itself is long committed
                    log.error("A {} notification email to {} could not be sent: {}", category, mask(target.email()), e.getClass().getSimpleName());
                }
            }
        } catch (RuntimeException e) {
            log.error("{} notification emails were not sent: {}", category, e.getClass().getSimpleName());
        }
    }

    /** Who may receive these notices right now, read from the database in its own short read-only transaction. */
    private List<Target> resolve(NotificationCategory category, Map<UUID, EmailContent> notices) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setReadOnly(true);
        return template.execute(status -> {
            List<Target> targets = new ArrayList<>();
            for (Map.Entry<UUID, EmailContent> entry : notices.entrySet()) {
                User user = userRepository.findById(entry.getKey()).orElse(null);
                if (user == null || !user.isActive()) continue;
                if (!usableAddress(user.getEmail())) {
                    log.warn("A {} notification was skipped: the account has no usable email address", category);
                    continue;
                }
                if (!preferences.isEmailEnabled(user.getId(), category)) continue;
                targets.add(new Target(user.getEmail().trim(), entry.getValue()));
            }
            return targets;
        });
    }

    static boolean usableAddress(String email) {
        if (email == null) return false;
        String trimmed = email.trim();
        return !trimmed.isEmpty() && trimmed.length() <= MAX_ADDRESS_LENGTH && PLAIN_ADDRESS.matcher(trimmed).matches();
    }

    private static String mask(String email) {
        int at = email == null ? -1 : email.indexOf('@');
        if (at <= 1) return "***";
        return email.charAt(0) + "***" + email.substring(at);
    }
}
