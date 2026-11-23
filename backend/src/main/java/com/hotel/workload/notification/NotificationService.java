package com.hotel.workload.notification;

import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
import com.hotel.workload.verification.EmailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
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
import java.util.function.Supplier;
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

    /**
     * One message for one account.
     *
     * @param includeInactive only honoured for the SECURITY category: the account may be inactive at delivery. It exists for the single
     *                        notice that tells an owner their account was just deactivated; no other notice type is ever delivered to
     *                        an inactive account.
     */
    public record Notice(UUID userId, EmailContent content, boolean includeInactive) {
        public Notice(UUID userId, EmailContent content) {
            this(userId, content, false);
        }
    }

    private record Target(UUID userId, String email, EmailContent content) {}

    private final UserRepository userRepository;
    private final NotificationPreferenceService preferences;
    private final EmailService emailService;
    private final PlatformTransactionManager transactionManager;
    private final TaskExecutor executor;
    private final boolean async;

    public NotificationService(UserRepository userRepository, NotificationPreferenceService preferences, EmailService emailService,
                               PlatformTransactionManager transactionManager,
                               @Qualifier(NotificationDeliveryConfig.EXECUTOR_BEAN) TaskExecutor executor,
                               @Value("${taskotel.notifications.async:true}") boolean async) {
        this.userRepository = userRepository;
        this.preferences = preferences;
        this.emailService = emailService;
        this.transactionManager = transactionManager;
        this.executor = executor;
        this.async = async;
    }

    /**
     * Sends the notices for one event once the current transaction has committed (or now, when there is none). Never throws.
     *
     * @param category the preference that decides whether each recipient wants it (SECURITY always does)
     * @param notices  the messages, one per account; an account named twice receives the first one only
     */
    public void sendAfterCommit(NotificationCategory category, Collection<Notice> notices) {
        if (notices == null || notices.isEmpty()) return;
        sendAfterCommit(category, () -> notices);
    }

    /**
     * As {@link #sendAfterCommit(NotificationCategory, Collection)}, but WHO receives the message is decided only at delivery time: the
     * supplier runs after the commit, in a short read-only transaction, so the recipients (an assignee, a Manager of a department) come from
     * the database as it is then, with every grant, account and department state current. A supplier that throws sends nothing.
     */
    public void sendAfterCommit(NotificationCategory category, Supplier<Collection<Notice>> recipients) {
        try {
            if (category == null || recipients == null) return;
            if (TransactionSynchronizationManager.isSynchronizationActive() && TransactionSynchronizationManager.isActualTransactionActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        dispatch(category, recipients);
                    }
                });
            } else {
                dispatch(category, recipients);
            }
        } catch (RuntimeException e) {
            log.error("Could not schedule {} notification emails: {}", category, e.getClass().getSimpleName());
        }
    }

    /**
     * Runs {@code work} on the notification worker once the current transaction has committed (or now, when there is none): the same
     * after-commit, bounded, best-effort hand-off as the emails, for work that decides FOR ITSELF whom to tell (workload alerts). A full
     * queue or a shutdown drops it and logs; a failure inside it is logged and contained. Never throws.
     */
    public void runAfterCommit(String label, Runnable work) {
        try {
            if (work == null) return;
            if (TransactionSynchronizationManager.isSynchronizationActive() && TransactionSynchronizationManager.isActualTransactionActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        dispatchWork(label, work);
                    }
                });
            } else {
                dispatchWork(label, work);
            }
        } catch (RuntimeException e) {
            log.error("Could not schedule {}: {}", label, e.getClass().getSimpleName());
        }
    }

    private void dispatchWork(String label, Runnable work) {
        Runnable guarded = () -> {
            try {
                work.run();
            } catch (RuntimeException e) {
                log.error("{} failed: {}", label, e.getClass().getSimpleName());
            }
        };
        if (!async) {
            guarded.run();
            return;
        }
        try {
            executor.execute(guarded);
        } catch (RuntimeException e) {
            log.warn("A {} event was dropped: the delivery queue is full or the application is shutting down", label);
        }
    }

    /**
     * For code that is ALREADY running on the worker after its own commit: resolves the recipients (active, verified address, preference)
     * and sends, right here. It must be called with no transaction or lock held.
     */
    public void sendNow(NotificationCategory category, Collection<Notice> notices) {
        if (category == null || notices == null || notices.isEmpty()) return;
        deliver(category, () -> notices);
    }

    /** What became of one account's message in a tracked delivery. SENT means the email provider accepted the request, nothing more. */
    public enum Outcome {
        /** The provider accepted the request (this is not proof that the inbox received it). */
        SENT,
        /** The provider CONFIRMED a refusal (an HTTP error answer), or email is not configured: it is known that nothing was sent. Nothing retries it. */
        FAILED,
        /**
         * Whether the provider accepted the request is NOT known: the connection broke or timed out after the request may have been received,
         * or an unexpected error occurred. This is not proof that no email was sent, so callers must count it as possibly sent. Nothing retries it.
         */
        UNKNOWN,
        /** Not sent, on purpose: the account is gone, inactive, unverified, has no usable address, switched the category off, or shares an address already served. */
        SKIPPED
    }

    /** Whether the email provider is configured (a caller that records "sent" must not run without it). */
    public boolean isEmailConfigured() {
        return emailService.isConfigured();
    }

    /**
     * For messages whose "already sent" record must be TRUTHFUL (task reminders). Unlike {@link #sendAfterCommit}, the caller learns:
     * <ul>
     *   <li><b>at once</b>, from the return value, whether the worker ACCEPTED the job. {@code false} means the queue is full or the
     *       application is shutting down: nothing will run, and the caller must release whatever it reserved;</li>
     *   <li><b>later, on the worker</b>, through {@code onFinished}, what became of each named account (see {@link Outcome}). The callback
     *       runs after the emails were attempted, with no transaction or lock held; a failure inside it is logged and contained.</li>
     * </ul>
     * Who receives the message is decided on the worker at delivery time, exactly as in {@link #sendAfterCommit}. If the application stops or
     * crashes after the job was accepted but before it ran, neither the email nor the callback happens: the caller's reservation then stays
     * unresolved, and nothing here repeats it. This is best effort, not guaranteed delivery.
     */
    public boolean enqueueTracked(NotificationCategory category, Supplier<Collection<Notice>> recipients, java.util.function.Consumer<Map<UUID, Outcome>> onFinished) {
        return enqueueTracked(category, recipients, onFinished, null);
    }

    /**
     * As above, and accounts that SHARE one email address (any letter case) are handled without losing anyone's content: every eligible
     * account sharing an address is served by ONE email, whose content {@code mergeForSharedAddress} builds from the accounts' ids (in
     * order). Each account's own eligibility and preference are decided first and independently, so an account that opted out is never
     * merged in and is reported SKIPPED on its own, while every merged account gets the outcome of that one email. With no merger, the first
     * account wins and the others are reported SKIPPED (the behaviour of {@link #sendAfterCommit}).
     */
    public boolean enqueueTracked(NotificationCategory category, Supplier<Collection<Notice>> recipients, java.util.function.Consumer<Map<UUID, Outcome>> onFinished,
                                  java.util.function.Function<List<UUID>, EmailContent> mergeForSharedAddress) {
        if (category == null || recipients == null || onFinished == null) return false;
        Runnable job = () -> {
            Map<UUID, Outcome> outcomes = deliverTracked(category, recipients, mergeForSharedAddress);
            try {
                onFinished.accept(outcomes);
            } catch (RuntimeException e) {
                log.error("{} delivery bookkeeping failed: {}", category, e.getClass().getSimpleName());
            }
        };
        if (!async) {
            job.run();
            return true;
        }
        try {
            executor.execute(job);
            return true;
        } catch (RuntimeException e) {
            log.warn("A {} job was not accepted: the delivery queue is full or the application is shutting down", category);
            return false;
        }
    }

    private Map<UUID, Outcome> deliverTracked(NotificationCategory category, Supplier<Collection<Notice>> recipients,
                                              java.util.function.Function<List<UUID>, EmailContent> merger) {
        Map<UUID, Outcome> outcomes = new LinkedHashMap<>();
        try {
            java.util.Set<UUID> named = new java.util.LinkedHashSet<>();
            boolean configured = emailService.isConfigured();
            List<Target> eligible = resolveEligible(category, recipients, named, false);
            for (UUID id : named) outcomes.put(id, configured ? Outcome.SKIPPED : Outcome.FAILED);
            if (!configured) {
                log.warn("Email is not configured: {} notification email(s) were not sent", category);
                return outcomes;
            }
            // One email per address: the accounts that share one are grouped (in the order they were named)
            Map<String, List<Target>> byAddress = new LinkedHashMap<>();
            for (Target target : eligible) {
                byAddress.computeIfAbsent(target.email().toLowerCase(java.util.Locale.ROOT), k -> new ArrayList<>()).add(target);
            }
            for (List<Target> group : byAddress.values()) {
                Target first = group.get(0);
                if (group.size() == 1 || merger == null) {
                    // A lone account, or no merger: the first account is served (any others stay SKIPPED, as in sendAfterCommit)
                    outcomes.put(first.userId(), sendOne(category, first.email(), first.content()));
                    continue;
                }
                EmailContent merged;
                try {
                    merged = merger.apply(group.stream().map(Target::userId).toList());
                } catch (RuntimeException e) {
                    merged = null;
                    log.error("Combining {} notices for a shared address failed: {}", category, e.getClass().getSimpleName());
                }
                if (merged == null) {
                    // Nothing is lost: each account gets its own email (a duplicate at worst, never a missing reminder)
                    for (Target target : group) outcomes.put(target.userId(), sendOne(category, target.email(), target.content()));
                } else {
                    Outcome shared = sendOne(category, first.email(), merged);
                    for (Target target : group) outcomes.put(target.userId(), shared);
                }
            }
        } catch (RuntimeException e) {
            log.error("{} notification emails were not sent: {}", category, e.getClass().getSimpleName());
        }
        return outcomes;
    }

    /** Sends one email and says what is KNOWN about it: SENT, FAILED (the provider confirmed a refusal) or UNKNOWN (it may have been accepted). */
    private Outcome sendOne(NotificationCategory category, String address, EmailContent content) {
        try {
            emailService.sendNotificationEmail(address, content.subject(), content.text(), content.html());
            return Outcome.SENT;
        } catch (RuntimeException e) {
            Outcome outcome = classify(e);
            log.error("A {} notification email to {} could not be sent ({}): {}", category, mask(address), outcome, e.getClass().getSimpleName());
            return outcome;
        }
    }

    /**
     * A provider answer (an EmailSendingException with no I/O cause: SendGrid replied with an error, or the client was not configured)
     * proves nothing was accepted: FAILED. A broken or timed-out connection (an I/O cause), or anything unexpected, does not: the request
     * may have been received, so it is UNKNOWN.
     */
    static Outcome classify(RuntimeException e) {
        if (!(e instanceof com.hotel.workload.verification.EmailSendingException)) return Outcome.UNKNOWN;
        for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof java.io.IOException) return Outcome.UNKNOWN;
        }
        return Outcome.FAILED;
    }

    /**
     * Hands the delivery to the notification worker (see {@link NotificationDeliveryConfig}), so the calling thread returns at once. If the
     * queue is full, or the application is shutting down, the event is dropped and logged: it is never run on the calling thread. With
     * {@code taskotel.notifications.async=false} (the tests) it runs right here, as before.
     */
    private void dispatch(NotificationCategory category, Supplier<Collection<Notice>> recipients) {
        if (!async) {
            deliver(category, recipients);
            return;
        }
        try {
            executor.execute(() -> deliver(category, recipients));
        } catch (RuntimeException e) {
            log.warn("A {} notification was dropped: the delivery queue is full or the application is shutting down", category);
        }
    }

    private void deliver(NotificationCategory category, Supplier<Collection<Notice>> recipients) {
        try {
            if (!emailService.isConfigured()) {
                log.warn("Email is not configured: {} notification email(s) were not sent", category);
                return;
            }
            List<Target> targets = resolve(category, recipients);
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
    private List<Target> resolve(NotificationCategory category, Supplier<Collection<Notice>> recipients) {
        return resolveEligible(category, recipients, null, true);
    }

    /**
     * Every ELIGIBLE account (active, verified usable address, category switched on), read in one short read-only transaction. When
     * {@code named} is given, every account the supplier named (eligible or not) is added to it. With {@code firstAddressWins} two accounts
     * sharing an address collapse to the first; without it all of them are returned so the caller can combine them.
     */
    private List<Target> resolveEligible(NotificationCategory category, Supplier<Collection<Notice>> recipients, java.util.Set<UUID> named, boolean firstAddressWins) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setReadOnly(true);
        return template.execute(status -> {
            // One notice per account per event: a person named twice receives the first notice only
            Map<UUID, Notice> notices = new LinkedHashMap<>();
            Collection<Notice> given = recipients.get();
            if (given != null) {
                for (Notice notice : given) {
                    if (notice != null && notice.userId() != null && notice.content() != null) notices.putIfAbsent(notice.userId(), notice);
                }
            }
            List<Target> targets = new ArrayList<>();
            java.util.Set<String> addresses = new java.util.HashSet<>();
            boolean security = category == NotificationCategory.SECURITY;
            for (Map.Entry<UUID, Notice> entry : notices.entrySet()) {
                if (named != null) named.add(entry.getKey());
                User user = userRepository.findById(entry.getKey()).orElse(null);
                if (user == null) continue;
                // Inactive accounts are skipped, except for the one explicit SECURITY notice that tells an owner their account was deactivated
                if (!user.isActive() && !(security && entry.getValue().includeInactive())) continue;
                // Every notification, whatever its category, goes only to an address the owner has verified (an account that has not
                // finished onboarding has an address nobody has confirmed, possibly mistyped). The deactivation exception above lifts the
                // "active" requirement only: that notice needs a verified address too.
                if (!user.isEmailVerified()) {
                    log.warn("A {} notification was skipped: the account's email address is not verified", category);
                    continue;
                }
                if (!usableAddress(user.getEmail())) {
                    log.warn("A {} notification was skipped: the account has no usable email address", category);
                    continue;
                }
                if (!preferences.isEmailEnabled(user.getId(), category)) continue;
                // Two accounts sharing one address (any letter case) get one email between them, not two
                if (firstAddressWins && !addresses.add(user.getEmail().trim().toLowerCase(java.util.Locale.ROOT))) continue;
                targets.add(new Target(user.getId(), user.getEmail().trim(), entry.getValue().content()));
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
