package com.hotel.workload.security;

/**
 * Body of PUT /api/profile/password. It names no user: whose password changes comes only from the signed-in account.
 * toString never shows the values, so a request can never leak a password into a log.
 */
public record ChangePasswordRequest(String currentPassword, String newPassword, String confirmPassword) {
    @Override
    public String toString() {
        return "ChangePasswordRequest[redacted]";
    }
}
