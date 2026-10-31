package com.hotel.workload.profile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ProfileImageValidatorTest {

    private static ResponseStatusException rejected(MockMultipartFile file) {
        return assertThrows(ResponseStatusException.class, () -> ProfileImageValidator.validate(file));
    }

    @Test
    @DisplayName("JPG, JPEG, PNG and WebP files with matching content and type are accepted, bytes unchanged")
    void acceptsTheFourAllowedKinds() {
        assertEquals(ProfileImageValidator.ImageType.JPEG, ProfileImageValidator.validate(TestImages.file("a.jpg", "image/jpeg", TestImages.jpeg())).type());
        assertEquals(ProfileImageValidator.ImageType.JPEG, ProfileImageValidator.validate(TestImages.file("a.JPEG", "image/jpeg", TestImages.jpeg())).type());
        assertEquals(ProfileImageValidator.ImageType.PNG, ProfileImageValidator.validate(TestImages.file("a.png", "image/png", TestImages.png())).type());
        assertEquals(ProfileImageValidator.ImageType.WEBP, ProfileImageValidator.validate(TestImages.file("a.webp", "image/webp", TestImages.webp())).type());
        assertArrayEquals(TestImages.png(), ProfileImageValidator.validate(TestImages.file("a.png", "image/png", TestImages.png())).content());
    }

    @Test
    @DisplayName("An unknown declared type (none, or application/octet-stream) is decided by the content; image/jpg is treated as JPEG")
    void unknownDeclaredTypeFallsBackToContent() {
        assertEquals(ProfileImageValidator.ImageType.PNG, ProfileImageValidator.validate(TestImages.file("a.png", null, TestImages.png())).type());
        assertEquals(ProfileImageValidator.ImageType.WEBP, ProfileImageValidator.validate(TestImages.file("a.webp", "application/octet-stream", TestImages.webp())).type());
        assertEquals(ProfileImageValidator.ImageType.JPEG, ProfileImageValidator.validate(TestImages.file("a.jpg", "image/jpg", TestImages.jpeg())).type());
    }

    @Test
    @DisplayName("A missing or empty file is a 400 asking for an image")
    void missingOrEmpty() {
        ResponseStatusException none = assertThrows(ResponseStatusException.class, () -> ProfileImageValidator.validate(null));
        assertEquals(HttpStatus.BAD_REQUEST, none.getStatusCode());
        assertEquals(ProfileImageValidator.MISSING_MESSAGE, none.getReason());
        assertEquals(HttpStatus.BAD_REQUEST, rejected(TestImages.file("a.png", "image/png", new byte[0])).getStatusCode());
    }

    @Test
    @DisplayName("Anything over 5 MB is a 413; exactly 5 MB is still accepted")
    void sizeLimit() {
        byte[] tooBig = Arrays.copyOf(TestImages.png(), (int) ProfileImageValidator.MAX_BYTES + 1);
        ResponseStatusException e = rejected(TestImages.file("a.png", "image/png", tooBig));
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, e.getStatusCode());
        assertEquals(ProfileImageValidator.TOO_LARGE_MESSAGE, e.getReason());

        byte[] limit = Arrays.copyOf(TestImages.png(), (int) ProfileImageValidator.MAX_BYTES);
        assertEquals(ProfileImageValidator.ImageType.PNG, ProfileImageValidator.validate(TestImages.file("a.png", "image/png", limit)).type());
    }

    @ParameterizedTest
    @CsvSource({"a.gif,image/gif", "a.svg,image/svg+xml", "a.bmp,image/bmp", "a,image/png", "a.png.exe,application/x-msdownload", "a.pdf,application/pdf"})
    @DisplayName("Other extensions are refused with a 415, even with image bytes")
    void unsupportedExtension(String name, String type) {
        ResponseStatusException e = rejected(TestImages.file(name, type, TestImages.png()));
        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, e.getStatusCode());
        assertEquals(ProfileImageValidator.UNSUPPORTED_MESSAGE, e.getReason());
    }

    @Test
    @DisplayName("An allowed extension with a declared type that is not an allowed image is refused")
    void unsupportedDeclaredType() {
        ResponseStatusException e = rejected(TestImages.file("a.png", "text/html", TestImages.png()));
        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, e.getStatusCode());
    }

    @Test
    @DisplayName("A renamed non-image (text, script, SVG) is refused by its content")
    void contentThatIsNotAnImage() {
        byte[] text = "<svg xmlns='http://www.w3.org/2000/svg'><script>alert(1)</script></svg>".getBytes(StandardCharsets.UTF_8);
        ResponseStatusException e = rejected(TestImages.file("evil.png", "image/png", text));
        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, e.getStatusCode());
        assertEquals(ProfileImageValidator.NOT_AN_IMAGE_MESSAGE, e.getReason());
        assertEquals(ProfileImageValidator.NOT_AN_IMAGE_MESSAGE, rejected(TestImages.file("a.jpg", "image/jpeg", new byte[] {(byte) 0xFF, (byte) 0xD8})).getReason());
    }

    @Test
    @DisplayName("Content that does not match the extension or the declared type is refused")
    void mismatches() {
        assertEquals(ProfileImageValidator.MISMATCH_MESSAGE, rejected(TestImages.file("a.png", "image/png", TestImages.jpeg())).getReason());
        assertEquals(ProfileImageValidator.MISMATCH_MESSAGE, rejected(TestImages.file("a.jpg", "image/png", TestImages.jpeg())).getReason());
        assertEquals(ProfileImageValidator.MISMATCH_MESSAGE, rejected(TestImages.file("a.webp", "image/webp", TestImages.png())).getReason());
    }
}
