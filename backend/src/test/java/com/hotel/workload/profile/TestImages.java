package com.hotel.workload.profile;

import org.springframework.mock.web.MockMultipartFile;

import java.util.Arrays;

/** Small files whose first bytes carry real JPEG, PNG and WebP signatures, for upload tests. Nothing is decoded. */
final class TestImages {

    private TestImages() {}

    static byte[] jpeg() {
        return withPayload(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10, 'J', 'F', 'I', 'F', 0x00});
    }

    static byte[] png() {
        return withPayload(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D, 'I', 'H', 'D', 'R'});
    }

    static byte[] webp() {
        return withPayload(new byte[] {'R', 'I', 'F', 'F', 0x24, 0x00, 0x00, 0x00, 'W', 'E', 'B', 'P', 'V', 'P', '8', ' '});
    }

    static MockMultipartFile file(String filename, String contentType, byte[] content) {
        return new MockMultipartFile("file", filename, contentType, content);
    }

    static MockMultipartFile jpegFile() {
        return file("me.jpg", "image/jpeg", jpeg());
    }

    private static byte[] withPayload(byte[] header) {
        byte[] content = Arrays.copyOf(header, header.length + 64);
        for (int i = header.length; i < content.length; i++) content[i] = (byte) i;
        return content;
    }
}
