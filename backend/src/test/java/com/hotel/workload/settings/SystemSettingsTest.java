package com.hotel.workload.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.workload.security.Role;
import com.hotel.workload.security.ScopeFixtures;
import com.hotel.workload.security.UserRepository;
import com.hotel.workload.staff.Department;
import com.hotel.workload.staff.DepartmentRepository;
import com.hotel.workload.staff.StaffRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** System settings through the real security chain, service and database (H2). The tests commit, so each one clears the settings row first. */
@SpringBootTest
public class SystemSettingsTest {

    private static final String ADMIN = "sset_admin";
    private static final String MANAGER = "sset_manager";
    private static final String STAFF = "sset_staff";
    private static final String URL = "/api/system-settings";

    @Autowired private WebApplicationContext context;
    @Autowired private SystemSettingsRepository repository;
    @Autowired private SystemSettingsService service;
    @Autowired private UserRepository userRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private final ObjectMapper mapper = new ObjectMapper();
    private MockMvc mockMvc;
    private ScopeFixtures fixtures;

    @BeforeEach
    public void setUp() {
        SecurityContextHolder.clearContext();
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        fixtures = new ScopeFixtures(userRepository, staffRepository, departmentRepository, passwordEncoder);
        Department department = fixtures.department("SSET_D");
        fixtures.account(ADMIN, Role.ADMIN, true);
        fixtures.manager(MANAGER, department);
        fixtures.staff(STAFF, department);
        repository.deleteAll();
    }

    @AfterEach
    public void tearDown() {
        SecurityContextHolder.clearContext();
        repository.deleteAll();
        fixtures.remove(ADMIN, MANAGER, STAFF);
        // The department the fixtures created is committed too: remove it so other tests that count departments are not affected
        departmentRepository.findByCode("SSET_D").ifPresent(departmentRepository::delete);
        departmentRepository.flush();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private MvcResult call(String username, String role, MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.with(user(username).roles(role)).contentType(MediaType.APPLICATION_JSON)).andReturn();
    }

    private static String body(boolean auto, boolean alert, boolean cross, String threshold) {
        return "{\"autoBalancingEnabled\":" + auto + ",\"capacityAlertEnabled\":" + alert + ",\"crossDepartmentAssistanceEnabled\":" + cross
                + ",\"capacityThresholdPercent\":" + threshold + "}";
    }

    private MvcResult putAsAdmin(String json) throws Exception {
        return call(ADMIN, "ADMIN", put(URL).content(json));
    }

