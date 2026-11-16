package com.hotel.workload.staff;

import com.hotel.workload.verification.EmailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * The invitation email sent when an Admin or a Manager has created an account: the username, the temporary password and a link to the
 * existing login page, so the new user can start without anyone passing credentials on by hand.
 *
 * <p>It is called by the create-account endpoint AFTER the account has been committed (a failed or rolled-back creation never gets here),
 * and it runs in the request, outside any transaction, so that the creator can be told whether the email went out. It deliberately does
 * NOT use the notification worker: that path skips accounts whose email is not verified yet (every new account), and its failures are
 * invisible to the person who created the account.
 *
 * <p>The temporary password is sensitive. It exists here only as an argument for the email and is never logged or stored; a failure is
 * logged without the address and without any credential. A failed email never undoes the account.
 */
@Service
public class AccountInvitationService {

    private static final Logger log = LoggerFactory.getLogger(AccountInvitationService.class);
    static final String DEFAULT_FRONTEND_BASE_URL = "http://localhost:3000";
    static final String LOGIN_PATH = "/login";

    private final EmailService emailService;
    private final String loginUrl;

    public AccountInvitationService(EmailService emailService,
                                    @Value("${taskotel.frontend-base-url:" + DEFAULT_FRONTEND_BASE_URL + "}") String frontendBaseUrl) {
        this.emailService = emailService;
        this.loginUrl = loginUrlFor(frontendBaseUrl);
        // The address only (it is configuration, not a secret), so a deployment still pointing at localhost is easy to spot
        log.info("Account invitation emails link to {}", loginUrl);
    }

    /** The login page link for a configured base address: trailing slashes removed, http or https only, otherwise the development default. */
    static String loginUrlFor(String frontendBaseUrl) {
        String base = frontendBaseUrl == null ? "" : frontendBaseUrl.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        boolean usable = (base.startsWith("http://") || base.startsWith("https://")) && base.length() > "https://".length()
                && base.chars().noneMatch(c -> Character.isWhitespace(c) || c == '"' || c == '<' || c == '>');
        if (!usable) {
            log.warn("taskotel.frontend-base-url is not a usable http(s) address; invitation links use the development default");
            base = DEFAULT_FRONTEND_BASE_URL;
        }
        return base + LOGIN_PATH;
    }

    public String loginUrl() {
        return loginUrl;
    }

    /**
     * Sends the invitation to the address registered for the new account.
     *
     * @return true when the email service accepted it; false when it could not be sent (never throws)
     */
    public boolean send(String toEmail, String username, String temporaryPassword) {
        try {
            if (toEmail == null || toEmail.isBlank() || username == null || username.isBlank() || temporaryPassword == null || temporaryPassword.isEmpty()) {
                log.warn("An account invitation was not sent: the account details are incomplete");
                return false;
            }
            if (!emailService.isConfigured()) {
                log.warn("An account invitation was not sent: email is not configured");
                return false;
            }
            emailService.sendAccountInvitationEmail(toEmail.trim(), username, temporaryPassword, loginUrl);
            return true;
        } catch (RuntimeException e) {
            // Class name only: no address, no username, no password
            log.error("An account invitation email could not be sent: {}", e.getClass().getSimpleName());
            return false;
        }
    }
}
