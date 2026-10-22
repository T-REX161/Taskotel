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
        SendGridProperties properties = new SendGridProperties("SG.fake-test-key", "noreply@hotelwb.com", "EquiFlow");
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
        assertTrue(body.contains("EquiFlow - Email Verification Code"), "Email must contain the expected subject");
        assertTrue(body.contains("Welcome to EquiFlow."), "Email body must welcome the user to EquiFlow");
        assertTrue(body.contains("EquiFlow Team"), "Email must be signed by the EquiFlow Team");
        assertTrue(body.contains(testCode), "Email body must contain the 6-digit code");
        assertTrue(body.contains("10 minutes"), "Email body must mention 10 minutes expiration");
        assertTrue(body.contains("do not share"), "Email body must contain warning not to share code");
        assertTrue(body.contains("staff@hotel.com"), "Email body must specify the recipient");
        assertTrue(body.contains("noreply@hotelwb.com"), "Email body must specify the from address");
    }

    @Test
    @DisplayName("SendGrid service throws EmailSendingException when API key is not configured or blank")
    public void testSendVerificationEmail_WhenApiKeyNotConfigured() {
        SendGridProperties properties = new SendGridProperties("", "noreply@hotelwb.com", "EquiFlow");
        SendGridEmailService emailService = new SendGridEmailService(properties);

        assertThrows(EmailSendingException.class, () ->
                emailService.sendVerificationEmail("test@example.com", "123456")
        );
    }

    @Test
    @DisplayName("SendGrid service throws EmailSendingException when from-email is unconfigured")
    public void testSendVerificationEmail_WhenFromEmailNotConfigured() {
        SendGridProperties properties = new SendGridProperties("SG.valid-key", "", "EquiFlow");
        SendGrid mockSendGrid = mock(SendGrid.class);
        SendGridEmailService emailService = new SendGridEmailService(properties, mockSendGrid);

        assertThrows(EmailSendingException.class, () ->
                emailService.sendVerificationEmail("test@example.com", "123456")
        );
    }

    @Test
    @DisplayName("SendGrid service throws EmailSendingException when recipient email is missing or empty")
    public void testSendVerificationEmail_WhenRecipientEmailEmpty() {
        SendGridProperties properties = new SendGridProperties("SG.valid-key", "noreply@hotelwb.com", "EquiFlow");
        SendGrid mockSendGrid = mock(SendGrid.class);
        SendGridEmailService emailService = new SendGridEmailService(properties, mockSendGrid);

        assertThrows(EmailSendingException.class, () ->
                emailService.sendVerificationEmail("", "123456")
        );
    }

    @Test
    @DisplayName("SendGrid service throws EmailSendingException when API returns error status")
    public void testSendVerificationEmail_FailureStatus() throws IOException {
        SendGridProperties properties = new SendGridProperties("SG.test-key", "noreply@hotelwb.com", "EquiFlow");
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
    @DisplayName("SendGrid service throws EmailSendingException on IOException")
    public void testSendVerificationEmail_IOException() throws IOException {
        SendGridProperties properties = new SendGridProperties("SG.test-key", "noreply@hotelwb.com", "EquiFlow");
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
        SendGridProperties properties = new SendGridProperties("SG.fake-test-key", "noreply@hotelwb.com", "EquiFlow");
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
        assertTrue(body.contains("EquiFlow - Password Reset Code"), "Email must contain the password reset subject");
        assertTrue(body.contains("your EquiFlow account"), "Email body must identify the EquiFlow account");
        assertTrue(body.contains("EquiFlow Team"), "Email must be signed by the EquiFlow Team");
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
        SendGridProperties properties = new SendGridProperties("", "noreply@hotelwb.com", "EquiFlow");
        SendGridEmailService emailService = new SendGridEmailService(properties);

        // Missing configuration must throw EmailSendingException
        assertThrows(EmailSendingException.class, () ->
                emailService.sendPasswordResetEmail("test@example.com", "654321")
        );
    }

    @Test
    @DisplayName("SendGrid password reset email throws EmailSendingException when API returns error status")
    public void testSendPasswordResetEmail_FailureStatus() throws IOException {
        SendGridProperties properties = new SendGridProperties("SG.test-key", "noreply@hotelwb.com", "EquiFlow");
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
        SendGridProperties properties = new SendGridProperties("SG.test-key", "noreply@hotelwb.com", "EquiFlow");
        SendGrid mockSendGrid = mock(SendGrid.class);

        when(mockSendGrid.api(any(Request.class))).thenThrow(new IOException("Connection reset"));

        SendGridEmailService emailService = new SendGridEmailService(properties, mockSendGrid);

        assertThrows(EmailSendingException.class, () ->
                emailService.sendPasswordResetEmail("test@example.com", "654321")
        );
    }

    @Test
    @DisplayName("Default sender display name is EquiFlow, and email texts carry no old product branding")
    public void testEquiFlowBranding() {
        assertEquals("EquiFlow", new SendGridProperties().getFromName(), "Default sender display name must be EquiFlow");
        assertEquals("EquiFlow", new SendGridProperties("SG.key", "noreply@hotelwb.com", null).getFromName(), "Null display name falls back to EquiFlow");

        SendGridEmailService emailService = new SendGridEmailService(new SendGridProperties());
        String texts = String.join("\n",
                SendGridEmailService.VERIFICATION_SUBJECT,
                SendGridEmailService.PASSWORD_RESET_SUBJECT,
                emailService.buildEmailBody("123456"),
                emailService.buildPasswordResetEmailBody("654321"));
        assertFalse(texts.contains("Hotel WB"), "Email texts must not contain the old \"Hotel WB\" branding");
        assertFalse(texts.contains("Workload Balancer"), "Email texts must not contain the old \"Workload Balancer\" branding");
        assertTrue(SendGridEmailService.VERIFICATION_SUBJECT.startsWith("EquiFlow"), "Verification subject must start with EquiFlow");
        assertTrue(SendGridEmailService.PASSWORD_RESET_SUBJECT.startsWith("EquiFlow"), "Password reset subject must start with EquiFlow");
    }
}
