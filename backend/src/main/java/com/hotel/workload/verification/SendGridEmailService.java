package com.hotel.workload.verification;

import com.sendgrid.Method;
import com.sendgrid.Request;
import com.sendgrid.Response;
import com.sendgrid.SendGrid;
import com.sendgrid.helpers.mail.Mail;
import com.sendgrid.helpers.mail.objects.Content;
import com.sendgrid.helpers.mail.objects.Email;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
public class SendGridEmailService implements EmailService {

    private static final Logger log = LoggerFactory.getLogger(SendGridEmailService.class);

    public static final String VERIFICATION_SUBJECT = "EquiFlow - Email Verification Code";
    public static final String PASSWORD_RESET_SUBJECT = "EquiFlow - Password Reset Code";

    private final SendGridProperties properties;
    private final SendGrid sendGridClient;

    @org.springframework.beans.factory.annotation.Autowired
    public SendGridEmailService(SendGridProperties properties) {
        this.properties = properties;
        String key = properties.getApiKey();
        if (key != null && !key.isBlank() && !key.equalsIgnoreCase("test-sendgrid-api-key") && !key.startsWith("test-")) {
            this.sendGridClient = new SendGrid(key);
        } else {
            this.sendGridClient = null;
        }
    }

    public SendGridEmailService(SendGridProperties properties, SendGrid sendGridClient) {
        this.properties = properties;
        this.sendGridClient = sendGridClient;
    }

    @Override
    public void sendVerificationEmail(String toEmail, String code) {
        if (sendGridClient == null) {
            log.error("SendGrid API key is not configured or missing. Cannot dispatch verification email to recipient: {}", maskEmail(toEmail));
            throw new EmailSendingException("SendGrid is not configured. Failed to dispatch verification email.");
        }

        if (properties == null || properties.getFromEmail() == null || properties.getFromEmail().isBlank()) {
            log.error("SendGrid sender email (from-email) is not configured. Cannot dispatch verification email to recipient: {}", maskEmail(toEmail));
            throw new EmailSendingException("SendGrid sender email is not configured.");
        }

        if (toEmail == null || toEmail.isBlank()) {
            log.error("Recipient email is missing or empty.");
            throw new EmailSendingException("Recipient email is required.");
        }

        String body = buildEmailBody(code);

        Email from = new Email(properties.getFromEmail(), properties.getFromName());
        Email to = new Email(toEmail);
        Content content = new Content("text/plain", body);
        Mail mail = new Mail(from, VERIFICATION_SUBJECT, to, content);

        try {
            Request request = new Request();
            request.setMethod(Method.POST);
            request.setEndpoint("mail/send");
            request.setBody(mail.build());

            Response response = sendGridClient.api(request);
            if (response.getStatusCode() >= 200 && response.getStatusCode() < 300) {
                log.info("Verification email successfully dispatched to recipient: {}", maskEmail(toEmail));
            } else {
                log.error("Failed to send verification email via SendGrid. HTTP Status: {}", response.getStatusCode());
                throw new EmailSendingException("Failed to send verification email via SendGrid, status code: " + response.getStatusCode());
            }
        } catch (IOException e) {
            log.error("IOException occurred while dispatching verification email via SendGrid: {}", e.getMessage());
            throw new EmailSendingException("Failed to dispatch verification email", e);
        }
    }

    public String buildEmailBody(String code) {
        return "Hello,\n\n"
                + "Welcome to EquiFlow. Please use the following 6-digit verification code to complete your first-login email verification:\n\n"
                + "Verification Code: " + code + "\n\n"
                + "Expiration: 10 minutes\n\n"
                + "For your security, do not share this code with anyone.\n\n"
                + "Regards,\n"
                + "EquiFlow Team";
    }

    @Override
    public void sendPasswordResetEmail(String toEmail, String code) {
        if (sendGridClient == null) {
            log.error("SendGrid API key is not configured or missing. Cannot dispatch password reset email to recipient: {}", maskEmail(toEmail));
            throw new EmailSendingException("SendGrid is not configured. Failed to dispatch password reset email.");
        }

        if (properties == null || properties.getFromEmail() == null || properties.getFromEmail().isBlank()) {
            log.error("SendGrid sender email (from-email) is not configured. Cannot dispatch password reset email to recipient: {}", maskEmail(toEmail));
            throw new EmailSendingException("SendGrid sender email is not configured.");
        }

        if (toEmail == null || toEmail.isBlank()) {
            log.error("Recipient email is missing or empty.");
            throw new EmailSendingException("Recipient email is required.");
        }

        String body = buildPasswordResetEmailBody(code);

        Email from = new Email(properties.getFromEmail(), properties.getFromName());
        Email to = new Email(toEmail);
        Content content = new Content("text/plain", body);
        Mail mail = new Mail(from, PASSWORD_RESET_SUBJECT, to, content);

        try {
            Request request = new Request();
            request.setMethod(Method.POST);
            request.setEndpoint("mail/send");
            request.setBody(mail.build());

            Response response = sendGridClient.api(request);
            if (response.getStatusCode() >= 200 && response.getStatusCode() < 300) {
                log.info("Password reset email successfully dispatched to recipient: {}", maskEmail(toEmail));
            } else {
                log.error("Failed to send password reset email via SendGrid. HTTP Status: {}", response.getStatusCode());
                throw new EmailSendingException("Failed to send password reset email via SendGrid, status code: " + response.getStatusCode());
            }
        } catch (IOException e) {
            log.error("IOException occurred while dispatching password reset email via SendGrid: {}", e.getMessage());
            throw new EmailSendingException("Failed to dispatch password reset email", e);
        }
    }

    public String buildPasswordResetEmailBody(String code) {
        return "Hello,\n\n"
                + "You have requested a password reset for your EquiFlow account.\n\n"
                + "Password Reset Code: " + code + "\n\n"
                + "Expiration: 10 minutes\n\n"
                + "For your security, do not share this code with anyone.\n\n"
                + "If you did not request a password reset, please ignore this email and your password will remain unchanged.\n\n"
                + "Regards,\n"
                + "EquiFlow Team";
    }

    private String maskEmail(String email) {
        if (email == null || !email.contains("@")) {
            return "***";
        }
        int atIndex = email.indexOf("@");
        if (atIndex <= 2) {
            return "***" + email.substring(atIndex);
        }
        return email.substring(0, 2) + "***" + email.substring(atIndex);
    }

    @Override
    public boolean isConfigured() {
        return sendGridClient != null;
    }
}
