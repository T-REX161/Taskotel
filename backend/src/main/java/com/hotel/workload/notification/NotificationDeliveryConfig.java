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
 *   <li><b>One worker thread.</b> Events are delivered in the order they were committed, so for example a "deactivated" email can never
 *       overtake the "reactivated" one that followed it.</li>
 *   <li><b>A bounded in-memory queue</b> (1000 events by default, {@code taskotel.notifications.queue-capacity}). When it is full the new
 *       event is DROPPED and logged (category only); the work is never run on the caller's thread, and the business operation is never
 *       affected.</li>
 *   <li><b>Graceful shutdown.</b> On a normal stop the queue is drained for at most 20 seconds before the process exits.</li>
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
