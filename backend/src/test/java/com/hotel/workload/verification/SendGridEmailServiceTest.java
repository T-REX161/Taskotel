package com.hotel.workload.verification;

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

public class SendGridEmailServiceTest {

    @Test
    @DisplayName("SendGrid email contains required subject, body details, 6-digit code, expiration, and disclaimer")
    public void testSendVerificationEmail_Success() throws IOException {
        SendGridProperties properties = new SendGridProperties("SG.fake-test-key", "noreply@hotelwb.com", "Taskotel");
        SendGrid mockSendGrid = mock(SendGrid.class);

        Response mockResponse = new Response();
        mockResponse.setStatusCode(202);
        mockResponse.setBody("Accepted");
        when(mockSendGrid.api(any(Request.class))).thenReturn(mockResponse);

        SendGridEmailService emailService = new SendGridEmailService(properties, mockSendGrid);

        String testCode = "482910";
        String recipient = "staff@hotel.com";
        emailService.sendVerificationEmail(recipient, testCode);

        ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
        verify(mockSendGrid, times(1)).api(requestCaptor.capture());

        Request sentRequest = requestCaptor.getValue();
        assertEquals("mail/send", sentRequest.getEndpoint());
        String body = sentRequest.getBody();

        // Check subject and required body contents
        assertTrue(body.contains("Taskotel - Email Verification Code"), "Email must contain the expected subject");
        assertTrue(body.contains("Welcome to Taskotel."), "Email body must welcome the user to Taskotel");
        assertTrue(body.contains("Taskotel Team"), "Email must be signed by the Taskotel Team");
        assertTrue(body.contains(testCode), "Email body must contain the 6-digit code");
        assertTrue(body.contains("10 minutes"), "Email body must mention 10 minutes expiration");
        assertTrue(body.contains("do not share"), "Email body must contain warning not to share code");
        assertTrue(body.contains("staff@hotel.com"), "Email body must specify the recipient");
        assertTrue(body.contains("noreply@hotelwb.com"), "Email body must specify the from address");
    }

    @Test
    @DisplayName("SendGrid service throws EmailSendingException when API key is not configured or blank")
    public void testSendVerificationEmail_WhenApiKeyNotConfigured() {
        SendGridProperties properties = new SendGridProperties("", "noreply@hotelwb.com", "Taskotel");
        SendGridEmailService emailService = new SendGridEmailService(properties);

        assertThrows(EmailSendingException.class, () ->
                emailService.sendVerificationEmail("test@example.com", "123456")
        );
    }

    @Test
    @DisplayName("SendGrid service throws EmailSendingException when from-email is unconfigured")
    public void testSendVerificationEmail_WhenFromEmailNotConfigured() {
        SendGridProperties properties = new SendGridProperties("SG.valid-key", "", "Taskotel");
        SendGrid mockSendGrid = mock(SendGrid.class);
        SendGridEmailService emailService = new SendGridEmailService(properties, mockSendGrid);

        assertThrows(EmailSendingException.class, () ->
                emailService.sendVerificationEmail("test@example.com", "123456")
        );
    }

    @Test
    @DisplayName("SendGrid service throws EmailSendingException when recipient email is missing or empty")
    public void testSendVerificationEmail_WhenRecipientEmailEmpty() {
        SendGridProperties properties = new SendGridProperties("SG.valid-key", "noreply@hotelwb.com", "Taskotel");
        SendGrid mockSendGrid = mock(SendGrid.class);
        SendGridEmailService emailService = new SendGridEmailService(properties, mockSendGrid);

        assertThrows(EmailSendingException.class, () ->
                emailService.sendVerificationEmail("", "123456")
        );
    }

    @Test
    @DisplayName("SendGrid service throws EmailSendingException when API returns error status")
    public void testSendVerificationEmail_FailureStatus() throws IOException {
        SendGridProperties properties = new SendGridProperties("SG.test-key", "noreply@hotelwb.com", "Taskotel");
        SendGrid mockSendGrid = mock(SendGrid.class);

        Response mockResponse = new Response();
        mockResponse.setStatusCode(401);
        mockResponse.setBody("Unauthorized");
        when(mockSendGrid.api(any(Request.class))).thenReturn(mockResponse);

        SendGridEmailService emailService = new SendGridEmailService(properties, mockSendGrid);

        assertThrows(EmailSendingException.class, () ->
                emailService.sendVerificationEmail("test@example.com", "123456")
        );
    }

