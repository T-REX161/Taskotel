package com.hotel.workload.security;

import java.time.LocalDateTime;
import java.util.UUID;

public class UserDto {

    private UUID id;
    private String username;
    private String email;
    private Role role;
    private boolean active;
    private boolean emailVerified;
    private String fullName;
    private LocalDateTime createdAt;

    public UserDto() {}

    public UserDto(UUID id, String username, String email, Role role, boolean active,
                   boolean emailVerified, String fullName, LocalDateTime createdAt) {
        this.id = id;
        this.username = username;
        this.email = email;
        this.role = role;
        this.active = active;
        this.emailVerified = emailVerified;
        this.fullName = fullName;
        this.createdAt = createdAt;
    }

    public static UserDto fromEntity(User user) {
        return fromEntity(user, null);
    }

    public static UserDto fromEntity(User user, String staffName) {
        if (user == null) {
            return null;
        }

        String resolvedFullName;
        if (staffName != null && !staffName.trim().isEmpty()) {
            resolvedFullName = staffName.trim();
        } else {
            resolvedFullName = deriveFallbackName(user);
        }

        return new UserDto(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getRole(),
                user.isActive(),
                user.isEmailVerified(),
                resolvedFullName,
                user.getCreatedAt()
        );
    }

    public static String deriveFallbackName(User user) {
        if (user == null) {
            return "Unknown User";
        }
        String username = user.getUsername();
        if (username != null && !username.trim().isEmpty()) {
            return formatDisplayName(username.trim());
        }
        String email = user.getEmail();
        if (email != null && !email.trim().isEmpty()) {
            String prefix = email.split("@")[0];
            return formatDisplayName(prefix);
        }
        if (user.getRole() != null) {
            switch (user.getRole()) {
                case ADMIN:
                    return "Administrator";
                case MANAGER:
                    return "Manager";
                case STAFF:
                    return "Staff";
                default:
                    return "User";
            }
        }
        return "Unknown User";
    }

    private static String formatDisplayName(String input) {
        String[] parts = input.split("[._\\-]+");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (!part.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append(" ");
                }
                sb.append(Character.toUpperCase(part.charAt(0)));
                if (part.length() > 1) {
                    sb.append(part.substring(1));
                }
            }
        }
        return sb.length() > 0 ? sb.toString() : input;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
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

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public boolean isEmailVerified() {
        return emailVerified;
    }

    public void setEmailVerified(boolean emailVerified) {
        this.emailVerified = emailVerified;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
