package com.hotel.workload.profile;

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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** PUT /api/profile/display-name over HTTP, through the real security chain, controller, service and (in-memory) database. */
@SpringBootTest
public class DisplayNameControllerTest {

    private static final String PREFIX = "dn_";

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @MockitoBean private ProfileImageStorage storage;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        cleanUp();
    }

    @AfterEach
    void cleanUp() {
        staffRepository.findAllWithUser().stream().filter(s -> s.getUser().getUsername().startsWith(PREFIX)).forEach(staffRepository::delete);
        userRepository.findAll().stream().filter(u -> u.getUsername().startsWith(PREFIX)).forEach(userRepository::delete);
    }

    private User account(String name, Role role) {
        User user = new User();
        user.setUsername(PREFIX + name);
        user.setEmail(PREFIX + name + "@hotel.test");
        user.setPassword(passwordEncoder.encode("Correct@123"));
        user.setRole(role);
        user.setActive(true);
        user.setEmailVerified(true);
        user.setCredentialsSetupRequired(false);
        user.setProfileImageUrl("https://res.cloudinary.com/demo/image/upload/v1/me");
        user.setProfileImagePublicId("taskotel/profile-pictures/me");
        return userRepository.saveAndFlush(user);
    }

    private User reload(User user) {
        return userRepository.findById(user.getId()).orElseThrow();
    }

    private MvcResult perform(AbstractMockHttpServletRequestBuilder<?> request, User as) throws Exception {
        return mockMvc.perform(request.with(user(as.getUsername()).roles(as.getRole().name()))).andReturn();
    }

    private MvcResult rename(User as, String json) throws Exception {
        return perform(put("/api/profile/display-name").contentType(MediaType.APPLICATION_JSON).content(json), as);
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    @Test
    @DisplayName("Every role can change its own display name: 200, saved and returned, username, role and picture untouched")
    void updatesForEveryRole() throws Exception {
        for (Role role : Role.values()) {
            User me = account(role.name().toLowerCase(), role);

            MvcResult result = rename(me, "{\"displayName\":\"  Ada Lovelace  \"}");

            assertEquals(200, result.getResponse().getStatus(), role + ": " + body(result));
            assertTrue(body(result).contains("\"displayName\":\"Ada Lovelace\""), "trimmed and returned: " + body(result));
            assertTrue(body(result).contains("\"username\":\"" + me.getUsername() + "\""));
            User saved = reload(me);
            assertEquals("Ada Lovelace", saved.getDisplayName());
            assertEquals(me.getUsername(), saved.getUsername());
            assertEquals(role, saved.getRole());
            assertEquals(me.getProfileImageUrl(), saved.getProfileImageUrl(), "the picture is not touched");
            assertEquals(me.getProfileImagePublicId(), saved.getProfileImagePublicId());
        }
    }

    @Test
    @DisplayName("An account without a chosen name shows its username with a capital first letter; the profile still has every earlier field")
    void defaultsFromUsername() throws Exception {
        User me = account("fresh", Role.STAFF);
        assertNull(me.getDisplayName());

        MvcResult result = perform(get("/api/profile"), me);

        assertEquals(200, result.getResponse().getStatus());
        assertTrue(body(result).contains("\"displayName\":\"Dn_fresh\""), body(result));
        assertTrue(body(result).contains("\"username\":\"dn_fresh\"") && body(result).contains("\"role\":\"STAFF\"")
                && body(result).contains("\"fullName\":") && body(result).contains("\"profileImageUrl\":"), body(result));
    }

    @Test
    @DisplayName("The profile reports the account's own active and email-verified state, not a constant")
    void reportsActiveAndEmailVerified() throws Exception {
        User verified = account("verified", Role.ADMIN);
        MvcResult ok = perform(get("/api/profile"), verified);
        assertTrue(body(ok).contains("\"active\":true") && body(ok).contains("\"emailVerified\":true"), body(ok));

        User pending = account("pending", Role.MANAGER);
        pending.setEmailVerified(false);
        pending.setActive(false);
        userRepository.saveAndFlush(pending);
        MvcResult notYet = perform(get("/api/profile"), pending);
        assertTrue(body(notYet).contains("\"active\":false") && body(notYet).contains("\"emailVerified\":false"), body(notYet));
        assertFalse(body(notYet).contains("\"password\"") || body(notYet).toLowerCase().contains("hash") || body(notYet).toLowerCase().contains("token"),
                "no password, hash or token is exposed");
    }

    @Test
    @DisplayName("Empty, blank, missing, too long and control-character names are refused with 400 and nothing changes")
    void invalidNames() throws Exception {
        User me = account("invalid", Role.MANAGER);
        rename(me, "{\"displayName\":\"Keep Me\"}");

        for (String json : new String[] {
                "{\"displayName\":\"\"}", "{\"displayName\":\"    \"}", "{\"displayName\":null}", "{}",
                "{\"displayName\":\"" + "x".repeat(DisplayNames.MAX_LENGTH + 1) + "\"}",
                "{\"displayName\":\"line\\nbreak\"}", "{\"displayName\":\"tab\\there\"}"}) {
            MvcResult result = rename(me, json);
            assertEquals(400, result.getResponse().getStatus(), json + " -> " + body(result));
            assertEquals("Keep Me", reload(me).getDisplayName(), "unchanged after " + json);
        }
        assertTrue(body(rename(me, "{\"displayName\":\"\"}")).contains(DisplayNames.EMPTY_MESSAGE));
        assertTrue(body(rename(me, "{\"displayName\":\"" + "x".repeat(51) + "\"}")).contains(DisplayNames.TOO_LONG_MESSAGE));
        assertEquals(400, perform(put("/api/profile/display-name").contentType(MediaType.APPLICATION_JSON).content("not json"), me)
                .getResponse().getStatus());
        assertEquals("Keep Me", reload(me).getDisplayName());
    }

    @Test
    @DisplayName("The longest allowed name (50 characters, emoji and accents included) is saved")
    void longestAndUnicodeNames() throws Exception {
        User me = account("unicode", Role.ADMIN);
        String longest = "é".repeat(DisplayNames.MAX_LENGTH);
        assertEquals(200, rename(me, "{\"displayName\":\"" + longest + "\"}").getResponse().getStatus());
        assertEquals(longest, reload(me).getDisplayName());
        assertEquals(200, rename(me, "{\"displayName\":\"Zoë 😀 O'Neil-Smith\"}").getResponse().getStatus());
        assertEquals("Zoë 😀 O'Neil-Smith", reload(me).getDisplayName());
    }

    @Test
    @DisplayName("Only the signed-in account changes: other users are untouched and a user id in the request is ignored")
    void cannotChangeAnotherUser() throws Exception {
        User alice = account("alice", Role.STAFF);
        User bob = account("bob", Role.STAFF);
        rename(bob, "{\"displayName\":\"Bob Original\"}");

        MvcResult result = rename(alice, "{\"displayName\":\"Alice New\",\"id\":\"" + bob.getId() + "\",\"userId\":\"" + bob.getId()
                + "\",\"username\":\"" + bob.getUsername() + "\"}");

        assertEquals(200, result.getResponse().getStatus(), body(result));
        assertEquals("Alice New", reload(alice).getDisplayName());
        assertEquals("Bob Original", reload(bob).getDisplayName(), "bob is untouched");
        assertEquals(alice.getUsername(), reload(alice).getUsername());
        assertEquals(bob.getUsername(), reload(bob).getUsername());
    }

    @Test
    @DisplayName("The username can not be changed through it: the request has no such field and the response keeps the real username")
    void usernameStaysReadOnly() throws Exception {
        User me = account("fixed", Role.STAFF);
        MvcResult result = rename(me, "{\"displayName\":\"Someone\",\"username\":\"hacker\",\"role\":\"ADMIN\"}");
        assertEquals(200, result.getResponse().getStatus());
        assertEquals("dn_fixed", reload(me).getUsername());
        assertEquals(Role.STAFF, reload(me).getRole());
        assertFalse(body(result).contains("hacker"));
    }

    @Test
    @DisplayName("Without a token the endpoint is refused and nothing changes")
    void requiresAuthentication() throws Exception {
        User me = account("anon", Role.STAFF);
        MvcResult result = mockMvc.perform(put("/api/profile/display-name").contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"Intruder\"}")).andReturn();
        assertTrue(result.getResponse().getStatus() == 401 || result.getResponse().getStatus() == 403, "status " + result.getResponse().getStatus());
        assertNull(reload(me).getDisplayName());
    }

    @Test
    @DisplayName("The name survives logout and login: a fresh token reads the saved display name")
    void persistsAcrossLogin() throws Exception {
        User me = account("persist", Role.MANAGER);
        assertEquals(200, rename(me, "{\"displayName\":\"Persistent Pat\"}").getResponse().getStatus());

        MvcResult login = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .with(request -> { request.setRemoteAddr("10.9.1.7"); return request; })
                .content("{\"username\":\"" + me.getUsername() + "\",\"password\":\"Correct@123\"}")).andReturn();
        assertEquals(200, login.getResponse().getStatus(), body(login));
        Matcher token = Pattern.compile("\"token\":\"([^\"]+)\"").matcher(body(login));
        assertTrue(token.find());

        MvcResult profile = mockMvc.perform(get("/api/profile").header("Authorization", "Bearer " + token.group(1))).andReturn();

        assertEquals(200, profile.getResponse().getStatus(), body(profile));
        assertTrue(body(profile).contains("\"displayName\":\"Persistent Pat\""), body(profile));
        assertTrue(body(profile).contains("\"username\":\"dn_persist\""));
    }
}
