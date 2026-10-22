package com.hotel.workload.verification;

public interface VerificationCodeService {

    /**
     * Generates a cryptographically secure 6-digit verification code, hashes and saves it to the user,
     * and dispatches the code to the user's email.
     *
     * @param userIdentifier username or email
     */
    void sendVerificationCode(String userIdentifier);

    /**
     * Validates an entered verification code against the stored hash for the user.
     *
     * @param userIdentifier username or email
     * @param rawCode the 6-digit verification code entered by the user
     * @return setup token if setup is required, or null if fully verified
     * @throws VerificationException if verification fails (invalid, expired, max attempts exceeded, or already used)
     * @throws UserNotFoundException if user does not exist
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
