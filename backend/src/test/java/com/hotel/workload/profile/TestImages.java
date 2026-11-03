package com.hotel.workload.profile;

import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Real, decodable JPEG and PNG images (encoded by ImageIO) and a structurally valid WebP, for upload tests. The upload validator
 * decodes JPEG and PNG and checks WebP's container, so these have to be genuine files, not just signatures.
 */
final class TestImages {

    private TestImages() {}

    /** A real JPEG of the given size. */
    static byte[] jpeg(int width, int height) {
        return encode(width, height, "jpg", BufferedImage.TYPE_INT_RGB);
    }

    /** A real PNG of the given size. */
    static byte[] png(int width, int height) {
        return encode(width, height, "png", BufferedImage.TYPE_INT_ARGB);
    }

    static byte[] jpeg() {
        return jpeg(32, 24);
    }

    static byte[] png() {
        return png(32, 24);
    }

    /**
     * A WebP-shaped file: a correct RIFF container holding one lossless ("VP8L") chunk. Nothing in the JDK can decode WebP, so the
     * server can only check this structure; the chunk's pixel data is a placeholder.
     */
    static byte[] webp() {
        byte[] payload = new byte[14];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (0x20 + i);
        return webpWith("VP8L", payload.length, payload);
    }

    /** A RIFF/WebP container whose first chunk has the given fourcc and declared size; the RIFF size matches the bytes given. */
    static byte[] webpWith(String fourcc, long declaredChunkSize, byte[] chunkData) {
        int padded = chunkData.length + (chunkData.length % 2);
        byte[] out = new byte[12 + 8 + padded];
        System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, out, 0, 4);
        putLe32(out, 4, out.length - 8);
        System.arraycopy("WEBP".getBytes(StandardCharsets.US_ASCII), 0, out, 8, 4);
        System.arraycopy(fourcc.getBytes(StandardCharsets.US_ASCII), 0, out, 12, 4);
        putLe32(out, 16, declaredChunkSize);
        System.arraycopy(chunkData, 0, out, 20, chunkData.length);
        return out;
    }

    /** The first half of a valid file: a signature and header followed by nothing, as an interrupted upload would leave it. */
    static byte[] truncated(byte[] full) {
        return Arrays.copyOf(full, full.length / 2);
    }

    /** A valid signature followed by noise: it looks like the format for the first bytes and is not an image at all. */
    static byte[] signatureWithGarbage(byte[] signature) {
        byte[] content = Arrays.copyOf(signature, signature.length + 200);
        for (int i = signature.length; i < content.length; i++) content[i] = (byte) (i * 31 + 7);
        return content;
    }

    static MockMultipartFile file(String filename, String contentType, byte[] content) {
        return new MockMultipartFile("file", filename, contentType, content);
    }

    static MockMultipartFile jpegFile() {
        return file("me.jpg", "image/jpeg", jpeg());
    }

    private static byte[] encode(int width, int height, String format, int type) {
        BufferedImage image = new BufferedImage(width, height, type);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(new Color(40, 120, 200));
            g.fillRect(0, 0, width, height);
            g.setColor(new Color(240, 200, 40));
            g.fillOval(width / 4, height / 4, Math.max(1, width / 2), Math.max(1, height / 2));
        } finally {
            g.dispose();
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, format, out)) throw new IllegalStateException("No writer for " + format);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void putLe32(byte[] target, int offset, long value) {
        target[offset] = (byte) value;
        target[offset + 1] = (byte) (value >> 8);
        target[offset + 2] = (byte) (value >> 16);
        target[offset + 3] = (byte) (value >> 24);
    }
}