    private JsonNode json(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    private void signInAs(String username, String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private static UpdateSystemSettingsRequest request(boolean auto, boolean alert, boolean cross, Integer threshold) {
        UpdateSystemSettingsRequest r = new UpdateSystemSettingsRequest();
        r.setAutoBalancingEnabled(auto);
        r.setCapacityAlertEnabled(alert);
        r.setCrossDepartmentAssistanceEnabled(cross);
        r.setCapacityThresholdPercent(threshold == null ? null : BigDecimal.valueOf(threshold));
        return r;
    }

    // ── defaults and persistence ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("With nothing saved, GET returns the agreed defaults (all on, threshold 85, no saved-by) and creates no row")
    void defaultsWhenNoRecord() throws Exception {
        MvcResult result = call(ADMIN, "ADMIN", get(URL));
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        JsonNode body = json(result);
        assertTrue(body.get("autoBalancingEnabled").asBoolean());
        assertTrue(body.get("capacityAlertEnabled").asBoolean());
        assertTrue(body.get("crossDepartmentAssistanceEnabled").asBoolean());
        assertEquals(85, body.get("capacityThresholdPercent").asInt());
        assertTrue(body.get("updatedAt").isNull());
        assertTrue(body.get("updatedBy").isNull());
        assertEquals(0, repository.count(), "reading never writes");
    }

    @Test
    @DisplayName("The first update creates the single record, returns what was saved and records who saved it; GET then returns it")
    void firstUpdateCreatesTheRecord() throws Exception {
        MvcResult saved = putAsAdmin(body(false, true, false, "70"));
        assertEquals(200, saved.getResponse().getStatus(), saved.getResponse().getContentAsString());
        JsonNode body = json(saved);
        assertFalse(body.get("autoBalancingEnabled").asBoolean());
        assertTrue(body.get("capacityAlertEnabled").asBoolean());
        assertFalse(body.get("crossDepartmentAssistanceEnabled").asBoolean());
        assertEquals(70, body.get("capacityThresholdPercent").asInt());
        assertEquals(ADMIN, body.get("updatedBy").asText());
        assertFalse(body.get("updatedAt").isNull());
        assertEquals(1, repository.count());

        JsonNode read = json(call(ADMIN, "ADMIN", get(URL)));
        assertEquals(body.get("capacityThresholdPercent").asInt(), read.get("capacityThresholdPercent").asInt());
        assertFalse(read.get("autoBalancingEnabled").asBoolean());
    }

    @Test
    @DisplayName("The values are stored in the database, with the Admin's id, not just echoed")
    void valuesPersist() throws Exception {
        putAsAdmin(body(false, false, true, "42"));
        SystemSettings row = repository.findBySettingsKey("GLOBAL").orElseThrow();
        assertFalse(row.isAutoBalancingEnabled());
        assertFalse(row.isCapacityAlertEnabled());
        assertTrue(row.isCrossDepartmentAssistanceEnabled());
        assertEquals(42, row.getCapacityThresholdPercent());
        assertEquals(ADMIN, row.getUpdatedByUsername());
        assertEquals(userRepository.findByUsername(ADMIN).orElseThrow().getId(), row.getUpdatedByUserId());
        assertNotNull(row.getUpdatedAt());
    }

    @Test
    @DisplayName("A later update changes the existing record (same id, still one row) and the new values are returned")
    void laterUpdateModifiesTheSameRecord() throws Exception {
        putAsAdmin(body(true, true, true, "85"));
        SystemSettings first = repository.findBySettingsKey("GLOBAL").orElseThrow();

        MvcResult second = putAsAdmin(body(false, false, false, "10"));
        assertEquals(200, second.getResponse().getStatus());
        assertEquals(1, repository.count(), "no duplicate record");
        SystemSettings after = repository.findBySettingsKey("GLOBAL").orElseThrow();
        assertEquals(first.getId(), after.getId(), "the same record was updated");
        assertFalse(after.isAutoBalancingEnabled());
        assertEquals(10, after.getCapacityThresholdPercent());
        assertEquals(10, json(call(ADMIN, "ADMIN", get(URL))).get("capacityThresholdPercent").asInt());
    }

    @Test
    @DisplayName("Who saved comes from the signed-in account: a user id, name or role in the body is ignored")
    void saverIsNeverTakenFromTheBody() throws Exception {
        String sneaky = "{\"autoBalancingEnabled\":true,\"capacityAlertEnabled\":true,\"crossDepartmentAssistanceEnabled\":true,"
                + "\"capacityThresholdPercent\":85,\"updatedBy\":\"someone_else\",\"updatedByUsername\":\"someone_else\","
                + "\"updatedByUserId\":\"00000000-0000-0000-0000-000000000000\",\"role\":\"ADMIN\"}";
        assertEquals(200, putAsAdmin(sneaky).getResponse().getStatus());
        assertEquals(ADMIN, repository.findBySettingsKey("GLOBAL").orElseThrow().getUpdatedByUsername());
    }

    // ── validation ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("The threshold must be a whole number from 1 to 100: 0, negatives, 101 and more, decimals and text are 400 and store nothing")
    void thresholdIsValidated() throws Exception {
        for (String bad : List.of("0", "-1", "-50", "101", "1000", "85.5", "0.5", "100.1", "\"abc\"", "\"\"", "null", "[]", "{}", "1e3")) {
            MvcResult result = putAsAdmin(body(true, true, true, bad));
            assertEquals(400, result.getResponse().getStatus(), bad + ": " + result.getResponse().getContentAsString());
        }
        assertEquals(0, repository.count(), "nothing was stored by a rejected request");

        putAsAdmin(body(true, true, true, "60"));
        assertEquals(400, putAsAdmin(body(false, false, false, "150")).getResponse().getStatus());
        SystemSettings kept = repository.findBySettingsKey("GLOBAL").orElseThrow();
        assertEquals(60, kept.getCapacityThresholdPercent(), "a rejected update leaves the saved values as they were");
        assertTrue(kept.isAutoBalancingEnabled());
    }

    @Test
    @DisplayName("The boundaries 1 and 100 are accepted, and so is a whole number written with a zero decimal (85.0)")
    void thresholdBoundaries() throws Exception {
        for (String ok : List.of("1", "100", "85.0")) {
            MvcResult result = putAsAdmin(body(true, true, true, ok));
            assertEquals(200, result.getResponse().getStatus(), ok + ": " + result.getResponse().getContentAsString());
        }
        assertEquals(85, repository.findBySettingsKey("GLOBAL").orElseThrow().getCapacityThresholdPercent());
    }

    @Test
    @DisplayName("Every one of the four settings is required: a missing or null value is 400")
    void everythingIsRequired() throws Exception {
        for (String incomplete : List.of(
                "{}",
                "{\"capacityAlertEnabled\":true,\"crossDepartmentAssistanceEnabled\":true,\"capacityThresholdPercent\":85}",
                "{\"autoBalancingEnabled\":true,\"crossDepartmentAssistanceEnabled\":true,\"capacityThresholdPercent\":85}",
                "{\"autoBalancingEnabled\":true,\"capacityAlertEnabled\":true,\"capacityThresholdPercent\":85}",
                "{\"autoBalancingEnabled\":true,\"capacityAlertEnabled\":true,\"crossDepartmentAssistanceEnabled\":true}",
                "{\"autoBalancingEnabled\":null,\"capacityAlertEnabled\":true,\"crossDepartmentAssistanceEnabled\":true,\"capacityThresholdPercent\":85}")) {
            assertEquals(400, putAsAdmin(incomplete).getResponse().getStatus(), incomplete);
        }
        assertEquals(400, call(ADMIN, "ADMIN", put(URL).content("not json")).getResponse().getStatus());
        assertEquals(0, repository.count());
    }

    // ── who may use it ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Manager and Staff can neither read nor change the settings (403), and a refused update stores nothing")
    void managerAndStaffAreRefused() throws Exception {
        putAsAdmin(body(true, true, true, "85"));
        for (String[] who : new String[][] {{MANAGER, "MANAGER"}, {STAFF, "STAFF"}}) {
            assertEquals(403, call(who[0], who[1], get(URL)).getResponse().getStatus(), who[1] + " GET");
            assertEquals(403, call(who[0], who[1], put(URL).content(body(false, false, false, "5"))).getResponse().getStatus(), who[1] + " PUT");
        }
        SystemSettings row = repository.findBySettingsKey("GLOBAL").orElseThrow();
        assertTrue(row.isAutoBalancingEnabled());
        assertEquals(85, row.getCapacityThresholdPercent());
    }

    @Test
    @DisplayName("Without a sign-in both routes are 401")
    void anonymousIsRefused() throws Exception {
        assertEquals(401, mockMvc.perform(get(URL)).andReturn().getResponse().getStatus());
        assertEquals(401, mockMvc.perform(put(URL).contentType(MediaType.APPLICATION_JSON).content(body(true, true, true, "85"))).andReturn().getResponse().getStatus());
        assertEquals(0, repository.count());
    }

    @Test
    @DisplayName("Other methods on the route are not open (POST, DELETE, PATCH)")
    void otherMethodsAreNotOpen() throws Exception {
        for (var request : List.of(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(URL),
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(URL),
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(URL))) {
            int status = call(ADMIN, "ADMIN", request.content(body(true, true, true, "85"))).getResponse().getStatus();
            assertTrue(status == 403 || status == 404 || status == 405, "status " + status);
        }
        assertEquals(0, repository.count());
    }

    // ── the service enforces it by itself (no URL rule in front) ──────────────────────────────

    @Test
    @DisplayName("Called directly, the service refuses Manager and Staff (403) and no sign-in (401), and an Admin can use it")
    void serviceEnforcesAdminOnly() {
        signInAs(MANAGER, "MANAGER");
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> service.get()).getStatusCode().value());
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> service.update(request(true, true, true, 85))).getStatusCode().value());
        signInAs(STAFF, "STAFF");
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> service.get()).getStatusCode().value());
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> service.update(request(true, true, true, 85))).getStatusCode().value());
        SecurityContextHolder.clearContext();
        assertEquals(401, assertThrows(ResponseStatusException.class, () -> service.get()).getStatusCode().value());
        assertEquals(401, assertThrows(ResponseStatusException.class, () -> service.update(request(true, true, true, 85))).getStatusCode().value());
        assertEquals(0, repository.count());

        signInAs(ADMIN, "ADMIN");
        assertEquals(85, service.get().capacityThresholdPercent());
        assertEquals(33, service.update(request(true, false, true, 33)).capacityThresholdPercent());
        assertEquals(33, service.get().capacityThresholdPercent());
    }

    @Test
    @DisplayName("Called directly, the service itself rejects a bad threshold or a missing value (no controller validation in front)")
    void serviceValidatesByItself() {
        signInAs(ADMIN, "ADMIN");
        for (Integer bad : new Integer[] {0, -1, 101, 500, null}) {
            assertEquals(400, assertThrows(ResponseStatusException.class, () -> service.update(request(true, true, true, bad))).getStatusCode().value(), String.valueOf(bad));
        }
        UpdateSystemSettingsRequest decimal = request(true, true, true, 85);
        decimal.setCapacityThresholdPercent(new BigDecimal("85.5"));
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> service.update(decimal)).getStatusCode().value());
        UpdateSystemSettingsRequest noFlag = request(true, true, true, 85);
        noFlag.setCapacityAlertEnabled(null);
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> service.update(noFlag)).getStatusCode().value());
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> service.update(null)).getStatusCode().value());
        assertEquals(0, repository.count());
    }

    // ── only one record can exist ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("A second global record cannot be added: the unique key refuses it")
    void databaseRefusesASecondRecord() {
        putAsAdminDirect(request(true, true, true, 85));
        SystemSettings duplicate = new SystemSettings();
        duplicate.setUpdatedAt(java.time.LocalDateTime.now());
        duplicate.setUpdatedByUserId(userRepository.findByUsername(ADMIN).orElseThrow().getId());
        duplicate.setUpdatedByUsername(ADMIN);
        assertThrows(DataIntegrityViolationException.class, () -> repository.saveAndFlush(duplicate));
        assertEquals(1, repository.count());
    }

    private void putAsAdminDirect(UpdateSystemSettingsRequest request) {
        signInAs(ADMIN, "ADMIN");
        service.update(request);
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Admins saving for the first time at the same moment still leave exactly one record, and every save succeeds")
    void concurrentFirstSavesMakeOneRecord() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int threshold = 10 + i;
            results.add(pool.submit(() -> {
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(ADMIN, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
                try {
                    start.await();
                    return service.update(request(true, true, true, threshold)).capacityThresholdPercent();
                } finally {
                    SecurityContextHolder.clearContext();
                }
            }));
        }
        start.countDown();
        List<Integer> saved = new ArrayList<>();
        for (Future<Integer> result : results) saved.add(result.get());
        pool.shutdown();
        assertEquals(threads, saved.size(), "every save succeeded");
        assertEquals(1, repository.count(), "exactly one global record");
        int stored = repository.findBySettingsKey("GLOBAL").orElseThrow().getCapacityThresholdPercent();
        assertTrue(saved.contains(stored), "the stored value is one of the saved values");
    }
}
