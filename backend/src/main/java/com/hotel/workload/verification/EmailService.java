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
     * Like {@link #sendEmailChangeVerificationEmail}, for a change an Admin or Manager started for someone else (Stage 3): the code goes to
     * the NEW address and must be entered by the account holder, signed in, in their own profile. Defaults to the ordinary message.
     */
    default void sendAdministratorEmailChangeVerificationEmail(String toNewEmail, String code) {
        sendEmailChangeVerificationEmail(toNewEmail, code);
    }

    /**
     * Sent to a PROPOSED address that another account already uses, in place of a verification code (so an address that can and one that
     * cannot be used cost the same number of sends). It says only that the address was submitted and cannot be used; it never contains a
     * code, and names no account or person.
     */
    void sendEmailAddressInUseNotice(String toProposedEmail);

    /** Like {@link #sendEmailChangeNotice} (requested, not completed), saying an administrator or manager started the change. */
    default void sendAdministratorEmailChangeNotice(String toOldEmail, String maskedNewEmail) {
        sendEmailChangeNotice(toOldEmail, maskedNewEmail, false);
    }

    /**
     * Sent to the CURRENT address when an Admin or Manager proposed an address that cannot be used. Unlike {@link #sendAdministratorEmailChangeNotice}
     * it must not say a change is waiting: nothing was started and the active address has not changed. It names no account or person.
     */
    void sendAdministratorEmailChangeUnavailableNotice(String toOldEmail, String maskedProposedEmail);

    /**
     * Checks if the email service is properly configured to send emails.
     *
     * @return true if configured, false otherwise
     */
    default boolean isConfigured() {
        return true;
    }
}
