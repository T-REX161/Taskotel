package com.hotel.workload.workload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.workload.security.JwtUtil;
import com.hotel.workload.security.Role;
import com.hotel.workload.security.ScopeFixtures;
import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * Who may read GET /api/workload/department-capacity, with real JWTs for real accounts (an Admin, a valid Manager linked to an active
 * department, Staff, an invalid Manager) and no sign-in, and what each of them gets. All rows are created inside a transaction that is rolled
 * back, in the in-memory test database: nothing is written to a development or production database.
 */
@SpringBootTest
@Transactional
public class DepartmentCapacityAccessTest {

    private static final String URL = "/api/workload/department-capacity";

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository users;
    @Autowired private StaffRepository staffRepository;
    @Autowired private DepartmentRepository departmentRepository;
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

    private JsonNode get(String bearer) throws Exception {
        return json.readTree(call(URL, bearer, 200).getResponse().getContentAsString());
    }

    private static List<String> codes(JsonNode answer) {
        List<String> codes = new ArrayList<>();
        for (JsonNode entry : answer.get("departments")) codes.add(entry.get("code").asText());
        return codes;
    }

    private void save(String adminBearer, boolean alert, int threshold) throws Exception {
        MvcResult saved = mvc.perform(req(HttpMethod.PUT, "/api/system-settings", adminBearer, Map.of(
                "autoBalancingEnabled", true, "capacityAlertEnabled", alert, "crossDepartmentAssistanceEnabled", true, "capacityThresholdPercent", threshold))).andReturn();
        assertEquals(200, saved.getResponse().getStatus(), saved.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("Admin 200 with every department, a valid Manager 200 with only their own, Staff 403, anonymous 401")
    void adminAndValidManagerAreServedStaffAndAnonymousAreNot() throws Exception {
        String admin = account("cap_admin", Role.ADMIN);
        String manager = withDepartment("cap_manager", Role.MANAGER);
        String staff = withDepartment("cap_staff", Role.STAFF);

        JsonNode forAdmin = get(admin);
        JsonNode forManager = get(manager);
        assertTrue(codes(forAdmin).size() > 1 && codes(forAdmin).contains("HOUSEKEEPING"), "the Admin sees every department: " + codes(forAdmin));
        assertEquals(List.of("HOUSEKEEPING"), codes(forManager), "the Manager sees only the department they manage");
        for (JsonNode answer : new JsonNode[]{forAdmin, forManager}) {
            assertEquals(480, answer.get("shiftMinutes").asInt());
            assertEquals(85, answer.get("capacityThresholdPercent").asInt(), "nothing saved yet: the default");
            assertTrue(answer.get("enabled").asBoolean());
            JsonNode entry = answer.get("departments").get(0);
            for (String field : List.of("remainingMinutes", "assignedMinutes", "waitingMinutes", "activeTasks", "waitingTasks", "eligibleStaff", "helpersIn",
                    "lentOut", "capacityMinutes", "utilizationPercent", "level", "atRisk", "noCapacity")) {
                assertTrue(entry.has(field), "every entry carries " + field);
            }
        }
        call(URL, staff, 403);
        call(URL, null, 401);
    }

    @Test
    @DisplayName("The threshold in the answer follows what the Admin saves, over the real endpoints, for the Manager too")
    void managerSeesWhatTheAdminSaves() throws Exception {
        String admin = account("cap_admin", Role.ADMIN);
        String manager = withDepartment("cap_manager", Role.MANAGER);
        for (int threshold : new int[]{10, 40, 100, 1}) {
            save(admin, true, threshold);
            for (String bearer : new String[]{admin, manager}) {
                JsonNode answer = get(bearer);
                assertEquals(threshold, answer.get("capacityThresholdPercent").asInt());
                assertTrue(answer.get("enabled").asBoolean());
            }
        }
        // The Manager can read the figures but never change the setting or read the settings themselves
        assertEquals(403, mvc.perform(req(HttpMethod.GET, "/api/system-settings", manager, null)).andReturn().getResponse().getStatus());
        assertEquals(403, mvc.perform(req(HttpMethod.PUT, "/api/system-settings", manager, Map.of(
                "autoBalancingEnabled", true, "capacityAlertEnabled", true, "crossDepartmentAssistanceEnabled", true, "capacityThresholdPercent", 5))).andReturn().getResponse().getStatus());
        assertEquals(1, get(admin).get("capacityThresholdPercent").asInt(), "the refused change did not happen");
    }

    @Test
    @DisplayName("With the alert switched off nothing is AT RISK, whatever the load, and the answer says the alert is off")
    void switchedOffMeansNothingIsAtRisk() throws Exception {
        String admin = account("cap_admin", Role.ADMIN);
        save(admin, false, 1);
        JsonNode answer = get(admin);
        assertFalse(answer.get("enabled").asBoolean());
        for (JsonNode entry : answer.get("departments")) {
            assertFalse(entry.get("atRisk").asBoolean(), entry.get("code").asText());
            assertFalse("AT_RISK".equals(entry.get("level").asText()));
        }
    }

    @Test
    @DisplayName("Refused even with a valid-looking token: a Manager with no department, a deactivated Manager, and a Staff account holding a token that claims ADMIN")
    void invalidCallersAreRefusedByTheDatabaseCheck() throws Exception {
        call(URL, account("cap_nodept", Role.MANAGER), 403);                                        // a Manager account with no Staff record in any department

        String deactivated = account("cap_off", Role.MANAGER, Role.MANAGER, false);
        scope.staffRecord("cap_off" + tag, Role.MANAGER, TestDepartments.HOUSEKEEPING);
        int deactivatedStatus = mvc.perform(req(HttpMethod.GET, URL, deactivated, null)).andReturn().getResponse().getStatus();
        assertTrue(deactivatedStatus == 401 || deactivatedStatus == 403, "a deactivated account is refused (" + deactivatedStatus + ")");

        // The URL rule alone would let this through (the token says ADMIN); the service checks the account in the database and refuses a Staff account
        String forged = account("cap_forged", Role.STAFF, Role.ADMIN, true);
        scope.staffRecord("cap_forged" + tag, Role.STAFF, TestDepartments.HOUSEKEEPING);
        call(URL, forged, 403);
    }

    @Test
    @DisplayName("Other methods on the path are not open, and the old threshold-only path no longer exists")
    void otherMethodsAreNotOpen() throws Exception {
        String admin = account("cap_admin", Role.ADMIN);
        for (HttpMethod method : new HttpMethod[]{HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE, HttpMethod.PATCH}) {
            int status = mvc.perform(req(method, URL, admin, Map.of())).andReturn().getResponse().getStatus();
            assertTrue(status >= 400 && status < 500, method + " is refused (" + status + ")");
        }
        assertTrue(mvc.perform(req(HttpMethod.GET, "/api/workload/department-risk-threshold", admin, null)).andReturn().getResponse().getStatus() >= 400);
    }
}
