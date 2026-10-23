package com.hotel.workload.staff;

import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * A hotel department. Replaces the fixed enum of the same name.
 *
 * <p>{@code code} is the stable business key. Staff and tasks keep storing it in their existing
 * {@code department} column (for example {@code HOUSEKEEPING}), so JSON contracts and all existing rows
 * are unchanged. The code is generated once when a department is created and never changes, even if the
 * department is renamed.
 */
@Entity
@Table(name = "departments", uniqueConstraints = @UniqueConstraint(name = "uk_departments_code", columnNames = "code"))
public class Department {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "code", nullable = false, length = 64, updatable = false)
    private String code;

    // Uniqueness (case-insensitive) is enforced by DepartmentServiceImpl and, in PostgreSQL, by the
    // uk_departments_name_lower index created by Flyway V4.
    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "icon_key", nullable = false, length = 50)
    private String iconKey = DepartmentCatalog.DEFAULT_ICON_KEY;

    @Column(name = "color_key", nullable = false, length = 50)
    private String colorKey = DepartmentCatalog.DEFAULT_COLOR_KEY;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "head_staff_id", foreignKey = @ForeignKey(name = "fk_departments_head_staff"))
    private Staff headStaff;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public Department() {}

    public Department(String code, String name) {
        this.code = code;
        this.name = name;
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    /** Human-readable label for user-facing messages (same value as {@link #getName()}). */
    public String getDisplayName() { return name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getIconKey() { return iconKey; }
    public void setIconKey(String iconKey) { this.iconKey = iconKey; }

    public String getColorKey() { return colorKey; }
    public void setColorKey(String colorKey) { this.colorKey = colorKey; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public Staff getHeadStaff() { return headStaff; }
    public void setHeadStaff(Staff headStaff) { this.headStaff = headStaff; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }

    /** Departments are the same when their codes match, whether loaded eagerly, lazily or built by hand. */
    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof Department department)) return false;
        return code != null && Objects.equals(code, department.getCode());
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(code);
    }

    @Override
    public String toString() {
        return "Department[" + code + "]";
    }
}
