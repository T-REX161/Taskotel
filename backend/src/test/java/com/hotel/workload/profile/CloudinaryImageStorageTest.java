package com.hotel.workload.profile;

import com.cloudinary.Cloudinary;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the real Cloudinary SDK (and its HttpClient 5 transport, at the version the Spring Boot BOM selects) against a local
 * stand-in for the Cloudinary API, so the requests the application really sends can be checked without any network access.
 */
public class CloudinaryImageStorageTest {

    private static final String CLOUD = "demo-cloud";
    private static final String API_KEY = "123456789012345";
    private static final String API_SECRET = "test-secret-AbC_123";

    private HttpServer server;
    private final List<Received> requests = new ArrayList<>();
    private volatile int status = 200;
    private volatile String responseBody = "{}";

    private record Received(String path, String rawBody, Map<String, String> fields, String headers) {}

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            String raw = new String(body, StandardCharsets.ISO_8859_1);
            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            String headers = exchange.getRequestHeaders().entrySet().stream()
                    .map(e -> e.getKey() + ": " + String.join(",", e.getValue())).collect(Collectors.joining("\n"));
            synchronized (requests) {
                requests.add(new Received(exchange.getRequestURI().getPath(), raw, formFields(contentType, raw), headers));
            }
            byte[] answer = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, answer.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(answer);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private CloudinaryImageStorage storage() {
        Cloudinary cloudinary = CloudinaryConfig.createClient(new CloudinaryProperties(CLOUD, API_KEY, API_SECRET, null));
        cloudinary.config.uploadPrefix = "http://127.0.0.1:" + server.getAddress().getPort();
        return new CloudinaryImageStorage(cloudinary, CloudinaryProperties.DEFAULT_FOLDER);
    }

    private Received only() {
        synchronized (requests) {
            assertEquals(1, requests.size(), "exactly one request to Cloudinary");
            return requests.get(0);
        }
    }

    @Test
    @DisplayName("Upload: one signed request to the image upload API, in the profile folder, stored as a 512x512 square, no secret sent")
    void uploadSendsASignedRequestWithTheProfileSettings() throws Exception {
        responseBody = """
                {"public_id":"taskotel/profile-pictures/user_42_abc","version":1730000000,"format":"jpg","width":512,"height":512,
                 "secure_url":"https://res.cloudinary.com/demo-cloud/image/upload/v1730000000/taskotel/profile-pictures/user_42_abc.jpg"}""";

        ProfileImageStorage.StoredImage stored = storage().upload(TestImages.jpeg(), "user_42_abc");

        Received request = only();
        assertEquals("/v1_1/" + CLOUD + "/image/upload", request.path());
        Map<String, String> f = request.fields();
        assertEquals("taskotel/profile-pictures", f.get("folder"));
        assertEquals("user_42_abc", f.get("public_id"));
        assertEquals("false", f.get("overwrite"));
        assertEquals("jpg,jpeg,png,webp", f.get("allowed_formats"));
        assertEquals("c_fill,g_auto,h_512,w_512", f.get("transformation"));
        assertEquals(API_KEY, f.get("api_key"));
        assertTrue(f.get("timestamp").matches("\\d{10}"), "a current Unix timestamp");
        assertTrue(f.get("signature").matches("[0-9a-f]{40}"), "a SHA-1 signature");
        assertEquals(expectedSignature(f), f.get("signature"), "signed with the configured secret");
        assertTrue(request.rawBody().contains("filename="), "the image travels as a file part");
        assertFalse(request.rawBody().contains(API_SECRET) || request.headers().contains(API_SECRET), "the API secret is never sent");

        assertEquals("taskotel/profile-pictures/user_42_abc", stored.publicId());
        assertEquals("https://res.cloudinary.com/demo-cloud/image/upload/f_auto,q_auto/v1730000000/taskotel/profile-pictures/user_42_abc", stored.url());
    }

    @Test
    @DisplayName("Delete: a signed destroy request that also invalidates cached copies; 'not found' counts as already removed")
    void deleteSendsASignedDestroyRequest() throws Exception {
        responseBody = "{\"result\":\"ok\"}";
        storage().delete("taskotel/profile-pictures/user_42_abc");

        Received request = only();
        assertEquals("/v1_1/" + CLOUD + "/image/destroy", request.path());
        assertEquals("taskotel/profile-pictures/user_42_abc", request.fields().get("public_id"));
        assertEquals("true", request.fields().get("invalidate"));
        assertEquals(expectedSignature(request.fields()), request.fields().get("signature"));
        assertFalse(request.rawBody().contains(API_SECRET));

        responseBody = "{\"result\":\"not found\"}";
        storage().delete("taskotel/profile-pictures/gone");
    }

