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

/** PUT /api/profile/preferences and the preferences in GET /api/profile: per-user, validated, persistent, no extra data exposed. */
@SpringBootTest
public class PreferencesControllerTest {

    private static final String PREFIX = "pref_";

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @MockitoBean private ProfileImageStorage storage;

    private MockMvc mockMvc;
    private int clients = 0;

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
        return userRepository.saveAndFlush(user);
    }

    private User reload(User user) {
        return userRepository.findById(user.getId()).orElseThrow();
    }

    private MvcResult perform(AbstractMockHttpServletRequestBuilder<?> request, User as) throws Exception {
        return mockMvc.perform(request.with(user(as.getUsername()).roles(as.getRole().name()))).andReturn();
    }

    private MvcResult save(User as, String theme, String date, String time) throws Exception {
        return perform(put("/api/profile/preferences").contentType(MediaType.APPLICATION_JSON)
                .content("{\"themePreference\":" + quote(theme) + ",\"dateFormat\":" + quote(date) + ",\"timeFormat\":" + quote(time) + "}"), as);
    }

    private static String quote(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    private static String prefsJson(String theme, String date, String time) {
        return "\"preferences\":{\"themePreference\":\"" + theme + "\",\"dateFormat\":\"" + date + "\",\"timeFormat\":\"" + time + "\"}";
    }

    @Test
    @DisplayName("An account that never chose anything gets the defaults: light, DD/MM/YYYY, 12-hour; nothing is written to its row")
    void defaults() throws Exception {
        User me = account("fresh", Role.STAFF);

        MvcResult result = perform(get("/api/profile"), me);

        assertEquals(200, result.getResponse().getStatus());
        assertTrue(body(result).contains(prefsJson("LIGHT", "DMY", "H12")), body(result));
        assertNull(reload(me).getThemePreference());
        assertNull(reload(me).getDateFormat());
        assertNull(reload(me).getTimeFormat());
    }

    @Test
    @DisplayName("Every option of every preference can be saved, by every role, and comes back from GET /api/profile")
    void everyOptionForEveryRole() throws Exception {
        for (Role role : Role.values()) {
            User me = account(role.name().toLowerCase(), role);
            for (String theme : UserPreferences.THEMES) {
                MvcResult result = save(me, theme, "MDY", "H24");
                assertEquals(200, result.getResponse().getStatus(), role + " " + theme + ": " + body(result));
                assertTrue(body(result).contains(prefsJson(theme, "MDY", "H24")), body(result));
            }
            for (String date : UserPreferences.DATE_FORMATS) {
                assertEquals(200, save(me, "DARK", date, "H12").getResponse().getStatus(), role + " " + date);
                assertTrue(body(perform(get("/api/profile"), me)).contains(prefsJson("DARK", date, "H12")));
            }
            for (String time : UserPreferences.TIME_FORMATS) {
                assertEquals(200, save(me, "SYSTEM", "YMD", time).getResponse().getStatus(), role + " " + time);
            }
            User saved = reload(me);
            assertEquals("SYSTEM", saved.getThemePreference());
            assertEquals("YMD", saved.getDateFormat());
            assertEquals("H24", saved.getTimeFormat());
            assertEquals(role, saved.getRole());
            assertEquals(me.getUsername(), saved.getUsername());
        }
    }

    @Test
    @DisplayName("Invalid, missing and unknown values are refused with 400 and nothing changes")
    void invalidValues() throws Exception {
        User me = account("invalid", Role.MANAGER);
        save(me, "DARK", "YMD", "H24");

        String[][] bad = {
                {"PURPLE", "YMD", "H24"}, {"DARK", "DD-MM-YYYY", "H24"}, {"DARK", "YMD", "H36"},
                {null, "YMD", "H24"}, {"DARK", null, "H24"}, {"DARK", "YMD", null}, {"", "YMD", "H24"}, {"DARK", " ", "H24"},
                {"DARK; DROP TABLE users", "YMD", "H24"}, {"DARK", "YMD", "H24'--"}};
        for (String[] row : bad) {
            MvcResult result = save(me, row[0], row[1], row[2]);
            assertEquals(400, result.getResponse().getStatus(), String.join("|", String.valueOf(row[0]), String.valueOf(row[1]), String.valueOf(row[2])) + " -> " + body(result));
            User unchanged = reload(me);
            assertEquals("DARK", unchanged.getThemePreference());
            assertEquals("YMD", unchanged.getDateFormat());
            assertEquals("H24", unchanged.getTimeFormat());
        }
        assertEquals(400, perform(put("/api/profile/preferences").contentType(MediaType.APPLICATION_JSON).content("not json"), me).getResponse().getStatus());
        assertEquals(400, perform(put("/api/profile/preferences").contentType(MediaType.APPLICATION_JSON).content("{}"), me).getResponse().getStatus());
    }

    @Test
    @DisplayName("Preferences are per user: Admin, Manager and Staff each keep their own, and one user's change never reaches another")
    void independentPerUser() throws Exception {
        User admin = account("admin", Role.ADMIN);
        User manager = account("manager", Role.MANAGER);
        User staff = account("staff", Role.STAFF);

        save(admin, "DARK", "MDY", "H24");
        save(manager, "SYSTEM", "YMD", "H12");
        // staff never chose

        assertTrue(body(perform(get("/api/profile"), admin)).contains(prefsJson("DARK", "MDY", "H24")));
        assertTrue(body(perform(get("/api/profile"), manager)).contains(prefsJson("SYSTEM", "YMD", "H12")));
        assertTrue(body(perform(get("/api/profile"), staff)).contains(prefsJson("LIGHT", "DMY", "H12")));

        save(staff, "LIGHT", "YMD", "H24"); // staff changing theirs leaves the others alone
        assertTrue(body(perform(get("/api/profile"), admin)).contains(prefsJson("DARK", "MDY", "H24")));
        assertTrue(body(perform(get("/api/profile"), manager)).contains(prefsJson("SYSTEM", "YMD", "H12")));
    }

    @Test
    @DisplayName("Who is changed comes only from the token: an id or username in the request body is ignored")
    void cannotChangeAnotherUser() throws Exception {
        User alice = account("alice", Role.STAFF);
        User bob = account("bob", Role.STAFF);
        save(bob, "DARK", "MDY", "H24");

        MvcResult result = perform(put("/api/profile/preferences").contentType(MediaType.APPLICATION_JSON)
                .content("{\"themePreference\":\"SYSTEM\",\"dateFormat\":\"YMD\",\"timeFormat\":\"H12\",\"id\":\"" + bob.getId()
                        + "\",\"userId\":\"" + bob.getId() + "\",\"username\":\"" + bob.getUsername() + "\"}"), alice);

        assertEquals(200, result.getResponse().getStatus(), body(result));
        assertEquals("SYSTEM", reload(alice).getThemePreference());
        assertEquals("DARK", reload(bob).getThemePreference());
        assertEquals("MDY", reload(bob).getDateFormat());
        assertEquals("H24", reload(bob).getTimeFormat());
    }

    @Test
    @DisplayName("Saved preferences survive signing out and back in: a fresh login token reads them")
    void persistAcrossLogin() throws Exception {
        User me = account("persist", Role.MANAGER);
        assertEquals(200, save(me, "DARK", "YMD", "H24").getResponse().getStatus());

        MvcResult login = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .with(request -> { request.setRemoteAddr("10.6.2." + (++clients)); return request; })
                .content("{\"username\":\"" + me.getUsername() + "\",\"password\":\"Correct@123\"}")).andReturn();
        assertEquals(200, login.getResponse().getStatus(), body(login));
        Matcher token = Pattern.compile("\"token\":\"([^\"]+)\"").matcher(body(login));
        assertTrue(token.find());

        MvcResult profile = mockMvc.perform(get("/api/profile").header("Authorization", "Bearer " + token.group(1))).andReturn();

        assertEquals(200, profile.getResponse().getStatus(), body(profile));
        assertTrue(body(profile).contains(prefsJson("DARK", "YMD", "H24")), body(profile));
    }

    @Test
    @DisplayName("Saving preferences touches nothing else, and the response exposes no secrets")
    void touchesNothingElse() throws Exception {
        User me = account("only", Role.ADMIN);
        me.setProfileImageUrl("https://res.cloudinary.com/demo/image/upload/v1/me");
        me.setProfileImagePublicId("taskotel/profile-pictures/me");
        me.setDisplayName("Keep Me");
        userRepository.saveAndFlush(me);
        String hashBefore = reload(me).getPassword();

        MvcResult result = save(me, "DARK", "MDY", "H24");

        User saved = reload(me);
        assertEquals("Keep Me", saved.getDisplayName());
        assertEquals(me.getProfileImageUrl(), saved.getProfileImageUrl());
        assertEquals(me.getProfileImagePublicId(), saved.getProfileImagePublicId());
        assertEquals(hashBefore, saved.getPassword());
        assertTrue(saved.isActive() && saved.isEmailVerified());
        assertFalse(body(result).contains("\"password\"") || body(result).toLowerCase().contains("hash") || body(result).toLowerCase().contains("token"), body(result));
    }

    @Test
    @DisplayName("Without a token the endpoint is refused and nothing changes")
    void requiresAuthentication() throws Exception {
        User me = account("anon", Role.STAFF);
        MvcResult result = mockMvc.perform(put("/api/profile/preferences").contentType(MediaType.APPLICATION_JSON)
                .content("{\"themePreference\":\"DARK\",\"dateFormat\":\"YMD\",\"timeFormat\":\"H24\"}")).andReturn();
        assertTrue(result.getResponse().getStatus() == 401 || result.getResponse().getStatus() == 403, "status " + result.getResponse().getStatus());
        assertNull(reload(me).getThemePreference());
    }
}
