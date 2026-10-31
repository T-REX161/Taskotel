package com.hotel.workload.profile;

import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Checks an uploaded profile picture before anything is sent to Cloudinary: it must be present, at most 5 MB, have a JPG, JPEG,
 * PNG or WebP extension, declare a matching content type (when it declares one), and its bytes must really be that kind of image.
 */
public final class ProfileImageValidator {

    public static final long MAX_BYTES = 5L * 1024 * 1024;

    public static final String MISSING_MESSAGE = "Choose an image to upload.";
    public static final String TOO_LARGE_MESSAGE = "The image is too large. Images must be 5 MB or smaller.";
    public static final String UNSUPPORTED_MESSAGE = "Only JPG, JPEG, PNG or WebP images can be uploaded.";
    public static final String NOT_AN_IMAGE_MESSAGE = "The file is not a valid JPG, PNG or WebP image.";
    public static final String MISMATCH_MESSAGE = "The file's content does not match its type. Please choose a real JPG, PNG or WebP image.";

    public enum ImageType {
        JPEG("image/jpeg", "jpg", "jpeg"),
        PNG("image/png", "png"),
        WEBP("image/webp", "webp");

        private final String mimeType;
        private final List<String> extensions;

        ImageType(String mimeType, String... extensions) {
            this.mimeType = mimeType;
            this.extensions = List.of(extensions);
        }

        public String mimeType() {
            return mimeType;
        }

        static ImageType forExtension(String extension) {
            for (ImageType type : values()) {
                if (type.extensions.contains(extension)) return type;
            }
            return null;
        }

        static ImageType forMimeType(String mimeType) {
            for (ImageType type : values()) {
                if (type.mimeType.equals(mimeType)) return type;
            }
            return null;
        }
    }

    public record ValidatedImage(byte[] content, ImageType type) {}

    private ProfileImageValidator() {}

    public static ValidatedImage validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw reject(HttpStatus.BAD_REQUEST, MISSING_MESSAGE);
        }
        if (file.getSize() > MAX_BYTES) {
            throw reject(HttpStatus.PAYLOAD_TOO_LARGE, TOO_LARGE_MESSAGE);
        }

        ImageType byExtension = ImageType.forExtension(extensionOf(file.getOriginalFilename()));
        if (byExtension == null) {
            throw reject(HttpStatus.UNSUPPORTED_MEDIA_TYPE, UNSUPPORTED_MESSAGE);
        }

        // A browser always declares a type; "application/octet-stream" or none means "unknown", and then the bytes decide
        String declared = declaredType(file.getContentType());
        ImageType byDeclaredType = declared == null ? null : ImageType.forMimeType(declared);
        if (declared != null && byDeclaredType == null) {
            throw reject(HttpStatus.UNSUPPORTED_MEDIA_TYPE, UNSUPPORTED_MESSAGE);
        }

        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw reject(HttpStatus.BAD_REQUEST, NOT_AN_IMAGE_MESSAGE);
        }
        if (content.length == 0) {
            throw reject(HttpStatus.BAD_REQUEST, MISSING_MESSAGE);
        }
        if (content.length > MAX_BYTES) {
            throw reject(HttpStatus.PAYLOAD_TOO_LARGE, TOO_LARGE_MESSAGE);
        }

        ImageType actual = detect(content);
        if (actual == null) {
            throw reject(HttpStatus.UNSUPPORTED_MEDIA_TYPE, NOT_AN_IMAGE_MESSAGE);
        }
        if (actual != byExtension || (byDeclaredType != null && actual != byDeclaredType)) {
            throw reject(HttpStatus.UNSUPPORTED_MEDIA_TYPE, MISMATCH_MESSAGE);
        }
        return new ValidatedImage(content, actual);
    }

    /** The image kind from the file's first bytes (its signature), or null when it is none of the allowed kinds. */
    static ImageType detect(byte[] content) {
        if (startsWith(content, 0, 0xFF, 0xD8, 0xFF)) {
            return ImageType.JPEG;
        }
        if (startsWith(content, 0, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
            return ImageType.PNG;
        }
        if (startsWith(content, 0, 'R', 'I', 'F', 'F') && startsWith(content, 8, 'W', 'E', 'B', 'P')) {
            return ImageType.WEBP;
        }
        return null;
    }

    private static boolean startsWith(byte[] content, int offset, int... signature) {
        if (content.length < offset + signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if ((content[offset + i] & 0xFF) != signature[i]) return false;
        }
        return true;
    }

    static String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        String name = filename.trim();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** The declared media type without parameters, with the common JPEG aliases folded in; null when it says nothing useful. */
    static String declaredType(String contentType) {
        if (contentType == null) {
            return null;
        }
        String type = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (type.isEmpty() || type.equals("application/octet-stream")) {
            return null;
        }
        return Arrays.asList("image/jpg", "image/pjpeg").contains(type) ? "image/jpeg" : type;
    }

    private static ResponseStatusException reject(HttpStatus status, String message) {
        return new ResponseStatusException(status, message);
    }
}