    @Test
    @DisplayName("A rejected request is logged with SendGrid's error text, with addresses, keys and codes masked")
    public void testDescribeFailure_MasksSensitiveText() {
        Response response = new Response();
        response.setStatusCode(403);
        response.setBody("{\"errors\":[{\"message\":\"The from address does not match a verified Sender Identity for noreply@hotelwb.com. "
                + "key SG.abc123.def456 code 482910\",\"field\":\"from\",\"help\":\"x\"}]}");
        String text = SendGridEmailService.describeFailure(response);
        assertTrue(text.contains("verified Sender Identity"), text);
        assertTrue(text.contains("field=from"), text);
        assertFalse(text.contains("noreply@hotelwb.com") || text.contains("abc123") || text.contains("482910"), text);
        assertEquals("(no details)", SendGridEmailService.describeFailure(new Response()));
    }

    @Test
    @DisplayName("SendGrid service throws EmailSendingException on IOException")
    public void testSendVerificationEmail_IOException() throws IOException {
        SendGridProperties properties = new SendGridProperties("SG.test-key", "noreply@hotelwb.com", "Taskotel");
        SendGrid mockSendGrid = mock(SendGrid.class);

        when(mockSendGrid.api(any(Request.class))).thenThrow(new IOException("Network failure"));

        SendGridEmailService emailService = new SendGridEmailService(properties, mockSendGrid);

        assertThrows(EmailSendingException.class, () ->
                emailService.sendVerificationEmail("test@example.com", "123456")
        );
    }

    @Test
    @DisplayName("SendGrid password reset email contains required subject, body details, 6-digit code, expiration, disclaimer, and ignore instructions")
    public void testSendPasswordResetEmail_Success() throws IOException {
        SendGridProperties properties = new SendGridProperties("SG.fake-test-key", "noreply@hotelwb.com", "Taskotel");
        SendGrid mockSendGrid = mock(SendGrid.class);

        Response mockResponse = new Response();
        mockResponse.setStatusCode(202);
        mockResponse.setBody("Accepted");
        when(mockSendGrid.api(any(Request.class))).thenReturn(mockResponse);

        SendGridEmailService emailService = new SendGridEmailService(properties, mockSendGrid);

        String testCode = "654321";
        String recipient = "manager@hotel.com";
        emailService.sendPasswordResetEmail(recipient, testCode);

        ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
        verify(mockSendGrid, times(1)).api(requestCaptor.capture());

        Request sentRequest = requestCaptor.getValue();
        assertEquals("mail/send", sentRequest.getEndpoint());
        String body = sentRequest.getBody();

        // Check subject and required body contents
        assertTrue(body.contains("Taskotel - Password Reset Code"), "Email must contain the password reset subject");
        assertTrue(body.contains("your Taskotel account"), "Email body must identify the Taskotel account");
        assertTrue(body.contains("Taskotel Team"), "Email must be signed by the Taskotel Team");
        assertTrue(body.contains(testCode), "Email body must contain the 6-digit reset code");
        assertTrue(body.contains("10 minutes"), "Email body must mention 10 minutes expiration");
        assertTrue(body.contains("do not share"), "Email body must contain warning not to share code");
        assertTrue(body.contains("ignore this email"), "Email body must state that if not requested, the email can be ignored");
        assertTrue(body.contains("manager@hotel.com"), "Email body must specify the recipient");
        assertTrue(body.contains("noreply@hotelwb.com"), "Email body must specify the from address");
    }

    @Test
    @DisplayName("SendGrid password reset email throws EmailSendingException when API key is not configured")
    public void testSendPasswordResetEmail_WhenApiKeyNotConfigured() {
        SendGridProperties properties = new SendGridProperties("", "noreply@hotelwb.com", "Taskotel");
        SendGridEmailService emailService = new SendGridEmailService(properties);

        // Missing configuration must throw EmailSendingException
        assertThrows(EmailSendingException.class, () ->
                emailService.sendPasswordResetEmail("test@example.com", "654321")
        );
    }

    @Test
    @DisplayName("SendGrid password reset email throws EmailSendingException when API returns error status")
    public void testSendPasswordResetEmail_FailureStatus() throws IOException {
        SendGridProperties properties = new SendGridProperties("SG.test-key", "noreply@hotelwb.com", "Taskotel");
        SendGrid mockSendGrid = mock(SendGrid.class);

        Response mockResponse = new Response();
        mockResponse.setStatusCode(403);
        mockResponse.setBody("Forbidden");
        when(mockSendGrid.api(any(Request.class))).thenReturn(mockResponse);

        SendGridEmailService emailService = new SendGridEmailService(properties, mockSendGrid);

        assertThrows(EmailSendingException.class, () ->
                emailService.sendPasswordResetEmail("test@example.com", "654321")
        );
    }

    @Test
    @DisplayName("SendGrid password reset email throws EmailSendingException on IOException")
    public void testSendPasswordResetEmail_IOException() throws IOException {
        SendGridProperties properties = new SendGridProperties("SG.test-key", "noreply@hotelwb.com", "Taskotel");
        SendGrid mockSendGrid = mock(SendGrid.class);

        when(mockSendGrid.api(any(Request.class))).thenThrow(new IOException("Connection reset"));

        SendGridEmailService emailService = new SendGridEmailService(properties, mockSendGrid);

        assertThrows(EmailSendingException.class, () ->
                emailService.sendPasswordResetEmail("test@example.com", "654321")
        );
    }

