package com.hotel.workload.security;

public class ResetPasswordRequest {
    private String email;
    private String code;
    private String newPassword;

    private String confirmPassword;

    public ResetPasswordRequest() {}

    public ResetPasswordRequest(String email, String code, String newPassword, String confirmPassword) {
        this.email = email;
        this.code = code;
        this.newPassword = newPassword;
        this.confirmPassword = confirmPassword;
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

    public String getNewPassword() {
        return newPassword;
    }

    public void setNewPassword(String newPassword) {
        this.newPassword = newPassword;
    }

    public String getConfirmPassword() {
        return confirmPassword;
    }

    public void setConfirmPassword(String confirmPassword) {
        this.confirmPassword = confirmPassword;
    }
}
