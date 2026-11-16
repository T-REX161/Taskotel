package com.hotel.workload.staff;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.hotel.workload.notification.EmailContent;
import com.hotel.workload.notification.NotificationEmailTemplates;
import com.hotel.workload.verification.EmailSendingException;
import com.hotel.workload.verification.EmailService;
import com.hotel.workload.verification.SendGridEmailService;
import com.hotel.workload.verification.SendGridProperties;
import com.sendgrid.Request;
import com.sendgrid.Response;
import com.sendgrid.SendGrid;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** The invitation email itself (content, escaping, link), the login URL rules, the service's behaviour, and the SendGrid path with log safety. */
public class AccountInvitationEmailTest {

    private static final String PASSWORD = "Tmp@Pass1234xYz";

    // ── the email ────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("The email: required subject, welcome, username, temporary password, a prominent login button and link, first-login steps, no internal ids")
    public void content() {
        EmailContent email = NotificationEmailTemplates.accountInvitation("mia.rao", PASSWORD, "https://taskotel.example.com/login");
        assertEquals("Welcome to Taskotel — Your Account Details", email.subject());
        for (String fragment : new String[] {"Welcome to Taskotel | Hotel Operations", "Username: mia.rao", "Temporary password: " + PASSWORD,
                "Open Taskotel Login: https://taskotel.example.com/login", "How to get started", "1. Open the Taskotel login page",
                "verification code", "permanent password", "Taskotel Team", "limited time"}) {
            assertTrue(email.text().contains(fragment), fragment + " in:\n" + email.text());
        }
        assertTrue(email.html().contains("<a href=\"https://taskotel.example.com/login\""), "the button links to the login page");
        assertTrue(email.html().contains(">Open Taskotel Login</a>"));
        assertTrue(email.html().contains("Welcome to Taskotel | Hotel Operations") && email.html().contains("mia.rao") && email.html().contains(PASSWORD));
        assertFalse(java.util.regex.Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}").matcher(email.text() + email.html()).find(),
                "no ids of any kind");
    }

    @Test
    @DisplayName("Everything dynamic is escaped in the HTML; the subject is fixed; a link that is not http(s) is never made a button")
    public void escapingAndLinkSafety() {
        String nasty = "<b>x</b>&\"'z";
        EmailContent email = NotificationEmailTemplates.accountInvitation("<script>alert(1)</script>", nasty, "https://example.test/login\"onmouseover=\"x");
        assertFalse(email.html().contains("<script>") || email.html().contains("<b>x</b>"), "no markup from a value");
        assertTrue(email.html().contains("&lt;script&gt;") && email.html().contains("&lt;b&gt;x&lt;/b&gt;&amp;&quot;&#39;z"));
        assertFalse(email.html().contains("onmouseover=\"x"), "a quote in the link cannot break out of the attribute");
        assertFalse(email.subject().contains("\n") || email.subject().contains("script"));

        EmailContent bad = NotificationEmailTemplates.accountInvitation("u", PASSWORD, "javascript:alert(1)");
        assertFalse(bad.html().contains("href"), "no button for a non-http(s) link");
        assertFalse(bad.text().contains("javascript:"));
    }

    // ── the login URL ────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("The login link comes from configuration: slashes tidied, http or https only, never invented; the development default is the local frontend")
    public void loginUrlRules() {
        assertEquals("http://localhost:3000/login", AccountInvitationService.loginUrlFor("http://localhost:3000"));
        assertEquals("https://taskotel.example.com/login", AccountInvitationService.loginUrlFor("https://taskotel.example.com"));
        assertEquals("https://taskotel.example.com/login", AccountInvitationService.loginUrlFor("  https://taskotel.example.com///  "));
        assertEquals("https://example.com:8443/app/login", AccountInvitationService.loginUrlFor("https://example.com:8443/app/"));
        for (String unusable : new String[] {null, "", "   ", "taskotel.example.com", "ftp://example.com", "javascript:alert(1)", "https://", "https://exa mple.com", "https://a\"b.com", "https://a<b.com"}) {
            assertEquals("http://localhost:3000/login", AccountInvitationService.loginUrlFor(unusable), "unusable: " + unusable);
        }
    }

    // ── the service ──────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("send() passes exactly the registered address, the username, the temporary password and the login link; incomplete details send nothing")
    public void sendPassesTheRightValues() {
        EmailService email = mock(EmailService.class);
        when(email.isConfigured()).thenReturn(true);
        AccountInvitationService service = new AccountInvitationService(email, "https://app.example.test/");
        assertEquals("https://app.example.test/login", service.loginUrl());

        assertTrue(service.send("  new.user@hotel.test ", "new.user", PASSWORD));
        verify(email).sendAccountInvitationEmail("new.user@hotel.test", "new.user", PASSWORD, "https://app.example.test/login");

        for (String[] incomplete : new String[][] {{null, "u", PASSWORD}, {"", "u", PASSWORD}, {"a@b.test", null, PASSWORD}, {"a@b.test", " ", PASSWORD}, {"a@b.test", "u", null}, {"a@b.test", "u", ""}}) {
            assertFalse(service.send(incomplete[0], incomplete[1], incomplete[2]));
        }
        verify(email, times(1)).sendAccountInvitationEmail(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("send() returns false, never throws, when the email is not configured or the send fails, and logs neither the address nor the password")
    public void sendFailureIsContained() {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        root.addAppender(logs);
        try {
            EmailService email = mock(EmailService.class);
            AccountInvitationService service = new AccountInvitationService(email, "http://localhost:3000");

            when(email.isConfigured()).thenReturn(false);
            assertFalse(service.send("who@hotel.test", "who", PASSWORD));
            verify(email, never()).sendAccountInvitationEmail(anyString(), anyString(), anyString(), anyString());

            when(email.isConfigured()).thenReturn(true);
            doThrow(new EmailSendingException("SendGrid said no for who@hotel.test " + PASSWORD)).when(email)
                    .sendAccountInvitationEmail(anyString(), anyString(), anyString(), anyString());
            assertFalse(service.send("who@hotel.test", "who", PASSWORD));
            doThrow(new IllegalStateException("boom")).when(email).sendAccountInvitationEmail(anyString(), anyString(), anyString(), anyString());
            assertFalse(service.send("who@hotel.test", "who", PASSWORD));
        } finally {
            root.detachAppender(logs);
        }
        for (ILoggingEvent event : logs.list) {
            String line = event.getFormattedMessage();
            assertFalse(line.contains(PASSWORD) || line.contains("who@hotel.test"), "log line leaks a secret or the address: " + line);
        }
        assertTrue(logs.list.stream().anyMatch(e -> e.getFormattedMessage().contains("could not be sent")));
    }

    // ── SendGrid ─────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("SendGrid: the invitation goes out as one mail/send request with both bodies; neither a success nor a failure puts the password in a log line")
    public void sendGridPathAndLogs() throws IOException {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        root.addAppender(logs);
        try {
            SendGrid client = mock(SendGrid.class);
            Response accepted = new Response();
            accepted.setStatusCode(202);
            when(client.api(any(Request.class))).thenReturn(accepted);
            SendGridEmailService service = new SendGridEmailService(new SendGridProperties("SG.fake-test-key", "noreply@hotelwb.com", "Taskotel"), client);

            service.sendAccountInvitationEmail("new.user@hotel.test", "new.user", PASSWORD, "https://app.example.test/login");
            org.mockito.ArgumentCaptor<Request> captor = org.mockito.ArgumentCaptor.forClass(Request.class);
            verify(client).api(captor.capture());
            assertEquals("mail/send", captor.getValue().getEndpoint());
            String body = captor.getValue().getBody();
            assertTrue(body.contains("new.user@hotel.test") && body.contains("Your Account Details") && body.contains("text/plain") && body.contains("text/html"));
            assertTrue(body.contains(PASSWORD) && body.contains("https://app.example.test/login"));

            Response refused = new Response();
            refused.setStatusCode(400);
            refused.setBody("{\"errors\":[{\"message\":\"bad\"}]}");
            when(client.api(any(Request.class))).thenReturn(refused);
            assertThrows(EmailSendingException.class, () -> service.sendAccountInvitationEmail("new.user@hotel.test", "new.user", PASSWORD, "https://app.example.test/login"));
            when(client.api(any(Request.class))).thenThrow(new IOException("connection reset"));
            assertThrows(EmailSendingException.class, () -> service.sendAccountInvitationEmail("new.user@hotel.test", "new.user", PASSWORD, "https://app.example.test/login"));
            assertThrows(EmailSendingException.class, () -> service.sendAccountInvitationEmail("", "new.user", PASSWORD, "https://app.example.test/login"));
        } finally {
            root.detachAppender(logs);
        }
        for (ILoggingEvent event : logs.list) {
            assertFalse(event.getFormattedMessage().contains(PASSWORD), "a log line contains the temporary password: " + event.getFormattedMessage());
            assertFalse(event.getFormattedMessage().contains("new.user@hotel.test"), "a log line contains the full address");
        }
    }
}
