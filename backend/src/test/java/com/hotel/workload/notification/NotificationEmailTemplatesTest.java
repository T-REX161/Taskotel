package com.hotel.workload.notification;

import com.hotel.workload.notification.NotificationEmailTemplates.TaskInfo;
import com.hotel.workload.verification.EmailSendingException;
import com.hotel.workload.verification.SendGridEmailService;
import com.hotel.workload.verification.SendGridProperties;
import com.sendgrid.Request;
import com.sendgrid.Response;
import com.sendgrid.SendGrid;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** The notification email layout and the SendGrid send path for it (SendGrid is mocked; nothing leaves the machine). */
public class NotificationEmailTemplatesTest {

    private static TaskInfo task(String title, String description) {
        return new TaskInfo(title, description, "HIGH", "2027-01-15", "17:30", 45, "Housekeeping");
    }

    @Test
    @DisplayName("Assigned and reassigned emails carry the task facts in plain text and HTML, with the Taskotel branding and the settings hint")
    public void layout() {
        EmailContent assigned = NotificationEmailTemplates.taskAssigned(task("Clean room 402", "Use the green cloth"));
        assertEquals("Taskotel - New task assigned: Clean room 402", assigned.subject());
        for (String fragment : new String[] {"Task: Clean room 402", "Description: Use the green cloth", "Priority: HIGH", "Department: Housekeeping",
                "Due: 2027-01-15 17:30", "Estimated duration: 45 minutes", "Regards,\nTaskotel Team", "Settings > Notification Preferences"}) {
            assertTrue(assigned.text().contains(fragment), fragment);
        }
        assertTrue(assigned.html().contains("Taskotel") && assigned.html().contains("Clean room 402") && assigned.html().contains("Notification Preferences"));

        EmailContent away = NotificationEmailTemplates.taskUnassigned(task("Clean room 402", null));
        assertEquals("Taskotel - Task reassigned: Clean room 402", away.subject());
        assertTrue(away.text().contains("no longer assigned to you"));
        assertFalse(away.text().contains("Description:"), "an empty field is left out");
    }

    @Test
    @DisplayName("Everything dynamic is escaped in the HTML, and the subject never holds a line break")
    public void escapingAndSubject() {
        EmailContent content = NotificationEmailTemplates.taskAssigned(task("<script>alert('x')</script>\r\nBcc: attacker@evil.test", "a & b \"q\""));
        assertFalse(content.html().contains("<script>"), "no raw markup from a title");
        assertTrue(content.html().contains("&lt;script&gt;"));
        assertTrue(content.html().contains("a &amp; b &quot;q&quot;"));
        assertFalse(content.subject().contains("\n") || content.subject().contains("\r"), "no header injection through the subject");
        assertFalse(content.text().contains("\r"));
    }

    @Test
    @DisplayName("A long title is shortened in the subject and a long description in the body")
    public void longValuesAreShortened() {
        EmailContent content = NotificationEmailTemplates.taskAssigned(task("T".repeat(300), "D".repeat(1000)));
        assertTrue(content.subject().length() < 120, content.subject());
        assertFalse(content.text().contains("D".repeat(400)));
    }

    @Test
    @DisplayName("SendGrid: a notification goes out as a mail/send request with the subject, both bodies and the recipient; a non-2xx answer is an EmailSendingException")
    public void sendGridPath() throws IOException {
        SendGrid client = mock(SendGrid.class);
        Response accepted = new Response();
        accepted.setStatusCode(202);
        when(client.api(any(Request.class))).thenReturn(accepted);
        SendGridEmailService service = new SendGridEmailService(new SendGridProperties("SG.fake-test-key", "noreply@hotelwb.com", "Taskotel"), client);

        EmailContent content = NotificationEmailTemplates.taskAssigned(task("Clean room 402", null));
        service.sendNotificationEmail("staff@hotel.test", content.subject(), content.text(), content.html());
        ArgumentCaptor<Request> captor = ArgumentCaptor.forClass(Request.class);
        verify(client).api(captor.capture());
        assertEquals("mail/send", captor.getValue().getEndpoint());
        String body = captor.getValue().getBody();
        assertTrue(body.contains("staff@hotel.test") && body.contains("New task assigned: Clean room 402") && body.contains("text/plain") && body.contains("text/html"));

        Response refused = new Response();
        refused.setStatusCode(400);
        refused.setBody("{\"errors\":[{\"message\":\"bad address\"}]}");
        when(client.api(any(Request.class))).thenReturn(refused);
        assertThrows(EmailSendingException.class, () -> service.sendNotificationEmail("x@hotel.test", "s", "t", "h"));
        assertThrows(EmailSendingException.class, () -> service.sendNotificationEmail("", "s", "t", "h"));
        assertThrows(EmailSendingException.class, () -> new SendGridEmailService(new SendGridProperties("", "noreply@hotelwb.com", "Taskotel"))
                .sendNotificationEmail("x@hotel.test", "s", "t", "h"));
    }

    @Test
    @DisplayName("The status email names the old and new status, escapes everything dynamic, keeps the subject on one line and carries no link")
    public void statusTemplate() {
        EmailContent content = NotificationEmailTemplates.taskStatusChanged(task("<b>Fix</b>\r\nBcc: x@evil.test", null), "Assigned", "In Progress", "Mia <script> (Staff)");
        assertTrue(content.subject().startsWith("Taskotel - Task status updated: "));
        assertTrue(content.subject().endsWith("is now In Progress"));
        assertFalse(content.subject().contains("\n") || content.subject().contains("\r"));
        for (String fragment : new String[] {"Previous status: Assigned", "New status: In Progress", "Priority: HIGH", "Department: Housekeeping", "Due: 2027-01-15 17:30", "Changed by: Mia"}) {
            assertTrue(content.text().contains(fragment), fragment);
        }
        assertFalse(content.html().contains("<script>") || content.html().contains("<b>Fix</b>"));
        assertTrue(content.html().contains("&lt;script&gt;"));
        assertFalse(content.text().contains("http") || content.html().contains("href"), "no link when no application address is configured");
        assertFalse(NotificationEmailTemplates.taskStatusChanged(task("T", null), "Pending", "Assigned", null).text().contains("Changed by"));
    }
}