    @Test
    @DisplayName("Transactional emails use the exact Taskotel wording and contain no EquiFlow branding anywhere")
    public void testExactTaskotelWording() {
        assertEquals("Taskotel - Email Verification Code", SendGridEmailService.VERIFICATION_SUBJECT);
        assertEquals("Taskotel - Password Reset Code", SendGridEmailService.PASSWORD_RESET_SUBJECT);

        SendGridEmailService emailService = new SendGridEmailService(new SendGridProperties());
        String verification = emailService.buildEmailBody("123456");
        String reset = emailService.buildPasswordResetEmailBody("654321");
        assertTrue(verification.contains("Welcome to Taskotel."));
        assertTrue(verification.endsWith("Regards,\nTaskotel Team"));
        assertTrue(reset.contains("your Taskotel account"));
        assertTrue(reset.endsWith("Regards,\nTaskotel Team"));
        // the codes and expiry wording are untouched
        assertTrue(verification.contains("Verification Code: 123456") && verification.contains("Expiration: 10 minutes"));
        assertTrue(reset.contains("Password Reset Code: 654321") && reset.contains("Expiration: 10 minutes"));

        String everything = String.join("\n", SendGridEmailService.VERIFICATION_SUBJECT, SendGridEmailService.PASSWORD_RESET_SUBJECT, verification, reset,
                new SendGridProperties().getFromName());
        assertFalse(everything.toLowerCase().contains("equiflow"), "no EquiFlow in any subject, body, signature or default sender name");
    }

    @Test
    @DisplayName("HTML version: only the number is styled (16px, weight 500, inline); no card, colour, border, heading or container")
    public void testRestrainedCodeStyling() {
        SendGridEmailService emailService = new SendGridEmailService(new SendGridProperties());
        String code = "561254";
        for (String html : new String[] {emailService.buildEmailHtmlBody(code), emailService.buildPasswordResetEmailHtmlBody(code)}) {
            assertTrue(html.contains("Code: <span style=\"font-size:16px;font-weight:500;\">561254</span></p>"), "the label and number sit inline, only the number is styled");
            assertEquals(1, html.split("style=", -1).length - 1, "one inline style in the whole message");
            assertEquals(1, html.split("font-size", -1).length - 1);
            for (String forbidden : new String[] {"background", "border", "padding", "margin", "<div", "<table", "<h1", "<h2", "<h3", "<strong", "<b>", "color", "box", "letter-spacing", "display"}) {
                assertFalse(html.contains(forbidden), "no " + forbidden);
            }
            assertTrue(html.contains("Expiration: 10 minutes") && html.contains("For your security, do not share this code with anyone."));
            assertTrue(html.endsWith("Regards,<br>Taskotel Team</p>"));
        }
        assertTrue(emailService.buildPasswordResetEmailHtmlBody(code).contains("If you did not request a password reset"));
    }

    @Test
    @DisplayName("The code in the HTML version is escaped, so it can never inject markup")
    public void testHtmlCodeIsEscaped() {
        SendGridEmailService emailService = new SendGridEmailService(new SendGridProperties());
        String html = emailService.buildEmailHtmlBody("<b>1</b>\"&");
        assertFalse(html.contains("<b>1</b>"));
        assertTrue(html.contains("&lt;b&gt;1&lt;/b&gt;&quot;&amp;"));
    }

    @Test
    @DisplayName("The plain-text fallback keeps the simple layout: 'Verification Code: <code>' on one line, nothing else changed")
    public void testPlainTextFallbackUnchanged() {
        SendGridEmailService emailService = new SendGridEmailService(new SendGridProperties());
        assertEquals("Hello,\n\n"
                + "Welcome to Taskotel. Please use the following 6-digit verification code to complete your first-login email verification:\n\n"
                + "Verification Code: 561254\n\n"
                + "Expiration: 10 minutes\n\n"
                + "For your security, do not share this code with anyone.\n\n"
                + "Regards,\n"
                + "Taskotel Team", emailService.buildEmailBody("561254"));
        assertTrue(emailService.buildPasswordResetEmailBody("561254").contains("Password Reset Code: 561254\n\n"));
    }

