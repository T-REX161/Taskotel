package com.hotel.workload.security;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByUsername(String username);
    Optional<User> findByEmail(String email);
    // A list: the unique constraint on email is case-sensitive, so two accounts could differ only in case
    List<User> findAllByEmailIgnoreCase(String email);
    Optional<User> findBySetupToken(String setupToken);
    boolean existsByUsername(String username);
    boolean existsByEmail(String email);

    /** Changes only the three preference columns of one account, so nothing else on the row can be overwritten. */
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("update User u set u.themePreference = :theme, u.dateFormat = :date, u.timeFormat = :time where u.id = :id")
    int updatePreferences(@org.springframework.data.repository.query.Param("id") UUID id,
                          @org.springframework.data.repository.query.Param("theme") String theme,
                          @org.springframework.data.repository.query.Param("date") String date,
                          @org.springframework.data.repository.query.Param("time") String time);

    /** Records a successful sign-in. Touches only that column, so it can never overwrite another change to the row (and leaves updated_at alone). */
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("update User u set u.lastLoginAt = :at where u.id = :id")
    int updateLastLogin(@org.springframework.data.repository.query.Param("id") UUID id,
                        @org.springframework.data.repository.query.Param("at") java.time.LocalDateTime at);

    long countByRoleAndActiveTrue(Role role);

    /** Changes only the active flag of one account (no staff record needed). */
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("update User u set u.active = :active where u.id = :id")
    int updateActive(@org.springframework.data.repository.query.Param("id") UUID id,
                     @org.springframework.data.repository.query.Param("active") boolean active);

    /** Stores a new password hash and when it changed. Touches only those two columns, so nothing else on the row can be overwritten. */
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("update User u set u.password = :hash, u.passwordChangedAt = :at where u.id = :id")
    int updatePassword(@org.springframework.data.repository.query.Param("id") UUID id,
                       @org.springframework.data.repository.query.Param("hash") String hash,
                       @org.springframework.data.repository.query.Param("at") java.time.LocalDateTime at);

    /** Changes only the display name column, so a concurrent picture or password change on the same row is never overwritten. */
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("update User u set u.displayName = :displayName where u.id = :id")
    int updateDisplayName(@org.springframework.data.repository.query.Param("id") UUID id,
                          @org.springframework.data.repository.query.Param("displayName") String displayName);
}
