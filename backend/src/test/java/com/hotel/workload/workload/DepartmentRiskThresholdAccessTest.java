package com.hotel.workload.workload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.workload.security.JwtUtil;
import com.hotel.workload.security.Role;
import com.hotel.workload.security.ScopeFixtures;
import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
import com.hotel.workload.settings.SystemSettingsRepository;
import com.hotel.workload.staff.DepartmentRepository;
import com.hotel.workload.staff.StaffRepository;
import com.hotel.workload.staff.TestDepartments;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * Who may read GET /api/workload/department-risk-threshold, with real JWTs for real accounts (an Admin, a valid Manager linked to an active
 * department, Staff, an invalid Manager) and no sign-in. All rows are created inside a transaction that is rolled back, in the in-memory test
 * database: nothing is written to a development or production database.
 */
@SpringBootTest
@Transactional
public class DepartmentRiskThresholdAccessTest {

    private static final String URL = "/api/workload/department-risk-threshold";

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository users;
    @Autowired private StaffRepository staffRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private SystemSettingsRepository settingsRepository;
    @Autowired private JwtUtil jwt;
    @Autowired private PasswordEncoder passwordEncoder;

    private final ObjectMapper json = new ObjectMapper();
    private final String tag = UUID.randomUUID().toString().substring(0, 6).toLowerCase();
    private MockMvc mvc;
    private ScopeFixtures scope;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        scope = new ScopeFixtures(users, staffRepository, departmentRepository, passwordEncoder);
    }

    /** A real account and a real JWT for it. The token's role claim is {@code claimedRole}, which may differ from the account's role. */
    private String account(String name, Role role, Role claimedRole, boolean active) {
        User user = new User();
        user.setUsername(name + tag);
        user.setEmail(name + tag + "@t.test");
        user.setPassword("x");
        user.setRole(role);
        user.setActive(active);
        user.setEmailVerified(true);
        user.setCredentialsSetupRequired(false);
        users.saveAndFlush(user);
        return "Bearer " + jwt.generateToken(user.getUsername(), claimedRole);
    }

    private String account(String name, Role role) {
        return account(name, role, role, true);
    }

    /** An active account with an active Staff record in a real, active department. */
    private String withDepartment(String name, Role role) {
        String bearer = account(name, role);
        scope.staffRecord(name + tag, role, TestDepartments.HOUSEKEEPING);
        return bearer;
    }

    private MockHttpServletRequestBuilder req(HttpMethod method, String url, String bearer, Object body) throws Exception {
        MockHttpServletRequestBuilder builder = request(method, url);
        if (bearer != null) builder.header("Authorization", bearer);
        if (body != null) builder.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        return builder;
    }

    private MvcResult call(String url, String bearer, int expected) throws Exception {
        MvcResult result = mvc.perform(req(HttpMethod.GET, url, bearer, null)).andReturn();
        assertEquals(expected, result.getResponse().getStatus(), "GET " + url + " -> " + result.getResponse().getContentAsString());
        return result;
    }

    @Test
    @DisplayName("Admin 200, a valid Manager with an active department 200, Staff 403, anonymous 401")
    void adminAndValidManagerAreServedStaffAndAnonymousAreNot() throws Exception {
        String admin = account("rt_admin", Role.ADMIN);
        String manager = withDepartment("rt_manager", Role.MANAGER);
        String staff = withDepartment("rt_staff", Role.STAFF);

        for (String bearer : new String[]{admin, manager}) {
            JsonNode answer = json.readTree(call(URL, bearer, 200).getResponse().getContentAsString());
            assertEquals(2, answer.size(), "only the alert switch and the percentage, nothing else from System Settings");
            assertTrue(answer.get("enabled").isBoolean() && answer.get("capacityThresholdPercent").isInt());
            assertEquals(85, answer.get("capacityThresholdPercent").asInt(), "nothing saved yet: the default");
        }
        call(URL, staff, 403);
        call(URL, null, 401);
    }

    @Test
    @DisplayName("The same value reaches the valid Manager after the Admin saves a change, over the real endpoints")
    void managerSeesWhatTheAdminSaves() throws Exception {
        String admin = account("rt_admin", Role.ADMIN);
        String manager = withDepartment("rt_manager", Role.MANAGER);
        for (int threshold : new int[]{10, 40, 100, 1}) {
            MvcResult saved = mvc.perform(req(HttpMethod.PUT, "/api/system-settings", admin, java.util.Map.of(
                    "autoBalancingEnabled", true, "capacityAlertEnabled", true, "crossDepartmentAssistanceEnabled", true,
                    "capacityThresholdPercent", threshold))).andReturn();
            assertEquals(200, saved.getResponse().getStatus(), saved.getResponse().getContentAsString());
            for (String bearer : new String[]{admin, manager}) {
                assertEquals(threshold, json.readTree(call(URL, bearer, 200).getResponse().getContentAsString()).get("capacityThresholdPercent").asInt());
            }
        }
        // The Manager can read the number but never change it or read the settings themselves
        assertEquals(403, mvc.perform(req(HttpMethod.GET, "/api/system-settings", manager, null)).andReturn().getResponse().getStatus());
        assertEquals(403, mvc.perform(req(HttpMethod.PUT, "/api/system-settings", manager, java.util.Map.of(
                "autoBalancingEnabled", true, "capacityAlertEnabled", true, "crossDepartmentAssistanceEnabled", true, "capacityThresholdPercent", 5))).andReturn().getResponse().getStatus());
        assertEquals(1, json.readTree(call(URL, admin, 200).getResponse().getContentAsString()).get("capacityThresholdPercent").asInt(), "the refused change did not happen");
    }

    @Test
    @DisplayName("Alert switched off is reported as such, so the cards fall back to the default ranges")
    void switchedOffIsReported() throws Exception {
        String admin = account("rt_admin", Role.ADMIN);
        String manager = withDepartment("rt_manager", Role.MANAGER);
        MvcResult saved = mvc.perform(req(HttpMethod.PUT, "/api/system-settings", admin, java.util.Map.of(
                "autoBalancingEnabled", true, "capacityAlertEnabled", false, "crossDepartmentAssistanceEnabled", true, "capacityThresholdPercent", 40))).andReturn();
        assertEquals(200, saved.getResponse().getStatus());
        assertEquals(false, json.readTree(call(URL, manager, 200).getResponse().getContentAsString()).get("enabled").asBoolean());
    }

    @Test
    @DisplayName("Refused even with a valid-looking token: a Manager with no department, a deactivated Manager, and a Staff account holding a token that claims ADMIN")
    void invalidCallersAreRefusedByTheDatabaseCheck() throws Exception {
        String noDepartment = account("rt_nodept", Role.MANAGER);                                  // a Manager account with no Staff record in any department
        call(URL, noDepartment, 403);

        String deactivated = account("rt_off", Role.MANAGER, Role.MANAGER, false);
        scope.staffRecord("rt_off" + tag, Role.MANAGER, TestDepartments.HOUSEKEEPING);
        int deactivatedStatus = mvc.perform(req(HttpMethod.GET, URL, deactivated, null)).andReturn().getResponse().getStatus();
        assertTrue(deactivatedStatus == 401 || deactivatedStatus == 403, "a deactivated account is refused (" + deactivatedStatus + ")");

        // The URL rule alone would let this through (the token says ADMIN); the service checks the account in the database and refuses a Staff account
        String forged = account("rt_forged", Role.STAFF, Role.ADMIN, true);
        scope.staffRecord("rt_forged" + tag, Role.STAFF, TestDepartments.HOUSEKEEPING);
        call(URL, forged, 403);
    }

    @Test
    @DisplayName("Other methods on the path are not open")
    void otherMethodsAreNotOpen() throws Exception {
        String admin = account("rt_admin", Role.ADMIN);
        for (HttpMethod method : new HttpMethod[]{HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE, HttpMethod.PATCH}) {
            int status = mvc.perform(req(method, URL, admin, java.util.Map.of())).andReturn().getResponse().getStatus();
            assertTrue(status >= 400 && status < 500, method + " is refused (" + status + ")");
        }
    }
}
