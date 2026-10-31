package com.hotel.workload.profile;

/** A profile picture could not be stored or removed. The message is for the server log only, never for the client. */
public class ProfileImageStorageException extends RuntimeException {

    public enum Reason {
        /** The Cloudinary settings are missing or wrong (for example a cloud name with a space, or credentials Cloudinary refuses). */
        NOT_CONFIGURED,
        /** Cloudinary refused the file itself (for example, image data it cannot read). */
        REJECTED,
        /** Cloudinary could not be reached, failed, or answered unexpectedly. */
        UNAVAILABLE
    }

    private final Reason reason;

    public ProfileImageStorageException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public ProfileImageStorageException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
