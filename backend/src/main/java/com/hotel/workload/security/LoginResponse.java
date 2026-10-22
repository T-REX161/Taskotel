package com.hotel.workload.security;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class LoginResponse {
    private String token;
    private String username;
    private Role role;
    private String email;
    private boolean emailVerificationRequired;
    private Boolean emailVerified;
    private String message;

    public LoginResponse() {}

    public LoginResponse(String token, String username, Role role) {
        this.token = token;
        this.username = username;
        this.role = role;
        this.emailVerificationRequired = false;
        this.emailVerified = true;
    }

    public LoginResponse(String token, String username, Role role, String email, boolean emailVerificationRequired, String message) {
        this.token = token;
        this.username = username;
        this.role = role;
        this.email = email;
        this.emailVerificationRequired = emailVerificationRequired;
        this.emailVerified = !emailVerificationRequired;
        this.message = message;
    }

    public static LoginResponse verificationRequired(String username, String email) {
        LoginResponse response = new LoginResponse();
        response.setUsername(username);
        response.setEmail(email);
        response.setRole(Role.STAFF);
        response.setEmailVerificationRequired(true);
        response.setEmailVerified(false);
        response.setMessage("Email verification required. A verification code has been sent to your email.");
        return response;
    }

    public static LoginResponse success(String token, String username, Role role) {
        return new LoginResponse(token, username, role);
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public boolean isEmailVerificationRequired() {
        return emailVerificationRequired;
    }

    public void setEmailVerificationRequired(boolean emailVerificationRequired) {
        this.emailVerificationRequired = emailVerificationRequired;
    }

    public Boolean getEmailVerified() {
        return emailVerified;
    }

    public void setEmailVerified(Boolean emailVerified) {
        this.emailVerified = emailVerified;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}

