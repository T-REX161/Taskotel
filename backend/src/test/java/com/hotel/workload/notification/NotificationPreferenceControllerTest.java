package com.hotel.workload.notification;

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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** GET/PUT /api/notification-preferences over HTTP, through the real security chain, controller, service and (in-memory) database. */
@SpringBootTest
public class NotificationPreferenceControllerTest {

    private static final String PREFIX = "np_";
    private static final String PATH = "/api/notification-preferences";

    /** The email defaults every user starts with. */
    private static final String DEFAULTS =
            "{\"category\":\"TASK_ASSIGNED\",\"email\":true,\"locked\":false},"
            + "{\"category\":\"TASK_STATUS\",\"email\":false,\"locked\":false},"
            + "{\"category\":\"WORKLOAD_ALERT\",\"email\":true,\"locked\":false},"
            + "{\"category\":\"TASK_REMINDER\",\"email\":false,\"locked\":false},"
            + "{\"category\":\"SECURITY\",\"email\":true,\"locked\":true}";

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private NotificationPreferenceRepository preferences;
    @Autowired private PasswordEncoder passwordEncoder;

    private MockMvc mockMvc;
    private int clients = 0;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        cleanUp();
    }

    @AfterEach
    void cleanUp() {
        Set<java.util.UUID> mine = new java.util.HashSet<>();
        userRepository.findAll().stream().filter(u -> u.getUsername().startsWith(PREFIX)).forEach(u -> mine.add(u.getId()));
        preferences.findAll().stream().filter(p -> mine.contains(p.getUserId())).forEach(preferences::delete);
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

    private MvcResult perform(AbstractMockHttpServletRequestBuilder<?> request, User as) throws Exception {
        return mockMvc.perform(request.with(user(as.getUsername()).roles(as.getRole().name()))).andReturn();
    }

    private MvcResult read(User as) throws Exception {
        return perform(get(PATH), as);
    }

    private MvcResult save(User as, String json) throws Exception {
        return perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content(json), as);
    }

    private static String change(String category, boolean email) {
        return "{\"category\":\"" + category + "\",\"email\":" + email + "}";
    }

    private static String request(String... changes) {
        return "{\"preferences\":[" + String.join(",", changes) + "]}";
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    private static String rows(String category, boolean email, boolean locked) {
        return "{\"category\":\"" + category + "\",\"email\":" + email + ",\"locked\":" + locked + "}";
    }

    private int savedRows(User user) {
        return preferences.findByUserId(user.getId()).size();
    }

    // ── defaults and loading ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("A user who never saved anything gets the five categories with the required defaults, and no row is created for them")
    void defaults() throws Exception {
        User me = account("fresh", Role.STAFF);

        MvcResult result = read(me);

        assertEquals(200, result.getResponse().getStatus());
        assertEquals("{\"preferences\":[" + DEFAULTS + "]}", body(result));
        assertEquals(0, savedRows(me), "reading never writes");
    }

    @Test
    @DisplayName("Existing accounts (created before this feature, with their data intact) get the same defaults")
    void existingAccountsGetDefaults() throws Exception {
        User old = account("old", Role.MANAGER);
        old.setDisplayName("Keep Me");
        userRepository.saveAndFlush(old);

        assertTrue(body(read(old)).contains(DEFAULTS));
        User after = userRepository.findById(old.getId()).orElseThrow();
        assertEquals("Keep Me", after.getDisplayName());
        assertEquals(old.getEmail(), after.getEmail());
        assertEquals(old.getPassword(), after.getPassword());
    }

    // ── updating ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Every role can change its own preferences; the answer is the complete current set and a later read agrees")
    void updateForEveryRole() throws Exception {
        for (Role role : Role.values()) {
            User me = account(role.name().toLowerCase(), role);

            MvcResult result = save(me, request(change("TASK_STATUS", true), change("WORKLOAD_ALERT", false)));

            assertEquals(200, result.getResponse().getStatus(), role + ": " + body(result));
            String expected = "{\"preferences\":["
                    + rows("TASK_ASSIGNED", true, false) + ","
                    + rows("TASK_STATUS", true, false) + ","
                    + rows("WORKLOAD_ALERT", false, false) + ","
                    + rows("TASK_REMINDER", false, false) + ","
                    + rows("SECURITY", true, true) + "]}";
            assertEquals(expected, body(result));
            assertEquals(expected, body(read(me)), "persisted, not just echoed");
            assertEquals(2, savedRows(me), "only the changed categories are stored");
        }
    }

    @Test
    @DisplayName("Saving again updates the same row (no duplicates) and a change can be reverted")
    void savingAgainUpdates() throws Exception {
        User me = account("again", Role.ADMIN);
        save(me, request(change("TASK_REMINDER", false)));
        save(me, request(change("TASK_REMINDER", true)));
        save(me, request(change("TASK_REMINDER", true)));

        assertEquals(1, savedRows(me));
        assertTrue(body(read(me)).contains(rows("TASK_REMINDER", true, false)));
    }

    @Test
    @DisplayName("All four switchable categories can have email switched off and on")
    void everyCategoryBothWays() throws Exception {
        User me = account("combos", Role.STAFF);
        for (String category : List.of("TASK_ASSIGNED", "TASK_STATUS", "WORKLOAD_ALERT", "TASK_REMINDER")) {
            for (boolean email : new boolean[] {false, true}) {
                assertEquals(200, save(me, request(change(category, email))).getResponse().getStatus());
                assertTrue(body(read(me)).contains(rows(category, email, false)), category + " " + email);
            }
        }
    }

    @Test
    @DisplayName("There is no in-app channel: responses never mention it, a leftover inApp in a request is ignored, and stored in-app values are kept but unused")
    void inAppIsGone() throws Exception {
        User me = account("legacy", Role.MANAGER);
        assertFalse(body(read(me)).toLowerCase().contains("inapp"), "a read has no in-app field");

        // an older client still sending inApp: accepted, only email is applied
        MvcResult result = save(me, request("{\"category\":\"TASK_STATUS\",\"inApp\":false,\"email\":true}"));
        assertEquals(200, result.getResponse().getStatus(), body(result));
        assertFalse(body(result).toLowerCase().contains("inapp"));
        assertTrue(body(result).contains(rows("TASK_STATUS", true, false)));

        // a value stored earlier for the old column is left exactly as it was when the email choice changes
        preferences.saveAndFlush(new NotificationPreference(me.getId(), NotificationCategory.TASK_REMINDER, false, true));
        assertTrue(body(read(me)).contains(rows("TASK_REMINDER", true, false)), "email is what is read; the old in-app false changes nothing");
        assertEquals(200, save(me, request(change("TASK_REMINDER", false))).getResponse().getStatus());
        NotificationPreference row = preferences.findByUserId(me.getId()).stream().filter(p -> p.getCategory().equals("TASK_REMINDER")).findFirst().orElseThrow();
        assertFalse(row.isInApp(), "the stored in-app value is not touched");
        assertFalse(row.isEmail());
    }

    // ── security alerts ────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Security alerts cannot be turned off, alone or with other changes (even with an old inApp field); nothing in the request is applied")
    void securityAlertsAreMandatory() throws Exception {
        User me = account("secure", Role.MANAGER);
        for (String bad : new String[] {
                request(change("SECURITY", false)),
                request("{\"category\":\"SECURITY\",\"inApp\":true,\"email\":false}"),
                request(change("TASK_STATUS", false), change("SECURITY", false))}) {
            MvcResult result = save(me, bad);
            assertEquals(400, result.getResponse().getStatus(), bad + " -> " + body(result));
            assertTrue(body(result).contains(NotificationPreferenceService.SECURITY_LOCKED_MESSAGE), body(result));
        }
        assertEquals(0, savedRows(me), "the refused requests changed nothing, not even their other categories");
        assertTrue(body(read(me)).contains(rows("SECURITY", true, true)));
        assertEquals(200, save(me, request(change("SECURITY", true))).getResponse().getStatus(), "confirming it is on is fine");
    }

    @Test
    @DisplayName("Even if a security row were turned off in the database, it is still reported on")
    void storedOffSecurityRowIsStillOn() throws Exception {
        User me = account("tampered", Role.STAFF);
        preferences.saveAndFlush(new NotificationPreference(me.getId(), NotificationCategory.SECURITY, false, false));
        assertTrue(body(read(me)).contains(rows("SECURITY", true, true)));
    }

    // ── validation ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Malformed requests get 400 and change nothing: empty, unknown or repeated category, missing switch, not JSON")
    void invalidRequests() throws Exception {
        User me = account("invalid", Role.STAFF);
        for (String bad : new String[] {
                "{}", "{\"preferences\":[]}", "{\"preferences\":null}", "{\"preferences\":[null]}",
                request(change("NOPE", true)), request("{\"email\":true}"),
                request("{\"category\":\"TASK_STATUS\",\"inApp\":true}"), request("{\"category\":\"TASK_STATUS\"}"),
                request(change("TASK_STATUS", true), change("TASK_STATUS", false)),
                request(change("task_status", true)), request(change("TASK_STATUS; DROP TABLE users", true))}) {
            MvcResult result = save(me, bad);
            assertEquals(400, result.getResponse().getStatus(), bad + " -> " + body(result));
        }
        assertEquals(400, perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content("not json"), me).getResponse().getStatus());
        assertEquals(0, savedRows(me));
    }

    // ── isolation, identity and authorization ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("Preferences are per user: Admin, Manager and Staff keep their own, and one user's save never reaches another")
    void independentPerUser() throws Exception {
        User admin = account("admin", Role.ADMIN);
        User manager = account("manager", Role.MANAGER);
        User staff = account("staff", Role.STAFF);

        save(admin, request(change("TASK_ASSIGNED", false)));
        save(manager, request(change("TASK_ASSIGNED", false), change("TASK_REMINDER", true)));

        assertTrue(body(read(admin)).contains(rows("TASK_ASSIGNED", false, false)));
        assertTrue(body(read(manager)).contains(rows("TASK_ASSIGNED", false, false)));
        assertTrue(body(read(manager)).contains(rows("TASK_REMINDER", true, false)));
        assertEquals("{\"preferences\":[" + DEFAULTS + "]}", body(read(staff)), "staff never changed anything");

        save(staff, request(change("TASK_ASSIGNED", true), change("TASK_STATUS", false)));
        assertTrue(body(read(admin)).contains(rows("TASK_ASSIGNED", false, false)), "admin unchanged by staff's save");
        assertTrue(body(read(admin)).contains(rows("TASK_STATUS", false, false)));
        assertTrue(body(read(manager)).contains(rows("TASK_ASSIGNED", false, false)));
    }

    @Test
    @DisplayName("Who is changed comes only from the token: a user id, username or userId in the body or query is ignored")
    void cannotTouchAnotherUser() throws Exception {
        User alice = account("alice", Role.STAFF);
        User bob = account("bob", Role.STAFF);
        save(bob, request(change("TASK_STATUS", false)));

        MvcResult result = perform(put(PATH).param("userId", bob.getId().toString()).param("username", bob.getUsername())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + bob.getId() + "\",\"username\":\"" + bob.getUsername() + "\",\"preferences\":["
                        + change("TASK_STATUS", true) + "]}"), alice);

        assertEquals(200, result.getResponse().getStatus(), body(result));
        assertTrue(body(read(alice)).contains(rows("TASK_STATUS", true, false)));
        assertTrue(body(read(bob)).contains(rows("TASK_STATUS", false, false)), "bob is untouched");
        assertEquals(1, savedRows(alice));
        assertEquals(1, savedRows(bob));

        MvcResult readOther = perform(get(PATH).param("userId", bob.getId().toString()).param("username", bob.getUsername()), alice);
        assertTrue(body(readOther).contains(rows("TASK_STATUS", true, false)), "a query naming bob still returns alice's own");
    }

    @Test
    @DisplayName("Without a token both endpoints are refused and nothing is written")
    void requiresAuthentication() throws Exception {
        User me = account("anon", Role.STAFF);
        int get = mockMvc.perform(get(PATH)).andReturn().getResponse().getStatus();
        int put = mockMvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content(request(change("TASK_STATUS", false)))).andReturn().getResponse().getStatus();
        assertTrue(get == 401 || get == 403, "GET status " + get);
        assertTrue(put == 401 || put == 403, "PUT status " + put);
        assertEquals(0, savedRows(me));
        assertEquals(0, preferences.findAll().stream().filter(p -> p.getUserId().equals(me.getId())).count());
    }

    @Test
    @DisplayName("Saved preferences survive signing out and back in: a fresh login token reads them")
    void persistAcrossLogin() throws Exception {
        User me = account("persist", Role.MANAGER);
        assertEquals(200, save(me, request(change("WORKLOAD_ALERT", true))).getResponse().getStatus());

        MvcResult login = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .with(r -> { r.setRemoteAddr("10.5.4." + (++clients)); return r; })
                .content("{\"username\":\"" + me.getUsername() + "\",\"password\":\"Correct@123\"}")).andReturn();
        assertEquals(200, login.getResponse().getStatus(), body(login));
        Matcher token = Pattern.compile("\"token\":\"([^\"]+)\"").matcher(body(login));
        assertTrue(token.find());

        MvcResult prefs = mockMvc.perform(get(PATH).header("Authorization", "Bearer " + token.group(1))).andReturn();

        assertEquals(200, prefs.getResponse().getStatus(), body(prefs));
        assertTrue(body(prefs).contains(rows("WORKLOAD_ALERT", true, false)), body(prefs));
    }

    @Test
    @DisplayName("The response holds only categories and switches: no account data, hashes or tokens")
    void exposesNothingElse() throws Exception {
        User me = account("clean", Role.ADMIN);
        String json = body(save(me, request(change("TASK_STATUS", true))));
        for (String forbidden : new String[] {"password", "hash", "token", "email\":\"", "username", me.getId().toString(), me.getEmail()}) {
            assertFalse(json.toLowerCase().contains(forbidden.toLowerCase()), "must not contain " + forbidden + ": " + json);
        }
    }
}
