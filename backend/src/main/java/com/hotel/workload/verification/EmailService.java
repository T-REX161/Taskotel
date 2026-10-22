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
     * Checks if the email service is properly configured to send emails.
     *
     * @return true if configured, false otherwise
     */
    default boolean isConfigured() {
        return true;
    }
}