    @Test
    @DisplayName("Cloudinary errors become storage exceptions: refused image REJECTED, wrong settings NOT_CONFIGURED, anything else UNAVAILABLE")
    void errorsAreClassified() {
        status = 400;
        responseBody = "{\"error\":{\"message\":\"Invalid image file\"}}";
        ProfileImageStorageException rejected = assertThrows(ProfileImageStorageException.class, () -> storage().upload(TestImages.png(), "x"));
        assertEquals(ProfileImageStorageException.Reason.REJECTED, rejected.getReason());

        // Wrong secret, key or cloud name: a settings problem, not an outage
        status = 401;
        for (String message : new String[] {"Invalid Signature 0123abcd. String to sign - 'timestamp=1'.", "Unknown API key ***", "Invalid cloud_name demo"}) {
            responseBody = "{\"error\":{\"message\":\"" + message + "\"}}";
            assertEquals(ProfileImageStorageException.Reason.NOT_CONFIGURED,
                    assertThrows(ProfileImageStorageException.class, () -> storage().upload(TestImages.png(), "x")).getReason(), message);
        }

        // An API key without upload permission: Cloudinary's real 403 answer (seen with a restricted key)
        status = 403;
        responseBody = "{\"error\":{\"message\":\"[prodenv:0123abcd] Request forbidden due to missing permissions (actions=[\\\"create\\\"])\"}}";
        assertEquals(ProfileImageStorageException.Reason.NOT_CONFIGURED,
                assertThrows(ProfileImageStorageException.class, () -> storage().upload(TestImages.png(), "x")).getReason(),
                "a key without permission is a settings problem, not an outage");

        status = 500;
        responseBody = "{\"error\":{\"message\":\"General Error\"}}";
        assertEquals(ProfileImageStorageException.Reason.UNAVAILABLE,
                assertThrows(ProfileImageStorageException.class, () -> storage().delete("x")).getReason());

        status = 420;
        responseBody = "{\"error\":{\"message\":\"Rate Limit Exceeded\"}}";
        assertEquals(ProfileImageStorageException.Reason.UNAVAILABLE,
                assertThrows(ProfileImageStorageException.class, () -> storage().upload(TestImages.png(), "x")).getReason());

        status = 200;
        responseBody = "{\"result\":\"error\"}";
        assertEquals(ProfileImageStorageException.Reason.UNAVAILABLE,
                assertThrows(ProfileImageStorageException.class, () -> storage().delete("x")).getReason(), "an unconfirmed delete");

        responseBody = "{\"version\":1}";
        assertEquals(ProfileImageStorageException.Reason.UNAVAILABLE,
                assertThrows(ProfileImageStorageException.class, () -> storage().upload(TestImages.png(), "x")).getReason(), "no public id");
    }

    @Test
    @DisplayName("A cloud name with a space (the API key's label) fails before any request and is reported as a settings problem")
    void cloudNameWithASpaceIsASettingsProblem() {
        Cloudinary cloudinary = CloudinaryConfig.createClient(new CloudinaryProperties(CLOUD, API_KEY, API_SECRET, null));
        cloudinary.config.cloudName = "Taskotel Backend";
        cloudinary.config.uploadPrefix = "http://127.0.0.1:" + server.getAddress().getPort();
        CloudinaryImageStorage storage = new CloudinaryImageStorage(cloudinary, CloudinaryProperties.DEFAULT_FOLDER);

        ProfileImageStorageException upload = assertThrows(ProfileImageStorageException.class, () -> storage.upload(TestImages.png(), "x"));
        assertEquals(ProfileImageStorageException.Reason.NOT_CONFIGURED, upload.getReason(), "not an outage: the URL cannot even be built");
        assertTrue(upload.getCause() instanceof IllegalArgumentException, String.valueOf(upload.getCause()));
        assertEquals(ProfileImageStorageException.Reason.NOT_CONFIGURED,
                assertThrows(ProfileImageStorageException.class, () -> storage.delete("x")).getReason());
        assertTrue(requests.isEmpty(), "nothing reached the server");
    }

