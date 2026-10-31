package com.hotel.workload.profile;

import com.cloudinary.Cloudinary;
import com.cloudinary.Transformation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Profile pictures in Cloudinary. Every request is signed on the server with the API key and secret (the SDK adds the
 * timestamp and signature), so the secret never leaves the backend and the browser never talks to Cloudinary for uploads.
 *
 * <p>An image is stored once, already cropped to a 512 x 512 square around its most important content (faces first), so no
 * full-size original is kept. It is delivered with automatic format and quality ({@code f_auto,q_auto}).
 */
public class CloudinaryImageStorage implements ProfileImageStorage {

    private static final Logger log = LoggerFactory.getLogger(CloudinaryImageStorage.class);

    static final int SIZE = 512;
    static final String ALLOWED_FORMATS = "jpg,jpeg,png,webp";

    /** Cloudinary's answers when the cloud name, key or secret is wrong, or the key may not upload or delete (compared in lower case). */
    private static final List<String> SETTINGS_ERRORS = List.of(
            "invalid signature", "invalid api_key", "unknown api key", "invalid cloud_name", "cloud_name is disabled", "must supply api",
            "request forbidden", "missing permissions");

    private final Cloudinary cloudinary;
    private final String folder;

    /** {@code cloudinary} is null when the credentials are missing; every upload or delete is then refused. */
    public CloudinaryImageStorage(Cloudinary cloudinary, String folder) {
        this.cloudinary = cloudinary;
        this.folder = folder;
    }

    @Override
    public boolean isConfigured() {
        return cloudinary != null;
    }

    @Override
    public StoredImage upload(byte[] image, String publicIdHint) {
        requireConfigured();
        Map<String, Object> options = new HashMap<>();
        options.put("folder", folder);
        options.put("public_id", publicIdHint);
        options.put("resource_type", "image");
        options.put("overwrite", false);
        options.put("allowed_formats", ALLOWED_FORMATS);
        options.put("transformation", squareCrop());

        Map<?, ?> result;
        try {
            result = cloudinary.uploader().upload(image, options);
        } catch (Exception e) {
            throw failure("upload", e);
        }

        Object publicId = result == null ? null : result.get("public_id");
        if (!(publicId instanceof String id) || id.isBlank()) {
            log.warn("Cloudinary upload answered without a public id");
            throw new ProfileImageStorageException(ProfileImageStorageException.Reason.UNAVAILABLE, "Cloudinary upload returned no public id");
        }
        return new StoredImage(deliveryUrl(id, result.get("version")), id);
    }

    @Override
    public void delete(String publicId) {
        requireConfigured();
        Map<String, Object> options = new HashMap<>();
        options.put("resource_type", "image");
        options.put("invalidate", true); // cached copies stop being served too

        Map<?, ?> result;
        try {
            result = cloudinary.uploader().destroy(publicId, options);
        } catch (Exception e) {
            throw failure("delete", e);
        }

        Object outcome = result == null ? null : result.get("result");
        if (!"ok".equals(outcome) && !"not found".equals(outcome)) {
            log.warn("Cloudinary delete was not confirmed (result: {})", outcome);
            throw new ProfileImageStorageException(ProfileImageStorageException.Reason.UNAVAILABLE, "Cloudinary delete was not confirmed");
        }
    }

    /** The secure delivery URL: HTTPS, the stored square, best format and quality for each browser. */
    String deliveryUrl(String publicId, Object version) {
        return cloudinary.url()
                .secure(true)
                .resourceType("image")
                .type("upload")
                .version(version)
                .transformation(new Transformation().fetchFormat("auto").quality("auto"))
                .generate(publicId);
    }

    private static Transformation squareCrop() {
        return new Transformation().width(SIZE).height(SIZE).crop("fill").gravity("auto");
    }

    private void requireConfigured() {
        if (cloudinary == null) {
            throw new ProfileImageStorageException(ProfileImageStorageException.Reason.NOT_CONFIGURED, "Cloudinary is not configured");
        }
    }

    private static ProfileImageStorageException failure(String action, Exception e) {
        String detail = safeDetail(e);
        String lower = detail.toLowerCase(Locale.ROOT);
        ProfileImageStorageException.Reason reason;
        if (e instanceof IllegalArgumentException || SETTINGS_ERRORS.stream().anyMatch(lower::contains)) {
            // The request could not be built or Cloudinary refused the credentials: a settings problem, not an outage
            reason = ProfileImageStorageException.Reason.NOT_CONFIGURED;
            log.warn("Cloudinary {} failed because of the Cloudinary settings (check CLOUDINARY_CLOUD_NAME, CLOUDINARY_API_KEY, "
                    + "CLOUDINARY_API_SECRET, and that the API key may upload and delete assets): {}", action, detail);
        } else if (lower.contains("invalid image") || lower.contains("image file format") || lower.contains("not allowed")) {
            reason = ProfileImageStorageException.Reason.REJECTED;
            log.warn("Cloudinary {} refused the image: {}", action, detail);
        } else {
            reason = ProfileImageStorageException.Reason.UNAVAILABLE;
            log.warn("Cloudinary {} failed: {}", action, detail);
        }
        return new ProfileImageStorageException(reason, "Cloudinary " + action + " failed", e);
    }

    /** The error text for the server log: long digit runs (such as an API key) are masked and the text is kept short. */
    static String safeDetail(Exception e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        String masked = message.replaceAll("\\d{10,}", "***");
        return masked.length() > 300 ? masked.substring(0, 300) + "..." : masked;
    }
}