    @Test
    @DisplayName("Both emails are sent as plain text first and HTML second, each carrying the dynamic code")
    public void testSentRequestCarriesBothParts() throws Exception {
        SendGrid mockSendGrid = mock(SendGrid.class);
        Response ok = new Response();
        ok.setStatusCode(202);
        when(mockSendGrid.api(any(Request.class))).thenReturn(ok);
        SendGridEmailService emailService = new SendGridEmailService(new SendGridProperties("SG.fake-test-key", "noreply@hotelwb.com", "Taskotel"), mockSendGrid);

        emailService.sendVerificationEmail("staff@hotel.com", "731905");
        emailService.sendPasswordResetEmail("staff@hotel.com", "842016");

        ArgumentCaptor<Request> captor = ArgumentCaptor.forClass(Request.class);
        verify(mockSendGrid, times(2)).api(captor.capture());
        String[] codes = {"731905", "842016"};
        for (int i = 0; i < 2; i++) {
            String body = captor.getAllValues().get(i).getBody();
            int plain = body.indexOf("\"type\":\"text/plain\""), html = body.indexOf("\"type\":\"text/html\"");
            assertTrue(plain >= 0 && html > plain, "text/plain comes first, then text/html: " + body);
            assertTrue(body.contains("Code: " + codes[i]), "plain text carries the code");
            assertTrue(body.contains("font-size:16px;font-weight:500;"), "html carries the styled number");
            assertTrue(body.contains(codes[i]));
        }
    }

    @Test
    @DisplayName("The sent email names the sender \"Taskotel\" by default, keeps the configured address, and an override still wins")
    public void testSenderDisplayNameInSentRequest() throws Exception {
        SendGrid mockSendGrid = mock(SendGrid.class);
        Response ok = new Response();
        ok.setStatusCode(202);
        when(mockSendGrid.api(any(Request.class))).thenReturn(ok);
        ArgumentCaptor<Request> captor = ArgumentCaptor.forClass(Request.class);

        // no display name configured: the default is used, with the configured sender address unchanged
        new SendGridEmailService(new SendGridProperties("SG.fake-test-key", "me@example.test", null), mockSendGrid).sendVerificationEmail("staff@hotel.com", "123456");
        new SendGridEmailService(new SendGridProperties("SG.fake-test-key", "me@example.test", null), mockSendGrid).sendPasswordResetEmail("staff@hotel.com", "123456");
        // an explicit display name is still respected
        new SendGridEmailService(new SendGridProperties("SG.fake-test-key", "me@example.test", "Custom Sender"), mockSendGrid).sendVerificationEmail("staff@hotel.com", "123456");

        verify(mockSendGrid, times(3)).api(captor.capture());
        for (int i = 0; i < 2; i++) {
            String body = captor.getAllValues().get(i).getBody();
            assertTrue(body.contains("\"name\":\"Taskotel\""), body);
            assertTrue(body.contains("\"email\":\"me@example.test\""), "the sender address is unchanged");
            assertFalse(body.contains("Hotel Workload Balancer") || body.contains("EquiFlow"));
        }
        assertTrue(captor.getAllValues().get(2).getBody().contains("\"name\":\"Custom Sender\""));
    }

    @Test
    @DisplayName("The sender display name defaults to Taskotel in every configuration file, and an explicit override still wins")
    public void testSenderNameDefaultsInConfiguration() throws Exception {
        for (String file : new String[] {"src/main/resources/application.yml", "src/main/resources/application-prod.yml", "src/main/resources/application-production.yml"}) {
            String yaml = java.nio.file.Files.readString(java.nio.file.Path.of(file));
            assertTrue(yaml.contains("from-name: ${SENDGRID_FROM_NAME:Taskotel}"), file);
            assertFalse(yaml.contains("EquiFlow"), file);
        }
        assertEquals("My Hotel", new SendGridProperties("SG.key", "noreply@hotelwb.com", "My Hotel").getFromName());
    }

    @Test
    @DisplayName("Default sender display name is Taskotel, and email texts carry no old product branding")
    public void testTaskotelBranding() {
        assertEquals("Taskotel", new SendGridProperties().getFromName(), "Default sender display name");
        assertEquals("Taskotel", new SendGridProperties("SG.key", "noreply@hotelwb.com", null).getFromName(), "Null display name falls back to the default");

        SendGridEmailService emailService = new SendGridEmailService(new SendGridProperties());
        String texts = String.join("\n",
                SendGridEmailService.VERIFICATION_SUBJECT,
                SendGridEmailService.PASSWORD_RESET_SUBJECT,
                emailService.buildEmailBody("123456"),
                emailService.buildPasswordResetEmailBody("654321"));
        assertFalse(texts.contains("Hotel WB"), "Email texts must not contain the old \"Hotel WB\" branding");
        assertFalse(texts.contains("Workload Balancer"), "Email texts must not contain the old \"Workload Balancer\" branding");
        assertTrue(SendGridEmailService.VERIFICATION_SUBJECT.startsWith("Taskotel"), "Verification subject must start with Taskotel");
        assertTrue(SendGridEmailService.PASSWORD_RESET_SUBJECT.startsWith("Taskotel"), "Password reset subject must start with Taskotel");
    }
}
