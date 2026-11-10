package com.hotel.workload.profile;

import java.time.LocalDateTime;

/**
 * The signed-in user's own profile. {@code displayName} is the name the user chose (or the default derived from the username);
 * {@code fullName} is the staff record name used elsewhere. {@code profileImageUrl} is null when there is no picture.
 * {@code active} is whether the account is enabled; {@code emailVerified} is whether the account's email address has been verified
 * (a stored account fact, not whether anyone is signed in right now).
 * {@code createdAt}, {@code lastLoginAt} and {@code passwordChangedAt} are the server's own timestamps for this account; the last two
 * are null when not recorded (never guessed). {@code preferences} are this user's own appearance, date and time format choices
 * (defaults filled in). {@code email} is the account's registered address; {@code phone} is the staff record's phone number and is
 * null when there is none (an account without a staff record, or no number entered): it is never invented. No hashes, codes or
 * tokens are ever part of this response. {@code pendingEmailMasked} and {@code pendingEmailExpiresAt} describe an email change that is waiting
 * for this user's verification code (the address masked, never in full); both are null when there is none or its code has expired.
 */
public record ProfileDto(String username, String role, String fullName, String profileImageUrl, String displayName,
                         boolean active, boolean emailVerified,
                         LocalDateTime createdAt, LocalDateTime lastLoginAt, LocalDateTime passwordChangedAt,
                         UserPreferences.Values preferences, String email, String phone,
                         String pendingEmailMasked, LocalDateTime pendingEmailExpiresAt) {}
