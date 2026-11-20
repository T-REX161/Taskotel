package com.hotel.workload.security;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByUsername(String username);
    Optional<User> findByEmail(String email);

    /**
     * The account for a user name, locked for the rest of the transaction (SELECT ... FOR UPDATE). Email verification reads its state
     * through these two finders and nothing else: because the row is loaded only after the lock is held, simultaneous sends and
     * verifications are processed one at a time and each sees the committed result of the one before (no lost attempt counts, no
     * double use of a code, no two sends inside one cooldown). Must be called inside a transaction.
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT u FROM User u WHERE u.username = :username")
    Optional<User> findByUsernameForUpdate(@org.springframework.data.repository.query.Param("username") String username);

    /** As {@link #findByUsernameForUpdate}, by email address (exact match, like {@link #findByEmail}). */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT u FROM User u WHERE u.email = :email")
    Optional<User> findByEmailForUpdate(@org.springframework.data.repository.query.Param("email") String email);

    /** All accounts whose email equals this one ignoring case, locked like {@link #findByEmailForUpdate}. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT u FROM User u WHERE lower(u.email) = lower(:email) ORDER BY u.id")
    java.util.List<User> findAllByEmailIgnoreCaseForUpdate(@org.springframework.data.repository.query.Param("email") String email);

    /**
     * The account for an email typed in any letter case (sign-in already ignores case; the stored address keeps the case it was created
     * with). An exact match wins; otherwise a case-insensitive match is used only when it is unambiguous (exactly one account).
     */
    default Optional<User> findByEmailIgnoreCaseForUpdate(String email) {
        Optional<User> exact = findByEmailForUpdate(email);
        if (exact.isPresent()) return exact;
        java.util.List<User> matches = findAllByEmailIgnoreCaseForUpdate(email);
        return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
    }
    /**
     * Every active administrator account, locked for the rest of the transaction (SELECT ... FOR UPDATE, in id order). Used by
     * AdminAccountGuard so concurrent deactivations of administrators are decided one at a time. Must be called inside a transaction.
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT u FROM User u WHERE u.role = com.hotel.workload.security.Role.ADMIN AND u.active = true ORDER BY u.id")
    List<User> findActiveAdminsForUpdate();

    /** One account, locked for the rest of the transaction (SELECT ... FOR UPDATE), read fresh. Must be called inside a transaction. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT u FROM User u WHERE u.id = :id")
    Optional<User> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") java.util.UUID id);

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
