package com.hotel.workload.verification;

public interface EmailService {

    /**
     * Dispatches the 6-digit email verification code to the recipient using SendGrid.
     *
     * @param toEmail recipient email address
     * @param code 6-digit verification code
     */
    void sendVerificationEmail(String toEmail, String code);

    /**
     * Dispatches the 6-digit password reset code to the recipient using SendGrid.
     *
     * @param toEmail recipient email address
     * @param code 6-digit password reset code
     */
    void sendPasswordResetEmail(String toEmail, String code);

    /**
     * Dispatches the 6-digit code that proves ownership of a NEW email address a signed-in user wants to switch to.
     *
     * @param toNewEmail the new address (never the current one)
     * @param code 6-digit code
     */
    void sendEmailChangeVerificationEmail(String toNewEmail, String code);

    /**
     * Tells the CURRENT address that a change of email address was requested or has been completed. It never contains a code; the new
     * address is shown masked.
     *
     * @param toOldEmail the address the account had until now
     * @param maskedNewEmail the new address with its local part hidden (for example "ma***@example.com")
     * @param completed false when the change was only requested, true once it has taken effect
     */
    void sendEmailChangeNotice(String toOldEmail, String maskedNewEmail, boolean completed);

    /**
     * Checks if the email service is properly configured to send emails.
     *
     * @return true if configured, false otherwise
     */
    default boolean isConfigured() {
        return true;
    }
}
