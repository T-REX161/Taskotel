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

    public static final String VERIFICATION_SUBJECT = "Taskotel - Email Verification Code";
    public static final String PASSWORD_RESET_SUBJECT = "Taskotel - Password Reset Code";

    private final SendGridProperties properties;
    private final SendGrid sendGridClient;

    @org.springframework.beans.factory.annotation.Autowired
    public SendGridEmailService(SendGridProperties properties) {
        this.properties = properties;
        // The sender display name only (never the key or address), so an overriding SENDGRID_FROM_NAME is easy to spot
        log.info("Transactional email sender display name: {}", properties.getFromName());
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
        mail.addContent(new Content("text/html", buildEmailHtmlBody(code))); // after text/plain, as SendGrid requires

        try {
            Request request = new Request();
            request.setMethod(Method.POST);
            request.setEndpoint("mail/send");
            request.setBody(mail.build());

            Response response = sendGridClient.api(request);
            if (response.getStatusCode() >= 200 && response.getStatusCode() < 300) {
                log.info("Verification email successfully dispatched to recipient: {}", maskEmail(toEmail));
            } else {
                log.error("Failed to send verification email via SendGrid. HTTP Status: {}; SendGrid said: {}", response.getStatusCode(), describeFailure(response));
                throw new EmailSendingException("Failed to send verification email via SendGrid, status code: " + response.getStatusCode());
            }
        } catch (IOException e) {
            log.error("IOException occurred while dispatching verification email via SendGrid: {}", e.getMessage());
            throw new EmailSendingException("Failed to dispatch verification email", e);
        }
    }

    public String buildEmailBody(String code) {
        return "Hello,\n\n"
                + "Welcome to Taskotel. Please use the following 6-digit verification code to complete your first-login email verification:\n\n"
                + "Verification Code: " + code + "\n\n"
                + "Expiration: 10 minutes\n\n"
                + "For your security, do not share this code with anyone.\n\n"
                + "Regards,\n"
                + "Taskotel Team";
    }

    /**
     * The same email as buildEmailBody in simple HTML: the wording is identical, and only the number itself is a little larger and
     * semi-bold (an inline style that common email clients honour). No card, background, border, heading or extra container.
     */
    public String buildEmailHtmlBody(String code) {
        return "<p>Hello,</p>"
                + "<p>Welcome to Taskotel. Please use the following 6-digit verification code to complete your first-login email verification:</p>"
                + "<p>Verification Code: " + styledCode(code) + "</p>"
                + "<p>Expiration: 10 minutes</p>"
                + "<p>For your security, do not share this code with anyone.</p>"
                + "<p>Regards,<br>Taskotel Team</p>";
    }

    public String buildPasswordResetEmailHtmlBody(String code) {
        return "<p>Hello,</p>"
                + "<p>You have requested a password reset for your Taskotel account.</p>"
                + "<p>Password Reset Code: " + styledCode(code) + "</p>"
                + "<p>Expiration: 10 minutes</p>"
                + "<p>For your security, do not share this code with anyone.</p>"
                + "<p>If you did not request a password reset, please ignore this email and your password will remain unchanged.</p>"
                + "<p>Regards,<br>Taskotel Team</p>";
    }

    /** Only the digits are styled: 16px and weight 500, inline, escaped like any other text. */
    private static String styledCode(String code) {
        String safe = code == null ? "" : code.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
        return "<span style=\"font-size:16px;font-weight:500;\">" + safe + "</span>";
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
        mail.addContent(new Content("text/html", buildPasswordResetEmailHtmlBody(code)));

        try {
            Request request = new Request();
            request.setMethod(Method.POST);
            request.setEndpoint("mail/send");
            request.setBody(mail.build());

            Response response = sendGridClient.api(request);
            if (response.getStatusCode() >= 200 && response.getStatusCode() < 300) {
                log.info("Password reset email successfully dispatched to recipient: {}", maskEmail(toEmail));
            } else {
                log.error("Failed to send password reset email via SendGrid. HTTP Status: {}; SendGrid said: {}", response.getStatusCode(), describeFailure(response));
                throw new EmailSendingException("Failed to send password reset email via SendGrid, status code: " + response.getStatusCode());
            }
        } catch (IOException e) {
            log.error("IOException occurred while dispatching password reset email via SendGrid: {}", e.getMessage());
            throw new EmailSendingException("Failed to dispatch password reset email", e);
        }
    }

    public String buildPasswordResetEmailBody(String code) {
        return "Hello,\n\n"
                + "You have requested a password reset for your Taskotel account.\n\n"
                + "Password Reset Code: " + code + "\n\n"
                + "Expiration: 10 minutes\n\n"
                + "For your security, do not share this code with anyone.\n\n"
                + "If you did not request a password reset, please ignore this email and your password will remain unchanged.\n\n"
                + "Regards,\n"
                + "Taskotel Team";
    }

    /**
     * What SendGrid said about a rejected request, for the server log only: its error messages and the field they name (for example
     * "The from address does not match a verified Sender Identity"). Email addresses are masked and the text is cut short, so neither
     * the key, a code nor a full address is ever logged. Nothing of this reaches the client.
     */
    static String describeFailure(Response response) {
        String body = response == null ? null : response.getBody();
        if (body == null || body.isBlank()) {
            return "(no details)";
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\"(message|field)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(body);
        StringBuilder details = new StringBuilder();
        while (matcher.find() && details.length() < 400) {
            if (details.length() > 0) {
                details.append("; ");
            }
            details.append(matcher.group(1)).append('=').append(matcher.group(2));
        }
        String text = details.length() > 0 ? details.toString() : body;
        text = text.replaceAll("[\\w.+-]+@[\\w.-]+", "***@***").replaceAll("\\bSG\\.[\\w.-]+", "SG.***").replaceAll("\\b\\d{6}\\b", "******");
        return text.length() > 400 ? text.substring(0, 400) + "..." : text;
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

    public static final String EMAIL_CHANGE_SUBJECT = "Taskotel - Confirm Your New Email Address";
    public static final String EMAIL_CHANGE_REQUESTED_SUBJECT = "Taskotel - Email Address Change Requested";
    public static final String EMAIL_CHANGE_COMPLETED_SUBJECT = "Taskotel - Your Email Address Was Changed";
    public static final String EMAIL_CHANGE_NOT_STARTED_SUBJECT = "Taskotel - Email Address Change Could Not Proceed";
    public static final String EMAIL_ADDRESS_IN_USE_SUBJECT = "Taskotel - Email Address Cannot Be Used";

    @Override
    public void sendEmailChangeVerificationEmail(String toNewEmail, String code) {
        String text = "Hello,\n\n"
                + "Use the following 6-digit code to confirm this as the new email address for your Taskotel account:\n\n"
                + "Verification Code: " + code + "\n\n"
                + "Expiration: 10 minutes\n\n"
                + "For your security, do not share this code with anyone. If you did not ask to change your email address, ignore this message: nothing will change.\n\n"
                + "Regards,\n"
                + "Taskotel Team";
        String html = "<p>Hello,</p>"
                + "<p>Use the following 6-digit code to confirm this as the new email address for your Taskotel account:</p>"
                + "<p>Verification Code: " + styledCode(code) + "</p>"
                + "<p>Expiration: 10 minutes</p>"
                + "<p>For your security, do not share this code with anyone. If you did not ask to change your email address, ignore this message: nothing will change.</p>"
                + "<p>Regards,<br>Taskotel Team</p>";
        dispatch(toNewEmail, EMAIL_CHANGE_SUBJECT, text, html, "email change verification");
    }

    @Override
    public void sendEmailChangeNotice(String toOldEmail, String maskedNewEmail, boolean completed) {
        String what = completed
                ? "The email address on your Taskotel account was changed to " + maskedNewEmail + "."
                : "A request was made to change the email address on your Taskotel account to " + maskedNewEmail + ". Nothing has changed yet.";
        String advice = "If this was not you, change your password right away and contact your administrator.";
        String text = "Hello,\n\n" + what + "\n\n" + advice + "\n\nRegards,\nTaskotel Team";
        String html = "<p>Hello,</p><p>" + escape(what) + "</p><p>" + advice + "</p><p>Regards,<br>Taskotel Team</p>";
        dispatch(toOldEmail, completed ? EMAIL_CHANGE_COMPLETED_SUBJECT : EMAIL_CHANGE_REQUESTED_SUBJECT, text, html, "email change notice");
    }

    @Override
    public void sendAdministratorEmailChangeVerificationEmail(String toNewEmail, String code) {
        String text = "Hello,\n\n"
                + "An administrator or manager asked to set this as the new email address for your Taskotel account. To accept, sign in to Taskotel and enter this 6-digit code under Profile:\n\n"
                + "Verification Code: " + code + "\n\n"
                + "Expiration: 10 minutes\n\n"
                + "For your security, do not share this code with anyone, including the person who asked for the change. If you did not expect this, ignore this message: nothing will change.\n\n"
                + "Regards,\n"
                + "Taskotel Team";
        String html = "<p>Hello,</p>"
                + "<p>An administrator or manager asked to set this as the new email address for your Taskotel account. To accept, sign in to Taskotel and enter this 6-digit code under Profile:</p>"
                + "<p>Verification Code: " + styledCode(code) + "</p>"
                + "<p>Expiration: 10 minutes</p>"
                + "<p>For your security, do not share this code with anyone, including the person who asked for the change. If you did not expect this, ignore this message: nothing will change.</p>"
                + "<p>Regards,<br>Taskotel Team</p>";
        dispatch(toNewEmail, EMAIL_CHANGE_SUBJECT, text, html, "email change verification");
    }

    @Override
    public void sendEmailAddressInUseNotice(String toProposedEmail) {
        String text = "Hello,\n\n"
                + "This email address was submitted as a proposed login address for a Taskotel account, but it cannot be used because it is already registered.\n\n"
                + "No action is needed and nothing has changed. If you did not expect this message, you can ignore it.\n\n"
                + "Regards,\n"
                + "Taskotel Team";
        String html = "<p>Hello,</p>"
                + "<p>This email address was submitted as a proposed login address for a Taskotel account, but it cannot be used because it is already registered.</p>"
                + "<p>No action is needed and nothing has changed. If you did not expect this message, you can ignore it.</p>"
                + "<p>Regards,<br>Taskotel Team</p>";
        dispatch(toProposedEmail, EMAIL_ADDRESS_IN_USE_SUBJECT, text, html, "email address in use notice");
    }

    @Override
    public void sendAdministratorEmailChangeNotice(String toOldEmail, String maskedNewEmail) {
        String what = "An administrator or manager asked to change the email address on your Taskotel account to " + maskedNewEmail
                + ". Nothing has changed yet: the change only happens if you confirm it yourself with a code sent to the new address.";
        String advice = "If you do not recognise this, change your password and contact your administrator.";
        String text = "Hello,\n\n" + what + "\n\n" + advice + "\n\nRegards,\nTaskotel Team";
        String html = "<p>Hello,</p><p>" + escape(what) + "</p><p>" + advice + "</p><p>Regards,<br>Taskotel Team</p>";
        dispatch(toOldEmail, EMAIL_CHANGE_REQUESTED_SUBJECT, text, html, "email change notice");
    }

    /** Text of the notice for a proposed address that cannot be used (public so tests can pin the exact wording). */
    public static String unavailableNoticeText(String maskedProposedEmail) {
        return "An administrator or manager asked to change the email address on your Taskotel account to " + maskedProposedEmail
                + ", but the request could not proceed because that address cannot be used. Your email address has not changed, and no change is waiting for you to confirm.";
    }

    @Override
    public void sendAdministratorEmailChangeUnavailableNotice(String toOldEmail, String maskedProposedEmail) {
        String what = unavailableNoticeText(maskedProposedEmail);
        String advice = "If you do not recognise this, change your password and contact your administrator.";
        String text = "Hello,\n\n" + what + "\n\n" + advice + "\n\nRegards,\nTaskotel Team";
        String html = "<p>Hello,</p><p>" + escape(what) + "</p><p>" + advice + "</p><p>Regards,<br>Taskotel Team</p>";
        dispatch(toOldEmail, EMAIL_CHANGE_NOT_STARTED_SUBJECT, text, html, "email change not started notice");
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** Sends one message (plain text and HTML). Same checks and failure behaviour as the verification and reset emails; nothing sensitive is logged. */
    private void dispatch(String toEmail, String subject, String text, String html, String kind) {
        if (sendGridClient == null) {
            log.error("SendGrid API key is not configured or missing. Cannot dispatch {} email to recipient: {}", kind, maskEmail(toEmail));
            throw new EmailSendingException("SendGrid is not configured. Failed to dispatch " + kind + " email.");
        }
        if (properties == null || properties.getFromEmail() == null || properties.getFromEmail().isBlank()) {
            log.error("SendGrid sender email (from-email) is not configured. Cannot dispatch {} email to recipient: {}", kind, maskEmail(toEmail));
            throw new EmailSendingException("SendGrid sender email is not configured.");
        }
        if (toEmail == null || toEmail.isBlank()) {
            log.error("Recipient email is missing or empty.");
            throw new EmailSendingException("Recipient email is required.");
        }
        Mail mail = new Mail(new Email(properties.getFromEmail(), properties.getFromName()), subject, new Email(toEmail), new Content("text/plain", text));
        mail.addContent(new Content("text/html", html));
        try {
            Request request = new Request();
            request.setMethod(Method.POST);
            request.setEndpoint("mail/send");
            request.setBody(mail.build());
            Response response = sendGridClient.api(request);
            if (response.getStatusCode() >= 200 && response.getStatusCode() < 300) {
                log.info("{} email successfully dispatched to recipient: {}", kind, maskEmail(toEmail));
            } else {
                log.error("Failed to send {} email via SendGrid. HTTP Status: {}; SendGrid said: {}", kind, response.getStatusCode(), describeFailure(response));
                throw new EmailSendingException("Failed to send " + kind + " email via SendGrid, status code: " + response.getStatusCode());
            }
        } catch (IOException e) {
            log.error("IOException occurred while dispatching {} email via SendGrid: {}", kind, e.getMessage());
            throw new EmailSendingException("Failed to dispatch " + kind + " email", e);
        }
    }

    @Override
    public boolean isConfigured() {
        return sendGridClient != null;
    }
}
