package com.hotel.workload.notification;

import com.hotel.workload.verification.EmailService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * The small, bounded worker that sends notification emails after the business transaction has committed, so the request thread (and the
 * database connection it holds until its transaction is fully closed) never waits for SendGrid.
 *
 * <ul>
 *   <li><b>One worker thread.</b> Events are delivered in the order they were SUBMITTED to the worker. That is normally, but not
 *       guaranteed to be, the order in which their transactions committed: each request submits from its own thread in its after-commit
 *       callback, so two concurrent transactions on the same account can, in a rare window between commit and callback, submit in the
 *       opposite order (for example a "reactivated" email ahead of the "deactivated" one that committed first). Strict commit-order
 *       delivery would need a database-ordered outbox, which is deliberately not part of this design.</li>
 *   <li><b>A bounded in-memory queue</b> (1000 events by default, {@code taskotel.notifications.queue-capacity}). When it is full the new
 *       event is DROPPED and logged (category only); the work is never run on the caller's thread, and the business operation is never
 *       affected.</li>
 *   <li><b>Graceful shutdown.</b> On a normal stop the queue is drained, waiting at most 20 seconds, and the executor is closed BEFORE the
 *       email client. The order on a context close, as verified against Spring's own code and by
 *       {@code NotificationShutdownLifecycleTest} in a real context: (1) ContextClosedEvent; (2) lifecycle beans stop from the highest
 *       phase down, so the web server's graceful shutdown (phase 2147482623) lets in-flight requests finish and queue their
 *       notifications first, and only then does this executor's own lifecycle (phase 1073741823) pause it; (3) beans are destroyed in
 *       reverse dependency order: this executor drains its queue, then the email client is closed.
 *       {@code setWaitForTasksToCompleteOnShutdown(true)} is what keeps the executor accepting events during step 2: with it, Spring
 *       shuts the executor down late (at step 3) instead of soft-shutting it down early, at step 1, which would reject the events of
 *       in-flight requests. The test fails if that setting is lost.</li>
 * </ul>
 *
 * <p><b>Limits, stated plainly.</b> The queue lives in memory only. Events still queued when the process crashes or is forcibly terminated
 * (or that are still undelivered after the 20 seconds of a graceful stop) are lost, and nothing retries a failed send. Delivery is
 * therefore best effort, not guaranteed. There is deliberately no outbox, persistent queue or retry here.
 */
@Configuration
public class NotificationDeliveryConfig {

    static final String EXECUTOR_BEAN = "notificationExecutor";
    static final int SHUTDOWN_WAIT_SECONDS = 20;

    /**
     * The parameter makes the executor depend on the email service, so on shutdown the executor is closed (and its queue drained) BEFORE
     * the email client is closed: Spring destroys beans in the reverse order of their dependencies.
     */
    @Bean(name = EXECUTOR_BEAN)
    public ThreadPoolTaskExecutor notificationExecutor(@Value("${taskotel.notifications.queue-capacity:1000}") int queueCapacity,
                                                       EmailService emailService) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(Math.max(1, queueCapacity));
        executor.setThreadNamePrefix("notify-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(SHUTDOWN_WAIT_SECONDS);
        // A full queue rejects (the default policy): NotificationService catches it, logs and drops the event. It is never run by the caller.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        return executor;
    }
}
