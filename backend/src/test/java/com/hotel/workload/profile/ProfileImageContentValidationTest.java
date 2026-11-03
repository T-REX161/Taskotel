package com.hotel.workload.profile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The upload rules from the server's side: real decodable images are accepted whatever their shape, and files that only look like
 * images (renamed, truncated, garbage behind a signature, damaged containers) are refused before anything is stored.
 */
public class ProfileImageContentValidationTest {

    private static ResponseStatusException rejected(String name, String type, byte[] content) {
        return assertThrows(ResponseStatusException.class, () -> ProfileImageValidator.validate(TestImages.file(name, type, content)));
    }

    @Test
    @DisplayName("Real JPG, JPEG, PNG and WebP images are accepted")
    void validImagesAreAccepted() {
        assertEquals(ProfileImageValidator.ImageType.JPEG, ProfileImageValidator.validate(TestImages.file("me.jpg", "image/jpeg", TestImages.jpeg())).type());
        assertEquals(ProfileImageValidator.ImageType.JPEG, ProfileImageValidator.validate(TestImages.file("me.jpeg", "image/jpeg", TestImages.jpeg())).type());
        assertEquals(ProfileImageValidator.ImageType.PNG, ProfileImageValidator.validate(TestImages.file("me.png", "image/png", TestImages.png())).type());
        assertEquals(ProfileImageValidator.ImageType.WEBP, ProfileImageValidator.validate(TestImages.file("me.webp", "image/webp", TestImages.webp())).type());
    }

    @Test
    @DisplayName("Images do not have to be square: wide and tall pictures are accepted (the user crops them to a square)")
    void nonSquareImagesAreAccepted() {
        assertEquals(ProfileImageValidator.ImageType.JPEG, ProfileImageValidator.validate(TestImages.file("wide.jpg", "image/jpeg", TestImages.jpeg(900, 120))).type());
        assertEquals(ProfileImageValidator.ImageType.PNG, ProfileImageValidator.validate(TestImages.file("tall.png", "image/png", TestImages.png(60, 700))).type());
        assertEquals(ProfileImageValidator.ImageType.PNG, ProfileImageValidator.validate(TestImages.file("tiny.png", "image/png", TestImages.png(1, 1))).type());
    }

