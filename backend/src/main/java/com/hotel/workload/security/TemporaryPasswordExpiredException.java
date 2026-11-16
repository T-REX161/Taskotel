package com.hotel.workload.security;

/**
 * Sign-in with the right TEMPORARY password after its lifetime has passed. It is still a 401 like any refused sign-in, but unlike the
 * others it carries a message and a code in its body so the login page can tell the user what to do (use "Forgot password"). It is thrown
 * only after the password itself has matched, so it tells nothing to someone who does not know the password.
 */
public class TemporaryPasswordExpiredException extends InvalidCredentialsException {

    public static final String CODE = "TEMPORARY_PASSWORD_EXPIRED";

    public TemporaryPasswordExpiredException(String message) {
        super(message);
    }
}
