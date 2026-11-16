package com.hotel.workload.notification;

import com.hotel.workload.verification.EmailService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.withSettings;

/**
 * The notification worker through a REAL Spring application-context shutdown (the production {@link NotificationDeliveryConfig} in a real
 * context that is refreshed and then closed), not a hand-built executor.
 *
 * <p>The lifecycle, as verified in Spring's own code: on {@code close()} the context first publishes ContextClosedEvent, then stops its
 * SmartLifecycle beans from the highest phase down, then destroys beans in reverse dependency order. In Spring Boot the web server's
 * graceful shutdown is a lifecycle with phase 2147482623, which lets in-flight requests finish (and commit, and queue their notifications)
 * BEFORE the executor's own lifecycle (phase 1073741823) is stopped. {@link InFlightRequests} below plays that role at the same phase.
 */
public class NotificationShutdownLifecycleTest {

    /** What happened, in order, across the shutdown. */
    static final class Events {
        final List<String> log = new CopyOnWriteArrayList<>();
        volatile int rejected;
    }

    /** Stands in for the web server's graceful shutdown: at the web server's phase it finishes "requests" that queue notifications. */
    static final class InFlightRequests implements SmartLifecycle {
        private final ThreadPoolTaskExecutor executor;
        private final Events events;
        private volatile boolean running;

        InFlightRequests(ThreadPoolTaskExecutor executor, Events events) {
            this.executor = executor;
            this.events = events;
        }

        @Override
        public void start() {
            running = true;
        }

        @Override
        public void stop() {
            running = false;
        }

        @Override
        public void stop(Runnable callback) {
            // the requests that are still completing during graceful shutdown each commit and queue one notification
            for (int i = 1; i <= 3; i++) {
                String name = "in-flight-" + i;
                try {
                    executor.execute(() -> {
                        pause(50);
                        events.log.add(name);
                    });
                } catch (RuntimeException e) {
                    events.rejected++;
                }
            }
            running = false;
            callback.run();
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public int getPhase() {
            return 2147482623; // WebServerGracefulShutdownLifecycle.getPhase()
        }
    }

    @Configuration
    static class Harness {
        @Bean
        Events events() {
            return new Events();
        }

        @Bean
        EmailService emailService(Events events) throws Exception {
            EmailService service = Mockito.mock(EmailService.class, withSettings().extraInterfaces(DisposableBean.class));
            doAnswer(invocation -> {
                events.log.add("email-client-closed");
                return null;
            }).when((DisposableBean) service).destroy();
            return service;
        }

        @Bean
        InFlightRequests inFlightRequests(@Qualifier(NotificationDeliveryConfig.EXECUTOR_BEAN) ThreadPoolTaskExecutor executor, Events events) {
            return new InFlightRequests(executor, events);
        }
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static AnnotationConfigApplicationContext started() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.register(NotificationDeliveryConfig.class, Harness.class);
        context.refresh();
        return context;
    }

    @Test
    @DisplayName("Notifications queued by requests that are still completing during graceful shutdown are accepted and delivered, before the email client is closed")
    public void inFlightRequestsAreNotRejectedDuringShutdown() {
        AnnotationConfigApplicationContext context = started();
        Events events = context.getBean(Events.class);
        context.close();

        assertEquals(0, events.rejected, "no notification from an in-flight request may be rejected: " + events.log);
        assertEquals(List.of("in-flight-1", "in-flight-2", "in-flight-3", "email-client-closed"), events.log,
                "all three were delivered, in submission order, and only then was the email client closed");
    }

    @Test
    @DisplayName("Events already queued when the context closes are drained (not discarded) and still run before the email client is closed")
    public void queuedEventsAreDrained() {
        AnnotationConfigApplicationContext context = started();
        Events events = context.getBean(Events.class);
        ThreadPoolTaskExecutor executor = context.getBean(NotificationDeliveryConfig.EXECUTOR_BEAN, ThreadPoolTaskExecutor.class);
        for (int i = 1; i <= 5; i++) {
            String name = "queued-" + i;
            executor.execute(() -> {
                pause(40);
                events.log.add(name);
            });
        }
        context.close();

        assertEquals(0, events.rejected);
        assertEquals(List.of("queued-1", "queued-2", "queued-3", "queued-4", "queued-5", "in-flight-1", "in-flight-2", "in-flight-3", "email-client-closed"),
                events.log);
    }

    @Test
    @DisplayName("Once the context has closed completely, a new event is rejected (and never runs on the caller's thread)")
    public void afterCloseEventsAreRejected() {
        AnnotationConfigApplicationContext context = started();
        ThreadPoolTaskExecutor executor = context.getBean(NotificationDeliveryConfig.EXECUTOR_BEAN, ThreadPoolTaskExecutor.class);
        Events events = context.getBean(Events.class);
        context.close();
        int before = events.log.size();
        assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> events.log.add("late")));
        assertEquals(before, events.log.size(), "the rejected event did not run anywhere, least of all on this thread");
    }
}
