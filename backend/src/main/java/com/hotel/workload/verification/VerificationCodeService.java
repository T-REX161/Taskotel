package com.hotel.workload.verification;

public interface VerificationCodeService {

    /**
     * Generates a cryptographically secure 6-digit verification code, hashes and saves it to the user,
     * and dispatches the code to the user's email.
     *
     * <p>For an identifier that matches no account, or an inactive account, it returns normally and sends and stores nothing, so the
     * caller cannot tell the difference.
     *
     * @param userIdentifier username or email
     * @throws VerificationException only when the identifier is blank
     */
    void sendVerificationCode(String userIdentifier);

    /**
     * Validates an entered verification code against the stored hash for the user.
     *
     * @param userIdentifier username or email
     * @param rawCode the 6-digit verification code entered by the user
     * @return setup token if setup is required, or null if fully verified
     * @throws VerificationException with one generic message if verification fails for any reason (no such or inactive account, no active
     *         code, wrong, expired or used-up code); the reason is logged, never returned
     */
    String verifyCode(String userIdentifier, String rawCode);

    /**
     * Generates a cryptographically secure random 6-digit numeric string.
     *
     * @return 6-digit string
     */
    String generateCode();

    /**
     * Hashes a raw verification code.
     *
     * @param rawCode plain 6-digit code
     * @return hashed string
     */
    String hashCode(String rawCode);
}
