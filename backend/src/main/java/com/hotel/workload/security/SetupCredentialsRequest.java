package com.hotel.workload.security;

import jakarta.validation.constraints.NotBlank;

public class SetupCredentialsRequest {
    @NotBlank(message = "Setup token is required")
    private String setupToken;
    
    @NotBlank(message = "New username is required")
    private String newUsername;
    
    @NotBlank(message = "New password is required")
    private String newPassword;
    
    public String getSetupToken() { return setupToken; }
    public void setSetupToken(String setupToken) { this.setupToken = setupToken; }
    
    public String getNewUsername() { return newUsername; }
    public void setNewUsername(String newUsername) { this.newUsername = newUsername; }
    
    public String getNewPassword() { return newPassword; }
    public void setNewPassword(String newPassword) { this.newPassword = newPassword; }
}
