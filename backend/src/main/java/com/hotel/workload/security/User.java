package com.hotel.workload.security;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String password;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "email_verified")
    private boolean emailVerified = false;

    @Column(name = "verification_code_hash")
    private String verificationCodeHash;

    @Column(name = "verification_code_expires_at")
    private LocalDateTime verificationCodeExpiresAt;

    @Column(name = "verification_attempt_count")
    private int verificationAttemptCount = 0;

    @Column(name = "verification_created_at")
    private LocalDateTime verificationCreatedAt;

    @Column(name = "credentials_setup_required", columnDefinition = "boolean default false")
    private Boolean credentialsSetupRequired = false;

    @Column(name = "setup_token")
    private String setupToken;

    @Column(name = "password_reset_code_hash")
    private String passwordResetCodeHash;

    @Column(name = "password_reset_expires_at")
    private LocalDateTime passwordResetExpiresAt;

    @Column(name = "password_reset_attempt_count")
    private Integer passwordResetAttemptCount = 0;

    @Column(name = "password_reset_last_sent_at")
    private LocalDateTime passwordResetLastSentAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @Column(name = "first_login", columnDefinition = "boolean default true")
    private boolean firstLogin = true;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (role == Role.ADMIN || role == Role.MANAGER) {
            emailVerified = true;
            credentialsSetupRequired = false;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    @PostLoad
    protected void onPostLoad() {
        if (role == Role.ADMIN || role == Role.MANAGER) {
            emailVerified = true;
            credentialsSetupRequired = false;
        }
    }

    // Getters and Setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public boolean isEmailVerified() {
        if (role == Role.ADMIN || role == Role.MANAGER) {
            return true;
        }
        return emailVerified;
    }
    public Boolean getEmailVerified() { return isEmailVerified(); }
    public void setEmailVerified(boolean emailVerified) { this.emailVerified = emailVerified; }
    public boolean isCredentialsSetupRequired() {
        if (role == Role.ADMIN || role == Role.MANAGER) {
            return false;
        }
        return credentialsSetupRequired != null ? credentialsSetupRequired : false;
    }
    public void setCredentialsSetupRequired(Boolean credentialsSetupRequired) { this.credentialsSetupRequired = credentialsSetupRequired; }
    public String getSetupToken() { return setupToken; }
    public void setSetupToken(String setupToken) { this.setupToken = setupToken; }
    public String getVerificationCodeHash() { return verificationCodeHash; }
    public void setVerificationCodeHash(String verificationCodeHash) { this.verificationCodeHash = verificationCodeHash; }
    public LocalDateTime getVerificationCodeExpiresAt() { return verificationCodeExpiresAt; }
    public void setVerificationCodeExpiresAt(LocalDateTime verificationCodeExpiresAt) { this.verificationCodeExpiresAt = verificationCodeExpiresAt; }
    public int getVerificationAttemptCount() { return verificationAttemptCount; }
    public void setVerificationAttemptCount(int verificationAttemptCount) { this.verificationAttemptCount = verificationAttemptCount; }
    public LocalDateTime getVerificationCreatedAt() { return verificationCreatedAt; }
    public void setVerificationCreatedAt(LocalDateTime verificationCreatedAt) { this.verificationCreatedAt = verificationCreatedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public String getPasswordResetCodeHash() { return passwordResetCodeHash; }
    public void setPasswordResetCodeHash(String passwordResetCodeHash) { this.passwordResetCodeHash = passwordResetCodeHash; }
    public LocalDateTime getPasswordResetExpiresAt() { return passwordResetExpiresAt; }
    public void setPasswordResetExpiresAt(LocalDateTime passwordResetExpiresAt) { this.passwordResetExpiresAt = passwordResetExpiresAt; }
    public Integer getPasswordResetAttemptCount() { return passwordResetAttemptCount != null ? passwordResetAttemptCount : 0; }
    public void setPasswordResetAttemptCount(Integer passwordResetAttemptCount) { this.passwordResetAttemptCount = passwordResetAttemptCount != null ? passwordResetAttemptCount : 0; }
    public LocalDateTime getPasswordResetLastSentAt() { return passwordResetLastSentAt; }
    public void setPasswordResetLastSentAt(LocalDateTime passwordResetLastSentAt) { this.passwordResetLastSentAt = passwordResetLastSentAt; }
    public boolean isFirstLogin() { return firstLogin; }
    public void setFirstLogin(boolean firstLogin) { this.firstLogin = firstLogin; }
}
