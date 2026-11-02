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

    // The profile picture in Cloudinary: its HTTPS delivery URL and the public id needed to delete it. Both null without a picture.
    @Column(name = "profile_image_url", length = 512)
    private String profileImageUrl;

    @Column(name = "profile_image_public_id")
    private String profileImagePublicId;

    // What the user chose to be called in the app. Null until they set one (the default, derived from the username, is applied when reading).
    @Column(name = "display_name", length = 50)
    private String displayName;

    // Account activity shown to the user on their own profile. Null means "not recorded" (accounts that existed before tracking began
    // and have not signed in / changed their password since); nothing is guessed or backfilled.
    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    @Column(name = "password_changed_at")
    private LocalDateTime passwordChangedAt;

    // Personal display preferences (see profile.UserPreferences): null until chosen, the defaults then apply when reading
    @Column(name = "theme_preference", length = 10)
    private String themePreference;

    @Column(name = "date_format", length = 10)
    private String dateFormat;

    @Column(name = "time_format", length = 10)
    private String timeFormat;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
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
    /** The stored state, for every role: nothing is assumed from the role. */
    public boolean isEmailVerified() {
        return emailVerified;
    }
    public Boolean getEmailVerified() { return isEmailVerified(); }
    public void setEmailVerified(boolean emailVerified) { this.emailVerified = emailVerified; }
    public boolean isCredentialsSetupRequired() {
        // The stored state for every role: nothing is assumed from the role
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
    public String getProfileImageUrl() { return profileImageUrl; }
    public void setProfileImageUrl(String profileImageUrl) { this.profileImageUrl = profileImageUrl; }
    public String getProfileImagePublicId() { return profileImagePublicId; }
    public void setProfileImagePublicId(String profileImagePublicId) { this.profileImagePublicId = profileImagePublicId; }
    public LocalDateTime getLastLoginAt() { return lastLoginAt; }
    public void setLastLoginAt(LocalDateTime lastLoginAt) { this.lastLoginAt = lastLoginAt; }
    public LocalDateTime getPasswordChangedAt() { return passwordChangedAt; }
    public void setPasswordChangedAt(LocalDateTime passwordChangedAt) { this.passwordChangedAt = passwordChangedAt; }
    public String getThemePreference() { return themePreference; }
    public void setThemePreference(String themePreference) { this.themePreference = themePreference; }
    public String getDateFormat() { return dateFormat; }
    public void setDateFormat(String dateFormat) { this.dateFormat = dateFormat; }
    public String getTimeFormat() { return timeFormat; }
    public void setTimeFormat(String timeFormat) { this.timeFormat = timeFormat; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
}