    @ParameterizedTest
    @CsvSource({"me.gif,image/gif", "me.svg,image/svg+xml", "me.bmp,image/bmp", "me.pdf,application/pdf", "me.tiff,image/tiff", "me.ico,image/x-icon", "me.txt,text/plain"})
    @DisplayName("GIF, SVG, BMP, PDF and other formats are refused by extension with a 415")
    void unsupportedFormats(String name, String type) {
        ResponseStatusException e = rejected(name, type, TestImages.png());
        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, e.getStatusCode());
        assertEquals(ProfileImageValidator.UNSUPPORTED_MESSAGE, e.getReason());
    }

    @ParameterizedTest
    @CsvSource({"me.png,image/png", "me.jpg,image/jpeg", "me.webp,image/webp", "me.jpeg,application/octet-stream"})
    @DisplayName("A GIF renamed to an allowed extension is refused by its content, whatever type the browser declared")
    void renamedGifIsRefused(String name, String type) {
        byte[] gif = "GIF89a\u0001\u0000\u0001\u0000\u0080\u0000\u0000\u0000\u0000\u0000".getBytes(StandardCharsets.ISO_8859_1);
        ResponseStatusException e = rejected(name, type, gif);
        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, e.getStatusCode());
        assertEquals(ProfileImageValidator.NOT_AN_IMAGE_MESSAGE, e.getReason());
    }

    @Test
    @DisplayName("A renamed SVG, PDF or script is refused by its content")
    void renamedNonImagesAreRefused() {
        byte[] svg = "<svg xmlns='http://www.w3.org/2000/svg'><script>alert(1)</script></svg>".getBytes(StandardCharsets.UTF_8);
        byte[] pdf = "%PDF-1.7\n1 0 obj\n<<>>\nendobj\n".getBytes(StandardCharsets.UTF_8);
        for (byte[] content : new byte[][] {svg, pdf}) {
            for (String name : new String[] {"a.png", "a.jpg", "a.webp"}) {
                assertEquals(ProfileImageValidator.NOT_AN_IMAGE_MESSAGE, rejected(name, "image/png", content).getReason());
            }
        }
    }

    @Test
    @DisplayName("A real PNG renamed to .jpg (or the reverse) is refused: the content must match the extension")
    void contentMustMatchExtension() {
        assertEquals(ProfileImageValidator.MISMATCH_MESSAGE, rejected("me.jpg", "image/jpeg", TestImages.png()).getReason());
        assertEquals(ProfileImageValidator.MISMATCH_MESSAGE, rejected("me.png", "image/png", TestImages.jpeg()).getReason());
    }

    @Test
    @DisplayName("A truncated PNG is refused as damaged (400)")
    void truncatedPng() {
        ResponseStatusException e = rejected("me.png", "image/png", TestImages.truncated(TestImages.png(200, 200)));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        assertEquals(ProfileImageValidator.CORRUPT_MESSAGE, e.getReason());
    }

    @Test
    @DisplayName("A valid signature followed by garbage is refused as damaged, for JPEG and PNG")
    void signatureWithGarbageIsCorrupt() {
        byte[] jpegSignature = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10, 'J', 'F', 'I', 'F', 0x00};
        byte[] pngSignature = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        ResponseStatusException jpeg = rejected("me.jpg", "image/jpeg", TestImages.signatureWithGarbage(jpegSignature));
        assertEquals(HttpStatus.BAD_REQUEST, jpeg.getStatusCode());
        assertEquals(ProfileImageValidator.CORRUPT_MESSAGE, jpeg.getReason());
        ResponseStatusException png = rejected("me.png", "image/png", TestImages.signatureWithGarbage(pngSignature));
        assertEquals(HttpStatus.BAD_REQUEST, png.getStatusCode());
        assertEquals(ProfileImageValidator.CORRUPT_MESSAGE, png.getReason());
    }

    @Test
    @DisplayName("A PNG with damaged pixel data is refused as damaged")
    void damagedPngBody() {
        byte[] png = TestImages.png(200, 200);
        for (int i = png.length / 2; i < png.length - 16; i++) png[i] = (byte) 0xA5; // overwrite the compressed pixel data
        assertEquals(ProfileImageValidator.CORRUPT_MESSAGE, rejected("me.png", "image/png", png).getReason());
    }

    @Test
    @DisplayName("WebP containers with a wrong chunk type or sizes that do not fit the file are refused as damaged")
    void damagedWebp() {
        byte[] data = new byte[16];
        assertEquals(ProfileImageValidator.CORRUPT_MESSAGE, rejected("me.webp", "image/webp", TestImages.webpWith("JUNK", data.length, data)).getReason());
        assertEquals(ProfileImageValidator.CORRUPT_MESSAGE, rejected("me.webp", "image/webp", TestImages.webpWith("VP8L", 5_000, data)).getReason());
        byte[] tooLongRiff = TestImages.webp();
        tooLongRiff[4] = (byte) 0xFF; // the RIFF header claims far more bytes than the file has
        assertEquals(ProfileImageValidator.CORRUPT_MESSAGE, rejected("me.webp", "image/webp", tooLongRiff).getReason());
        byte[] justHeader = Arrays.copyOf(TestImages.webp(), 16);
        assertEquals(ProfileImageValidator.CORRUPT_MESSAGE, rejected("me.webp", "image/webp", justHeader).getReason());
    }

    @Test
    @DisplayName("An image claiming an absurd pixel count is refused before it is decoded")
    void excessivePixelCount() {
        byte[] png = TestImages.png(8, 8);
        // IHDR holds width and height as big-endian ints right after the 8-byte signature, the length and the "IHDR" tag
        png[16] = 0x00; png[17] = 0x01; png[18] = (byte) 0x86; png[19] = (byte) 0xA0; // 100,000 wide
        png[20] = 0x00; png[21] = 0x01; png[22] = (byte) 0x86; png[23] = (byte) 0xA0; // 100,000 tall
        ResponseStatusException e = rejected("huge.png", "image/png", png);
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
    }
}
