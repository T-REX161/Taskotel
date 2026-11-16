package com.hotel.workload.verification;

import com.sendgrid.Client;
import com.sendgrid.SendGrid;
import org.apache.http.impl.client.CloseableHttpClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F1a: every call to SendGrid has explicit HTTP timeouts. A local socket that accepts a connection and never answers stands in for a
 * stalled SendGrid; nothing is ever sent to the real service.
 */
public class SendGridTimeoutTest {

    /** Accepts connections and never replies, until closed. */
    private static final class BlackHole implements AutoCloseable {
        final ServerSocket server;
        final List<Socket> accepted = new ArrayList<>();
        final Thread acceptor;

        BlackHole() throws Exception {
            server = new ServerSocket(0);
            acceptor = new Thread(() -> {
                try {
                    while (!server.isClosed()) accepted.add(server.accept());
                } catch (Exception ignored) {
                    // closed
                }
            }, "black-hole");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        int port() {
            return server.getLocalPort();
        }

        @Override
        public void close() throws Exception {
            server.close();
            for (Socket socket : new ArrayList<>(accepted)) socket.close();
        }
    }

    private static SendGridEmailService serviceTalkingTo(int port, SendGridProperties properties) {
        CloseableHttpClient http = SendGridEmailService.buildHttpClient(properties);
        SendGrid sendGrid = new SendGrid("SG.fake-test-key", new Client(http, true)); // true: plain http, for the local socket
        sendGrid.setHost("localhost:" + port);
        return new SendGridEmailService(properties, sendGrid);
    }

    @Test
    @DisplayName("The timeouts have safe defaults and can be configured")
    public void defaultsAndSetters() {
        SendGridProperties properties = new SendGridProperties("SG.x", "noreply@hotelwb.com", "Taskotel");
        assertEquals(5000, properties.getConnectTimeoutMs());
        assertEquals(5000, properties.getConnectionRequestTimeoutMs());
        assertEquals(10000, properties.getSocketTimeoutMs());
        properties.setConnectTimeoutMs(1000);
        properties.setConnectionRequestTimeoutMs(2000);
        properties.setSocketTimeoutMs(3000);
        assertEquals(1000, properties.getConnectTimeoutMs());
        assertEquals(2000, properties.getConnectionRequestTimeoutMs());
        assertEquals(3000, properties.getSocketTimeoutMs());
    }

    @Test
    @DisplayName("A SendGrid that accepts the connection but never answers fails with EmailSendingException after the socket timeout, not never")
    public void stalledResponseTimesOut() throws Exception {
        SendGridProperties properties = new SendGridProperties("SG.fake-test-key", "noreply@hotelwb.com", "Taskotel");
        properties.setSocketTimeoutMs(400);
        try (BlackHole hole = new BlackHole()) {
            SendGridEmailService service = serviceTalkingTo(hole.port(), properties);

            long start = System.nanoTime();
            EmailSendingException notification = assertThrows(EmailSendingException.class,
                    () -> service.sendNotificationEmail("staff@hotel.test", "Subject", "text", "<p>html</p>"));
            long millis = (System.nanoTime() - start) / 1_000_000;
            assertTrue(millis < 5000, "must give up shortly after the 400 ms timeout, took " + millis + " ms");
            assertNotNull(notification.getCause(), "the timeout is carried as the cause");
            assertFalse(String.valueOf(notification.getMessage()).contains("staff@hotel.test"), "no address in the error");

            // the code emails use the same client, and a failure still reaches their callers as EmailSendingException (so nothing is stored)
            assertThrows(EmailSendingException.class, () -> service.sendVerificationEmail("staff@hotel.test", "123456"));
            assertThrows(EmailSendingException.class, () -> service.sendPasswordResetEmail("staff@hotel.test", "123456"));
        }
    }

    @Test
    @DisplayName("The production constructor builds its own client with timeouts, and close() is safe to call repeatedly and when SendGrid is not configured")
    public void closeIsSafe() {
        SendGridEmailService configured = new SendGridEmailService(new SendGridProperties("SG.real-looking-key", "noreply@hotelwb.com", "Taskotel"));
        assertTrue(configured.isConfigured());
        assertDoesNotThrow(configured::close);
        assertDoesNotThrow(configured::close);

        SendGridEmailService notConfigured = new SendGridEmailService(new SendGridProperties("", "noreply@hotelwb.com", "Taskotel"));
        assertFalse(notConfigured.isConfigured());
        assertDoesNotThrow(notConfigured::close);

        SendGridEmailService injected = new SendGridEmailService(new SendGridProperties("SG.x", "noreply@hotelwb.com", "Taskotel"), null);
        assertDoesNotThrow(injected::close);
    }
}
