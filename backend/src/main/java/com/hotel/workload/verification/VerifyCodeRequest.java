package com.hotel.workload.verification;

/**
 * The body of a verification request. It deliberately carries NO bean-validation annotations: a request rejected by validation would
 * be answered before the controller method runs, so it would never be charged to the rate limiter and a client could send unlimited
 * malformed codes. The strict six-digit rule is enforced in {@link VerificationCodeServiceImpl#verifyCode} instead, after the limiter has
 * counted the request; a missing, blank, non-numeric, too-short, too-long or non-ASCII-digit code never verifies and gets the same generic
 * failure as a wrong one.
 */
public class VerifyCodeRequest {

    private String identifier;
    private String username;
    private String email;

    private String code;

    public VerifyCodeRequest() {}

    public VerifyCodeRequest(String identifier, String code) {
        this.identifier = identifier;
        this.code = code;
    }

    public String getIdentifier() {
        return identifier;
    }

    public void setIdentifier(String identifier) {
        this.identifier = identifier;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getEffectiveIdentifier() {
        if (identifier != null && !identifier.isBlank()) {
            return identifier.trim();
        }
        if (username != null && !username.isBlank()) {
            return username.trim();
        }
        if (email != null && !email.isBlank()) {
            return email.trim();
        }
        return null;
    }
}
