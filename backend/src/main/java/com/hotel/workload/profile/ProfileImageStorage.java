package com.hotel.workload.profile;

/**
 * Where profile pictures are kept. The application uses Cloudinary ({@link CloudinaryImageStorage}); tests replace it with a mock.
 */
public interface ProfileImageStorage {

    /** False when the credentials are missing, in which case uploads and deletes are refused. */
    boolean isConfigured();

    /**
     * Stores an already validated image under a new public id and returns where it is delivered from. Nothing that is
     * already stored is touched, so a failed upload leaves the current picture in place.
     *
     * @throws ProfileImageStorageException when the image service refuses the image or cannot be reached
     */
    StoredImage upload(byte[] image, String publicIdHint);

    /**
     * Removes a stored image. An image that is already gone counts as removed.
     *
     * @throws ProfileImageStorageException when the image service cannot be reached or does not confirm the removal
     */
    void delete(String publicId);

    /** The HTTPS delivery URL and the public id that identifies the image for a later delete. */
    record StoredImage(String url, String publicId) {}
}
