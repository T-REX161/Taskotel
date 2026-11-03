package com.hotel.workload.profile;

import com.hotel.workload.security.JwtUtil;
import com.hotel.workload.security.Role;
import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
import com.hotel.workload.staff.StaffRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The profile picture endpoints over HTTP, through the real security filter chain, controller, service and (in-memory) database.
 * Only Cloudinary is replaced by a mock: no image is uploaded anywhere.
 */
@SpringBootTest
public class ProfilePictureControllerTest {

    private static final String PREFIX = "pp_";
    private static final String OLD_ID = "taskotel/profile-pictures/user_old";
    private static final String OLD_URL = "https://res.cloudinary.com/demo/image/upload/f_auto,q_auto/v1/" + OLD_ID;
    private static final ProfileImageStorage.StoredImage NEW =
            new ProfileImageStorage.StoredImage("https://res.cloudinary.com/demo/image/upload/f_auto,q_auto/v2/taskotel/profile-pictures/user_new", "taskotel/profile-pictures/user_new");

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtUtil jwtUtil;
    @MockitoBean private ProfileImageStorage storage;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        reset(storage);
        when(storage.isConfigured()).thenReturn(true);
        cleanUp();
    }

    @AfterEach
    void cleanUp() {
        staffRepository.findAllWithUser().stream().filter(s -> s.getUser().getUsername().startsWith(PREFIX)).forEach(staffRepository::delete);
        userRepository.findAll().stream().filter(u -> u.getUsername().startsWith(PREFIX)).forEach(userRepository::delete);
    }

    private User account(String name, Role role, boolean withPicture) {
        User user = new User();
        user.setUsername(PREFIX + name);
        user.setEmail(PREFIX + name + "@hotel.test");
        user.setPassword(passwordEncoder.encode("Correct@123"));
        user.setRole(role);
        user.setActive(true);
        user.setEmailVerified(true);
        user.setCredentialsSetupRequired(false);
        if (withPicture) {
            user.setProfileImageUrl(OLD_URL);
            user.setProfileImagePublicId(OLD_ID);
        }
        return userRepository.saveAndFlush(user);
    }

    private User reload(User user) {
        return userRepository.findById(user.getId()).orElseThrow();
    }

    private MvcResult upload(User as, MockMultipartFile file) throws Exception {
        return perform(multipart("/api/profile/picture").file(file), as);
    }

    private MvcResult perform(AbstractMockHttpServletRequestBuilder<?> request, User as) throws Exception {
        return mockMvc.perform(request.with(user(as.getUsername()).roles(as.getRole().name()))).andReturn();
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    // ── upload ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Every role can upload its own picture: 200, the URL is saved and returned, the public id is not exposed")
    void uploadForEveryRole() throws Exception {
        for (Role role : Role.values()) {
            reset(storage);
            when(storage.isConfigured()).thenReturn(true);
            when(storage.upload(any(), anyString())).thenReturn(NEW);
            User me = account(role.name().toLowerCase(), role, false);

            MvcResult result = upload(me, TestImages.jpegFile());

            assertEquals(200, result.getResponse().getStatus(), role + ": " + body(result));
            assertTrue(body(result).contains("\"profileImageUrl\":\"" + NEW.url() + "\""), body(result));
            assertTrue(body(result).contains("\"username\":\"" + me.getUsername() + "\""));
            assertFalse(body(result).toLowerCase().contains("publicid"), "the public id stays on the server");
            assertEquals(NEW.url(), reload(me).getProfileImageUrl());
            assertEquals(NEW.publicId(), reload(me).getProfileImagePublicId());
            org.mockito.ArgumentCaptor<byte[]> bytes = org.mockito.ArgumentCaptor.forClass(byte[].class);
            verify(storage).upload(bytes.capture(), anyString());
            assertArrayEquals(TestImages.jpeg(), bytes.getValue(), "the validated bytes are what is uploaded");
            verify(storage, never()).delete(anyString());
        }
    }

    @Test
    @DisplayName("Invalid files are refused before Cloudinary: wrong type 415, over 5 MB 413, missing 400; nothing changes")
    void invalidFilesNeverReachCloudinary() throws Exception {
        User me = account("validation", Role.STAFF, true);

        MvcResult wrongType = upload(me, TestImages.file("me.gif", "image/gif", TestImages.png()));
        assertEquals(415, wrongType.getResponse().getStatus());
        assertTrue(body(wrongType).contains(ProfileImageValidator.UNSUPPORTED_MESSAGE), body(wrongType));

        MvcResult fake = upload(me, TestImages.file("me.png", "image/png", "not really an image".getBytes()));
        assertEquals(415, fake.getResponse().getStatus());

        MvcResult tooBig = upload(me, TestImages.file("big.png", "image/png", Arrays.copyOf(TestImages.png(), (int) ProfileImageValidator.MAX_BYTES + 1)));
        assertEquals(413, tooBig.getResponse().getStatus());
        assertTrue(body(tooBig).contains("5 MB"), body(tooBig));

        MvcResult missing = perform(multipart("/api/profile/picture"), me);
        assertEquals(400, missing.getResponse().getStatus());
        assertTrue(body(missing).contains(ProfileImageValidator.MISSING_MESSAGE), body(missing));

        verify(storage, never()).upload(any(), anyString());
        assertEquals(OLD_URL, reload(me).getProfileImageUrl(), "the current picture is untouched");
    }

    @Test
    @DisplayName("Damaged, truncated and renamed files get a clear 4xx and never reach Cloudinary; a real picture still goes through")
    void corruptedAndRenamedFilesNeverReachCloudinary() throws Exception {
        User me = account("corrupt", Role.STAFF, true);

        MvcResult truncated = upload(me, TestImages.file("me.png", "image/png", TestImages.truncated(TestImages.png(200, 200))));
        assertEquals(400, truncated.getResponse().getStatus());
        assertTrue(body(truncated).contains(ProfileImageValidator.CORRUPT_MESSAGE), body(truncated));

        byte[] jpegHead = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10, 'J', 'F', 'I', 'F', 0x00};
        MvcResult garbage = upload(me, TestImages.file("me.jpg", "image/jpeg", TestImages.signatureWithGarbage(jpegHead)));
        assertEquals(400, garbage.getResponse().getStatus());

        MvcResult svg = upload(me, TestImages.file("me.svg", "image/svg+xml", "<svg xmlns='http://www.w3.org/2000/svg'/>".getBytes()));
        assertEquals(415, svg.getResponse().getStatus());

        MvcResult renamedSvg = upload(me, TestImages.file("me.png", "image/png", "<svg xmlns='http://www.w3.org/2000/svg'/>".getBytes()));
        assertEquals(415, renamedSvg.getResponse().getStatus());
        assertTrue(body(renamedSvg).contains(ProfileImageValidator.NOT_AN_IMAGE_MESSAGE), body(renamedSvg));

        MvcResult renamedGif = upload(me, TestImages.file("me.jpg", "image/jpeg", "GIF89a\u0001\u0000\u0001\u0000".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1)));
        assertEquals(415, renamedGif.getResponse().getStatus());

        MvcResult tooBig = upload(me, TestImages.file("big.png", "image/png", Arrays.copyOf(TestImages.png(), (int) ProfileImageValidator.MAX_BYTES + 1)));
        assertEquals(413, tooBig.getResponse().getStatus());
        assertTrue(body(tooBig).contains(ProfileImageValidator.TOO_LARGE_MESSAGE), body(tooBig));

        verify(storage, never()).upload(any(), anyString());
    }

    @Test
    @DisplayName("Without a sign-in every profile endpoint is a 401 and Cloudinary is never called")
    void unauthenticated() throws Exception {
        assertEquals(401, mockMvc.perform(get("/api/profile")).andReturn().getResponse().getStatus());
        assertEquals(401, mockMvc.perform(multipart("/api/profile/picture").file(TestImages.jpegFile())).andReturn().getResponse().getStatus());
        assertEquals(401, mockMvc.perform(delete("/api/profile/picture")).andReturn().getResponse().getStatus());
        assertEquals(401, mockMvc.perform(multipart("/api/profile/picture").file(TestImages.jpegFile())
                .header("Authorization", "Bearer not-a-real-token")).andReturn().getResponse().getStatus());
        verify(storage, never()).upload(any(), anyString());
        verify(storage, never()).delete(anyString());
    }

    @Test
    @DisplayName("A user cannot change another account's picture: an id or name in the request is ignored, only the caller's own changes")
    void cannotModifyAnotherUsersPicture() throws Exception {
        when(storage.upload(any(), anyString())).thenReturn(NEW);
        User manager = account("manager", Role.MANAGER, false);
        User victim = account("victim", Role.STAFF, true);

        MvcResult result = perform(multipart("/api/profile/picture").file(TestImages.jpegFile())
                .param("userId", victim.getId().toString()).param("username", victim.getUsername()), manager);
        assertEquals(200, result.getResponse().getStatus());
        assertEquals(NEW.url(), reload(manager).getProfileImageUrl(), "the caller's own picture changed");

        MvcResult removal = perform(delete("/api/profile/picture").param("userId", victim.getId().toString()), manager);
        assertEquals(200, removal.getResponse().getStatus());

        // The admin-only user API offers no way either, and a manager cannot even reach it
        assertEquals(403, perform(multipart("/api/users/" + victim.getId() + "/profile-picture").file(TestImages.jpegFile()), manager).getResponse().getStatus());
        assertEquals(403, perform(delete("/api/users/" + victim.getId() + "/profile-picture"), manager).getResponse().getStatus());

        User untouched = reload(victim);
        assertEquals(OLD_URL, untouched.getProfileImageUrl());
        assertEquals(OLD_ID, untouched.getProfileImagePublicId());
        verify(storage, never()).delete(OLD_ID);
    }

    // ── replace ────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Replacing: the new picture is saved and the old Cloudinary image is deleted afterwards")
    void successfulReplacement() throws Exception {
        when(storage.upload(any(), anyString())).thenReturn(NEW);
        User me = account("replace", Role.STAFF, true);

        MvcResult result = upload(me, TestImages.file("new.webp", "image/webp", TestImages.webp()));

        assertEquals(200, result.getResponse().getStatus(), body(result));
        assertEquals(NEW.url(), reload(me).getProfileImageUrl());
        verify(storage).delete(OLD_ID);
        verify(storage, never()).delete(NEW.publicId());
    }

    @Test
    @DisplayName("A failed replacement keeps the old picture: 502 with a plain message, nothing deleted, database unchanged")
    void failedReplacementKeepsTheOldPicture() throws Exception {
        when(storage.upload(any(), anyString()))
                .thenThrow(new ProfileImageStorageException(ProfileImageStorageException.Reason.UNAVAILABLE, "connection refused"));
        User me = account("failed", Role.MANAGER, true);

        MvcResult result = upload(me, TestImages.jpegFile());

        assertEquals(502, result.getResponse().getStatus());
        assertTrue(body(result).contains(ProfilePictureService.UPLOAD_UNAVAILABLE_MESSAGE), body(result));
        assertFalse(body(result).contains("connection refused"), "internal details stay in the log");
        assertEquals(OLD_URL, reload(me).getProfileImageUrl());
        assertEquals(OLD_ID, reload(me).getProfileImagePublicId());
        verify(storage, never()).delete(anyString());
    }

    @Test
    @DisplayName("Cloudinary failures map to safe answers: refused image 400, missing credentials 503")
    void cloudinaryFailureHandling() throws Exception {
        User me = account("errors", Role.STAFF, false);
        when(storage.upload(any(), anyString()))
                .thenThrow(new ProfileImageStorageException(ProfileImageStorageException.Reason.REJECTED, "Invalid image file"));
        MvcResult rejected = upload(me, TestImages.jpegFile());
        assertEquals(400, rejected.getResponse().getStatus());
        assertTrue(body(rejected).contains(ProfilePictureService.REJECTED_MESSAGE));

        when(storage.isConfigured()).thenReturn(false);
        MvcResult notConfigured = upload(me, TestImages.jpegFile());
        assertEquals(503, notConfigured.getResponse().getStatus());
        assertTrue(body(notConfigured).contains(ProfilePictureService.NOT_CONFIGURED_MESSAGE));
        assertNull(reload(me).getProfileImageUrl());
    }

    @Test
    @DisplayName("Wrong Cloudinary settings found during an upload (bad cloud name or refused credentials) are a 503, not 'not responding'; the picture is kept")
    void settingsProblemDuringUpload() throws Exception {
        User me = account("settings", Role.MANAGER, true);
        when(storage.upload(any(), anyString()))
                .thenThrow(new ProfileImageStorageException(ProfileImageStorageException.Reason.NOT_CONFIGURED, "Cloudinary upload failed"));

        MvcResult result = upload(me, TestImages.jpegFile());

        assertEquals(503, result.getResponse().getStatus());
        assertTrue(body(result).contains(ProfilePictureService.NOT_CONFIGURED_MESSAGE), body(result));
        assertFalse(body(result).contains(ProfilePictureService.UPLOAD_UNAVAILABLE_MESSAGE));
        assertEquals(OLD_URL, reload(me).getProfileImageUrl());
        assertEquals(OLD_ID, reload(me).getProfileImagePublicId());
        verify(storage, never()).delete(anyString());
    }

    // ── delete ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Deleting removes the image in Cloudinary and clears the account; the default avatar applies again")
    void successfulDeletion() throws Exception {
        User me = account("remove", Role.ADMIN, true);

        MvcResult result = perform(delete("/api/profile/picture"), me);

        assertEquals(200, result.getResponse().getStatus(), body(result));
        assertTrue(body(result).contains("\"profileImageUrl\":null"), body(result));
        verify(storage).delete(OLD_ID);
        assertNull(reload(me).getProfileImageUrl());
        assertNull(reload(me).getProfileImagePublicId());
    }

    @Test
    @DisplayName("If Cloudinary cannot delete, the picture stays recorded (502), so it is never shown as removed while still stored")
    void failedDeletionKeepsThePicture() throws Exception {
        User me = account("remove_fail", Role.STAFF, true);
        doThrow(new ProfileImageStorageException(ProfileImageStorageException.Reason.UNAVAILABLE, "timeout")).when(storage).delete(OLD_ID);

        MvcResult result = perform(delete("/api/profile/picture"), me);

        assertEquals(502, result.getResponse().getStatus());
        assertTrue(body(result).contains(ProfilePictureService.REMOVE_UNAVAILABLE_MESSAGE));
        assertEquals(OLD_URL, reload(me).getProfileImageUrl());
    }

    // ── retrieval and existing users ───────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Existing accounts without a picture keep working: profile, staff list and user list show a null image")
    void existingUsersWithoutPictures() throws Exception {
        User admin = account("lister", Role.ADMIN, false);
        String json = """
                {"fullName": "Picture Staff", "email": "pp_listed@hotel.test", "department": "FRONT_DESK", "availability": "AVAILABLE",
                 "username": "pp_listed", "temporaryPassword": "TempPass@123", "role": "STAFF"}""";
        assertEquals(201, perform(post("/api/staff").contentType(MediaType.APPLICATION_JSON).content(json), admin).getResponse().getStatus());

        MvcResult profile = perform(get("/api/profile"), admin);
        assertEquals(200, profile.getResponse().getStatus());
        assertTrue(body(profile).contains("\"profileImageUrl\":null"), body(profile));
        assertTrue(body(profile).contains("\"role\":\"ADMIN\""));

        String staffList = body(perform(get("/api/staff"), admin));
        assertTrue(staffList.contains("\"username\":\"pp_listed\""), staffList);
        assertTrue(staffList.contains("\"profileImageUrl\""), "the staff list carries the image field");

        // Once the staff member has a picture, both lists show it without any extra request
        User listed = userRepository.findByUsername("pp_listed").orElseThrow();
        listed.setProfileImageUrl(OLD_URL);
        listed.setProfileImagePublicId(OLD_ID);
        userRepository.saveAndFlush(listed);
        String staffWithPicture = body(perform(get("/api/staff"), admin));
        String usersWithPicture = body(perform(get("/api/users"), admin));
        assertTrue(staffWithPicture.contains(OLD_URL));
        assertTrue(usersWithPicture.contains(OLD_URL));
        assertFalse(staffWithPicture.toLowerCase().contains("publicid") || usersWithPicture.toLowerCase().contains("publicid"),
                "the public id field stays on the server");
        verifyNoInteractions(storage);
    }

    @Test
    @DisplayName("With a real sign-in token the profile is the token's own account, for every role")
    void realTokenGetsOwnProfile() throws Exception {
        for (Role role : Role.values()) {
            User me = account("token_" + role.name().toLowerCase(), role, role == Role.MANAGER);
            String token = jwtUtil.generateToken(me.getUsername(), role);

            MvcResult result = mockMvc.perform(get("/api/profile").header("Authorization", "Bearer " + token)).andReturn();

            assertEquals(200, result.getResponse().getStatus(), role + ": " + body(result));
            assertTrue(body(result).contains("\"username\":\"" + me.getUsername() + "\""));
            assertTrue(body(result).contains("\"role\":\"" + role.name() + "\""));
            assertEquals(role == Role.MANAGER, body(result).contains(OLD_URL));
        }
    }

    @Test
    @DisplayName("A multipart request over the server limit gets the same 5 MB message as a 413")
    void oversizedRequestHandler() {
        var response = new com.hotel.workload.security.GlobalExceptionHandler()
                .handleMaxUploadSizeExceeded(new org.springframework.web.multipart.MaxUploadSizeExceededException(ProfileImageValidator.MAX_BYTES));
        assertEquals(413, response.getStatusCode().value());
        assertEquals(ProfileImageValidator.TOO_LARGE_MESSAGE, response.getBody().get("message"));
    }

    @Test
    @DisplayName("Login is unaffected by pictures: an account with a picture signs in exactly as before")
    void loginUnaffected() throws Exception {
        User me = account("login", Role.STAFF, true);
        MvcResult result = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .with(request -> { request.setRemoteAddr("10.9.0.1"); return request; })
                .content("{\"username\":\"" + me.getUsername() + "\",\"password\":\"Correct@123\"}")).andReturn();
        assertEquals(200, result.getResponse().getStatus(), body(result));
        assertTrue(body(result).contains("\"token\":\""));
        verifyNoInteractions(storage);
    }
}
