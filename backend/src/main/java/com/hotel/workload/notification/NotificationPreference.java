package com.hotel.workload.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One user's choice for one notification category: whether to receive it in the app and by email.
 *
 * <p>A row exists only once the user has saved a choice for that category; until then the category's default applies
 * (see {@link NotificationCategory}), so new and existing users need no setup. The user is a plain identifier; the database
 * removes a user's rows together with the user (foreign key with cascade in migration V12).
 */
@Entity
@Table(name = "notification_preferences",
        uniqueConstraints = @UniqueConstraint(name = "uk_notification_preferences_user_category", columnNames = {"user_id", "category"}))
public class NotificationPreference {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "category", nullable = false, length = 30)
    private String category;

    @Column(name = "in_app", nullable = false)
    private boolean inApp;

    @Column(name = "email", nullable = false)
    private boolean email;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected NotificationPreference() {}

    public NotificationPreference(UUID userId, NotificationCategory category, boolean inApp, boolean email) {
        this.userId = userId;
        this.category = category.name();
        this.inApp = inApp;
        this.email = email;
        this.updatedAt = LocalDateTime.now();
    }

    /** Changes the email choice only. The in-app column is kept as stored: there is no in-app delivery, so it is neither shown nor used. */
    public void setEmail(boolean email) {
        this.email = email;
        this.updatedAt = LocalDateTime.now();
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getCategory() { return category; }
    public boolean isInApp() { return inApp; }
    public boolean isEmail() { return email; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