    @Test
    @DisplayName("An unreachable Cloudinary is UNAVAILABLE, not a crash")
    void unreachable() {
        CloudinaryImageStorage storage = storage();
        server.stop(0);
        assertEquals(ProfileImageStorageException.Reason.UNAVAILABLE,
                assertThrows(ProfileImageStorageException.class, () -> storage.upload(TestImages.png(), "x")).getReason());
    }

    @Test
    @DisplayName("Without credentials nothing is sent and every call is refused as NOT_CONFIGURED")
    void notConfigured() {
        CloudinaryImageStorage storage = new CloudinaryImageStorage(null, CloudinaryProperties.DEFAULT_FOLDER);
        assertFalse(storage.isConfigured());
        assertEquals(ProfileImageStorageException.Reason.NOT_CONFIGURED,
                assertThrows(ProfileImageStorageException.class, () -> storage.upload(TestImages.png(), "x")).getReason());
        assertEquals(ProfileImageStorageException.Reason.NOT_CONFIGURED,
                assertThrows(ProfileImageStorageException.class, () -> storage.delete("x")).getReason());
        assertTrue(requests.isEmpty());
    }

    @Test
    @DisplayName("The configuration builds a storage only from complete credentials; placeholders leave it switched off")
    void configBuildsStorageOnlyWhenComplete() {
        CloudinaryConfig config = new CloudinaryConfig();
        assertTrue(config.profileImageStorage(new CloudinaryProperties(CLOUD, API_KEY, API_SECRET, null)).isConfigured());
        assertFalse(config.profileImageStorage(new CloudinaryProperties("your_cloud_name", "your_api_key", "your_api_secret", null)).isConfigured());
        assertFalse(config.profileImageStorage(new CloudinaryProperties(CLOUD, API_KEY, "", null)).isConfigured());
        assertFalse(config.profileImageStorage(new CloudinaryProperties("Taskotel Backend", API_KEY, API_SECRET, null)).isConfigured(),
                "a cloud name that cannot be part of a URL switches uploads off at startup");
    }

    @Test
    @DisplayName("Logged Cloudinary errors mask long digit runs such as an API key and stay short")
    void safeDetail() {
        assertEquals("Unknown API key ***", CloudinaryImageStorage.safeDetail(new RuntimeException("Unknown API key 123456789012345")));
        assertTrue(CloudinaryImageStorage.safeDetail(new RuntimeException("x".repeat(500))).length() <= 303);
    }

    /** Cloudinary's documented signing: the signed parameters sorted by name as k=v joined with &, plus the secret, SHA-1. */
    private static String expectedSignature(Map<String, String> fields) throws Exception {
        TreeMap<String, String> signed = new TreeMap<>(fields);
        signed.keySet().removeAll(List.of("file", "api_key", "signature", "resource_type", "cloud_name"));
        signed.values().removeIf(String::isEmpty);
        String toSign = signed.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining("&")) + API_SECRET;
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(toSign.getBytes(StandardCharsets.UTF_8)));
    }

    /** The text fields of a multipart/form-data body (file parts are skipped), or of a urlencoded body. */
    private static Map<String, String> formFields(String contentType, String raw) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (contentType != null && contentType.startsWith("multipart/form-data")) {
            Matcher b = Pattern.compile("boundary=\"?([^\";]+)\"?").matcher(contentType);
            if (!b.find()) return fields;
            for (String part : raw.split("--" + Pattern.quote(b.group(1)))) {
                int split = part.indexOf("\r\n\r\n");
                if (split < 0) continue;
                String head = part.substring(0, split);
                Matcher name = Pattern.compile("name=\"([^\"]+)\"").matcher(head);
                if (!name.find() || head.contains("filename=")) continue;
                String value = part.substring(split + 4);
                if (value.endsWith("\r\n")) value = value.substring(0, value.length() - 2);
                fields.put(name.group(1), new String(value.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8));
            }
        } else if (raw != null && !raw.isEmpty()) {
            for (String pair : raw.split("&")) {
                String[] kv = pair.split("=", 2);
                fields.put(java.net.URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                        kv.length > 1 ? java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
            }
        }
        return fields;
    }
}
